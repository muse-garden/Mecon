package com.mecon.core.engine.edit

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.StoragePitchEvent
import com.mecon.api.storage.events.StorageVoiceEvent
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.tracks.StoragePageBreak
import com.mecon.api.storage.tracks.StorageSystemBreak
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MeasureEditEngineTest {
    private fun scoreWithNotes(): RuntimeScore {
        val base = StorageScore.create(StorageScore.CreationOptions(measureCount = 4))
        val pitchTrackId = base.pitchTracks.keys.first()
        val voiceTrackId = base.voiceTracks.keys.first()
        val pitchOne = StoragePitchEvent(EventId("p1"), TimeCode.of(1, Fraction.ZERO), listOf(Pitch.C4))
        val pitchThree = StoragePitchEvent(EventId("p3"), TimeCode.of(3, Fraction.ZERO), listOf(Pitch.D4))
        val storage = base.copy(
            pitchTracks = base.pitchTracks.mapValues { (_, track) -> track.copy(events = listOf(pitchOne, pitchThree)) },
            voiceTracks = base.voiceTracks.mapValues { (_, track) -> track.copy(events = listOf(
                StorageVoiceEvent(EventId("v1"), TimeCode.of(1, Fraction.ZERO), pitchOne.id, Duration.QUARTER),
                StorageVoiceEvent(EventId("v3"), TimeCode.of(3, Fraction.ZERO), pitchThree.id, Duration.QUARTER),
            )) },
        )
        return RuntimeScore.fromStorage(storage)
    }

    @Test
    fun insertAfterBarlineShiftsTailButKeepsEarlierContent() {
        val result = MeasureEditEngine.insertAfter(scoreWithNotes(), afterMeasure = 1, count = 2)!!
        assertEquals((1..6).toList(), result.measures.map { it.value.number })
        assertEquals(listOf(1, 5), result.voiceTracks.values.single().events.map { it.onset.measure })
        assertEquals(listOf(1, 5), result.pitchTracks.values.single().events.map { it.onset.measure })
    }

    @Test
    fun deleteMeasureRemovesItsNotesAndRenumbersTail() {
        val result = MeasureEditEngine.delete(scoreWithNotes(), setOf(3))!!
        assertEquals((1..3).toList(), result.measures.map { it.value.number })
        assertEquals(listOf(1), result.voiceTracks.values.single().events.map { it.onset.measure })
        assertEquals(listOf(1), result.pitchTracks.values.single().events.map { it.onset.measure })
    }

    @Test
    fun deleteCannotRemoveEveryMeasure() {
        assertNull(MeasureEditEngine.delete(scoreWithNotes(), setOf(1, 2, 3, 4)))
    }

    private fun scoreWithBreak(beforeMeasure: Int, page: Boolean = false): RuntimeScore {
        val base = StorageScore.create(StorageScore.CreationOptions(measureCount = 6))
        val onset = TimeCode.of(beforeMeasure, Fraction.ZERO)
        return RuntimeScore.fromStorage(base.copy(
            globalTrack = base.globalTrack.copy(
                events = listOf(if (page) StoragePageBreak(onset) else StorageSystemBreak(onset))
            )
        ))
    }

    @Test
    fun deletingAcrossBreakKeepsBoundaryBetweenSurvivingSides() {
        val result = MeasureEditEngine.delete(scoreWithBreak(4, page = true), setOf(3, 4))!!
        assertEquals(setOf(3), result.forcedPageBreaks)
        assertEquals(listOf(3), result.globalTrack.events.filterIsInstance<StoragePageBreak>().map { it.onset.measure })
    }

    @Test
    fun insertAtPreviousSystemEndKeepsNewMeasuresBeforeBreak() {
        val result = MeasureEditEngine.insertAfter(scoreWithBreak(4), 3, 2)!!
        assertEquals(setOf(6), result.forcedSystemBreaks)
    }

    @Test
    fun insertAtNextSystemStartKeepsNewMeasuresAfterBreak() {
        val result = MeasureEditEngine.insertAfter(
            scoreWithBreak(4), 3, 2, MeasureEditEngine.BoundaryInsertion.AFTER_BREAK
        )!!
        assertEquals(setOf(4), result.forcedSystemBreaks)
    }
}
