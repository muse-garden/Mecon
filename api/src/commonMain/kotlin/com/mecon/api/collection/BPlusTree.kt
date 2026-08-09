package com.mecon.api.collection

import com.mecon.api.config.MeconConfig

interface BPlusTreeAggregator<V, A : Comparable<A>> {
    fun extract(value: V): A
    fun combine(a1: A, a2: A): A
    val empty: A
}

class BPlusTree<K : Comparable<K>, V, A : Comparable<A>> private constructor(
    val order: Int,
    internal val root: Node<K, V, A>,
    val size: Int,
    val aggregator: BPlusTreeAggregator<V, A>?
) : Iterable<Map.Entry<K, V>> {

    init {
        require(order >= 3) { "Order must be at least 3" }
    }

    constructor(order: Int = MeconConfig.Collection.BPLUS_TREE_DEFAULT_ORDER, aggregator: BPlusTreeAggregator<V, A>? = null) : this(
        order,
        LeafNode(emptyList(), emptyList(), aggregator?.empty),
        0,
        aggregator
    )

    val aggregate: A? get() = root.aggregate

    fun get(key: K): V? = root.get(key)

    fun put(key: K, value: V): BPlusTree<K, V, A> {
        return when (val res = root.put(key, value, order, aggregator)) {
            is PutResult.Unchanged -> this
            is PutResult.Updated -> BPlusTree(order, res.node, size + res.sizeDelta, aggregator)
            is PutResult.Split -> {
                val newRoot = InternalNode(
                    listOf(res.splitKey),
                    listOf(res.left, res.right),
                    InternalNode.computeAggregate(listOf(res.left, res.right), aggregator)
                )
                BPlusTree(order, newRoot, size + res.sizeDelta, aggregator)
            }
        }
    }

    fun remove(key: K): BPlusTree<K, V, A> {
        return when (val res = root.remove(key, order, aggregator)) {
            is RemoveResult.Unchanged -> this
            is RemoveResult.Updated -> BPlusTree(order, res.node, size + res.sizeDelta, aggregator)
            is RemoveResult.Underflow -> {
                val underflowed = res.node
                if (underflowed is InternalNode && underflowed.children.size == 1) {
                    BPlusTree(order, underflowed.children.first(), size + res.sizeDelta, aggregator)
                } else if (underflowed is LeafNode && underflowed.keys.isEmpty() && (size + res.sizeDelta) == 0) {
                    BPlusTree(order, LeafNode(emptyList(), emptyList(), aggregator?.empty), 0, aggregator)
                } else {
                    BPlusTree(order, underflowed, size + res.sizeDelta, aggregator)
                }
            }
        }
    }

    fun prefixSum(key: K): A {
        requireNotNull(aggregator) { "Aggregator not configured" }
        return root.prefixSum(key, aggregator)
    }

    fun findByPrefix(target: A): Pair<Map.Entry<K, V>, A>? {
        requireNotNull(aggregator) { "Aggregator not configured" }
        val res = root.findByPrefix(target, aggregator, aggregator.empty) ?: return null
        return Pair(EntryImpl(res.first.first, res.first.second), res.second)
    }

    /**
     * Aggregate values whose keys fall in the inclusive range [[start], [end]]. Fully covered
     * persistent subtrees contribute their cached aggregate directly; only the two boundary paths
     * are descended. The usual cost is therefore O(log N), independent of the number of values in
     * the range (for associative aggregators such as sum / min / max).
     */
    fun aggregateRange(start: K, end: K): A {
        require(start <= end) { "Range start must not be greater than end" }
        val configured = requireNotNull(aggregator) { "Aggregator not configured" }
        return aggregateNodeRange(root, start, end, configured, null, null)
    }

    fun iteratorFrom(key: K): Iterator<Map.Entry<K, V>> = BPlusTreeIterator(root, key)

    override fun iterator(): Iterator<Map.Entry<K, V>> = BPlusTreeIterator(root)

    /**
     * Structural diff against [newer], exploiting persistent sharing: any subtree that is referentially
     * identical (`===`) between the two trees is skipped wholesale. Two trees produced from one another
     * by a handful of `put` / `remove` ops therefore diff in **O(changes · log N)** rather than O(N) —
     * they share all but the edited root-to-leaf paths.
     *
     * The receiver is the *old* tree, [newer] the *new* one. Each differing key is reported exactly once:
     * present only in the receiver → [onRemoved]; only in [newer] → [onAdded]; present in both but with
     * values that are neither identical (`===`) nor equal (`==`) → [onChanged] (old then new value).
     */
    fun diff(
        newer: BPlusTree<K, V, A>,
        onRemoved: (K, V) -> Unit,
        onAdded: (K, V) -> Unit,
        onChanged: (K, V, V) -> Unit,
    ) {
        diffNodes(root, newer.root, onRemoved, onAdded, onChanged)
    }
}

