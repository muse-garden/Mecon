package com.mecon.renderer.render

import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.renderer.elements.NoteElement
import com.mecon.renderer.enums.StemDirection
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.StaffLayoutInfo
import com.mecon.renderer.layout.UnifiedLayoutResult
import com.mecon.renderer.layout.VoiceEventLayout

/**
 * Outer-edge anchor points for a single notehead, in coordinates relative to its
 * event's anchor (slot X + note relativeX for X, staff center for Y).
 *
 * Superset of the per-computer anchors that previously lived in [TieLayoutComputer],
 * [SlurLayoutComputer], etc. Above-bowed notation attaches at [topY], below-bowed at
 * [bottomY]; ties attach at [leftEdge] / [rightEdge].
 */
data class NoteheadAnchor(
    val leftEdge: StaffSpace,
    val rightEdge: StaffSpace,
    val centerY: StaffSpace,
    val topY: StaffSpace,
    val bottomY: StaffSpace,
)

/**
 * Everything needed to position notation attached to one laid-out event.
 *
 * Resolved exclusively through [LayoutQuery.environment] so render components never
 * touch the global layout structures directly.
 */
data class EventEnvironment(
    val voiceLayout: VoiceEventLayout,
    val slotX: StaffSpace,
    val noteElement: NoteElement,
    val staffLayout: StaffLayoutInfo,
    val stemDirection: StemDirection?,
    /** System (line) this event belongs to. 0 in continuous mode. */
    val systemIndex: Int = 0,
) {
    /** Anchor for the notehead at [pitchIndex], or null if this event has no such notehead. */
    fun notehead(pitchIndex: Int): NoteheadAnchor? {
        val info = noteElement.noteBody.noteheads.find { it.pitchIndex == pitchIndex } ?: return null
        val bounds = info.geometry.bounds
        return NoteheadAnchor(
            leftEdge = bounds.origin.x,
            rightEdge = bounds.origin.x + bounds.width,
            centerY = info.geometry.position.y,
            topY = bounds.origin.y,
            bottomY = bounds.origin.y + bounds.height,
        )
    }
}

/**
 * Single entry point for render components to query the post-layout score.
 *
 * Owns the only indexes into global layout structures (per-event environment, per-staff
 * X order, per-(staff,track) time order). Render functions should depend on this plus
 * their own local element group — never on [UnifiedLayoutResult] traversals directly.
 * This keeps "find what to render" (here) separate from "how to render" (the render
 * functions), which is what makes per-edit local re-rendering possible.
 */
