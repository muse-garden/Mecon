package com.mecon.core.engine.edit

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.orderedStaffs
import com.mecon.api.storage.StaffLayoutPreset
import com.mecon.api.storage.StorageScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PolyphonyLimitValidatorTest {
    @Test
    fun countsChordHeadsAcrossNotationLanes() {
        val score = grandStaff()
        val (upper, lower) = score.orderedStaffs()
        val withUpper = assertNotNull(
            NoteEditEngine.insertChord(
                score,
                chord(
                    upper.voiceTracks.single().id,
                    TimeCode.ofMeasure(1),
                    listOf(60, 64),
                ),
            ),
        ).score
        val withBoth = assertNotNull(
            NoteEditEngine.insertChord(
                withUpper,
                chord(
                    lower.voiceTracks.single().id,
                    TimeCode.ofMeasure(1),
                    listOf(48, 55, 59),
                ),
            ),
        ).score

        val validation = PolyphonyLimitValidator.validate(withBoth, limit = 4)

        assertFalse(validation.isValid)
        assertEquals(5, validation.peak)
        assertEquals(1, validation.overflows.size)
        assertEquals(TimeCode.of(1, Fraction.ZERO), validation.overflows.single().start)
        assertEquals(TimeCode.of(1, Fraction.QUARTER), validation.overflows.single().end)
    }

    @Test
    fun treatsSpansAsHalfOpenAndIgnoresGraceNotes() {
        val score = grandStaff()
        val (upper, lower) = score.orderedStaffs()
        val withFirst = assertNotNull(
            NoteEditEngine.insertChord(
                score,
                chord(
                    upper.voiceTracks.single().id,
                    TimeCode.ofMeasure(1),
                    listOf(60, 64, 67),
                ),
            ),
        ).score
        val withFollowing = assertNotNull(
            NoteEditEngine.insertChord(
                withFirst,
                chord(
                    lower.voiceTracks.single().id,
                    TimeCode.of(1, Fraction.QUARTER),
                    listOf(48, 55, 59),
                ),
            ),
        ).score
        val withGrace = assertNotNull(
            NoteEditEngine.insertChord(
                withFollowing,
                chord(
                    upper.voiceTracks.single().id,
                    TimeCode.of(1, Fraction.QUARTER),
                    listOf(72, 76),
                ).copy(grace = NoteEditEngine.GraceInsertion()),
            ),
        ).score

        val validation = PolyphonyLimitValidator.validate(withGrace, limit = 3)

        assertTrue(validation.isValid)
        assertEquals(3, validation.peak)
    }

    private fun grandStaff(): RuntimeScore = RuntimeScore.fromStorage(
        StorageScore.create(
            StorageScore.CreationOptions(
                layout = StaffLayoutPreset.PIANO_GRAND,
                measureCount = 1,
            ),
        ),
    )

    private fun chord(
        voiceTrackId: com.mecon.api.primitive.TrackId,
        start: TimeCode,
        midi: List<Int>,
    ) = NoteEditEngine.ChordInsertion(
        voiceTrackId = voiceTrackId,
        start = start,
        duration = Duration(DurationBase.QUARTER),
        pitches = midi.map(Pitch::fromMidi),
    )
}
