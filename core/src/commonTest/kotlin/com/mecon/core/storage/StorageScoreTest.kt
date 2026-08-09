package com.mecon.api.storage

import com.mecon.api.primitive.*
import com.mecon.api.storage.events.StoragePitchEvent
import com.mecon.api.storage.events.StorageSlurEvent
import com.mecon.api.storage.events.StorageVoiceEvent
import com.mecon.api.storage.tracks.BracketStyle
import com.mecon.api.storage.tracks.StaffGroupMember
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.computed.StaffLabelPlacement
import com.mecon.core.engine.StaffHeaderComputer
import com.mecon.core.serializer.ScoreSerializer
import kotlin.test.*

class StorageScoreTest {

    @Test
    fun testCreateEmptyScore() {
        val score = StorageScore.create(StorageScore.CreationOptions(title = "Test Score"))

        assertEquals("Test Score", score.metadata.title)
        assertEquals(TimeSignature.COMMON, score.defaultTimeSignature)
        assertEquals(KeySignature.C_MAJOR, score.defaultKeySignature)
        assertEquals(4, score.measures.size)
        assertEquals(1, score.staffGroups.size)
        assertEquals(1, score.staffTracks.size)
        assertEquals(1, score.pitchTracks.size)
    }

    @Test
    fun testAddPitchEvent() {
        var score = StorageScore.create()
        val pitchTrackId = score.pitchTracks.keys.first()
        val voiceTrackId = score.voiceTracks.keys.first()

        // Create pitch event (pure pitch data)
        val pitchEvent = StoragePitchEvent.single(
            onset = TimeCode.of(1, Fraction(0, 4)),
            pitch = Pitch.C4
        )
        score = score.addPitchEvent(pitchTrackId, pitchEvent)

        // Create voice event (rendered note)
        val voiceEvent = StorageVoiceEvent.create(
            onset = TimeCode.of(1, Fraction(0, 4)),
            pitchEventId = pitchEvent.id,
            duration = Duration.QUARTER
        )
        score = score.addVoiceEvent(voiceTrackId, voiceEvent)

        val pitchTrack = score.getPitchTrack(pitchTrackId)
        assertNotNull(pitchTrack)
        assertEquals(1, pitchTrack.events.size)
        assertEquals(listOf(Pitch.C4), pitchTrack.events[0].pitches)

        val voiceTrack = score.getVoiceTrack(voiceTrackId)
        assertNotNull(voiceTrack)
        assertEquals(1, voiceTrack.events.size)
        assertEquals(Duration.QUARTER, voiceTrack.events[0].duration)
    }

    @Test
    fun testCreateDemoScore() {
        val score = StorageScore.createDemo()

        assertEquals("Demo Score", score.metadata.title)

        val allPitchEvents = score.getAllPitchEvents()
        assertEquals(8, allPitchEvents.size)  // C major scale

        val allVoiceEvents = score.getAllVoiceEvents()
        assertEquals(8, allVoiceEvents.size)  // Corresponding voice events

        val firstPitchEvent = allPitchEvents.first()
        assertEquals(listOf(Pitch.C4), firstPitchEvent.pitches)

        // Verify voice event references pitch event
        val firstVoiceEvent = allVoiceEvents.first()
        assertEquals(Duration.QUARTER, firstVoiceEvent.duration)
        assertEquals(firstPitchEvent.id, firstVoiceEvent.pitchEventId)
    }

