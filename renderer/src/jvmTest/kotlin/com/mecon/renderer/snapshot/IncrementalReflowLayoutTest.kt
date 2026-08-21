package com.mecon.renderer.snapshot

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.tracks.Clef
import com.mecon.core.engine.computeScore
import com.mecon.core.engine.edit.ClefEditEngine
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.PageGeometry
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.layout.SystemOrigin
import com.mecon.renderer.layout.UnifiedLayoutComputer
import com.mecon.renderer.layout.UnifiedLayoutResult
import com.mecon.renderer.smufl.BravuraFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Landing-1 (layout-level) guard for **incremental reflow with convergence**
 * (`docs/data_model/incremental-update.md` §3.2). When an edit moves line breaks, the incremental
 * breaker no longer bails to a full solve: it reuses the untouched prefix, re-packs from the line
 * before the edit, and converges back onto the cached tail — emitting a per-system
 * [UnifiedLayoutResult.systemLineage].
 *
 * These tests assert the reflow-incremental **layout** is identical to a full `computeLayout` of the
 * same edited score: same measure→system partition, same justified slot X, same pagination — and that
 * the reflow path was actually taken (lineage present), with the far tail reused (convergence).
 *
 * The layout is re-solved drift-free on reflow (see [UnifiedLayoutComputer]); the perf win is in the
 * render splicer reusing rendered elements via the lineage (Landing 2), not in the X solve.
 */
class IncrementalReflowLayoutTest {

