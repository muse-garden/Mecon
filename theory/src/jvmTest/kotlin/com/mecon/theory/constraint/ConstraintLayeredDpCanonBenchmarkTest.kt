package com.mecon.theory.constraint

import com.mecon.api.primitive.NoteName
import com.mecon.api.primitive.PitchClass
import com.mecon.theory.Key
import com.mecon.theory.SearchBackend
import com.mecon.theory.SearchConfig
import com.mecon.theory.DynamicProgrammingSearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.SpelledPitchClass
import com.mecon.theory.TonalContext
import com.mecon.theory.TonalPlan
import com.mecon.theory.TonalSpan
import com.mecon.theory.VoicePlan
import com.mecon.theory.WritingSearchTrace
import com.mecon.theory.WritingSearchTraceEventKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

/**
 * 真实规模基准：卡农进行 I-V-vi-iii-IV-I-IV-V-I，标准 SATB 音域、全自然音三和弦词汇。
 *
 * 审计回归：锁定状态合并、边工作量计数、有限 trace 和显式 bounded 语义。
 */
class ConstraintLayeredDpCanonBenchmarkTest {
    private val key = Key.major(PitchClass.C)
    private val context = TonalContext.fromKey(key, tonicSpelling = SpelledPitchClass(NoteName.C))
    private val plan = TonalPlan(listOf(TonalSpan(SlotWindow(0, null), context)))
    private val canon = listOf(1, 5, 6, 3, 4, 1, 4, 5, 1)

    @Test
    fun canonProgressionReportsStateMergingAndRealDpWorkUnits() {
        val dfs = solveCanon(SearchBackend.GREEDY_DFS)
        val dp = solveCanon(SearchBackend.LAYERED_DP)

        assertEquals(SearchBackend.GREEDY_DFS, dfs.trace.backend)
        assertEquals(SearchBackend.LAYERED_DP, dp.trace.backend)
        assertTrue(dp.capabilitySupported, "固定自然三和弦 FREE preset 应由规则状态计划覆盖")
        assertTrue(dp.trace.bounded, "AUTO/显式 DP 在自由写作下始终是有界模式")
        assertEquals(canon.size, dp.trace.dpStatePlan.size)
        assertTrue(dp.trace.dpStatePlan.first().contains("recentFrames=1"))
        assertTrue(dp.trace.dpStatePlan[1].contains("recentFrames=2"))
        assertTrue(dp.trace.dpStatePlan.last().contains("recentFrames=0"))
        assertTrue("free.melody.consecutive-leaps" in dp.trace.dpCoveredRules)
        assertTrue("free.melody.no-repeated-pattern" in dp.trace.dpTerminalRerankRules)

        // 候选层与状态前沿都明确标记 bounded，不再把 beamWidth 冒充 DP 状态上限。
        assertTrue(dp.trace.candidateLayersTruncated, "层候选应被 maxCandidatesPerTarget 截断")
        assertTrue(dp.trace.frontierTruncated, "前沿应被 maxFrontierStates 截断")
        assertTrue(dp.trace.transitionCandidatesTruncated, "bounded DP 应按每个前驱的候选上限截断出边")

        val layers = dp.layers()
        assertEquals(canon.size, layers.size, "每个槽应恰好展开一层")
        val generated = layers.values.sumOf { it.generatedLabels }
        val merged = layers.values.sumOf { it.generatedLabels - it.distinctStates }
        val mergeRate = merged.toDouble() / generated
        val intermediate = layers.toSortedMap().values.toList().dropLast(1)
        val intermediateGenerated = intermediate.sumOf { it.generatedLabels }
        val intermediateMerged = intermediate.sumOf { it.generatedLabels - it.distinctStates }
        val intermediateMergeRate = intermediateMerged.toDouble() / intermediateGenerated
        assertTrue(merged > 0, "移除完整路径后应至少发生一次等价状态合并")
        assertTrue(mergeRate > 0.0, "自动状态计划应产生真实合并")

        // DP 的真实成本单列为 evaluatedTransitions；不再与 DFS visitedNodes 混为同一量纲。
        assertTrue(
            dp.trace.evaluatedTransitions > dp.trace.visitedNodes,
            "visited=${dp.trace.visitedNodes} transitions=${dp.trace.evaluatedTransitions}",
        )
        assertEquals(DP_TRANSITION_BUDGET, dp.trace.transitionBudget)
        // 终层 branch-and-bound：只为可能进入 top-k 的完整路径展开全局规则。
        val terminalLayer = layers.toSortedMap().values.last()
        assertTrue(
            dp.trace.terminalGlobalEvaluations in 1 until terminalLayer.generatedLabels,
            "terminalGlobalEvaluations=${dp.trace.terminalGlobalEvaluations} " +
                "terminalLabels=${terminalLayer.generatedLabels}",
        )
        assertTrue(dp.trace.entries.any { it.kind == WritingSearchTraceEventKind.SOLUTION })
        layers.values.forEach { layer ->
            assertTrue(layer.candidateCount > 0)
            assertEquals(layer.layerTransitions, layer.transitionTierCounts.sum())
            assertEquals(layer.generatedLabels, layer.acceptedTransitionTierCounts.sum())
        }

        println("== 卡农进行 I-V-vi-iii-IV-I-IV-V-I ==")
        println("DFS  ${dfs.duration} best=${dfs.total} visited=${dfs.trace.visitedNodes}")
        dfs.dfsLayers().toSortedMap().forEach { (depth, layer) ->
            println(
                "  DFS depth $depth: expansions=${layer.expansions} " +
                    "candidates=${layer.candidates} accepted=${layer.accepted}"
            )
        }
        println("DP   ${dp.duration} best=${dp.total} visited=${dp.trace.visitedNodes} " +
            "transitions=${dp.trace.evaluatedTransitions} merged=$merged rate=$mergeRate " +
            "intermediateMerged=$intermediateMerged intermediateRate=$intermediateMergeRate " +
            "terminalGlobalEvaluations=${dp.trace.terminalGlobalEvaluations}")
        layers.toSortedMap().forEach { (slot, layer) ->
            println(
                "  DP layer $slot: candidates=${layer.candidateCount} transitions=${layer.layerTransitions} " +
                    "tiers=${layer.transitionTierCounts} acceptedTiers=${layer.acceptedTransitionTierCounts} " +
                    "generated=${layer.generatedLabels} states=${layer.distinctStates} " +
                    "retained=${layer.retainedLabels} cumulativeTransitions=${layer.cumulativeTransitions}"
            )
        }
    }

