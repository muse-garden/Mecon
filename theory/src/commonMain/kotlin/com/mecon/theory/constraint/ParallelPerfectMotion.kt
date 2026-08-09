package com.mecon.theory.constraint

import com.mecon.api.primitive.Pitch
import kotlin.math.abs

internal data class ParallelPerfectMotion(
    val upperVoiceIndex: Int,
    val lowerVoiceIndex: Int,
    val intervalClass: Int,
)

/**
 * Finds pairs that move in the same direction between the same perfect interval class.
 * Voice lists are ordered from high to low; octave compounds intentionally collapse to class 0.
 */
internal fun detectParallelPerfectMotions(
    previousPitches: List<Pitch>,
    currentPitches: List<Pitch>,
): List<ParallelPerfectMotion> {
    require(previousPitches.size == currentPitches.size) {
        "Parallel-motion frames must contain the same voices"
    }
    return buildList {
        previousPitches.indices.forEach { upperIndex ->
            ((upperIndex + 1) until previousPitches.size).forEach { lowerIndex ->
                val upperMotion = currentPitches[upperIndex].midiNumber -
                    previousPitches[upperIndex].midiNumber
                val lowerMotion = currentPitches[lowerIndex].midiNumber -
                    previousPitches[lowerIndex].midiNumber
                if (upperMotion == 0 || lowerMotion == 0 || upperMotion.sign != lowerMotion.sign) {
                    return@forEach
                }
                val beforeInterval = intervalClass(
                    previousPitches[upperIndex],
                    previousPitches[lowerIndex],
                )
                val afterInterval = intervalClass(
                    currentPitches[upperIndex],
                    currentPitches[lowerIndex],
                )
                if (beforeInterval in PERFECT_INTERVAL_CLASSES && afterInterval == beforeInterval) {
                    add(ParallelPerfectMotion(upperIndex, lowerIndex, beforeInterval))
                }
            }
        }
    }
}

private fun intervalClass(upper: Pitch, lower: Pitch): Int =
    abs(upper.midiNumber - lower.midiNumber).mod(12)

private val Int.sign: Int get() = compareTo(0)

private val PERFECT_INTERVAL_CLASSES = setOf(0, 7)
