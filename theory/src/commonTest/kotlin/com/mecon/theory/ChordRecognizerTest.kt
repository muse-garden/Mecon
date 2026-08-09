package com.mecon.theory

import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.PitchClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChordRecognizerTest {
    @Test
    fun exactMajorTriadWinsOverPartialSeventhCandidates() {
        val result = ChordRecognizer.recognizePitchClasses(listOf(0, 4, 7))

        assertEquals(1, result.size)
        assertEquals(ChordQuality.MAJOR, result.single().chord.quality)
        assertEquals(0, result.single().chord.root.value)
        assertTrue(result.single().missingTones.isEmpty())
    }

    @Test
    fun missingFifthStillDeterminesMajorTriad() {
        val result = ChordRecognizer.recognizePitchClasses(listOf(0, 4))

        assertEquals(1, result.size)
        val candidate = result.single()
        assertEquals(ChordQuality.MAJOR, candidate.chord.quality)
        assertEquals(0, candidate.chord.root.value)
        assertEquals(listOf(MissingChordTone(ChordTone.FIFTH, PitchClass(7))), candidate.missingTones)
    }

    @Test
    fun missingFifthStillDeterminesMinorTriad() {
        val result = ChordRecognizer.recognizePitchClasses(listOf(0, 3))

        assertEquals(1, result.size)
        val candidate = result.single()
        assertEquals(ChordQuality.MINOR, candidate.chord.quality)
        assertEquals(0, candidate.chord.root.value)
        assertEquals(listOf(MissingChordTone(ChordTone.FIFTH, PitchClass(7))), candidate.missingTones)
    }

    @Test
    fun missingThirdKeepsMajorMinorAmbiguity() {
        val result = ChordRecognizer.recognizePitchClasses(listOf(0, 7))
            .sortedBy { it.chord.quality.name }

        assertEquals(listOf(ChordQuality.MAJOR, ChordQuality.MINOR), result.map { it.chord.quality }.sortedBy { it.name })
        assertTrue(result.all { it.chord.root.value == 0 })
        assertEquals(setOf(ChordTone.THIRD), result.flatMap { it.missingTones.map { missing -> missing.tone } }.toSet())
        assertEquals(setOf(3, 4), result.flatMap { it.missingTones.map { missing -> missing.pitchClass.value } }.toSet())
    }

    @Test
    fun missingFifthCanDetermineDominantSeventh() {
        val result = ChordRecognizer.recognizePitchClasses(listOf(0, 4, 10))

        assertEquals(1, result.size)
        val candidate = result.single()
        assertEquals(ChordQuality.DOMINANT7, candidate.chord.quality)
        assertEquals(0, candidate.chord.root.value)
        assertEquals(listOf(MissingChordTone(ChordTone.FIFTH, PitchClass(7))), candidate.missingTones)
    }

    @Test
    fun exactInversionKeepsBassPitchClass() {
        val result = ChordRecognizer.recognizePitchClasses(listOf(0, 4, 9), bass = 0)

        assertEquals(1, result.size)
        val candidate = result.single()
        assertEquals(ChordQuality.MINOR, candidate.chord.quality)
        assertEquals(9, candidate.chord.root.value)
        assertEquals(0, candidate.chord.bass?.value)
    }

    @Test
    fun diminishedSeventhUsesBassAsSymmetricRoot() {
        val result = ChordRecognizer.recognize(
            listOf(
                Pitch.fromName("F#3"),
                Pitch.fromName("A3"),
                Pitch.fromName("C4"),
                Pitch.fromName("Eb4"),
            )
        )

        assertEquals(1, result.size)
        val candidate = result.single()
        assertEquals(ChordQuality.DIMINISHED7, candidate.chord.quality)
        assertEquals(6, candidate.chord.root.value)
        assertTrue(candidate.enharmonicSubstitutions.isEmpty())
    }

    @Test
    fun augmentedTriadUsesBassAsSymmetricRootWithoutSubstitutions() {
        val result = ChordRecognizer.recognize(
            listOf(
                Pitch.fromName("E3"),
                Pitch.fromName("G#3"),
                Pitch.fromName("C4"),
            )
        )

        assertEquals(1, result.size)
        val candidate = result.single()
        assertEquals(ChordQuality.AUGMENTED, candidate.chord.quality)
        assertEquals(4, candidate.chord.root.value)
        assertTrue(candidate.enharmonicSubstitutions.isEmpty())
    }

    @Test
    fun recognizesEnharmonicSubstitutionFromSemitoneMatch() {
        val result = ChordRecognizer.recognize(
            listOf(
                Pitch.fromName("C#4"),
                Pitch.fromName("F4"),
                Pitch.fromName("Ab4"),
            )
        )

        assertEquals(1, result.size)
        val candidate = result.single()
        assertEquals(ChordQuality.MAJOR, candidate.chord.quality)
        assertEquals(1, candidate.chord.root.value)
        assertEquals(
            listOf(
                EnharmonicSubstitution(
                    tone = ChordTone.ROOT,
                    written = Pitch.fromName("C#4"),
                    expected = Pitch.fromName("Db4"),
                )
            ),
            candidate.enharmonicSubstitutions,
        )
    }
}
