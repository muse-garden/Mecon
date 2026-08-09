package com.mecon.theory

import com.mecon.api.primitive.EventId

data class RuleScoreContribution(
    val ruleId: RuleId,
    val amount: Double,
    val reason: String,
)

data class ScoreBreakdown(
    val total: Double,
    val findings: List<RuleFinding<EventId>> = emptyList(),
    val contributions: List<RuleScoreContribution> = emptyList(),
) {
    val hasHardViolation: Boolean =
        findings.any { it.kind == RuleFindingKind.VIOLATION && it.severity == RuleSeverity.HARD }
}

data class WritingSearchResult<State>(
    val state: State,
    val breakdown: ScoreBreakdown,
)

/**
 * Lexicographic, search-only priority. It changes exploration order without becoming a musical
 * score contribution, so final explanations and score totals remain rule-derived.
 */
data class SearchPriority(
    val components: List<Int> = emptyList(),
) : Comparable<SearchPriority> {
    override fun compareTo(other: SearchPriority): Int {
        val commonSize = minOf(components.size, other.components.size)
        for (index in 0 until commonSize) {
            val compared = components[index].compareTo(other.components[index])
            if (compared != 0) return compared
        }
        return components.size.compareTo(other.components.size)
    }

    companion object {
        val NEUTRAL = SearchPriority()
    }
}

fun interface SearchCancellation {
    fun isCancelled(): Boolean

    companion object {
        val NONE: SearchCancellation = SearchCancellation { false }
    }
}

interface ScoredCandidateSpace<State, Candidate> : CandidateSpace<State, Candidate> {
    fun isComplete(state: State, task: WritingTask): Boolean
    fun score(state: State, task: WritingTask): ScoreBreakdown
    fun searchPriority(state: State, task: WritingTask): SearchPriority = SearchPriority.NEUTRAL
    fun diversityKey(state: State): String = state.toString()
    fun diversityGroupKey(state: State): String = diversityKey(state)
    fun similarity(left: State, right: State): Double =
        if (diversityGroupKey(left) == diversityGroupKey(right)) 1.0 else 0.0

    /** Similarity of equal-depth partial realizations, including register when the space supports it. */
    fun prefixSimilarity(left: State, right: State): Double =
        if (diversityKey(left) == diversityKey(right)) 1.0 else 0.0

    /** Coarse local arrangement stratum used to reserve visibility before similarity re-ranking. */
    fun prefixDiversityGroupKey(state: State): String = stepDiversityKey(state)

    /**
     * 逐槽结构身份（diverse-search.md §4）。用于多样化搜索的槽距离、强制变异与重合检测。
     * 默认退化为整状态的 [diversityKey] 单元素列表，非多样化路径不受影响。
     */
    fun slotDiversityKeys(state: State): List<String> = listOf(diversityKey(state))

    /** 最后一步的结构身份，用于强制变异与重合检测（diverse-search.md §4）。 */
    fun stepDiversityKey(state: State): String =
        slotDiversityKeys(state).lastOrNull() ?: diversityKey(state)
}

object BeamSearchSolver {
    fun <State, Candidate> solve(
        task: WritingTask,
        space: ScoredCandidateSpace<State, Candidate>,
    ): List<WritingSearchResult<State>> {
        var beam = listOf(space.initial(task))
        var guard = 0
        while (beam.any { !space.isComplete(it, task) }) {
            guard++
            require(guard <= MAX_SEARCH_STEPS) { "Search did not converge within $MAX_SEARCH_STEPS steps" }
            val expanded = beam.flatMap { state ->
                if (space.isComplete(state, task)) {
                    listOf(state)
                } else {
                    space.candidates(state, task).map { candidate -> space.apply(state, candidate) }
                }
            }
            if (expanded.isEmpty()) break
            beam = expanded
                .map { state -> state to space.score(state, task) }
                .filterNot { (_, score) -> score.hasHardViolation }
                .sortedWith(
                    compareBy<Pair<State, ScoreBreakdown>> { space.searchPriority(it.first, task) }
                        .thenBy { it.second.total }
                        .thenBy { space.diversityKey(it.first) }
                )
                .distinctBy { space.diversityKey(it.first) }
                .take(task.searchConfig.beamWidth)
                .map { it.first }
            if (beam.isEmpty()) break
        }
        val ranked = beam
            .filter { space.isComplete(it, task) }
            .map { state -> WritingSearchResult(state, space.score(state, task)) }
            .sortedWith(
                compareBy<WritingSearchResult<State>> { space.searchPriority(it.state, task) }
                    .thenBy { it.breakdown.total }
                    .thenBy { space.diversityKey(it.state) }
            )
            .distinctBy { space.diversityGroupKey(it.state) }
        return selectDiverseResults(ranked, task.searchConfig, space)
    }