private fun <K : Comparable<K>, V, A : Comparable<A>> aggregateNodeRange(
    node: Node<K, V, A>,
    start: K,
    end: K,
    aggregator: BPlusTreeAggregator<V, A>,
    lowerInclusive: K?,
    upperExclusive: K?,
): A {
    val outsideLeft = upperExclusive != null && start >= upperExclusive
    val outsideRight = lowerInclusive != null && end < lowerInclusive
    if (outsideLeft || outsideRight) return aggregator.empty

    val coversLower = lowerInclusive != null && start <= lowerInclusive
    val coversUpper = upperExclusive != null && end >= upperExclusive
    if (coversLower && coversUpper) return node.aggregate ?: aggregator.empty

    return when (node) {
        is LeafNode -> {
            var result = aggregator.empty
            for (i in node.keys.indices) {
                val key = node.keys[i]
                if (key < start) continue
                if (key > end) break
                result = aggregator.combine(result, aggregator.extract(node.values[i]))
            }
            result
        }
        is InternalNode -> {
            var result = aggregator.empty
            for (i in node.children.indices) {
                val childLower = if (i == 0) lowerInclusive else node.keys[i - 1]
                val childUpper = if (i == node.children.lastIndex) upperExclusive else node.keys[i]
                val childAggregate = aggregateNodeRange(
                    node.children[i], start, end, aggregator, childLower, childUpper
                )
                result = aggregator.combine(result, childAggregate)
            }
            result
        }
    }
}

/**
 * Diff two nodes, reporting differing entries. Skips shared (`===`) subtrees. When both nodes are
 * internal with identical separator keys — the shape after a non-splitting put/remove — children align
 * by index and we recurse child-by-child (each shared child returns immediately via the `===` check).
 * When shapes diverge (a split/merge changed the separators, or heights differ) we fall back to merging
 * the two subtrees' flattened, sorted entry lists; this is localised to the divergent subtree because
 * still-shared siblings were skipped one level up.
 */
private fun <K : Comparable<K>, V, A : Comparable<A>> diffNodes(
    old: Node<K, V, A>,
    new: Node<K, V, A>,
    onRemoved: (K, V) -> Unit,
    onAdded: (K, V) -> Unit,
    onChanged: (K, V, V) -> Unit,
) {
    if (old === new) return
    if (old is LeafNode && new is LeafNode) {
        mergeLeaves(old, new, onRemoved, onAdded, onChanged)
        return
    }
    if (old is InternalNode && new is InternalNode && old.keys == new.keys) {
        val oc = old.children
        val nc = new.children
        for (i in oc.indices) diffNodes(oc[i], nc[i], onRemoved, onAdded, onChanged)
        return
    }
    mergeSorted(collectEntries(old), collectEntries(new), onRemoved, onAdded, onChanged)
}

/** Merge two sorted leaf nodes by key, reporting removed / added / changed entries. */
private fun <K : Comparable<K>, V, A : Comparable<A>> mergeLeaves(
    old: LeafNode<K, V, A>,
    new: LeafNode<K, V, A>,
    onRemoved: (K, V) -> Unit,
    onAdded: (K, V) -> Unit,
    onChanged: (K, V, V) -> Unit,
) {
    val ok = old.keys; val ov = old.values
    val nk = new.keys; val nv = new.values
    var i = 0; var j = 0
    while (i < ok.size && j < nk.size) {
        val c = ok[i].compareTo(nk[j])
        when {
            c < 0 -> { onRemoved(ok[i], ov[i]); i++ }
            c > 0 -> { onAdded(nk[j], nv[j]); j++ }
            else -> {
                val a = ov[i]; val b = nv[j]
                if (a !== b && a != b) onChanged(ok[i], a, b)
                i++; j++
            }
        }
    }
    while (i < ok.size) { onRemoved(ok[i], ov[i]); i++ }
    while (j < nk.size) { onAdded(nk[j], nv[j]); j++ }
}

