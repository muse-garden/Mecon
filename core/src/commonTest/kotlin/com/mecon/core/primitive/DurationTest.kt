package com.mecon.api.primitive

import kotlin.test.*

class DurationTest {

    @Test
    fun testBaseDurations() {
        assertEquals(4096, DurationBase.WHOLE.ticks)
        assertEquals(2048, DurationBase.HALF.ticks)
        assertEquals(1024, DurationBase.QUARTER.ticks)
        assertEquals(512, DurationBase.EIGHTH.ticks)
        assertEquals(256, DurationBase.SIXTEENTH.ticks)
    }

    @Test
    fun testDurationToTicks() {
        assertEquals(1024, Duration.QUARTER.toTicks())
        assertEquals(512, Duration.EIGHTH.toTicks())
    }

    @Test
    fun testDottedDuration() {
        // Dotted quarter = quarter + eighth = 1024 + 512 = 1536
        val dottedQuarter = Duration(DurationBase.QUARTER, dots = 1)
        assertEquals(1536, dottedQuarter.toTicks())

        // Double dotted quarter = quarter + eighth + sixteenth = 1024 + 512 + 256 = 1792
        val doubleDottedQuarter = Duration(DurationBase.QUARTER, dots = 2)
        assertEquals(1792, doubleDottedQuarter.toTicks())
    }

    @Test
    fun testTuplet() {
        val tripletQuarter = Duration(DurationBase.QUARTER, tuplet = Tuplet.TRIPLET)
        // Triplet: 3 notes in time of 2, so each is 2/3 of normal
        // 1024 * 2/3 = 682.67 -> 682 (integer division)
        assertEquals(682, tripletQuarter.toTicks())
    }

    @Test
    fun testToFraction() {
        assertEquals(Fraction(1, 4), Duration.QUARTER.toFraction())
        assertEquals(Fraction(1, 8), Duration.EIGHTH.toFraction())
        assertEquals(Fraction(1, 2), Duration.HALF.toFraction())

        // Dotted quarter = 1/4 + 1/8 = 3/8
        assertEquals(Fraction(3, 8), Duration.DOTTED_QUARTER.toFraction())
    }

    @Test
    fun testInvalidDots() {
        assertFailsWith<IllegalArgumentException> {
            Duration(DurationBase.QUARTER, dots = 4)
        }
        assertFailsWith<IllegalArgumentException> {
            Duration(DurationBase.QUARTER, dots = -1)
        }
    }

    @Test
    fun testBaseToFraction() {
        assertEquals(Fraction(1, 1), DurationBase.WHOLE.toFraction())
        assertEquals(Fraction(1, 2), DurationBase.HALF.toFraction())
        assertEquals(Fraction(1, 4), DurationBase.QUARTER.toFraction())
        assertEquals(Fraction(2, 1), DurationBase.BREVE.toFraction())
    }
}

class TupletTest {

    @Test
    fun testTripletRatio() {
        assertEquals(Fraction(2, 3), Tuplet.TRIPLET.ratio)
    }

    @Test
    fun testDupletRatio() {
        assertEquals(Fraction(3, 2), Tuplet.DUPLET.ratio)
    }

    @Test
    fun testInvalidTuplet() {
        assertFailsWith<IllegalArgumentException> {
            Tuplet(0, 2)
        }
        assertFailsWith<IllegalArgumentException> {
            Tuplet(3, 0)
        }
    }
}
