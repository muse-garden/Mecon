package com.mecon.desktop.ui.views.drag

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import com.mecon.desktop.ui.views.AnnotationRangeEndpoint
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.desktop.ui.views.globalToDesign
import com.mecon.desktop.ui.views.resolveAnnotationBoundarySnap
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderResult

/** How far the pointer may stray from the source row before the drag retargets to another system. */
internal const val ANNOTATION_SOURCE_ROW_LOCK_DP = 28f
private const val ENDPOINT_ALIGNMENT_TOLERANCE_PX = 0.5f

internal data class AnnotationRangeEndpointHit(
    val eventId: EventId,
    val endpoint: AnnotationRangeEndpoint,
    val point: AbsolutePoint,
    val systemIndex: Int,
)

internal data class AnnotationRangeDragState(
    val eventId: EventId,
    val endpoint: AnnotationRangeEndpoint,
    val originalPoint: AbsolutePoint,
    val currentPoint: AbsolutePoint,
    val sourceSystemIndex: Int,
    val candidateTime: TimeCode? = null,
)

internal fun annotationRangeEndpointPoints(
    result: RenderResult,
    eventId: EventId,
): List<AnnotationRangeEndpointHit> {
    val elements = result.elements.filter {
        it.type == RenderElementType.TEXT_ANNOTATION && it.eventId == eventId &&
            it.hitBox.width.value > 0f && it.hitBox.height.value > 0f
    }
    if (elements.isEmpty()) return emptyList()
    // A Range keeps the original whole-range measure metadata after the annotation layout splits
    // it across systems. Use the fragment's system ownership to expose only the two true outer
    // endpoints; otherwise every line start/end becomes a handle for the whole musical range.
    val firstSystem = elements.minOf { it.systemIndex ?: 0 }
    val lastSystem = elements.maxOf { it.systemIndex ?: 0 }
    val firstSystemElements = elements.filter { (it.systemIndex ?: 0) == firstSystem }
    val lastSystemElements = elements.filter { (it.systemIndex ?: 0) == lastSystem }
    val startX = firstSystemElements.minOf { it.hitBox.origin.x.value }
    val endX = lastSystemElements.maxOf { it.hitBox.bottomRight.x.value }
    return buildList {
        firstSystemElements.filter {
            kotlin.math.abs(it.hitBox.origin.x.value - startX) <= ENDPOINT_ALIGNMENT_TOLERANCE_PX
        }.forEach { element ->
            add(
                AnnotationRangeEndpointHit(
                    eventId,
                    AnnotationRangeEndpoint.START,
                    AbsolutePoint(element.hitBox.origin.x, element.center.y),
                    element.systemIndex ?: firstSystem,
                )
            )
        }
        lastSystemElements.filter {
            kotlin.math.abs(it.hitBox.bottomRight.x.value - endX) <= ENDPOINT_ALIGNMENT_TOLERANCE_PX
        }.forEach { element ->
            add(
                AnnotationRangeEndpointHit(
                    eventId,
                    AnnotationRangeEndpoint.END,
                    AbsolutePoint(element.hitBox.bottomRight.x, element.center.y),
                    element.systemIndex ?: lastSystem,
                )
            )
        }
    }
}

/** Endpoint hit testing for split annotation ranges, including duplicated double-tonality lines. */
internal fun annotationRangeEndpointAt(
    result: RenderResult,
    point: AbsolutePoint,
    resizableEventIds: Set<EventId>,
    radius: Float,
): AnnotationRangeEndpointHit? = resizableEventIds.asSequence()
    .flatMap { eventId -> annotationRangeEndpointPoints(result, eventId).asSequence() }
    .filter { hit ->
        val dx = hit.point.x.value - point.x.value
        val dy = hit.point.y.value - point.y.value
        dx * dx + dy * dy <= radius * radius
    }.minByOrNull { hit ->
        val dx = hit.point.x.value - point.x.value
        val dy = hit.point.y.value - point.y.value
        dx * dx + dy * dy
    }

