package com.mecon.core.engine.edit

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.StoragePitchEvent
import com.mecon.api.storage.events.StorageVoiceEvent
import com.mecon.api.storage.tracks.StoragePitchTrack
import com.mecon.api.storage.tracks.StorageVoiceTrack
import com.mecon.api.runtime.RuntimeScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimeSignatureEditEngineTest {

    private fun voiceEventsAt(score: RuntimeScore, trackId: TrackId): List<TimeCode> =
        score.voiceTracks.getValue(trackId).events.map { it.onset }.sorted()

    /** Single-staff score; measure 1 filled with [quarters] quarter notes, rest empty. */
    private fun scoreWithQuarters(quarters: Int, measureCount: Int): Triple<StorageScore, TrackId, TrackId> {
        var score = StorageScore.create(StorageScore.CreationOptions(measureCount = measureCount))
        val pitchTrackId = score.pitchTracks.keys.first()
        val voiceTrackId = score.voiceTracks.keys.first()
        for (i in 0 until quarters) {
            val onset = TimeCode.of(1, Fraction(i, 4))
            val pe = StoragePitchEvent.single(onset, Pitch.C4)
            score = score.addPitchEvent(pitchTrackId, pe)
                .addVoiceEvent(voiceTrackId, StorageVoiceEvent.create(onset, pe.id, Duration.QUARTER))
        }
        return Triple(score, pitchTrackId, voiceTrackId)
    }

    @Test
    fun fourFourToTwoFourSplitsMeasure() {
        val (score, pitchTrackId, voiceTrackId) = scoreWithQuarters(quarters = 4, measureCount = 1)

        val result = assertNotNull(
            TimeSignatureEditEngine.setTimeSignature(RuntimeScore.fromStorage(score), 1, TimeSignature.TWO_FOUR)
        )

        // Four quarters re-bar into two 2/4 measures.
        assertEquals(
            listOf(
                TimeCode.of(1, Fraction.ZERO),
                TimeCode.of(1, Fraction(1, 4)),
                TimeCode.of(2, Fraction.ZERO),
                TimeCode.of(2, Fraction(1, 4)),
            ),
            voiceEventsAt(result.score, voiceTrackId),
        )
        // Pitch onsets stay in sync with their voice events.
        assertEquals(
            voiceEventsAt(result.score, voiceTrackId),
            result.score.pitchTracks.getValue(pitchTrackId).events.map { it.onset }.sorted(),
        )
        assertEquals(2, result.score.measures.size)
        assertEquals(TimeSignature.TWO_FOUR, result.score.getTimeSignatureAt(1))
    }

    @Test
    fun emptyMeasureStaysEmptyAndScoreExpands() {
        // Measure 1 full (4 quarters), measure 2 empty.
        val (score, _, voiceTrackId) = scoreWithQuarters(quarters = 4, measureCount = 2)

        val result = assertNotNull(
            TimeSignatureEditEngine.setTimeSignature(RuntimeScore.fromStorage(score), 1, TimeSignature.TWO_FOUR)
        )

        // Measure 1 → measures 1 & 2; original empty measure 2 → measure 3 (still empty).
        assertEquals(3, result.score.measures.size)
        assertTrue(voiceEventsAt(result.score, voiceTrackId).all { it.measure in 1..2 })
        assertTrue(result.score.getAllVoiceEvents().none { it.onset.measure == 3 })
    }

    @Test
    fun multiVoiceMeasuresStayAligned() {
        var (score, _, voice1) = scoreWithQuarters(quarters = 4, measureCount = 1)
        // Add a second voice on the same staff, also 4 quarters in measure 1.
        val pitch2 = StoragePitchTrack.create("p2")
        val voice2 = StorageVoiceTrack.create("v2", voiceNumber = 2, pitchTrackId = pitch2.id)
        var pt2 = pitch2
        var vt2 = voice2
        for (i in 0 until 4) {
            val onset = TimeCode.of(1, Fraction(i, 4))
            val pe = StoragePitchEvent.single(onset, Pitch.E4)
            pt2 = pt2.addEvent(pe)
            vt2 = vt2.addEvent(StorageVoiceEvent.create(onset, pe.id, Duration.QUARTER))
        }
        val staffId = score.staffTracks.keys.first()
        score = score.copy(
            pitchTracks = score.pitchTracks + (pt2.id to pt2),
            voiceTracks = score.voiceTracks + (vt2.id to vt2),
            staffTracks = score.staffTracks + (staffId to score.staffTracks.getValue(staffId)
                .copy(voiceTrackIds = score.staffTracks.getValue(staffId).voiceTrackIds + vt2.id)),
        )

        val result = assertNotNull(
            TimeSignatureEditEngine.setTimeSignature(RuntimeScore.fromStorage(score), 1, TimeSignature.TWO_FOUR)
        )

        assertEquals(2, result.score.measures.size)
        // Both voices re-bar identically.
        assertEquals(voiceEventsAt(result.score, voice1), voiceEventsAt(result.score, vt2.id))
    }

    @Test
    fun noOpWhenMeterUnchanged() {
        // A freshly created score's meter is TimeSignature.COMMON (4/4 with the C symbol).
        val (score, _, _) = scoreWithQuarters(quarters = 4, measureCount = 1)
        assertNull(TimeSignatureEditEngine.setTimeSignature(RuntimeScore.fromStorage(score), 1, TimeSignature.COMMON))
    }

    @Test
    fun beatGroupsOnlyChangeDoesNotRebar() {
        // 6/8 with 6 eighth notes in one measure.
        var score = StorageScore.create(StorageScore.CreationOptions(timeSignature = TimeSignature.SIX_EIGHT, measureCount = 1))
        val pitchTrackId = score.pitchTracks.keys.first()
        val voiceTrackId = score.voiceTracks.keys.first()
        for (i in 0 until 6) {
            val onset = TimeCode.of(1, Fraction(i, 8))
            val pe = StoragePitchEvent.single(onset, Pitch.C4)
            score = score.addPitchEvent(pitchTrackId, pe)
                .addVoiceEvent(voiceTrackId, StorageVoiceEvent.create(onset, pe.id, Duration.EIGHTH))
        }
        val before = score.voiceTracks.getValue(voiceTrackId).events.map { it.onset }.sorted()

        // Same 6/8 but grouped 2+2+2 — same measure duration, so no re-bar.
        val regrouped = TimeSignature(6, 8, beatGroups = listOf(2, 2, 2))
        val result = assertNotNull(TimeSignatureEditEngine.setTimeSignature(RuntimeScore.fromStorage(score), 1, regrouped))

        assertEquals(before, voiceEventsAt(result.score, voiceTrackId))
        assertEquals(1, result.score.measures.size)
        assertEquals(regrouped, result.score.getTimeSignatureAt(1))
    }
}
