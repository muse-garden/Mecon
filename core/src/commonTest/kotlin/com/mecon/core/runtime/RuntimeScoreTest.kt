package com.mecon.api.runtime

import com.mecon.api.primitive.*
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.storage.PageArrangement
import com.mecon.api.storage.PageLayoutConfig
import com.mecon.api.storage.ScoreViewPreferences
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.TieInfo
import com.mecon.api.storage.tracks.StorageGlobalTrack
import com.mecon.api.storage.tracks.StorageSystemBreak
import kotlin.test.*

class RuntimeScoreTest {

    /**
     * Regression: an in-memory edit recommits via [RuntimeScore.toStorage]. It must round-trip the
     * pagination / view-preference / global-track fields, or every note edit silently reverts the
     * paginate toggle to off (toolbar arrangement button vanishes), flips the page arrangement back to
     * vertical, and drops forced system/page breaks.
     */
    @Test
    fun testToStorageRoundTripsLayoutAndViewState() {
        val base = StorageScore.createDemo()
        val storage = base.copy(
            pageLayout = PageLayoutConfig.DEFAULT.copy(paginated = true),
            viewPreferences = ScoreViewPreferences(pageArrangement = PageArrangement.HORIZONTAL),
            globalTrack = StorageGlobalTrack.create().let {
                it.copy(events = it.events + StorageSystemBreak(TimeCode.of(2, Fraction.ZERO)))
            }
        )

        val roundTripped = RuntimeScore.fromStorage(storage).toStorage()

        assertTrue(roundTripped.pageLayout.paginated, "paginate toggle lost on round-trip")
        assertEquals(PageArrangement.HORIZONTAL, roundTripped.viewPreferences.pageArrangement)
        assertEquals(
            storage.globalTrack.events, roundTripped.globalTrack.events,
            "forced breaks / global events lost on round-trip"
        )
    }

    @Test
    fun testToStoragePreservesVoiceNotationFields() {
        val base = StorageScore.createDemo()
        val storage = base.copy(voiceTracks = base.voiceTracks.mapValues { (_, track) ->
            track.copy(events = track.events.mapIndexed { index, event ->
                if (index == 0) event.copy(
                    ties = listOf(TieInfo(0, isLetRing = true)),
                    slurStarts = 1,
                    slurEnds = 1,
                ) else event
            })
        })

        val event = RuntimeScore.fromStorage(storage).toStorage()
            .voiceTracks.values.first().events.first()
        assertEquals(storage.voiceTracks.values.first().events.first().ties, event.ties)
        assertEquals(1, event.slurStarts)
        assertEquals(1, event.slurEnds)
    }

    @Test
    fun testToStoragePreservesExplicitDefaultValuedSignatures() {
        val base = StorageScore.create(StorageScore.CreationOptions(measureCount = 3))
        val storage = base.copy(measures = base.measures.map { measure ->
            if (measure.number == 2) measure.copy(
                timeSignature = base.defaultTimeSignature,
                keySignature = base.defaultKeySignature,
            ) else measure
        })

        val roundTripped = RuntimeScore.fromStorage(storage).toStorage()
        assertEquals(base.defaultTimeSignature, roundTripped.getMeasure(2)?.timeSignature)
        assertEquals(base.defaultKeySignature, roundTripped.getMeasure(2)?.keySignature)
    }

    @Test
    fun testCreateFromStorage() {
        val storage = StorageScore.createDemo()
        val runtime = RuntimeScore.fromStorage(storage)

        assertEquals(storage.id, runtime.id)
        assertEquals(storage.metadata.title, runtime.metadata.title)
        assertEquals(4, runtime.measures.size)
        assertEquals(8, runtime.getAllPitchEvents().size)
    }

    @Test
    fun testGetPitchEventsInRange() {
        val storage = StorageScore.createDemo()
        val runtime = RuntimeScore.fromStorage(storage)

        // Get events in first measure
        val eventsM1 = runtime.getPitchEventsInRange(
            TimeCode.of(1, Fraction(0, 1)),
            TimeCode.of(1, Fraction(1, 1))
        )
        assertEquals(4, eventsM1.size)

        // Get events in second measure
        val eventsM2 = runtime.getPitchEventsInRange(
            TimeCode.of(2, Fraction(0, 1)),
            TimeCode.of(2, Fraction(1, 1))
        )
        assertEquals(4, eventsM2.size)
    }

