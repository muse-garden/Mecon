package com.mecon.theory.constraint

import com.mecon.api.primitive.EventId
import com.mecon.theory.DynamicProgrammingSearchMode
import com.mecon.theory.FixedVoice
import com.mecon.theory.FixedVoiceIncrementalScore
import com.mecon.theory.FixedVoiceVerticality
import com.mecon.theory.FixedVoiceWritingCandidateSpace
import com.mecon.theory.FixedVoiceWritingFrame
import com.mecon.theory.FixedVoiceWritingState
import com.mecon.theory.RuleFinding
import com.mecon.theory.RuleId
import com.mecon.theory.RuleProfile
import com.mecon.theory.RuleSeverity
import com.mecon.theory.SearchBackend
import com.mecon.theory.SearchPriority
import com.mecon.theory.WritingSearchResult
import com.mecon.theory.WritingSearchTrace
import com.mecon.theory.WritingSearchTraceEntry
import com.mecon.theory.WritingSearchTraceEventKind
import com.mecon.theory.WritingSearchTraceResult

internal data class LayeredDpCapability(
    val supported: Boolean,
    val autoPreferred: Boolean = false,
    val reason: String? = null,
    val requiresBoundedGlobalRerank: Boolean = false,
    val statePlan: LayeredDpStatePlan? = null,
) {
    companion object {
        fun analyze(program: ConstraintProgram): LayeredDpCapability {
            val plan = LayeredDpStatePlanner.collect(program)
            if (!plan.supported) {
                return LayeredDpCapability(
                    supported = false,
                    reason = "Layered DP capability audit failed: ${plan.unsupportedReasons.joinToString("; ")}",
                    statePlan = plan,
                )
            }
            val autoPreferred = program.searchConfig.prefixDiversity.enabled ||
                program.searchConfig.diversity.enabled
            return LayeredDpCapability(
                supported = true,
                // Free-practice diversity needs several competing prefixes at every depth; the DP
                // frontier now owns that contract directly. Plain single-result programs keep the
                // cheaper DFS default until their performance crossover is revalidated.
                autoPreferred = autoPreferred,
                reason = if (autoPreferred) null else
                    "Layered DP is available explicitly; AUTO keeps DFS for non-diverse search",
                requiresBoundedGlobalRerank = plan.requiresBoundedGlobalRerank,
                statePlan = plan,
            )
        }
    }
}

