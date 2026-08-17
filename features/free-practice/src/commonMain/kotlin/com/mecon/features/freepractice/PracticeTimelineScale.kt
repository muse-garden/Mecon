package com.mecon.features.freepractice

import com.mecon.api.primitive.Fraction

/** Shared zoom policy: a chord with the configured default duration keeps a stable visual width. */
object PracticeTimelineScale {
    const val DEFAULT_CHORD_WIDTH: Float = 144f
    const val MIN_CHORD_WIDTH: Float = 44f
    const val MAX_CHORD_WIDTH: Float = 176f

    fun pixelsPerWhole(
        defaultChordDuration: Fraction,
        defaultChordWidth: Float = DEFAULT_CHORD_WIDTH,
    ): Float {
        require(defaultChordDuration > Fraction.ZERO) { "Default chord duration must be positive" }
        require(defaultChordWidth > 0f) { "Default chord width must be positive" }
        return defaultChordWidth / defaultChordDuration.toFloat()
    }
}
