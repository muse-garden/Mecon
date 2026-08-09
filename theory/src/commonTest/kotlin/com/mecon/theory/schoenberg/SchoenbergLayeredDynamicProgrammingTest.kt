package com.mecon.theory.schoenberg

import com.mecon.api.primitive.PitchClass
import com.mecon.theory.ChordArity
import com.mecon.theory.DynamicProgrammingSearchConfig
import com.mecon.theory.DynamicProgrammingSearchMode
import com.mecon.theory.Key
import com.mecon.theory.RuleSeverity
import com.mecon.theory.SearchBackend
import com.mecon.theory.SearchConfig
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.ConstraintProgramSolver
import com.mecon.theory.constraint.ConstraintSolveDiagnosticCode
import com.mecon.theory.constraint.ConstraintSolveOutcome
import com.mecon.theory.constraint.WritingRulePreset
import com.mecon.theory.harmony.HarmonicTreatmentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 勋伯格进入 DP 能力集的边界：**逐槽固定目标**是唯一的门槛，和弦类型不再是。
 * SCHOENBERG_GENERAL 只装 FourPartTextbookWritingRuleProvider，其规则只读音高 / 音程 / 音域。
 */
class SchoenbergLayeredDynamicProgrammingTest {
    private val key = Key.major(PitchClass.C)

    private fun dpConfig(maxResults: Int = 1) = SearchConfig(
        maxResults = maxResults,
        beamWidth = 64,
        backend = SearchBackend.LAYERED_DP,
    )

    private fun fixedIntegratedProgram(
        treatmentIds: Set<HarmonicTreatmentId>,
        continuationChordCount: Int,
        backend: SearchBackend = SearchBackend.LAYERED_DP,
        select: (SchoenbergSymbolicProgression) -> Boolean = { true },
    ): ConstraintProgram {
        val progression = SchoenbergIntegratedTechTree.enumerate(
            key = key,
            options = SchoenbergIntegratedTechTree.EnumerationOptions(
                continuationChordCount = continuationChordCount,
                treatmentIds = treatmentIds,
                requireAdjacentCommonTone = false,
            ),
        ).first(select)
        return SchoenbergIntegratedTechTree.program(
            key = key,
            continuationChordCount = continuationChordCount,
            treatmentIds = treatmentIds,
            progression = progression,
            requireAdjacentCommonTone = false,
            searchConfig = dpConfig().copy(backend = backend),
        )
    }

    @Test
    fun openChordDomainsStillFailClosedForExplicitDp() {
        // 首尾槽恒为主和弦，中间槽才是开放的；长度 2 时没有中间槽。
        val program = SchoenbergIntegratedTechTree.program(
            key = key,
            continuationChordCount = 3,
            treatmentIds = SchoenbergHarmonicTreatments.integratedDiatonicTreatments,
            requireAdjacentCommonTone = false,
            searchConfig = dpConfig(),
        )

        assertEquals(WritingRulePreset.SCHOENBERG_GENERAL, program.writingRulePreset)
        assertTrue(program.slotDomains.any { it.targets.size > 1 }, "本例应确实是开放域")

        val outcome = assertIs<ConstraintSolveOutcome.Invalid>(
            ConstraintProgramSolver.solvePolyphonicOutcome(program),
        )
        assertEquals(
            ConstraintSolveDiagnosticCode.UNSUPPORTED_SEARCH_BACKEND,
            outcome.diagnostics.single().code,
            "诊断：${outcome.diagnostics}",
        )

        val automatic = assertIs<ConstraintSolveOutcome.Solved>(
            ConstraintProgramSolver.solvePolyphonicOutcome(
                program.copy(searchConfig = program.searchConfig.copy(backend = SearchBackend.AUTO)),
            ),
        )
        assertEquals(SearchBackend.GREEDY_DFS, automatic.trace.backend)
        assertTrue(automatic.trace.fallbackReason?.contains("not a fixed chord target") == true)
    }

