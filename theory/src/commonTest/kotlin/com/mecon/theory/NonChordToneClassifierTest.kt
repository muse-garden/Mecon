package com.mecon.theory

import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.PitchClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NonChordToneClassifierTest {
    private val c = Chord(PitchClass.C, ChordQuality.MAJOR)
    private val f = Chord(PitchClass.F, ChordQuality.MAJOR)
    private val g = Chord(PitchClass.G, ChordQuality.MAJOR)

    @Test
    fun chordToneIsNotClassified() {
        assertNull(classify(Pitch.D4, Pitch.E4, Pitch.F4, chord = c))
    }

    @Test
    fun classifiesPassingAndNeighborByDirection() {
        assertEquals(NonChordToneType.PASSING, classify(Pitch.C4, Pitch.D4, Pitch.E4)?.primary)
        assertEquals(NonChordToneType.NEIGHBOR, classify(Pitch.E4, Pitch.F4, Pitch.E4)?.primary)
        assertEquals(
            NonChordToneType.PASSING,
            classify(Pitch.C4, Pitch(0, 1), Pitch.D4)?.primary,
        )
    }

    @Test
    fun classifiesSuspensionAndRetardationOnStrongBeat() {
        assertEquals(
            NonChordToneType.SUSPENSION,
            classify(Pitch.F4, Pitch.F4, Pitch.E4, previousChord = f, chord = c, strong = true)?.primary,
        )
        assertEquals(
            NonChordToneType.RETARDATION,
            classify(Pitch.B4, Pitch.B4, Pitch.C5, previousChord = g, chord = c, strong = true)?.primary,
        )
    }

    @Test
    fun classifiesAppoggiaturaEscapeAnticipationAndPedal() {
        assertEquals(
            NonChordToneType.APPOGGIATURA,
            classify(Pitch.C4, Pitch.F4, Pitch.E4, chord = c, strong = true)?.primary,
        )
        assertEquals(NonChordToneType.ESCAPE, classify(Pitch.C4, Pitch.D4, Pitch.G4)?.primary)
        assertEquals(
            NonChordToneType.ANTICIPATION,
            classify(Pitch.C4, Pitch.D4, Pitch.D4, chord = c, nextChord = g)?.primary,
        )
        assertEquals(
            NonChordToneType.PEDAL,
            classify(Pitch.C4, Pitch.C4, Pitch.C4, previousChord = c, chord = g, nextChord = c)?.primary,
        )
        assertEquals(
            NonChordToneType.SUSTAINED,
            classify(
                Pitch.C4,
                Pitch.C4,
                Pitch.C4,
                previousChord = c,
                chord = g,
                nextChord = c,
                boundary = VoiceBoundary.INNER,
            )?.primary,
        )
    }

    @Test
    fun escapeToneMustBelongToCurrentDiatonicScale() {
        assertEquals(
            null,
            NonChordToneClassifier.classify(
                NonChordToneContext(
                    previousPitch = Pitch.C4,
                    pitch = Pitch.D4,
                    nextPitch = Pitch.G4,
                    previousChord = c,
                    chord = c,
                    nextChord = c,
                    beatWeight = BeatWeight.WEAK,
                    isDiatonic = false,
                )
            ),
        )
    }

    private fun classify(
        previous: Pitch?,
        pitch: Pitch,
        next: Pitch?,
        previousChord: Chord? = c,
        chord: Chord = c,
        nextChord: Chord? = chord,
        strong: Boolean = false,
        boundary: VoiceBoundary? = null,
    ) = NonChordToneClassifier.classify(
        NonChordToneContext(
            previousPitch = previous,
            pitch = pitch,
            nextPitch = next,
            previousChord = previousChord,
            chord = chord,
            nextChord = nextChord,
            beatWeight = if (strong) BeatWeight.STRONG else BeatWeight.WEAK,
            voiceBoundary = boundary,
        )
    )
}
