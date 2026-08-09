package com.mecon.renderer.layout.stem

import com.mecon.renderer.enums.StemDirection
import com.mecon.renderer.geometry.StaffSpace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BeamLayoutComputerTest {
    @Test
    fun storedEndpointSlopeIsReconstructedAtCurrentXPositions() {
        val result = BeamLayoutComputer().compute(
            inputs = listOf(
                BeamNoteInput("a", StaffSpace(10f), StaffSpace(-5f), StaffSpace(0f)),
                BeamNoteInput("b", StaffSpace(30f), StaffSpace(-4f), StaffSpace(0f)),
            ),
            direction = StemDirection.UP,
        )

        assertNotNull(result)
        assertEquals(20f, result.endX.value - result.startX.value)
        assertEquals(result.startY.value + result.slope * 20f, result.endY.value)
    }

    @Test
    fun yAtUsesAbsoluteEndpointSlope() {
        val result = BeamLayoutResult(
            startX = StaffSpace(2f),
            startY = StaffSpace(-3f),
            endX = StaffSpace(6f),
            endY = StaffSpace(-1f),
            slope = 0.5f,
        )

        assertEquals(-2f, result.yAt(StaffSpace(4f)).value)
    }
}
