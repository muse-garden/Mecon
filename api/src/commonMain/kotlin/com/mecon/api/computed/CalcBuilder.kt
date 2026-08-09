package com.mecon.api.computed

import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.HasOnset
import com.mecon.api.runtime.TimeIndexedList

data class AlignedEvent2<A : HasOnset, B : HasOnset>(
    override val onset: TimeCode,
    val events: Pair<A?, B?>
) : HasOnset

data class AlignedEvent3<A : HasOnset, B : HasOnset, C : HasOnset>(
    override val onset: TimeCode,
    val events: Triple<A?, B?, C?>
) : HasOnset

/**
 * Cross-track alignment utilities for plugin-derived tracks.
 *
 * These are the **full-rebuild** entry points. For repeated alignment where the inputs change a little at a
 * time, hold a [ReferenceAligner] / [BilateralAligner] (see `CalcBuilderIncremental.kt`) instead — they
 * diff the inputs and re-pair only the affected rows.
 */
object CalcBuilder {

    /**
     * 双向对齐两个 track。
     * 若任意轨道在 ta 时刻有一个事件 ea，则另一轨道在 tb (tb <= ta 取最大) 时刻的事件与它对齐。
     */
    fun <A : HasOnset, B : HasOnset> alignBilateral(
        trackA: TimeIndexedList<A>,
        trackB: TimeIndexedList<B>
    ): TimeIndexedList<AlignedEvent2<A, B>> =
        TimeIndexedList.of(bilateralRows(trackA, trackB, from = null, toExclusive = null))

    /**
     * 以 trackA 为参考 track，单向对齐 trackB。
     * 若 trackA 在 t 时刻有一个事件 e，则 trackB 在 tb (tb <= t 取最大) 时刻的事件与它对齐。
     * 支持 offset 偏移。
     */
    fun <A : HasOnset, B : HasOnset> alignLe(
        referenceTrack: TimeIndexedList<A>,
        alignTrack: TimeIndexedList<B>,
        offset: Int = 0
    ): TimeIndexedList<AlignedEvent2<A, B>> =
        referenceAlignFull(referenceTrack, alignTrack, strict = false, offset = offset)

    /**
     * 以 trackA 为参考 track，单向对齐 trackB。
     * 若 trackA 在 t 时刻有一个事件 e，则 trackB 在 tb (tb < t 取最大) 时刻的事件与它对齐。
     */
    fun <A : HasOnset, B : HasOnset> alignL(
        referenceTrack: TimeIndexedList<A>,
        alignTrack: TimeIndexedList<B>,
        offset: Int = 0
    ): TimeIndexedList<AlignedEvent2<A, B>> =
        referenceAlignFull(referenceTrack, alignTrack, strict = true, offset = offset)

    /**
     * 映射到一个新轨道。
     */
    fun <T : HasOnset, R : HasOnset> mapToNewTrack(
        alignedTrack: TimeIndexedList<T>,
        transform: (T) -> R?
    ): TimeIndexedList<R> {
        val mappedList = alignedTrack.toList().mapNotNull { transform(it) }
        return TimeIndexedList.of(mappedList)
    }
}

/**
 * One reference-aligned row: the reference event [a] paired with the align-track event in effect at
 * `a.onset` (`<=` when not [strict], `<` when [strict]; then shifted by [offset]). Shared by the full
 * [CalcBuilder.alignLe] / [CalcBuilder.alignL] and the windowed [ReferenceAligner] re-solve.
 */
internal fun <A : HasOnset, B : HasOnset> referenceRow(
    a: A,
    alignList: List<B>,
    strict: Boolean,
    offset: Int,
): AlignedEvent2<A, B> =
    AlignedEvent2(a.onset, Pair(a, matchReference(alignList, a.onset, strict, offset)))

/**
 * The align-track event paired with a reference event at [time]: the last entry of [alignList] (sorted by
 * onset) with `onset < time` ([strict]) or `onset <= time`, shifted by [offset]; `null` if out of range.
 * Binary search → O(log B). [alignList] must be onset-sorted (as produced by [TimeIndexedList.toList]).
 */
internal fun <B : HasOnset> matchReference(
    alignList: List<B>,
    time: TimeCode,
    strict: Boolean,
    offset: Int,
): B? {
    // Elements satisfying the predicate form a prefix (onsets are sorted); find the prefix length.
    var lo = 0
    var hi = alignList.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        val onset = alignList[mid].onset
        val satisfies = if (strict) onset < time else onset <= time
        if (satisfies) lo = mid + 1 else hi = mid
    }
    val targetIdx = lo - 1
    val finalIdx = targetIdx + offset
    return alignList.getOrNull(finalIdx)
}

internal fun <A : HasOnset, B : HasOnset> referenceAlignFull(
    referenceTrack: TimeIndexedList<A>,
    alignTrack: TimeIndexedList<B>,
    strict: Boolean,
    offset: Int,
): TimeIndexedList<AlignedEvent2<A, B>> {
    val alignList = alignTrack.toList()
    val rows = referenceTrack.map { referenceRow(it, alignList, strict, offset) }
    return TimeIndexedList.of(rows)
}

/**
 * Bilateral rows for union onsets in `[from, toExclusive)` (both `null` → whole track). Each row carries
 * the A/B events in effect (largest onset `<=` the row time) at that union onset. The inner scans walk the
 * full tracks, so the carry-in state at [from] is resolved correctly even for a windowed range — shared by
 * the full [CalcBuilder.alignBilateral] and the windowed [BilateralAligner] re-solve.
 */
internal fun <A : HasOnset, B : HasOnset> bilateralRows(
    trackA: TimeIndexedList<A>,
    trackB: TimeIndexedList<B>,
    from: TimeCode?,
    toExclusive: TimeCode?,
): List<AlignedEvent2<A, B>> {
    val listA = trackA.toList()
    val listB = trackB.toList()
    val times = (listA.map { it.onset } + listB.map { it.onset })
        .distinct()
        .sorted()
        .filter { (from == null || it >= from) && (toExclusive == null || it < toExclusive) }

    val result = ArrayList<AlignedEvent2<A, B>>(times.size)
    var currentA: A? = null
    var currentB: B? = null
    var idxA = 0
    var idxB = 0
    for (time in times) {
        while (idxA < listA.size && listA[idxA].onset <= time) { currentA = listA[idxA]; idxA++ }
        while (idxB < listB.size && listB[idxB].onset <= time) { currentB = listB[idxB]; idxB++ }
        result.add(AlignedEvent2(time, Pair(currentA, currentB)))
    }
    return result
}
