package com.mecon.renderer.layout

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.renderer.enums.RestType
import com.mecon.renderer.geometry.StaffSpace
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the rest vertical-position mapping ([RestLayout]). Rests now accept a custom display
 * staff position; these guard the default-per-type fallback and the position → relativeY conversion.
 */
class RestLayoutTest {

    @Test
    fun defaultStaffPositionHangsWholeRestAndCentersOthers() {
        assertEquals(2, RestLayout.defaultRestStaffPosition(RestType.WHOLE))
        assertEquals(0, RestLayout.defaultRestStaffPosition(RestType.HALF))
        assertEquals(0, RestLayout.defaultRestStaffPosition(RestType.QUARTER))
        assertEquals(0, RestLayout.defaultRestStaffPosition(RestType.EIGHTH))
    }

    @Test
    fun defaultStaffPositionFromDurationMatchesType() {
        assertEquals(2, RestLayout.defaultRestStaffPosition(Duration(DurationBase.WHOLE)))
        assertEquals(0, RestLayout.defaultRestStaffPosition(Duration(DurationBase.QUARTER)))
    }

    @Test
    fun relativeYIsHalfASpacePerPositionUpward() {
        // relativeY is +down, so a higher staff position yields a more-negative (higher) Y.
        assertEquals(StaffSpace(0f), RestLayout.relativeY(0))
        assertEquals(StaffSpace(-1f), RestLayout.relativeY(2))
        assertEquals(StaffSpace(1f), RestLayout.relativeY(-2))
    }

    @Test
    fun defaultRelativeYStillMatchesLegacyValues() {
        // Behaviour preserved for the default (override == null) path.
        assertEquals(StaffSpace(-1f), RestLayout.defaultRelativeY(RestType.WHOLE))
        assertEquals(StaffSpace(0f), RestLayout.defaultRelativeY(RestType.HALF))
        assertEquals(StaffSpace(0f), RestLayout.defaultRelativeY(RestType.QUARTER))
    }
}
