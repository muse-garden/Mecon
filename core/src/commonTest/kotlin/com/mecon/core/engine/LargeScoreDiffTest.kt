package com.mecon.core.engine

import com.mecon.api.computed.ComputeChangeSet
import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.computeChangeSetBetween
import com.mecon.api.primitive.*
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.state.RenderHint
import com.mecon.api.state.ScoreStateManager
import com.mecon.api.storage.StorageScore
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * On a large score, edits (and their undo) must diff to a **minimal, correct** [ComputeChangeSet] via the
 * persistent event store's structural diff — proving the tree diff backing the renderer's lineage guard /
 * undo path (docs/data_model/computed-event-store.md §7a) really engages rather than degrading to an
 * all-events change. The algorithm-level proof (sub-linear node traversal) lives in
 * `BPlusTreeDiffTest.largeTreeSingleEditTouchesOnlyLocalNodes`; the renderer-output proof lives in
 * `RenderIncrementalParityTest`.
 */
class LargeScoreDiffTest {

    private val MEASURES = 60
    private val EDIT_MEASURE = 30

    private fun id(measure: Int, beat: Int) = EventId("m${measure}b$beat")

    /** A 60-bar 4/4 score with four quarter notes per bar (240 voice events). All-natural (C major). */
    private fun largeScore(): RuntimeScore = buildScore { m, b ->
        listOf(Pitch.C4, Pitch.E4, Pitch.G4, Pitch.E4)[b]
    }

    /** A random 4/4 score: [measures] bars, four quarter slots per bar, each a random natural pitch or rest. */
    private fun randomScore(rng: Random, measures: Int): RuntimeScore {
        val pool = listOf(
            Pitch.C4, Pitch.D4, Pitch.E4, Pitch.F4, Pitch.G4, Pitch.A4, Pitch.B4,
            Pitch.of(NoteName.C, 5), Pitch.of(NoteName.D, 5), Pitch.of(NoteName.C, 3),
        )
        return buildScore(measures) { _, _ -> if (rng.nextInt(10) == 0) null else pool[rng.nextInt(pool.size)] }
    }

