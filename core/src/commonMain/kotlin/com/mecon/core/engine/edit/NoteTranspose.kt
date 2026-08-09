package com.mecon.core.engine.edit

import com.mecon.api.primitive.DiatonicTranspose
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeRange
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimeTieInfo
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.core.engine.edit.StaffTrackOps.replaceVoice

/**
 * Diatonic transpose of selected notes/chords (the spec's "拖动平移音高"): pitches move by whole
 * diatonic steps and are respelled with the key signature's default accidental, dropping any
 * temporary accidental on the original note.
 */
internal object NoteTranspose {

    /**
     * Transpose the [targets] by [stepDelta] diatonic steps (positive = up), spelling each moved
     * pitch with the **key signature's default accidental** for its new note name — i.e. any
     * temporary accidental on the original note is dropped (the "平移后默认删去临时升降号" rule). Timing
     * and durations are untouched; only pitches change in place.
     *
     * Returns `null` for a no-op (`stepDelta == 0`, no targets, or nothing actually changed —
     * e.g. all targets are rests or unknown events).
     */
    fun transpose(
        runtime: RuntimeScore,
        targets: List<NoteEditEngine.TransposeTarget>,
        stepDelta: Int,
    ): NoteEditEngine.TransposeResult? {
        if (stepDelta == 0 || targets.isEmpty()) return null

        // Clamp the delta so no moved pitch is pushed outside the playable MIDI range (a transpose past
        // the top/bottom would otherwise produce an unplayable note and crash the audio converter).
        val clamped = DiatonicTranspose.clampDelta(movedPitchKeys(runtime, targets), stepDelta)
        if (clamped == 0) return null

        var current = runtime
        val touchedMeasures = mutableSetOf<Int>()
        val movedEvents = ArrayList<NoteEditEngine.MovedEvent>()

        for ((voiceId, voiceTargets) in targets.groupBy { it.voiceTrackId }) {
            val voice = current.getVoiceTrack(voiceId) ?: continue
            // null value = whole event; a present set = only those pitch indices.
            val movedByEvent: Map<EventId, Set<Int>?> =
                voiceTargets.associate { it.eventId to it.pitchIndices?.takeIf { s -> s.isNotEmpty() } }

            var voiceChanged = false
            val newEvents = voice.events.toList().map { event ->
                if (event.id !in movedByEvent || event.isRest) return@map event
                val moved = transposeEvent(current, event, movedByEvent[event.id], clamped)
                    ?: return@map event
                voiceChanged = true
                movedEvents.add(NoteEditEngine.MovedEvent(event.id, moved.movedNewIndices))
                touchedMeasures.add(event.onset.measure)
                moved.event
            }
            if (voiceChanged) current = replaceVoice(current, voice, newEvents)
        }

        if (movedEvents.isEmpty()) return null
        val intervals = touchedMeasures.sorted().map { m ->
            TimeRange(TimeCode.of(m, Fraction.ZERO), TimeCode.of(m + 1, Fraction.ZERO))
        }
        return NoteEditEngine.TransposeResult(current, intervals, movedEvents)
    }

    /** Every (pitch, key) that [targets] would move — used to clamp the step delta into MIDI range. */
    private fun movedPitchKeys(
        runtime: RuntimeScore,
        targets: List<NoteEditEngine.TransposeTarget>,
    ): List<Pair<Pitch, KeySignature>> {
        val out = ArrayList<Pair<Pitch, KeySignature>>()
        for (target in targets) {
            val voice = runtime.getVoiceTrack(target.voiceTrackId) ?: continue
            val event = voice.events.toList().firstOrNull { it.id == target.eventId } ?: continue
            if (event.isRest) continue
            val key = runtime.getKeySignatureAt(event.onset.measure)
            val indices = target.pitchIndices?.takeIf { it.isNotEmpty() }
                ?.filter { it in event.pitches.indices } ?: event.pitches.indices.toList()
            indices.forEach { out.add(event.pitches[it] to key) }
        }
        return out
    }

    /** A transposed event plus the post-sort indices of the noteheads that moved (null = whole event). */
    private data class TransposedEvent(val event: RuntimeVoiceEvent, val movedNewIndices: Set<Int>?)

    /**
     * Apply a diatonic [stepDelta] to the [movedIndices] (null = all pitches) of [event], respelling
     * each moved pitch by the key signature in force at its measure. Returns the new event plus the
     * post-sort indices of the moved noteheads (null when the whole event moved), or null when nothing
     * changed. Chord pitches are re-sorted by sounding pitch; ties on moved pitches are dropped and
     * ties on surviving pitches are re-indexed to the sorted order.
     */
    private fun transposeEvent(
        runtime: RuntimeScore,
        event: RuntimeVoiceEvent,
        movedIndices: Set<Int>?,
        stepDelta: Int,
    ): TransposedEvent? {
        val oldPitches = event.pitches
        if (oldPitches.isEmpty()) return null
        val wholeEvent = movedIndices == null
        val indices = (movedIndices?.filter { it in oldPitches.indices }?.toSet()
            ?: oldPitches.indices.toSet())
        if (indices.isEmpty()) return null

        val key = runtime.getKeySignatureAt(event.onset.measure)
        val newPitches = oldPitches.mapIndexed { i, p ->
            if (i !in indices) p
            else DiatonicTranspose.spell(key, p.diatonicSteps + stepDelta)
        }
        if (newPitches == oldPitches) return null

        // Re-sort by sounding pitch and remember oldIndex → newIndex for tie remapping / re-selection.
        val order = newPitches.indices.sortedBy { newPitches[it].midiNumber }
        val sortedPitches = order.map { newPitches[it] }
        val oldToNew = IntArray(order.size).also { arr ->
            order.forEachIndexed { newIdx, oldIdx -> arr[oldIdx] = newIdx }
        }
        val newTies = event.ties.mapNotNull { tie ->
            when {
                tie.pitchIndex in indices -> null                       // moved pitch: drop its tie
                tie.pitchIndex in oldPitches.indices ->
                    RuntimeTieInfo(oldToNew[tie.pitchIndex], tie.isLetRing)
                else -> null
            }
        }
        // Whole-event move → re-select the event; partial → re-select just the moved noteheads (new idx).
        val movedNewIndices = if (wholeEvent) null else indices.map { oldToNew[it] }.toSet()
        return TransposedEvent(
            event = event.copy(
                pitchEvent = event.pitchEvent.copy(pitches = sortedPitches),
                ties = newTies,
            ),
            movedNewIndices = movedNewIndices,
        )
    }
}
