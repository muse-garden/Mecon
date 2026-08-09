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
import com.mecon.renderer.render.RenderEngine
import com.mecon.renderer.render.RenderResult
import com.mecon.renderer.smufl.BravuraFont
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Landing-2 (render-level) golden-rule tests for **incremental reflow**
 * (docs/renderer/incremental-rendering.md):
 * when an edit moves line breaks, the paginated splicer reuses cached rendered elements for the
 * untouched prefix / converged tail (via [com.mecon.renderer.layout.UnifiedLayoutResult.systemLineage])
 * and regenerates only the re-packed band — and the result must be pixel-identical (order-independent,
 * sub-pixel) to a full render of the edited score.
 */
class IncrementalReflowRenderTest {

    private val multiPage = PageGeometry(
        paginated = true,
        lineWidth = StaffSpace(60f),
        pageContentHeight = StaffSpace(40f),
        paperWidth = StaffSpace(70f),
        paperHeight = StaffSpace(50f),
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

    private val NAT = (0..7).map { Pitch(it, 0) }
    private val SHARP = (0..7).map { Pitch(it, 1) }

    private fun buildBase(measures: Int): RuntimeScore {
        var s = emptyScore()
        for (m in 1..measures) for (k in 0..7) {
            s = s.addNote("n_${m}_$k", TimeCode.of(m, Fraction(k, 8)), NAT[k], Duration.EIGHTH)
        }
        return s
    }

    private fun RuntimeScore.setMeasureSharp(m: Int, sharp: Boolean): RuntimeScore {
        var s = this
        val pitches = if (sharp) SHARP else NAT
        for (k in 0..7) s = s.editPitch("n_${m}_$k", pitches[k])
        return s
    }

    private fun measureInterval(m: Int) =
        TimeRange(TimeCode.of(m, Fraction(0, 8)), TimeCode.of(m + 1, Fraction(0, 8)))

    private data class Outcome(val inc: RenderResult, val spliced: Boolean, val full: RenderResult)

    /** Render [base], incrementally render the whole-measure sharpen/naturalize of [editM], full-render the edit. */
    context(BravuraFont)
    private fun reflowEdit(base: RuntimeScore, editM: Int, sharp: Boolean): Outcome {
        val edited = base.setMeasureSharp(editM, sharp)
        val inc = computeScoreIncremental(computeScore(base), edited, measureInterval(editM))
        val engine = RenderEngine(RenderLayoutConfig.DEFAULT)
        engine.render(base, pageGeometry = multiPage)
        val incR = engine.renderIncremental(inc.computed, inc.changeSet, pageGeometry = multiPage)
        val full = RenderEngine(RenderLayoutConfig.DEFAULT).render(edited, pageGeometry = multiPage)
        return Outcome(incR, engine.lastRenderWasSpliced(), full)
    }

    private fun BravuraFont.systemsOf(rt: RuntimeScore) =
        UnifiedLayoutComputer(RenderLayoutConfig.DEFAULT)
            .computeLayout(computeScore(rt), rt, pageGeometry = multiPage).systems

    @Test
    fun wideningReflowSplicesAndMatchesFull() {
        val font = loadFont() ?: return
        with(font) {
            val base = buildBase(40)
            val editM = systemsOf(base)[1].measureRange.first
            val o = reflowEdit(base, editM, sharp = true)
            assertTrue(o.spliced, "widening reflow must engage the reflow splice, not a full render")
            assertCommandsWithinEps(o.full, o.inc)
        }
    }

    @Test
    fun narrowingReflowSplicesAndMatchesFull() {
        val font = loadFont() ?: return
        with(font) {
            val base0 = buildBase(40)
            val editM = systemsOf(base0)[1].measureRange.first
            val wide = base0.setMeasureSharp(editM, sharp = true) // widened baseline
            val o = reflowEdit(wide, editM, sharp = false)         // naturalize → narrows
            assertTrue(o.spliced, "narrowing reflow must engage the reflow splice")
            assertCommandsWithinEps(o.full, o.inc)
        }
    }

    @Test
    fun reflowAtForcedBreakSplicesAndMatchesFull() {
        val font = loadFont() ?: return
        with(font) {
            val breakM = 16
            val base = buildBase(40).copy(forcedSystemBreaks = setOf(breakM))
            val breakLine = systemsOf(base).indexOfFirst { it.measureRange.first == breakM }
            assertTrue(breakLine >= 3)
            val editM = systemsOf(base)[breakLine - 2].measureRange.first
            val o = reflowEdit(base, editM, sharp = true)
            assertTrue(o.spliced, "reflow bounded by a forced break must splice")
            assertCommandsWithinEps(o.full, o.inc)
        }
    }

    @Test
    fun wideningOnClefBreakLineSplicesAndMatchesFull() {
        val font = loadFont() ?: return
        with(font) {
            // The motivating case: edit a note ON a clef-break line. The reflow splice must reuse the
            // untouched systems and regenerate the moved band + its coupled courtesy clef.
            val base0 = buildBase(40)
            val plain = systemsOf(base0)
            val breakSys = maxOf(2, plain.size / 2)
            val breakMeasure = plain[breakSys].measureRange.first
            val staffId = base0.staffTracks.keys.first()
            val withClef = ClefEditEngine.setClef(
                base0, ClefEditEngine.Target(staffId, TimeCode.ofMeasure(breakMeasure)), Clef.BASS,
            )!!.score
            val o = reflowEdit(withClef, breakMeasure, sharp = true)
            assertTrue(o.spliced, "editing on a clef-break line must engage the reflow splice")
            assertCommandsWithinEps(o.full, o.inc)
        }
    }
}
