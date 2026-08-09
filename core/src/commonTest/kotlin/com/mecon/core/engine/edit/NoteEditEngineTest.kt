package com.mecon.core.engine.edit

import com.mecon.api.primitive.*
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeTieInfo
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.runtime.tracks.RuntimeVoiceTrack
import com.mecon.api.storage.BeamingInfo
import com.mecon.api.storage.RenderingProps
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.TupletSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoteEditEngineTest {

    // ---- fixtures --------------------------------------------------------------------------------

    private fun emptyScore(): RuntimeScore =
        RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("T", TimeSignature.COMMON, KeySignature.C_MAJOR)))

    private fun RuntimeScore.ptId(): TrackId = pitchTracks.keys.first()
    private fun RuntimeScore.vtId(): TrackId = voiceTracks.keys.first()
    private fun RuntimeScore.staffId(): TrackId = staffTracks.keys.first()

    private fun tc(measure: Int, num: Int = 0, den: Int = 1) = TimeCode.of(measure, Fraction(num, den))

    private fun RuntimeScore.addNote(
        idTag: String, onset: TimeCode, pitch: Pitch?, duration: Duration,
    ): RuntimeScore {
        val pe = RuntimePitchEvent(
            id = EventId("p-$idTag"), onset = onset,
            pitches = if (pitch == null) emptyList() else listOf(pitch),
        )
        val ve = RuntimeVoiceEvent(
            id = EventId(idTag), onset = onset, pitchEvent = pe, duration = duration,
        )
        return addPitchEvent(ptId(), pe).addVoiceEvent(vtId(), ve)
    }

    private fun RuntimeScore.addChord(
        idTag: String,
        onset: TimeCode,
        pitches: List<Pitch>,
        duration: Duration,
        rendering: RenderingProps? = null,
    ): RuntimeScore {
        val pe = RuntimePitchEvent(
            id = EventId("p-$idTag"),
            onset = onset,
            pitches = pitches,
        )
        val ve = RuntimeVoiceEvent(
            id = EventId(idTag),
            onset = onset,
            pitchEvent = pe,
            duration = duration,
            rendering = rendering,
        )
        return addPitchEvent(ptId(), pe).addVoiceEvent(vtId(), ve)
    }

    private fun RuntimeScore.voice(): RuntimeVoiceTrack = getVoiceTrack(vtId())!!
    private fun RuntimeScore.events(): List<RuntimeVoiceEvent> = voice().events.toList()

    private fun insert(
        runtime: RuntimeScore, start: TimeCode, duration: Duration,
        pitch: Pitch? = Pitch.C4, isRest: Boolean = false, trailingTie: Boolean = false,
    ): RuntimeScore = NoteEditEngine.insert(
        runtime,
        NoteEditEngine.Insertion(runtime.vtId(), start, duration, pitch, isRest, trailingTie),
    )!!.score

    // ---- DurationDecomposer ----------------------------------------------------------------------

    @Test
    fun decomposeSingleNoteValues() {
        assertEquals(listOf(Duration.QUARTER), DurationDecomposer.decompose(Fraction(1, 4)))
        assertEquals(listOf(Duration.WHOLE), DurationDecomposer.decompose(Fraction(1, 1)))
    }

    @Test
    fun decomposeDottedMerge() {
        // 3/4 = dotted half
        assertEquals(listOf(Duration(DurationBase.HALF, dots = 1)), DurationDecomposer.decompose(Fraction(3, 4)))
        // 7/16 = dotted quarter + sixteenth
        assertEquals(
            listOf(Duration(DurationBase.QUARTER, dots = 1), Duration(DurationBase.SIXTEENTH)),
            DurationDecomposer.decompose(Fraction(7, 16)),
        )
    }

    @Test
    fun decomposeSumsExactly() {
        val total = DurationDecomposer.decompose(Fraction(5, 8))
            .fold(Fraction.ZERO) { acc, d -> acc + d.toFraction() }
        assertEquals(Fraction(5, 8), total)
    }

    // ---- clearInterval ---------------------------------------------------------------------------

    @Test
    fun clearIntervalSplitsContainingNote() {
        // Whole note C4 in m1; carve out [1/4, 1/2) → quarter + half, both C4.
        val base = emptyScore().addNote("w", tc(1, 0), Pitch.C4, Duration.WHOLE)
        val kept = NoteEditEngine.clearInterval(base, base.voice(), tc(1, 1, 4), tc(1, 1, 2))
            .sortedBy { it.onset }
        assertEquals(2, kept.size)
        assertEquals(tc(1, 0), kept[0].onset)
        assertEquals(Duration.QUARTER, kept[0].duration)
        assertEquals(tc(1, 1, 2), kept[1].onset)
        assertEquals(Duration.HALF, kept[1].duration)
        assertTrue(kept.all { it.pitches == listOf(Pitch.C4) })
    }

    @Test
    fun clearIntervalTrimsAndRemoves() {
        val base = emptyScore()
            .addNote("a", tc(1, 0), Pitch.C4, Duration.HALF)      // [0, 1/2)
            .addNote("b", tc(1, 1, 2), Pitch.D4, Duration.HALF)   // [1/2, 1)
        // Clear [1/4, 3/4): trims 'a' to a quarter, trims 'b' to start at 3/4.
        val kept = NoteEditEngine.clearInterval(base, base.voice(), tc(1, 1, 4), tc(1, 3, 4))
            .sortedBy { it.onset }
        assertEquals(2, kept.size)
        assertEquals(tc(1, 0), kept[0].onset)
        assertEquals(Duration.QUARTER, kept[0].duration)
        assertEquals(tc(1, 3, 4), kept[1].onset)
        assertEquals(Duration.QUARTER, kept[1].duration)
    }

    @Test
    fun replaceRangePreservesOutsideEventIdentity() {
        val base = emptyScore()
            .addNote("before", tc(1, 0), Pitch.C4, Duration.QUARTER)
            .addNote("inside", tc(1, 1, 4), Pitch.D4, Duration.HALF)
            .addNote("after", tc(1, 3, 4), Pitch.E4, Duration.QUARTER)

        val result = NoteEditEngine.replaceRange(
            runtime = base,
            voiceTrackIds = setOf(base.vtId()),
            start = tc(1, 1, 4),
            end = tc(1, 3, 4),
            notes = listOf(
                NoteEditEngine.RangeNote(
                    voiceTrackId = base.vtId(),
                    start = tc(1, 1, 4),
                    duration = Fraction.HALF,
                    pitch = Pitch.G4,
                ),
            ),
        )

        val events = result.score.events()
        assertTrue(events.any { it.id == EventId("before") })
        assertTrue(events.any { it.id == EventId("after") })
        assertTrue(events.none { it.id == EventId("inside") })
        assertEquals(listOf(Pitch.G4), events.single { it.onset == tc(1, 1, 4) }.pitches)
        assertTrue(result.insertedEventIdsByNoteIndex.getValue(0).isNotEmpty())
    }

    // ---- insertion -------------------------------------------------------------------------------

    @Test
    fun insertTiesAcrossBarline() {
        // Insert a half note at beat 3/4 of m1 (4/4): spills 1/4 into m2 → two tied quarters.
        // The surrounding holes of both bars are padded with rests, so inspect just the notes.
        val base = emptyScore()
        val result = insert(base, tc(1, 3, 4), Duration.HALF, Pitch.C4)
        val notes = result.events().filter { !it.isRest }.sortedBy { it.onset }
        assertEquals(2, notes.size)
        assertEquals(tc(1, 3, 4), notes[0].onset)
        assertEquals(Duration.QUARTER, notes[0].duration)
        assertTrue(notes[0].ties.isNotEmpty(), "first piece should tie into the next measure")
        assertEquals(tc(2, 0), notes[1].onset)
        assertEquals(Duration.QUARTER, notes[1].duration)
        assertTrue(notes[1].ties.isEmpty(), "last piece has no trailing tie by default")
    }

    @Test
    fun insertFillsRemainderOfMeasureWithRests() {
        // 4/4 empty bar; insert a quarter at beat 0 → quarter note, then the remaining 3 beats merge
        // metrically into a quarter rest (beat 2) + half rest (beats 3-4), not three quarter rests.
        val base = emptyScore()
        val result = insert(base, tc(1, 0), Duration.QUARTER, Pitch.C4)
        val events = result.events().sortedBy { it.onset }
        assertEquals(3, events.size)
        assertEquals(Duration.QUARTER, events[0].duration)
        assertTrue(!events[0].isRest)
        val rests = events.drop(1)
        assertTrue(rests.all { it.isRest })
        assertEquals(listOf(Duration.QUARTER, Duration.HALF), rests.map { it.duration })
        assertEquals(listOf(tc(1, 1, 4), tc(1, 1, 2)), rests.map { it.onset })
    }

    @Test
    fun insertThirtySecondAlignsRestsToBeat() {
        // 4/4: a 32nd at beat 0 → 32nd + 16th + 8th rests (short→long into beat 1), then the rest of
        // the bar merges long→short: a quarter (beat 2) + half (beats 3-4).
        val base = emptyScore()
        val result = insert(base, tc(1, 0), Duration(DurationBase.THIRTY_SECOND), Pitch.C4)
        val events = result.events().sortedBy { it.onset }
        assertTrue(!events[0].isRest)
        assertEquals(Duration(DurationBase.THIRTY_SECOND), events[0].duration)
        val rests = events.drop(1)
        assertTrue(rests.all { it.isRest })
        assertEquals(
            listOf(
                Duration(DurationBase.THIRTY_SECOND),
                Duration(DurationBase.SIXTEENTH),
                Duration(DurationBase.EIGHTH),
                Duration.QUARTER,
                Duration.HALF,
            ),
            rests.map { it.duration },
        )
    }

    @Test
    fun insertChordsOntoSameDurationNote() {
        val base = emptyScore().addNote("c", tc(1, 0), Pitch.C4, Duration.QUARTER)
        val result = insert(base, tc(1, 0), Duration.QUARTER, Pitch.E4)
        val events = result.events()
        assertEquals(1, events.size)
        assertEquals(listOf(Pitch.C4, Pitch.E4), events[0].pitches.sortedBy { it.midiNumber })
    }

    @Test
    fun monodicInsertionReplacesPitchWithoutReplacingEvent() {
        val rendering = RenderingProps.DEFAULT.copy(hidden = true)
        val base = emptyScore().addChord(
            idTag = "existing",
            onset = tc(1, 0),
            pitches = listOf(Pitch.C4, Pitch.E4),
            duration = Duration.QUARTER,
            rendering = rendering,
        )
        val result = NoteEditEngine.insert(
            base,
            NoteEditEngine.Insertion(
                voiceTrackId = base.vtId(),
                start = tc(1, 0),
                duration = Duration.QUARTER,
                pitch = Pitch.G4,
            ),
            NoteEditEngine.InsertionPolicy.MONODIC,
        )!!

        val event = result.score.events().single()
        assertEquals(EventId("existing"), event.id)
        assertEquals(Duration.QUARTER, event.duration)
        assertEquals(rendering, event.rendering)
        assertEquals(listOf(Pitch.G4), event.pitches)
        assertEquals(EventId("existing"), result.insertedEventId)
    }

    @Test
    fun monodicBatchRejectsMultiplePitches() {
        val base = emptyScore()
        val result = NoteEditEngine.insertChord(
            base,
            NoteEditEngine.ChordInsertion(
                voiceTrackId = base.vtId(),
                start = tc(1, 0),
                duration = Duration.QUARTER,
                pitches = listOf(Pitch.C4, Pitch.E4),
            ),
            NoteEditEngine.InsertionPolicy.MONODIC,
        )

        assertNull(result)
    }

    @Test
    fun insertChordAllowsDuplicatePitchAsUnison() {
        val base = emptyScore().addNote("c", tc(1, 0), Pitch.C4, Duration.QUARTER)
        val result = NoteEditEngine.insert(
            base, NoteEditEngine.Insertion(base.vtId(), tc(1, 0), Duration.QUARTER, Pitch.C4),
        )
        assertEquals(listOf(Pitch.C4, Pitch.C4), result!!.score.events().single().pitches)
    }

    @Test
    fun insertChordIsAtomicAndKeepsExistingEventDuration() {
        val base = emptyScore().addNote("c", tc(1, 0), Pitch.C4, Duration.HALF)
        val result = NoteEditEngine.insertChord(
            base,
            NoteEditEngine.ChordInsertion(
                voiceTrackId = base.vtId(),
                start = tc(1, 0),
                duration = Duration.EIGHTH,
                pitches = listOf(Pitch.E4, Pitch.G4),
            ),
        )!!
        val event = result.score.events().single()
        assertEquals(Duration.HALF, event.duration)
        assertEquals(listOf(Pitch.C4, Pitch.E4, Pitch.G4), event.pitches)
        assertEquals(EventId("c"), result.insertedEventId)
    }

    @Test
    fun insertChordPreservesEnharmonicPitchesWithSameSoundingMidi() {
        val base = emptyScore()
        val result = NoteEditEngine.insertChord(
            base,
            NoteEditEngine.ChordInsertion(
                voiceTrackId = base.vtId(),
                start = tc(1, 0),
                duration = Duration.QUARTER,
                pitches = listOf(Pitch.fromName("C#4"), Pitch.fromName("Db4"), Pitch.E4),
            ),
        )!!.score
        val pitches = result.events().first { !it.isRest }.pitches
        assertEquals(listOf(61, 61, 64), pitches.map { it.midiNumber })
        assertEquals(listOf(Pitch.fromName("C#4"), Pitch.fromName("Db4"), Pitch.E4), pitches)
    }

    @Test
    fun insertingLowerChordToneRemapsExistingTieAndAddsRequestedTie() {
        val base = insert(
            emptyScore(), tc(1, 0), Duration.QUARTER, Pitch.E4, trailingTie = true,
        )
        val result = NoteEditEngine.insertChord(
            base,
            NoteEditEngine.ChordInsertion(
                voiceTrackId = base.vtId(),
                start = tc(1, 0),
                duration = Duration.QUARTER,
                pitches = listOf(Pitch.C4),
                trailingTie = true,
            ),
        )!!.score
        val event = result.events().first { !it.isRest }
        assertEquals(listOf(Pitch.C4, Pitch.E4), event.pitches)
        assertEquals(listOf(0, 1), event.ties.map { it.pitchIndex })
    }

    @Test
    fun insertOverwritesViaClearInterval() {
        // Existing whole note; insert a quarter at beat 0 → clears the middle, leaves quarter + rest-of-fill.
        val base = emptyScore().addNote("w", tc(1, 0), Pitch.C4, Duration.WHOLE)
        val result = insert(base, tc(1, 0), Duration.QUARTER, Pitch.G4)
        val events = result.events().sortedBy { it.onset }
        // beat 0: new G4 quarter; beats 1/4..1: remnant C4 (dotted half).
        assertEquals(tc(1, 0), events[0].onset)
        assertEquals(listOf(Pitch.G4), events[0].pitches)
        assertEquals(Duration.QUARTER, events[0].duration)
        assertEquals(tc(1, 1, 4), events[1].onset)
        assertEquals(listOf(Pitch.C4), events[1].pitches)
        assertEquals(Duration(DurationBase.HALF, dots = 1), events[1].duration)
    }

    @Test
    fun insertRestClearsAndPlacesRest() {
        val base = emptyScore().addNote("c", tc(1, 0), Pitch.C4, Duration.QUARTER)
        val result = insert(base, tc(1, 0), Duration.QUARTER, pitch = null, isRest = true)
        val events = result.events().sortedBy { it.onset }
        // The quarter note becomes a quarter rest; the rest of the bar is padded with rests too.
        assertTrue(events.all { it.isRest })
        assertEquals(tc(1, 0), events[0].onset)
        assertEquals(Duration.QUARTER, events[0].duration)
        assertTrue(events.all { it.ties.isEmpty() })
    }

    @Test
    fun insertTrailingTieSetsTieOnLastPiece() {
        val base = emptyScore()
        val result = insert(base, tc(1, 0), Duration.QUARTER, Pitch.C4, trailingTie = true)
        val notes = result.events().filter { !it.isRest }
        assertEquals(1, notes.size)
        assertTrue(notes[0].ties.isNotEmpty())
    }

    @Test
    fun insertIntoMissingVoiceCreatesVoiceTrack() {
        val base = emptyScore()
        val result = NoteEditEngine.insert(
            base,
            NoteEditEngine.Insertion(
                voiceTrackId = base.vtId(),
                start = tc(1, 0),
                duration = Duration.QUARTER,
                pitch = Pitch.C4,
                staffTrackId = base.staffId(),
                voiceNumber = 3,
            ),
        )!!.score

        val voice3 = result.voiceTracks.values.single { it.voiceNumber == 3 }
        assertEquals(listOf(Pitch.C4), voice3.events.toList().single { !it.isRest }.pitches)
        assertTrue(result.staffTracks.values.single().voiceTracks.any { it.id == voice3.id })
    }

    @Test
    fun tupletSpecChoosesCommonBeatUnits() {
        assertEquals(
            NoteEditEngine.TupletSpec(3, 2, DurationBase.EIGHTH),
            NoteEditEngine.tupletSpecFor(Fraction(1, 4), 3),
        )
        assertEquals(
            NoteEditEngine.TupletSpec(5, 4, DurationBase.SIXTEENTH),
            NoteEditEngine.tupletSpecFor(Fraction(1, 4), 5),
        )
        assertEquals(
            NoteEditEngine.TupletSpec(2, 3, DurationBase.EIGHTH),
            NoteEditEngine.tupletSpecFor(Fraction(3, 8), 2),
        )
    }

    @Test
    fun insertTupletCreatesFirstNoteAndRemainingBeatRests() {
        val base = emptyScore()
        val result = NoteEditEngine.insert(
            base,
            NoteEditEngine.Insertion(
                voiceTrackId = base.vtId(),
                start = tc(1, 0),
                duration = Duration.QUARTER,
                pitch = Pitch.C4,
                tupletCount = 3,
            ),
        )!!.score

        val group = result.events().filter { it.onset < tc(1, 1, 4) }.sortedBy { it.onset }
        assertEquals(3, group.size)
        assertEquals(listOf(false, true, true), group.map { it.isRest })
        assertEquals(List(3) { Duration(DurationBase.EIGHTH, tuplet = Tuplet(3, 2)) }, group.map { it.duration })
        assertEquals(3, group.first().tupletSpan?.count)
        assertEquals(DurationBase.EIGHTH, group.first().tupletSpan?.beatUnit)
    }

    @Test
    fun insertInsideTupletUsesDisplayedDurationWithTupletRatio() {
        val base = emptyScore()
        val withTuplet = NoteEditEngine.insert(
            base,
            NoteEditEngine.Insertion(
                voiceTrackId = base.vtId(),
                start = tc(1, 0),
                duration = Duration.QUARTER,
                pitch = Pitch.C4,
                tupletCount = 3,
            ),
        )!!.score

        val result = NoteEditEngine.insert(
            withTuplet,
            NoteEditEngine.Insertion(
                voiceTrackId = withTuplet.vtId(),
                start = tc(1, 1, 12),
                duration = Duration.EIGHTH,
                pitch = Pitch.D4,
            ),
        )!!.score

        val group = result.events().filter { it.onset < tc(1, 1, 4) }.sortedBy { it.onset }
        assertEquals(3, group.size)
        assertEquals(listOf(tc(1, 0), tc(1, 1, 12), tc(1, 1, 6)), group.map { it.onset })
        assertEquals(listOf(Pitch.C4), group[0].pitches)
        assertEquals(listOf(Pitch.D4), group[1].pitches)
        assertTrue(group[2].isRest)
        assertEquals(List(3) { Duration(DurationBase.EIGHTH, tuplet = Tuplet(3, 2)) }, group.map { it.duration })
        assertEquals(3, group.first().tupletSpan?.count)
    }

    @Test
    fun insertShorterDisplayedValueInsideTupletKeepsExactActualDuration() {
        val base = emptyScore()
        val withTuplet = NoteEditEngine.insert(
            base,
            NoteEditEngine.Insertion(base.vtId(), tc(1, 0), Duration.QUARTER, Pitch.C4, tupletCount = 3),
        )!!.score

        val result = NoteEditEngine.insert(
            withTuplet,
            NoteEditEngine.Insertion(withTuplet.vtId(), tc(1, 1, 12), Duration.SIXTEENTH, Pitch.D4),
        )!!.score
        val group = result.events().filter { it.onset < tc(1, 1, 4) }.sortedBy { it.onset }

        assertEquals(listOf(tc(1, 0), tc(1, 1, 12), tc(1, 1, 8), tc(1, 1, 6)), group.map { it.onset })
        assertEquals(
            listOf(DurationBase.EIGHTH, DurationBase.SIXTEENTH, DurationBase.SIXTEENTH, DurationBase.EIGHTH),
            group.map { it.duration.base },
        )
        assertEquals(Fraction(1, 24), group[1].duration.toFraction(), "a triplet sixteenth is 1/6 beat")
        assertTrue(group[2].isRest)
        assertTrue(group.all { it.duration.tuplet == Tuplet(3, 2) })
    }

    // ---- deletion --------------------------------------------------------------------------------

    private fun RuntimeScore.addChord(
        idTag: String, onset: TimeCode, pitches: List<Pitch>, duration: Duration,
    ): RuntimeScore {
        val pe = RuntimePitchEvent(id = EventId("p-$idTag"), onset = onset, pitches = pitches)
        val ve = RuntimeVoiceEvent(id = EventId(idTag), onset = onset, pitchEvent = pe, duration = duration)
        return addPitchEvent(ptId(), pe).addVoiceEvent(vtId(), ve)
    }

    private fun delete(runtime: RuntimeScore, eventId: String, pitchIndices: Set<Int>? = null): RuntimeScore =
        NoteEditEngine.delete(
            runtime, NoteEditEngine.Deletion(runtime.vtId(), EventId(eventId), pitchIndices),
        )!!.score

    @Test
    fun deleteTupletMemberReplacesItWithTupletRest() {
        val base = emptyScore()
        var score = NoteEditEngine.insert(
            base,
            NoteEditEngine.Insertion(base.vtId(), tc(1, 0), Duration.QUARTER, Pitch.C4, tupletCount = 3),
        )!!.score
        score = NoteEditEngine.insert(
            score,
            NoteEditEngine.Insertion(score.vtId(), tc(1, 1, 12), Duration.EIGHTH, Pitch.D4),
        )!!.score
        val middle = score.events().single { it.onset == tc(1, 1, 12) }

        val result = NoteEditEngine.delete(
            score, NoteEditEngine.Deletion(score.vtId(), middle.id),
        )!!.score
        val group = result.events().filter { it.onset < tc(1, 1, 4) }.sortedBy { it.onset }

        assertEquals(3, group.size)
        assertTrue(group[1].isRest)
        assertEquals(Duration(DurationBase.EIGHTH, tuplet = Tuplet(3, 2)), group[1].duration)
        assertEquals(3, group.first().tupletSpan?.count)
    }

    @Test
    fun deleteNoteBecomesRest() {
        val base = emptyScore().addNote("c", tc(1, 0), Pitch.C4, Duration.QUARTER)
        val e = delete(base, "c").events().single()
        assertTrue(e.isRest)
        assertEquals(tc(1, 0), e.onset)
        assertEquals(Duration.QUARTER, e.duration)
    }

    @Test
    fun deleteHalfNoteFillsBeatAlignedRests() {
        // A half note at beat 0 is metrically aligned, so it re-fills as a SINGLE half rest — the
        // span is not fragmented into one rest per beat.
        val base = emptyScore().addNote("h", tc(1, 0), Pitch.C4, Duration.HALF)
        val rests = delete(base, "h").events().sortedBy { it.onset }
        assertTrue(rests.all { it.isRest })
        assertEquals(listOf(Duration.HALF), rests.map { it.duration })
        assertEquals(listOf(tc(1, 0)), rests.map { it.onset })
    }

    @Test
    fun deleteChordPitchKeepsRemaining() {
        val base = emptyScore().addChord("c", tc(1, 0), listOf(Pitch.C4, Pitch.E4, Pitch.G4), Duration.QUARTER)
        val events = delete(base, "c", setOf(1)).events()  // remove E4
        assertEquals(1, events.size)
        assertTrue(!events[0].isRest)
        assertEquals(listOf(Pitch.C4, Pitch.G4), events[0].pitches)
    }

    @Test
    fun deleteAllChordPitchesBecomesRest() {
        val base = emptyScore().addChord("c", tc(1, 0), listOf(Pitch.C4, Pitch.E4), Duration.QUARTER)
        val e = delete(base, "c", setOf(0, 1)).events().single()
        assertTrue(e.isRest)
    }

    @Test
    fun deleteRestRefillsSpanAsRest() {
        val base = emptyScore().addNote("r", tc(1, 0), pitch = null, duration = Duration.QUARTER)
        val e = delete(base, "r").events().single()
        assertTrue(e.isRest)
        assertEquals(tc(1, 0), e.onset)
        assertEquals(Duration.QUARTER, e.duration)
    }

    @Test
    fun deleteWholeNoteBecomesSingleWholeRest() {
        // A full 4/4 bar merges into one whole rest, not four quarter rests.
        val base = emptyScore().addNote("w", tc(1, 0), Pitch.C4, Duration.WHOLE)
        val e = delete(base, "w").events().single()
        assertTrue(e.isRest)
        assertEquals(Duration.WHOLE, e.duration)
        assertEquals(tc(1, 0), e.onset)
    }

    @Test
    fun deleteOffbeatHalfDoesNotMergeAcrossMidbar() {
        // A half note starting on beat 2 ([1/4, 3/4)) straddles the bar midpoint, so a half rest there
        // is illegal: it must stay two quarter rests (short→long is moot for equal pieces).
        val base = emptyScore().addNote("h", tc(1, 1, 4), Pitch.C4, Duration.HALF)
        val rests = delete(base, "h").events().filter { it.isRest && it.onset >= tc(1, 1, 4) && it.onset < tc(1, 3, 4) }
            .sortedBy { it.onset }
        assertEquals(listOf(Duration.QUARTER, Duration.QUARTER), rests.map { it.duration })
        assertEquals(listOf(tc(1, 1, 4), tc(1, 1, 2)), rests.map { it.onset })
    }

    @Test
    fun deleteMiddleOfSixteenthRunMergesRests() {
        // 4/4 bar filled with 16 sixteenth notes; delete the middle 14, keeping only the first & last.
        // The freed span [1/16, 15/16) must re-engrave as merged rests (16th+8th+quarter+quarter+8th+16th),
        // NOT 14 separate sixteenth rests.
        var rt = emptyScore()
        for (k in 0 until 16) rt = rt.addNote("n$k", tc(1, k, 16), Pitch.C4, Duration.SIXTEENTH)
        for (k in 1..14) rt = delete(rt, "n$k")

        val events = rt.events().sortedBy { it.onset }
        val notes = events.filter { !it.isRest }
        assertEquals(listOf(tc(1, 0, 16), tc(1, 15, 16)), notes.map { it.onset })
        val rests = events.filter { it.isRest }
        assertEquals(
            listOf(
                Duration.SIXTEENTH, Duration.EIGHTH, Duration.QUARTER,
                Duration.QUARTER, Duration.EIGHTH, Duration.SIXTEENTH,
            ),
            rests.map { it.duration },
        )
        assertEquals(tc(1, 1, 16), rests.first().onset)
    }

    @Test
    fun deleteUnknownEventIsNoOp() {
        val base = emptyScore().addNote("c", tc(1, 0), Pitch.C4, Duration.QUARTER)
        assertNull(NoteEditEngine.delete(base, NoteEditEngine.Deletion(base.vtId(), EventId("nope"))))
    }

    // ---- in-place property edits -----------------------------------------------------------------

    private fun RuntimeScore.durationEdit(id: String, duration: Duration) =
        NoteEditEngine.DurationEdit(vtId(), EventId(id), duration)

    @Test
    fun editDurationShrinkFillsFreedTailWithRest() {
        // A half note at beat 0 shrunk to a quarter → quarter note, then the freed [1/4, 1/2) plus the
        // rest of the bar are re-engraved as rests.
        val base = emptyScore().addNote("h", tc(1, 0), Pitch.C4, Duration.HALF)
        val outcome = NoteEditEngine.editDurations(base, listOf(base.durationEdit("h", Duration.QUARTER)))
        assertTrue(outcome is NoteEditEngine.EditOutcome.Changed)
        val events = (outcome as NoteEditEngine.EditOutcome.Changed).score.events().sortedBy { it.onset }
        assertEquals(Duration.QUARTER, events[0].duration)
        assertEquals(listOf(Pitch.C4), events[0].pitches)
        assertTrue(events.drop(1).all { it.isRest })
    }

    @Test
    fun editDurationGrowIntoRestIsAllowed() {
        // A lone quarter in an otherwise empty 4/4 bar grown to a half: nothing to overlap, so it grows.
        val base = emptyScore().addNote("q", tc(1, 0), Pitch.C4, Duration.QUARTER)
        val outcome = NoteEditEngine.editDurations(base, listOf(base.durationEdit("q", Duration.HALF)))
        assertTrue(outcome is NoteEditEngine.EditOutcome.Changed)
        val notes = (outcome as NoteEditEngine.EditOutcome.Changed).score.events().filter { !it.isRest }
        assertEquals(1, notes.size)
        assertEquals(Duration.HALF, notes[0].duration)
        assertEquals(tc(1, 0), notes[0].onset)
    }

    @Test
    fun editTwoAdjacentQuartersToHalfIsRejected() {
        // The spec case: two adjacent quarter notes both changed to half would overlap → whole batch rejected.
        val base = emptyScore()
            .addNote("a", tc(1, 0), Pitch.C4, Duration.QUARTER)
            .addNote("b", tc(1, 1, 4), Pitch.D4, Duration.QUARTER)
        val outcome = NoteEditEngine.editDurations(
            base, listOf(base.durationEdit("a", Duration.HALF), base.durationEdit("b", Duration.HALF)),
        )
        assertTrue(outcome is NoteEditEngine.EditOutcome.Conflict)
    }

    @Test
    fun editAccidentalSetsSharpKeepingNoteName() {
        val base = emptyScore().addNote("c", tc(1, 0), Pitch.C4, Duration.QUARTER)
        val outcome = NoteEditEngine.editAccidentals(
            base, listOf(NoteEditEngine.AccidentalEdit(base.vtId(), EventId("c"), Accidental.SHARP)),
        )
        assertTrue(outcome is NoteEditEngine.EditOutcome.Changed)
        val note = (outcome as NoteEditEngine.EditOutcome.Changed).score.events().single { !it.isRest }
        assertEquals(Pitch(0, 1), note.pitches.single()) // C#4: same diatonic step, +1 chromatic
    }

    @Test
    fun editAccidentalNullRevertsToKeyDefault() {
        // C#4 with the accidental cleared spells back to C natural under C major.
        val base = emptyScore().addNote("cs", tc(1, 0), Pitch(0, 1), Duration.QUARTER)
        val outcome = NoteEditEngine.editAccidentals(
            base, listOf(NoteEditEngine.AccidentalEdit(base.vtId(), EventId("cs"), null)),
        )
        assertTrue(outcome is NoteEditEngine.EditOutcome.Changed)
        val note = (outcome as NoteEditEngine.EditOutcome.Changed).score.events().single { !it.isRest }
        assertEquals(Pitch(0, 0), note.pitches.single())
    }

    @Test
    fun editTieAddsAndRemovesTrailingTie() {
        val base = emptyScore()
            .addNote("a", tc(1, 0), Pitch.C4, Duration.HALF)
            .addNote("b", tc(1, 1, 2), Pitch.C4, Duration.HALF)
        val tied = NoteEditEngine.editTies(base, listOf(NoteEditEngine.TieEdit(base.vtId(), EventId("a"), true)))
        assertTrue(tied is NoteEditEngine.EditOutcome.Changed)
        val a = (tied as NoteEditEngine.EditOutcome.Changed).score.events().single { it.id == EventId("a") }
        assertEquals(listOf(RuntimeTieInfo(0, false)), a.ties)

        val untied = NoteEditEngine.editTies(tied.score, listOf(NoteEditEngine.TieEdit(base.vtId(), EventId("a"), false)))
        assertTrue(untied is NoteEditEngine.EditOutcome.Changed)
        val a2 = (untied as NoteEditEngine.EditOutcome.Changed).score.events().single { it.id == EventId("a") }
        assertTrue(a2.ties.isEmpty())
    }

    @Test
    fun editGrowOverUnselectedNoteIsAllowed() {
        // Two adjacent quarters; grow only the FIRST to a half. The second note is unselected (not in
        // the batch), so it may be overwritten — the grow is allowed, not a conflict.
        val base = emptyScore()
            .addNote("a", tc(1, 0), Pitch.C4, Duration.QUARTER)
            .addNote("b", tc(1, 1, 4), Pitch.D4, Duration.QUARTER)
        val outcome = NoteEditEngine.editDurations(base, listOf(base.durationEdit("a", Duration.HALF)))
        assertTrue(outcome is NoteEditEngine.EditOutcome.Changed)
        val notes = (outcome as NoteEditEngine.EditOutcome.Changed).score.events()
            .filter { !it.isRest }.sortedBy { it.onset }
        // 'a' is now a half at beat 0; the overwritten 'b' is gone.
        assertEquals(listOf(tc(1, 0)), notes.map { it.onset })
        assertEquals(Duration.HALF, notes[0].duration)
        assertEquals(listOf(Pitch.C4), notes[0].pitches)
    }

    @Test
    fun editAccidentalOnSelectedChordPitchOnly() {
        // C–E–G chord; sharpen only E (index 1). C and G keep their spelling.
        val base = emptyScore().addChord("c", tc(1, 0), listOf(Pitch.C4, Pitch.E4, Pitch.G4), Duration.QUARTER)
        val outcome = NoteEditEngine.editAccidentals(
            base, listOf(NoteEditEngine.AccidentalEdit(base.vtId(), EventId("c"), Accidental.SHARP, setOf(1))),
        )
        assertTrue(outcome is NoteEditEngine.EditOutcome.Changed)
        val chord = (outcome as NoteEditEngine.EditOutcome.Changed).score.events().single { !it.isRest }
        // E4 (diatonic step 2) becomes E#4 (+1 chromatic); C4 and G4 unchanged.
        assertEquals(listOf(Pitch.C4, Pitch(2, 1), Pitch.G4), chord.pitches)
    }

    @Test
    fun editTieOnSelectedChordPitchOnly() {
        // C–E chord tied to a following C–E chord; tie out only the lower pitch (index 0).
        val base = emptyScore()
            .addChord("a", tc(1, 0), listOf(Pitch.C4, Pitch.E4), Duration.HALF)
            .addChord("b", tc(1, 1, 2), listOf(Pitch.C4, Pitch.E4), Duration.HALF)
        val outcome = NoteEditEngine.editTies(
            base, listOf(NoteEditEngine.TieEdit(base.vtId(), EventId("a"), true, setOf(0))),
        )
        assertTrue(outcome is NoteEditEngine.EditOutcome.Changed)
        val a = (outcome as NoteEditEngine.EditOutcome.Changed).score.events().single { it.id == EventId("a") }
        assertEquals(listOf(RuntimeTieInfo(0, false)), a.ties) // only index 0 tied, index 1 untouched
    }

    @Test
    fun editEmptyOrUnchangedIsNoOp() {
        val base = emptyScore().addNote("c", tc(1, 0), Pitch.C4, Duration.QUARTER)
        assertTrue(NoteEditEngine.editDurations(base, emptyList()) is NoteEditEngine.EditOutcome.NoOp)
        // Same duration → no change.
        assertTrue(
            NoteEditEngine.editDurations(base, listOf(base.durationEdit("c", Duration.QUARTER)))
                is NoteEditEngine.EditOutcome.NoOp,
        )
    }

    @Test
    fun applyTupletToEighthRestAndEighthNoteMakesTripletBeats() {
        val base = emptyScore()
            .addNote("r", tc(1, 0), null, Duration.EIGHTH)
            .addNote("n", tc(1, 1, 8), Pitch.C4, Duration.EIGHTH)

        val outcome = NoteEditEngine.applyTuplets(
            base,
            listOf(NoteEditEngine.TupletEdit(base.vtId(), setOf(EventId("r"), EventId("n")), 3)),
        )
        assertTrue(outcome is NoteEditEngine.EditOutcome.Changed)
        val group = (outcome as NoteEditEngine.EditOutcome.Changed).score.events()
            .filter { it.onset < tc(1, 1, 4) }
            .sortedBy { it.onset }

        assertEquals(listOf(tc(1, 0), tc(1, 1, 12), tc(1, 1, 6)), group.map { it.onset })
        assertEquals(listOf(true, false, true), group.map { it.isRest })
        assertEquals(List(3) { Duration(DurationBase.EIGHTH, tuplet = Tuplet(3, 2)) }, group.map { it.duration })
        assertEquals(3, group.first().tupletSpan?.count)
    }

    @Test
    fun growingLastTupletMemberPastSpanIsRejectedAtomically() {
        val base = emptyScore()
            .addNote("r", tc(1, 0), null, Duration.EIGHTH)
            .addNote("n", tc(1, 1, 8), Pitch.C4, Duration.EIGHTH)
        val tupled = NoteEditEngine.applyTuplets(
            base,
            listOf(NoteEditEngine.TupletEdit(base.vtId(), setOf(EventId("r"), EventId("n")), 3)),
        ) as NoteEditEngine.EditOutcome.Changed
        val tailRest = tupled.score.events()
            .filter { it.onset < tc(1, 1, 4) }
            .sortedBy { it.onset }
            .last()
        val filled = assertNotNull(
            NoteEditEngine.insert(
                tupled.score,
                NoteEditEngine.Insertion(
                    voiceTrackId = tupled.score.vtId(),
                    start = tailRest.onset,
                    duration = Duration.EIGHTH,
                    pitch = Pitch.E4,
                ),
            ),
        ).score
        val last = filled.events()
            .filter { !it.isRest && it.onset < tc(1, 1, 4) }
            .maxBy { it.onset }

        val outcome = NoteEditEngine.editDurations(
            filled,
            listOf(
                NoteEditEngine.DurationEdit(
                    voiceTrackId = filled.vtId(),
                    eventId = last.id,
                    duration = Duration.QUARTER,
                ),
            ),
        )

        assertTrue(outcome is NoteEditEngine.EditOutcome.Conflict)
    }

    @Test
    fun shrinkingTupletMemberKeepsTupletRestsAndSpan() {
        val base = emptyScore()
            .addNote("r", tc(1, 0), null, Duration.EIGHTH)
            .addNote("n", tc(1, 1, 8), Pitch.C4, Duration.EIGHTH)
        val tupled = NoteEditEngine.applyTuplets(
            base,
            listOf(NoteEditEngine.TupletEdit(base.vtId(), setOf(EventId("r"), EventId("n")), 3)),
        ) as NoteEditEngine.EditOutcome.Changed

        val outcome = NoteEditEngine.editDurations(
            tupled.score,
            listOf(tupled.score.durationEdit("n", Duration.SIXTEENTH)),
        )
        assertTrue(outcome is NoteEditEngine.EditOutcome.Changed)
        val group = (outcome as NoteEditEngine.EditOutcome.Changed).score.events()
            .filter { it.onset < tc(1, 1, 4) }
            .sortedBy { it.onset }
        assertEquals(3, group.first().tupletSpan?.count)
        assertTrue(group.all { it.duration.tuplet == Tuplet(3, 2) })
    }

    @Test
    fun applyTupletToQuarterNoteOccupiesFirstTwoTripletBeats() {
        val base = emptyScore().addNote("q", tc(1, 0), Pitch.C4, Duration.QUARTER)

        val outcome = NoteEditEngine.applyTuplets(
            base,
            listOf(NoteEditEngine.TupletEdit(base.vtId(), setOf(EventId("q")), 3)),
        )
        assertTrue(outcome is NoteEditEngine.EditOutcome.Changed)
        val group = (outcome as NoteEditEngine.EditOutcome.Changed).score.events()
            .filter { it.onset < tc(1, 1, 4) }
            .sortedBy { it.onset }

        assertEquals(2, group.size)
        assertEquals(Duration(DurationBase.QUARTER, tuplet = Tuplet(3, 2)), group[0].duration)
        assertEquals(tc(1, 0), group[0].onset)
        assertTrue(!group[0].isRest)
        assertEquals(Duration(DurationBase.EIGHTH, tuplet = Tuplet(3, 2)), group[1].duration)
        assertEquals(tc(1, 1, 6), group[1].onset)
        assertTrue(group[1].isRest)
    }

    @Test
    fun applyTupletRejectsCrossMeasureSelection() {
        val base = emptyScore()
            .addNote("a", tc(1, 3, 4), Pitch.C4, Duration.QUARTER)
            .addNote("b", tc(2, 0), Pitch.D4, Duration.QUARTER)

        val outcome = NoteEditEngine.applyTuplets(
            base,
            listOf(NoteEditEngine.TupletEdit(base.vtId(), setOf(EventId("a"), EventId("b")), 3)),
        )
        assertTrue(outcome is NoteEditEngine.EditOutcome.Conflict)
    }

    // ---- voice moves -----------------------------------------------------------------------------

    @Test
    fun moveSelectedChordPitchSplitsChordIntoTargetVoice() {
        val base = emptyScore().addChord(
            "c",
            tc(1, 0),
            listOf(Pitch.C4, Pitch.E4, Pitch.G4),
            Duration.QUARTER,
        )
        val result = NoteEditEngine.moveVoices(
            base,
            listOf(NoteEditEngine.VoiceMoveTarget(base.vtId(), EventId("c"), 2, setOf(1))),
        )!!

        val voice1 = result.score.voiceTracks.values.single { it.voiceNumber == 1 }
        val voice2 = result.score.voiceTracks.values.single { it.voiceNumber == 2 }
        assertEquals(listOf(Pitch.C4, Pitch.G4), voice1.events.toList().single { !it.isRest }.pitches)
        assertEquals(listOf(Pitch.E4), voice2.events.toList().single { !it.isRest }.pitches)
        assertEquals(1, result.movedEvents.size)
        assertEquals(setOf(0), result.movedEvents.single().pitchIndices)
    }

    @Test
    fun moveWholeNoteToTargetVoiceFillsSourceWithRest() {
        val base = emptyScore().addNote("n", tc(1, 0), Pitch.C4, Duration.QUARTER)
        val result = NoteEditEngine.moveVoices(
            base,
            listOf(NoteEditEngine.VoiceMoveTarget(base.vtId(), EventId("n"), 4)),
        )!!

        val voice1 = result.score.voiceTracks.values.single { it.voiceNumber == 1 }
        val voice4 = result.score.voiceTracks.values.single { it.voiceNumber == 4 }
        val sourceEvents = voice1.events.toList().sortedBy { it.onset }
        assertEquals(tc(1, 0), sourceEvents.first().onset)
        assertEquals(Duration.QUARTER, sourceEvents.first().duration)
        assertTrue(sourceEvents.first().isRest)
        assertEquals(listOf(Pitch.C4), voice4.events.toList().single { !it.isRest }.pitches)
        assertEquals(setOf(0), result.movedEvents.single().pitchIndices)
    }

    @Test
    fun moveWholeEventPermutationSwapsVoicesAtomicallyAndPreservesIds() {
        val withUpper = emptyScore().addNote("upper", tc(1, 0), Pitch.C5, Duration.QUARTER)
        val lower = NoteEditEngine.insert(
            withUpper,
            NoteEditEngine.Insertion(
                voiceTrackId = withUpper.vtId(),
                start = tc(1, 0),
                duration = Duration.QUARTER,
                pitch = Pitch.C4,
                staffTrackId = withUpper.staffId(),
                voiceNumber = 2,
            ),
        )!!
        val lowerVoice = lower.score.voiceTracks.values.single { it.voiceNumber == 2 }
        val result = NoteEditEngine.moveVoices(
            lower.score,
            listOf(
                NoteEditEngine.VoiceMoveTarget(withUpper.vtId(), EventId("upper"), 2),
                NoteEditEngine.VoiceMoveTarget(lowerVoice.id, lower.insertedEventId!!, 1),
            ),
        )!!

        assertTrue(result.score.voiceTracks.getValue(lowerVoice.id).events.any { it.id == EventId("upper") })
        assertTrue(result.score.voiceTracks.getValue(withUpper.vtId()).events.any { it.id == lower.insertedEventId })
        assertEquals(setOf(EventId("upper"), lower.insertedEventId), result.movedEvents.map { it.eventId }.toSet())
    }

    @Test
    fun moveToTargetVoiceWithMismatchedDurationUsesInsertClearInterval() {
        val initial = emptyScore()
        val withVoice2 = NoteEditEngine.insert(
            initial,
            NoteEditEngine.Insertion(
                voiceTrackId = initial.vtId(),
                start = tc(1, 0),
                duration = Duration.HALF,
                pitch = Pitch.E4,
                staffTrackId = initial.staffId(),
                voiceNumber = 2,
            ),
        )!!.score
        val base = withVoice2.addNote("n", tc(1, 0), Pitch.C4, Duration.QUARTER)
        val result = NoteEditEngine.moveVoices(
            base,
            listOf(NoteEditEngine.VoiceMoveTarget(base.vtId(), EventId("n"), 2)),
        )!!

        val voice2Notes = result.score.voiceTracks.values.single { it.voiceNumber == 2 }
            .events.toList()
            .filter { !it.isRest }
            .sortedBy { it.onset }
        assertEquals(listOf(Pitch.C4), voice2Notes[0].pitches)
        assertEquals(Duration.QUARTER, voice2Notes[0].duration)
        assertEquals(listOf(Pitch.E4), voice2Notes[1].pitches)
        assertEquals(tc(1, 1, 4), voice2Notes[1].onset)
        assertEquals(Duration.QUARTER, voice2Notes[1].duration)
    }

    @Test
    fun movePitchToExistingSamePitchDoesNotDuplicateTargetChord() {
        val initial = emptyScore()
        val withVoice2 = NoteEditEngine.insert(
            initial,
            NoteEditEngine.Insertion(
                voiceTrackId = initial.vtId(),
                start = tc(1, 0),
                duration = Duration.QUARTER,
                pitch = Pitch.E4,
                staffTrackId = initial.staffId(),
                voiceNumber = 2,
            ),
        )!!.score
        val base = withVoice2.addChord(
            "c",
            tc(1, 0),
            listOf(Pitch.C4, Pitch.E4),
            Duration.QUARTER,
        )
        val result = NoteEditEngine.moveVoices(
            base,
            listOf(NoteEditEngine.VoiceMoveTarget(base.vtId(), EventId("c"), 2, setOf(1))),
        )!!

        val voice2 = result.score.voiceTracks.values.single { it.voiceNumber == 2 }
        assertEquals(listOf(Pitch.E4), voice2.events.toList().single { !it.isRest }.pitches)
    }

    // ---- moveRest --------------------------------------------------------------------------------

    @Test
    fun moveRestSetsDisplayPositionAndTouchesItsMeasure() {
        // A quarter rest at beat 0 of m2; move it up to staff position 4.
        val base = emptyScore().addNote("r", tc(2, 0), null, Duration.QUARTER)
        val outcome = NoteEditEngine.moveRest(
            base, listOf(NoteEditEngine.RestMoveTarget(base.vtId(), EventId("r"), staffPosition = 4)),
        )
        assertTrue(outcome is NoteEditEngine.EditOutcome.Changed)
        val changed = outcome as NoteEditEngine.EditOutcome.Changed
        val rest = changed.score.events().single { it.id == EventId("r") }
        assertEquals(4, rest.rendering?.restStaffPosition)
        // Only the rest's own (whole) measure is touched.
        assertEquals(listOf(TimeRange(tc(2, 0), tc(3, 0))), changed.intervals)
        assertEquals(listOf(EventId("r")), changed.resultEventIds)
    }

    @Test
    fun moveRestNullResetsOverride() {
        val base = emptyScore().addNote("r", tc(1, 0), null, Duration.QUARTER)
        val moved = NoteEditEngine.moveRest(
            base, listOf(NoteEditEngine.RestMoveTarget(base.vtId(), EventId("r"), staffPosition = -3)),
        ) as NoteEditEngine.EditOutcome.Changed
        // Reset back to default (null) clears the override.
        val reset = NoteEditEngine.moveRest(
            moved.score, listOf(NoteEditEngine.RestMoveTarget(base.vtId(), EventId("r"), staffPosition = null)),
        )
        assertTrue(reset is NoteEditEngine.EditOutcome.Changed)
        val rest = (reset as NoteEditEngine.EditOutcome.Changed).score.events().single { it.id == EventId("r") }
        assertNull(rest.rendering?.restStaffPosition)
    }

    @Test
    fun moveRestIgnoresNotesAndUnchangedPositions() {
        // Target is a note, not a rest → ignored.
        val withNote = emptyScore().addNote("n", tc(1, 0), Pitch.C4, Duration.QUARTER)
        assertTrue(
            NoteEditEngine.moveRest(withNote, listOf(NoteEditEngine.RestMoveTarget(withNote.vtId(), EventId("n"), 4)))
                is NoteEditEngine.EditOutcome.NoOp,
        )
        // A rest already at the requested override → no change.
        val withRest = emptyScore().addNote("r", tc(1, 0), null, Duration.QUARTER)
        val moved = NoteEditEngine.moveRest(
            withRest, listOf(NoteEditEngine.RestMoveTarget(withRest.vtId(), EventId("r"), 2)),
        ) as NoteEditEngine.EditOutcome.Changed
        assertTrue(
            NoteEditEngine.moveRest(moved.score, listOf(NoteEditEngine.RestMoveTarget(withRest.vtId(), EventId("r"), 2)))
                is NoteEditEngine.EditOutcome.NoOp,
        )
    }

    @Test
    fun copyPasteKeepsAbsolutePitchAndCapturedBeaming() {
        val base = emptyScore()
            .addChord(
                "a",
                tc(1, 0),
                listOf(Pitch(0, 1)),
                Duration(DurationBase.EIGHTH),
                RenderingProps(beaming = BeamingInfo.start()),
            )
            .addChord(
                "b",
                tc(1, 1, 8),
                listOf(Pitch.E4),
                Duration(DurationBase.EIGHTH),
                RenderingProps(beaming = BeamingInfo.end()),
            )

        val clipboard = NoteEditEngine.copyNotes(
            base,
            listOf(
                NoteEditEngine.CopyTarget(base.vtId(), EventId("a"), beaming = BeamingInfo.start()),
                NoteEditEngine.CopyTarget(base.vtId(), EventId("b"), beaming = BeamingInfo.end()),
            ),
        )!!
        val pasted = NoteEditEngine.pasteNotes(
            base,
            clipboard,
            NoteEditEngine.PasteTarget(base.vtId(), tc(2, 0)),
        )!!

        val notes = pasted.score.events().filter { !it.isRest && it.onset.measure == 2 }.sortedBy { it.onset }
        assertEquals(2, notes.size)
        assertEquals(listOf(Pitch(0, 1)), notes[0].pitches)
        assertEquals(listOf(Pitch.E4), notes[1].pitches)
        assertEquals(BeamingInfo.start(), notes[0].rendering?.beaming)
        assertEquals(BeamingInfo.end(), notes[1].rendering?.beaming)
    }

    @Test
    fun copyPasteSelectedChordPitchesOnly() {
        val base = emptyScore()
            .addChord("c", tc(1, 0), listOf(Pitch.C4, Pitch.E4, Pitch.G4), Duration.QUARTER)

        val clipboard = NoteEditEngine.copyNotes(
            base,
            listOf(NoteEditEngine.CopyTarget(base.vtId(), EventId("c"), pitchIndices = setOf(1))),
        )!!
        val pasted = NoteEditEngine.pasteNotes(
            base,
            clipboard,
            NoteEditEngine.PasteTarget(base.vtId(), tc(1, 1, 2)),
        )!!

        val pastedNote = pasted.pastedEventIds.single().let { id ->
            pasted.score.events().single { it.id == id }
        }
        assertEquals(tc(1, 1, 2), pastedNote.onset)
        assertEquals(listOf(Pitch.E4), pastedNote.pitches)
    }

    @Test
    fun copyPasteTupletSubsetRebuildsTupletSpanAtTarget() {
        val tupletDuration = Duration(DurationBase.EIGHTH, tuplet = Tuplet(3, 2))
        val withoutSpan = emptyScore()
            .addNote("a", tc(1, 0), Pitch.C4, tupletDuration)
            .addNote("b", tc(1, 1, 12), Pitch.D4, tupletDuration)
            .addNote("c", tc(1, 1, 6), Pitch.E4, tupletDuration)
        val start = withoutSpan.events().single { it.id == EventId("a") }.copy(
            tupletSpan = TupletSpan(tc(1, 1, 4), count = 3, beatUnit = DurationBase.EIGHTH),
        )
        val source = withoutSpan
            .removeVoiceEvent(withoutSpan.vtId(), start.id)
            .addVoiceEvent(withoutSpan.vtId(), start)
        val clipboard = NoteEditEngine.copyNotes(
            source,
            listOf(
                NoteEditEngine.CopyTarget(source.vtId(), EventId("b")),
                NoteEditEngine.CopyTarget(source.vtId(), EventId("c")),
            ),
        )!!
        val pasted = NoteEditEngine.pasteNotes(
            source,
            clipboard,
            NoteEditEngine.PasteTarget(source.vtId(), tc(2, 0)),
        )!!

        val group = pasted.score.events()
            .filter { it.onset.measure == 2 && it.onset < tc(2, 1, 4) }
            .sortedBy { it.onset }
        assertEquals(3, group.size)
        assertEquals(listOf(tc(2, 0), tc(2, 1, 12), tc(2, 1, 6)), group.map { it.onset })
        assertEquals(listOf(true, false, false), group.map { it.isRest })
        assertEquals(listOf(emptyList(), listOf(Pitch.D4), listOf(Pitch.E4)), group.map { it.pitches })
        assertEquals(List(3) { tupletDuration }, group.map { it.duration })
        assertEquals(3, group[0].tupletSpan?.count)
        assertEquals(DurationBase.EIGHTH, group[0].tupletSpan?.beatUnit)
        assertEquals(tc(2, 1, 4), group[0].tupletSpan?.endTimeCode)
        assertNull(group[1].tupletSpan)
        assertNull(group[2].tupletSpan)
    }

    @Test
    fun captureReplacesOnlyTakeSpanAndKeepsPerPitchTies() {
        val base = emptyScore()
            .addNote("old", tc(1, 0), Pitch.G4, Duration.HALF)
        val capture = NoteEditEngine.CaptureInsertion(
            voiceTrackId = base.vtId(),
            staffTrackId = base.staffId(),
            voiceNumber = 1,
            start = tc(1, 0),
            end = tc(1, 1, 2),
            cells = listOf(
                NoteEditEngine.ChordInsertion(
                    voiceTrackId = base.vtId(),
                    start = tc(1, 0),
                    duration = Duration.QUARTER,
                    pitches = listOf(Pitch.C4, Pitch.E4),
                    tieOutMidi = setOf(Pitch.C4.midiNumber),
                ),
                NoteEditEngine.ChordInsertion(
                    voiceTrackId = base.vtId(),
                    start = tc(1, 1, 4),
                    duration = Duration.QUARTER,
                    pitches = listOf(Pitch.C4),
                ),
            ),
        )

        val result = NoteEditEngine.insertCapture(base, capture)!!
        val notes = result.score.events().filterNot { it.isRest }.sortedBy { it.onset }

        assertEquals(listOf(tc(1, 0), tc(1, 1, 4)), notes.map { it.onset })
        assertEquals(listOf(Pitch.C4, Pitch.E4), notes[0].pitches)
        assertEquals(setOf(0), notes[0].ties.mapTo(linkedSetOf()) { it.pitchIndex })
        assertEquals(listOf(Pitch.C4), notes[1].pitches)
        assertEquals(TimeRange(tc(1, 0), tc(2, 0)), result.editInterval)
    }

    @Test
    fun atomicChordCanCreateTupletGroupWithoutDroppingChordTones() {
        val base = emptyScore()
        val result = NoteEditEngine.insertChord(
            base,
            NoteEditEngine.ChordInsertion(
                voiceTrackId = base.vtId(),
                start = tc(1, 0),
                duration = Duration.QUARTER,
                pitches = listOf(Pitch.C4, Pitch.E4),
                tieOutMidi = setOf(Pitch.C4.midiNumber),
                tupletCount = 3,
            ),
        )!!
        val group = result.score.events()
            .filter { it.onset < tc(1, 1, 4) }
            .sortedBy { it.onset }

        assertEquals(3, group.size)
        assertEquals(listOf(Pitch.C4, Pitch.E4), group.first().pitches)
        assertEquals(setOf(0), group.first().ties.mapTo(linkedSetOf()) { it.pitchIndex })
        assertEquals(3, group.first().tupletSpan?.count)
        assertEquals(listOf(false, true, true), group.map { it.isRest })
    }
}
