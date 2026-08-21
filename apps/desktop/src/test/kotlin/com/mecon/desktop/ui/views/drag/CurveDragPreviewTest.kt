package com.mecon.desktop.ui.views.drag

import com.mecon.desktop.ui.views.*

import com.mecon.api.interaction.EventSectionId
import com.mecon.api.primitive.EventId
import com.mecon.api.render.RenderColor
import com.mecon.renderer.geometry.AbsolutePathSegment
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.SlurCurveBuilder
import com.mecon.renderer.geometry.SlurDirection
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.render.CoordinateTransformer
import com.mecon.renderer.render.DrawPath
import com.mecon.renderer.render.RenderElement
import com.mecon.renderer.render.RenderElementId
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderResult
import com.mecon.renderer.smufl.EngravingDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CurveDragPreviewTest {
    @Test
    fun previewRebuildsSelectedCurveWithoutMovingItsEndpoints() {
        val transformer = CoordinateTransformer()
        val start = RelativePoint(StaffSpace(1f), StaffSpace(3f))
        val end = RelativePoint(StaffSpace(7f), StaffSpace(3f))
        val originalApex = StaffSpace(0.8f)
        val thickness = EngravingDefaults.BRAVURA.tieMidpointThickness
        val originalPath = SlurCurveBuilder.buildLensPath(
            start = start,
            end = end,
            direction = SlurDirection.ABOVE,
            midpointThickness = thickness,
            minHeight = originalApex,
            maxHeight = originalApex,
            heightUsesHorizontalSpan = true,
        )
        val originalBounds = SlurCurveBuilder.lensBounds(
            start = start,
            end = end,
            direction = SlurDirection.ABOVE,
            midpointThickness = thickness,
            minHeight = originalApex,
            maxHeight = originalApex,
            heightUsesHorizontalSpan = true,
        )
        val elementId = RenderElementId(1L)
        val command = DrawPath(
            path = transformer.toAbsolute(originalPath),
            fillColor = RenderColor.BLACK,
            bounds = transformer.toAbsolute(originalBounds),
        )
        val result = RenderResult(
            elements = listOf(RenderElement(
                id = elementId,
                type = RenderElementType.TIE,
                commands = listOf(command),
                hitBox = command.bounds,
            )),
            bounds = command.bounds,
            firstSystem = 0,
            lastSystem = 0,
            firstMeasure = 1,
            lastMeasure = 1,
            transformerSnapshot = transformer,
        )

        val preview = createCurveDragPreview(
            result,
            CurveDragState(
                kind = CurveKind.TIE,
                sectionId = EventSectionId.voiceTie(EventId("source"), 0),
                elementIds = listOf(elementId),
                above = true,
                startApex = originalApex.value,
                currentApex = 2f,
                slopeDamping = 1f,
                middleStraightening = 0f,
            ),
        )

        val previewPath = (preview.segments.single().commands.single() as DrawPath).path
        val originalSegments = command.path.segments
        val previewSegments = previewPath.segments
        val originalStart = (originalSegments.first() as AbsolutePathSegment.MoveTo).point
        val previewStart = (previewSegments.first() as AbsolutePathSegment.MoveTo).point
        val originalCubics = originalSegments.filterIsInstance<AbsolutePathSegment.CubicTo>()
        val previewCubics = previewSegments.filterIsInstance<AbsolutePathSegment.CubicTo>()

        assertEquals(originalStart, previewStart)
        assertEquals(originalCubics.first().end, previewCubics.first().end)
        assertTrue(
            previewCubics.first().control1.y.value < originalCubics.first().control1.y.value,
            "increasing an above curve's apex must move its outer control point upward",
        )
    }
}
