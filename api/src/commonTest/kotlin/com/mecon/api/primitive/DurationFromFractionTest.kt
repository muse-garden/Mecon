package com.mecon.api.primitive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DurationFromFractionTest {

    @Test
    fun plainAndDottedValuesMapToOneSymbol() {
        assertEquals(Duration.WHOLE, Duration.fromFraction(Fraction.ONE))
        assertEquals(Duration.QUARTER, Duration.fromFraction(Fraction(2, 8)))
        assertEquals(Duration.DOTTED_QUARTER, Duration.fromFraction(Fraction(3, 8)))
        assertEquals(Duration.DOTTED_HALF, Duration.fromFraction(Fraction(3, 4)))
        assertEquals(Duration.DOTTED_EIGHTH, Duration.fromFraction(Fraction(3, 16)))
        assertEquals(
            Duration(DurationBase.QUARTER, dots = 2),
            Duration.fromFraction(Fraction(7, 16)),
        )
    }

    @Test
    fun tripletValuesKeepTheirTuplet() {
        assertEquals(Duration.triplet(DurationBase.QUARTER), Duration.fromFraction(Fraction(1, 6)))
        assertEquals(Duration.triplet(DurationBase.EIGHTH), Duration.fromFraction(Fraction(1, 12)))
    }

    @Test
    fun tiedAndNonPositiveValuesHaveNoSingleSymbol() {
        assertNull(Duration.fromFraction(Fraction(5, 8)))
        assertNull(Duration.fromFraction(Fraction(9, 16)))
        assertNull(Duration.fromFraction(Fraction.ZERO))
        assertNull(Duration.fromFraction(-Fraction.QUARTER))
    }

    @Test
    fun atMostFallsBackToTheLongestFittingSymbol() {
        assertEquals(Duration.HALF, Duration.atMost(Fraction(5, 8)))
        assertEquals(Duration.DOTTED_QUARTER, Duration.atMost(Fraction(3, 8)))
        assertEquals(
            Duration(DurationBase.ONE_TWENTY_EIGHTH),
            Duration.atMost(Fraction(1, 1024)),
        )
    }
}
