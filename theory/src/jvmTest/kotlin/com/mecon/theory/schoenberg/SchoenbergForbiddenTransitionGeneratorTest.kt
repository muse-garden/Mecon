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
 * 勋伯格「禁忌相邻进行」表的生成器兼时效守卫。
 *
 * - 重刷禁忌表（新增/修改一类勋伯格规则后必做）：
 *   `./gradlew.bat :theory:schoenbergForbiddenTransitionTest --tests "*SchoenbergForbiddenTransitionGenerator*" -Pschoenberg.forbidden.write=true`
 *   会把结果写到 [OUTPUT_PATH] 并在控制台打印人类可读清单。
 * - 按需校验：运行独立任务但不传写开关，断言已提交的表与当前规则重新探测的结果一致。
 *
 * 判据：只用一般四部写作规则（平行五/八度、声部间距、音域、交叉——见 §4「勋伯格模式关掉具体和弦规则」），
 * 把两个和弦放在相邻两拍、各自和弦音完整，若求解器找不到任何合规四部实现，即该有向进行为禁忌。判据与调性无关，
 * 故在 C 大调探测一次即可。
 */
class SchoenbergForbiddenTransitionGeneratorTest {
    private val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
    private val triads by lazy { exerciseTriads(key, includeLeadingTriad = true) }

    @Test
    fun forbiddenTransitionTableStaysFresh() {
        val writeMode = System.getProperty("schoenberg.forbidden.write") == "true"
        val computed = computeForbiddenTransitions(includeChapterDiagnosis = writeMode)
        val file = File(OUTPUT_PATH)
        if (writeMode) {
            file.parentFile?.mkdirs()
            file.writeText(render(computed))
            println("SCHOENBERG_FORBIDDEN wrote ${computed.size} transitions -> ${file.absolutePath}")
            computed.forEach { pairing ->
                println(
                    "  ${humanLabel(pairing.first)}  =>  ${humanLabel(pairing.second)}" +
                        "    chapters=${pairing.chapterIds.joinToString(",")} " +
                        "(${pairing.first} => ${pairing.second})"
                )
            }
        } else {
            val committed = SchoenbergForbiddenTransitions.parse(
                file.takeIf { it.exists() }?.readText(),
            )
            assertEquals(
                computed.map { it.first.token to it.second.token }.toSet(),
                committed,
                "勋伯格禁忌进行表已过期。重刷：./gradlew.bat :theory:schoenbergForbiddenTransitionTest " +
                    "--tests \"*SchoenbergForbiddenTransitionGenerator*\" -Pschoenberg.forbidden.write=true",
            )
            // 运行时经 classpath 资源读取该表，确认打包路径可达且解析结果与源文件一致。
            assertEquals(
                committed,
                SchoenbergForbiddenTransitions.parse(loadSchoenbergForbiddenTransitionResource(minor = false)),
                "运行时 classpath 资源 forbidden-transitions.txt 与源文件不一致（打包路径问题？）。",
            )
        }
    }

    private data class Pairing(
        val first: SchoenbergSymbolicChord,
        val second: SchoenbergSymbolicChord,
        val chapterIds: Set<String>,
    )