    @Test
    fun testAddAndRemovePitchEvent() {
        var runtime = RuntimeScore.create(title = "Test Score")
        val pitchTrackId = runtime.pitchTracks.keys.first()

        // Add a pitch event (new model: only pitches, no duration)
        val pitchEvent = RuntimePitchEvent.single(
            onset = TimeCode.of(1, Fraction(0, 4)),
            pitch = Pitch.C4
        )
        runtime = runtime.addPitchEvent(pitchTrackId, pitchEvent)

        assertEquals(1, runtime.getAllPitchEvents().size)
        assertNotNull(runtime.findPitchEvent(pitchEvent.id))

        // Remove the event
        runtime = runtime.removePitchEvent(pitchTrackId, pitchEvent.id)

        assertEquals(0, runtime.getAllPitchEvents().size)
        assertNull(runtime.findPitchEvent(pitchEvent.id))
    }

    @Test
    fun testMeasureAccess() {
        val runtime = RuntimeScore.create()

        val measure1 = runtime.getMeasure(1)
        assertNotNull(measure1)
        assertEquals(1, measure1.number)
        assertEquals(TimeSignature.COMMON, measure1.timeSignature)

        assertNull(runtime.getMeasure(100))  // Non-existent measure
    }

    @Test
    fun testTimeSignatureAt() {
        val runtime = RuntimeScore.create(
            timeSignature = TimeSignature.FOUR_FOUR
        )

        assertEquals(TimeSignature.FOUR_FOUR, runtime.getTimeSignatureAt(1))
        assertEquals(TimeSignature.FOUR_FOUR, runtime.getTimeSignatureAt(4))
    }
}

class RuntimePitchEventTest {

    @Test
    fun testSinglePitch() {
        val event = RuntimePitchEvent.single(
            onset = TimeCode.of(1, Fraction(0, 4)),
            pitch = Pitch.C4
        )

        assertEquals(listOf(Pitch.C4), event.pitches)
        assertFalse(event.isRest)
        assertFalse(event.isChord)
        assertEquals(Pitch.C4, event.primaryPitch)
    }

    @Test
    fun testChord() {
        val event = RuntimePitchEvent.chord(
            onset = TimeCode.of(1, Fraction(0, 4)),
            Pitch.C4, Pitch.E4, Pitch.G4
        )

        assertEquals(3, event.pitches.size)
        assertFalse(event.isRest)
        assertTrue(event.isChord)
        assertEquals(Pitch.C4, event.primaryPitch)  // Lowest pitch
    }

    @Test
    fun testRest() {
        val event = RuntimePitchEvent.rest(TimeCode.of(1, Fraction(0, 4)))

        assertTrue(event.pitches.isEmpty())
        assertTrue(event.isRest)
        assertFalse(event.isChord)
        assertNull(event.primaryPitch)
    }
}

class RuntimeVoiceEventTest {

    @Test
    fun testEndTime() {
        val pitchEvent = RuntimePitchEvent.single(
            onset = TimeCode.of(1, Fraction(0, 4)),
            pitch = Pitch.C4
        )
        val voiceEvent = RuntimeVoiceEvent.create(
            onset = pitchEvent.onset,
            pitchEvent = pitchEvent,
            duration = Duration.QUARTER
        )

        val endTime = voiceEvent.endTime
        // Quarter note from beat 0 ends at beat 1/4
        assertEquals(TimeCode.of(1, Fraction(1, 4)), endTime)
    }

    @Test
    fun testOverlaps() {
        val pitchEvent = RuntimePitchEvent.single(
            onset = TimeCode.of(1, Fraction(1, 4)),
            pitch = Pitch.C4
        )
        val voiceEvent = RuntimeVoiceEvent.create(
            onset = pitchEvent.onset,
            pitchEvent = pitchEvent,
            duration = Duration.HALF
        )
        // Event spans from beat 1/4 to beat 3/4

        // Overlapping range
        assertTrue(voiceEvent.overlaps(TimeRange(
            TimeCode.of(1, Fraction(0, 4)),
            TimeCode.of(1, Fraction(2, 4))
        )))

        // Non-overlapping range (before)
        assertFalse(voiceEvent.overlaps(TimeRange(
            TimeCode.of(1, Fraction(0, 4)),
            TimeCode.of(1, Fraction(0, 4))
        )))
    }
}
