package com.mecon.renderer.layout

import com.mecon.api.collection.BPlusTree
import com.mecon.api.collection.BPlusTreeAggregator
import com.mecon.renderer.geometry.StaffSpace
import kotlin.math.max
import kotlin.math.min

/** Max-reducible note reach used as the aggregate of each local-X B+ tree. */
internal data class ExtentAggregate(val top: Float, val bottom: Float) : Comparable<ExtentAggregate> {
    override fun compareTo(other: ExtentAggregate): Int =
        compareValuesBy(this, other, ExtentAggregate::top, ExtentAggregate::bottom)
}

private object ExtentAggregator : BPlusTreeAggregator<ExtentAggregate, ExtentAggregate> {
    override fun extract(value: ExtentAggregate): ExtentAggregate = value
    override fun combine(a1: ExtentAggregate, a2: ExtentAggregate): ExtentAggregate =
        ExtentAggregate(max(a1.top, a2.top), max(a1.bottom, a2.bottom))
    override val empty: ExtentAggregate = ExtentAggregate(2f, 2f)
}

/** The ordinal makes simultaneous notes distinct while retaining X-major ordering. */
internal data class LocalXKey(val x: Float, val ordinal: Int) : Comparable<LocalXKey> {
    override fun compareTo(other: LocalXKey): Int {
        val xOrder = x.compareTo(other.x)
        return if (xOrder != 0) xOrder else ordinal.compareTo(other.ordinal)
    }
}

internal class MeasureExtentChunk(
    private val byStaff: Map<Int, BPlusTree<LocalXKey, ExtentAggregate, ExtentAggregate>>,
) {
    fun extent(staffIndex: Int, lo: Float, hi: Float): ExtentAggregate {
        val tree = byStaff[staffIndex] ?: return ExtentAggregator.empty
        return tree.aggregateRange(
            LocalXKey(min(lo, hi), Int.MIN_VALUE),
            LocalXKey(max(lo, hi), Int.MAX_VALUE),
        )
    }
}

/**
 * Persistent first level of the two-level note-extent index.
 *
 * The outer B+ tree is keyed by measure; each value owns per-staff local-X B+ trees whose aggregate is
 * max(top,bottom). Incremental layout replaces only chunks in the affected measure window, so every
 * untouched chunk and outer-tree subtree remains structurally shared. X is deliberately measure-local:
 * translations caused by an earlier edit are represented by [NoteExtentIndex]'s per-frame transforms,
 * never by rewriting suffix keys.
 */
class NoteExtentTree private constructor(
    private val measures: BPlusTree<Int, MeasureExtentChunk, Int>,
) {
    internal val isEmpty: Boolean get() = measures.size == 0
    private fun chunk(measure: Int): MeasureExtentChunk? = measures.get(measure)

    /** Test seam proving that an incremental update retained the exact immutable chunk object. */
    internal fun sharesChunkWith(other: NoteExtentTree, measure: Int): Boolean =
        chunk(measure) != null && chunk(measure) === other.chunk(measure)

    companion object {
        val EMPTY = NoteExtentTree(BPlusTree(aggregator = null))

        internal fun build(
            layoutsByMeasure: Map<Int, List<VoiceEventLayout>>,
            slots: UnifiedTimeSlotMap,
            extentOf: (VoiceEventLayout) -> Pair<StaffSpace, StaffSpace>,
            cached: NoteExtentTree? = null,
            replaceWindow: IntRange? = null,
        ): NoteExtentTree {
            val canPatch = cached != null && !cached.isEmpty && replaceWindow != null
            var outer = if (canPatch) cached!!.measures
                else BPlusTree<Int, MeasureExtentChunk, Int>(aggregator = null)
            val measuresToBuild: Iterable<Int> = if (canPatch) replaceWindow!!
                else layoutsByMeasure.keys.sorted()
            val boundsByMeasure = slots.measureBounds

            for (measure in measuresToBuild) {
                outer = outer.remove(measure)
                val layouts = layoutsByMeasure[measure].orEmpty()
                if (layouts.isEmpty()) continue
                val origin = boundsByMeasure[measure]?.minX ?: continue
                val trees = HashMap<Int, BPlusTree<LocalXKey, ExtentAggregate, ExtentAggregate>>()
                var ordinal = 0
                for (layout in layouts) {
                    val x = slots.atTime(layout.time)?.x?.value ?: continue
                    val (top, bottom) = extentOf(layout)
                    val tree = trees[layout.staffIndex]
                        ?: BPlusTree(aggregator = ExtentAggregator)
                    trees[layout.staffIndex] = tree.put(
                        LocalXKey(x - origin, ordinal++),
                        ExtentAggregate(top.value, bottom.value),
                    )
                }
                if (trees.isNotEmpty()) outer = outer.put(measure, MeasureExtentChunk(trees))
            }
            return if (outer.size == 0) EMPTY else NoteExtentTree(outer)
        }
    }

    internal fun index(
        baseSlots: UnifiedTimeSlotMap,
        displaySlots: UnifiedTimeSlotMap,
        systemFilter: Set<Int>? = null,
    ): NoteExtentIndex {
        if (isEmpty || systemFilter?.isEmpty() == true) return NoteExtentIndex.EMPTY
        val baseBounds = baseSlots.measureBounds
        val displayBounds = if (baseSlots === displaySlots) baseBounds else displaySlots.measureBounds
        val placements = HashMap<Int, MutableList<NoteExtentIndex.Placement>>()
        for ((measure, displayed) in displayBounds) {
            if (systemFilter != null && displayed.systemIndex !in systemFilter) continue
            val chunk = chunk(measure) ?: continue
            val base = baseBounds[measure] ?: continue
            val baseWidth = base.maxX - base.minX
            val displayWidth = displayed.maxX - displayed.minX
            val scale = if (baseWidth > 1e-6f) displayWidth / baseWidth else 1f
            placements.getOrPut(displayed.systemIndex) { mutableListOf() }.add(
                NoteExtentIndex.Placement(
                    chunk = chunk,
                    displayMinX = displayed.minX,
                    displayMaxX = displayed.maxX,
                    scale = scale,
                )
            )
        }
        if (placements.isEmpty()) return NoteExtentIndex.EMPTY
        return NoteExtentIndex(placements)
    }
}