    // Short page height so systems stack across several pages — reflow must re-paginate the moved tail.
    private val multiSystem = PageGeometry(
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

    private fun RuntimeScore.editPitches(tag: String, newPitches: List<Pitch>): RuntimeScore {
        val ve = voiceTracks.getValue(vtId()).events.toList().first { it.id == EventId(tag) }
        val newPe = ve.pitchEvent.copy(pitches = newPitches)
        return removeVoiceEvent(vtId(), EventId(tag))
            .removePitchEvent(ptId(), ve.pitchEvent.id)
            .addPitchEvent(ptId(), newPe)
            .addVoiceEvent(vtId(), ve.copy(pitchEvent = newPe))
    }

    private fun RuntimeScore.editPitch(tag: String, newPitch: Pitch): RuntimeScore =
        editPitches(tag, listOf(newPitch))

    // Eight eighth notes per measure on eight distinct staff steps (C4..C5). The widened form turns each
    // into a compact four-note chromatic cluster. Its collision-avoiding accidental columns make a
    // decisive width bump even though left-side accidental ink no longer leaves trailing slot padding.
    private val NAT = (0..7).map { Pitch(it, 0) }
    private val SHARP_CLUSTERS = (0..7).map { root ->
        (0..3).map { offset -> Pitch(root + offset, 1) }
    }

    private fun buildBase(measures: Int): RuntimeScore {
        var s = emptyScore()
        for (m in 1..measures) {
            for (k in 0..7) {
                s = s.addNote("n_${m}_$k", TimeCode.of(m, Fraction(k, 8)), NAT[k], Duration.EIGHTH)
            }
        }
        return s
    }

    /** Expand all eight notes of [m] into sharp clusters, or restore their natural single pitches. */
    private fun RuntimeScore.setMeasureSharp(m: Int, sharp: Boolean): RuntimeScore {
        var s = this
        for (k in 0..7) {
            val pitches = if (sharp) SHARP_CLUSTERS[k] else listOf(NAT[k])
            s = s.editPitches("n_${m}_$k", pitches)
        }
        return s
    }

    private fun BravuraFont.layoutFull(rt: RuntimeScore): UnifiedLayoutResult =
        UnifiedLayoutComputer(RenderLayoutConfig.DEFAULT)
            .computeLayout(computeScore(rt), rt, pageGeometry = multiSystem)

    private fun BravuraFont.layoutInc(
        edited: RuntimeScore, cached: UnifiedLayoutResult, window: IntRange,
    ): UnifiedLayoutResult =
        UnifiedLayoutComputer(RenderLayoutConfig.DEFAULT)
            .computeLayout(computeScore(edited), edited, pageGeometry = multiSystem, reuseXFrom = cached, reuseWindow = window)

    private fun BravuraFont.layoutEditableContinuous(
        runtime: RuntimeScore,
        cached: UnifiedLayoutResult? = null,
        window: IntRange? = null,
    ): UnifiedLayoutResult =
        UnifiedLayoutComputer(RenderLayoutConfig.DEFAULT.copy(padEmptyMeasures = true))
            .computeLayout(
                computeScore(runtime),
                runtime,
                pageGeometry = PageGeometry.continuous(),
                reuseXFrom = cached,
                reuseWindow = window,
            )

    private fun slotIndex(layout: UnifiedLayoutResult): Map<TimeCode, Pair<Int, Float>> =
        layout.timeSlotMap.all().associate { it.time to (it.systemIndex to it.x.value) }

    /** The reflow and a full solve must agree on partition, justified X and pagination. */
    private fun assertLayoutParity(full: UnifiedLayoutResult, inc: UnifiedLayoutResult) {
        assertEquals(
            full.systems.map { it.measureRange }, inc.systems.map { it.measureRange },
            "measure→system partition must match a full solve",
        )
        assertEquals(
            full.systems.map { it.pageIndex }, inc.systems.map { it.pageIndex },
            "per-system page assignment must match",
        )
        assertEquals(full.pages.size, inc.pages.size, "page count must match")
        val fx = slotIndex(full)
        val ix = slotIndex(inc)
        assertEquals(fx.keys, ix.keys, "same set of time slots")
        for ((time, fv) in fx) {
            val iv = ix.getValue(time)
            assertEquals(fv.first, iv.first, "slot $time system index")
            assertTrue(
                kotlin.math.abs(fv.second - iv.second) < 0.02f,
                "slot $time justified X: full=${fv.second} inc=${iv.second}",
            )
        }
    }

    @Test
    fun wideningEditReflowsAndMatchesFull() {
        val font = loadFont() ?: return
        with(font) {
            val base = buildBase(40)
            val cached = layoutFull(base)
            assertTrue(cached.systems.size >= 4, "need several systems; got ${cached.systems.size}")

            val editM = cached.systems[1].measureRange.first // widen a measure on the 2nd line
            val edited = base.setMeasureSharp(editM, sharp = true)
            val inc = layoutInc(edited, cached, editM..editM)
            val full = layoutFull(edited)

            assertNotNull(inc.systemLineage, "widening edit must take the reflow path")
            // A genuine reflow: the partition actually moved (uniform widths cascade with no slack, so the
            // line count may or may not change — but the measure→line grouping must differ from cached).
            assertTrue(
                cached.systems.map { it.measureRange } != full.systems.map { it.measureRange },
                "widening must move the line partition (cached=${cached.systems.map { it.measureRange }})",
            )
            assertLayoutParity(full, inc)
        }
    }

    @Test
    fun narrowingEditReflowsAndMatchesFull() {
        val font = loadFont() ?: return
        with(font) {
            // Narrowing is the reverse of the (proven) widening: cache a layout with one measure widened,
            // then naturalize it — the measure shrinks, freeing space so a following measure pulls up.
            val base0 = buildBase(40)
            val editM = layoutFull(base0).systems[1].measureRange.first
            val wide = base0.setMeasureSharp(editM, sharp = true)
            val cached = layoutFull(wide)

            val edited = wide.setMeasureSharp(editM, sharp = false) // naturalize → narrows
            val inc = layoutInc(edited, cached, editM..editM)
            val full = layoutFull(edited)

            assertNotNull(inc.systemLineage, "narrowing edit must take the reflow path")
            assertTrue(
                cached.systems.map { it.measureRange } != full.systems.map { it.measureRange },
                "narrowing must move the line partition",
            )
            assertLayoutParity(full, inc)
        }
    }

    @Test
    fun repeatedReflowsPatchOnlyTheEditedMeasureWidth() {
        val font = loadFont() ?: return
        with(font) {
            val base = buildBase(40)
            val cached = layoutFull(base)

            val firstEdit = cached.systems[1].measureRange.first
            val editedOnce = base.setMeasureSharp(firstEdit, sharp = true)
            val firstIncremental = layoutInc(editedOnce, cached, firstEdit..firstEdit)

            val secondEdit = firstIncremental.systems[3].measureRange.first
            val editedTwice = editedOnce.setMeasureSharp(secondEdit, sharp = true)
            val secondIncremental = layoutInc(
                editedTwice, firstIncremental, secondEdit..secondEdit,
            )
            val full = layoutFull(editedTwice)

            assertLayoutParity(full, secondIncremental)
            assertEquals(
                firstIncremental.preBreakMeasureWidths.getValue(firstEdit),
                secondIncremental.preBreakMeasureWidths.getValue(firstEdit),
                "the first edit's cached width must survive a later window patch bit-for-bit",
            )
            assertTrue(
                firstIncremental.preBreakMeasureWidths.getValue(secondEdit) !=
                    secondIncremental.preBreakMeasureWidths.getValue(secondEdit),
                "the second edit must replace its own measure width",
            )
        }
    }

    @Test
    fun repeatedEditsBesideEmptyMeasureDoNotAccumulatePaddingWidth() {
        val font = loadFont() ?: return
        with(font) {
            var runtime = emptyScore()
                .addNote("moving", TimeCode.of(1, Fraction.ZERO), Pitch.C4, Duration.QUARTER)
            var incremental = layoutEditableContinuous(runtime)

            repeat(8) { dragIndex ->
                runtime = runtime.editPitch(
                    "moving",
                    if (dragIndex % 2 == 0) Pitch.D4 else Pitch.C4,
                )
                incremental = layoutEditableContinuous(runtime, incremental, 1..2)
                val full = layoutEditableContinuous(runtime)
                val incrementalX = incremental.preBreakTimeSlotMap.all().associate { it.time to it.x.value }
                val fullX = full.preBreakTimeSlotMap.all().associate { it.time to it.x.value }

                assertEquals(fullX.keys, incrementalX.keys)
                for ((time, expectedX) in fullX) {
                    assertTrue(
                        kotlin.math.abs(expectedX - incrementalX.getValue(time)) < 0.001f,
                        "drag ${dragIndex + 1}, slot $time drifted: full=$expectedX incremental=${incrementalX.getValue(time)}",
                    )
                }
            }
        }
    }

    @Test
    fun reflowReusesPrefixAndConvergesAtForcedBreak() {
        val font = loadFont() ?: return
        with(font) {
            // A forced system break is a firewall: the greedy fold cannot cross it, so a widening cascade
            // stops there and the tail (from the forced break on) is reused — a deterministic convergence,
            // unlike perfectly uniform widths which otherwise cascade to the score end.
            val breakM = 16
            val base = buildBase(40).copy(forcedSystemBreaks = setOf(breakM))
            val cached = layoutFull(base)
            val breakLine = cached.systems.indexOfFirst { it.measureRange.first == breakM }
            assertTrue(breakLine >= 3, "forced break should land a few lines in; got line $breakLine")

            // Edit two lines before the forced break, so there is a non-empty reused prefix AND a reused tail.
            val editLine = breakLine - 2
            val editM = cached.systems[editLine].measureRange.first
            val edited = base.setMeasureSharp(editM, sharp = true)
            val inc = layoutInc(edited, cached, editM..editM)
            val full = layoutFull(edited)
            val lineage = assertNotNull(inc.systemLineage, "widening before a forced break must reflow")

            assertLayoutParity(full, inc)
            assertEquals(SystemOrigin.Reuse(0), lineage[0], "prefix system 0 reused as identity")
            assertTrue(lineage.any { it is SystemOrigin.Regenerate }, "the edited band must be regenerated")
            // Every system at/after the forced break is reused (convergence no later than the firewall).
            for ((idx, sys) in inc.systems.withIndex()) {
                if (sys.measureRange.first >= breakM) {
                    assertTrue(
                        lineage[idx] is SystemOrigin.Reuse,
                        "system $idx (measures ${sys.measureRange}) at/after forced break should be reused",
                    )
                }
            }
            val regen = lineage.count { it is SystemOrigin.Regenerate }
            assertTrue(regen < breakLine, "regenerated band ($regen) must be bounded by the firewall at line $breakLine")
        }
    }

    @Test
    fun wideningOnClefBreakLineReflowsAndMatchesFull() {
        val font = loadFont() ?: return
        with(font) {
            // The original slow case: a clef change lands on a middle line break (courtesy clef + suppressed
            // opening clef), and the edit is ON that break line. The reflow must recompute the courtesy /
            // suppression from the moved partition, identically to a full solve.
            val base0 = buildBase(40)
            val plain = layoutFull(base0)
            val breakSys = maxOf(2, plain.systems.size / 2)
            val breakMeasure = plain.systems[breakSys].measureRange.first
            val staffId = base0.staffTracks.keys.first()
            val withClef = assertNotNull(
                ClefEditEngine.setClef(
                    base0, ClefEditEngine.Target(staffId, TimeCode.ofMeasure(breakMeasure)), Clef.BASS,
                ),
                "clef edit at the break should apply",
            ).score
            val cached = layoutFull(withClef)
            assertTrue(cached.suppressedClefTimes.isNotEmpty(), "base must carry a suppressed break clef")
            assertTrue(cached.systems.any { it.lineEndClefs.isNotEmpty() }, "base must carry a courtesy clef")

            val editM = breakMeasure
            val edited = withClef.setMeasureSharp(editM, sharp = true)
            val inc = layoutInc(edited, cached, editM..editM)
            val full = layoutFull(edited)

            assertNotNull(inc.systemLineage, "editing on the clef-break line must reflow")
            assertLayoutParity(full, inc)
            assertEquals(full.suppressedClefTimes, inc.suppressedClefTimes, "suppressed break clefs must match full")
            assertEquals(
                full.systems.sumOf { it.lineEndClefs.size }, inc.systems.sumOf { it.lineEndClefs.size },
                "courtesy clef count must match full",
            )
        }
    }
}
