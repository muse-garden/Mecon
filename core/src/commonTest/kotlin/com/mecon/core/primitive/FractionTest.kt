package com.mecon.api.primitive

import kotlin.test.*

class FractionTest {

    @Test
    fun testCreation() {
        val f = Fraction(1, 2)
        assertEquals(1, f.numerator)
        assertEquals(2, f.denominator)
    }

    @Test
    fun testDenominatorMustBePositive() {
        assertFailsWith<IllegalArgumentException> {
            Fraction(1, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            Fraction(1, -1)
        }
    }

    @Test
    fun testAddition() {
        assertEquals(Fraction(1, 1), Fraction(1, 2) + Fraction(1, 2))
        assertEquals(Fraction(3, 4), Fraction(1, 2) + Fraction(1, 4))
        assertEquals(Fraction(7, 12), Fraction(1, 3) + Fraction(1, 4))
    }

    @Test
    fun testSubtraction() {
        assertEquals(Fraction(0, 1), Fraction(1, 2) - Fraction(1, 2))
        assertEquals(Fraction(1, 4), Fraction(1, 2) - Fraction(1, 4))
        assertEquals(Fraction(-1, 4), Fraction(1, 4) - Fraction(1, 2))
    }

    @Test
    fun testMultiplication() {
        assertEquals(Fraction(1, 4), Fraction(1, 2) * Fraction(1, 2))
        assertEquals(Fraction(3, 8), Fraction(3, 4) * Fraction(1, 2))
    }

    @Test
    fun testDivision() {
        assertEquals(Fraction(1, 1), Fraction(1, 2) / Fraction(1, 2))
        assertEquals(Fraction(2, 1), Fraction(1, 2) / Fraction(1, 4))
    }

    @Test
    fun testComparison() {
        assertTrue(Fraction(1, 2) > Fraction(1, 4))
        assertTrue(Fraction(1, 4) < Fraction(1, 2))
        // Structural equality requires simplification
        assertEquals(Fraction(1, 2), Fraction(2, 4).simplified())
        // Value comparison: 1/2 == 2/4 (compareTo returns 0)
        assertEquals(0, Fraction(1, 2).compareTo(Fraction(2, 4)))
        assertTrue(Fraction(1, 3) < Fraction(1, 2))
    }

    @Test
    fun testSimplification() {
        assertEquals(Fraction(1, 2), Fraction(2, 4).simplified())
        assertEquals(Fraction(1, 3), Fraction(3, 9).simplified())
        assertEquals(Fraction(0, 1), Fraction(0, 5).simplified())
    }

    @Test
    fun testToDouble() {
        assertEquals(0.5, Fraction(1, 2).toDouble())
        assertEquals(0.25, Fraction(1, 4).toDouble())
        assertEquals(0.333, Fraction(1, 3).toDouble(), 0.001)
    }

    @Test
    fun testNegation() {
        assertEquals(Fraction(-1, 2), -Fraction(1, 2))
        assertEquals(Fraction(1, 2), -Fraction(-1, 2))
    }

    @Test
    fun testCompanionConstants() {
        assertEquals(0, Fraction.ZERO.numerator)
        assertEquals(1, Fraction.ONE.numerator)
        assertEquals(1, Fraction.HALF.numerator)
        assertEquals(2, Fraction.HALF.denominator)
    }
}
