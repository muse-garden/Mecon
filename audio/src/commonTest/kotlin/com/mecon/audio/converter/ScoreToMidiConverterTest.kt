package com.mecon.audio.converter

import com.mecon.audio.model.*
import com.mecon.api.primitive.*
import com.mecon.api.runtime.RuntimeMeasure
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.TimeIndexedList
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.runtime.tracks.RuntimePitchTrack
import com.mecon.api.runtime.tracks.RuntimeVoiceTrack
import com.mecon.api.storage.ScoreMetadata
import com.mecon.api.storage.InstrumentTemplate
import com.mecon.api.storage.StaffTemplate
import com.mecon.api.storage.ArpeggioType
import com.mecon.api.storage.StaffLayoutPreset
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.NavigationMark
import com.mecon.api.storage.tracks.Clef
import com.mecon.api.storage.events.GraceNoteInfo
import com.mecon.api.storage.events.GraceTimeSource
import com.mecon.api.storage.events.StorageTempoEvent
import com.mecon.api.storage.events.StoragePitchEvent
import com.mecon.api.storage.events.StorageVoiceEvent
import com.mecon.api.storage.events.TieInfo
import com.mecon.api.storage.events.TempoDisplayStyle
import com.mecon.api.storage.events.TempoMarkType
import com.mecon.api.storage.events.TempoTransition
import com.mecon.api.storage.events.OrnamentKind
import com.mecon.api.storage.events.TrillPlaybackMode
import com.mecon.api.storage.tracks.StorageGlobalTrack
import com.mecon.api.storage.tracks.BreathMarkScope
import com.mecon.api.storage.tracks.StorageFermata
import com.mecon.api.collection.BPlusTree
import com.mecon.api.collection.BPlusTreeAggregator
import com.mecon.core.engine.edit.ExpressionEditEngine
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.core.musicxml.MusicXmlConverter
import kotlin.test.*

class ScoreToMidiConverterTest {

    private val dummyAgg = object : BPlusTreeAggregator<RuntimeMeasure, Int> {
        override fun extract(value: RuntimeMeasure): Int = 0
        override fun combine(a1: Int, a2: Int): Int = 0
        override val empty: Int = 0
    }

    private fun createMeasureTree(vararg measures: RuntimeMeasure): BPlusTree<Int, RuntimeMeasure, Int> {
        var tree = BPlusTree<Int, RuntimeMeasure, Int>(aggregator = dummyAgg)
        for (m in measures) {
            tree = tree.put(m.number, m)
        }
        return tree
    }

    private fun createSimpleScore(
        pitchEvents: List<RuntimePitchEvent>,
        tempo: Float = 120f,
        timeSignature: TimeSignature = TimeSignature.COMMON
    ): RuntimeScore {
        val pitchTrack = RuntimePitchTrack(
            id = TrackId.generate(),
            name = "Test Track",
            events = TimeIndexedList.of(pitchEvents)
        )

        val measure = RuntimeMeasure(
            number = 1,
            timeSignature = timeSignature,
            keySignature = KeySignature.C_MAJOR
        )

        return RuntimeScore(
            id = ScoreId.generate(),
            metadata = ScoreMetadata(title = "Test Score"),
            defaultTimeSignature = timeSignature,
            defaultKeySignature = KeySignature.C_MAJOR,
            defaultTempo = tempo,
            measures = createMeasureTree(measure),
            staffTracks = emptyMap(),
            voiceTracks = emptyMap(),
            pitchTracks = mapOf(pitchTrack.id to pitchTrack)
        )
    }

    private fun createEditableNoteScore(
        pitches: List<Pitch>,
        duration: Duration = Duration.WHOLE,
    ): RuntimeScore {
        var score = RuntimeScore.fromStorage(StorageScore.create(
            StorageScore.CreationOptions(layout = StaffLayoutPreset.TREBLE, measureCount = 1),
        ))
        val staff = score.staffTracks.values.first()
        val voice = staff.voiceTracks.first()
        for (pitch in pitches) {
            score = assertNotNull(NoteEditEngine.insert(score, NoteEditEngine.Insertion(
                voiceTrackId = voice.id,
                staffTrackId = staff.id,
                voiceNumber = voice.voiceNumber,
                start = TimeCode.ofMeasure(1),
                duration = duration,
                pitch = pitch,
            ))).score
        }
        return score
    }

    @Test
    fun testEmptyScore() {
        val score = createSimpleScore(emptyList())
        val midiScore = ScoreToMidiConverter.convert(score)

        assertEquals(1, midiScore.tracks.size)
        assertTrue(midiScore.tracks[0].events.isEmpty())
        assertEquals(120f, midiScore.getInitialTempo())
    }

    @Test
    fun trillExpandsDiatonicallyAndExplicitAccidentalChangesPlaybackPitch() {
        val original = createEditableNoteScore(listOf(Pitch.C4))
        val staff = original.staffTracks.values.first()
        val note = original.voiceTracks.values.first().events.toList().first { !it.isRest }
        val added = assertNotNull(ExpressionEditEngine.addOrnament(
            original,
            staff.id,
            note.id,
            OrnamentKind.TRILL,
        ))

        val diatonic = ScoreToMidiConverter.convert(added.score)
            .tracks.single().getNoteOnEvents().sortedBy { it.absoluteTicks }
        assertEquals(listOf(60, 62, 60, 62), diatonic.take(4).map { it.midiNumber })

        val explicit = assertNotNull(ExpressionEditEngine.updateOrnament(
            added.score,
            added.selectedAttachmentIds.single(),
            upperAccidental = Accidental.FLAT,
            trillPlaybackMode = TrillPlaybackMode.CONTROL_FLOW,
            updateUpperAccidental = true,
        ))
        // The MIDI backend has no native ornament controller; CONTROL_FLOW therefore uses the
        // documented safe expansion fallback while retaining the user's stored preference.
        val fallback = ScoreToMidiConverter.convert(explicit.score)
            .tracks.single().getNoteOnEvents().sortedBy { it.absoluteTicks }
        assertEquals(listOf(60, 61, 60, 61), fallback.take(4).map { it.midiNumber })
    }

