package com.mecon.audio.converter

import com.mecon.audio.model.*
import com.mecon.api.primitive.*
import com.mecon.api.runtime.RuntimeMeasure
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.resolvedTempoKeyframes
import com.mecon.api.runtime.performanceTimingFor
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.tracks.RuntimePitchTrack
import com.mecon.api.storage.events.GraceNoteInfo
import com.mecon.api.storage.events.GraceTimeSource
import com.mecon.api.storage.events.TempoTransition
import com.mecon.api.storage.NavigationMark
import com.mecon.api.storage.tracks.StorageFermata
import com.mecon.api.storage.tracks.StorageGlobalBreathMark
import com.mecon.api.storage.events.StorageOrnamentMark
import com.mecon.api.storage.events.OrnamentKind
import com.mecon.api.storage.events.OrnamentAnchor
import com.mecon.api.storage.ArpeggioType
import kotlinx.collections.immutable.toImmutableList
import com.mecon.api.collection.BPlusTree

/**
 * Converts RuntimeScore to MidiScore.
 *
 * Driven by PitchEvents — VoiceEvents only contribute grace-note metadata
 * (see [com.mecon.api.storage.events.GraceNoteInfo]) which is looked up by
 * `pitchEventId`. Each PitchEvent's sounding duration extends until the next
 * PitchEvent in the same track, except that grace groups carve out a window
 * of [GraceNoteInfo.totalDuration] taken from either the preceding note or
 * the principal note that follows them.
 */
object ScoreToMidiConverter {

    data class PlaybackMeasureOccurrence(
        val measureNumber: Int,
        val sourceStartTicks: Long,
        val playbackStartTicks: Long,
        val durationTicks: Long,
    )

    data class PlaybackHold(
        /** Position on the repeat-expanded timeline before performance holds are inserted. */
        val basePlaybackTicks: Long,
        val durationTicks: Long,
        /** Written-score position where the playhead remains during the hold. */
        val sourceTicks: Long,
    )

    /** Expanded measure order used by MIDI conversion and score-playhead mapping. */
    data class PlaybackTimeline(
        val occurrences: List<PlaybackMeasureOccurrence>,
        val holds: List<PlaybackHold> = emptyList(),
    ) {
        fun sourceTicksAt(playbackTicks: Long): Long {
            var completedHoldTicks = 0L
            for (hold in holds) {
                val holdStart = hold.basePlaybackTicks + completedHoldTicks
                if (playbackTicks < holdStart) break
                if (playbackTicks < holdStart + hold.durationTicks) return hold.sourceTicks
                completedHoldTicks += hold.durationTicks
            }
            return sourceTicksAtBase(playbackTicks - completedHoldTicks)
        }

        private fun sourceTicksAtBase(basePlaybackTicks: Long): Long {
            val occurrence = occurrences.lastOrNull { it.playbackStartTicks <= basePlaybackTicks }
                ?: return basePlaybackTicks
            val local = (basePlaybackTicks - occurrence.playbackStartTicks)
                .coerceIn(0L, occurrence.durationTicks)
            return occurrence.sourceStartTicks + local
        }

        fun firstPlaybackTick(measureNumber: Int, beatTicks: Long): Long? =
            occurrences.firstOrNull { it.measureNumber == measureNumber }
                ?.let { occurrence ->
                    val base = occurrence.playbackStartTicks + beatTicks
                    base + holds.filter { it.basePlaybackTicks <= base }.sumOf { it.durationTicks }
                }
    }

    /**
     * Configuration for MIDI conversion.
     */
    data class ConversionConfig(
        val defaultVelocity: Velocity = Velocity.DEFAULT,
        val ticksPerQuarter: Int = MidiScore.DEFAULT_TICKS_PER_QUARTER
    )

