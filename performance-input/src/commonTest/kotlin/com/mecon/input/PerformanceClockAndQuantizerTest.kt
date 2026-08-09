package com.mecon.input

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.StorageTempoEvent
import com.mecon.api.storage.events.TempoTransition
import kotlin.math.abs
import com.mecon.api.storage.tracks.StorageGlobalTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PerformanceClockAndQuantizerTest {
    @Test
    fun `clock integrates tempo keyframes and converts in both directions`() {
        val storage = baseStorage(2).copy(
            globalTrack = StorageGlobalTrack(
                id = TrackId.generate(),
                tempoEvents = listOf(
                    StorageTempoEvent.create(TimeCode.of(1, Fraction.ZERO), 60f),
                    StorageTempoEvent.create(TimeCode.of(1, Fraction.HALF), 120f),
                ),
            ),
        )
        val score = RuntimeScore.fromStorage(storage)
        val clock = PerformanceClock(score, TimeCode.of(1, Fraction.ZERO), anchorNanos = 0L)

        assertEquals(Fraction(3, 4), clock.positionAt(2_500_000_000L))
        assertEquals(2_500_000_000L, clock.nanosAt(TimeCode.of(1, Fraction(3, 4))))
    }

    @Test
    fun `clock integrates a linear tempo ramp`() {
        val storage = baseStorage(1).copy(
            globalTrack = StorageGlobalTrack(
                id = TrackId.generate(),
                tempoEvents = listOf(
                    StorageTempoEvent.create(
                        TimeCode.of(1, Fraction.ZERO),
                        60f,
                        transitionToNext = TempoTransition.LINEAR,
                    ),
                    StorageTempoEvent.create(TimeCode.of(1, Fraction.HALF), 120f),
                ),
            ),
        )
        val clock = PerformanceClock(
            RuntimeScore.fromStorage(storage),
            TimeCode.of(1, Fraction.ZERO),
            anchorNanos = 0L,
        )

        val expectedNanos = (2.0 * kotlin.math.ln(2.0) * 1_000_000_000.0).toLong()
        assertTrue(abs(clock.nanosAt(TimeCode.of(1, Fraction.HALF)) - expectedNanos) < 1_000_000L)
        assertEquals(Fraction.HALF, clock.positionAt(expectedNanos))
    }

    @Test
    fun `straight wins ties and triplet boundary is available`() {
        val settings = QuantizationSettings()
        assertEquals(
            QuantizedGridKind.STRAIGHT,
            PerformanceQuantizer.nearestBoundary(Fraction.QUARTER, settings).grid,
        )
        val triplet = PerformanceQuantizer.nearestBoundary(Fraction(1, 12), settings)
        assertEquals(Fraction(1, 12), triplet.position)
        assertEquals(QuantizedGridKind.TRIPLET, triplet.grid)
    }

    @Test
    fun `unequal chord releases become held-set segments with per-pitch ties`() {
        val score = RuntimeScore.fromStorage(baseStorage(1))
        val clock = PerformanceClock(score, TimeCode.of(1, Fraction.ZERO), 0L)
        val c = Pitch.C4
        val e = Pitch(2, 0)
        val take = RawPerformanceTake(
            startedAtNanos = 0L,
            endedAtNanos = 1_000_000_000L,
            notes = listOf(
                RawPlayedNote(InputKeyId("midi", 60), c, 0L, 1_000_000_000L, 90),
                RawPlayedNote(InputKeyId("midi", 64), e, 20_000_000L, 500_000_000L, 90),
            ),
        )

        val result = PerformanceQuantizer.quantize(
            take,
            clock,
            QuantizationSettings(allowedTuplets = emptySet()),
        )!!

        assertEquals(2, result.segments.size)
        assertEquals(listOf(c.midiNumber, e.midiNumber), result.segments[0].pitches.map { it.midiNumber })
        assertEquals(setOf(c.midiNumber), result.segments[0].tieOutMidi)
        assertEquals(listOf(c.midiNumber), result.segments[1].pitches.map { it.midiNumber })
        assertTrue(result.segments[1].tieOutMidi.isEmpty())
    }

    private fun baseStorage(measures: Int): StorageScore =
        StorageScore.create(
            StorageScore.CreationOptions(
                title = "performance",
                timeSignature = TimeSignature.COMMON,
                keySignature = KeySignature.C_MAJOR,
                measureCount = measures,
            ),
        )
}
