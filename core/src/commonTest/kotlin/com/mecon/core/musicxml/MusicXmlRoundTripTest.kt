package com.mecon.core.musicxml

import com.mecon.api.primitive.*
import com.mecon.api.storage.*
import com.mecon.api.storage.events.*
import com.mecon.api.storage.tracks.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.math.abs

/**
 * Round-trip tests for MusicXML import/export: build a StorageScore, export to MusicXML,
 * re-import, and assert that the newly-wired features survive the trip.
 *
 * IDs are regenerated on import, so assertions target structural / semantic fields only.
 */
class MusicXmlRoundTripTest {

    // ---- helpers ----

    private fun roundTrip(score: StorageScore): StorageScore {
        val xml = MusicXmlConverter.export(score).getOrThrow()
        return MusicXmlConverter.import(xml).getOrThrow()
    }

    private fun allVoiceEvents(score: StorageScore): List<StorageVoiceEvent> =
        score.voiceTracks.values.flatMap { it.events }.sortedBy { it.onset }

    private fun firstStaff(score: StorageScore): StorageStaffTrack =
        score.staffTracks.values.first()

    private data class SingleStaffScoreOptions(
        val measureCount: Int,
        val metadata: ScoreMetadata = ScoreMetadata(),
        val defaultKey: KeySignature = KeySignature.C_MAJOR,
        val defaultTime: TimeSignature = TimeSignature.COMMON,
        val staffClef: Clef = Clef.TREBLE,
        val clefChanges: List<StorageClefChange> = emptyList(),
        val attachments: List<StorageStaffAttachment> = emptyList(),
        val transposition: TranspositionConfig? = null,
        val globalEvents: List<StorageGlobalEvent> = emptyList(),
        val measures: List<StorageMeasure> = (1..measureCount).map { StorageMeasure(number = it) },
        val notes: List<Triple<TimeCode, List<Pitch>, StorageVoiceEvent.() -> StorageVoiceEvent>> = emptyList(),
        val durations: List<Duration> = emptyList(),
    )

    /** Build a single-staff score from a list of (onset, pitches, duration, configure). */
    private fun singleStaffScore(options: SingleStaffScoreOptions): StorageScore = with(options) {
        val pitchTrack = StoragePitchTrack.create("Notes")
        val pitchEvents = mutableListOf<StoragePitchEvent>()
        val voiceEvents = mutableListOf<StorageVoiceEvent>()
        notes.forEachIndexed { i, (onset, pitches, configure) ->
            val pe = StoragePitchEvent.create(onset = onset, pitches = pitches)
            pitchEvents.add(pe)
            val base = StorageVoiceEvent.create(
                onset = onset,
                pitchEventId = pe.id,
                duration = durations.getOrElse(i) { Duration(DurationBase.QUARTER) }
            )
            voiceEvents.add(base.configure())
        }
        val voiceTrack = StorageVoiceTrack(
            id = TrackId.generate(), name = "Voice 1", voiceNumber = 1,
            pitchTrackId = pitchTrack.id, events = voiceEvents
        )
        val staff = StorageStaffTrack(
            id = TrackId.generate(), name = "Staff 1", clef = staffClef,
            keySignature = defaultKey, transposition = transposition,
            voiceTrackIds = listOf(voiceTrack.id), attachments = attachments, clefChanges = clefChanges
        )
        return StorageScore(
            id = ScoreId.generate(),
            metadata = metadata,
            defaultTimeSignature = defaultTime,
            defaultKeySignature = defaultKey,
            measures = measures,
            pitchTracks = mapOf(pitchTrack.id to pitchTrack.copy(events = pitchEvents)),
            voiceTracks = mapOf(voiceTrack.id to voiceTrack),
            staffTracks = mapOf(staff.id to staff),
            globalTrack = StorageGlobalTrack(id = TrackId.generate(), events = globalEvents),
            staffGroups = listOf(StorageStaffGroup.ofStaffs(bracket = BracketStyle.NONE, staffIds = listOf(staff.id)))
        )
    }

    private fun note(measure: Int, beat: Fraction, pitches: List<Pitch>): Triple<TimeCode, List<Pitch>, StorageVoiceEvent.() -> StorageVoiceEvent> =
        Triple(TimeCode.of(measure, beat), pitches) { this }

    private fun assertNear(expected: Float, actual: Float) {
        assertTrue(abs(expected - actual) < 0.001f, "Expected $expected, got $actual")
    }

    // ---- tests ----

    @Test
    fun pitchesAndDurationsRoundTrip() {
        val pitches = listOf(Pitch(0, 0), Pitch(1, 0), Pitch(2, 0), Pitch(3, 1)) // C4 D4 E4 F#4
        val score = singleStaffScore(SingleStaffScoreOptions(
            measureCount = 1,
            notes = pitches.mapIndexed { i, p -> note(1, Fraction(i, 4), listOf(p)) },
            durations = List(4) { Duration(DurationBase.QUARTER) }
        ))
        val out = roundTrip(score)
        val events = allVoiceEvents(out)
        assertEquals(4, events.size)
        val outPitches = events.map { out.findPitchEvent(it.pitchEventId)!!.pitches.single() }
        assertEquals(pitches, outPitches)
    }