/** Layered shortest-path search for free-writing constraint programs. */
internal object ConstraintLayeredDynamicProgrammingSolver {
    fun solveWithTrace(
        search: ConstraintProgramSolver.ConstraintSearch,
        context: ConstraintSolveContext,
        maxEntries: Int,
    ): WritingSearchTraceResult<FixedVoiceWritingState<ChordTarget>> {
        val task = search.task
        val config = task.searchConfig.dynamicProgramming
        val exact = config.mode == DynamicProgrammingSearchMode.EXACT
        val preserveDiverseLabels = task.searchConfig.diversityWeight > 0.0 ||
            task.searchConfig.diversity.enabled || task.searchConfig.prefixDiversity.enabled
        val capability = LayeredDpCapability.analyze(search.program)
        val statePlan = requireNotNull(capability.statePlan)
        val activeSearchWidth = if (task.searchConfig.prefixDiversity.enabled) {
            maxOf(task.searchConfig.maxResults, task.searchConfig.prefixDiversity.frontierWidth)
        } else {
            task.searchConfig.maxResults
        }
        val boundedFrontierLimit = minOf(
            config.maxFrontierStates,
            maxOf(task.searchConfig.beamWidth, activeSearchWidth) * task.searchConfig.maxResults,
        )
        val traceRecorder = DpTraceRecorder(maxEntries)
        fun record(entry: WritingSearchTraceEntry) = traceRecorder.record(entry)

        var visited = 0
        var evaluatedTransitions = 0
        var terminalGlobalEvaluations = 0
        val terminalGlobalLowerBound = if (
            exact || preserveDiverseLabels || context.excludedDiversityGroupKeys.isNotEmpty()
        ) {
            null
        } else {
            terminalGlobalScoreLowerBound(
                program = search.program,
                policy = search.space.dpScorePolicy,
                profile = task.ruleProfile,
            )
        }
        var exhaustedBudget = false
        var cancelled = false
        var candidateLayerTruncated = false
        var frontierTruncated = false
        var labelsTruncated = false
        var transitionCandidatesTruncated = false
        // 状态摘要随 label 增量维护，构造 state key 时不再回扫整条前缀。
        val summaryPlan = DpStateSummaryPlan.compile(statePlan, search.program, search.voices)
        var frontier = listOf(
            DpLabel(
                state = search.space.initial(task),
                score = FixedVoiceIncrementalScore(),
                priority = SearchPriority.NEUTRAL,
                previousProfile = search.initialBoundary?.let(search.candidateFactory::candidateProfile),
                previousVerticality = search.initialBoundary?.let(search.space::verticalityOf),
                recentSignatures = emptyList(),
                lineageKey = null,
                constraintHistories = summaryPlan.initialConstraintHistories(),
                extremes = DpExtremeSummary.initial(summaryPlan.slots.size),
                pathMidi = IntArray(0),
            )
        )

        for (slotIndex in search.program.slotDomains.indices) {
            if (context.cancellation.isCancelled()) {
                cancelled = true
                record(traceEntry(WritingSearchTraceEventKind.CANCELLED, slotIndex, "layer $slotIndex"))
                break
            }
            val rawLayerFrames = buildList {
                for (target in search.program.slotDomains[slotIndex].targets) {
                    val batch = search.candidateFactory.layerCandidates(
                        slotIndex = slotIndex,
                        target = target,
                        // EXACT treats this as a hard memory limit and returns BudgetExhausted instead
                        // of silently truncating. BOUNDED treats it as the documented candidate cap.
                        maxCandidates = config.maxCandidatesPerTarget,
                        diversifyLocally = preserveDiverseLabels,
                    )
                    candidateLayerTruncated = candidateLayerTruncated || batch.truncated
                    if (exact && batch.truncated) {
                        exhaustedBudget = true
                        record(
                            traceEntry(
                                WritingSearchTraceEventKind.BUDGET_EXHAUSTED,
                                slotIndex,
                                "exact candidate layer exceeded ${config.maxCandidatesPerTarget} per target",
                            )
                        )
                        break
                    }
                    addAll(batch.frames)
                }
            }
            if (exhaustedBudget) break
            if (rawLayerFrames.isEmpty()) {
                record(traceEntry(WritingSearchTraceEventKind.DEAD_END, slotIndex, "empty candidate layer"))
                frontier = emptyList()
                break
            }
            val prePruneSpacing = search.feasibilityPolicy != null &&
                task.ruleProfile.canPrePrune(WindowFeasibilityRuleProvider.ADJACENT_SPACING)
            val prePruneLargeLeaps = search.feasibilityPolicy != null &&
                task.ruleProfile.canPrePrune(WindowFeasibilityRuleProvider.SIMULTANEOUS_LARGE_LEAPS)
            val largeLeapBoundaryForcedHard = task.ruleProfile
                .configFor(WindowFeasibilityRuleProvider.SIMULTANEOUS_LARGE_LEAPS)
                .severityOverride == RuleSeverity.HARD
            val layerFrames = rawLayerFrames.mapNotNull { frame ->
                val spacing = search.feasibilityPolicy
                    ?.takeIf { prePruneSpacing }
                    ?.adjacentSpacingViolations(search.voices, frame)
                    .orEmpty()
                if (spacing.isNotEmpty()) {
                    if (traceRecorder.canRecord(WritingSearchTraceEventKind.HARD_PRUNED)) {
                        record(
                            traceEntry(
                                kind = WritingSearchTraceEventKind.HARD_PRUNED,
                                depth = slotIndex + 1,
                                state = "slot$slotIndex: pre-pruned adjacent spacing",
                                hardViolations = spacing.map { violation ->
                                    "${WindowFeasibilityRuleProvider.ADJACENT_SPACING.value}: " +
                                        "相邻声部间距 ${violation.distance} 个半音，" +
                                        "超过搜索上限 ${violation.limit}。"
                                },
                            )
                        )
                    }
                    null
                } else {
                    val verticality = search.space.verticalityOf(frame)
                    val profile = search.candidateFactory.candidateProfile(frame)
                    DpLayerFrame(
                        frame = frame,
                        profile = profile,
                        verticality = verticality,
                        sharedVerticalFindings = search.space.dpPrefixIndependentVerticalFindings(
                            representativeState = frontier.first().state.copy(
                                frames = frontier.first().state.frames + frame,
                            ),
                            frame = frame,
                            verticality = verticality,
                        ),
                        tieBreakKey = frameTieBreakKey(frame, search.voices),
                        signature = DpVoiceFrameSignature.of(frame, search.voices),
                        extremeScopeMatches = BooleanArray(summaryPlan.slots.size) { index ->
                            search.program.constraints[summaryPlan.slots[index].constraintIndex]
                                .scope.matches(frame.slotIndex, frame.target)
                        },
                    )
                }
            }
            if (layerFrames.isEmpty()) {
                record(traceEntry(WritingSearchTraceEventKind.DEAD_END, slotIndex, "empty feasible candidate layer"))
                frontier = emptyList()
                break
            }

            // 出边宽度与比较器对整层恒定，不随前驱标签变化：提到层外算一次。
            val boundedOutgoingPerTarget = maxOf(
                MIN_BOUNDED_OUTGOING_PER_TARGET,
                task.searchConfig.maxResults * BOUNDED_OUTGOING_PER_RESULT,
                if (preserveDiverseLabels) task.searchConfig.candidateLimit else 0,
            ).coerceAtMost(task.searchConfig.candidateLimit)
            val outgoingLimit = boundedOutgoingPerTarget *
                search.program.slotDomains[slotIndex].targets.size
            val comparator = labelComparator(search, exact)
            val labelLimit = maxOf(config.maxLabelsPerState, task.searchConfig.maxResults)
            val retentionPoolLimit = if (preserveDiverseLabels) {
                maxOf(
                    labelLimit * DIVERSE_LABEL_POOL_MULTIPLIER,
                    task.searchConfig.prefixDiversity.frontierWidth,
                    task.searchConfig.diversity.mutationPoolSize * task.searchConfig.maxResults,
                )
            } else {
                labelLimit
            }
            val grouped = LinkedHashMap<DpStateKey, MutableList<DpLabel>>()
            var generatedLabels = 0
            val transitionTierCounts = IntArray(TransitionRelaxationTier.entries.size)
            val acceptedTransitionTierCounts = IntArray(TransitionRelaxationTier.entries.size)
            // Count pre-truncation state identities without retaining every full key in bounded mode.
            val distinctStateHashes = hashSetOf<Int>()
            // 终层没有后继，state plan 为空：所有完整路径落进同一个状态组，只保留 labelLimit 条。
            // 因此终层不逐边展开全局规则，先收集基础分标签，再按可采纳下界做 branch-and-bound。
            val terminalLayer = slotIndex == search.program.slotDomains.lastIndex
            val terminalCandidates = if (terminalLayer) mutableListOf<DpLabel>() else null
            loop@ for (label in frontier) {
                if (visited >= task.searchConfig.nodeBudget) {
                    exhaustedBudget = true
                    record(traceEntry(WritingSearchTraceEventKind.BUDGET_EXHAUSTED, slotIndex, "layer $slotIndex"))
                    break@loop
                }
                visited++
                var accepted = 0
                val previousFrame = label.state.frames.lastOrNull() ?: search.initialBoundary
                val truncateOutgoing = !exact && layerFrames.size > outgoingLimit
                if (truncateOutgoing) transitionCandidatesTruncated = true
                val outgoingFrames = rankOutgoingFrames(
                    layerFrames = layerFrames,
                    previousProfile = label.previousProfile,
                    candidateFactory = search.candidateFactory,
                    limit = if (truncateOutgoing) outgoingLimit else null,
                )
                for (layerFrame in outgoingFrames) {
                    val frame = layerFrame.frame
                    if (context.cancellation.isCancelled()) {
                        cancelled = true
                        record(traceEntry(WritingSearchTraceEventKind.CANCELLED, slotIndex, "layer $slotIndex"))
                        break@loop
                    }
                    val relaxedBoundary = previousFrame?.slotIndex == -1 && search.relaxBoundaryLargeLeaps
                    val canPrePruneThisEdge = prePruneLargeLeaps &&
                        (!relaxedBoundary || largeLeapBoundaryForcedHard)
                    val leaping = if (previousFrame != null && canPrePruneThisEdge) {
                        search.feasibilityPolicy!!.simultaneousLargeLeapVoices(
                            search.voices,
                            previousFrame,
                            frame,
                        )
                    } else {
                        emptyList()
                    }
                    if (leaping.size > (search.feasibilityPolicy?.maxSimultaneousLargeLeapVoices ?: Int.MAX_VALUE)) {
                        if (traceRecorder.canRecord(WritingSearchTraceEventKind.HARD_PRUNED)) {
                            record(
                                traceEntry(
                                    kind = WritingSearchTraceEventKind.HARD_PRUNED,
                                    depth = slotIndex + 1,
                                    state = "slot$slotIndex: pre-pruned simultaneous large leaps",
                                    hardViolations = listOf(
                                        "${WindowFeasibilityRuleProvider.SIMULTANEOUS_LARGE_LEAPS.value}: " +
                                            "前后有 ${leaping.size} 个声部同时做超过八度的跳进。"
                                    ),
                                )
                            )
                        }
                        continue
                    }
                    if (evaluatedTransitions >= config.maxTransitionEvaluations) {
                        exhaustedBudget = true
                        record(
                            traceEntry(
                                WritingSearchTraceEventKind.BUDGET_EXHAUSTED,
                                slotIndex,
                                "transition budget ${config.maxTransitionEvaluations} exhausted",
                            )
                        )
                        break@loop
                    }
                    evaluatedTransitions++
                    val transitionTier = search.candidateFactory
                        .transitionTier(label.previousProfile, layerFrame.profile)
                    transitionTierCounts[transitionTier.ordinal]++
                    if (!relationConstraintsAllowFrame(search.program, search.voices, label.state, frame)) continue
                    val verticalFindings = layerFrame.sharedVerticalFindings +
                        search.space.dpPrefixSensitiveVerticalFindings(
                        representativeState = label.state.copy(frames = label.state.frames + frame),
                        frame = frame,
                        verticality = layerFrame.verticality,
                    )
                    val step = search.space.dpApplyAndScore(
                        state = label.state,
                        frame = frame,
                        verticalFindings = verticalFindings,
                        previousScore = label.score,
                        task = task,
                        includeGlobalFindings = false,
                        verticality = layerFrame.verticality,
                        previousVerticality = label.previousVerticality,
                    )
                    val next = step.state
                    if (step.score.hasHardViolation) {
                        if (traceRecorder.canRecord(WritingSearchTraceEventKind.HARD_PRUNED)) {
                            record(
                                traceEntry(
                                    kind = WritingSearchTraceEventKind.HARD_PRUNED,
                                    depth = next.frames.size,
                                    state = describe(next),
                                    hardViolations = step.score.findings
                                        .filter { it.severity == RuleSeverity.HARD }
                                        .map { "${it.ruleId.value}: ${it.message}" },
                                )
                            )
                        }
                        continue
                    }
                    accepted++
                    generatedLabels++
                    acceptedTransitionTierCounts[transitionTier.ordinal]++
                    val nextLabel = DpLabel(
                        state = next,
                        score = step.score,
                        // 路径优先级按转移放宽层增量扩展，等价于对整条路径重算。
                        priority = search.candidateFactory.extendPathPriority(
                            previous = label.priority,
                            previousFrame = label.previousProfile,
                            frame = layerFrame.profile,
                        ),
                        previousProfile = layerFrame.profile,
                        previousVerticality = layerFrame.verticality,
                        recentSignatures = if (summaryPlan.maxRecentFrames == 0) {
                            emptyList()
                        } else {
                            (label.recentSignatures + layerFrame.signature)
                                .takeLast(summaryPlan.maxRecentFrames)
                        },
                        lineageKey = label.lineageKey ?: search.space.prefixDiversityGroupKey(next),
                        constraintHistories = summaryPlan.extendConstraintHistories(
                            label.constraintHistories,
                            frame,
                        ),
                        extremes = label.extremes.extend(summaryPlan.slots, layerFrame),
                        pathMidi = label.pathMidi + layerFrame.profile.midi,
                    )
                    if (terminalCandidates != null) {
                        terminalCandidates += nextLabel
                        continue
                    }
                    val key = stateKey(nextLabel, summaryPlan, slotIndex)
                    distinctStateHashes += key.hashCode()
                    if (exact && key !in grouped && grouped.size >= config.maxFrontierStates) {
                        exhaustedBudget = true
                        record(
                            traceEntry(
                                WritingSearchTraceEventKind.BUDGET_EXHAUSTED,
                                slotIndex,
                                "exact frontier exceeded ${config.maxFrontierStates} states",
                            )
                        )
                        break@loop
                    }
                    val labels = grouped.getOrPut(key) { mutableListOf() }
                    if (insertRetainedLabel(labels, nextLabel, comparator, retentionPoolLimit)) {
                        labelsTruncated = true
                    }
                    if (!exact && grouped.size > boundedFrontierLimit * FRONTIER_PRUNE_MULTIPLIER) {
                        retainBestStateGroups(
                            grouped,
                            boundedFrontierLimit,
                            comparator,
                            search,
                            preserveDiverseLabels,
                        )
                        frontierTruncated = true
                    }
                }
                record(
                    traceEntry(
                        kind = WritingSearchTraceEventKind.EXPANDED,
                        depth = slotIndex,
                        state = describe(label.state),
                        candidateCount = layerFrames.size,
                        acceptedCount = accepted,
                    )
                )
            }
            if (cancelled || exhaustedBudget) break

            if (terminalCandidates != null) {
                val resolved = resolveTerminalLabels(
                    candidates = terminalCandidates,
                    search = search,
                    comparator = comparator,
                    labelLimit = terminalLabelLimit(
                        exact = exact,
                        labelLimit = labelLimit,
                        search = search,
                        excludedGroupCount = context.excludedDiversityGroupKeys.size,
                    ),
                    globalLowerBound = terminalGlobalLowerBound,
                    excludedGroupKeys = context.excludedDiversityGroupKeys,
                )
                terminalGlobalEvaluations += resolved.globalEvaluations
                if (resolved.truncated) labelsTruncated = true
                resolved.labels.forEach { label ->
                    val key = stateKey(label, summaryPlan, slotIndex)
                    distinctStateHashes += key.hashCode()
                    grouped.getOrPut(key) { mutableListOf() } += label
                }
            }

            if (terminalCandidates == null && preserveDiverseLabels) {
                grouped.values.forEach { labels ->
                    val selected = selectDiverseLabels(labels, labelLimit, search)
                    if (selected.size < labels.size) labelsTruncated = true
                    labels.clear()
                    labels += selected
                }
            }

            if (!exact && grouped.size > boundedFrontierLimit) {
                retainBestStateGroups(
                    grouped,
                    boundedFrontierLimit,
                    comparator,
                    search,
                    preserveDiverseLabels,
                )
                frontierTruncated = true
            }
            val retainedGroups = grouped.values.sortedWith { left, right ->
                comparator.compare(left.first(), right.first())
            }
            val nextFrontier = retainedGroups.flatten().sortedWith(comparator)
            record(
                traceEntry(
                    kind = WritingSearchTraceEventKind.LAYER_COMPLETED,
                    depth = slotIndex,
                    state = "layer $slotIndex",
                    candidateCount = layerFrames.size,
                    acceptedCount = generatedLabels,
                    generatedLabels = generatedLabels,
                    distinctStates = distinctStateHashes.size,
                    retainedLabels = nextFrontier.size,
                    evaluatedTransitions = evaluatedTransitions,
                    transitionTierCounts = transitionTierCounts.toList(),
                    acceptedTransitionTierCounts = acceptedTransitionTierCounts.toList(),
                )
            )
            frontier = nextFrontier
            if (frontier.isEmpty()) {
                record(traceEntry(WritingSearchTraceEventKind.DEAD_END, slotIndex + 1, "no surviving labels"))
                break
            }
        }

        val ranked = if (!cancelled && frontier.firstOrNull()?.state?.frames?.size == search.program.length) {
            frontier
                .asSequence()
                .filter { search.space.diversityGroupKey(it.state) !in context.excludedDiversityGroupKeys }
                .map { WritingSearchResult(it.state, search.space.dpBreakdown(it.score)) }
                .sortedWith(
                    compareBy<WritingSearchResult<FixedVoiceWritingState<ChordTarget>>> { it.breakdown.total }
                        .thenBy { search.space.diversityKey(it.state) }
                )
                .distinctBy { search.space.diversityGroupKey(it.state) }
                .toList()
        } else {
            emptyList()
        }
        val results = selectDiverseResults(ranked, search)
        results.forEach { result ->
            record(
                traceEntry(
                    kind = WritingSearchTraceEventKind.SOLUTION,
                    depth = result.state.frames.size,
                    state = describe(result.state),
                    totalScore = result.breakdown.total,
                )
            )
        }
        return WritingSearchTraceResult(
            results = results,
            trace = WritingSearchTrace(
                nodeBudget = task.searchConfig.nodeBudget,
                visitedNodes = visited,
                exhaustedBudget = exhaustedBudget,
                cancelled = cancelled,
                entries = traceRecorder.entries,
                backend = SearchBackend.LAYERED_DP,
                bounded = !exact,
                candidateLayersTruncated = candidateLayerTruncated,
                frontierTruncated = frontierTruncated,
                equivalentLabelsTruncated = labelsTruncated,
                transitionCandidatesTruncated = transitionCandidatesTruncated,
                evaluatedTransitions = evaluatedTransitions,
                transitionBudget = config.maxTransitionEvaluations,
                boundedGlobalRerank = !exact && capability.requiresBoundedGlobalRerank,
                terminalGlobalEvaluations = terminalGlobalEvaluations,
                dpStatePlan = statePlan.describeLayers(),
                dpCoveredRules = statePlan.coveredRuleIds.map { it.value }.sorted(),
                dpTerminalRerankRules = statePlan.terminalRerankRuleIds.map { it.value }.sorted(),
            ),
        )
    }