    private fun <State, Candidate> selectDiverseResults(
        ranked: List<WritingSearchResult<State>>,
        config: SearchConfig,
        space: ScoredCandidateSpace<State, Candidate>,
    ): List<WritingSearchResult<State>> {
        if (config.diversityWeight == 0.0 || ranked.size <= 1) {
            return ranked.take(config.maxResults)
        }
        val selected = mutableListOf<WritingSearchResult<State>>()
        val remaining = ranked.toMutableList()
        while (selected.size < config.maxResults && remaining.isNotEmpty()) {
            val next = remaining.minWith(
                compareBy<WritingSearchResult<State>> { candidate ->
                    val similarityPenalty = selected.maxOfOrNull { chosen ->
                        space.similarity(candidate.state, chosen.state)
                    } ?: 0.0
                    candidate.breakdown.total + similarityPenalty * config.diversityWeight
                }.thenBy { space.diversityKey(it.state) }
            )
            selected += next
            remaining.remove(next)
        }
        return selected
    }

    private const val MAX_SEARCH_STEPS = 512
}


/**
 * 面向约束程序的贪心深度优先搜索。
 *
 * 每一层只暂存当前节点的候选，按完整前缀评分（含声部移动成本）优先深入；得到足够的
 * 不同解后立即停止，避免束搜索把所有层级组合同时物化。
 */
enum class WritingSearchTraceEventKind {
    EXPANDED,
    LAYER_COMPLETED,
    HARD_PRUNED,
    DEAD_END,
    SOLUTION,
    BUDGET_EXHAUSTED,
    CANCELLED,

    // 多样化重启阶段（diverse-search.md §9）
    SEED_SOLUTION,
    MUTATION_RESTART,
    DIVERSITY_REJECTED,
    REJOIN_PRUNED,
    NEIGHBORHOOD_EXHAUSTED,
}

data class WritingSearchTraceEntry(
    val kind: WritingSearchTraceEventKind,
    val depth: Int,
    val state: String,
    val candidateCount: Int = 0,
    val acceptedCount: Int = 0,
    val totalScore: Double? = null,
    val hardViolations: List<String> = emptyList(),
    /** DP-only layer metrics; zero for ordinary DFS entries. */
    val generatedLabels: Int = 0,
    val distinctStates: Int = 0,
    val retainedLabels: Int = 0,
    val evaluatedTransitions: Int = 0,
    /** Evaluated edges in strict/omission/inner-fifth/soprano-sixth/wider order. */
    val transitionTierCounts: List<Int> = emptyList(),
    /** Surviving labels in the same relaxation-tier order. */
    val acceptedTransitionTierCounts: List<Int> = emptyList(),
)

data class WritingSearchTrace(
    val nodeBudget: Int,
    val visitedNodes: Int,
    val exhaustedBudget: Boolean,
    val cancelled: Boolean = false,
    val entries: List<WritingSearchTraceEntry>,
    val backend: SearchBackend = SearchBackend.GREEDY_DFS,
    val bounded: Boolean = false,
    val fallbackReason: String? = null,
    val candidateLayersTruncated: Boolean = false,
    val frontierTruncated: Boolean = false,
    val equivalentLabelsTruncated: Boolean = false,
    val transitionCandidatesTruncated: Boolean = false,
    /** DP transition scoring is the expensive work unit and is deliberately separate from DFS nodes. */
    val evaluatedTransitions: Int = 0,
    val transitionBudget: Int? = null,
    /** Some terminal-only soft rules are evaluated after the bounded local-state frontier is built. */
    val boundedGlobalRerank: Boolean = false,
    /**
     * 终层实际展开全局规则的完整路径数。终层按基础分做 branch-and-bound，只对可能进入 top-k
     * 的路径评估全局规则，因此该值通常远小于终层接受的转移数。
     */
    val terminalGlobalEvaluations: Int = 0,
    /** Human-readable DP state requirements compiled from the active rules, one entry per layer. */
    val dpStatePlan: List<String> = emptyList(),
    val dpCoveredRules: List<String> = emptyList(),
    val dpTerminalRerankRules: List<String> = emptyList(),
)

data class WritingSearchTraceResult<State>(
    val results: List<WritingSearchResult<State>>,
    val trace: WritingSearchTrace,
)

/**
 * 面向约束程序的贪心深度优先搜索。
 *
 * 每一层只暂存当前节点的候选，按完整前缀评分（含声部移动成本）优先深入；得到足够的
 * 不同解后立即停止，避免束搜索把所有层级组合同时物化。
 */
