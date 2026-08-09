package com.mecon.theory.schoenberg

import com.mecon.theory.ChordQuality
import com.mecon.theory.Key
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.NaturalTriads
import com.mecon.theory.SearchConfig
import com.mecon.theory.constraint.ConstraintProgramSolver
import com.mecon.theory.constraint.ConstraintModality
import com.mecon.theory.textbook.TextbookTriadPosition
import com.mecon.theory.ChordArity
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.constraint.TargetSelector
import com.mecon.theory.textbook.TextbookSeventhPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchoenbergCommonToneExercisesTest {
    @Test
    fun softenedChapterViolationKeepsAPositivePenalty() {
        val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
        val progression = SchoenbergSecondInversionChapter.enumerate(key).first()
        val hardRule = SchoenbergSecondInversionChapter.program(key, progression).constraints
            .first { it.modality == ConstraintModality.Require }
        val softened = hardRule.asSoftPreference()

        assertEquals(6.0, (softened.modality as ConstraintModality.Prefer).weight)
    }

    @Test
    fun secondaryHarmonyIsOneGeneralIntegratedStageAfterFreerSeventhAndLeadingChords() {
        val descriptors = SchoenbergCommonToneExercises.exerciseDescriptors
        val freerIndex = descriptors.indexOfFirst {
            it.exerciseId == SchoenbergCommonToneExercises.FREER_SEVENTH_LEADING_EXERCISE_ID
        }
        val secondaryIndex = descriptors.indexOfFirst {
            it.exerciseId == SchoenbergCommonToneExercises.SECONDARY_HARMONY_EXERCISE_ID
        }
        val secondary = descriptors[secondaryIndex]

        assertTrue(freerIndex >= 0)
        assertEquals(freerIndex + 1, secondaryIndex)
        assertEquals(SchoenbergExerciseGroup.INTEGRATED, secondary.group)
        assertEquals(SchoenbergCommonToneExercises.GENERAL_BRANCH_RULE_ID, secondary.parentId)
    }

    @Test
    fun majorIntegratedStagesHaveCumulativeVocabularies() {
        val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
        val stages = listOf(
            SchoenbergCommonToneExercises.INTEGRATED_MAJOR_LEADING_EXERCISE_ID,
            SchoenbergCommonToneExercises.INTEGRATED_MAJOR_FIRST_INVERSION_EXERCISE_ID,
            SchoenbergCommonToneExercises.INTEGRATED_MAJOR_SECOND_INVERSION_EXERCISE_ID,
            SchoenbergCommonToneExercises.INTEGRATED_MAJOR_SEVENTH_CHORD_EXERCISE_ID,
            SchoenbergCommonToneExercises.SECONDARY_HARMONY_EXERCISE_ID,
        ).map { SchoenbergIntegratedTechTree.vocabularyForStage(it, key) }

        stages.zipWithNext().forEach { (previous, next) -> assertTrue(next.containsAll(previous)) }
        assertTrue(stages[2].any { it.position == TextbookTriadPosition.SECOND_INVERSION })
        assertTrue(stages[3].any { it.arity == ChordArity.SEVENTH })
        assertTrue(stages[4].any { it.secondaryFamily != null })

        val earlierProgression = SchoenbergCommonToneExercises.enumerateForExercise(
            SchoenbergCommonToneExercises.INTEGRATED_MAJOR_LEADING_EXERCISE_ID,
            key,
            continuationChordCount = 4,
        ).first()
        val acceptedByNextStage = SchoenbergIntegratedTechTree.programForStage(
            exerciseId = SchoenbergCommonToneExercises.INTEGRATED_MAJOR_FIRST_INVERSION_EXERCISE_ID,
            key = key,
            continuationChordCount = 4,
            progression = earlierProgression,
        )
        assertEquals(earlierProgression.slots.size, acceptedByNextStage.slotDomains.size)
    }

    @Test
    fun laterIntegratedStagesEnumerateTheirNewChordFamilies() {
        val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
        val sixFour = SchoenbergCommonToneExercises.enumerateForExercise(
            SchoenbergCommonToneExercises.INTEGRATED_MAJOR_SECOND_INVERSION_EXERCISE_ID,
            key,
            continuationChordCount = 5,
        )
        val sevenths = SchoenbergCommonToneExercises.enumerateForExercise(
            SchoenbergCommonToneExercises.INTEGRATED_MAJOR_SEVENTH_CHORD_EXERCISE_ID,
            key,
            continuationChordCount = 7,
        )

        assertTrue(sixFour.isNotEmpty())
        assertTrue(sixFour.any { progression -> progression.slots.any { it.position == TextbookTriadPosition.SECOND_INVERSION } })
        assertTrue(sevenths.isNotEmpty())
        assertTrue(sevenths.any { progression -> progression.slots.any { it.arity == ChordArity.SEVENTH } })
    }

    @Test
    fun secondaryHarmonyIntegratedStageEnumeratesAppliedChordsAndLocalResolution() {
        listOf(
            KeySignatureMode.MAJOR,
            KeySignatureMode.MINOR,
        ).forEach { mode ->
            val exerciseId = SchoenbergCommonToneExercises.SECONDARY_HARMONY_EXERCISE_ID
            val key = Key.fromKeySignatureFifths(0, mode)
            val selected = SchoenbergSecondaryDominantChapter.harmonyChoices(key).first()
            val progressions = SchoenbergCommonToneExercises.enumerateForExercise(
                exerciseId = exerciseId,
                key = key,
                continuationChordCount = SchoenbergCommonToneExercises.minContinuationChordCount(exerciseId),
                selections = mapOf(
                    SchoenbergExerciseSelectionKeys.SECONDARY_HARMONY to listOf(selected.id),
                ),
            )

            assertTrue(progressions.isNotEmpty(), "$exerciseId should enumerate applied harmony")
            assertTrue(progressions.all { SchoenbergKnowledgeTag.SECONDARY_HARMONY in it.knowledgeTags })
            assertTrue(progressions.all { progression ->
                progression.slots.any { it.transitionToken() == selected.chord.transitionToken() }
            })
            assertTrue(progressions.all { progression ->
                progression.slots.zipWithNext().all { (before, after) ->
                    before.secondaryFamily == null ||
                        SchoenbergSecondaryDominantChapter.allowsResolution(
                            before,
                            after,
                            key,
                            exerciseTriads(key, includeLeadingTriad = true),
                        )
                }
            })
        }
    }

    @Test
    fun integratedEnumerationNeverPlacesSameRootChordsAdjacently() {
        val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
        listOf(
            SchoenbergCommonToneExercises.INTEGRATED_MAJOR_FIRST_INVERSION_EXERCISE_ID to 5,
            SchoenbergCommonToneExercises.INTEGRATED_MAJOR_SECOND_INVERSION_EXERCISE_ID to 5,
            SchoenbergCommonToneExercises.INTEGRATED_MAJOR_SEVENTH_CHORD_EXERCISE_ID to 7,
        ).forEach { (exerciseId, count) ->
            val progressions = SchoenbergCommonToneExercises.enumerateForExercise(exerciseId, key, count)
            assertTrue(progressions.isNotEmpty(), "$exerciseId 应能枚举出进行。")
            assertTrue(
                progressions.all { progression ->
                    progression.slots.zipWithNext().none { (before, after) -> before.degree == after.degree }
                },
                "$exerciseId 不应把相同根音（如 I 与 I6 / I7）排在相邻槽位。",
            )
        }
    }

    @Test
    fun integratedEnumerationResolvesSeventhChordsByRootAscendingFourth() {
        val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
        val exerciseId = SchoenbergCommonToneExercises.INTEGRATED_MAJOR_SEVENTH_CHORD_EXERCISE_ID
        val count = SchoenbergCommonToneExercises.minContinuationChordCount(exerciseId)
        val progressions = SchoenbergCommonToneExercises.enumerateForExercise(exerciseId, key, count)
        assertTrue(progressions.any { it.slots.any { chord -> chord.arity == ChordArity.SEVENTH } })
        // 每个七和弦（含导七和弦 vii°7）都必须由根音上行四度（下行五度，调内 +3 音级）的和弦承接——
        // 与导三和弦解决到 III 同理。禁止 V7→iii（下三度）、V7→vi（上二度）等根音进行。
        assertTrue(
            progressions.all { progression ->
                progression.slots.zipWithNext().none { (before, after) ->
                    before.arity == ChordArity.SEVENTH && (after.degree - before.degree).mod(7) != 3
                }
            },
            "枚举出的七和弦必须以根音上行四度解决。",
        )
    }

    @Test
    fun laterIntegratedExercisesSolveFirstEnumeratedProgression() {
        val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
        listOf(
            SchoenbergCommonToneExercises.INTEGRATED_MAJOR_SECOND_INVERSION_EXERCISE_ID,
            SchoenbergCommonToneExercises.INTEGRATED_MAJOR_SEVENTH_CHORD_EXERCISE_ID,
            SchoenbergCommonToneExercises.SECONDARY_HARMONY_EXERCISE_ID,
        ).forEach { exerciseId ->
            val count = SchoenbergCommonToneExercises.minContinuationChordCount(exerciseId)
            val selectedSecondaryId = if (exerciseId == SchoenbergCommonToneExercises.SECONDARY_HARMONY_EXERCISE_ID) {
                SchoenbergSecondaryDominantChapter.harmonyChoices(key).first().id
            } else {
                null
            }
            val progression = SchoenbergCommonToneExercises
                .enumerateForExercise(
                    exerciseId = exerciseId,
                    key = key,
                    continuationChordCount = count,
                    selections = selectedSecondaryId?.let {
                        mapOf(SchoenbergExerciseSelectionKeys.SECONDARY_HARMONY to listOf(it))
                    }.orEmpty(),
                )
                .firstOrNull()
            assertTrue(progression != null, "$exerciseId 应能枚举出进行。")
            val program = SchoenbergCommonToneExercises.programForExercise(
                exerciseId,
                key,
                count,
                progression = progression,
                searchConfig = SearchConfig(maxResults = 1, beamWidth = 160),
            )
            val solutions = ConstraintProgramSolver.solve(program)
            assertTrue(solutions.isNotEmpty(), "$exerciseId 综合练习的首选进行必须能求解出四部写作。")
            assertTrue(
                solutions.first().breakdown.findings.none { it.severity.name == "HARD" },
                "$exerciseId 的求解结果不应含硬约束违规。",
            )
        }
    }

    @Test
    fun integratedChordSelectorsIntersectWithinRowsAndUseDistinctSlots() {
        val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
        val progressions = SchoenbergIntegratedTechTree.enumerateForStage(
            exerciseId = SchoenbergCommonToneExercises.INTEGRATED_MAJOR_FIRST_INVERSION_EXERCISE_ID,
            key = key,
            continuationChordCount = 5,
            chordSelectors = listOf(
                TargetSelector(degrees = setOf(7), arities = setOf(ChordArity.TRIAD), inversions = setOf(1)),
                TargetSelector(degrees = setOf(3)),
            ),
        )

        assertTrue(progressions.isNotEmpty())
        assertTrue(progressions.all { progression ->
            progression.slots.any { it.degree == 7 && it.arity == ChordArity.TRIAD && it.position.ordinal == 1 } &&
                progression.slots.any { it.degree == 3 }
        })
    }

    @Test
    fun sixFourChapterEnumeratesAndSolvesOnlyPreparedStepResolvedConnections() {
        val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
        val progressions = SchoenbergCommonToneExercises.enumerateForExercise(
            SchoenbergCommonToneExercises.SECOND_INVERSION_EXERCISE_ID,
            key,
            continuationChordCount = 2,
        )

        assertTrue(progressions.isNotEmpty())
        assertTrue(progressions.all { progression ->
            progression.slots.size == 3 &&
                progression.slots[1].position == TextbookTriadPosition.SECOND_INVERSION &&
                progression.slots.zipWithNext().none { (before, after) ->
                    before.position == TextbookTriadPosition.SECOND_INVERSION &&
                        after.position == TextbookTriadPosition.SECOND_INVERSION
                }
        })

        val program = SchoenbergCommonToneExercises.programForExercise(
            SchoenbergCommonToneExercises.SECOND_INVERSION_EXERCISE_ID,
            key,
            continuationChordCount = 2,
            progression = progressions.first(),
            searchConfig = SearchConfig(maxResults = 1, beamWidth = 192),
        )
        val solutions = ConstraintProgramSolver.solve(program)

        assertTrue(solutions.isNotEmpty())
        assertTrue(solutions.first().breakdown.findings.none { it.severity.name == "HARD" })
        assertTrue(solutions.first().breakdown.findings.any { it.ruleId == SchoenbergSecondInversionChapter.PREPARATION_RULE_ID })
        assertTrue(solutions.first().breakdown.findings.any { it.ruleId == SchoenbergSecondInversionChapter.BASS_RESOLUTION_RULE_ID })
    }

    @Test
    fun leadingSeventhFifthUsesGeneralLeadingRuleAndSolvesCircle() {
        val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
        val progressions = SchoenbergCommonToneExercises.enumerateForExercise(
            SchoenbergCommonToneExercises.SEVENTH_CHORD_EXERCISE_ID,
            key,
            continuationChordCount = 7,
        )

        assertEquals(listOf(1, 4, 7, 3, 6, 2, 5, 1), progressions.first().slots.map { it.degree })
        assertTrue(progressions.first().slots.subList(1, 7).all { it.arity == ChordArity.SEVENTH })
        assertTrue(progressions.flatMap { it.slots }.any { it.seventhPosition == TextbookSeventhPosition.THIRD_INVERSION })

        val secondInversionProgram = SchoenbergCommonToneExercises.programForExercise(
            SchoenbergCommonToneExercises.SEVENTH_CHORD_EXERCISE_ID,
            key,
            continuationChordCount = 7,
            progression = progressions.first { progression ->
                progression.slots.any { it.seventhPosition == TextbookSeventhPosition.SECOND_INVERSION }
            },
        )
        assertTrue(
            secondInversionProgram.constraints.any { constraint ->
                constraint.ruleId == SchoenbergSeventhChordChapter.PREPARATION_RULE_ID &&
                    constraint.expr is com.mecon.theory.constraint.ConstraintExpr.Or
            },
            "七和弦第二转位应从通用预备原则推导出根音/五音择一预备。",
        )

        val program = SchoenbergCommonToneExercises.programForExercise(
            SchoenbergCommonToneExercises.SEVENTH_CHORD_EXERCISE_ID,
            key,
            continuationChordCount = 7,
            progression = progressions.first(),
            searchConfig = SearchConfig(maxResults = 1, beamWidth = 256),
        )
        val leadingSlot = program.slotDomains[2].targets.single()
        assertEquals(ChordArity.SEVENTH, leadingSlot.arity)
        // 七音（七和弦本身的不协和音）仍由七和弦章节按 slot 推导。
        assertTrue(program.chordToneNeighbors.any { it.sourceSlot == 2 && it.sourceTone == ChordTone.SEVENTH })
        // 五音（导七和弦的第二不协和音，即导三和弦的五音）改由一般导和弦规则统一处理，不限 arity、以 sourceSelector
        // 命中导音级而非钉具体 slot——书中三和弦规则在对应七和弦一体适用，本章不再重写五音。
        assertTrue(
            program.chordToneNeighbors.any {
                it.sourceTone == ChordTone.FIFTH && it.sourceSelector.degrees == setOf(LEADING_TONE_DEGREE)
            },
            "导七和弦的五音应由一般导和弦规则（不限 arity）预备/解决，而非在七和弦章节重写。",
        )

        val solutions = ConstraintProgramSolver.solve(program)
        assertTrue(solutions.isNotEmpty())
        assertTrue(solutions.first().breakdown.findings.none { it.severity.name == "HARD" })
        assertTrue(solutions.first().breakdown.findings.any { it.ruleId == SchoenbergSeventhChordChapter.PREPARATION_RULE_ID })
        assertTrue(solutions.first().breakdown.findings.any { it.ruleId == SchoenbergSeventhChordChapter.RESOLUTION_RULE_ID })
    }

    @Test
    fun enumeratesRootPositionCommonToneProgressionsFromCountOnly() {
        val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
        val progressions = SchoenbergCommonToneExercises.enumerateForExercise(
            exerciseId = SchoenbergCommonToneExercises.FIRST_EXERCISE_ID,
            key = key,
            continuationChordCount = 2,
        )

        assertTrue(progressions.isNotEmpty())
        progressions.forEach { progression ->
            assertEquals(3, progression.slots.size)
            assertEquals(1, progression.slots.first().degree)
            assertEquals(
                progression.slots.map { it.degree to it.quality }.size,
                progression.slots.map { it.degree to it.quality }.toSet().size,
            )
        }
    }

    @Test
    fun solvesVoicingForEnumeratedProgression() {
        val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
        val progression = SchoenbergCommonToneExercises.enumerateForExercise(
            exerciseId = SchoenbergCommonToneExercises.FIRST_EXERCISE_ID,
            key = key,
            continuationChordCount = 2,
        ).first()
        val program = SchoenbergCommonToneExercises.programForExercise(
            exerciseId = SchoenbergCommonToneExercises.FIRST_EXERCISE_ID,
            key = key,
            continuationChordCount = 2,
            progression = progression,
            searchConfig = SearchConfig(maxResults = 2, beamWidth = 160),
        )

        val solutions = ConstraintProgramSolver.solve(program)

        assertTrue(solutions.isNotEmpty())
        assertTrue(
            solutions.all { solution ->
                solution.breakdown.findings.none {
                    it.ruleId.value.startsWith("solver.constraint.") && it.severity.name == "HARD"
                }
            },
        )
    }

    @Test
    fun solvesFiveContinuationChordsFromCountOnly() {
        val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
        val program = SchoenbergCommonToneExercises.programForExercise(
            exerciseId = SchoenbergCommonToneExercises.FIRST_EXERCISE_ID,
            key = key,
            continuationChordCount = 5,
            searchConfig = SearchConfig(maxResults = 1, beamWidth = 128),
        )

        val solutions = ConstraintProgramSolver.solve(program)

        assertTrue(solutions.isNotEmpty())
        assertEquals(6, solutions.first().voicings.size)
        assertTrue(
            solutions.all { solution ->
                solution.breakdown.findings.none {
                    it.ruleId.value.startsWith("solver.constraint.") && it.severity.name == "HARD"
                }
            },
        )
    }

    @Test
    fun leadingTriadExerciseEnumeratesPreparationLeadingResolution() {
        val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
        val progressions = SchoenbergCommonToneExercises.enumerateForExercise(
            SchoenbergCommonToneExercises.LEADING_TRIAD_EXERCISE_ID,
            key,
            continuationChordCount = 2,
        )
        val triads = NaturalTriads.inKey(key)
        val leading = triads.first { it.degree == 7 && it.quality.name == "DIMINISHED" }
        val leadingFifth = leading.chord.pitchClasses[2]

        assertTrue(progressions.isNotEmpty())
        progressions.forEach { progression ->
            assertEquals(3, progression.slots.size)
            assertEquals(7, progression.slots[1].degree)
            assertEquals(TextbookTriadPosition.ROOT_POSITION, progression.slots[1].position)
            assertEquals(3, progression.slots[2].degree)
            val preparation = triads.first {
                it.degree == progression.slots[0].degree && it.quality == progression.slots[0].quality
            }
            assertTrue(leadingFifth in preparation.chord.pitchClasses)
        }
    }

    @Test
    fun leadingTriadExerciseSolvesPreparationAndResolution() {
        val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
        val progression = SchoenbergCommonToneExercises.enumerateForExercise(
            SchoenbergCommonToneExercises.LEADING_TRIAD_EXERCISE_ID,
            key,
            continuationChordCount = 2,
        ).first()
        val program = SchoenbergCommonToneExercises.programForExercise(
            exerciseId = SchoenbergCommonToneExercises.LEADING_TRIAD_EXERCISE_ID,
            key = key,
            continuationChordCount = 2,
            progression = progression,
            searchConfig = SearchConfig(maxResults = 1, beamWidth = 160),
        )

        val solutions = ConstraintProgramSolver.solve(program)

        assertTrue(solutions.isNotEmpty())
        val findingIds = solutions.first().breakdown.findings.map { it.ruleId }.toSet()
        assertTrue(SchoenbergCommonToneExercises.LEADING_TRIAD_PREPARATION_RULE_ID in findingIds)
        assertTrue(SchoenbergCommonToneExercises.LEADING_TRIAD_RESOLUTION_RULE_ID in findingIds)
        assertTrue(
            solutions.all { solution ->
                solution.breakdown.findings.none {
                    it.ruleId.value.startsWith("solver.constraint.") && it.severity.name == "HARD"
                }
            },
        )
    }

    @Test
    fun firstInversionExerciseEnumeratesRootToSixthAndSixthToSixthConnections() {
        val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
        val progressions = SchoenbergCommonToneExercises.enumerateForExercise(
            SchoenbergCommonToneExercises.FIRST_INVERSION_EXERCISE_ID,
            key,
            continuationChordCount = 1,
        )

        assertTrue(progressions.any {
            it.slots.map { slot -> slot.position } ==
                listOf(TextbookTriadPosition.ROOT_POSITION, TextbookTriadPosition.FIRST_INVERSION)
        })
        assertTrue(progressions.any {
            it.slots.map { slot -> slot.position } ==
                listOf(TextbookTriadPosition.FIRST_INVERSION, TextbookTriadPosition.FIRST_INVERSION)
        })
    }

    @Test
    fun firstInversionExerciseSolvesWithoutHardConstraintFindings() {
        val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
        val progression = SchoenbergCommonToneExercises.enumerateForExercise(
            SchoenbergCommonToneExercises.FIRST_INVERSION_EXERCISE_ID,
            key,
            continuationChordCount = 1,
        )
            .first { it.slots.first().position == TextbookTriadPosition.ROOT_POSITION }
        val program = SchoenbergCommonToneExercises.programForExercise(
            exerciseId = SchoenbergCommonToneExercises.FIRST_INVERSION_EXERCISE_ID,
            key = key,
            continuationChordCount = 1,
            progression = progression,
            searchConfig = SearchConfig(maxResults = 1, beamWidth = 160),
        )

        val solutions = ConstraintProgramSolver.solve(program)

        assertTrue(solutions.isNotEmpty())
        assertTrue(
            solutions.all { solution ->
                solution.breakdown.findings.none {
                    it.ruleId.value.startsWith("solver.constraint.") && it.severity.name == "HARD"
                }
            },
        )
    }

    @Test
    fun firstInversionExerciseAllowsIiSixToPrepareLeadingTriad() {
        val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
        val progression = SchoenbergCommonToneExercises.enumerateForExercise(
            SchoenbergCommonToneExercises.FIRST_INVERSION_EXERCISE_ID,
            key,
            continuationChordCount = 1,
        )
            .first {
                it.slots[0].degree == 2 &&
                    it.slots[0].position == TextbookTriadPosition.FIRST_INVERSION &&
                    it.slots[1].degree == 7
            }
        val program = SchoenbergCommonToneExercises.programForExercise(
            exerciseId = SchoenbergCommonToneExercises.FIRST_INVERSION_EXERCISE_ID,
            key = key,
            continuationChordCount = 1,
            progression = progression,
            searchConfig = SearchConfig(maxResults = 1, beamWidth = 192),
        )

        val solutions = ConstraintProgramSolver.solve(program)

        assertTrue(solutions.isNotEmpty())
        assertTrue(
            solutions.all { solution ->
                solution.breakdown.findings.none {
                    it.ruleId.value == "solver.constraint.avoid-doubling" && it.severity.name == "HARD"
                }
            },
        )
    }

    @Test
    fun integratedExerciseRewardsLeadingTriadInversionRichness() {
        val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
        val progressions = SchoenbergCommonToneExercises.enumerateForExercise(
            exerciseId = SchoenbergCommonToneExercises.INTEGRATED_MAJOR_FIRST_INVERSION_EXERCISE_ID,
            key = key,
            continuationChordCount = 4,
        )

        assertTrue(progressions.isNotEmpty())
        assertTrue(progressions.all { it.slots.last().degree == 1 && it.slots.last().position == TextbookTriadPosition.ROOT_POSITION })
        assertTrue(progressions.any { it.slots.any { slot -> slot.degree == 7 } })
        assertTrue(
            progressions.take(8).any { progression ->
                SchoenbergKnowledgeTag.LEADING_FIRST_INVERSION in progression.knowledgeTags
            },
            "综合枚举前列应奖励导和弦转位等组合知识。",
        )
    }

    @Test
    fun integratedEnumerationUsesDescriptorTargetsAndDynamicLengthRanges() {
        val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
        val leadingOnly = SchoenbergCommonToneExercises.enumerateForExercise(
            exerciseId = SchoenbergCommonToneExercises.INTEGRATED_MAJOR_LEADING_EXERCISE_ID,
            key = key,
            continuationChordCount = 4,
        )
        val leadingAndSixth = SchoenbergCommonToneExercises.enumerateForExercise(
            exerciseId = SchoenbergCommonToneExercises.INTEGRATED_MAJOR_FIRST_INVERSION_EXERCISE_ID,
            key = key,
            continuationChordCount = 4,
        )

        assertTrue(leadingOnly.isNotEmpty())
        assertTrue(leadingOnly.all { SchoenbergKnowledgeTag.LEADING_TRIAD in it.knowledgeTags })
        assertTrue(leadingAndSixth.isNotEmpty())
        assertTrue(leadingAndSixth.all { SchoenbergKnowledgeTag.LEADING_TRIAD in it.knowledgeTags })
        assertTrue(leadingAndSixth.any { SchoenbergKnowledgeTag.FIRST_INVERSION in it.knowledgeTags })
        assertEquals(
            4..6,
            SchoenbergCommonToneExercises.continuationChordCountRange(
                SchoenbergCommonToneExercises.INTEGRATED_MAJOR_LEADING_EXERCISE_ID,
            ),
        )
        assertEquals(
            4..12,
            SchoenbergCommonToneExercises.continuationChordCountRange(
                SchoenbergCommonToneExercises.INTEGRATED_MAJOR_FIRST_INVERSION_EXERCISE_ID,
            ),
        )
    }


    @Test
    fun boundedIntegratedEnumerationSupportsEightAndTwelveContinuationChords() {
        val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
        val eight = SchoenbergCommonToneExercises.enumerateForExercise(
            exerciseId = SchoenbergCommonToneExercises.INTEGRATED_MAJOR_FIRST_INVERSION_EXERCISE_ID,
            key = key,
            continuationChordCount = 8,
        )
        val twelve = SchoenbergCommonToneExercises.enumerateForExercise(
            exerciseId = SchoenbergCommonToneExercises.INTEGRATED_MAJOR_FIRST_INVERSION_EXERCISE_ID,
            key = key,
            continuationChordCount = 12,
        )

        assertTrue(eight.isNotEmpty())
        assertTrue(twelve.isNotEmpty())
        assertTrue(eight.size <= 64)
        assertTrue(twelve.size <= 64)
        assertTrue(eight.all { it.slots.size == 9 })
        assertTrue(twelve.all { it.slots.size == 13 })
        assertTrue(eight.all { it.slots.dropLast(1).distinct().size == it.slots.size - 1 })
        assertTrue(twelve.all { it.slots.dropLast(1).distinct().size == it.slots.size - 1 })
        assertTrue((eight + twelve).all { SchoenbergKnowledgeTag.LEADING_TRIAD in it.knowledgeTags })
        assertTrue(eight.any { SchoenbergKnowledgeTag.FIRST_INVERSION in it.knowledgeTags })
        assertTrue(twelve.any { SchoenbergKnowledgeTag.FIRST_INVERSION in it.knowledgeTags })
    }

    @Test
    fun tracesRejectedIntegratedLeadingAndSixthProgression() {
        val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
        val progression = SchoenbergSymbolicProgression(
            slots = listOf(
                SchoenbergSymbolicChord(1, ChordQuality.MAJOR),
                SchoenbergSymbolicChord(1, ChordQuality.MAJOR),
                SchoenbergSymbolicChord(4, ChordQuality.MAJOR),
                SchoenbergSymbolicChord(7, ChordQuality.DIMINISHED, TextbookTriadPosition.FIRST_INVERSION),
                SchoenbergSymbolicChord(3, ChordQuality.MINOR),
                SchoenbergSymbolicChord(1, ChordQuality.MAJOR),
            ),
            kind = SchoenbergConnectionKind.INTEGRATED,
        )
        val program = SchoenbergCommonToneExercises.programForExercise(
            exerciseId = SchoenbergCommonToneExercises.INTEGRATED_MAJOR_FIRST_INVERSION_EXERCISE_ID,
            key = key,
            continuationChordCount = 5,
            progression = progression,
            searchConfig = SearchConfig(maxResults = 1, beamWidth = 128),
        )
        val traced = ConstraintProgramSolver.trace(
            program,
            maxEntries = 8,
        )
        println(
            "SCHOENBERG_DFS_TRACE identityCollision=" +
                "${program.slotDomains[0].targets.single().identityKey()} == " +
                "${program.slotDomains[1].targets.single().identityKey()}",
        )

        println(
            "SCHOENBERG_DFS_TRACE progression=" +
                progression.slots.joinToString(" -> ") { "${it.degree}/${it.position}" } +
                " visited=${traced.trace.visitedNodes}/${traced.trace.nodeBudget}" +
                " exhausted=${traced.trace.exhaustedBudget}" +
                " solutions=${traced.solutions.size}",
        )
        traced.trace.entries.forEach { entry ->
            println(
                "SCHOENBERG_DFS_TRACE kind=${entry.kind} depth=${entry.depth}" +
                    " candidates=${entry.candidateCount}/${entry.acceptedCount}" +
                    " state=${entry.state}" +
                    " hard=${entry.hardViolations.joinToString(" | ")}",
            )
        }

        assertTrue(traced.solutions.isEmpty())
        assertTrue(traced.trace.entries.any { it.hardViolations.isNotEmpty() })
    }

    @Test
    fun logsIntegratedEnumerationAndLeadingTriadSolverPath() {
        val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
        val progressionsByCount = (2..5).associateWith { continuationChordCount ->
            SchoenbergCommonToneExercises.enumerateForExercise(
                exerciseId = SchoenbergCommonToneExercises.INTEGRATED_MAJOR_FIRST_INVERSION_EXERCISE_ID,
                key = key,
                continuationChordCount = continuationChordCount,
            )
        }

        progressionsByCount.forEach { (count, progressions) ->
            val leading = progressions.filter { progression -> progression.slots.any { it.degree == 7 } }
            println(
                "SCHOENBERG_ENUM count=" + count + " total=" + progressions.size +
                    " leading=" + leading.size + " samples=" +
                    progressions.take(6).joinToString { it.slots.joinToString("-") { chord -> chord.degree.toString() + chord.position.ordinal } },
            )
            assertTrue(progressions.all { it.slots.last().degree == 1 })
            if (count >= 4) assertTrue(leading.isNotEmpty(), "至少四个接续和弦时应能枚举到导和弦。")
        }

        val leadingProgression = progressionsByCount.getValue(4)
            .first { progression -> progression.slots.any { it.degree == 7 } }
        val solutions = ConstraintProgramSolver.solve(
            SchoenbergCommonToneExercises.programForExercise(
                exerciseId = SchoenbergCommonToneExercises.INTEGRATED_MAJOR_FIRST_INVERSION_EXERCISE_ID,
                key = key,
                continuationChordCount = 4,
                progression = leadingProgression,
                searchConfig = SearchConfig(maxResults = 2, beamWidth = 160),
            )
        )
        println(
            "SCHOENBERG_SOLVER progression=" +
                leadingProgression.slots.joinToString("-") { it.degree.toString() } +
                " solutions=" + solutions.size +
                " findings=" + solutions.firstOrNull()?.breakdown?.findings?.map { it.ruleId },
        )
        assertTrue(solutions.isNotEmpty(), "枚举出的导和弦进行必须能进入四部写作求解。")


    }

}