    @Test
    fun mordentOscillationCountAndArpeggioDirectionAffectMidiExpansion() {
        val single = createEditableNoteScore(listOf(Pitch.C4))
        val staff = single.staffTracks.values.first()
        val voice = single.voiceTracks.values.first()
        val note = voice.events.toList().first { !it.isRest }
        val added = assertNotNull(ExpressionEditEngine.addOrnament(
            single,
            staff.id,
            note.id,
            OrnamentKind.MORDENT,
        ))
        val repeated = assertNotNull(ExpressionEditEngine.updateOrnament(
            added.score,
            added.selectedAttachmentIds.single(),
            oscillations = 3,
        ))
        val mordentNotes = ScoreToMidiConverter.convert(repeated.score)
            .tracks.single().getNoteOnEvents().sortedBy { it.absoluteTicks }
            .take(7).map { it.midiNumber }
        assertEquals(listOf(60, 59, 60, 59, 60, 59, 60), mordentNotes)

        val chord = createEditableNoteScore(listOf(Pitch.C4, Pitch.E4, Pitch.G4))
        val chordVoice = chord.voiceTracks.values.first()
        val chordEvent = chordVoice.events.toList().first { !it.isRest }
        val downward = assertNotNull(ExpressionEditEngine.setArpeggio(
            chord,
            listOf(ExpressionEditEngine.NoteTarget(chordVoice.id, chordEvent.id)),
            ArpeggioType.DOWN,
        ))
        val arpeggioOns = ScoreToMidiConverter.convert(downward.score)
            .tracks.single().getNoteOnEvents().sortedBy { it.absoluteTicks }
        assertEquals(listOf(67, 64, 60), arpeggioOns.map { it.midiNumber })
        assertTrue(arpeggioOns.zipWithNext().all { (a, b) -> a.absoluteTicks < b.absoluteTicks })
    }

    @Test
    fun testSingleNote() {
        val pitchEvent = RuntimePitchEvent.single(
            onset = TimeCode.of(1, Fraction.ZERO),
            pitch = Pitch.C4
        )

        val score = createSimpleScore(listOf(pitchEvent))
        val midiScore = ScoreToMidiConverter.convert(score)

        val track = midiScore.tracks[0]
        val noteOnEvents = track.getNoteOnEvents()
        val noteOffEvents = track.getNoteOffEvents()

        assertEquals(1, noteOnEvents.size)
        assertEquals(1, noteOffEvents.size)

        val noteOn = noteOnEvents[0]
        assertEquals(60, noteOn.midiNumber)  // C4 = MIDI 60
        assertEquals(0L, noteOn.absoluteTicks)
        assertEquals(pitchEvent.id, noteOn.sourceEventId)
    }

    @Test
    fun testChord() {
        // C major chord at measure 1, beat 0
        val chordEvent = RuntimePitchEvent.chord(
            onset = TimeCode.of(1, Fraction.ZERO),
            Pitch.C4,  // C4
            Pitch.E4,  // E4
            Pitch.G4   // G4
        )

        val score = createSimpleScore(listOf(chordEvent))
        val midiScore = ScoreToMidiConverter.convert(score)

        val track = midiScore.tracks[0]
        val noteOnEvents = track.getNoteOnEvents()

        assertEquals(3, noteOnEvents.size)

        val midiNumbers = noteOnEvents.map { it.midiNumber }.sorted()
        assertEquals(listOf(60, 64, 67), midiNumbers)  // C4, E4, G4
    }

    @Test
    fun outOfRangeChordPitchesAreSilentWithoutBlockingValidPitches() {
        val chordEvent = RuntimePitchEvent.chord(
            onset = TimeCode.of(1, Fraction.ZERO),
            Pitch.fromMidi(-1),
            Pitch.C4,
            Pitch.fromMidi(128),
        )

        val track = ScoreToMidiConverter.convert(createSimpleScore(listOf(chordEvent))).tracks.single()

        assertEquals(listOf(60), track.getNoteOnEvents().map { it.midiNumber })
        assertEquals(listOf(60), track.getNoteOffEvents().map { it.midiNumber })
    }