object GreedyDepthFirstSolver {
    fun <State, Candidate> solve(
        task: WritingTask,
        space: ScoredCandidateSpace<State, Candidate>,
        cancellation: SearchCancellation = SearchCancellation.NONE,
        excludedDiversityGroups: Set<String> = emptySet(),
    ): List<WritingSearchResult<State>> =
        if (task.searchConfig.diversity.enabled) {
            DiversifiedRestartSolver.run(
                task,
                space,
                describe = space::diversityKey,
                cancellation = cancellation,
                initialSeenGroups = excludedDiversityGroups,
            ).toResults()
        } else if (task.searchConfig.prefixDiversity.enabled) {
            DiversePrefixBeamSolver.run(
                task,
                space,
                describe = space::diversityKey,
                cancellation = cancellation,
                initialSeenGroups = excludedDiversityGroups,
            ).toResults()
        } else {
            coreRun(
                task,
                space,
                describe = space::diversityKey,
                cancellation = cancellation,
                seenGroups = excludedDiversityGroups.toMutableSet(),
            ).toResults()
        }

    fun <State, Candidate> solveWithTrace(
        task: WritingTask,
        space: ScoredCandidateSpace<State, Candidate>,
        maxEntries: Int = DEFAULT_TRACE_ENTRIES,
        describe: (State) -> String = space::diversityKey,
        depthOf: (State) -> Int = { 0 },
        cancellation: SearchCancellation = SearchCancellation.NONE,
        excludedDiversityGroups: Set<String> = emptySet(),
    ): WritingSearchTraceResult<State> {
        require(maxEntries >= 0) { "maxEntries must be non-negative" }
        val entries = mutableListOf<WritingSearchTraceEntry>()
        val record: (WritingSearchTraceEntry) -> Unit = { entry ->
            if (entries.size < maxEntries) entries += entry
        }
        val run = if (task.searchConfig.diversity.enabled) {
            DiversifiedRestartSolver.run(
                task, space, describe, depthOf, record, cancellation, excludedDiversityGroups,
            )
        } else if (task.searchConfig.prefixDiversity.enabled) {
            DiversePrefixBeamSolver.run(
                task, space, describe, depthOf, record, cancellation, excludedDiversityGroups,
            )
        } else {
            coreRun(
                task, space, describe, depthOf, record,
                cancellation = cancellation,
                seenGroups = excludedDiversityGroups.toMutableSet(),
            )
        }
        return WritingSearchTraceResult(
            results = run.toResults(),
            trace = WritingSearchTrace(
                nodeBudget = run.nodeBudget,
                visitedNodes = run.visitedNodes,
                exhaustedBudget = run.exhaustedBudget,
                cancelled = run.cancelled,
                entries = entries,
            ),
        )
    }

