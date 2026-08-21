package com.mecon.desktop.ui.views.drag

import com.mecon.api.interaction.EventSectionId
import com.mecon.renderer.geometry.AbsolutePathSegment
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.SlurCurveBuilder
import com.mecon.renderer.geometry.SlurDirection
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.render.DrawPath
import com.mecon.renderer.render.RenderCommand
import com.mecon.renderer.render.RenderElementId
import com.mecon.renderer.render.RenderResult
import com.mecon.renderer.smufl.EngravingDefaults

internal enum class CurveKind { TIE, SLUR }

internal data class CurveDragState(
    val kind: CurveKind,
    val sectionId: EventSectionId,
    val elementIds: List<RenderElementId>,
    val above: Boolean,
    val startApex: Float,
    val currentApex: Float,
    val slopeDamping: Float,
    val middleStraightening: Float,
    val committing: Boolean = false,
    val commitBaseline: RenderResult? = null,
)

internal data class CurveDragPreviewSegment(
    val commands: List<RenderCommand>,
    val anchor: AbsolutePoint,
)

internal data class CurveDragPreview(
    val segments: List<CurveDragPreviewSegment>,
)

/**
 * Rebuild only the selected tie/slur paths from the displayed frame's endpoints.
 * This is transient Canvas geometry: no score mutation, compute pass, or layout pass.
 */
internal fun createCurveDragPreview(
    result: RenderResult,
    drag: CurveDragState,
): CurveDragPreview {
    val direction = if (drag.above) SlurDirection.ABOVE else SlurDirection.BELOW
    val thickness = when (drag.kind) {
        CurveKind.TIE -> EngravingDefaults.BRAVURA.tieMidpointThickness
        CurveKind.SLUR -> EngravingDefaults.BRAVURA.slurMidpointThickness
    }
    val apex = StaffSpace(drag.currentApex)
    val segments = drag.elementIds.mapNotNull { elementId ->
        val element = result.elementById(elementId) ?: return@mapNotNull null
        val original = element.commands.filterIsInstance<DrawPath>().firstOrNull()
            ?: return@mapNotNull null
        val pathSegments = original.path.segments
        val startAbsolute = (pathSegments.firstOrNull() as? AbsolutePathSegment.MoveTo)?.point
            ?: return@mapNotNull null
        val cubics = pathSegments.filterIsInstance<AbsolutePathSegment.CubicTo>()
        if (cubics.size < 2) return@mapNotNull null
        // The first half traces the outer curve from start to end; the second half
        // returns along the inner curve. This also covers straightened multi-cubic slurs.
        val endAbsolute = cubics[cubics.size / 2 - 1].end
        val start = result.transformerSnapshot.toRelative(startAbsolute)
        val end = result.transformerSnapshot.toRelative(endAbsolute)
        val relativePath = SlurCurveBuilder.buildLensPath(
            start = start,
            end = end,
            direction = direction,
            midpointThickness = thickness,
            minHeight = apex,
            maxHeight = apex,
            slopeDamping = drag.slopeDamping,
            heightUsesHorizontalSpan = true,
            middleStraightening = drag.middleStraightening,
        )
        val relativeBounds = SlurCurveBuilder.lensBounds(
            start = start,
            end = end,
            direction = direction,
            midpointThickness = thickness,
            minHeight = apex,
            maxHeight = apex,
            slopeDamping = drag.slopeDamping,
            heightUsesHorizontalSpan = true,
            middleStraightening = drag.middleStraightening,
        )
        CurveDragPreviewSegment(
            commands = listOf(original.copy(
                path = result.transformerSnapshot.toAbsolute(relativePath),
                bounds = result.transformerSnapshot.toAbsolute(relativeBounds),
            )),
            anchor = startAbsolute,
        )
    }
    return CurveDragPreview(segments)
}
