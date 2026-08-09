package com.mecon.plugins.chord

import com.mecon.api.computed.AlignedEvent2
import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.computed.ReferenceAligner
import com.mecon.api.computed.pluginEventsOf
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.TimeIndexedList
import com.mecon.api.runtime.tracks.RuntimeVoiceTrack
import com.mecon.theory.BeatWeight
import com.mecon.theory.NonChordToneClassification
import com.mecon.theory.NonChordToneClassifier
import com.mecon.theory.NonChordToneContext
import com.mecon.theory.NonChordToneType

data class ChordToneResult(
    val isChordTone: Boolean,
    val nonChordTone: NonChordToneClassification? = null,
) {
    val type: NonChordToneType? get() = nonChordTone?.primary
}

/**
 * Computes chord-tone membership for every notehead in the score.
 *
 * Pairs each voice event with the most recent chord whose onset ≤ the note's onset ("the chord in effect at
 * that moment") via [ReferenceAligner] (alignLe semantics). The aligner is **stateful and incremental**: it
 * is cached between [compute] calls and, when the score changes a little (a note edited, a chord moved),
 * re-pairs only the affected onset window and patches the corresponding map entries — the rest is reused.
 *
 * Voice events are aligned directly off the score's persistent [ComputedScore.computedEvents] B+ tree
 * (`asTimeIndexedList()`), so successive incrementally-computed scores share structure and the input diff
 * stays O(changes · log N).
 *
 * Return: (voiceEventId, pitchIndex) → true if the pitch is a chord tone. Pairs are absent when no chord
 * event precedes or coincides with the note.
 *
 * Threading: callers ([com.mecon.api.plugin.NoteStyleProvider.computeStyles] via the render engine's
 * `render` / `reapplyNoteStyles`) are serialized onto a single render dispatch, so the cache is accessed
 * single-threaded; no synchronization is used.
 */
object ChordToneAnalysis {

    private var cachedAligner: ReferenceAligner<ComputedVoiceEvent, ComputedChordEvent>? = null
    private var cachedResult: Map<Pair<EventId, Int>, Boolean> = emptyMap()
    private var cachedDetailedResult: Map<Pair<EventId, Int>, ChordToneResult> = emptyMap()
    private var cachedVoiceTracks: Map<TrackId, RuntimeVoiceTrack> = emptyMap()
    private var cachedNeighbors: Map<EventId, Pair<EventId?, EventId?>> = emptyMap()
    private var cachedAlignedRows: Map<EventId, AlignedEvent2<ComputedVoiceEvent, ComputedChordEvent>> = emptyMap()
    var lastChangedKeys: Set<Pair<EventId, Int>> = emptySet()
        private set

    fun compute(computedScore: ComputedScore): Map<Pair<EventId, Int>, Boolean> {
        return computeDetailed(computedScore).mapValues { it.value.isChordTone }
    }

