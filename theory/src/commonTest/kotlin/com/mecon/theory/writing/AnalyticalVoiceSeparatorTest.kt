package com.mecon.theory.writing

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyticalVoiceSeparatorTest {
    @Test
    fun chordalSourceEventBecomesIndependentMonodicVoices() {
        val eventId = EventId("chord")
        val notes = listOf(72, 67, 60).mapIndexed { index, midi ->
            AnalyticalNoteSpan(
                source = SourceNoteheadId(eventId, index),
                onset = Fraction.ZERO,
                duration = Fraction.QUARTER,
                pitch = Pitch.fromMidi(midi),
            )
        }

        val separated = AnalyticalVoiceSeparator.separate(notes, voiceCount = 3)

        assertTrue(separated.unassigned.isEmpty())
        assertEquals(
            listOf(0, 1, 2),
            notes.map { separated.voiceByNotehead.getValue(it.source) },
        )
    }

    @Test
    fun reportsOnlyCapacityOverflowWithoutChangingSourceNotes() {
        val notes = (0 until 4).map { index ->
            AnalyticalNoteSpan(
                source = SourceNoteheadId(EventId("event-$index"), 0),
                onset = Fraction.ZERO,
                duration = Fraction.QUARTER,
                pitch = Pitch.fromMidi(72 - index * 3),
            )
        }

        val separated = AnalyticalVoiceSeparator.separate(notes, voiceCount = 3)

        assertEquals(notes.mapTo(linkedSetOf()) { it.source }, separated.unassigned)
    }
}
