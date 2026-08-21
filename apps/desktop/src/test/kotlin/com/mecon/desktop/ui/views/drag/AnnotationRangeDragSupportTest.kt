package com.mecon.desktop.ui.views.drag

import com.mecon.desktop.ui.views.*

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.geometry.ScaleFactor
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.render.CoordinateTransformer
import com.mecon.renderer.render.RenderElement
import com.mecon.renderer.render.RenderElementId
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderResult
import com.mecon.renderer.render.RenderedMeasureBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnnotationRangeDragSupportTest {
    @Test
    fun horizontalDragKeepsTheEndpointOnItsSourceSystem() {
        assertEquals(
            2,
            annotationDragTargetSystem(
                sourceSystemIndex = 2,
                sourceRawY = 240f,
                pointerRawY = 246f,
                nearestSystemIndex = 0,
                sourceRowLockPx = 28f,
            ),
        )
        assertEquals(
            0,
            annotationDragTargetSystem(
                sourceSystemIndex = 2,
                sourceRawY = 240f,
                pointerRawY = 100f,
                nearestSystemIndex = 0,
                sourceRowLockPx = 28f,
            ),
        )
    }

    @Test
    fun splitRangeExposesOnlyItsOuterSystemEndpoints() {
        val eventId = EventId("tonal-region")
        var id = 0L
        fun element(system: Int, x0: Float, x1: Float, y: Float) = RenderElement(
            id = RenderElementId(id++),
            type = RenderElementType.TEXT_ANNOTATION,
            commands = emptyList(),
            hitBox = rect(x0, y, x1, y + 8f),
            eventId = eventId,
            // Range fragments intentionally retain the whole range's measure metadata.
            measureNumber = 1,
            endMeasureNumber = 9,
            systemIndex = system,
        )
        val result = RenderResult.EMPTY.copy(
            elements = listOf(
                element(0, 10f, 90f, 0f),
                element(0, 10f, 90f, 10f),
                element(0, 10f, 40f, 20f),
                element(0, 40f, 90f, 20f), // internal key-signature split
                element(1, 10f, 90f, 100f),
                element(1, 10f, 90f, 110f),
                element(2, 10f, 50f, 200f),
                element(2, 10f, 50f, 210f),
                element(2, 10f, 50f, 220f),
            ),
        )

        val endpoints = annotationRangeEndpointPoints(result, eventId)

        val starts = endpoints.filter { it.endpoint == AnnotationRangeEndpoint.START }
        val ends = endpoints.filter { it.endpoint == AnnotationRangeEndpoint.END }
        assertEquals(3, starts.size)
        assertTrue(starts.all { it.point.x.value == 10f && it.point.y.value < 100f })
        assertTrue(starts.all { it.systemIndex == 0 })
        assertEquals(3, ends.size)
        assertTrue(ends.all { it.point.x.value == 50f && it.point.y.value > 200f })
        assertTrue(ends.all { it.systemIndex == 2 })
        assertNull(
            annotationRangeEndpointAt(
                result = result,
                point = AbsolutePoint(Pixels(10f), Pixels(104f)),
                resizableEventIds = setOf(eventId),
                radius = 5f,
            ),
            "a continuation-line edge must not resize the whole tonal region",
        )
    }

    @Test
    fun measureBoundarySnapConvertsStaffSpaceToAbsolutePixels() {
        val result = RenderResult.EMPTY.copy(
            transformerSnapshot = CoordinateTransformer(scale = ScaleFactor(10f)),
            measureBounds = listOf(
                RenderedMeasureBounds(
                    systemIndex = 2,
                    measureNumber = 5,
                    leftX = StaffSpace(10f),
                    rightX = StaffSpace(20f),
                )
            ),
        )

        val snap = resolveAnnotationBoundarySnap(result, absoluteX = 195f, systemIndex = 2)

        assertEquals(TimeCode.of(6, Fraction.ZERO), snap?.time)
        assertEquals(200f, snap?.absoluteX)
    }

    private fun rect(x0: Float, y0: Float, x1: Float, y1: Float) = AbsoluteRect(
        origin = AbsolutePoint(Pixels(x0), Pixels(y0)),
        width = Pixels(x1 - x0),
        height = Pixels(y1 - y0),
    )
}
