package com.mecon.renderer.render

import com.mecon.renderer.geometry.AbsoluteCubicBezier
import com.mecon.renderer.geometry.AbsolutePath
import com.mecon.renderer.geometry.AbsolutePathSegment
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.geometry.Pixels

/**
 * Pure-geometry translation helpers used to project a globally-laid-out [RenderElement]
 * into page-local coordinates (see [RenderResult.pages]).
 *
 * Only the absolute pixel geometry is shifted — colors, glyph identities, hit metadata
 * and [RenderElement.id] are preserved, so the global section index / style snapshot keep
 * resolving correctly. [RenderElement.relativeHitBox] is intentionally left untouched
 * because hit testing runs against the global spatial index, not the per-page elements.
 */
fun RenderElement.translatedBy(dx: Float, dy: Float): RenderElement {
    if (dx == 0f && dy == 0f) return this
    return copy(
        commands = commands.map { it.translatedBy(dx, dy) },
        hitBox = hitBox.translatedBy(dx, dy)
    )
}

private fun AbsolutePoint.translatedBy(dx: Float, dy: Float): AbsolutePoint =
    AbsolutePoint(Pixels(x.value + dx), Pixels(y.value + dy))

private fun AbsoluteRect.translatedBy(dx: Float, dy: Float): AbsoluteRect =
    AbsoluteRect(origin.translatedBy(dx, dy), width, height)

private fun AbsoluteCubicBezier.translatedBy(dx: Float, dy: Float): AbsoluteCubicBezier =
    AbsoluteCubicBezier(
        p0 = p0.translatedBy(dx, dy),
        p1 = p1.translatedBy(dx, dy),
        p2 = p2.translatedBy(dx, dy),
        p3 = p3.translatedBy(dx, dy)
    )

private fun AbsolutePath.translatedBy(dx: Float, dy: Float): AbsolutePath = AbsolutePath(
    segments = segments.map { seg ->
        when (seg) {
            is AbsolutePathSegment.MoveTo -> AbsolutePathSegment.MoveTo(seg.point.translatedBy(dx, dy))
            is AbsolutePathSegment.LineTo -> AbsolutePathSegment.LineTo(seg.point.translatedBy(dx, dy))
            is AbsolutePathSegment.QuadTo -> AbsolutePathSegment.QuadTo(
                seg.control.translatedBy(dx, dy), seg.end.translatedBy(dx, dy)
            )
            is AbsolutePathSegment.CubicTo -> AbsolutePathSegment.CubicTo(
                seg.control1.translatedBy(dx, dy),
                seg.control2.translatedBy(dx, dy),
                seg.end.translatedBy(dx, dy)
            )
            is AbsolutePathSegment.Close -> AbsolutePathSegment.Close
        }
    }
)

private fun RenderCommand.translatedBy(dx: Float, dy: Float): RenderCommand = when (this) {
    is DrawGlyph -> copy(position = position.translatedBy(dx, dy), bounds = bounds.translatedBy(dx, dy))
    is DrawLine -> copy(
        start = start.translatedBy(dx, dy),
        end = end.translatedBy(dx, dy),
        bounds = bounds.translatedBy(dx, dy)
    )
    is DrawRect -> copy(rect = rect.translatedBy(dx, dy), bounds = bounds.translatedBy(dx, dy))
    is DrawPath -> copy(path = path.translatedBy(dx, dy), bounds = bounds.translatedBy(dx, dy))
    is DrawBezier -> copy(curve = curve.translatedBy(dx, dy), bounds = bounds.translatedBy(dx, dy))
    is DrawText -> copy(position = position.translatedBy(dx, dy), bounds = bounds.translatedBy(dx, dy))
    is DrawEllipse -> copy(center = center.translatedBy(dx, dy), bounds = bounds.translatedBy(dx, dy))
    is RenderGroup -> copy(
        commands = commands.map { it.translatedBy(dx, dy) },
        clipRect = clipRect?.translatedBy(dx, dy),
        bounds = bounds.translatedBy(dx, dy)
    )
}
