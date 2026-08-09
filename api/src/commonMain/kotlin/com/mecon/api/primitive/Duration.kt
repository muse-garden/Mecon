package com.mecon.api.primitive

import kotlinx.serialization.Serializable

/**
 * Base note duration values.
 * Ticks use 4096 as the reference for a whole note.
 */
enum class DurationBase(val ticks: Int, val displayName: String) {
    MAXIMA(32768, "Maxima"),           // 8x whole note
    LONGA(16384, "Longa"),             // 4x whole note
    BREVE(8192, "Breve"),              // 2x whole note (double whole)
    WHOLE(4096, "Whole"),              // Whole note
    HALF(2048, "Half"),                // Half note
    QUARTER(1024, "Quarter"),          // Quarter note
    EIGHTH(512, "Eighth"),             // Eighth note
    SIXTEENTH(256, "16th"),            // 16th note
    THIRTY_SECOND(128, "32nd"),        // 32nd note
    SIXTY_FOURTH(64, "64th"),          // 64th note
    ONE_TWENTY_EIGHTH(32, "128th");    // 128th note

    /**
     * Convert to fractional beat value (assuming quarter note = 1 beat).
     */
    fun toFraction(): Fraction = when (this) {
        MAXIMA -> Fraction(8, 1)
        LONGA -> Fraction(4, 1)
        BREVE -> Fraction(2, 1)
        WHOLE -> Fraction(1, 1)
        HALF -> Fraction(1, 2)
        QUARTER -> Fraction(1, 4)
        EIGHTH -> Fraction(1, 8)
        SIXTEENTH -> Fraction(1, 16)
        THIRTY_SECOND -> Fraction(1, 32)
        SIXTY_FOURTH -> Fraction(1, 64)
        ONE_TWENTY_EIGHTH -> Fraction(1, 128)
    }

    companion object {
        /**
         * Get duration base from ticks.
         */
        fun fromTicks(ticks: Int): DurationBase? =
            entries.find { it.ticks == ticks }
    }
}

/**
 * Tuplet ratio (e.g., 3:2 for triplets).
 */
@Serializable
data class Tuplet(
    val actual: Int,   // Actual notes played (e.g., 3 for triplet)
    val normal: Int    // Normal duration notes (e.g., 2 for triplet)
) {
    init {
        require(actual > 0) { "Actual must be positive" }
        require(normal > 0) { "Normal must be positive" }
    }

    /** Ratio as fraction (normal/actual) */
    val ratio: Fraction get() = Fraction(normal, actual)

    override fun toString(): String = "$actual:$normal"

    companion object {
        val TRIPLET = Tuplet(3, 2)
        val DUPLET = Tuplet(2, 3)
        val QUINTUPLET = Tuplet(5, 4)
        val SEXTUPLET = Tuplet(6, 4)
    }
}

/**
 * Complete duration representation including base duration, dots, and tuplet.
 */
@Serializable
data class Duration(
    val base: DurationBase,
    val dots: Int = 0,
    val tuplet: Tuplet? = null
) {
    init {
        require(dots in 0..3) { "Dots must be 0-3, got $dots" }
    }

    /**
     * Calculate total ticks for this duration.
     */
    fun toTicks(): Int {
        var ticks = base.ticks

        // Apply dots: each dot adds half of the previous value
        var dotValue = ticks / 2
        repeat(dots) {
            ticks += dotValue
            dotValue /= 2
        }

        // Apply tuplet ratio
        tuplet?.let {
            ticks = ticks * it.normal / it.actual
        }

        return ticks
    }

    /**
     * Convert to fractional beat value.
     */
    fun toFraction(): Fraction {
        var fraction = base.toFraction()

        // Apply dots
        var dotValue = fraction / 2
        repeat(dots) {
            fraction = fraction + dotValue
            dotValue = dotValue / 2
        }

        // Apply tuplet
        tuplet?.let {
            fraction = fraction * it.ratio
        }

        return fraction.simplified()
    }

    override fun toString(): String = buildString {
        append(base.displayName)
        if (dots > 0) append(" ${"•".repeat(dots)}")
        tuplet?.let { append(" ($it)") }
    }

    companion object {
        // Common durations
        val WHOLE = Duration(DurationBase.WHOLE)
        val HALF = Duration(DurationBase.HALF)
        val QUARTER = Duration(DurationBase.QUARTER)
        val EIGHTH = Duration(DurationBase.EIGHTH)
        val SIXTEENTH = Duration(DurationBase.SIXTEENTH)
        val THIRTY_SECOND = Duration(DurationBase.THIRTY_SECOND)

        // Dotted durations
        val DOTTED_HALF = Duration(DurationBase.HALF, dots = 1)
        val DOTTED_QUARTER = Duration(DurationBase.QUARTER, dots = 1)
        val DOTTED_EIGHTH = Duration(DurationBase.EIGHTH, dots = 1)

        /**
         * Create a triplet version of a duration.
         */
        fun triplet(base: DurationBase): Duration =
            Duration(base, tuplet = Tuplet.TRIPLET)

        /**
         * The duration written as a single symbol for [fraction] (whole note = 1), or null when the
         * value can only be notated with ties (5/8, 9/16, …) or an unsupported tuplet.
         */
        fun fromFraction(fraction: Fraction): Duration? =
            fraction.simplified().takeIf { it.isPositive }?.let(SINGLE_SYMBOL_BY_FRACTION::get)

        /**
         * The longest single-symbol duration that does not exceed [fraction]. Domains that only need
         * a magnitude — solver frames synthesize events purely to evaluate rules — use this for
         * values [fromFraction] cannot express.
         */
        fun atMost(fraction: Fraction): Duration =
            UNDOTTED_AND_DOTTED.firstOrNull { it.toFraction() <= fraction }
                ?: Duration(DurationBase.ONE_TWENTY_EIGHTH)

        /** Plain and dotted values, longest first; fewer dots wins whenever two forms coincide. */
        private val UNDOTTED_AND_DOTTED: List<Duration> by lazy {
            DurationBase.entries
                .flatMap { base -> (0..3).map { dots -> Duration(base, dots) } }
                .sortedWith(compareByDescending<Duration> { it.toFraction() }.thenBy { it.dots })
        }

        /**
         * Plain, dotted and triplet values. Deeper tuplets are left out on purpose: a duplet or a
         * dotted triplet re-expresses values (5/8, 9/16, …) that scores really write as ties, and
         * answering with one would hide that the caller needs more than one symbol.
         */
        private val SINGLE_SYMBOL_BY_FRACTION: Map<Fraction, Duration> by lazy {
            val triplets = DurationBase.entries.map(::triplet)
            buildMap {
                (UNDOTTED_AND_DOTTED + triplets).forEach { duration ->
                    val value = duration.toFraction()
                    if (value !in keys) put(value, duration)
                }
            }
        }
    }
}
