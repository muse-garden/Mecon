package com.mecon.renderer.snapshot

import com.mecon.api.computed.ComputedScore
import com.mecon.api.primitive.*
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.tracks.StorageKeySignatureChange
import com.mecon.api.storage.tracks.StoragePitchTrack
import com.mecon.api.storage.tracks.StorageVoiceTrack
import com.mecon.core.engine.computeScore
import com.mecon.core.engine.computeScoreIncremental
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.render.IncrementalRenderPath
import com.mecon.renderer.render.RenderEngine
import com.mecon.renderer.render.RenderResult
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Renderer-side golden rule (docs/renderer/incremental-rendering.md): rendering an
 * incrementally-computed [ComputedScore] via [RenderEngine.renderIncremental] must be pixel-identical
 * to a full [RenderEngine.render] of the same edited runtime — same draw commands, bounds and count.
 */
class RenderIncrementalParityTest {

    private fun emptyScore() =
        RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("T", TimeSignature.COMMON, KeySignature.C_MAJOR)))

    private fun RuntimeScore.ptId() = pitchTracks.keys.first()
    private fun RuntimeScore.vtId() = voiceTracks.keys.first()
    private fun tc(m: Int, n: Int = 0, d: Int = 1) = TimeCode.of(m, Fraction(n, d))

    private fun RuntimeScore.addNote(tag: String, onset: TimeCode, pitch: Pitch?, duration: Duration): RuntimeScore {
        val pe = RuntimePitchEvent(EventId("p-$tag"), onset, if (pitch == null) emptyList() else listOf(pitch))
        val ve = RuntimeVoiceEvent(EventId(tag), onset, pe, duration)
        return addPitchEvent(ptId(), pe).addVoiceEvent(vtId(), ve)
    }

    private fun RuntimeScore.addChord(tag: String, onset: TimeCode, pitches: List<Pitch>, duration: Duration): RuntimeScore {
        val pe = RuntimePitchEvent(EventId("p-$tag"), onset, pitches)
        val ve = RuntimeVoiceEvent(EventId(tag), onset, pe, duration)
        return addPitchEvent(ptId(), pe).addVoiceEvent(vtId(), ve)
    }

    private fun RuntimeScore.setPitches(tag: String, pitches: List<Pitch>): RuntimeScore {
        val ve = voiceTracks.getValue(vtId()).events.toList().first { it.id == EventId(tag) }
        val newPe = ve.pitchEvent.copy(pitches = pitches)
        return removeVoiceEvent(vtId(), EventId(tag))
            .removePitchEvent(ptId(), ve.pitchEvent.id)
            .addPitchEvent(ptId(), newPe)
            .addVoiceEvent(vtId(), ve.copy(pitchEvent = newPe))
    }

    private fun RuntimeScore.editPitch(tag: String, newPitch: Pitch): RuntimeScore {
        val ve = voiceTracks.getValue(vtId()).events.toList().first { it.id == EventId(tag) }
        val newPe = ve.pitchEvent.copy(pitches = listOf(newPitch))
        return removeVoiceEvent(vtId(), EventId(tag))
            .removePitchEvent(ptId(), ve.pitchEvent.id)
            .addPitchEvent(ptId(), newPe)
            .addVoiceEvent(vtId(), ve.copy(pitchEvent = newPe))
    }

    private fun RuntimeScore.editDuration(tag: String, newDuration: Duration): RuntimeScore {
        val ve = voiceTracks.getValue(vtId()).events.toList().first { it.id == EventId(tag) }
        return removeVoiceEvent(vtId(), EventId(tag))
            .addVoiceEvent(vtId(), ve.copy(duration = newDuration))
    }

    /** Grace onset: measure [m], beat [n]/[d], grace component −1 (one grace before the principal). */
    private fun graceTc(m: Int, n: Int = 0, d: Int = 1) = TimeCode.of(m, Fraction(n, d), Fraction(-1, 1))

    /** A single-voice score with one mid-score key-signature change at [changeMeasure]. */
    private fun keyChangeScore(changeMeasure: Int, key: KeySignature): RuntimeScore {
        val base = StorageScore.create(StorageScore.CreationOptions("T", TimeSignature.COMMON, KeySignature.C_MAJOR))
        val storage = base.copy(
            globalTrack = base.globalTrack.copy(
                events = listOf(StorageKeySignatureChange(TimeCode.ofMeasure(changeMeasure), key))
            )
        )
        return RuntimeScore.fromStorage(storage)
    }

    /** A score with two voice tracks sharing one staff (voice 1 = index 0, voice 2 = index 1). */
    private fun twoVoiceScore(): RuntimeScore {
        val base = StorageScore.create(StorageScore.CreationOptions("T", TimeSignature.COMMON, KeySignature.C_MAJOR))
        val staffId = base.staffTracks.keys.first()
        val staff = base.staffTracks.getValue(staffId)
        val pitch2 = StoragePitchTrack.create("V2 Notes")
        val voice2 = StorageVoiceTrack.create("Voice 2", 2, pitch2.id)
        val storage = base.copy(
            pitchTracks = base.pitchTracks + (pitch2.id to pitch2),
            voiceTracks = base.voiceTracks + (voice2.id to voice2),
            staffTracks = base.staffTracks +
                (staffId to staff.copy(voiceTrackIds = staff.voiceTrackIds + voice2.id))
        )
        return RuntimeScore.fromStorage(storage)
    }

    /** Add a note to the [voiceIndex]-th voice track (0-based) and its associated pitch track. */
    private fun RuntimeScore.addNoteToVoice(
        voiceIndex: Int, tag: String, onset: TimeCode, pitch: Pitch, duration: Duration
    ): RuntimeScore {
        val vt = voiceTracks.values.toList()[voiceIndex]
        val pe = RuntimePitchEvent(EventId("p-$tag"), onset, listOf(pitch))
        val ve = RuntimeVoiceEvent(EventId(tag), onset, pe, duration)
        return addPitchEvent(vt.pitchTrackId, pe).addVoiceEvent(vt.id, ve)
    }

    private val numberRegex = Regex("-?\\d+\\.\\d+(?:[eE]-?\\d+)?|-?\\d+")

    /**
     * Compare two rendered values for **sub-pixel** equality: identical non-numeric structure, equal
     * count of numbers, each within [eps]. The incremental layout computes tail X as `cachedX + delta`,
     * which is mathematically equal to a full solve but reorders float ops, so the two differ by float
     * rounding (~3e-5 px) — not a visual difference. [eps] = 0.02 px stays far below any real logic
     * error (a measure-spacing mistake shifts elements by whole staff-spaces).
     */
    private fun assertApprox(expected: Any?, actual: Any?, label: String, eps: Float = 0.02f) {
        val e = expected.toString()
        val a = actual.toString()
        assertEquals(numberRegex.replace(e, "#"), numberRegex.replace(a, "#"), "$label: structure differs")
        val en = numberRegex.findAll(e).map { it.value.toFloat() }.toList()
        val an = numberRegex.findAll(a).map { it.value.toFloat() }.toList()
        assertEquals(en.size, an.size, "$label: number count differs")
        for (j in en.indices) {
            assertTrue(abs(en[j] - an[j]) <= eps, "$label: number $j differs ${en[j]} vs ${an[j]}")
        }
    }

    /** Assert [inc] is pixel-identical (to sub-pixel tolerance) to [full]: count, bounds and commands. */
    private fun assertPixelParity(full: RenderResult, inc: RenderResult) {
        assertEquals(full.elements.size, inc.elements.size, "element count differs")
        assertApprox(full.bounds, inc.bounds, "bounds")
        val fc = full.allCommands()
        val ic = inc.allCommands()
        assertEquals(fc.size, ic.size, "draw command count differs")
        for (i in fc.indices) assertApprox(fc[i], ic[i], "draw command #$i")
    }

    /** Render [edited] both ways and assert pixel parity. */
    private fun assertRenderParity(previous: ComputedScore, edited: RuntimeScore, editInterval: TimeRange) {
        val font = loadFont() ?: return // font assets absent in this environment → skip
        with(font) {
            val inc = computeScoreIncremental(previous, edited, editInterval)
            val incRender = RenderEngine(RenderLayoutConfig.DEFAULT).renderIncremental(inc.computed, inc.changeSet)
            val fullRender = RenderEngine(RenderLayoutConfig.DEFAULT).render(edited)
            assertPixelParity(fullRender, incRender)
        }
    }

    /**
     * Render [base] on an engine (caching its layout + rich elements), then [edited] incrementally on the
     * **same** engine so the incremental layout / element-splice path can fire. Assert the layout took
     * [expectedPath] and that the result is equivalent to a full render of [edited].
     *
     * When the layout is incremental the render goes through the element-level splice, which **reorders**
     * draw commands (reuse prefix / translate tail / regenerate window), so equivalence is checked as an
     * order-independent multiset ([assertCommandMultisetEquivalent]); a FULL fallback preserves order but
     * the multiset still matches, so the same check covers both.
     */
    private fun assertReuseParity(
        base: RuntimeScore,
        edited: RuntimeScore,
        editInterval: TimeRange,
        expectedPath: IncrementalRenderPath,
    ) {
        val font = loadFont() ?: return // font assets absent in this environment → skip
        with(font) {
            val previous = computeScore(base)
            val inc = computeScoreIncremental(previous, edited, editInterval)

            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            engine.render(base) // baseline render → caches the layout + rich elements the splice reuses
            val incRender = engine.renderIncremental(inc.computed, inc.changeSet)

            assertEquals(expectedPath, engine.lastIncrementalRenderPath(), "incremental layout path")

            val fullRender = RenderEngine(RenderLayoutConfig.DEFAULT).render(edited)
            assertCommandMultisetEquivalent(fullRender, incRender)
        }
    }

    /** Same-width pitch edit: window measure re-solved with zero shift, tail copied — pixel-identical. */
    @Test
    fun sameWidthPitchEditIsIncrementalAndMatchesFull() {
        val base = emptyScore()
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.QUARTER)
            .addNote("n2", tc(1, 1, 4), Pitch.E4, Duration.QUARTER)
            .addNote("n3", tc(2, 0), Pitch.G4, Duration.HALF)
            .addNote("anchor", tc(3, 0), Pitch.C4, Duration.WHOLE)
        val edited = base.editPitch("n2", Pitch.A4)
        assertReuseParity(base, edited, TimeRange(tc(1, 1, 4), tc(1, 2, 4)), IncrementalRenderPath.INCREMENTAL)
    }

    /** Accidental-widening edit: the window measure re-solves wider, later measures translate — incremental. */
    @Test
    fun accidentalWideningPitchEditIsIncrementalAndMatchesFull() {
        val base = emptyScore()
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.QUARTER)
            .addNote("n2", tc(1, 1, 4), Pitch.E4, Duration.QUARTER)
            .addNote("n3", tc(2, 0), Pitch.G4, Duration.HALF)
            .addNote("anchor", tc(3, 0), Pitch.C4, Duration.WHOLE)
        // E4 → F#4 adds a sharp glyph; measure 1 re-solves wider, measures 2–3 shift by Δ.
        val edited = base.editPitch("n2", Pitch.of(NoteName.F, 4, Accidental.SHARP))
        assertReuseParity(base, edited, TimeRange(tc(1, 1, 4), tc(1, 2, 4)), IncrementalRenderPath.INCREMENTAL)
    }

    /** Duration edit changes proportional spacing in the window measure; tail translates — incremental. */
    @Test
    fun durationEditIsIncrementalAndMatchesFull() {
        val base = emptyScore()
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.QUARTER)
            .addNote("n2", tc(1, 1, 4), Pitch.E4, Duration.QUARTER)
            .addNote("n3", tc(1, 2, 4), Pitch.G4, Duration.HALF)
            .addNote("anchor", tc(3, 0), Pitch.C4, Duration.WHOLE)
        val edited = base.editDuration("n2", Duration.EIGHTH)
        assertReuseParity(base, edited, TimeRange(tc(1, 1, 4), tc(1, 2, 4)), IncrementalRenderPath.INCREMENTAL)
    }

    /** Inserting a note into an empty measure re-solves that measure and translates the tail — incremental. */
    @Test
    fun noteInsertionIsIncrementalAndMatchesFull() {
        val base = emptyScore()
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.WHOLE)
            .addNote("anchor", tc(3, 0), Pitch.G4, Duration.WHOLE)
        val edited = base.addNote("n2", tc(2, 0), Pitch.D4, Duration.WHOLE)
        assertReuseParity(base, edited, TimeRange(tc(2, 0), tc(3, 0)), IncrementalRenderPath.INCREMENTAL)
    }

    /** Appending past the last measure changes measure count → structural reflow → full layout. */
    @Test
    fun structuralAppendFallsBackToFull() {
        val base = emptyScore()
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.WHOLE)
            .addNote("anchor", tc(3, 0), Pitch.G4, Duration.WHOLE)
        val edited = base.addNote("tail", tc(5, 0), Pitch.E4, Duration.WHOLE)
        assertReuseParity(base, edited, TimeRange(tc(5, 0), tc(6, 0)), IncrementalRenderPath.FULL)
    }

    /**
     * Generalized lineage guard (diff fallback). When no trustworthy change-set hint is available —
     * undo/redo, or a coalesced render that skipped the intermediate frame — [RenderEngine.renderIncremental]
     * recovers the change set by structurally diffing the cached frame against the new computed. Passing
     * `changeSet = null` forces that path; the result must still be incremental and match a full render.
     */
    @Test
    fun diffFallbackMatchesFullAndStaysIncremental() {
        val base = emptyScore()
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.QUARTER)
            .addNote("n2", tc(1, 1, 4), Pitch.E4, Duration.QUARTER)
            .addNote("n3", tc(2, 0), Pitch.G4, Duration.HALF)
            .addNote("anchor", tc(3, 0), Pitch.C4, Duration.WHOLE)
        val edited = base.editPitch("n2", Pitch.A4)
        val font = loadFont() ?: return
        with(font) {
            val inc = computeScoreIncremental(computeScore(base), edited, TimeRange(tc(1, 1, 4), tc(1, 2, 4)))
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            engine.render(base) // caches base as the displayed frame
            // No hint → diff the cached (base) frame against the edited computed.
            val incRender = engine.renderIncremental(inc.computed, changeSet = null, expectedPrevious = null)
            assertEquals(IncrementalRenderPath.INCREMENTAL, engine.lastIncrementalRenderPath(), "diff fallback should be incremental")
            assertCommandMultisetEquivalent(RenderEngine(RenderLayoutConfig.DEFAULT).render(edited), incRender)
        }
    }

    /** Diff fallback over a structural edit (measure count changes) must detect reflow → full layout. */
    @Test
    fun diffFallbackStructuralFallsBackToFull() {
        val base = emptyScore()
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.WHOLE)
            .addNote("anchor", tc(3, 0), Pitch.G4, Duration.WHOLE)
        val edited = base.addNote("tail", tc(5, 0), Pitch.E4, Duration.WHOLE)
        val font = loadFont() ?: return
        with(font) {
            val inc = computeScoreIncremental(computeScore(base), edited, TimeRange(tc(5, 0), tc(6, 0)))
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            engine.render(base)
            val incRender = engine.renderIncremental(inc.computed, changeSet = null, expectedPrevious = null)
            assertEquals(IncrementalRenderPath.FULL, engine.lastIncrementalRenderPath(), "structural diff → full")
            assertCommandMultisetEquivalent(RenderEngine(RenderLayoutConfig.DEFAULT).render(edited), incRender)
        }
    }

    /**
     * The lineage guard across a realistic session: on a **single engine**, render a base, apply a run of
     * incremental edits (lineage hits → trusted hints), then undo all the way back to the original
     * (lineage breaks → diff fallback). Every frame — forward and reverse — must match a full render of
     * that state, and the final restored frame must match the original. This is the end-to-end "guard is
     * correct" test (docs/renderer/incremental-rendering.md / computed-event-store.md).
     */
    @Test
    fun multiStepEditsThenUndoAllMatchFullEachFrame() {
        val base = emptyScore()
            .addNote("a", tc(1, 0), Pitch.C4, Duration.QUARTER)
            .addNote("b", tc(1, 1, 4), Pitch.E4, Duration.QUARTER)
            .addNote("c", tc(2, 0), Pitch.G4, Duration.HALF)
            .addNote("d", tc(3, 0), Pitch.C4, Duration.WHOLE)
            .addNote("e", tc(4, 0), Pitch.E4, Duration.WHOLE)
        // Each edit: (tag, new pitch, edit interval). Same-measure-width pitch edits → stay incremental.
        val edits = listOf(
            Triple("b", Pitch.A4, TimeRange(tc(1, 1, 4), tc(1, 2, 4))),
            Triple("c", Pitch.A4, TimeRange(tc(2, 0), tc(2, 2, 4))),
            Triple("e", Pitch.G4, TimeRange(tc(4, 0), tc(5, 0))),
        )
        val font = loadFont() ?: return
        with(font) {
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)

            // History of (runtime, computed); index 0 = base. Render the base via renderIncremental with no
            // hint so the engine caches base as the *exact* displayed computed (render() would recompute a
            // separate instance and break the trusted-hint lineage for the first edit).
            val runtimes = mutableListOf(base)
            val computeds = mutableListOf(computeScore(base))
            val first = engine.renderIncremental(computeds[0], changeSet = null, expectedPrevious = null)
            assertCommandMultisetEquivalent(RenderEngine(RenderLayoutConfig.DEFAULT).render(base), first)

            // Forward edits: lineage hits (expectedPrevious === cached) → trusted precomputed change set.
            for ((tag, pitch, interval) in edits) {
                val newRuntime = runtimes.last().editPitch(tag, pitch)
                val inc = computeScoreIncremental(computeds.last(), newRuntime, interval)
                val r = engine.renderIncremental(inc.computed, inc.changeSet, expectedPrevious = computeds.last())
                assertCommandMultisetEquivalent(RenderEngine(RenderLayoutConfig.DEFAULT).render(newRuntime), r)
                runtimes.add(newRuntime); computeds.add(inc.computed)
            }

            // Undo all the way back: no hint → diff fallback recovers displayed→target each step.
            for (i in computeds.size - 2 downTo 0) {
                val r = engine.renderIncremental(computeds[i], changeSet = null, expectedPrevious = null)
                assertCommandMultisetEquivalent(RenderEngine(RenderLayoutConfig.DEFAULT).render(runtimes[i]), r)
            }
        }
    }

    /**
     * Undo of a transpose that turned a measure's FIRST note into a cluster (a second → noteheads on
     * both sides of the stem). The undo renders against the cached *cluster* frame via the diff fallback;
     * the first slot's noteheads must be regenerated, not dropped (regression: a bare stem remained
     * because the slot regeneration was keyed by an X-range that the cluster width-change crossed).
     */
    @Test
    fun undoOfClusterAtMeasureStartMatchesFull() {
        // State 0: a plain chord (third, no cluster) at measure 2's first note.
        val state0 = emptyScore()
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.WHOLE)
            .addChord("chord", tc(2, 0), listOf(Pitch.of(NoteName.E, 5), Pitch.of(NoteName.G, 5)), Duration.HALF)
            .addNote("n3", tc(2, 1, 2), Pitch.C4, Duration.HALF)
            .addNote("anchor", tc(3, 0), Pitch.C4, Duration.WHOLE)
        // State 1: the lower note moves up a step (E5→F5) → F5/G5 is a second → cluster.
        val state1 = state0.setPitches("chord", listOf(Pitch.of(NoteName.F, 5), Pitch.of(NoteName.G, 5)))

        val font = loadFont() ?: return
        with(font) {
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            val c0 = computeScore(state0)
            val c1 = computeScoreIncremental(c0, state1, TimeRange(tc(2, 0), tc(2, 1, 2)))

            engine.renderIncremental(c0, changeSet = null, expectedPrevious = null)        // base frame cached
            engine.renderIncremental(c1.computed, c1.changeSet, expectedPrevious = c0)     // forward: form cluster
            val undo = engine.renderIncremental(c0, changeSet = null, expectedPrevious = null) // undo via diff

            assertCommandMultisetEquivalent(RenderEngine(RenderLayoutConfig.DEFAULT).render(state0), undo)
        }
    }

    @Test
    fun pitchEditIsPixelIdenticalToFull() {
        val base = emptyScore()
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.QUARTER)
            .addNote("n2", tc(1, 1, 4), Pitch.E4, Duration.QUARTER)
            .addNote("n3", tc(2, 0), Pitch.G4, Duration.HALF)
            .addNote("anchor", tc(3, 0), Pitch.C4, Duration.WHOLE)
        val previous = with(loadFont() ?: return) { computeScore(base) }
        val edited = base.editPitch("n2", Pitch.A4)
        assertRenderParity(previous, edited, TimeRange(tc(1, 1, 4), tc(1, 2, 4)))
    }

    @Test
    fun insertingNoteIntoEmptyMeasureIsPixelIdenticalToFull() {
        val base = emptyScore()
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.WHOLE)
            .addNote("anchor", tc(3, 0), Pitch.G4, Duration.WHOLE)
        val previous = with(loadFont() ?: return) { computeScore(base) }
        val edited = base.addNote("n2", tc(2, 0), Pitch.D4, Duration.WHOLE)
        assertRenderParity(previous, edited, TimeRange(tc(2, 0), tc(3, 0)))
    }

    // --- §2.5 ⑥ validation fixtures: grace / multi-voice / mid-score key signature -----------------
    // These directly exercise the window-only collect + slot-splice reasoning that the single-voice,
    // grace-free fixtures above leave proven only by construction (see docs/renderer/incremental-rendering.md, `collect`).

    /**
     * A grace note OUTSIDE the re-solve window must be reused verbatim from the cached slot map. A grace
     * carries its principal's measure in `time.measure`, so a window-confined edit elsewhere leaves it
     * untouched — this is the regression guard against windowing collect with a B+ tree range query,
     * which would drop the leading grace (its negative grace component sorts before the downbeat).
     */
    @Test
    fun graceOutsideWindowReusedIncremental() {
        val base = emptyScore()
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.QUARTER)
            .addNote("n2", tc(1, 1, 4), Pitch.E4, Duration.QUARTER)
            .addNote("g", graceTc(2, 0), Pitch.of(NoteName.D, 5), Duration.EIGHTH)
            .addNote("p", tc(2, 0), Pitch.G4, Duration.HALF)
            .addNote("anchor", tc(3, 0), Pitch.C4, Duration.WHOLE)
        val edited = base.editPitch("n2", Pitch.A4) // same-width edit confined to measure 1
        assertReuseParity(base, edited, TimeRange(tc(1, 1, 4), tc(1, 2, 4)), IncrementalRenderPath.INCREMENTAL)
    }

    /** A grace note INSIDE the re-solve window must be rebuilt by the window-only collect, not dropped. */
    @Test
    fun graceInsideWindowRebuiltIncremental() {
        val base = emptyScore()
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.WHOLE)
            .addNote("g", graceTc(2, 0), Pitch.of(NoteName.D, 5), Duration.EIGHTH)
            .addNote("p", tc(2, 0), Pitch.G4, Duration.HALF)
            .addNote("p2", tc(2, 1, 2), Pitch.E4, Duration.HALF)
            .addNote("anchor", tc(3, 0), Pitch.C4, Duration.WHOLE)
        val edited = base.editPitch("p2", Pitch.F4) // same-width edit in measure 2 (window holds the grace)
        assertReuseParity(base, edited, TimeRange(tc(2, 1, 2), tc(2, 3, 4)), IncrementalRenderPath.INCREMENTAL)
    }

    /**
     * A key-signature change OUTSIDE the window: the window-only collect never emits it (it advances the
     * clef/key context but only emits window elements), yet the cached slot map carries it — the splice
     * must keep it. Guards structural-element reuse across the window boundary.
     */
    @Test
    fun keySignatureChangeOutsideWindowReusedIncremental() {
        val base = keyChangeScore(3, KeySignature.D_MAJOR)
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.QUARTER)
            .addNote("n2", tc(1, 1, 4), Pitch.E4, Duration.QUARTER)
            .addNote("n3", tc(2, 0), Pitch.G4, Duration.HALF)
            .addNote("n4", tc(3, 0), Pitch.A4, Duration.HALF)
            .addNote("anchor", tc(4, 0), Pitch.C4, Duration.WHOLE)
        val edited = base.editPitch("n2", Pitch.A4) // same-width edit confined to measure 1
        assertReuseParity(base, edited, TimeRange(tc(1, 1, 4), tc(1, 2, 4)), IncrementalRenderPath.INCREMENTAL)
    }

    /** A key-signature change INSIDE the window must be rebuilt by the window-only collect. */
    @Test
    fun keySignatureChangeInsideWindowRebuiltIncremental() {
        val base = keyChangeScore(3, KeySignature.D_MAJOR)
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.WHOLE)
            .addNote("n2", tc(2, 0), Pitch.G4, Duration.WHOLE)
            .addNote("n3", tc(3, 0), Pitch.A4, Duration.QUARTER)
            .addNote("n3b", tc(3, 1, 4), Pitch.B4, Duration.QUARTER)
            .addNote("anchor", tc(4, 0), Pitch.C4, Duration.WHOLE)
        val edited = base.editPitch("n3b", Pitch.G4) // same-width edit in measure 3 (window holds the key change)
        assertReuseParity(base, edited, TimeRange(tc(3, 1, 4), tc(3, 2, 4)), IncrementalRenderPath.INCREMENTAL)
    }

    /**
     * Two voices on one staff: editing one voice's note re-solves the window measure, whose window-only
     * collect must rebuild BOTH voices (measureVoices completeness) — otherwise the untouched voice's
     * notes in that measure would vanish from the incremental render.
     */
    @Test
    fun multiVoiceEditIsIncrementalAndMatchesFull() {
        val base = twoVoiceScore()
            .addNoteToVoice(0, "v1a", tc(1, 0), Pitch.G4, Duration.QUARTER)
            .addNoteToVoice(0, "v1b", tc(1, 1, 4), Pitch.A4, Duration.QUARTER)
            .addNoteToVoice(1, "v2a", tc(1, 0), Pitch.C4, Duration.QUARTER)
            .addNoteToVoice(1, "v2b", tc(1, 1, 4), Pitch.D4, Duration.QUARTER)
            .addNoteToVoice(0, "v1c", tc(2, 0), Pitch.G4, Duration.HALF)
            .addNoteToVoice(1, "v2c", tc(2, 0), Pitch.C4, Duration.HALF)
            .addNoteToVoice(0, "anchor", tc(3, 0), Pitch.G4, Duration.WHOLE)
        val edited = base.editPitch("v1b", Pitch.B4) // same-width edit of voice 1 in measure 1
        assertReuseParity(base, edited, TimeRange(tc(1, 1, 4), tc(1, 2, 4)), IncrementalRenderPath.INCREMENTAL)
    }
}
