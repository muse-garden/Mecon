package com.mecon.core.engine.edit

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeRange
import com.mecon.api.primitive.Tuplet
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimeTieInfo
import com.mecon.api.runtime.tracks.RuntimeVoiceTrack
import com.mecon.api.storage.RenderingProps
import com.mecon.api.storage.events.TupletSpan
import com.mecon.core.engine.edit.EditGeometry.absolute
import com.mecon.core.engine.edit.EditGeometry.advance
import com.mecon.core.engine.edit.EditGeometry.isMeasureLocalSpan
import com.mecon.core.engine.edit.EditGeometry.timeCodeAt
import com.mecon.core.engine.edit.StaffTrackOps.replaceVoice
import com.mecon.core.engine.edit.StaffTrackOps.resolveInsertionVoice
import com.mecon.core.engine.edit.TupletSupport.activeTupletContext
import com.mecon.core.engine.edit.TupletSupport.clearTupletInterval
import com.mecon.core.engine.edit.TupletSupport.fillTupletRests
import com.mecon.core.engine.edit.TupletSupport.smallNoteContextByStartId
import com.mecon.core.engine.edit.TupletSupport.tupletSpecFor
import com.mecon.core.engine.edit.VoiceSpanEditing.clearInterval
import com.mecon.core.engine.edit.VoiceSpanEditing.clearIntervalEvents
import com.mecon.core.engine.edit.VoiceSpanEditing.createVoiceEvent
import com.mecon.core.engine.edit.VoiceSpanEditing.fillGaps
import com.mecon.core.engine.edit.VoiceSpanEditing.fillRange

/**
 * Note/rest insertion (the spec's "音符插入"): clears the target span before inserting, chords
 * onto a same-duration note already at the onset, and ties a note that overflows a barline into
 * the next measure. See `docs/data_model/incremental-update.md` §6.
 */
internal object NoteInsertion {

    fun insertChord(
        runtime: RuntimeScore,
        chord: NoteEditEngine.ChordInsertion,
        policy: NoteEditEngine.InsertionPolicy,
    ): NoteEditEngine.Result? {
        val pitches = chord.pitches
        if (policy == NoteEditEngine.InsertionPolicy.MONODIC && pitches.size > 1) return null
        val template = NoteEditEngine.Insertion(
            voiceTrackId = chord.voiceTrackId,
            start = chord.start,
            duration = chord.duration,
            pitch = pitches.firstOrNull(),
            isRest = chord.isRest,
            trailingTie = chord.trailingTie,
            staffTrackId = chord.staffTrackId,
            voiceNumber = chord.voiceNumber,
            tupletCount = chord.tupletCount,
            beaming = chord.beaming,
            articulations = chord.articulations,
            grace = chord.grace,
        )
        if (chord.isRest || pitches.isEmpty()) {
            return insert(runtime, template.copy(pitch = null, isRest = true), policy)
        }
        if (chord.grace != null) {
            val first = insert(runtime, template.copy(pitch = pitches.first()), policy) ?: return null
            val inserted = first.insertedEventId?.let { id ->
                first.score.getVoiceTrack(chord.voiceTrackId)?.events?.toList()?.firstOrNull { it.id == id }
                    ?: first.score.getAllVoiceEvents().firstOrNull { it.id == id }
            } ?: return first
            var current = first
            for (pitch in pitches.drop(1)) {
                current = insert(
                    current.score,
                    template.copy(start = inserted.onset, pitch = pitch),
                    policy,
                ) ?: current
            }
            return current
        }

        val resolved = resolveInsertionVoice(runtime, template) ?: return null
        val existing = resolved.voice.eventsAt(chord.start).firstOrNull { !it.isRest }
        val effectiveDuration = existing?.duration ?: chord.duration
        var current = resolved.score
        var finalResult: NoteEditEngine.Result? = null
        for ((pitchIndex, pitch) in pitches.withIndex()) {
            val continuingTupletGroup = chord.tupletCount != null && pitchIndex > 0
            val currentDuration = if (continuingTupletGroup) {
                current.voiceTracks[resolved.voice.id]
                    ?.eventsAt(chord.start)
                    ?.firstOrNull { !it.isRest }
                    ?.duration
                    ?: effectiveDuration
            } else {
                effectiveDuration
            }
            val result = insert(
                current,
                template.copy(
                    voiceTrackId = resolved.voice.id,
                    staffTrackId = null,
                    duration = currentDuration,
                    pitch = pitch,
                    trailingTie = chord.trailingTie || pitch.midiNumber in chord.tieOutMidi,
                    // The first pitch creates the group. Remaining chord tones merge into its
                    // first cell inside the now-active tuplet context.
                    tupletCount = if (continuingTupletGroup) null else chord.tupletCount,
                ),
                policy,
            ) ?: continue
            current = result.score
            finalResult = result
        }
        return finalResult?.copy(score = current)
    }