    /**
     * 返回和弦音隶属与教材外音分类。和弦或音符变化只重算 ReferenceAligner 窗口内的音，
     * 并把同声部前后各扩一音，因为分类只依赖 (previous, current, next) 三音窗口。
     */
    fun computeDetailed(computedScore: ComputedScore): Map<Pair<EventId, Int>, ChordToneResult> {
        val chordEvents = computedScore
            .pluginEventsOf<StorageChordEvent>(StorageChordEvent.TRACK_TYPE)
            .map { ComputedChordEvent.fromRuntime(RuntimeChordEvent.fromStorage(it)) }

        val voiceList = computedScore.computedEvents.asTimeIndexedList()
        if (chordEvents.isEmpty() || voiceList.isEmpty()) return reset()

        val chordList = TimeIndexedList.of(chordEvents)
        val previous = cachedAligner

        if (previous == null) {
            val aligner = ReferenceAligner.build(voiceList, chordList)
            cachedAligner = aligner
            cachedAlignedRows = aligner.aligned.toList().mapNotNull { row ->
                row.events.first?.id?.let { it to row }
            }.toMap()
            rebuildNeighborIndex(computedScore)
            return buildFullDetailedResult(computedScore, aligner.aligned, chordEvents).toMutableMap()
                .also { result -> applyNeighborGroupLabels(result, computedScore, result.keys.mapTo(linkedSetOf()) { it.first }) }
                .also { result ->
                    lastChangedKeys = result.keys
                    storeDetailedResult(result)
                }
        }

        val updated = previous.update(voiceList, chordList)
        cachedAligner = updated

        val windowStart = updated.lastRecomputedWindowStart
        if (windowStart == null) {
            lastChangedKeys = emptySet()
            return cachedDetailedResult // nothing changed → reuse the whole map
        }

        val windowEnd = updated.lastRecomputedWindowEndExclusive
        val oldRows = windowRows(previous.aligned, windowStart, windowEnd)
        val newRows = windowRows(updated.aligned, windowStart, windowEnd)

        // Every voice id touched in the window (old or new) — re-pair from the fresh rows, drop the rest.
        val affectedIds = HashSet<EventId>()
        oldRows.forEach { it.events.first?.let { ev -> affectedIds.add(ev.id) } }
        newRows.forEach { it.events.first?.let { ev -> affectedIds.add(ev.id) } }

        val oldNeighbors = cachedNeighbors
        rebuildNeighborIndex(computedScore)
        expandWithNeighbors(affectedIds, oldNeighbors)
        expandWithNeighbors(affectedIds, cachedNeighbors)
        // 邻音组是五音复合形，任一成员变化都会影响最远四个邻接音的标签。
        repeat(3) {
            expandWithNeighbors(affectedIds, oldNeighbors)
            expandWithNeighbors(affectedIds, cachedNeighbors)
        }

        val oldAffectedKeys = cachedDetailedResult.keys.filterTo(linkedSetOf()) { it.first in affectedIds }
        val out = cachedDetailedResult.toMutableMap()
        out.keys.removeAll { it.first in affectedIds }
        val alignedById = cachedAlignedRows.toMutableMap()
        oldRows.forEach { row -> row.events.first?.id?.let(alignedById::remove) }
        newRows.forEach { row -> row.events.first?.id?.let { alignedById[it] = row } }
        cachedAlignedRows = alignedById
        affectedIds.forEach { eventId ->
            alignedById[eventId]?.let { addDetailedRow(out, computedScore, it, chordEvents) }
        }
        applyNeighborGroupLabels(out, computedScore, affectedIds)
        lastChangedKeys = oldAffectedKeys + out.keys.filter { it.first in affectedIds }
        return out.also(::storeDetailedResult)
    }

    private fun reset(): Map<Pair<EventId, Int>, ChordToneResult> {
        lastChangedKeys = cachedDetailedResult.keys
        cachedAligner = null
        cachedResult = emptyMap()
        cachedDetailedResult = emptyMap()
        cachedVoiceTracks = emptyMap()
        cachedNeighbors = emptyMap()
        cachedAlignedRows = emptyMap()
        return cachedDetailedResult
    }

    private fun buildFullDetailedResult(
        computedScore: ComputedScore,
        aligned: TimeIndexedList<AlignedEvent2<ComputedVoiceEvent, ComputedChordEvent>>,
        chords: List<ComputedChordEvent>,
    ): Map<Pair<EventId, Int>, ChordToneResult> {
        val out = mutableMapOf<Pair<EventId, Int>, ChordToneResult>()
        aligned.toList().forEach { addDetailedRow(out, computedScore, it, chords) }
        return out
    }

    /** Classify every pitch of [row]'s voice event against its matched chord; no chord → no entries. */
    private fun addDetailedRow(
        out: MutableMap<Pair<EventId, Int>, ChordToneResult>,
        computedScore: ComputedScore,
        row: AlignedEvent2<ComputedVoiceEvent, ComputedChordEvent>,
        chords: List<ComputedChordEvent>,
    ) {
        val voice = row.events.first ?: return
        val chord = row.events.second?.chord ?: return
        val (previousId, nextId) = cachedNeighbors[voice.id] ?: (null to null)
        val previous = previousId?.let { computedScore.computedEvents[it] }
        val next = nextId?.let { computedScore.computedEvents[it] }
        val previousChord = previous?.let { chordAt(chords, it.onset)?.chord }
        val nextChord = next?.let { chordAt(chords, it.onset)?.chord }
        for ((pitchIndex, pitchData) in voice.pitchData.withIndex()) {
            val isChordTone = chord.contains(pitchData.pitch)
            out[voice.id to pitchIndex] = ChordToneResult(
                isChordTone = isChordTone,
                nonChordTone = if (isChordTone) null else NonChordToneClassifier.classify(
                    NonChordToneContext(
                        previousPitch = neighborPitch(previous, pitchIndex),
                        pitch = pitchData.pitch,
                        nextPitch = neighborPitch(next, pitchIndex),
                        previousChord = previousChord,
                        chord = chord,
                        nextChord = nextChord,
                        beatWeight = if (voice.onset.beat?.isZero != false) BeatWeight.STRONG else BeatWeight.WEAK,
                        isDiatonic = computedScore.runtime
                            .getKeySignatureAt(voice.onset.measure)
                            .isDiatonic(pitchData.pitch),
                    )
                ),
            )
        }
    }