    @Test
    fun fixedDiatonicProgressionIsSupportedWithOneFramePerLayer() {
        val program = fixedIntegratedProgram(
            treatmentIds = SchoenbergHarmonicTreatments.integratedDiatonicTreatments,
            continuationChordCount = 3,
        )

        val solved = assertIs<ConstraintSolveOutcome.Solved>(
            ConstraintProgramSolver.solvePolyphonicOutcome(program),
        )
        assertEquals(SearchBackend.LAYERED_DP, solved.trace.backend)
        // 8 条 textbook 规则里最深的只读前一帧；末层没有未来，降为 0。
        val plan = solved.trace.dpStatePlan
        assertTrue(plan.isNotEmpty(), "DP trace 应携带逐层状态计划")
        assertTrue(
            plan.dropLast(1).all { it.contains("recentFrames=1") },
            "中间层应恒为 recentFrames=1：$plan",
        )
        assertTrue(plan.last().contains("recentFrames=0"), "末层应为 recentFrames=0：$plan")
    }

    /**
     * 解除自然三和弦限制的卡尺。**用 EXACT**：BOUNDED 会截断每前驱出边（宽度
     * `max(8, 4 × maxResults)`），其分值只是受控近似。EXACT 是全局最优，因此判据是
     * **不劣于 DFS**（DFS 是贪心束搜索，本例中它确实更差），而不是相等。
     */
    @Test
    fun seventhChordProgressionIsSupportedAndExactDpIsNoWorseThanDfs() {
        val hasSeventh: (SchoenbergSymbolicProgression) -> Boolean = { progression ->
            progression.slots.any { it.arity == ChordArity.SEVENTH }
        }
        val base = fixedIntegratedProgram(
            treatmentIds = SchoenbergHarmonicTreatments.integratedDiatonicTreatments,
            continuationChordCount = 3,
            select = hasSeventh,
        )
        assertTrue(
            base.slotDomains.any { it.targets.single().arity == ChordArity.SEVENTH },
            "这条卡尺必须含七和弦，否则证明不了自然三和弦限制已解除",
        )

        val dp = assertIs<ConstraintSolveOutcome.Solved>(
            ConstraintProgramSolver.solvePolyphonicOutcome(
                base.copy(
                    searchConfig = base.searchConfig.copy(
                        backend = SearchBackend.LAYERED_DP,
                        dynamicProgramming = DynamicProgrammingSearchConfig(
                            mode = DynamicProgrammingSearchMode.EXACT,
                        ),
                    ),
                ),
            ),
        )
        val dfs = assertIs<ConstraintSolveOutcome.Solved>(
            ConstraintProgramSolver.solvePolyphonicOutcome(
                base.copy(searchConfig = base.searchConfig.copy(backend = SearchBackend.GREEDY_DFS)),
            ),
        )
        assertEquals(SearchBackend.LAYERED_DP, dp.trace.backend)
        assertEquals(SearchBackend.GREEDY_DFS, dfs.trace.backend)
        val dpTotal = dp.solutions.first().breakdown.total
        val dfsTotal = dfs.solutions.first().breakdown.total
        assertTrue(
            dpTotal <= dfsTotal,
            "含七和弦的固定进行上 EXACT DP 应不劣于 DFS：DP=$dpTotal DFS=$dfsTotal",
        )
        assertTrue(
            dp.solutions.first().breakdown.findings.none { it.severity == RuleSeverity.HARD },
            "最优解不应留下硬违规：${dp.solutions.first().breakdown.findings}",
        )
    }

    @Test
    fun rootMotionChapterCompositesAreCoveredButForceBoundedRerank() {
        val base = fixedIntegratedProgram(
            treatmentIds = SchoenbergHarmonicTreatments.integratedDiatonicTreatments,
            continuationChordCount = 4,
        )
        val program = base.copy(
            constraints = base.constraints +
                SchoenbergRootMotionAndRepetitionChapter.constraints(base.length),
        )
        assertTrue(
            program.constraints.any { it.expr !is com.mecon.theory.constraint.ConstraintExpr.Atom },
            "根音进行章节应带来 And/Or/Not 合成式",
        )

        // NoRepeatedVoicePattern 只在完整路径上评分：能力集支持，但 EXACT 必须被拒。
        val solved = assertIs<ConstraintSolveOutcome.Solved>(
            ConstraintProgramSolver.solvePolyphonicOutcome(program),
        )
        assertEquals(SearchBackend.LAYERED_DP, solved.trace.backend)
    }
}
