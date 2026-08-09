package com.mecon.theory.constraint

import com.mecon.api.primitive.NoteName
import com.mecon.api.primitive.PitchClass
import com.mecon.theory.DynamicProgrammingSearchConfig
import com.mecon.theory.Key
import com.mecon.theory.SearchBackend
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.SpelledPitchClass
import com.mecon.theory.TonalContext
import com.mecon.theory.TonalPlan
import com.mecon.theory.TonalSpan
import com.mecon.theory.VoicePlan
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.measureTimedValue

/**
 * DFS 与 DP 的「同等分值耗时」对比。
 *
 * 只比首解耗时会同时误判两个后端：DFS 与 DP 的默认宽度并不等价，各自还有一个真正决定质量的
 * 旋钮——DFS 是每节点候选池 `beamWidth`，DP 是每前驱出边宽度
 * `min(max(8, 4 × maxResults), candidateLimit)`。因此这里扫两条曲线，按 (分值, 耗时) 对比。
 *
 * 分值是确定的，可以断言；耗时依赖机器，只打印。
 */
class ConstraintDpVersusDfsQualityBenchmarkTest {
    private val key = Key.major(PitchClass.C)
    private val context = TonalContext.fromKey(key, tonicSpelling = SpelledPitchClass(NoteName.C))
    private val plan = TonalPlan(listOf(TonalSpan(SlotWindow(0, null), context)))
    private val canon = listOf(1, 5, 6, 3, 4, 1, 4, 5, 1)

    @Test
    fun dpReachesBetterScoresThanAnySweptDfsWidth() {
        val configs = buildList {
            listOf(16, 32, 48, 64, 128).forEach { width ->
                add(Config("DFS pool=$width", dfs(beamWidth = width)))
            }
            listOf(32, 64, 128).forEach { pool ->
                add(Config("DP pool=$pool out=8", dp(pool = pool, outgoing = 8)))
            }
            listOf(16, 32).forEach { outgoing ->
                add(Config("DP pool=128 out=$outgoing", dp(pool = 128, outgoing = outgoing)))
            }
        }
        val measured = measureInterleaved(configs)

        println("== 卡农进行 I-V-vi-iii-IV-I-IV-V-I：分值 vs 耗时 ==")
        measured.forEach { (name, result) ->
            println("  $name: best=${result.best} solutions=${result.count} time=${result.time}")
        }

        val byName = measured.toMap()
        fun best(name: String) = byName.getValue(name).best

        // DFS 的质量随候选池加深到某一点后不再改善：更深的池只是更慢。
        assertTrue(
            best("DFS pool=48") <= best("DFS pool=32"),
            "更深的候选池不应变差：${best("DFS pool=48")} vs ${best("DFS pool=32")}",
        )
        assertTrue(
            best("DFS pool=128") == best("DFS pool=48"),
            "DFS 质量应在候选池 48 处到顶：${best("DFS pool=128")} vs ${best("DFS pool=48")}",
        )

        // DP 的层候选池是它的质量旋钮；出边宽度 8 时的前沿宽度再大也换不到分数。
        assertTrue(
            best("DP pool=64 out=8") < best("DP pool=32 out=8"),
            "DP 层候选池应带来更好的分值：${best("DP pool=64 out=8")} vs ${best("DP pool=32 out=8")}",
        )
        assertTrue(
            best("DP pool=128 out=8") == best("DP pool=64 out=8"),
            "层候选池超过 64 后 DP 出边宽度成为瓶颈",
        )

        // 关键结论：放宽出边宽度后，DP 达到 DFS 在任何扫过的候选池深度都达不到的分值。
        val dfsCeiling = listOf(16, 32, 48, 64, 128).minOf { best("DFS pool=$it") }
        assertTrue(
            best("DP pool=128 out=16") < dfsCeiling,
            "DP 应严格优于 DFS 的质量上限：${best("DP pool=128 out=16")} vs $dfsCeiling",
        )
    }

    private data class Config(val name: String, val program: ConstraintProgram)

    private data class Result(val best: Double, val count: Int, val time: Duration)

    /**
     * 交错采样：所有配置先各跑 [WARMUP_ROUNDS] 遍再统一计时，否则先跑的配置吃掉全部 JIT 惩罚，
     * 曲线会完全失真。每个配置取多轮最小值。
     */
    private fun measureInterleaved(configs: List<Config>): List<Pair<String, Result>> {
        repeat(WARMUP_ROUNDS) { configs.forEach { solve(it.program) } }
        val samples = configs.associate { it.name to mutableListOf<Duration>() }
        val outcomes = mutableMapOf<String, Pair<Double, Int>>()
        repeat(TIMED_ROUNDS) {
            configs.forEach { config ->
                val timed = measureTimedValue { solve(config.program) }
                samples.getValue(config.name) += timed.duration
                outcomes[config.name] = timed.value
            }
        }
        return configs.map { config ->
            val (best, count) = outcomes.getValue(config.name)
            config.name to Result(best, count, samples.getValue(config.name).min())
        }
    }

    private fun solve(program: ConstraintProgram): Pair<Double, Int> {
        val solved = assertIs<ConstraintSolveOutcome.Solved>(
            ConstraintProgramSolver.solvePolyphonicOutcome(program, maxTraceEntries = 0),
        )
        return solved.solutions.minOf { it.breakdown.total } to solved.solutions.size
    }

    private fun dfs(beamWidth: Int): ConstraintProgram =
        buildProgram(
            SearchConfig(
                maxResults = 1,
                beamWidth = beamWidth,
                backend = SearchBackend.GREEDY_DFS,
            )
        )

    /** DP 出边宽度由 `min(max(8, 4 × maxResults), candidateLimit)` 决定，两处一起抬才生效。 */
    private fun dp(pool: Int, outgoing: Int): ConstraintProgram =
        buildProgram(
            SearchConfig(
                maxResults = maxOf(1, outgoing / 4),
                beamWidth = outgoing,
                backend = SearchBackend.LAYERED_DP,
                dynamicProgramming = DynamicProgrammingSearchConfig(
                    maxCandidatesPerTarget = pool,
                    maxLabelsPerState = 1,
                    maxFrontierStates = 8,
                    maxTransitionEvaluations = 400_000,
                ),
            )
        )

    private fun buildProgram(searchConfig: SearchConfig): ConstraintProgram {
        val vocabulary = DiatonicChordVocabulary.forContext(
            context = context,
            compatibilityKey = key,
            includeSevenths = false,
            includeInversions = false,
        )
        val byDegree = vocabulary.associateBy { it.degree }
        return FreeHarmonySolver.compile(
            FreeHarmonyRequest(
                key = key,
                tonalPlan = plan,
                slotCount = canon.size,
                vocabulary = vocabulary,
                voicePlan = VoicePlan.standardFourPart(),
                fixedTargetIdentityBySlot = canon.withIndex().associate { (index, degree) ->
                    index to requireNotNull(byDegree[degree]).identityKey()
                },
                searchConfig = searchConfig,
            )
        )
    }

    private companion object {
        const val WARMUP_ROUNDS = 6
        const val TIMED_ROUNDS = 5
    }
}
