package com.mecon.audio.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoteAuditionTest {
    @Test
    fun filtersOutOfRangePitchesAtTheAudioRequestBoundary() {
        val request = NoteAudition(midiNumbers = listOf(-1, 0, 60, 127, 128))

        assertEquals(listOf(0, 60, 127), request.midiNumbers)
    }

    @Test
    fun allOutOfRangePitchesBecomeASilentRequest() {
        val request = NoteAudition(midiNumbers = listOf(-12, 140))

        assertTrue(request.midiNumbers.isEmpty())
    }
}