    /**
     * 确定性贪心 DFS。透传祖先链以便多样化阶段以任意前缀作为变异重启点（diverse-search.md §2.1）。
     * [budget] 允许多样化搜索跨阶段共享节点预算；默认按 [SearchConfig.nodeBudget] 自建。
     */
    internal fun <State, Candidate> coreRun(
        task: WritingTask,
        space: ScoredCandidateSpace<State, Candidate>,
        describe: (State) -> String,
        depthOf: (State) -> Int = { 0 },
        record: (WritingSearchTraceEntry) -> Unit = {},
        budget: NodeBudget = NodeBudget(task.searchConfig.nodeBudget),
        maxResults: Int = task.searchConfig.maxResults,
        seenGroups: MutableSet<String> = mutableSetOf(),
        cancellation: SearchCancellation = SearchCancellation.NONE,
    ): SearchRun<State> {
        val solutions = mutableListOf<SolutionWithChain<State>>()

        fun visit(state: State, path: List<State>) {
            if (solutions.size >= maxResults) return
            if (cancellation.isCancelled()) {
                record(traceEntry(WritingSearchTraceEventKind.CANCELLED, depthOf(state), describe(state)))
                return
            }
            if (!budget.tryConsume()) {
                record(traceEntry(WritingSearchTraceEventKind.BUDGET_EXHAUSTED, 0, describe(state)))
                return
            }
            val depth = depthOf(state)
            if (space.isComplete(state, task)) {
                val score = space.score(state, task)
                if (score.hasHardViolation) {
                    record(
                        traceEntry(
                            WritingSearchTraceEventKind.HARD_PRUNED, depth, describe(state),
                            totalScore = score.total, hardViolations = hardViolations(score),
                        )
                    )
                } else if (seenGroups.add(space.diversityGroupKey(state))) {
                    solutions += SolutionWithChain(WritingSearchResult(state, score), path)
                    record(
                        traceEntry(
                            WritingSearchTraceEventKind.SOLUTION, depth, describe(state),
                            totalScore = score.total,
                        )
                    )
                }
                return
            }

            val candidates = space.candidates(state, task)
            val scored = candidates.map { candidate ->
                val next = space.apply(state, candidate)
                next to space.score(next, task)
            }
            val accepted = scored
                .filterNot { (_, score) -> score.hasHardViolation }
                .sortedWith(
                    compareBy<Pair<State, ScoreBreakdown>> { space.searchPriority(it.first, task) }
                        .thenBy { it.second.total }
                        .thenBy { space.diversityKey(it.first) },
                )
            record(
                traceEntry(
                    WritingSearchTraceEventKind.EXPANDED, depth, describe(state),
                    candidateCount = candidates.size, acceptedCount = accepted.size,
                )
            )
            scored
                .asSequence()
                .filter { (_, score) -> score.hasHardViolation }
                .take(MAX_PRUNES_PER_EXPANSION)
                .forEach { (next, score) ->
                    record(
                        traceEntry(
                            WritingSearchTraceEventKind.HARD_PRUNED, depth + 1, describe(next),
                            totalScore = score.total, hardViolations = hardViolations(score),
                        )
                    )
                }
            if (accepted.isEmpty()) {
                record(
                    traceEntry(
                        WritingSearchTraceEventKind.DEAD_END, depth, describe(state),
                        candidateCount = candidates.size,
                        hardViolations = scored
                            .asSequence()
                            .flatMap { (_, score) -> hardViolations(score).asSequence() }
                            .distinct()
                            .take(MAX_DEAD_END_REASONS)
                            .toList()
                            .ifEmpty { listOf("下一槽没有满足关系约束的候选。") },
                    )
                )
                return
            }
            for ((next, _) in accepted) {
                if (cancellation.isCancelled()) {
                    record(traceEntry(WritingSearchTraceEventKind.CANCELLED, depth, describe(state)))
                    break
                }
                visit(next, path + next)
            }
        }

        val initial = space.initial(task)
        visit(initial, listOf(initial))
        return SearchRun(
            solutions = solutions.sortedWith(
                compareBy<SolutionWithChain<State>> { space.searchPriority(it.result.state, task) }
                    .thenBy { it.result.breakdown.total }
                    .thenBy { space.diversityKey(it.result.state) },
            ),
            nodeBudget = budget.limit,
            visitedNodes = budget.consumed,
            exhaustedBudget = budget.exhausted,
            cancelled = cancellation.isCancelled(),
        )
    }

    internal fun hardViolations(score: ScoreBreakdown): List<String> =
        score.findings
            .asSequence()
            .filter { it.kind == RuleFindingKind.VIOLATION && it.severity == RuleSeverity.HARD }
            .map { "${it.ruleId.value}: ${it.message}" }
            .distinct()
            .toList()

    internal fun traceEntry(
        kind: WritingSearchTraceEventKind,
        depth: Int,
        state: String,
        candidateCount: Int = 0,
        acceptedCount: Int = 0,
        totalScore: Double? = null,
        hardViolations: List<String> = emptyList(),
    ): WritingSearchTraceEntry =
        WritingSearchTraceEntry(kind, depth, state, candidateCount, acceptedCount, totalScore, hardViolations)

    internal const val DEFAULT_TRACE_ENTRIES = 128
    internal const val MAX_PRUNES_PER_EXPANSION = 8
    internal const val MAX_DEAD_END_REASONS = 6
}

/** 完整解及其前缀链 [S0, S1, ..., Sn]，chain[m] 为已完成 [0, m) 槽的状态（含初始空状态）。 */
internal data class SolutionWithChain<State>(
    val result: WritingSearchResult<State>,
    val chain: List<State>,
)

internal data class SearchRun<State>(
    val solutions: List<SolutionWithChain<State>>,
    val nodeBudget: Int,
    val visitedNodes: Int,
    val exhaustedBudget: Boolean,
    val cancelled: Boolean = false,
) {
    fun toResults(): List<WritingSearchResult<State>> = solutions.map { it.result }
}

/** 跨阶段共享的节点预算计数器（diverse-search.md §7：总预算覆盖首解与全部重启）。 */
internal class NodeBudget(val limit: Int) {
    var consumed: Int = 0
        private set
    var exhausted: Boolean = false
        private set

    val remaining: Int get() = (limit - consumed).coerceAtLeast(0)

    /** 消费一个节点；预算耗尽时返回 false 并标记 [exhausted]。 */
    fun tryConsume(): Boolean {
        if (consumed >= limit) {
            exhausted = true
            return false
        }
        consumed++
        return true
    }
}
