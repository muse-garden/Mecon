package com.mecon.core.engine.edit

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeRange
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimeTieInfo
import com.mecon.core.engine.edit.EditGeometry.absolute
import com.mecon.core.engine.edit.EditGeometry.advance
import com.mecon.core.engine.edit.StaffTrackOps.replaceVoice
import com.mecon.core.engine.edit.TupletSupport.activeTupletContext
import com.mecon.core.engine.edit.VoiceSpanEditing.consolidateRests
import com.mecon.core.engine.edit.VoiceSpanEditing.fillRange

/** Note/chord/rest deletion (the spec's "删除音符 / 删除休止符"). */
internal object NoteDeletion {

    /**
     * Apply [deletion] to [runtime], returning the new score, or `null` if it was a no-op (unknown
     * voice or event — e.g. an implicit whole-measure rest with no backing runtime event).
     *
     * Two cases, mirroring the spec's "删除音符 / 删除休止符":
     * - A real subset of a chord's pitches → those noteheads are removed and the event kept (ties on
     *   the surviving pitches are re-indexed; ties on removed pitches are dropped).
     * - Otherwise (whole note, last chord pitch, or a rest) → the event's `[onset, end)` span is
     *   re-filled with rest(s) following the same engraving rule as note insertion ([fillRange]).
     */
    fun delete(runtime: RuntimeScore, deletion: NoteEditEngine.Deletion): NoteEditEngine.Result? {
        val voice = runtime.getVoiceTrack(deletion.voiceTrackId) ?: return null
        val target = voice.events.toList().firstOrNull { it.id == deletion.eventId } ?: return null
        if (target.isGrace) {
            return GraceNoteEditing.delete(runtime, deletion.voiceTrackId, deletion.eventId)
        }

        val start = target.onset
        val end = advance(runtime, start, target.duration.toFraction())
        val interval = TimeRange(start, end)

        // Chord-pitch removal: a *proper* subset of the chord's pitches → keep the event, drop them.
        if (!target.isRest && deletion.pitchIndices != null) {
            val removed = deletion.pitchIndices.filter { it in target.pitches.indices }.toSet()
            if (removed.isNotEmpty() && removed.size < target.pitches.size) {
                val newPitches = target.pitches.filterIndexed { i, _ -> i !in removed }
                // Re-index ties onto the surviving pitches; ties on removed pitches disappear.
                val newTies = target.ties.mapNotNull { tie ->
                    if (tie.pitchIndex in removed) null
                    else RuntimeTieInfo(tie.pitchIndex - removed.count { it < tie.pitchIndex }, tie.isLetRing)
                }
                val newEvent = target.copy(
                    pitchEvent = target.pitchEvent.copy(pitches = newPitches),
                    ties = newTies,
                )
                val newEvents = voice.events.toList().map { if (it.id == target.id) newEvent else it }
                return NoteEditEngine.Result(replaceVoice(runtime, voice, newEvents), interval, newEvent.id)
            }
        }

        // A tuplet member must remain on the tuplet grid. Replacing it in-place with a rest keeps
        // both its displayed duration/actual ratio and (for the first member) the group span.
        if (target.duration.tuplet != null && activeTupletContext(runtime, voice, start) != null) {
            val replacement = target.copy(
                pitchEvent = target.pitchEvent.copy(pitches = emptyList()),
                ties = emptyList(),
            )
            val events = voice.events.toList().map { if (it.id == target.id) replacement else it }
            return NoteEditEngine.Result(replaceVoice(runtime, voice, events), interval, replacement.id)
        }

        // Whole event (note, full chord, or rest) → replace its span with engraved rest(s), then
        // consolidate the rests of the touched measure(s) so neighbouring rests (e.g. those left by
        // earlier deletions in a batch) merge into properly engraved values rather than staying a
        // string of small rests — deleting the middle of a 16th-note run yields 16th+8th+quarter+…,
        // not fourteen 16th rests.
        val rest = fillRange(
            runtime = runtime,
            start = start,
            length = target.duration.toFraction(),
            pitches = emptyList(),
            articulations = emptyList(),
            isRest = true,
            trailingTie = false,
        )
        val kept = voice.events.toList().filter { it.id != target.id }
        val endBeat = end.beat ?: Fraction.ZERO
        val lastMeasure = if (endBeat.isPositive) end.measure else end.measure - 1
        val merged = consolidateRests(runtime, kept + rest, start.measure, lastMeasure)

        // Rests may have been re-expressed beyond the deleted span (a merge reaches adjacent rests),
        // and rests never cross barlines, so report the whole touched measure span for incremental.
        val widened = TimeRange(
            TimeCode.of(start.measure, Fraction.ZERO),
            TimeCode.of(lastMeasure + 1, Fraction.ZERO),
        )
        val absStart = absolute(runtime, start)
        val representative = merged.firstOrNull {
            it.isRest && absolute(runtime, it.onset) <= absStart &&
                absolute(runtime, it.onset) + it.duration.toFraction() > absStart
        }?.id
        return NoteEditEngine.Result(replaceVoice(runtime, voice, merged), widened, representative)
    }
}
