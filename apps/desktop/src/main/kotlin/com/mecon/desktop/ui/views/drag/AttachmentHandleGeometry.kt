package com.mecon.desktop.ui.views.drag

import com.mecon.api.computed.ComputedHairpin
import com.mecon.api.computed.ComputedOctaveShift
import com.mecon.api.computed.ComputedOrnamentMark
import com.mecon.api.computed.ComputedStaffAttachment
import com.mecon.api.computed.ComputedTempoKeyframe
import com.mecon.api.interaction.StaffAttachmentSection
import com.mecon.api.primitive.TimeCode
import com.mecon.api.storage.AttachmentGeometry
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.render.DrawGlyph
import com.mecon.renderer.render.DrawLine
import com.mecon.renderer.render.DrawText
import com.mecon.renderer.render.RenderCommand
import com.mecon.renderer.render.RenderElement
import com.mecon.renderer.render.RenderResult

/** Radius (in absolute px, before zoom) within which a pointer grabs a span endpoint, not its body. */
internal const val ATTACHMENT_CONTROL_HIT_RADIUS = 8f

/** The end of a span attachment, or null for point attachments. Spans own two anchors, not one. */
internal val ComputedStaffAttachment.spanEndTime: TimeCode?
    get() = when (this) {
        is ComputedHairpin -> endTime
        is ComputedOctaveShift -> endTime
        is ComputedOrnamentMark -> endTime
        is ComputedTempoKeyframe -> nextTime.takeIf { isGradual }
        else -> null
    }

/** True when the attachment occupies a time range and therefore exposes start/end/body handles. */
internal val ComputedStaffAttachment.isSpan: Boolean
    get() = this is ComputedHairpin ||
        this is ComputedOctaveShift ||
        (this is ComputedOrnamentMark && endTime != null) ||
        (this is ComputedTempoKeyframe && isGradual)

internal fun deriveAttachmentGeometry(
    result: RenderResult,
    section: StaffAttachmentSection,
    element: RenderElement,
): AttachmentGeometry? {
    val attachment = section.attachment
    val startPos = result.timeCodePositions[attachment.time] ?: return null
    val startAnchor = result.transformerSnapshot.toRelative(
        AbsolutePoint(Pixels(startPos.x), element.center.y)
    )
    val origin = result.transformerSnapshot.toRelative(element.hitBox.origin)
    val center = result.transformerSnapshot.toRelative(element.center)
    val system = result.spatialIndex.allSystems().firstOrNull { it.systemIndex == element.systemIndex }
    val staffCenter = system?.staffRegions?.firstOrNull { it.staffIndex == attachment.staffIndex }?.centerY?.value ?: 0f
    val endDx = attachment.spanEndTime?.let { time ->
        val pos = result.timeCodePositions[time] ?: return@let null
        val end = result.transformerSnapshot.toRelative(
            AbsolutePoint(element.hitBox.bottomRight.x, element.center.y)
        )
        val anchor = result.transformerSnapshot.toRelative(
            AbsolutePoint(Pixels(pos.x), element.center.y)
        )
        end.x.value - anchor.x.value
    }
    return AttachmentGeometry(
        startDx = origin.x.value - startAnchor.x.value,
        startDy = center.y.value - staffCenter,
        endDx = endDx,
        endDy = endDx?.let { center.y.value - staffCenter },
    )
}

/**
 * Warp an attachment's existing draw commands between transient endpoint positions. Point dynamics
 * translate as one unit; line/text attachment commands interpolate the two endpoint displacements.
 */
internal fun warpAttachmentCommands(
    commands: List<RenderCommand>,
    originalStart: AbsolutePoint,
    originalEnd: AbsolutePoint?,
    currentStart: AbsolutePoint,
    currentEnd: AbsolutePoint?,
): List<RenderCommand> {
    fun warp(point: AbsolutePoint): AbsolutePoint {
        val end0 = originalEnd
        val end1 = currentEnd
        val fraction = if (end0 == null || end1 == null || end0.x.value == originalStart.x.value) 0f else
            ((point.x.value - originalStart.x.value) / (end0.x.value - originalStart.x.value)).coerceIn(0f, 1f)
        val dx0 = currentStart.x.value - originalStart.x.value
        val dy0 = currentStart.y.value - originalStart.y.value
        val dx1 = if (end0 != null && end1 != null) end1.x.value - end0.x.value else dx0
        val dy1 = if (end0 != null && end1 != null) end1.y.value - end0.y.value else dy0
        return AbsolutePoint(
            Pixels(point.x.value + dx0 + (dx1 - dx0) * fraction),
            Pixels(point.y.value + dy0 + (dy1 - dy0) * fraction),
        )
    }

    return commands.map { command ->
        when (command) {
            is DrawLine -> command.copy(start = warp(command.start), end = warp(command.end))
            is DrawGlyph -> command.copy(position = warp(command.position))
            is DrawText -> command.copy(position = warp(command.position))
            else -> command
        }
    }
}

/** Offset the grabbed handle(s) by a pointer delta, in staff spaces. */
internal fun AttachmentGeometry.movedBy(
    endpoint: String,
    isHairpin: Boolean,
    dx: Float,
    dy: Float,
): AttachmentGeometry = when (endpoint) {
    "body" -> copy(
        startDx = startDx + dx,
        endDx = endDx?.plus(dx),
        startDy = startDy + dy,
        endDy = endDy?.plus(dy),
    )
    // Only a wedge keeps independent endpoint heights; every other span stays level, so dragging
    // one end carries the other with it.
    "start" -> if (isHairpin) copy(
        startDx = startDx + dx,
        startDy = startDy + dy,
    ) else copy(
        startDx = startDx + dx,
        startDy = startDy + dy,
        endDy = endDy?.plus(dy),
    )
    else -> if (isHairpin) copy(
        endDx = endDx?.plus(dx),
        endDy = endDy?.plus(dy),
    ) else copy(
        endDx = endDx?.plus(dx),
        endDy = endDy?.plus(dy),
        startDy = startDy + dy,
    )
}

/**
 * Push the mark out of the forbidden band that spans the staff and its notes, so an attachment never
 * lands on top of the music it annotates. Body drags move both ends as a unit, preserving a wedge's
 * slope while letting the whole mark switch between below and above the staff.
 */
internal fun AttachmentGeometry.constrainedToSafeBand(
    endpoint: String,
    isHairpin: Boolean,
    topLimit: Float,
    bottomLimit: Float,
): AttachmentGeometry {
    fun safeY(value: Float): Float = if (value in topLimit..bottomLimit) {
        if (kotlin.math.abs(value - topLimit) <= kotlin.math.abs(value - bottomLimit)) topLimit
        else bottomLimit
    } else value

    return when {
        endpoint == "body" -> {
            val safeStart = safeY(startDy)
            val endOffset = (endDy ?: startDy) - startDy
            val adjustedStart = if (safeStart <= topLimit) {
                minOf(safeStart, topLimit - maxOf(0f, endOffset))
            } else {
                maxOf(safeStart, bottomLimit - minOf(0f, endOffset))
            }
            copy(startDy = adjustedStart, endDy = endDy?.let { adjustedStart + endOffset })
        }
        isHairpin -> copy(startDy = safeY(startDy), endDy = endDy?.let(::safeY))
        else -> {
            val y = safeY(if (endpoint == "end") endDy ?: startDy else startDy)
            copy(startDy = y, endDy = endDy?.let { y })
        }
    }
}
