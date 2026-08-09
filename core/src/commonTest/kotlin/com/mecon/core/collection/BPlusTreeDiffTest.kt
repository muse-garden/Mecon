package com.mecon.api.collection

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for [BPlusTree.diff] — the persistent-tree structural diff backing incremental change-set
 * computation (undo/redo and the renderer's lineage fallback, see
 * docs/data_model/incremental-update.md and docs/renderer/incremental-rendering.md).
 */
class BPlusTreeDiffTest {

    private val dummyAgg = object : BPlusTreeAggregator<String, Int> {
        override fun extract(value: String): Int = 0
        override fun combine(a1: Int, a2: Int): Int = 0
        override val empty: Int = 0
    }

    private fun tree(order: Int = 4): BPlusTree<Int, String, Int> = BPlusTree(order, dummyAgg)

    /** Collect [BPlusTree.diff] into three maps for easy assertion. */
    private class Diff {
        val removed = HashMap<Int, String>()
        val added = HashMap<Int, String>()
        val changed = HashMap<Int, Pair<String, String>>()
    }

    private fun BPlusTree<Int, String, Int>.diffTo(newer: BPlusTree<Int, String, Int>): Diff {
        val d = Diff()
        diff(
            newer,
            onRemoved = { k, v -> d.removed[k] = v },
            onAdded = { k, v -> d.added[k] = v },
            onChanged = { k, o, n -> d.changed[k] = o to n },
        )
        return d
    }

    @Test
    fun identicalTreesNoDiff() {
        var t = tree()
        for (i in 0 until 50) t = t.put(i, "v$i")
        val d = t.diffTo(t) // same instance → === short-circuit
        assertTrue(d.removed.isEmpty() && d.added.isEmpty() && d.changed.isEmpty())
    }

    @Test
    fun emptyToPopulatedIsAllAdded() {
        var t = tree()
        for (i in 0 until 10) t = t.put(i, "v$i")
        val d = tree().diffTo(t)
        assertEquals((0 until 10).associateWith { "v$it" }, d.added)
        assertTrue(d.removed.isEmpty() && d.changed.isEmpty())
    }

    @Test
    fun singleAddRemoveChange() {
        var base = tree()
        for (i in 0 until 30) base = base.put(i, "v$i")

        val added = base.put(100, "new")
        assertEquals(mapOf(100 to "new"), base.diffTo(added).added)

        val removed = base.remove(15)
        assertEquals(mapOf(15 to "v15"), base.diffTo(removed).removed)

        val changed = base.put(15, "V15")
        assertEquals(mapOf(15 to ("v15" to "V15")), base.diffTo(changed).changed)
    }

    @Test
    fun equalValuePutIsNotAChange() {
        var base = tree()
        for (i in 0 until 20) base = base.put(i, "v$i")
        // Put an equal (==) but fresh String instance for an existing key.
        val same = base.put(5, buildString { append("v"); append(5) })
        val d = base.diffTo(same)
        assertTrue(d.removed.isEmpty() && d.added.isEmpty() && d.changed.isEmpty())
    }

    @Test
    fun crossesSplitsAndMerges() {
        // order = 3 maximises splits/merges so the shape-divergence fallback is well exercised.
        var base = BPlusTree<Int, String, Int>(order = 3, dummyAgg)
        for (i in 0 until 40) base = base.put(i, "v$i")

        var edited = base
        // Remove a contiguous run (triggers merges) and add a high run (triggers splits).
        for (i in 10 until 20) edited = edited.remove(i)
        for (i in 100 until 110) edited = edited.put(i, "v$i")
        edited = edited.put(0, "V0") // a change too

        val d = base.diffTo(edited)
        assertEquals((10 until 20).associateWith { "v$it" }, d.removed)
        assertEquals((100 until 110).associateWith { "v$it" }, d.added)
        assertEquals(mapOf(0 to ("v0" to "V0")), d.changed)
    }

    /** A value whose [equals] bumps a shared counter, so a test can measure how many entries the diff
     *  actually compares — i.e. prove it skips `===`-shared subtrees instead of scanning all N. */
    private class Counted(val s: String) {
        override fun equals(other: Any?): Boolean { comparisons++; return other is Counted && other.s == s }
        override fun hashCode(): Int = s.hashCode()
        companion object { var comparisons = 0 }
    }

    private val countedAgg = object : BPlusTreeAggregator<Counted, Int> {
        override fun extract(value: Counted): Int = 0
        override fun combine(a1: Int, a2: Int): Int = 0
        override val empty: Int = 0
    }

    @Test
    fun largeTreeSingleEditTouchesOnlyLocalNodes() {
        val n = 4000
        val order = 16
        var base = BPlusTree<Int, Counted, Int>(order, countedAgg)
        for (i in 0 until n) base = base.put(i, Counted("v$i"))

        // A single value edit deep in the middle: only that leaf's path is copied; every other subtree
        // stays === with base, so the diff must skip them wholesale.
        val edited = base.put(n / 2, Counted("EDITED"))

        Counted.comparisons = 0
        val changed = HashMap<Int, Pair<Counted, Counted>>()
        base.diff(
            edited,
            onRemoved = { _, _ -> fail("unexpected removal") },
            onAdded = { _, _ -> fail("unexpected addition") },
            onChanged = { k, o, nw -> changed[k] = o to nw },
        )

        assertEquals(setOf(n / 2), changed.keys, "diff must report exactly the one edited key")
        // Value comparisons happen only in non-===-shared leaves. With a single edit that is one leaf
        // (≤ order entries; unchanged siblings within it are === and skipped too), nowhere near n.
        assertTrue(
            Counted.comparisons <= order,
            "diff compared ${Counted.comparisons} values; expected ≲ one leaf ($order), not ~$n — the === pruning is not engaging",
        )
    }

    @Test
    fun fuzzMatchesBruteForce() {
        val rng = Random(20260611)
        repeat(40) {
            val order = 3 + rng.nextInt(6)
            // Build a random base map/tree.
            val baseMap = HashMap<Int, String>()
            var base = BPlusTree<Int, String, Int>(order, dummyAgg)
            repeat(rng.nextInt(120)) {
                val k = rng.nextInt(200)
                val v = "v${rng.nextInt(1000)}"
                base = base.put(k, v); baseMap[k] = v
            }
            // Apply random edits to derive the new tree (persistent, sharing structure).
            val newMap = HashMap(baseMap)
            var edited = base
            repeat(rng.nextInt(60)) {
                val k = rng.nextInt(200)
                when (rng.nextInt(3)) {
                    0, 1 -> { val v = "v${rng.nextInt(1000)}"; edited = edited.put(k, v); newMap[k] = v }
                    else -> { edited = edited.remove(k); newMap.remove(k) }
                }
            }

            // Brute-force reference diff.
            val expRemoved = baseMap.filterKeys { it !in newMap }
            val expAdded = newMap.filterKeys { it !in baseMap }
            val expChanged = baseMap.keys.intersect(newMap.keys)
                .filter { baseMap[it] != newMap[it] }
                .associateWith { baseMap[it]!! to newMap[it]!! }

            val d = base.diffTo(edited)
            assertEquals(expRemoved, d.removed, "removed (order=$order)")
            assertEquals(expAdded, d.added, "added (order=$order)")
            assertEquals(expChanged, d.changed, "changed (order=$order)")
        }
    }
}
