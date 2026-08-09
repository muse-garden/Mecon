package com.mecon.renderer.render

import com.mecon.api.computed.ComputedScore
import com.mecon.api.primitive.EventId
import com.mecon.api.storage.events.TupletDisplayStyle
import com.mecon.renderer.enums.StemDirection
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.SlurDirection
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.layout.RestLayout
import com.mecon.renderer.layout.StaffLayoutInfo
import com.mecon.renderer.layout.TupletLayout
import com.mecon.renderer.layout.VoiceEventLayout

/**
 * Resolves [com.mecon.api.computed.ComputedTupletInfo] entries into [TupletLayout]s
 * by locating each tuplet's member notes in the post-layout score and choosing a
 * tilted bracket/slur baseline that hugs the notes.
 *
 * Side selection mirrors stem direction sampled from the first event: Stems UP →
 * bracket goes ABOVE; Stems DOWN → BELOW; rest-only groups default to ABOVE.
 *
 * Vertical placement is per-event rather than per-staff: for each group member the
 * computer takes the "outer" extent (stem tip if it points away from the staff,
 * otherwise notehead edge; beam-adjusted tip if available) and biases the bracket
 * baseline outward by [bracketPadding] / [numberOnlyPadding]. The line slants from
 * the start to the end member's extent and is then lifted as needed so every
 * interior member also clears it.
 *
 * Cross-staff or cross-system spans are not handled specially per the project
 * decision; the renderer just draws between the resolved endpoints.
 */