    /** 全综合练习词汇（含四六/七和弦，减三导和弦不含四六）里，所有能相邻却写不出四部的有向对。 */
    private fun computeForbiddenTransitions(includeChapterDiagnosis: Boolean): List<Pairing> {
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
                    // 只探测枚举本可产出的相邻对：不同根音、至少一个共同音（其余对枚举已用和声规则排除）。
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
                    if (includeChapterDiagnosis) {
                        val diagnosis = transitionDiagnosis(beforeTarget, afterTarget)
                        if (!diagnosis.writable) {
                            add(Pairing(before, after, diagnosis.implicatedChapterIds))
                        }
                    } else if (!transitionWritable(beforeTarget, afterTarget)) {
                        add(Pairing(before, after, emptySet()))
                    }
                }
            }
        }
    }

    /**
     * 综合练习（含四六/七和弦的最全阶段）的规则装配来源，探测器由此**自动**继承其全部规则族：
     * 完整性、重复避免、共同音、导和弦（及未来任何）邻接预备/解决。新增/修改综合练习规则后重刷即生效，
     * 无需手改探测器。以 `progression = null` 取开放域装配（其 typed 规则族与具体进行无关）。
     */
    private val referenceProgram by lazy {
        SchoenbergIntegratedTechTree.program(
            key = key,
            continuationChordCount = 2,
            treatmentIds = SchoenbergHarmonicTreatments.integratedFrontierTreatments,
            progression = null,
        )
    }

    /**
     * 相邻两拍 [before]→[after]，在综合练习的硬约束下能否写出至少一个四部实现。约束**从练习程序自身提取**
     * （见 [referenceProgram]），不再手列——故导和弦五音预备/解决这类跨和弦规则会让 `vii°6/4 → iii6`
     * （五音在低音无法下行到 E）等按**转位**成为禁忌，且任何新增邻接规则同样自动生效。
     *
     * 邻接（关系）规则投影到 before→after 结点：解决(NEXT)钉在 before(槽0)、预备(PREVIOUS)钉在 after(槽1)，
     * 只测“结点内可判定”的部分；需要窗外槽（before 的前驱 / after 的后继）的部分留给相应的其他结点。
     */
    private fun transitionDiagnosis(
        before: ChordTarget,
        after: ChordTarget,
    ): SchoenbergForbiddenTransitionDiagnosis =
        SchoenbergForbiddenTransitionAnalyzer.diagnose(transitionProgram(before, after))

    private fun transitionWritable(
        before: ChordTarget,
        after: ChordTarget,
    ): Boolean = ConstraintProgramSolver.solve(transitionProgram(before, after)).isNotEmpty()

    private fun transitionProgram(
        before: ChordTarget,
        after: ChordTarget,
    ): ConstraintProgram {
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
        return program
    }

    private val SchoenbergSymbolicChord.token: String get() = transitionToken()

    private fun render(pairs: List<Pairing>): String = buildString {
        appendLine("# 勋伯格综合练习：四部写作无法实现的相邻和弦进行（自动生成，请勿手改）")
        appendLine("# 重刷：./gradlew.bat :theory:schoenbergForbiddenTransitionTest --tests \"*SchoenbergForbiddenTransitionGenerator*\" -Pschoenberg.forbidden.write=true")
        appendLine("# 判据：从综合练习程序自身提取的硬约束（一般四部规则 + 完整性/重复/共同音/邻接预备解决）下，两拍相邻无任何合规四部实现。")
        appendLine("# 规则自动继承练习：新增 typed 规则后重刷即生效。转位敏感：如 vii°6/4 => iii6 被收录。判据与调性无关（C 大调探测）。")
        appendLine("# 格式：<度数/性质/规模/转位> => <度数/性质/规模/转位>    # 人类可读罗马标记")
        appendLine("#")
        pairs.forEach { pairing ->
            appendLine(
                "${pairing.first.token} => ${pairing.second.token}    # " +
                    "${humanLabel(pairing.first)} => ${humanLabel(pairing.second)}; " +
                    "chapters=${pairing.chapterIds.joinToString(",")}"
            )
        }
    }

    private fun humanLabel(chord: SchoenbergSymbolicChord): String {
        val base = ROMAN[chord.degree - 1]
        val quality = when (chord.quality) {
            ChordQuality.MAJOR, ChordQuality.MAJOR7 -> base
            ChordQuality.MINOR, ChordQuality.MINOR7, ChordQuality.MINOR_MAJOR7 -> base.lowercase()
            ChordQuality.DIMINISHED, ChordQuality.DIMINISHED7, ChordQuality.HALF_DIMINISHED7 -> base.lowercase() + "°"
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
        const val OUTPUT_PATH = "src/jvmMain/resources/schoenberg/forbidden-transitions.txt"
        val ROMAN = listOf("I", "II", "III", "IV", "V", "VI", "VII")
    }
}
