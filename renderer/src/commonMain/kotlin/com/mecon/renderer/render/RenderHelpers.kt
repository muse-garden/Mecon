package com.mecon.renderer.render

import com.mecon.api.render.RenderColor
import com.mecon.renderer.geometry.*
import com.mecon.renderer.smufl.BravuraFont
import com.mecon.renderer.smufl.GlyphInfo

/**
 * Helper functions for rendering operations.
 */
object RenderHelpers {

    /**
     * Create a DrawGlyph command for a SMuFL glyph.
     *
     * @param glyph The glyph information
     * @param origin Absolute position for the glyph's SMuFL origin (reference point/baseline).
     *               This is the (0,0) point in SMuFL coordinates where the glyph is defined.
     * @param fontSize Font size in pixels (equivalent to 4 staff spaces / 1 em)
     * @return DrawGlyph render command
     */
    context(BravuraFont)
    fun createGlyphCommand(
        glyph: GlyphInfo,
        origin: AbsolutePoint,
        fontSize: Pixels,
        scaleX: Float = 1f,
        scaleY: Float = 1f
    ): DrawGlyph {
        // Calculate bounds using SMuFL metadata
        val bbox = this@BravuraFont.getBBox(glyph)
        val scale = fontSize / 4f
        val xScale = scale * scaleX
        val yScale = scale * scaleY

        val bounds = bbox?.let {
            // SMuFL coordinate system: Y is up, Reference is (0,0)
            // Rendering coordinate system: Y is down
            AbsoluteRect(
                origin = AbsolutePoint(
                    origin.x + xScale * it.southWest.x.value,
                    origin.y - yScale * it.northEast.y.value,
                ),
                width = xScale * it.width.value,
                height = yScale * it.height.value,
            )
        } ?: AbsoluteRect(
            origin = AbsolutePoint(origin.x, origin.y - fontSize / 2 * scaleY),
            width = fontSize * 0.8f * scaleX,
            height = fontSize * scaleY,
        )

        return DrawGlyph(
            position = origin,
            glyph = glyph,
            fontSize = fontSize,
            scaleX = scaleX,
            scaleY = scaleY,
            color = RenderColor.BLACK,
            bounds = bounds
        )
    }

    /**
     * Calculate the bounding rectangle for a line.
     *
     * @param line The line to calculate bounds for
     * @return Bounding rectangle
     */
    fun calculateLineBounds(line: AbsoluteLine): AbsoluteRect {
        val minX = minOf(line.start.x.value, line.end.x.value) - line.thickness.value / 2
        val minY = minOf(line.start.y.value, line.end.y.value) - line.thickness.value / 2
        val maxX = maxOf(line.start.x.value, line.end.x.value) + line.thickness.value / 2
        val maxY = maxOf(line.start.y.value, line.end.y.value) + line.thickness.value / 2

        return AbsoluteRect(
            origin = AbsolutePoint(Pixels(minX), Pixels(minY)),
            width = Pixels(maxX - minX),
            height = Pixels(maxY - minY)
        )
    }

    /**
     * Calculate the total bounds from a list of render elements.
     *
     * @param elements List of render elements
     * @return Combined bounding rectangle
     */
    fun calculateBounds(elements: List<RenderElement>): AbsoluteRect {
        if (elements.isEmpty()) {
            return AbsoluteRect(AbsolutePoint.ZERO, Pixels.ZERO, Pixels.ZERO)
        }

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (element in elements) {
            val bounds = element.hitBox
            minX = minOf(minX, bounds.origin.x.value)
            minY = minOf(minY, bounds.origin.y.value)
            maxX = maxOf(maxX, bounds.origin.x.value + bounds.width.value)
            maxY = maxOf(maxY, bounds.origin.y.value + bounds.height.value)
        }

        return AbsoluteRect(
            origin = AbsolutePoint(Pixels(minX), Pixels(minY)),
            width = Pixels(maxX - minX),
            height = Pixels(maxY - minY)
        )
    }

    /**
     * Calculate the total bounds from a list of render commands.
     *
     * @param commands List of render commands
     * @return Combined bounding rectangle
     */
    fun calculateBoundsFromCommands(commands: List<RenderCommand>): AbsoluteRect {
        if (commands.isEmpty()) {
            return AbsoluteRect(AbsolutePoint.ZERO, Pixels.ZERO, Pixels.ZERO)
        }

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (command in commands) {
            val bounds = command.bounds
            minX = minOf(minX, bounds.origin.x.value)
            minY = minOf(minY, bounds.origin.y.value)
            maxX = maxOf(maxX, bounds.origin.x.value + bounds.width.value)
            maxY = maxOf(maxY, bounds.origin.y.value + bounds.height.value)
        }

        return AbsoluteRect(
            origin = AbsolutePoint(Pixels(minX), Pixels(minY)),
            width = Pixels(maxX - minX),
            height = Pixels(maxY - minY)
        )
    }

    /**
     * Merge multiple bounding rectangles into one.
     *
     * @param rects List of rectangles to merge
     * @return Combined bounding rectangle
     */
    fun mergeBounds(rects: List<AbsoluteRect>): AbsoluteRect {
        if (rects.isEmpty()) {
            return AbsoluteRect(AbsolutePoint.ZERO, Pixels.ZERO, Pixels.ZERO)
        }

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (rect in rects) {
            minX = minOf(minX, rect.origin.x.value)
            minY = minOf(minY, rect.origin.y.value)
            maxX = maxOf(maxX, rect.origin.x.value + rect.width.value)
            maxY = maxOf(maxY, rect.origin.y.value + rect.height.value)
        }

        return AbsoluteRect(
            origin = AbsolutePoint(Pixels(minX), Pixels(minY)),
            width = Pixels(maxX - minX),
            height = Pixels(maxY - minY)
        )
    }
}
