package com.mecon.renderer.render

import com.mecon.api.render.RenderColor
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.layout.TitleAlignment
import com.mecon.renderer.layout.TitleBlockLayout

/** Converts page title block layout into text render elements. */
internal class TitleBlockRenderer(
    private val transformer: CoordinateTransformer,
) {
    fun render(
        block: TitleBlockLayout,
        idGenerator: () -> RenderElementId
    ): List<RenderElement> {
        val out = mutableListOf<RenderElement>()
        for (line in block.lines) {
            if (line.text.isEmpty()) continue
            val pos = transformer.toAbsolute(RelativePoint(line.anchorX, line.topY))
            val fontSizePixels = transformer.toPixels(line.fontSize)
            val alignment = when (line.alignment) {
                TitleAlignment.LEFT -> TextAlignment.LEFT
                TitleAlignment.CENTER -> TextAlignment.CENTER
                TitleAlignment.RIGHT -> TextAlignment.RIGHT
            }
            val widthGuess = Pixels(line.text.length * fontSizePixels.value * APPROX_CHAR_WIDTH_RATIO)
            val boundsOriginX = when (line.alignment) {
                TitleAlignment.LEFT -> pos.x
                TitleAlignment.CENTER -> Pixels(pos.x.value - widthGuess.value * 0.5f)
                TitleAlignment.RIGHT -> Pixels(pos.x.value - widthGuess.value)
            }
            val bounds = AbsoluteRect(
                origin = AbsolutePoint(boundsOriginX, pos.y),
                width = widthGuess,
                height = fontSizePixels
            )
            val cmd = DrawText(
                position = AbsolutePoint(pos.x, pos.y),
                text = line.text,
                fontFamily = "Arial",
                fontSize = fontSizePixels,
                fontWeight = if (line.bold) FontWeight.BOLD else FontWeight.NORMAL,
                fontStyle = if (line.italic) FontStyle.ITALIC else FontStyle.NORMAL,
                color = RenderColor.BLACK,
                alignment = alignment,
                bounds = bounds
            )
            out.add(
                renderElement(idGenerator(), RenderElementType.TEXT_ANNOTATION)
                    .addCommands(listOf(cmd)).hitBox(bounds).build()
            )
        }
        return out
    }

    companion object {
        private const val APPROX_CHAR_WIDTH_RATIO: Float = 0.6f
    }
}
