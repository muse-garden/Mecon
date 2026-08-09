package com.mecon.exploration

import com.mecon.theory.textbook.DominantSeventhRules
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 七和弦收进 S2：便捷请求经 `fromConvenience` → SEVENTH-arity `ConstraintProgramSpec` → 通用
 * `ConstraintProgramSolver` 求解（docs/theory/roadmap.md）。行为等价卡尺同增量一——目标 finding 命中 /
 * 候选非空 / 无解诊断一致，非逐候选字节相等（selectedRules 编译为 REQUIRE_INDICATION，返回候选必命中）。
 */
class ConstraintProgramSeventhTest {

    private fun solveViaProgram(convenience: RuleExampleRequest): SolveResult {
        val spec = ConstraintProgramCompiler.fromConvenience(convenience)
        return SolverEngine.solve(
            SolveRequest(
                program = spec,
                key = convenience.key,
                policyId = "free-triads",
                search = convenience.search,
            )
        )
    }

    @Test
    fun seventhConvenienceCompilesToSeventhArity() {
        val convenience = RuleExampleRequest(
            from = DegreeSpec(2),
            to = DegreeSpec(5),
            selectedRules = listOf(DominantSeventhRules.SUPERTONIC_TO_DOMINANT.value),
        )
        val spec = ConstraintProgramCompiler.fromConvenience(convenience)
        val chordAts = spec.slotConstraints.filterIsInstance<ChordAtSpec>()
        assertTrue(
            chordAts.any { it.arity == ChordAritySpec.SEVENTH },
            "七和弦场景应落成 SEVENTH-arity 槽：${chordAts.map { it.slot to it.arity }}",
        )
    }

    @Test
    fun solveSupertonicSeventhViaConstraintProgramHitsTargetFinding() {
        val convenience = RuleExampleRequest(
            from = DegreeSpec(2),
            to = DegreeSpec(5),
            selectedRules = listOf(DominantSeventhRules.SUPERTONIC_TO_DOMINANT.value),
            search = SearchSpec(maxResults = 2, beamWidth = 128),
        )
        val result = solveViaProgram(convenience)

        assertTrue(result.output.diagnostics.isEmpty(), "诊断：${result.output.diagnostics}")
        assertTrue(result.output.candidates.isNotEmpty(), "上主七经约束程序路径应有候选")
        assertTrue(
            result.output.candidates.all { candidate ->
                candidate.findings.any { it.ruleId == DominantSeventhRules.SUPERTONIC_TO_DOMINANT.value }
            },
            "候选都应命中目标 finding",
        )
    }

    @Test
    fun solveRootV7ToTonicOmittedFifthViaConstraintProgram() {
        // 属七章核心：V7(省五)-I 完全。三和弦解决和弦以 triadSonority 走七和弦章（不触发三章 missing-chord-tone）。
        val convenience = RuleExampleRequest(
            from = DegreeSpec(5),
            to = DegreeSpec(1),
            selectedRules = listOf(DominantSeventhRules.ROOT_V7_TO_I_OMITTED_FIFTH.value),
            search = SearchSpec(maxResults = 2, beamWidth = 128),
        )
        val result = solveViaProgram(convenience)

        assertTrue(result.output.diagnostics.isEmpty(), "诊断：${result.output.diagnostics}")
        assertTrue(result.output.candidates.isNotEmpty(), "V7-I 省五经约束程序路径应有候选")
        assertTrue(
            result.output.candidates.all { candidate ->
                candidate.findings.any { it.ruleId == DominantSeventhRules.ROOT_V7_TO_I_OMITTED_FIFTH.value }
            },
        )
    }

    @Test
    fun solveCircleOfFifthsVariantsViaConstraintProgram() {
        // 五度圈四变体 × 大小调，经约束程序路径求解（与 SolverApiTest 的便捷路径回归对齐）。
        val variants = listOf(
            DominantSeventhRules.CIRCLE_OF_FIFTHS_SEVENTHS,
            DominantSeventhRules.CIRCLE_ROOT_POSITION_ALTERNATION,
            DominantSeventhRules.CIRCLE_FIRST_THIRD_INVERSION,
            DominantSeventhRules.CIRCLE_SECOND_ROOT_INVERSION,
        )
        for (mode in listOf(KeyModeSpec.MAJOR, KeyModeSpec.MINOR)) {
            for (rule in variants) {
                val convenience = RuleExampleRequest(
                    key = KeySpec(fifths = 0, mode = mode),
                    from = DegreeSpec(4),
                    to = DegreeSpec(7),
                    selectedRules = listOf(rule.value),
                    search = SearchSpec(maxResults = 1, beamWidth = 128),
                )
                val result = solveViaProgram(convenience)
                assertTrue(
                    result.output.candidates.isNotEmpty(),
                    "$mode ${rule.value} 程序路径应有候选，诊断：${result.output.diagnostics}",
                )
                assertTrue(
                    result.output.candidates.all { candidate ->
                        candidate.findings.any { it.ruleId == rule.value }
                    },
                    "$mode ${rule.value} 候选应命中目标 finding",
                )
            }
        }
    }
}