    private fun neighborPitch(event: ComputedVoiceEvent?, pitchIndex: Int) =
        event?.pitchData?.getOrNull(pitchIndex)?.pitch ?: event?.primaryPitch

    private fun chordAt(chords: List<ComputedChordEvent>, time: TimeCode): ComputedChordEvent? {
        var low = 0
        var high = chords.lastIndex
        var match: ComputedChordEvent? = null
        while (low <= high) {
            val mid = (low + high) ushr 1
            val candidate = chords[mid]
            if (candidate.onset <= time) {
                match = candidate
                low = mid + 1
            } else high = mid - 1
        }
        return match
    }

    private fun rebuildNeighborIndex(computedScore: ComputedScore) {
        val currentTracks = computedScore.runtime.voiceTracks
        val nextNeighbors = cachedNeighbors.toMutableMap()
        val removedTrackIds = cachedVoiceTracks.keys - currentTracks.keys
        if (removedTrackIds.isNotEmpty()) {
            val removedEventIds = cachedVoiceTracks
                .filterKeys { it in removedTrackIds }
                .values.flatMap { it.events.toList() }.mapTo(HashSet()) { it.id }
            nextNeighbors.keys.removeAll(removedEventIds)
        }
        for ((trackId, track) in currentTracks) {
            if (cachedVoiceTracks[trackId] === track) continue
            cachedVoiceTracks[trackId]?.events?.toList()?.forEach { nextNeighbors.remove(it.id) }
            val ids = track.events.toList().map { it.id }
            ids.forEachIndexed { index, id ->
                nextNeighbors[id] = ids.getOrNull(index - 1) to ids.getOrNull(index + 1)
            }
        }
        cachedVoiceTracks = currentTracks
        cachedNeighbors = nextNeighbors
    }

    private fun expandWithNeighbors(
        affectedIds: MutableSet<EventId>,
        neighbors: Map<EventId, Pair<EventId?, EventId?>>,
    ) {
        val adjacent = affectedIds.flatMap { id ->
            neighbors[id]?.let { listOfNotNull(it.first, it.second) }.orEmpty()
        }
        affectedIds.addAll(adjacent)
    }

    private fun storeDetailedResult(result: Map<Pair<EventId, Int>, ChordToneResult>) {
        cachedDetailedResult = result
        cachedResult = result.mapValues { it.value.isChordTone }
    }

    private fun applyNeighborGroupLabels(
        out: MutableMap<Pair<EventId, Int>, ChordToneResult>,
        computedScore: ComputedScore,
        affectedIds: Set<EventId>,
    ) {
        val possibleStarts = linkedSetOf<EventId>()
        affectedIds.forEach { affected ->
            var current: EventId? = affected
            repeat(5) {
                current?.let(possibleStarts::add)
                current = current?.let { cachedNeighbors[it]?.first }
            }
        }
        possibleStarts.forEach startLoop@ { start ->
            val ids = mutableListOf<EventId>()
            var current: EventId? = start
            repeat(5) {
                val id = current ?: return@startLoop
                ids += id
                current = cachedNeighbors[id]?.second
            }
            val pitches = ids.map { computedScore.computedEvents[it]?.primaryPitch ?: return@startLoop }
            val returnsToBase = pitches[0].isEnharmonic(pitches[2]) && pitches[0].isEnharmonic(pitches[4])
            val firstSide = pitches[1].midiNumber - pitches[0].midiNumber
            val secondSide = pitches[3].midiNumber - pitches[0].midiNumber
            val oppositeStepNeighbors = kotlin.math.abs(firstSide) in 1..2 &&
                kotlin.math.abs(secondSide) in 1..2 && firstSide * secondSide < 0
            if (!returnsToBase || !oppositeStepNeighbors) return@startLoop
            listOf(ids[1], ids[3]).forEach memberLoop@ { id ->
                val key = id to 0
                val existing = out[key] ?: return@memberLoop
                if (!existing.isChordTone) {
                    out[key] = existing.copy(
                        nonChordTone = NonChordToneClassification(NonChordToneType.NEIGHBOR_GROUP),
                    )
                }
            }
        }
    }

    private fun windowRows(
        aligned: TimeIndexedList<AlignedEvent2<ComputedVoiceEvent, ComputedChordEvent>>,
        start: TimeCode,
        endExclusive: TimeCode?,
    ): List<AlignedEvent2<ComputedVoiceEvent, ComputedChordEvent>> =
        if (endExclusive == null) aligned.atOrAfter(start)
        else aligned.range(start, endExclusive).filter { it.onset < endExclusive }
}
