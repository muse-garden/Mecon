package com.mecon.renderer.geometry

import com.mecon.api.render.RenderColor
import com.mecon.api.storage.events.HairpinType
import com.mecon.renderer.render.CoordinateTransformer
import com.mecon.renderer.render.DrawLine
import com.mecon.renderer.render.RenderCommand
import com.mecon.renderer.render.RenderHelpers
import com.mecon.renderer.smufl.BravuraFont

/**
 * A crescendo / diminuendo **wedge** — the drawn angle-bracket "arrow" (`<` / `>`),
 * rendered as two converging lines. Drawn manually (no SMuFL glyph), per the
 * dynamics spec.
 *
 * The text form of a hairpin (`cresc.` / `dim.` + dashed line) is *not* a wedge:
 * it is a plain span and uses [IntervalAttachmentGeometry] instead.
 *
 * Coordinates are relative to the attachment anchor: X is system staff-space,
 * Y is relative to the hairpin band's vertical centre (0).
 */
data class HairpinGeometry(
    val startX: StaffSpace,
    val endX: StaffSpace,
    val type: HairpinType,
    /** Vertical centre of the hairpin, relative to the staff centre. */
    val yCenter: StaffSpace,
    /** End centre; differs after an endpoint drag, allowing a sloped wedge. */
    val endYCenter: StaffSpace = yCenter,
    /** Full vertical spread at the open end. */
    val spread: StaffSpace,
    val thickness: StaffSpace,
    override val bounds: RelativeRect,
    /**
     * Vertical spread at [startX] / [endX]. A whole hairpin opens from 0 to [spread]
     * (crescendo) or [spread] to 0 (diminuendo). When a hairpin is split across a
     * system break each segment carries the interpolated spreads at its own endpoints
     * so the wedge stays continuous across the break instead of restarting closed.
     */
    val startSpread: StaffSpace = if (type == HairpinType.CRESCENDO) StaffSpace.ZERO else spread,
    val endSpread: StaffSpace = if (type == HairpinType.CRESCENDO) spread else StaffSpace.ZERO,
) : DrawableGeometry {

    context(BravuraFont)
    override fun draw(
        offset: RelativePoint,
        transformer: CoordinateTransformer
    ): List<RenderCommand> {
        // Trapezoid between the two endpoint spreads. A whole crescendo has
        // startSpread 0 → endSpread full (apex at start); a diminuendo is the reverse.
        // A mid-hairpin segment has both endpoints partially open, keeping the wedge
        // continuous across a system/page break.
        val startHalf = startSpread / 2f
        val endHalf = endSpread / 2f
        return listOf(
            line(RelativePoint(startX, yCenter - startHalf), RelativePoint(endX, endYCenter - endHalf), offset, transformer),
            line(RelativePoint(startX, yCenter + startHalf), RelativePoint(endX, endYCenter + endHalf), offset, transformer),
        )
    }

    private fun line(
        from: RelativePoint,
        to: RelativePoint,
        offset: RelativePoint,
        transformer: CoordinateTransformer,
    ): DrawLine {
        val abs = transformer.toAbsolute(
            RelativeLine(
                start = RelativePoint(from.x + offset.x, from.y + offset.y),
                end = RelativePoint(to.x + offset.x, to.y + offset.y),
                thickness = thickness
            )
        )
        return DrawLine(
            start = abs.start,
            end = abs.end,
            thickness = abs.thickness,
            color = RenderColor.BLACK,
            bounds = RenderHelpers.calculateLineBounds(abs)
        )
    }
}
