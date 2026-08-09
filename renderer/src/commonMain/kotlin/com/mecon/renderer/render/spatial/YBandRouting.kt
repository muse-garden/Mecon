package com.mecon.renderer.render.spatial

import kotlin.math.abs

/**
 * Routes an item to the nearest of several stacked, non-overlapping vertical bands by a
 * probe Y. Distance is 0 when the probe is inside a band, otherwise the gap to the closest
 * edge; ties resolve to the first band.
 *
 * Shared by the two paginated bucketing sites so they cannot drift apart:
 * - page slicing (`RenderEngine.buildPages`) — which page sheet an element is drawn on;
 * - per-system index routing (`ScoreSpatialAdapter.buildPaginatedIndex`) — which
 *   `SystemNode` a hittable is inserted into.
 *
 * Both must bucket by an identical rule so that whatever is drawn on a page can be hit on
 * the system that page contains (the "draw and hit stay in sync" invariant).
 */
object YBandRouting {
    /**
     * @param items  candidate bands (any payload)
     * @param probeY the Y to route
     * @param top    lower edge accessor for a band
     * @param bottom upper edge accessor for a band
     * @return the nearest band, or null when [items] is empty
     */
    inline fun <T> nearest(
        items: List<T>,
        probeY: Float,
        top: (T) -> Float,
        bottom: (T) -> Float
    ): T? = items.minByOrNull { item ->
        val t = top(item)
        val b = bottom(item)
        if (probeY in t..b) 0f else minOf(abs(probeY - t), abs(probeY - b))
    }

    /**
     * Binary-search equivalent of [nearest] for bands that are already **sorted ascending by [top] and
     * non-overlapping** (`bottom(items[i]) <= top(items[i+1])`) — e.g. the per-system notation bands, which
     * stack top-to-bottom in reading order. O(log n) instead of O(n); produces the identical band as [nearest]
     * for such input, including the "ties resolve to the first (lower) band" rule.
     *
     * Callers with unsorted / overlapping bands must keep using [nearest]; this routine relies on the ordering.
     */
    inline fun <T> nearestSorted(
        items: List<T>,
        probeY: Float,
        top: (T) -> Float,
        bottom: (T) -> Float
    ): T? {
        val n = items.size
        if (n == 0) return null
        // Smallest index whose bottom >= probeY (half-open search over [0, n]).
        var lo = 0
        var hi = n
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (bottom(items[mid]) >= probeY) hi = mid else lo = mid + 1
        }
        if (lo == n) return items[n - 1]          // probe below every band → nearest is the last
        val cur = items[lo]
        if (probeY >= top(cur)) return cur          // probe lies inside items[lo]
        if (lo == 0) return cur                     // probe above the first band → nearest is the first
        // Probe sits in the gap between items[lo-1] and items[lo]; tie resolves to the lower index.
        val prev = items[lo - 1]
        return if (probeY - bottom(prev) <= top(cur) - probeY) prev else cur
    }
}
