package com.mecon.renderer.render.spatial

import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.render.RenderElementId
import com.mecon.api.collection.BPlusTree
import com.mecon.api.collection.BPlusTreeAggregator

object MeasureWidthAggregator : BPlusTreeAggregator<StaffSpace, StaffSpace> {
    override fun extract(value: StaffSpace): StaffSpace = value
    override fun combine(a1: StaffSpace, a2: StaffSpace): StaffSpace = StaffSpace(a1.value + a2.value)
    override val empty: StaffSpace = StaffSpace(0f)
}

data class IndexAndLocalOffset(val index: Int, val localOffset: StaffSpace)

/**
 * Represents a system (line) in the score's spatial hierarchy.
 *
 * Contains a B+ tree for measure widths and a 2D grid of
 * [MeasureStaffCell]s indexed by [measureIndex][staffIndex].
 *
 * @param systemIndex Index of this system in the score (0-based)
 * @param measureCount Number of measures in this system
 * @param staffRegions Staff Y regions within this system
 * @param topY Top boundary of this system (in score-relative coordinates)
 * @param bottomY Bottom boundary of this system
 * @param startX Left edge X of this system
 */
class SystemNode(
    val systemIndex: Int,
    val measureCount: Int,
    /** Score measure number for each local measure-cell index. */
    val measureNumbers: List<Int> = List(measureCount) { it + 1 },
    val staffRegions: List<StaffRegion>,
    val topY: StaffSpace,
    val bottomY: StaffSpace,
    val startX: StaffSpace,
    /**
     * Pre-built cell grid `[measureIndex][staffRegionIndex]`. Supplied by the incremental spatial-index
     * build, which reuses the previous frame's cells for unchanged measures. Null ⇒ allocate empty cells
     * (the normal full-build path). Must match the node's dimensions when non-null.
     */
    initialCells: Array<Array<MeasureStaffCell>>? = null,
) {
    /** B+ tree for O(log n) measure position lookup mapping measureIndex -> measureWidth */
    var measureTree = BPlusTree<Int, StaffSpace, StaffSpace>(aggregator = MeasureWidthAggregator)

    /** 2D grid: cells[measureIndex][staffRegionIndex] */
    private val cells: Array<Array<MeasureStaffCell>> = initialCells
        ?: Array(measureCount.coerceAtLeast(1)) {
            Array(staffRegions.size.coerceAtLeast(1)) { MeasureStaffCell() }
        }

    /**
     * Get the cell for a specific measure and staff.
     */
    fun getCell(measureIndex: Int, staffRegionIndex: Int): MeasureStaffCell {
        return cells[measureIndex][staffRegionIndex]
    }

    /**
     * This system reused at a new vertical position, [dy] staff-spaces away. The cell grid stores
     * hittables in measure-local + staff-local coordinates, so it is invariant to the shift and shared
     * as-is (read-only); only the Y bands ([staffRegions], [topY]/[bottomY]) move. The measure width tree
     * is immutable (`put` returns a new tree) and shared too. Used by the paginated incremental index
     * build to reuse an unchanged system that the edit pushed up or down.
     */
    fun shiftedY(dy: StaffSpace): SystemNode {
        val shifted = SystemNode(
            systemIndex = systemIndex,
            measureCount = measureCount,
            measureNumbers = measureNumbers,
            staffRegions = staffRegions.map { it.shiftedY(dy) },
            topY = topY + dy,
            bottomY = bottomY + dy,
            startX = startX,
            initialCells = cells,
        )
        shifted.measureTree = measureTree
        return shifted
    }

    /**
     * Expand the coarse system/staff Y bands to include the actual bounds of their hittables.
     * Cells remain staff-local, so they can be shared unchanged by the returned node.
     */
    fun expandedToInclude(hittables: List<HittableRegistration>): SystemNode {
        if (hittables.isEmpty()) return this
        val expandedRegions = staffRegions.map { region ->
            val owned = hittables.filter { it.staffIndex == region.staffIndex }
            if (owned.isEmpty()) region else region.copy(
                topY = StaffSpace(minOf(region.topY.value, owned.minOf { it.relativeHitBox.top.value })),
                bottomY = StaffSpace(maxOf(region.bottomY.value, owned.maxOf { it.relativeHitBox.bottom.value })),
            )
        }
        val expanded = SystemNode(
            systemIndex = systemIndex,
            measureCount = measureCount,
            measureNumbers = measureNumbers,
            staffRegions = expandedRegions,
            topY = expandedRegions.minOf { it.topY },
            bottomY = expandedRegions.maxOf { it.bottomY },
            startX = startX,
            initialCells = cells,
        )
        expanded.measureTree = measureTree
        return expanded
    }

    /**
     * Set the width of a specific measure. Replaces SegmentTree.setWidth.
     */
    fun setMeasureWidth(measureIndex: Int, width: StaffSpace) {
        measureTree = measureTree.put(measureIndex, width)
    }

    /**
     * Get the total width of all measures in this system.
     */
    fun totalWidth(): StaffSpace = measureTree.aggregate ?: StaffSpace(0f)

    /**
     * Find which measure contains the given X offset (relative to system startX).
     *
     * @param x X coordinate relative to system startX
     * @return IndexAndLocalOffset, or null if out of bounds
     */
    fun findMeasureAtX(x: StaffSpace): IndexAndLocalOffset? {
        val localX = StaffSpace(x.value - startX.value)
        if (localX.value < 0f) return null
        
        val res = measureTree.findByPrefix(localX) ?: return null
        val measureIndex = res.first.key
        val prefixBeforeMeasure = res.second
        val localOffset = StaffSpace(localX.value - prefixBeforeMeasure.value)
        return IndexAndLocalOffset(measureIndex, localOffset)
    }

    /**
     * Get the prefix sum of widths before the given measure index.
     */
    fun measurePrefixSum(measureIndex: Int): StaffSpace {
        return measureTree.prefixSum(measureIndex)
    }

    /**
     * Find all staff regions containing the given Y coordinate.
     */
    fun findStavesAtY(y: StaffSpace): List<Int> {
        val result = mutableListOf<Int>()
        for (i in staffRegions.indices) {
            if (staffRegions[i].containsY(y)) {
                result.add(i)
            }
        }
        return result
    }

    /**
     * Check if a point (in score-relative coordinates) falls within this system.
     */
    fun containsPoint(point: RelativePoint): Boolean {
        return point.y >= topY && point.y <= bottomY &&
                point.x >= startX &&
                point.x.value <= startX.value + totalWidth().value
    }

    /**
     * Query all hittable elements at the given point (score-relative coordinates).
     */
    fun query(point: RelativePoint): List<HittableElement> {
        val measureResult = findMeasureAtX(point.x) ?: return emptyList()
        val measureIndex = measureResult.index
        if (measureIndex >= measureCount) return emptyList()

        val localX = measureResult.localOffset

        val matchingStaves = findStavesAtY(point.y)
        if (matchingStaves.isEmpty()) return emptyList()

        val result = mutableListOf<HittableElement>()
        for (staffRegionIndex in matchingStaves) {
            val staffRegion = staffRegions[staffRegionIndex]
            val localPoint = RelativePoint(
                localX,
                StaffSpace(point.y.value - staffRegion.centerY.value)
            )
            result.addAll(cells[measureIndex][staffRegionIndex].query(localPoint))
        }

        return result
    }

    /**
     * Query all hittable elements overlapping the given rectangle (score-relative coordinates).
     *
     * Counterpart to [query] for marquee / box selection. Because cells store elements in a frame
     * local to their measure (X) and staff (Y), the rectangle is translated into each candidate
     * cell's frame before delegating to [MeasureStaffCell.queryRegion]:
     * - only staves whose vertical band meets the rectangle are visited;
     * - only measures whose horizontal span meets the rectangle are visited (measure spans come
     *   from the B+ tree prefix sums, the same source [findMeasureAtX] uses).
     *
     * Elements are de-duplicated by id, so an element straddling overlapping staff regions (e.g. a
     * grand staff) is returned once.
     */
    fun queryRegion(rect: RelativeRect): List<HittableElement> {
        if (measureCount == 0 || staffRegions.isEmpty()) return emptyList()
        val collected = LinkedHashMap<RenderElementId, HittableElement>()
        for (staffRegionIndex in staffRegions.indices) {
            val region = staffRegions[staffRegionIndex]
            // Skip staves whose vertical band does not meet the rectangle.
            if (region.bottomY.value < rect.top.value || region.topY.value > rect.bottom.value) continue
            for (m in 0 until measureCount) {
                val mStart = startX.value + measureTree.prefixSum(m).value
                val mEnd = startX.value + measureTree.prefixSum(m + 1).value
                // Skip measures that do not meet the rectangle horizontally.
                if (mEnd < rect.left.value || mStart > rect.right.value) continue
                // Translate the rectangle into this cell's local frame (X from the measure start,
                // Y from the staff centre line) before testing overlap.
                val localRect = RelativeRect(
                    origin = RelativePoint(
                        StaffSpace(rect.left.value - mStart),
                        StaffSpace(rect.top.value - region.centerY.value),
                    ),
                    width = rect.width,
                    height = rect.height,
                )
                for (el in getCell(m, staffRegionIndex).queryRegion(localRect)) {
                    collected[el.elementId] = el
                }
            }
        }
        return collected.values.toList()
    }

    // Lock API for future incremental updates
    private var locked: Boolean = false

    fun lock() { locked = true }
    fun unlock() { locked = false }
    fun isLocked(): Boolean = locked
}
