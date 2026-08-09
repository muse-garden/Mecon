package com.mecon.renderer.geometry

import com.mecon.api.render.RenderColor
import com.mecon.api.storage.events.StaffAttachmentPlacement
import com.mecon.renderer.render.CoordinateTransformer
import com.mecon.renderer.render.DrawLine
import com.mecon.renderer.render.DrawText
import com.mecon.renderer.smufl.GlyphInfo
import com.mecon.renderer.render.FontStyle
import com.mecon.renderer.render.RenderCommand
import com.mecon.renderer.render.RenderHelpers
import com.mecon.renderer.render.TextAlignment
import com.mecon.renderer.smufl.BravuraFont

/** Style of the horizontal line connecting a span's two ends. */
enum class SpanLineStyle { SOLID, DASHED, WAVY_TRILL }

/**
 * Content drawn at one end of an [IntervalAttachmentGeometry] span.
 *
 * This is the reusable vocabulary behind every "horizontal line + end content"
 * symbol — octave brackets, `cresc.`/`dim.` text hairpins, piano pedalling,
 * `accel.`/`rit.`, etc. A new symbol of that family needs no new geometry code:
 * pick a [SpanLineStyle] and a [SpanEnd] for each end.
 */
sealed interface SpanEnd {
    /** Nothing drawn; the line runs flush to the endpoint. */
    object None : SpanEnd

    /** A short italic/serif text label (e.g. `8va`, `cresc.`, `Ped.`). */
    data class Text(
        val text: String,
        val fontStyle: FontStyle = FontStyle.ITALIC,
        val fontFamily: String = "serif",
        /** Mean glyph advance as a fraction of the font size, for width estimation. */
        val widthFactor: Float = 0.5f,
    ) : SpanEnd

    /** A Bravura/SMuFL glyph at the span end (for example the `tr` sign). */
    data class Glyph(val glyph: GlyphInfo) : SpanEnd

    /**
     * An ornament glyph with optional auxiliary-note accidentals. Accidentals belong to the
     * start content so a split span renders them only on its first system.
     */
    data class Ornament(
        val glyph: GlyphInfo,
        val upperAccidental: GlyphInfo? = null,
        val lowerAccidental: GlyphInfo? = null,
    ) : SpanEnd

    /** A short vertical hook closing the span toward the staff (e.g. an 8va bracket). */
    data class Hook(val size: StaffSpace) : SpanEnd

    /** A volta-style start corner: vertical hook plus a label immediately to its right. */
    data class LabeledHook(
        val text: Text,
        val size: StaffSpace,
    ) : SpanEnd
}

/**
 * Generic span attachment: a horizontal line between two endpoints with
 * configurable content at each end.
 *
 * Coordinates are relative to the attachment anchor: X is system staff-space,
 * Y is relative to the band's vertical centre ([yCenter]). The line sits on the
 * text baseline so labels and the connecting line read as one unit.
 *
 * Crescendo/diminuendo **wedges** are *not* drawn here — they have their own
 * [HairpinGeometry]. Everything else expressible as a line plus end content
 * (octave brackets, text hairpins, pedalling, tempo changes) flows through here.
 */