    /**
     * Apply [insertion] to [runtime], returning the new score, or `null` if it was a no-op
     * (unknown voice or an invalid insertion span).
     */
    fun insert(
        runtime: RuntimeScore,
        insertion: NoteEditEngine.Insertion,
        policy: NoteEditEngine.InsertionPolicy,
    ): NoteEditEngine.Result? {
        if (insertion.grace != null) {
            return GraceNoteEditing.insert(runtime, insertion)
        }
        val resolved = resolveInsertionVoice(runtime, insertion) ?: return null
        val baseRuntime = resolved.score
        val voice = resolved.voice
        val startAbs = absolute(baseRuntime, insertion.start)
        if (startAbs < Fraction.ZERO) return null
        // Pointer snapping and imported legacy scores may express a barline as the previous measure's
        // full beat (or, historically, even a beat beyond it). Canonicalise before any measure-local
        // decomposition so `measureLength - beat` can never become negative.
        val normalizedInsertion = insertion.copy(start = timeCodeAt(baseRuntime, startAbs))
        normalizedInsertion.tupletCount?.let { count ->
            return insertTuplet(baseRuntime, voice, normalizedInsertion, count)
        }
        normalizedInsertion.smallNoteAppendStartEventId?.let { startEventId ->
            if (normalizedInsertion.isRest || normalizedInsertion.pitch == null) return null
            val smallNotes = smallNoteContextByStartId(voice, startEventId) ?: return null
            return appendSmallNote(baseRuntime, voice, normalizedInsertion, smallNotes)
        }
        val tupletContext = activeTupletContext(baseRuntime, voice, normalizedInsertion.start)
        val effectiveDuration = if (tupletContext != null && normalizedInsertion.duration.tuplet == null) {
            normalizedInsertion.duration.copy(tuplet = tupletContext.tuplet)
        } else {
            normalizedInsertion.duration
        }
        val effectiveInsertion = normalizedInsertion.copy(duration = effectiveDuration)
        val length = effectiveInsertion.duration.toFraction()
        val end = advance(baseRuntime, effectiveInsertion.start, length)
        if (tupletContext != null && absolute(baseRuntime, end) > absolute(baseRuntime, tupletContext.span.endTimeCode)) {
            return null
        }
        val interval = TimeRange(effectiveInsertion.start, end)

        // Chord case: a note (not a rest) of the *same* duration already sits exactly on this onset
        // → add the pitch to its chord instead of clearing anything.
        if (!effectiveInsertion.isRest && effectiveInsertion.pitch != null) {
            val sameSlot = voice.eventsAt(effectiveInsertion.start)
                .firstOrNull { !it.isRest && it.duration == effectiveInsertion.duration }
            if (sameSlot != null) {
                if (policy == NoteEditEngine.InsertionPolicy.MONODIC) {
                    val newPitchEvent = sameSlot.pitchEvent.copy(
                        pitches = listOf(effectiveInsertion.pitch),
                        articulations =
                            (sameSlot.pitchEvent.articulations + effectiveInsertion.articulations)
                                .distinct(),
                    )
                    val retainedTie = sameSlot.ties.firstOrNull()?.copy(pitchIndex = 0)
                    val ties = when {
                        effectiveInsertion.trailingTie ->
                            listOf(retainedTie ?: RuntimeTieInfo(0, isLetRing = false))
                        retainedTie != null -> listOf(retainedTie)
                        else -> emptyList()
                    }
                    val newEvent = sameSlot.copy(
                        pitchEvent = newPitchEvent,
                        ties = ties,
                    )
                    val newEvents =
                        voice.events.toList().map { if (it.id == sameSlot.id) newEvent else it }
                    return NoteEditEngine.Result(
                        replaceVoice(baseRuntime, voice, newEvents),
                        interval,
                        newEvent.id,
                    )
                }
                data class IndexedPitch(val pitch: com.mecon.api.primitive.Pitch, val oldIndex: Int?)
                val sorted = (
                    sameSlot.pitches.mapIndexed { index, pitch -> IndexedPitch(pitch, index) } +
                        IndexedPitch(effectiveInsertion.pitch, null)
                    ).sortedBy { it.pitch.midiNumber }
                val oldToNew = sorted.mapIndexedNotNull { newIndex, indexed ->
                    indexed.oldIndex?.let { it to newIndex }
                }.toMap()
                val insertedIndex = sorted.indexOfFirst { it.oldIndex == null }
                val newPitchEvent = sameSlot.pitchEvent.copy(
                    pitches = sorted.map { it.pitch },
                    articulations = (sameSlot.pitchEvent.articulations + effectiveInsertion.articulations).distinct(),
                )
                val remappedTies = sameSlot.ties.mapNotNull { tie ->
                    oldToNew[tie.pitchIndex]?.let { tie.copy(pitchIndex = it) }
                }
                val insertedTie = if (effectiveInsertion.trailingTie && insertedIndex >= 0) {
                    listOf(RuntimeTieInfo(insertedIndex, isLetRing = false))
                } else {
                    emptyList()
                }
                val newEvent = sameSlot.copy(
                    pitchEvent = newPitchEvent,
                    ties = (remappedTies + insertedTie).sortedBy { it.pitchIndex },
                )
                val newEvents = voice.events.toList().map { if (it.id == sameSlot.id) newEvent else it }
                return NoteEditEngine.Result(replaceVoice(baseRuntime, voice, newEvents), interval, newEvent.id)
            }
        }

        // Otherwise: carve out the span, then materialise the new note/rest into it.
        val kept = if (tupletContext != null) {
            clearTupletInterval(
                baseRuntime, voice.events.toList(), effectiveInsertion.start, end, tupletContext,
            )
        } else {
            // A small-note member's displayed Duration is not a reliable boundary for ordinary
            // overlap clearing: its group ratio/declared span owns the actual region. In
            // particular, a member can appear to extend beyond the exclusive group endpoint and
            // would then be rebuilt as an ordinary rest/note when inserting at the next measure.
            // Isolate every completed group before carving the normal interval and add its exact
            // immutable events back unchanged.
            val clearStart = absolute(baseRuntime, effectiveInsertion.start)
            val voiceEvents = voice.events.toList()
            val completedSmallNoteRanges = voiceEvents.mapNotNull { event ->
                val span = event.tupletSpan?.takeIf { it.smallNotes } ?: return@mapNotNull null
                val startAbs = absolute(baseRuntime, event.onset)
                val endAbs = absolute(baseRuntime, span.endTimeCode)
                if (endAbs <= clearStart) startAbs to endAbs else null
            }
            val preservedSmallNotes = voiceEvents.filter { event ->
                val onsetAbs = absolute(baseRuntime, event.onset)
                completedSmallNoteRanges.any { (startAbs, endAbs) ->
                    onsetAbs >= startAbs && onsetAbs < endAbs
                }
            }
            clearIntervalEvents(
                baseRuntime,
                voiceEvents.filterNot { candidate ->
                    preservedSmallNotes.any { it.id == candidate.id }
                },
                effectiveInsertion.start,
                end,
            ) + preservedSmallNotes
        }
        val pitches = if (effectiveInsertion.isRest || effectiveInsertion.pitch == null) emptyList()
                      else listOf(effectiveInsertion.pitch)
        val replacementTupletSpan = if (tupletContext != null && effectiveInsertion.start == tupletContext.start.onset) {
            tupletContext.span
        } else {
            null
        }
        val insertRendering = when {
            tupletContext?.span?.smallNotes == true -> RenderingProps.DEFAULT.copy(
                beaming = effectiveInsertion.beaming,
                scale = SMALL_NOTE_SCALE,
            )
            effectiveInsertion.beaming != null -> RenderingProps.DEFAULT.copy(beaming = effectiveInsertion.beaming)
            else -> null
        }
        val inserted = if (effectiveInsertion.duration.tuplet != null) {
            listOf(createVoiceEvent(
                onset = effectiveInsertion.start,
                duration = effectiveInsertion.duration,
                pitches = pitches,
                articulations = effectiveInsertion.articulations,
                isRest = effectiveInsertion.isRest,
                rendering = insertRendering,
                ties = if (effectiveInsertion.trailingTie && pitches.isNotEmpty()) {
                    pitches.indices.map { RuntimeTieInfo(it, isLetRing = false) }
                } else {
                    emptyList()
                },
                tupletSpan = replacementTupletSpan,
            ))
        } else {
            val pieces = fillRange(
                runtime = baseRuntime,
                start = effectiveInsertion.start,
                length = length,
                pitches = pitches,
                articulations = effectiveInsertion.articulations,
                isRest = effectiveInsertion.isRest,
                trailingTie = effectiveInsertion.trailingTie,
            )
            if (insertRendering != null && pieces.isNotEmpty())
                listOf(pieces.first().copy(rendering = insertRendering)) + pieces.drop(1)
            else pieces
        }
        // Keep the touched measures well-formed: pad any holes (before/after the edit, including the
        // remainder of the final measure) with rests so e.g. a lone quarter in 4/4 yields the note
        // plus a dotted-half rest rather than a half-empty bar.
        val endBeat = end.beat ?: Fraction.ZERO
        val fromMeasure = effectiveInsertion.start.measure
        val toMeasure = if (endBeat.isPositive) end.measure else end.measure - 1
        val filled = fillGaps(baseRuntime, kept + inserted, fromMeasure, toMeasure)
        val normalized = if (tupletContext?.span?.smallNotes == true) {
            filled.map { event ->
                if (event.onset >= tupletContext.start.onset && event.onset < tupletContext.span.endTimeCode) {
                    event.copy(
                        rendering = (event.rendering ?: RenderingProps.DEFAULT).copy(
                            scale = SMALL_NOTE_SCALE,
                            hidden = event.isRest,
                        )
                    )
                } else {
                    event
                }
            }
        } else {
            filled
        }
        return NoteEditEngine.Result(
            replaceVoice(baseRuntime, voice, normalized),
            interval,
            inserted.firstOrNull()?.id,
        )
    }

