package com.mecon.renderer.render.spatial

import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderElementId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HierarchicalSpatialIndexTest {
    private fun id(value: String) = RenderElementId.global(value.hashCode().toLong() and 0xffffffL)

    private fun createSystemNode(
        systemIndex: Int,
        topY: Float,
        bottomY: Float,
        startX: Float = 0f,
        measureWidths: List<Float> = listOf(20f)
    ): SystemNode {
        val staffRegions = listOf(
            StaffRegion(
                staffIndex = 0,
                centerY = StaffSpace((topY + bottomY) / 2f),
                topY = StaffSpace(topY),
                bottomY = StaffSpace(bottomY)
            )
        )
        val node = SystemNode(
            systemIndex = systemIndex,
            measureCount = measureWidths.size,
            staffRegions = staffRegions,
            topY = StaffSpace(topY),
            bottomY = StaffSpace(bottomY),
            startX = StaffSpace(startX)
        )
        for (i in measureWidths.indices) {
            node.setMeasureWidth(i, StaffSpace(measureWidths[i]))
        }
        return node
    }

    @Test
    fun testSingleSystem() {
        val index = HierarchicalSpatialIndex()
        val system = createSystemNode(0, 0f, 10f, startX = 0f, measureWidths = listOf(30f))

        val elem = ScoreHittableElement(
            elementId = id("note1"),
            type = RenderElementType.NOTEHEAD,
            sections = emptyList(),
            relativeBounds = RelativeRect(
                origin = RelativePoint(StaffSpace(10f), StaffSpace(-0.5f)),
                width = StaffSpace(1f),
                height = StaffSpace(1f)
            )
        )
        system.getCell(0, 0).add(elem)

        index.addSystem(system)

        // Query at (10.5, 5) → system0, measure0, staff0
        // centerY=5, localX=10.5, cellY=5-5=0, in bounds [-0.5, 0.5]
        val result = index.query(RelativePoint.of(10.5f, 5f))
        assertEquals(1, result.size)
        assertEquals(id("note1"), result[0].elementId)
    }

    @Test
    fun overlappingExpandedSystemBandsDoNotBreakFarHitLookup() {
        val index = HierarchicalSpatialIndex()
        val owner = createSystemNode(0, 0f, 100f, measureWidths = listOf(30f))
        val later = createSystemNode(1, 20f, 30f, measureWidths = listOf(30f))
        val beamId = id("far_beam")
        owner.getCell(0, 0).add(ScoreHittableElement(
            elementId = beamId,
            type = RenderElementType.BEAM,
            sections = emptyList(),
            relativeBounds = RelativeRect(
                origin = RelativePoint.of(5f, 39.5f),
                width = StaffSpace(2f),
                height = StaffSpace(1f),
            ),
        ))
        index.addSystem(owner)
        index.addSystem(later)

        assertEquals(beamId, index.query(RelativePoint.of(5.5f, 90f)).single().elementId)
    }

    @Test
    fun testMultipleSystems() {
        val index = HierarchicalSpatialIndex()

        val system0 = createSystemNode(0, 0f, 10f, measureWidths = listOf(30f))
        val system1 = createSystemNode(1, 15f, 25f, measureWidths = listOf(30f))

        system0.getCell(0, 0).add(ScoreHittableElement(
            elementId = id("s0_note"),
            type = RenderElementType.NOTEHEAD,
            sections = emptyList(),
            relativeBounds = RelativeRect(
                origin = RelativePoint(StaffSpace(5f), StaffSpace(-0.5f)),
                width = StaffSpace(1f),
                height = StaffSpace(1f)
            )
        ))
        system1.getCell(0, 0).add(ScoreHittableElement(
            elementId = id("s1_note"),
            type = RenderElementType.NOTEHEAD,
            sections = emptyList(),
            relativeBounds = RelativeRect(
                origin = RelativePoint(StaffSpace(5f), StaffSpace(-0.5f)),
                width = StaffSpace(1f),
                height = StaffSpace(1f)
            )
        ))

        index.addSystem(system0)
        index.addSystem(system1)

        // Query in system 0 (y=5, centerY=5)
        val result0 = index.query(RelativePoint.of(5.5f, 5f))
        assertEquals(1, result0.size)
        assertEquals(id("s0_note"), result0[0].elementId)

        // Query in system 1 (y=20, centerY=20)
        val result1 = index.query(RelativePoint.of(5.5f, 20f))
        assertEquals(1, result1.size)
        assertEquals(id("s1_note"), result1[0].elementId)

        // Query between systems (y=12)
        val resultGap = index.query(RelativePoint.of(5.5f, 12f))
        assertTrue(resultGap.isEmpty())
    }

    @Test
    fun testLockSystem() {
        val index = HierarchicalSpatialIndex()
        val system = createSystemNode(0, 0f, 10f, measureWidths = listOf(30f))

        system.getCell(0, 0).add(ScoreHittableElement(
            elementId = id("note1"),
            type = RenderElementType.NOTEHEAD,
            sections = emptyList(),
            relativeBounds = RelativeRect(
                origin = RelativePoint(StaffSpace(5f), StaffSpace(-0.5f)),
                width = StaffSpace(1f),
                height = StaffSpace(1f)
            )
        ))

        index.addSystem(system)

        // Query should return results
        val result1 = index.query(RelativePoint.of(5.5f, 5f))
        assertEquals(1, result1.size)

        // Lock the system
        index.lockSystem(0)

        // Query should return empty
        val result2 = index.query(RelativePoint.of(5.5f, 5f))
        assertTrue(result2.isEmpty())

        // Unlock
        index.unlockSystem(0)

        // Query should return results again
        val result3 = index.query(RelativePoint.of(5.5f, 5f))
        assertEquals(1, result3.size)
    }

    private fun note(id: String, x: Float, y: Float = -0.5f, w: Float = 1f, h: Float = 1f) =
        ScoreHittableElement(
            elementId = this.id(id),
            type = RenderElementType.NOTEHEAD,
            sections = emptyList(),
            relativeBounds = RelativeRect(
                origin = RelativePoint(StaffSpace(x), StaffSpace(y)),
                width = StaffSpace(w),
                height = StaffSpace(h)
            )
        )

    private fun region(x0: Float, y0: Float, x1: Float, y1: Float) = RelativeRect(
        origin = RelativePoint(StaffSpace(x0), StaffSpace(y0)),
        width = StaffSpace(x1 - x0),
        height = StaffSpace(y1 - y0)
    )

    @Test
    fun testQueryRegionTranslatesPerMeasure() {
        // 3 measures of width 10 → measure m occupies score-X [10m, 10m+10].
        val index = HierarchicalSpatialIndex()
        val system = createSystemNode(0, 0f, 10f, startX = 0f, measureWidths = listOf(10f, 10f, 10f))
        // cell(0,0) local x=5 → score X ~[5,6]; cell(2,0) local x=5 → score X ~[25,26].
        system.getCell(0, 0).add(note("m0", 5f))
        system.getCell(2, 0).add(note("m2", 5f))
        index.addSystem(system)

        // Rectangle over the first half of the system selects only the first-measure note.
        val left = index.queryRegion(region(0f, 0f, 15f, 10f))
        assertEquals(1, left.size)
        assertEquals(id("m0"), left[0].elementId)

        // Rectangle spanning the whole system selects both.
        val all = index.queryRegion(region(0f, 0f, 30f, 10f))
        assertEquals(2, all.size)
        assertTrue(all.any { it.elementId == id("m0") })
        assertTrue(all.any { it.elementId == id("m2") })
    }

    @Test
    fun testQueryRegionAcrossSystemsRespectsYBand() {
        val index = HierarchicalSpatialIndex()
        val system0 = createSystemNode(0, 0f, 10f, measureWidths = listOf(30f))
        val system1 = createSystemNode(1, 15f, 25f, measureWidths = listOf(30f))
        system0.getCell(0, 0).add(note("s0", 5f))
        system1.getCell(0, 0).add(note("s1", 5f))
        index.addSystem(system0)
        index.addSystem(system1)

        // Rectangle confined to system 0's Y band selects only its note.
        val top = index.queryRegion(region(0f, 0f, 30f, 10f))
        assertEquals(1, top.size)
        assertEquals(id("s0"), top[0].elementId)

        // Rectangle covering both bands selects both.
        val both = index.queryRegion(region(0f, 0f, 30f, 25f))
        assertEquals(2, both.size)
    }

    @Test
    fun testQueryRegionSkipsLockedSystem() {
        val index = HierarchicalSpatialIndex()
        val system = createSystemNode(0, 0f, 10f, measureWidths = listOf(30f))
        system.getCell(0, 0).add(note("n", 5f))
        index.addSystem(system)

        assertEquals(1, index.queryRegion(region(0f, 0f, 30f, 10f)).size)

        index.lockSystem(0)
        assertTrue(index.queryRegion(region(0f, 0f, 30f, 10f)).isEmpty())

        index.unlockSystem(0)
        assertEquals(1, index.queryRegion(region(0f, 0f, 30f, 10f)).size)
    }

    @Test
    fun testQueryRegionEmptyIndex() {
        val index = HierarchicalSpatialIndex()
        assertTrue(index.queryRegion(region(0f, 0f, 100f, 100f)).isEmpty())
    }

    @Test
    fun testClear() {
        val index = HierarchicalSpatialIndex()
        index.addSystem(createSystemNode(0, 0f, 10f))
        assertEquals(1, index.size)

        index.clear()
        assertEquals(0, index.size)
    }

    @Test
    fun testEmptyIndex() {
        val index = HierarchicalSpatialIndex()
        val result = index.query(RelativePoint.of(5f, 5f))
        assertTrue(result.isEmpty())
    }
}