    @Test
    fun testGetTimeSignatureAt() {
        var score = StorageScore.create(StorageScore.CreationOptions(
            timeSignature = TimeSignature.FOUR_FOUR
        ))

        // Add a measure with different time signature
        score = score.addMeasure(StorageMeasure(
            number = 5,
            timeSignature = TimeSignature.THREE_FOUR
        ))

        // Measure 1-4 should use default (4/4)
        assertEquals(TimeSignature.FOUR_FOUR, score.getTimeSignatureAt(1))
        assertEquals(TimeSignature.FOUR_FOUR, score.getTimeSignatureAt(4))

        // Measure 5+ should use 3/4
        assertEquals(TimeSignature.THREE_FOUR, score.getTimeSignatureAt(5))
    }
    @Test
    fun defaultScoreMapsItsStaffToPiano() {
        val score = StorageScore.create()
        val piano = assertNotNull(score.instruments.singleOrNull())
        assertEquals("Piano", piano.name)
        assertEquals(score.staffTracks.keys.toList(), piano.staffIds)
        assertEquals(0, piano.playback.midiProgram)
    }

    @Test
    fun grandStaffBelongsToOneInstrument() {
        val score = StorageScore.create(StorageScore.CreationOptions(layout = StaffLayoutPreset.PIANO_GRAND))
        assertEquals(2, score.staffTracks.size)
        assertEquals(1, score.instruments.size)
        assertEquals(score.staffTracks.keys.toSet(), score.instruments.single().staffIds.toSet())
        assertEquals(BracketStyle.BRACE, score.staffGroups.single().bracket)
    }

    @Test
    fun groupTemplatesMayNestButNotCross() {
        val instruments = listOf("Flute", "Oboe", "Clarinet", "Bassoon").map { name ->
            InstrumentTemplate(name, staves = listOf(StaffTemplate(name, com.mecon.api.storage.tracks.Clef.TREBLE)))
        }
        val score = StorageScore.create(StorageScore.CreationOptions(
            instrumentTemplates = instruments,
            groupTemplates = listOf(
                StaffGroupTemplate(0, 3, BracketStyle.SQUARE),
                StaffGroupTemplate(0, 1, BracketStyle.SUB_BRACKET)
            )
        ))
        val outer = score.staffGroups.single()
        val inner = assertIs<StaffGroupMember.Group>(outer.members.first()).group
        assertEquals(BracketStyle.SUB_BRACKET, inner.bracket)
        assertEquals(2, inner.allStaffIds().size)

        assertFailsWith<IllegalArgumentException> {
            StorageScore.create(StorageScore.CreationOptions(
                instrumentTemplates = instruments,
                groupTemplates = listOf(
                    StaffGroupTemplate(0, 2, BracketStyle.SQUARE),
                    StaffGroupTemplate(1, 3, BracketStyle.SUB_BRACKET)
                )
            ))
        }
    }

    @Test
    fun multiStaffInstrumentGetsAutomaticBraceAlongsideOuterGroups() {
        val piano = InstrumentTemplate(
            "Piano", staves = listOf(
                StaffTemplate("Piano 1", com.mecon.api.storage.tracks.Clef.TREBLE),
                StaffTemplate("Piano 2", com.mecon.api.storage.tracks.Clef.BASS)
            )
        )
        val violin = InstrumentTemplate(
            "Violin", staves = listOf(StaffTemplate("Violin", com.mecon.api.storage.tracks.Clef.TREBLE))
        )
        val score = StorageScore.create(StorageScore.CreationOptions(
            instrumentTemplates = listOf(piano, violin),
            groupTemplates = listOf(StaffGroupTemplate(0, 2, BracketStyle.SQUARE))
        ))
        val nestedBrace = score.staffGroups.single().members
            .filterIsInstance<StaffGroupMember.Group>().single().group
        assertEquals(BracketStyle.BRACE, nestedBrace.bracket)
        assertEquals(2, nestedBrace.allStaffIds().size)
    }

    @Test
    fun computedHeaderUsesEditableInstrumentDisplayNames() {
        val score = StorageScore.create(StorageScore.CreationOptions(
            instrumentTemplates = listOf(
                InstrumentTemplate("Violin I", staves = listOf(StaffTemplate("Violin I", com.mecon.api.storage.tracks.Clef.TREBLE)), labelInHeader = true),
                InstrumentTemplate("Violin II", staves = listOf(StaffTemplate("Violin II", com.mecon.api.storage.tracks.Clef.TREBLE)), labelInHeader = true)
            )
        ))
        val header = StaffHeaderComputer.compute(RuntimeScore.fromStorage(score))
        assertTrue(header.labels.any { it.text == "Violin I" })
        assertTrue(header.labels.any { it.text == "Violin II" })
    }

