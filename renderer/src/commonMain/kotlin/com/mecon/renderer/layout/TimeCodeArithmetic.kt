package com.mecon.renderer.layout

import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode

/**
 * Arithmetic utilities for TimeCode operations in layout calculations.
 *
 * Provides functions to convert TimeCode to absolute ticks and compute
 * time differences for proportional spacing calculations.
 */
object TimeCodeArithmetic {
    /**
     * Ticks per whole note (same as DurationBase.WHOLE.ticks).
     */
    private const val TICKS_PER_WHOLE = 4096

    /**
     * Convert a TimeCode to absolute ticks.
     *
     * The conversion uses the measure number and beat position:
     * - Measure numbers are 1-based integers (first component)
     * - Beat is a fraction of a whole note (second component, if present)
     *
     * Formula: absoluteTicks = (measure - 1) * TICKS_PER_WHOLE + beat * TICKS_PER_WHOLE
     *
     * Note: This assumes 4/4 time for measure width. For accurate multi-meter
     * support, the algorithm would need meter context.
     */
    fun TimeCode.toAbsoluteTicks(): Long {
        val measureNum = measure
        val beatFraction = beat ?: Fraction.ZERO

        // Convert measure number to ticks (measures are 1-based)
        val measureTicks = (measureNum - 1).toLong() * TICKS_PER_WHOLE

        // Convert beat fraction to ticks
        val beatTicks = (beatFraction.numerator.toLong() * TICKS_PER_WHOLE) / beatFraction.denominator

        return measureTicks + beatTicks
    }

    /**
     * Calculate the tick difference between two TimeCodes.
     *
     * @param other The target TimeCode
     * @return The difference in ticks (other - this), can be negative
     */
    fun TimeCode.ticksTo(other: TimeCode): Long {
        return other.toAbsoluteTicks() - this.toAbsoluteTicks()
    }

    /**
     * Calculate the absolute tick difference (always positive).
     */
    fun TimeCode.absoluteTicksTo(other: TimeCode): Long {
        return kotlin.math.abs(ticksTo(other))
    }

    /**
     * Check if two TimeCodes are equal in terms of absolute position.
     */
    fun TimeCode.isSamePosition(other: TimeCode): Boolean {
        return toAbsoluteTicks() == other.toAbsoluteTicks()
    }

    /**
     * Get ticks per quarter note (for spacing calculations).
     */
    val TICKS_PER_QUARTER: Int = DurationBase.QUARTER.ticks
}
