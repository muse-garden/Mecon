package com.mecon.renderer.geometry

import com.mecon.api.render.RenderColor
import com.mecon.renderer.render.CoordinateTransformer
import com.mecon.renderer.render.DrawText
import com.mecon.renderer.render.FontStyle
import com.mecon.renderer.render.RenderCommand
import com.mecon.renderer.render.RenderHelpers
import com.mecon.renderer.render.TextAlignment
import com.mecon.renderer.smufl.BravuraFont
import com.mecon.renderer.smufl.GlyphInfo
import kotlinx.serialization.Serializable

/** One measured part of a mixed serif-text / SMuFL tempo mark. */
@Serializable
sealed interface TempoMarkPart {
    val x: StaffSpace
    val width: StaffSpace

    @Serializable
    data class Text(
        val value: String,
        override val x: StaffSpace,
        override val width: StaffSpace,
        val italic: Boolean = false,
    ) : TempoMarkPart

    @Serializable
    data class Glyph(
        val value: GlyphInfo,
        override val x: StaffSpace,
        override val width: StaffSpace,
        val baselineOffset: StaffSpace,
    ) : TempoMarkPart
}

/** Mixed tempo text and metronome-note glyphs sharing one collision/hit-test box. */
@Serializable
data class TempoMarkGeometry(
    val parts: List<TempoMarkPart>,
    val topLeft: RelativePoint,
    val textSize: StaffSpace,
    override val bounds: RelativeRect,
) : DrawableGeometry {
    context(BravuraFont)
    override fun draw(offset: RelativePoint, transformer: CoordinateTransformer): List<RenderCommand> =
        parts.map { part ->
            val x = topLeft.x + part.x + offset.x
            when (part) {
                is TempoMarkPart.Text -> {
                    val y = topLeft.y + textSize * 0.82f + offset.y
                    val abs = transformer.toAbsolute(RelativePoint(x, y))
                    val boundsOrigin = transformer.toAbsolute(RelativePoint(x, topLeft.y + offset.y))
                    val width = transformer.toPixels(part.width)
                    val height = transformer.toPixels(textSize * 1.2f)
                    DrawText(
                        position = abs,
                        text = part.value,
                        fontFamily = "serif",
                        fontSize = transformer.toPixels(textSize),
                        fontStyle = if (part.italic) FontStyle.ITALIC else FontStyle.NORMAL,
                        color = RenderColor.BLACK,
                        alignment = TextAlignment.LEFT,
                        bounds = AbsoluteRect(boundsOrigin, width, height),
                    )
                }
                is TempoMarkPart.Glyph -> RenderHelpers.createGlyphCommand(
                    glyph = part.value,
                    origin = transformer.toAbsolute(RelativePoint(
                        x,
                        topLeft.y + part.baselineOffset + offset.y,
                    )),
                    fontSize = transformer.toPixels(StaffSpace(4f)),
                )
            }
        }
}
