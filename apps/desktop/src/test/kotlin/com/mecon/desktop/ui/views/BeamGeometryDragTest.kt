package com.mecon.desktop.ui.views

import com.mecon.api.storage.BeamGeometry
import com.mecon.api.storage.CrossStaffBeamBase
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.Pixels
import kotlin.test.Test
import kotlin.test.assertEquals

class BeamGeometryDragTest {
    private val centers = mapOf(0 to 10f, 1 to 20f, 2 to 30f)

    @Test
    fun wholeBeamDragReanchorsToConcreteAdjacentStaffPair() {
        val original = BeamGeometry(
            startDy = -0.5f,
            endDy = 0.5f,
            crossStaffBase = CrossStaffBeamBase.BETWEEN_STAFFS,
            crossStaffOffset = 0f,
            betweenStaffUpperIndex = 0,
            betweenStaffLowerIndex = 1,
        )

        val moved = relocateBeamGeometry(original, endpoint = null, deltaY = 9f, centers)

        assertEquals(CrossStaffBeamBase.BETWEEN_STAFFS, moved.crossStaffBase)
        assertEquals(1, moved.betweenStaffUpperIndex)
        assertEquals(2, moved.betweenStaffLowerIndex)
        assertEquals(-1f, moved.crossStaffOffset)
        assertEquals(original.startDy, moved.startDy)
        assertEquals(original.endDy, moved.endDy)
    }

    @Test
    fun endpointDragChangesOnlyThatEndpoint() {
        val original = BeamGeometry(
            startDy = 0f,
            endDy = 0f,
            crossStaffBase = CrossStaffBeamBase.BETWEEN_STAFFS,
            crossStaffOffset = 1f,
            betweenStaffUpperIndex = 1,
            betweenStaffLowerIndex = 2,
        )

        val moved = relocateBeamGeometry(original, endpoint = "start", deltaY = -2f, centers)

        assertEquals(-2f, moved.startDy)
        assertEquals(0f, moved.endDy)
        assertEquals(1f, moved.crossStaffOffset)
        assertEquals(1, moved.betweenStaffUpperIndex)
        assertEquals(2, moved.betweenStaffLowerIndex)
    }

    @Test
    fun ordinaryBeamFollowsPointerDelta() {
        val moved = relocateBeamGeometry(BeamGeometry(1f, 2f), endpoint = null, deltaY = 3f, emptyMap())

        assertEquals(4f, moved.startDy)
        assertEquals(5f, moved.endDy)
    }

    @Test
    fun endpointControlsUseIndependentHitRadius() {
        val start = AbsolutePoint(Pixels(10f), Pixels(20f))
        val end = AbsolutePoint(Pixels(40f), Pixels(30f))

        assertEquals(
            "start",
            hitBeamControlPoint(AbsolutePoint(Pixels(13f), Pixels(24f)), start, end, radius = 5f),
        )
        assertEquals(
            "end",
            hitBeamControlPoint(AbsolutePoint(Pixels(40f), Pixels(25f)), start, end, radius = 5f),
        )
        assertEquals(
            null,
            hitBeamControlPoint(AbsolutePoint(Pixels(25f), Pixels(25f)), start, end, radius = 5f),
        )
    }

    @Test
    fun shortBeamReservesCenterForWholeBeamDrag() {
        val start = AbsolutePoint(Pixels(10f), Pixels(20f))
        val end = AbsolutePoint(Pixels(20f), Pixels(20f))

        assertEquals(
            "start",
            hitBeamControlPoint(AbsolutePoint(Pixels(11.5f), Pixels(20f)), start, end, radius = 5f),
        )
        assertEquals(
            null,
            hitBeamControlPoint(AbsolutePoint(Pixels(15f), Pixels(20f)), start, end, radius = 5f),
        )
        assertEquals(
            "end",
            hitBeamControlPoint(AbsolutePoint(Pixels(18.5f), Pixels(20f)), start, end, radius = 5f),
        )
    }
}
