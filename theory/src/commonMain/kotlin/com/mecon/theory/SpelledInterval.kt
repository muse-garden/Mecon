package com.mecon.theory

import com.mecon.api.primitive.IntervalQuality
import com.mecon.api.primitive.Pitch
import kotlin.math.abs

enum class IntervalDirection(val sign: Int) {
    ASCENDING(1),
    DESCENDING(-1),
}

enum class SpelledIntervalBase {
    PERFECT,
    MAJOR,
    MINOR,
}

data class SpelledInterval(
    val number: Int,
    val base: SpelledIntervalBase,
    val offset: Int = 0,
    val direction: IntervalDirection = IntervalDirection.ASCENDING,
) {
    init {
        require(number >= 1) { "Interval number must be positive, got $number" }
        if (isPerfectClass(number)) {
            require(base == SpelledIntervalBase.PERFECT) {
                "Unisons, fourths, fifths, and octaves must use PERFECT as their base"
            }
        } else {
            require(base != SpelledIntervalBase.PERFECT) {
                "Seconds, thirds, sixths, and sevenths must use MAJOR or MINOR as their base"
            }
        }
    }

    val semitones: Int
        get() = direction.sign * (baseSemitones(number, base) + offset)

    val simpleNumber: Int
        get() = ((number - 1).mod(7)) + 1

    val octaveCount: Int
        get() = (number - 1) / 7

    val quality: IntervalQuality
        get() = when (base) {
            SpelledIntervalBase.PERFECT -> when (offset) {
                0 -> IntervalQuality.PERFECT
                1 -> IntervalQuality.AUGMENTED
                2 -> IntervalQuality.DOUBLY_AUGMENTED
                -1 -> IntervalQuality.DIMINISHED
                -2 -> IntervalQuality.DOUBLY_DIMINISHED
                else -> if (offset > 0) IntervalQuality.AUGMENTED else IntervalQuality.DIMINISHED
            }
            SpelledIntervalBase.MAJOR -> when (offset) {
                0 -> IntervalQuality.MAJOR
                1 -> IntervalQuality.AUGMENTED
                2 -> IntervalQuality.DOUBLY_AUGMENTED
                else -> if (offset > 0) IntervalQuality.AUGMENTED else IntervalQuality.DIMINISHED
            }
            SpelledIntervalBase.MINOR -> when (offset) {
                0 -> IntervalQuality.MINOR
                -1 -> IntervalQuality.DIMINISHED
                -2 -> IntervalQuality.DOUBLY_DIMINISHED
                else -> if (offset > 0) IntervalQuality.AUGMENTED else IntervalQuality.DIMINISHED
            }
        }

    val isCompound: Boolean get() = number > 8

    fun isEnharmonicallyEquivalentTo(other: SpelledInterval): Boolean =
        abs(semitones) == abs(other.semitones)

    fun transpose(pitch: Pitch): Pitch {
        val diatonicOffset = direction.sign * (number - 1)
        val targetDiatonicSteps = pitch.diatonicSteps + diatonicOffset
        val targetMidi = pitch.midiNumber + semitones
        val naturalTarget = Pitch(targetDiatonicSteps, 0)
        return Pitch(targetDiatonicSteps, targetMidi - naturalTarget.midiNumber)
    }

    override fun toString(): String {
        val qualityPrefix = when (quality) {
            IntervalQuality.DOUBLY_DIMINISHED -> "dd"
            IntervalQuality.DIMINISHED -> "d"
            IntervalQuality.MINOR -> "m"
            IntervalQuality.PERFECT -> "P"
            IntervalQuality.MAJOR -> "M"
            IntervalQuality.AUGMENTED -> "A"
            IntervalQuality.DOUBLY_AUGMENTED -> "AA"
        }
        val directionPrefix = if (direction == IntervalDirection.DESCENDING) "-" else ""
        return "$directionPrefix$qualityPrefix$number"
    }

    companion object {
        fun between(from: Pitch, to: Pitch): SpelledInterval {
            val diatonicDelta = to.diatonicSteps - from.diatonicSteps
            val direction = if (to.midiNumber < from.midiNumber) {
                IntervalDirection.DESCENDING
            } else {
                IntervalDirection.ASCENDING
            }
            val number = abs(diatonicDelta) + 1
            val semitones = abs(to.midiNumber - from.midiNumber)
            return fromNumberAndSemitones(number, semitones, direction)
        }

        fun fromNumberAndSemitones(
            number: Int,
            semitones: Int,
            direction: IntervalDirection = IntervalDirection.ASCENDING,
        ): SpelledInterval {
            require(number >= 1) { "Interval number must be positive, got $number" }
            require(semitones >= 0) { "Semitones must be non-negative, got $semitones" }
            return if (isPerfectClass(number)) {
                val perfectBase = baseSemitones(number, SpelledIntervalBase.PERFECT)
                SpelledInterval(
                    number = number,
                    base = SpelledIntervalBase.PERFECT,
                    offset = semitones - perfectBase,
                    direction = direction,
                )
            } else {
                val majorBase = baseSemitones(number, SpelledIntervalBase.MAJOR)
                val delta = semitones - majorBase
                if (delta >= 0) {
                    SpelledInterval(
                        number = number,
                        base = SpelledIntervalBase.MAJOR,
                        offset = delta,
                        direction = direction,
                    )
                } else {
                    SpelledInterval(
                        number = number,
                        base = SpelledIntervalBase.MINOR,
                        offset = delta + 1,
                        direction = direction,
                    )
                }
            }
        }

        private fun isPerfectClass(number: Int): Boolean =
            ((number - 1).mod(7) + 1) in setOf(1, 4, 5)

        private fun baseSemitones(number: Int, base: SpelledIntervalBase): Int {
            val simple = ((number - 1).mod(7)) + 1
            val octaves = (number - 1) / 7
            val simpleMajorOrPerfect = when (simple) {
                1 -> 0
                2 -> 2
                3 -> 4
                4 -> 5
                5 -> 7
                6 -> 9
                7 -> 11
                else -> error("Unexpected interval number $number")
            }
            val minorAdjustment = if (base == SpelledIntervalBase.MINOR) -1 else 0
            return simpleMajorOrPerfect + octaves * 12 + minorAdjustment
        }
    }
}

