package com.mecon.renderer.render.spatial

import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.BarlineLayout
import com.mecon.renderer.layout.RenderConstants
import com.mecon.renderer.layout.SystemLayout
import com.mecon.renderer.layout.UnifiedLayoutResult

/**
 * Adapter that builds a [HierarchicalSpatialIndex] from layout results
 * and [HittableRegistration]s produced by the RenderEngine.
 *
 * Maps the music-domain structure (systems, measures, staves) onto the
 * abstract spatial hierarchy. Handles:
 * - Computing measure boundaries from barline layouts
 * - Building staff regions from staff layout info
 * - Inserting pre-enriched hittable registrations into cells
 */
class ScoreSpatialAdapter {

    /**
     * Build a hierarchical spatial index from layout and hittable registrations.
     *
     * @param layoutResult The unified layout result providing structure info
     * @param measureBoundaries Pre-computed measure boundaries
     * @param hittables Enriched hittable registrations from RenderEngine
     * @return A fully populated hierarchical spatial index
     */
    fun buildIndex(
        layoutResult: UnifiedLayoutResult,
        measureBoundaries: List<MeasureBoundary>,
        hittables: List<HittableRegistration>
    ): HierarchicalSpatialIndex {
        val index = HierarchicalSpatialIndex()

        if (layoutResult.isEmpty() || layoutResult.staffLayouts.isEmpty()) {
            return index
        }

        // Paginated mode lays out multiple systems stacked vertically, each with its own
        // justified X range and Y offset. A single continuous SystemNode cannot describe
        // that, so build one node per visual system. Continuous mode keeps the original
        // single-system path below (bit-identical to before).
        if (layoutResult.paginated && layoutResult.systems.isNotEmpty()) {
            return buildPaginatedIndex(layoutResult, hittables)
        }

        if (measureBoundaries.isEmpty()) {
            return index
        }

        val measureCount = measureBoundaries.size
        val staffRegions = buildStaffRegions(layoutResult)

        // Compute system Y bounds from staff regions
        val systemTopY = if (staffRegions.isNotEmpty()) {
            staffRegions.minOf { it.topY }
        } else StaffSpace.ZERO
        val systemBottomY = if (staffRegions.isNotEmpty()) {
            staffRegions.maxOf { it.bottomY }
        } else layoutResult.height

        // First barline X or indent as system start
        val barlines = layoutResult.barlineLayouts.all()
        val systemStartX = if (barlines.isNotEmpty()) barlines.first().x else StaffSpace.ZERO

        val systemNode = SystemNode(
            systemIndex = 0,
            measureCount = measureCount,
            measureNumbers = measureBoundaries.map { it.measureNumber },
            staffRegions = staffRegions,
            topY = systemTopY,
            bottomY = systemBottomY,
            startX = systemStartX
        )

        // Set measure widths in the segment tree
        for (i in measureBoundaries.indices) {
            systemNode.setMeasureWidth(i, measureBoundaries[i].width)
        }

        // Build staff region index for quick lookup by staffIndex
        val staffRegionByStaffIndex = staffRegions.associateBy { it.staffIndex }

        // Insert hittable registrations into cells
        for (hittable in hittables) {
            insertHittable(hittable, systemNode, staffRegionByStaffIndex, hittable.measureIndices)
        }

        index.addSystem(systemNode.expandedToInclude(hittables))
        return index
    }