    private fun solveCanon(backend: SearchBackend): Run {
        val vocabulary = DiatonicChordVocabulary.forContext(
            context = context,
            compatibilityKey = key,
            includeSevenths = false,
            includeInversions = false,
        )
        val byDegree = vocabulary.associateBy { it.degree }
        val program = FreeHarmonySolver.compile(
            FreeHarmonyRequest(
                key = key,
                tonalPlan = plan,
                slotCount = canon.size,
                vocabulary = vocabulary,
                voicePlan = VoicePlan.standardFourPart(),
                fixedTargetIdentityBySlot = canon.withIndex().associate { (index, degree) ->
                    index to requireNotNull(byDegree[degree]).identityKey()
                },
                searchConfig = SearchConfig(
                    maxResults = 1,
                    backend = backend,
                    dynamicProgramming = DynamicProgrammingSearchConfig(
                        maxCandidatesPerTarget = 128,
                        maxLabelsPerState = 1,
                        maxFrontierStates = 32,
                        maxTransitionEvaluations = DP_TRANSITION_BUDGET,
                    ),
                ),
            )
        )
        val capability = LayeredDpCapability.analyze(program)
        // 单次计时受 JIT 预热与 GC 支配，无法比较两个后端：预热后取多次采样的最小值。
        var best: kotlin.time.Duration? = null
        var solved: ConstraintSolveOutcome.Solved? = null
        repeat(WARMUP_RUNS + TIMED_RUNS) { index ->
            val timed = measureTimedValue {
                ConstraintProgramSolver.solvePolyphonicOutcome(program, maxTraceEntries = TRACE_ENTRY_LIMIT)
            }
            val outcome = assertIs<ConstraintSolveOutcome.Solved>(timed.value, "backend=$backend")
            solved = outcome
            if (index >= WARMUP_RUNS && (best == null || timed.duration < best!!)) best = timed.duration
        }
        val result = requireNotNull(solved)
        return Run(
            trace = result.trace,
            total = result.solutions.first().breakdown.total,
            duration = requireNotNull(best).toString(),
            capabilitySupported = capability.supported,
        )
    }

    private data class Run(
        val trace: WritingSearchTrace,
        val total: Double,
        val duration: String,
        val capabilitySupported: Boolean,
    ) {
        fun layers(): Map<Int, Layer> {
            var previousTransitions = 0
            return trace.entries
                .filter { it.kind == WritingSearchTraceEventKind.LAYER_COMPLETED }
                .sortedBy { it.depth }
                .associate { entry ->
                    val layerTransitions = entry.evaluatedTransitions - previousTransitions
                    previousTransitions = entry.evaluatedTransitions
                    entry.depth to
                        Layer(
                            candidateCount = entry.candidateCount,
                            generatedLabels = entry.generatedLabels,
                            distinctStates = entry.distinctStates,
                            retainedLabels = entry.retainedLabels,
                            layerTransitions = layerTransitions,
                            cumulativeTransitions = entry.evaluatedTransitions,
                            transitionTierCounts = entry.transitionTierCounts,
                            acceptedTransitionTierCounts = entry.acceptedTransitionTierCounts,
                        )
                }
        }

        fun dfsLayers(): Map<Int, DfsLayer> = trace.entries
            .filter { it.kind == WritingSearchTraceEventKind.EXPANDED }
            .groupBy { it.depth }
            .mapValues { (_, entries) ->
                DfsLayer(
                    expansions = entries.size,
                    candidates = entries.sumOf { it.candidateCount },
                    accepted = entries.sumOf { it.acceptedCount },
                )
            }
    }

    private data class Layer(
        val candidateCount: Int,
        val generatedLabels: Int,
        val distinctStates: Int,
        val retainedLabels: Int,
        val layerTransitions: Int,
        val cumulativeTransitions: Int,
        val transitionTierCounts: List<Int>,
        val acceptedTransitionTierCounts: List<Int>,
    )

    private data class DfsLayer(
        val expansions: Int,
        val candidates: Int,
        val accepted: Int,
    )

    private companion object {
        const val TRACE_ENTRY_LIMIT = 128
        const val DP_TRANSITION_BUDGET = 100_000
        const val WARMUP_RUNS = 6
        const val TIMED_RUNS = 5
    }
}
