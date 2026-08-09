package com.mecon.plugins.chord

import com.mecon.api.computed.ComputedEventStore
import com.mecon.api.computed.ComputedPitchData
import com.mecon.api.computed.ComputedPluginEvent
import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.computed.MeasurePosition
import com.mecon.api.computed.tracks.ComputedPluginTrack
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.TimeIndexedList
import com.mecon.api.runtime.events.RuntimePluginEvent
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.StoragePluginEvent
import com.mecon.theory.ChordQuality
import com.mecon.theory.KeySignatureMode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolyphonyAnalysisTest {
    private val runtime: RuntimeScore by lazy {
        RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("polyphony")))
    }

    @BeforeTest
    fun reset() {
        PolyphonyAnalysisEngine.resetForTesting()
    }

    @Test
    fun exactActiveSonorityIsRecognizedAtVoiceMovement() {
        val time = tc(0)
        val score = score(
            listOf(
                voice("c", time, Pitch.C4),
                voice("e", time, Pitch.E4),
                voice("g", time, Pitch.G4),
            )
        )

        val frame = PolyphonyAnalysisEngine.compute(score).getValue(time)

        assertEquals(listOf(Pitch.C4, Pitch.E4, Pitch.G4), frame.activePitches.map { it.pitch })
        assertEquals(ChordQuality.MAJOR, frame.recognizedChord?.quality)
        assertEquals(0, frame.recognizedChord?.root?.value)
    }

    @Test
    fun explicitNonChordSliceIsExcludedFromPassingChordRecognition() {
        val time = tc(0)
        val d = voice("d", time, Pitch.D4)
        val mark = StorageNonChordToneEvent.create(
            onset = time,
            endOnset = d.endTime,
            voiceEventId = d.id,
            pitchIndex = 0,
        )
        val score = score(
            events = listOf(
                voice("c", time, Pitch.C4),
                d,
                voice("e", time, Pitch.E4),
                voice("g", time, Pitch.G4),
            ),
            pluginTracks = listOf(pluginTrack(StorageNonChordToneEvent.TRACK_TYPE, listOf(mark))),
        )

        val chord = PolyphonyAnalysisEngine.compute(score).getValue(time).recognizedChord

        assertEquals(ChordQuality.MAJOR, chord?.quality)
        assertEquals(0, chord?.root?.value)
    }

    @Test
    fun ambiguousRegionShowsEveryKeyAndResolvesAfterItsEnd() {
        val start = tc(0)
        val end = tc(2)
        val cMajor = PolyphonyTonalKey(0, KeySignatureMode.MAJOR)
        val gMajor = PolyphonyTonalKey(1, KeySignatureMode.MAJOR)
        val region = StorageTonalRegionEvent.create(
            onset = start,
            endOnset = end,
            keys = listOf(cMajor, gMajor),
            resolvedKey = gMajor,
        )
        val score = score(
            events = emptyList(),
            pluginTracks = listOf(pluginTrack(StorageTonalRegionEvent.TRACK_TYPE, listOf(region))),
        )

        assertEquals(
            listOf(cMajor, gMajor),
            PolyphonyTonalContextResolver.keysAt(score, tc(1)).map(PolyphonyTonalKey::from),
        )
        assertEquals(
            listOf(gMajor),
            PolyphonyTonalContextResolver.keysAt(score, tc(3)).map(PolyphonyTonalKey::from),
        )
        assertEquals(
            "C:1 · G:4",
            PolyphonyDegreeFormatter.format(
                listOf(cMajor.toModulationKey(), gMajor.toModulationKey()),
                Pitch.C4,
            ),
        )
    }

    @Test
    fun singlePitchEditPatchesOnlyItsSoundingWindow() {
        val events = (1..80).map { measure ->
            voice("v$measure", TimeCode.ofMeasure(measure), Pitch.C4)
        }
        val initial = score(events)
        val initialFrames = PolyphonyAnalysisEngine.compute(initial)
        val changedTime = TimeCode.ofMeasure(41)
        val changedEvent = voice("v41", changedTime, Pitch.F4)
        val changed = initial.copy(
            computedEvents = initial.computedEvents.put(changedEvent)
        )

        PolyphonyAnalysisEngine.compute(changed)

        assertTrue(PolyphonyAnalysisEngine.lastRecomputedFrameCount < initialFrames.size)
        assertEquals(Pitch.F4, PolyphonyAnalysisEngine.compute(changed)
            .getValue(changedTime).activePitches.single().pitch)
    }

    @Test
    fun ambiguousIncompleteSonorityIsNotAutoRecognized() {
        val time = tc(0)
        val frame = PolyphonyAnalysisEngine.compute(
            score(listOf(voice("c", time, Pitch.C4), voice("g", time, Pitch.G4)))
        ).getValue(time)
        assertNull(frame.recognizedChord)
    }

    @Test
    fun nativeKeySignatureEditInvalidatesTonalFrameCache() {
        val time = tc(0)
        val events = listOf(voice("c", time, Pitch.C4))
        val initial = score(events)
        assertEquals(0, PolyphonyAnalysisEngine.compute(initial).getValue(time).tonalKeys.single().fifths)

        val changedMeasure = runtime.getMeasure(1)!!.copy(keySignature = KeySignature.G_MAJOR)
        val changedRuntime = runtime.copy(
            defaultKeySignature = KeySignature.G_MAJOR,
            measures = runtime.measures.put(1, changedMeasure),
        )
        val changed = score(events, scoreRuntime = changedRuntime)

        assertEquals(1, PolyphonyAnalysisEngine.compute(changed).getValue(time).tonalKeys.single().fifths)
        assertTrue(PolyphonyAnalysisEngine.lastRecomputedFrameCount > 0)
    }

    private fun score(
        events: List<ComputedVoiceEvent>,
        pluginTracks: List<ComputedPluginTrack<*>> = emptyList(),
        scoreRuntime: RuntimeScore = runtime,
    ) = ComputedScore(
        runtime = scoreRuntime,
        computedEvents = ComputedEventStore.of(events),
        pluginTracks = pluginTracks.associateBy { it.id },
    )

    private fun voice(id: String, onset: TimeCode, pitch: Pitch) = ComputedVoiceEvent(
        id = EventId(id),
        onset = onset,
        duration = Duration.QUARTER,
        pitchData = listOf(
            ComputedPitchData(
                pitch = pitch,
                midiPitch = pitch.midiNumber,
                staffPosition = 0,
                effectiveAccidental = null,
                needsLedgerLine = false,
            )
        ),
        measurePosition = MeasurePosition(1, onset.beat ?: Fraction.ZERO, Fraction.ZERO),
        isRest = false,
        beamInfo = null,
    )

    private fun <T : StoragePluginEvent> pluginTrack(
        type: String,
        events: List<T>,
    ): ComputedPluginTrack<T> {
        val computed = events.map { storage ->
            val runtimeEvent = object : RuntimePluginEvent<T> {
                override val id = storage.id
                override val onset = storage.onset
                override val storageEvent = storage
            }
            object : ComputedPluginEvent<T> {
                override val id = storage.id
                override val onset = storage.onset
                override val runtimeEvent = runtimeEvent
            }
        }
        return ComputedPluginTrack(
            id = TrackId(type),
            name = type,
            type = type,
            events = TimeIndexedList.of(computed),
        )
    }

    private fun tc(quarter: Int): TimeCode =
        TimeCode.of(1, Fraction(quarter, 4))
}
