package com.mecon.api.runtime

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode

/**
 * Immutable conversion between score-local [TimeCode] and linear whole-note time.
 *
 * It is built once per runtime frame and shared by editing, layout projections and piano-roll
 * hit-testing so varying meters cannot produce subtly different horizontal coordinates.
 */
data class ScoreTimeMap(
    private val measureStarts: List<Fraction>,
    private val measureLengths: List<Fraction>,
    private val fallbackMeasureLength: Fraction,
) {
    init {
        require(measureStarts.isNotEmpty()) { "ScoreTimeMap requires measure 1" }
        require(measureStarts.size == measureLengths.size) {
            "ScoreTimeMap starts and lengths must have the same size"
        }
        require(fallbackMeasureLength.isPositive) { "Fallback measure length must be positive" }
    }

    fun absolute(time: TimeCode): Fraction {
        val measure = time.measure.coerceAtLeast(1)
        val start = if (measure <= measureStarts.size) {
            measureStarts[measure - 1]
        } else {
            val knownEnd = measureStarts.last() + measureLengths.last()
            knownEnd + fallbackMeasureLength * (measure - measureStarts.size - 1)
        }
        return start + (time.beat ?: Fraction.ZERO)
    }

    fun timeCodeAt(position: Fraction): TimeCode {
        val target = position.coerceAtLeast(Fraction.ZERO)
        var low = 0
        var high = measureStarts.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val start = measureStarts[middle]
            val end = start + measureLengths[middle]
            when {
                target < start -> high = middle - 1
                target >= end -> low = middle + 1
                else -> return TimeCode.of(middle + 1, target - start)
            }
        }
        val knownEnd = measureStarts.last() + measureLengths.last()
        if (target == knownEnd) return TimeCode.of(measureStarts.size + 1, Fraction.ZERO)
        val beyond = target - knownEnd
        val offset = beyond / fallbackMeasureLength
        val fullMeasures = offset.numerator / offset.denominator
        val measure = measureStarts.size + 1 + fullMeasures
        val beat = beyond - fallbackMeasureLength * fullMeasures
        return TimeCode.of(measure, beat)
    }

    companion object {
        fun from(score: RuntimeScore): ScoreTimeMap {
            val lastMeasure = score.measures.map { it.key }.maxOrNull() ?: 1
            val starts = ArrayList<Fraction>(lastMeasure)
            val lengths = ArrayList<Fraction>(lastMeasure)
            var cursor = Fraction.ZERO
            for (measure in 1..lastMeasure) {
                starts += cursor
                val length = score.getTimeSignatureAt(measure).measureDuration()
                lengths += length
                cursor += length
            }
            return ScoreTimeMap(
                measureStarts = starts,
                measureLengths = lengths,
                fallbackMeasureLength = score.getTimeSignatureAt(lastMeasure).measureDuration(),
            )
        }
    }
}
