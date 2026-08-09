package com.mecon.api.collection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BPlusTreeTest {

    // Simple Int aggregator to mock type checking for non-aggregated tests
    private val dummyAgg = object : BPlusTreeAggregator<String, Int> {
        override fun extract(value: String): Int = 0
        override fun combine(a1: Int, a2: Int): Int = 0
        override val empty: Int = 0
    }

    @Test
    fun testEmptyTree() {
        val tree = BPlusTree<Int, String, Int>(order = 3, dummyAgg)
        assertEquals(0, tree.size)
        assertNull(tree.get(1))
        assertEquals(emptyList(), tree.toList())
    }

    @Test
    fun testSingleInsert() {
        var tree = BPlusTree<Int, String, Int>(order = 3, dummyAgg)
        tree = tree.put(1, "one")
        assertEquals(1, tree.size)
        assertEquals("one", tree.get(1))
        assertEquals(listOf(1 to "one"), tree.map { it.key to it.value })
    }

    @Test
    fun testUpdateExisting() {
        var tree = BPlusTree<Int, String, Int>(order = 3, dummyAgg)
        tree = tree.put(1, "one")
        val tree2 = tree.put(1, "ONE")
        
        assertEquals(1, tree.size)
        assertEquals("one", tree.get(1))
        
        assertEquals(1, tree2.size)
        assertEquals("ONE", tree2.get(1))
    }

    @Test
    fun testMultipleInsertsNoSplit() {
        var tree = BPlusTree<Int, String, Int>(order = 4, dummyAgg)
        tree = tree.put(10, "ten")
        tree = tree.put(20, "twenty")
        tree = tree.put(30, "thirty")
        
        assertEquals(3, tree.size)
        assertEquals("ten", tree.get(10))
        assertEquals("twenty", tree.get(20))
        assertEquals("thirty", tree.get(30))
        
        val expected = listOf(10 to "ten", 20 to "twenty", 30 to "thirty")
        assertEquals(expected, tree.map { it.key to it.value })
    }

    @Test
    fun testInsertWithSplit() {
        var tree = BPlusTree<Int, String, Int>(order = 3, dummyAgg)
        tree = tree.put(10, "10")
        tree = tree.put(20, "20")
        tree = tree.put(30, "30")
        
        assertEquals(3, tree.size)
        assertEquals("10", tree.get(10))
        assertEquals("20", tree.get(20))
        assertEquals("30", tree.get(30))
        
        val expected = listOf(10 to "10", 20 to "20", 30 to "30")
        assertEquals(expected, tree.map { it.key to it.value })
    }

    @Test
    fun testInsertReverseOrder() {
        var tree = BPlusTree<Int, String, Int>(order = 3, dummyAgg)
        for (i in 100 downTo 1) {
            tree = tree.put(i, i.toString())
        }
        
        assertEquals(100, tree.size)
        for (i in 1..100) {
            assertEquals(i.toString(), tree.get(i))
        }
        
        val expected = (1..100).map { it to it.toString() }
        assertEquals(expected, tree.map { it.key to it.value })
    }

    @Test
    fun testInsertRandomOrder() {
        var tree = BPlusTree<Int, String, Int>(order = 5, dummyAgg)
        val nums = (1..1000).shuffled()
        
        for (n in nums) {
            tree = tree.put(n, n.toString())
        }
        
        assertEquals(1000, tree.size)
        for (i in 1..1000) {
            assertEquals(i.toString(), tree.get(i))
        }
        
        val expected = (1..1000).map { it to it.toString() }
        assertEquals(expected, tree.map { it.key to it.value })
    }

    @Test
    fun testImmutability() {
        val tree1 = BPlusTree<Int, String, Int>(order = 3, dummyAgg)
        val tree2 = tree1.put(1, "one")
        val tree3 = tree2.put(2, "two")
        val tree4 = tree3.put(3, "three")
        
        assertEquals(0, tree1.size)
        assertNull(tree1.get(1))
        
        assertEquals(1, tree2.size)
        assertEquals("one", tree2.get(1))
        assertNull(tree2.get(2))
        
        assertEquals(2, tree3.size)
        assertEquals("one", tree3.get(1))
        assertEquals("two", tree3.get(2))
        assertNull(tree3.get(3))
        
        assertEquals(3, tree4.size)
    }

    @Test
    fun testRemoveLeafNoUnderflow() {
        var tree = BPlusTree<Int, String, Int>(order = 4, dummyAgg)
        tree = tree.put(1, "1").put(2, "2").put(3, "3")
        
        val oldTree = tree
        tree = tree.remove(2)
        
        assertEquals(2, tree.size)
        assertEquals("1", tree.get(1))
        assertNull(tree.get(2))
        assertEquals("3", tree.get(3))
        
        assertEquals("2", oldTree.get(2))
    }

    @Test
    fun testRemoveWithBorrowLeft() {
        var tree = BPlusTree<Int, String, Int>(order = 3, dummyAgg)
        tree = tree.put(10, "10").put(20, "20").put(30, "30").put(40, "40")
        tree = tree.remove(40)
        
        assertEquals(3, tree.size)
        assertEquals("30", tree.get(30))
        assertNull(tree.get(40))
        assertEquals(listOf(10 to "10", 20 to "20", 30 to "30"), tree.map { it.key to it.value })
    }
    
    @Test
    fun testRemoveWithMerge() {
        var tree = BPlusTree<Int, String, Int>(order = 4, dummyAgg)
        for (i in 1..10) {
            tree = tree.put(i * 10, (i * 10).toString())
        }
        
        for (i in 1..10) {
            tree = tree.remove(i * 10)
            assertEquals(10 - i, tree.size)
            assertNull(tree.get(i * 10))
            
            val expected = ((i + 1)..10).map { it * 10 to (it * 10).toString() }
            assertEquals(expected, tree.map { it.key to it.value })
        }
    }

    @Test
    fun testRemoveRandom() {
        var tree = BPlusTree<Int, String, Int>(order = 5, dummyAgg)
        val nums = (1..1000).toList()
        
        for (n in nums) {
            tree = tree.put(n, n.toString())
        }
        
        val shuffleToRemove = nums.shuffled()
        var currentSize = 1000
        
        for (n in shuffleToRemove) {
            tree = tree.remove(n)
            currentSize--
            assertEquals(currentSize, tree.size)
            assertNull(tree.get(n))
        }
        
        assertEquals(0, tree.size)
        assertEquals(emptyList(), tree.toList())
    }
    
    @Test
    fun testNonExistentRemove() {
        var tree = BPlusTree<Int, String, Int>(order = 3, dummyAgg)
        tree = tree.put(1, "1")
        val tree2 = tree.remove(2)
        assert(tree === tree2)
    }

    @Test
    fun testAggregator() {
        val sumAgg = object : BPlusTreeAggregator<Int, Int> {
            override fun extract(value: Int): Int = value
            override fun combine(a1: Int, a2: Int): Int = a1 + a2
            override val empty: Int = 0
        }
        
        var tree = BPlusTree<Int, Int, Int>(order = 3, sumAgg)
        
        tree = tree.put(10, 5)
        tree = tree.put(20, 10)
        tree = tree.put(30, 2)
        tree = tree.put(40, 8)
        
        assertEquals(25, tree.aggregate)
        
        assertEquals(0, tree.prefixSum(10))
        assertEquals(5, tree.prefixSum(20))
        assertEquals(15, tree.prefixSum(30))
        assertEquals(17, tree.prefixSum(40))
        assertEquals(25, tree.prefixSum(50))
        
        // Find element that crosses the accumulation target
        val r1 = tree.findByPrefix(3)
        assertEquals(10, r1?.first?.key)
        assertEquals(0, r1?.second)

        val r2 = tree.findByPrefix(4)
        assertEquals(10, r2?.first?.key)
        assertEquals(0, r2?.second)

        // Target exactly at or inside the second element
        val r3 = tree.findByPrefix(5)
        assertEquals(20, r3?.first?.key)
        assertEquals(5, r3?.second)
        
        // Target passing second element
        val r4 = tree.findByPrefix(14)
        assertEquals(20, r4?.first?.key)
        assertEquals(5, r4?.second)

        // Target reaching fourth element
        val r5 = tree.findByPrefix(17)
        assertEquals(40, r5?.first?.key)
        assertEquals(17, r5?.second)

        val r6 = tree.findByPrefix(25)
        assertNull(r6)
    }

    @Test
    fun aggregateRangeUsesInclusiveBounds() {
        val sumAgg = object : BPlusTreeAggregator<Int, Int> {
            override fun extract(value: Int): Int = value
            override fun combine(a1: Int, a2: Int): Int = a1 + a2
            override val empty: Int = 0
        }
        var tree = BPlusTree<Int, Int, Int>(order = 4, aggregator = sumAgg)
        for (i in 1..40) tree = tree.put(i, i)

        assertEquals((10..30).sum(), tree.aggregateRange(10, 30))
        assertEquals(17, tree.aggregateRange(17, 17))
        assertEquals(0, tree.aggregateRange(41, 50))
    }

    @Test
    fun aggregateRangeReflectsPersistentUpdates() {
        val sumAgg = object : BPlusTreeAggregator<Int, Int> {
            override fun extract(value: Int): Int = value
            override fun combine(a1: Int, a2: Int): Int = a1 + a2
            override val empty: Int = 0
        }
        var original = BPlusTree<Int, Int, Int>(order = 4, aggregator = sumAgg)
        for (i in 1..20) original = original.put(i, i)
        val edited = original.put(10, 100).remove(11)

        assertEquals((8..12).sum(), original.aggregateRange(8, 12))
        assertEquals(8 + 9 + 100 + 12, edited.aggregateRange(8, 12))
    }

    @Test
    fun aggregateRangeMatchesBruteForceAcrossTreeShapes() {
        val sumAgg = object : BPlusTreeAggregator<Int, Int> {
            override fun extract(value: Int): Int = value
            override fun combine(a1: Int, a2: Int): Int = a1 + a2
            override val empty: Int = 0
        }
        for (order in 3..8) {
            var tree = BPlusTree<Int, Int, Int>(order = order, aggregator = sumAgg)
            val values = (1..75).associateWith { (it * 17) % 23 }
            for ((key, value) in values) tree = tree.put(key, value)
            for (start in -5..80 step 5) {
                for (end in start..85 step 7) {
                    val expected = values.filterKeys { it in start..end }.values.sum()
                    assertEquals(expected, tree.aggregateRange(start, end), "order=$order range=$start..$end")
                }
            }
        }
    }
}
