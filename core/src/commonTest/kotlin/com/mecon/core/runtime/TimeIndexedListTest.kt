package com.mecon.api.runtime

import com.mecon.api.primitive.*
import kotlin.test.*

class TimeIndexedListTest {

    // Test data class
    data class TestEvent(
        override val onset: TimeCode,
        val value: String
    ) : HasOnset

    @Test
    fun testEmpty() {
        val list = TimeIndexedList.empty<TestEvent>()
        assertTrue(list.isEmpty())
        assertEquals(0, list.size)
    }

    @Test
    fun testInsert() {
        var list = TimeIndexedList.empty<TestEvent>()

        val event1 = TestEvent(TimeCode.of(1, Fraction(0, 4)), "first")
        val event2 = TestEvent(TimeCode.of(1, Fraction(1, 4)), "second")
        val event3 = TestEvent(TimeCode.of(1, Fraction(2, 4)), "third")

        // Insert in arbitrary order
        list = list.insert(event2)
        list = list.insert(event1)
        list = list.insert(event3)

        assertEquals(3, list.size)

        // Should be sorted by onset
        val items = list.toList()
        assertEquals("first", items[0].value)
        assertEquals("second", items[1].value)
        assertEquals("third", items[2].value)
    }

    @Test
    fun testRange() {
        val events = listOf(
            TestEvent(TimeCode.of(1, Fraction(0, 4)), "m1-b0"),
            TestEvent(TimeCode.of(1, Fraction(1, 4)), "m1-b1"),
            TestEvent(TimeCode.of(1, Fraction(2, 4)), "m1-b2"),
            TestEvent(TimeCode.of(1, Fraction(3, 4)), "m1-b3"),
            TestEvent(TimeCode.of(2, Fraction(0, 4)), "m2-b0"),
            TestEvent(TimeCode.of(2, Fraction(1, 4)), "m2-b1")
        )
        val list = TimeIndexedList.of(events)

        // Get middle range
        val range = list.range(
            TimeCode.of(1, Fraction(1, 4)),
            TimeCode.of(1, Fraction(3, 4))
        )
        assertEquals(3, range.size)
        assertEquals("m1-b1", range[0].value)
        assertEquals("m1-b2", range[1].value)
        assertEquals("m1-b3", range[2].value)
    }

    @Test
    fun testAt() {
        val events = listOf(
            TestEvent(TimeCode.of(1, Fraction(0, 4)), "a"),
            TestEvent(TimeCode.of(1, Fraction(0, 4)), "b"),  // Same time
            TestEvent(TimeCode.of(1, Fraction(1, 4)), "c")
        )
        val list = TimeIndexedList.of(events)

        val atBeat0 = list.at(TimeCode.of(1, Fraction(0, 4)))
        assertEquals(2, atBeat0.size)
        assertTrue(atBeat0.any { it.value == "a" })
        assertTrue(atBeat0.any { it.value == "b" })
    }

    @Test
    fun testLastBefore() {
        val events = listOf(
            TestEvent(TimeCode.of(1, Fraction(0, 4)), "first"),
            TestEvent(TimeCode.of(1, Fraction(2, 4)), "second"),
            TestEvent(TimeCode.of(2, Fraction(0, 4)), "third")
        )
        val list = TimeIndexedList.of(events)

        val last = list.lastBefore(TimeCode.of(2, Fraction(0, 4)))
        assertNotNull(last)
        assertEquals("second", last.value)

        val firstLast = list.lastBefore(TimeCode.of(1, Fraction(0, 4)))
        assertNull(firstLast)  // Nothing before the first
    }

    @Test
    fun testFirstAtOrAfter() {
        val events = listOf(
            TestEvent(TimeCode.of(1, Fraction(0, 4)), "first"),
            TestEvent(TimeCode.of(1, Fraction(2, 4)), "second"),
            TestEvent(TimeCode.of(2, Fraction(0, 4)), "third")
        )
        val list = TimeIndexedList.of(events)

        val first = list.firstAtOrAfter(TimeCode.of(1, Fraction(1, 4)))
        assertNotNull(first)
        assertEquals("second", first.value)

        val exact = list.firstAtOrAfter(TimeCode.of(1, Fraction(2, 4)))
        assertNotNull(exact)
        assertEquals("second", exact.value)
    }

    @Test
    fun testRemoveWhere() {
        val events = listOf(
            TestEvent(TimeCode.of(1, Fraction(0, 4)), "keep"),
            TestEvent(TimeCode.of(1, Fraction(1, 4)), "remove"),
            TestEvent(TimeCode.of(1, Fraction(2, 4)), "keep")
        )
        val list = TimeIndexedList.of(events)

        val filtered = list.removeWhere { it.value == "remove" }

        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.value == "keep" })
    }

    @Test
    fun testFilter() {
        val events = listOf(
            TestEvent(TimeCode.of(1, Fraction(0, 4)), "a"),
            TestEvent(TimeCode.of(1, Fraction(1, 4)), "ab"),
            TestEvent(TimeCode.of(1, Fraction(2, 4)), "abc")
        )
        val list = TimeIndexedList.of(events)

        val filtered = list.filter { it.value.length > 1 }

        assertEquals(2, filtered.size)
        assertEquals("ab", filtered.toList()[0].value)
        assertEquals("abc", filtered.toList()[1].value)
    }

    @Test
    fun testBefore() {
        val events = listOf(
            TestEvent(TimeCode.of(1, Fraction(0, 4)), "first"),
            TestEvent(TimeCode.of(1, Fraction(1, 4)), "second"),
            TestEvent(TimeCode.of(1, Fraction(2, 4)), "third")
        )
        val list = TimeIndexedList.of(events)

        val before = list.before(TimeCode.of(1, Fraction(2, 4)))
        assertEquals(2, before.size)
        assertEquals("first", before[0].value)
        assertEquals("second", before[1].value)
    }

    @Test
    fun testAtOrAfter() {
        val events = listOf(
            TestEvent(TimeCode.of(1, Fraction(0, 4)), "first"),
            TestEvent(TimeCode.of(1, Fraction(1, 4)), "second"),
            TestEvent(TimeCode.of(1, Fraction(2, 4)), "third")
        )
        val list = TimeIndexedList.of(events)

        val atOrAfter = list.atOrAfter(TimeCode.of(1, Fraction(1, 4)))
        assertEquals(2, atOrAfter.size)
        assertEquals("second", atOrAfter[0].value)
        assertEquals("third", atOrAfter[1].value)
    }
}
