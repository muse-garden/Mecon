package com.mecon.api.primitive

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Interval quality
 */
enum class IntervalQuality {
    DOUBLY_DIMINISHED, DIMINISHED, MINOR, PERFECT, MAJOR, AUGMENTED, DOUBLY_AUGMENTED
}

/**
 * Interval represents the distance between two pitches in semitones.
 */
@Serializable
@JvmInline
value class Interval(val semitones: Int) : Comparable<Interval> {

    /** The interval within one octave (0-11) */
    val simpleSemitones: Int get() = semitones.mod(12)

    /** Whether this is a compound interval (>= octave) */
    val isCompound: Boolean get() = kotlin.math.abs(semitones) >= 12

    /** Whether this interval is ascending (positive) */
    val isAscending: Boolean get() = semitones >= 0

    /**
     * Get the quality of the interval.
     * Note: This is a simplified implementation based only on semitones.
     */
    val quality: IntervalQuality
        get() = when (simpleSemitones) {
            0 -> IntervalQuality.PERFECT    // Unison
            1 -> IntervalQuality.MINOR      // Minor 2nd
            2 -> IntervalQuality.MAJOR      // Major 2nd
            3 -> IntervalQuality.MINOR      // Minor 3rd
            4 -> IntervalQuality.MAJOR      // Major 3rd
            5 -> IntervalQuality.PERFECT    // Perfect 4th
            6 -> IntervalQuality.AUGMENTED  // Tritone
            7 -> IntervalQuality.PERFECT    // Perfect 5th
            8 -> IntervalQuality.MINOR      // Minor 6th
            9 -> IntervalQuality.MAJOR      // Major 6th
            10 -> IntervalQuality.MINOR     // Minor 7th
            11 -> IntervalQuality.MAJOR     // Major 7th
            else -> IntervalQuality.PERFECT
        }

    /**
     * Get the interval name (simplified).
     */
    val name: String
        get() = when (simpleSemitones) {
            0 -> "P1"   // Perfect Unison
            1 -> "m2"   // Minor 2nd
            2 -> "M2"   // Major 2nd
            3 -> "m3"   // Minor 3rd
            4 -> "M3"   // Major 3rd
            5 -> "P4"   // Perfect 4th
            6 -> "TT"   // Tritone
            7 -> "P5"   // Perfect 5th
            8 -> "m6"   // Minor 6th
            9 -> "M6"   // Major 6th
            10 -> "m7"  // Minor 7th
            11 -> "M7"  // Major 7th
            else -> "$semitones st"
        }

    /**
     * Invert the interval (within one octave).
     */
    fun invert(): Interval = Interval(12 - simpleSemitones)

    operator fun plus(other: Interval): Interval = Interval(semitones + other.semitones)
    operator fun minus(other: Interval): Interval = Interval(semitones - other.semitones)
    operator fun unaryMinus(): Interval = Interval(-semitones)

    override fun compareTo(other: Interval): Int = semitones.compareTo(other.semitones)

    override fun toString(): String = name

    companion object {
        val UNISON = Interval(0)
        val MINOR_SECOND = Interval(1)
        val MAJOR_SECOND = Interval(2)
        val MINOR_THIRD = Interval(3)
        val MAJOR_THIRD = Interval(4)
        val PERFECT_FOURTH = Interval(5)
        val TRITONE = Interval(6)
        val PERFECT_FIFTH = Interval(7)
        val MINOR_SIXTH = Interval(8)
        val MAJOR_SIXTH = Interval(9)
        val MINOR_SEVENTH = Interval(10)
        val MAJOR_SEVENTH = Interval(11)
        val OCTAVE = Interval(12)

        /**
         * Calculate interval between two pitches.
         */
        fun between(from: Pitch, to: Pitch): Interval =
            Interval(to.midiNumber - from.midiNumber)
    }
}