data class IntervalAttachmentGeometry(
    val startX: StaffSpace,
    val endX: StaffSpace,
    /** Vertical centre of the band, relative to the staff centre. */
    val yCenter: StaffSpace,
    val lineStyle: SpanLineStyle,
    val startContent: SpanEnd,
    val endContent: SpanEnd,
    /** Side the span sits on; decides which way a [SpanEnd.Hook] points (toward the staff). */
    val placement: StaffAttachmentPlacement,
    val thickness: StaffSpace,
    val textSize: StaffSpace,
    /** Gap between a text label and the start of the line. */
    val textGap: StaffSpace,
    override val bounds: RelativeRect,
    /** Suppressed on continuation segments so a split span shows its start content once. */
    val showStartContent: Boolean = true,
    /** Suppressed on non-final segments so a split span shows its end content once. */
    val showEndContent: Boolean = true,
) : DrawableGeometry {

    private fun SpanEnd.Text.width(): StaffSpace =
        StaffSpace(text.length * textSize.value * widthFactor)

    context(BravuraFont)
    override fun draw(
        offset: RelativePoint,
        transformer: CoordinateTransformer
    ): List<RenderCommand> {
        val commands = mutableListOf<RenderCommand>()

        // Text box top sits 0.4*textSize above the band centre; the line rests on the
        // text baseline, ~0.75*textSize below the box top (serif italic metrics).
        val textTopRelY = yCenter - StaffSpace(textSize.value * 0.4f)
        val lineRelY = textTopRelY + StaffSpace(textSize.value * 0.75f)
        val fontPx = transformer.toPixels(textSize)

        // The line runs from after the start content to before the end content.
        // Only a text end shortens the line; a hook shares the line's endpoint corner.
        var lineStart = startX
        var lineEnd = endX

        if (showStartContent) when (val c = startContent) {
            is SpanEnd.Text -> {
                commands.add(text(c, startX, textTopRelY, fontPx, offset, transformer))
                lineStart = startX + c.width() + textGap
            }
            is SpanEnd.Hook -> commands.add(hook(startX, lineRelY, c.size, offset, transformer))
            is SpanEnd.LabeledHook -> {
                commands.add(hook(startX, lineRelY, c.size, offset, transformer))
                commands.add(text(c.text, startX + textGap, textTopRelY, fontPx, offset, transformer))
                lineStart = startX + textGap + c.text.width() + textGap
            }
            is SpanEnd.Glyph -> {
                val bbox = this@BravuraFont.getBBox(c.glyph)
                if (bbox != null) {
                    val origin = RelativePoint(
                        startX - bbox.southWest.x,
                        yCenter + bbox.height / 2f + bbox.southWest.y,
                    )
                    commands += GlyphGeometry.fromBBox(c.glyph, origin, bbox).draw(offset, transformer)
                    lineStart = startX + bbox.width + textGap
                }
            }
            is SpanEnd.Ornament -> {
                val width = commands.drawOrnament(c, startX, yCenter, offset, transformer, alignRight = false)
                lineStart = startX + width + textGap
            }
            SpanEnd.None -> {}
        }
        if (showEndContent) when (val c = endContent) {
            is SpanEnd.Text -> {
                commands.add(text(c, endX - c.width(), textTopRelY, fontPx, offset, transformer))
                lineEnd = endX - c.width() - textGap
            }
            is SpanEnd.Hook -> commands.add(hook(endX, lineRelY, c.size, offset, transformer))
            is SpanEnd.LabeledHook -> {
                commands.add(hook(endX, lineRelY, c.size, offset, transformer))
                commands.add(text(c.text, endX - c.text.width(), textTopRelY, fontPx, offset, transformer))
                lineEnd = endX - c.text.width() - textGap
            }
            is SpanEnd.Glyph -> {
                val bbox = this@BravuraFont.getBBox(c.glyph)
                if (bbox != null) {
                    val origin = RelativePoint(
                        endX - bbox.width - bbox.southWest.x,
                        yCenter + bbox.height / 2f + bbox.southWest.y,
                    )
                    commands += GlyphGeometry.fromBBox(c.glyph, origin, bbox).draw(offset, transformer)
                    lineEnd = endX - bbox.width - textGap
                }
            }
            is SpanEnd.Ornament -> {
                val width = commands.drawOrnament(c, endX, yCenter, offset, transformer, alignRight = true)
                lineEnd = endX - width - textGap
            }
            SpanEnd.None -> {}
        }

        if (lineStart < lineEnd) {
            if (lineStyle == SpanLineStyle.WAVY_TRILL) {
                val glyph = com.mecon.renderer.smufl.SmuflGlyphs.wiggleTrill
                val bbox = this@BravuraFont.getBBox(glyph)
                if (bbox != null) {
                    var x = lineStart
                    val advance = bbox.width.takeIf { it.value > 0.05f } ?: StaffSpace(0.9f)
                    while (x < lineEnd) {
                        val origin = RelativePoint(
                            x - bbox.southWest.x,
                            yCenter + bbox.height / 2f + bbox.southWest.y,
                        )
                        commands += GlyphGeometry.fromBBox(glyph, origin, bbox).draw(offset, transformer)
                        x += advance
                    }
                }
                return commands
            }
            val dash = if (lineStyle == SpanLineStyle.DASHED) {
                val dashLen = transformer.toPixels(StaffSpace(0.5f)).value
                listOf(dashLen, dashLen)
            } else null
            commands.add(
                line(
                    from = RelativePoint(lineStart, lineRelY),
                    to = RelativePoint(lineEnd, lineRelY),
                    offset = offset,
                    transformer = transformer,
                    dash = dash,
                )
            )
        }
        return commands
    }

    context(BravuraFont)
    private fun MutableList<RenderCommand>.drawOrnament(
        content: SpanEnd.Ornament,
        anchorX: StaffSpace,
        centerY: StaffSpace,
        offset: RelativePoint,
        transformer: CoordinateTransformer,
        alignRight: Boolean,
    ): StaffSpace {
        val mainBox = this@BravuraFont.getBBox(content.glyph) ?: return StaffSpace.ZERO
        val upperBox = content.upperAccidental?.let(this@BravuraFont::getBBox)
        val lowerBox = content.lowerAccidental?.let(this@BravuraFont::getBBox)
        val width = listOfNotNull(mainBox.width, upperBox?.width, lowerBox?.width).maxOrNull()
            ?: mainBox.width
        val left = if (alignRight) anchorX - width else anchorX
        val gap = StaffSpace(0.18f)
        val totalHeight = mainBox.height +
            (upperBox?.height?.plus(gap) ?: StaffSpace.ZERO) +
            (lowerBox?.height?.plus(gap) ?: StaffSpace.ZERO)
        var top = centerY - totalHeight / 2f

        if (content.upperAccidental != null && upperBox != null) {
            this += GlyphGeometry.fromBBox(
                content.upperAccidental,
                RelativePoint(left + (width - upperBox.width) / 2f - upperBox.southWest.x, top + upperBox.northEast.y),
                upperBox,
            ).draw(offset, transformer)
            top += upperBox.height + gap
        }
        this += GlyphGeometry.fromBBox(
            content.glyph,
            RelativePoint(left + (width - mainBox.width) / 2f - mainBox.southWest.x, top + mainBox.northEast.y),
            mainBox,
        ).draw(offset, transformer)
        top += mainBox.height + gap
        if (content.lowerAccidental != null && lowerBox != null) {
            this += GlyphGeometry.fromBBox(
                content.lowerAccidental,
                RelativePoint(left + (width - lowerBox.width) / 2f - lowerBox.southWest.x, top + lowerBox.northEast.y),
                lowerBox,
            ).draw(offset, transformer)
        }
        return width
    }

    private fun text(
        content: SpanEnd.Text,
        x: StaffSpace,
        topY: StaffSpace,
        fontPx: Pixels,
        offset: RelativePoint,
        transformer: CoordinateTransformer,
    ): DrawText {
        val pos = RelativePoint(x + offset.x, topY + offset.y)
        val absPos = transformer.toAbsolute(pos)
        return DrawText(
            position = absPos,
            text = content.text,
            fontFamily = content.fontFamily,
            fontSize = fontPx,
            fontStyle = content.fontStyle,
            color = RenderColor.BLACK,
            alignment = TextAlignment.LEFT,
            bounds = AbsoluteRect(
                origin = absPos,
                width = transformer.toPixels(content.width()),
                height = fontPx,
            )
        )
    }

    /** A short vertical hook at [x], pointing toward the staff. */
    private fun hook(
        x: StaffSpace,
        lineY: StaffSpace,
        size: StaffSpace,
        offset: RelativePoint,
        transformer: CoordinateTransformer,
    ): DrawLine {
        val endY = if (placement == StaffAttachmentPlacement.ABOVE) lineY + size else lineY - size
        return line(RelativePoint(x, lineY), RelativePoint(x, endY), offset, transformer)
    }

    private fun line(
        from: RelativePoint,
        to: RelativePoint,
        offset: RelativePoint,
        transformer: CoordinateTransformer,
        dash: List<Float>? = null,
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
            dashIntervals = dash,
            bounds = RenderHelpers.calculateLineBounds(abs)
        )
    }
}