    private fun selectDiverseResults(
        ranked: List<WritingSearchResult<FixedVoiceWritingState<ChordTarget>>>,
        search: ConstraintProgramSolver.ConstraintSearch,
    ): List<WritingSearchResult<FixedVoiceWritingState<ChordTarget>>> {
        val config = search.task.searchConfig
        val remaining = ranked.sortedWith(
            compareBy<WritingSearchResult<FixedVoiceWritingState<ChordTarget>>> { it.breakdown.total }
                .thenBy { search.space.diversityKey(it.state) }
        ).toMutableList()
        if (!config.diversity.enabled && config.diversityWeight == 0.0) {
            return remaining.take(config.maxResults)
        }
        val selected = mutableListOf<WritingSearchResult<FixedVoiceWritingState<ChordTarget>>>()
        while (selected.size < config.maxResults && remaining.isNotEmpty()) {
            val eligible = if (config.diversity.enabled && selected.isNotEmpty()) {
                remaining.filter { candidate ->
                    selected.all { chosen -> meetsDiversityGate(candidate.state, chosen.state, search) }
                }
            } else {
                remaining
            }
            if (eligible.isEmpty()) break
            val next = eligible.minWith(
                compareBy<WritingSearchResult<FixedVoiceWritingState<ChordTarget>>> { candidate ->
                    val similarity = selected.maxOfOrNull { chosen ->
                        search.space.similarity(candidate.state, chosen.state)
                    } ?: 0.0
                    candidate.breakdown.total + similarity * config.diversityWeight
                }.thenBy { it.breakdown.total }
                    .thenBy { seededTieBreak(search.space.diversityKey(it.state), config.diversity.seed) }
            )
            selected += next
            remaining.remove(next)
        }
        return selected
    }

