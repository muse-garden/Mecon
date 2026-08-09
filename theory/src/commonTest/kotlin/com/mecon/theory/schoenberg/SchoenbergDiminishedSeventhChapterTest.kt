package com.mecon.theory.schoenberg

import com.mecon.api.primitive.PitchClass
import com.mecon.theory.Key
import com.mecon.theory.SearchConfig
import com.mecon.theory.constraint.ConstraintProgramSolver
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.SlotDomain
import com.mecon.theory.constraint.ChordToneNeighborDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchoenbergDiminishedSeventhChapterTest {
    @Test
    fun symmetricChordChoiceExposesEveryInKeyDominantUse() {
        val key = Key.major(PitchClass.C)
        val choices = SchoenbergDiminishedSeventhChapter.chordChoices(key)

        assertEquals(3, choices.size)
        val sharpTwo = choices.first {
            it.chord.degree == 2 && it.chord.rootAlteration == 1
        }
        val usages = SchoenbergDiminishedSeventhChapter.usageChoices(key, sharpTwo.id)

        assertEquals(setOf(3, 5), usages.map { it.tonicizedDegree }.toSet())
        assertTrue(
            usages.any {
                it.tonicizedDegree == 5 &&
                    it.omittedRootDegree == 2 &&
                    it.omittedRootAlteration == 0
            },
            "♯2-♯4-6-1 must be usable as rootless V9/V by lowering ♯2 to 2",
        )
    }

    @Test
    fun independentExerciseWaivesPreparationButRequiresFunctionalResolution() {
        val key = Key.major(PitchClass.C)
        val chord = SchoenbergDiminishedSeventhChapter.chordChoices(key)
            .first { it.chord.degree == 2 && it.chord.rootAlteration == 1 }
        val usage = SchoenbergDiminishedSeventhChapter.usageChoices(key, chord.id)
            .first { it.tonicizedDegree == 5 }
        val progression = SchoenbergDiminishedSeventhChapter.enumerate(
            key = key,
            chordId = chord.id,
            usageId = usage.id,
        ).single()
        val program = SchoenbergDiminishedSeventhChapter.program(
            key = key,
            progression = progression,
            searchConfig = SearchConfig(maxResults = 1, beamWidth = 512),
        )

        assertFalse(
            program.constraints.any {
                it.ruleId == SchoenbergSeventhChordChapter.PREPARATION_RULE_ID
            },
        )
        assertTrue(
            program.constraints.any {
                it.ruleId == SchoenbergDiminishedSeventhChapter.LOWER_TO_ROOT_RULE_ID
            },
        )
        assertTrue(
            program.constraints.any {
                it.ruleId == SchoenbergDiminishedSeventhChapter.ALTERED_TONE_STEP_RULE_ID
            },
        )
        assertTrue(ConstraintProgramSolver.solve(program).isNotEmpty())
    }

    @Test
    fun oneGeneralIntegratedStageSupportsMajorAndMinor() {
        val exerciseId = SchoenbergCommonToneExercises.INTEGRATED_DIMINISHED_SEVENTH_EXERCISE_ID
        val descriptor = SchoenbergCommonToneExercises.descriptorForExercise(exerciseId)
        assertEquals(SchoenbergCommonToneExercises.GENERAL_BRANCH_RULE_ID, descriptor.parentId)

        listOf(Key.major(PitchClass.C), Key.minor(PitchClass.A)).forEach { key ->
            val vocabulary = SchoenbergIntegratedTechTree.vocabularyForStage(exerciseId, key)
            assertTrue(vocabulary.any { it.isRootlessDominantNinth() })
            val progressions = SchoenbergCommonToneExercises.enumerateForExercise(
                exerciseId = exerciseId,
                key = key,
                continuationChordCount = 8,
            )
            assertTrue(progressions.isNotEmpty())
            assertTrue(
                progressions.all {
                    SchoenbergKnowledgeTag.DIMINISHED_SEVENTH in it.knowledgeTags
                }
            )

            val program = SchoenbergIntegratedTechTree.programForStage(
                exerciseId = exerciseId,
                key = key,
                continuationChordCount = 8,
            )
            assertTrue(
                program.constraints.any {
                    it.ruleId == SchoenbergDiminishedSeventhChapter.LOWER_TO_ROOT_RULE_ID
                }
            )
            assertTrue(
                program.constraints.any {
                    it.ruleId == SchoenbergDiminishedSeventhChapter.ALTERED_TONE_STEP_RULE_ID
                }
            )
        }
    }

    @Test
    fun integratedPairProjectionKeepsSharpTwoRootlessFiveToFiveWritable() {
        val key = Key.major(PitchClass.C)
        val progression = SchoenbergDiminishedSeventhChapter.enumerate(key)
            .first {
                it.slots.first().degree == 2 &&
                    it.slots.first().rootAlteration == 1 &&
                    it.slots.first().appliedToDegree == 5
            }
        val reference = SchoenbergIntegratedTechTree.program(
            key = key,
            continuationChordCount = 2,
            treatmentIds = SchoenbergHarmonicTreatments.integratedDiminishedTreatments,
        )
        val triads = exerciseTriads(key, includeLeadingTriad = true)
        val domains = progression.slots.map { SlotDomain(listOf(it.toTarget(triads))) }
        val pair = ConstraintProgram.fromRequirements(
            key = key,
            slotDomains = domains,
            configuration = com.mecon.theory.constraint.ConstraintRequirementConfiguration(
                ruleProfile = SchoenbergCommonToneExercises.SCHOENBERG_PROFILE,
                toneCompleteness = reference.toneCompleteness,
                avoidDoublings = reference.avoidDoublings.filter { it.slot in 0..1 },
                adjacentCommonTones = reference.adjacentCommonTones,
                chordToneNeighbors = reference.chordToneNeighbors.map {
                    it.copy(
                        sourceSlot = when (it.direction) {
                            ChordToneNeighborDirection.NEXT -> 0
                            ChordToneNeighborDirection.PREVIOUS -> 1
                        }
                    )
                },
                ruleModules = emptyList(),
                includeDerivedTextbookConstraints = false,
                searchConfig = SearchConfig(maxResults = 1, beamWidth = 512),
            ),
        )

        assertTrue(ConstraintProgramSolver.solve(pair).isNotEmpty())
    }
}
