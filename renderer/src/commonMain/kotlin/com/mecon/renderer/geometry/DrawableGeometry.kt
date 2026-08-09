package com.mecon.renderer.geometry

import com.mecon.renderer.render.CoordinateTransformer
import com.mecon.renderer.render.RenderCommand
import com.mecon.renderer.smufl.BravuraFont
import kotlinx.serialization.Serializable

/**
 * Abstract geometry element that can be positioned and rendered.
 * All coordinates are relative (Y=0 is staff center for staff elements).
 *
 * These geometries are created when constructing LayoutElement objects and
 * are translated to final positions during rendering.
 */
@Serializable
sealed interface DrawableGeometry {
    /** Bounding box in relative coordinates */
    val bounds: RelativeRect

    /**
     * Draw this geometry at the specified offset.
     *
     * @param offset Position offset to apply (e.g., event X + staff center Y)
     * @param transformer Coordinate transformer for relative to absolute conversion
     * @return List of render commands
     */
    context(BravuraFont)
    fun draw(
        offset: RelativePoint,
        transformer: CoordinateTransformer
    ): List<RenderCommand>
}

/**
 * Compute the total bounding box width from a list of geometries.
 * Returns the rightmost extent (assumes geometries are positioned relative to x=0).
 */
fun List<DrawableGeometry>.computeMinimumWidth(): StaffSpace {
    if (isEmpty()) return StaffSpace.ZERO
    return maxOf { it.bounds.right }
}
