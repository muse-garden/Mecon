package com.mecon.core.engine.edit

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeRange
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.storage.GraceNoteType
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.GraceTimeSource
import com.mecon.api.storage.events.TupletDisplayStyle
import com.mecon.core.engine.computeScore
import com.mecon.core.engine.computeScoreIncremental
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraceAndSmallNoteEditingTest {
    private fun emptyScore(): RuntimeScore =
        RuntimeScore.fromStorage(
            StorageScore.create(
                StorageScore.CreationOptions("T", TimeSignature.COMMON, KeySignature.C_MAJOR)
            )
        )

    private fun RuntimeScore.voiceId(): TrackId = voiceTracks.keys.first()
    private fun RuntimeScore.staffId(): TrackId = staffTracks.keys.first()
    private fun RuntimeScore.events(): List<RuntimeVoiceEvent> =
        getVoiceTrack(voiceId())!!.events.toList().sortedBy { it.onset }

    private fun RuntimeScore.addRest(id: String, onset: TimeCode, duration: Duration): RuntimeScore {
        val pitch = RuntimePitchEvent(EventId("p-$id"), onset, emptyList())
        val event = RuntimeVoiceEvent(EventId(id), onset, pitch, duration)
        return addPitchEvent(pitchTracks.keys.first(), pitch).addVoiceEvent(voiceId(), event)
    }

    private fun grace(
        score: RuntimeScore,
        start: TimeCode,
        pitch: Pitch,
        duration: Duration = Duration(DurationBase.SIXTEENTH),
    ): NoteEditEngine.Result = assertNotNull(
        NoteEditEngine.insert(
            score,
            NoteEditEngine.Insertion(
                voiceTrackId = score.voiceId(),
                staffTrackId = score.staffId(),
                start = start,
                duration = duration,
                pitch = pitch,
                grace = NoteEditEngine.GraceInsertion(
                    totalDuration = Duration(DurationBase.EIGHTH),
                    stealFrom = GraceTimeSource.PRINCIPAL,
                    noteType = GraceNoteType.APPOGGIATURA,
                ),
            )
        )
    )

    @Test
    fun graceAppendReindexesGroupAndKeepsMetadataOnFirstMember() {
        val anchor = TimeCode.of(1, Fraction.ZERO)
        val first = grace(emptyScore(), anchor, Pitch.C4)
        val second = grace(first.score, anchor, Pitch.D4, Duration(DurationBase.THIRTY_SECOND))
        val third = grace(second.score, anchor, Pitch.E4, Duration(DurationBase.SIXTY_FOURTH))
        val graces = third.score.events().filter { it.isGrace }

        assertEquals(3, graces.size)
        assertEquals(Fraction(-1, 1), graces[0].onset.grace)
        assertEquals(Fraction(-2, 3), graces[1].onset.grace)
        assertEquals(Fraction(-1, 3), graces[2].onset.grace)
        assertEquals(listOf(Pitch.C4, Pitch.D4, Pitch.E4), graces.map { it.pitches.single() })
        assertNotNull(graces[0].graceInfo)
        assertEquals(null, graces[1].graceInfo)
        assertEquals(DurationBase.THIRTY_SECOND, graces[1].duration.base)
        assertEquals(DurationBase.SIXTY_FOURTH, graces[2].duration.base)
    }

    @Test
    fun graceSlotAcceptsChordAndGroupPropertiesCanBeEdited() {
        val anchor = TimeCode.of(1, Fraction(1, 4))
        val first = grace(emptyScore(), anchor, Pitch.C4)
        val slot = first.score.events().single { it.isGrace }.onset
        val chord = grace(first.score, slot, Pitch.E4)
        val event = chord.score.events().single { it.isGrace }
        assertEquals(listOf(Pitch.C4, Pitch.E4), event.pitches)

        val edited = NoteEditEngine.editGraceGroups(
            chord.score,
            listOf(
                NoteEditEngine.GraceGroupEdit(
                    chord.score.voiceId(),
                    event.id,
                    Duration(DurationBase.QUARTER),
                    GraceTimeSource.PREVIOUS,
                )
            ),
        ) as NoteEditEngine.EditOutcome.Changed
        val info = edited.score.events().single { it.isGrace }.graceInfo
        assertEquals(Duration(DurationBase.QUARTER), info?.totalDuration)
        assertEquals(GraceTimeSource.PREVIOUS, info?.stealFrom)
    }

    @Test
    fun atomicGraceChordCreatesOneGraceSlot() {
        val score = emptyScore()
        val result = assertNotNull(
            NoteEditEngine.insertChord(
                score,
                NoteEditEngine.ChordInsertion(
                    voiceTrackId = score.voiceId(),
                    staffTrackId = score.staffId(),
                    start = TimeCode.of(1, Fraction.ZERO),
                    duration = Duration(DurationBase.SIXTEENTH),
                    pitches = listOf(Pitch.C4, Pitch.E4),
                    grace = NoteEditEngine.GraceInsertion(),
                )
            )
        )
        val grace = result.score.events().single { it.isGrace }
        assertEquals(listOf(Pitch.C4, Pitch.E4), grace.pitches)
    }

    @Test
    fun scoreStartGraceSurvivesIncrementalInsertAndDelete() {
        val base = emptyScore()
        val previous = computeScore(base)
        val inserted = grace(base, TimeCode.of(1, Fraction.ZERO), Pitch.C4)
        val insertedId = assertNotNull(inserted.insertedEventId)
        val afterInsert = computeScoreIncremental(
            previous,
            inserted.score,
            inserted.editInterval,
        ).computed

        assertTrue(afterInsert.getComputedEvent(insertedId)?.isGrace == true)

        val deleted = assertNotNull(
            NoteEditEngine.delete(
                inserted.score,
                NoteEditEngine.Deletion(inserted.score.voiceId(), insertedId),
            )
        )
        val afterDelete = computeScoreIncremental(
            afterInsert,
            deleted.score,
            TimeRange(TimeCode.of(1, Fraction.ZERO), TimeCode.of(2, Fraction.ZERO)),
        ).computed
        assertEquals(null, afterDelete.getComputedEvent(insertedId))
    }

    @Test
    fun deletingGraceMemberReindexesAndTransfersGroupMetadata() {
        val anchor = TimeCode.of(1, Fraction.ONE)
        val first = grace(emptyScore(), anchor, Pitch.C4)
        val second = grace(first.score, anchor, Pitch.D4)
        val oldFirst = second.score.events().first { it.isGrace }
        val deleted = assertNotNull(
            NoteEditEngine.delete(
                second.score,
                NoteEditEngine.Deletion(second.score.voiceId(), oldFirst.id),
            )
        )
        val remaining = deleted.score.events().single { it.isGrace }
        assertEquals(Fraction(-1, 1), remaining.onset.grace)
        assertNotNull(remaining.graceInfo)
    }

    @Test
    fun restSelectionBecomesMeteredSmallNoteRegionAndNewNotesStaySmall() {
        val score = emptyScore().addRest("rest", TimeCode.of(1, Fraction.ZERO), Duration.QUARTER)
        val converted = NoteEditEngine.createSmallNoteRegions(
            score,
            listOf(NoteEditEngine.SmallNoteEdit(score.voiceId(), setOf(EventId("rest")))),
        ) as NoteEditEngine.EditOutcome.Changed
        val start = converted.score.events().first { it.tupletSpan?.smallNotes == true }
        assertEquals(TupletDisplayStyle.NONE, start.tupletSpan?.displayStyle)
        assertTrue(
            converted.score.events()
                .filter { it.onset >= start.onset && it.onset < start.tupletSpan!!.endTimeCode }
                .all { it.rendering?.scale == 0.7f }
        )
        assertTrue(
            converted.score.events()
                .filter { it.onset >= start.onset && it.onset < start.tupletSpan!!.endTimeCode }
                .all { !it.isRest || it.rendering?.hidden == true },
            "unentered capacity in a small-note region must remain invisible",
        )

        val inserted = assertNotNull(
            NoteEditEngine.insert(
                converted.score,
                NoteEditEngine.Insertion(
                    voiceTrackId = converted.score.voiceId(),
                    start = start.onset,
                    duration = Duration(DurationBase.EIGHTH),
                    pitch = Pitch.C4,
                )
            )
        )
        assertEquals(0.7f, inserted.score.events().first { !it.isRest }.rendering?.scale)

        val fineOnset = TimeCode.of(1, Fraction(1, 64))
        val subdivided = assertNotNull(
            NoteEditEngine.insert(
                inserted.score,
                NoteEditEngine.Insertion(
                    voiceTrackId = inserted.score.voiceId(),
                    start = fineOnset,
                    duration = Duration(DurationBase.THIRTY_SECOND),
                    pitch = Pitch.E4,
                )
            )
        )
        assertTrue(
            subdivided.score.events().any { !it.isRest && it.onset == fineOnset },
            "small-note region must accept a finer onset that was not in its original rest grid",
        )
        assertTrue(
            subdivided.score.events()
                .filter { it.onset >= start.onset && it.onset < start.tupletSpan!!.endTimeCode }
                .all { it.rendering?.scale == 0.7f },
        )
        assertTrue(
            subdivided.score.events()
                .filter { it.onset >= start.onset && it.onset < start.tupletSpan!!.endTimeCode }
                .all { if (it.isRest) it.rendering?.hidden == true else it.rendering?.hidden != true },
            "only entered small notes should be visible inside the open region",
        )
    }

    @Test
    fun halfRestSmallNoteRegionAcceptsMoreThanFourSixteenthNotes() {
        val score = emptyScore().addRest("rest", TimeCode.of(1, Fraction.ZERO), Duration.HALF)
        val converted = NoteEditEngine.createSmallNoteRegions(
            score,
            listOf(NoteEditEngine.SmallNoteEdit(score.voiceId(), setOf(EventId("rest")))),
        ) as NoteEditEngine.EditOutcome.Changed
        val initialStart = converted.score.events().first { it.tupletSpan?.smallNotes == true }
        val regionEnd = initialStart.tupletSpan!!.endTimeCode
        val ratio = assertNotNull(initialStart.duration.tuplet)
        val duration = Duration(DurationBase.SIXTEENTH)
        val step = duration.copy(tuplet = ratio).toFraction()
        var current = converted.score

        repeat(5) { index ->
            val onset = TimeCode.of(1, step * Fraction(index, 1))
            current = assertNotNull(
                NoteEditEngine.insert(
                    current,
                    NoteEditEngine.Insertion(
                        voiceTrackId = current.voiceId(),
                        start = onset,
                        duration = duration,
                        pitch = Pitch.C4,
                        smallNoteAppendStartEventId = if (index == 4) {
                            current.events().first { it.tupletSpan?.smallNotes == true }.id
                        } else null,
                    ),
                ),
                "insertion ${index + 1} at $onset should remain inside the small-note region",
            ).score
            val regionStart = assertNotNull(
                current.events().firstOrNull { it.tupletSpan?.smallNotes == true },
                "the small-note span must survive insertion ${index + 1}",
            )
            assertEquals(initialStart.onset, regionStart.onset)
            assertEquals(regionEnd, regionStart.tupletSpan?.endTimeCode)
            assertEquals(
                index + 1,
                current.events().count { !it.isRest && it.onset >= regionStart.onset && it.onset < regionEnd },
                "the explicit append intent must be folded back into the fixed small-note region",
            )
            assertTrue(
                current.events()
                    .filter { it.onset >= regionStart.onset && it.onset < regionEnd }
                    .all { if (it.isRest) it.rendering?.hidden == true else it.rendering?.scale == 0.7f },
                "all remaining capacity must stay hidden after insertion ${index + 1}",
            )
        }
    }

    @Test
    fun editingSmallNoteDurationChangesOnlyItsDisplayedValueWithoutAddingRests() {
        val score = emptyScore().addRest("rest", TimeCode.of(1, Fraction.ZERO), Duration.HALF)
        val converted = NoteEditEngine.createSmallNoteRegions(
            score,
            listOf(NoteEditEngine.SmallNoteEdit(score.voiceId(), setOf(EventId("rest")))),
        ) as NoteEditEngine.EditOutcome.Changed
        val initialStart = converted.score.events().first { it.tupletSpan?.smallNotes == true }
        val regionEnd = assertNotNull(initialStart.tupletSpan).endTimeCode
        val inputDuration = Duration.SIXTEENTH
        val step = inputDuration.copy(tuplet = assertNotNull(initialStart.duration.tuplet)).toFraction()
        val pitches = listOf(Pitch.C4, Pitch.D4, Pitch.E4)
        var current = converted.score
        repeat(3) { index ->
            current = assertNotNull(
                NoteEditEngine.insert(
                    current,
                    NoteEditEngine.Insertion(
                        voiceTrackId = current.voiceId(),
                        start = TimeCode.of(1, step * Fraction(index, 1)),
                        duration = inputDuration,
                        pitch = pitches[index],
                    ),
                ),
            ).score
        }
        val before = current.events()
            .filter { !it.isRest && it.onset < regionEnd }
            .sortedBy { it.onset }
        val target = before[1]

        val outcome = NoteEditEngine.editDurations(
            current,
            listOf(
                NoteEditEngine.DurationEdit(
                    voiceTrackId = current.voiceId(),
                    eventId = target.id,
                    duration = Duration.EIGHTH,
                ),
            ),
        )
        assertTrue(outcome is NoteEditEngine.EditOutcome.Changed)
        val changed = (outcome as NoteEditEngine.EditOutcome.Changed).score
        val after = changed.events()
            .filter { !it.isRest && it.onset < regionEnd }
            .sortedBy { it.onset }

        assertEquals(before.map { it.id }, after.map { it.id }, "all small-note identities must survive")
        assertEquals(before.map { it.pitches }, after.map { it.pitches }, "all pitches must remain unchanged")
        assertEquals(
            listOf(DurationBase.SIXTEENTH, DurationBase.EIGHTH, DurationBase.SIXTEENTH),
            after.map { it.duration.base },
            "only the selected member's displayed value may change",
        )
        assertTrue(
            changed.events().none { it.isRest && it.onset >= initialStart.onset && it.onset < regionEnd },
            "duration editing must not insert capacity rests into a small-note group",
        )
        val rebuiltStart = after.first()
        assertEquals(regionEnd, rebuiltStart.tupletSpan?.endTimeCode)
        val actualEnd = EditGeometry.advance(
            changed,
            after.last().onset,
            after.last().duration.toFraction(),
        )
        assertEquals(regionEnd, actualEnd, "the re-ratioed members must still fill the fixed region")
    }

    @Test
    fun smallNoteEndpointWithoutAppendIntentBelongsToFollowingTimeAxis() {
        val score = emptyScore().addRest("rest", TimeCode.of(1, Fraction.ZERO), Duration.HALF)
        val converted = NoteEditEngine.createSmallNoteRegions(
            score,
            listOf(NoteEditEngine.SmallNoteEdit(score.voiceId(), setOf(EventId("rest")))),
        ) as NoteEditEngine.EditOutcome.Changed
        val start = converted.score.events().first { it.tupletSpan?.smallNotes == true }
        val endpoint = start.tupletSpan!!.endTimeCode

        val inserted = assertNotNull(
            NoteEditEngine.insert(
                converted.score,
                NoteEditEngine.Insertion(
                    voiceTrackId = converted.score.voiceId(),
                    start = endpoint,
                    duration = Duration.QUARTER,
                    pitch = Pitch.D4,
                ),
            ),
        ).score

        val unchangedGroup = assertNotNull(
            inserted.events().firstOrNull { it.tupletSpan?.smallNotes == true },
            "normal endpoint insertion must preserve the preceding group; events=${inserted.events()}",
        )
        assertEquals(start.id, unchangedGroup.id, "normal endpoint insertion must preserve group identity")
        assertEquals(start.onset, unchangedGroup.onset)
        assertEquals(endpoint, unchangedGroup.tupletSpan?.endTimeCode)
        assertNotNull(
            inserted.events().firstOrNull { it.onset == endpoint && Pitch.D4 in it.pitches },
            "the exclusive endpoint must insert on the following normal time axis",
        )
    }

    @Test
    fun smallNoteRegionEndingAtBarlineNeverStoresRestsInFollowingEmptyMeasure() {
        val score = emptyScore().addRest(
            "rest",
            TimeCode.of(1, Fraction(1, 2)),
            Duration.HALF,
        )
        val converted = NoteEditEngine.createSmallNoteRegions(
            score,
            listOf(NoteEditEngine.SmallNoteEdit(score.voiceId(), setOf(EventId("rest")))),
        ) as NoteEditEngine.EditOutcome.Changed
        val start = converted.score.events().first { it.tupletSpan?.smallNotes == true }
        val endpoint = assertNotNull(start.tupletSpan).endTimeCode
        assertEquals(TimeCode.of(2, Fraction.ZERO), endpoint)
        assertTrue(
            converted.score.events().none { it.onset.measure == 2 },
            "conversion placeholders must stop at the region's exclusive barline endpoint",
        )

        val duration = Duration.SIXTEENTH
        val step = duration.copy(tuplet = assertNotNull(start.duration.tuplet)).toFraction()
        var current = converted.score
        repeat(4) { index ->
            current = assertNotNull(
                NoteEditEngine.insert(
                    current,
                    NoteEditEngine.Insertion(
                        voiceTrackId = current.voiceId(),
                        start = TimeCode.of(1, Fraction(1, 2) + step * Fraction(index, 1)),
                        duration = duration,
                        pitch = Pitch.C4,
                    ),
                ),
            ).score
            assertTrue(
                current.events().none { it.onset.measure == 2 },
                "small-note insertion ${index + 1} must not materialize rests after the region",
            )
        }

        val currentStart = current.events().first { it.tupletSpan?.smallNotes == true }
        val appended = assertNotNull(
            NoteEditEngine.insert(
                current,
                NoteEditEngine.Insertion(
                    voiceTrackId = current.voiceId(),
                    start = endpoint,
                    duration = duration,
                    pitch = Pitch.D4,
                    smallNoteAppendStartEventId = currentStart.id,
                ),
            ),
        ).score
        assertTrue(
            appended.events().none { it.onset.measure == 2 },
            "dynamic append must leave the following empty measure implicit",
        )
    }

    @Test
    fun insertionCanonicalizesBeatBeyondBarlineInsteadOfProducingNegativeDuration() {
        val score = emptyScore()
        val result = assertNotNull(
            NoteEditEngine.insert(
                score,
                NoteEditEngine.Insertion(
                    voiceTrackId = score.voiceId(),
                    start = TimeCode.of(1, Fraction(5, 4)),
                    duration = Duration.QUARTER,
                    pitch = Pitch.C4,
                ),
            ),
        )
        val inserted = assertNotNull(result.score.events().firstOrNull { it.id == result.insertedEventId })
        assertEquals(TimeCode.of(2, Fraction(1, 4)), inserted.onset)
    }
}
