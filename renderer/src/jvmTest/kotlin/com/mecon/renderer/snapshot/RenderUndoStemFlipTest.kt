package com.mecon.renderer.snapshot

import com.mecon.api.primitive.*
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.storage.StorageScore
import com.mecon.core.engine.computeScore
import com.mecon.core.engine.computeScoreIncremental
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.render.RenderEngine
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression: deleting a pitch from a wide chord flips the stem direction; undoing must not leave a
 * second (stale) stem. The undo is modelled as the renderer's diff-recovery splice path (null change
 * set). The stale stem arose because the splice bucketed cached elements by hit-box centre X, and a
 * down-stem sits left of its notehead's slot — so it fell into the reused "prefix" while the window
 * regeneration also recreated it. Fixed by bucketing measure-bearing elements by measureNumber.
 */
class RenderUndoStemFlipTest {

    private fun emptyScore() =
        RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("T", TimeSignature.COMMON, KeySignature.C_MAJOR)))

    private fun RuntimeScore.ptId() = pitchTracks.keys.first()
    private fun RuntimeScore.vtId() = voiceTracks.keys.first()
    private fun tc(m: Int, n: Int = 0, d: Int = 1) = TimeCode.of(m, Fraction(n, d))

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

    @Test
    fun undoAfterChordPitchDeleteDoesNotLeaveStaleStem() {
        val font = loadFont() ?: return
        with(font) {
            // Wide chord G3..D5 straddling the middle line → stem UP (lowest note furthest from middle).
            // HALF (not WHOLE) so the chord actually has a stem.
            val base = emptyScore()
                .addChord("c1", tc(1, 0), listOf(Pitch.of(NoteName.G, 3), Pitch.of(NoteName.D, 5)), Duration.HALF)
                .addChord("anchor", tc(3, 0), listOf(Pitch.C4), Duration.HALF)
            // Delete the low note → single D5 above the middle → stem flips DOWN.
            val edited = base.setPitches("c1", listOf(Pitch.of(NoteName.D, 5)))

            val baseComputed = computeScore(base)
            val editInterval = TimeRange(tc(1, 0), tc(2, 0))
            val inc = computeScoreIncremental(baseComputed, edited, editInterval)

            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            engine.render(base)                                  // displayed: wide chord (stem UP)
            engine.renderIncremental(inc.computed, inc.changeSet) // forward delete: stem DOWN

            // Undo: jump back to base. The pipeline passes a null change set (history jump), so the
            // engine recovers the change set by diffing displayed(edited) → base.
            val undoRender = engine.renderIncremental(baseComputed, changeSet = null, expectedPrevious = null)

            val fullBase = RenderEngine(RenderLayoutConfig.DEFAULT).render(base)
            assertTrue(engine.lastRenderWasSpliced(), "expected splice on undo")
            assertCommandMultisetEquivalent(fullBase, undoRender)
        }
    }
}
