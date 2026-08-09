package com.mecon.renderer.layout

import com.mecon.api.computed.ComputedHairpin
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.HairpinStyle
import com.mecon.api.storage.events.HairpinType
import com.mecon.api.storage.events.StaffAttachmentPlacement
import com.mecon.api.storage.events.StorageHairpin
import com.mecon.core.engine.computeScore
import com.mecon.renderer.enums.NoteheadType
import com.mecon.renderer.geometry.HairpinGeometry
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.smufl.BravuraFont
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression guard for the vertical anchoring of a hairpin that crosses a system break.
 *
 * A cross-break span's normalised `[xStart, xEnd]` covers the whole justified band (its end
 * wrapped onto a later line, so end < start before the per-line split). Sampling the local
 * note extent over that band wrongly grabs the lowest note anywhere on the start line —
 * including the dead region LEFT of where the wedge is actually drawn — and pushes the wedge
 * far below its notes (the bug seen on measure 7 of the G-minor prelude). The fix anchors a
 * cross-break span to its true drawn region on the start line instead.
 *
 * The test places a DEEP note far to the left on the start line and a SHALLOW note where the
 * hairpin actually begins, then checks that a cross-break diminuendo lands at the same height
 * as an otherwise-identical in-line diminuendo over the same shallow notes. Before the fix the
 * cross-break one was dragged down to the deep note (~7 staff-spaces lower) and this diverged.
 */
class CrossBreakHairpinAnchorTest {

    private fun loadFontOrNull(): BravuraFont? {
        val base = File("../apps/desktop/src/main/resources/bravura")
        val meta = File(base, "bravuraMetadata.json")
        val names = File(base, "glyphnames.json")
        if (!meta.exists() || !names.exists()) return null
        return BravuraFont.fromJson(meta.readText(), names.readText())
    }

    private val staffTrackId = TrackId("s0")

    // Slot times. X / systemIndex are assigned by hand (below) independently of time order so we
    // can model a justified, system-tagged layout without running the whole pipeline.
    private val tDeep = TimeCode.of(1, Fraction(1, 4))
    private val tStart = TimeCode.of(1, Fraction(2, 4))   // hairpin start; shallow note sits here
    private val tInlineEnd = TimeCode.of(1, Fraction(3, 4)) // in-line reference end, still on system 0
    private val tCrossEnd = TimeCode.of(2, Fraction(0, 4))  // cross-break end, on system 1

    /** A note slot with an explicit X and system tag (events are irrelevant to attachment anchoring). */
    private fun slot(time: TimeCode, x: Float, system: Int) =
        UnifiedTimeSlot(time = time, events = emptyList(), x = StaffSpace(x), systemIndex = system)

    /** A minimal voice-event layout; only its eventId/time/staff matter (extent comes from the lambda). */
    private fun voiceEvent(eventId: String, time: TimeCode) = VoiceEventLayout(
        eventId = EventId(eventId),
        trackId = staffTrackId,
        time = time,
        staffIndex = 0,
        measureNumber = time.measure,
        primary = NoteheadLayout(
            eventId = EventId(eventId),
            trackId = staffTrackId,
            relativeX = StaffSpace.ZERO,
            relativeY = StaffSpace.ZERO,
            staffIndex = 0,
            noteheadType = NoteheadType.WHOLE,
            staffPosition = 0,
        ),
    )

    private fun hairpin(endTime: TimeCode, type: HairpinType = HairpinType.DIMINUENDO) = ComputedHairpin(
        id = EventId("h"),
        time = tStart,
        endTime = endTime,
        staffTrackId = staffTrackId,
        staffIndex = 0,
        placement = StaffAttachmentPlacement.BELOW,
        voiceNumber = null,
        type = type,
        style = HairpinStyle.WEDGE,
        source = StorageHairpin.create(tStart, endTime, type),
    )

    @Test
    fun crossBreakHairpinAnchorsToItsDrawnRegionNotTheLineStart() {
        val font = loadFontOrNull() ?: return // skip when font assets are unavailable

        // Start line (system 0): a DEEP note at x=50 (far left of the wedge), a SHALLOW note at
        // x=200 where the hairpin begins, plus a reference end at x=250. The cross-break end is on
        // system 1 at x=20 (< the start X, which is what marks the wedge as wrapping).
        val timeSlotMap = UnifiedTimeSlotMap(
            listOf(
                slot(tDeep, x = 50f, system = 0),
                slot(tStart, x = 200f, system = 0),
                slot(tInlineEnd, x = 250f, system = 0),
                slot(tCrossEnd, x = 20f, system = 1),
            )
        )

        val deepBottom = StaffSpace(10f)
        val shallowBottom = StaffSpace(3f)
        val extentOf: (VoiceEventLayout) -> Pair<StaffSpace, StaffSpace> = { ve ->
            val bottom = if (ve.eventId == EventId("deep")) deepBottom else shallowBottom
            StaffSpace(2f) to bottom
        }
        val noteExtentIndex = NoteExtentIndex.build(
            listOf(
                voiceEvent("deep", tDeep),
                voiceEvent("shallow", tStart),
                voiceEvent("inlineEnd", tInlineEnd),
                voiceEvent("crossEnd", tCrossEnd),
            ),
            timeSlotMap,
            extentOf = extentOf,
        )
        // Reserve extents (used only for staff spacing, not the drawn Y) — line-global worst case.
        val noteExtents = mapOf(
            0 to mapOf(0 to (StaffSpace(2f) to deepBottom)),
            1 to mapOf(0 to (StaffSpace(2f) to shallowBottom)),
        )

        val computer = StaffAttachmentLayoutComputer(RenderLayoutConfig.DEFAULT)
        val runtime = RuntimeScore.fromStorage(
            StorageScore.create(StorageScore.CreationOptions("", TimeSignature.COMMON, KeySignature.C_MAJOR))
        )
        val baseComputed = computeScore(runtime)

        fun yCenterOf(endTime: TimeCode): Float {
            val computed = baseComputed.copy(staffAttachments = listOf(hairpin(endTime)))
            val placed = with(font) {
                computer.compute(computed, timeSlotMap, noteExtents, noteExtentIndex)
            }.placed.single()
            return placed.geometries.filterIsInstance<HairpinGeometry>().single().yCenter.value
        }

        val crossBreakY = yCenterOf(tCrossEnd)   // end on system 1 → wraps
        val inLineY = yCenterOf(tInlineEnd)       // end on system 0 → anchored locally to shallow note

        // Both sit over the same shallow notes, so they must share a baseline. Before the fix the
        // cross-break wedge was anchored to the deep note (deepBottom − shallowBottom = 7 spaces
        // lower), so this difference would be ≈ 7 instead of ≈ 0.
        assertTrue(
            abs(crossBreakY - inLineY) < 0.01f,
            "cross-break hairpin yCenter ($crossBreakY) should match the in-line reference ($inLineY); " +
                "a large gap means it was dragged down by the deep note left of its drawn region",
        )
    }
}