/** Merge two key-sorted entry lists, reporting removed / added / changed entries. */
private fun <K : Comparable<K>, V> mergeSorted(
    old: List<Pair<K, V>>,
    new: List<Pair<K, V>>,
    onRemoved: (K, V) -> Unit,
    onAdded: (K, V) -> Unit,
    onChanged: (K, V, V) -> Unit,
) {
    var i = 0; var j = 0
    while (i < old.size && j < new.size) {
        val c = old[i].first.compareTo(new[j].first)
        when {
            c < 0 -> { onRemoved(old[i].first, old[i].second); i++ }
            c > 0 -> { onAdded(new[j].first, new[j].second); j++ }
            else -> {
                val a = old[i].second; val b = new[j].second
                if (a !== b && a != b) onChanged(old[i].first, a, b)
                i++; j++
            }
        }
    }
    while (i < old.size) { onRemoved(old[i].first, old[i].second); i++ }
    while (j < new.size) { onAdded(new[j].first, new[j].second); j++ }
}

/** In-order (key-sorted) flatten of a subtree to (key, value) pairs. */
private fun <K : Comparable<K>, V, A : Comparable<A>> collectEntries(node: Node<K, V, A>): List<Pair<K, V>> {
    val out = ArrayList<Pair<K, V>>()
    fun rec(n: Node<K, V, A>) {
        when (n) {
            is LeafNode -> for (idx in n.keys.indices) out.add(n.keys[idx] to n.values[idx])
            is InternalNode -> for (child in n.children) rec(child)
        }
    }
    rec(node)
    return out
}

internal sealed interface PutResult<K : Comparable<K>, V, A : Comparable<A>> {
    class Unchanged<K : Comparable<K>, V, A : Comparable<A>> : PutResult<K, V, A>
    class Updated<K : Comparable<K>, V, A : Comparable<A>>(val node: Node<K, V, A>, val sizeDelta: Int) : PutResult<K, V, A>
    class Split<K : Comparable<K>, V, A : Comparable<A>>(
        val left: Node<K, V, A>,
        val splitKey: K,
        val right: Node<K, V, A>,
        val sizeDelta: Int
    ) : PutResult<K, V, A>
}

internal sealed interface RemoveResult<K : Comparable<K>, V, A : Comparable<A>> {
    class Unchanged<K : Comparable<K>, V, A : Comparable<A>> : RemoveResult<K, V, A>
    class Updated<K : Comparable<K>, V, A : Comparable<A>>(val node: Node<K, V, A>, val sizeDelta: Int) : RemoveResult<K, V, A>
    class Underflow<K : Comparable<K>, V, A : Comparable<A>>(val node: Node<K, V, A>, val sizeDelta: Int) : RemoveResult<K, V, A>
}

internal sealed interface Node<K : Comparable<K>, V, A : Comparable<A>> {
    val keys: List<K>
    val aggregate: A?
    fun get(key: K): V?
    fun put(key: K, value: V, order: Int, aggregator: BPlusTreeAggregator<V, A>?): PutResult<K, V, A>
    fun remove(key: K, order: Int, aggregator: BPlusTreeAggregator<V, A>?): RemoveResult<K, V, A>
    fun prefixSum(key: K, aggregator: BPlusTreeAggregator<V, A>): A
    fun findByPrefix(target: A, aggregator: BPlusTreeAggregator<V, A>, currentAccum: A): Pair<Pair<K, V>, A>?
}