    @Test
    fun repeatBarlinesExpandMidiAndMapPlayheadBackToSourceMeasures() {
        val events = listOf(
            RuntimePitchEvent.single(TimeCode.of(1, Fraction.ZERO), Pitch.C4),
            RuntimePitchEvent.single(TimeCode.of(2, Fraction.ZERO), Pitch.D4),
            RuntimePitchEvent.single(TimeCode.of(3, Fraction.ZERO), Pitch.E4),
        )
        val base = createSimpleScore(events)
        val score = base.copy(
            measures = createMeasureTree(
                RuntimeMeasure(
                    1, TimeSignature.COMMON, KeySignature.C_MAJOR,
                    repeatStart = true,
                ),
                RuntimeMeasure(
                    2, TimeSignature.COMMON, KeySignature.C_MAJOR,
                    repeatEnd = true, repeatCount = 2,
                ),
                RuntimeMeasure(3, TimeSignature.COMMON, KeySignature.C_MAJOR),
            ),
        )

        val midi = ScoreToMidiConverter.convert(score)
        assertEquals(
            listOf(0L, 4096L, 8192L, 12288L, 16384L),
            midi.tracks.single().getNoteOnEvents().map { it.absoluteTicks },
        )

        val timeline = ScoreToMidiConverter.playbackTimeline(score)
        assertEquals(listOf(1, 2, 1, 2, 3), timeline.occurrences.map { it.measureNumber })
        assertEquals(0L, timeline.sourceTicksAt(8192L))
        assertEquals(4096L, timeline.sourceTicksAt(12288L))
    }

    @Test
    fun batchTimeCodeConversionUsesVariableMeasureDurations() {
        val score = createSimpleScore(emptyList()).copy(
            measures = createMeasureTree(
                RuntimeMeasure(1, TimeSignature(3, 4), KeySignature.C_MAJOR),
                RuntimeMeasure(2, TimeSignature(5, 8), KeySignature.C_MAJOR),
            ),
        )

        assertEquals(
            listOf(0L, 3072L, 3072L, 5632L),
            ScoreToMidiConverter.timeCodesToTicks(
                listOf(
                    TimeCode.of(1, Fraction.ZERO),
                    TimeCode.of(1, Fraction(3, 4)),
                    TimeCode.of(2, Fraction.ZERO),
                    TimeCode.of(2, Fraction(5, 8)),
                ),
                score,
            ),
        )
    }

    @Test
    fun repeatCountControlsTotalPasses() {
        val score = createSimpleScore(emptyList()).copy(
            measures = createMeasureTree(
                RuntimeMeasure(
                    1, TimeSignature.COMMON, KeySignature.C_MAJOR,
                    repeatStart = true, repeatEnd = true, repeatCount = 4,
                ),
            ),
        )

        assertEquals(
            listOf(1, 1, 1, 1),
            ScoreToMidiConverter.playbackTimeline(score).occurrences.map { it.measureNumber },
        )
    }

    @Test
    fun voltaEndingsFollowTheActiveRepeatPass() {
        val score = createSimpleScore(emptyList()).copy(
            measures = createMeasureTree(
                RuntimeMeasure(1, TimeSignature.COMMON, KeySignature.C_MAJOR, repeatStart = true),
                RuntimeMeasure(2, TimeSignature.COMMON, KeySignature.C_MAJOR),
                RuntimeMeasure(
                    3, TimeSignature.COMMON, KeySignature.C_MAJOR,
                    repeatEnd = true, voltaNumbers = setOf(1),
                ),
                RuntimeMeasure(4, TimeSignature.COMMON, KeySignature.C_MAJOR, voltaNumbers = setOf(2)),
                RuntimeMeasure(5, TimeSignature.COMMON, KeySignature.C_MAJOR),
            )
        )

        assertEquals(
            listOf(1, 2, 3, 1, 2, 4, 5),
            ScoreToMidiConverter.playbackTimeline(score).occurrences.map { it.measureNumber },
        )
    }

    @Test
    fun daCapoAlFineJumpsOnceAndStopsAtFine() {
        val score = createSimpleScore(emptyList()).copy(
            measures = createMeasureTree(
                RuntimeMeasure(1, TimeSignature.COMMON, KeySignature.C_MAJOR),
                RuntimeMeasure(
                    2, TimeSignature.COMMON, KeySignature.C_MAJOR,
                    navigationMarks = setOf(NavigationMark.FINE),
                ),
                RuntimeMeasure(
                    3, TimeSignature.COMMON, KeySignature.C_MAJOR,
                    navigationMarks = setOf(NavigationMark.DA_CAPO_AL_FINE),
                ),
            )
        )

        assertEquals(
            listOf(1, 2, 3, 1, 2),
            ScoreToMidiConverter.playbackTimeline(score).occurrences.map { it.measureNumber },
        )
    }

    @Test
    fun dalSegnoAlCodaUsesPairedSegnoToCodaAndCodaMarks() {
        val score = createSimpleScore(emptyList()).copy(
            measures = createMeasureTree(
                RuntimeMeasure(
                    1, TimeSignature.COMMON, KeySignature.C_MAJOR,
                    navigationMarks = setOf(NavigationMark.SEGNO),
                ),
                RuntimeMeasure(
                    2, TimeSignature.COMMON, KeySignature.C_MAJOR,
                    navigationMarks = setOf(NavigationMark.TO_CODA),
                ),
                RuntimeMeasure(3, TimeSignature.COMMON, KeySignature.C_MAJOR),
                RuntimeMeasure(
                    4, TimeSignature.COMMON, KeySignature.C_MAJOR,
                    navigationMarks = setOf(NavigationMark.CODA),
                ),
                RuntimeMeasure(
                    5, TimeSignature.COMMON, KeySignature.C_MAJOR,
                    navigationMarks = setOf(NavigationMark.DAL_SEGNO_AL_CODA),
                ),
            )
        )

        assertEquals(
            listOf(1, 2, 3, 4, 5, 1, 2, 4, 5),
            ScoreToMidiConverter.playbackTimeline(score).occurrences.map { it.measureNumber },
        )
    }

