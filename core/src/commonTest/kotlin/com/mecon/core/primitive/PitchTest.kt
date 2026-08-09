package com.mecon.api.primitive

import kotlin.test.*

class PitchTest {

    @Test
    fun testMiddleC() {
        val c4 = Pitch.C4
        assertEquals(0, c4.diatonicSteps)
        assertEquals(0, c4.chromaticOffset)
        assertEquals(60, c4.midiNumber)
        assertEquals(4, c4.octave)
        assertEquals(NoteName.C, c4.noteName)
        assertEquals(Accidental.NATURAL, c4.accidental)
    }

    @Test
    fun testA4() {
        val a4 = Pitch.A4
        assertEquals(5, a4.diatonicSteps)
        assertEquals(0, a4.chromaticOffset)
        assertEquals(69, a4.midiNumber)
        assertEquals(4, a4.octave)
        assertEquals(NoteName.A, a4.noteName)
    }

    @Test
    fun testSharp() {
        val cSharp4 = Pitch(0, 1)
        assertEquals(61, cSharp4.midiNumber)
        assertEquals(Accidental.SHARP, cSharp4.accidental)
        assertEquals(NoteName.C, cSharp4.noteName)
        assertEquals("C#4", cSharp4.format())
    }

    @Test
    fun testFlat() {
        val dFlat4 = Pitch(1, -1)
        assertEquals(61, dFlat4.midiNumber)
        assertEquals(Accidental.FLAT, dFlat4.accidental)
        assertEquals(NoteName.D, dFlat4.noteName)
        assertEquals("Db4", dFlat4.format())
    }

    @Test
    fun testEnharmonicEquivalence() {
        val cSharp4 = Pitch(0, 1)
        val dFlat4 = Pitch(1, -1)
        assertTrue(cSharp4.isEnharmonic(dFlat4))
        assertEquals(cSharp4.midiNumber, dFlat4.midiNumber)
        assertNotEquals(cSharp4, dFlat4)  // Different pitch objects
    }

    @Test
    fun testFromMidi() {
        assertEquals(60, Pitch.fromMidi(60).midiNumber)
        assertEquals(69, Pitch.fromMidi(69).midiNumber)

        // Check sharp preference
        val cSharp = Pitch.fromMidi(61, preferSharps = true)
        assertEquals(NoteName.C, cSharp.noteName)
        assertEquals(Accidental.SHARP, cSharp.accidental)

        // Check flat preference
        val dFlat = Pitch.fromMidi(61, preferSharps = false)
        assertEquals(NoteName.D, dFlat.noteName)
        assertEquals(Accidental.FLAT, dFlat.accidental)
    }

    @Test
    fun testFromName() {
        assertEquals(60, Pitch.fromName("C4").midiNumber)
        assertEquals(61, Pitch.fromName("C#4").midiNumber)
        assertEquals(61, Pitch.fromName("Db4").midiNumber)
        assertEquals(72, Pitch.fromName("C5").midiNumber)
        assertEquals(48, Pitch.fromName("C3").midiNumber)
        assertEquals(62, Pitch.fromName("Cx4").midiNumber)  // Double sharp
        assertEquals(58, Pitch.fromName("Cbb4").midiNumber) // Double flat = C4 - 2
    }

    @Test
    fun testFromNameInvalid() {
        assertFailsWith<IllegalArgumentException> {
            Pitch.fromName("invalid")
        }
        assertFailsWith<IllegalArgumentException> {
            Pitch.fromName("C")  // Missing octave
        }
    }

    @Test
    fun testTranspose() {
        val c4 = Pitch.C4
        assertEquals(61, c4.transpose(1).midiNumber)
        assertEquals(62, c4.transpose(2).midiNumber)
        assertEquals(72, c4.transpose(12).midiNumber)
        assertEquals(59, c4.transpose(-1).midiNumber)
    }

    @Test
    fun testIntervalTo() {
        val c4 = Pitch.C4
        val e4 = Pitch.E4
        val g4 = Pitch.G4

        assertEquals(Interval.MAJOR_THIRD, c4.intervalTo(e4))
        assertEquals(Interval.PERFECT_FIFTH, c4.intervalTo(g4))
    }

    @Test
    fun testComparison() {
        assertTrue(Pitch.C4 < Pitch.D4)
        assertTrue(Pitch.A4 > Pitch.G4)
        assertTrue(Pitch.C4 < Pitch.C5)
    }

    @Test
    fun testOctaveCalculation() {
        assertEquals(4, Pitch.C4.octave)
        assertEquals(5, Pitch.C5.octave)
        assertEquals(3, Pitch(diatonicSteps = -1).octave)  // B3
        assertEquals(3, Pitch(diatonicSteps = -7).octave)  // C3
    }

    @Test
    fun testPitchClass() {
        assertEquals(PitchClass.C, Pitch.C4.pitchClass)
        assertEquals(PitchClass.C, Pitch.C5.pitchClass)
        assertEquals(PitchClass(1), Pitch(0, 1).pitchClass)  // C#
    }
}

class PitchClassTest {

    @Test
    fun testTranspose() {
        assertEquals(PitchClass(2), PitchClass.C.transpose(2))
        assertEquals(PitchClass.C, PitchClass(11).transpose(1))  // B -> C
    }

    @Test
    fun testIntervalTo() {
        assertEquals(4, PitchClass.C.intervalTo(PitchClass.E))
        assertEquals(7, PitchClass.C.intervalTo(PitchClass.G))
    }

    @Test
    fun testToNoteName() {
        assertEquals("C", PitchClass.C.toNoteName(preferSharps = true))
        assertEquals("C#", PitchClass(1).toNoteName(preferSharps = true))
        assertEquals("Db", PitchClass(1).toNoteName(preferSharps = false))
    }
}
