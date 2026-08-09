package com.mecon.core.engine.edit

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.orderedStaffs
import com.mecon.api.storage.StaffLayoutPreset
import com.mecon.api.storage.StorageScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class VoiceMoveAcrossStaffTest {
    @Test
    fun movesOneChordHeadToAnotherStaffWithoutMovingTheOtherHead() {
        val empty = RuntimeScore.fromStorage(
            StorageScore.create(
                StorageScore.CreationOptions(
                    layout = StaffLayoutPreset.PIANO_GRAND,
                    measureCount = 1,
                )
            )
        )
        val (upper, lower) = empty.orderedStaffs()
        val upperVoice = upper.voiceTracks.single()
        val inserted = assertNotNull(
            NoteEditEngine.insertChord(
                empty,
                NoteEditEngine.ChordInsertion(
                    voiceTrackId = upperVoice.id,
                    start = TimeCode.ofMeasure(1),
                    duration = Duration(DurationBase.QUARTER),
                    pitches = listOf(Pitch.fromMidi(60), Pitch.fromMidi(67)),
                ),
            )
        )
        val eventId = assertNotNull(inserted.insertedEventId)

        val moved = assertNotNull(
            NoteEditEngine.moveVoices(
                inserted.score,
                listOf(
                    NoteEditEngine.VoiceMoveTarget(
                        voiceTrackId = upperVoice.id,
                        eventId = eventId,
                        targetVoiceNumber = lower.voiceTracks.single().voiceNumber,
                        pitchIndices = setOf(0),
                        targetStaffId = lower.id,
                    )
                ),
            )
        )

        val upperPitches = moved.score.getVoiceTrack(upperVoice.id)
            ?.events?.toList()?.flatMap { it.pitches }.orEmpty()
        val lowerPitches = moved.score.getStaffTrack(lower.id)
            ?.voiceTracks?.flatMap { voice -> voice.events.toList().flatMap { it.pitches } }.orEmpty()
        assertEquals(listOf(67), upperPitches.map(Pitch::midiNumber))
        assertEquals(listOf(60), lowerPitches.map(Pitch::midiNumber))
    }
}
