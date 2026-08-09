package com.mecon.renderer.render

import com.mecon.api.storage.BeamGeometry
import com.mecon.renderer.enums.StemDirection
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.stem.BeamLayoutResult
import com.mecon.renderer.layout.stem.BeamNoteInput
import kotlin.test.Test
import kotlin.test.assertEquals

class ManualBeamDirectionTest {
    @Test
    fun beamAboveNotesUsesUpStems() {
        val geometry = BeamGeometry(startDy = -6f, endDy = -4f, manuallyAdjusted = true)

        assertEquals(
            StemDirection.UP,
            resolveManualBeamDirection(geometry, noteheadCenters = listOf(-1f, 1f), StemDirection.DOWN),
        )
    }

    @Test
    fun beamDraggedPastNotesUsesDownStems() {
        val geometry = BeamGeometry(startDy = 5f, endDy = 7f, manuallyAdjusted = true)

        assertEquals(
            StemDirection.DOWN,
            resolveManualBeamDirection(geometry, noteheadCenters = listOf(-1f, 1f), StemDirection.UP),
        )
    }

    @Test
    fun emptyNoteheadsKeepLayoutDirection() {
        val geometry = BeamGeometry(startDy = 5f, endDy = 7f, manuallyAdjusted = true)

        assertEquals(
            StemDirection.UP,
            resolveManualBeamDirection(geometry, noteheadCenters = emptyList(), StemDirection.UP),
        )
    }

    @Test
    fun insufficientClearanceTranslatesWithoutReplacingManualSlope() {
        val stored = BeamLayoutResult(
            startX = StaffSpace(0f), startY = StaffSpace(-1f),
            endX = StaffSpace(10f), endY = StaffSpace(1f), slope = 0.2f,
        )
        val inputs = listOf(
            BeamNoteInput("a", StaffSpace(0f), StaffSpace(-4f), StaffSpace(0f)),
            BeamNoteInput("b", StaffSpace(10f), StaffSpace(-2f), StaffSpace(0f)),
        )

        val adjusted = adjustManualBeamForClearance(stored, inputs, StemDirection.UP, 2f)

        assertEquals(-4f, adjusted.startY.value)
        assertEquals(-2f, adjusted.endY.value)
        assertEquals(stored.slope, adjusted.slope)
        assertEquals(
            stored.endY.value - stored.startY.value,
            adjusted.endY.value - adjusted.startY.value,
        )
    }
}
