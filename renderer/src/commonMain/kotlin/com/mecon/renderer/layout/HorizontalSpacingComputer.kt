package com.mecon.renderer.layout

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.renderer.geometry.StaffSpace
import kotlin.math.log2
import kotlin.math.pow

/**
 * Computes horizontal spacing for notes based on their durations.
 *
 * Uses a logarithmic spacing algorithm where longer notes get proportionally
 * more space, but not in a strictly linear relationship.
 */
class HorizontalSpacingComputer(
    private val config: RenderLayoutConfig
) {
    /**
     * Calculate the ideal width for a note based on its duration.
     *
     * Uses the formula: width = baseUnit * (ratio ^ log2(duration/reference))
     * where reference duration is typically a quarter note.
     */
    fun widthForDuration(duration: Duration): StaffSpace {
        val ticks = duration.toTicks()
        val referenceTicks = DurationBase.QUARTER.ticks  // 1024 ticks for quarter note

        if (ticks <= 0) return config.minimumNoteSpacing

        // Calculate ratio relative to quarter note
        val ratio = ticks.toFloat() / referenceTicks

        // Apply logarithmic scaling
        val exponent = log2(ratio)
        val scaleFactor = config.spacingRatio.pow(exponent)

        val width = StaffSpace(config.baseSpacingUnit.value * scaleFactor)

        return maxOf(width, config.minimumNoteSpacing)
    }

    /**
     * Calculate width for a duration given as a Fraction of a whole note.
     */
    fun widthForFraction(fraction: Fraction): StaffSpace {
        val wholeTicks = DurationBase.WHOLE.ticks
        val ticks = (wholeTicks * fraction.numerator) / fraction.denominator
        val referenceTicks = DurationBase.QUARTER.ticks

        if (ticks <= 0) return config.minimumNoteSpacing

        val ratio = ticks.toFloat() / referenceTicks
        val exponent = log2(ratio)
        val scaleFactor = config.spacingRatio.pow(exponent)

        val width = StaffSpace(config.baseSpacingUnit.value * scaleFactor)

        return maxOf(width, config.minimumNoteSpacing)
    }

    /**
     * Calculate spacing for a time slot containing multiple voices.
     * Takes the maximum width needed by any voice at that time point.
     */
    fun widthForTimeSlot(durations: List<Duration>): StaffSpace {
        if (durations.isEmpty()) return config.minimumNoteSpacing
        return durations.maxOf { widthForDuration(it) }
    }

    companion object {
        /**
         * Create a computer with default configuration.
         */
        fun default(): HorizontalSpacingComputer =
            HorizontalSpacingComputer(RenderLayoutConfig.DEFAULT)
    }
}
