package com.mecon.theory

import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Mode as ApiMode
import com.mecon.api.primitive.PitchClass

enum class Mode(
    val signatureTonicDegree: Int?,
    private val intervals: List<Int>,
) {
    IONIAN(1, listOf(0, 2, 4, 5, 7, 9, 11)),
    DORIAN(2, listOf(0, 2, 3, 5, 7, 9, 10)),
    PHRYGIAN(3, listOf(0, 1, 3, 5, 7, 8, 10)),
    LYDIAN(4, listOf(0, 2, 4, 6, 7, 9, 11)),
    MIXOLYDIAN(5, listOf(0, 2, 4, 5, 7, 9, 10)),
    AEOLIAN(6, listOf(0, 2, 3, 5, 7, 8, 10)),
    LOCRIAN(7, listOf(0, 1, 3, 5, 6, 8, 10)),
    HARMONIC_MINOR(6, listOf(0, 2, 3, 5, 7, 8, 11)),
    MELODIC_MINOR(6, listOf(0, 2, 3, 5, 7, 9, 11)),
    PENTATONIC_MAJOR(1, listOf(0, 2, 4, 7, 9)),
    PENTATONIC_MINOR(6, listOf(0, 3, 5, 7, 10)),
    BLUES(null, listOf(0, 3, 5, 6, 7, 10)),
    CHROMATIC(null, (0..11).toList()),
    WHOLE_TONE(null, listOf(0, 2, 4, 6, 8, 10)),
    DIMINISHED(null, listOf(0, 2, 3, 5, 6, 8, 9, 11)),
    AUGMENTED(null, listOf(0, 3, 4, 7, 8, 11));

    fun semitones(): List<Int> = intervals
}

data class Scale(
    val root: PitchClass,
    val mode: Mode,
    val pitchClasses: List<PitchClass>,
) {
    val notes: List<String> get() = pitchClasses.map { it.toNoteName() }

    fun contains(pitchClass: PitchClass): Boolean = pitchClasses.contains(pitchClass)

    companion object {
        fun fromMode(root: PitchClass, mode: Mode): Scale =
            Scale(root, mode, mode.semitones().map { root.transpose(it) })

        fun fromMode(mode: Mode, root: PitchClass): Scale = fromMode(root, mode)

        fun major(root: PitchClass): Scale {
            return fromMode(root, Mode.IONIAN)
        }
        fun minor(root: PitchClass): Scale {
            return fromMode(root, Mode.AEOLIAN)
        }
    }
}

enum class KeySignatureMode(
    val tonicDegree: Int,
    val scaleMode: Mode,
) {
    MAJOR(1, Mode.IONIAN),
    MINOR(6, Mode.AEOLIAN);

    fun tonicForSignatureRoot(signatureRoot: PitchClass): PitchClass =
        signatureRoot.transpose(MAJOR_SIGNATURE_INTERVALS[tonicDegree - 1])

    fun signatureRootForTonic(tonic: PitchClass): PitchClass =
        tonic.transpose(-MAJOR_SIGNATURE_INTERVALS[tonicDegree - 1])

    companion object {
        fun fromApiMode(mode: ApiMode): KeySignatureMode = when (mode) {
            ApiMode.MINOR,
            ApiMode.AEOLIAN -> MINOR
            else -> MAJOR
        }
    }
}

data class Key(val root: PitchClass, val mode: Mode) {
    val scale: Scale get() = Scale.fromMode(root, mode)

    val naturalTriads: List<NaturalTriad> get() = NaturalTriads.inKey(this)

    val diatonicChords: List<Chord> get() = naturalTriads.map { it.chord }

    fun naturalTriadMatches(chord: Chord): List<NaturalTriad> =
        NaturalTriads.matches(this, chord)

    fun isNaturalTriad(chord: Chord): Boolean =
        NaturalTriads.contains(this, chord)

    fun minorAlterationsUsedBy(chord: Chord): Set<MinorAlteration> =
        NaturalTriads.minorAlterationsUsedBy(this, chord)

    companion object {
        fun major(root: PitchClass) = Key(root, Mode.IONIAN)
        fun minor(root: PitchClass) = Key(root, Mode.AEOLIAN)

        fun fromKeySignatureFifths(
            fifths: Int,
            interpretation: KeySignatureMode,
        ): Key {
            val signatureRoot = KeySignature.majorByFifths(fifths).root
            return Key(
                root = interpretation.tonicForSignatureRoot(signatureRoot),
                mode = interpretation.scaleMode,
            )
        }

        fun fromKeySignature(
            keySignature: KeySignature,
            interpretation: KeySignatureMode = KeySignatureMode.fromApiMode(keySignature.mode),
        ): Key = fromKeySignatureFifths(keySignature.fifths, interpretation)
    }
}

private val MAJOR_SIGNATURE_INTERVALS = listOf(0, 2, 4, 5, 7, 9, 11)
