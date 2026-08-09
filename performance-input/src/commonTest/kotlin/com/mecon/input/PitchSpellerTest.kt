package com.mecon.input

import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Pitch
import kotlin.test.Test
import kotlin.test.assertEquals

class PitchSpellerTest {
    @Test
    fun `sharp key and flat key choose opposite enharmonics`() {
        assertEquals(
            Pitch.fromName("F#4"),
            PitchSpeller.spell(66, PitchSpellingContext(KeySignature.G_MAJOR)),
        )
        assertEquals(
            Pitch.fromName("Gb4"),
            PitchSpeller.spell(66, PitchSpellingContext(KeySignature.majorByFifths(-6))),
        )
    }

    @Test
    fun `sounding midi input inverts staff transposition`() {
        val pitch = MidiPitchResolver.resolveWrittenPitch(
            inputMidi = 60,
            keySignature = KeySignature.D_MAJOR,
            staffTranspositionSemitones = -2,
            settings = MidiPitchSettings(
                mode = InputPitchMode.ABSOLUTE,
                semantics = MidiPitchSemantics.SOUNDING,
            ),
        )
        assertEquals(60, pitch?.midiNumber?.plus(-2))
    }
}