/**
 * Query view over [NoteExtentTree] for one concrete slot transform (continuous or justified pages).
 * A query touches only the measures in its system and uses each inner tree's cached max aggregate.
 */
internal class NoteExtentIndex internal constructor(
    private val bySystem: Map<Int, List<Placement>>,
) {
    internal class Placement(
        val chunk: MeasureExtentChunk,
        val displayMinX: Float,
        val displayMaxX: Float,
        val scale: Float,
    )

    fun localExtent(
        systemIndex: Int,
        staffIndex: Int,
        xStart: StaffSpace,
        xEnd: StaffSpace,
        margin: StaffSpace,
    ): Pair<StaffSpace, StaffSpace> {
        val placements = bySystem[systemIndex] ?: return DEFAULT
        val lo = min(xStart.value, xEnd.value) - margin.value
        val hi = max(xStart.value, xEnd.value) + margin.value
        var aggregate = ExtentAggregator.empty
        for (placement in placements) {
            if (hi < placement.displayMinX || lo > placement.displayMaxX) continue
            val safeScale = if (placement.scale > 1e-6f) placement.scale else 1f
            val localLo = (lo - placement.displayMinX) / safeScale
            val localHi = (hi - placement.displayMinX) / safeScale
            aggregate = ExtentAggregator.combine(
                aggregate,
                placement.chunk.extent(staffIndex, localLo, localHi),
            )
        }
        return StaffSpace(aggregate.top) to StaffSpace(aggregate.bottom)
    }

    companion object {
        private val DEFAULT = StaffSpace(2f) to StaffSpace(2f)
        val EMPTY = NoteExtentIndex(emptyMap())

        /** Compatibility entry point for focused tests and non-incremental callers. */
        fun build(
            voiceEventLayouts: List<VoiceEventLayout>,
            timeSlotMap: UnifiedTimeSlotMap,
            systemFilter: Set<Int>? = null,
            extentOf: (VoiceEventLayout) -> Pair<StaffSpace, StaffSpace>,
        ): NoteExtentIndex = build(
            VoiceEventLayoutMap.fromList(voiceEventLayouts), timeSlotMap, systemFilter, extentOf
        )

        /** Compatibility entry point; production layout retains the [NoteExtentTree] between frames. */
        fun build(
            voiceEventLayouts: VoiceEventLayoutMap,
            timeSlotMap: UnifiedTimeSlotMap,
            systemFilter: Set<Int>? = null,
            extentOf: (VoiceEventLayout) -> Pair<StaffSpace, StaffSpace>,
        ): NoteExtentIndex {
            if (voiceEventLayouts.isEmpty() || systemFilter?.isEmpty() == true) return EMPTY
            val byMeasure = voiceEventLayouts.all().groupBy { it.measureNumber }
            val tree = NoteExtentTree.build(byMeasure, timeSlotMap, extentOf)
            return tree.index(timeSlotMap, timeSlotMap, systemFilter)
        }
    }
}