internal class LeafNode<K : Comparable<K>, V, A : Comparable<A>>(
    override val keys: List<K>,
    val values: List<V>,
    override val aggregate: A?
) : Node<K, V, A> {

    companion object {
        fun <V, A : Comparable<A>> computeAggregate(values: List<V>, aggregator: BPlusTreeAggregator<V, A>?): A? {
            if (aggregator == null) return null
            if (values.isEmpty()) return aggregator.empty
            var acc = aggregator.extract(values[0])
            for (i in 1 until values.size) {
                acc = aggregator.combine(acc, aggregator.extract(values[i]))
            }
            return acc
        }
    }

    override fun get(key: K): V? {
        val idx = keys.binarySearch(key)
        return if (idx >= 0) values[idx] else null
    }

    override fun put(key: K, value: V, order: Int, aggregator: BPlusTreeAggregator<V, A>?): PutResult<K, V, A> {
        val idx = keys.binarySearch(key)
        if (idx >= 0) {
            if (values[idx] == value) return PutResult.Unchanged()
            val newValues = values.toMutableList().apply { set(idx, value) }
            return PutResult.Updated(LeafNode(keys, newValues, computeAggregate(newValues, aggregator)), 0)
        }
        val insIdx = -idx - 1
        val newKeys = keys.toMutableList().apply { add(insIdx, key) }
        val newValues = values.toMutableList().apply { add(insIdx, value) }
        val maxKeys = order - 1
        if (newKeys.size <= maxKeys) {
            return PutResult.Updated(LeafNode(newKeys, newValues, computeAggregate(newValues, aggregator)), 1)
        }
        val mid = newKeys.size / 2
        val leftKeys = newKeys.subList(0, mid)
        val leftValues = newValues.subList(0, mid)
        val rightKeys = newKeys.subList(mid, newKeys.size)
        val rightValues = newValues.subList(mid, newValues.size)
        val splitKey = rightKeys.first()
        return PutResult.Split(
            LeafNode(leftKeys, leftValues, computeAggregate(leftValues, aggregator)),
            splitKey,
            LeafNode(rightKeys, rightValues, computeAggregate(rightValues, aggregator)),
            1
        )
    }

    override fun remove(key: K, order: Int, aggregator: BPlusTreeAggregator<V, A>?): RemoveResult<K, V, A> {
        val idx = keys.binarySearch(key)
        if (idx < 0) return RemoveResult.Unchanged()
        val newKeys = keys.toMutableList().apply { removeAt(idx) }
        val newValues = values.toMutableList().apply { removeAt(idx) }
        val minSize = (order + 1) / 2 - 1
        val newNode = LeafNode(newKeys, newValues, computeAggregate(newValues, aggregator))
        return if (newKeys.size >= minSize) {
            RemoveResult.Updated(newNode, -1)
        } else {
            RemoveResult.Underflow(newNode, -1)
        }
    }

    override fun prefixSum(key: K, aggregator: BPlusTreeAggregator<V, A>): A {
        var acc = aggregator.empty
        for (i in keys.indices) {
            if (keys[i] < key) {
                acc = aggregator.combine(acc, aggregator.extract(values[i]))
            } else {
                break
            }
        }
        return acc
    }

    override fun findByPrefix(target: A, aggregator: BPlusTreeAggregator<V, A>, currentAccum: A): Pair<Pair<K, V>, A>? {
        var acc = currentAccum
        for (i in keys.indices) {
            val nextAcc = aggregator.combine(acc, aggregator.extract(values[i]))
            if (nextAcc > target) {
                return Pair(keys[i] to values[i], acc)
            }
            acc = nextAcc
        }
        return null
    }
}

