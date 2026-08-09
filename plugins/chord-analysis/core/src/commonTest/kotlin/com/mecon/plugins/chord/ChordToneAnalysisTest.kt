package com.mecon.plugins.chord

import com.mecon.api.computed.ComputedEventStore
import com.mecon.api.computed.ComputedPitchData
import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.computed.MeasurePosition
import com.mecon.api.computed.tracks.ComputedPluginTrack
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.TimeIndexedList
import com.mecon.api.storage.StorageScore
import com.mecon.theory.ChordQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ChordToneAnalysis.compute].
 *
 * The function takes a [ComputedScore] and returns a map from
 * `(voiceEventId, pitchIndex)` to `Boolean` (true = chord tone).
 * Key scenarios:
 *  - no chords / no notes → empty result
 *  - correct membership under a single chord
 *  - note before first chord → absent from result (no chord in effect)
 *  - chord changes → each note uses the most recent chord (alignLe semantics)
 *  - multi-pitch voice events (notation chords) → every pitch classified
 */
class ChordToneAnalysisTest {

    // ---------------------------------------------------------------------------
    // Infrastructure helpers
    // ---------------------------------------------------------------------------

    private val baseRuntime: RuntimeScore by lazy {
        RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("test")))
    }

    private fun pitchData(pitch: Pitch) = ComputedPitchData(
        pitch = pitch,
        midiPitch = pitch.midiNumber,
        staffPosition = 0,
        effectiveAccidental = null,
        needsLedgerLine = false
    )

    private fun voiceEvent(
        id: String,
        onset: TimeCode,
        vararg pitches: Pitch
    ) = ComputedVoiceEvent(
        id = EventId(id),
        onset = onset,
        duration = Duration.QUARTER,
        pitchData = pitches.map { pitchData(it) },
        measurePosition = MeasurePosition(1, Fraction.ZERO, Fraction.ZERO),
        isRest = false,
        beamInfo = null
    )

    private fun chordEvent(
        onset: TimeCode,
        root: Int,
        quality: ChordQuality,
        bass: Int? = null
    ): ComputedChordEvent {
        val storage = StorageChordEvent.create(onset, root, quality, bass)
        return ComputedChordEvent.fromRuntime(RuntimeChordEvent.fromStorage(storage))
    }

    private fun score(
        voiceEvents: List<ComputedVoiceEvent> = emptyList(),
        chordEvents: List<ComputedChordEvent> = emptyList()
    ): ComputedScore {
        val pluginTracks = if (chordEvents.isEmpty()) {
            emptyMap()
        } else {
            val track = ComputedPluginTrack(
                id = TrackId("chord-track"),
                name = "Chords",
                type = StorageChordEvent.TRACK_TYPE,
                events = TimeIndexedList.of(chordEvents)
            )
            mapOf(track.id to track)
        }
        return ComputedScore(
            runtime = baseRuntime,
            computedEvents = ComputedEventStore.of(voiceEvents),
            pluginTracks = pluginTracks
        )
    }

    // ---------------------------------------------------------------------------
    // Empty / degenerate cases
    // ---------------------------------------------------------------------------

    @Test
    fun noChordEvents_returnsEmptyMap() {
        val s = score(
            voiceEvents = listOf(voiceEvent("v1", TimeCode.ofMeasure(1), Pitch.C4)),
            chordEvents = emptyList()
        )
        assertTrue(ChordToneAnalysis.compute(s).isEmpty())
    }

    @Test
    fun noVoiceEvents_returnsEmptyMap() {
        val s = score(
            voiceEvents = emptyList(),
            chordEvents = listOf(chordEvent(TimeCode.ofMeasure(1), 0, ChordQuality.MAJOR))
        )
        assertTrue(ChordToneAnalysis.compute(s).isEmpty())
    }

    @Test
    fun emptyScore_returnsEmptyMap() {
        assertTrue(ChordToneAnalysis.compute(score()).isEmpty())
    }

    // ---------------------------------------------------------------------------
    // Single chord – C major (pitchClasses: C=0, E=4, G=7)
    // ---------------------------------------------------------------------------

    @Test
    fun cMajor_c4_isChordTone() {
        val onset = TimeCode.ofMeasure(1)
        val s = score(
            voiceEvents = listOf(voiceEvent("v1", onset, Pitch.C4)),
            chordEvents = listOf(chordEvent(onset, 0, ChordQuality.MAJOR))
        )
        assertEquals(true, ChordToneAnalysis.compute(s)[EventId("v1") to 0])
    }

    @Test
    fun cMajor_e4_isChordTone() {
        val onset = TimeCode.ofMeasure(1)
        val s = score(
            voiceEvents = listOf(voiceEvent("v1", onset, Pitch.E4)),
            chordEvents = listOf(chordEvent(onset, 0, ChordQuality.MAJOR))
        )
        assertEquals(true, ChordToneAnalysis.compute(s)[EventId("v1") to 0])
    }

    @Test
    fun cMajor_g4_isChordTone() {
        val onset = TimeCode.ofMeasure(1)
        val s = score(
            voiceEvents = listOf(voiceEvent("v1", onset, Pitch.G4)),
            chordEvents = listOf(chordEvent(onset, 0, ChordQuality.MAJOR))
        )
        assertEquals(true, ChordToneAnalysis.compute(s)[EventId("v1") to 0])
    }

    @Test
    fun cMajor_d4_isNonChordTone() {
        val onset = TimeCode.ofMeasure(1)
        val s = score(
            voiceEvents = listOf(voiceEvent("v1", onset, Pitch.D4)),
            chordEvents = listOf(chordEvent(onset, 0, ChordQuality.MAJOR))
        )
        assertEquals(false, ChordToneAnalysis.compute(s)[EventId("v1") to 0])
    }

    @Test
    fun cMajor_a4_isNonChordTone() {
        val onset = TimeCode.ofMeasure(1)
        val s = score(
            voiceEvents = listOf(voiceEvent("v1", onset, Pitch.A4)),
            chordEvents = listOf(chordEvent(onset, 0, ChordQuality.MAJOR))
        )
        assertEquals(false, ChordToneAnalysis.compute(s)[EventId("v1") to 0])
    }

    // ---------------------------------------------------------------------------
    // Note before first chord → alignLe finds no candidate → absent from result
    // ---------------------------------------------------------------------------

    @Test
    fun noteBeforeFirstChord_absentFromResult() {
        val noteOnset  = TimeCode.ofMeasure(1)
        val chordOnset = TimeCode.ofMeasure(2)
        val s = score(
            voiceEvents  = listOf(voiceEvent("v1", noteOnset, Pitch.C4)),
            chordEvents  = listOf(chordEvent(chordOnset, 0, ChordQuality.MAJOR))
        )
        assertNull(
            ChordToneAnalysis.compute(s)[EventId("v1") to 0],
            "Note onset < first chord onset: no chord in effect"
        )
    }

    // ---------------------------------------------------------------------------
    // Chord changes – alignLe semantics: each note uses the most recent chord
    // whose onset ≤ note.onset
    // ---------------------------------------------------------------------------

    @Test
    fun chordChange_earlierNoteUsesCMajor_laterNoteUsesDMinor() {
        val t1 = TimeCode.ofMeasure(1)  // C major begins
        val t2 = TimeCode.ofMeasure(2)  // note in C-major zone
        val t3 = TimeCode.ofMeasure(3)  // D minor begins
        val t4 = TimeCode.ofMeasure(4)  // note in D-minor zone

        // E4 (pitchClass = 4): in C major {0,4,7} → chord tone
        //                       not in D minor {2,5,9} → non-chord tone
        val s = score(
            voiceEvents = listOf(
                voiceEvent("v-early", t2, Pitch.E4),
                voiceEvent("v-late",  t4, Pitch.E4)
            ),
            chordEvents = listOf(
                chordEvent(t1, 0, ChordQuality.MAJOR),  // C
                chordEvent(t3, 2, ChordQuality.MINOR)   // Dm
            )
        )
        val result = ChordToneAnalysis.compute(s)
        assertEquals(true,  result[EventId("v-early") to 0], "E4 under C major should be chord tone")
        assertEquals(false, result[EventId("v-late")  to 0], "E4 under D minor should be non-chord tone")
    }

    @Test
    fun chordChange_dMinorNote_fIsChordTone_eIsNot() {
        val t1 = TimeCode.ofMeasure(1)
        val t2 = TimeCode.ofMeasure(2)
        // D minor: root=2, intervals=[0,3,7] → D(2), F(5), A(9)
        val s = score(
            voiceEvents = listOf(
                voiceEvent("vF", t2, Pitch.F4),
                voiceEvent("vE", t2, Pitch.E4)
            ),
            chordEvents = listOf(chordEvent(t1, 2, ChordQuality.MINOR))
        )
        val result = ChordToneAnalysis.compute(s)
        assertEquals(true,  result[EventId("vF") to 0], "F4 is chord tone of Dm")
        assertEquals(false, result[EventId("vE") to 0], "E4 is not chord tone of Dm")
    }

    @Test
    fun noteCoincidentWithChordOnset_isClassified() {
        // alignLe includes ≤: a note at exactly the same onset as a chord should match
        val onset = TimeCode.ofMeasure(1)
        val s = score(
            voiceEvents = listOf(voiceEvent("v1", onset, Pitch.G4)),
            chordEvents = listOf(chordEvent(onset, 0, ChordQuality.MAJOR))  // C major: G is chord tone
        )
        assertEquals(true, ChordToneAnalysis.compute(s)[EventId("v1") to 0])
    }

    // ---------------------------------------------------------------------------
    // Multi-pitch voice events (notation chords)
    // ---------------------------------------------------------------------------

    @Test
    fun multiPitch_allChordTones_allMarkedTrue() {
        val onset = TimeCode.ofMeasure(1)
        // C major voice event: C4, E4, G4 → all chord tones
        val s = score(
            voiceEvents = listOf(voiceEvent("v1", onset, Pitch.C4, Pitch.E4, Pitch.G4)),
            chordEvents = listOf(chordEvent(onset, 0, ChordQuality.MAJOR))
        )
        val result = ChordToneAnalysis.compute(s)
        assertEquals(3, result.size)
        assertEquals(true, result[EventId("v1") to 0])  // C4
        assertEquals(true, result[EventId("v1") to 1])  // E4
        assertEquals(true, result[EventId("v1") to 2])  // G4
    }

    @Test
    fun multiPitch_mixedTones_classifiedIndependently() {
        val onset = TimeCode.ofMeasure(1)
        // C major (0,4,7): C4=true, D4=false, E4=true
        val s = score(
            voiceEvents = listOf(voiceEvent("v1", onset, Pitch.C4, Pitch.D4, Pitch.E4)),
            chordEvents = listOf(chordEvent(onset, 0, ChordQuality.MAJOR))
        )
        val result = ChordToneAnalysis.compute(s)
        assertEquals(true,  result[EventId("v1") to 0])  // C4 – chord tone
        assertEquals(false, result[EventId("v1") to 1])  // D4 – non-chord tone
        assertEquals(true,  result[EventId("v1") to 2])  // E4 – chord tone
    }

    @Test
    fun multiPitch_pitchIndexIsPerEvent_multipleEvents() {
        val onset = TimeCode.ofMeasure(1)
        // Two separate voice events, each multi-pitch: C major chord
        val s = score(
            voiceEvents = listOf(
                voiceEvent("v1", onset, Pitch.C4, Pitch.E4),   // pitchIndex 0=C, 1=E
                voiceEvent("v2", onset, Pitch.D4, Pitch.G4)    // pitchIndex 0=D, 1=G
            ),
            chordEvents = listOf(chordEvent(onset, 0, ChordQuality.MAJOR))
        )
        val result = ChordToneAnalysis.compute(s)
        assertEquals(true,  result[EventId("v1") to 0])  // C4
        assertEquals(true,  result[EventId("v1") to 1])  // E4
        assertEquals(false, result[EventId("v2") to 0])  // D4
        assertEquals(true,  result[EventId("v2") to 1])  // G4
    }

    // ---------------------------------------------------------------------------
    // Specific chord qualities
    // ---------------------------------------------------------------------------

    @Test
    fun dominantSeven_allFourTonesClassifiedCorrectly() {
        val onset = TimeCode.ofMeasure(1)
        // G7: root=7, intervals=[0,4,7,10] → G(7), B(11), D(2), F(5)
        val s = score(
            voiceEvents = listOf(
                voiceEvent("vG", onset, Pitch.G4),
                voiceEvent("vB", onset, Pitch.B4),
                voiceEvent("vD", onset, Pitch.D4),
                voiceEvent("vF", onset, Pitch.F4),
                voiceEvent("vE", onset, Pitch.E4)   // non-chord tone
            ),
            chordEvents = listOf(chordEvent(onset, 7, ChordQuality.DOMINANT7))
        )
        val result = ChordToneAnalysis.compute(s)
        assertEquals(true,  result[EventId("vG") to 0], "G is root of G7")
        assertEquals(true,  result[EventId("vB") to 0], "B is major 3rd of G7")
        assertEquals(true,  result[EventId("vD") to 0], "D is 5th of G7")
        assertEquals(true,  result[EventId("vF") to 0], "F is minor 7th of G7")
        assertEquals(false, result[EventId("vE") to 0], "E is not in G7")
    }

    @Test
    fun diminishedChord_onlyThreeTones() {
        val onset = TimeCode.ofMeasure(1)
        // B diminished: root=11, intervals=[0,3,6] → B(11), D(2), F(5)
        val s = score(
            voiceEvents = listOf(
                voiceEvent("vB", onset, Pitch.B4),
                voiceEvent("vD", onset, Pitch.D4),
                voiceEvent("vF", onset, Pitch.F4),
                voiceEvent("vG", onset, Pitch.G4)  // non-chord tone
            ),
            chordEvents = listOf(chordEvent(onset, 11, ChordQuality.DIMINISHED))
        )
        val result = ChordToneAnalysis.compute(s)
        assertEquals(true,  result[EventId("vB") to 0])
        assertEquals(true,  result[EventId("vD") to 0])
        assertEquals(true,  result[EventId("vF") to 0])
        assertEquals(false, result[EventId("vG") to 0])
    }

    @Test
    fun slashChord_bassNoteIsChordTone() {
        val onset = TimeCode.ofMeasure(1)
        // C/E: root=C(0), bass=E(4), quality=MAJOR → pitchClasses include E
        val s = score(
            voiceEvents = listOf(voiceEvent("vE", onset, Pitch.E4)),
            chordEvents = listOf(chordEvent(onset, 0, ChordQuality.MAJOR, bass = 4))
        )
        assertEquals(true, ChordToneAnalysis.compute(s)[EventId("vE") to 0], "E is chord tone in C/E")
    }

    // ---------------------------------------------------------------------------
    // Result key structure
    // ---------------------------------------------------------------------------

    @Test
    fun resultKeyCount_equalsVoiceEventPitchCount() {
        val onset = TimeCode.ofMeasure(1)
        // 2 voice events: 3 pitches + 1 pitch = 4 total keys
        val s = score(
            voiceEvents = listOf(
                voiceEvent("v1", onset, Pitch.C4, Pitch.E4, Pitch.G4),
                voiceEvent("v2", onset, Pitch.D4)
            ),
            chordEvents = listOf(chordEvent(onset, 0, ChordQuality.MAJOR))
        )
        assertEquals(4, ChordToneAnalysis.compute(s).size)
    }
}
