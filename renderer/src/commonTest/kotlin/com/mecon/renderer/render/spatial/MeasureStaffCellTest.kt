package com.mecon.renderer.render.spatial

import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.render.RenderElementId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeasureStaffCellTest {
    private fun id(value: String) = RenderElementId.global(value.hashCode().toLong() and 0xffffffL)

    private fun createHittable(
        id: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float
    ): HittableElement {
        return object : HittableElement {
            override val elementId = this@MeasureStaffCellTest.id(id)
            override fun boundingBox() = RelativeRect(
                origin = RelativePoint(StaffSpace(x), StaffSpace(y)),
                width = StaffSpace(width),
                height = StaffSpace(height)
            )
        }
    }

    @Test
    fun testAddAndQuery() {
        val cell = MeasureStaffCell()
        cell.add(createHittable("e1", 2f, -1f, 1f, 2f))
        cell.add(createHittable("e2", 5f, -0.5f, 1f, 1f))

        assertEquals(2, cell.size)

        // Point inside e1
        val result1 = cell.query(RelativePoint.of(2.5f, 0f))
        assertEquals(1, result1.size)
        assertEquals(id("e1"), result1[0].elementId)

        // Point inside e2
        val result2 = cell.query(RelativePoint.of(5.5f, 0f))
        assertEquals(1, result2.size)
        assertEquals(id("e2"), result2[0].elementId)

        // Point outside both
        val result3 = cell.query(RelativePoint.of(10f, 0f))
        assertTrue(result3.isEmpty())
    }

    @Test
    fun testOverlappingElements() {
        val cell = MeasureStaffCell()
        cell.add(createHittable("e1", 1f, -1f, 4f, 2f))
        cell.add(createHittable("e2", 2f, -0.5f, 3f, 1f))

        // Point in overlap region
        val result = cell.query(RelativePoint.of(3f, 0f))
        assertEquals(2, result.size)
        assertTrue(result.any { it.elementId == id("e1") })
        assertTrue(result.any { it.elementId == id("e2") })
    }

    @Test
    fun testRemove() {
        val cell = MeasureStaffCell()
        cell.add(createHittable("e1", 1f, -1f, 2f, 2f))
        cell.add(createHittable("e2", 5f, -1f, 2f, 2f))

        assertEquals(2, cell.size)

        val removed = cell.remove(id("e1"))
        assertTrue(removed)
        assertEquals(1, cell.size)

        // e1 no longer found
        val result = cell.query(RelativePoint.of(2f, 0f))
        assertTrue(result.isEmpty())
    }

    @Test
    fun testClear() {
        val cell = MeasureStaffCell()
        cell.add(createHittable("e1", 1f, -1f, 2f, 2f))
        cell.add(createHittable("e2", 5f, -1f, 2f, 2f))

        cell.clear()
        assertEquals(0, cell.size)
        assertTrue(cell.isEmpty())
    }

    @Test
    fun testQueryRegionSelectsOverlapping() {
        val cell = MeasureStaffCell()
        cell.add(createHittable("e1", 1f, -1f, 1f, 2f))   // x in [1,2]
        cell.add(createHittable("e2", 5f, -0.5f, 1f, 1f))  // x in [5,6]
        cell.add(createHittable("e3", 10f, 0f, 1f, 1f))    // x in [10,11]

        // A rectangle spanning x in [0,6], y in [-2,2] overlaps e1 and e2 but not e3.
        val rect = RelativeRect(
            origin = RelativePoint(StaffSpace(0f), StaffSpace(-2f)),
            width = StaffSpace(6f),
            height = StaffSpace(4f)
        )
        val result = cell.queryRegion(rect)
        assertEquals(2, result.size)
        assertTrue(result.any { it.elementId == id("e1") })
        assertTrue(result.any { it.elementId == id("e2") })
    }

    @Test
    fun testQueryRegionRespectsVerticalBand() {
        val cell = MeasureStaffCell()
        cell.add(createHittable("high", 1f, -3f, 1f, 1f))  // y in [-3,-2]
        cell.add(createHittable("low", 1f, 2f, 1f, 1f))    // y in [2,3]

        // Rectangle covering only the upper band selects "high" but not "low".
        val rect = RelativeRect(
            origin = RelativePoint(StaffSpace(0f), StaffSpace(-4f)),
            width = StaffSpace(5f),
            height = StaffSpace(2f) // y in [-4,-2]
        )
        val result = cell.queryRegion(rect)
        assertEquals(1, result.size)
        assertEquals(id("high"), result[0].elementId)
    }

    @Test
    fun testQueryRegionEmptyWhenDisjoint() {
        val cell = MeasureStaffCell()
        cell.add(createHittable("e1", 1f, -1f, 1f, 2f))
        val rect = RelativeRect(
            origin = RelativePoint(StaffSpace(20f), StaffSpace(-1f)),
            width = StaffSpace(2f),
            height = StaffSpace(2f)
        )
        assertTrue(cell.queryRegion(rect).isEmpty())
    }

    @Test
    fun testSortOrder() {
        val cell = MeasureStaffCell()
        // Add out of order
        cell.add(createHittable("e3", 10f, 0f, 1f, 1f))
        cell.add(createHittable("e1", 1f, 0f, 1f, 1f))
        cell.add(createHittable("e2", 5f, 0f, 1f, 1f))

        // Query at each position should find the correct element
        assertEquals(id("e1"), cell.query(RelativePoint.of(1.5f, 0.5f))[0].elementId)
        assertEquals(id("e2"), cell.query(RelativePoint.of(5.5f, 0.5f))[0].elementId)
        assertEquals(id("e3"), cell.query(RelativePoint.of(10.5f, 0.5f))[0].elementId)
    }
}
