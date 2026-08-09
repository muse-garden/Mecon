package com.mecon.core.engine.edit

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.storage.StorageScore
import kotlin.test.Test
import kotlin.test.assertEquals

class DirectNoteEditingTest {
    private fun score(): RuntimeScore = RuntimeScore.fromStorage(
        StorageScore.create(
            StorageScore.CreationOptions(
                title = "Direct",
                timeSignature = TimeSignature.COMMON,
                keySignature = KeySignature.C_MAJOR,
            )
        )
    )

    private fun RuntimeScore.voiceId(): TrackId = voiceTracks.keys.first()
    private fun RuntimeScore.pitchId(): TrackId = pitchTracks.keys.first()

    private fun RuntimeScore.addEvent(
        id: String,
        onset: Fraction,
        duration: Duration,
        pitches: List<Pitch>,
    ): RuntimeScore {
        val time = TimeCode.of(1, onset)
        val pitch = RuntimePitchEvent(EventId("pitch-$id"), time, pitches)
        val event = RuntimeVoiceEvent(EventId(id), time, pitch, duration)
        return addPitchEvent(pitchId(), pitch).addVoiceEvent(voiceId(), event)
    }

    @Test
    fun exactPitchEditMovesOnlyTheDraggedChordTone() {
        val base = score().addEvent(
            id = "chord",
            onset = Fraction.ZERO,
            duration = Duration.QUARTER,
            pitches = listOf(Pitch.C4, Pitch.E4),
        )

        val result = NoteEditEngine.editExactPitches(
            base,
            listOf(
                NoteEditEngine.ExactPitchEdit(
                    voiceTrackId = base.voiceId(),
                    eventId = EventId("chord"),
                    pitchIndex = 1,
                    pitch = Pitch.fromMidi(66),
                )
            ),
        )!!

        val event = result.score.voiceTracks.getValue(base.voiceId()).events
            .first { it.id == EventId("chord") }
        assertEquals(listOf(Pitch.C4, Pitch.fromMidi(66)), event.pitches)
        assertEquals(setOf(1), result.movedEvents.single().pitchIndices)
    }

    @Test
    fun movingSharedEndResizesWholeChordAndFollowingNoteTogether() {
        val base = score()
            .addEvent(
                id = "left",
                onset = Fraction.ZERO,
                duration = Duration.QUARTER,
                pitches = listOf(Pitch.C4, Pitch.E4),
            )
            .addEvent(
                id = "right",
                onset = Fraction.QUARTER,
                duration = Duration.QUARTER,
                pitches = listOf(Pitch.D4),
            )

        val result = NoteEditEngine.editRangeBoundary(
            base,
            NoteEditEngine.RangeBoundaryEdit(
                voiceTrackId = base.voiceId(),
                eventId = EventId("left"),
                boundary = NoteEditEngine.RangeBoundary.END,
                target = TimeCode.of(1, Fraction(3, 8)),
                minimumLength = Fraction.EIGHTH,
            ),
        )!!
        val pitched = result.score.voiceTracks.getValue(base.voiceId()).events
            .filterNot { it.isRest }
            .sortedBy { it.onset }

        assertEquals(Fraction.ZERO, pitched[0].onset.beat)
        assertEquals(Fraction(3, 8), pitched[0].duration.toFraction())
        assertEquals(listOf(Pitch.C4, Pitch.E4), pitched[0].pitches)
        assertEquals(Fraction(3, 8), pitched[1].onset.beat)
        assertEquals(Fraction.EIGHTH, pitched[1].duration.toFraction())
        assertEquals(listOf(Pitch.D4), pitched[1].pitches)
    }
}
