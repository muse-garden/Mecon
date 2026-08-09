package com.mecon.theory

/**
 * Deterministic, score-aware beam search for callers that need the first result to compare
 * different early realizations instead of exhausting one greedy DFS branch.
 */
internal object DiversePrefixBeamSolver {
    private data class PrefixNode<State>(
        val state: State,
        val score: ScoreBreakdown,
        val path: List<State>,
        val lineageKey: String? = null,
    )

    fun <State, Candidate> run(
        task: WritingTask,
        space: ScoredCandidateSpace<State, Candidate>,
        describe: (State) -> String = space::diversityKey,
        depthOf: (State) -> Int = { 0 },
        record: (WritingSearchTraceEntry) -> Unit = {},
        cancellation: SearchCancellation = SearchCancellation.NONE,
        initialSeenGroups: Set<String> = emptySet(),
        budget: NodeBudget = NodeBudget(task.searchConfig.nodeBudget),
    ): SearchRun<State> {
        val initial = space.initial(task)
        if (!budget.tryConsume()) {
            return SearchRun(emptyList(), budget.limit, budget.consumed, true)
        }
        var frontier = listOf(PrefixNode(initial, space.score(initial, task), listOf(initial)))
        val width = maxOf(
            task.searchConfig.prefixDiversity.frontierWidth,
            task.searchConfig.maxResults,
        )

        while (frontier.any { !space.isComplete(it.state, task) }) {
            if (cancellation.isCancelled()) {
                record(
                    GreedyDepthFirstSolver.traceEntry(
                        WritingSearchTraceEventKind.CANCELLED,
                        depthOf(frontier.first().state),
                        describe(frontier.first().state),
                    )
                )
                return SearchRun(
                    emptyList(), budget.limit, budget.consumed, budget.exhausted, cancelled = true,
                )
            }
            val expanded = mutableListOf<PrefixNode<State>>()
            for (node in frontier) {
                if (space.isComplete(node.state, task)) {
                    expanded += node
                    continue
                }
                val candidates = space.candidates(node.state, task)
                var acceptedCount = 0
                for (candidate in candidates) {
                    if (cancellation.isCancelled() || !budget.tryConsume()) break
                    val next = space.apply(node.state, candidate)
                    val score = space.score(next, task)
                    if (score.hasHardViolation) {
                        record(
                            GreedyDepthFirstSolver.traceEntry(
                                WritingSearchTraceEventKind.HARD_PRUNED,
                                depthOf(next),
                                describe(next),
                                totalScore = score.total,
                                hardViolations = GreedyDepthFirstSolver.hardViolations(score),
                            )
                        )
                    } else {
                        acceptedCount++
                        expanded += PrefixNode(
                            state = next,
                            score = score,
                            path = node.path + next,
                            lineageKey = node.lineageKey ?: space.prefixDiversityGroupKey(next),
                        )
                    }
                }
                record(
                    GreedyDepthFirstSolver.traceEntry(
                        WritingSearchTraceEventKind.EXPANDED,
                        depthOf(node.state),
                        describe(node.state),
                        candidateCount = candidates.size,
                        acceptedCount = acceptedCount,
                    )
                )
                if (budget.exhausted || cancellation.isCancelled()) break
            }
            if (expanded.isEmpty()) break
            frontier = selectDiversePrefixes(
                nodes = expanded,
                limit = width,
                config = task.searchConfig.prefixDiversity,
                space = space,
            )
            if (budget.exhausted) break
        }

        val seenGroups = initialSeenGroups.toMutableSet()
        val solutions = frontier
            .asSequence()
            .filter { space.isComplete(it.state, task) }
            .sortedWith(
                compareBy<PrefixNode<State>> { it.score.total }
                    .thenBy { space.diversityKey(it.state) }
            )
            .filter { seenGroups.add(space.diversityGroupKey(it.state)) }
            .take(task.searchConfig.maxResults)
            .map { node ->
                record(
                    GreedyDepthFirstSolver.traceEntry(
                        WritingSearchTraceEventKind.SOLUTION,
                        depthOf(node.state),
                        describe(node.state),
                        totalScore = node.score.total,
                    )
                )
                SolutionWithChain(WritingSearchResult(node.state, node.score), node.path)
            }
            .toList()
        return SearchRun(
            solutions = solutions,
            nodeBudget = budget.limit,
            visitedNodes = budget.consumed,
            exhaustedBudget = budget.exhausted,
            cancelled = cancellation.isCancelled(),
        )
    }

    private fun <State, Candidate> selectDiversePrefixes(
        nodes: List<PrefixNode<State>>,
        limit: Int,
        config: PrefixDiversitySearchConfig,
        space: ScoredCandidateSpace<State, Candidate>,
    ): List<PrefixNode<State>> {
        val remaining = nodes
            .sortedWith(
                compareBy<PrefixNode<State>> { it.score.total }
                    .thenBy { space.diversityKey(it.state) }
            )
            .distinctBy { space.diversityKey(it.state) }
            .toMutableList()
        if (remaining.size <= limit) return remaining

        val selected = mutableListOf<PrefixNode<State>>()
        val qualityCeiling = remaining.first().score.total + config.scoreTolerance
        val lineageRepresentatives = remaining
            .filter { it.lineageKey != null && it.score.total <= qualityCeiling }
            .groupBy { it.lineageKey }
            .values
            .map { siblings ->
                siblings.minWith(
                    compareBy<PrefixNode<State>> { it.score.total }
                        .thenBy { space.diversityKey(it.state) }
                )
            }
            .sortedWith(
                compareBy<PrefixNode<State>> { it.score.total }
                    .thenBy { space.diversityKey(it.state) }
            )
            .take(limit)
        selected += lineageRepresentatives
        remaining.removeAll(lineageRepresentatives.toSet())
        val localRepresentatives = remaining
            .filter { it.score.total <= qualityCeiling }
            .groupBy { space.prefixDiversityGroupKey(it.state) }
            .values
            .map { stratum ->
                stratum.minWith(
                    compareBy<PrefixNode<State>> { it.score.total }
                        .thenBy { space.diversityKey(it.state) }
                )
            }
            .sortedWith(
                compareBy<PrefixNode<State>> { it.score.total }
                    .thenBy { space.diversityKey(it.state) }
            )
            .take((limit - selected.size).coerceAtLeast(0))
        selected += localRepresentatives
        remaining.removeAll(localRepresentatives.toSet())
        if (selected.isEmpty()) selected += remaining.removeAt(0)
        val bestScore = selected.first().score.total
        while (selected.size < limit && remaining.isNotEmpty()) {
            val good = remaining.filter { it.score.total <= bestScore + config.scoreTolerance }
            val pool = good.ifEmpty { remaining }
            val next = pool.minWith(
                compareBy<PrefixNode<State>> { candidate ->
                    val similarity = selected.maxOf { chosen ->
                        space.prefixSimilarity(candidate.state, chosen.state)
                    }
                    candidate.score.total + similarity * config.similarityWeight
                }.thenBy { it.score.total }
                    .thenBy { space.diversityKey(it.state) }
            )
            selected += next
            remaining.remove(next)
        }
        return selected
    }
}
