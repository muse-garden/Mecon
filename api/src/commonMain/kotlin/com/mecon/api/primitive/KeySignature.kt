package com.mecon.api.primitive

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.Serializable

/**
 * Musical mode (scale type).
 */
enum class Mode(private val intervals: List<Int>) {
    MAJOR(listOf(0, 2, 4, 5, 7, 9, 11)),
    MINOR(listOf(0, 2, 3, 5, 7, 8, 10)),
    DORIAN(listOf(0, 2, 3, 5, 7, 9, 10)),
    PHRYGIAN(listOf(0, 1, 3, 5, 7, 8, 10)),
    LYDIAN(listOf(0, 2, 4, 6, 7, 9, 11)),
    MIXOLYDIAN(listOf(0, 2, 4, 5, 7, 9, 10)),
    AEOLIAN(listOf(0, 2, 3, 5, 7, 8, 10)),  // Natural minor
    LOCRIAN(listOf(0, 1, 3, 5, 6, 8, 10));

    /**
     * Get the intervals from root as a list of semitones.
     */
    fun intervals(): List<Interval> = intervals.map { Interval(it) }

    /**
     * Get scale degrees (semitone offsets from root).
     */
    fun semitones(): List<Int> = intervals
}

/**
 * Key signature definition.
 */
@Serializable
data class KeySignature(
    val root: PitchClass,
    val mode: Mode,
    val customScale: ImmutableList<PitchClass>? = null,
    val fifthsOverride: Int? = null
) {
    init {
        require(fifthsOverride == null || fifthsOverride in -7..7) {
            "Key signature fifths must be between -7 and 7"
        }
    }

    /**
     * Get all pitch classes in this key.
     */
    fun scale(): List<PitchClass> =
        customScale?.toList() ?: mode.semitones().map { root.transpose(it) }

    /**
     * Get the number of sharps (positive) or flats (negative) in this key signature.
     */
    val fifths: Int
        get() = fifthsOverride ?: inferFifths(root, mode)

    /**
     * Display root name preserving the key-signature spelling when enharmonic keys share a pitch class.
     */
    val displayName: String
        get() = when (mode) {
            Mode.MAJOR -> MAJOR_NAMES_BY_FIFTHS[fifths]
            Mode.MINOR -> MINOR_NAMES_BY_FIFTHS[fifths]
            else -> null
        } ?: root.toNoteName(fifths >= 0)

    /**
     * Get the accidentals that should be shown in the key signature.
     * Returns a list of note names with their accidentals.
     */
    fun accidentals(): List<Pair<NoteName, Accidental>> {
        val sharps = listOf(
            NoteName.F, NoteName.C, NoteName.G, NoteName.D,
            NoteName.A, NoteName.E, NoteName.B
        )
        val flats = listOf(
            NoteName.B, NoteName.E, NoteName.A, NoteName.D,
            NoteName.G, NoteName.C, NoteName.F
        )

        return when {
            fifths > 0 -> sharps.take(fifths).map { it to Accidental.SHARP }
            fifths < 0 -> flats.take(-fifths).map { it to Accidental.FLAT }
            else -> emptyList()
        }
    }

    /**
     * Get the default accidental for a note name in this key.
     */
    fun accidentalFor(noteName: NoteName): Accidental {
        val accidentalMap = accidentals().toMap()
        return accidentalMap[noteName] ?: Accidental.NATURAL
    }

    /**
     * Check if a pitch belongs to this key (is diatonic).
     */
    fun isDiatonic(pitch: Pitch): Boolean =
        pitch.pitchClass in scale()

    override fun toString(): String {
        return "$displayName ${mode.name.lowercase()}"
    }

    companion object {
        // Common key signatures
        val C_MAJOR = KeySignature(PitchClass.C, Mode.MAJOR)
        val G_MAJOR = KeySignature(PitchClass.G, Mode.MAJOR)
        val D_MAJOR = KeySignature(PitchClass.D, Mode.MAJOR)
        val F_MAJOR = KeySignature(PitchClass.F, Mode.MAJOR)
        val A_MINOR = KeySignature(PitchClass.A, Mode.MINOR)
        val E_MINOR = KeySignature(PitchClass.E, Mode.MINOR)
        val D_MINOR = KeySignature(PitchClass.D, Mode.MINOR)

        fun majorByFifths(fifths: Int): KeySignature =
            byFifths(fifths, Mode.MAJOR, MAJOR_ROOTS_BY_FIFTHS)

        fun minorByFifths(fifths: Int): KeySignature =
            byFifths(fifths, Mode.MINOR, MINOR_ROOTS_BY_FIFTHS)

        private fun byFifths(
            fifths: Int,
            mode: Mode,
            rootsByFifths: Map<Int, PitchClass>,
        ): KeySignature {
            require(fifths in -7..7) {
                "Key signature fifths must be between -7 and 7"
            }
            val root = rootsByFifths.getValue(fifths)
            return KeySignature(
                root = root,
                mode = mode,
                fifthsOverride = fifths.takeIf { inferFifths(root, mode) != it },
            )
        }

        private fun inferFifths(root: PitchClass, mode: Mode): Int {
            if (mode != Mode.MAJOR && mode != Mode.MINOR) return 0

            val majorRoot = if (mode == Mode.MINOR) {
                root.transpose(3)
            } else {
                root
            }

            return MAJOR_FIFTHS_BY_ROOT[majorRoot] ?: 0
        }

        private val MAJOR_FIFTHS_BY_ROOT = mapOf(
            PitchClass.C to 0,
            PitchClass(7) to 1,   // G
            PitchClass(2) to 2,   // D
            PitchClass(9) to 3,   // A
            PitchClass(4) to 4,   // E
            PitchClass(11) to 5,  // B, enharmonic Cb requires fifthsOverride = -7
            PitchClass(6) to 6,   // F#, enharmonic Gb requires fifthsOverride = -6
            PitchClass(1) to 7,   // C#, enharmonic Db requires fifthsOverride = -5
            PitchClass(8) to -4,  // Ab
            PitchClass(3) to -3,  // Eb
            PitchClass(10) to -2, // Bb
            PitchClass(5) to -1,  // F
        )

        private val MAJOR_ROOTS_BY_FIFTHS = mapOf(
            -7 to PitchClass(11), // Cb
            -6 to PitchClass(6),  // Gb
            -5 to PitchClass(1),  // Db
            -4 to PitchClass(8),  // Ab
            -3 to PitchClass(3),  // Eb
            -2 to PitchClass(10), // Bb
            -1 to PitchClass(5),  // F
            0 to PitchClass.C,
            1 to PitchClass(7),   // G
            2 to PitchClass(2),   // D
            3 to PitchClass(9),   // A
            4 to PitchClass(4),   // E
            5 to PitchClass(11),  // B
            6 to PitchClass(6),   // F#
            7 to PitchClass(1),   // C#
        )

        private val MINOR_ROOTS_BY_FIFTHS = mapOf(
            -7 to PitchClass(8),  // Ab
            -6 to PitchClass(3),  // Eb
            -5 to PitchClass(10), // Bb
            -4 to PitchClass(5),  // F
            -3 to PitchClass.C,
            -2 to PitchClass(7),  // G
            -1 to PitchClass(2),  // D
            0 to PitchClass(9),   // A
            1 to PitchClass(4),   // E
            2 to PitchClass(11),  // B
            3 to PitchClass(6),   // F#
            4 to PitchClass(1),   // C#
            5 to PitchClass(8),   // G#
            6 to PitchClass(3),   // D#
            7 to PitchClass(10),  // A#
        )

        private val MAJOR_NAMES_BY_FIFTHS = mapOf(
            -7 to "Cb",
            -6 to "Gb",
            -5 to "Db",
            -4 to "Ab",
            -3 to "Eb",
            -2 to "Bb",
            -1 to "F",
            0 to "C",
            1 to "G",
            2 to "D",
            3 to "A",
            4 to "E",
            5 to "B",
            6 to "F#",
            7 to "C#",
        )

        private val MINOR_NAMES_BY_FIFTHS = mapOf(
            -7 to "Ab",
            -6 to "Eb",
            -5 to "Bb",
            -4 to "F",
            -3 to "C",
            -2 to "G",
            -1 to "D",
            0 to "A",
            1 to "E",
            2 to "B",
            3 to "F#",
            4 to "C#",
            5 to "G#",
            6 to "D#",
            7 to "A#",
        )
    }
}
