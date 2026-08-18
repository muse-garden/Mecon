package com.mecon.theory.voiceleading

import com.mecon.theory.ChordQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VoiceLeadingTheoryTest {
    @Test
    fun standardFamiliesContainTheRequestedTriadAndSeventhTypes() {
        assertEquals(
            setOf(
                ChordQuality.MAJOR,
                ChordQuality.MINOR,
                ChordQuality.AUGMENTED,
                ChordQuality.DIMINISHED,
            ),
            StandardVoiceLeadingChordFamilies.TRIADS.definitions
                .mapTo(linkedSetOf()) { it.compatibilityQuality },
        )
        val seventhQualities = StandardVoiceLeadingChordFamilies.SEVENTHS.definitions
            .mapTo(linkedSetOf()) { it.compatibilityQuality }
        assertTrue(
            seventhQualities.containsAll(setOf(
                ChordQuality.MAJOR7,
                ChordQuality.MINOR7,
                ChordQuality.DOMINANT7,
                ChordQuality.DIMINISHED7,
                ChordQuality.HALF_DIMINISHED7,
                ChordQuality.MINOR_MAJOR7,
                ChordQuality.AUGMENTED7,
            )),
        )
    }

    @Test
    fun triadsReachOneAndTwoStepTargetsWithoutMovingTheSameToneTwice() {
        val candidates = VoiceLeadingTransformations.enumerate(
            sourcePitchClasses = listOf(0, 4, 7),
            family = StandardVoiceLeadingChordFamilies.TRIADS,
        )

        val cMinor = candidates.single { candidate ->
            candidate.targetPitchClasses == listOf(0, 3, 7)
        }
        assertEquals(1, cMinor.transformationCount)
        assertEquals(-1, cMinor.paths.single().moves.single().semitones)

        val cDiminished = candidates.single { candidate ->
            candidate.targetPitchClasses == listOf(0, 3, 6)
        }
        assertEquals(2, cDiminished.transformationCount)
        assertTrue(cDiminished.paths.all { path ->
            path.moves.map { it.sourceToneIndex }.distinct().size == 2
        })
        assertTrue(candidates.flatMap { it.paths }.all { path ->
            path.moves.map { it.sourceToneIndex }.distinct().size == path.moves.size
        })
    }

    @Test
    fun orderedPathFlagsTheParallelPerfectRiskExample() {
        val candidate = VoiceLeadingTransformations.enumerate(
            sourcePitchClasses = listOf(0, 4, 7),
            family = StandardVoiceLeadingChordFamilies.TRIADS,
        ).single { it.targetPitchClasses == listOf(1, 4, 8) }

        val risky = assertNotNull(candidate.paths.firstOrNull { path ->
            path.moves.map { it.sourceToneIndex }.toSet() == setOf(0, 2) &&
                path.moves.all { it.semitones == 1 }
        })
        assertTrue(VoiceLeadingParallelRisk.PARALLEL_FIFTH in risky.parallelRisks)
        assertTrue(
            VoiceLeadingParallelRisk.PARALLEL_OCTAVE_IF_MOVED_TONE_IS_DOUBLED in
                risky.parallelRisks,
        )
    }

    @Test
    fun seventhTraversalUsesAtMostThreeDistinctOriginalTones() {
        val candidates = VoiceLeadingTransformations.enumerate(
            sourcePitchClasses = listOf(0, 4, 7, 10),
            family = StandardVoiceLeadingChordFamilies.SEVENTHS,
        )

        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.any { it.transformationCount == 3 })
        assertTrue(candidates.all { it.transformationCount in 1..3 })
        assertTrue(candidates.flatMap { it.paths }.all { path ->
            path.moves.map { it.sourceToneIndex }.distinct().size == path.moves.size
        })
        assertTrue(candidates.flatMap { it.paths }.any { it.threeTonesSameDirection })
    }

    @Test
    fun symmetricChordsExposeEquivalentRootsForConnectionSpecificChoice() {
        val augmented = VoiceLeadingTransformations.recognize(
            pitchClasses = listOf(0, 4, 8),
            family = StandardVoiceLeadingChordFamilies.TRIADS,
        )
        val diminishedSeventh = VoiceLeadingTransformations.recognize(
            pitchClasses = listOf(0, 3, 6, 9),
            family = StandardVoiceLeadingChordFamilies.SEVENTHS,
        )

        assertEquals(setOf(0, 4, 8), augmented.mapTo(linkedSetOf()) { it.rootPitchClass })
        assertEquals(
            setOf(0, 3, 6, 9),
            diminishedSeventh.mapTo(linkedSetOf()) { it.rootPitchClass },
        )

        val augmentedToEMajor = VoiceLeadingTransformations.enumerate(
            sourcePitchClasses = listOf(0, 4, 8),
            family = StandardVoiceLeadingChordFamilies.TRIADS,
        ).single { it.targetPitchClasses == listOf(4, 8, 11) }
        val eMajorToAugmented = VoiceLeadingTransformations.enumerate(
            sourcePitchClasses = listOf(4, 8, 11),
            family = StandardVoiceLeadingChordFamilies.TRIADS,
        ).single { it.targetPitchClasses == listOf(0, 4, 8) }
        assertEquals(8, augmentedToEMajor.rootConnection.sourceRootPitchClass)
        assertEquals(0, eMajorToAugmented.rootConnection.targetRootPitchClass)
        assertEquals(
            SchoenbergChromaticRootMotion.RISING,
            augmentedToEMajor.rootConnection.motion,
        )
        assertEquals(SchoenbergChromaticRootMotion.RISING, eMajorToAugmented.rootConnection.motion)
    }

    @Test
    fun chromaticRootMotionUsesSchoenbergDirectionSemantics() {
        assertEquals(
            SchoenbergChromaticRootMotion.RISING,
            VoiceLeadingTransformations.classifyRootMotion(0, 5).motion,
        )
        assertEquals(
            SchoenbergChromaticRootMotion.DESCENDING,
            VoiceLeadingTransformations.classifyRootMotion(0, 4).motion,
        )
        assertEquals(
            SchoenbergChromaticRootMotion.SUPERSTRONG,
            VoiceLeadingTransformations.classifyRootMotion(0, 2).motion,
        )
        assertEquals(
            SchoenbergChromaticRootMotion.UNCLASSIFIED,
            VoiceLeadingTransformations.classifyRootMotion(0, 6).motion,
        )
    }
}