    /**
     * Incrementally build a spatial index from a [cached] one for an element-level splice: reuse the
     * cached cells for every measure the edit did not touch, and rebuild only the cells the edit's
     * window (old + new) occupies. The cell grid stores hittables in **measure-local + staff-local**
     * coordinates, so a reused cell is correct unchanged even when the whole tail shifted by Δx/Δy —
     * both the element and its measure origin / staff centre moved by the same Δ, cancelling.
     *
     * Affected cells are *copied* from the cached cell (preserving any element from an adjacent
     * unchanged measure that overlaps the boundary), the cached window elements ([removedWindowHittables])
     * removed from the copy, and the regenerated window elements ([newWindowHittables]) inserted. This is
     * robust to a hittable spanning two measure cells: the spanned cell is in the affected set (it appears
     * in some window element's `measureIndices`), so it is copy-patched rather than reused stale.
     *
     * Returns null — caller falls back to a full [buildIndex] — when the layout shape can't be matched to
     * the cached node (paginated, different system / staff / measure count).
     *
     * Continuous (single-system) layouts only; the splice that calls this already requires that shape.
     */
    fun buildIndexIncremental(
        cached: HierarchicalSpatialIndex,
        layoutResult: UnifiedLayoutResult,
        measureBoundaries: List<MeasureBoundary>,
        removedWindowHittables: List<HittableRegistration>,
        newWindowHittables: List<HittableRegistration>,
    ): HierarchicalSpatialIndex? {
        if (layoutResult.paginated) return null
        val cachedNode = cached.allSystems().singleOrNull() ?: return null
        val staffRegions = buildStaffRegions(layoutResult).map { region ->
            cachedNode.staffRegions.firstOrNull { it.staffIndex == region.staffIndex }?.let { cached ->
                region.copy(
                    topY = StaffSpace(minOf(region.topY.value, cached.topY.value)),
                    bottomY = StaffSpace(maxOf(region.bottomY.value, cached.bottomY.value)),
                )
            } ?: region
        }
        if (staffRegions.isEmpty() || staffRegions.size != cachedNode.staffRegions.size) return null
        val measureCount = measureBoundaries.size
        if (measureCount == 0 || measureCount != cachedNode.measureCount) return null

        // Cells the edit touches: every measure index occupied by a cached window element (to drop) or a
        // regenerated one (to add). A hittable spanning two cells lists both, so both get copy-patched.
        val affected = HashSet<Int>()
        for (h in removedWindowHittables) affected.addAll(h.measureIndices)
        for (h in newWindowHittables) affected.addAll(h.measureIndices)
        val removedIds = removedWindowHittables.mapTo(HashSet()) { it.elementId }

        val staffCount = staffRegions.size
        val cells = Array(measureCount.coerceAtLeast(1)) { m ->
            Array(staffCount.coerceAtLeast(1)) { s ->
                if (m < cachedNode.measureCount && s < cachedNode.staffRegions.size) {
                    val cachedCell = cachedNode.getCell(m, s)
                    if (m in affected) cachedCell.copyOf().also { copy -> removedIds.forEach { copy.remove(it) } }
                    else cachedCell // reuse read-only (the previous frame still shares it)
                } else MeasureStaffCell()
            }
        }

        val systemTopY = staffRegions.minOf { it.topY }
        val systemBottomY = staffRegions.maxOf { it.bottomY }
        val barlines = layoutResult.barlineLayouts.all()
        val systemStartX = if (barlines.isNotEmpty()) barlines.first().x else StaffSpace.ZERO

        val node = SystemNode(
            systemIndex = 0,
            measureCount = measureCount,
            measureNumbers = measureBoundaries.map { it.measureNumber },
            staffRegions = staffRegions,
            topY = systemTopY,
            bottomY = systemBottomY,
            startX = systemStartX,
            initialCells = cells,
        )
        // Rebuild the width tree fresh (O(measures·log) — cheap vs. O(hittables)); tail prefix sums then
        // shift automatically off the one changed window-measure width.
        for (i in measureBoundaries.indices) node.setMeasureWidth(i, measureBoundaries[i].width)

        val staffByIndex = staffRegions.associateBy { it.staffIndex }
        for (h in newWindowHittables) insertHittable(h, node, staffByIndex, h.measureIndices)

        val index = HierarchicalSpatialIndex()
        index.addSystem(node.expandedToInclude(newWindowHittables))
        return index
    }

