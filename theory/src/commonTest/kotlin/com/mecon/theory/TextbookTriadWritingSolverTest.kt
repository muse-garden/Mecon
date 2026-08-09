package com.mecon.theory

import com.mecon.api.primitive.PitchClass
import com.mecon.theory.textbook.FirstInversionTriadRules
import com.mecon.theory.textbook.RootPositionTriadRules
import com.mecon.theory.textbook.SecondInversionTriadRules
import com.mecon.theory.textbook.TextbookTriadPosition
import com.mecon.theory.textbook.TextbookTriadWritingProblem
import com.mecon.theory.textbook.TextbookTriadWritingSlot
import com.mecon.theory.textbook.TextbookTriadWritingSolver
import com.mecon.theory.textbook.textbookTriadInKey
import com.mecon.theory.textbook.toConstraintProgram
import com.mecon.theory.constraint.ChordTone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextbookTriadWritingSolverTest {
    @Test
    fun compilesTextbookPresetIntoConstraintProgram() {
        val key = Key.major(PitchClass.C)
        val tonic = textbookTriadInKey(key, 1)
        val dominant = textbookTriadInKey(key, 5)
        val problem = TextbookTriadWritingProblem(
            key = key,
            slots = listOf(
                TextbookTriadWritingSlot.rootPosition(tonic),
                TextbookTriadWritingSlot.firstInversion(dominant),
                TextbookTriadWritingSlot.secondInversion(tonic),
            ),
        )

        val program = problem.toConstraintProgram()

        assertEquals(3, program.slotDomains.size)
        assertTrue(program.ruleModules?.isNotEmpty() == true)
        assertTrue(program.toneCompleteness.any { it.window.contains(0) && it.selector.inversions == setOf(0) })
        assertTrue(program.toneCompleteness.any { it.window.contains(1) && it.selector.inversions == setOf(1) })
        assertTrue(program.toneCompleteness.any { it.window.contains(2) && it.selector.inversions == setOf(2) })
        assertTrue(program.doublings.any { it.slot == 0 && it.tone == ChordTone.ROOT && it.required })
        assertTrue(program.doublings.any { it.slot == 2 && it.tone == ChordTone.BASS && it.required })
        assertEquals(3, program.avoidScaleDegreeDoublings.size)
        assertTrue(program.doublings.any { it.ruleId == RootPositionTriadRules.EXPECT_ROOT_DOUBLING })
        assertTrue(program.doublings.any { it.ruleId == SecondInversionTriadRules.EXPECT_BASS_DOUBLING })
        assertTrue(program.toneCompleteness.any {
            it.ruleId == RootPositionTriadRules.MISSING_CHORD_TONE && !it.required
        })
    }

    @Test
    fun solvesFirstInversionTriadSequenceWithThirdInBass() {
        val key = Key.major(PitchClass.C)
        val tonic = RootPositionTriadRules.triadInKey(key, 1)
        val dominant = RootPositionTriadRules.triadInKey(key, 5)

        val solutions = TextbookTriadWritingSolver.solve(
            TextbookTriadWritingProblem(
                key = key,
                slots = listOf(tonic, dominant, tonic).map(TextbookTriadWritingSlot::firstInversion),
                searchConfig = SearchConfig(maxResults = 4, beamWidth = 96),
            )
        )

        assertTrue(solutions.isNotEmpty())
        solutions.forEach { solution ->
            assertFalse(solution.breakdown.hasHardViolation)
            assertTrue(solution.voicings.all { voicing ->
                voicing.position == TextbookTriadPosition.FIRST_INVERSION &&
                    voicing.bass.pitchClass == FirstInversionTriadRules.firstInversionBassPitchClass(voicing.triad)
            })
        }
    }

    @Test
    fun canGenerateDemonstrationForMajorRootDominantToMinorSixth() {
        val key = Key.major(PitchClass.C)
        val dominant = textbookTriadInKey(key, 5, ChordQuality.MAJOR)
        val minorSixth = textbookTriadInKey(key, 6, ChordQuality.MINOR)
        val profile = FirstInversionTriadRules.INTRODUCTORY_PROFILE.copy(
            requirements = listOf(
                RuleRequirement(
                    FirstInversionTriadRules.MAJOR_ROOT_DOMINANT_TO_MINOR_SIXTH,
                    RequirementMode.REQUIRE_VIOLATION,
                )
            )
        )

        val solutions = TextbookTriadWritingSolver.solve(
            TextbookTriadWritingProblem(
                key = key,
                slots = listOf(
                    TextbookTriadWritingSlot.rootPosition(dominant),
                    TextbookTriadWritingSlot.firstInversion(minorSixth),
                ),
                ruleProfile = profile,
                searchConfig = SearchConfig(maxResults = 2, beamWidth = 128),
            )
        )

        assertTrue(solutions.isNotEmpty())
        assertTrue(
            solutions.any { solution ->
                solution.breakdown.findings.any {
                    it.ruleId == FirstInversionTriadRules.MAJOR_ROOT_DOMINANT_TO_MINOR_SIXTH &&
                        it.kind == RuleFindingKind.VIOLATION
                }
            }
        )
        val demonstratedAfterChord = solutions.first().voicings[1]
        assertEquals(ChordQuality.MINOR, demonstratedAfterChord.triad.quality)
        assertTrue(PitchClass.A in listOf(
            demonstratedAfterChord.soprano.pitchClass,
            demonstratedAfterChord.alto.pitchClass,
            demonstratedAfterChord.tenor.pitchClass,
            demonstratedAfterChord.bass.pitchClass,
        ))
        assertTrue(PitchClass.C in listOf(
            demonstratedAfterChord.soprano.pitchClass,
            demonstratedAfterChord.alto.pitchClass,
            demonstratedAfterChord.tenor.pitchClass,
            demonstratedAfterChord.bass.pitchClass,
        ))
        assertTrue(PitchClass.E in listOf(
            demonstratedAfterChord.soprano.pitchClass,
            demonstratedAfterChord.alto.pitchClass,
            demonstratedAfterChord.tenor.pitchClass,
            demonstratedAfterChord.bass.pitchClass,
        ))
        assertEquals(PitchClass.C, demonstratedAfterChord.bass.pitchClass)
    }

    @Test
    fun solvesCadentialSecondInversionAsThreeChordPattern() {
        val key = Key.major(PitchClass.C)
        val tonic = RootPositionTriadRules.triadInKey(key, 1)
        val dominant = RootPositionTriadRules.triadInKey(key, 5)

        val solutions = TextbookTriadWritingSolver.solve(
            TextbookTriadWritingProblem(
                key = key,
                slots = listOf(
                    TextbookTriadWritingSlot.secondInversion(tonic),
                    TextbookTriadWritingSlot.rootPosition(dominant),
                    TextbookTriadWritingSlot.rootPosition(tonic),
                ),
                ruleProfile = SecondInversionTriadRules.INTRODUCTORY_PROFILE.copy(
                    requirements = listOf(
                        RuleRequirement(
                            SecondInversionTriadRules.CADENTIAL_SIX_FOUR,
                            RequirementMode.REQUIRE_INDICATION,
                        )
                    )
                ),
                searchConfig = SearchConfig(maxResults = 3, beamWidth = 128),
            )
        )

        assertTrue(solutions.isNotEmpty())
        solutions.forEach { solution ->
            assertFalse(solution.breakdown.hasHardViolation)
            assertEquals(TextbookTriadPosition.SECOND_INVERSION, solution.voicings.first().position)
            assertEquals(PitchClass.G, solution.voicings.first().bass.pitchClass)
            assertTrue(solution.breakdown.findings.any { it.ruleId == SecondInversionTriadRules.CADENTIAL_SIX_FOUR })
        }
    }
}
