package com.mecon.theory.constraint

import com.mecon.api.primitive.NoteName
import com.mecon.api.primitive.PitchClass
import com.mecon.theory.DynamicProgrammingSearchConfig
import com.mecon.theory.DiversitySearchConfig
import com.mecon.theory.Key
import com.mecon.theory.SearchBackend
import com.mecon.theory.SearchConfig
import com.mecon.theory.PrefixDiversitySearchConfig
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

    @Test
    fun longerOpenProgressionUsesDpForDiverseFreeWriting() {
        val slots = canon + listOf(5, 1)
        val common = SearchConfig(
            maxResults = 2,
            beamWidth = 12,
            prefixDiversity = PrefixDiversitySearchConfig(enabled = true, frontierWidth = 12),
            diversity = DiversitySearchConfig(
                enabled = true,
                minChangedSlotRatio = 0.25,
                minChangedVoiceCellRatio = 0.10,
            ),
        )
        val dfsProgram = buildOpenProgram(slots, common.copy(backend = SearchBackend.GREEDY_DFS))
        val dpProgram = buildOpenProgram(
            slots,
            common.copy(
                backend = SearchBackend.LAYERED_DP,
                dynamicProgramming = DynamicProgrammingSearchConfig(
                    maxCandidatesPerTarget = 48,
                    maxLabelsPerState = 2,
                    maxFrontierStates = 32,
                    maxTransitionEvaluations = 100_000,
                ),
            ),
        )

        // One untimed DP pass removes first-use/JIT noise; elapsed time remains diagnostic only.
        solve(dpProgram, "long DP")
        val dfs = measureTimedValue {
            ConstraintProgramSolver.solvePolyphonicOutcome(dfsProgram, maxTraceEntries = 0)
        }
        val dp = measureTimedValue { solve(dpProgram, "long DP") }

        println(
            "== 11-slot open free writing ==\n" +
                "  DFS outcome=${dfs.value::class.simpleName} time=${dfs.duration}\n" +
                "  DP  best=${dp.value.first} count=${dp.value.second} time=${dp.duration}"
        )
        assertIs<ConstraintSolveOutcome.BudgetExhausted>(
            dfs.value,
            "the long diverse DFS baseline should expose its node-budget limit",
        )
        assertTrue(dp.value.second >= 2, "diverse DP should retain multiple long-form results")
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

    private fun solve(program: ConstraintProgram, label: String = ""): Pair<Double, Int> {
        val outcome = ConstraintProgramSolver.solvePolyphonicOutcome(
            program,
            maxTraceEntries = if (label.isEmpty()) 0 else 16,
        )
        val solved = assertIs<ConstraintSolveOutcome.Solved>(outcome, "$label outcome=$outcome")
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

    private fun buildOpenProgram(
        degrees: List<Int>,
        searchConfig: SearchConfig,
    ): ConstraintProgram {
        val vocabulary = DiatonicChordVocabulary.forContext(
            context = context,
            compatibilityKey = key,
            includeSevenths = true,
            includeInversions = false,
        )
        val triads = vocabulary.filter { it.arity == com.mecon.theory.ChordArity.TRIAD }
            .associateBy { it.degree }
        val sevenths = vocabulary.filter { it.arity == com.mecon.theory.ChordArity.SEVENTH }
            .associateBy { it.degree }
        val allowed = degrees.mapIndexedNotNull { index, degree ->
            if (index % 4 != 1) return@mapIndexedNotNull null
            index to setOf(
                requireNotNull(triads[degree]).identityKey(),
                requireNotNull(sevenths[degree]).identityKey(),
            )
        }.toMap()
        val fixed = degrees.mapIndexedNotNull { index, degree ->
            if (index % 4 == 1) null else index to requireNotNull(triads[degree]).identityKey()
        }.toMap()
        return FreeHarmonySolver.compile(
            FreeHarmonyRequest(
                key = key,
                tonalPlan = plan,
                slotCount = degrees.size,
                vocabulary = vocabulary,
                voicePlan = VoicePlan.standardFourPart(),
                allowedTargetIdentityKeysBySlot = allowed,
                fixedTargetIdentityBySlot = fixed,
                searchConfig = searchConfig,
            )
        )
    }

    private companion object {
        const val WARMUP_ROUNDS = 6
        const val TIMED_ROUNDS = 5
    }
}