internal class TupletLayoutComputer(
    private val config: RenderLayoutConfig,
) {

    fun computeTupletLayouts(
        computedScore: ComputedScore,
        query: LayoutQuery,
        stemAdjustments: Map<EventId, StaffSpace> = emptyMap(),
        measureFilter: IntRange? = null,
    ): List<TupletLayout> {
        val result = mutableListOf<TupletLayout>()

        // Windowed passes (paginated splice) range-query only the affected measures; the full pass scans
        // every event. The per-event guard below stays for the range query's inclusive-end overscan.
        val events = if (measureFilter != null) computedScore.eventsInMeasureRange(measureFilter)
        else computedScore.computedEvents.values
        for (event in events) {
            if (measureFilter != null && event.onset.measure !in measureFilter) continue
            val tupletInfo = event.tupletInfo ?: continue
            if (tupletInfo.displayStyle == TupletDisplayStyle.NONE) continue

            val startLayout = query.layoutResult.voiceEventLayouts[tupletInfo.startEventId] ?: continue
            val endLayout = query.layoutResult.voiceEventLayouts[tupletInfo.endEventId] ?: continue

            // Members are events in the same voice within the tuplet's time span. The layout
            // trackId is the staff track, so using it here would mix all voices on that staff.
            val members = query.membersInTimeRange(
                staffIndex = startLayout.staffIndex,
                voiceTrackId = startLayout.voiceTrackId,
                startTime = startLayout.time,
                endTime = endLayout.time,
            )

            val direction = chooseDirection(startLayout.stem?.direction)
            val padding = when (tupletInfo.displayStyle) {
                TupletDisplayStyle.NUMBER_ONLY -> NUMBER_ONLY_PADDING
                else -> BRACKET_PADDING
            }

            val memberData = members.mapNotNull { resolveMember(it, query, direction, stemAdjustments) }
            if (memberData.isEmpty()) continue

            val (start, end) = computeEndpoints(memberData, direction, padding)

            result.add(TupletLayout(
                startEventId = tupletInfo.startEventId,
                endEventId = tupletInfo.endEventId,
                trackId = startLayout.trackId,
                staffIndex = startLayout.staffIndex,
                measureNumber = startLayout.measureNumber,
                start = start,
                end = end,
                direction = direction,
                displayStyle = tupletInfo.displayStyle,
                numberText = tupletInfo.count.toString(),
                numberSize = NUMBER_SIZE,
            ))
        }

        return result
    }

    private fun chooseDirection(stem: StemDirection?): SlurDirection = when (stem) {
        StemDirection.UP -> SlurDirection.ABOVE
        StemDirection.DOWN -> SlurDirection.BELOW
        null -> SlurDirection.ABOVE
    }

    /**
     * Pick endpoints that slant from the first to the last member's outer extent,
     * then nudge both upward/downward so every interior member also clears the line.
     */
    private fun computeEndpoints(
        members: List<MemberData>,
        direction: SlurDirection,
        padding: StaffSpace,
    ): Pair<RelativePoint, RelativePoint> {
        // Push direction: ABOVE → smaller Y on screen, BELOW → larger Y.
        val padSign = if (direction == SlurDirection.ABOVE) -1f else 1f
        val padded = padding.value * padSign

        val first = members.first()
        val last = members.last()
        var startY = first.outerY.value + padded
        var endY = last.outerY.value + padded

        // Slide the line further in the padSign direction if any interior member
        // would otherwise poke past it.
        if (members.size > 2) {
            val sx = first.centerX.value
            val ex = last.centerX.value
            val span = (ex - sx).takeIf { kotlin.math.abs(it) > 0.0001f } ?: 1f
            var shift = 0f
            for (i in 1 until members.size - 1) {
                val m = members[i]
                val t = ((m.centerX.value - sx) / span).coerceIn(0f, 1f)
                val lineY = startY + (endY - startY) * t
                val neededY = m.outerY.value + padded
                val delta = (neededY - lineY) * padSign
                if (delta > shift) shift = delta
            }
            if (shift > 0f) {
                startY += shift * padSign
                endY += shift * padSign
            }
        }

        return RelativePoint(first.leftEdgeX, StaffSpace(startY)) to
            RelativePoint(last.rightEdgeX, StaffSpace(endY))
    }

    private fun resolveMember(
        voiceLayout: VoiceEventLayout,
        query: LayoutQuery,
        direction: SlurDirection,
        stemAdjustments: Map<EventId, StaffSpace>,
    ): MemberData? {
        val slot = query.layoutResult.timeSlotMap.atTime(voiceLayout.time) ?: return null
        val staffLayout = query.staffLayoutFor(voiceLayout) ?: return null

        val noteElement = slot.noteByEvent(voiceLayout.eventId)

        // Prefer aligning the bracket endpoint to the stem X (so e.g. stems-up groups
        // start at the right edge of the first notehead — at the stem — rather than
        // wrapping the entire notehead).
        val stem = voiceLayout.stem
        val anchorX: StaffSpace = when {
            noteElement != null && stem != null ->
                slot.x + noteElement.relativeX + stem.relativeX
            noteElement != null -> {
                val bounds = noteElement.noteBody
                slot.x + noteElement.relativeX + (bounds.leftExtent + bounds.rightExtent) / 2f
            }
            else -> {
                val restX = (voiceLayout.primary as? RestLayout)?.relativeX ?: StaffSpace.ZERO
                slot.x + restX
            }
        }

        val outerY = computeOuterY(voiceLayout, staffLayout, direction, stemAdjustments)

        return MemberData(
            centerX = anchorX,
            leftEdgeX = anchorX,
            rightEdgeX = anchorX,
            outerY = outerY,
        )
    }

    /**
     * Y coordinate of the event's outer edge in score-relative coordinates (smaller
     * = higher on screen). For [SlurDirection.ABOVE] this is the topmost point; for
     * [SlurDirection.BELOW], the bottommost.
     */
    private fun computeOuterY(
        voiceLayout: VoiceEventLayout,
        staffLayout: StaffLayoutInfo,
        direction: SlurDirection,
        stemAdjustments: Map<EventId, StaffSpace>,
    ): StaffSpace {
        val adjusted = stemAdjustments[voiceLayout.eventId]
        val stem = voiceLayout.stem
        if (stem != null) {
            val tipBase = staffLayout.centerY + stem.tipY
            val attachBase = staffLayout.centerY + stem.attachmentY
            // If the beam pass moved the stem tip, prefer the adjusted Y for the tip end.
            val tipY = adjusted ?: tipBase
            return when (direction) {
                SlurDirection.ABOVE -> minOf(tipY, attachBase)
                SlurDirection.BELOW -> maxOf(tipY, attachBase)
            }
        }
        (voiceLayout.primary as? RestLayout)?.let { rest ->
            val restCenter = staffLayout.centerY + rest.relativeY
            return when (direction) {
                SlurDirection.ABOVE -> restCenter - REST_CLEARANCE
                SlurDirection.BELOW -> restCenter + REST_CLEARANCE
            }
        }
        return when (direction) {
            SlurDirection.ABOVE -> staffLayout.contentTopY
            SlurDirection.BELOW -> staffLayout.contentBottomY
        }
    }

    private data class MemberData(
        val centerX: StaffSpace,
        val leftEdgeX: StaffSpace,
        val rightEdgeX: StaffSpace,
        val outerY: StaffSpace,
    )

    companion object {
        /** Gap between the outermost note/stem extent and the bracket / slur baseline. */
        val BRACKET_PADDING = StaffSpace(1.0f)
        /** Smaller gap for NUMBER_ONLY, which sits just outside the beam without a line. */
        val NUMBER_ONLY_PADDING = StaffSpace(0.5f)
        /** Approximate half-height clearance around rest glyphs for tuplet placement. */
        val REST_CLEARANCE = StaffSpace(0.9f)
        /** Approximate visual size of the tuplet number (used for bracket gap sizing). */
        val NUMBER_SIZE = StaffSpace(1.2f)
    }
}