    @Test
    fun testRest() {
        // A rest followed by a note
        val restEvent = RuntimePitchEvent.rest(
            onset = TimeCode.of(1, Fraction.ZERO)
        )
        val noteEvent = RuntimePitchEvent.single(
            onset = TimeCode.of(1, Fraction(1, 4)),  // Beat 2 (quarter note position)
            pitch = Pitch.C4
        )

        val score = createSimpleScore(listOf(restEvent, noteEvent))
        val midiScore = ScoreToMidiConverter.convert(score)

        val track = midiScore.tracks[0]
        val noteOnEvents = track.getNoteOnEvents()

        // Rest should not generate MIDI events
        assertEquals(1, noteOnEvents.size)
        assertEquals(60, noteOnEvents[0].midiNumber)
    }

    @Test
    fun testMultipleNotes() {
        val ticksPerQuarter = MidiScore.DEFAULT_TICKS_PER_QUARTER

        // Two quarter notes
        val note1 = RuntimePitchEvent.single(
            onset = TimeCode.of(1, Fraction.ZERO),
            pitch = Pitch.C4
        )
        val note2 = RuntimePitchEvent.single(
            onset = TimeCode.of(1, Fraction(1, 4)),  // Quarter note later
            pitch = Pitch.D4
        )

        val score = createSimpleScore(listOf(note1, note2))
        val midiScore = ScoreToMidiConverter.convert(score)

        val track = midiScore.tracks[0]
        val noteOnEvents = track.getNoteOnEvents().sortedBy { it.absoluteTicks }

        assertEquals(2, noteOnEvents.size)
        assertEquals(60, noteOnEvents[0].midiNumber)  // C4
        assertEquals(62, noteOnEvents[1].midiNumber)  // D4

        // Second note should start one quarter note (1024 ticks) after first
        assertEquals(0L, noteOnEvents[0].absoluteTicks)
        assertEquals(ticksPerQuarter.toLong(), noteOnEvents[1].absoluteTicks)
    }

    @Test
    fun testNoteDuration() {
        val ticksPerQuarter = MidiScore.DEFAULT_TICKS_PER_QUARTER

        // Two notes a quarter note apart
        val note1 = RuntimePitchEvent.single(
            onset = TimeCode.of(1, Fraction.ZERO),
            pitch = Pitch.C4
        )
        val note2 = RuntimePitchEvent.single(
            onset = TimeCode.of(1, Fraction(1, 4)),  // Quarter note later
            pitch = Pitch.D4
        )

        val score = createSimpleScore(listOf(note1, note2))
        val midiScore = ScoreToMidiConverter.convert(score)

        val track = midiScore.tracks[0]
        val noteOffEvents = track.getNoteOffEvents().sortedBy { it.absoluteTicks }

        // First note should end when second note starts
        assertEquals(ticksPerQuarter.toLong(), noteOffEvents[0].absoluteTicks)
        assertEquals(60, noteOffEvents[0].midiNumber)  // C4
    }

    @Test
    fun fermataExtendsItsNoteAndDelaysFollowingPlayback() {
        var storage = StorageScore.create(StorageScore.CreationOptions(measureCount = 1))
        val pitchTrackId = storage.pitchTracks.keys.first()
        val voiceTrackId = storage.voiceTracks.keys.first()
        val firstPitch = StoragePitchEvent.single(TimeCode.of(1, Fraction.ZERO), Pitch.C4)
        val secondPitch = StoragePitchEvent.single(TimeCode.of(1, Fraction.QUARTER), Pitch.D4)
        storage = storage
            .addPitchEvent(pitchTrackId, firstPitch)
            .addVoiceEvent(
                voiceTrackId,
                StorageVoiceEvent.create(firstPitch.onset, firstPitch.id, Duration.QUARTER),
            )
            .addPitchEvent(pitchTrackId, secondPitch)
            .addVoiceEvent(
                voiceTrackId,
                StorageVoiceEvent.create(secondPitch.onset, secondPitch.id, Duration.QUARTER),
            )
            .copy(globalTrack = storage.globalTrack.copy(
                events = storage.globalTrack.events + StorageFermata.create(
                    onset = TimeCode.of(1, Fraction.QUARTER),
                    extension = Fraction.ONE,
                ),
            ))

        val midi = ScoreToMidiConverter.convert(RuntimeScore.fromStorage(storage))
        val track = midi.tracks.single()
        val noteOns = track.getNoteOnEvents().sortedBy { it.absoluteTicks }
        val noteOffs = track.getNoteOffEvents().sortedBy { it.absoluteTicks }
        val quarter = MidiScore.DEFAULT_TICKS_PER_QUARTER.toLong()

        assertEquals(quarter * 2, noteOffs.first { it.sourceEventId == firstPitch.id }.absoluteTicks)
        assertEquals(quarter * 2, noteOns.first { it.sourceEventId == secondPitch.id }.absoluteTicks)

        val timeline = ScoreToMidiConverter.playbackTimeline(RuntimeScore.fromStorage(storage))
        assertEquals(0L, timeline.sourceTicksAt(quarter + quarter / 2))
        assertEquals(quarter, timeline.sourceTicksAt(quarter * 2))
        assertEquals(
            quarter * 2,
            ScoreToMidiConverter.timeCodeToPlaybackTicks(
                TimeCode.of(1, Fraction.QUARTER),
                RuntimeScore.fromStorage(storage),
            ),
        )
    }

