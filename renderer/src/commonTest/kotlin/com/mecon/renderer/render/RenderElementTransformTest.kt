package com.mecon.renderer.render

import com.mecon.api.render.RenderColor
import com.mecon.renderer.geometry.AbsoluteCubicBezier
import com.mecon.renderer.geometry.AbsolutePath
import com.mecon.renderer.geometry.AbsolutePathSegment
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.geometry.Pixels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RenderElementTransformTest {
    private val elementId = RenderElementId.global(1)

    private fun rect(x: Float, y: Float, w: Float = 10f, h: Float = 10f) =
        AbsoluteRect(AbsolutePoint(Pixels(x), Pixels(y)), Pixels(w), Pixels(h))

    @Test
    fun zeroDeltaReturnsSameInstance() {
        val el = RenderElement(elementId, RenderElementType.NOTE, emptyList(), rect(5f, 5f))
        assertSame(el, el.translatedBy(0f, 0f))
    }

    @Test
    fun shiftsHitBoxAndCommands() {
        val line = DrawLine(
            start = AbsolutePoint(Pixels(0f), Pixels(0f)),
            end = AbsolutePoint(Pixels(10f), Pixels(20f)),
            thickness = Pixels(1f),
            bounds = rect(0f, 0f, 10f, 20f)
        )
        val glyph = DrawGlyph(
            position = AbsolutePoint(Pixels(4f), Pixels(8f)),
            glyph = com.mecon.renderer.smufl.GlyphInfo(
                name = "test", codepoint = 'A'
            ),
            fontSize = Pixels(12f),
            bounds = rect(4f, 8f)
        )
        val el = RenderElement(
            id = elementId,
            type = RenderElementType.NOTE,
            commands = listOf(line, glyph),
            hitBox = rect(2f, 100f)
        )

        val moved = el.translatedBy(0f, -100f)

        // Hit box Y shifted, X unchanged.
        assertEquals(2f, moved.hitBox.origin.x.value)
        assertEquals(0f, moved.hitBox.origin.y.value)

        val movedLine = moved.commands[0] as DrawLine
        assertEquals(-100f, movedLine.start.y.value)
        assertEquals(-80f, movedLine.end.y.value)
        assertEquals(10f, movedLine.end.x.value) // X untouched

        val movedGlyph = moved.commands[1] as DrawGlyph
        assertEquals(4f, movedGlyph.position.x.value)
        assertEquals(-92f, movedGlyph.position.y.value)
    }

    @Test
    fun shiftsPathAndBezierSegments() {
        val path = DrawPath(
            path = AbsolutePath(
                listOf(
                    AbsolutePathSegment.MoveTo(AbsolutePoint(Pixels(1f), Pixels(2f))),
                    AbsolutePathSegment.CubicTo(
                        AbsolutePoint(Pixels(3f), Pixels(4f)),
                        AbsolutePoint(Pixels(5f), Pixels(6f)),
                        AbsolutePoint(Pixels(7f), Pixels(8f))
                    ),
                    AbsolutePathSegment.Close
                )
            ),
            bounds = rect(1f, 2f)
        )
        val bezier = DrawBezier(
            curve = AbsoluteCubicBezier(
                p0 = AbsolutePoint(Pixels(0f), Pixels(0f)),
                p1 = AbsolutePoint(Pixels(1f), Pixels(1f)),
                p2 = AbsolutePoint(Pixels(2f), Pixels(2f)),
                p3 = AbsolutePoint(Pixels(3f), Pixels(3f))
            ),
            color = RenderColor.BLACK,
            endpointThickness = Pixels(1f),
            midpointThickness = Pixels(2f),
            bounds = rect(0f, 0f)
        )
        val el = RenderElement(elementId, RenderElementType.SLUR, listOf(path, bezier), rect(0f, 50f))

        val moved = el.translatedBy(10f, 5f)

        val movedPath = (moved.commands[0] as DrawPath).path
        val move = movedPath.segments[0] as AbsolutePathSegment.MoveTo
        assertEquals(11f, move.point.x.value)
        assertEquals(7f, move.point.y.value)
        val cubic = movedPath.segments[1] as AbsolutePathSegment.CubicTo
        assertEquals(17f, cubic.end.x.value)
        assertEquals(13f, cubic.end.y.value)

        val movedCurve = (moved.commands[1] as DrawBezier).curve
        assertEquals(10f, movedCurve.p0.x.value)
        assertEquals(5f, movedCurve.p0.y.value)
        assertEquals(13f, movedCurve.p3.x.value)
        assertEquals(8f, movedCurve.p3.y.value)
    }
}