    /**
     * Append at a small-note region's right edge. All entered members are re-spaced with one common
     * tuplet ratio so their freely chosen displayed durations occupy the region's fixed metered
     * length exactly; the region therefore remains open for another append instead of spilling into
     * ordinary rests after its former capacity is exhausted.
     */
    private fun appendSmallNote(
        runtime: RuntimeScore,
        voice: RuntimeVoiceTrack,
        insertion: NoteEditEngine.Insertion,
        context: TupletSupport.TupletContext,
    ): NoteEditEngine.Result? {
        val regionStart = context.start.onset
        val regionEnd = context.span.endTimeCode
        val existing = voice.events.toList()
            .filter { !it.isRest && it.onset >= regionStart && it.onset < regionEnd }
            .sortedBy { it.onset }
        if (existing.isEmpty()) return null

        val displayedDuration = insertion.duration.copy(tuplet = null)
        val newEvent = createVoiceEvent(
            onset = insertion.start,
            duration = displayedDuration,
            pitches = listOf(insertion.pitch ?: return null),
            articulations = insertion.articulations,
            isRest = false,
            rendering = RenderingProps.DEFAULT.copy(
                beaming = insertion.beaming,
                scale = SMALL_NOTE_SCALE,
            ),
            ties = if (insertion.trailingTie) listOf(RuntimeTieInfo(0, isLetRing = false)) else emptyList(),
        )
        val members = existing + newEvent
        val totalDisplayed = members.fold(Fraction.ZERO) { sum, event ->
            sum + event.duration.copy(tuplet = null).toFraction()
        }
        if (!totalDisplayed.isPositive) return null
        val regionLength = absolute(runtime, regionEnd) - absolute(runtime, regionStart)
        val ratio = (regionLength / totalDisplayed).simplified()
        if (!ratio.isPositive) return null
        val tuplet = Tuplet(actual = ratio.denominator, normal = ratio.numerator)
        val span = context.span.copy(
            count = members.size.coerceAtLeast(2),
            beatUnit = displayedDuration.base,
        )

        var onset = regionStart
        val rebuilt = members.mapIndexed { index, event ->
            val duration = event.duration.copy(tuplet = tuplet)
            event.copy(
                onset = onset,
                pitchEvent = event.pitchEvent.copy(onset = onset),
                duration = duration,
                rendering = (event.rendering ?: RenderingProps.DEFAULT).copy(
                    scale = SMALL_NOTE_SCALE,
                    hidden = false,
                ),
                tupletSpan = if (index == 0) span else null,
            ).also {
                onset = advance(runtime, onset, duration.toFraction())
            }
        }
        val regionIds = voice.events.toList()
            .filter { it.onset >= regionStart && it.onset < regionEnd }
            .mapTo(mutableSetOf()) { it.id }
        val updated = voice.events.toList().filter { it.id !in regionIds } + rebuilt
        return NoteEditEngine.Result(
            score = replaceVoice(runtime, voice, updated),
            editInterval = TimeRange(regionStart, regionEnd),
            insertedEventId = newEvent.id,
        )
    }