    @Test
    fun localBreathShortensPreviousNoteWithoutMovingFollowingBeat() {
        var storage = StorageScore.create(StorageScore.CreationOptions(measureCount = 1))
        val pitchTrackId = storage.pitchTracks.keys.first()
        val voiceTrackId = storage.voiceTracks.keys.first()
        val firstPitch = StoragePitchEvent.single(TimeCode.of(1, Fraction.ZERO), Pitch.C4)
        val secondPitch = StoragePitchEvent.single(TimeCode.of(1, Fraction.QUARTER), Pitch.D4)
        storage = storage
            .addPitchEvent(pitchTrackId, firstPitch)
            .addVoiceEvent(
                voiceTrackId,
                StorageVoiceEvent.create(firstPitch.onset, firstPitch.id, Duration.QUARTER),
            )
            .addPitchEvent(pitchTrackId, secondPitch)
            .addVoiceEvent(
                voiceTrackId,
                StorageVoiceEvent.create(secondPitch.onset, secondPitch.id, Duration.QUARTER),
            )
        val runtime = RuntimeScore.fromStorage(storage)
        val staffId = runtime.staffTracks.keys.single()
        val withBreath = assertNotNull(ExpressionEditEngine.addBreathMark(
            runtime = runtime,
            staffId = staffId,
            afterTime = TimeCode.of(1, Fraction.QUARTER),
            scope = BreathMarkScope.STAFF,
            pause = Fraction.HALF,
        )).score

        val midi = ScoreToMidiConverter.convert(withBreath)
        val track = midi.tracks.single()
        val quarter = MidiScore.DEFAULT_TICKS_PER_QUARTER.toLong()

        assertEquals(
            quarter / 2,
            track.getNoteOffEvents().first { it.sourceEventId == firstPitch.id }.absoluteTicks,
        )
        assertEquals(
            quarter,
            track.getNoteOnEvents().first { it.sourceEventId == secondPitch.id }.absoluteTicks,
        )
        assertTrue(ScoreToMidiConverter.playbackTimeline(withBreath).holds.isEmpty())
    }

    @Test
    fun globalBreathPausesScoreWithoutShorteningPreviousNote() {
        var storage = StorageScore.create(StorageScore.CreationOptions(measureCount = 1))
        val pitchTrackId = storage.pitchTracks.keys.first()
        val voiceTrackId = storage.voiceTracks.keys.first()
        val firstPitch = StoragePitchEvent.single(TimeCode.of(1, Fraction.ZERO), Pitch.C4)
        val secondPitch = StoragePitchEvent.single(TimeCode.of(1, Fraction.QUARTER), Pitch.D4)
        storage = storage
            .addPitchEvent(pitchTrackId, firstPitch)
            .addVoiceEvent(
                voiceTrackId,
                StorageVoiceEvent.create(firstPitch.onset, firstPitch.id, Duration.QUARTER),
            )
            .addPitchEvent(pitchTrackId, secondPitch)
            .addVoiceEvent(
                voiceTrackId,
                StorageVoiceEvent.create(secondPitch.onset, secondPitch.id, Duration.QUARTER),
            )
        val runtime = RuntimeScore.fromStorage(storage)
        val withBreath = assertNotNull(ExpressionEditEngine.addBreathMark(
            runtime = runtime,
            staffId = runtime.staffTracks.keys.single(),
            afterTime = TimeCode.of(1, Fraction.QUARTER),
            scope = BreathMarkScope.GLOBAL,
            pause = Fraction.HALF,
        )).score

        val midi = ScoreToMidiConverter.convert(withBreath)
        val track = midi.tracks.single()
        val quarter = MidiScore.DEFAULT_TICKS_PER_QUARTER.toLong()

        assertEquals(
            quarter,
            track.getNoteOffEvents().first { it.sourceEventId == firstPitch.id }.absoluteTicks,
        )
        assertEquals(
            quarter + quarter / 2,
            track.getNoteOnEvents().first { it.sourceEventId == secondPitch.id }.absoluteTicks,
        )
        assertEquals(quarter / 2, ScoreToMidiConverter.playbackTimeline(withBreath).holds.single().durationTicks)
    }

    @Test
    fun testTempo() {
        val score = createSimpleScore(emptyList(), tempo = 90f)
        val midiScore = ScoreToMidiConverter.convert(score)

        assertEquals(90f, midiScore.getInitialTempo())
        assertEquals(1, midiScore.tempoTrack.size)
        assertEquals(90f, midiScore.tempoTrack[0].bpm)
    }

