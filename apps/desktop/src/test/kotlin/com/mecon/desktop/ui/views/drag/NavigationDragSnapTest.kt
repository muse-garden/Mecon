package com.mecon.desktop.ui.views.drag

import com.mecon.desktop.ui.views.*

import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationDragSnapTest {
    @Test
    fun downwardSnapDoesNotApplySystemDistanceTwice() {
        val sourceAnchor = 10f
        val targetAnchor = 30f
        val sourceOffset = -1f
        val localPointerAdjustment = 2f
        val accumulated =
            sourceOffset + (targetAnchor - sourceAnchor) + localPointerAdjustment

        assertEquals(
            sourceOffset + localPointerAdjustment,
            navigationOffsetYAfterSnap(accumulated, sourceAnchor, targetAnchor),
        )
    }

    @Test
    fun upwardSnapDoesNotApplySystemDistanceTwice() {
        val sourceAnchor = 30f
        val targetAnchor = 10f
        val sourceOffset = 1.5f
        val localPointerAdjustment = -0.5f
        val accumulated =
            sourceOffset + (targetAnchor - sourceAnchor) + localPointerAdjustment

        assertEquals(
            sourceOffset + localPointerAdjustment,
            navigationOffsetYAfterSnap(accumulated, sourceAnchor, targetAnchor),
        )
    }

    @Test
    fun sameSystemDragKeepsLocalOffset() {
        assertEquals(1.25f, navigationOffsetYAfterSnap(1.25f, 20f, 20f))
    }
}
