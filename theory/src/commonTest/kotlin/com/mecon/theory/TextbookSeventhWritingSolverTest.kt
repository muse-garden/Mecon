package com.mecon.theory

import com.mecon.api.primitive.PitchClass
import com.mecon.theory.textbook.DominantSeventhRules
import com.mecon.theory.textbook.TextbookSeventhWritingProblem
import com.mecon.theory.textbook.TextbookSeventhWritingSolver
import com.mecon.theory.textbook.SeventhFifthConstraint
import com.mecon.theory.textbook.toConstraintProgram
import com.mecon.theory.constraint.ChordTone
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextbookSeventhWritingSolverTest {
    @Test
    fun compilesSeventhCompletenessIntoConstraintProgram() {
        val key = Key.major(PitchClass.C)
        val dominant = DominantSeventhRules.seventhChordInKey(key, 5)
        val program = TextbookSeventhWritingProblem(
            key = key,
            slots = listOf(
                com.mecon.theory.textbook.TextbookSeventhWritingSlot(
                    chord = dominant,
                    allowedPositions = setOf(com.mecon.theory.textbook.TextbookSeventhPosition.ROOT_POSITION),
                    fifthConstraint = SeventhFifthConstraint.OMIT_FIFTH,
                )
            ),
        ).toConstraintProgram()

        assertTrue(program.ruleModules?.isNotEmpty() == true)
        assertTrue(program.toneCompleteness.any { ChordTone.SEVENTH in it.requiredTones })
        assertTrue(program.toneCompleteness.any { ChordTone.FIFTH in it.omittedTones })
        assertTrue(program.toneCompleteness.any {
            it.ruleId == DominantSeventhRules.ROOT_OR_SEVENTH_OMITTED
        })
    }

    @Test
    fun circleSecondRootInversionSolverKeepsAllSeventhsComplete() {
        val key = Key.major(PitchClass.C)
        val slots = SceneMatcher.instantiateSeventh(
            RuleCatalog.scenes(DominantSeventhRules.CIRCLE_SECOND_ROOT_INVERSION).single(),
            key,
        )

        val solutions = TextbookSeventhWritingSolver.solve(
            TextbookSeventhWritingProblem(
                key = key,
                slots = slots,
                ruleProfile = DominantSeventhRules.INTRODUCTORY_PROFILE.copy(
                    requirements = listOf(
                        RuleRequirement(
                            ruleId = DominantSeventhRules.CIRCLE_SECOND_ROOT_INVERSION,
                            mode = RequirementMode.REQUIRE_INDICATION,
                        )
                    )
                ),
                searchConfig = SearchConfig(maxResults = 1, beamWidth = 64),
            )
        )

        assertTrue(solutions.isNotEmpty(), "二转位/原位交替五度圈应能生成候选")
        solutions.forEach { solution ->
            assertFalse(solution.breakdown.hasHardViolation)
            solution.voicings.dropLast(1).forEach { voicing ->
                val fifth = voicing.chord.chord.pitchClasses[2]
                assertTrue(
                    listOf(voicing.soprano, voicing.alto, voicing.tenor, voicing.bass)
                        .any { it.pitchClass == fifth },
                    "第 ${voicing.slotIndex} 槽七和弦应完整保留五音",
                )
            }
        }
    }
}