    private fun insertTuplet(
        runtime: RuntimeScore,
        voice: RuntimeVoiceTrack,
        insertion: NoteEditEngine.Insertion,
        count: Int,
    ): NoteEditEngine.Result? {
        val totalLength = insertion.duration.toFraction()
        val spec = tupletSpecFor(totalLength, count) ?: return null
        val end = advance(runtime, insertion.start, totalLength)
        if (!isMeasureLocalSpan(insertion.start, end)) return null

        val pitches = if (insertion.isRest || insertion.pitch == null) emptyList() else listOf(insertion.pitch)
        val firstDuration = Duration(spec.beatUnit, tuplet = spec.tuplet)
        val firstEvent = createVoiceEvent(
            onset = insertion.start,
            duration = firstDuration,
            pitches = pitches,
            articulations = emptyList(),
            isRest = insertion.isRest,
            rendering = null,
            ties = if (insertion.trailingTie && pitches.isNotEmpty()) {
                listOf(RuntimeTieInfo(0, isLetRing = false))
            } else {
                emptyList()
            },
            tupletSpan = TupletSpan(
                endTimeCode = end,
                count = count,
                beatUnit = spec.beatUnit,
                displayStyle = spec.displayStyle,
            ),
        )
        val restStart = advance(runtime, insertion.start, firstDuration.toFraction())
        val restLength = totalLength - firstDuration.toFraction()
        val rests = fillTupletRests(runtime, restStart, restLength, spec)

        val kept = clearInterval(runtime, voice, insertion.start, end)
        val filled = fillGaps(runtime, kept + firstEvent + rests, insertion.start.measure, insertion.start.measure)
        return NoteEditEngine.Result(
            score = replaceVoice(runtime, voice, filled),
            editInterval = TimeRange(TimeCode.of(insertion.start.measure, Fraction.ZERO), TimeCode.of(insertion.start.measure + 1, Fraction.ZERO)),
            insertedEventId = firstEvent.id,
        )
    }
    private const val SMALL_NOTE_SCALE = 0.7f
}
