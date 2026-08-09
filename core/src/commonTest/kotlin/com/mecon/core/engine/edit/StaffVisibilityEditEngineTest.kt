package com.mecon.core.engine.edit

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.StoragePitchEvent
import com.mecon.api.storage.events.StorageVoiceEvent
import com.mecon.api.storage.tracks.MeasureRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StaffVisibilityEditEngineTest {
    private fun emptyScore() = RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions(measureCount = 6)))

    private fun staffId(score: RuntimeScore): TrackId = score.staffTracks.keys.first()

    /** A single-staff score with a note in measure 2. */
    private fun scoreWithNoteInM2(): RuntimeScore {
        val base = StorageScore.create(StorageScore.CreationOptions(measureCount = 6))
        val pitch = StoragePitchEvent(EventId("p2"), TimeCode.of(2, Fraction.ZERO), listOf(Pitch.C4))
        val storage = base.copy(
            pitchTracks = base.pitchTracks.mapValues { (_, t) -> t.copy(events = listOf(pitch)) },
            voiceTracks = base.voiceTracks.mapValues { (_, t) -> t.copy(events = listOf(
                StorageVoiceEvent(EventId("v2"), TimeCode.of(2, Fraction.ZERO), pitch.id, Duration.QUARTER)
            )) },
        )
        return RuntimeScore.fromStorage(storage)
    }

    private fun hidden(score: RuntimeScore, id: TrackId) = score.staffTracks.getValue(id).hiddenRanges

    @Test
    fun hideAddsANormalizedRange() {
        val score = emptyScore()
        val id = staffId(score)
        val result = StaffVisibilityEditEngine.hide(score, setOf(id), MeasureRange(2, 4))!!
        assertEquals(listOf(MeasureRange(2, 4)), hidden(result, id))
    }

    @Test
    fun hideMergesAdjacentRanges() {
        val score = emptyScore()
        val id = staffId(score)
        val step1 = StaffVisibilityEditEngine.hide(score, setOf(id), MeasureRange(2, 3))!!
        val step2 = StaffVisibilityEditEngine.hide(step1, setOf(id), MeasureRange(4, 5))!!
        // 2..3 and 4..5 are adjacent → merged into a single 2..5.
        assertEquals(listOf(MeasureRange(2, 5)), hidden(step2, id))
    }

    @Test
    fun showSplitsAHiddenRange() {
        val score = emptyScore()
        val id = staffId(score)
        val hiddenAll = StaffVisibilityEditEngine.hide(score, setOf(id), MeasureRange(1, 6))!!
        val result = StaffVisibilityEditEngine.show(hiddenAll, setOf(id), MeasureRange(3, 4))!!
        assertEquals(listOf(MeasureRange(1, 2), MeasureRange(5, 6)), hidden(result, id))
    }

    @Test
    fun hideIsClampedToScoreMeasureCount() {
        val score = emptyScore()
        val id = staffId(score)
        val result = StaffVisibilityEditEngine.hide(score, setOf(id), MeasureRange(4, 100))!!
        assertEquals(listOf(MeasureRange(4, 6)), hidden(result, id))
    }

    @Test
    fun hideIsBlockedWhenRegionHasNotes() {
        val score = scoreWithNoteInM2()
        val id = staffId(score)
        assertTrue(StaffVisibilityEditEngine.hasNotesInRegion(score, setOf(id), MeasureRange(1, 3)))
        assertNull(StaffVisibilityEditEngine.hide(score, setOf(id), MeasureRange(1, 3)))
    }

    @Test
    fun hideEmptyMeasuresSucceedsEvenWhenOtherMeasuresHaveNotes() {
        val score = scoreWithNoteInM2()
        val id = staffId(score)
        val result = StaffVisibilityEditEngine.hide(score, setOf(id), MeasureRange(3, 5))!!
        assertEquals(listOf(MeasureRange(3, 5)), hidden(result, id))
    }

    @Test
    fun showNeverBlocksOnNotes() {
        val score = scoreWithNoteInM2()
        val id = staffId(score)
        // Nothing hidden → show is a no-op → null.
        assertNull(StaffVisibilityEditEngine.show(score, setOf(id), MeasureRange(2, 2)))
    }

    @Test
    fun hideCellsHidesPerStaffMeasures() {
        val score = emptyScore()
        val id = staffId(score)
        val result = StaffVisibilityEditEngine.hideCells(score, mapOf(id to setOf(1, 2, 5)))!!
        assertEquals(listOf(MeasureRange(1, 2), MeasureRange(5, 5)), hidden(result, id))
    }

    @Test
    fun noOpReturnsNull() {
        val score = emptyScore()
        val id = staffId(score)
        val hiddenAll = StaffVisibilityEditEngine.hide(score, setOf(id), MeasureRange(2, 4))!!
        // Hiding an already-hidden range changes nothing.
        assertNull(StaffVisibilityEditEngine.hide(hiddenAll, setOf(id), MeasureRange(2, 4)))
    }
}