    @Test
    fun testMeasureOffset() {
        val ticksPerQuarter = MidiScore.DEFAULT_TICKS_PER_QUARTER
        val wholeNoteTicks = 4 * ticksPerQuarter  // 4096

        // Create score with two measures
        val measure1 = RuntimeMeasure(
            number = 1,
            timeSignature = TimeSignature.COMMON,  // 4/4
            keySignature = KeySignature.C_MAJOR
        )
        val measure2 = RuntimeMeasure(
            number = 2,
            timeSignature = TimeSignature.COMMON,
            keySignature = KeySignature.C_MAJOR
        )

        // Note at beginning of measure 1
        val note1 = RuntimePitchEvent.single(
            onset = TimeCode.of(1, Fraction.ZERO),
            pitch = Pitch.C4
        )
        // Note at beginning of measure 2
        val note2 = RuntimePitchEvent.single(
            onset = TimeCode.of(2, Fraction.ZERO),
            pitch = Pitch.D4
        )

        val pitchTrack = RuntimePitchTrack(
            id = TrackId.generate(),
            name = "Test Track",
            events = TimeIndexedList.of(listOf(note1, note2))
        )

        val score = RuntimeScore(
            id = ScoreId.generate(),
            metadata = ScoreMetadata(title = "Test Score"),
            defaultTimeSignature = TimeSignature.COMMON,
            defaultKeySignature = KeySignature.C_MAJOR,
            defaultTempo = 120f,
            measures = createMeasureTree(measure1, measure2),
            staffTracks = emptyMap(),
            voiceTracks = emptyMap(),
            pitchTracks = mapOf(pitchTrack.id to pitchTrack)
        )

        val midiScore = ScoreToMidiConverter.convert(score)
        val noteOnEvents = midiScore.tracks[0].getNoteOnEvents().sortedBy { it.absoluteTicks }

        assertEquals(2, noteOnEvents.size)
        assertEquals(0L, noteOnEvents[0].absoluteTicks)  // Measure 1, beat 0
        assertEquals(wholeNoteTicks.toLong(), noteOnEvents[1].absoluteTicks)  // Measure 2 starts after one whole measure (4/4 = 1 whole note)
    }

    @Test
    fun testTicksPerQuarter() {
        val score = createSimpleScore(emptyList())
        val midiScore = ScoreToMidiConverter.convert(score)

        assertEquals(MidiScore.DEFAULT_TICKS_PER_QUARTER, midiScore.ticksPerQuarter)
        assertEquals(1024, midiScore.ticksPerQuarter)  // Matches DurationBase.QUARTER.ticks
    }

    @Test
    fun testMultipleTracks() {
        // Create two pitch tracks
        val track1 = RuntimePitchTrack(
            id = TrackId.generate(),
            name = "Track 1",
            events = TimeIndexedList.of(listOf(
                RuntimePitchEvent.single(TimeCode.of(1, Fraction.ZERO), Pitch.C4)
            ))
        )
        val track2 = RuntimePitchTrack(
            id = TrackId.generate(),
            name = "Track 2",
            events = TimeIndexedList.of(listOf(
                RuntimePitchEvent.single(TimeCode.of(1, Fraction.ZERO), Pitch.G4)
            ))
        )

        val score = RuntimeScore(
            id = ScoreId.generate(),
            metadata = ScoreMetadata(title = "Test Score"),
            defaultTimeSignature = TimeSignature.COMMON,
            defaultKeySignature = KeySignature.C_MAJOR,
            defaultTempo = 120f,
            measures = createMeasureTree(RuntimeMeasure(1, TimeSignature.COMMON, KeySignature.C_MAJOR)),
            staffTracks = emptyMap(),
            voiceTracks = emptyMap(),
            pitchTracks = mapOf(track1.id to track1, track2.id to track2)
        )

        val midiScore = ScoreToMidiConverter.convert(score)

        assertEquals(2, midiScore.tracks.size)

        // Each track should have its own note
        val allNoteOns = midiScore.tracks.flatMap { it.getNoteOnEvents() }
        val midiNumbers = allNoteOns.map { it.midiNumber }.sorted()
        assertEquals(listOf(60, 67), midiNumbers)  // C4 and G4
    }

    /**
     * Build a score with one pitch track + one matching voice track.
     * Voice events share the same onset/duration as their referenced pitch events;
     * the only additional info is [graceInfoByPitchEventId] which gets attached to
     * the first grace voice event in each group.
     */
    private fun scoreWithVoice(
        pitchEvents: List<RuntimePitchEvent>,
        graceInfoByPitchEventId: Map<EventId, GraceNoteInfo> = emptyMap(),
        pitchEventDurations: Map<EventId, Duration> = emptyMap(),
    ): RuntimeScore {
        val pitchTrack = RuntimePitchTrack(
            id = TrackId.generate(),
            name = "Pitches",
            events = TimeIndexedList.of(pitchEvents)
        )
        val voiceEvents = pitchEvents.map { pe ->
            RuntimeVoiceEvent(
                id = EventId.generate(),
                onset = pe.onset,
                pitchEvent = pe,
                duration = pitchEventDurations[pe.id] ?: Duration.QUARTER,
                graceInfo = graceInfoByPitchEventId[pe.id],
            )
        }
        val voiceTrack = RuntimeVoiceTrack(
            id = TrackId.generate(),
            name = "Voice",
            voiceNumber = 1,
            pitchTrackId = pitchTrack.id,
            pitchTrack = pitchTrack,
            events = TimeIndexedList.of(voiceEvents)
        )
        val measure = RuntimeMeasure(
            number = 1,
            timeSignature = TimeSignature.COMMON,
            keySignature = KeySignature.C_MAJOR
        )
        return RuntimeScore(
            id = ScoreId.generate(),
            metadata = ScoreMetadata(title = "Grace Test"),
            defaultTimeSignature = TimeSignature.COMMON,
            defaultKeySignature = KeySignature.C_MAJOR,
            defaultTempo = 120f,
            measures = createMeasureTree(measure),
            staffTracks = emptyMap(),
            voiceTracks = mapOf(voiceTrack.id to voiceTrack),
            pitchTracks = mapOf(pitchTrack.id to pitchTrack)
        )
    }

