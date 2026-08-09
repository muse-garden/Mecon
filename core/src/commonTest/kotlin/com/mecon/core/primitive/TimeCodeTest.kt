package com.mecon.api.primitive

import kotlin.test.*

class TimeCodeTest {

    @Test
    fun testCreation() {
        val tc = TimeCode.ofMeasure(1)
        assertEquals(1, tc.measure)
        assertNull(tc.beat)
    }

    @Test
    fun testWithBeat() {
        val tc = TimeCode.of(2, Fraction(1, 4))
        assertEquals(2, tc.measure)
        assertEquals(Fraction(1, 4), tc.beat)
    }

    @Test
    fun testComparison() {
        val tc1 = TimeCode.ofMeasure(1)
        val tc2 = TimeCode.ofMeasure(2)
        assertTrue(tc1 < tc2)

        val tc3 = TimeCode.of(1, Fraction(1, 4))
        val tc4 = TimeCode.of(1, Fraction(1, 2))
        assertTrue(tc3 < tc4)

        val tc5 = TimeCode.of(1, Fraction(0, 1))
        assertTrue(tc5 < tc3)
    }

    @Test
    fun testShorterListComparison() {
        // Shorter list padded with zeros
        val tc1 = TimeCode.ofMeasure(1)  // [1/1]
        val tc2 = TimeCode.of(1, Fraction(1, 4))  // [1/1, 1/4]

        // 1/1 == 1/1, then 0 < 1/4
        assertTrue(tc1 < tc2)
    }

    @Test
    fun testPlus() {
        val tc = TimeCode.of(1, Fraction(1, 4))
        val result = tc + Fraction(1, 4)
        assertEquals(Fraction(1, 2), result.beat)
    }

    @Test
    fun testSameMeasure() {
        val tc1 = TimeCode.of(1, Fraction(1, 4))
        val tc2 = TimeCode.of(1, Fraction(3, 4))
        val tc3 = TimeCode.of(2, Fraction(1, 4))

        assertTrue(tc1.sameMeasure(tc2))
        assertFalse(tc1.sameMeasure(tc3))
    }

    @Test
    fun testFormat() {
        val tc = TimeCode.of(1, Fraction(1, 4))
        assertEquals("1/1:1/4", tc.format())
    }
}

class TimeRangeTest {

    @Test
    fun testContains() {
        val start = TimeCode.of(1, Fraction(0, 1))
        val end = TimeCode.of(1, Fraction(1, 1))
        val range = TimeRange(start, end)

        assertTrue(TimeCode.of(1, Fraction(1, 4)) in range)
        assertTrue(start in range)
        assertTrue(end in range)
        assertFalse(TimeCode.of(2, Fraction(0, 1)) in range)
    }

    @Test
    fun testOverlaps() {
        val range1 = TimeRange(
            TimeCode.of(1, Fraction(0, 1)),
            TimeCode.of(1, Fraction(1, 2))
        )
        val range2 = TimeRange(
            TimeCode.of(1, Fraction(1, 4)),
            TimeCode.of(1, Fraction(3, 4))
        )
        val range3 = TimeRange(
            TimeCode.of(2, Fraction(0, 1)),
            TimeCode.of(2, Fraction(1, 1))
        )

        assertTrue(range1.overlaps(range2))
        assertFalse(range1.overlaps(range3))
    }
}
