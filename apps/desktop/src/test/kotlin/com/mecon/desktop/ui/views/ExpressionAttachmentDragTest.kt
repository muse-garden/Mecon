package com.mecon.desktop.ui.views

import com.mecon.api.render.RenderColor
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.render.DrawLine
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import kotlin.test.Test
import kotlin.test.assertEquals

class ExpressionAttachmentDragTest {
    @Test
    fun livePreviewWarpsBothSpanEndpointsIndependently() {
        val start = AbsolutePoint(Pixels(10f), Pixels(20f))
        val end = AbsolutePoint(Pixels(50f), Pixels(20f))
        val line = DrawLine(
            start = start,
            end = end,
            thickness = Pixels(1f),
            color = RenderColor.BLACK,
            bounds = AbsoluteRect(start, Pixels(40f), Pixels(1f)),
        )

        val warped = warpAttachmentCommands(
            listOf(line), start, end,
            AbsolutePoint(Pixels(15f), Pixels(16f)),
            AbsolutePoint(Pixels(60f), Pixels(28f)),
        ).single() as DrawLine

        assertEquals(15f, warped.start.x.value)
        assertEquals(16f, warped.start.y.value)
        assertEquals(60f, warped.end.x.value)
        assertEquals(28f, warped.end.y.value)
    }

    @Test
    fun livePreviewTranslatesPointAttachmentAsOneUnit() {
        val start = AbsolutePoint(Pixels(10f), Pixels(20f))
        val line = DrawLine(
            start = start,
            end = AbsolutePoint(Pixels(14f), Pixels(20f)),
            thickness = Pixels(1f),
            bounds = AbsoluteRect(start, Pixels(4f), Pixels(1f)),
        )

        val warped = warpAttachmentCommands(
            listOf(line), start, null,
            AbsolutePoint(Pixels(13f), Pixels(25f)), null,
        ).single() as DrawLine

        assertEquals(13f, warped.start.x.value)
        assertEquals(25f, warped.start.y.value)
        assertEquals(17f, warped.end.x.value)
        assertEquals(25f, warped.end.y.value)
    }

    @Test
    fun dynamicGuideNeverChoosesANoteheadFromAnotherSystem() {
        val time = TimeCode.of(17, Fraction.ZERO)
        val sameSystem = AbsolutePoint(Pixels(100f), Pixels(200f))
        val lowerSystemButGeometricallyCloser = AbsolutePoint(Pixels(100f), Pixels(240f))
        val symbol = AbsolutePoint(Pixels(100f), Pixels(250f))

        val anchor = chooseNearestNoteheadAnchor(
            candidates = listOf(
                NoteheadAnchorCandidate(sameSystem, time, staffIndex = 0, systemIndex = 4),
                NoteheadAnchorCandidate(lowerSystemButGeometricallyCloser, time, staffIndex = 0, systemIndex = 5),
            ),
            time = time,
            staffIndex = 0,
            systemIndex = 4,
            symbol = symbol,
        )

        assertEquals(sameSystem, anchor)
    }
}