    /**
     * Convert a RuntimeScore to MidiScore.
     */
    fun convert(
        score: RuntimeScore,
        config: ConversionConfig = ConversionConfig()
    ): MidiScore {
        val measureOffsets = buildMeasureOffsets(score.measures, config.ticksPerQuarter)

        // GraceNoteInfo lives on voice events; pitch events know only their position.
        // Build a reverse lookup so the per-pitch-track conversion can find a group's
        // graceInfo by the first grace's pitchEventId.
        val graceInfoByPitchEventId: Map<EventId, GraceNoteInfo> =
            score.voiceTracks.values.flatMap { it.events.toList() }
                .mapNotNull { ve -> ve.graceInfo?.let { ve.pitchEvent.id to it } }
                .toMap()

        val instrumentIndexByPitchTrack = buildMap {
            score.instruments.forEachIndexed { index, instrument ->
                instrument.staves.flatMap { it.voiceTracks }.forEach { voice ->
                    put(voice.pitchTrackId, index)
                }
            }
        }
        val playbackByPitchTrack = buildMap {
            score.instruments.forEach { instrument ->
                instrument.staves.flatMap { it.voiceTracks }.forEach { voice ->
                    put(voice.pitchTrackId, instrument.playback)
                }
            }
        }
        // Ties live on voice events too. Without them every tied note is struck again, which turns
        // a suspension into an appoggiatura and a held chord into a repeated one.
        val tiedOutPitchesByPitchEventId: Map<EventId, Set<Int>> =
            score.voiceTracks.values.flatMap { it.events.toList() }
                .filter { it.ties.isNotEmpty() }
                .associate { event ->
                    event.pitchEvent.id to event.ties.mapTo(hashSetOf()) { tie ->
                        event.pitchEvent.pitches.getOrNull(tie.pitchIndex)?.midiNumber ?: -1
                    }
                }

        val voiceEventById = score.voiceTracks.values
            .flatMap { it.events.toList() }
            .associateBy { it.id }
        val ornamentByPitchEventId = buildMap {
            score.staffTracks.values.forEach { staff ->
                staff.attachments.filterIsInstance<StorageOrnamentMark>().forEach { mark ->
                    val event = voiceEventById[mark.sourceEventId] ?: return@forEach
                    put(event.pitchEvent.id, mark)
                }
            }
        }
        val arpeggioByPitchEventId = voiceEventById.values.mapNotNull { event ->
            event.rendering?.arpeggio?.let { event.pitchEvent.id to it }
        }.toMap()

        val midiTracks = score.pitchTracks.values.mapIndexed { fallbackIndex, pitchTrack ->
            val instrumentIndex = instrumentIndexByPitchTrack[pitchTrack.id] ?: fallbackIndex
            val playback = playbackByPitchTrack[pitchTrack.id]
            convertPitchTrack(
                track = pitchTrack,
                channel = melodicChannelForInstrument(instrumentIndex),
                midiProgram = playback?.midiProgram,
                measureOffsets = measureOffsets,
                measures = score.measures,
                defaultTimeSignature = score.defaultTimeSignature,
                graceInfoByPitchEventId = graceInfoByPitchEventId,
                ornamentByPitchEventId = ornamentByPitchEventId,
                arpeggioByPitchEventId = arpeggioByPitchEventId,
                tiedOutPitchesByPitchEventId = tiedOutPitchesByPitchEventId,
                score = score,
                config = config
            )
        }

        val tempoTrack = buildTempoTrack(score, measureOffsets, config.ticksPerQuarter)

        val linear = MidiScore.create(
            tracks = midiTracks,
            tempoTrack = tempoTrack,
            ticksPerQuarter = config.ticksPerQuarter
        )
        val expanded = expandRepeats(linear, score, measureOffsets, config.ticksPerQuarter)
        return applyPerformanceTiming(expanded, score, config.ticksPerQuarter)
    }

