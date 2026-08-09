package com.mecon.input

import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.NoteName
import com.mecon.api.primitive.Pitch
import kotlin.math.abs

data class PitchSpellingContext(
    val keySignature: KeySignature,
    val previousPitch: Pitch? = null,
    val pitchesAtOnset: List<Pitch> = emptyList(),
    val previousPitchesInMeasure: List<Pitch> = emptyList(),
)

/**
 * Deterministic spelling for MIDI input. Explicit computer-keyboard spelling bypasses this class.
 * The cost favours the key signature, accidental economy, melodic readability and tertian chords.
 */
object PitchSpeller {
    fun spell(midi: Int, context: PitchSpellingContext): Pitch? {
        if (midi !in 0..127) return null
        return candidates(midi).minWithOrNull(
            compareBy<Pitch> { cost(it, context) }
                .thenBy { tieBreakAccidental(it, context.keySignature) }
                .thenBy { it.diatonicSteps }
        )
    }

    fun candidates(midi: Int): List<Pitch> {
        if (midi !in 0..127) return emptyList()
        val approxOctave = 4 + floorDiv(midi - 60, 12)
        return buildList {
            for (octave in (approxOctave - 1)..(approxOctave + 1)) {
                for (name in NoteName.entries) {
                    val natural = Pitch.of(name, octave)
                    val offset = midi - natural.midiNumber
                    if (offset in -2..2) add(Pitch(natural.diatonicSteps, offset))
                }
            }
        }.distinct()
    }

    private fun cost(candidate: Pitch, context: PitchSpellingContext): Int {
        val key = context.keySignature
        val keyOffset = key.accidentalFor(candidate.noteName).offset
        var result = abs(candidate.chromaticOffset - keyOffset) * 20
        if (abs(candidate.chromaticOffset) == 2) result += 24

        val samePosition = context.previousPitchesInMeasure
            .lastOrNull { it.diatonicSteps == candidate.diatonicSteps }
        if (samePosition != null && samePosition.chromaticOffset != candidate.chromaticOffset) {
            result += 12
        }

        context.previousPitch?.let { previous ->
            val semitones = abs(candidate.midiNumber - previous.midiNumber)
            val diatonic = abs(candidate.diatonicSteps - previous.diatonicSteps)
            if (semitones <= 2 && diatonic > 1) result += 10
            if (semitones >= 3 && diatonic == 0) result += 8
        }

        for (other in context.pitchesAtOnset) {
            val diatonic = abs(candidate.diatonicSteps - other.diatonicSteps).mod(7)
            if (diatonic == 2 || diatonic == 4 || diatonic == 6) result -= 3
            if (candidate.diatonicSteps == other.diatonicSteps &&
                candidate.chromaticOffset != other.chromaticOffset
            ) {
                result += 20
            }
        }
        return result
    }

    private fun tieBreakAccidental(pitch: Pitch, key: KeySignature): Int = when {
        key.fifths > 0 -> when {
            pitch.chromaticOffset > 0 -> 0
            pitch.chromaticOffset == 0 -> 1
            else -> 2
        }
        key.fifths < 0 -> when {
            pitch.chromaticOffset < 0 -> 0
            pitch.chromaticOffset == 0 -> 1
            else -> 2
        }
        else -> abs(pitch.chromaticOffset)
    }

    private fun floorDiv(a: Int, b: Int): Int {
        val q = a / b
        return if ((a xor b) < 0 && q * b != a) q - 1 else q
    }
}
