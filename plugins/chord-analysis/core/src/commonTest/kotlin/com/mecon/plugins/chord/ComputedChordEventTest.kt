package com.mecon.plugins.chord

import com.mecon.api.primitive.TimeCode
import com.mecon.theory.ChordQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [ComputedChordEvent.symbol] — the display string shown in the annotation staff
 * and chord panel.
 *
 * Root spelling uses sharps ([preferSharps = true]) as declared in [ComputedChordEvent].
 * Expected format: "{root}{qualitySuffix}" or "{root}{qualitySuffix}/{bass}".
 */
class ComputedChordEventTest {

    // ---------------------------------------------------------------------------
    // Helper
    // ---------------------------------------------------------------------------

    private fun symbol(root: Int, quality: ChordQuality, bass: Int? = null): String {
        val storage = StorageChordEvent.create(TimeCode.ofMeasure(1), root, quality, bass)
        return ComputedChordEvent.fromRuntime(RuntimeChordEvent.fromStorage(storage)).symbol
    }

    // ---------------------------------------------------------------------------
    // Quality suffixes
    // ---------------------------------------------------------------------------

    @Test
    fun major_noSuffix() = assertEquals("C", symbol(0, ChordQuality.MAJOR))

    @Test
    fun minor_mSuffix() = assertEquals("Dm", symbol(2, ChordQuality.MINOR))

    @Test
    fun dominant7_sevenSuffix() = assertEquals("G7", symbol(7, ChordQuality.DOMINANT7))

    @Test
    fun major7_maj7Suffix() = assertEquals("Cmaj7", symbol(0, ChordQuality.MAJOR7))

    @Test
    fun minor7_m7Suffix() = assertEquals("Am7", symbol(9, ChordQuality.MINOR7))

    @Test
    fun diminished_degreeSuffix() = assertEquals("B°", symbol(11, ChordQuality.DIMINISHED))

    @Test
    fun augmented_plusSuffix() = assertEquals("C+", symbol(0, ChordQuality.AUGMENTED))

    @Test
    fun halfDiminished7_phiSuffix() = assertEquals("Bø7", symbol(11, ChordQuality.HALF_DIMINISHED7))

    @Test
    fun diminished7_degreeSeven() = assertEquals("B°7", symbol(11, ChordQuality.DIMINISHED7))

    @Test
    fun sus2_sus2Suffix() = assertEquals("Dsus2", symbol(2, ChordQuality.SUS2))

    @Test
    fun sus4_sus4Suffix() = assertEquals("Gsus4", symbol(7, ChordQuality.SUS4))

    @Test
    fun minorMajor7_mMaj7Suffix() = assertEquals("Cm(maj7)", symbol(0, ChordQuality.MINOR_MAJOR7))

    @Test
    fun augmented7_plusSevenSuffix() = assertEquals("C+7", symbol(0, ChordQuality.AUGMENTED7))

    @Test
    fun add9_add9Suffix() = assertEquals("Cadd9", symbol(0, ChordQuality.ADD9))

    // ---------------------------------------------------------------------------
    // Slash chords
    // ---------------------------------------------------------------------------

    @Test
    fun slashChord_appendsBassName() {
        // F/A: root=F(5), bass=A(9), quality=MAJOR → "F/A"
        assertEquals("F/A", symbol(5, ChordQuality.MAJOR, bass = 9))
    }

    @Test
    fun slashChord_firstInversion_C_over_E() {
        // C/E: root=C(0), bass=E(4) → "C/E"
        assertEquals("C/E", symbol(0, ChordQuality.MAJOR, bass = 4))
    }

    @Test
    fun slashChord_secondInversion_C_over_G() {
        // C/G: root=C(0), bass=G(7) → "C/G"
        assertEquals("C/G", symbol(0, ChordQuality.MAJOR, bass = 7))
    }

    @Test
    fun slashChord_minorWithBass() {
        // Dm/F: root=D(2), bass=F(5), quality=MINOR → "Dm/F"
        assertEquals("Dm/F", symbol(2, ChordQuality.MINOR, bass = 5))
    }

    @Test
    fun slashChord_sharpRootAndBass() {
        // C#/G#: root=C#(1), bass=G#(8) → "C#/G#"
        assertEquals("C#/G#", symbol(1, ChordQuality.MAJOR, bass = 8))
    }

    @Test
    fun noSlash_whenBassIsNull() {
        assertEquals("C", symbol(0, ChordQuality.MAJOR, bass = null))
    }

    // ---------------------------------------------------------------------------
    // Root names: all 12 pitch classes use sharp spelling
    // ---------------------------------------------------------------------------

    @Test
    fun allTwelveRoots_majorSpellingWithSharps() {
        val expected = mapOf(
            0 to "C", 1 to "C#", 2 to "D",  3 to "D#", 4 to "E",  5 to "F",
            6 to "F#", 7 to "G", 8 to "G#", 9 to "A", 10 to "A#", 11 to "B"
        )
        for ((root, name) in expected) {
            assertEquals(name, symbol(root, ChordQuality.MAJOR), "root=$root")
        }
    }

    // ---------------------------------------------------------------------------
    // Chord object: pitchClass membership sanity
    // ---------------------------------------------------------------------------

    @Test
    fun cMajorChord_containsCorrectPitchClasses() {
        val storage = StorageChordEvent.create(TimeCode.ofMeasure(1), 0, ChordQuality.MAJOR)
        val computed = ComputedChordEvent.fromRuntime(RuntimeChordEvent.fromStorage(storage))
        val chord = computed.chord
        assertTrue(chord.contains(com.mecon.api.primitive.Pitch.C4), "C is in C major")
        assertTrue(chord.contains(com.mecon.api.primitive.Pitch.E4), "E is in C major")
        assertTrue(chord.contains(com.mecon.api.primitive.Pitch.G4), "G is in C major")
        assertFalse(chord.contains(com.mecon.api.primitive.Pitch.D4), "D is not in C major")
        assertFalse(chord.contains(com.mecon.api.primitive.Pitch.A4), "A is not in C major")
    }

    @Test
    fun dMinorChord_containsCorrectPitchClasses() {
        val storage = StorageChordEvent.create(TimeCode.ofMeasure(1), 2, ChordQuality.MINOR)
        val chord = ComputedChordEvent.fromRuntime(RuntimeChordEvent.fromStorage(storage)).chord
        // D minor: D(2), F(5), A(9)
        assertTrue(chord.contains(com.mecon.api.primitive.Pitch.D4))
        assertTrue(chord.contains(com.mecon.api.primitive.Pitch.F4))
        assertTrue(chord.contains(com.mecon.api.primitive.Pitch.A4))
        assertFalse(chord.contains(com.mecon.api.primitive.Pitch.C4))
        assertFalse(chord.contains(com.mecon.api.primitive.Pitch.E4))
    }
}
