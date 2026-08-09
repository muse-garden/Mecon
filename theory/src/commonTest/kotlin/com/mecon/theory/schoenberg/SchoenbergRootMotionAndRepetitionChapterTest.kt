package com.mecon.theory.schoenberg

import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.Key
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.SearchConfig
import com.mecon.theory.constraint.ConstraintProgramSolver
import com.mecon.theory.constraint.ConstraintPredicate
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.constraint.atomicPredicates
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookTriadPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchoenbergRootMotionAndRepetitionChapterTest {
    @Test
    fun classifiesEveryDirectedDiatonicRootMotion() {
        assertEquals(SchoenbergRootMotionDirection.RISING, classify(5, 1))
        assertEquals(SchoenbergRootMotionDirection.RISING, classify(5, 3))
        assertEquals(SchoenbergRootMotionDirection.DESCENDING, classify(1, 5))
        assertEquals(SchoenbergRootMotionDirection.DESCENDING, classify(1, 3))
        assertEquals(SchoenbergRootMotionDirection.SUPERSTRONG, classify(1, 2))
        assertEquals(SchoenbergRootMotionDirection.SUPERSTRONG, classify(2, 1))
        assertEquals(SchoenbergRootMotionDirection.REPEATED, classify(1, 1))
    }

    @Test
    fun descendingProgressionMustBeTemporaryAndCompensated() {
        // I-V is descending, but I-V-vi has a two-step I-vi rising result.
        assertTrue(followsPolicy(1, 5, 6))
        // I-V-iii still has a descending I-iii two-step result.
        assertFalse(followsPolicy(1, 5, 3))
        // A terminal descending progression has no compensating motion.
        assertFalse(followsPolicy(1, 5))
    }

    @Test
    fun generalExerciseSupportsMajorAndMinorWithCompleteNaturalVocabulary() {
        val descriptor = SchoenbergCommonToneExercises.descriptorForExercise(
            SchoenbergCommonToneExercises.ROOT_MOTION_AND_REPETITION_EXERCISE_ID
        )
        assertEquals(SchoenbergCommonToneExercises.GENERAL_BRANCH_RULE_ID, descriptor.parentId)

        listOf(KeySignatureMode.MAJOR, KeySignatureMode.MINOR).forEach { mode ->
            val key = Key.fromKeySignatureFifths(0, mode)
            val vocabulary = SchoenbergIntegratedTechTree.vocabularyForStage(
                SchoenbergCommonToneExercises.ROOT_MOTION_AND_REPETITION_EXERCISE_ID,
                key,
            )
            assertEquals((1..7).toSet(), vocabulary.map { it.degree }.toSet())
            assertTrue(vocabulary.any { it.position == TextbookTriadPosition.SECOND_INVERSION })
            assertTrue(vocabulary.any { it.arity == ChordArity.SEVENTH })

            val program = SchoenbergCommonToneExercises.programForExercise(
                exerciseId = SchoenbergCommonToneExercises.ROOT_MOTION_AND_REPETITION_EXERCISE_ID,
                key = key,
                continuationChordCount = 6,
            )
            val predicates = program.constraints.flatMap { it.expr.atomicPredicates().toList() }
            assertTrue(predicates.any { it is ConstraintPredicate.RootDiatonicMotion })
            assertTrue(predicates.any { it is ConstraintPredicate.UniqueVoiceExtreme })
            assertTrue(predicates.any { it is ConstraintPredicate.NoRepeatedVoicePattern })
            assertTrue(predicates.any { it is ConstraintPredicate.MinimumSimilarChordDistance })
            assertTrue(predicates.any { it is ConstraintPredicate.DistinctSimilarChordProgressions })
            val preference = predicates.filterIsInstance<ConstraintPredicate.RootProgressionPreference>().single()
            assertEquals(
                SchoenbergRootMotionAndRepetitionChapter.ROOT_PROGRESSION_SCORING_POLICY,
                preference.scoringPolicy,
            )
        }
    }

    @Test
    fun similarChordAndProgressionChecksIgnoreInversionAndArity() {
        val closeReturn = listOf(
            symbolic(1),
            symbolic(2),
            symbolic(1, position = TextbookTriadPosition.FIRST_INVERSION),
        )
        assertFalse(SchoenbergRootMotionAndRepetitionChapter.followsHarmonicRepetitionPolicy(closeReturn))

        val repeatedProgression = listOf(
            symbolic(1),
            symbolic(2),
            symbolic(5),
            symbolic(4),
            symbolic(1, position = TextbookTriadPosition.FIRST_INVERSION),
            symbolic(2, arity = ChordArity.SEVENTH),
        )
        assertFalse(SchoenbergRootMotionAndRepetitionChapter.followsHarmonicRepetitionPolicy(repeatedProgression))

        val separatedWithoutRepeatedPair = listOf(symbolic(1), symbolic(2), symbolic(5), symbolic(1))
        assertTrue(SchoenbergRootMotionAndRepetitionChapter.followsHarmonicRepetitionPolicy(separatedWithoutRepeatedPair))
    }

    @Test
    fun enumerationScoreDemotesCloseReturnsAndConsecutiveSuperstrongMotion() {
        val awkward = listOf(symbolic(1), symbolic(2), symbolic(3), symbolic(1))
        val calmer = listOf(symbolic(1), symbolic(4), symbolic(2), symbolic(1))

        assertTrue(SchoenbergRootMotionAndRepetitionChapter.followsHarmonicRepetitionPolicy(awkward))
        assertTrue(SchoenbergRootMotionAndRepetitionChapter.followsDirectionPolicy(awkward))
        assertTrue(
            SchoenbergRootMotionAndRepetitionChapter.enumerationScore(awkward).total >
                SchoenbergRootMotionAndRepetitionChapter.enumerationScore(calmer).total
        )
    }

    @Test
    fun enumeratedProgressionsAlwaysFollowDirectionPolicy() {
        listOf(KeySignatureMode.MAJOR, KeySignatureMode.MINOR).forEach { mode ->
            val key = Key.fromKeySignatureFifths(0, mode)
            val progressions = SchoenbergRootMotionAndRepetitionChapter.enumerate(
                key = key,
                continuationChordCount = 6,
                budget = SchoenbergIntegratedTechTree.EnumerationBudget(
                    maxResults = 8,
                    maxVisitedNodes = 20_000,
                ),
            )
            assertTrue(progressions.isNotEmpty(), "$mode should enumerate root-motion exercises")
            assertTrue(progressions.all { SchoenbergRootMotionAndRepetitionChapter.followsDirectionPolicy(it.slots) })
            assertTrue(
                progressions.all {
                    SchoenbergRootMotionAndRepetitionChapter.followsHarmonicRepetitionPolicy(it.slots)
                }
            )
            val scores = progressions.map {
                SchoenbergRootMotionAndRepetitionChapter.enumerationScore(it.slots).total
            }
            assertTrue(scores.zipWithNext().all { (before, after) -> before <= after })
        }
    }

    @Test
    fun enumeratedProgressionCanBeRealizedWithRepetitionRulesEnabled() {
        val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
        val progression = SchoenbergRootMotionAndRepetitionChapter.enumerate(
            key = key,
            continuationChordCount = 6,
            budget = SchoenbergIntegratedTechTree.EnumerationBudget(
                maxResults = 8,
                maxVisitedNodes = 20_000,
            ),
        ).first()
        val solutions = ConstraintProgramSolver.solve(
            SchoenbergRootMotionAndRepetitionChapter.program(
                key = key,
                continuationChordCount = 6,
                progression = progression,
                searchConfig = SearchConfig(maxResults = 1, beamWidth = 256),
            )
        )

        assertTrue(solutions.isNotEmpty())
        assertTrue(
            solutions.first().breakdown.findings.none {
                it.ruleId == SchoenbergRootMotionAndRepetitionChapter.UNIQUE_SOPRANO_CLIMAX_RULE_ID
            }
        )
        val expectedHarmonicScore =
            SchoenbergRootMotionAndRepetitionChapter.enumerationScore(progression.slots).total
        val realizedHarmonicScore = solutions.first().breakdown.findings
            .single { it.ruleId == SchoenbergRootMotionAndRepetitionChapter.ROOT_PROGRESSION_SCORE_RULE_ID }
            .scoreDelta
        assertEquals(expectedHarmonicScore, realizedHarmonicScore, absoluteTolerance = 0.000_001)
    }

    @Test
    fun minorChromaticLeadingRootUsesItsSymbolicScaleDegree() {
        val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MINOR)
        val progression = SchoenbergSymbolicProgression(
            slots = listOf(
                symbolic(1, quality = ChordQuality.MINOR),
                symbolic(4, quality = ChordQuality.MAJOR),
                symbolic(
                    7,
                    quality = ChordQuality.DIMINISHED,
                    position = TextbookTriadPosition.FIRST_INVERSION,
                ),
                symbolic(1, quality = ChordQuality.MINOR),
            ),
            kind = SchoenbergConnectionKind.INTEGRATED,
        )
        val program = SchoenbergRootMotionAndRepetitionChapter.program(
            key = key,
            continuationChordCount = 3,
            progression = progression,
            searchConfig = SearchConfig(maxResults = 1, beamWidth = 128),
        )

        assertTrue(
            program.slotDomains.any { domain ->
                domain.targets.single().pitchClassFor(ChordTone.ROOT) !in key.scale.pitchClasses
            }
        )
        assertTrue(SchoenbergRootMotionAndRepetitionChapter.followsDirectionPolicy(progression.slots))
        assertTrue(ConstraintProgramSolver.targetOnlyHardViolations(program).isEmpty())
        assertTrue(ConstraintProgramSolver.solve(program).isNotEmpty())

        val invalidProgression = SchoenbergSymbolicProgression(
            slots = listOf(
                symbolic(1, quality = ChordQuality.MINOR),
                symbolic(5, quality = ChordQuality.MINOR),
                symbolic(3, quality = ChordQuality.MAJOR),
                symbolic(1, quality = ChordQuality.MINOR),
            ),
            kind = SchoenbergConnectionKind.INTEGRATED,
        )
        val invalidProgram = SchoenbergRootMotionAndRepetitionChapter.program(
            key = key,
            continuationChordCount = 3,
            progression = invalidProgression,
            searchConfig = SearchConfig(maxResults = 1, beamWidth = 128),
        )
        val preflightTrace = ConstraintProgramSolver.trace(invalidProgram)
        assertTrue(ConstraintProgramSolver.targetOnlyHardViolations(invalidProgram).isNotEmpty())
        assertEquals(0, preflightTrace.trace.visitedNodes)
        assertTrue(
            ConstraintProgramSolver.solveFirstFeasible(
                programs = listOf(invalidProgram, program),
                maxProgramAttempts = 2,
            ).isNotEmpty()
        )
    }

    private fun classify(from: Int, to: Int): SchoenbergRootMotionDirection =
        SchoenbergRootMotionAndRepetitionChapter.classify(from, to)

    private fun followsPolicy(vararg degrees: Int): Boolean =
        SchoenbergRootMotionAndRepetitionChapter.followsDirectionPolicy(
            degrees.map { degree ->
                SchoenbergSymbolicChord(
                    degree = degree,
                    quality = ChordQuality.MAJOR,
                )
            }
        )

    private fun symbolic(
        degree: Int,
        quality: ChordQuality = ChordQuality.MAJOR,
        position: TextbookTriadPosition = TextbookTriadPosition.ROOT_POSITION,
        arity: ChordArity = ChordArity.TRIAD,
    ): SchoenbergSymbolicChord =
        SchoenbergSymbolicChord(
            degree = degree,
            quality = quality,
            position = position,
            arity = arity,
            seventhPosition = if (arity == ChordArity.SEVENTH) TextbookSeventhPosition.ROOT_POSITION else null,
        )
}
