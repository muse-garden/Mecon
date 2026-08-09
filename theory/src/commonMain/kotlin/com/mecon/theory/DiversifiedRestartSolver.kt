package com.mecon.theory

import kotlin.math.ceil
import kotlin.random.Random

/**
 * 多样化重启 DFS（docs/theory/diverse-search.md）。约束空间中的 ruin-and-recreate：
 *
 * - **阶段 A**：跑确定性贪心 DFS（[GreedyDepthFirstSolver.coreRun]，explore 池关闭）取质量最优
 *   首解，并保留其前缀链作为变异重启点；
 * - **阶段 B**：从已接收结果中选 reference，恢复某槽前缀状态强制变异，再用受限随机贪心 DFS
 *   重建后缀；完整候选须对全部已接收结果满足槽距离与声部单元距离门槛才被接收。
 *
 * HARD 合法性边界不放宽；随机性只改变合法候选的访问顺序（[SearchConfig] 的 seed 保证可复现）。
 * 节点预算与重启预算共同保证终止；空间过窄时返回较少结果而非近重复。
 */
internal object DiversifiedRestartSolver {
    private data class RestrictedDescentContext<State, Candidate>(
        val task: WritingTask,
        val space: ScoredCandidateSpace<State, Candidate>,
        val budget: NodeBudget,
        val rng: Random,
        val diversity: DiversitySearchConfig,
        val totalSlots: Int,
        val minChangedSlots: Int,
        val referenceSlotKeys: List<String>,
        val record: (WritingSearchTraceEntry) -> Unit,
        val describe: (State) -> String,
        val cancellation: SearchCancellation,
    )

