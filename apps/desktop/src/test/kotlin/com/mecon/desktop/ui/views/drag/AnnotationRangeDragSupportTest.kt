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
import com.mecon.renderer.render.spatial.HierarchicalSpatialIndex
import com.mecon.renderer.render.spatial.StaffRegion
import com.mecon.renderer.render.spatial.SystemNode
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
        assertEquals(
            4,
            annotationDragTargetSystem(
                sourceSystemIndex = 2,
                sourceRawY = 240f,
                pointerRawY = 240f,
                nearestSystemIndex = 4,
                sourceRowLockPx = 28f,
                pointerOnSourcePage = false,
            ),
            "the row lock must not capture a same-height system on another page",
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

    @Test
    fun crossSystemPreviewProjectsEndpointOntoTargetAnnotationLane() {
        val eventId = EventId("tonal-region")
        val targetLane = RenderElement(
            id = RenderElementId(1L),
            type = RenderElementType.TEXT_ANNOTATION,
            commands = emptyList(),
            hitBox = rect(10f, 82f, 80f, 90f),
            eventId = eventId,
            systemIndex = 1,
        )
        val spatialIndex = HierarchicalSpatialIndex().apply {
            addSystem(system(index = 0, staffCenterY = 10f))
            addSystem(system(index = 1, staffCenterY = 100f))
        }
        val result = RenderResult.EMPTY.copy(
            elements = listOf(targetLane),
            spatialIndex = spatialIndex,
            transformerSnapshot = CoordinateTransformer(scale = ScaleFactor(1f)),
        )

        val targetY = annotationDragTargetY(
            result = result,
            eventId = eventId,
            sourceSystemIndex = 0,
            targetSystemIndex = 1,
            sourceY = Pixels(-4f),
        )

        assertEquals(86f, targetY.value)
    }

    @Test
    fun crossSystemPreviewPreservesLaneOffsetWhenTargetHasNoRangeFragment() {
        val spatialIndex = HierarchicalSpatialIndex().apply {
            addSystem(system(index = 0, staffCenterY = 10f))
            addSystem(system(index = 1, staffCenterY = 100f))
        }
        val result = RenderResult.EMPTY.copy(
            spatialIndex = spatialIndex,
            transformerSnapshot = CoordinateTransformer(scale = ScaleFactor(1f)),
        )

        val targetY = annotationDragTargetY(
            result = result,
            eventId = EventId("tonal-region"),
            sourceSystemIndex = 0,
            targetSystemIndex = 1,
            sourceY = Pixels(-4f),
        )

        assertEquals(86f, targetY.value)
    }

    private fun rect(x0: Float, y0: Float, x1: Float, y1: Float) = AbsoluteRect(
        origin = AbsolutePoint(Pixels(x0), Pixels(y0)),
        width = Pixels(x1 - x0),
        height = Pixels(y1 - y0),
    )

    private fun system(index: Int, staffCenterY: Float) = SystemNode(
        systemIndex = index,
        measureCount = 1,
        staffRegions = listOf(
            StaffRegion(
                staffIndex = 0,
                centerY = StaffSpace(staffCenterY),
                topY = StaffSpace(staffCenterY - 4f),
                bottomY = StaffSpace(staffCenterY + 4f),
            )
        ),
        topY = StaffSpace(staffCenterY - 6f),
        bottomY = StaffSpace(staffCenterY + 6f),
        startX = StaffSpace.ZERO,
    )
}