    private fun meetsDiversityGate(
        candidate: FixedVoiceWritingState<ChordTarget>,
        chosen: FixedVoiceWritingState<ChordTarget>,
        search: ConstraintProgramSolver.ConstraintSearch,
    ): Boolean {
        val config = search.task.searchConfig.diversity
        val candidateSlots = search.space.slotDiversityKeys(candidate)
        val chosenSlots = search.space.slotDiversityKeys(chosen)
        if (candidateSlots.size != chosenSlots.size || candidateSlots.isEmpty()) return false
        val changedSlotRatio = candidateSlots.indices.count { candidateSlots[it] != chosenSlots[it] }.toDouble() /
            candidateSlots.size.toDouble()
        val changedCellRatio = 1.0 - search.space.similarity(candidate, chosen)
        return changedSlotRatio + DIVERSITY_EPSILON >= config.minChangedSlotRatio &&
            changedCellRatio + DIVERSITY_EPSILON >= config.minChangedVoiceCellRatio
    }

    private fun selectDiverseLabels(
        labels: List<DpLabel>,
        limit: Int,
        search: ConstraintProgramSolver.ConstraintSearch,
    ): List<DpLabel> {
        if (labels.size <= limit) return labels.sortedWith(labelComparator(search, exact = false))
        val config = search.task.searchConfig.prefixDiversity
        val remaining = labels.sortedWith(
            compareBy<DpLabel> { it.score.total }.thenBy { it.diversityKey(search.space) }
        ).distinctBy { it.diversityKey(search.space) }.toMutableList()
        if (remaining.size <= limit) return remaining

        val selected = mutableListOf<DpLabel>()
        val qualityCeiling = remaining.first().score.total + if (config.enabled) {
            config.scoreTolerance
        } else {
            DIVERSE_LABEL_SCORE_TOLERANCE
        }
        val lineageRepresentatives = remaining
            .filter { it.lineageKey != null && it.score.total <= qualityCeiling }
            .groupBy { it.lineageKey }
            .values
            .map { siblings -> siblings.minBy { it.score.total } }
            .sortedWith(compareBy<DpLabel> { it.score.total }.thenBy { it.diversityKey(search.space) })
            .take(limit)
        selected += lineageRepresentatives
        remaining.removeAll(lineageRepresentatives.toSet())
        if (selected.isEmpty() && remaining.isNotEmpty()) selected += remaining.removeAt(0)
        val similarityWeight = if (config.enabled) config.similarityWeight else DEFAULT_DP_PREFIX_SIMILARITY_WEIGHT

        // 每个候选到已选集合的最大相似度按“新选中一个就更新一次”增量维护：整轮的相似度调用
        // 从 O(n × limit²)（比较器对每次比较的两个操作数各重算一次）降到 O(n × limit)。
        val pool = ArrayList(remaining)
        val maxSimilarity = DoubleArray(pool.size) { index ->
            selected.maxOf { chosen -> prefixSimilarity(pool[index], chosen) }
        }
        val taken = BooleanArray(pool.size)
        var remainingCount = pool.size
        while (selected.size < limit && remainingCount > 0) {
            var bestIndex = -1
            var bestWithinCeiling = false
            for (index in pool.indices) {
                if (taken[index]) continue
                val candidate = pool[index]
                val withinCeiling = candidate.score.total <= qualityCeiling
                if (bestIndex >= 0) {
                    // 天花板内的候选整体优先；只有全都超出天花板时才回退到全体比较。
                    if (bestWithinCeiling && !withinCeiling) continue
                    if (!(withinCeiling && !bestWithinCeiling) &&
                        !isBetterDiverseCandidate(
                            candidate = candidate,
                            candidateSimilarity = maxSimilarity[index],
                            incumbent = pool[bestIndex],
                            incumbentSimilarity = maxSimilarity[bestIndex],
                            similarityWeight = similarityWeight,
                            space = search.space,
                        )
                    ) continue
                }
                bestIndex = index
                bestWithinCeiling = withinCeiling
            }
            if (bestIndex < 0) break
            val next = pool[bestIndex]
            taken[bestIndex] = true
            remainingCount--
            selected += next
            for (index in pool.indices) {
                if (taken[index]) continue
                val similarity = prefixSimilarity(pool[index], next)
                if (similarity > maxSimilarity[index]) maxSimilarity[index] = similarity
            }
        }
        return selected
    }

    /**
     * 与 `FixedVoiceWritingCandidateSpace.prefixSimilarity` 同义（逐 (帧, 声部) 比较 MIDI 后取
     * 命中比例），但直接读 label 上展平的整型路径，省掉每次调用 `O(槽 × 声部)` 次按
     * `VoiceId` 的 map 查找与 `Pitch` 拆包。
     */
    private fun prefixSimilarity(left: DpLabel, right: DpLabel): Double {
        val leftPath = left.pathMidi
        val rightPath = right.pathMidi
        if (leftPath.size != rightPath.size || leftPath.isEmpty()) return 0.0
        var same = 0
        for (index in leftPath.indices) {
            if (leftPath[index] == rightPath[index]) same++
        }
        return same.toDouble() / leftPath.size.toDouble()
    }

    /**
     * 与原比较器逐分量等价：`(分数 + 相似度 × 权重, 分数, diversityKey)`。并列时保留先出现者，
     * 与 `minWith` 的语义一致。
     */
    private fun isBetterDiverseCandidate(
        candidate: DpLabel,
        candidateSimilarity: Double,
        incumbent: DpLabel,
        incumbentSimilarity: Double,
        similarityWeight: Double,
        space: FixedVoiceWritingCandidateSpace<ChordTarget>,
    ): Boolean {
        val candidateCost = candidate.score.total + candidateSimilarity * similarityWeight
        val incumbentCost = incumbent.score.total + incumbentSimilarity * similarityWeight
        if (candidateCost != incumbentCost) return candidateCost < incumbentCost
        if (candidate.score.total != incumbent.score.total) {
            return candidate.score.total < incumbent.score.total
        }
        return candidate.diversityKey(space) < incumbent.diversityKey(space)
    }

    private fun seededTieBreak(value: String, seed: Long): Long {
        var hash = seed xor 0x9E3779B97F4A7C15UL.toLong()
        value.forEach { character -> hash = (hash xor character.code.toLong()) * 0x100000001B3L }
        return hash
    }

    /**
     * 逐层状态 key 只读取标签上已增量维护好的摘要：最近帧签名是层常量，极值摘要随每帧 O(1) 更新。
     * 这里不再回扫整条前缀，也不再为每条转移重建声部音高列表。
     */
    private fun stateKey(
        label: DpLabel,
        plan: DpStateSummaryPlan,
        slotIndex: Int,
    ): DpStateKey {
        val recentCount = plan.recentFrameCounts[slotIndex]
        val recentFrames = when {
            recentCount == 0 -> emptyList()
            recentCount >= label.recentSignatures.size -> label.recentSignatures
            else -> label.recentSignatures.takeLast(recentCount)
        }
        val recent = recentFrames.map { signature ->
            signature.project(
                spelledPitches = plan.recentFramesNeedSpelling[slotIndex] &&
                    !plan.recentFramesHaveFixedTargets[slotIndex],
                targetSemantics = plan.recentFramesNeedTarget[slotIndex] &&
                    !plan.recentFramesHaveFixedTargets[slotIndex],
            )
        }
        val selected = plan.extremeSlotsByLayer[slotIndex]
        val extremes = IntArray(selected.size * 2) { position ->
            val slot = selected[position / 2]
            if (position % 2 == 0) label.extremes.values[slot] else label.extremes.occurrences[slot]
        }
        val histories = plan.constraintHistoryPositionsByLayer[slotIndex].map(label.constraintHistories::get)
        val compositeTruths = plan.compositeTruths(label.state, slotIndex)
        return DpStateKey(recent, extremes, histories, compositeTruths)
    }