    /**
     * PREVIOUS: three graces on beat 0.5 should shrink the preceding quarter at beat 0
     * and sound in `[principalTicks - totalDuration, principalTicks)`.
     */
    @Test
    fun testGraceStealsFromPrevious() {
        val tpq = MidiScore.DEFAULT_TICKS_PER_QUARTER
        val totalGraceFraction = Fraction(1, 8) // one eighth note

        val prev = RuntimePitchEvent.single(TimeCode.of(1, Fraction.ZERO), Pitch.C4)
        val g1 = RuntimePitchEvent.single(
            TimeCode.of(1, Fraction(1, 2), Fraction(-1, 1)), Pitch.D4
        )
        val g2 = RuntimePitchEvent.single(
            TimeCode.of(1, Fraction(1, 2), Fraction(-2, 3)), Pitch.E4
        )
        val g3 = RuntimePitchEvent.single(
            TimeCode.of(1, Fraction(1, 2), Fraction(-1, 3)), Pitch.F4
        )
        val principal = RuntimePitchEvent.single(TimeCode.of(1, Fraction(1, 2)), Pitch.G4)

        val score = scoreWithVoice(
            pitchEvents = listOf(prev, g1, g2, g3, principal),
            graceInfoByPitchEventId = mapOf(
                g1.id to GraceNoteInfo(Duration.EIGHTH, GraceTimeSource.PREVIOUS)
            ),
        )

        val ons = ScoreToMidiConverter.convert(score)
            .tracks[0].getNoteOnEvents().associateBy { it.sourceEventId }
        val offs = ScoreToMidiConverter.convert(score)
            .tracks[0].getNoteOffEvents().associateBy { it.sourceEventId }

        val principalTicks = (4L * tpq) * 1 / 2  // beat 1/2 = 2 quarters = 2048
        val window = (4L * tpq) * totalGraceFraction.numerator / totalGraceFraction.denominator
        val perGrace = window / 3

        // Previous note now ends at windowStart, not at principal onset.
        assertEquals(principalTicks - window, offs[prev.id]!!.absoluteTicks)
        // Graces sound evenly in [windowStart, principalTicks).
        assertEquals(principalTicks - window, ons[g1.id]!!.absoluteTicks)
        assertEquals(principalTicks - window + perGrace, ons[g2.id]!!.absoluteTicks)
        assertEquals(principalTicks - window + perGrace * 2, ons[g3.id]!!.absoluteTicks)
        // Principal keeps its baseline onset.
        assertEquals(principalTicks, ons[principal.id]!!.absoluteTicks)
    }

    /**
     * PRINCIPAL: graces delay the principal; they sound in
     * `[principalTicks, principalTicks + totalDuration)` and the principal's
     * onset is pushed back by `totalDuration`.
     */
    @Test
    fun testGraceStealsFromPrincipal() {
        val tpq = MidiScore.DEFAULT_TICKS_PER_QUARTER
        val totalGraceFraction = Fraction(1, 8)

        val g1 = RuntimePitchEvent.single(
            TimeCode.of(1, Fraction.ZERO, Fraction(-1, 1)), Pitch.D4
        )
        val g2 = RuntimePitchEvent.single(
            TimeCode.of(1, Fraction.ZERO, Fraction(-1, 2)), Pitch.E4
        )
        val principal = RuntimePitchEvent.single(TimeCode.of(1, Fraction.ZERO), Pitch.C4)

        val score = scoreWithVoice(
            pitchEvents = listOf(g1, g2, principal),
            graceInfoByPitchEventId = mapOf(
                g1.id to GraceNoteInfo(Duration.EIGHTH, GraceTimeSource.PRINCIPAL)
            ),
        )

        val ons = ScoreToMidiConverter.convert(score)
            .tracks[0].getNoteOnEvents().associateBy { it.sourceEventId }

        val window = (4L * tpq) * totalGraceFraction.numerator / totalGraceFraction.denominator
        val perGrace = window / 2

        assertEquals(0L, ons[g1.id]!!.absoluteTicks)
        assertEquals(perGrace, ons[g2.id]!!.absoluteTicks)
        // Principal pushed back by the full grace window.
        assertEquals(window, ons[principal.id]!!.absoluteTicks)
    }

    @Test
    fun testVelocity() {
        val noteEvent = RuntimePitchEvent.single(
            onset = TimeCode.of(1, Fraction.ZERO),
            pitch = Pitch.C4
        )

        val score = createSimpleScore(listOf(noteEvent))
        val midiScore = ScoreToMidiConverter.convert(score)

        val noteOn = midiScore.tracks[0].getNoteOnEvents()[0]
        assertEquals(Velocity.DEFAULT.value, noteOn.velocity.value)
    }

    @Test
    fun instrumentProgramAndChannelAreSharedAcrossGrandStaff() {
        val storage = StorageScore.create(StorageScore.CreationOptions(
            instrumentTemplates = listOf(
                InstrumentTemplate(
                    name = "Organ",
                    staves = listOf(
                        StaffTemplate("Manual", Clef.TREBLE),
                        StaffTemplate("Pedal", Clef.BASS)
                    ),
                    midiProgram = 19
                )
            )
        ))
        val midi = ScoreToMidiConverter.convert(RuntimeScore.fromStorage(storage))

        assertEquals(2, midi.tracks.size)
        assertEquals(setOf(0), midi.tracks.map { it.channel.value }.toSet())
        assertTrue(midi.tracks.all { track ->
            track.events.filterIsInstance<MidiProgramChangeEvent>().single().program == 19
        })
    }

