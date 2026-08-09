package com.mecon.renderer.geometry

import com.mecon.renderer.render.CoordinateTransformer
import com.mecon.renderer.render.DrawLine
import com.mecon.api.render.RenderColor
import com.mecon.renderer.render.LineCap
import com.mecon.renderer.render.RenderCommand
import com.mecon.renderer.render.RenderHelpers
import com.mecon.renderer.smufl.BravuraFont
import kotlinx.serialization.Serializable

/**
 * A line segment to be rendered.
 *
 * @property start Start point relative to the event's anchor
 * @property end End point relative to the event's anchor
 * @property thickness Line thickness in staff spaces
 * @property bounds Computed bounding box
 */
@Serializable
data class LineGeometry(
    val start: RelativePoint,
    val end: RelativePoint,
    val thickness: StaffSpace,
    override val bounds: RelativeRect,
    /** Optional on/off pattern in staff spaces. */
    val dashIntervals: List<StaffSpace>? = null,
    val cap: LineCap = LineCap.BUTT,
) : DrawableGeometry {

    context(BravuraFont)
    override fun draw(
        offset: RelativePoint,
        transformer: CoordinateTransformer
    ): List<RenderCommand> {
        // Apply offset to start and end
        val finalStart = RelativePoint(
            start.x + offset.x,
            start.y + offset.y
        )
        val finalEnd = RelativePoint(
            end.x + offset.x,
            end.y + offset.y
        )

        // Create relative line
        val line = RelativeLine(
            start = finalStart,
            end = finalEnd,
            thickness = thickness
        )

        // Convert to absolute coordinates
        val absLine = transformer.toAbsolute(line)

        // Create line command
        val command = DrawLine(
            start = absLine.start,
            end = absLine.end,
            thickness = absLine.thickness,
            color = RenderColor.BLACK,
            cap = cap,
            dashIntervals = dashIntervals?.map { transformer.toPixels(it).value },
            bounds = RenderHelpers.calculateLineBounds(absLine)
        )

        return listOf(command)
    }

    companion object {
        /**
         * Create a vertical line geometry.
         */
        fun vertical(
            x: StaffSpace,
            startY: StaffSpace,
            endY: StaffSpace,
            thickness: StaffSpace,
            dashIntervals: List<StaffSpace>? = null,
            cap: LineCap = LineCap.BUTT,
        ): LineGeometry {
            val minY = minOf(startY, endY)
            val maxY = maxOf(startY, endY)
            return LineGeometry(
                start = RelativePoint(x, startY),
                end = RelativePoint(x, endY),
                thickness = thickness,
                bounds = RelativeRect(
                    origin = RelativePoint(x - thickness / 2f, minY),
                    width = thickness,
                    height = maxY - minY
                ),
                dashIntervals = dashIntervals,
                cap = cap,
            )
        }

        /**
         * Create a horizontal line geometry.
         */
        fun horizontal(
            y: StaffSpace,
            startX: StaffSpace,
            endX: StaffSpace,
            thickness: StaffSpace,
            dashIntervals: List<StaffSpace>? = null,
            cap: LineCap = LineCap.BUTT,
        ): LineGeometry {
            val minX = minOf(startX, endX)
            val maxX = maxOf(startX, endX)
            return LineGeometry(
                start = RelativePoint(startX, y),
                end = RelativePoint(endX, y),
                thickness = thickness,
                bounds = RelativeRect(
                    origin = RelativePoint(minX, y - thickness / 2f),
                    width = maxX - minX,
                    height = thickness
                ),
                dashIntervals = dashIntervals,
                cap = cap,
            )
        }
    }
}
