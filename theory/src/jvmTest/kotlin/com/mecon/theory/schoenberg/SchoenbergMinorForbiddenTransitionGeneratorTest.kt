package com.mecon.theory.schoenberg

import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.Key
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.SearchConfig
import com.mecon.theory.constraint.ChordToneNeighborDirection
import com.mecon.theory.constraint.ChordTarget
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.ConstraintProgramSolver
import com.mecon.theory.constraint.SlotDomain
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookTriadPosition
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 小调「禁忌相邻进行」表的生成器兼时效守卫，与 [SchoenbergForbiddenTransitionGeneratorTest] 平行，只是探测在 **a 小调**、
 * 参考程序取小调最全综合练习。小调因升六 / 导音的旋律硬要求（[SchoenbergMinorChapter]）与减/增和弦预备解决，
 * 禁忌相邻对与大调不同，故单独一张表 `forbidden-transitions-minor.txt`。
 *
 * - 重刷：`./gradlew.bat :theory:schoenbergForbiddenTransitionTest --tests "*SchoenbergMinorForbiddenTransitionGenerator*" -Pschoenberg.forbidden.write=true`
 * - 判据在小调内调性无关，a 小调探测一次即可。
 */
class SchoenbergMinorForbiddenTransitionGeneratorTest {
    private val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MINOR)
    private val triads by lazy { exerciseTriads(key, includeLeadingTriad = true) }

    @Test
    fun minorForbiddenTransitionTableStaysFresh() {
        val computed = computeForbiddenTransitions()
        val file = File(OUTPUT_PATH)
        if (System.getProperty("schoenberg.forbidden.write") == "true") {
            file.parentFile?.mkdirs()
            file.writeText(render(computed))
            println("SCHOENBERG_MINOR_FORBIDDEN wrote ${computed.size} transitions -> ${file.absolutePath}")
            computed.forEach { (before, after) ->
                println("  ${humanLabel(before)}  =>  ${humanLabel(after)}    ($before => $after)")
            }
        } else {
            val committed = SchoenbergForbiddenTransitions.parse(
                file.takeIf { it.exists() }?.readText(),
            )
            assertEquals(
                computed.map { it.first.token to it.second.token }.toSet(),
                committed,
                "勋伯格小调禁忌进行表已过期。重刷：./gradlew.bat :theory:schoenbergForbiddenTransitionTest " +
                    "--tests \"*SchoenbergMinorForbiddenTransitionGenerator*\" -Pschoenberg.forbidden.write=true",
            )
            assertEquals(
                committed,
                SchoenbergForbiddenTransitions.parse(loadSchoenbergForbiddenTransitionResource(minor = true)),
                "运行时 classpath 资源 forbidden-transitions-minor.txt 与源文件不一致（打包路径问题？）。",
            )
        }
    }

    private data class Pairing(val first: SchoenbergSymbolicChord, val second: SchoenbergSymbolicChord)

    private fun computeForbiddenTransitions(): List<Pairing> {
        val vocabulary = SchoenbergIntegratedTechTree
            .vocabularyForStage(
                SchoenbergCommonToneExercises.INTEGRATED_AUGMENTED_SIXTH_EXERCISE_ID,
                key,
            )
            .sortedBy { it.transitionToken() }
        val targets = vocabulary.associateWith { it.toTarget(triads) }
        return buildList {
            vocabulary.forEach { before ->
                vocabulary.forEach { after ->
                    if (before.degree == after.degree && before.rootAlteration == after.rootAlteration) return@forEach
                    if (
                        after.secondaryFamily != null &&
                        !SchoenbergSecondaryDominantChapter.allowsPreparation(before, after, key, triads)
                    ) return@forEach
                    if (
                        before.secondaryFamily != null &&
                        after.augmentedSixthFamily != null
                    ) return@forEach
                    if (
                        before.secondaryFamily != null &&
                        !SchoenbergSecondaryDominantChapter.allowsResolution(before, after, key, triads)
                    ) return@forEach
                    if (
                        before.augmentedSixthFamily != null &&
                        !SchoenbergIntegratedTransitionPolicy.allowsAugmentedSixthResolution(before, after)
                    ) return@forEach
                    val beforeTarget = targets.getValue(before)
                    val afterTarget = targets.getValue(after)
                    val beforeTones = beforeTarget.sonority.pitchClasses
                    val afterTones = afterTarget.sonority.pitchClasses
                    if (
                        before.secondaryFamily == null &&
                        after.secondaryFamily == null &&
                        before.augmentedSixthFamily == null &&
                        after.augmentedSixthFamily == null &&
                        beforeTones.none { it in afterTones }
                    ) return@forEach
                    if (!transitionWritable(beforeTarget, afterTarget)) add(Pairing(before, after))
                }
            }
        }
    }

    private val referenceProgram by lazy {
        SchoenbergIntegratedTechTree.program(
            key = key,
            continuationChordCount = 2,
            treatmentIds = SchoenbergHarmonicTreatments.integratedFrontierTreatments,
            progression = null,
        )
    }

    private fun transitionWritable(
        before: ChordTarget,
        after: ChordTarget,
    ): Boolean {
        val domains = listOf(before, after).map { SlotDomain(listOf(it)) }
        val pairNeighbors = referenceProgram.chordToneNeighbors.map { requirement ->
            val pinnedSourceSlot = when (requirement.direction) {
                ChordToneNeighborDirection.NEXT -> 0
                ChordToneNeighborDirection.PREVIOUS -> 1
            }
            requirement.copy(sourceSlot = pinnedSourceSlot)
        }
        val program = ConstraintProgram.fromRequirements(
            key = key,
            slotDomains = domains,
            configuration = com.mecon.theory.constraint.ConstraintRequirementConfiguration(
                ruleProfile = SchoenbergCommonToneExercises.SCHOENBERG_PROFILE,
                toneCompleteness = referenceProgram.toneCompleteness,
                avoidDoublings = referenceProgram.avoidDoublings.filter { it.slot in 0..1 },
                adjacentCommonTones = referenceProgram.adjacentCommonTones,
                chordToneNeighbors = pairNeighbors,
                ruleModules = emptyList(),
                includeDerivedTextbookConstraints = false,
                searchConfig = SearchConfig(maxResults = 1, beamWidth = 512),

            ),
        )
        return ConstraintProgramSolver.solve(program).isNotEmpty()
    }

    private val SchoenbergSymbolicChord.token: String get() = transitionToken()

    private fun render(pairs: List<Pairing>): String = buildString {
        appendLine("# 勋伯格小调综合练习：四部写作无法实现的相邻和弦进行（自动生成，请勿手改）")
        appendLine("# 重刷：./gradlew.bat :theory:schoenbergForbiddenTransitionTest --tests \"*SchoenbergMinorForbiddenTransitionGenerator*\" -Pschoenberg.forbidden.write=true")
        appendLine("# 判据：从小调综合练习程序自身提取的硬约束（一般四部规则 + 完整性/重复/共同音/减增预备解决/旋律进行）下，两拍相邻无任何合规四部实现。")
        appendLine("# 规则自动继承练习：新增 typed 规则后重刷即生效。判据在小调内调性无关（a 小调探测）。")
        appendLine("# 格式：<度数/性质/规模/转位> => <度数/性质/规模/转位>    # 人类可读罗马标记")
        appendLine("#")
        pairs.forEach { (before, after) ->
            appendLine("${before.token} => ${after.token}    # ${humanLabel(before)} => ${humanLabel(after)}")
        }
    }

    private fun humanLabel(chord: SchoenbergSymbolicChord): String {
        val base = ROMAN[chord.degree - 1]
        val quality = when (chord.quality) {
            ChordQuality.MAJOR, ChordQuality.MAJOR7 -> base
            ChordQuality.MINOR, ChordQuality.MINOR7, ChordQuality.MINOR_MAJOR7 -> base.lowercase()
            ChordQuality.DIMINISHED, ChordQuality.DIMINISHED7, ChordQuality.HALF_DIMINISHED7 -> base.lowercase() + "°"
            ChordQuality.AUGMENTED, ChordQuality.AUGMENTED7 -> base + "+"
            else -> base
        }
        val figure = if (chord.arity == ChordArity.SEVENTH) {
            when (chord.seventhPosition) {
                TextbookSeventhPosition.ROOT_POSITION, null -> "7"
                TextbookSeventhPosition.FIRST_INVERSION -> "65"
                TextbookSeventhPosition.SECOND_INVERSION -> "43"
                TextbookSeventhPosition.THIRD_INVERSION -> "42"
            }
        } else {
            when (chord.position) {
                TextbookTriadPosition.ROOT_POSITION -> ""
                TextbookTriadPosition.FIRST_INVERSION -> "6"
                TextbookTriadPosition.SECOND_INVERSION -> "64"
            }
        }
        return quality + figure
    }

    private companion object {
        const val OUTPUT_PATH = "src/jvmMain/resources/schoenberg/forbidden-transitions-minor.txt"
        val ROMAN = listOf("I", "II", "III", "IV", "V", "VI", "VII")
    }
}