    @Test
    fun defaultAssignmentsAreSequentialExceptForHornAndAppearInStaffHeader() {
        val score = StorageScore.create(StorageScore.CreationOptions(
            orchestrationEnabled = true,
            instrumentTemplates = listOf(
                InstrumentTemplate(
                    name = "Flutes",
                    catalogId = "flute",
                    staves = listOf(
                        StaffTemplate("Flutes 1", com.mecon.api.storage.tracks.Clef.TREBLE),
                        StaffTemplate("Flutes 2", com.mecon.api.storage.tracks.Clef.TREBLE),
                    ),
                    playerCount = 4,
                ),
                InstrumentTemplate(
                    name = "Horns",
                    catalogId = "horn",
                    staves = listOf(
                        StaffTemplate("Horns 1", com.mecon.api.storage.tracks.Clef.TREBLE),
                        StaffTemplate("Horns 2", com.mecon.api.storage.tracks.Clef.TREBLE),
                    ),
                    playerCount = 4,
                ),
            ),
        ))
        val orchestration = score.orchestration!!
        fun numbersFor(instrumentIndex: Int): List<List<Int>> {
            val instrument = score.instruments[instrumentIndex]
            val players = orchestration.players.filter { player ->
                player.instruments.any { it.id == instrument.id }
            }
            val numberById = players.mapIndexed { index, player -> player.id to index + 1 }.toMap()
            return instrument.staffIds.map { staffId ->
                orchestration.staffAssignments
                    .filter { it.staffId == staffId }
                    .mapNotNull { numberById[it.playerId] }
                    .sorted()
            }
        }
        assertEquals(listOf(listOf(1, 2), listOf(3, 4)), numbersFor(0))
        assertEquals(listOf(listOf(1, 3), listOf(2, 4)), numbersFor(1))

        val playerLabels = StaffHeaderComputer.compute(RuntimeScore.fromStorage(score)).labels
            .filter { it.placement == StaffLabelPlacement.BEFORE_BRACKETS }
            .map { it.text }
        assertEquals(listOf("1,2", "3,4", "1,3", "2,4"), playerLabels)
    }
}

class ScoreSerializerTest {

    @Test
    fun catalogIdentitySurvivesSerialization() {
        val original = StorageScore.create(StorageScore.CreationOptions(
            instrumentTemplates = listOf(
                InstrumentTemplate(
                    name = "Horns",
                    catalogId = "horn",
                    staves = listOf(StaffTemplate("Horns", com.mecon.api.storage.tracks.Clef.TREBLE)),
                ),
            ),
        ))
        assertEquals(
            "horn",
            ScoreSerializer.fromYaml(ScoreSerializer.toYaml(original)).instruments.single().catalogId,
        )
    }

    @Test
    fun testYamlRoundTrip() {
        val original = StorageScore.createDemo()
        val yaml = ScoreSerializer.toYaml(original)
        val restored = ScoreSerializer.fromYaml(yaml)

        assertEquals(original.id, restored.id)
        assertEquals(original.metadata.title, restored.metadata.title)
        assertEquals(original.getAllPitchEvents().size, restored.getAllPitchEvents().size)
        assertEquals(original.instruments, restored.instruments)
    }

    @Test
    fun testJsonRoundTrip() {
        val original = StorageScore.createDemo()
        val json = ScoreSerializer.toJson(original)
        val restored = ScoreSerializer.fromJson(json)

        assertEquals(original.id, restored.id)
        assertEquals(original.metadata.title, restored.metadata.title)
        assertEquals(original.getAllPitchEvents().size, restored.getAllPitchEvents().size)
    }

