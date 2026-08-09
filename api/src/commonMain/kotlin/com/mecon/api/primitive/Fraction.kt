package com.mecon.api.primitive

import kotlinx.serialization.Serializable

/**
 * Immutable fraction type for precise beat position representation.
 *
 * Used for representing time positions, durations, and other musical values
 * that require exact fractional representation.
 */
@Serializable
data class Fraction(
    val numerator: Int,
    val denominator: Int
) : Comparable<Fraction> {

    init {
        require(denominator > 0) { "Denominator must be positive, got $denominator" }
    }

    operator fun plus(other: Fraction): Fraction {
        val commonDenom = lcm(denominator, other.denominator)
        return Fraction(
            numerator * (commonDenom / denominator) + other.numerator * (commonDenom / other.denominator),
            commonDenom
        ).simplified()
    }

    operator fun minus(other: Fraction): Fraction {
        val commonDenom = lcm(denominator, other.denominator)
        return Fraction(
            numerator * (commonDenom / denominator) - other.numerator * (commonDenom / other.denominator),
            commonDenom
        ).simplified()
    }

    operator fun times(other: Fraction): Fraction =
        Fraction(numerator * other.numerator, denominator * other.denominator).simplified()

    operator fun times(scalar: Int): Fraction =
        Fraction(numerator * scalar, denominator).simplified()

    operator fun div(other: Fraction): Fraction {
        require(other.numerator != 0) { "Division by zero" }
        return Fraction(numerator * other.denominator, denominator * other.numerator).simplified()
    }

    operator fun div(scalar: Int): Fraction {
        require(scalar != 0) { "Division by zero" }
        return Fraction(numerator, denominator * scalar).simplified()
    }

    operator fun unaryMinus(): Fraction = Fraction(-numerator, denominator)

    override fun compareTo(other: Fraction): Int {
        val commonDenom = lcm(denominator, other.denominator)
        val a = numerator * (commonDenom / denominator)
        val b = other.numerator * (commonDenom / other.denominator)
        return a.compareTo(b)
    }

    /**
     * Returns a simplified version of this fraction.
     */
    fun simplified(): Fraction {
        if (numerator == 0) return Fraction(0, 1)
        val g = gcd(numerator, denominator)
        val newNumerator = numerator / g
        val newDenominator = denominator / g
        // Ensure denominator is positive
        return if (newDenominator < 0) {
            Fraction(-newNumerator, -newDenominator)
        } else {
            Fraction(newNumerator, newDenominator)
        }
    }

    fun toDouble(): Double = numerator.toDouble() / denominator.toDouble()

    fun toFloat(): Float = numerator.toFloat() / denominator.toFloat()

    val isZero: Boolean get() = numerator == 0

    val isPositive: Boolean get() = numerator > 0

    val isNegative: Boolean get() = numerator < 0

    override fun toString(): String = "$numerator/$denominator"

    companion object {
        val ZERO = Fraction(0, 1)
        val ONE = Fraction(1, 1)
        val HALF = Fraction(1, 2)
        val QUARTER = Fraction(1, 4)
        val EIGHTH = Fraction(1, 8)
        val SIXTEENTH = Fraction(1, 16)

        // Special value representing positive infinity (used for barlines)
        val POSITIVE_INFINITY = Fraction(Int.MAX_VALUE, 1)

        fun of(numerator: Int, denominator: Int): Fraction = Fraction(numerator, denominator)

        fun fromInt(value: Int): Fraction = Fraction(value, 1)
    }
}
