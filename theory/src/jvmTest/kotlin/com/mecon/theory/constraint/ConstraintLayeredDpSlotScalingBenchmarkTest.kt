package com.mecon.theory.constraint

import com.mecon.api.primitive.NoteName
import com.mecon.api.primitive.PitchClass
import com.mecon.theory.ChordArity
import com.mecon.theory.DiversitySearchConfig
import com.mecon.theory.DynamicProgrammingSearchConfig
import com.mecon.theory.Key
import com.mecon.theory.PrefixDiversitySearchConfig
import com.mecon.theory.SearchBackend
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.SpelledPitchClass
import com.mecon.theory.TonalContext
import com.mecon.theory.TonalPlan
import com.mecon.theory.TonalSpan
import com.mecon.theory.VoicePlan
import com.mecon.theory.WritingSearchTraceEntry
import com.mecon.theory.WritingSearchTraceEventKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

/**
 * 槽位扩展性基准（[docs/theory/dp-slot-scaling-review.md] 的可执行版本）。
 *
 * 该文档的核心结论是**每层规模不随槽位增长**：逐层 generatedLabels / distinctStates /
 * retainedLabels 只由前沿与出边上限决定，转移总数线性增长。耗时依赖机器只打印，结构量确定可断言。
 * 配置在此固化，避免再出现无法复现的历史数字。
 */
class ConstraintLayeredDpSlotScalingBenchmarkTest {
    private val key = Key.major(PitchClass.C)
    private val context = TonalContext.fromKey(key, tonicSpelling = SpelledPitchClass(NoteName.C))
    private val plan = TonalPlan(listOf(TonalSpan(SlotWindow(0, null), context)))

    /** 卡农进行的延长版；`index % 4 == 1` 的槽位在同音级三/七和弦之间二选一。 */
    private val degrees = listOf(1, 5, 6, 3, 4, 1, 4, 5, 1) +
        listOf(5, 1, 4, 5, 1, 6, 5, 1) +
        listOf(1, 5, 6, 3, 4, 1, 4, 5, 1)

    @Test
    fun perLayerSearchSizeDoesNotGrowWithSlotCount() {
        // 预热一次，避免首个配置吃掉全部 JIT 惩罚。
        solve(SLOT_COUNTS.first())

        val runs = SLOT_COUNTS.associateWith { solve(it) }
        println("== 分层 DP 槽位扩展性（$SLOT_COUNTS 槽，3 个多样化结果）==")
        runs.forEach { (slots, run) ->
            println(
                "  slots=$slots time=${run.duration} transitions=${run.transitions} " +
                    "terminalGlobal=${run.terminalGlobalEvaluations} " +
                    "terminalLowerBound=${run.terminalLowerBoundApplied}"
            )
        }

        val shortest = runs.getValue(SLOT_COUNTS.first())
        runs.forEach { (slots, run) ->
            assertEquals(3, run.solutions, "slots=$slots 应产出 3 个多样化结果")
            assertEquals(slots, run.layers.size, "slots=$slots 的层数应等于槽数")
            // 相同前缀 → 相同逐层规模。终层状态计划为空、所有完整路径落进同一组，单独排除。
            shortest.layers.dropLast(1).forEachIndexed { layer, expected ->
                val actual = run.layers[layer]
                assertEquals(
                    layerShape(expected),
                    layerShape(actual),
                    "slots=$slots 第 $layer 层规模不应随总槽数变化",
                )
            }
        }

        // 进入下一层的标签数由前沿上限 × 每状态标签上限封死，与总槽数无关。
        // 总转移数仍随槽位增长，但那只是层数变多（本基准里更长的进行还含更多开放槽），
        // 不是单层规模膨胀。
        runs.forEach { (slots, run) ->
            run.layers.dropLast(1).forEach { layer ->
                assertTrue(
                    layer.retainedLabels <= MAX_RETAINED_PER_LAYER,
                    "slots=$slots 第 ${layer.depth} 层保留了 ${layer.retainedLabels} 条标签，" +
                        "超过前沿上限 $MAX_RETAINED_PER_LAYER",
                )
            }
        }
    }

