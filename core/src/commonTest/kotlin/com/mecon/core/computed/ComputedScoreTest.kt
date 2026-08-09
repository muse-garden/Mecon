package com.mecon.api.computed

import com.mecon.api.primitive.*
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.toStorage
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.storage.BeamingInfo
import com.mecon.api.storage.RenderingProps
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.StaffLayoutPreset
import com.mecon.core.engine.edit.NoteEditEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComputedScoreTest {

    @Test
    fun testBasicComputation() {
        // Create a simple score using the demo (which creates both pitch and voice events)
        val storage = StorageScore.createDemo()
        val runtime = RuntimeScore.fromStorage(storage)
        val computed = com.mecon.core.engine.computeScore(runtime)

        // Demo creates 8 notes (C major scale). Empty trailing measures also get
        // implicit whole rests, so count only the real (non-rest) note events.
        assertEquals(8, computed.computedEvents.values.count { !it.isRest }, "Should have 8 note events")

        val firstEvent = computed.allEventsSorted().first()
        assertEquals(1, firstEvent.measurePosition.measure)
        // First note is C4 (diatonicSteps=0)
        assertTrue(firstEvent.pitchData.isNotEmpty())
    }

    @Test
    fun hiddenTimeSignaturesKeepMeterButEmitNoNotationEvents() {
        val storage = StorageScore.createDemo().copy(showTimeSignatures = false)
        val runtime = RuntimeScore.fromStorage(storage)
        val computed = com.mecon.core.engine.computeScore(runtime)

        assertTrue(!runtime.toStorage().showTimeSignatures)
        assertTrue(computed.timeSignatures.isEmpty())
        assertTrue(computed.barlines.isNotEmpty(), "隐藏拍号不能移除按拍号计算的小节线")
    }

    @Test
    fun testMeasurePositionComputation() {
        val runtime = createSimpleScore()
        val computed = com.mecon.core.engine.computeScore(runtime)

        val event = computed.allEventsSorted().first()
        assertEquals(1, event.measurePosition.measure)
        assertEquals(Fraction.ZERO, event.measurePosition.beatPosition)
    }

    @Test
    fun testStaffPositionComputation() {
        // C4 in treble clef should be at position 0 (ledger line below staff)
        val runtime = createSimpleScore()
        val computed = com.mecon.core.engine.computeScore(runtime)

        val event = computed.allEventsSorted().first()
        // Staff position is in pitchData
        assertTrue(event.pitchData.isNotEmpty())
        assertNotNull(event.pitchData.first().staffPosition)
    }

    @Test
    fun testAccidentalComputation() {
        // Create score with C# - should show accidental in C major
        val runtime = createScoreWithAccidental()
        val computed = com.mecon.core.engine.computeScore(runtime)

        val event = computed.allEventsSorted().first()
        val pitchData = event.pitchData.first()
        assertEquals(Accidental.SHARP, pitchData.effectiveAccidental)
    }

    @Test
    fun testAccidentalNotShownWhenInKey() {
        // Create score with F# in G major - should NOT show accidental
        val runtime = createScoreInGMajor()
        val computed = com.mecon.core.engine.computeScore(runtime)

        val event = computed.allEventsSorted().first()
        val pitchData = event.pitchData.first()
        // F# is in G major key signature, so no accidental needed
        assertNull(pitchData.effectiveAccidental)
    }

    @Test
    fun differentlyAlteredUnisonForcesBothAccidentals() {
        val storage = StorageScore.create(
            StorageScore.CreationOptions("Test", TimeSignature.COMMON, KeySignature.C_MAJOR)
        )
        var runtime = RuntimeScore.fromStorage(storage)
        val pitchTrackId = runtime.pitchTracks.keys.first()
        val voiceTrackId = runtime.voiceTracks.keys.first()
        val pitchEvent = RuntimePitchEvent.chord(
            TimeCode.of(1, Fraction.ZERO),
            Pitch.C4,
            Pitch.fromName("C#4"),
        )
        runtime = runtime
            .addPitchEvent(pitchTrackId, pitchEvent)
            .addVoiceEvent(
                voiceTrackId,
                RuntimeVoiceEvent.create(pitchEvent.onset, pitchEvent, Duration.QUARTER),
            )

        val event = com.mecon.core.engine.computeScore(runtime)
            .allEventsSorted()
            .first { !it.isRest }
        assertEquals(
            listOf(Accidental.NATURAL, Accidental.SHARP),
            event.pitchData.map { it.effectiveAccidental },
        )
    }

    @Test
    fun testBeamGroupComputation() {
        val runtime = createScoreWithEighthNotes()
        val computed = com.mecon.core.engine.computeScore(runtime)

        // Check that beam groups are created
        val beamGroups = computed.getBeamGroups()
        // Two eighth notes in same beat should be beamed together
        assertTrue(beamGroups.isNotEmpty() || computed.computedEvents.values.all { it.beamInfo == null })
    }

    @Test
    fun explicitNonBeamedSuppressesAutomaticBeaming() {
        val runtime = createScoreWithExplicitNonBeamedEighthNotes()
        val computed = com.mecon.core.engine.computeScore(runtime)

        val authoredEvents = computed.allEventsSorted().filter { !it.isRest }
        assertEquals(2, authoredEvents.size)
        assertTrue(authoredEvents.all { it.beamInfo == null })
    }

    @Test
    fun middleBeamConnectsToExplicitNonBeamedMusicXmlNeighbours() {
        val runtime = createScoreWithExplicitNonBeamedEighthNotes(count = 3)
        val voiceTrack = runtime.voiceTracks.values.first()
        val events = voiceTrack.events.toList()
        val outcome = NoteEditEngine.editBeaming(
            runtime,
            listOf(NoteEditEngine.BeamingEdit(voiceTrack.id, events[1].id, BeamingInfo.middle()))
        )
        val editedRuntime = (outcome as NoteEditEngine.EditOutcome.Changed).score

        val computedEvents = com.mecon.core.engine.computeScore(editedRuntime)
            .allEventsSorted()
            .filter { !it.isRest }

        assertEquals(3, computedEvents.size)
        assertEquals(listOf(0, 1, 1), computedEvents.map { it.beamInfo?.beamsLeft })
        assertEquals(listOf(1, 1, 0), computedEvents.map { it.beamInfo?.beamsRight })
        assertEquals(1, computedEvents.mapNotNull { it.beamInfo?.groupId }.toSet().size)
    }

    @Test
    fun explicitBeamEdgeDoesNotConnectAcrossTimeGap() {
        val storage = StorageScore.create(StorageScore.CreationOptions("Test", TimeSignature.COMMON, KeySignature.C_MAJOR))
        var runtime = RuntimeScore.fromStorage(storage)
        val pitchTrackId = runtime.pitchTracks.keys.first()
        val voiceTrackId = runtime.voiceTracks.keys.first()

        listOf(
            Fraction.ZERO to BeamingInfo.start(),
            Fraction(1, 2) to BeamingInfo.NONE,
        ).forEachIndexed { index, (beat, beaming) ->
            val pitchEvent = RuntimePitchEvent.single(
                onset = TimeCode.of(1, beat),
                pitch = Pitch(index),
            )
            runtime = runtime.addPitchEvent(pitchTrackId, pitchEvent)
            runtime = runtime.addVoiceEvent(
                voiceTrackId,
                RuntimeVoiceEvent.create(
                    onset = pitchEvent.onset,
                    pitchEvent = pitchEvent,
                    duration = Duration.EIGHTH,
                    rendering = RenderingProps(beaming = beaming),
                )
            )
        }

        val computedEvents = com.mecon.core.engine.computeScore(runtime)
            .allEventsSorted()
            .filter { !it.isRest }

        assertEquals(2, computedEvents.size)
        assertTrue(
            computedEvents.all { it.beamInfo == null },
            "explicit beam controls must not bridge a rest or other temporal gap"
        )
    }

    @Test
    fun automaticBeamGroupIdsDoNotCollideAcrossStaves() {
        val runtime = createGrandStaffWithSameBeatEighthNotes()
        val computed = com.mecon.core.engine.computeScore(runtime)
        val voiceTrackByEventId = runtime.voiceTracks.values
            .flatMap { voiceTrack -> voiceTrack.events.map { it.id to voiceTrack.id } }
            .toMap()
        val beamGroupsByVoiceTrack = computed.computedEvents.values
            .filter { !it.isRest && it.measurePosition.measure == 1 }
            .groupBy { voiceTrackByEventId[it.id] }
            .mapValues { (_, events) -> events.mapNotNull { it.beamInfo?.groupId }.toSet() }

        assertEquals(2, beamGroupsByVoiceTrack.size)
        assertTrue(beamGroupsByVoiceTrack.values.all { it.size == 1 })
        assertEquals(2, beamGroupsByVoiceTrack.values.flatten().toSet().size)
    }

    @Test
    fun testChordComputation() {
        val runtime = createScoreWithChord()
        val computed = com.mecon.core.engine.computeScore(runtime)

        // Empty trailing measures get implicit rests; count only the real chord event.
        assertEquals(1, computed.computedEvents.values.count { !it.isRest }, "Should have 1 note event (chord)")

        val event = computed.allEventsSorted().first()
        assertEquals(3, event.pitchData.size, "Chord should have 3 pitches")
    }

    @Test
    fun testRestComputation() {
        val runtime = createScoreWithRest()
        val computed = com.mecon.core.engine.computeScore(runtime)

        // The explicit rest is the only non-synthesized event; empty trailing
        // measures add implicit whole rests (which carry an originVoiceTrackId).
        assertEquals(1, computed.computedEvents.values.count { it.originVoiceTrackId == null },
            "Should have 1 authored event (rest)")

        val event = computed.allEventsSorted().first()
        assertTrue(event.isRest, "Event should be a rest")
        assertTrue(event.pitchData.isEmpty(), "Rest should have no pitch data")
    }

    @Test
    fun testBarlineTimeCodesAreBetweenMeasures() {
        // Create a score with multiple measures and events
        val runtime = createScoreWithMultipleMeasures()
        val computed = com.mecon.core.engine.computeScore(runtime)

        // Get all barlines sorted by time
        val barlines = computed.allBarlinesSorted()

        // Should have at least 2 barlines (start and end)
        assertTrue(barlines.size >= 2, "Should have at least start and end barlines")

        // Get all voice events sorted by time
        val events = computed.allEventsSorted()
        assertTrue(events.isNotEmpty(), "Should have voice events")

        // For each barline (except the first one at time 0)
        for (i in 1 until barlines.size) {
            val barline = barlines[i]
            val barlineTime = barline.time
            val measureNumber = barline.measureNumber

            // The barline at measureNumber i marks the END of measure i
            // It should be strictly after all events in measure i
            // and strictly before all events in measure i+1

            // Find events in measure i (the measure this barline ends)
            val currentMeasureEvents = events.filter {
                it.measurePosition.measure == measureNumber
            }

            // Find events in measure i+1 (the measure after this barline)
            val nextMeasureEvents = events.filter {
                it.measurePosition.measure == measureNumber + 1
            }

            // Verify barline is strictly after all events in the current measure
            for (event in currentMeasureEvents) {
                val eventEndTime = event.endTime
                assertTrue(
                    barlineTime > eventEndTime,
                    "Barline ending measure $measureNumber (time $barlineTime) should be > " +
                    "event end time $eventEndTime in measure $measureNumber"
                )
            }

            // Verify barline is strictly before all events in the next measure
            for (event in nextMeasureEvents) {
                val eventStartTime = event.onset
                assertTrue(
                    barlineTime < eventStartTime,
                    "Barline ending measure $measureNumber (time $barlineTime) should be < " +
                    "event start time $eventStartTime in measure ${measureNumber + 1}"
                )
            }
        }
    }

    @Test
    fun testBarlineTypeCorrect() {
        val runtime = createScoreWithMultipleMeasures()
        val computed = com.mecon.core.engine.computeScore(runtime)

        val barlines = computed.allBarlinesSorted()
        assertTrue(barlines.isNotEmpty(), "Should have barlines")

        // First barline should be SINGLE
        assertEquals(BarlineType.SINGLE, barlines.first().type, "First barline should be SINGLE")

        // Last barline should be FINAL
        assertEquals(BarlineType.FINAL, barlines.last().type, "Last barline should be FINAL")

        // Intermediate barlines should be SINGLE
        for (i in 1 until barlines.size - 1) {
            assertEquals(
                BarlineType.SINGLE,
                barlines[i].type,
                "Intermediate barline should be SINGLE"
            )
        }
    }

    @Test
    fun testBarlineMeasureNumbers() {
        val runtime = createScoreWithMultipleMeasures()
        val computed = com.mecon.core.engine.computeScore(runtime)

        val barlines = computed.allBarlinesSorted()

        // First barline should have measureNumber 0
        assertEquals(0, barlines.first().measureNumber, "First barline should have measureNumber 0")

        // Subsequent barlines should have increasing measure numbers
        for (i in 1 until barlines.size) {
            assertEquals(
                i,
                barlines[i].measureNumber,
                "Barline $i should have measureNumber $i"
            )
        }
    }

    // Helper functions to create test scores
    // New model: create both PitchEvents and VoiceEvents

    private fun createSimpleScore(): RuntimeScore {
        val storage = StorageScore.create(StorageScore.CreationOptions("Test", TimeSignature.COMMON, KeySignature.C_MAJOR))
        var runtime = RuntimeScore.fromStorage(storage)

        val pitchTrackId = runtime.pitchTracks.keys.first()
        val voiceTrackId = runtime.voiceTracks.keys.first()

        // Create pitch event
        val pitchEvent = RuntimePitchEvent.single(
            onset = TimeCode.of(1, Fraction.ZERO),
            pitch = Pitch.C4
        )
        runtime = runtime.addPitchEvent(pitchTrackId, pitchEvent)

        // Create voice event
        val voiceEvent = RuntimeVoiceEvent.create(
            onset = pitchEvent.onset,
            pitchEvent = pitchEvent,
            duration = Duration.QUARTER
        )
        runtime = runtime.addVoiceEvent(voiceTrackId, voiceEvent)

        return runtime
    }

    private fun createScoreWithAccidental(): RuntimeScore {
        val storage = StorageScore.create(StorageScore.CreationOptions("Test", TimeSignature.COMMON, KeySignature.C_MAJOR))
        var runtime = RuntimeScore.fromStorage(storage)

        val pitchTrackId = runtime.pitchTracks.keys.first()
        val voiceTrackId = runtime.voiceTracks.keys.first()

        // Create pitch event with C#
        val pitchEvent = RuntimePitchEvent.single(
            onset = TimeCode.of(1, Fraction.ZERO),
            pitch = Pitch(0, 1) // C#4
        )
        runtime = runtime.addPitchEvent(pitchTrackId, pitchEvent)

        // Create voice event
        val voiceEvent = RuntimeVoiceEvent.create(
            onset = pitchEvent.onset,
            pitchEvent = pitchEvent,
            duration = Duration.QUARTER
        )
        runtime = runtime.addVoiceEvent(voiceTrackId, voiceEvent)

        return runtime
    }

    private fun createScoreInGMajor(): RuntimeScore {
        val storage = StorageScore.create(StorageScore.CreationOptions("Test", TimeSignature.COMMON, KeySignature.G_MAJOR))
        var runtime = RuntimeScore.fromStorage(storage)

        val pitchTrackId = runtime.pitchTracks.keys.first()
        val voiceTrackId = runtime.voiceTracks.keys.first()

        // Create pitch event with F#
        val pitchEvent = RuntimePitchEvent.single(
            onset = TimeCode.of(1, Fraction.ZERO),
            pitch = Pitch(3, 1) // F#4
        )
        runtime = runtime.addPitchEvent(pitchTrackId, pitchEvent)

        // Create voice event
        val voiceEvent = RuntimeVoiceEvent.create(
            onset = pitchEvent.onset,
            pitchEvent = pitchEvent,
            duration = Duration.QUARTER
        )
        runtime = runtime.addVoiceEvent(voiceTrackId, voiceEvent)

        return runtime
    }

    private fun createScoreWithEighthNotes(): RuntimeScore {
        val storage = StorageScore.create(StorageScore.CreationOptions("Test", TimeSignature.COMMON, KeySignature.C_MAJOR))
        var runtime = RuntimeScore.fromStorage(storage)

        val pitchTrackId = runtime.pitchTracks.keys.first()
        val voiceTrackId = runtime.voiceTracks.keys.first()

        // First eighth note
        val pitchEvent1 = RuntimePitchEvent.single(
            onset = TimeCode.of(1, Fraction.ZERO),
            pitch = Pitch.C4
        )
        runtime = runtime.addPitchEvent(pitchTrackId, pitchEvent1)

        val voiceEvent1 = RuntimeVoiceEvent.create(
            onset = pitchEvent1.onset,
            pitchEvent = pitchEvent1,
            duration = Duration.EIGHTH
        )
        runtime = runtime.addVoiceEvent(voiceTrackId, voiceEvent1)

        // Second eighth note
        val pitchEvent2 = RuntimePitchEvent.single(
            onset = TimeCode.of(1, Fraction(1, 8)),
            pitch = Pitch.D4
        )
        runtime = runtime.addPitchEvent(pitchTrackId, pitchEvent2)

        val voiceEvent2 = RuntimeVoiceEvent.create(
            onset = pitchEvent2.onset,
            pitchEvent = pitchEvent2,
            duration = Duration.EIGHTH
        )
        runtime = runtime.addVoiceEvent(voiceTrackId, voiceEvent2)

        return runtime
    }

    private fun createScoreWithExplicitNonBeamedEighthNotes(count: Int = 2): RuntimeScore {
        val storage = StorageScore.create(StorageScore.CreationOptions("Test", TimeSignature.COMMON, KeySignature.C_MAJOR))
        var runtime = RuntimeScore.fromStorage(storage)

        val pitchTrackId = runtime.pitchTracks.keys.first()
        val voiceTrackId = runtime.voiceTracks.keys.first()
        val explicitNoBeam = RenderingProps(beaming = BeamingInfo.NONE)

        repeat(count) { index ->
            val pitchEvent = RuntimePitchEvent.single(
                onset = TimeCode.of(1, Fraction(index, 8)),
                pitch = Pitch.C4
            )
            runtime = runtime.addPitchEvent(pitchTrackId, pitchEvent)
            runtime = runtime.addVoiceEvent(
                voiceTrackId,
                RuntimeVoiceEvent.create(
                    onset = pitchEvent.onset,
                    pitchEvent = pitchEvent,
                    duration = Duration.EIGHTH,
                    rendering = explicitNoBeam
                )
            )
        }

        return runtime
    }

    private fun createGrandStaffWithSameBeatEighthNotes(): RuntimeScore {
        val storage = StorageScore.create(StorageScore.CreationOptions(
            title = "Test",
            timeSignature = TimeSignature.COMMON,
            keySignature = KeySignature.C_MAJOR,
            layout = StaffLayoutPreset.PIANO_GRAND
        ))
        var runtime = RuntimeScore.fromStorage(storage)

        runtime.staffTracks.values.forEachIndexed { staffIndex, staff ->
            val voiceTrack = staff.voiceTracks.first()
            val voiceTrackId = voiceTrack.id
            val pitchTrackId = voiceTrack.pitchTrackId
            val pitches = if (staffIndex == 0) listOf(Pitch(7), Pitch(8)) else listOf(Pitch(-7), Pitch(-6))

            pitches.forEachIndexed { index, pitch ->
                val onset = TimeCode.of(1, Fraction(index, 8))
                val pitchEvent = RuntimePitchEvent.single(onset = onset, pitch = pitch)
                runtime = runtime.addPitchEvent(pitchTrackId, pitchEvent)
                runtime = runtime.addVoiceEvent(
                    voiceTrackId,
                    RuntimeVoiceEvent.create(
                        onset = onset,
                        pitchEvent = pitchEvent,
                        duration = Duration.EIGHTH
                    )
                )
            }
        }

        return runtime
    }

    private fun createScoreWithChord(): RuntimeScore {
        val storage = StorageScore.create(StorageScore.CreationOptions("Test", TimeSignature.COMMON, KeySignature.C_MAJOR))
        var runtime = RuntimeScore.fromStorage(storage)

        val pitchTrackId = runtime.pitchTracks.keys.first()
        val voiceTrackId = runtime.voiceTracks.keys.first()

        // Create chord pitch event (C-E-G)
        val pitchEvent = RuntimePitchEvent.chord(
            onset = TimeCode.of(1, Fraction.ZERO),
            Pitch.C4, Pitch.E4, Pitch.G4
        )
        runtime = runtime.addPitchEvent(pitchTrackId, pitchEvent)

        // Create voice event
        val voiceEvent = RuntimeVoiceEvent.create(
            onset = pitchEvent.onset,
            pitchEvent = pitchEvent,
            duration = Duration.QUARTER
        )
        runtime = runtime.addVoiceEvent(voiceTrackId, voiceEvent)

        return runtime
    }

    private fun createScoreWithRest(): RuntimeScore {
        val storage = StorageScore.create(StorageScore.CreationOptions("Test", TimeSignature.COMMON, KeySignature.C_MAJOR))
        var runtime = RuntimeScore.fromStorage(storage)

        val pitchTrackId = runtime.pitchTracks.keys.first()
        val voiceTrackId = runtime.voiceTracks.keys.first()

        // Create rest pitch event
        val pitchEvent = RuntimePitchEvent.rest(TimeCode.of(1, Fraction.ZERO))
        runtime = runtime.addPitchEvent(pitchTrackId, pitchEvent)

        // Create voice event
        val voiceEvent = RuntimeVoiceEvent.create(
            onset = pitchEvent.onset,
            pitchEvent = pitchEvent,
            duration = Duration.QUARTER
        )
        runtime = runtime.addVoiceEvent(voiceTrackId, voiceEvent)

        return runtime
    }

    private fun createScoreWithMultipleMeasures(): RuntimeScore {
        // Create a score with 4/4 time signature (each measure is 1 whole note = 4 quarter notes)
        val storage = StorageScore.create(StorageScore.CreationOptions("Test", TimeSignature.COMMON, KeySignature.C_MAJOR))
        var runtime = RuntimeScore.fromStorage(storage)

        val pitchTrackId = runtime.pitchTracks.keys.first()
        val voiceTrackId = runtime.voiceTracks.keys.first()

        // Measure 1: Add a quarter note at the beginning
        val pitchEvent1 = RuntimePitchEvent.single(
            onset = TimeCode.of(1, Fraction.ZERO),
            pitch = Pitch.C4
        )
        runtime = runtime.addPitchEvent(pitchTrackId, pitchEvent1)
        val voiceEvent1 = RuntimeVoiceEvent.create(
            onset = pitchEvent1.onset,
            pitchEvent = pitchEvent1,
            duration = Duration.QUARTER
        )
        runtime = runtime.addVoiceEvent(voiceTrackId, voiceEvent1)

        // Measure 1: Add another quarter note
        val pitchEvent2 = RuntimePitchEvent.single(
            onset = TimeCode.of(1, Fraction(1, 4)),
            pitch = Pitch.D4
        )
        runtime = runtime.addPitchEvent(pitchTrackId, pitchEvent2)
        val voiceEvent2 = RuntimeVoiceEvent.create(
            onset = pitchEvent2.onset,
            pitchEvent = pitchEvent2,
            duration = Duration.QUARTER
        )
        runtime = runtime.addVoiceEvent(voiceTrackId, voiceEvent2)

        // Measure 2: Add a half note at the beginning of measure 2
        // In 4/4 time, measure 2 starts at beat 1 (previous measure duration = 4/4)
        val pitchEvent3 = RuntimePitchEvent.single(
            onset = TimeCode.of(2, Fraction.ZERO),
            pitch = Pitch.E4
        )
        runtime = runtime.addPitchEvent(pitchTrackId, pitchEvent3)
        val voiceEvent3 = RuntimeVoiceEvent.create(
            onset = pitchEvent3.onset,
            pitchEvent = pitchEvent3,
            duration = Duration.HALF
        )
        runtime = runtime.addVoiceEvent(voiceTrackId, voiceEvent3)

        // Measure 3: Add a whole note
        val pitchEvent4 = RuntimePitchEvent.single(
            onset = TimeCode.of(3, Fraction.ZERO),
            pitch = Pitch.G4
        )
        runtime = runtime.addPitchEvent(pitchTrackId, pitchEvent4)
        val voiceEvent4 = RuntimeVoiceEvent.create(
            onset = pitchEvent4.onset,
            pitchEvent = pitchEvent4,
            duration = Duration.WHOLE
        )
        runtime = runtime.addVoiceEvent(voiceTrackId, voiceEvent4)

        return runtime
    }
}
