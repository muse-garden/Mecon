package com.mecon.api.runtime

import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeRange
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Tests for [TimeIndexedList.changedSpan] — the per-event diff primitive that bounds incremental
 * re-alignment windows. Checked against a brute-force reference over distinct-onset lists.
 */
class TimeIndexedListDiffTest {

    private data class Ev(val id: Int, val tick: Int, val payload: Int) : HasOnset {
        override val onset: TimeCode get() = tc(tick)
    }

    private fun til(items: List<Ev>) = TimeIndexedList.of(items)

    /** Brute-force changed span: min/max onset whose (distinct-onset) event differs by value. */
    private fun brute(old: List<Ev>, new: List<Ev>): TimeRange? {
        val oldByOnset = old.associateBy { it.onset }
        val newByOnset = new.associateBy { it.onset }
        val differing = (oldByOnset.keys + newByOnset.keys).filter { oldByOnset[it] != newByOnset[it] }
        if (differing.isEmpty()) return null
        return TimeRange(differing.min(), differing.max())
    }

    @Test
    fun identicalReferenceReturnsNull() {
        val list = til(listOf(Ev(1, 0, 0), Ev(2, 5, 0), Ev(3, 9, 0)))
        // Same instance → BPlusTree.diff skips the whole tree via `===`.
        assertNull(list.changedSpan(list))
    }

    @Test
    fun valueEqualRebuildReturnsNull() {
        val a = til(listOf(Ev(1, 0, 7), Ev(2, 5, 7)))
        val b = til(listOf(Ev(1, 0, 7), Ev(2, 5, 7)))
        assertNull(a.changedSpan(b))
    }

    @Test
    fun singleValueChange() {
        val a = til(listOf(Ev(1, 0, 0), Ev(2, 5, 0), Ev(3, 9, 0)))
        val b = til(listOf(Ev(1, 0, 0), Ev(2, 5, 99), Ev(3, 9, 0)))
        assertEquals(TimeRange(tc(5), tc(5)), a.changedSpan(b))
    }

    @Test
    fun addAndRemoveSpanEndpoints() {
        val a = til(listOf(Ev(1, 0, 0), Ev(2, 9, 0)))
        val added = til(listOf(Ev(1, 0, 0), Ev(3, 4, 0), Ev(2, 9, 0)))
        assertEquals(TimeRange(tc(4), tc(4)), a.changedSpan(added))

        val removed = til(listOf(Ev(2, 9, 0)))
        assertEquals(TimeRange(tc(0), tc(0)), a.changedSpan(removed))
    }

    @Test
    fun crossMeasureChangeSpansBoth() {
        // ticks 1 (measure 1) and 21 (measure 6) both change.
        val a = til(listOf(Ev(1, 1, 0), Ev(2, 10, 0), Ev(3, 21, 0)))
        val b = til(listOf(Ev(1, 1, 1), Ev(2, 10, 0), Ev(3, 21, 1)))
        assertEquals(TimeRange(tc(1), tc(21)), a.changedSpan(b))
    }

    @Test
    fun emptyToNonEmptyAndBack() {
        val empty = TimeIndexedList.empty<Ev>()
        val full = til(listOf(Ev(1, 2, 0), Ev(2, 7, 0)))
        assertEquals(TimeRange(tc(2), tc(7)), empty.changedSpan(full))
        assertEquals(TimeRange(tc(2), tc(7)), full.changedSpan(empty))
        assertNull(empty.changedSpan(TimeIndexedList.empty()))
    }

    @Test
    fun multipleEventsSameMeasureDistinctOnsets() {
        // ticks 0,1,2,3 are all measure 1; change only tick 2.
        val a = til(listOf(Ev(1, 0, 0), Ev(2, 1, 0), Ev(3, 2, 0), Ev(4, 3, 0)))
        val b = til(listOf(Ev(1, 0, 0), Ev(2, 1, 0), Ev(3, 2, 5), Ev(4, 3, 0)))
        assertEquals(TimeRange(tc(2), tc(2)), a.changedSpan(b))
    }

    @Test
    fun sharedStructureAfterPutReturnsNullOutsideEdit() {
        // A persistent edit shares all but the touched measure; an unrelated query still sees no change.
        val base = til(listOf(Ev(1, 0, 0), Ev(2, 40, 0)))
        assertSame(base, base) // sanity
        assertNull(base.changedSpan(base))
    }

    @Test
    fun fuzzMatchesBruteForce() {
        val rnd = Random(20260615)
        repeat(300) {
            val old = randomTrack(rnd)
            val new = randomEdit(old, rnd, idSeed = 1000 + it)
            assertEquals(brute(old, new), til(old).changedSpan(til(new)), "old=$old new=$new")
        }
    }

    // ---- random helpers (distinct ticks → distinct onsets) ----

    private fun randomTrack(rnd: Random): List<Ev> {
        val n = rnd.nextInt(0, 8)
        val ticks = mutableSetOf<Int>()
        while (ticks.size < n) ticks.add(rnd.nextInt(0, 30))
        return ticks.sorted().mapIndexed { i, t -> Ev(i, t, rnd.nextInt(0, 3)) }
    }

    private fun randomEdit(list: List<Ev>, rnd: Random, idSeed: Int): List<Ev> {
        val out = list.toMutableList()
        val used = out.map { it.tick }.toMutableSet()
        when (rnd.nextInt(4)) {
            0 -> { // add at a free tick
                var t = rnd.nextInt(0, 30)
                if (t !in used) out.add(Ev(idSeed, t, rnd.nextInt(0, 3)))
            }
            1 -> if (out.isNotEmpty()) out.removeAt(rnd.nextInt(out.size)) // remove
            2 -> if (out.isNotEmpty()) { // change payload
                val i = rnd.nextInt(out.size)
                out[i] = out[i].copy(payload = out[i].payload + 1)
            }
            3 -> if (out.isNotEmpty()) { // move onset to a free tick
                val i = rnd.nextInt(out.size)
                val t = rnd.nextInt(0, 30)
                if (t !in used) out[i] = out[i].copy(tick = t)
            }
        }
        return out
    }
}

private fun tc(tick: Int): TimeCode = TimeCode.of(tick / 4 + 1, tick % 4, 4)
