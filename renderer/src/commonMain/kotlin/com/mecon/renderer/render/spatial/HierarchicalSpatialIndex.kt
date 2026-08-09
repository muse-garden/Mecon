package com.mecon.renderer.render.spatial

import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.render.RenderElementId

/**
 * Top-level hierarchical spatial index for the entire score.
 *
 * Systems are stored sorted by topY for binary search.
 * Query flow: find system → delegate to [SystemNode.query].
 */
class HierarchicalSpatialIndex {
    /** Systems sorted by topY. Their expanded hittable bands may overlap. */
    private val systems = mutableListOf<SystemNode>()

    /**
     * Add a system node. Systems should be added in Y order (top to bottom).
     */
    fun addSystem(system: SystemNode) {
        systems.add(system)
    }

    /**
     * Get a system by index.
     */
    fun getSystem(systemIndex: Int): SystemNode? {
        return systems.firstOrNull { it.systemIndex == systemIndex }
    }

    /**
     * Get all systems.
     */
    fun allSystems(): List<SystemNode> = systems.toList()

    /**
     * Clear all systems.
     */
    fun clear() {
        systems.clear()
    }

    /**
     * Number of systems in the index.
     */
    val size: Int get() = systems.size

    /**
     * Query all hittable elements at the given point (score-relative coordinates).
     *
     * Uses binary search to find the system, then delegates to SystemNode.query.
     */
    fun query(point: RelativePoint): List<HittableElement> {
        val matchingSystems = findSystemsAtY(point.y)
        if (matchingSystems.isEmpty()) return emptyList()

        val result = mutableListOf<HittableElement>()
        for (system in matchingSystems) {
            if (!system.isLocked()) {
                result.addAll(system.query(point))
            }
        }
        return result
    }

    /**
     * Query all hittable elements overlapping the given rectangle (score-relative coordinates).
     *
     * Counterpart to [query] for marquee / box selection: where [query] resolves a single point,
     * this collects every element whose bounding box intersects [rect]. Locked systems are
     * skipped, and any system whose vertical band misses the rectangle is pruned before delegating
     * to [SystemNode.queryRegion]. Results are de-duplicated by element id.
     */
    fun queryRegion(rect: RelativeRect): List<HittableElement> {
        if (systems.isEmpty()) return emptyList()
        val collected = LinkedHashMap<RenderElementId, HittableElement>()
        for (system in systems) {
            if (system.isLocked()) continue
            // Prune systems whose vertical band does not meet the rectangle.
            if (system.bottomY.value < rect.top.value || system.topY.value > rect.bottom.value) continue
            for (el in system.queryRegion(rect)) {
                collected[el.elementId] = el
            }
        }
        return collected.values.toList()
    }

    /**
     * Find systems whose Y range contains the given Y coordinate.
     *
     * Uses binary search to find the first candidate, then scans for overlaps.
     */
    private fun findSystemsAtY(y: StaffSpace): List<SystemNode> {
        if (systems.isEmpty()) return emptyList()

        // A manually positioned spanning element can expand one system beyond later systems,
        // so bottomY is no longer monotonic and binary search would skip valid owners.
        return systems.filter { y >= it.topY && y <= it.bottomY }
    }

    /**
     * Resolve the staff nearest a score-relative [point], reusing the same per-system [StaffRegion]
     * structures built for hit testing (rather than re-deriving staff geometry from render elements).
     *
     * A region that *contains* the point wins; otherwise the staff whose centre line is vertically
     * closest is returned, so the note pen still previews a ghost when the cursor sits a little above
     * or below the staff. Because each system carries its own Y-offset regions, this resolves to the
     * correct line in paginated / multi-system layouts. Null when the index has no systems.
     */
    fun staffAt(point: RelativePoint): StaffHit? {
        var best: StaffHit? = null
        var bestScore = Float.MAX_VALUE
        for (system in systems) {
            for (region in system.staffRegions) {
                val dist = kotlin.math.abs(region.centerY.value - point.y.value)
                // Containing regions are always preferred over merely-near ones.
                val score = if (region.containsY(point.y)) dist else dist + CONTAINMENT_BIAS
                if (score < bestScore) {
                    bestScore = score
                    best = StaffHit(system.systemIndex, region.staffIndex, region.centerY)
                }
            }
        }
        return best
    }

    /**
     * Lock a system (prevents queries from returning its elements).
     */
    fun lockSystem(systemIndex: Int) {
        getSystem(systemIndex)?.lock()
    }

    /**
     * Unlock a system.
     */
    fun unlockSystem(systemIndex: Int) {
        getSystem(systemIndex)?.unlock()
    }

    companion object {
        /** Penalty added to a non-containing staff's distance so containment always wins. */
        private const val CONTAINMENT_BIAS = 1_000_000f
    }
}

/**
 * A staff resolved from a query point: which system it belongs to, its display-order index, and the
 * Y of its middle line (score-relative, already system-offset). Returned by
 * [HierarchicalSpatialIndex.staffAt] for note-pen placement.
 */
data class StaffHit(
    val systemIndex: Int,
    val staffIndex: Int,
    val centerY: StaffSpace,
)
