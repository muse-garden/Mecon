package com.mecon.desktop.export

import com.mecon.api.render.RenderColor
import com.mecon.renderer.geometry.AbsolutePathSegment
import com.mecon.renderer.render.DrawBezier
import com.mecon.renderer.render.DrawEllipse
import com.mecon.renderer.render.DrawGlyph
import com.mecon.renderer.render.DrawLine
import com.mecon.renderer.render.DrawPath
import com.mecon.renderer.render.DrawRect
import com.mecon.renderer.render.DrawText
import com.mecon.renderer.render.LineCap
import com.mecon.renderer.render.RenderCommand
import com.mecon.renderer.render.RenderGroup
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import java.awt.Shape
import java.awt.geom.PathIterator

/**
 * Replays a list of [RenderCommand]s onto one PDFBox [PDPageContentStream].
 *
 * The content stream must already carry a CTM that maps the renderer's design-pixel space (y-down,
 * origin at the page's top-left) to PDF points, so every coordinate here is passed through verbatim
 * in design pixels. Each command is wrapped in a graphics-state save/restore so per-command colour,
 * alpha, dash and line width never leak into the next.
 *
 * Glyphs and text are drawn as filled vector outlines produced by [AwtGlyphOutliner]; every other
 * command maps directly to PDF path operators. This mirrors [com.mecon.desktop.ui.views.ComposeScoreRenderer]
 * on the Skia side — same commands, different backend.
 */
