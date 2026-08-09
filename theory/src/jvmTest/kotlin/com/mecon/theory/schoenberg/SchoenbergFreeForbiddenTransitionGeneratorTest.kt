package com.mecon.theory.schoenberg

import com.mecon.theory.Key
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.SearchConfig
import com.mecon.theory.constraint.ChordToneNeighborDirection
import com.mecon.theory.constraint.ChordTarget
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.ConstraintProgramSolver
import com.mecon.theory.constraint.ConstraintRequirementConfiguration
import com.mecon.theory.constraint.SlotDomain
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Free-treatment forbidden tables are projected from the free chapter's typed hard rules.
 * Unlike the earlier common-tone stages, the vocabulary can connect without a common tone,
 * so every different-root directed pair is probed.
 */
class SchoenbergFreeForbiddenTransitionGeneratorTest {
    @Test
    fun freeForbiddenTransitionTablesStayFresh() {
        listOf(false, true).forEach { minor ->
            val key = Key.fromKeySignatureFifths(
                0,
                if (minor) KeySignatureMode.MINOR else KeySignatureMode.MAJOR,
            )
            val computed = computeForbiddenTransitions(key)
            val file = File(outputPath(minor))
            if (System.getProperty("schoenberg.forbidden.write") == "true") {
                file.parentFile?.mkdirs()
                file.writeText(render(computed, minor))
                println("SCHOENBERG_FREE_FORBIDDEN ${if (minor) "minor" else "major"} " +
                    "wrote ${computed.size} transitions -> ${file.absolutePath}")
            } else {
                val committed = SchoenbergForbiddenTransitions.parse(
                    file.takeIf { it.exists() }?.readText(),
                )
                assertEquals(
                    computed.map { it.first.transitionToken() to it.second.transitionToken() }.toSet(),
                    committed,
                    "勋伯格自由处理禁忌表已过期。重刷：./gradlew.bat :theory:schoenbergForbiddenTransitionTest " +
                        "--tests \"*SchoenbergFreeForbiddenTransitionGenerator*\" " +
                        "-Pschoenberg.forbidden.write=true",
                )
                assertEquals(
                    committed,
                    SchoenbergForbiddenTransitions.parse(
                        loadSchoenbergForbiddenTransitionResource(
                            minor,
                            SchoenbergForbiddenTransitionProfile.FREE,
                        )
                    ),
                    "运行时自由处理禁忌表资源与源文件不一致。",
                )
            }
        }
    }

    private data class Pairing(
        val first: SchoenbergSymbolicChord,
        val second: SchoenbergSymbolicChord,
    )

    private fun computeForbiddenTransitions(key: Key): List<Pairing> {
        val triads = exerciseTriads(key, includeLeadingTriad = true)
        val vocabulary = SchoenbergIntegratedTechTree
            .vocabularyForStage(SchoenbergCommonToneExercises.FREER_SEVENTH_LEADING_EXERCISE_ID, key)
            .sortedWith(compareBy({ it.degree }, { it.arity }, { it.seventhPosition?.ordinal ?: it.position.ordinal }))
        val referenceProgram = SchoenbergIntegratedTechTree.program(
            key = key,
            continuationChordCount = 2,
            treatmentIds = SchoenbergHarmonicTreatments.integratedDiatonicTreatments,
            progression = null,
            requireAdjacentCommonTone = false,
            dissonanceTreatment = SchoenbergDissonanceTreatment.FREER,
        )
        val targets = vocabulary.associateWith { it.toTarget(triads) }
        return buildList {
            vocabulary.forEach { before ->
                vocabulary.forEach { after ->
                    if (before.degree == after.degree) return@forEach
                    if (!transitionWritable(
                            key,
                            targets.getValue(before),
                            targets.getValue(after),
                            referenceProgram,
                        )
                    ) {
                        add(Pairing(before, after))
                    }
                }
            }
        }
    }

    private fun transitionWritable(
        key: Key,
        before: ChordTarget,
        after: ChordTarget,
        referenceProgram: ConstraintProgram,
    ): Boolean {
        val domains = listOf(before, after).map { SlotDomain(listOf(it)) }
        val pairNeighbors = referenceProgram.chordToneNeighbors.map { requirement ->
            requirement.copy(
                sourceSlot = when (requirement.direction) {
                    ChordToneNeighborDirection.NEXT -> 0
                    ChordToneNeighborDirection.PREVIOUS -> 1
                }
            )
        }
        val program = ConstraintProgram.fromRequirements(
            key = key,
            slotDomains = domains,
            configuration = ConstraintRequirementConfiguration(
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

    private fun render(pairs: List<Pairing>, minor: Boolean): String = buildString {
        appendLine("# 勋伯格${if (minor) "小调" else "大调"}自由处理：四部写作无法实现的相邻和弦进行（自动生成，请勿手改）")
        appendLine("# 重刷：./gradlew.bat :theory:schoenbergForbiddenTransitionTest --tests \"*SchoenbergFreeForbiddenTransitionGenerator*\" -Pschoenberg.forbidden.write=true")
        appendLine("# 判据：自由处理 program 的一般四部规则、完整性及仍保留的 typed 硬旋律规则；不含严格七音/减五度预备解决。")
        appendLine("# 格式：<度数/性质/规模/转位> => <度数/性质/规模/转位>")
        appendLine("#")
        pairs.forEach { (before, after) ->
            appendLine("${before.transitionToken()} => ${after.transitionToken()}")
        }
    }

    private fun outputPath(minor: Boolean): String =
        "src/jvmMain/resources/schoenberg/forbidden-transitions-free${if (minor) "-minor" else ""}.txt"
}