    /**
     * Build a per-system index for paginated layout. Each [SystemLayout] becomes a
     * [SystemNode] whose staff regions carry the system's Y offset and whose measure
     * grid uses that system's justified barline X positions. Hittables are routed to
     * the system whose Y band contains them (bands are disjoint and stacked top→bottom),
     * then re-resolved to measures using that system's local boundaries — the global
     * [HittableRegistration.measureIndices] are meaningless once rows share an X range.
     */
    private fun buildPaginatedIndex(
        layoutResult: UnifiedLayoutResult,
        hittables: List<HittableRegistration>
    ): HierarchicalSpatialIndex {
        val index = HierarchicalSpatialIndex()
        val barlineByMeasure = barlineByMeasure(layoutResult)

        val built = layoutResult.systems.mapNotNull { buildSystemNode(it, barlineByMeasure) }

        // Route every hittable to the nearest system band (0 distance if inside), then
        // re-resolve it to that system's local measures and insert.
        for (h in hittables) {
            val hb = h.relativeHitBox
            val hcY = hb.origin.y.value + hb.height.value / 2f
            val target = h.systemIndex?.let { owner -> built.firstOrNull { it.node.systemIndex == owner } }
                ?: YBandRouting.nearest(built, hcY, { it.top }, { it.bottom })
                ?: continue
            val localMeasures = findOverlappingMeasureIndices(hb, target.boundaries, target.startX)
            insertHittable(h, target.node, target.staffByIndex, localMeasures)
        }

        // Add systems in Y order so the index's binary search over topY holds.
        val hitsBySystem = hittables.groupBy { it.systemIndex }
        for (b in built.sortedBy { it.top }) {
            index.addSystem(b.node.expandedToInclude(hitsBySystem[b.node.systemIndex].orEmpty()))
        }
        return index
    }

    /** A system node plus its Y band and local measure boundaries (for hittable routing). */
    private data class Built(
        val node: SystemNode,
        val staffByIndex: Map<Int, StaffRegion>,
        val boundaries: List<MeasureBoundary>,
        val startX: StaffSpace,
        val top: Float,
        val bottom: Float,
    )

    /**
     * Right edge of measure m = the barline that CLOSES it (barlines sit at a measure's end). When several
     * barlines share a measureNumber (opening + closing / repeat), the closing one is rightmost — pick by
     * max X, not associateBy (which would keep whichever came last).
     */
    private fun barlineByMeasure(layoutResult: UnifiedLayoutResult): Map<Int, BarlineLayout> =
        layoutResult.barlineLayouts.all()
            .groupBy { it.measureNumber }
            .mapValues { (_, bls) -> bls.maxBy { it.x.value } }

    /** Build one paginated [SystemNode] (with its Y band + local boundaries) for [sys]; null if empty. */
    private fun buildSystemNode(sys: SystemLayout, barlineByMeasure: Map<Int, BarlineLayout>): Built? {
        val padding = RenderConstants.STAFF_SELECTION_PADDING
        val staffRegions = sys.staffLayouts.map { s ->
            StaffRegion(
                staffIndex = s.staffIndex,
                centerY = s.centerY,
                topY = s.contentTopY - padding,
                bottomY = s.contentBottomY + padding
            )
        }
        if (staffRegions.isEmpty()) return null

        val systemTopY = staffRegions.minOf { it.topY }
        val systemBottomY = staffRegions.maxOf { it.bottomY }
        val startX = sys.lineStartX

        // Per-system measure boundaries: each measure m in the system's range is closed on the right by
        // its barline's justified X; the first measure opens at lineStartX (covering any re-stated header).
        val boundaries = mutableListOf<MeasureBoundary>()
        var leftEdge = startX
        for (m in sys.measureRange) {
            val right = barlineByMeasure[m]?.x ?: sys.lineEndX
            val width = StaffSpace((right.value - leftEdge.value).coerceAtLeast(0f))
            boundaries.add(MeasureBoundary(leftEdge, width, m))
            leftEdge = right
        }
        if (boundaries.isEmpty()) {
            boundaries.add(
                MeasureBoundary(startX, StaffSpace(sys.lineEndX.value - startX.value), sys.measureRange.first)
            )
        }

        val node = SystemNode(
            systemIndex = sys.systemIndex,
            measureCount = boundaries.size,
            measureNumbers = boundaries.map { it.measureNumber },
            staffRegions = staffRegions,
            topY = systemTopY,
            bottomY = systemBottomY,
            startX = startX
        )
        for (i in boundaries.indices) node.setMeasureWidth(i, boundaries[i].width)

        return Built(node, staffRegions.associateBy { it.staffIndex }, boundaries, startX,
            systemTopY.value, systemBottomY.value)
    }