class PdfContentWriter(
    private val cs: PDPageContentStream,
    private val outliner: AwtGlyphOutliner,
) {
    fun write(commands: List<RenderCommand>) {
        for (command in commands) write(command)
    }

    private fun write(command: RenderCommand) {
        when (command) {
            is DrawLine -> line(command)
            is DrawRect -> rect(command)
            is DrawEllipse -> ellipse(command)
            is DrawPath -> path(command)
            is DrawBezier -> bezier(command)
            is DrawGlyph -> glyph(command)
            is DrawText -> text(command)
            is RenderGroup -> command.commands.forEach { write(it) } // opacity/clip intentionally flattened
        }
    }

    // --- Primitives ---------------------------------------------------------------------------

    private fun line(c: DrawLine) = withState(strokeColor = c.color) {
        cs.setLineWidth(c.thickness.value)
        cs.setLineCapStyle(
            when (c.cap) {
                LineCap.BUTT -> 0
                LineCap.ROUND -> 1
                LineCap.SQUARE -> 2
            }
        )
        c.dashIntervals?.let { if (it.isNotEmpty()) cs.setLineDashPattern(it.toFloatArray(), 0f) }
        cs.moveTo(c.start.x.value, c.start.y.value)
        cs.lineTo(c.end.x.value, c.end.y.value)
        cs.stroke()
    }

    private fun rect(c: DrawRect) = withState(fillColor = c.fillColor, strokeColor = c.strokeColor) {
        if (c.fillColor == null && c.strokeColor == null) return@withState // no paint → don't leave a dangling path
        cs.addRect(c.rect.origin.x.value, c.rect.origin.y.value, c.rect.width.value, c.rect.height.value)
        if (c.strokeColor != null) cs.setLineWidth(c.strokeThickness.value)
        paint(fill = c.fillColor != null, stroke = c.strokeColor != null, evenOdd = false)
    }

    private fun ellipse(c: DrawEllipse) = withState(fillColor = c.fillColor, strokeColor = c.strokeColor) {
        if (c.fillColor == null && c.strokeColor == null) return@withState
        val cx = c.center.x.value
        val cy = c.center.y.value
        val rx = c.radiusX.value
        val ry = c.radiusY.value
        val k = 0.5522847498f // cubic-bezier circle approximation constant
        cs.moveTo(cx + rx, cy)
        cs.curveTo(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry)
        cs.curveTo(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy)
        cs.curveTo(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry)
        cs.curveTo(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy)
        cs.closePath()
        if (c.strokeColor != null) cs.setLineWidth(c.strokeThickness.value)
        paint(fill = c.fillColor != null, stroke = c.strokeColor != null, evenOdd = false)
    }

    private fun path(c: DrawPath) = withState(fillColor = c.fillColor, strokeColor = c.strokeColor) {
        if (c.fillColor == null && c.strokeColor == null) return@withState
        var curX = 0f
        var curY = 0f
        for (seg in c.path.segments) {
            when (seg) {
                is AbsolutePathSegment.MoveTo -> {
                    cs.moveTo(seg.point.x.value, seg.point.y.value); curX = seg.point.x.value; curY = seg.point.y.value
                }
                is AbsolutePathSegment.LineTo -> {
                    cs.lineTo(seg.point.x.value, seg.point.y.value); curX = seg.point.x.value; curY = seg.point.y.value
                }
                is AbsolutePathSegment.QuadTo -> {
                    quadTo(seg.control.x.value, seg.control.y.value, seg.end.x.value, seg.end.y.value, curX, curY)
                    curX = seg.end.x.value; curY = seg.end.y.value
                }
                is AbsolutePathSegment.CubicTo -> {
                    cs.curveTo(
                        seg.control1.x.value, seg.control1.y.value,
                        seg.control2.x.value, seg.control2.y.value,
                        seg.end.x.value, seg.end.y.value,
                    )
                    curX = seg.end.x.value; curY = seg.end.y.value
                }
                AbsolutePathSegment.Close -> cs.closePath()
            }
        }
        if (c.strokeColor != null) cs.setLineWidth(c.strokeThickness.value)
        paint(fill = c.fillColor != null, stroke = c.strokeColor != null, evenOdd = false)
    }

    private fun bezier(c: DrawBezier) = withState(strokeColor = c.color) {
        // Mirrors ComposeScoreRenderer: filled slurs/ties are approximated by a round-capped stroke at
        // the midpoint thickness; a plain curve strokes at the endpoint thickness.
        cs.setLineWidth(if (c.filled) c.midpointThickness.value else c.endpointThickness.value)
        cs.setLineCapStyle(1)
        cs.moveTo(c.curve.p0.x.value, c.curve.p0.y.value)
        cs.curveTo(
            c.curve.p1.x.value, c.curve.p1.y.value,
            c.curve.p2.x.value, c.curve.p2.y.value,
            c.curve.p3.x.value, c.curve.p3.y.value,
        )
        cs.stroke()
    }

    // --- Glyph / text via AWT outlines --------------------------------------------------------

    private fun glyph(c: DrawGlyph) {
        val shape = outliner.glyphOutline(
            codepoint = c.glyph.codepoint,
            sizePx = c.fontSize.value,
            x = c.position.x.value,
            y = c.position.y.value,
            scaleX = c.scaleX,
            scaleY = c.scaleY,
        )
        fillShape(shape, c.color)
    }

    private fun text(c: DrawText) {
        val shape = outliner.textOutline(c) ?: return
        fillShape(shape, c.color)
    }

    // --- Helpers ------------------------------------------------------------------------------

    private fun fillShape(shape: Shape, color: RenderColor) = withState(fillColor = color) {
        val evenOdd = appendShape(shape)
        paint(fill = true, stroke = false, evenOdd = evenOdd)
    }

    /** Emit [shape] as PDF path operators (quadratics promoted to cubics). Returns true for even-odd winding. */
    private fun appendShape(shape: Shape): Boolean {
        val it = shape.getPathIterator(null)
        val coords = FloatArray(6)
        var curX = 0f
        var curY = 0f
        while (!it.isDone) {
            when (it.currentSegment(coords)) {
                PathIterator.SEG_MOVETO -> { cs.moveTo(coords[0], coords[1]); curX = coords[0]; curY = coords[1] }
                PathIterator.SEG_LINETO -> { cs.lineTo(coords[0], coords[1]); curX = coords[0]; curY = coords[1] }
                PathIterator.SEG_QUADTO -> {
                    quadTo(coords[0], coords[1], coords[2], coords[3], curX, curY)
                    curX = coords[2]; curY = coords[3]
                }
                PathIterator.SEG_CUBICTO -> {
                    cs.curveTo(coords[0], coords[1], coords[2], coords[3], coords[4], coords[5])
                    curX = coords[4]; curY = coords[5]
                }
                PathIterator.SEG_CLOSE -> cs.closePath()
            }
            it.next()
        }
        return it.windingRule == PathIterator.WIND_EVEN_ODD
    }

    /** Promote a quadratic (control q, end p1) from current point (x0,y0) to a cubic, then emit it. */
    private fun quadTo(qx: Float, qy: Float, x1: Float, y1: Float, x0: Float, y0: Float) {
        val c1x = x0 + 2f / 3f * (qx - x0)
        val c1y = y0 + 2f / 3f * (qy - y0)
        val c2x = x1 + 2f / 3f * (qx - x1)
        val c2y = y1 + 2f / 3f * (qy - y1)
        cs.curveTo(c1x, c1y, c2x, c2y, x1, y1)
    }

    private fun paint(fill: Boolean, stroke: Boolean, evenOdd: Boolean) {
        when {
            fill && stroke -> if (evenOdd) cs.fillAndStrokeEvenOdd() else cs.fillAndStroke()
            fill -> if (evenOdd) cs.fillEvenOdd() else cs.fill()
            stroke -> cs.stroke()
        }
    }

    /**
     * Run [body] inside a graphics-state save/restore, applying fill / stroke colours and, when either
     * carries a non-opaque alpha, a transparency ext-gstate. Keeps per-command style from leaking.
     */
    private inline fun withState(
        fillColor: RenderColor? = null,
        strokeColor: RenderColor? = null,
        body: () -> Unit,
    ) {
        cs.saveGraphicsState()
        try {
            fillColor?.let {
                cs.setNonStrokingColor(java.awt.Color(it.red, it.green, it.blue))
            }
            strokeColor?.let {
                cs.setStrokingColor(java.awt.Color(it.red, it.green, it.blue))
            }
            val minAlpha = listOfNotNull(fillColor?.alpha, strokeColor?.alpha).minOrNull()
            if (minAlpha != null && minAlpha < 255) {
                val a = minAlpha / 255f
                val gs = PDExtendedGraphicsState()
                gs.setNonStrokingAlphaConstant(a)
                gs.setStrokingAlphaConstant(a)
                cs.setGraphicsStateParameters(gs)
            }
            body()
        } finally {
            cs.restoreGraphicsState()
        }
    }
}
