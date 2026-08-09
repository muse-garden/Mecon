package com.mecon.api.primitive

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.math.abs

/**
 * Note name (C, D, E, F, G, A, B)
 */
enum class NoteName {
    C, D, E, F, G, A, B;

    /** Index in diatonic scale (0-6) */
    fun toIndex(): Int = ordinal

    /** Semitone offset from C (C=0, D=2, E=4, F=5, G=7, A=9, B=11) */
    fun toSemitone(): Int = SEMITONES[ordinal]

    companion object {
        private val SEMITONES = intArrayOf(0, 2, 4, 5, 7, 9, 11)

        fun fromIndex(index: Int): NoteName = entries[index.mod(7)]
    }
}

/**
 * Accidental (sharp, flat, natural, etc.)
 */
enum class Accidental(val offset: Int, val symbol: String, val asciiSymbol: String) {
    DOUBLE_FLAT(-2, "𝄫", "bb"),
    FLAT(-1, "♭", "b"),
    NATURAL(0, "", ""),
    SHARP(1, "♯", "#"),
    DOUBLE_SHARP(2, "𝄪", "x");

    companion object {
        /**
         * Get accidental from semitone offset.
         * Values outside [-2, 2] are clamped.
         */
        fun fromOffset(offset: Int): Accidental = when {
            offset <= -2 -> DOUBLE_FLAT
            offset == -1 -> FLAT
            offset == 0 -> NATURAL
            offset == 1 -> SHARP
            else -> DOUBLE_SHARP
        }
    }
}

/**
 * Pitch represents a musical pitch with precise spelling information.
 *
 * Uses diatonic steps (relative to C4) and chromatic offset (accidentals)
 * to precisely distinguish enharmonic equivalents (e.g., C# vs Db).
 *
 * @param diatonicSteps Position in diatonic scale relative to C4
 *   - 0 = C4, 1 = D4, 2 = E4, 3 = F4, 4 = G4, 5 = A4, 6 = B4
 *   - 7 = C5, 14 = C6, -1 = B3, -7 = C3
 *
 * @param chromaticOffset Chromatic modification (accidentals)
 *   - 0 = natural, 1 = sharp, 2 = double sharp, -1 = flat, -2 = double flat
 *
 * Examples:
 *   - C4  = Pitch(0, 0)
 *   - C#4 = Pitch(0, 1)
 *   - Db4 = Pitch(1, -1)  // Enharmonic to C#4
 *   - E4  = Pitch(2, 0)
 *   - Fb4 = Pitch(3, -1)  // Enharmonic to E4
 */
