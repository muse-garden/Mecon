package com.mecon.theory

import com.mecon.api.primitive.PitchClass
import com.mecon.theory.textbook.MelodyTextbookRules
import com.mecon.theory.textbook.RootPositionTriadRules
import com.mecon.theory.textbook.RootPositionTriadSolver
import com.mecon.theory.textbook.RootPositionTriadWritingProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootPositionTriadSolverTest {
    @Test
    fun solvesSpecifiedRootPositionTriadSequence() {
        val key = Key.major(PitchClass.C)
        val tonic = RootPositionTriadRules.triadInKey(key, 1)
        val dominant = RootPositionTriadRules.triadInKey(key, 5)

        val solutions = RootPositionTriadSolver.solve(
            RootPositionTriadWritingProblem(
                key = key,
                triads = listOf(tonic, dominant, tonic),
                searchConfig = SearchConfig(maxResults = 6, beamWidth = 64),
            )
        )

        assertTrue(solutions.isNotEmpty())
        solutions.forEach { solution ->
            assertEquals(3, solution.voicings.size)
            assertFalse(solution.breakdown.hasHardViolation)
            assertTrue(solution.voicings.zip(listOf(tonic, dominant, tonic)).all { (voicing, triad) ->
                voicing.triad == triad && voicing.bass.pitchClass == triad.root
            })
        }
    }

    @Test
    fun keepsConnectionIndicationWhileSuppressingGenericLeadingToneWarning() {
        val key = Key.major(PitchClass.C)
        val dominant = RootPositionTriadRules.triadInKey(key, 5)
        val submediant = RootPositionTriadRules.triadInKey(key, 6)

        val solutions = RootPositionTriadSolver.solve(
            RootPositionTriadWritingProblem(
                key = key,
                triads = listOf(dominant, submediant),
                searchConfig = SearchConfig(maxResults = 24, beamWidth = 96),
            )
        )

        val explainedSolution = solutions.firstOrNull { solution ->
            solution.breakdown.findings.any {
                it.ruleId == RootPositionTriadRules.MAJOR_DOMINANT_TO_SIXTH_INNER_LEADING_TONE
            }
        }

        assertTrue(solutions.isNotEmpty())
        assertTrue(explainedSolution != null)
        assertFalse(
            explainedSolution.breakdown.findings.any {
                it.ruleId == MelodyTextbookRules.LEADING_TONE_RESOLUTION
            }
        )
    }

    @Test
    fun exposesScoreContributionsForRulesAndMotion() {
        val key = Key.major(PitchClass.C)
        val tonic = RootPositionTriadRules.triadInKey(key, 1)
        val dominant = RootPositionTriadRules.triadInKey(key, 5)

        val solution = RootPositionTriadSolver.solve(
            RootPositionTriadWritingProblem(
                key = key,
                triads = listOf(tonic, dominant),
                searchConfig = SearchConfig(maxResults = 1, beamWidth = 64),
            )
        ).single()

        assertTrue(solution.breakdown.findings.isNotEmpty())
        assertTrue(solution.breakdown.contributions.isNotEmpty())
    }
}
