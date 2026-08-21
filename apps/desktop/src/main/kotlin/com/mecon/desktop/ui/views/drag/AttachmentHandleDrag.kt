package com.mecon.desktop.ui.views.drag

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import com.mecon.api.computed.ComputedBreathMark
import com.mecon.api.computed.ComputedHairpin
import com.mecon.api.computed.ComputedTempoKeyframe
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.AttachmentGeometry
import com.mecon.api.storage.events.HairpinStyle
import com.mecon.desktop.ui.views.resolveBreathBoundaryTime
import com.mecon.desktop.ui.views.resolveExpressionTime
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.render.RenderElementId
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderResult

internal data class AttachmentDragState(
    val id: EventId,
    val endpoint: String,
    val isHairpin: Boolean,
    val isBreath: Boolean,
    val start: AttachmentGeometry,
    val current: AttachmentGeometry,
    val staffId: TrackId,
    val startTime: TimeCode,
    val endTime: TimeCode?,
    val originalStartTime: TimeCode,
    val originalEndTime: TimeCode?,
    val elementId: RenderElementId,
    val originalStartPoint: AbsolutePoint,
    val originalEndPoint: AbsolutePoint?,
    val systemIndex: Int?,
    val topLimit: Float,
    val bottomLimit: Float,
    val committing: Boolean = false,
    val commitBaseline: RenderResult? = null,
)

/**
 * Family H — the staff-attachment semantic handle: dynamics, hairpins, 8va spans, gradual tempo,
 * breath marks and ornaments. Point marks expose one handle; spans expose start, end and body.
 *
 * A drag moves the mark in two independent ways at once: vertically it slides in staff spaces
 * (clamped out of the staff/note band), horizontally it re-anchors to a different `TimeCode`. Both
 * land in a single `MoveAttachment` on release.
 */
internal class AttachmentHandleDragHandler : ScoreDragHandler {
    private var startRelX = 0f
    private var startRelY = 0f

    fun start(context: ScoreDragContext, pick: ScoreDragPick): ScoreDragHandler? {
        val section = pick.attachment ?: return null
        val element = pick.attachmentElement ?: return null
        val point = pick.point ?: return null
        val score = context.document.score ?: return null
        if (context.readOnly) return null
        val result = context.result
        val attachment = section.attachment
        // The opening tempo mark is the score's anchor; it has no free position to drag.
        if (attachment is ComputedTempoKeyframe &&
            attachment.time.measure == 1 &&
            (attachment.time.beat?.numerator ?: 0) == 0
        ) return null

        val endpoint = if (!attachment.isSpan) "start" else {
            val radius = ATTACHMENT_CONTROL_HIT_RADIUS / context.viewport.scale
            when {
                kotlin.math.abs(point.x.value - element.hitBox.origin.x.value) <= radius -> "start"
                kotlin.math.abs(point.x.value - element.hitBox.bottomRight.x.value) <= radius -> "end"
                attachment is ComputedHairpin || attachment is ComputedTempoKeyframe -> "body"
                else -> return null // octave spans remain endpoint-only
            }
        }
        val stored = score.geometry?.attachments?.get(attachment.id)
            ?: context.document.beamGeometry?.attachments?.get(attachment.id)
            ?: deriveAttachmentGeometry(result, section, element)
            ?: return null
        context.ensureSelected(section)

        val relative = context.toRelative(point)
        startRelX = relative.x.value
        startRelY = relative.y.value
        val (topLimit, bottomLimit) = safeBandLimits(context, section, element)
        val endTime = attachment.spanEndTime
        context.previews.attachment = AttachmentDragState(
            id = attachment.id,
            endpoint = endpoint,
            isHairpin = (attachment as? ComputedHairpin)?.style == HairpinStyle.WEDGE,
            isBreath = attachment is ComputedBreathMark,
            start = stored,
            current = stored,
            staffId = attachment.staffTrackId,
            startTime = attachment.time,
            endTime = endTime,
            originalStartTime = attachment.time,
            originalEndTime = endTime,
            elementId = element.id,
            originalStartPoint = AbsolutePoint(element.hitBox.origin.x, element.center.y),
            originalEndPoint = if (attachment.isSpan) {
                AbsolutePoint(element.hitBox.bottomRight.x, element.center.y)
            } else null,
            systemIndex = element.systemIndex,
            topLimit = topLimit,
            bottomLimit = bottomLimit,
        )
        return this
    }

    /**
     * The vertical band the mark may not enter: the staff plus every notehead/rest on it in this
     * system, widened by half the mark's own height so its box also stays clear.
     */
    private fun safeBandLimits(
        context: ScoreDragContext,
        section: com.mecon.api.interaction.StaffAttachmentSection,
        element: com.mecon.renderer.render.RenderElement,
    ): Pair<Float, Float> {
        val result = context.result
        val attachment = section.attachment
        val elementTop = context.toRelative(element.hitBox.origin)
        val elementBottom = context.toRelative(element.hitBox.bottomRight)
        val system = result.spatialIndex.allSystems()
            .firstOrNull { it.systemIndex == element.systemIndex }
        val staffCenter = system?.staffRegions
            ?.firstOrNull { it.staffIndex == attachment.staffIndex }?.centerY?.value ?: 0f
        val noteBoxes = result.elements.asSequence().filter {
            it.systemIndex == element.systemIndex && it.staffIndex == attachment.staffIndex &&
                it.type in setOf(RenderElementType.NOTEHEAD, RenderElementType.REST)
        }.map { note ->
            context.toRelative(note.hitBox.origin).y.value - staffCenter to
                context.toRelative(note.hitBox.bottomRight).y.value - staffCenter
        }.toList()
        val halfHeight = (elementBottom.y.value - elementTop.y.value) / 2f
        return Pair(
            minOf(-2f, noteBoxes.minOfOrNull { it.first } ?: -2f) - halfHeight - 0.2f,
            maxOf(2f, noteBoxes.maxOfOrNull { it.second } ?: 2f) + halfHeight + 0.2f,
        )
    }

