package com.mecon.renderer.layout

import com.mecon.api.collection.BPlusTree
import com.mecon.api.collection.BPlusTreeAggregator
import com.mecon.renderer.geometry.StaffSpace
import kotlin.math.max

/** One measure's already-reduced note reach, keyed by staff index. */
private data class MeasureVerticalExtent(
    val byStaff: Map<Int, Pair<StaffSpace, StaffSpace>>,
)

/**
 * Subtree maximum for the measure-axis vertical-extent tree.
 *
 * [BPlusTree] requires aggregate values to be comparable for its prefix-search API. Vertical pagination
 * only uses range aggregation, but a deterministic lexicographic order keeps the aggregate compatible
 * with the shared collection primitive without changing its API.
 */
private data class VerticalExtentAggregate(
    val byStaff: Map<Int, Pair<StaffSpace, StaffSpace>>,
) : Comparable<VerticalExtentAggregate> {
    override fun compareTo(other: VerticalExtentAggregate): Int {
        val staffs = (byStaff.keys + other.byStaff.keys).sorted()
        for (staff in staffs) {
            val a = byStaff[staff]
            val b = other.byStaff[staff]
            if (a == null) return -1
            if (b == null) return 1
            val top = a.first.value.compareTo(b.first.value)
            if (top != 0) return top
            val bottom = a.second.value.compareTo(b.second.value)
            if (bottom != 0) return bottom
        }
        return 0
    }
}

private object VerticalExtentAggregator :
    BPlusTreeAggregator<MeasureVerticalExtent, VerticalExtentAggregate> {
    override fun extract(value: MeasureVerticalExtent): VerticalExtentAggregate =
        VerticalExtentAggregate(value.byStaff)

    override fun combine(
        a1: VerticalExtentAggregate,
        a2: VerticalExtentAggregate,
    ): VerticalExtentAggregate {
        if (a1.byStaff.isEmpty()) return a2
        if (a2.byStaff.isEmpty()) return a1
        val combined = HashMap(a1.byStaff)
        for ((staff, extent) in a2.byStaff) {
            val current = combined[staff]
            combined[staff] = if (current == null) extent else {
                StaffSpace(max(current.first.value, extent.first.value)) to
                    StaffSpace(max(current.second.value, extent.second.value))
            }
        }
        return VerticalExtentAggregate(combined)
    }

    override val empty: VerticalExtentAggregate = VerticalExtentAggregate(emptyMap())
}

/**
 * Persistent measure-axis range-max index used by paginated staff stacking.
 *
 * A live edit replaces only the affected measure chunks. Querying a system's measure range then consumes
 * cached subtree maxima instead of walking every measure in that system.
 */
class MeasureVerticalExtentTree private constructor(
    private val measures: BPlusTree<Int, MeasureVerticalExtent, VerticalExtentAggregate>,
) {
    internal fun extent(range: IntRange): Map<Int, Pair<StaffSpace, StaffSpace>> =
        if (range.isEmpty() || measures.size == 0) emptyMap()
        else measures.aggregateRange(range.first, range.last).byStaff

    internal fun sharesMeasureWith(other: MeasureVerticalExtentTree, measure: Int): Boolean =
        measures.get(measure) === other.measures.get(measure)

    companion object {
        val EMPTY = MeasureVerticalExtentTree(BPlusTree(aggregator = VerticalExtentAggregator))

        internal fun build(
            extents: Map<Int, Map<Int, Pair<StaffSpace, StaffSpace>>>,
            cached: MeasureVerticalExtentTree? = null,
            replaceWindow: IntRange? = null,
        ): MeasureVerticalExtentTree {
            val canPatch = cached != null && replaceWindow != null
            var tree = if (canPatch) cached!!.measures
                else BPlusTree(aggregator = VerticalExtentAggregator)
            val measuresToBuild: Iterable<Int> = if (canPatch) replaceWindow!! else extents.keys.sorted()
            for (measure in measuresToBuild) {
                tree = tree.remove(measure)
                val byStaff = extents[measure]
                if (!byStaff.isNullOrEmpty()) tree = tree.put(measure, MeasureVerticalExtent(byStaff))
            }
            return if (tree.size == 0) EMPTY else MeasureVerticalExtentTree(tree)
        }
    }
}
