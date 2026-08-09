package com.mecon.input

import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Pitch

enum class MidiPitchSemantics {
    /** Normal MIDI keyboard: the emitted note is the pitch that should sound. */
    SOUNDING,
    /** A specialised controller already emits the written note for the target part. */
    WRITTEN,
}

data class MidiPitchSettings(
    val mode: InputPitchMode = InputPitchMode.RELATIVE_TO_KEY,
    val semantics: MidiPitchSemantics = MidiPitchSemantics.SOUNDING,
    val deviceCenterMidi: Int = 60,
    val centerOctave: Int = 4,
    val registerOffset: Int = 0,
)

object MidiPitchResolver {
    fun resolveWrittenPitch(
        inputMidi: Int,
        keySignature: KeySignature,
        staffTranspositionSemitones: Int,
        settings: MidiPitchSettings,
        context: PitchSpellingContext = PitchSpellingContext(keySignature),
    ): Pitch? {
        if (inputMidi !in 0..127) return null
        val writtenMidi = when (settings.mode) {
            InputPitchMode.ABSOLUTE -> when (settings.semantics) {
                MidiPitchSemantics.SOUNDING -> inputMidi - staffTranspositionSemitones
                MidiPitchSemantics.WRITTEN -> inputMidi
            }
            InputPitchMode.RELATIVE_TO_KEY -> {
                val tonic = ComputerKeyboardLayout().resolve(
                    key = ComputerNoteKey.G,
                    mode = InputPitchMode.RELATIVE_TO_KEY,
                    keySignature = keySignature,
                    centerOctave = settings.centerOctave,
                    registerOffset = settings.registerOffset,
                ) ?: return null
                val writtenTarget = tonic.midiNumber + (inputMidi - settings.deviceCenterMidi)
                when (settings.semantics) {
                    // Relative mapping chooses the written tonic. Its sounding anchor includes the
                    // staff transposition, which cancels when converting the played result back.
                    MidiPitchSemantics.SOUNDING ->
                        (writtenTarget + staffTranspositionSemitones) - staffTranspositionSemitones
                    MidiPitchSemantics.WRITTEN -> writtenTarget
                }
            }
        }
        return PitchSpeller.spell(writtenMidi, context)
    }
}
