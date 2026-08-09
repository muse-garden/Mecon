package com.mecon.renderer.elements

import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.StaffSpace
import kotlin.test.Test
import kotlin.test.assertEquals

class StaffElementTest {

    @Test
    fun testStandardStaff() {
        val staff = StaffElement.standard(
            origin = RelativePoint.ZERO,
            width = StaffSpace(100f)
        )

        assertEquals(5, staff.lineCount)
        assertEquals(StaffSpace(100f), staff.width)
        assertEquals(StaffSpace(4f), staff.height)
    }

    @Test
    fun testLinePositions() {
        val staff = StaffElement.standard(
            origin = RelativePoint.ZERO,
            width = StaffSpace(100f)
        )

        // Lines should be at y = 0, 1, 2, 3, 4
        assertEquals(StaffSpace(0f), staff.lineY(0))
        assertEquals(StaffSpace(1f), staff.lineY(1))
        assertEquals(StaffSpace(2f), staff.lineY(2))  // Middle line
        assertEquals(StaffSpace(3f), staff.lineY(3))
        assertEquals(StaffSpace(4f), staff.lineY(4))
    }

    @Test
    fun testStaffPositionToY() {
        val staff = StaffElement.standard(
            origin = RelativePoint.ZERO,
            width = StaffSpace(100f)
        )

        // Staff position 0 = middle line (y = 2)
        assertEquals(StaffSpace(2f), staff.staffPositionToY(0))

        // Staff position 2 = one line above middle (y = 1)
        assertEquals(StaffSpace(1f), staff.staffPositionToY(2))

        // Staff position -2 = one line below middle (y = 3)
        assertEquals(StaffSpace(3f), staff.staffPositionToY(-2))

        // Staff position 1 = space above middle (y = 1.5)
        assertEquals(StaffSpace(1.5f), staff.staffPositionToY(1))
    }

    @Test
    fun testToLines() {
        val staff = StaffElement.standard(
            origin = RelativePoint.ZERO,
            width = StaffSpace(50f)
        )

        val lines = staff.toLines()
        assertEquals(5, lines.size)

        // First line should be at y = 0
        assertEquals(StaffSpace(0f), lines[0].start.y)
        assertEquals(StaffSpace(50f), lines[0].end.x)
    }

    @Test
    fun testPercussionStaff() {
        val staff = StaffElement.percussion(
            origin = RelativePoint.ZERO,
            width = StaffSpace(100f)
        )

        assertEquals(1, staff.lineCount)
        assertEquals(StaffSpace(0f), staff.height)
    }
}
