package com.mecon.renderer.render.spatial

import com.mecon.renderer.geometry.StaffSpace

/**
 * Defines the vertical region occupied by a staff within a system.
 *
 * Used to determine which staff a click falls on.
 * Regions may overlap (e.g., grand staff piano), so a single Y coordinate
 * may match multiple staff indices.
 *
 * @param staffIndex Index of the staff within the system (0-based)
 * @param centerY Y coordinate of the staff's middle line
 * @param topY Top boundary of this staff's hit region
 * @param bottomY Bottom boundary of this staff's hit region
 */
data class StaffRegion(
    val staffIndex: Int,
    val centerY: StaffSpace,
    val topY: StaffSpace,
    val bottomY: StaffSpace
) {
    /**
     * Check if a Y coordinate falls within this staff region.
     */
    fun containsY(y: StaffSpace): Boolean {
        return y >= topY && y <= bottomY
    }

    /**
     * Check if a Y coordinate falls between the outer lines of this staff.
     *
     * [topY] / [bottomY] intentionally cover the full occupied content range so
     * ledger-line notes and stems remain hittable. Empty-measure selection uses
     * this narrower five-line band instead.
     */
    fun containsStaffLinesY(y: StaffSpace): Boolean {
        val staffLineExtent = StaffSpace(2f)
        return y >= centerY - staffLineExtent && y <= centerY + staffLineExtent
    }

    /** This region shifted vertically by [dy] (used when reusing a cached system node that moved). */
    fun shiftedY(dy: StaffSpace): StaffRegion =
        copy(centerY = centerY + dy, topY = topY + dy, bottomY = bottomY + dy)
}