    @Test
    fun testGeometryAndExplicitSlurRoundTrip() {
        var score = StorageScore.createDemo()
        val voiceTrackId = score.voiceTracks.keys.first()
        val events = score.getVoiceTrack(voiceTrackId)!!.events

        val slur = StorageSlurEvent(EventId("slur-1"), events[0].id, events[3].id)
        score = score.updateVoiceTrack(voiceTrackId) { it.copy(slurs = listOf(slur)) }

        val geometry = ScoreGeometry(
            articulations = mapOf(
                events[1].id to ArticulationGeometry(listOf(MarkOffset(0, above = true, dx = 0.1f, dy = -2.3f)))
            ),
            slurs = mapOf(
                slur.id to SlurGeometry(
                    startPitchIndex = 0, endPitchIndex = 0,
                    startDx = 0.2f, startDy = -1.5f, endDx = -0.2f, endDy = -1.5f,
                    above = true, minApex = 1.2f, maxApex = 2.0f,
                    slopeDamping = 0.8f, middleStraightening = 0.3f,
                )
            ),
            attachments = mapOf(
                EventId("hairpin-1") to AttachmentGeometry(
                    startDx = -0.5f,
                    startDy = 4f,
                    endDx = -0.5f,
                    endDy = 5f,
                    spread = 1.2f,
                    manuallyAdjustedY = true,
                )
            ),
            beams = mapOf(
                "beam_1" to BeamGeometry(
                    startDy = -4.25f,
                    endDy = -3.75f,
                    crossStaffBase = CrossStaffBeamBase.BETWEEN_STAFFS,
                    crossStaffOffset = 0.5f,
                    betweenStaffUpperIndex = 1,
                    betweenStaffLowerIndex = 2,
                    manuallyAdjusted = true,
                )
            ),
        )
        score = score.copy(geometry = geometry)

        for (restored in listOf(
            ScoreSerializer.fromYaml(ScoreSerializer.toYaml(score)),
            ScoreSerializer.fromJson(ScoreSerializer.toJson(score)),
        )) {
            assertEquals(listOf(slur), restored.getVoiceTrack(voiceTrackId)!!.slurs)
            assertEquals(geometry, restored.geometry)
        }
    }

    @Test
    fun geometryWithoutDropsOnlyRequestedBeamEntries() {
        val geometry = ScoreGeometry(
            beams = mapOf(
                "keep" to BeamGeometry(1f, 2f),
                "drop" to BeamGeometry(3f, 4f),
            )
        )

        val result = geometry.without(
            staleArticulations = emptySet(),
            staleSlurs = emptySet(),
            staleBeams = setOf("drop"),
        )

        assertEquals(setOf("keep"), result.beams.keys)
        assertEquals(BeamGeometry(1f, 2f), result.beams["keep"])
    }

    @Test
    fun testScoreWithoutGeometrySerializesWithoutGeometryKey() {
        // Old files / auto-laid-out scores must round-trip unchanged: no geometry key, null on load.
        val score = StorageScore.createDemo()
        val yaml = ScoreSerializer.toYaml(score)
        assertFalse(yaml.contains("geometry"), "expected no geometry key, got:\n$yaml")
        assertNull(ScoreSerializer.fromYaml(yaml).geometry)
    }

    @Test
    fun testDetectFormat() {
        assertEquals(ScoreSerializer.Format.YAML, ScoreSerializer.detectFormat("score.yaml"))
        assertEquals(ScoreSerializer.Format.YAML, ScoreSerializer.detectFormat("score.yml"))
        assertEquals(ScoreSerializer.Format.JSON, ScoreSerializer.detectFormat("score.json"))
        assertEquals(ScoreSerializer.Format.YAML, ScoreSerializer.detectFormat("score.txt")) // default
    }
}
