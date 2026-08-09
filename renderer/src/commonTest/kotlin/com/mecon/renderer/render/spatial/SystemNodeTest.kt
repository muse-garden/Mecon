package com.mecon.renderer.render.spatial

import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderElementId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemNodeTest {
    private fun id(value: String) = RenderElementId.global(value.hashCode().toLong() and 0xffffffL)

    private fun createStaffRegions(): List<StaffRegion> = listOf(
        StaffRegion(staffIndex = 0, centerY = StaffSpace(5f), topY = StaffSpace(1f), bottomY = StaffSpace(9f)),
        StaffRegion(staffIndex = 1, centerY = StaffSpace(15f), topY = StaffSpace(11f), bottomY = StaffSpace(19f))
    )

    private fun createSystemNode(): SystemNode {
        val staffRegions = createStaffRegions()
        val node = SystemNode(
            systemIndex = 0,
            measureCount = 3,
            staffRegions = staffRegions,
            topY = StaffSpace(1f),
            bottomY = StaffSpace(19f),
            startX = StaffSpace(2f)
        )
        // Set measure widths: 10, 15, 20
        node.setMeasureWidth(0, StaffSpace(10f))
        node.setMeasureWidth(1, StaffSpace(15f))
        node.setMeasureWidth(2, StaffSpace(20f))
        return node
    }

    @Test
    fun testFindMeasureAtX() {
        val node = createSystemNode()

        // startX = 2, measure 0 spans [2, 12), measure 1 spans [12, 27), measure 2 spans [27, 47)
        val r0 = node.findMeasureAtX(StaffSpace(5f))
        assertEquals(0, r0?.index)
        assertEquals(3f, r0?.localOffset?.value ?: 0f, 0.001f)

        val r1 = node.findMeasureAtX(StaffSpace(15f))
        assertEquals(1, r1?.index)
        assertEquals(3f, r1?.localOffset?.value ?: 0f, 0.001f)

        val r2 = node.findMeasureAtX(StaffSpace(30f))
        assertEquals(2, r2?.index)
        assertEquals(3f, r2?.localOffset?.value ?: 0f, 0.001f)
    }

    @Test
    fun testFindStavesAtY() {
        val node = createSystemNode()

        // Y = 5 is center of staff 0
        val staves0 = node.findStavesAtY(StaffSpace(5f))
        assertEquals(1, staves0.size)
        assertEquals(0, staves0[0])

        // Y = 15 is center of staff 1
        val staves1 = node.findStavesAtY(StaffSpace(15f))
        assertEquals(1, staves1.size)
        assertEquals(1, staves1[0])

        // Y = 0 is outside both staves
        val stavesNone = node.findStavesAtY(StaffSpace(0f))
        assertTrue(stavesNone.isEmpty())
    }

    @Test
    fun testContainsPoint() {
        val node = createSystemNode()

        assertTrue(node.containsPoint(RelativePoint.of(10f, 10f)))
        assertTrue(!node.containsPoint(RelativePoint.of(0f, 10f)))  // X before startX
        assertTrue(!node.containsPoint(RelativePoint.of(10f, 0f)))  // Y above system
        assertTrue(!node.containsPoint(RelativePoint.of(10f, 20f))) // Y below system
    }

    @Test
    fun expandedBandsKeepFarAwayHittableQueryable() {
        val node = createSystemNode()
        val beamId = id("far_beam")
        node.getCell(0, 0).add(ScoreHittableElement(
            elementId = beamId,
            type = RenderElementType.BEAM,
            sections = emptyList(),
            // Cell Y is relative to staff 0 centreY=5; score Y is therefore 40.
            relativeBounds = RelativeRect(
                origin = RelativePoint.of(5f, 34.5f),
                width = StaffSpace(4f),
                height = StaffSpace(1f),
            ),
        ))
        val expanded = node.expandedToInclude(listOf(
            HittableRegistration(
                elementId = beamId,
                relativeHitBox = RelativeRect(
                    origin = RelativePoint.of(7f, 39.5f),
                    width = StaffSpace(4f),
                    height = StaffSpace(1f),
                ),
                type = RenderElementType.BEAM,
                sections = emptyList(),
                staffIndex = 0,
                systemIndex = 0,
                measureIndices = listOf(0),
            )
        ))

        assertEquals(beamId, expanded.query(RelativePoint.of(7f, 40f)).single().elementId)
        assertEquals(40.5f, expanded.bottomY.value)
    }

    @Test
    fun testQuery() {
        val node = createSystemNode()

        // Add element in measure 0, staff 0
        val elem = ScoreHittableElement(
            elementId = id("note1"),
            type = RenderElementType.NOTEHEAD,
            sections = emptyList(),
            relativeBounds = RelativeRect(
                origin = RelativePoint(StaffSpace(3f), StaffSpace(-0.5f)),
                width = StaffSpace(1f),
                height = StaffSpace(1f)
            )
        )
        node.getCell(0, 0).add(elem)

        // Query at measure 0 (starts at x=2, localX=3), staff 0 (centerY=5, so y=5 → cellY=0)
        // Point x=5 → measureIndex=0, localX=3; y=5 → staff 0, cellY = 5-5 = 0
        val result = node.query(RelativePoint.of(5f, 5f))
        assertEquals(1, result.size)
        assertEquals(id("note1"), result[0].elementId)
    }

    @Test
    fun testQueryMultipleStaves() {
        val staffRegions = listOf(
            StaffRegion(staffIndex = 0, centerY = StaffSpace(5f), topY = StaffSpace(1f), bottomY = StaffSpace(10f)),
            StaffRegion(staffIndex = 1, centerY = StaffSpace(8f), topY = StaffSpace(4f), bottomY = StaffSpace(12f))
        )

        val node = SystemNode(
            systemIndex = 0,
            measureCount = 1,
            staffRegions = staffRegions,
            topY = StaffSpace(1f),
            bottomY = StaffSpace(12f),
            startX = StaffSpace(0f)
        )
        node.setMeasureWidth(0, StaffSpace(20f))

        // Add elements in both staves
        node.getCell(0, 0).add(ScoreHittableElement(
            elementId = id("s0_note"),
            type = RenderElementType.NOTEHEAD,
            sections = emptyList(),
            relativeBounds = RelativeRect(
                origin = RelativePoint(StaffSpace(5f), StaffSpace(-1f)),
                width = StaffSpace(1f),
                height = StaffSpace(2f)
            )
        ))
        node.getCell(0, 1).add(ScoreHittableElement(
            elementId = id("s1_note"),
            type = RenderElementType.NOTEHEAD,
            sections = emptyList(),
            relativeBounds = RelativeRect(
                origin = RelativePoint(StaffSpace(5f), StaffSpace(-1f)),
                width = StaffSpace(1f),
                height = StaffSpace(2f)
            )
        ))

        // Y=6 overlaps both staff regions (staff 0: [1,10], staff 1: [4,12])
        // staff 0 centerY=5, cellY = 6-5 = 1 → in bounds [-1, 1] → yes
        // staff 1 centerY=8, cellY = 6-8 = -2 → in bounds [-1, 1] → no
        val result = node.query(RelativePoint.of(5.5f, 6f))
        assertEquals(1, result.size)
        assertEquals(id("s0_note"), result[0].elementId)
    }

    @Test
    fun testLocking() {
        val node = createSystemNode()

        node.getCell(0, 0).add(ScoreHittableElement(
            elementId = id("note1"),
            type = RenderElementType.NOTEHEAD,
            sections = emptyList(),
            relativeBounds = RelativeRect(
                origin = RelativePoint(StaffSpace(3f), StaffSpace(-0.5f)),
                width = StaffSpace(1f),
                height = StaffSpace(1f)
            )
        ))

        // Unlocked - should return results
        val result1 = node.query(RelativePoint.of(5f, 5f))
        assertEquals(1, result1.size)

        // Note: locking is enforced by HierarchicalSpatialIndex, not SystemNode.query
        // SystemNode.query doesn't check lock status itself
    }
}