    /**
     * 由逐层状态计划一次编译出的摘要布局：[slots] 是 (约束, 极值方向, 声部) 的笛卡尔展开，
     * [extremeSlotsByLayer] 是每层 key 要读取的槽下标。都只依赖程序本身，不随转移变化。
     */
    private class DpStateSummaryPlan(
        val slots: List<DpExtremeSlot>,
        val extremeSlotsByLayer: List<IntArray>,
        val recentFrameCounts: IntArray,
        val recentFramesNeedSpelling: BooleanArray,
        val recentFramesNeedTarget: BooleanArray,
        val recentFramesHaveFixedTargets: BooleanArray,
        val constraintHistorySpecs: List<DpConstraintHistorySpec>,
        val constraintHistoryPositionsByLayer: List<IntArray>,
        val compositeConstraintPositionsByLayer: List<IntArray>,
        val compositeConstraintIndices: IntArray,
        private val algebra: ConstraintAlgebraRuleProvider,
    ) {
        val maxRecentFrames: Int = recentFrameCounts.maxOrNull() ?: 0

        fun compositeTruths(
            state: FixedVoiceWritingState<ChordTarget>,
            layer: Int,
        ): List<DpCompositeConstraintTruth> {
            val positions = compositeConstraintPositionsByLayer[layer]
            if (positions.isEmpty()) return emptyList()
            val all = algebra.dpCompositeTruths(state, compositeConstraintIndices)
            return positions.map(all::get)
        }

        fun initialConstraintHistories(): List<DpConstraintHistorySignature> =
            constraintHistorySpecs.map { spec -> initialConstraintHistorySignature(spec.predicate) }

        fun extendConstraintHistories(
            previous: List<DpConstraintHistorySignature>,
            frame: FixedVoiceWritingFrame<ChordTarget>,
        ): List<DpConstraintHistorySignature> = constraintHistorySpecs.mapIndexed { position, spec ->
            extendConstraintHistorySignature(spec, previous[position], frame)
        }

        companion object {
            fun compile(
                statePlan: LayeredDpStatePlan,
                program: ConstraintProgram,
                voices: List<FixedVoice>,
            ): DpStateSummaryPlan {
                val needs = statePlan.layers.flatMap { it.voiceExtremes }.distinct()
                val slots = needs.flatMap { need ->
                    voices.indices
                        .filter { index -> need.voiceFilter.allows(voices[index]) }
                        .map { index ->
                            DpExtremeSlot(
                                constraintIndex = need.constraintIndex,
                                extreme = need.extreme,
                                voiceIndex = index,
                                maxOccurrences = need.maxOccurrences,
                            )
                        }
                }
                val constraintHistoryNeeds = statePlan.layers
                    .flatMap { layer -> layer.constraintHistories }
                    .distinct()
                    .sortedWith(compareBy({ it.constraintIndex }, { it.predicateIndex }))
                val constraintHistorySpecs = constraintHistoryNeeds.map { need ->
                    DpConstraintHistorySpec(
                        constraintIndex = need.constraintIndex,
                        predicateIndex = need.predicateIndex,
                        predicate = program.constraints[need.constraintIndex].expr
                            .atomicPredicates().elementAt(need.predicateIndex),
                        scope = program.constraints[need.constraintIndex].scope,
                    )
                }
                val positionByConstraintNeed = constraintHistoryNeeds
                    .mapIndexed { position, need -> need to position }
                    .toMap()
                val compositeConstraintIndices = statePlan.layers
                    .flatMap { layer -> layer.compositeTruths.map { it.constraintIndex } }
                    .distinct()
                    .sorted()
                    .toIntArray()
                val compositePositionByIndex = compositeConstraintIndices
                    .mapIndexed { position, index -> index to position }
                    .toMap()
                val recentFrameCounts = statePlan.layers.map { it.recentFrameCount }.toIntArray()
                return DpStateSummaryPlan(
                    slots = slots,
                    extremeSlotsByLayer = statePlan.layers.map { layer ->
                        slots.indices.filter { index ->
                            layer.voiceExtremes.any { need ->
                                need.constraintIndex == slots[index].constraintIndex &&
                                    need.extreme == slots[index].extreme
                            }
                        }.toIntArray()
                    },
                    recentFrameCounts = recentFrameCounts,
                    recentFramesNeedSpelling = statePlan.layers
                        .map { it.recentFramesNeedSpelling }
                        .toBooleanArray(),
                    recentFramesNeedTarget = statePlan.layers
                        .map { it.recentFramesNeedTarget }
                        .toBooleanArray(),
                    recentFramesHaveFixedTargets = statePlan.layers.mapIndexed { layer, _ ->
                        val count = recentFrameCounts[layer]
                        count == 0 || (layer - count + 1..layer).all { slot ->
                            program.slotDomains[slot].targets.size == 1
                        }
                    }.toBooleanArray(),
                    constraintHistorySpecs = constraintHistorySpecs,
                    constraintHistoryPositionsByLayer = statePlan.layers.map { layer ->
                        layer.constraintHistories
                            .map { need -> positionByConstraintNeed.getValue(need) }
                            .distinct()
                            .sorted()
                            .toIntArray()
                    },
                    compositeConstraintPositionsByLayer = statePlan.layers.map { layer ->
                        layer.compositeTruths
                            .map { need -> compositePositionByIndex.getValue(need.constraintIndex) }
                            .distinct()
                            .sorted()
                            .toIntArray()
                    },
                    compositeConstraintIndices = compositeConstraintIndices,
                    algebra = ConstraintAlgebraRuleProvider(program, voices),
                )
            }
        }
    }

    /**
     * 按转移局部优先级为一个前驱标签排出边。同一层的候选帧常量已在 [DpLayerFrame] 里算好，
     * 这里只做整型比较；[limit] 非空时只选出前 limit 条，不再对整层排序。
     */
    private fun rankOutgoingFrames(
        layerFrames: List<DpLayerFrame>,
        previousProfile: FrameCandidateProfile?,
        candidateFactory: ChordTargetCandidateFactory,
        limit: Int?,
    ): List<DpLayerFrame> {
        fun rank(layerFrame: DpLayerFrame) = RankedLayerFrame(
            layerFrame = layerFrame,
            priority = candidateFactory.localPriority(previousProfile, layerFrame.profile),
        )
        if (limit == null) {
            return layerFrames.map(::rank).sortedWith(RANKED_FRAME_COMPARATOR).map { it.layerFrame }
        }
        val best = ArrayList<RankedLayerFrame>(limit)
        layerFrames.forEach { layerFrame ->
            val candidate = rank(layerFrame)
            var insertion = best.size
            for (index in best.indices) {
                if (RANKED_FRAME_COMPARATOR.compare(candidate, best[index]) < 0) {
                    insertion = index
                    break
                }
            }
            if (insertion >= limit) return@forEach
            best.add(insertion, candidate)
            if (best.size > limit) best.removeAt(best.lastIndex)
        }
        return best.map { it.layerFrame }
    }