    private fun layerShape(entry: WritingSearchTraceEntry): List<Int> =
        listOf(entry.candidateCount, entry.generatedLabels, entry.distinctStates, entry.retainedLabels)

    private fun solve(slotCount: Int): Run {
        val program = buildOpenProgram(degrees.take(slotCount))
        val timed = measureTimedValue {
            ConstraintProgramSolver.solvePolyphonicOutcome(program, maxTraceEntries = 4096)
        }
        val solved = assertIs<ConstraintSolveOutcome.Solved>(timed.value, "slots=$slotCount")
        return Run(
            duration = timed.duration.toString(),
            solutions = solved.solutions.size,
            transitions = solved.trace.evaluatedTransitions,
            terminalGlobalEvaluations = solved.trace.terminalGlobalEvaluations,
            terminalLowerBoundApplied = solved.trace.terminalLowerBoundApplied,
            layers = solved.trace.entries
                .filter { it.kind == WritingSearchTraceEventKind.LAYER_COMPLETED }
                .sortedBy { it.depth },
        )
    }

    private class Run(
        val duration: String,
        val solutions: Int,
        val transitions: Int,
        val terminalGlobalEvaluations: Int,
        val terminalLowerBoundApplied: Boolean,
        val layers: List<WritingSearchTraceEntry>,
    )

    private fun buildOpenProgram(slotDegrees: List<Int>): ConstraintProgram {
        val vocabulary = DiatonicChordVocabulary.forContext(
            context = context,
            compatibilityKey = key,
            includeSevenths = true,
            includeInversions = false,
        )
        val triads = vocabulary.filter { it.arity == ChordArity.TRIAD }.associateBy { it.degree }
        val sevenths = vocabulary.filter { it.arity == ChordArity.SEVENTH }.associateBy { it.degree }
        val allowed = slotDegrees.mapIndexedNotNull { index, degree ->
            if (index % 4 != 1) return@mapIndexedNotNull null
            index to setOf(
                requireNotNull(triads[degree]).identityKey(),
                requireNotNull(sevenths[degree]).identityKey(),
            )
        }.toMap()
        val fixed = slotDegrees.mapIndexedNotNull { index, degree ->
            if (index % 4 == 1) null else index to requireNotNull(triads[degree]).identityKey()
        }.toMap()
        return FreeHarmonySolver.compile(
            FreeHarmonyRequest(
                key = key,
                tonalPlan = plan,
                slotCount = slotDegrees.size,
                vocabulary = vocabulary,
                voicePlan = VoicePlan.standardFourPart(),
                allowedTargetIdentityKeysBySlot = allowed,
                fixedTargetIdentityBySlot = fixed,
                searchConfig = SearchConfig(
                    maxResults = 3,
                    beamWidth = 12,
                    backend = SearchBackend.LAYERED_DP,
                    prefixDiversity = PrefixDiversitySearchConfig(enabled = true, frontierWidth = 12),
                    diversity = DiversitySearchConfig(
                        enabled = true,
                        minChangedSlotRatio = 0.25,
                        minChangedVoiceCellRatio = 0.10,
                    ),
                    dynamicProgramming = DynamicProgrammingSearchConfig(
                        maxCandidatesPerTarget = 48,
                        maxLabelsPerState = 2,
                        maxFrontierStates = 32,
                        maxTransitionEvaluations = 1_000_000,
                    ),
                ),
            )
        )
    }

    private companion object {
        val SLOT_COUNTS = listOf(9, 13, 19)

        /**
         * `min(maxFrontierStates=32, max(beamWidth, frontierWidth) × maxResults) × max(maxLabelsPerState, maxResults)`
         * = 32 × 3。上限只由搜索配置决定，与槽数无关。
         */
        const val MAX_RETAINED_PER_LAYER = 96
    }
}