class LayoutQuery(
    val layoutResult: UnifiedLayoutResult,
    val staffLayoutByIndex: Map<Int, StaffLayoutInfo>,
    private val computedScore: ComputedScore,
) {
    /** The computed event for [eventId], if any. */
    fun event(eventId: EventId): ComputedVoiceEvent? = computedScore.getComputedEvent(eventId)

    /** Left/right X edges of a system (line), or null if unknown. Used to clip cross-system spans. */
    fun systemBounds(systemIndex: Int): Pair<StaffSpace, StaffSpace>? =
        layoutResult.systems.firstOrNull { it.systemIndex == systemIndex }
            ?.let { it.lineStartX to it.lineEndX }

    /**
     * Resolve the staff layout for this event's visual system. When the event has no slot (not laid out)
     * the flat single-system map is used; but when the slot resolves to a system that does NOT contain the
     * staff — the staff collapsed out (fully hidden on that line) — this returns null so the event's
     * spanners/glyphs are skipped rather than drawn at the flat/global Y.
     */
    fun staffLayoutFor(voiceLayout: VoiceEventLayout): StaffLayoutInfo? {
        val slot = layoutResult.timeSlotMap.atTime(voiceLayout.time)
            ?: return staffLayoutByIndex[voiceLayout.staffIndex]
        return layoutResult.staffForSystem(slot.systemIndex, voiceLayout.staffIndex)
    }

    /**
     * Resolve the layout environment for [eventId], or null if it isn't laid out
     * (e.g. filtered out, or the event has no notehead in its slot).
     */
    fun environment(eventId: EventId): EventEnvironment? {
        val voiceLayout = layoutResult.voiceEventLayouts[eventId] ?: return null
        val slot = layoutResult.timeSlotMap.atTime(voiceLayout.time) ?: return null
        val noteElement = slot.noteByEvent(eventId) ?: return null
        // Resolve the staff for this event's system so its Y is offset correctly when
        // paginated. Falls back to the flat per-index map for the single-system case.
        val staffLayout = staffLayoutFor(voiceLayout) ?: return null
        return EventEnvironment(
            voiceLayout = voiceLayout,
            slotX = slot.x,
            noteElement = noteElement,
            staffLayout = staffLayout,
            stemDirection = voiceLayout.stem?.direction,
            systemIndex = slot.systemIndex,
        )
    }

    /** Representative X (slot X + note relativeX) of a laid-out voice event, or null for rests/missing. */
    private fun representativeX(voiceLayout: VoiceEventLayout): Float? {
        val slot = layoutResult.timeSlotMap.atTime(voiceLayout.time) ?: return null
        val relX = slot.noteByEvent(voiceLayout.eventId)?.relativeX ?: StaffSpace.ZERO
        return (slot.x + relX).value
    }

    private data class StaffXEntry(val x: Float, val layout: VoiceEventLayout)

    /**
     * Per-system, per-staff note layouts sorted by representative X. Build only the systems actually
     * queried by a slur pass: paginated incremental rendering normally asks for one or two systems, so
     * using the computed-event B+ tree range avoids grouping every voice layout in the score.
     */
    private val bySystemStaffSortedByX = HashMap<Int, Map<Int, List<StaffXEntry>>>()

    private fun staffXIndexForSystem(systemIndex: Int): Map<Int, List<StaffXEntry>> =
        bySystemStaffSortedByX.getOrPut(systemIndex) {
            val system = layoutResult.systems.firstOrNull { it.systemIndex == systemIndex }
            val layouts = if (system != null) {
                computedScore.eventsInMeasureRange(system.measureRange).asSequence()
                    .filter { it.onset.measure in system.measureRange }
                    .mapNotNull { layoutResult.voiceEventLayouts[it.id] }
                    .filter {
                        layoutResult.timeSlotMap.atTime(it.time)?.systemIndex == systemIndex
                    }
                    .toList()
            } else {
                layoutResult.voiceEventLayouts.all().filter {
                    layoutResult.timeSlotMap.atTime(it.time)?.systemIndex == systemIndex
                }
            }
            layouts.groupBy { it.staffIndex }.mapValues { (_, staffLayouts) ->
                staffLayouts.mapNotNull { vl -> representativeX(vl)?.let { StaffXEntry(it, vl) } }
                    .sortedBy { it.x }
            }
        }

    /**
     * Voice events on [staffIndex] in [systemIndex] whose representative X lies within
     * `[xStart - margin, xEnd + margin]`, in X order.
     *
     * The margin keeps the result inclusion-safe versus a precise per-notehead test:
     * a chord notehead can sit up to ~1 staff space either side of the event's
     * representative X, so callers must still filter precisely. Replaces a full
     * `voiceEventLayouts.all()` scan with O(log N + K).
     */
    fun voiceLayoutsOnStaffInXRange(
        systemIndex: Int,
        staffIndex: Int,
        xStart: StaffSpace,
        xEnd: StaffSpace,
        margin: StaffSpace = X_RANGE_MARGIN,
    ): List<VoiceEventLayout> {
        val entries = staffXIndexForSystem(systemIndex)[staffIndex] ?: return emptyList()
        val lo = xStart.value - margin.value
        val hi = xEnd.value + margin.value
        val from = lowerBound(entries, lo)
        val out = ArrayList<VoiceEventLayout>()
        var i = from
        while (i < entries.size && entries[i].x <= hi) {
            out.add(entries[i].layout)
            i++
        }
        return out
    }

    /**
     * Voice events on the given (staff, voice track) whose time code is within
     * `[startTime, endTime]` inclusive, in time order. Used to collect tuplet members.
     */
    fun membersInTimeRange(
        staffIndex: Int,
        voiceTrackId: TrackId,
        startTime: TimeCode,
        endTime: TimeCode,
    ): List<VoiceEventLayout> =
        computedScore.computedEvents.range(startTime, endTime).asSequence()
            .mapNotNull { layoutResult.voiceEventLayouts[it.id] }
            .filter {
                it.staffIndex == staffIndex && it.voiceTrackId == voiceTrackId &&
                    it.time >= startTime && it.time <= endTime
            }
            .sortedBy { it.time }
            .toList()

    /** First index in [entries] (sorted by x) whose x >= [target]. */
    private fun lowerBound(entries: List<StaffXEntry>, target: Float): Int {
        var lo = 0
        var hi = entries.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid].x < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /** Inclusion margin for X-range queries (≈ max notehead half-width + inset). */
        val X_RANGE_MARGIN = StaffSpace(2f)
    }
}