/**
 * Keep a drag on its own row while the pointer stays near it, so nudging a handle sideways cannot
 * retarget the range to the system above or below.
 */
internal fun annotationDragTargetSystem(
    sourceSystemIndex: Int,
    sourceRawY: Float?,
    pointerRawY: Float,
    nearestSystemIndex: Int?,
    sourceRowLockPx: Float,
): Int = if (
    sourceRawY != null && kotlin.math.abs(pointerRawY - sourceRawY) <= sourceRowLockPx
) {
    sourceSystemIndex
} else {
    nearestSystemIndex ?: sourceSystemIndex
}

/**
 * A range-endpoint handle for analysis annotations (tonal regions, double-tonality lines).
 *
 * Structurally this is a family-H handle over a family-B range target, but the annotation itself is
 * an analysis-domain event rather than one of the notation intents, so it commits through the
 * annotation resize callback instead of `ScoreEditIntent`. It is also the one handle that stays
 * available when viewport panning is disabled, because embedded editors still need to edit regions.
 */
internal class AnnotationRangeDragHandler : ScoreDragHandler {
    private var endpointHit: AnnotationRangeEndpointHit? = null

    fun start(context: ScoreDragContext, pick: ScoreDragPick): ScoreDragHandler? {
        if (context.readOnly) return null
        val point = pick.point ?: return null
        val endpoint = annotationRangeEndpointAt(
            result = context.result,
            point = point,
            resizableEventIds = context.mode.resizableAnnotationEventIds,
            radius = ANNOTATION_ENDPOINT_HIT_RADIUS / context.viewport.scale,
        ) ?: return null
        endpointHit = endpoint
        context.previews.annotationRange = AnnotationRangeDragState(
            eventId = endpoint.eventId,
            endpoint = endpoint.endpoint,
            originalPoint = endpoint.point,
            currentPoint = endpoint.point,
            sourceSystemIndex = endpoint.systemIndex,
        )
        context.actions.selection.selectAnnotationEvent(endpoint.eventId)
        return this
    }

    override fun drag(context: ScoreDragContext, change: PointerInputChange, dragAmount: Offset) {
        val point = context.toAbsolute(change.position)
        if (point != null) {
            val drag = context.previews.annotationRange
            val targetSystem = drag?.let {
                annotationDragTargetSystem(
                    sourceSystemIndex = it.sourceSystemIndex,
                    sourceRawY = sourceRawY(context, it.originalPoint),
                    pointerRawY = change.position.y,
                    nearestSystemIndex = context.nearestSystem(change.position),
                    sourceRowLockPx = ANNOTATION_SOURCE_ROW_LOCK_DP * context.density *
                        context.viewport.scale,
                )
            } ?: context.nearestSystem(change.position)
            val snap = resolveAnnotationBoundarySnap(context.result, point.x.value, targetSystem)
            context.previews.annotationRange = drag?.copy(
                currentPoint = AbsolutePoint(Pixels(snap?.absoluteX ?: point.x.value), point.y),
                candidateTime = snap?.time,
            )
        }
        change.consume()
    }

    /** The handle's own screen Y, so the row lock compares like with like. */
    private fun sourceRawY(context: ScoreDragContext, origin: AbsolutePoint): Float? {
        val designY = if (context.frame.paginated) {
            globalToDesign(
                origin.x.value, origin.y.value, context.frame.pages, context.frame.pageSlots,
            )?.y
        } else origin.y.value
        return designY?.let { it * context.density * context.viewport.scale + context.viewport.offset.y }
    }

    override fun end(context: ScoreDragContext) {
        val endpoint = endpointHit
        val time = context.previews.annotationRange?.candidateTime
        if (endpoint != null && time != null) {
            context.actions.selection.resizeAnnotationRange(endpoint.eventId, endpoint.endpoint, time)
        }
        context.previews.annotationRange = null
        endpointHit = null
    }

    override fun cancel(context: ScoreDragContext) {
        endpointHit = null
    }

    private companion object {
        const val ANNOTATION_ENDPOINT_HIT_RADIUS = 10f
    }
}
