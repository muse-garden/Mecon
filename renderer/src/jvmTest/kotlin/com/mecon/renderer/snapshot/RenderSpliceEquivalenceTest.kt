package com.mecon.renderer.snapshot

import com.mecon.api.primitive.*
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeTieInfo
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.OrnamentKind
import com.mecon.core.engine.computeScore
import com.mecon.core.engine.computeScoreIncremental
import com.mecon.core.engine.edit.ExpressionEditEngine
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.render.RenderEngine
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Element-level incremental render (`docs/data_model/incremental-update.md` §3.2 step 3) verification.
 *
 * The splice reuses cached elements for the prefix, translates the tail by Δ, and regenerates only the
 * window — so its draw-command **order differs** from a full render (staff lines, then prefix, then the
 * regenerated window, then the tail). Command-*order* parity (the §5.2 golden rule used for the layout
 * stage) therefore no longer applies; instead this asserts the two renders are the same **multiset** of
 * draw commands within sub-pixel tolerance, plus equal element count and bounds. It also pins that the
 * splice actually engaged ([RenderEngine.lastRenderWasSpliced]) so the path can't silently rot into a
 * full-render fallback.
 */
class RenderSpliceEquivalenceTest {

    private fun emptyScore() =
        RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("T", TimeSignature.COMMON, KeySignature.C_MAJOR)))

    private fun RuntimeScore.ptId() = pitchTracks.keys.first()
    private fun RuntimeScore.vtId() = voiceTracks.keys.first()
    private fun tc(m: Int, n: Int = 0, d: Int = 1) = TimeCode.of(m, Fraction(n, d))

    private fun RuntimeScore.addNote(
        tag: String, onset: TimeCode, pitch: Pitch?, duration: Duration,
        ties: List<RuntimeTieInfo> = emptyList(), slurStarts: Int = 0, slurEnds: Int = 0,
    ): RuntimeScore {
        val pe = RuntimePitchEvent(EventId("p-$tag"), onset, if (pitch == null) emptyList() else listOf(pitch))
        val ve = RuntimeVoiceEvent(
            EventId(tag), onset, pe, duration,
            ties = ties, slurStarts = slurStarts, slurEnds = slurEnds,
        )
        return addPitchEvent(ptId(), pe).addVoiceEvent(vtId(), ve)
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

    /** Render [base] then [edited] incrementally on the same engine; assert spliced + multiset-equivalent. */
    private fun assertSpliceEquivalent(base: RuntimeScore, edited: RuntimeScore, editInterval: TimeRange) {
        val font = loadFont() ?: return
        with(font) {
            val previous = computeScore(base)
            val inc = computeScoreIncremental(previous, edited, editInterval)

            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            engine.render(base) // baseline → caches rich elements
            val incRender = engine.renderIncremental(inc.computed, inc.changeSet)

            assertTrue(engine.lastRenderWasSpliced(), "expected the element-level splice to engage")

            val fullRender = RenderEngine(RenderLayoutConfig.DEFAULT).render(edited)
            assertCommandMultisetEquivalent(fullRender, incRender)
        }
    }

    /**
     * Render [base] then [edited] incrementally; assert the splice deliberately bailed to a full render
     * (and the output still matches a from-scratch render). Used for cases the continuous splice can't
     * model — e.g. a tie / slur straddling the edit window.
     */
    private fun assertFullRenderFallback(base: RuntimeScore, edited: RuntimeScore, editInterval: TimeRange) {
        val font = loadFont() ?: return
        with(font) {
            val previous = computeScore(base)
            val inc = computeScoreIncremental(previous, edited, editInterval)

            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            engine.render(base)
            val incRender = engine.renderIncremental(inc.computed, inc.changeSet)

            assertFalse(engine.lastRenderWasSpliced(), "expected a full-render fallback, not a splice")

            val fullRender = RenderEngine(RenderLayoutConfig.DEFAULT).render(edited)
            assertCommandMultisetEquivalent(fullRender, incRender)
        }
    }

    @Test
    fun sameWidthPitchEditSplicesAndMatchesFull() {
        val base = emptyScore()
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.QUARTER)
            .addNote("n2", tc(1, 1, 4), Pitch.E4, Duration.QUARTER)
            .addNote("n3", tc(2, 0), Pitch.G4, Duration.HALF)
            .addNote("anchor", tc(3, 0), Pitch.C4, Duration.WHOLE)
        val edited = base.editPitch("n2", Pitch.A4)
        assertSpliceEquivalent(base, edited, TimeRange(tc(1, 1, 4), tc(1, 2, 4)))
    }

    @Test
    fun accidentalWideningSplicesAndMatchesFull() {
        val base = emptyScore()
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.QUARTER)
            .addNote("n2", tc(1, 1, 4), Pitch.E4, Duration.QUARTER)
            .addNote("n3", tc(2, 0), Pitch.G4, Duration.HALF)
            .addNote("anchor", tc(3, 0), Pitch.C4, Duration.WHOLE)
        val edited = base.editPitch("n2", Pitch.of(NoteName.F, 4, Accidental.SHARP))
        assertSpliceEquivalent(base, edited, TimeRange(tc(1, 1, 4), tc(1, 2, 4)))
    }

    @Test
    fun durationEditSplicesAndMatchesFull() {
        val base = emptyScore()
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.QUARTER)
            .addNote("n2", tc(1, 1, 4), Pitch.E4, Duration.QUARTER)
            .addNote("n3", tc(1, 2, 4), Pitch.G4, Duration.HALF)
            .addNote("anchor", tc(3, 0), Pitch.C4, Duration.WHOLE)
        val edited = base.editDuration("n2", Duration.EIGHTH)
        assertSpliceEquivalent(base, edited, TimeRange(tc(1, 1, 4), tc(1, 2, 4)))
    }

    @Test
    fun noteEditWithOrnamentRemainsSpliceableAndMatchesFull() {
        val plain = emptyScore()
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.QUARTER)
            .addNote("n2", tc(1, 1, 4), Pitch.E4, Duration.QUARTER)
            .addNote("n3", tc(2, 0), Pitch.G4, Duration.HALF)
            .addNote("anchor", tc(3, 0), Pitch.C4, Duration.WHOLE)
        val base = assertNotNull(ExpressionEditEngine.addOrnament(
            plain,
            plain.staffTracks.keys.first(),
            EventId("n1"),
            OrnamentKind.TRILL,
        )).score
        val edited = base.editPitch("n2", Pitch.A4)

        assertSpliceEquivalent(base, edited, TimeRange(tc(1, 1, 4), tc(1, 2, 4)))
    }

    @Test
    fun noteInsertionSplicesAndMatchesFull() {
        val base = emptyScore()
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.WHOLE)
            .addNote("anchor", tc(3, 0), Pitch.G4, Duration.WHOLE)
        val edited = base.addNote("n2", tc(2, 0), Pitch.D4, Duration.WHOLE)
        assertSpliceEquivalent(base, edited, TimeRange(tc(2, 0), tc(3, 0)))
    }

    /**
     * Regression: editing a note inside a beamed measure must keep the beam (the continuous splice
     * regenerates the window's beam groups, not just stems/flags). Before the fix the window's
     * [com.mecon.renderer.elements.BeamGroupElement] commands were never emitted, so the incremental
     * render dropped every beam in the edited measure and this multiset comparison failed.
     */
    @Test
    fun beamedMeasureEditKeepsBeams() {
        val base = emptyScore()
            .addNote("n1", tc(1, 0, 8), Pitch.C4, Duration.EIGHTH)
            .addNote("n2", tc(1, 1, 8), Pitch.E4, Duration.EIGHTH)
            .addNote("n3", tc(1, 2, 8), Pitch.G4, Duration.EIGHTH)
            .addNote("n4", tc(1, 3, 8), Pitch.C5, Duration.EIGHTH)
            .addNote("anchor", tc(3, 0), Pitch.C4, Duration.WHOLE)
        val edited = base.editPitch("n2", Pitch.A4)
        assertSpliceEquivalent(base, edited, TimeRange(tc(1, 1, 8), tc(1, 2, 8)))
    }

    /** A tie wholly inside the edit window is regenerated with it; splice still matches a full render. */
    @Test
    fun tieInsideWindowSplicesAndMatchesFull() {
        val base = emptyScore()
            .addNote("t1", tc(1, 0), Pitch.C4, Duration.HALF, ties = listOf(RuntimeTieInfo(0, false)))
            .addNote("t2", tc(1, 2, 4), Pitch.C4, Duration.HALF)
            .addNote("free", tc(2, 0), Pitch.E4, Duration.QUARTER)
            .addNote("anchor", tc(3, 0), Pitch.G4, Duration.WHOLE)
        val edited = base.editPitch("free", Pitch.A4)
        assertSpliceEquivalent(base, edited, TimeRange(tc(2, 0), tc(2, 1, 4)))
    }

    /** A slur wholly inside the edit window is regenerated with it; splice still matches a full render. */
    @Test
    fun slurInsideWindowSplicesAndMatchesFull() {
        val base = emptyScore()
            .addNote("s1", tc(1, 0), Pitch.C4, Duration.QUARTER, slurStarts = 1)
            .addNote("s2", tc(1, 1, 4), Pitch.E4, Duration.QUARTER)
            .addNote("s3", tc(1, 2, 4), Pitch.G4, Duration.HALF, slurEnds = 1)
            .addNote("anchor", tc(3, 0), Pitch.C4, Duration.WHOLE)
        val edited = base.editPitch("s2", Pitch.A4)
        assertSpliceEquivalent(base, edited, TimeRange(tc(1, 1, 4), tc(1, 2, 4)))
    }

    /**
     * A slur whose endpoints straddle the edit window — start in the prefix (measure 1), end in the tail
     * (measure 3), while the edit is in the middle (measure 2) — sits in two different splice zones that
     * shift by different deltas. It can't be rigidly translated nor regenerated as a unit, so the splice
     * must bail to a full render.
     */
    @Test
    fun slurStraddlingWindowFallsBackToFullRender() {
        val base = emptyScore()
            .addNote("s1", tc(1, 0), Pitch.C4, Duration.WHOLE, slurStarts = 1)
            .addNote("mid", tc(2, 0), Pitch.E4, Duration.WHOLE)
            .addNote("s2", tc(3, 0), Pitch.G4, Duration.WHOLE, slurEnds = 1)
            .addNote("anchor", tc(4, 0), Pitch.C4, Duration.WHOLE)
        // Edit the middle note → window = {2}; the slur s1→s2 spans measure 1 (prefix) to measure 3 (tail).
        val edited = base.editPitch("mid", Pitch.A4)
        assertFullRenderFallback(base, edited, TimeRange(tc(2, 0), tc(2, 1, 4)))
    }
}
