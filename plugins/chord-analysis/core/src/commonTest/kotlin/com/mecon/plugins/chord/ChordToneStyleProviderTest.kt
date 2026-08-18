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
import com.mecon.api.render.RenderColor
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.TimeIndexedList
import com.mecon.api.storage.StorageScore
import com.mecon.theory.ChordQuality
import com.mecon.theory.NonChordToneType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [ChordToneStyleProvider]:
 *  - the `isEnabled` toggle short-circuits to an empty map
 *  - chord tones receive the green fill color (rgb 34, 197, 94)
 *  - unclassified non-chord tones receive the red fallback color
 *  - correct number of entries is produced
 *
 * [ChordToneStyleProvider] is a singleton object with mutable state ([isEnabled]).
 * Each test resets that state in [setUp] and [tearDown] to prevent cross-test pollution.
 */
class ChordToneStyleProviderTest {

    private val baseRuntime: RuntimeScore by lazy {
        RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("test")))
    }

    @BeforeTest
    fun setUp() {
        ChordToneStyleProvider.isEnabled = true
    }

    @AfterTest
    fun tearDown() {
        ChordToneStyleProvider.isEnabled = false
    }

    // ---------------------------------------------------------------------------
    // Infrastructure helpers (duplicated from ChordToneAnalysisTest for clarity)
    // ---------------------------------------------------------------------------

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

    private fun chordEvent(onset: TimeCode, root: Int, quality: ChordQuality): ComputedChordEvent {
        val storage = StorageChordEvent.create(onset, root, quality)
        return ComputedChordEvent.fromRuntime(RuntimeChordEvent.fromStorage(storage))
    }

    private fun score(
        voiceEvents: List<ComputedVoiceEvent>,
        chordEvents: List<ComputedChordEvent>
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

    private val green  = RenderColor.rgb(34,  197,  94)
    private val unclassified = RenderColor.rgb(239, 68, 68)

    // ---------------------------------------------------------------------------
    // Toggle (isEnabled)
    // ---------------------------------------------------------------------------

    @Test
    fun disabled_returnsEmptyMapRegardlessOfContent() {
        ChordToneStyleProvider.isEnabled = false
        val onset = TimeCode.ofMeasure(1)
        val s = score(
            voiceEvents = listOf(voiceEvent("v1", onset, Pitch.C4)),
            chordEvents = listOf(chordEvent(onset, 0, ChordQuality.MAJOR))
        )
        assertTrue(ChordToneStyleProvider.computeStyles(s).isEmpty())
    }

    @Test
    fun enabled_withNoChords_returnsEmptyMap() {
        val s = score(
            voiceEvents = listOf(voiceEvent("v1", TimeCode.ofMeasure(1), Pitch.C4)),
            chordEvents = emptyList()
        )
        assertTrue(ChordToneStyleProvider.computeStyles(s).isEmpty())
    }

    @Test
    fun toggleOffThenOn_restoresStyles() {
        val onset = TimeCode.ofMeasure(1)
        val s = score(
            voiceEvents = listOf(voiceEvent("v1", onset, Pitch.C4)),
            chordEvents = listOf(chordEvent(onset, 0, ChordQuality.MAJOR))
        )

        ChordToneStyleProvider.isEnabled = false
        assertTrue(ChordToneStyleProvider.computeStyles(s).isEmpty())

        ChordToneStyleProvider.isEnabled = true
        assertFalse(ChordToneStyleProvider.computeStyles(s).isEmpty())
    }

    // ---------------------------------------------------------------------------
    // Color assignment
    // ---------------------------------------------------------------------------

    @Test
    fun everyNonChordToneType_hasLegendAndRenderColor() {
        assertEquals(NonChordToneType.entries.toSet(), ChordToneStyleProvider.typeColors.keys)
    }

    @Test
    fun chordTone_receivesGreenFillColor() {
        val onset = TimeCode.ofMeasure(1)
        // C major (0,4,7) — C4 is chord tone
        val s = score(
            voiceEvents = listOf(voiceEvent("v1", onset, Pitch.C4)),
            chordEvents = listOf(chordEvent(onset, 0, ChordQuality.MAJOR))
        )
        val override = ChordToneStyleProvider.computeStyles(s)[EventId("v1") to 0]
        assertNotNull(override)
        assertEquals(green, override.fillColor)
    }

    @Test
    fun unclassifiedNonChordTone_receivesFallbackColor() {
        val onset = TimeCode.ofMeasure(1)
        // C major (0,4,7) — D4 (pitchClass=2) is NOT a chord tone
        val s = score(
            voiceEvents = listOf(voiceEvent("v1", onset, Pitch.D4)),
            chordEvents = listOf(chordEvent(onset, 0, ChordQuality.MAJOR))
        )
        val override = ChordToneStyleProvider.computeStyles(s)[EventId("v1") to 0]
        assertNotNull(override)
        assertEquals(unclassified, override.fillColor)
    }

    @Test
    fun mixedNotes_correctColorPerPitch() {
        val onset = TimeCode.ofMeasure(1)
        // C major: C4(chord tone=green), D4(non=orange), E4(chord tone=green)
        val s = score(
            voiceEvents = listOf(voiceEvent("v1", onset, Pitch.C4, Pitch.D4, Pitch.E4)),
            chordEvents = listOf(chordEvent(onset, 0, ChordQuality.MAJOR))
        )
        val styles = ChordToneStyleProvider.computeStyles(s)
        assertEquals(green,  styles[EventId("v1") to 0]?.fillColor)  // C4
        assertEquals(unclassified, styles[EventId("v1") to 1]?.fillColor)  // D4
        assertEquals(green,  styles[EventId("v1") to 2]?.fillColor)  // E4
    }

    @Test
    fun dominant7_allFourTones_green_nonTone_orange() {
        val onset = TimeCode.ofMeasure(1)
        // G7: G(7), B(11), D(2), F(5) — chord tones; E(4) is not
        val s = score(
            voiceEvents = listOf(
                voiceEvent("vG", onset, Pitch.G4),
                voiceEvent("vB", onset, Pitch.B4),
                voiceEvent("vD", onset, Pitch.D4),
                voiceEvent("vF", onset, Pitch.F4),
                voiceEvent("vE", onset, Pitch.E4)
            ),
            chordEvents = listOf(chordEvent(onset, 7, ChordQuality.DOMINANT7))
        )
        val styles = ChordToneStyleProvider.computeStyles(s)
        assertEquals(green,  styles[EventId("vG") to 0]?.fillColor)
        assertEquals(green,  styles[EventId("vB") to 0]?.fillColor)
        assertEquals(green,  styles[EventId("vD") to 0]?.fillColor)
        assertEquals(green,  styles[EventId("vF") to 0]?.fillColor)
        assertEquals(unclassified, styles[EventId("vE") to 0]?.fillColor)
    }

    // ---------------------------------------------------------------------------
    // Entry count
    // ---------------------------------------------------------------------------

    @Test
    fun styleCount_matchesTotalPitchCount() {
        val onset = TimeCode.ofMeasure(1)
        // 3-pitch event + 1-pitch event = 4 style entries
        val s = score(
            voiceEvents = listOf(
                voiceEvent("v1", onset, Pitch.C4, Pitch.E4, Pitch.G4),
                voiceEvent("v2", onset, Pitch.D4)
            ),
            chordEvents = listOf(chordEvent(onset, 0, ChordQuality.MAJOR))
        )
        assertEquals(4, ChordToneStyleProvider.computeStyles(s).size)
    }

    @Test
    fun noteBeforeFirstChord_producesNoStyleEntry() {
        // Note at measure 1, chord at measure 2: alignLe finds no chord → note absent from analysis
        val s = score(
            voiceEvents = listOf(voiceEvent("v1", TimeCode.ofMeasure(1), Pitch.C4)),
            chordEvents = listOf(chordEvent(TimeCode.ofMeasure(2), 0, ChordQuality.MAJOR))
        )
        assertTrue(ChordToneStyleProvider.computeStyles(s).isEmpty())
    }

    // ---------------------------------------------------------------------------
    // StyleOverride structure
    // ---------------------------------------------------------------------------

    @Test
    fun styleOverride_onlySetsFillColor_backgroundColorIsNull() {
        val onset = TimeCode.ofMeasure(1)
        val s = score(
            voiceEvents = listOf(voiceEvent("v1", onset, Pitch.C4)),
            chordEvents = listOf(chordEvent(onset, 0, ChordQuality.MAJOR))
        )
        val override = ChordToneStyleProvider.computeStyles(s)[EventId("v1") to 0]
        assertNotNull(override)
        assertNotNull(override.fillColor)
        assertEquals(null, override.backgroundColor)
    }

    // ---------------------------------------------------------------------------
    // Chord change: re-evaluates per active chord
    // ---------------------------------------------------------------------------

    @Test
    fun chordChange_colorFollowsActiveChord() {
        val t1 = TimeCode.ofMeasure(1)  // C major
        val t2 = TimeCode.ofMeasure(2)  // note under C major
        val t3 = TimeCode.ofMeasure(3)  // D minor
        val t4 = TimeCode.ofMeasure(4)  // note under D minor

        // E4: chord tone in C major (green), non-chord tone in D minor (orange)
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
        val styles = ChordToneStyleProvider.computeStyles(s)
        assertEquals(green,  styles[EventId("v-early") to 0]?.fillColor)
        assertEquals(unclassified, styles[EventId("v-late")  to 0]?.fillColor)
    }
}