    @Test
    fun tempoKeyframesAndLinearTransitionDriveMidiTempoTrack() {
        val opening = StorageTempoEvent.create(
            onset = TimeCode.of(1, Fraction.ZERO),
            bpm = 100f,
            markType = TempoMarkType.KEYFRAME,
            displayStyle = TempoDisplayStyle.HIDDEN,
            transitionToNext = TempoTransition.LINEAR,
        )
        val destination = StorageTempoEvent.create(
            onset = TimeCode.of(1, Fraction.HALF),
            bpm = 140f,
            markType = TempoMarkType.METRONOME,
        )
        val score = createSimpleScore(emptyList()).copy(
            globalTrack = StorageGlobalTrack(
                id = TrackId.generate(),
                tempoEvents = listOf(opening, destination),
            ),
        )

        val midi = ScoreToMidiConverter.convert(score)

        assertEquals(100f, midi.tempoTrack.first().bpm)
        assertEquals(140f, midi.tempoTrack.last().bpm)
        assertTrue(midi.tempoTrack.size > 2)
        assertTrue(midi.tempoTrack.zipWithNext().all { (a, b) -> a.absoluteTicks < b.absoluteTicks })
    }

    @Test
    fun importedGrandStaffKeepsBothHandsPlayable() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <score-partwise version="4.0">
              <part-list>
                <score-part id="P1"><part-name>Piano</part-name></score-part>
              </part-list>
              <part id="P1">
                <measure number="1">
                  <attributes>
                    <divisions>1</divisions><staves>2</staves>
                    <clef number="1"><sign>G</sign><line>2</line></clef>
                    <clef number="2"><sign>F</sign><line>4</line></clef>
                  </attributes>
                  <note>
                    <pitch><step>C</step><octave>4</octave></pitch>
                    <duration>4</duration><voice>1</voice><type>whole</type><staff>1</staff>
                  </note>
                  <backup><duration>4</duration></backup>
                  <note>
                    <pitch><step>C</step><octave>3</octave></pitch>
                    <duration>4</duration><voice>1</voice><type>whole</type><staff>2</staff>
                  </note>
                </measure>
              </part>
            </score-partwise>
        """.trimIndent()

        val storage = MusicXmlConverter.import(xml).getOrThrow()
        val pitchTrackIds = storage.voiceTracks.values.map { it.pitchTrackId }
        assertEquals(2, pitchTrackIds.distinct().size)

        val midi = ScoreToMidiConverter.convert(RuntimeScore.fromStorage(storage))
        val noteOns = midi.tracks.flatMap { it.getNoteOnEvents() }
        val noteOffs = midi.tracks.flatMap { it.getNoteOffEvents() }

        assertEquals(listOf(48, 60), noteOns.map { it.midiNumber }.sorted())
        assertEquals(2, noteOffs.size)
        assertTrue(noteOffs.all { it.absoluteTicks > 0L })
    }

    @Test
    fun tiedNotesSoundOnceAndHoldForTheWholeChain() {
        val storage = StorageScore.create(
            StorageScore.CreationOptions(layout = StaffLayoutPreset.TREBLE, measureCount = 3),
        )
        val staff = storage.staffTracks.values.first()
        val voiceTrackId = staff.voiceTrackIds.first()
        val voice = storage.voiceTracks.getValue(voiceTrackId)
        val pitchTrackId = voice.pitchTrackId
        // Three whole notes on the same pitch; the first two tie into the next.
        val pitchEvents = (1..3).map { measure ->
            StoragePitchEvent(
                id = EventId("tie-pitch-$measure"),
                onset = TimeCode.ofMeasure(measure),
                pitches = listOf(Pitch.C4),
            )
        }
        val voiceEvents = pitchEvents.mapIndexed { index, pitchEvent ->
            StorageVoiceEvent(
                id = EventId("tie-voice-${index + 1}"),
                onset = pitchEvent.onset,
                pitchEventId = pitchEvent.id,
                duration = Duration.WHOLE,
                ties = if (index < 2) listOf(TieInfo(0)) else emptyList(),
            )
        }
        val tied = storage.copy(
            pitchTracks = storage.pitchTracks + (
                pitchTrackId to storage.pitchTracks.getValue(pitchTrackId).copy(events = pitchEvents)
                ),
            voiceTracks = storage.voiceTracks + (voiceTrackId to voice.copy(events = voiceEvents)),
        )

        val midi = ScoreToMidiConverter.convert(RuntimeScore.fromStorage(tied))
        val noteOns = midi.tracks.flatMap { it.getNoteOnEvents() }
        val noteOffs = midi.tracks.flatMap { it.getNoteOffEvents() }

        assertEquals(1, noteOns.size, "a tie chain is one attack, not three")
        assertEquals(0L, noteOns.single().absoluteTicks)
        assertEquals(1, noteOffs.size)
        assertEquals(
            midi.ticksPerQuarter * 12L,
            noteOffs.single().absoluteTicks,
            "the note must hold through all three tied whole notes",
        )
    }

    @Test
    fun pitchedInstrumentsSkipGeneralMidiPercussionChannel() {
        val storage = StorageScore.create(StorageScore.CreationOptions(
            instrumentTemplates = List(10) { index ->
                InstrumentTemplate(
                    name = "Instrument ${index + 1}",
                    staves = listOf(StaffTemplate("Staff ${index + 1}", Clef.TREBLE)),
                    midiProgram = 40
                )
            }
        ))

        val midi = ScoreToMidiConverter.convert(RuntimeScore.fromStorage(storage))
        val channels = midi.tracks.map { it.channel.value }

        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 10), channels)
        assertFalse(9 in channels)
    }

}
