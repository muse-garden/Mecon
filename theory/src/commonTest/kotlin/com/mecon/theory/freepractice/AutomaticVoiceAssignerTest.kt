package com.mecon.theory.freepractice

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutomaticVoiceAssignerTest {
    private val voices = listOf(
        voice("s", 0, 60, 84),
        voice("a", 1, 55, 77),
        voice("t", 2, 48, 69),
        voice("b", 3, 36, 60),
    )

    @Test
    fun fullSonorityIsAssignedHighToLowWithoutCrossing() {
        val result = AutomaticVoiceAssigner.assign(
            voices,
            listOf(72, 67, 60, 48).mapIndexed { index, midi ->
                note("n$index", Fraction.ZERO, midi, voices.last().id)
            },
        )

        assertTrue(result.unassignedEventIds.isEmpty())
        assertEquals(
            voices.map { it.id },
            (0..3).map { result.voiceByEventId.getValue(EventId("n$it")) },
        )
    }

    @Test
    fun sparseSonorityMinimizesMotionAcrossOrderedVoiceSubsets() {
        val first = listOf(72, 67, 60, 48).mapIndexed { index, midi ->
            note("first-$index", Fraction.ZERO, midi, voices[index].id)
        }
        val second = listOf(
            note("next-high", Fraction.QUARTER, 69, voices[0].id),
            note("next-low", Fraction.QUARTER, 62, voices[2].id),
        )

        val result = AutomaticVoiceAssigner.assign(voices, first + second)

        assertEquals(voices[1].id, result.voiceByEventId[EventId("next-high")])
        assertEquals(voices[2].id, result.voiceByEventId[EventId("next-low")])
    }

    @Test
    fun manualAssignmentRemainsFixedWhileAutomaticNotesRouteAroundIt() {
        val manual = note(
            id = "manual",
            onset = Fraction.ZERO,
            midi = 65,
            voiceId = voices[1].id,
            source = VoiceAssignmentSource.MANUAL,
        )
        val automatic = note("automatic", Fraction.ZERO, 72, voices[3].id)

        val result = AutomaticVoiceAssigner.assign(voices, listOf(manual, automatic))

        assertEquals(voices[1].id, result.voiceByEventId[manual.eventId])
        assertEquals(voices[0].id, result.voiceByEventId[automatic.eventId])
    }

    private fun voice(id: String, order: Int, low: Int, high: Int) =
        WorkspaceVoiceSpec(
            id = TrackId(id),
            order = order,
            boundary = when (order) {
                0 -> WorkspaceVoiceBoundary.UPPER_OUTER
                3 -> WorkspaceVoiceBoundary.LOWER_OUTER
                else -> WorkspaceVoiceBoundary.INNER
            },
            lowest = Pitch.fromMidi(low),
            highest = Pitch.fromMidi(high),
        )

    private fun note(
        id: String,
        onset: Fraction,
        midi: Int,
        voiceId: TrackId,
        source: VoiceAssignmentSource = VoiceAssignmentSource.AUTOMATIC,
    ) = VoiceAssignmentNote(
        eventId = EventId(id),
        onset = onset,
        duration = Fraction.QUARTER,
        pitch = Pitch.fromMidi(midi),
        currentVoiceId = voiceId,
        source = source,
    )
}