    /** Apply notated fermata holds and breath pauses after repeat expansion. */
    private fun applyPerformanceTiming(
        midi: MidiScore,
        score: RuntimeScore,
        ticksPerQuarter: Int,
    ): MidiScore {
        val voiceByPitchTrack = score.voiceTracks.values.associateBy { it.pitchTrackId }
        val tracks = midi.tracks.map { track ->
            val voice = voiceByPitchTrack[track.id] ?: return@map track
            val adjustments = voice.events.toList().associate { event ->
                event.pitchEvent.id to score.performanceTimingFor(voice.id, event.id)
            }
            if (adjustments.values.all { it.total.isZero }) return@map track

            // A chord has several note-off events at the same tick. Advance the performance clock
            // only after its final pitch has ended, then subsequent note-ons inherit the hold/pause.
            val remainingOffs = track.events.filterIsInstance<MidiNoteOffEvent>()
                .groupingBy { it.sourceEventId to it.absoluteTicks }
                .eachCount()
                .toMutableMap()
            val noteOnTicksBySource = track.events.filterIsInstance<MidiNoteOnEvent>()
                .mapNotNull { noteOn -> noteOn.sourceEventId?.let { it to noteOn.absoluteTicks } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, ticks) -> ticks.sorted() }
            var accumulated = 0L
            val shifted = track.events.map { event ->
                if (event is MidiNoteOffEvent) {
                    val adjustment = event.sourceEventId?.let(adjustments::get)
                    val extension = adjustment?.fermataExtension
                        ?.let { beatFractionToTicks(it, ticksPerQuarter) } ?: 0L
                    val globalPause = adjustment?.globalBreathPause
                        ?.let { beatFractionToTicks(it, ticksPerQuarter) } ?: 0L
                    val localPause = adjustment?.localBreathPause
                        ?.let { beatFractionToTicks(it, ticksPerQuarter) } ?: 0L
                    val sourceOnset = event.sourceEventId
                        ?.let(noteOnTicksBySource::get)
                        ?.lastOrNull { it <= event.absoluteTicks }
                        ?: event.absoluteTicks
                    val shortenedOff = (event.absoluteTicks - localPause).coerceAtLeast(sourceOnset)
                    val moved = event.copy(
                        absoluteTicks = shortenedOff + accumulated + extension,
                    )
                    val key = event.sourceEventId to event.absoluteTicks
                    val left = (remainingOffs[key] ?: 1) - 1
                    if (left <= 0) {
                        remainingOffs.remove(key)
                        accumulated += extension + globalPause
                    } else remainingOffs[key] = left
                    moved
                } else event.shiftedBy(accumulated)
            }
            track.copy(events = shifted.toImmutableList())
        }
        return MidiScore.create(tracks, midi.tempoTrack, midi.ticksPerQuarter)
    }

    fun playbackTimeline(
        score: RuntimeScore,
        ticksPerQuarter: Int = MidiScore.DEFAULT_TICKS_PER_QUARTER,
    ): PlaybackTimeline {
        val measures = score.measures.map { it.value }.sortedBy { it.number }
        if (measures.isEmpty()) return PlaybackTimeline(emptyList())
        val sourceOffsets = buildMeasureOffsets(score.measures, ticksPerQuarter)
        val order = buildPlaybackMeasureOrder(measures)
        var playbackStart = 0L
        val occurrences = order.map { measure ->
            val duration = fractionToTicks(measure.duration, ticksPerQuarter)
            PlaybackMeasureOccurrence(
                measureNumber = measure.number,
                sourceStartTicks = sourceOffsets[measure.number] ?: 0L,
                playbackStartTicks = playbackStart,
                durationTicks = duration,
            ).also { playbackStart += duration }
        }
        val holds = occurrences.flatMap { occurrence ->
            score.globalTrack.events.mapNotNull { event ->
                if (event.onset.measure != occurrence.measureNumber) return@mapNotNull null
                val amount = when (event) {
                    is StorageFermata -> event.extension
                    is StorageGlobalBreathMark -> event.pause
                    else -> return@mapNotNull null
                }
                val beatTicks = fractionToTicks(event.onset.beat ?: Fraction.ZERO, ticksPerQuarter)
                val visualTime = if (event is StorageFermata) {
                    score.voiceTracks.values.mapNotNull { voice ->
                        voice.events.toList().lastOrNull { !it.isGrace && it.onset < event.onset }?.onset
                    }.maxOrNull() ?: event.onset
                } else event.onset
                PlaybackHold(
                    basePlaybackTicks = occurrence.playbackStartTicks + beatTicks,
                    durationTicks = beatFractionToTicks(amount, ticksPerQuarter),
                    sourceTicks = timeCodeToTicks(visualTime, sourceOffsets, ticksPerQuarter),
                )
            }
        }.groupBy { it.basePlaybackTicks }
            .map { (base, sameTime) ->
                PlaybackHold(
                    basePlaybackTicks = base,
                    durationTicks = sameTime.sumOf { it.durationTicks },
                    sourceTicks = sameTime.minOf { it.sourceTicks },
                )
            }
            .sortedBy { it.basePlaybackTicks }
        return PlaybackTimeline(occurrences, holds)
    }

    private fun buildPlaybackMeasureOrder(measures: List<RuntimeMeasure>): List<RuntimeMeasure> {
        val out = mutableListOf<RuntimeMeasure>()
        val passesByEndIndex = mutableMapOf<Int, Int>()
        var repeatStartIndex = 0
        var activePass = 1
        var index = 0
        var navigationJumped = false
        var stopAtFine = false
        var jumpAtToCoda = false
        var codaJumped = false
        val segnoIndex = measures.indexOfFirst { NavigationMark.SEGNO in it.navigationMarks }
            .takeIf { it >= 0 } ?: 0
        val codaIndex = measures.indexOfFirst { NavigationMark.CODA in it.navigationMarks }
            .takeIf { it >= 0 }
        // Malformed imported files must not create an unbounded conversion loop.
        val maxOccurrences = (measures.size * 64).coerceAtLeast(64)
        while (index in measures.indices && out.size < maxOccurrences) {
            val measure = measures[index]

            if (measure.voltaNumbers.isNotEmpty() && activePass !in measure.voltaNumbers) {
                val skippedEnd = generateSequence(index) { it + 1 }
                    .takeWhile {
                        it in measures.indices &&
                            measures[it].voltaNumbers.isNotEmpty() &&
                            activePass !in measures[it].voltaNumbers
                    }
                    .lastOrNull() ?: index
                index = skippedEnd + 1
                continue
            }

            if (measure.repeatStart) repeatStartIndex = index
            out += measure

            if (navigationJumped && stopAtFine && NavigationMark.FINE in measure.navigationMarks) break
            if (navigationJumped && jumpAtToCoda && !codaJumped &&
                NavigationMark.TO_CODA in measure.navigationMarks && codaIndex != null
            ) {
                codaJumped = true
                index = codaIndex
                continue
            }

            if (!navigationJumped) {
                val jump = measure.navigationMarks.firstOrNull {
                    it in setOf(
                        NavigationMark.DA_CAPO,
                        NavigationMark.DAL_SEGNO,
                        NavigationMark.DA_CAPO_AL_FINE,
                        NavigationMark.DAL_SEGNO_AL_FINE,
                        NavigationMark.DA_CAPO_AL_CODA,
                        NavigationMark.DAL_SEGNO_AL_CODA,
                    )
                }
                if (jump != null) {
                    navigationJumped = true
                    stopAtFine = jump == NavigationMark.DA_CAPO_AL_FINE ||
                        jump == NavigationMark.DAL_SEGNO_AL_FINE
                    jumpAtToCoda = jump == NavigationMark.DA_CAPO_AL_CODA ||
                        jump == NavigationMark.DAL_SEGNO_AL_CODA
                    passesByEndIndex.clear()
                    activePass = 1
                    repeatStartIndex = 0
                    index = if (jump in setOf(
                            NavigationMark.DAL_SEGNO,
                            NavigationMark.DAL_SEGNO_AL_FINE,
                            NavigationMark.DAL_SEGNO_AL_CODA,
                        )
                    ) segnoIndex else 0
                    continue
                }
            }

            if (measure.repeatEnd) {
                val completedPasses = passesByEndIndex[index] ?: 1
                val totalPasses = measure.repeatCount.coerceAtLeast(2)
                if (completedPasses < totalPasses) {
                    passesByEndIndex[index] = completedPasses + 1
                    activePass = completedPasses + 1
                    index = repeatStartIndex.coerceIn(0, index)
                } else {
                    passesByEndIndex.remove(index)
                    activePass = 1
                    repeatStartIndex = index + 1
                    index++
                }
            } else {
                if (measure.voltaNumbers.isNotEmpty() &&
                    measures.getOrNull(index + 1)?.voltaNumbers?.isEmpty() != false
                ) {
                    activePass = 1
                }
                index++
            }
        }
        return out
    }

    private fun expandRepeats(
        linear: MidiScore,
        score: RuntimeScore,
        sourceOffsets: Map<Int, Long>,
        ticksPerQuarter: Int,
    ): MidiScore {
        val timeline = playbackTimeline(score, ticksPerQuarter)
        val measures = score.measures.map { it.value }.sortedBy { it.number }
        val isLinear = timeline.occurrences.size == measures.size &&
            timeline.occurrences.zip(measures).all { (occurrence, measure) ->
                occurrence.measureNumber == measure.number &&
                    occurrence.playbackStartTicks == occurrence.sourceStartTicks
            }
        if (isLinear) return linear

        val pitchMeasureById = score.pitchTracks.values
            .flatMap { it.events.toList() }
            .associate { it.id to it.onset.measure }

        val tracks = linear.tracks.map { track ->
            val setup = track.events.filterIsInstance<MidiProgramChangeEvent>()
                .filter { it.absoluteTicks == 0L }
            val musical = timeline.occurrences.flatMap { occurrence ->
                val shift = occurrence.playbackStartTicks - occurrence.sourceStartTicks
                track.events.asSequence()
                    .filterNot { it is MidiProgramChangeEvent }
                    .filter { event -> event.sourceEventId?.let(pitchMeasureById::get) == occurrence.measureNumber }
                    .map { event -> event.shiftedBy(shift) }
                    .toList()
            }
            track.copy(events = (setup + musical).sortedBy { it.absoluteTicks }.toImmutableList())
        }

        val tempo = timeline.occurrences.flatMap { occurrence ->
            val sourceStart = occurrence.sourceStartTicks
            val sourceEnd = sourceStart + occurrence.durationTicks
            val effectiveAtStart = linear.tempoTrack
                .lastOrNull { it.absoluteTicks <= sourceStart }
                ?: MidiTempoEvent(0L, score.defaultTempo)
            val withinMeasure = linear.tempoTrack
                .filter { it.absoluteTicks in sourceStart until sourceEnd }
                .map { it.copy(absoluteTicks = occurrence.playbackStartTicks + it.absoluteTicks - sourceStart) }
            listOf(effectiveAtStart.copy(absoluteTicks = occurrence.playbackStartTicks)) + withinMeasure
        }
            .groupBy { it.absoluteTicks }
            .map { (_, events) -> events.last() }
            .sortedBy { it.absoluteTicks }

        return MidiScore.create(tracks, tempo, ticksPerQuarter)
    }

    private fun MidiEvent.shiftedBy(delta: Long): MidiEvent = when (this) {
        is MidiNoteOnEvent -> copy(absoluteTicks = absoluteTicks + delta)
        is MidiNoteOffEvent -> copy(absoluteTicks = absoluteTicks + delta)
        is MidiProgramChangeEvent -> copy(absoluteTicks = absoluteTicks + delta)
        is MidiControlChangeEvent -> copy(absoluteTicks = absoluteTicks + delta)
        is MidiTempoEvent -> copy(absoluteTicks = absoluteTicks + delta)
    }

    /**
     * General MIDI reserves zero-based channel 9 for percussion. Sending a
     * pitched instrument such as Violin II there makes its note numbers select
     * drum samples, regardless of the preceding program change.
     *
     * The current single-port backend has only 15 melodic channels. Preserve
     * the existing overflow behaviour by sharing the last melodic channel once
     * those channels are exhausted, but never route a pitched track to channel 9.
     */
    private fun melodicChannelForInstrument(instrumentIndex: Int): MidiChannel {
        val melodicIndex = instrumentIndex.coerceIn(0, 14)
        return MidiChannel(if (melodicIndex >= 9) melodicIndex + 1 else melodicIndex)
    }

    /**
     * Converts every resolved keyframe to MIDI tempo events. Continuous transitions are sampled at an
     * eighth-note cadence (capped for very long spans); the destination keyframe remains authoritative.
     */
    private fun buildTempoTrack(
        score: RuntimeScore,
        measureOffsets: Map<Int, Long>,
        ticksPerQuarter: Int,
    ): List<MidiTempoEvent> {
        val keyframes = score.resolvedTempoKeyframes()
        val out = mutableListOf<MidiTempoEvent>()
        for ((index, current) in keyframes.withIndex()) {
            val startTick = timeCodeToTicks(current.source.onset, measureOffsets, ticksPerQuarter)
            out += MidiTempoEvent(startTick, current.effectiveBpm, current.source.id)
            val next = keyframes.getOrNull(index + 1) ?: continue
            if (current.source.transitionToNext == TempoTransition.STEP) continue
            val endTick = timeCodeToTicks(next.source.onset, measureOffsets, ticksPerQuarter)
            val duration = endTick - startTick
            if (duration <= 1L) continue
            val cadence = (ticksPerQuarter / 2).coerceAtLeast(1).toLong()
            val samples = (duration / cadence).toInt().coerceIn(2, 128)
            for (sample in 1 until samples) {
                val t = sample.toFloat() / samples
                val shaped = when (current.source.transitionToNext) {
                    TempoTransition.LINEAR -> t
                    TempoTransition.EASE_IN -> t * t
                    TempoTransition.EASE_OUT -> 1f - (1f - t) * (1f - t)
                    TempoTransition.EASE_IN_OUT -> t * t * (3f - 2f * t)
                    TempoTransition.STEP -> 0f
                }
                out += MidiTempoEvent(
                    absoluteTicks = startTick + (duration * t).toLong(),
                    bpm = current.effectiveBpm + (next.effectiveBpm - current.effectiveBpm) * shaped,
                    sourceEventId = current.source.id,
                )
            }
        }
        return out
            .groupBy { it.absoluteTicks }
            .map { (_, events) -> events.last() }
            .sortedBy { it.absoluteTicks }
    }

    private fun buildMeasureOffsets(
        measures: BPlusTree<Int, RuntimeMeasure, Int>,
        ticksPerQuarter: Int
    ): Map<Int, Long> {
        val offsets = mutableMapOf<Int, Long>()
        var currentTicks = 0L

        val sortedMeasures = measures.map { it.value }

        for (measure in sortedMeasures) {
            offsets[measure.number] = currentTicks
            val measureDurationTicks = fractionToTicks(measure.duration, ticksPerQuarter)
            currentTicks += measureDurationTicks
        }

        val lastMeasure = sortedMeasures.lastOrNull()
        if (lastMeasure != null) {
            val lastDuration = fractionToTicks(lastMeasure.duration, ticksPerQuarter)
            for (i in 1..100) {
                val measureNum = lastMeasure.number + i
                offsets[measureNum] = currentTicks + lastDuration * i
            }
        }

        return offsets
    }

    /**
     * Resolved sounding window for a single pitch event.
     */
    private data class Timing(val onsetTicks: Long, val durationTicks: Long)

    private data class TieChains(
        /** Pitches that continue a tie and must not be struck again. */
        val continuations: Map<EventId, Set<Int>>,
        /** Where a tie chain's first note actually stops sounding. */
        val endTicks: Map<EventId, Map<Int, Long>>,
    )

    /**
     * Follows tie chains within one pitch track.
     *
     * A tie is matched to the next event that carries the same sounding pitch, which covers
     * ordinary ties and tied chord members. Anything the chain cannot match keeps the untied
     * behaviour, so this only ever merges notes it is sure about.
     */
    private fun resolveTieChains(
        pitchEvents: List<RuntimePitchEvent>,
        timings: Map<EventId, Timing>,
        tiedOutPitchesByPitchEventId: Map<EventId, Set<Int>>,
    ): TieChains {
        if (tiedOutPitchesByPitchEventId.isEmpty()) return TieChains(emptyMap(), emptyMap())
        val continuations = mutableMapOf<EventId, MutableSet<Int>>()
        val endTicks = mutableMapOf<EventId, MutableMap<Int, Long>>()
        val sounding = pitchEvents.filterNot { it.isRest }
        sounding.forEachIndexed { index, event ->
            val tiedOut = tiedOutPitchesByPitchEventId[event.id] ?: return@forEachIndexed
            tiedOut.forEach { midiNumber ->
                // Walk the chain forward while each next event repeats the pitch.
                var cursor = index
                var end: Long? = null
                while (true) {
                    val next = sounding.getOrNull(cursor + 1) ?: break
                    if (next.pitches.none { it.midiNumber == midiNumber }) break
                    val nextTiming = timings[next.id] ?: break
                    continuations.getOrPut(next.id) { hashSetOf() } += midiNumber
                    end = nextTiming.onsetTicks + nextTiming.durationTicks
                    cursor++
                    if (tiedOutPitchesByPitchEventId[next.id]?.contains(midiNumber) != true) break
                }
                if (end != null) endTicks.getOrPut(event.id) { hashMapOf() }[midiNumber] = end
            }
        }
        return TieChains(continuations, endTicks)
    }

    private fun convertPitchTrack(
        track: RuntimePitchTrack,
        channel: MidiChannel,
        midiProgram: Int?,
        measureOffsets: Map<Int, Long>,
        measures: BPlusTree<Int, RuntimeMeasure, Int>,
        defaultTimeSignature: TimeSignature,
        graceInfoByPitchEventId: Map<EventId, GraceNoteInfo>,
        ornamentByPitchEventId: Map<EventId, StorageOrnamentMark>,
        arpeggioByPitchEventId: Map<EventId, ArpeggioType>,
        tiedOutPitchesByPitchEventId: Map<EventId, Set<Int>>,
        score: RuntimeScore,
        config: ConversionConfig
    ): MidiTrack {
        val pitchEvents = track.events.toList().sortedBy { it.onset }
        val timings = computeTimings(
            pitchEvents = pitchEvents,
            graceInfoByPitchEventId = graceInfoByPitchEventId,
            measureOffsets = measureOffsets,
            measures = measures,
            defaultTimeSignature = defaultTimeSignature,
            ticksPerQuarter = config.ticksPerQuarter
        )

        val tieChains = resolveTieChains(pitchEvents, timings, tiedOutPitchesByPitchEventId)

        val events = mutableListOf<MidiEvent>()
        midiProgram?.let { program ->
            events += MidiProgramChangeEvent(absoluteTicks = 0, program = program, channel = channel)
        }
        for (pe in pitchEvents) {
            if (pe.isRest) continue
            val timing = timings[pe.id] ?: continue
            if (timing.durationTicks <= 0) continue

            val ornament = ornamentByPitchEventId[pe.id]
            if (ornament != null && pe.pitches.size == 1) {
                events += expandedOrnamentEvents(
                    pitch = pe.pitches.single(),
                    timing = timing,
                    ornament = ornament,
                    key = score.getKeySignatureAt(pe.onset.measure),
                    channel = channel,
                    velocity = config.defaultVelocity,
                    ticksPerQuarter = config.ticksPerQuarter,
                    sourceEventId = pe.id,
                )
                continue
            }

            val arpeggio = arpeggioByPitchEventId[pe.id]
            val orderedPitches = when (arpeggio) {
                ArpeggioType.DOWN -> pe.pitches.sortedByDescending { it.midiNumber }
                else -> pe.pitches.sortedBy { it.midiNumber }
            }
            val arpeggioStep = if (arpeggio != null && arpeggio != ArpeggioType.NON_ARPEGGIATE) {
                (config.ticksPerQuarter / 16).coerceAtLeast(1).toLong()
            } else 0L
            for ((pitchIndex, pitch) in orderedPitches.withIndex()) {
                if (!MidiNoteRange.contains(pitch.midiNumber)) continue
                // A tied continuation is the same sounding note, so it is neither struck again
                // nor released here; the note it continues already holds until the chain ends.
                if (tieChains.continuations[pe.id]?.contains(pitch.midiNumber) == true) continue
                val stagger = arpeggioStep * pitchIndex
                val end = tieChains.endTicks[pe.id]?.get(pitch.midiNumber)
                    ?: (timing.onsetTicks + timing.durationTicks)
                events.add(
                    MidiNoteOnEvent(
                        absoluteTicks = timing.onsetTicks + stagger,
                        midiNumber = pitch.midiNumber,
                        velocity = config.defaultVelocity,
                        channel = channel,
                        sourceEventId = pe.id
                    )
                )
                events.add(
                    MidiNoteOffEvent(
                        absoluteTicks = end,
                        midiNumber = pitch.midiNumber,
                        channel = channel,
                        sourceEventId = pe.id
                    )
                )
            }
        }

        events.sortBy { it.absoluteTicks }

        return MidiTrack(
            id = track.id,
            name = track.name,
            channel = channel,
            events = events.toImmutableList()
        )
    }

    private fun expandedOrnamentEvents(
        pitch: Pitch,
        timing: Timing,
        ornament: StorageOrnamentMark,
        key: KeySignature,
        channel: MidiChannel,
        velocity: Velocity,
        ticksPerQuarter: Int,
        sourceEventId: EventId,
    ): List<MidiEvent> {
        fun auxiliary(step: Int, explicit: Accidental?): Pitch {
            val diatonic = pitch.diatonicSteps + step
            val noteName = NoteName.fromIndex(diatonic)
            val accidental = explicit ?: key.accidentalFor(noteName)
            return Pitch(diatonic, accidental.offset)
        }
        val upper = auxiliary(1, ornament.upperAccidental)
        val lower = auxiliary(-1, ornament.lowerAccidental)
        val basePattern = when (ornament.kind) {
            OrnamentKind.TRILL,
            OrnamentKind.TREMBLEMENT,
            OrnamentKind.TREMBLEMENT_COUPERIN -> listOf(pitch, upper)
            OrnamentKind.MORDENT,
            OrnamentKind.MORDENT_UPPER_PREFIX,
            OrnamentKind.MORDENT_RELEASE -> buildList {
                repeat(ornament.oscillations) { add(pitch); add(lower) }
                add(pitch)
            }
            OrnamentKind.INVERTED_MORDENT,
            OrnamentKind.INVERTED_MORDENT_UPPER_PREFIX -> buildList {
                repeat(ornament.oscillations) { add(pitch); add(upper) }
                add(pitch)
            }
            OrnamentKind.TURN,
            OrnamentKind.TURN_SLASH -> listOf(upper, pitch, lower, pitch)
            OrnamentKind.INVERTED_TURN -> listOf(lower, pitch, upper, pitch)
        }
        val elementTicks = (
            ticksPerQuarter.toLong() * ornament.elementDuration.numerator /
                ornament.elementDuration.denominator
            ).coerceAtLeast(1L)
        val available = timing.durationTicks
        if (available <= 0L) return emptyList()
        val pattern = if (ornament.kind == OrnamentKind.TRILL ||
            ornament.kind == OrnamentKind.TREMBLEMENT ||
            ornament.kind == OrnamentKind.TREMBLEMENT_COUPERIN
        ) {
            generateSequence(0) { it + 1 }
                .map { basePattern[it % basePattern.size] }
                .take((available / elementTicks).toInt().coerceAtLeast(1))
                .toList()
        } else basePattern
        val sounded = pattern.take((available / elementTicks).toInt().coerceAtLeast(1))
        val patternDuration = (sounded.size * elementTicks).coerceAtMost(available)
        val start = if (ornament.anchor == OrnamentAnchor.BETWEEN_NOTES) {
            timing.onsetTicks + available - patternDuration
        } else timing.onsetTicks
        val out = mutableListOf<MidiEvent>()
        var cursor = start
        sounded.forEach { note ->
            val end = minOf(cursor + elementTicks, timing.onsetTicks + available)
            if (end > cursor && MidiNoteRange.contains(note.midiNumber)) {
                out += MidiNoteOnEvent(cursor, note.midiNumber, velocity, channel, sourceEventId)
                out += MidiNoteOffEvent(end, note.midiNumber, channel, sourceEventId)
            }
            cursor = end
        }
        val noteEnd = timing.onsetTicks + available
        if (cursor < noteEnd) {
            out += MidiNoteOnEvent(cursor, pitch.midiNumber, velocity, channel, sourceEventId)
            out += MidiNoteOffEvent(noteEnd, pitch.midiNumber, channel, sourceEventId)
        }
        return out
    }

    /**
     * Two-pass timing resolution:
     * 1. Each event gets a baseline `(onsetTicks, endTicks)` ignoring its grace
     *    component (so all events sharing `(measure, beat)` collapse onto the
     *    principal's tick).
     * 2. Grace groups (contiguous events at the same `(measure, beat)` whose
     *    first member carries [GraceNoteInfo]) override the baseline:
     *    - graces are spread evenly across [GraceNoteInfo.totalDuration]
     *    - depending on [GraceTimeSource], either the preceding event is
     *      shortened (PREVIOUS) or the principal is delayed (PRINCIPAL)
     */
    private fun computeTimings(
        pitchEvents: List<RuntimePitchEvent>,
        graceInfoByPitchEventId: Map<EventId, GraceNoteInfo>,
        measureOffsets: Map<Int, Long>,
        measures: BPlusTree<Int, RuntimeMeasure, Int>,
        defaultTimeSignature: TimeSignature,
        ticksPerQuarter: Int,
    ): Map<EventId, Timing> {
        if (pitchEvents.isEmpty()) return emptyMap()

        fun baselineOnsetTicks(pe: RuntimePitchEvent): Long {
            val measureOffset = measureOffsets[pe.onset.measure] ?: 0L
            val beat = pe.onset.beat ?: Fraction.ZERO
            return measureOffset + fractionToTicks(beat, ticksPerQuarter)
        }

        // Baseline next-event onset (in track order), or end-of-measure for the last event.
        fun nextOnsetTicks(idx: Int): Long {
            for (j in (idx + 1) until pitchEvents.size) {
                return baselineOnsetTicks(pitchEvents[j])
            }
            val lastMeasureNum = pitchEvents[idx].onset.measure
            val measureDuration = measures.get(lastMeasureNum)?.duration
                ?: defaultTimeSignature.measureDuration()
            return (measureOffsets[lastMeasureNum] ?: 0L) +
                fractionToTicks(measureDuration, ticksPerQuarter)
        }

        val result = mutableMapOf<EventId, Timing>()

        // Pass 1: baseline timings.
        for (i in pitchEvents.indices) {
            val pe = pitchEvents[i]
            val onset = baselineOnsetTicks(pe)
            val end = nextOnsetTicks(i)
            result[pe.id] = Timing(onset, (end - onset).coerceAtLeast(0L))
        }

        // Pass 2: override grace groups.
        var i = 0
        while (i < pitchEvents.size) {
            val head = pitchEvents[i]
            val info = graceInfoByPitchEventId[head.id]
            if (head.onset.grace == null || info == null) {
                i++
                continue
            }

            val groupMeasure = head.onset.measure
            val groupBeat = head.onset.beat ?: Fraction.ZERO
            var j = i
            val graceEvents = mutableListOf<RuntimePitchEvent>()
            var principal: RuntimePitchEvent? = null
            while (j < pitchEvents.size) {
                val e = pitchEvents[j]
                val sameBeat = e.onset.measure == groupMeasure &&
                    (e.onset.beat ?: Fraction.ZERO) == groupBeat
                if (!sameBeat) break
                if (e.onset.grace != null) {
                    graceEvents.add(e)
                    j++
                } else {
                    principal = e
                    j++
                    break
                }
            }
            val groupEndIdx = j

            val principalTicks = (measureOffsets[groupMeasure] ?: 0L) +
                fractionToTicks(groupBeat, ticksPerQuarter)
            val totalDurationTicks = fractionToTicks(info.totalDuration.toFraction(), ticksPerQuarter)
            val n = graceEvents.size
            val perGraceTicks = if (n > 0) (totalDurationTicks / n).coerceAtLeast(1L) else 0L

            when (info.stealFrom) {
                GraceTimeSource.PREVIOUS -> {
                    val windowStart = principalTicks - totalDurationTicks
                    // Shorten the preceding pitch event (skip rests).
                    var prevIdx = i - 1
                    while (prevIdx >= 0 && pitchEvents[prevIdx].isRest) prevIdx--
                    if (prevIdx >= 0) {
                        val prev = pitchEvents[prevIdx]
                        val prevOnset = result[prev.id]?.onsetTicks ?: baselineOnsetTicks(prev)
                        val newDur = (windowStart - prevOnset).coerceAtLeast(1L)
                        result[prev.id] = Timing(prevOnset, newDur)
                    }
                    // Graces sound in [windowStart, principalTicks).
                    for ((k, g) in graceEvents.withIndex()) {
                        val onset = windowStart + k * perGraceTicks
                        result[g.id] = Timing(onset, perGraceTicks)
                    }
                    // Principal keeps its baseline.
                }
                GraceTimeSource.PRINCIPAL -> {
                    // Graces sound in [principalTicks, principalTicks + totalDurationTicks).
                    for ((k, g) in graceEvents.withIndex()) {
                        val onset = principalTicks + k * perGraceTicks
                        result[g.id] = Timing(onset, perGraceTicks)
                    }
                    // Principal is delayed by totalDurationTicks; its end stays put.
                    if (principal != null) {
                        val originalEnd = result[principal.id]?.let { it.onsetTicks + it.durationTicks }
                            ?: (principalTicks + totalDurationTicks)
                        val newStart = principalTicks + totalDurationTicks
                        val newDur = (originalEnd - newStart).coerceAtLeast(1L)
                        result[principal.id] = Timing(newStart, newDur)
                    }
                }
            }

            i = groupEndIdx
        }

        return result
    }

    /**
     * Convert TimeCode to absolute ticks using a runtime score.
     */
    fun timeCodeToTicks(
        timeCode: TimeCode,
        score: RuntimeScore,
        ticksPerQuarter: Int = MidiScore.DEFAULT_TICKS_PER_QUARTER
    ): Long {
        val measureOffsets = buildMeasureOffsets(score.measures, ticksPerQuarter)
        return timeCodeToTicks(timeCode, measureOffsets, ticksPerQuarter)
    }

    /**
     * Convert a batch of score positions while building variable-measure offsets exactly once.
     *
     * Callers constructing render/playback indexes must use this overload instead of invoking
     * [timeCodeToTicks] once per position, which would rescan every measure for every item.
     */
    fun timeCodesToTicks(
        timeCodes: Iterable<TimeCode>,
        score: RuntimeScore,
        ticksPerQuarter: Int = MidiScore.DEFAULT_TICKS_PER_QUARTER,
    ): List<Long> {
        val measureOffsets = buildMeasureOffsets(score.measures, ticksPerQuarter)
        return timeCodes.map { timeCode ->
            timeCodeToTicks(timeCode, measureOffsets, ticksPerQuarter)
        }
    }

    /** First sounding occurrence of [timeCode] in the expanded repeat timeline. */
    fun timeCodeToPlaybackTicks(
        timeCode: TimeCode,
        score: RuntimeScore,
        ticksPerQuarter: Int = MidiScore.DEFAULT_TICKS_PER_QUARTER,
    ): Long {
        val beatTicks = fractionToTicks(timeCode.beat ?: Fraction.ZERO, ticksPerQuarter)
        return playbackTimeline(score, ticksPerQuarter)
            .firstPlaybackTick(timeCode.measure, beatTicks)
            ?: timeCodeToTicks(timeCode, score, ticksPerQuarter)
    }

    /** Batch form of [timeCodeToPlaybackTicks] that builds the expanded playback timeline once. */
    fun timeCodesToPlaybackTicks(
        timeCodes: Iterable<TimeCode>,
        score: RuntimeScore,
        ticksPerQuarter: Int = MidiScore.DEFAULT_TICKS_PER_QUARTER,
    ): List<Long> {
        val timeline = playbackTimeline(score, ticksPerQuarter)
        val measureOffsets = buildMeasureOffsets(score.measures, ticksPerQuarter)
        return timeCodes.map { timeCode ->
            val beatTicks = fractionToTicks(timeCode.beat ?: Fraction.ZERO, ticksPerQuarter)
            timeline.firstPlaybackTick(timeCode.measure, beatTicks)
                ?: timeCodeToTicks(timeCode, measureOffsets, ticksPerQuarter)
        }
    }

    private fun timeCodeToTicks(
        timeCode: TimeCode,
        measureOffsets: Map<Int, Long>,
        ticksPerQuarter: Int
    ): Long {
        val measureNum = timeCode.measure
        val measureOffset = measureOffsets[measureNum] ?: 0L
        val beat = timeCode.beat ?: Fraction.ZERO
        val beatTicks = fractionToTicks(beat, ticksPerQuarter)
        return measureOffset + beatTicks
    }

    private fun fractionToTicks(fraction: Fraction, ticksPerQuarter: Int): Long {
        val wholeNoteTicks = 4L * ticksPerQuarter
        return (wholeNoteTicks * fraction.numerator / fraction.denominator)
    }

    /** Performance-mark values are quarter-note beats, not whole-note fractions. */
    private fun beatFractionToTicks(fraction: Fraction, ticksPerQuarter: Int): Long =
        ticksPerQuarter.toLong() * fraction.numerator / fraction.denominator
}
