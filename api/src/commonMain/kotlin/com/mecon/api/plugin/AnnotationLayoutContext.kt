package com.mecon.api.plugin

import com.mecon.api.computed.ComputedScore
import com.mecon.api.primitive.TimeCode

/**
 * Read-only view passed to [AnnotationStaffProvider.layout]. Plugins use this to
 * inspect the computed score and resolve TimeCode → x positions without touching
 * the renderer's absolute coordinate machinery.
 */
interface AnnotationLayoutContext {
    /** The fully computed score the plugin is laying out against. */
    val computedScore: ComputedScore

    /**
     * Return the x coordinate of [time] in staff-space units, or `null` if [time]
     * falls outside the laid-out time range.
     *
     * The renderer wraps this value in its own [StaffSpace][com.mecon.renderer.geometry.StaffSpace]
     * type internally; plugins work with the raw `Float` to keep the SPI renderer-free.
     */
    fun xForTime(time: TimeCode): Float?
}