    fun <State, Candidate> run(
        task: WritingTask,
        space: ScoredCandidateSpace<State, Candidate>,
        describe: (State) -> String = space::diversityKey,
        depthOf: (State) -> Int = { 0 },
        record: (WritingSearchTraceEntry) -> Unit = {},
        cancellation: SearchCancellation = SearchCancellation.NONE,
        initialSeenGroups: Set<String> = emptySet(),
    ): SearchRun<State> {
        val cfg = task.searchConfig
        val div = cfg.diversity
        val budget = NodeBudget(cfg.nodeBudget)
        val rng = Random(div.seed)
        val totalSlots = task.timeline.slots.size
        val minChangedSlots = ceil(div.minChangedSlotRatio * totalSlots).toInt()
        val seenGroups = initialSeenGroups.toMutableSet()

        // ---- 阶段 A：确定性首解（explore 池关闭，保证首候选与旧贪心 DFS 一致） -------------
        val deterministicTask = task.copy(
            searchConfig = cfg.copy(
                maxResults = 1,
                diversity = div.copy(enabled = false),
            ),
        )
        val phaseA = if (cfg.prefixDiversity.enabled) {
            DiversePrefixBeamSolver.run(
                task = deterministicTask,
                space = space,
                describe = describe,
                depthOf = depthOf,
                record = record,
                cancellation = cancellation,
                initialSeenGroups = seenGroups,
                budget = budget,
            )
        } else {
            GreedyDepthFirstSolver.coreRun(
                task = deterministicTask,
                space = space,
                describe = describe,
                depthOf = depthOf,
                record = record,
                budget = budget,
                maxResults = 1,
                seenGroups = seenGroups,
                cancellation = cancellation,
            )
        }
        if (phaseA.solutions.isEmpty()) {
            return SearchRun(
                emptyList(), budget.limit, budget.consumed, budget.exhausted, cancellation.isCancelled(),
            )
        }
        phaseA.solutions.forEach { solution ->
            seenGroups += space.diversityGroupKey(solution.result.state)
        }

        val accepted = mutableListOf<SolutionWithChain<State>>()
        val seed = phaseA.solutions.first()
        accepted += seed
        record(
            GreedyDepthFirstSolver.traceEntry(
                WritingSearchTraceEventKind.SEED_SOLUTION,
                depth = depthOf(seed.result.state),
                state = describe(seed.result.state),
                totalScore = seed.result.breakdown.total,
            )
        )

        fun passesGate(candidate: State): Boolean {
            val cKeys = space.slotDiversityKeys(candidate)
            return accepted.all { acc ->
                val other = acc.result.state
                val slotRatio = changedSlotRatio(cKeys, space.slotDiversityKeys(other))
                val cellRatio = 1.0 - space.similarity(candidate, other)
                slotRatio >= div.minChangedSlotRatio - EPS && cellRatio >= div.minChangedVoiceCellRatio - EPS
            }
        }

        // 阶段 A 顺带发现的其它解，若已直接满足门槛就免费收编，减少重启开销。
        for (candidate in phaseA.solutions.drop(1)) {
            if (accepted.size >= cfg.maxResults) break
            if (passesGate(candidate.result.state)) accepted += candidate
        }

        // ---- 阶段 B：强制变异重启 ---------------------------------------------------------
        val exhaustedSlots = HashMap<String, MutableSet<Int>>()
        val attempts = HashMap<String, HashMap<Int, Int>>()
        val mutationPenalties = HashMap<String, List<Double>>()
        val tabu = HashSet<String>()

        fun referenceKey(ref: SolutionWithChain<State>): String = space.diversityKey(ref.result.state)

        fun remainingSlots(refKey: String): Int =
            totalSlots - (exhaustedSlots[refKey]?.size ?: 0)

        fun pickReference(): SolutionWithChain<State>? {
            val eligible = accepted.filter { remainingSlots(referenceKey(it)) > 0 }
            if (eligible.isEmpty()) return null
            // 未探索槽越多的结果权重越高，逐步覆盖多个解簇而非围绕首解打转。
            return weightedPick(eligible, rng) { remainingSlots(referenceKey(it)).toDouble() }
        }

        fun pickMutationSlot(reference: SolutionWithChain<State>, refKey: String): Int? {
            val done = exhaustedSlots.getOrPut(refKey) { mutableSetOf() }
            val slotAttempts = attempts.getOrPut(refKey) { HashMap() }
            val open = (0 until totalSlots).filter { it !in done }
            if (open.isEmpty()) return null
            val nextSlotPenalties = mutationPenalties.getOrPut(refKey) {
                mutationLookAheadPenalties(reference, task, space, totalSlots)
            }
            val maxPenalty = nextSlotPenalties.maxOrNull()?.takeIf { it > EPS } ?: 0.0
            return weightedPick(open, rng) { m ->
                // 较早槽保留有限偏置；若下一槽引入大量扣分，优先改变它的前置和弦。
                mutationSlotWeight(
                    mutationSlot = m,
                    totalSlots = totalSlots,
                    attempts = slotAttempts[m] ?: 0,
                    nextSlotPenalty = nextSlotPenalties[m],
                    maxPenalty = maxPenalty,
                    diversity = div,
                )
            }
        }

        fun tryRestart(reference: SolutionWithChain<State>, refKey: String, m: Int): SolutionWithChain<State>? {
            val chain = reference.chain
            if (m + 1 >= chain.size) {
                exhaustedSlots.getValue(refKey).add(m)
                return null
            }
            val prefix = chain[m]
            val refSlotKeys = space.slotDiversityKeys(reference.result.state)
            val refNextKey = refSlotKeys.getOrNull(m)
            val alternatives = space.candidates(prefix, task)
                .asSequence()
                .map { candidate -> space.apply(prefix, candidate) }
                .map { next -> next to space.score(next, task) }
                .filterNot { it.second.hasHardViolation }
                .filter { space.stepDiversityKey(it.first) != refNextKey }
                .sortedBy { it.second.total }
                .toList()
            if (alternatives.isEmpty()) {
                record(neighborhoodExhausted(space, describe, prefix, m))
                exhaustedSlots.getValue(refKey).add(m)
                return null
            }

            var tried = 0
            for ((altState, _) in alternatives) {
                if (tried >= div.mutationPoolSize) break
                if (budget.remaining <= 0) break
                val altKey = space.stepDiversityKey(altState)
                val tabuKey = "$refKey#$m#$altKey"
                if (tabuKey in tabu) continue
                tabu += tabuKey
                tried++
                record(
                    GreedyDepthFirstSolver.traceEntry(
                        WritingSearchTraceEventKind.MUTATION_RESTART,
                        depth = m,
                        state = describe(altState),
                    )
                )
                val completedPath = restrictedDescend(
                    context = RestrictedDescentContext(
                        task,
                        space,
                        budget,
                        rng,
                        div,
                        totalSlots,
                        minChangedSlots,
                        refSlotKeys,
                        record,
                        describe,
                        cancellation,
                    ),
                    start = altState,
                    path = chain.subList(0, m + 1) + altState,
                )
                if (completedPath != null) {
                    val completed = completedPath.last()
                    val group = space.diversityGroupKey(completed)
                    if (group !in seenGroups && passesGate(completed)) {
                        seenGroups += group
                        return SolutionWithChain(
                            WritingSearchResult(completed, space.score(completed, task)),
                            completedPath,
                        )
                    }
                    record(
                        GreedyDepthFirstSolver.traceEntry(
                            WritingSearchTraceEventKind.DIVERSITY_REJECTED,
                            depth = totalSlots,
                            state = describe(completed),
                        )
                    )
                }
            }
            exhaustedSlots.getValue(refKey).add(m)
            return null
        }

        var restarts = 0
        while (
            accepted.size < cfg.maxResults && restarts < div.restartBudget &&
            budget.remaining > 0 && !cancellation.isCancelled()
        ) {
            restarts++
            val reference = pickReference() ?: break
            val refKey = referenceKey(reference)
            val slot = pickMutationSlot(reference, refKey)
            if (slot == null) {
                // 该 reference 的可变槽已全部耗尽，标记后换下一个。
                exhaustedSlots.getValue(refKey).addAll(0 until totalSlots)
                continue
            }
            val attemptsForReference = attempts.getValue(refKey)
            attemptsForReference[slot] = (attemptsForReference[slot] ?: 0) + 1
            val newResult = tryRestart(reference, refKey, slot)
            if (newResult != null) accepted += newResult
        }

        // ---- 最终选择（§8）：首名固定为阶段 A 首解，其余按质量排序 ------------------------
        val rest = accepted.drop(1).sortedWith(
            compareBy<SolutionWithChain<State>> { it.result.breakdown.total }
                .thenBy { space.diversityKey(it.result.state) },
        )
        return SearchRun(
            solutions = listOf(seed) + rest,
            nodeBudget = budget.limit,
            visitedNodes = budget.consumed,
            exhaustedBudget = budget.exhausted,
            cancelled = cancellation.isCancelled(),
        )
    }

