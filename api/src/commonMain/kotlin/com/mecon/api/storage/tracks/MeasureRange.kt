package com.mecon.api.storage.tracks

import kotlinx.serialization.Serializable

/**
 * An inclusive, closed range of measure numbers `[from, to]`.
 *
 * Used by [StorageStaffTrack.hiddenRanges] to mark spans where a staff is
 * hidden. A serializable stand-in for [IntRange] (which kotlinx.serialization
 * cannot serialize directly).
 */
@Serializable
data class MeasureRange(val from: Int, val to: Int) {
    init { require(from <= to) { "MeasureRange requires from <= to, got $from..$to" } }

    operator fun contains(measure: Int): Boolean = measure in from..to

    val measures: IntRange get() = from..to
}

/**
 * List-level operations over hidden-measure ranges. All results are normalized:
 * sorted by [MeasureRange.from], with overlapping/adjacent ranges merged, and
 * every range clamped into `[min, max]`. Empty ranges (after clamping) drop out.
 */
object MeasureRanges {
    /** Merge, sort and clamp [ranges] into `[min, max]`. */
    fun normalize(ranges: List<MeasureRange>, min: Int = 1, max: Int = Int.MAX_VALUE): List<MeasureRange> {
        if (min > max) return emptyList()
        val clamped = ranges.mapNotNull { r ->
            val lo = maxOf(r.from, min)
            val hi = minOf(r.to, max)
            if (lo <= hi) MeasureRange(lo, hi) else null
        }.sortedBy { it.from }
        if (clamped.isEmpty()) return emptyList()
        val merged = ArrayList<MeasureRange>()
        var current = clamped.first()
        for (next in clamped.drop(1)) {
            current = if (next.from <= current.to + 1) {
                // Overlapping or adjacent → merge.
                MeasureRange(current.from, maxOf(current.to, next.to))
            } else {
                merged.add(current); next
            }
        }
        merged.add(current)
        return merged
    }

    /** Whether any range covers [measure]. */
    fun contains(ranges: List<MeasureRange>, measure: Int): Boolean =
        ranges.any { measure in it }

    /** Whether every measure in `[from, to]` is covered by [ranges]. */
    fun coversAll(ranges: List<MeasureRange>, from: Int, to: Int): Boolean =
        (from..to).all { m -> ranges.any { m in it } }

    /** Add [range] to [ranges], normalized into `[min, max]`. */
    fun add(
        ranges: List<MeasureRange>,
        range: MeasureRange,
        min: Int = 1,
        max: Int = Int.MAX_VALUE,
    ): List<MeasureRange> = normalize(ranges + range, min, max)

    /** Remove the span `[from, to]` from [ranges], splitting ranges as needed. */
    fun subtract(ranges: List<MeasureRange>, from: Int, to: Int): List<MeasureRange> =
        normalize(ranges.flatMap { r ->
            when {
                r.to < from || r.from > to -> listOf(r)                 // disjoint → keep
                else -> buildList {
                    if (r.from < from) add(MeasureRange(r.from, from - 1)) // left remnant
                    if (r.to > to) add(MeasureRange(to + 1, r.to))         // right remnant
                }
            }
        })
}
