package com.mecon.renderer.snapshot

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeRange
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.tracks.Clef
import com.mecon.core.engine.computeScore
import com.mecon.core.engine.computeScoreIncremental
import com.mecon.core.engine.edit.ClefEditEngine
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.PageGeometry
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.layout.UnifiedLayoutComputer
import com.mecon.renderer.layout.UnifiedLayoutResult
import com.mecon.renderer.render.IncrementalRenderPath
import com.mecon.renderer.render.RenderEngine
import com.mecon.renderer.smufl.BravuraFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards that a clef change on a line break no longer disables the paginated element splice for the
 * **whole** score. Before the localization fix, any score carrying a courtesy clef at a break
 * ([UnifiedLayoutResult.suppressedClefTimes] / [SystemLayout.lineEndClefs]) forced every edit —
 * anywhere in the document — to re-render all elements (seconds on a large orchestral score). Now the
 * bail is local: only edits touching the coupled break lines fall back to a full render.
 *
 * Two cases, both asserting pixel parity with a from-scratch full render — and both must splice: on the
 * incremental (non-reflow) path notation is unchanged and the partition is identical, so the courtesy clef
 * is constant and is regenerated / reused correctly per system. An edit **far** from the break splices, and
 * an edit **on** the break line splices too (no per-edit bail is needed — clef edits never reach this path).
 */
class ClefBreakSpliceParityTest {

    private val multiSystem = PageGeometry(
        paginated = true,
        lineWidth = StaffSpace(60f),
        pageContentHeight = StaffSpace(4000f),
        paperWidth = StaffSpace(70f),
        paperHeight = StaffSpace(4200f),
        leftMargin = StaffSpace(2f),
        topMargin = StaffSpace(2f),
    )

    private fun emptyScore() =
        RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("", TimeSignature.COMMON, KeySignature.C_MAJOR)))

    private fun RuntimeScore.ptId() = pitchTracks.keys.first()
    private fun RuntimeScore.vtId() = voiceTracks.keys.first()

    private fun RuntimeScore.addNote(tag: String, onset: TimeCode, pitch: Pitch, duration: Duration): RuntimeScore {
        val pe = RuntimePitchEvent(EventId("p-$tag"), onset, listOf(pitch))
        val ve = RuntimeVoiceEvent(EventId(tag), onset, pe, duration)
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

    /** A one-measure edit interval covering all of measure [m]. */
    private fun measureInterval(m: Int) =
        TimeRange(TimeCode.of(m, Fraction(0, 4)), TimeCode.of(m + 1, Fraction(0, 4)))

    /** 40 measures of C4/E4 half-note pairs — enough to break into ≥4 paginated systems. */
    private fun buildBase(): RuntimeScore {
        var s = emptyScore()
        for (m in 1..40) {
            s = s
                .addNote("n_${m}_0", TimeCode.of(m, Fraction(0, 4)), Pitch.C4, Duration.HALF)
                .addNote("n_${m}_1", TimeCode.of(m, Fraction(2, 4)), Pitch.E4, Duration.HALF)
        }
        return s
    }

    private fun layoutOf(score: RuntimeScore, font: BravuraFont): UnifiedLayoutResult =
        with(font) {
            UnifiedLayoutComputer(RenderLayoutConfig.DEFAULT)
                .computeLayout(computeScore(score), score, pageGeometry = multiSystem)
        }

    /**
     * A base score whose clef change lands on a *middle* line break, so system 0 is nowhere near the
     * coupled boundary lines. Returns the score plus the break measure (start of the changed line).
     */
    private fun baseWithMiddleClefBreak(font: BravuraFont): Pair<RuntimeScore, Int> {
        // Build once and reuse: each buildBase() mints fresh track ids, so a staffId taken from one
        // instance would not resolve against a different instance passed to setClef.
        val fresh = buildBase()
        val plain = layoutOf(fresh, font)
        assertTrue(plain.systems.size >= 4, "need ≥4 systems for a clear middle break; got ${plain.systems.size}")
        // A middle system (≥2), so its previous line (the courtesy carrier) is also ≥1 — system 0 stays clear.
        val breakSystemIdx = maxOf(2, plain.systems.size / 2)
        val breakMeasure = plain.systems[breakSystemIdx].measureRange.first

        val staffId = fresh.staffTracks.keys.first()
        val edited = assertNotNull(
            ClefEditEngine.setClef(
                fresh,
                ClefEditEngine.Target(staffId, TimeCode.ofMeasure(breakMeasure)),
                Clef.BASS,
            ),
            "clef edit at the break should apply",
        ).score
        return edited to breakMeasure
    }

    @Test
    fun editFarFromClefBreakSplicesAndMatchesFull() {
        val font = loadFont() ?: return // font assets absent → skip
        with(font) {
            val (base, breakMeasure) = baseWithMiddleClefBreak(font)

            // Sanity: this base really carries a courtesy clef at a break (otherwise the test proves nothing).
            val baseLayout = layoutOf(base, font)
            assertTrue(baseLayout.suppressedClefTimes.isNotEmpty(), "base must have a suppressed break clef")
            assertTrue(baseLayout.systems.any { it.lineEndClefs.isNotEmpty() }, "base must have a courtesy clef")
            assertTrue(breakMeasure > 2, "break must be well past measure 1 for a 'far' edit")

            // Edit a note in measure 1 (system 0) — far from the middle break. Same-width E4→A4.
            val edited = base.editPitch("n_1_1", Pitch.A4)
            val inc = computeScoreIncremental(computeScore(base), edited, measureInterval(1))

            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            engine.render(base, pageGeometry = multiSystem) // caches the displayed frame for the splice
            val incRender = engine.renderIncremental(inc.computed, inc.changeSet, pageGeometry = multiSystem)

            assertEquals(IncrementalRenderPath.INCREMENTAL, engine.lastIncrementalRenderPath(), "layout should be incremental")
            assertTrue(engine.lastRenderWasSpliced(), "edit far from the break must splice, not full-render")

            val fullRender = RenderEngine(RenderLayoutConfig.DEFAULT).render(edited, pageGeometry = multiSystem)
            assertCommandMultisetEquivalent(fullRender, incRender)
        }
    }

    @Test
    fun editOnClefBreakLineSplicesAndMatchesFull() {
        val font = loadFont() ?: return // font assets absent → skip
        with(font) {
            val (base, breakMeasure) = baseWithMiddleClefBreak(font)

            // Edit a note in the break measure itself. The clef is unchanged (pitch-only edit), so the
            // courtesy clef is stable and the affected systems regenerate it correctly — this splices.
            val edited = base.editPitch("n_${breakMeasure}_1", Pitch.A4)
            val inc = computeScoreIncremental(computeScore(base), edited, measureInterval(breakMeasure))

            val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
            engine.render(base, pageGeometry = multiSystem)
            val incRender = engine.renderIncremental(inc.computed, inc.changeSet, pageGeometry = multiSystem)

            assertEquals(IncrementalRenderPath.INCREMENTAL, engine.lastIncrementalRenderPath(), "layout should be incremental")
            assertTrue(engine.lastRenderWasSpliced(), "edit on the break line now splices (clef unchanged → courtesy clef stable)")

            val fullRender = RenderEngine(RenderLayoutConfig.DEFAULT).render(edited, pageGeometry = multiSystem)
            assertCommandMultisetEquivalent(fullRender, incRender)
        }
    }
}
