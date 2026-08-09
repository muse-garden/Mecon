package com.mecon.theory

import com.mecon.api.primitive.Interval
import com.mecon.api.primitive.IntervalQuality
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.PitchClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TheoryTest {
    @Test
    fun testIntervalQuality() {
        val interval = Interval(12) // Octave
        assertEquals(IntervalQuality.PERFECT, interval.quality)
    }

    @Test
    fun testScaleAndKey() {
        val cMajor = Key.major(PitchClass.C)
        val scale = cMajor.scale
        assertTrue(scale.contains(PitchClass.C))
        assertTrue(scale.contains(PitchClass.E))
        assertTrue(scale.contains(PitchClass.G))
        assertEquals(7, scale.pitchClasses.size)
    }

    @Test
    fun testChord() {
        val cMajor = Chord.create(PitchClass.C, ChordQuality.MAJOR)
        assertEquals(listOf(PitchClass.C, PitchClass.E, PitchClass.G), cMajor.pitchClasses)

        val g7 = Chord.create(PitchClass.G, ChordQuality.DOMINANT7)
        assertEquals(listOf(PitchClass.G, PitchClass.B, PitchClass.D, PitchClass.F), g7.pitchClasses)
    }

    @Test
    fun testChordParse() {
        val cMaj7 = Chord.parse("Cmaj7")
        assertEquals(PitchClass.C, cMaj7.root)
        assertEquals(ChordQuality.MAJOR7, cMaj7.quality)

        val fSharpMin = Chord.parse("F#m")
        assertEquals(PitchClass(6), fSharpMin.root) // F#
        assertEquals(ChordQuality.MINOR, fSharpMin.quality)
    }

    @Test
    fun testPitchExtensions() {
        val cMaj = Key.major(PitchClass.C)
        val pitchE = Pitch.parse("E4")
        assertEquals(3, pitchE.degree(cMaj))

        val fromDegree5 = Pitch.fromDegree(5, cMaj)
        assertEquals(PitchClass.G, fromDegree5.pitchClass)
    }

    @Test
    fun keySignatureModeInterpretsSameFifthsAsMajorOrRelativeMinor() {
        val cMajor = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
        val aMinor = Key.fromKeySignatureFifths(0, KeySignatureMode.MINOR)

        assertEquals(PitchClass.C, cMajor.root)
        assertEquals(Mode.IONIAN, cMajor.mode)
        assertEquals(PitchClass.A, aMinor.root)
        assertEquals(Mode.AEOLIAN, aMinor.mode)
        assertEquals(PitchClass.C, KeySignatureMode.MINOR.signatureRootForTonic(PitchClass.A))
    }

    @Test
    fun majorKeyHasSevenNaturalTriads() {
        val triads = Key.major(PitchClass.C).naturalTriads

        assertEquals(
            listOf(
                PitchClass.C to ChordQuality.MAJOR,
                PitchClass.D to ChordQuality.MINOR,
                PitchClass.E to ChordQuality.MINOR,
                PitchClass.F to ChordQuality.MAJOR,
                PitchClass.G to ChordQuality.MAJOR,
                PitchClass.A to ChordQuality.MINOR,
                PitchClass.B to ChordQuality.DIMINISHED,
            ),
            triads.map { it.root to it.quality },
        )
        assertEquals((1..7).toList(), triads.map { it.degree })
        assertEquals((1..7).toList(), triads.map { it.signatureDegree })
        assertTrue(Key.major(PitchClass.C).isNaturalTriad(Chord(PitchClass.G, ChordQuality.MAJOR)))
        assertFalse(Key.major(PitchClass.C).isNaturalTriad(Chord(PitchClass.G, ChordQuality.MINOR)))
    }

    @Test
    fun minorKeyIncludesNaturalHarmonicAndMelodicTriads() {
        val triads = Key.minor(PitchClass.A).naturalTriads
        val byIdentity = triads.associateBy { it.root to it.quality }

        assertEquals(13, triads.size)
        assertTrue(PitchClass.A to ChordQuality.MINOR in byIdentity)
        assertTrue(PitchClass.B to ChordQuality.DIMINISHED in byIdentity)
        assertTrue(PitchClass.B to ChordQuality.MINOR in byIdentity)
        assertTrue(PitchClass.C to ChordQuality.MAJOR in byIdentity)
        assertTrue(PitchClass.C to ChordQuality.AUGMENTED in byIdentity)
        assertTrue(PitchClass.D to ChordQuality.MINOR in byIdentity)
        assertTrue(PitchClass.D to ChordQuality.MAJOR in byIdentity)
        assertTrue(PitchClass.E to ChordQuality.MINOR in byIdentity)
        assertTrue(PitchClass.E to ChordQuality.MAJOR in byIdentity)
        assertTrue(PitchClass.F to ChordQuality.MAJOR in byIdentity)
        assertTrue(PitchClass(6) to ChordQuality.DIMINISHED in byIdentity)
        assertTrue(PitchClass.G to ChordQuality.MAJOR in byIdentity)
        assertTrue(PitchClass(8) to ChordQuality.DIMINISHED in byIdentity)

        val eMajor = byIdentity.getValue(PitchClass.E to ChordQuality.MAJOR)
        assertEquals(5, eMajor.degree)
        assertEquals(3, eMajor.signatureDegree)
        assertEquals(setOf(MinorAlteration.RAISED_5), eMajor.minorAlterations)
        assertTrue(eMajor.usesMinorRaised5)
        assertFalse(eMajor.usesMinorRaised4)

        val dMajor = byIdentity.getValue(PitchClass.D to ChordQuality.MAJOR)
        assertEquals(setOf(MinorAlteration.RAISED_4), dMajor.minorAlterations)
        assertTrue(Key.minor(PitchClass.A).isNaturalTriad(Chord(PitchClass.D, ChordQuality.MAJOR)))
        assertEquals(
            setOf(MinorAlteration.RAISED_4),
            Key.minor(PitchClass.A).minorAlterationsUsedBy(Chord(PitchClass.D, ChordQuality.MAJOR)),
        )
    }

    @Test
    fun naturalTriadsCanFindPossibleKeys() {
        val dMinor = Chord(PitchClass.D, ChordQuality.MINOR)

        assertTrue(
            NaturalTriads.possibleKeys(dMinor).any {
                it.key == Key.major(PitchClass.C) && it.triad.degree == 2
            }
        )
        assertTrue(
            NaturalTriads.possibleKeys(dMinor).any {
                it.key == Key.minor(PitchClass.A) && it.triad.degree == 4
            }
        )

        val eMajorInAMinorSignature = NaturalTriads.possibleKeySignatures(
            Chord(PitchClass.E, ChordQuality.MAJOR),
            interpretations = listOf(KeySignatureMode.MINOR),
            fifthsRange = 0..0,
        ).single()

        assertEquals(Key.minor(PitchClass.A), eMajorInAMinorSignature.key)
        assertEquals(5, eMajorInAMinorSignature.triad.degree)
        assertEquals(setOf(MinorAlteration.RAISED_5), eMajorInAMinorSignature.triad.minorAlterations)
    }
}