    /**
     * 终层 branch-and-bound。终层的 state key 恒为空，所有完整路径竞争同一组 [labelLimit] 个名额，
     * 而全局规则要在整条路径上求值——逐边展开的成本与它对结果的影响完全不成比例。
     *
     * 排序键 `(路径优先级, 基础分)` 与全局分无关，且全局分不低于 [globalLowerBound]。因此在
     * `(优先级, 基础分 + 下界)` 已严格劣于当前第 k 名时可以停：后续候选的两个分量都只会更差。
     * 用严格大于保证并列候选仍被展开，多样化 tie-break 结果与逐边展开完全一致。
     */
    private fun resolveTerminalLabels(
        candidates: List<DpLabel>,
        search: ConstraintProgramSolver.ConstraintSearch,
        comparator: Comparator<DpLabel>,
        labelLimit: Int,
        globalLowerBound: Double?,
        excludedGroupKeys: Set<String>,
    ): TerminalResolution {
        if (candidates.isEmpty()) return TerminalResolution(emptyList(), globalEvaluations = 0, truncated = false)
        val ordered = candidates.sortedWith(
            compareBy<DpLabel> { it.priority }.thenBy { it.score.total }
        )
        val retained = mutableListOf<DpLabel>()
        var truncated = false
        var globalEvaluations = 0
        for (candidate in ordered) {
            if (search.space.diversityGroupKey(candidate.state) in excludedGroupKeys) continue
            if (retained.size >= labelLimit && globalLowerBound != null) {
                val worst = retained.last()
                val priorityOrder = candidate.priority.compareTo(worst.priority)
                val dominated = priorityOrder > 0 ||
                    (priorityOrder == 0 && candidate.score.total + globalLowerBound > worst.score.total)
                if (dominated) {
                    truncated = true
                    break
                }
            }
            globalEvaluations++
            val score = search.space.dpAppendGlobalFindings(
                state = candidate.state,
                score = candidate.score,
                task = search.task,
            )
            if (score.hasHardViolation) continue
            if (insertRetainedLabel(retained, candidate.withScore(score), comparator, labelLimit)) {
                truncated = true
            }
        }
        return TerminalResolution(retained, globalEvaluations, truncated)
    }

    private fun terminalLabelLimit(
        exact: Boolean,
        labelLimit: Int,
        search: ConstraintProgramSolver.ConstraintSearch,
        excludedGroupCount: Int,
    ): Int {
        if (exact && excludedGroupCount > 0) return Int.MAX_VALUE
        val config = search.task.searchConfig
        val diversePool = maxOf(
            labelLimit * TERMINAL_LABEL_POOL_MULTIPLIER,
            config.maxResults + excludedGroupCount,
            config.prefixDiversity.frontierWidth,
            config.diversity.restartBudget * config.diversity.mutationPoolSize,
        )
        return if (
            config.diversityWeight > 0.0 || config.diversity.enabled ||
            config.prefixDiversity.enabled || excludedGroupCount > 0
        ) diversePool else labelLimit
    }

    private class TerminalResolution(
        val labels: List<DpLabel>,
        val globalEvaluations: Int,
        val truncated: Boolean,
    )

    /** 保持标签列表有序并裁到上限；返回是否发生截断。 */
    private fun insertRetainedLabel(
        labels: MutableList<DpLabel>,
        label: DpLabel,
        comparator: Comparator<DpLabel>,
        limit: Int,
    ): Boolean {
        var insertion = labels.size
        for (index in labels.indices) {
            if (comparator.compare(label, labels[index]) < 0) {
                insertion = index
                break
            }
        }
        if (insertion >= limit) return true
        labels.add(insertion, label)
        if (labels.size > limit) {
            labels.removeAt(labels.lastIndex)
            return true
        }
        return false
    }

    private fun labelComparator(
        search: ConstraintProgramSolver.ConstraintSearch,
        exact: Boolean,
    ): Comparator<DpLabel> = if (exact) {
        compareBy<DpLabel> { it.score.total }
            .thenBy { it.diversityKey(search.space) }
            .thenBy { it.priority }
    } else {
        compareBy<DpLabel> { it.priority }
            .thenBy { it.score.total }
            .thenBy { it.diversityKey(search.space) }
    }

    private fun frameTieBreakKey(
        frame: FixedVoiceWritingFrame<ChordTarget>,
        voices: List<FixedVoice>,
    ): String = buildString {
        append(frame.target.identityKey())
        append(':')
        voices.joinTo(this, separator = ",") { voice -> frame.pitchFor(voice).midiNumber.toString() }
    }

    private fun retainBestStateGroups(
        grouped: LinkedHashMap<DpStateKey, MutableList<DpLabel>>,
        limit: Int,
        comparator: Comparator<DpLabel>,
        search: ConstraintProgramSolver.ConstraintSearch,
        preserveDiverseLabels: Boolean,
    ) {
        val ordered = grouped.entries
            .sortedWith { left, right -> comparator.compare(left.value.first(), right.value.first()) }
        val retained = if (preserveDiverseLabels) {
            val selected = selectDiverseLabels(ordered.map { it.value.first() }, limit, search).toSet()
            // 按 state key 记录已入选组：此前 `it in diverse` 在 entry 列表上线性查找，
            // 每次比较还要逐条比对整组 label，是 O(n²) 的深比较。
            val diverseKeys = hashSetOf<DpStateKey>()
            val diverse = ordered.filterTo(mutableListOf()) { entry ->
                (entry.value.first() in selected).also { if (it) diverseKeys += entry.key }
            }
            if (diverse.size < limit) {
                diverse += ordered.asSequence()
                    .filter { it.key !in diverseKeys }
                    .take(limit - diverse.size)
            }
            diverse
        } else {
            ordered.take(limit)
        }
        grouped.clear()
        retained.forEach { (key, labels) -> grouped[key] = labels }
    }

    private fun describe(state: FixedVoiceWritingState<ChordTarget>): String =
        state.frames.lastOrNull()?.let { frame ->
            "slot${frame.slotIndex}: degree=${frame.target.degree}, inversion=${frame.target.inversion}"
        } ?: "start"

    private fun traceEntry(
        kind: WritingSearchTraceEventKind,
        depth: Int,
        state: String,
        candidateCount: Int = 0,
        acceptedCount: Int = 0,
        totalScore: Double? = null,
        hardViolations: List<String> = emptyList(),
        generatedLabels: Int = 0,
        distinctStates: Int = 0,
        retainedLabels: Int = 0,
        evaluatedTransitions: Int = 0,
        transitionTierCounts: List<Int> = emptyList(),
        acceptedTransitionTierCounts: List<Int> = emptyList(),
    ) = WritingSearchTraceEntry(
        kind = kind,
        depth = depth,
        state = state,
        candidateCount = candidateCount,
        acceptedCount = acceptedCount,
        totalScore = totalScore,
        hardViolations = hardViolations,
        generatedLabels = generatedLabels,
        distinctStates = distinctStates,
        retainedLabels = retainedLabels,
        evaluatedTransitions = evaluatedTransitions,
        transitionTierCounts = transitionTierCounts,
        acceptedTransitionTierCounts = acceptedTransitionTierCounts,
    )

    /**
     * 一条 DP 路径标签。[priority] 与前驱帧摘要随标签增量维护，比较器和下一层扩展都不再重新
     * 扫描整条路径；[diversityKey] 只在真正比较到该维度时才构造一次。
     */
    private class DpLabel(
        val state: FixedVoiceWritingState<ChordTarget>,
        val score: FixedVoiceIncrementalScore,
        val priority: SearchPriority,
        val previousProfile: FrameCandidateProfile?,
        val previousVerticality: FixedVoiceVerticality?,
        /** 最近若干帧的层常量签名，长度为整个计划要求的最大帧数。 */
        val recentSignatures: List<DpVoiceFrameSignature>,
        /** 仅在复合约束尚无专用自动机时保留，用于保证前缀等价合并正确。 */
        /** 第一层排列谱系，供同状态的 k-best/Pareto label 保留。 */
        val lineageKey: String?,
        val constraintHistories: List<DpConstraintHistorySignature>,
        val extremes: DpExtremeSummary,
        /** 整条前缀按 (帧, 声部) 展平的 MIDI；只供 [prefixSimilarity] 做整型比较。 */
        val pathMidi: IntArray,
    ) {
        private var cachedDiversityKey: String? = null

        fun diversityKey(space: FixedVoiceWritingCandidateSpace<ChordTarget>): String =
            cachedDiversityKey ?: space.diversityKey(state).also { cachedDiversityKey = it }

        fun withScore(score: FixedVoiceIncrementalScore): DpLabel =
            DpLabel(
                state,
                score,
                priority,
                previousProfile,
                previousVerticality,
                recentSignatures,
                lineageKey,
                constraintHistories,
                extremes,
                pathMidi,
            )
    }

