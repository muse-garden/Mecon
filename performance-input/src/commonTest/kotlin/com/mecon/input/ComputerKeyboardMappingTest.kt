package com.mecon.input

import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Pitch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ComputerKeyboardMappingTest {
    private val layout = ComputerKeyboardLayout()

    @Test
    fun `confirmed staggered range reaches sharp six and flat seven`() {
        assertEquals(5, layout.degreeFor(ComputerNoteKey.LEFT_BRACKET)?.degreeOffset)
        assertEquals(1, layout.degreeFor(ComputerNoteKey.LEFT_BRACKET)?.chromaticDelta)
        assertEquals(6, layout.degreeFor(ComputerNoteKey.SLASH)?.degreeOffset)
        assertEquals(-1, layout.degreeFor(ComputerNoteKey.SLASH)?.chromaticDelta)
        assertEquals(0, layout.degreeFor(ComputerNoteKey.Y)?.degreeOffset)
        assertEquals(0, layout.degreeFor(ComputerNoteKey.V)?.degreeOffset)
    }

    @Test
    fun `absolute centre is C4 and symbol rows preserve explicit spelling`() {
        assertEquals(Pitch.fromName("C4"), resolve(ComputerNoteKey.G, InputPitchMode.ABSOLUTE))
        assertEquals(Pitch.fromName("C#4"), resolve(ComputerNoteKey.Y, InputPitchMode.ABSOLUTE))
        assertEquals(Pitch.fromName("Cb4"), resolve(ComputerNoteKey.V, InputPitchMode.ABSOLUTE))
        assertEquals(Pitch.fromName("A#4"), resolve(ComputerNoteKey.LEFT_BRACKET, InputPitchMode.ABSOLUTE))
        assertEquals(Pitch.fromName("Bb4"), resolve(ComputerNoteKey.SLASH, InputPitchMode.ABSOLUTE))
    }

    @Test
    fun `relative centre follows tonic spelling and scale`() {
        val f = layout.resolve(
            ComputerNoteKey.G, InputPitchMode.RELATIVE_TO_KEY,
            KeySignature.F_MAJOR, centerOctave = 4,
        )
        val bFlat = layout.resolve(
            ComputerNoteKey.K, InputPitchMode.RELATIVE_TO_KEY,
            KeySignature.F_MAJOR, centerOctave = 4,
        )
        assertEquals(Pitch.fromName("F4"), f)
        assertEquals(Pitch.fromName("Bb4"), bFlat)
    }

    @Test
    fun `relative centre updates across sharp flat and minor keys`() {
        fun center(key: KeySignature) = layout.resolve(
            ComputerNoteKey.G,
            InputPitchMode.RELATIVE_TO_KEY,
            key,
            centerOctave = 4,
        )

        assertEquals(Pitch.fromName("F#4"), center(KeySignature.majorByFifths(6)))
        assertEquals(Pitch.fromName("Eb4"), center(KeySignature.majorByFifths(-3)))
        assertEquals(Pitch.fromName("E4"), center(KeySignature.E_MINOR))
        assertEquals(Pitch.fromName("C4"), center(KeySignature.C_MAJOR))
    }

    @Test
    fun `custom natural anchor shifts every row together`() {
        val fAnchor = ComputerKeyboardLayout(ComputerNoteKey.F)
        assertEquals(0, fAnchor.degreeFor(ComputerNoteKey.F)?.degreeOffset)
        assertEquals(1, fAnchor.degreeFor(ComputerNoteKey.G)?.degreeOffset)
        assertEquals(1, fAnchor.degreeFor(ComputerNoteKey.T)?.chromaticDelta)
    }

    private fun resolve(key: ComputerNoteKey, mode: InputPitchMode): Pitch =
        assertNotNull(layout.resolve(key, mode, KeySignature.C_MAJOR, centerOctave = 4))
}
