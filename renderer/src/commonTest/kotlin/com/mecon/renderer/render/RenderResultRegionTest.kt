package com.mecon.renderer.render

import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.geometry.ScaleFactor
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.render.spatial.HierarchicalSpatialIndex
import com.mecon.renderer.render.spatial.ScoreHittableElement
import com.mecon.renderer.render.spatial.StaffRegion
import com.mecon.renderer.render.spatial.SystemNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [RenderResult.hitTestRegion]: the absolute→relative transform and the configurable
 * type filter that backs the marquee (box) selection scope.
 */
class RenderResultRegionTest {
    private val noteId = RenderElementId.system(0, 0)
    private val barlineId = RenderElementId.system(0, 1)

    /** Index with one system (one staff, one 30-wide measure) holding a note and a barline. */
    private fun buildResult(): RenderResult {
        val index = HierarchicalSpatialIndex()
        val system = SystemNode(
            systemIndex = 0,
            measureCount = 1,
            staffRegions = listOf(
                StaffRegion(
                    staffIndex = 0,
                    centerY = StaffSpace(5f),
                    topY = StaffSpace(0f),
                    bottomY = StaffSpace(10f),
                )
            ),
            topY = StaffSpace(0f),
            bottomY = StaffSpace(10f),
            startX = StaffSpace(0f),
        )
        system.setMeasureWidth(0, StaffSpace(30f))
        // Both at local x≈5 (score X [5,6]), y around the staff centre.
        system.getCell(0, 0).add(
            ScoreHittableElement(
                elementId = noteId,
                type = RenderElementType.NOTEHEAD,
                sections = emptyList(),
                relativeBounds = RelativeRect(
                    origin = RelativePoint(StaffSpace(5f), StaffSpace(-0.5f)),
                    width = StaffSpace(1f), height = StaffSpace(1f),
                ),
            )
        )
        system.getCell(0, 0).add(
            ScoreHittableElement(
                elementId = barlineId,
                type = RenderElementType.BARLINE,
                sections = emptyList(),
                relativeBounds = RelativeRect(
                    origin = RelativePoint(StaffSpace(5f), StaffSpace(-4f)),
                    width = StaffSpace(0.5f), height = StaffSpace(8f),
                ),
            )
        )
        index.addSystem(system)

        // scale = 1 → absolute pixels map 1:1 to staff space, so the rectangle below is in the
        // same numbers as the index coordinates.
        val transformer = CoordinateTransformer(scale = ScaleFactor(1f))
        return RenderResult.EMPTY.copy(
            spatialIndex = index,
            transformerSnapshot = transformer,
            measureBounds = listOf(
                RenderedMeasureBounds(
                    systemIndex = 0,
                    measureNumber = 1,
                    leftX = StaffSpace(0f),
                    rightX = StaffSpace(30f),
                )
            ),
        )
    }

    private fun rect(x0: Float, y0: Float, x1: Float, y1: Float) = AbsoluteRect(
        origin = AbsolutePoint(Pixels(x0), Pixels(y0)),
        width = Pixels(x1 - x0),
        height = Pixels(y1 - y0),
    )

    @Test
    fun noFilterReturnsEveryOverlappingElement() {
        val result = buildResult()
        val hits = result.hitTestRegion(rect(0f, 0f, 10f, 10f))
        assertEquals(2, hits.size)
        assertTrue(hits.any { it.elementId == noteId })
        assertTrue(hits.any { it.elementId == barlineId })
    }

    @Test
    fun typeFilterRestrictsScope() {
        val result = buildResult()
        val hits = result.hitTestRegion(rect(0f, 0f, 10f, 10f), setOf(RenderElementType.NOTEHEAD))
        assertEquals(1, hits.size)
        assertEquals(noteId, hits[0].elementId)
    }

    @Test
    fun cornerOrderIsNormalised() {
        val result = buildResult()
        // Bottom-right and top-left swapped: still selects the same elements.
        val hits = result.hitTestRegion(rect(10f, 10f, 0f, 0f), setOf(RenderElementType.NOTEHEAD))
        assertEquals(1, hits.size)
        assertEquals(noteId, hits[0].elementId)
    }

    @Test
    fun disjointRectangleSelectsNothing() {
        val result = buildResult()
        val hits = result.hitTestRegion(rect(50f, 0f, 60f, 10f))
        assertTrue(hits.isEmpty())
    }

    @Test
    fun emptyMeasureSelectionUsesOnlyFiveLineStaffBand() {
        val result = buildResult()

        assertEquals(1, result.measureStaffAt(AbsolutePoint(Pixels(20f), Pixels(5f)))?.first)
        assertEquals(0, result.measureStaffAt(AbsolutePoint(Pixels(20f), Pixels(5f)))?.second)
        assertTrue(result.measureStaffAt(AbsolutePoint(Pixels(20f), Pixels(2.5f))) == null)
        assertTrue(result.measureStaffAt(AbsolutePoint(Pixels(20f), Pixels(7.5f))) == null)
    }

    @Test
    fun widerContentRangeStillSupportsElementHitTesting() {
        val result = buildResult()

        // The barline extends beyond the five-line band; it remains a precise element hit.
        assertEquals(1, result.hitTest(AbsolutePoint(Pixels(5.25f), Pixels(1.5f))).elements.size)
        // But the same outer area is no longer an empty-measure fallback hit.
        assertTrue(result.measureStaffAt(AbsolutePoint(Pixels(20f), Pixels(1.5f))) == null)
    }
}