    override fun drag(context: ScoreDragContext, change: PointerInputChange, dragAmount: Offset) {
        val drag = context.previews.attachment
        val point = context.toAbsolute(change.position)
        if (drag != null && point != null) {
            val relative = context.toRelative(point)
            val dx = relative.x.value - startRelX
            val dy = relative.y.value - startRelY
            val moved = drag.start.movedBy(drag.endpoint, drag.isHairpin, dx, dy)
                .constrainedToSafeBand(drag.endpoint, drag.isHairpin, drag.topLimit, drag.bottomLimit)
            val (newStart, newEnd) = resolveTimes(context, drag, point, dx)
            context.previews.attachment = drag.copy(
                current = compensateAnchorShift(context, drag, moved, dx, newStart, newEnd, point),
                startTime = newStart,
                endTime = newEnd,
            )
        }
        change.consume()
    }

    /** Re-anchor the dragged handle(s) to the slot they now sit over, never crossing each other. */
    private fun resolveTimes(
        context: ScoreDragContext,
        drag: AttachmentDragState,
        point: AbsolutePoint,
        dx: Float,
    ): Pair<TimeCode, TimeCode?> {
        val result = context.result
        return when (drag.endpoint) {
            "body" -> {
                val dxPixels = result.transformerSnapshot.toPixels(StaffSpace(dx)).value
                val startCandidate = resolveExpressionTime(
                    result, drag.originalStartPoint.x.value + dxPixels, drag.systemIndex,
                )
                val endCandidate = drag.originalEndPoint?.let { endpoint ->
                    resolveExpressionTime(result, endpoint.x.value + dxPixels, drag.systemIndex)
                }
                if (startCandidate != null && endCandidate != null && startCandidate < endCandidate) {
                    startCandidate to endCandidate
                } else drag.startTime to drag.endTime
            }
            "start" -> {
                val anchor = if (drag.isBreath) {
                    resolveBreathBoundaryTime(result, point.x.value, drag.systemIndex)
                } else {
                    resolveExpressionTime(result, point.x.value, drag.systemIndex)
                }
                val candidate = anchor ?: drag.startTime
                if (drag.endTime == null || candidate < drag.endTime) candidate to drag.endTime
                else drag.startTime to drag.endTime
            }
            else -> {
                val candidate = resolveExpressionTime(result, point.x.value, drag.systemIndex)
                    ?: drag.endTime
                if (candidate != null && candidate > drag.startTime) drag.startTime to candidate
                else drag.startTime to drag.endTime
            }
        }
    }

    /**
     * TimeCode positions are anchor X values. When an endpoint crosses a midpoint separator and
     * snaps to the adjacent anchor, compensate the persisted delta so the symbol itself remains
     * under the pointer rather than jumping once for the drag and once again for the new anchor.
     */
    private fun compensateAnchorShift(
        context: ScoreDragContext,
        drag: AttachmentDragState,
        constrained: AttachmentGeometry,
        dx: Float,
        newStart: TimeCode,
        newEnd: TimeCode?,
        point: AbsolutePoint,
    ): AttachmentGeometry {
        val result = context.result
        fun anchorX(time: TimeCode?): Float? {
            val position = time?.let(result.timeCodePositions::get) ?: return null
            return context.toRelative(AbsolutePoint(Pixels(position.x), point.y)).x.value
        }
        return when (drag.endpoint) {
            "body" -> {
                val oldStartAnchor = anchorX(drag.originalStartTime)
                val newStartAnchor = anchorX(newStart)
                val oldEndAnchor = anchorX(drag.originalEndTime)
                val newEndAnchor = anchorX(newEnd)
                constrained.copy(
                    startDx = if (oldStartAnchor != null && newStartAnchor != null) {
                        oldStartAnchor + drag.start.startDx + dx - newStartAnchor
                    } else constrained.startDx,
                    endDx = if (oldEndAnchor != null && newEndAnchor != null) {
                        oldEndAnchor + (drag.start.endDx ?: 0f) + dx - newEndAnchor
                    } else constrained.endDx,
                )
            }
            "start" -> {
                val oldAnchor = anchorX(drag.originalStartTime)
                val newAnchor = anchorX(newStart)
                if (oldAnchor != null && newAnchor != null) constrained.copy(
                    startDx = oldAnchor + drag.start.startDx + dx - newAnchor,
                ) else constrained
            }
            else -> {
                val oldAnchor = anchorX(drag.originalEndTime)
                val newAnchor = anchorX(newEnd)
                if (oldAnchor != null && newAnchor != null) constrained.copy(
                    endDx = oldAnchor + (drag.start.endDx ?: 0f) + dx - newAnchor,
                ) else constrained
            }
        }
    }

    override fun end(context: ScoreDragContext) {
        val drag = context.previews.attachment ?: return
        val changed = drag.current != drag.start ||
            drag.startTime != drag.originalStartTime ||
            drag.endTime != drag.originalEndTime
        if (!changed) {
            context.previews.attachment = null
            return
        }
        context.previews.attachment = drag.copy(committing = true, commitBaseline = context.result)
        context.actions.expressions.moveAttachment(
            drag.id, drag.current, drag.startTime, drag.endTime,
        )
    }
}