    /**
     * 受限随机贪心 DFS（§3）：随机性只改变合法候选访问顺序，规则裁决不变。逐步施加重合/安全
     * 剪枝（§5），返回首个满足最小距离的完整路径，否则回溯至 null。
     */
    private fun <State, Candidate> restrictedDescend(
        context: RestrictedDescentContext<State, Candidate>,
        start: State,
        path: List<State>,
    ): List<State>? {
        val (
            task,
            space,
            budget,
            rng,
            div,
            totalSlots,
            minChangedSlots,
            referenceSlotKeys,
            record,
            describe,
            cancellation,
        ) = context
        if (cancellation.isCancelled()) return null
        if (!budget.tryConsume()) return null
        if (space.isComplete(start, task)) return path

        val scored = space.candidates(start, task)
            .map { candidate -> space.apply(start, candidate) }
            .map { next -> next to space.score(next, task) }
            .filterNot { it.second.hasHardViolation }
        if (scored.isEmpty()) return null

        val best = scored.minOf { it.second.total }
        val restricted = scored
            .sortedBy { it.second.total }
            .filterIndexed { index, (_, s) -> index < RCL_SIZE || s.total <= best + RCL_TOLERANCE }
        val order = weightedShuffle(restricted, rng) { (_, s) -> 1.0 / (1.0 + (s.total - best)) }

        for ((next, _) in order) {
            if (cancellation.isCancelled()) return null
            val nextDepth = space.slotDiversityKeys(next).size
            val changedSoFar = changedSlotCount(space.slotDiversityKeys(next), referenceSlotKeys, nextDepth)
            val remaining = totalSlots - nextDepth
            // 安全剪枝：即使后缀全变也达不到门槛。
            if (changedSoFar + remaining < minChangedSlots) continue
            if (div.rejoinPolicy != RejoinPolicy.BEFORE_MIN_DISTANCE || changedSoFar < minChangedSlots) {
                val slot = nextDepth - 1
                val rejoined = space.stepDiversityKey(next) == referenceSlotKeys.getOrNull(slot)
                if (rejoined && changedSoFar < minChangedSlots) {
                    record(
                        GreedyDepthFirstSolver.traceEntry(
                            WritingSearchTraceEventKind.REJOIN_PRUNED,
                            depth = slot,
                            state = describe(next),
                        )
                    )
                    continue
                }
            }
            val completed = restrictedDescend(
                context, next, path + next,
            )
            if (completed != null) return completed
        }
        return null
    }