    /** Build a [measures]-bar score, filling each of the four quarter slots with [pitchAt] (null = rest). */
    private fun buildScore(measures: Int = MEASURES, pitchAt: (m: Int, b: Int) -> Pitch?): RuntimeScore {
        var score = RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("Big", TimeSignature.COMMON, KeySignature.C_MAJOR)))
        val ptId = score.pitchTracks.keys.first()
        val vtId = score.voiceTracks.keys.first()
        for (m in 1..measures) {
            for (b in 0 until 4) {
                val onset = TimeCode.of(m, Fraction(b, 4))
                val pitch = pitchAt(m, b)
                val pe = RuntimePitchEvent(EventId("p-m${m}b$b"), onset, if (pitch == null) emptyList() else listOf(pitch))
                val ve = RuntimeVoiceEvent(id(m, b), onset, pe, Duration.QUARTER)
                score = score.addPitchEvent(ptId, pe).addVoiceEvent(vtId, ve)
            }
        }
        return score
    }

    /** Replace the note [eventId]'s pitch (null = make it a rest), keeping its event id. */
    private fun RuntimeScore.editNotePitch(eventId: EventId, newPitch: Pitch?): RuntimeScore {
        val vtId = voiceTracks.keys.first()
        val ptId = pitchTracks.keys.first()
        val ve = voiceTracks.getValue(vtId).events.toList().first { it.id == eventId }
        val newPe = ve.pitchEvent.copy(pitches = if (newPitch == null) emptyList() else listOf(newPitch))
        return removeVoiceEvent(vtId, ve.id)
            .removePitchEvent(ptId, ve.pitchEvent.id)
            .addPitchEvent(ptId, newPe)
            .addVoiceEvent(vtId, ve.copy(pitchEvent = newPe))
    }

    /** The half-open interval covering one quarter at (measure, beat). */
    private fun quarterInterval(measure: Int, beat: Int) =
        TimeRange(TimeCode.of(measure, Fraction(beat, 4)), TimeCode.of(measure, Fraction(beat + 1, 4)))

    /** Brute-force diff over the two scores' full event maps, mirroring [computeChangeSetBetween]'s event part. */
    private fun bruteForce(old: ComputedScore, new: ComputedScore): ComputeChangeSet {
        val oldMap = old.computedEvents.values.associateBy { it.id }
        val newMap = new.computedEvents.values.associateBy { it.id }
        val removed = (oldMap.keys - newMap.keys)
        val added = (newMap.keys - oldMap.keys)
        val modified = (oldMap.keys intersect newMap.keys).filterTo(HashSet()) { oldMap.getValue(it) != newMap.getValue(it) }
        val measures = buildList {
            removed.forEach { add(oldMap.getValue(it).onset.measure) }
            added.forEach { add(newMap.getValue(it).onset.measure) }
            modified.forEach { add(oldMap.getValue(it).onset.measure); add(newMap.getValue(it).onset.measure) }
        }
        val affected = if (measures.isEmpty()) IntRange.EMPTY else measures.min()..measures.max()
        return ComputeChangeSet(added, removed, modified, affected)
    }

    @Test
    fun editAndUndoDiffToMinimalChangeSetOnLargeScore() {
        val baseRuntime = largeScore()
        val base = computeScore(baseRuntime)
        assertEquals(MEASURES * 4, base.computedEvents.size, "fixture size")

        val editedId = id(EDIT_MEASURE, 2)
        val editedRuntime = baseRuntime.editNotePitch(editedId, Pitch.A4) // G4 → A4, both natural
        val edited = computeScoreIncremental(base, editedRuntime, quarterInterval(EDIT_MEASURE, 2)).computed

        // Forward (edit) diff: exactly one modified event, one affected measure — even though the
        // incremental compute re-puts a 2-measure window, the unchanged re-puts are == → no tree change,
        // so the diff sees only the genuinely changed event.
        val forward = computeChangeSetBetween(base, edited)
        assertEquals(setOf(editedId), forward.modifiedEvents, "only the edited note is modified")
        assertTrue(forward.addedEvents.isEmpty() && forward.removedEvents.isEmpty(), "no add/remove on a pitch edit")
        assertEquals(EDIT_MEASURE..EDIT_MEASURE, forward.affectedMeasures, "window is the single edited measure")
        assertFalse(forward.notationChanged || forward.structureReflow, "pitch edit is non-structural")
        assertTrue(forward.allowsIncrementalLayout, "should drive an incremental layout")

        // Drive a real edit→undo cycle through the manager and diff the way the render lineage guard does
        // on undo: displayed frame (edited) → restored frame (base).
        val mgr = ScoreStateManager(baseRuntime, base)
        mgr.commitNewState(editedRuntime, edited, RenderHint(base, forward))
        assertSame(edited, mgr.currentState.computedScore, "edit committed")

        mgr.undo()
        assertSame(base, mgr.currentState.computedScore, "undo restores the base computed instance")

        // After undo, the restored score must equal the original *by content*, not merely be the same
        // retained instance — a from-scratch recompute of the original runtime is identical.
        assertEquals(computeScore(baseRuntime).computedEvents, mgr.currentState.computedScore.computedEvents,
            "restored computed content must equal the original")

        val undo = computeChangeSetBetween(edited, mgr.currentState.computedScore)
        assertEquals(setOf(editedId), undo.modifiedEvents, "undo diff is the same single event (now reverted)")
        assertEquals(EDIT_MEASURE..EDIT_MEASURE, undo.affectedMeasures, "undo window is the single edited measure")
        assertTrue(undo.allowsIncrementalLayout, "undo should also render incrementally")
    }

    @Test
    fun randomLargeScoreEditDiffMatchesBruteForce() {
        val rng = Random(20260611)
        repeat(25) {
            val measures = 20 + rng.nextInt(40)
            val baseRuntime = randomScore(rng, measures)
            val base = computeScore(baseRuntime)

            // Pick a random existing note (not a rest) and change it to a different random pitch/rest.
            val notes = baseRuntime.voiceTracks.values.first().events.toList()
            val target = notes[rng.nextInt(notes.size)]
            val pool = listOf<Pitch?>(Pitch.C4, Pitch.D4, Pitch.E4, Pitch.G4, Pitch.B4, null)
            var newPitch = pool[rng.nextInt(pool.size)]
            if (newPitch != null && target.pitches == listOf(newPitch)) newPitch = Pitch.of(NoteName.A, 5)

            val editedRuntime = baseRuntime.editNotePitch(target.id, newPitch)
            val edited = computeScoreIncremental(base, editedRuntime, quarterInterval(target.onset.measure, target.onset.beat?.let { (it.numerator * 4 / it.denominator) } ?: 0)).computed

            val actual = computeChangeSetBetween(base, edited)
            val expected = bruteForce(base, edited)
            assertEquals(expected.addedEvents, actual.addedEvents, "added (measures=$measures)")
            assertEquals(expected.removedEvents, actual.removedEvents, "removed (measures=$measures)")
            assertEquals(expected.modifiedEvents, actual.modifiedEvents, "modified (measures=$measures)")
            assertEquals(expected.affectedMeasures, actual.affectedMeasures, "affectedMeasures (measures=$measures)")
            // Single same-structure note edit → no notation/structure change.
            assertFalse(actual.notationChanged || actual.structureReflow, "non-structural (measures=$measures)")
        }
    }

    @Test
    fun multiStepEditsThenUndoAllRestoreOriginal() {
        val baseRuntime = largeScore()
        val original = computeScore(baseRuntime)
        val mgr = ScoreStateManager(baseRuntime, original)

        // Six edits at distinct, non-adjacent measures (so they never interact), each G4 → A4 at beat 2.
        val editMeasures = listOf(5, 15, 25, 35, 45, 55)
        var runtime = baseRuntime
        var computed = original
        for (m in editMeasures) {
            val eid = id(m, 2)
            val nr = runtime.editNotePitch(eid, Pitch.A4)
            val inc = computeScoreIncremental(computed, nr, quarterInterval(m, 2))
            // Each forward step is itself a minimal one-event change.
            assertEquals(setOf(eid), computeChangeSetBetween(computed, inc.computed).modifiedEvents, "step m$m minimal")
            mgr.commitNewState(nr, inc.computed, RenderHint(computed, inc.changeSet))
            runtime = nr; computed = inc.computed
        }
        val top = computed

        // The render guard, asked to jump straight from the top frame back to the original (a fully
        // coalesced undo), must diff to exactly the six reverted events — proving the tree diff
        // reconstructs an arbitrary multi-step delta in one shot.
        val coalesced = computeChangeSetBetween(top, original)
        assertEquals(editMeasures.map { id(it, 2) }.toSet(), coalesced.modifiedEvents, "coalesced undo touches the six edits")
        assertEquals(editMeasures.min()..editMeasures.max(), coalesced.affectedMeasures, "coalesced undo span")
        assertTrue(coalesced.allowsIncrementalLayout, "coalesced undo is still incremental")

        // Step-by-step undo returns to the original content (and instance).
        repeat(editMeasures.size) { mgr.undo() }
        assertSame(original, mgr.currentState.computedScore, "undo-all restores the original instance")
        assertEquals(original.computedEvents, computeScore(baseRuntime).computedEvents, "restored == fresh recompute")
        assertFalse(mgr.canUndo(), "back at the oldest state")
    }
}