@Serializable
data class Pitch(
    val diatonicSteps: Int,
    val chromaticOffset: Int = 0
) : Comparable<Pitch> {

    /** Note name (C, D, E, F, G, A, B) */
    val noteName: NoteName
        get() = NoteName.fromIndex(diatonicSteps)

    /** Octave number (C4 = octave 4) */
    val octave: Int
        get() = 4 + floorDiv(diatonicSteps, 7)

    /** Accidental */
    val accidental: Accidental
        get() = Accidental.fromOffset(chromaticOffset)

    /** MIDI note number (C4 = 60) */
    val midiNumber: Int
        get() {
            val diatonicSemitones = intArrayOf(0, 2, 4, 5, 7, 9, 11)
            val octaveOffset = floorDiv(diatonicSteps, 7)
            val noteInOctave = diatonicSteps.mod(7)
            return 60 + octaveOffset * 12 + diatonicSemitones[noteInOctave] + chromaticOffset
        }

    /** Pitch class (0-11, where C=0) */
    val pitchClass: PitchClass
        get() = PitchClass(midiNumber.mod(12))

    /**
     * Raw diatonic position (alias for diatonicSteps).
     *
     * Note: For actual staff position considering clef, use
     * [com.mecon.api.computed.StaffPositionComputer.compute].
     */
    val staffPosition: Int
        get() = diatonicSteps

    /**
     * Check if this pitch is enharmonic (same sound) to another.
     */
    fun isEnharmonic(other: Pitch): Boolean = midiNumber == other.midiNumber

    /**
     * Simplify to the most common spelling.
     */
    fun simplify(): Pitch = fromMidi(midiNumber)

    /**
     * Transpose by interval (in semitones).
     * Note: This changes the pitch but may not preserve proper spelling.
     */
    fun transpose(interval: Interval): Pitch = fromMidi(midiNumber + interval.semitones)

    /**
     * Transpose by semitones.
     */
    fun transpose(semitones: Int): Pitch = fromMidi(midiNumber + semitones)

    /**
     * Get interval from this pitch to another.
     */
    fun intervalTo(other: Pitch): Interval = Interval(other.midiNumber - midiNumber)

    override fun compareTo(other: Pitch): Int = midiNumber.compareTo(other.midiNumber)

    /**
     * Format as pitch name string (e.g., "C#4", "Db4")
     */
    fun format(): String = "${noteName.name}${accidental.asciiSymbol}$octave"

    override fun toString(): String = format()

    companion object {
        // Common pitches
        val C4 = Pitch(0, 0)
        val D4 = Pitch(1, 0)
        val E4 = Pitch(2, 0)
        val F4 = Pitch(3, 0)
        val G4 = Pitch(4, 0)
        val A4 = Pitch(5, 0)
        val B4 = Pitch(6, 0)
        val C5 = Pitch(7, 0)

        // Middle C reference
        val MIDDLE_C = C4

        /**
         * Create pitch from MIDI number using default spelling.
         * @param preferSharps Use sharps (true) or flats (false) for black keys
         */
        fun fromMidi(midi: Int, preferSharps: Boolean = true): Pitch {
            val octaveFromC4 = floorDiv(midi - 60, 12)
            val semitoneInOctave = (midi - 60).mod(12)

            // Mapping: semitone -> (diatonic step in octave, chromatic offset)
            val sharpMapping = arrayOf(
                0 to 0,   // C
                0 to 1,   // C#
                1 to 0,   // D
                1 to 1,   // D#
                2 to 0,   // E
                3 to 0,   // F
                3 to 1,   // F#
                4 to 0,   // G
                4 to 1,   // G#
                5 to 0,   // A
                5 to 1,   // A#
                6 to 0    // B
            )
            val flatMapping = arrayOf(
                0 to 0,   // C
                1 to -1,  // Db
                1 to 0,   // D
                2 to -1,  // Eb
                2 to 0,   // E
                3 to 0,   // F
                4 to -1,  // Gb
                4 to 0,   // G
                5 to -1,  // Ab
                5 to 0,   // A
                6 to -1,  // Bb
                6 to 0    // B
            )

            val mapping = if (preferSharps) sharpMapping else flatMapping
            val (noteInOctave, offset) = mapping[semitoneInOctave]
            val diatonicSteps = octaveFromC4 * 7 + noteInOctave

            return Pitch(diatonicSteps, offset)
        }

        /**
         * Parse pitch from name string.
         * Supports formats: "C4", "C#4", "Db4", "C##4", "Dbb4", etc.
         */
        fun fromName(name: String): Pitch {
            val regex = """([A-Ga-g])([#bx]*)(-?\d+)""".toRegex()
            val match = regex.matchEntire(name)
                ?: throw IllegalArgumentException("Invalid pitch name: $name")
            val (noteStr, accidentalStr, octaveStr) = match.destructured

            val noteIndex = "CDEFGAB".indexOf(noteStr.uppercase())
            val octave = octaveStr.toInt()
            val diatonicSteps = (octave - 4) * 7 + noteIndex

            var chromaticOffset = 0
            for (c in accidentalStr) {
                chromaticOffset += when (c) {
                    '#' -> 1
                    'x' -> 2
                    'b' -> -1
                    else -> 0
                }
            }

            return Pitch(diatonicSteps, chromaticOffset)
        }

        /**
         * Create pitch from note name, accidental, and octave.
         */
        fun of(noteName: NoteName, octave: Int, accidental: Accidental = Accidental.NATURAL): Pitch {
            val diatonicSteps = (octave - 4) * 7 + noteName.toIndex()
            return Pitch(diatonicSteps, accidental.offset)
        }

        // Helper for integer division that rounds toward negative infinity
        private fun floorDiv(a: Int, b: Int): Int {
            val r = a / b
            return if ((a xor b) < 0 && r * b != a) r - 1 else r
        }
    }
}

/**
 * Pitch class (0-11) representing notes without octave.
 */
@Serializable
@JvmInline
value class PitchClass(val value: Int) {
    init {
        require(value in 0..11) { "PitchClass must be 0-11, got $value" }
    }

    fun transpose(semitones: Int): PitchClass = PitchClass((value + semitones).mod(12))

    fun intervalTo(other: PitchClass): Int = (other.value - value).mod(12)

    fun toNoteName(preferSharps: Boolean = true): String =
        if (preferSharps) SHARP_NAMES[value] else FLAT_NAMES[value]

    override fun toString(): String = toNoteName()

    companion object {
        private val SHARP_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        private val FLAT_NAMES = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

        val C = PitchClass(0)
        val D = PitchClass(2)
        val E = PitchClass(4)
        val F = PitchClass(5)
        val G = PitchClass(7)
        val A = PitchClass(9)
        val B = PitchClass(11)
    }
}

/**
 * Pitch range (from low to high pitch).
 */
@Serializable
data class PitchRange(val low: Pitch, val high: Pitch) {
    init {
        require(low <= high) { "Low pitch must be <= high pitch" }
    }

    operator fun contains(pitch: Pitch): Boolean =
        pitch >= low && pitch <= high
}