    /**
     * Incremental paginated index build for the line-granular splice: rebuild the [affectedSystems]'
     * nodes from the regenerated [newHittables], and reuse every other cached system node shifted by its
     * [deltaYBySystem] offset (cells store staff-local coords, so the shift is just a Y-band move — see
     * [SystemNode.shiftedY]). Returns null — caller falls back to a full [buildIndex] — when a cached node
     * for a non-affected system is missing or the layout isn't paginated.
     */
    fun buildIndexIncrementalPaginated(
        cached: HierarchicalSpatialIndex,
        layoutResult: UnifiedLayoutResult,
        affectedSystems: Set<Int>,
        deltaYBySystem: Map<Int, StaffSpace>,
        newHittables: List<HittableRegistration>,
    ): HierarchicalSpatialIndex? {
        if (!layoutResult.paginated) return null
        val cachedById = cached.allSystems().associateBy { it.systemIndex }
        val barlineByMeasure = barlineByMeasure(layoutResult)

        val affectedBuilt = mutableListOf<Built>()
        val nodes = mutableListOf<SystemNode>()
        for (sys in layoutResult.systems) {
            if (sys.systemIndex in affectedSystems) {
                val built = buildSystemNode(sys, barlineByMeasure) ?: return null
                affectedBuilt.add(built)
                nodes.add(built.node)
            } else {
                val cachedNode = cachedById[sys.systemIndex] ?: return null
                nodes.add(cachedNode.shiftedY(deltaYBySystem[sys.systemIndex] ?: StaffSpace.ZERO))
            }
        }

        // Insert the regenerated hittables into the rebuilt affected nodes (route by their new Y band).
        for (h in newHittables) {
            val hb = h.relativeHitBox
            val hcY = hb.origin.y.value + hb.height.value / 2f
            val target = h.systemIndex?.let { owner ->
                affectedBuilt.firstOrNull { it.node.systemIndex == owner }
            } ?: YBandRouting.nearest(affectedBuilt, hcY, { it.top }, { it.bottom })
                ?: continue
            val localMeasures = findOverlappingMeasureIndices(hb, target.boundaries, target.startX)
            insertHittable(h, target.node, target.staffByIndex, localMeasures)
        }

        val index = HierarchicalSpatialIndex()
        val hitsBySystem = newHittables.groupBy { it.systemIndex }
        for (n in nodes.sortedBy { it.topY.value }) {
            index.addSystem(n.expandedToInclude(hitsBySystem[n.systemIndex].orEmpty()))
        }
        return index
    }

    /**
     * Build staff regions from layout info using each staff's actual occupied
     * Y range (contentTopY / contentBottomY), so high or low notes far above /
     * below the five lines remain selectable.
     */
    private fun buildStaffRegions(layoutResult: UnifiedLayoutResult): List<StaffRegion> {
        val staffLayouts = layoutResult.staffLayouts
        if (staffLayouts.isEmpty()) return emptyList()

        val padding = RenderConstants.STAFF_SELECTION_PADDING
        return staffLayouts.map { staffLayout ->
            StaffRegion(
                staffIndex = staffLayout.staffIndex,
                centerY = staffLayout.centerY,
                topY = staffLayout.contentTopY - padding,
                bottomY = staffLayout.contentBottomY + padding
            )
        }
    }

