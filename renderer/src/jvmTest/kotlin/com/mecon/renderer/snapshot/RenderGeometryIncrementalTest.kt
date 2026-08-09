package com.mecon.renderer.snapshot

import com.mecon.api.primitive.*
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeTieInfo
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.storage.StorageScore
import com.mecon.core.engine.computeScore
import com.mecon.core.engine.computeScoreIncremental
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.render.RenderEngine
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Phase 2 of persisted slur / articulation geometry (`docs/data_model/incremental-update.md` §10):
 * editing an **overlay-bearing** score must locally recompute the geometry the edit invalidated while
 * the rest follows its anchors. Two guarantees:
 *
 *  1. **Golden rule (display)** — incrementally rendering the edited overlay score is pixel-equivalent
 *     (sub-pixel multiset) to a full auto render of the edited score. So the affected slur *reshapes*
 *     to the new auto layout (the stale stored shape was pruned), while unaffected entries stay sticky
 *     because their stored shape already equals auto.
 *  2. **Tight reuse** — an edit far from a symbol leaves its overlay entry classified reusable and
 *     reused **by reference** (no recompute, no sub-pixel drift).
 */
class RenderGeometryIncrementalTest {

    private fun emptyScore() =
        RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("T", TimeSignature.COMMON, KeySignature.C_MAJOR)))

    private fun RuntimeScore.ptId() = pitchTracks.keys.first()
    private fun RuntimeScore.vtId() = voiceTracks.keys.first()
    private fun tc(m: Int, n: Int = 0, d: Int = 1) = TimeCode.of(m, Fraction(n, d))

    private fun RuntimeScore.addNote(
        tag: String, onset: TimeCode, pitch: Pitch?, duration: Duration,
        slurStarts: Int = 0, slurEnds: Int = 0,
        ties: List<RuntimeTieInfo> = emptyList(),
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

    /**
     * Two phrasing slurs: A over m1..m2 (n1→n3, intervening n2) and B over m3..m4 (n4→n6, intervening
     * n5). Returns a score WITHOUT overlay — callers fold the captured overlay in themselves.
     */
    private fun twoSlurScore(): RuntimeScore = emptyScore()
        .addNote("n1", tc(1, 0), Pitch.C4, Duration.QUARTER, slurStarts = 1)
        .addNote("n2", tc(1, 1, 4), Pitch.E4, Duration.QUARTER)
        .addNote("n3", tc(2, 0), Pitch.G4, Duration.HALF, slurEnds = 1)
        .addNote("n4", tc(3, 0), Pitch.C4, Duration.QUARTER, slurStarts = 1)
        .addNote("n5", tc(3, 1, 4), Pitch.E4, Duration.QUARTER)
        .addNote("n6", tc(4, 0), Pitch.G4, Duration.HALF, slurEnds = 1)

    /** slurId of the slur opening on [startTag], read from a computed score. */
    private fun slurIdStartingAt(score: RuntimeScore, startTag: String): EventId =
        computeScore(score).slurs.first { it.startEventId == EventId(startTag) }.slurId

    @Test
    fun editingInsideASlurSpanReshapesItToAutoWhileSiblingStaysSticky() {
        val font = loadFont() ?: return
        with(font) {
            val base = twoSlurScore()

            // Capture the auto overlay, fold it into a fresh overlay-bearing score.
            val o0Engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            o0Engine.render(base)
            val overlay = o0Engine.captureGeometry()
            assertNotNull(overlay)
            assertTrue(overlay.slurs.size == 2, "expected both slurs captured")

            val baseOverlay = base.copy(geometry = overlay)
            // Edit n2 (intervening note inside slur A's span) → an accidental, widening m1.
            val editedOverlay = baseOverlay.editPitch("n2", Pitch(6, 1)) // F#4
            val editedNo = base.editPitch("n2", Pitch(6, 1))

            val prev = computeScore(baseOverlay)
            val inc = computeScoreIncremental(prev, editedOverlay, TimeRange(tc(1, 1, 4), tc(1, 2, 4)))

            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            engine.render(baseOverlay)
            val incRender = engine.renderIncremental(inc.computed, inc.changeSet)

            // Classification: slur A stale (reshape), slur B reusable (auto-adjust).
            val inv = engine.lastGeometryInvalidation()
            assertNotNull(inv)
            assertTrue(slurIdStartingAt(base, "n1") in inv.staleSlurs, "slur A reshapes")
            assertTrue(slurIdStartingAt(base, "n4") in inv.reusableSlurs, "slur B follows its anchors")

            // Golden rule: overlay-driven incremental render == full auto render of the edited score.
            val fullAuto = RenderEngine(RenderLayoutConfig.DEFAULT).render(editedNo)
            assertCommandMultisetEquivalent(fullAuto, incRender)
        }
    }

    @Test
    fun editingFarFromASlurReusesItsGeometryByReference() {
        val font = loadFont() ?: return
        with(font) {
            val base = twoSlurScore()
            val o0Engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            o0Engine.render(base)
            val overlay = o0Engine.captureGeometry()
            assertNotNull(overlay)

            val baseOverlay = base.copy(geometry = overlay)
            // Edit n6 (slur B's endpoint, m4) — far enough from slur A (m1..m2) that even the BACK=1
            // recompute window (m3..m4) never reaches it.
            val editedOverlay = baseOverlay.editPitch("n6", Pitch(6, 1))

            val prev = computeScore(baseOverlay)
            val inc = computeScoreIncremental(prev, editedOverlay, TimeRange(tc(4, 0), tc(4, 2, 4)))

            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            engine.render(baseOverlay)
            val seeded = engine.captureGeometry()
            assertNotNull(seeded)
            engine.renderIncremental(inc.computed, inc.changeSet)
            val folded = engine.captureGeometry()
            assertNotNull(folded)

            val slurA = slurIdStartingAt(base, "n1")
            val slurB = slurIdStartingAt(base, "n4")
            val inv = engine.lastGeometryInvalidation()
            assertNotNull(inv)
            assertTrue(slurA in inv.reusableSlurs)
            assertTrue(slurB in inv.staleSlurs, "the edited slur reshapes")

            // The untouched slur A is reused by reference — no recompute, no drift.
            assertSame(seeded.slurs[slurA], folded.slurs[slurA], "far-edit slur reused by reference")
        }
    }

    @Test
    fun geometryOnlyTieDragUsesBoundedIncrementalRender() {
        val font = loadFont() ?: return
        with(font) {
            val base = emptyScore()
                .addNote(
                    "t1", tc(1, 0), Pitch.C4, Duration.HALF,
                    ties = listOf(RuntimeTieInfo(0, false)),
                )
                .addNote("t2", tc(1, 1, 2), Pitch.C4, Duration.HALF)
                .addNote("far", tc(8, 0), Pitch.G4, Duration.WHOLE)
            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            engine.render(base)
            val captured = assertNotNull(engine.captureGeometry())
            val original = captured.ties.getValue(EventId("t1")).single()
            val adjusted = original.copy(
                minApex = original.minApex + 0.75f,
                maxApex = original.minApex + 0.75f,
                manuallyAdjusted = true,
                directionLocked = true,
            )
            val changedGeometry = captured.copy(
                ties = captured.ties + (EventId("t1") to listOf(adjusted)),
            )
            val previous = computeScore(base)
            val changed = previous.copy(runtime = base.copy(geometry = changedGeometry))

            engine.renderIncremental(
                changed,
                com.mecon.api.computed.ComputeChangeSet.forRange(1..1),
            )

            assertTrue(engine.lastRenderWasSpliced(), "a tie handle drag must not trigger full-score render")
            val folded = assertNotNull(engine.captureGeometry())
            assertTrue(
                folded.ties.getValue(EventId("t1")).single().minApex >= adjusted.minApex,
                "the bounded redraw must consume the manually adjusted apex",
            )
        }
    }
}
