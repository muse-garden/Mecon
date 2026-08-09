package com.mecon.api.runtime

import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeRange
import com.mecon.api.collection.BPlusTree
import com.mecon.api.collection.BPlusTreeAggregator

interface HasOnset {
    val onset: TimeCode
}

object TimeIndexedListAggregator : BPlusTreeAggregator<List<HasOnset>, Int> {
    override fun extract(value: List<HasOnset>): Int = value.size
    override fun combine(a1: Int, a2: Int): Int = a1 + a2
    override val empty: Int = 0
}

class TimeIndexedList<T : HasOnset> private constructor(
    private val tree: BPlusTree<Int, List<T>, Int>
) : Iterable<T> {

    val size: Int get() = tree.aggregate ?: 0

    fun isEmpty(): Boolean = size == 0
    fun isNotEmpty(): Boolean = size > 0

    operator fun get(index: Int): T {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException()
        val res = tree.findByPrefix(index) ?: throw IndexOutOfBoundsException()
        val measureEvents = res.first.value
        val prefixBeforeMeasure = res.second
        val indexInMeasure = index - prefixBeforeMeasure
        return measureEvents[indexInMeasure]
    }

    fun toList(): List<T> = tree.flatMap { it.value }

    override fun iterator(): Iterator<T> = tree.flatMap { it.value }.iterator()

    fun range(start: TimeCode, end: TimeCode): List<T> {
        if (isEmpty()) return emptyList()
        val result = mutableListOf<T>()
        val iter = tree.iteratorFrom(start.measure)
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key > end.measure) break
            for (item in entry.value) {
                if (item.onset > end) break
                if (item.onset >= start) result.add(item)
            }
        }
        return result
    }

    fun range(timeRange: TimeRange): List<T> = range(timeRange.start, timeRange.end)

    fun at(time: TimeCode): List<T> {
        val measureEvents = tree.get(time.measure) ?: return emptyList()
        val result = mutableListOf<T>()
        for (item in measureEvents) {
            if (item.onset > time) break
            if (item.onset == time) result.add(item)
        }
        return result
    }

    fun lastBefore(time: TimeCode): T? {
        if (isEmpty()) return null
        
        val measureEvents = tree.get(time.measure)
        if (measureEvents != null) {
            var lastInMeasure: T? = null
            for (item in measureEvents) {
                if (item.onset < time) lastInMeasure = item else break
            }
            if (lastInMeasure != null) return lastInMeasure
        }
        
        val numBefore = tree.prefixSum(time.measure)
        if (numBefore == 0) return null
        return get(numBefore - 1)
    }

    fun firstAtOrAfter(time: TimeCode): T? {
        if (isEmpty()) return null
        val iter = tree.iteratorFrom(time.measure)
        while (iter.hasNext()) {
            val entry = iter.next()
            for (item in entry.value) {
                if (item.onset >= time) return item
            }
        }
        return null
    }

    fun firstAfter(time: TimeCode): T? {
        if (isEmpty()) return null
        val iter = tree.iteratorFrom(time.measure)
        while (iter.hasNext()) {
            val entry = iter.next()
            for (item in entry.value) {
                if (item.onset > time) return item
            }
        }
        return null
    }

    fun lastAtOrBefore(time: TimeCode): T? {
        if (isEmpty()) return null
        val measureEvents = tree.get(time.measure)
        if (measureEvents != null) {
            var lastInMeasure: T? = null
            for (item in measureEvents) {
                if (item.onset <= time) lastInMeasure = item else break
            }
            if (lastInMeasure != null) return lastInMeasure
        }
        val numBefore = tree.prefixSum(time.measure)
        if (numBefore == 0) return null
        return get(numBefore - 1)
    }

    fun before(time: TimeCode): List<T> {
        if (isEmpty()) return emptyList()
        val result = mutableListOf<T>()
        val iter = tree.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key > time.measure) break
            for (item in entry.value) {
                if (item.onset < time) {
                    result.add(item)
                } else {
                    break
                }
            }
        }
        return result
    }

    fun atOrAfter(time: TimeCode): List<T> {
        if (isEmpty()) return emptyList()
        val result = mutableListOf<T>()
        val iter = tree.iteratorFrom(time.measure)
        while (iter.hasNext()) {
            val entry = iter.next()
            for (item in entry.value) {
                if (item.onset >= time) result.add(item)
            }
        }
        return result
    }

    fun insert(item: T): TimeIndexedList<T> {
        val measure = item.onset.measure
        val existing = tree.get(measure) ?: emptyList()
        val insertIdx = existing.binarySearchBy(item.onset) { it.onset }
        val actualIdx = if (insertIdx >= 0) insertIdx else -insertIdx - 1
        val newMeasure = existing.toMutableList().apply { add(actualIdx, item) }
        return TimeIndexedList(tree.put(measure, newMeasure))
    }

    fun insertAll(newItems: Iterable<T>): TimeIndexedList<T> {
        var currentTree = tree
        val byMeasure = newItems.groupBy { it.onset.measure }
        for ((measure, itemsToInsert) in byMeasure) {
            val existing = currentTree.get(measure) ?: emptyList()
            val combined = (existing + itemsToInsert).sortedBy { it.onset }
            currentTree = currentTree.put(measure, combined)
        }
        return TimeIndexedList(currentTree)
    }

    fun removeWhere(predicate: (T) -> Boolean): TimeIndexedList<T> {
        var currentTree = tree
        val toRemove = mutableListOf<Int>()
        for (entry in currentTree) {
            val filtered = entry.value.filterNot(predicate)
            if (filtered.size != entry.value.size) {
                if (filtered.isEmpty()) {
                    toRemove.add(entry.key)
                } else {
                    currentTree = currentTree.put(entry.key, filtered)
                }
            }
        }
        for (key in toRemove) {
            currentTree = currentTree.remove(key)
        }
        return TimeIndexedList(currentTree)
    }

    fun remove(item: T): TimeIndexedList<T> {
        val measure = item.onset.measure
        val existing = tree.get(measure) ?: return this
        val filtered = existing.filterNot { it === item }
        if (filtered.size == existing.size) return this
        val newTree = if (filtered.isEmpty()) {
            tree.remove(measure)
        } else {
            tree.put(measure, filtered)
        }
        return TimeIndexedList(newTree)
    }

    /**
     * Smallest inclusive onset span `[firstOnset, lastOnset]` whose per-event content differs from
     * [newer], or `null` when both lists hold value-equal events at every onset.
     *
     * Built on [com.mecon.api.collection.BPlusTree.diff]: measures that are referentially shared (`===`)
     * between the two trees are skipped wholesale, so two lists derived from one another by a handful of
     * edits diff in **O(changes · log N)** rather than O(N) (they share all but the edited measures). When
     * the trees share no structure the diff still returns the correct span, just in O(N).
     *
     * A measure reported as changed is examined per onset: an onset present on only one side, or whose
     * event sublist differs by value, contributes to the span. Used to bound incremental re-alignment
     * windows — see `CalcBuilderIncremental`.
     */
    fun changedSpan(newer: TimeIndexedList<T>): TimeRange? {
        var lo: TimeCode? = null
        var hi: TimeCode? = null
        fun touch(onset: TimeCode) {
            val l = lo
            val h = hi
            if (l == null || onset < l) lo = onset
            if (h == null || onset > h) hi = onset
        }
        tree.diff(
            newer.tree,
            onRemoved = { _, list -> list.forEach { touch(it.onset) } },
            onAdded = { _, list -> list.forEach { touch(it.onset) } },
            onChanged = { _, oldList, newList ->
                val oldByOnset = oldList.groupBy { it.onset }
                val newByOnset = newList.groupBy { it.onset }
                for (onset in oldByOnset.keys + newByOnset.keys) {
                    // Order-sensitive list equality per onset: an onset on only one side compares against
                    // null; same onset with a different event sublist (add/remove/value change) differs.
                    if (oldByOnset[onset] != newByOnset[onset]) touch(onset)
                }
            },
        )
        val l = lo ?: return null
        return TimeRange(l, hi!!)
    }

    fun <R> map(transform: (T) -> R): List<R> = toList().map(transform)

    fun filter(predicate: (T) -> Boolean): TimeIndexedList<T> {
        val items = toList().filter(predicate)
        return TimeIndexedList.of(items)
    }

    fun find(predicate: (T) -> Boolean): T? {
        for (entry in tree) {
            val found = entry.value.find(predicate)
            if (found != null) return found
        }
        return null
    }

    fun any(predicate: (T) -> Boolean): Boolean {
        for (entry in tree) {
            if (entry.value.any(predicate)) return true
        }
        return false
    }

    fun all(predicate: (T) -> Boolean): Boolean {
        for (entry in tree) {
            if (!entry.value.all(predicate)) return false
        }
        return true
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun <T : HasOnset> empty(): TimeIndexedList<T> {
            val agg = TimeIndexedListAggregator as BPlusTreeAggregator<List<T>, Int>
            return TimeIndexedList(BPlusTree(aggregator = agg))
        }

        @Suppress("UNCHECKED_CAST")
        fun <T : HasOnset> of(items: Iterable<T>): TimeIndexedList<T> {
            val agg = TimeIndexedListAggregator as BPlusTreeAggregator<List<T>, Int>
            var tree = BPlusTree<Int, List<T>, Int>(aggregator = agg)
            val byMeasure = items.groupBy { it.onset.measure }
            for ((measure, list) in byMeasure) {
                val sorted = list.sortedBy { it.onset }
                tree = tree.put(measure, sorted)
            }
            return TimeIndexedList(tree)
        }

        fun <T : HasOnset> of(vararg items: T): TimeIndexedList<T> = of(items.toList())
    }
}
