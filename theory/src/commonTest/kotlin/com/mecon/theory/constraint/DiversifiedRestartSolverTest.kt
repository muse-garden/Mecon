package com.mecon.theory.constraint

import com.mecon.theory.DiversitySearchConfig
import com.mecon.theory.Key
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.NaturalTriad
import com.mecon.theory.NaturalTriads
import com.mecon.theory.SearchConfig
import com.mecon.theory.WritingSearchTraceEventKind
import com.mecon.theory.mutationSlotWeight
import com.mecon.theory.textbook.TextbookTriadPosition
import com.mecon.theory.textbook.TextbookTriadTarget
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 多样化重启求解器验收（docs/theory/diverse-search.md §10）。所有练习都经 ConstraintProgramSolver
 * → GreedyDepthFirstSolver，因此在此层验证即覆盖全部教材练习。
 */
class DiversifiedRestartSolverTest {

    private val cMajor: Key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)

    private fun triad(degree: Int): NaturalTriad =
        NaturalTriads.inKey(cMajor).first { it.degree == degree }

    private fun rootSlot(degree: Int): SlotDomain =
        SlotDomain(listOf(TextbookTriadTarget(triad(degree), TextbookTriadPosition.ROOT_POSITION)))

    private fun program(
        diversify: Boolean,
        seed: Long = 1L,
        maxResults: Int = 4,
        degrees: List<Int> = listOf(1, 4, 5, 1),
    ): ConstraintProgram =
        ConstraintProgram.fromRequirements(
            key = cMajor,
            slotDomains = degrees.map { rootSlot(it) },
                configuration = com.mecon.theory.constraint.ConstraintRequirementConfiguration(
                searchConfig = SearchConfig(
                    maxResults = maxResults,
                    beamWidth = 128,
                    diversity = DiversitySearchConfig(enabled = diversify, seed = seed),
                ),

            ),
        )

    private fun solve(program: ConstraintProgram): List<ConstraintSolution> =
        ConstraintProgramSolver.solve(program)

    // ---- 结构提取工具 ---------------------------------------------------------------------

    private fun pitches(solution: ConstraintSolution): List<List<Int>> =
        solution.voicings.map { v ->
            listOf(v.soprano.midiNumber, v.alto.midiNumber, v.tenor.midiNumber, v.bass.midiNumber)
        }

    private fun slotKeys(solution: ConstraintSolution): List<String> =
        solution.voicings.map { v ->
            "${v.target.degree}:${v.target.arity}:" +
                listOf(v.soprano, v.alto, v.tenor, v.bass).joinToString(",") { it.pitchClass.value.toString() }
        }

    private fun groupKey(solution: ConstraintSolution): String = slotKeys(solution).joinToString("|")

    private fun changedSlots(a: ConstraintSolution, b: ConstraintSolution): Int {
        val ka = slotKeys(a)
        val kb = slotKeys(b)
        return ka.indices.count { ka[it] != kb.getOrNull(it) }
    }

    private fun firstDivergence(a: ConstraintSolution, b: ConstraintSolution): Int {
        val ka = slotKeys(a)
        val kb = slotKeys(b)
        return ka.indices.firstOrNull { ka[it] != kb.getOrNull(it) } ?: ka.size
    }

    // ---- §10.1 首解稳定 -------------------------------------------------------------------

    @Test
    fun firstCandidateMatchesDeterministicSolver() {
        val deterministic = solve(program(diversify = false)).first()
        val diversified = solve(program(diversify = true, seed = 7)).first()
        assertEquals(
            pitches(deterministic),
            pitches(diversified),
            "多样化首候选必须与确定性贪心 DFS 首解一致",
        )
    }

    @Test
    fun maxResultsOneDoesNotEnterRestart() {
        val single = solve(program(diversify = true, maxResults = 1))
        assertEquals(1, single.size)
        assertEquals(
            pitches(solve(program(diversify = false, maxResults = 1)).first()),
            pitches(single.first()),
        )
    }

    // ---- §10.2 合法性 ---------------------------------------------------------------------

    @Test
    fun everyResultIsFreeOfHardViolations() {
        solve(program(diversify = true, seed = 3)).forEach { solution ->
            assertFalse(solution.breakdown.hasHardViolation, "多样化输出不得含 HARD violation")
        }
    }

    // ---- §10.3 可复现 ---------------------------------------------------------------------

    @Test
    fun sameSeedReproducesResultsAndOrder() {
        val first = solve(program(diversify = true, seed = 5))
        val second = solve(program(diversify = true, seed = 5))
        assertEquals(first.map(::pitches), second.map(::pitches), "相同 seed 必须复现候选与顺序")
    }

    // ---- §10.4 多样性门槛 -----------------------------------------------------------------

    @Test
    fun anyTwoResultsMeetSlotDistanceThreshold() {
        val results = solve(program(diversify = true, seed = 4))
        assertTrue(results.size >= 2, "该进行的候选空间应足以产出多个不同解")
        val totalSlots = 4
        val minChangedSlots = ceil(0.35 * totalSlots).toInt()
        for (i in results.indices) {
            for (j in i + 1 until results.size) {
                assertTrue(
                    changedSlots(results[i], results[j]) >= minChangedSlots,
                    "解 $i 与 $j 的槽距离不足 $minChangedSlots",
                )
            }
        }
    }

    // ---- §10.5 分叉位置 -------------------------------------------------------------------

    @Test
    fun subsequentResultsForkBeforeTail() {
        val results = solve(program(diversify = true, seed = 6))
        assertTrue(results.size >= 2)
        val seed = results.first()
        val minFork = (1 until results.size).minOf { firstDivergence(seed, results[it]) }
        assertTrue(minFork < 3, "后续解不应只在末端分叉（minFork=$minFork）")
    }

    // ---- §10.6 窄空间不返回近重复 ---------------------------------------------------------

    @Test
    fun resultsAreAllStructurallyDistinct() {
        val results = solve(program(diversify = true, seed = 2))
        assertEquals(
            results.size,
            results.map(::groupKey).distinct().size,
            "多样化不得返回近重复候选",
        )
    }

    // ---- §10.7 终止性 + trace 事件 --------------------------------------------------------

    @Test
    fun visitedNodesStayWithinBudgetAndSeedIsTraced() {
        val trace = ConstraintProgramSolver.trace(program(diversify = true, seed = 1)).trace
        assertTrue(trace.visitedNodes <= trace.nodeBudget, "访问节点不得超过节点预算")
        assertTrue(
            trace.entries.any { it.kind == WritingSearchTraceEventKind.SEED_SOLUTION },
            "多样化 trace 应记录确定性首解 SEED_SOLUTION",
        )
    }

    @Test
    fun highPenaltyChordPrioritizesItsPredecessorAsMutationSlot() {
        val diversity = DiversitySearchConfig(
            enabled = true,
            earlyMutationBias = 1.0,
            penaltyMutationBias = 2.0,
        )
        val beforeHighPenalty = mutationSlotWeight(
            mutationSlot = 3,
            totalSlots = 8,
            attempts = 0,
            nextSlotPenalty = 24.0,
            maxPenalty = 24.0,
            diversity = diversity,
        )
        val earliestUnpenalized = mutationSlotWeight(
            mutationSlot = 0,
            totalSlots = 8,
            attempts = 0,
            nextSlotPenalty = 0.0,
            maxPenalty = 24.0,
            diversity = diversity,
        )

        assertTrue(
            beforeHighPenalty > earliestUnpenalized,
            "高扣分和弦的前一槽应压过单纯的早期位置偏置",
        )
    }
}
