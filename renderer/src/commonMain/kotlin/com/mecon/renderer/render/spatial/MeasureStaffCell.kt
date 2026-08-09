package com.mecon.renderer.render.spatial

import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.render.RenderElementId

/**
 * A cell in the spatial index grid, representing one measure × one staff.
 *
 * Stores hittable elements sorted by their bounding box left edge X coordinate.
 * Query performs linear scan with bounding box pre-check and precise intersection test.
 */
class MeasureStaffCell {
    private val elements = mutableListOf<HittableElement>()

    /**
     * Add an element to this cell, maintaining sort order by left-edge X.
     */
    fun add(element: HittableElement) {
        val insertX = element.boundingBox().left.value
        // Binary search for insertion point
        var lo = 0
        var hi = elements.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (elements[mid].boundingBox().left.value < insertX) {
                lo = mid + 1
            } else {
                hi = mid
            }
        }
        elements.add(lo, element)
    }

    /**
     * Remove an element by its ID.
     *
     * @return true if the element was found and removed
     */
    fun remove(elementId: RenderElementId): Boolean {
        return elements.removeAll { it.elementId == elementId }
    }

    /**
     * Remove all elements from this cell.
     */
    fun clear() {
        elements.clear()
    }

    /**
     * A shallow copy holding the same (immutable) hittable elements in the same sorted order.
     *
     * Used by the incremental spatial-index build to patch a window-adjacent cell without mutating the
     * cached cell (which the previously-rendered frame still shares): the copy is mutated (old window
     * elements removed, regenerated ones added) while the original stays intact.
     */
    fun copyOf(): MeasureStaffCell {
        val c = MeasureStaffCell()
        c.elements.addAll(elements)
        return c
    }

    /**
     * Query all elements that contain the given point.
     *
     * The point is in cell-relative coordinates (X relative to measure start,
     * Y relative to staff center line).
     *
     * @param localPoint Point in cell-relative coordinates
     * @return List of elements intersecting the point
     */
    fun query(localPoint: RelativePoint): List<HittableElement> {
        val result = mutableListOf<HittableElement>()
        for (element in elements) {
            if (element.intersect(localPoint)) {
                result.add(element)
            }
        }
        return result
    }

    /**
     * Query all elements whose bounding box overlaps the given rectangle.
     *
     * Counterpart to [query] for marquee / box selection: instead of a single point it returns
     * every element intersecting [localRect]. The rectangle is in cell-relative coordinates
     * (X relative to measure start, Y relative to staff center line), same as [query].
     *
     * Overlap uses bounding boxes only (no per-shape [HittableElement.intersect] refinement):
     * a marquee that grazes a glyph's box should select it even if it misses the exact outline.
     *
     * @param localRect Rectangle in cell-relative coordinates
     * @return List of elements whose bounding box intersects the rectangle
     */
    fun queryRegion(localRect: RelativeRect): List<HittableElement> {
        val result = mutableListOf<HittableElement>()
        for (element in elements) {
            if (element.boundingBox().overlaps(localRect)) {
                result.add(element)
            }
        }
        return result
    }

    /**
     * Number of elements in this cell.
     */
    val size: Int get() = elements.size

    /**
     * Whether this cell is empty.
     */
    fun isEmpty(): Boolean = elements.isEmpty()
}