    private fun <State, Candidate> neighborhoodExhausted(
        space: ScoredCandidateSpace<State, Candidate>,
        describe: (State) -> String,
        prefix: State,
        slot: Int,
    ): WritingSearchTraceEntry =
        GreedyDepthFirstSolver.traceEntry(
            WritingSearchTraceEventKind.NEIGHBORHOOD_EXHAUSTED,
            depth = slot,
            state = describe(prefix),
        )

    /** 逐槽结构 key 在 [0, upto) 内的差异计数（不同 stepDiversityKey 的槽数）。 */
    private fun changedSlotCount(left: List<String>, right: List<String>, upto: Int): Int {
        var changed = 0
        for (i in 0 until upto) {
            val l = left.getOrNull(i)
            val r = right.getOrNull(i)
            if (l != null && r != null && l != r) changed++
            else if (l != null && r == null) changed++
        }
        return changed
    }

    private fun changedSlotRatio(left: List<String>, right: List<String>): Double {
        val total = maxOf(left.size, right.size)
        if (total == 0) return 0.0
        return changedSlotCount(left, right, total).toDouble() / total.toDouble()
    }

    /**
     * Score introduced at slot `m + 1`, assigned to mutation slot `m`. Transition and voice-leading
     * penalties first become visible when the later chord is appended, but either side may be the
     * cause; branching one chord earlier lets the restart rebuild the complete penalized relation.
     */
    private fun <State, Candidate> mutationLookAheadPenalties(
        reference: SolutionWithChain<State>,
        task: WritingTask,
        space: ScoredCandidateSpace<State, Candidate>,
        totalSlots: Int,
    ): List<Double> {
        val prefixTotals = reference.chain.map { state -> space.score(state, task).total }
        return List(totalSlots) { mutationSlot ->
            val beforeNext = prefixTotals.getOrNull(mutationSlot + 1)
            val afterNext = prefixTotals.getOrNull(mutationSlot + 2)
            if (beforeNext == null || afterNext == null) 0.0
            else (afterNext - beforeNext).coerceAtLeast(0.0)
        }
    }

    /** 按权重不放回地采样一个元素（deterministic given [rng]）。 */
    private fun <T> weightedPick(items: List<T>, rng: Random, weight: (T) -> Double): T {
        val weights = items.map { weight(it).coerceAtLeast(0.0) }
        val sum = weights.sum()
        if (sum <= 0.0) return items[rng.nextInt(items.size)]
        var pick = rng.nextDouble() * sum
        for (i in items.indices) {
            pick -= weights[i]
            if (pick <= 0.0) return items[i]
        }
        return items.last()
    }

    /** 按权重生成稳定随机的完整访问顺序（不放回）。 */
    private fun <T> weightedShuffle(items: List<T>, rng: Random, weight: (T) -> Double): List<T> {
        val pool = items.toMutableList()
        val order = ArrayList<T>(pool.size)
        while (pool.isNotEmpty()) {
            val chosen = weightedPick(pool, rng, weight)
            order += chosen
            pool.remove(chosen)
        }
        return order
    }

    private const val EPS = 1e-9
    private const val RCL_SIZE = 6
    private const val RCL_TOLERANCE = 1.0
}

/** Testable weight policy used by the seeded mutation-slot picker. */
internal fun mutationSlotWeight(
    mutationSlot: Int,
    totalSlots: Int,
    attempts: Int,
    nextSlotPenalty: Double,
    maxPenalty: Double,
    diversity: DiversitySearchConfig,
): Double {
    val earlyWeight = if (diversity.earlyMutationBias == 0.0) {
        1.0
    } else {
        1.0 + diversity.earlyMutationBias *
            (totalSlots - mutationSlot - 1).toDouble() / totalSlots
    }
    val penaltyWeight = if (maxPenalty <= 0.0) {
        1.0
    } else {
        1.0 + diversity.penaltyMutationBias * nextSlotPenalty.coerceAtLeast(0.0) / maxPenalty
    }
    return earlyWeight * penaltyWeight / (1.0 + attempts)
}