    @Test
    fun hiddenStaffRangesRoundTripViaStaffDetailsPrintObject() {
        val base = singleStaffScore(SingleStaffScoreOptions(
            measureCount = 6,
            notes = listOf(note(1, Fraction(0, 4), listOf(Pitch(0, 0)))),
            durations = listOf(Duration(DurationBase.QUARTER)),
        ))
        val staff = firstStaff(base)
        val score = base.copy(
            staffTracks = mapOf(staff.id to staff.copy(hiddenRanges = listOf(MeasureRange(3, 4))))
        )
        // The exported XML must carry the visibility toggles.
        val xml = MusicXmlConverter.export(score).getOrThrow()
        assertTrue(xml.contains("""print-object="no""""), "hide must emit staff-details print-object=no")
        assertTrue(xml.contains("""print-object="yes""""), "re-show must emit staff-details print-object=yes")

        assertEquals(listOf(MeasureRange(3, 4)), firstStaff(roundTrip(score)).hiddenRanges)
    }

    @Test
    fun hiddenStaffRangeToScoreEndRoundTrips() {
        val base = singleStaffScore(SingleStaffScoreOptions(measureCount = 6))
        val staff = firstStaff(base)
        // A range with no explicit re-show (runs to the last measure) must still be reconstructed.
        val score = base.copy(
            staffTracks = mapOf(staff.id to staff.copy(hiddenRanges = listOf(MeasureRange(4, 6))))
        )
        assertEquals(listOf(MeasureRange(4, 6)), firstStaff(roundTrip(score)).hiddenRanges)
    }

    @Test
    fun scoreMetadataRoundTripThroughWorkIdentificationAndCredits() {
        val metadata = ScoreMetadata(
            title = "Synthetic Suite in G Minor",
            subtitle = "Op. 1 No. 1",
            composer = "Test Composer",
            arranger = "Mecon Team",
            lyricist = "Anonymous",
            copyright = "Public Domain"
        )
        val score = singleStaffScore(SingleStaffScoreOptions(
            measureCount = 1,
            metadata = metadata,
            notes = listOf(note(1, Fraction.ZERO, listOf(Pitch(0, 0)))),
            durations = listOf(Duration(DurationBase.WHOLE))
        ))

        val xml = MusicXmlConverter.export(score).getOrThrow()
        assertTrue(xml.contains("<work-title>Synthetic Suite in G Minor</work-title>"))
        assertTrue(xml.contains("<movement-title>Op. 1 No. 1</movement-title>"))
        assertTrue(xml.contains("<credit-type>title</credit-type>"))
        assertTrue(xml.contains("<credit-type>subtitle</credit-type>"))
        assertTrue(xml.contains("""<creator type="composer">Test Composer</creator>"""))

        val out = MusicXmlConverter.import(xml).getOrThrow()
        assertEquals(metadata.title, out.metadata.title)
        assertEquals(metadata.subtitle, out.metadata.subtitle)
        assertEquals(metadata.composer, out.metadata.composer)
        assertEquals(metadata.arranger, out.metadata.arranger)
        assertEquals(metadata.lyricist, out.metadata.lyricist)
        assertEquals(metadata.copyright, out.metadata.copyright)
    }

    @Test
    fun creditOnlyMetadataImportsIntoScoreMetadata() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <score-partwise version="4.0">
              <credit page="1">
                <credit-type>title</credit-type>
                <credit-words default-x="500" default-y="1600" justify="center" font-size="24">Synthetic Suite in G Minor</credit-words>
              </credit>
              <credit page="1">
                <credit-type>subtitle</credit-type>
                <credit-words default-x="500" default-y="1540" justify="center" font-size="14">Opus 1, No. 1</credit-words>
              </credit>
              <credit page="1">
                <credit-type>composer</credit-type>
                <credit-words default-x="950" default-y="1460" justify="right" font-size="12">Test Composer</credit-words>
                <credit-words>(2000 - 2099)</credit-words>
              </credit>
              <part-list>
                <score-part id="P1"><part-name>Piano</part-name></score-part>
              </part-list>
              <part id="P1">
                <measure number="1">
                  <attributes>
                    <divisions>4</divisions>
                    <key><fifths>0</fifths></key>
                    <time><beats>4</beats><beat-type>4</beat-type></time>
                    <clef><sign>G</sign><line>2</line></clef>
                  </attributes>
                  <note>
                    <pitch><step>C</step><octave>4</octave></pitch>
                    <duration>16</duration>
                    <voice>1</voice>
                    <type>whole</type>
                  </note>
                </measure>
              </part>
            </score-partwise>
        """.trimIndent()

        val metadata = MusicXmlConverter.import(xml).getOrThrow().metadata
        assertEquals("Synthetic Suite in G Minor", metadata.title)
        assertEquals("Opus 1, No. 1", metadata.subtitle)
        assertEquals("Test Composer", metadata.composer)
    }

    @Test
    fun clefInitialRoundTrip() {
        val score = singleStaffScore(SingleStaffScoreOptions(
            measureCount = 1, staffClef = Clef.BASS,
            notes = listOf(note(1, Fraction.ZERO, listOf(Pitch(-7, 0)))),
            durations = listOf(Duration(DurationBase.WHOLE))
        ))
        assertEquals(Clef.BASS, firstStaff(roundTrip(score)).clef)
    }

    @Test
    fun clefChangeRoundTrip() {
        val score = singleStaffScore(SingleStaffScoreOptions(
            measureCount = 2, staffClef = Clef.TREBLE,
            clefChanges = listOf(StorageClefChange(TimeCode.of(2, Fraction.ZERO), Clef.BASS)),
            notes = listOf(
                note(1, Fraction.ZERO, listOf(Pitch(4, 0))),
                note(2, Fraction.ZERO, listOf(Pitch(-5, 0)))
            ),
            durations = List(2) { Duration(DurationBase.WHOLE) }
        ))
        val staff = firstStaff(roundTrip(score))
        assertEquals(Clef.TREBLE, staff.clef)
        val change = staff.clefChanges.singleOrNull()
        assertNotNull(change)
        assertEquals(2, change.onset.measure)
        assertEquals(Clef.BASS, change.clef)
    }

    @Test
    fun inlineClefChangeRoundTripPreservesBeat() {
        val changeOnset = TimeCode.of(1, Fraction(1, 4))
        val score = singleStaffScore(SingleStaffScoreOptions(
            measureCount = 1, staffClef = Clef.TREBLE,
            clefChanges = listOf(StorageClefChange(changeOnset, Clef.BASS)),
            notes = listOf(
                note(1, Fraction.ZERO, listOf(Pitch(4, 0))),
                note(1, Fraction(1, 4), listOf(Pitch(-10, 0)))
            ),
            durations = List(2) { Duration(DurationBase.QUARTER) }
        ))

        val xml = MusicXmlConverter.export(score).getOrThrow()
        assertTrue(xml.contains("<forward>"), "inline clef changes should be positioned with forward/backup")
        assertTrue(xml.contains("<backup>"), "inline clef changes should rewind before normal voice export")

        val staff = firstStaff(MusicXmlConverter.import(xml).getOrThrow())
        val change = staff.clefChanges.singleOrNull()
        assertNotNull(change)
        assertEquals(changeOnset, change.onset)
        assertEquals(Clef.BASS, change.clef)
    }

    @Test
    fun keyAndTimeSignatureChangesRoundTrip() {
        val score = singleStaffScore(SingleStaffScoreOptions(
            measureCount = 2,
            defaultKey = KeySignature.C_MAJOR,
            defaultTime = TimeSignature.COMMON,
            globalEvents = listOf(
                StorageKeySignatureChange(TimeCode.of(2, Fraction.ZERO), KeySignature.G_MAJOR),
                StorageTimeSignatureChange(TimeCode.of(2, Fraction.ZERO), TimeSignature(3, 4))
            ),
            notes = listOf(
                note(1, Fraction.ZERO, listOf(Pitch(0, 0))),
                note(2, Fraction.ZERO, listOf(Pitch(1, 0)))
            ),
            durations = listOf(Duration(DurationBase.WHOLE), Duration(DurationBase.HALF, dots = 1))
        ))
        val out = roundTrip(score)
        assertEquals(KeySignature.C_MAJOR, out.defaultKeySignature)
        assertEquals(TimeSignature.COMMON, out.defaultTimeSignature)

        val keyChange = out.globalTrack.events.filterIsInstance<StorageKeySignatureChange>()
            .firstOrNull { it.onset.measure == 2 }
        assertNotNull(keyChange)
        assertEquals(KeySignature.G_MAJOR.fifths, keyChange.keySignature.fifths)

        val timeChange = out.globalTrack.events.filterIsInstance<StorageTimeSignatureChange>()
            .firstOrNull { it.onset.measure == 2 }
        assertNotNull(timeChange)
        assertEquals(3, timeChange.timeSignature.numerator)
        assertEquals(4, timeChange.timeSignature.denominator)
    }

    @Test
    fun flatEnharmonicKeySignaturesRoundTrip() {
        val score = singleStaffScore(SingleStaffScoreOptions(
            measureCount = 2,
            defaultKey = KeySignature.majorByFifths(-7),
            globalEvents = listOf(
                StorageKeySignatureChange(TimeCode.of(2, Fraction.ZERO), KeySignature.majorByFifths(-5))
            ),
            notes = listOf(
                note(1, Fraction.ZERO, listOf(Pitch(0, 0))),
                note(2, Fraction.ZERO, listOf(Pitch(1, 0)))
            ),
            durations = listOf(Duration(DurationBase.WHOLE), Duration(DurationBase.WHOLE))
        ))

        val xml = MusicXmlConverter.export(score).getOrThrow()
        assertTrue(xml.contains("<fifths>-7</fifths>"), xml)
        assertTrue(xml.contains("<fifths>-5</fifths>"), xml)

        val out = MusicXmlConverter.import(xml).getOrThrow()
        assertEquals(-7, out.defaultKeySignature.fifths)
        assertEquals("Cb", out.defaultKeySignature.displayName)

        val keyChange = out.globalTrack.events.filterIsInstance<StorageKeySignatureChange>()
            .firstOrNull { it.onset.measure == 2 }
        assertNotNull(keyChange)
        assertEquals(-5, keyChange.keySignature.fifths)
        assertEquals("Db", keyChange.keySignature.displayName)
    }

    @Test
    fun octaveShift8vaRoundTrip() {
        val end = StorageOctaveShiftEnd.create(onset = TimeCode.of(2, Fraction.ZERO))
        val start = StorageOctaveShiftStart.create(
            onset = TimeCode.of(1, Fraction.ZERO),
            shiftType = OctaveShiftType.OTTAVA,
            endEventId = end.id
        )
        val score = singleStaffScore(SingleStaffScoreOptions(
            measureCount = 2,
            attachments = listOf(start, end),
            notes = listOf(
                note(1, Fraction.ZERO, listOf(Pitch(9, 0))),
                note(2, Fraction.ZERO, listOf(Pitch(11, 0)))
            ),
            durations = List(2) { Duration(DurationBase.WHOLE) }
        ))
        val staff = firstStaff(roundTrip(score))
        val outStart = staff.attachments.filterIsInstance<StorageOctaveShiftStart>().singleOrNull()
        val outEnd = staff.attachments.filterIsInstance<StorageOctaveShiftEnd>().singleOrNull()
        assertNotNull(outStart)
        assertNotNull(outEnd)
        assertEquals(OctaveShiftType.OTTAVA, outStart.shiftType)
        assertEquals(1, outStart.onset.measure)
        assertEquals(2, outEnd.onset.measure)
        assertEquals(outEnd.id, outStart.endEventId)
    }

    @Test
    fun octaveShift8vbRoundTrip() {
        val end = StorageOctaveShiftEnd.create(onset = TimeCode.of(2, Fraction.ZERO))
        val start = StorageOctaveShiftStart.create(
            onset = TimeCode.of(1, Fraction.ZERO),
            shiftType = OctaveShiftType.OTTAVA_BASSA,
            endEventId = end.id
        )
        val score = singleStaffScore(SingleStaffScoreOptions(
            measureCount = 2,
            attachments = listOf(start, end),
            notes = listOf(
                note(1, Fraction.ZERO, listOf(Pitch(-7, 0))),
                note(2, Fraction.ZERO, listOf(Pitch(-4, 0)))
            ),
            durations = List(2) { Duration(DurationBase.WHOLE) }
        ))
        val staff = firstStaff(roundTrip(score))
        val outStart = staff.attachments.filterIsInstance<StorageOctaveShiftStart>().single()
        assertEquals(OctaveShiftType.OTTAVA_BASSA, outStart.shiftType)
    }

    @Test
    fun dynamicsAndHairpinRoundTrip() {
        val dyn = StorageDynamicMark.create(onset = TimeCode.of(1, Fraction.ZERO), level = DynamicLevel.MF)
        val hairpin = StorageHairpin.create(
            onset = TimeCode.of(1, Fraction.ZERO),
            endOnset = TimeCode.of(2, Fraction.ZERO),
            direction = HairpinType.CRESCENDO
        )
        val score = singleStaffScore(SingleStaffScoreOptions(
            measureCount = 2,
            attachments = listOf(dyn, hairpin),
            notes = listOf(
                note(1, Fraction.ZERO, listOf(Pitch(0, 0))),
                note(2, Fraction.ZERO, listOf(Pitch(0, 0)))
            ),
            durations = List(2) { Duration(DurationBase.WHOLE) }
        ))
        val staff = firstStaff(roundTrip(score))
        val outDyn = staff.attachments.filterIsInstance<StorageDynamicMark>().singleOrNull()
        assertNotNull(outDyn)
        assertEquals(DynamicLevel.MF, outDyn.level)

        val outHairpin = staff.attachments.filterIsInstance<StorageHairpin>().singleOrNull()
        assertNotNull(outHairpin)
        assertEquals(HairpinType.CRESCENDO, outHairpin.direction)
        assertEquals(1, outHairpin.onset.measure)
        assertEquals(2, outHairpin.endOnset.measure)
    }

    @Test
    fun tempoRoundTrip() {
        val score = singleStaffScore(SingleStaffScoreOptions(
            measureCount = 1,
            notes = listOf(note(1, Fraction.ZERO, listOf(Pitch(0, 0)))),
            durations = listOf(Duration(DurationBase.WHOLE))
        )).let {
            it.copy(
                globalTrack = it.globalTrack.copy(
                    tempoEvents = listOf(
                        StorageTempoEvent(
                            id = EventId.generate(),
                            onset = TimeCode.of(1, Fraction.ZERO),
                            bpm = 96f,
                            beatUnit = DurationBase.QUARTER
                        )
                    )
                )
            )
        }
        val out = roundTrip(score)
        val tempo = out.globalTrack.tempoEvents.singleOrNull()
        assertNotNull(tempo)
        assertEquals(96f, tempo.bpm)
        assertEquals(DurationBase.QUARTER, tempo.beatUnit)
    }

    @Test
    fun slursRoundTrip() {
        val score = singleStaffScore(SingleStaffScoreOptions(
            measureCount = 1,
            notes = listOf(
                Triple(TimeCode.of(1, Fraction.ZERO), listOf(Pitch(0, 0))) { copy(slurStarts = 1) },
                Triple(TimeCode.of(1, Fraction(1, 4)), listOf(Pitch(1, 0))) { copy(slurEnds = 1) }
            ),
            durations = List(2) { Duration(DurationBase.QUARTER) }
        ))
        val events = allVoiceEvents(roundTrip(score))
        assertEquals(1, events.first().slurStarts)
        assertEquals(1, events.last().slurEnds)
    }

    @Test
    fun tieAndSlurGeometryRoundTripThroughMusicXmlBezierAttributes() {
        val base = singleStaffScore(SingleStaffScoreOptions(
            measureCount = 1,
            notes = listOf(
                note(1, Fraction.ZERO, listOf(Pitch(0, 0))),
                note(1, Fraction(1, 4), listOf(Pitch(0, 0))),
            ),
            durations = List(2) { Duration(DurationBase.QUARTER) },
        ))
        val sourceTrack = base.voiceTracks.values.single()
        val source = sourceTrack.events[0]
        val target = sourceTrack.events[1]
        val slur = StorageSlurEvent.create(source.id, target.id)
        val score = base.copy(
            voiceTracks = mapOf(
                sourceTrack.id to sourceTrack.copy(
                    events = listOf(
                        source.copy(ties = listOf(TieInfo(pitchIndex = 0))),
                        target,
                    ),
                    slurs = listOf(slur),
                )
            ),
            geometry = ScoreGeometry(
                ties = mapOf(
                    source.id to listOf(
                        TieGeometry(
                            sourcePitchIndex = 0,
                            targetPitchIndex = 0,
                            startDx = 0.2f,
                            startDy = -0.1f,
                            endDx = 0f,
                            endDy = 0f,
                            above = true,
                            minApex = 1.2f,
                            maxApex = 1.2f,
                            directionLocked = true,
                            manuallyAdjusted = true,
                            autoEndpoints = true,
                        )
                    )
                ),
                slurs = mapOf(
                    slur.id to SlurGeometry(
                        startPitchIndex = 0,
                        endPitchIndex = 0,
                        startDx = -0.3f,
                        startDy = 0.2f,
                        endDx = 0.4f,
                        endDy = -0.25f,
                        above = false,
                        minApex = 2.4f,
                        maxApex = 2.4f,
                        slopeDamping = 1f,
                        middleStraightening = 0f,
                        directionLocked = true,
                        manuallyAdjusted = true,
                        autoEndpoints = true,
                    )
                ),
            ),
        )

        val xml = MusicXmlConverter.export(score).getOrThrow()
        assertTrue(xml.contains("""orientation="over""""))
        assertTrue(xml.contains("""placement="above""""))
        assertTrue(xml.contains("""placement="below""""))
        assertTrue(xml.contains("bezier-y="))
        assertTrue(xml.contains("relative-x="))
        assertTrue(xml.contains("relative-y="))

        val imported = MusicXmlConverter.import(xml).getOrThrow()
        val importedTie = imported.geometry?.ties?.values?.singleOrNull()?.singleOrNull()
        assertNotNull(importedTie)
        assertTrue(importedTie.above)
        assertTrue(importedTie.directionLocked)
        assertTrue(importedTie.manuallyAdjusted)
        assertTrue(importedTie.autoEndpoints)
        assertNear(1.2f, importedTie.minApex)

        val importedSlur = imported.geometry?.slurs?.values?.singleOrNull()
        assertNotNull(importedSlur)
        assertFalse(importedSlur.above)
        assertTrue(importedSlur.directionLocked)
        assertTrue(importedSlur.manuallyAdjusted)
        assertTrue(importedSlur.autoEndpoints)
        assertNear(2.4f, importedSlur.minApex)
        assertNear(-0.3f, importedSlur.startDx)
        assertNear(0.2f, importedSlur.startDy)
        assertNear(0.4f, importedSlur.endDx)
        assertNear(-0.25f, importedSlur.endDy)
    }

    @Test
    fun tupletRoundTrip() {
        val triplet = Duration(DurationBase.EIGHTH, tuplet = Tuplet(3, 2))
        val span = TupletSpan(
            endTimeCode = TimeCode.of(1, Fraction(1, 4)),
            count = 3,
            beatUnit = DurationBase.EIGHTH,
            displayStyle = TupletDisplayStyle.BRACKET_AND_NUMBER
        )
        val score = singleStaffScore(SingleStaffScoreOptions(
            measureCount = 1,
            notes = listOf(
                Triple(TimeCode.of(1, Fraction(0, 12)), listOf(Pitch(0, 0))) { copy(tupletSpan = span) },
                Triple(TimeCode.of(1, Fraction(1, 12)), listOf(Pitch(1, 0))) { this },
                Triple(TimeCode.of(1, Fraction(2, 12)), listOf(Pitch(2, 0))) { this }
            ),
            durations = List(3) { triplet }
        ))
        val events = allVoiceEvents(roundTrip(score))
        assertEquals(3, events.size)
        val startEvent = events.firstOrNull { it.tupletSpan != null }
        assertNotNull(startEvent)
        assertEquals(3, startEvent.tupletSpan!!.count)
        // Tuplet ratio survives on each note's duration.
        assertTrue(events.all { it.duration.tuplet == Tuplet(3, 2) })
    }

    @Test
    fun hiddenTupletShowNumberImportsAsNonRenderingSpan() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <score-partwise version="3.1">
              <part-list>
                <score-part id="P1"><part-name>Piano</part-name></score-part>
              </part-list>
              <part id="P1">
                <measure number="1">
                  <attributes>
                    <divisions>12</divisions>
                    <key><fifths>0</fifths></key>
                    <time><beats>4</beats><beat-type>4</beat-type></time>
                    <clef><sign>G</sign><line>2</line></clef>
                  </attributes>
                  <note>
                    <pitch><step>C</step><octave>4</octave></pitch>
                    <duration>8</duration>
                    <voice>1</voice>
                    <type>eighth</type>
                    <time-modification>
                      <actual-notes>3</actual-notes>
                      <normal-notes>2</normal-notes>
                    </time-modification>
                    <staff>1</staff>
                    <notations>
                      <tuplet type="start" bracket="no" show-number="none"/>
                    </notations>
                  </note>
                  <note>
                    <pitch><step>D</step><octave>4</octave></pitch>
                    <duration>8</duration>
                    <voice>1</voice>
                    <type>eighth</type>
                    <time-modification>
                      <actual-notes>3</actual-notes>
                      <normal-notes>2</normal-notes>
                    </time-modification>
                    <staff>1</staff>
                  </note>
                  <note>
                    <pitch><step>E</step><octave>4</octave></pitch>
                    <duration>8</duration>
                    <voice>1</voice>
                    <type>eighth</type>
                    <time-modification>
                      <actual-notes>3</actual-notes>
                      <normal-notes>2</normal-notes>
                    </time-modification>
                    <staff>1</staff>
                    <notations>
                      <tuplet type="stop"/>
                    </notations>
                  </note>
                </measure>
              </part>
            </score-partwise>
        """.trimIndent()

        val events = allVoiceEvents(MusicXmlConverter.import(xml).getOrThrow())
        val startEvent = events.firstOrNull { it.tupletSpan != null }
        assertNotNull(startEvent)
        assertEquals(TupletDisplayStyle.NONE, startEvent.tupletSpan!!.displayStyle)
    }

    @Test
    fun beamlessEighthNotesImportAsExplicitNonBeamed() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <score-partwise version="3.1">
              <part-list>
                <score-part id="P1"><part-name>Piano</part-name></score-part>
              </part-list>
              <part id="P1">
                <measure number="1">
                  <attributes>
                    <divisions>8</divisions>
                    <key><fifths>0</fifths></key>
                    <time><beats>4</beats><beat-type>4</beat-type></time>
                    <clef><sign>G</sign><line>2</line></clef>
                  </attributes>
                  <note>
                    <pitch><step>C</step><octave>4</octave></pitch>
                    <duration>4</duration>
                    <voice>1</voice>
                    <type>eighth</type>
                  </note>
                  <note>
                    <pitch><step>D</step><octave>4</octave></pitch>
                    <duration>4</duration>
                    <voice>1</voice>
                    <type>eighth</type>
                  </note>
                  <note>
                    <pitch><step>E</step><octave>4</octave></pitch>
                    <duration>2</duration>
                    <voice>1</voice>
                    <type>16th</type>
                    <beam number="1">begin</beam>
                    <beam number="2">begin</beam>
                  </note>
                  <note>
                    <pitch><step>F</step><octave>4</octave></pitch>
                    <duration>2</duration>
                    <voice>1</voice>
                    <type>16th</type>
                    <beam number="1">end</beam>
                    <beam number="2">end</beam>
                  </note>
                </measure>
              </part>
            </score-partwise>
        """.trimIndent()

        val events = allVoiceEvents(MusicXmlConverter.import(xml).getOrThrow())
        assertEquals(4, events.size)
        assertEquals(BeamingInfo.NONE, events[0].rendering?.beaming)
        assertEquals(BeamingInfo.NONE, events[1].rendering?.beaming)
        assertEquals(BeamingInfo.start(), events[2].rendering?.beaming)
        assertEquals(BeamingInfo.end(), events[3].rendering?.beaming)
    }

    @Test
    fun explicitNonBeamedEighthsRoundTripWithoutBeamElements() {
        val score = singleStaffScore(SingleStaffScoreOptions(
            measureCount = 1,
            notes = listOf(
                Triple(TimeCode.of(1, Fraction.ZERO), listOf(Pitch(0, 0))) {
                    copy(rendering = RenderingProps(beaming = BeamingInfo.NONE))
                },
                Triple(TimeCode.of(1, Fraction(1, 8)), listOf(Pitch(1, 0))) {
                    copy(rendering = RenderingProps(beaming = BeamingInfo.NONE))
                }
            ),
            durations = listOf(Duration(DurationBase.EIGHTH), Duration(DurationBase.EIGHTH))
        ))

        val xml = MusicXmlConverter.export(score).getOrThrow()
        assertFalse(xml.contains("<beam "), "explicit non-beamed eighths should export without beam elements")

        val events = allVoiceEvents(MusicXmlConverter.import(xml).getOrThrow())
        assertEquals(2, events.size)
        assertEquals(BeamingInfo.NONE, events[0].rendering?.beaming)
        assertEquals(BeamingInfo.NONE, events[1].rendering?.beaming)
    }

    @Test
    fun automaticBeamingExportsBeamElementsAndReimportsAsExplicitBeaming() {
        val score = singleStaffScore(SingleStaffScoreOptions(
            measureCount = 1,
            notes = listOf(
                note(1, Fraction.ZERO, listOf(Pitch(0, 0))),
                note(1, Fraction(1, 8), listOf(Pitch(1, 0)))
            ),
            durations = listOf(Duration(DurationBase.EIGHTH), Duration(DurationBase.EIGHTH))
        ))

        val xml = MusicXmlConverter.export(score).getOrThrow()
        assertTrue(xml.contains("""<beam number="1">begin</beam>"""))
        assertTrue(xml.contains("""<beam number="1">end</beam>"""))

        val events = allVoiceEvents(MusicXmlConverter.import(xml).getOrThrow())
        assertEquals(2, events.size)
        assertEquals(BeamingInfo.start(), events[0].rendering?.beaming)
        assertEquals(BeamingInfo.end(), events[1].rendering?.beaming)
    }

    @Test
    fun graceNoteRoundTrip() {
        val graceOnset = TimeCode.of(1, Fraction.ZERO, Fraction(-1, 1))
        val score = singleStaffScore(SingleStaffScoreOptions(
            measureCount = 1,
            notes = listOf(
                Triple(graceOnset, listOf(Pitch(0, 0))) {
                    copy(graceInfo = GraceNoteInfo(Duration(DurationBase.EIGHTH), GraceTimeSource.PRINCIPAL))
                },
                Triple(TimeCode.of(1, Fraction.ZERO), listOf(Pitch(1, 0))) { this }
            ),
            durations = listOf(Duration(DurationBase.EIGHTH), Duration(DurationBase.QUARTER))
        ))
        val events = allVoiceEvents(roundTrip(score))
        val grace = events.firstOrNull { it.onset.grace != null }
        assertNotNull(grace)
        assertNotNull(grace.graceInfo)
        assertEquals(GraceTimeSource.PRINCIPAL, grace.graceInfo!!.stealFrom)
    }

    @Test
    fun transposeRoundTrip() {
        val score = singleStaffScore(SingleStaffScoreOptions(
            measureCount = 1,
            transposition = TranspositionConfig(Interval.MAJOR_SECOND, octaveShift = 0),
            notes = listOf(note(1, Fraction.ZERO, listOf(Pitch(0, 0)))),
            durations = listOf(Duration(DurationBase.WHOLE))
        ))
        val staff = firstStaff(roundTrip(score))
        assertNotNull(staff.transposition)
        assertEquals(2, staff.transposition!!.interval.semitones)
    }

    @Test
    fun repeatBarlinesRoundTrip() {
        val score = singleStaffScore(SingleStaffScoreOptions(
            measureCount = 2,
            measures = listOf(
                StorageMeasure(number = 1, repeatStart = true),
                StorageMeasure(number = 2, repeatEnd = true, repeatCount = 2)
            ),
            notes = listOf(
                note(1, Fraction.ZERO, listOf(Pitch(0, 0))),
                note(2, Fraction.ZERO, listOf(Pitch(1, 0)))
            ),
            durations = List(2) { Duration(DurationBase.WHOLE) }
        ))
        val out = roundTrip(score)
        assertTrue(out.getMeasure(1)!!.repeatStart)
        assertTrue(out.getMeasure(2)!!.repeatEnd)
        assertEquals(2, out.getMeasure(2)!!.repeatCount)
    }

    @Test
    fun explicitBarlineStylesRoundTrip() {
        val score = singleStaffScore(SingleStaffScoreOptions(
            measureCount = 2,
            measures = listOf(
                StorageMeasure(number = 1),
                StorageMeasure(number = 2, endBarlineType = BarlineType.DASHED),
            ),
            notes = listOf(
                note(1, Fraction.ZERO, listOf(Pitch(0, 0))),
                note(2, Fraction.ZERO, listOf(Pitch(1, 0))),
            ),
            durations = List(2) { Duration(DurationBase.WHOLE) },
        )).copy(initialBarlineType = BarlineType.DOUBLE)

        val out = roundTrip(score)
        assertEquals(BarlineType.DOUBLE, out.initialBarlineType)
        assertEquals(BarlineType.DASHED, out.getMeasure(2)!!.endBarlineType)
    }

    @Test
    fun forcedLineAndPageBreaksRoundTrip() {
        val score = singleStaffScore(SingleStaffScoreOptions(
            measureCount = 3,
            globalEvents = listOf(
                StorageSystemBreak(TimeCode.of(2, Fraction.ZERO)),
                StoragePageBreak(TimeCode.of(3, Fraction.ZERO))
            ),
            notes = listOf(
                note(1, Fraction.ZERO, listOf(Pitch(0, 0))),
                note(2, Fraction.ZERO, listOf(Pitch(1, 0))),
                note(3, Fraction.ZERO, listOf(Pitch(2, 0)))
            ),
            durations = List(3) { Duration(DurationBase.WHOLE) }
        )).copy(pageLayout = PageLayoutConfig.DEFAULT.copy(paginated = true))

        val xml = MusicXmlConverter.export(score).getOrThrow()
        assertTrue(xml.contains("""<print new-system="yes"/>"""))
        assertTrue(xml.contains("""<print new-system="yes" new-page="yes"/>"""))

        val out = MusicXmlConverter.import(xml).getOrThrow()
        val systemBreaks = out.globalTrack.events.filterIsInstance<StorageSystemBreak>()
        val pageBreaks = out.globalTrack.events.filterIsInstance<StoragePageBreak>()
        assertEquals(listOf(2), systemBreaks.map { it.onset.measure })
        assertEquals(listOf(3), pageBreaks.map { it.onset.measure })
        assertTrue(out.pageLayout.paginated)
    }

    @Test
    fun pageLayoutDefaultsRoundTrip() {
        val pageLayout = PageLayoutConfig(
            paperWidthMm = 180f,
            paperHeightMm = 270f,
            marginTopMm = 12f,
            marginBottomMm = 18f,
            marginLeftMm = 16f,
            marginRightMm = 14f,
            staffSpaceMm = 2f,
            paginated = true,
            presetName = null
        )
        val score = singleStaffScore(SingleStaffScoreOptions(
            measureCount = 1,
            notes = listOf(note(1, Fraction.ZERO, listOf(Pitch(0, 0)))),
            durations = listOf(Duration(DurationBase.WHOLE))
        )).copy(pageLayout = pageLayout)

        val xml = MusicXmlConverter.export(score).getOrThrow()
        assertTrue(xml.contains("<defaults>"))
        assertTrue(xml.contains("<page-layout>"))

        val outLayout = MusicXmlConverter.import(xml).getOrThrow().pageLayout
        assertNear(pageLayout.paperWidthMm, outLayout.paperWidthMm)
        assertNear(pageLayout.paperHeightMm, outLayout.paperHeightMm)
        assertNear(pageLayout.marginTopMm, outLayout.marginTopMm)
        assertNear(pageLayout.marginBottomMm, outLayout.marginBottomMm)
        assertNear(pageLayout.marginLeftMm, outLayout.marginLeftMm)
        assertNear(pageLayout.marginRightMm, outLayout.marginRightMm)
        assertNear(pageLayout.staffSpaceMm, outLayout.staffSpaceMm)
        assertTrue(outLayout.paginated)
    }

    @Test
    fun multiVoiceTimingRoundTrip() {
        val pitchTrack = StoragePitchTrack.create("Notes")
        val pitchEvents = mutableListOf<StoragePitchEvent>()
        fun ve(onset: TimeCode, pitch: Pitch): StorageVoiceEvent {
            val pe = StoragePitchEvent.create(onset = onset, pitches = listOf(pitch))
            pitchEvents.add(pe)
            return StorageVoiceEvent.create(onset = onset, pitchEventId = pe.id, duration = Duration(DurationBase.QUARTER))
        }
        val v1 = StorageVoiceTrack(
            id = TrackId.generate(), name = "V1", voiceNumber = 1, pitchTrackId = pitchTrack.id,
            events = listOf(
                ve(TimeCode.of(1, Fraction(0, 4)), Pitch(4, 0)),
                ve(TimeCode.of(1, Fraction(1, 4)), Pitch(5, 0))
            )
        )
        val v2 = StorageVoiceTrack(
            id = TrackId.generate(), name = "V2", voiceNumber = 2, pitchTrackId = pitchTrack.id,
            events = listOf(
                ve(TimeCode.of(1, Fraction(0, 4)), Pitch(-3, 0)),
                ve(TimeCode.of(1, Fraction(1, 4)), Pitch(-2, 0))
            )
        )
        val staff = StorageStaffTrack(
            id = TrackId.generate(), name = "Staff", clef = Clef.TREBLE,
            voiceTrackIds = listOf(v1.id, v2.id)
        )
        val score = StorageScore(
            id = ScoreId.generate(),
            measures = listOf(StorageMeasure(number = 1)),
            pitchTracks = mapOf(pitchTrack.id to pitchTrack.copy(events = pitchEvents)),
            voiceTracks = mapOf(v1.id to v1, v2.id to v2),
            staffTracks = mapOf(staff.id to staff),
            staffGroups = listOf(StorageStaffGroup.ofStaffs(bracket = BracketStyle.NONE, staffIds = listOf(staff.id)))
        )
        val out = roundTrip(score)
        assertEquals(2, out.voiceTracks.size)
        out.voiceTracks.values.forEach { vt ->
            assertEquals(2, vt.events.size)
            val beats = vt.events.sortedBy { it.onset }.map { it.onset.beat }
            assertEquals(listOf(Fraction.ZERO.simplified(), Fraction(1, 4).simplified()), beats.map { it!!.simplified() })
        }
    }

    // ---- rest display position ----

    /** Build a single-rest, single-staff score with a non-default rest display [staffPosition]. */
    private fun restScore(staffPosition: Int?, clef: Clef): StorageScore = singleStaffScore(
        SingleStaffScoreOptions(
            measureCount = 1,
            staffClef = clef,
            notes = listOf(
                Triple<TimeCode, List<Pitch>, StorageVoiceEvent.() -> StorageVoiceEvent>(
                    TimeCode.of(1, Fraction.ZERO), emptyList()
                ) { copy(rendering = staffPosition?.let { RenderingProps(restStaffPosition = it) }) }
            ),
            durations = listOf(Duration(DurationBase.WHOLE)),
        ),
    )

    @Test
    fun restDisplayPositionRoundTripsInTreble() {
        val score = restScore(staffPosition = 4, clef = Clef.TREBLE)
        val xml = MusicXmlConverter.export(score).getOrThrow()
        // Position 4 under treble = top line F5 → display-step F, display-octave 5.
        assertTrue(xml.contains("<display-step>F</display-step>"), xml)
        assertTrue(xml.contains("<display-octave>5</display-octave>"))
        val rest = allVoiceEvents(roundTrip(score)).single()
        assertEquals(4, rest.rendering?.restStaffPosition)
    }

    @Test
    fun restDisplayPositionRoundTripsInBass() {
        // Same staff position, different clef → different step/octave, but the position must survive.
        val score = restScore(staffPosition = -2, clef = Clef.BASS)
        val rest = allVoiceEvents(roundTrip(score)).single()
        assertEquals(-2, rest.rendering?.restStaffPosition)
    }

    @Test
    fun defaultRestEmitsNoDisplayPositionAndImportsAsNull() {
        val score = restScore(staffPosition = null, clef = Clef.TREBLE)
        val xml = MusicXmlConverter.export(score).getOrThrow()
        assertFalse(xml.contains("<display-step>"), "default rest must not write a display position")
        val rest = allVoiceEvents(roundTrip(score)).single()
        assertEquals(null, rest.rendering?.restStaffPosition)
    }

    @Test
    fun instrumentNameStaffMappingAndProgramRoundTrip() {
        val original = StorageScore.create(StorageScore.CreationOptions(
            instrumentTemplates = listOf(
                InstrumentTemplate(
                    name = "Organ",
                    abbreviation = "Org.",
                    staves = listOf(
                        StaffTemplate("Great", Clef.TREBLE),
                        StaffTemplate("Pedal", Clef.BASS)
                    ),
                    midiProgram = 19
                )
            )
        ))

        val restored = roundTrip(original)
        val instrument = restored.instruments.single()
        assertEquals("Organ", instrument.name)
        assertEquals("Org.", instrument.abbreviation)
        assertEquals(2, instrument.staffIds.size)
        assertEquals(19, instrument.playback.midiProgram)
    }

}
