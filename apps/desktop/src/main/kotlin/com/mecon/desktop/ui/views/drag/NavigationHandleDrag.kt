package com.mecon.desktop.ui.views.drag

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import com.mecon.api.interaction.EventSectionId
import com.mecon.api.storage.NavigationMark
import com.mecon.api.storage.NavigationMarkOffset
import com.mecon.desktop.ui.views.navigationSystemAnchorY
import com.mecon.renderer.render.RenderElementId
import com.mecon.renderer.render.RenderResult

internal data class NavigationDragState(
    val sectionId: EventSectionId,
    val elementId: RenderElementId,
    val boundaryMeasure: Int,
    val mark: NavigationMark,
    val start: NavigationMarkOffset,
    val current: NavigationMarkOffset,
    val targetBoundaryMeasure: Int,
    val startVisualCenterX: Float,
    val targetAnchorX: Float = startVisualCenterX - start.dx,
    val startAnchorY: Float,
    val targetAnchorY: Float,
    val previewDx: Float = 0f,
    val previewDy: Float = 0f,
    val committing: Boolean = false,
    val commitBaseline: RenderResult? = null,
)

/** Remove the source-to-target system distance from a drag's accumulated Y offset. */
internal fun navigationOffsetYAfterSnap(
    currentOffsetY: Float,
    sourceAnchorY: Float,
    targetAnchorY: Float,
): Float = currentOffsetY + sourceAnchorY - targetAnchorY

/**
 * Family H over a family-B target — the navigation mark (segno, coda, D.C./D.S.) semantic handle.
 * The mark snaps to the nearest measure boundary, which may live on another system, and only its
 * local displacement from that boundary's anchor is persisted.
 *
 * Commits one `MoveNavigationMark` on release.
 */
internal class NavigationHandleDragHandler : ScoreDragHandler {
    private var startRelX = 0f
    private var startRelY = 0f

    fun start(context: ScoreDragContext, pick: ScoreDragPick): ScoreDragHandler? {
        val section = pick.navigation ?: return null
        val point = pick.point ?: return null
        if (context.readOnly) return null
        val element = context.result.sectionIndex.elementsFor(section).elementIds
            .mapNotNull(context.result::elementById)
            .firstOrNull() ?: return null
        val systemIndex = element.systemIndex ?: return null
        val sourceAnchorY = navigationSystemAnchorY(context.result, systemIndex) ?: return null
        context.ensureSelected(section)
        val relative = context.toRelative(point)
        val visualCenter = context.toRelative(element.center)
        startRelX = relative.x.value
        startRelY = relative.y.value
        context.previews.navigation.value = NavigationDragState(
            sectionId = section.id,
            elementId = element.id,
            boundaryMeasure = section.navigation.boundaryMeasure,
            mark = section.navigation.mark,
            start = section.navigation.offset,
            current = section.navigation.offset,
            targetBoundaryMeasure = section.navigation.boundaryMeasure,
            startVisualCenterX = visualCenter.x.value,
            startAnchorY = sourceAnchorY,
            targetAnchorY = sourceAnchorY,
        )
        return this
    }

    override fun drag(context: ScoreDragContext, change: PointerInputChange, dragAmount: Offset) {
        val drag = context.previews.navigation.value
        val point = context.toAbsolute(change.position)
        if (drag != null && point != null) {
            val relative = context.toRelative(point)
            val nearestSystem = context.nearestSystem(change.position)
            val target = context.result.measureBounds
                .asSequence()
                .filter { nearestSystem == null || it.systemIndex == nearestSystem }
                .minByOrNull { kotlin.math.abs(it.rightX.value - relative.x.value) }
            context.previews.navigation.value = drag.copy(
                current = NavigationMarkOffset(
                    dx = drag.start.dx + relative.x.value - startRelX,
                    dy = drag.start.dy + relative.y.value - startRelY,
                ),
                previewDx = relative.x.value - startRelX,
                previewDy = relative.y.value - startRelY,
                targetBoundaryMeasure = target?.measureNumber ?: drag.targetBoundaryMeasure,
                targetAnchorX = target?.rightX?.value ?: drag.targetAnchorX,
                targetAnchorY = nearestSystem?.let { navigationSystemAnchorY(context.result, it) }
                    ?: drag.targetAnchorY,
            )
        }
        change.consume()
    }

    override fun end(context: ScoreDragContext) {
        val drag = context.previews.navigation.value ?: return
        if (drag.previewDx == 0f && drag.previewDy == 0f) {
            context.previews.navigation.value = null
            return
        }
        val committedOffset = NavigationMarkOffset(
            dx = 0f,
            // Re-anchoring at the target system already applies the inter-system distance.
            // Keep only the mark's local displacement from that new anchor.
            dy = navigationOffsetYAfterSnap(
                currentOffsetY = drag.current.dy,
                sourceAnchorY = drag.startAnchorY,
                targetAnchorY = drag.targetAnchorY,
            ),
        )
        context.previews.navigation.value = drag.copy(
            current = committedOffset,
            previewDx = drag.targetAnchorX - drag.startVisualCenterX,
            committing = true,
            commitBaseline = context.result,
        )
        context.actions.structure.moveNavigationMark(
            drag.boundaryMeasure,
            drag.targetBoundaryMeasure,
            drag.mark,
            committedOffset,
        )
    }
}
