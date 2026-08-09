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
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end golden-rule tests for the now-stateful incremental [ChordToneAnalysis].
 *
 * [ChordToneAnalysis] caches its aligner between calls, so a chain of `compute` calls on edited scores
 * exercises the incremental window+splice path. Each incremental result must equal a **cold** full compute
 * of the same score — obtained here by first resetting the cache with an empty score (no chord track →
 * `reset()` clears the cache) and then computing the target.
 */
class ChordToneAnalysisIncrementalTest {

    private val baseRuntime = RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("test")))

    private data class V(val id: String, val tick: Int, val pitch: Pitch)
    private data class C(val tick: Int, val root: Int, val quality: ChordQuality)

    private fun tc(tick: Int) = TimeCode.of(tick / 4 + 1, Fraction(tick % 4, 4))

    private fun voiceEvent(v: V) = ComputedVoiceEvent(
        id = EventId(v.id),
        onset = tc(v.tick),
        duration = Duration.QUARTER,
        pitchData = listOf(
            ComputedPitchData(
                pitch = v.pitch,
                midiPitch = v.pitch.midiNumber,
                staffPosition = 0,
                effectiveAccidental = null,
                needsLedgerLine = false,
            )
        ),
        measurePosition = MeasurePosition(v.tick / 4 + 1, Fraction.ZERO, Fraction.ZERO),
        isRest = false,
        beamInfo = null,
    )

    private fun chordEvent(c: C) =
        ComputedChordEvent.fromRuntime(
            RuntimeChordEvent.fromStorage(StorageChordEvent.create(tc(c.tick), c.root, c.quality))
        )

    private fun score(voices: List<V>, chords: List<C>): ComputedScore {
        val pluginTracks = if (chords.isEmpty()) emptyMap() else {
            val track = ComputedPluginTrack(
                id = TrackId("chord-track"),
                name = "Chords",
                type = StorageChordEvent.TRACK_TYPE,
                events = TimeIndexedList.of(chords.map { chordEvent(it) }),
            )
            mapOf(track.id to track)
        }
        return ComputedScore(
            runtime = baseRuntime,
            computedEvents = ComputedEventStore.of(voices.map { voiceEvent(it) }),
            pluginTracks = pluginTracks,
        )
    }

    /** Cold full compute: reset the singleton cache (empty score), then compute the target from scratch. */
    private fun fullCompute(s: ComputedScore): Map<Pair<EventId, Int>, Boolean> {
        ChordToneAnalysis.compute(score(emptyList(), emptyList()))
        return ChordToneAnalysis.compute(s)
    }

    /** Run [scores] as one incremental session, asserting each step equals its cold full compute. */
    private fun assertIncrementalMatchesFull(scores: List<ComputedScore>) {
        val incremental = ArrayList<Map<Pair<EventId, Int>, Boolean>>()
        ChordToneAnalysis.compute(score(emptyList(), emptyList())) // reset → first real compute is a cold build
        for (s in scores) incremental.add(ChordToneAnalysis.compute(s).toMap())
        for ((i, s) in scores.withIndex()) {
            assertEquals(fullCompute(s), incremental[i], "incremental != full at step $i")
        }
    }

    @Test
    fun deterministicEditSequence() {
        val v = mutableListOf(
            V("v1", 0, Pitch.C4),
            V("v2", 2, Pitch.E4),
            V("v3", 4, Pitch.G4),   // measure 2
            V("v4", 8, Pitch.D4),   // measure 3
        )
        val c = mutableListOf(
            C(0, 0, ChordQuality.MAJOR),  // C major at start
            C(4, 7, ChordQuality.MAJOR),  // G major at measure 2
        )

        val steps = ArrayList<ComputedScore>()
        steps.add(score(v, c)) // 0: base

        // 1: edit v2 pitch E4 → F4 (now a non-chord tone under C major)
        v[1] = v[1].copy(pitch = Pitch.F4); steps.add(score(v, c))
        // 2: add a note in measure 2
        v.add(V("v5", 6, Pitch.B4)); steps.add(score(v, c))
        // 3: move the G chord from measure 2 start to mid-measure (tick 4 → 6)
        c[1] = c[1].copy(tick = 6); steps.add(score(v, c))
        // 4: change the C chord quality MAJOR → MINOR
        c[0] = c[0].copy(quality = ChordQuality.MINOR); steps.add(score(v, c))
        // 5: remove v3
        v.removeAll { it.id == "v3" }; steps.add(score(v, c))
        // 6: add a new chord at measure 3
        c.add(C(8, 2, ChordQuality.MINOR)); steps.add(score(v, c))

        assertIncrementalMatchesFull(steps)
    }

    @Test
    fun randomEditSequenceMatchesFull() {
        val rnd = Random(2026_06_15)
        val pitches = listOf(Pitch.C4, Pitch.D4, Pitch.E4, Pitch.F4, Pitch.G4, Pitch.A4, Pitch.B4)
        val qualities = listOf(ChordQuality.MAJOR, ChordQuality.MINOR, ChordQuality.DOMINANT7)

        var voices = mutableListOf(V("v0", 0, Pitch.C4), V("v1", 4, Pitch.E4))
        var chords = mutableListOf(C(0, 0, ChordQuality.MAJOR))
        var nextId = 100

        val steps = ArrayList<ComputedScore>()
        steps.add(score(voices, chords))
        repeat(40) {
            val voiceTicks = voices.map { it.tick }.toMutableSet()
            val chordTicks = chords.map { it.tick }.toMutableSet()
            when (rnd.nextInt(6)) {
                0 -> { val t = rnd.nextInt(0, 24); if (t !in voiceTicks) voices.add(V("v${nextId++}", t, pitches.random(rnd))) }
                1 -> if (voices.size > 1) voices.removeAt(rnd.nextInt(voices.size))
                2 -> if (voices.isNotEmpty()) { val i = rnd.nextInt(voices.size); voices[i] = voices[i].copy(pitch = pitches.random(rnd)) }
                3 -> { val t = rnd.nextInt(0, 24); if (t !in chordTicks) chords.add(C(t, rnd.nextInt(0, 12), qualities.random(rnd))) }
                4 -> if (chords.size > 1) chords.removeAt(rnd.nextInt(chords.size))
                5 -> if (chords.isNotEmpty()) { val i = rnd.nextInt(chords.size); chords[i] = chords[i].copy(quality = qualities.random(rnd)) }
            }
            voices = voices.sortedBy { it.tick }.toMutableList()
            chords = chords.sortedBy { it.tick }.toMutableList()
            steps.add(score(voices, chords))
        }
        assertIncrementalMatchesFull(steps)
    }
}
