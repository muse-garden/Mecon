package com.mecon.renderer.render.spatial

import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.render.RenderElementId

/**
 * Interface for elements that can participate in spatial hit testing.
 *
 * Coordinates are relative to the containing cell:
 * - X is relative to the measure start
 * - Y is relative to the staff center line
 */
interface HittableElement {
    /** Unique identifier for this element */
    val elementId: RenderElementId

    /**
     * Bounding box in cell-relative coordinates.
     * X relative to measure start, Y relative to staff center line.
     */
    fun boundingBox(): RelativeRect

    /**
     * Precise intersection test. The point is in the same coordinate space
     * as [boundingBox] (cell-relative).
     *
     * Default implementation checks bounding box containment.
     * Override for non-rectangular shapes (beams, slurs, etc.).
     */
    fun intersect(point: RelativePoint): Boolean {
        return point in boundingBox()
    }
}
