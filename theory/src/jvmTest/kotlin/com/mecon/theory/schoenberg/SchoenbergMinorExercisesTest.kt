package com.mecon.theory.schoenberg

import com.mecon.theory.ChordArity
import com.mecon.theory.Key
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.SearchConfig
import com.mecon.theory.constraint.ConstraintProgramSolver
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 小调综合练习与无共同音练习的接入测试：各阶段可枚举并求解，旋律进行硬要求在枚举层被尊重，
 * #4/#5 七和弦被排除，无共同音练习确实产生无共同音相邻。
 */
class SchoenbergMinorExercisesTest {
    private val minorKey = Key.fromKeySignatureFifths(0, KeySignatureMode.MINOR)
    private val majorKey = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)

    private val minorStages = listOf(
        SchoenbergCommonToneExercises.INTEGRATED_MINOR_LEADING_EXERCISE_ID to 4,
        SchoenbergCommonToneExercises.INTEGRATED_MINOR_FIRST_INVERSION_EXERCISE_ID to 4,
        SchoenbergCommonToneExercises.INTEGRATED_MINOR_SECOND_INVERSION_EXERCISE_ID to 5,
        SchoenbergCommonToneExercises.INTEGRATED_MINOR_SEVENTH_CHORD_EXERCISE_ID to 7,
    )

    @Test
    fun minorStagesEnumerateAndSolve() {
        minorStages.forEach { (id, count) ->
            val progressions = SchoenbergCommonToneExercises.enumerateForExercise(id, minorKey, count)
            assertTrue(progressions.isNotEmpty(), "$id enumerated empty")
            val program = SchoenbergCommonToneExercises.programForExercise(
                id, minorKey, count, progressions.first(), SearchConfig(maxResults = 1, beamWidth = 192),
            )
            assertTrue(
                ConstraintProgramSolver.solve(program).isNotEmpty(),
                "$id first progression did not solve: ${progressions.first().slots.map { it.degree to it.quality }}",
            )
        }
    }

    @Test
    fun minorEnumerationRespectsMelodicTendencies() {
        val triads = exerciseTriads(minorKey, includeLeadingTriad = true)
        val raisedSixth = SchoenbergMinorChapter.raisedSixthPitchClass(minorKey)
        val leadingTone = SchoenbergMinorChapter.leadingTonePitchClass(minorKey)
        val tonic = SchoenbergMinorChapter.tonicPitchClass(minorKey)
        minorStages.forEach { (id, count) ->
            SchoenbergCommonToneExercises.enumerateForExercise(id, minorKey, count).forEach { progression ->
                progression.slots.zipWithNext().forEach { (before, after) ->
                    val beforePcs = before.toTarget(triads).sonority.pitchClasses
                    val afterPcs = after.toTarget(triads).sonority.pitchClasses
                    // 升六 / 导音可保持（同变化音出现在后继和弦）或上行解决。
                    if (raisedSixth in beforePcs) {
                        assertTrue(
                            leadingTone in afterPcs || raisedSixth in afterPcs,
                            "升六未能保持或上行到导音: $id ${progression.slots}",
                        )
                    }
                    if (leadingTone in beforePcs) {
                        assertTrue(
                            tonic in afterPcs || leadingTone in afterPcs,
                            "导音未能保持或上行到主音: $id ${progression.slots}",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun minorSeventhVocabularyExcludesRaisedFourthAndFifthSevenths() {
        val excluded = setOf(
            SchoenbergMinorChapter.raisedSixthPitchClass(minorKey),
            SchoenbergMinorChapter.leadingTonePitchClass(minorKey),
        )
        val triads = exerciseTriads(minorKey, includeLeadingTriad = true)
        val sevenths = SchoenbergMinorChapter.minorSeventhVocabulary(minorKey)
        assertTrue(sevenths.isNotEmpty())
        sevenths.forEach { chord ->
            val seventhPc = chord.toTarget(triads).pitchClassFor(com.mecon.theory.constraint.ChordTone.SEVENTH)
            assertTrue(seventhPc !in excluded, "七音为 #4/#5 的七和弦未被排除: ${chord.degree}/${chord.quality}")
        }
    }

    @Test
    fun noCommonToneExerciseAllowsAdjacentPairsWithoutCommonTone() {
        listOf(
            SchoenbergCommonToneExercises.NO_COMMON_TONE_MINOR_EXERCISE_ID to minorKey,
            SchoenbergCommonToneExercises.NO_COMMON_TONE_MAJOR_EXERCISE_ID to majorKey,
        ).forEach { (id, key) ->
            val progressions = SchoenbergCommonToneExercises.enumerateForExercise(id, key, 4)
            assertTrue(progressions.isNotEmpty(), "$id enumerated empty")
            val triads = exerciseTriads(key, includeLeadingTriad = true)
            val hasNoCommonToneStep = progressions.any { progression ->
                progression.slots.zipWithNext().any { (before, after) ->
                    val beforePcs = before.toTarget(triads).sonority.pitchClasses.toSet()
                    val afterPcs = after.toTarget(triads).sonority.pitchClasses.toSet()
                    (beforePcs intersect afterPcs).isEmpty()
                }
            }
            assertTrue(hasNoCommonToneStep, "$id 没有产生任何无共同音相邻对")

            // 无共同音练习应复用完整词汇（不止基础三和弦）：应能出现七和弦或转位。
            val usesRicherVocabulary = progressions.any { progression ->
                progression.slots.any { slot ->
                    slot.arity == ChordArity.SEVENTH ||
                        slot.position != com.mecon.theory.textbook.TextbookTriadPosition.ROOT_POSITION
                }
            }
            assertTrue(usesRicherVocabulary, "$id 词汇仍只有基础原位三和弦")

            val program = SchoenbergCommonToneExercises.programForExercise(
                id, key, 4, progressions.first(), SearchConfig(maxResults = 1, beamWidth = 192),
            )
            assertTrue(ConstraintProgramSolver.solve(program).isNotEmpty(), "$id first progression did not solve")
        }
    }
}