internal class InternalNode<K : Comparable<K>, V, A : Comparable<A>>(
    override val keys: List<K>,
    val children: List<Node<K, V, A>>,
    override val aggregate: A?
) : Node<K, V, A> {

    companion object {
        fun <K : Comparable<K>, V, A : Comparable<A>> computeAggregate(children: List<Node<K, V, A>>, aggregator: BPlusTreeAggregator<V, A>?): A? {
            if (aggregator == null) return null
            if (children.isEmpty()) return aggregator.empty
            var acc = children[0].aggregate!!
            for (i in 1 until children.size) {
                acc = aggregator.combine(acc, children[i].aggregate!!)
            }
            return acc
        }
    }

    override fun get(key: K): V? {
        val idx = keys.binarySearch(key)
        val childIdx = if (idx >= 0) idx + 1 else -idx - 1
        return children[childIdx].get(key)
    }

    override fun put(key: K, value: V, order: Int, aggregator: BPlusTreeAggregator<V, A>?): PutResult<K, V, A> {
        val idx = keys.binarySearch(key)
        val childIdx = if (idx >= 0) idx + 1 else -idx - 1
        val res = children[childIdx].put(key, value, order, aggregator)
        return when (res) {
            is PutResult.Unchanged -> PutResult.Unchanged()
            is PutResult.Updated -> {
                val newChildren = children.toMutableList().apply { set(childIdx, res.node) }
                PutResult.Updated(InternalNode(keys, newChildren, computeAggregate(newChildren, aggregator)), res.sizeDelta)
            }
            is PutResult.Split -> {
                val newKeys = keys.toMutableList().apply { add(childIdx, res.splitKey) }
                val newChildren = children.toMutableList().apply {
                    set(childIdx, res.left)
                    add(childIdx + 1, res.right)
                }
                if (newChildren.size <= order) {
                    PutResult.Updated(InternalNode(newKeys, newChildren, computeAggregate(newChildren, aggregator)), res.sizeDelta)
                } else {
                    val mid = (newChildren.size + 1) / 2
                    val leftKeys = newKeys.subList(0, mid - 1)
                    val leftChildren = newChildren.subList(0, mid)
                    val rightKeys = newKeys.subList(mid, newKeys.size)
                    val rightChildren = newChildren.subList(mid, newChildren.size)
                    val splitKey = newKeys[mid - 1]
                    PutResult.Split(
                        InternalNode(leftKeys, leftChildren, computeAggregate(leftChildren, aggregator)),
                        splitKey,
                        InternalNode(rightKeys, rightChildren, computeAggregate(rightChildren, aggregator)),
                        res.sizeDelta
                    )
                }
            }
        }
    }

    override fun remove(key: K, order: Int, aggregator: BPlusTreeAggregator<V, A>?): RemoveResult<K, V, A> {
        val idx = keys.binarySearch(key)
        val childIdx = if (idx >= 0) idx + 1 else -idx - 1
        return when (val res = children[childIdx].remove(key, order, aggregator)) {
            is RemoveResult.Unchanged -> RemoveResult.Unchanged()
            is RemoveResult.Updated -> {
                val newChildren = children.toMutableList().apply { set(childIdx, res.node) }
                RemoveResult.Updated(InternalNode(keys, newChildren, computeAggregate(newChildren, aggregator)), res.sizeDelta)
            }
            is RemoveResult.Underflow -> {
                handleUnderflow(childIdx, res.node, order, res.sizeDelta, aggregator)
            }
        }
    }

    private fun handleUnderflow(childIdx: Int, underflowed: Node<K, V, A>, order: Int, sizeDelta: Int, aggregator: BPlusTreeAggregator<V, A>?): RemoveResult<K, V, A> {
        if (childIdx > 0) {
            val leftSibling = children[childIdx - 1]
            if (canBorrow(leftSibling, order)) {
                val (newLeft, newUnderflowed, newRoutingKey) = borrowRight(leftSibling, underflowed, keys[childIdx - 1], aggregator)
                val newKeys = keys.toMutableList().apply { set(childIdx - 1, newRoutingKey) }
                val newChildren = children.toMutableList().apply {
                    set(childIdx - 1, newLeft)
                    set(childIdx, newUnderflowed)
                }
                return RemoveResult.Updated(InternalNode(newKeys, newChildren, computeAggregate(newChildren, aggregator)), sizeDelta)
            }
        }
        if (childIdx < children.lastIndex) {
            val rightSibling = children[childIdx + 1]
            if (canBorrow(rightSibling, order)) {
                val (newUnderflowed, newRight, newRoutingKey) = borrowLeft(underflowed, rightSibling, keys[childIdx], aggregator)
                val newKeys = keys.toMutableList().apply { set(childIdx, newRoutingKey) }
                val newChildren = children.toMutableList().apply {
                    set(childIdx, newUnderflowed)
                    set(childIdx + 1, newRight)
                }
                return RemoveResult.Updated(InternalNode(newKeys, newChildren, computeAggregate(newChildren, aggregator)), sizeDelta)
            }
        }
        if (childIdx > 0) {
            val leftSibling = children[childIdx - 1]
            val merged = merge(leftSibling, underflowed, keys[childIdx - 1], aggregator)
            val newKeys = keys.toMutableList().apply { removeAt(childIdx - 1) }
            val newChildren = children.toMutableList().apply {
                set(childIdx - 1, merged)
                removeAt(childIdx)
            }
            val minSize = (order + 1) / 2
            val newInternal = InternalNode(newKeys, newChildren, computeAggregate(newChildren, aggregator))
            return if (newChildren.size >= minSize) {
                RemoveResult.Updated(newInternal, sizeDelta)
            } else {
                RemoveResult.Underflow(newInternal, sizeDelta)
            }
        } else {
            val rightSibling = children[childIdx + 1]
            val merged = merge(underflowed, rightSibling, keys[childIdx], aggregator)
            val newKeys = keys.toMutableList().apply { removeAt(childIdx) }
            val newChildren = children.toMutableList().apply {
                set(childIdx, merged)
                removeAt(childIdx + 1)
            }
            val minSize = (order + 1) / 2
            val newInternal = InternalNode(newKeys, newChildren, computeAggregate(newChildren, aggregator))
            return if (newChildren.size >= minSize) {
                RemoveResult.Updated(newInternal, sizeDelta)
            } else {
                RemoveResult.Underflow(newInternal, sizeDelta)
            }
        }
    }

    private fun canBorrow(node: Node<K, V, A>, order: Int): Boolean {
        return if (node is LeafNode) {
            node.keys.size > (order + 1) / 2 - 1
        } else if (node is InternalNode) {
            node.children.size > (order + 1) / 2
        } else false
    }

    private fun borrowRight(left: Node<K, V, A>, right: Node<K, V, A>, routingKey: K, aggregator: BPlusTreeAggregator<V, A>?): Triple<Node<K, V, A>, Node<K, V, A>, K> {
        if (left is LeafNode && right is LeafNode) {
            val borrowedKey = left.keys.last()
            val borrowedValue = left.values.last()
            val newLeftKeys = left.keys.dropLast(1)
            val newLeftValues = left.values.dropLast(1)
            val newRightKeys = listOf(borrowedKey) + right.keys
            val newRightValues = listOf(borrowedValue) + right.values
            return Triple(
                LeafNode(newLeftKeys, newLeftValues, LeafNode.computeAggregate(newLeftValues, aggregator)), 
                LeafNode(newRightKeys, newRightValues, LeafNode.computeAggregate(newRightValues, aggregator)), 
                newRightKeys.first()
            )
        } else if (left is InternalNode && right is InternalNode) {
            val newLeftKeys = left.keys.dropLast(1)
            val newLeftChildren = left.children.dropLast(1)
            val movedKeyUp = left.keys.last()
            val movedChild = left.children.last()
            val newRightKeys = listOf(routingKey) + right.keys
            val newRightChildren = listOf(movedChild) + right.children
            return Triple(
                InternalNode(newLeftKeys, newLeftChildren, computeAggregate(newLeftChildren, aggregator)), 
                InternalNode(newRightKeys, newRightChildren, computeAggregate(newRightChildren, aggregator)), 
                movedKeyUp
            )
        }
        error("Mismatched node types")
    }

    private fun borrowLeft(left: Node<K, V, A>, right: Node<K, V, A>, routingKey: K, aggregator: BPlusTreeAggregator<V, A>?): Triple<Node<K, V, A>, Node<K, V, A>, K> {
        if (left is LeafNode && right is LeafNode) {
            val borrowedKey = right.keys.first()
            val borrowedValue = right.values.first()
            val newRightKeys = right.keys.drop(1)
            val newRightValues = right.values.drop(1)
            val newLeftKeys = left.keys + borrowedKey
            val newLeftValues = left.values + borrowedValue
            return Triple(
                LeafNode(newLeftKeys, newLeftValues, LeafNode.computeAggregate(newLeftValues, aggregator)), 
                LeafNode(newRightKeys, newRightValues, LeafNode.computeAggregate(newRightValues, aggregator)), 
                newRightKeys.first()
            )
        } else if (left is InternalNode && right is InternalNode) {
            val movedKeyUp = right.keys.first()
            val movedChild = right.children.first()
            val newRightKeys = right.keys.drop(1)
            val newRightChildren = right.children.drop(1)
            val newLeftKeys = left.keys + routingKey
            val newLeftChildren = left.children + movedChild
            return Triple(
                InternalNode(newLeftKeys, newLeftChildren, computeAggregate(newLeftChildren, aggregator)), 
                InternalNode(newRightKeys, newRightChildren, computeAggregate(newRightChildren, aggregator)), 
                movedKeyUp
            )
        }
        error("Mismatched node types")
    }

    private fun merge(left: Node<K, V, A>, right: Node<K, V, A>, routingKey: K, aggregator: BPlusTreeAggregator<V, A>?): Node<K, V, A> {
        if (left is LeafNode && right is LeafNode) {
            val mergedValues = left.values + right.values
            return LeafNode(left.keys + right.keys, mergedValues, LeafNode.computeAggregate(mergedValues, aggregator))
        } else if (left is InternalNode && right is InternalNode) {
            val mergedChildren = left.children + right.children
            return InternalNode(left.keys + listOf(routingKey) + right.keys, mergedChildren, computeAggregate(mergedChildren, aggregator))
        }
        error("Mismatched node types")
    }

    override fun prefixSum(key: K, aggregator: BPlusTreeAggregator<V, A>): A {
        var acc = aggregator.empty
        val idx = keys.binarySearch(key)
        val childIdx = if (idx >= 0) idx + 1 else -idx - 1
        for (i in 0 until childIdx) {
            acc = aggregator.combine(acc, children[i].aggregate!!)
        }
        if (childIdx < children.size) {
            acc = aggregator.combine(acc, children[childIdx].prefixSum(key, aggregator))
        }
        return acc
    }

    override fun findByPrefix(target: A, aggregator: BPlusTreeAggregator<V, A>, currentAccum: A): Pair<Pair<K, V>, A>? {
        var acc = currentAccum
        for (i in children.indices) {
            val child = children[i]
            val nextAcc = aggregator.combine(acc, child.aggregate!!)
            if (nextAcc > target) {
                return child.findByPrefix(target, aggregator, acc)
            }
            acc = nextAcc
        }
        return null
    }
}