    /** 一层里与前驱无关的候选常量：只算一次，供该层所有入边共享。 */
    private class DpLayerFrame(
        val frame: FixedVoiceWritingFrame<ChordTarget>,
        val profile: FrameCandidateProfile,
        val verticality: FixedVoiceVerticality,
        val sharedVerticalFindings: List<RuleFinding<EventId>>,
        val tieBreakKey: String,
        val signature: DpVoiceFrameSignature,
        /** 与 extremeSlots 一一对应：该帧是否落在对应约束的作用域内。 */
        val extremeScopeMatches: BooleanArray,
    )

    private class RankedLayerFrame(
        val layerFrame: DpLayerFrame,
        val priority: SearchPriority,
    )

    /** [voiceExtremes] 是 (极值, 出现次数) 交替的紧凑数组，与逐层计划选中的槽一一对应。 */
    private class DpStateKey(
        private val recentFrames: List<DpProjectedFrameSignature>,
        private val voiceExtremes: IntArray,
        private val constraintHistories: List<DpConstraintHistorySignature>,
        private val compositeTruths: List<DpCompositeConstraintTruth>,
    ) {
        private val hash: Int = (((recentFrames.hashCode() * 31 + voiceExtremes.contentHashCode()) * 31 +
            constraintHistories.hashCode()) * 31 + compositeTruths.hashCode())

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DpStateKey) return false
            return hash == other.hash &&
                recentFrames == other.recentFrames &&
                voiceExtremes.contentEquals(other.voiceExtremes) &&
                constraintHistories == other.constraintHistories &&
                compositeTruths == other.compositeTruths
        }
    }

    /** 一帧完整拼写音高与 target 语义的层常量签名；同层入边共享实例。 */
    private class DpVoiceFrameSignature(
        private val midi: IntArray,
        private val spelling: IntArray,
        private val target: DpTargetSemanticSignature,
    ) {
        private val hash: Int = (midi.contentHashCode() * 31 + spelling.contentHashCode()) * 31 + target.hashCode()

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            return other is DpVoiceFrameSignature &&
                target == other.target && midi.contentEquals(other.midi) && spelling.contentEquals(other.spelling)
        }

        fun project(
            spelledPitches: Boolean,
            targetSemantics: Boolean,
        ): DpProjectedFrameSignature = DpProjectedFrameSignature(
            midi = midi,
            spelling = spelling.takeIf { spelledPitches },
            target = target.takeIf { targetSemantics },
        )

        companion object {
            fun of(
                frame: FixedVoiceWritingFrame<ChordTarget>,
                voices: List<FixedVoice>,
            ): DpVoiceFrameSignature {
                val midi = IntArray(voices.size) { index -> frame.pitchFor(voices[index]).midiNumber }
                val spelling = IntArray(voices.size * 2) { index ->
                    val pitch = frame.pitchFor(voices[index / 2])
                    if (index % 2 == 0) pitch.diatonicSteps else pitch.chromaticOffset
                }
                return DpVoiceFrameSignature(midi, spelling, DpTargetSemanticSignature.of(frame.target))
            }
        }
    }

    private class DpProjectedFrameSignature(
        private val midi: IntArray,
        private val spelling: IntArray?,
        private val target: DpTargetSemanticSignature?,
    ) {
        private val hash = (midi.contentHashCode() * 31 + (spelling?.contentHashCode() ?: 0)) * 31 +
            (target?.hashCode() ?: 0)

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DpProjectedFrameSignature || hash != other.hash || target != other.target) return false
            return midi.contentEquals(other.midi) && when {
                spelling == null -> other.spelling == null
                other.spelling == null -> false
                else -> spelling.contentEquals(other.spelling)
            }
        }
    }

    private data class DpTargetSemanticSignature(
        val identity: String,
        val sonorityIdentity: String,
        val interpretationIdentity: String,
        val realizationIdentity: String,
        val degree: Int,
        val quality: String,
        val inversion: Int,
        val arity: String,
        val bassPitchClass: Int,
        val tonePitchClasses: List<Int?>,
        val tonalContextIds: List<String>,
        val primaryTonalContextId: String?,
        val chordDefinitionId: String?,
    ) {
        companion object {
            fun of(target: ChordTarget): DpTargetSemanticSignature = DpTargetSemanticSignature(
                identity = target.identityKey(),
                sonorityIdentity = target.sonorityIdentityKey(),
                interpretationIdentity = target.interpretationIdentityKey(),
                realizationIdentity = target.realizationIdentityKey(),
                degree = target.degree,
                quality = target.quality.name,
                inversion = target.inversion,
                arity = target.arity.name,
                bassPitchClass = target.bassPitchClass.value,
                tonePitchClasses = ChordTone.entries.map { target.pitchClassFor(it)?.value },
                tonalContextIds = target.tonalContextIds().map { it.value }.sorted(),
                primaryTonalContextId = target.primaryTonalContextId()?.value,
                chordDefinitionId = target.chordDefinitionId()?.value,
            )
        }
    }

    private sealed interface DpConstraintHistorySignature

    private data class DpIdentityHistorySignature(
        val seen: Set<String>,
    ) : DpConstraintHistorySignature

    private data class DpTargetMatchHistorySignature(
        val matched: Boolean,
        val sawApplicableTarget: Boolean,
    ) : DpConstraintHistorySignature

    private data class DpSameSonorityHistorySignature(
        val selectedPitchClassSets: List<List<Int>>,
        val anySelectedSlotInScope: Boolean,
    ) : DpConstraintHistorySignature

    private data class DpRootMotionHistorySignature(
        val fromDegree: Int?,
        val toDegree: Int?,
        val fromInScope: Boolean,
        val toInScope: Boolean,
    ) : DpConstraintHistorySignature

    private data class DpMinimumDistanceHistorySignature(
        val violated: Boolean,
        val lastSlotByDegree: List<Int>,
    ) : DpConstraintHistorySignature

    private data class DpDistinctProgressionHistorySignature(
        val violated: Boolean,
        val usedPairs: Set<Int>,
        val previousDegree: Int?,
        val previousInScope: Boolean,
    ) : DpConstraintHistorySignature

    private data class DpRootProgressionHistorySignature(
        val applicableDegrees: List<Int>,
    ) : DpConstraintHistorySignature

    private data class DpConstraintHistorySpec(
        val constraintIndex: Int,
        val predicateIndex: Int,
        val predicate: ConstraintPredicate,
        val scope: ConstraintScope,
    )

    private fun initialConstraintHistorySignature(predicate: ConstraintPredicate): DpConstraintHistorySignature {
        return when (predicate) {
            is ConstraintPredicate.DistinctIdentities -> DpIdentityHistorySignature(emptySet())
            is ConstraintPredicate.TargetMatches -> DpTargetMatchHistorySignature(false, false)
            is ConstraintPredicate.SameSonority -> DpSameSonorityHistorySignature(emptyList(), false)
            is ConstraintPredicate.RootDiatonicMotion -> DpRootMotionHistorySignature(null, null, false, false)
            is ConstraintPredicate.MinimumSimilarChordDistance ->
                DpMinimumDistanceHistorySignature(false, List(7) { -1 })
            ConstraintPredicate.DistinctSimilarChordProgressions ->
                DpDistinctProgressionHistorySignature(false, emptySet(), null, false)
            is ConstraintPredicate.RootProgressionPreference -> DpRootProgressionHistorySignature(emptyList())
            else -> error("Constraint history is not registered for ${predicate::class.simpleName}")
        }
    }

    private fun extendConstraintHistorySignature(
        spec: DpConstraintHistorySpec,
        previous: DpConstraintHistorySignature,
        frame: FixedVoiceWritingFrame<ChordTarget>,
    ): DpConstraintHistorySignature {
        val predicate = spec.predicate
        val inScope = spec.scope.matches(frame.slotIndex, frame.target)
        return when (predicate) {
            is ConstraintPredicate.DistinctIdentities -> {
                previous as DpIdentityHistorySignature
                if (!inScope || !predicate.requirement.window.contains(frame.slotIndex)) previous else
                    previous.copy(
                        seen = previous.seen + frame.target.identityKey(predicate.requirement.identityMode),
                    )
            }
            is ConstraintPredicate.TargetMatches -> {
                previous as DpTargetMatchHistorySignature
                val applicable = inScope && predicate.requirement.window.contains(frame.slotIndex)
                previous.copy(
                    matched = previous.matched ||
                        (applicable && predicate.requirement.selector.matches(frame.target)),
                    sawApplicableTarget = previous.sawApplicableTarget || applicable,
                )
            }
            is ConstraintPredicate.SameSonority -> {
                previous as DpSameSonorityHistorySignature
                if (frame.slotIndex !in predicate.slots) previous else previous.copy(
                    selectedPitchClassSets = previous.selectedPitchClassSets + listOf(
                        frame.target.sonority.pitchClasses.map { it.value }.distinct().sorted()
                    ),
                    anySelectedSlotInScope = previous.anySelectedSlotInScope || inScope,
                )
            }
            is ConstraintPredicate.RootDiatonicMotion -> {
                previous as DpRootMotionHistorySignature
                when (frame.slotIndex) {
                    predicate.fromSlot -> previous.copy(
                        fromDegree = frame.target.degree,
                        fromInScope = inScope,
                    )
                    predicate.toSlot -> previous.copy(
                        toDegree = frame.target.degree,
                        toInScope = inScope,
                    )
                    else -> previous
                }
            }
            is ConstraintPredicate.MinimumSimilarChordDistance -> {
                previous as DpMinimumDistanceHistorySignature
                if (!inScope) previous else {
                    val lastByDegree = previous.lastSlotByDegree.toMutableList()
                    val position = frame.target.degree - 1
                    val last = lastByDegree[position]
                    lastByDegree[position] = frame.slotIndex
                    previous.copy(
                        violated = previous.violated ||
                            (last >= 0 && frame.slotIndex - last < predicate.minimumSlotDistance),
                        lastSlotByDegree = lastByDegree,
                    )
                }
            }
            ConstraintPredicate.DistinctSimilarChordProgressions -> {
                previous as DpDistinctProgressionHistorySignature
                val pair = if (previous.previousInScope && inScope && previous.previousDegree != null) {
                    (previous.previousDegree - 1) * 7 + (frame.target.degree - 1)
                } else {
                    null
                }
                previous.copy(
                    violated = previous.violated || (pair != null && pair in previous.usedPairs),
                    usedPairs = if (pair == null) previous.usedPairs else previous.usedPairs + pair,
                    previousDegree = frame.target.degree,
                    previousInScope = inScope,
                )
            }
            is ConstraintPredicate.RootProgressionPreference -> {
                previous as DpRootProgressionHistorySignature
                if (inScope) previous.copy(applicableDegrees = previous.applicableDegrees + frame.target.degree)
                else previous
            }
            else -> error("Constraint history is not registered for ${predicate::class.simpleName}")
        }
    }

    private class DpExtremeSlot(
        val constraintIndex: Int,
        val extreme: VoiceExtreme,
        val voiceIndex: Int,
        val maxOccurrences: Int,
    )

    /**
     * 声部极值摘要。[values] 用 [ABSENT] 表示尚未出现在约束作用域内的槽，[occurrences] 记录
     * 当前极值出现次数——两者都只依赖前缀，且加一帧后 O(槽数) 可更新。
     */
    private class DpExtremeSummary(
        val values: IntArray,
        val occurrences: IntArray,
    ) {
        fun extend(slots: List<DpExtremeSlot>, layerFrame: DpLayerFrame): DpExtremeSummary {
            if (slots.isEmpty()) return this
            val nextValues = values.copyOf()
            val nextOccurrences = occurrences.copyOf()
            slots.forEachIndexed { index, slot ->
                if (!layerFrame.extremeScopeMatches[index]) return@forEachIndexed
                val pitch = layerFrame.profile.midi[slot.voiceIndex]
                val current = nextValues[index]
                when {
                    current == ABSENT -> {
                        nextValues[index] = pitch
                        nextOccurrences[index] = 1
                    }
                    // 判定只看 `出现次数 > maxOccurrences`，因此计数饱和即可：出现 3 次与 4 次
                    // 对未来裁决完全等价，不饱和会把行为相同的前缀拆成两个状态。
                    pitch == current ->
                        nextOccurrences[index] =
                            minOf(nextOccurrences[index] + 1, slot.maxOccurrences + 1)
                    slot.extreme == VoiceExtreme.HIGHEST && pitch > current -> {
                        nextValues[index] = pitch
                        nextOccurrences[index] = 1
                    }
                    slot.extreme == VoiceExtreme.LOWEST && pitch < current -> {
                        nextValues[index] = pitch
                        nextOccurrences[index] = 1
                    }
                }
            }
            return DpExtremeSummary(nextValues, nextOccurrences)
        }

        companion object {
            const val ABSENT = Int.MIN_VALUE

            fun initial(size: Int): DpExtremeSummary =
                DpExtremeSummary(IntArray(size) { ABSENT }, IntArray(size))
        }
    }

    /**
     * 有限容量的优先 trace 缓冲。缓冲写满后每条 HARD_PRUNED 都会走一次准入判定，因此准入与
     * 淘汰都必须是常数级：按优先级维护占用槽位队列，不再逐条扫描整个缓冲。
     */
    private class DpTraceRecorder(private val limit: Int) {
        val entries = mutableListOf<WritingSearchTraceEntry>()
        private val slotsByPriority = Array(TRACE_PRIORITY_COUNT) { ArrayDeque<Int>() }

        fun canRecord(kind: WritingSearchTraceEventKind): Boolean {
            if (limit <= 0) return false
            if (entries.size < limit) return true
            return lowestOccupiedPriorityBelow(kind.tracePriority()) != null
        }

        fun record(entry: WritingSearchTraceEntry) {
            if (limit <= 0) return
            val priority = entry.kind.tracePriority()
            if (entries.size < limit) {
                entries += entry
                slotsByPriority[priority].addLast(entries.lastIndex)
                return
            }
            val evicted = lowestOccupiedPriorityBelow(priority) ?: return
            val slot = slotsByPriority[evicted].removeFirst()
            entries[slot] = entry
            slotsByPriority[priority].addLast(slot)
        }

        private fun lowestOccupiedPriorityBelow(priority: Int): Int? =
            (0 until priority).firstOrNull { slotsByPriority[it].isNotEmpty() }

        private fun WritingSearchTraceEventKind.tracePriority(): Int = when (this) {
            WritingSearchTraceEventKind.HARD_PRUNED -> 0
            WritingSearchTraceEventKind.EXPANDED -> 1
            WritingSearchTraceEventKind.LAYER_COMPLETED -> 2
            WritingSearchTraceEventKind.DEAD_END,
            WritingSearchTraceEventKind.SOLUTION,
            -> 3
            WritingSearchTraceEventKind.BUDGET_EXHAUSTED,
            WritingSearchTraceEventKind.CANCELLED,
            -> 4
            else -> 1
        }
    }

    private val RANKED_FRAME_COMPARATOR: Comparator<RankedLayerFrame> =
        compareBy<RankedLayerFrame> { it.priority }.thenBy { it.layerFrame.tieBreakKey }

    private const val TRACE_PRIORITY_COUNT = 5
    private const val FRONTIER_PRUNE_MULTIPLIER = 2
    private const val MIN_BOUNDED_OUTGOING_PER_TARGET = 8
    private const val BOUNDED_OUTGOING_PER_RESULT = 4
    private const val DIVERSE_LABEL_POOL_MULTIPLIER = 4
    private const val TERMINAL_LABEL_POOL_MULTIPLIER = 8
    private const val DIVERSE_LABEL_SCORE_TOLERANCE = 100.0
    private const val DEFAULT_DP_PREFIX_SIMILARITY_WEIGHT = 4.0
    private const val DIVERSITY_EPSILON = 1e-9
}

private fun RuleProfile.canPrePrune(ruleId: RuleId): Boolean {
    val config = configFor(ruleId)
    return config.enabled &&
        (config.severityOverride ?: RuleSeverity.HARD) == RuleSeverity.HARD &&
        suppressions.none { it.suppressedRuleId == ruleId }
}
