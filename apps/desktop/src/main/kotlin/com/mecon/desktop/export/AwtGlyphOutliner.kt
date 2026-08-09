package com.mecon.desktop.export

import com.mecon.api.render.FormattedText
import com.mecon.api.render.TextBaseline
import com.mecon.renderer.render.DrawText
import com.mecon.renderer.render.FontStyle
import com.mecon.renderer.render.FontWeight
import com.mecon.renderer.render.TextAlignment
import java.awt.Font
import java.awt.Shape
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.awt.geom.Area

/**
 * Turns music glyphs and text into vector [Shape] outlines using AWT font tooling.
 *
 * The PDF backend draws every glyph as a filled vector path rather than as PDF text, so the export
 * needs no font embedding / subsetting / CMap plumbing and renders any character the host JVM can
 * shape — including CJK titles — with the exact outlines of the underlying font files. Music glyphs
 * use the bundled Bravura OTF (matching the on-screen Skia rendering, which reads the same file);
 * text glyphs use logical AWT fonts (`Serif` / `SansSerif`), which resolve to real system fonts with
 * broad Unicode coverage.
 *
 * All coordinates returned are in the renderer's design-pixel space (y-down, baseline at the given
 * origin) — the same space [DrawText.position] / `DrawGlyph.position` live in — so the caller can emit
 * them straight into a page whose CTM maps design pixels to PDF points.
 */
class AwtGlyphOutliner(bravuraOtf: ByteArray) {

    // Outlines are resolution-independent, so metrics quality is irrelevant here; a plain context is
    // enough. Fractional metrics keep advance widths continuous for multi-run text alignment.
    private val frc = FontRenderContext(null, true, true)

    private val bravuraBase: Font =
        bravuraOtf.inputStream().use { Font.createFont(Font.TRUETYPE_FONT, it) }

    // Fonts are immutable and cheap to derive but not free; cache per (family, size, style) key.
    private val fontCache = HashMap<String, Font>()

    private fun deriveBravura(sizePx: Float): Font =
        fontCache.getOrPut("bravura@$sizePx") { bravuraBase.deriveFont(sizePx) }

    private fun textFont(family: String, sizePx: Float, bold: Boolean, italic: Boolean): Font {
        var style = Font.PLAIN
        if (bold) style = style or Font.BOLD
        if (italic) style = style or Font.ITALIC
        val logical = when {
            family.equals("serif", ignoreCase = true) -> Font.SERIF
            else -> Font.SANS_SERIF // "Arial", "sans-serif", anything else -> a broad sans fallback
        }
        return fontCache.getOrPut("$logical/$style@$sizePx") { Font(logical, style, 1).deriveFont(sizePx) }
    }

    /**
     * Outline for a single music glyph whose baseline origin is at ([x], [y]) in design pixels,
     * optionally scaled by ([scaleX], [scaleY]) about that origin (mirroring `DrawGlyph`).
     */
    fun glyphOutline(codepoint: Char, sizePx: Float, x: Float, y: Float, scaleX: Float, scaleY: Float): Shape {
        val font = deriveBravura(sizePx)
        val gv = font.createGlyphVector(frc, codepoint.toString())
        val outline = gv.getOutline(x, y)
        if (scaleX == 1f && scaleY == 1f) return outline
        val at = AffineTransform().apply {
            translate(x.toDouble(), y.toDouble())
            scale(scaleX.toDouble(), scaleY.toDouble())
            translate(-x.toDouble(), -y.toDouble())
        }
        return at.createTransformedShape(outline)
    }

    /**
     * Outline for a whole [DrawText] command, honouring alignment, and per-run size / weight / style /
     * baseline of any [DrawText.richText]. Returns a single combined [Shape] (union of every run), or
     * null when there is nothing to draw. Per-run colour is not applied here — the caller fills the
     * whole shape with the command's colour; rich-text runs currently inherit that colour.
     */
    fun textOutline(command: DrawText): Shape? {
        val rich = command.richText
        val baseSize = command.fontSize.value
        val runs = when {
            rich != null && rich.runs.isNotEmpty() -> rich.runs
            else -> listOf(com.mecon.api.render.TextRun(command.text))
        }.filter { it.text.isNotEmpty() }
        if (runs.isEmpty()) return null

        val baseBold = command.fontWeight >= FontWeight.BOLD
        val baseItalic = command.fontStyle == FontStyle.ITALIC

        // Measure total advance first so CENTER / RIGHT alignment can offset the start x.
        data class Prepared(val gv: java.awt.font.GlyphVector, val advance: Float, val dy: Float)
        val prepared = runs.map { run ->
            val size = baseSize * run.style.sizeScale
            val font = textFont(command.fontFamily, size, baseBold || run.style.bold, baseItalic || run.style.italic)
            val gv = font.createGlyphVector(frc, run.text)
            val dy = when (run.style.baseline) {
                // BaselineShift in Compose is a fraction of the run's metrics; ~0.375em up/down is a
                // close visual match for the superscript/subscript used by chord symbols.
                TextBaseline.SUPERSCRIPT -> -0.375f * size
                TextBaseline.SUBSCRIPT -> 0.375f * size
                TextBaseline.NORMAL -> 0f
            }
            Prepared(gv, gv.logicalBounds.width.toFloat(), dy)
        }

        val total = prepared.sumOf { it.advance.toDouble() }.toFloat()
        var penX = when (command.alignment) {
            TextAlignment.LEFT -> command.position.x.value
            TextAlignment.CENTER -> command.position.x.value - total / 2f
            TextAlignment.RIGHT -> command.position.x.value - total
        }
        val baselineY = command.position.y.value

        val area = Area()
        for (p in prepared) {
            area.add(Area(p.gv.getOutline(penX, baselineY + p.dy)))
            penX += p.advance
        }
        return area
    }
}
