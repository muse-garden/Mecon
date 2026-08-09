package com.mecon.api.computed

import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.HasOnset
import com.mecon.api.runtime.TimeIndexedList

/**
 * Stateful, **incremental** counterparts to [CalcBuilder]. Each aligner caches its two input tracks and the
 * aligned output; [ReferenceAligner.update] / [BilateralAligner.update] diff the new inputs against the
 * cached ones ([TimeIndexedList.changedSpan], backed by `BPlusTree.diff`), re-pair only the affected output
 * rows, and splice them into the persistent output — the rest is reused by reference.
 *
 * Golden rule (guarded by `CalcBuilderIncrementalTest`): `update(a, b).aligned` is value-equal to a full
 * `CalcBuilder.align*(a, b)` for any sequence of edits.
 *
 * The aligners are immutable: `update` returns a new aligner. [lastRecomputedRowCount] /
 * [lastRecomputedWindowStart] / [lastRecomputedWindowEndExclusive] describe the most recent re-solve (for
 * effectiveness assertions and for consumers that maintain derived state per affected onset).
 */

// ---------------------------------------------------------------------------------------------------------
// Reference alignment (alignLe / alignL): one row per reference event, paired with the in-effect align event.
// ---------------------------------------------------------------------------------------------------------

class ReferenceAligner<A : HasOnset, B : HasOnset> private constructor(
    private val strict: Boolean,
    private val offset: Int,
    private val trackA: TimeIndexedList<A>,
    private val trackB: TimeIndexedList<B>,
    val aligned: TimeIndexedList<AlignedEvent2<A, B>>,
    /** Start (inclusive) of the onset window re-solved by the last [update]; null for the initial build / a no-op. */
    val lastRecomputedWindowStart: TimeCode?,
    /** Exclusive end of the re-solved window; null = open to the end of the track. */
    val lastRecomputedWindowEndExclusive: TimeCode?,
    /** Number of output rows re-paired by the last [update] (0 = nothing changed). */
    val lastRecomputedRowCount: Int,
) {

    /**
     * Re-align against new inputs, reusing untouched rows. Window (offset == 0, tight):
     * - reference (A) changes affect exactly their own rows → `[aLo, firstA-after-aHi)`;
     * - align (B) changes shift the in-effect match for reference onsets in `[bLo, firstB-after-bHi)`.
     *
     * The two are merged into one contiguous window `[min start, max exclusive-end)`.
     *
     * With `offset != 0` the B match is **index**-based, so a B change does not just affect rows after it:
     * the shift means the changed/inserted/removed B entry is the target of a reference row `offset`
     * positions away (in either direction), and an add/remove renumbers every later index. A B change can
     * therefore touch any reference row, so the B side conservatively triggers a **full** reference rebuild.
     * A-only changes stay tight even under an offset (the B list — hence every index — is unchanged).
     */
    fun update(newA: TimeIndexedList<A>, newB: TimeIndexedList<B>): ReferenceAligner<A, B> {
        val aSpan = trackA.changedSpan(newA)
        val bSpan = trackB.changedSpan(newB)
        if (aSpan == null && bSpan == null) {
            return ReferenceAligner(strict, offset, newA, newB, aligned, null, null, 0)
        }

        if (bSpan != null && offset != 0) {
            val rebuilt = referenceAlignFull(newA, newB, strict, offset)
            return ReferenceAligner(strict, offset, newA, newB, rebuilt, newA.firstOrNull()?.onset, null, rebuilt.size)
        }

        val windowStart = listOfNotNull(aSpan?.start, bSpan?.start).min()
        val ends = ArrayList<TimeCode?>(2)
        if (aSpan != null) ends.add(newA.firstAfter(aSpan.end)?.onset)
        if (bSpan != null) {
            // Realignment point after a B change: the first reference row whose match is an unchanged B past
            // the change. Under alignLe (`<=`) that row is the one *at* the next B's onset (it now matches the
            // next B). Under alignL (`<`) a reference row exactly at the next B's onset still matches the older
            // B, so the affected range extends one reference row further. (offset == 0 on this path.)
            val nextBOnset = newB.firstAfter(bSpan.end)?.onset
            ends.add(
                when {
                    nextBOnset == null -> null
                    !strict -> nextBOnset
                    else -> newA.firstAfter(nextBOnset)?.onset
                }
            )
        }
        val windowEndExcl: TimeCode? = if (ends.any { it == null }) null else ends.filterNotNull().max()

        val refEvents = newA.windowEvents(windowStart, windowEndExcl)
        val newRows = if (offset == 0) {
            // Hot path: in-effect match via O(log) track query — no full toList of the align track.
            refEvents.map { a ->
                val b = if (strict) newB.lastBefore(a.onset) else newB.lastAtOrBefore(a.onset)
                AlignedEvent2(a.onset, Pair(a, b))
            }
        } else {
            // offset != 0 but only A changed → index-based match against the unchanged B list.
            val alignList = newB.toList()
            refEvents.map { referenceRow(it, alignList, strict, offset) }
        }

        val spliced = spliceWindow(aligned, windowStart, windowEndExcl, newRows)
        return ReferenceAligner(strict, offset, newA, newB, spliced, windowStart, windowEndExcl, newRows.size)
    }

    companion object {
        /** Build the initial aligner (full alignment). [strict] = use `<` (alignL) instead of `<=` (alignLe). */
        fun <A : HasOnset, B : HasOnset> build(
            referenceTrack: TimeIndexedList<A>,
            alignTrack: TimeIndexedList<B>,
            strict: Boolean = false,
            offset: Int = 0,
        ): ReferenceAligner<A, B> {
            val aligned = referenceAlignFull(referenceTrack, alignTrack, strict, offset)
            return ReferenceAligner(strict, offset, referenceTrack, alignTrack, aligned, null, null, aligned.size)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------
// Bilateral alignment: one row per union onset, carrying the in-effect event of each track.
// ---------------------------------------------------------------------------------------------------------

class BilateralAligner<A : HasOnset, B : HasOnset> private constructor(
    private val trackA: TimeIndexedList<A>,
    private val trackB: TimeIndexedList<B>,
    val aligned: TimeIndexedList<AlignedEvent2<A, B>>,
    val lastRecomputedWindowStart: TimeCode?,
    val lastRecomputedWindowEndExclusive: TimeCode?,
    val lastRecomputedRowCount: Int,
) {

    /**
     * Re-align against new inputs, reusing untouched rows. The carry-forward (each row holds the in-effect
     * event of each track) realigns at the first **unchanged** event after the change on each track, so the
     * window is `[min(aLo, bLo), max(firstA-after-aHi, firstB-after-bHi))`. Beyond it every row is value-equal
     * to the cached one (the changed onsets all lie before it, so currentA / currentB and the union-time set
     * match) → reused.
     */
    fun update(newA: TimeIndexedList<A>, newB: TimeIndexedList<B>): BilateralAligner<A, B> {
        val aSpan = trackA.changedSpan(newA)
        val bSpan = trackB.changedSpan(newB)
        if (aSpan == null && bSpan == null) {
            return BilateralAligner(newA, newB, aligned, null, null, 0)
        }

        val windowStart = listOfNotNull(aSpan?.start, bSpan?.start).min()
        val ends = ArrayList<TimeCode?>(2)
        if (aSpan != null) ends.add(newA.firstAfter(aSpan.end)?.onset)
        if (bSpan != null) ends.add(newB.firstAfter(bSpan.end)?.onset)
        val windowEndExcl: TimeCode? = if (ends.any { it == null }) null else ends.filterNotNull().max()

        val newRows = windowedBilateralRows(newA, newB, windowStart, windowEndExcl)
        val spliced = spliceWindow(aligned, windowStart, windowEndExcl, newRows)
        return BilateralAligner(newA, newB, spliced, windowStart, windowEndExcl, newRows.size)
    }

    companion object {
        fun <A : HasOnset, B : HasOnset> build(
            trackA: TimeIndexedList<A>,
            trackB: TimeIndexedList<B>,
        ): BilateralAligner<A, B> {
            val aligned = CalcBuilder.alignBilateral(trackA, trackB)
            return BilateralAligner(trackA, trackB, aligned, null, null, aligned.size)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------
// Shared window helpers
// ---------------------------------------------------------------------------------------------------------

/** Events with onset in `[start, endExclusive)` (endExclusive null → to the end of the track). O(window). */
private fun <T : HasOnset> TimeIndexedList<T>.windowEvents(start: TimeCode, endExclusive: TimeCode?): List<T> =
    if (endExclusive == null) atOrAfter(start)
    else range(start, endExclusive).filter { it.onset < endExclusive }

/**
 * Replace the rows of [old] whose onset is in `[start, endExclusive)` with [newRows]. Old rows are removed
 * by identity (they come from [old] itself), so the cost is O(window · log N); rows outside the window are
 * reused by reference.
 */
private fun <R : HasOnset> spliceWindow(
    old: TimeIndexedList<R>,
    start: TimeCode,
    endExclusive: TimeCode?,
    newRows: List<R>,
): TimeIndexedList<R> {
    var result = old
    for (r in old.windowEvents(start, endExclusive)) result = result.remove(r)
    return result.insertAll(newRows)
}

/** Bilateral rows for union onsets in `[start, endExclusive)`, carry-in seeded from the tracks. O(window). */
private fun <A : HasOnset, B : HasOnset> windowedBilateralRows(
    trackA: TimeIndexedList<A>,
    trackB: TimeIndexedList<B>,
    start: TimeCode,
    endExclusive: TimeCode?,
): List<AlignedEvent2<A, B>> {
    val aWin = trackA.windowEvents(start, endExclusive)
    val bWin = trackB.windowEvents(start, endExclusive)
    val times = (aWin.map { it.onset } + bWin.map { it.onset }).distinct().sorted()

    var currentA: A? = trackA.lastBefore(start)
    var currentB: B? = trackB.lastBefore(start)
    var idxA = 0
    var idxB = 0
    val result = ArrayList<AlignedEvent2<A, B>>(times.size)
    for (time in times) {
        while (idxA < aWin.size && aWin[idxA].onset <= time) { currentA = aWin[idxA]; idxA++ }
        while (idxB < bWin.size && bWin[idxB].onset <= time) { currentB = bWin[idxB]; idxB++ }
        result.add(AlignedEvent2(time, Pair(currentA, currentB)))
    }
    return result
}
