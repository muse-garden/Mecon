package com.mecon.input

import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.NoteName
import com.mecon.api.primitive.Pitch
import kotlin.math.abs

enum class InputPitchMode {
    ABSOLUTE,
    RELATIVE_TO_KEY,
}

/**
 * Semantic key positions instead of OS characters. A platform adapter maps physical keys onto this
 * enum, allowing the layout to be rebound without changing the musical mapping.
 */
enum class ComputerNoteKey {
    Q, W, E, R, T, Y, U, I, O, P, LEFT_BRACKET,
    A, S, D, F, G, H, J, K, L, SEMICOLON, APOSTROPHE,
    Z, X, C, V, B, N, M, COMMA, PERIOD, SLASH,
}

data class KeyboardDegree(
    val degreeOffset: Int,
    val chromaticDelta: Int,
    val spellingHint: SpellingHint,
)

data class KeyboardPitchRange(val low: Pitch, val high: Pitch)

/**
 * Default staggered mapping centred on a natural-row key. With the default G anchor:
 * - naturals A..' are offsets -4..+6;
 * - sharps Q..[ are offsets -5..+5 (Y=sharp 1, [=sharp 6);
 * - flats Z../ are offsets -3..+6 (V=flat 1, /=flat 7).
 */
data class ComputerKeyboardLayout(
    val anchorKey: ComputerNoteKey = ComputerNoteKey.G,
) {
    init {
        require(anchorKey in NATURAL_OFFSETS) { "The centre key must be on the natural row" }
    }

    private val anchorOffset: Int = NATURAL_OFFSETS.getValue(anchorKey)

    fun degreeFor(key: ComputerNoteKey): KeyboardDegree? {
        NATURAL_OFFSETS[key]?.let {
            return KeyboardDegree(it - anchorOffset, 0, SpellingHint.NATURAL)
        }
        SHARP_OFFSETS[key]?.let {
            return KeyboardDegree(it - anchorOffset, 1, SpellingHint.RAISE)
        }
        FLAT_OFFSETS[key]?.let {
            return KeyboardDegree(it - anchorOffset, -1, SpellingHint.LOWER)
        }
        return null
    }

    fun resolve(
        key: ComputerNoteKey,
        mode: InputPitchMode,
        keySignature: KeySignature,
        centerOctave: Int,
        registerOffset: Int = 0,
    ): Pitch? {
        val degree = degreeFor(key) ?: return null
        val octave = centerOctave + registerOffset
        val base = when (mode) {
            InputPitchMode.ABSOLUTE -> {
                val steps = (octave - 4) * 7 + degree.degreeOffset
                Pitch(steps, degree.chromaticDelta)
            }
            InputPitchMode.RELATIVE_TO_KEY -> {
                relativePitch(keySignature, octave, degree.degreeOffset, degree.chromaticDelta)
            }
        }
        return base.takeIf { it.midiNumber in 0..127 }
    }

    fun range(
        mode: InputPitchMode,
        keySignature: KeySignature,
        centerOctave: Int,
        registerOffset: Int = 0,
    ): KeyboardPitchRange? {
        val pitches = ComputerNoteKey.entries.mapNotNull {
            resolve(it, mode, keySignature, centerOctave, registerOffset)
        }
        val low = pitches.minByOrNull { it.midiNumber } ?: return null
        val high = pitches.maxByOrNull { it.midiNumber } ?: return null
        return KeyboardPitchRange(low, high)
    }

    companion object {
        private val naturalKeys = listOf(
            ComputerNoteKey.A, ComputerNoteKey.S, ComputerNoteKey.D, ComputerNoteKey.F,
            ComputerNoteKey.G, ComputerNoteKey.H, ComputerNoteKey.J, ComputerNoteKey.K,
            ComputerNoteKey.L, ComputerNoteKey.SEMICOLON, ComputerNoteKey.APOSTROPHE,
        )
        private val sharpKeys = listOf(
            ComputerNoteKey.Q, ComputerNoteKey.W, ComputerNoteKey.E, ComputerNoteKey.R,
            ComputerNoteKey.T, ComputerNoteKey.Y, ComputerNoteKey.U, ComputerNoteKey.I,
            ComputerNoteKey.O, ComputerNoteKey.P, ComputerNoteKey.LEFT_BRACKET,
        )
        private val flatKeys = listOf(
            ComputerNoteKey.Z, ComputerNoteKey.X, ComputerNoteKey.C, ComputerNoteKey.V,
            ComputerNoteKey.B, ComputerNoteKey.N, ComputerNoteKey.M, ComputerNoteKey.COMMA,
            ComputerNoteKey.PERIOD, ComputerNoteKey.SLASH,
        )

        private val NATURAL_OFFSETS = naturalKeys.zip(-4..6).toMap()
        private val SHARP_OFFSETS = sharpKeys.zip(-5..5).toMap()
        private val FLAT_OFFSETS = flatKeys.zip(-3..6).toMap()

        private fun relativePitch(
            key: KeySignature,
            centerOctave: Int,
            degreeOffset: Int,
            chromaticDelta: Int,
        ): Pitch {
            val tonicName = tonicNoteName(key)
            val tonicSteps = (centerOctave - 4) * 7 + tonicName.ordinal
            val targetSteps = tonicSteps + degreeOffset
            val degreeIndex = degreeOffset.mod(7)
            val targetPitchClass = key.scale().getOrNull(degreeIndex)?.value
                ?: key.root.value
            val natural = Pitch(targetSteps)
            val pcDelta = signedPitchClassDelta(natural.pitchClass.value, targetPitchClass)
            return Pitch(targetSteps, pcDelta + chromaticDelta)
        }

        private fun tonicNoteName(key: KeySignature): NoteName {
            val exact = NoteName.entries.filter { name ->
                val pc = (name.toSemitone() + key.accidentalFor(name).offset).mod(12)
                pc == key.root.value
            }
            if (exact.isNotEmpty()) return exact.first()

            return NoteName.entries.minWith(
                compareBy<NoteName> {
                    abs(signedPitchClassDelta(it.toSemitone(), key.root.value))
                }.thenBy {
                    when {
                        key.fifths > 0 -> -it.ordinal
                        key.fifths < 0 -> it.ordinal
                        else -> it.ordinal
                    }
                }
            )
        }

        private fun signedPitchClassDelta(from: Int, to: Int): Int {
            val up = (to - from).mod(12)
            return if (up <= 6) up else up - 12
        }
    }
}
