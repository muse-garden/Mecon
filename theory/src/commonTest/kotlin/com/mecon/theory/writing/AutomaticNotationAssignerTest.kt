package com.mecon.theory.writing

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals

class AutomaticNotationAssignerTest {
    private val upper = NotationLaneSpec(
        id = TrackId("upper"),
        order = 0,
        lowest = Pitch.fromMidi(60),
        highest = Pitch.fromMidi(84),
    )
    private val lower = NotationLaneSpec(
        id = TrackId("lower"),
        order = 1,
        lowest = Pitch.fromMidi(36),
        highest = Pitch.fromMidi(64),
    )

    @Test
    fun prefersExactSlotSoRepeatedEntryFormsAChord() {
        val selected = AutomaticNotationAssigner.assign(
            lanes = listOf(upper, lower),
            events = listOf(
                NotationEventSpan(
                    laneId = upper.id,
                    onset = Fraction.ZERO,
                    duration = Fraction.QUARTER,
                    pitches = listOf(Pitch.fromMidi(72)),
                )
            ),
            pending = PendingNotationNote(
                onset = Fraction.ZERO,
                duration = Fraction.QUARTER,
                pitch = Pitch.fromMidi(67),
            ),
        )

        assertEquals(upper.id, selected)
    }

    @Test
    fun doesNotMoveOrOverwriteAnOverlappingLane() {
        val selected = AutomaticNotationAssigner.assign(
            lanes = listOf(upper, lower),
            events = listOf(
                NotationEventSpan(
                    laneId = upper.id,
                    onset = Fraction.ZERO,
                    duration = Fraction.HALF,
                    pitches = listOf(Pitch.fromMidi(72)),
                )
            ),
            pending = PendingNotationNote(
                onset = Fraction.QUARTER,
                duration = Fraction.QUARTER,
                pitch = Pitch.fromMidi(60),
            ),
        )

        assertEquals(lower.id, selected)
    }

    @Test
    fun lowChordToneUsesLowerDefaultLaneEvenWhenHighToneWasEnteredFirst() {
        val selected = AutomaticNotationAssigner.assign(
            lanes = listOf(upper, lower),
            events = listOf(
                NotationEventSpan(
                    laneId = upper.id,
                    onset = Fraction.ZERO,
                    duration = Fraction.QUARTER,
                    pitches = listOf(Pitch.fromMidi(79)),
                )
            ),
            pending = PendingNotationNote(
                onset = Fraction.ZERO,
                duration = Fraction.QUARTER,
                pitch = Pitch.fromMidi(48),
            ),
        )

        assertEquals(lower.id, selected)
    }

    @Test
    fun contiguousCellsStayInOneLane() {
        val selected = AutomaticNotationAssigner.assign(
            lanes = listOf(upper, lower),
            events = listOf(
                NotationEventSpan(
                    laneId = lower.id,
                    onset = Fraction.ZERO,
                    duration = Fraction.EIGHTH,
                    pitches = listOf(Pitch.fromMidi(60)),
                )
            ),
            pending = PendingNotationNote(
                onset = Fraction.EIGHTH,
                duration = Fraction.EIGHTH,
                pitch = Pitch.fromMidi(65),
            ),
        )

        assertEquals(lower.id, selected)
    }
}