    /**
     * Insert a hittable registration into the appropriate cell(s) of the system node.
     */
    private fun insertHittable(
        hittable: HittableRegistration,
        systemNode: SystemNode,
        staffRegionByStaffIndex: Map<Int, StaffRegion>,
        measureIndices: List<Int>
    ) {
        val staffRegion = staffRegionByStaffIndex[hittable.staffIndex] ?: return
        val staffRegionIndex = systemNode.staffRegions.indexOf(staffRegion)
        if (staffRegionIndex < 0) return

        for (measureIndex in measureIndices) {
            if (measureIndex >= systemNode.measureCount) continue
            val measureStartX = StaffSpace(
                systemNode.startX.value + systemNode.measurePrefixSum(measureIndex).value
            )

            // Convert to cell-relative coordinates
            val cellRelativeBounds = RelativeRect(
                origin = RelativePoint(
                    StaffSpace(hittable.relativeHitBox.origin.x.value - measureStartX.value),
                    StaffSpace(hittable.relativeHitBox.origin.y.value - staffRegion.centerY.value)
                ),
                width = hittable.relativeHitBox.width,
                height = hittable.relativeHitBox.height
            )

            // Transform customIntersect coordinates: score-relative → cell-relative
            val cellCustomIntersect = if (hittable.customIntersect != null) {
                { cellPoint: RelativePoint ->
                    val scorePoint = RelativePoint(
                        cellPoint.x + measureStartX,
                        cellPoint.y + staffRegion.centerY
                    )
                    hittable.customIntersect.invoke(scorePoint)
                }
            } else null

            systemNode.getCell(measureIndex, staffRegionIndex).add(
                ScoreHittableElement(
                    elementId = hittable.elementId,
                    type = hittable.type,
                    sections = hittable.sections,
                    relativeBounds = cellRelativeBounds,
                    customIntersect = cellCustomIntersect,
                    metadata = hittable.metadata,
                )
            )
        }
    }

    /**
     * Representation of a measure's boundaries.
     */
    data class MeasureBoundary(
        val startX: StaffSpace,
        val width: StaffSpace,
        val measureNumber: Int
    )

    companion object {
        /**
         * Compute measure boundaries (startX and width) from barline positions.
         */
        fun computeMeasureBoundaries(
            barlines: List<BarlineLayout>,
            totalWidth: StaffSpace
        ): List<MeasureBoundary> {
            if (barlines.isEmpty()) {
                // Single measure spanning entire width
                return listOf(MeasureBoundary(StaffSpace.ZERO, totalWidth, 1))
            }

            val boundaries = mutableListOf<MeasureBoundary>()
            val sorted = barlines.sortedBy { it.x.value }

            for (i in 0 until sorted.lastIndex) {
                val startX = sorted[i].x
                val endX = sorted[i + 1].x
                val width = StaffSpace(endX.value - startX.value)
                if (width.value > 0f) {
                    // The right barline closes this measure; the left barline belongs to the
                    // preceding measure (or measure zero at the score start).
                    boundaries.add(MeasureBoundary(startX, width, sorted[i + 1].measureNumber))
                }
            }

            return boundaries
        }

        /**
         * Find which measure indices a hit box overlaps based on its X range.
         */
        fun findOverlappingMeasureIndices(
            hitBox: RelativeRect,
            measureBoundaries: List<MeasureBoundary>,
            systemStartX: StaffSpace
        ): List<Int> {
            val elementLeft = hitBox.left
            val elementRight = hitBox.right
            val result = mutableListOf<Int>()
            var accumulatedX = systemStartX.value

            for (i in measureBoundaries.indices) {
                val measureLeft = accumulatedX
                val measureRight = accumulatedX + measureBoundaries[i].width.value

                // Check overlap
                if (elementLeft.value < measureRight && elementRight.value > measureLeft) {
                    result.add(i)
                }

                accumulatedX = measureRight
            }

            return result
        }
    }
}