private class BPlusTreeIterator<K : Comparable<K>, V, A : Comparable<A>>(root: Node<K, V, A>, startKey: K? = null) : Iterator<Map.Entry<K, V>> {
    private val stack = mutableListOf<Pair<Node<K, V, A>, Int>>()

    init {
        if (startKey == null) {
            pushLeftmost(root)
        } else {
            pushToKey(root, startKey)
        }
    }

    private fun pushToKey(node: Node<K, V, A>, key: K) {
        var curr = node
        while (curr is InternalNode) {
            val idx = curr.keys.binarySearch(key)
            val childIdx = if (idx >= 0) idx + 1 else -idx - 1
            stack.add(curr to childIdx)
            curr = curr.children[childIdx]
        }
        if (curr is LeafNode) {
            val idx = curr.keys.binarySearch(key)
            val leafIdx = if (idx >= 0) idx else -idx - 1
            if (leafIdx < curr.keys.size) {
                stack.add(curr to leafIdx)
            } else {
                stack.add(curr to leafIdx)
                advanceParent()
            }
        }
    }

    private fun pushLeftmost(node: Node<K, V, A>) {
        var curr = node
        while (curr is InternalNode) {
            if (curr.children.isEmpty()) return
            stack.add(curr to 0)
            curr = curr.children[0]
        }
        if (curr is LeafNode && curr.keys.isNotEmpty()) {
            stack.add(curr to 0)
        }
    }

    override fun hasNext(): Boolean {
        while (stack.isNotEmpty()) {
            val last = stack.last()
            val node = last.first
            val i = last.second
            if (node is LeafNode) {
                if (i < node.keys.size) return true
                else {
                    stack.removeLast()
                    advanceParent()
                }
            } else {
                stack.removeLast()
            }
        }
        return false
    }

    private fun advanceParent() {
        while (stack.isNotEmpty()) {
            val last = stack.removeLast()
            val node = last.first
            val i = last.second
            if (node is InternalNode) {
                val nextI = i + 1
                if (nextI < node.children.size) {
                    stack.add(node to nextI)
                    pushLeftmost(node.children[nextI])
                    return
                }
            }
        }
    }

    override fun next(): Map.Entry<K, V> {
        if (!hasNext()) throw NoSuchElementException()
        val last = stack.removeLast()
        val leaf = last.first as LeafNode
        val i = last.second
        val entry = EntryImpl(leaf.keys[i], leaf.values[i])
        stack.add(leaf to i + 1)
        return entry
    }
}

private data class EntryImpl<K, V>(override val key: K, override val value: V) : Map.Entry<K, V>
