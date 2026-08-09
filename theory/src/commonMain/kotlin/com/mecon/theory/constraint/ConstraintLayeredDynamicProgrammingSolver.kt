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
            return LayeredDpCapability(
                supported = true,
                // State correctness and backend preference are intentionally separate decisions.
                autoPreferred = false,
                reason = "Layered DP remains explicit opt-in until performance and merge thresholds are revalidated",
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
        val capability = LayeredDpCapability.analyze(search.program)
        val statePlan = requireNotNull(capability.statePlan)
        val boundedFrontierLimit = config.maxFrontierStates
        val traceRecorder = DpTraceRecorder(maxEntries)
        fun record(entry: WritingSearchTraceEntry) = traceRecorder.record(entry)

        var visited = 0
        var evaluatedTransitions = 0
        var terminalGlobalEvaluations = 0
        val terminalGlobalLowerBound = terminalGlobalScoreLowerBound(
            program = search.program,
            policy = search.space.dpScorePolicy,
        )
        var exhaustedBudget = false
        var cancelled = false
        var candidateLayerTruncated = false
        var frontierTruncated = false
        var labelsTruncated = false
        var transitionCandidatesTruncated = false
        // 状态摘要随 label 增量维护，构造 state key 时不再回扫整条前缀。
        val summaryPlan = DpStateSummaryPlan.compile(statePlan, search.voices)
        var frontier = listOf(
            DpLabel(
                state = search.space.initial(task),
                score = FixedVoiceIncrementalScore(),
                priority = SearchPriority.NEUTRAL,
                previousProfile = search.initialBoundary?.let(search.candidateFactory::candidateProfile),
                previousVerticality = search.initialBoundary?.let(search.space::verticalityOf),
                recentSignatures = emptyList(),
                extremes = DpExtremeSummary.initial(summaryPlan.slots.size),
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
                    val representativeState = frontier.first().state.copy(
                        frames = frontier.first().state.frames + frame,
                    )
                    val verticality = search.space.verticalityOf(frame)
                    val profile = search.candidateFactory.candidateProfile(frame)
                    DpLayerFrame(
                        frame = frame,
                        profile = profile,
                        verticality = verticality,
                        verticalFindings = search.space.dpVerticalFindings(
                            representativeState = representativeState,
                            frame = frame,
                            verticality = verticality,
                        ),
                        tieBreakKey = frameTieBreakKey(frame, search.voices),
                        signature = DpVoiceFrameSignature(profile.midi),
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
            ).coerceAtMost(task.searchConfig.candidateLimit)
            val outgoingLimit = boundedOutgoingPerTarget *
                search.program.slotDomains[slotIndex].targets.size
            val comparator = labelComparator(search)
            val labelLimit = maxOf(config.maxLabelsPerState, task.searchConfig.maxResults)
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
                    val step = search.space.dpApplyAndScore(
                        state = label.state,
                        frame = frame,
                        verticalFindings = layerFrame.verticalFindings,
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
                        extremes = label.extremes.extend(summaryPlan.slots, layerFrame),
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
                    if (insertRetainedLabel(labels, nextLabel, comparator, labelLimit)) {
                        labelsTruncated = true
                    }
                    if (!exact && grouped.size > boundedFrontierLimit * FRONTIER_PRUNE_MULTIPLIER) {
                        retainBestStateGroups(grouped, boundedFrontierLimit, comparator)
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
                    labelLimit = labelLimit,
                    globalLowerBound = terminalGlobalLowerBound,
                )
                terminalGlobalEvaluations += resolved.globalEvaluations
                if (resolved.truncated) labelsTruncated = true
                resolved.labels.forEach { label ->
                    val key = stateKey(label, summaryPlan, slotIndex)
                    distinctStateHashes += key.hashCode()
                    grouped.getOrPut(key) { mutableListOf() } += label
                }
            }

            var retainedGroups = grouped.values.sortedWith { left, right ->
                comparator.compare(left.first(), right.first())
            }
            if (!exact && grouped.size > boundedFrontierLimit) {
                retainedGroups = retainedGroups.take(boundedFrontierLimit)
                frontierTruncated = true
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
                .distinctBy { search.space.diversityGroupKey(it.state) }
                .map { WritingSearchResult(it.state, search.space.dpBreakdown(it.score)) }
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
        if (config.diversityWeight == 0.0) return ranked.take(config.maxResults)
        val remaining = ranked.toMutableList()
        val selected = mutableListOf<WritingSearchResult<FixedVoiceWritingState<ChordTarget>>>()
        while (selected.size < config.maxResults && remaining.isNotEmpty()) {
            val next = remaining.minWith(
                compareBy<WritingSearchResult<FixedVoiceWritingState<ChordTarget>>> { candidate ->
                    val similarity = selected.maxOfOrNull { chosen ->
                        search.space.similarity(candidate.state, chosen.state)
                    } ?: 0.0
                    candidate.breakdown.total + similarity * config.diversityWeight
                }.thenBy { search.space.diversityKey(it.state) }
            )
            selected += next
            remaining.remove(next)
        }
        return selected
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
        val recent = when {
            recentCount == 0 -> emptyList()
            recentCount >= label.recentSignatures.size -> label.recentSignatures
            else -> label.recentSignatures.takeLast(recentCount)
        }
        val selected = plan.extremeSlotsByLayer[slotIndex]
        val extremes = IntArray(selected.size * 2) { position ->
            val slot = selected[position / 2]
            if (position % 2 == 0) label.extremes.values[slot] else label.extremes.occurrences[slot]
        }
        return DpStateKey(recent, extremes)
    }

    /**
     * 由逐层状态计划一次编译出的摘要布局：[slots] 是 (约束, 极值方向, 声部) 的笛卡尔展开，
     * [extremeSlotsByLayer] 是每层 key 要读取的槽下标。都只依赖程序本身，不随转移变化。
     */
    private class DpStateSummaryPlan(
        val slots: List<DpExtremeSlot>,
        val extremeSlotsByLayer: List<IntArray>,
        val recentFrameCounts: IntArray,
    ) {
        val maxRecentFrames: Int = recentFrameCounts.maxOrNull() ?: 0

        companion object {
            fun compile(statePlan: LayeredDpStatePlan, voices: List<FixedVoice>): DpStateSummaryPlan {
                val needs = statePlan.layers.flatMap { it.voiceExtremes }.distinct()
                val slots = needs.flatMap { need ->
                    voices.indices
                        .filter { index -> need.voiceFilter.allows(voices[index]) }
                        .map { index ->
                            DpExtremeSlot(
                                constraintIndex = need.constraintIndex,
                                extreme = need.extreme,
                                voiceIndex = index,
                            )
                        }
                }
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
                    recentFrameCounts = statePlan.layers.map { it.recentFrameCount }.toIntArray(),
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
        globalLowerBound: Double,
    ): TerminalResolution {
        if (candidates.isEmpty()) return TerminalResolution(emptyList(), globalEvaluations = 0, truncated = false)
        val ordered = candidates.sortedWith(
            compareBy<DpLabel> { it.priority }.thenBy { it.score.total }
        )
        val retained = mutableListOf<DpLabel>()
        var truncated = false
        var globalEvaluations = 0
        for (candidate in ordered) {
            if (retained.size >= labelLimit) {
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
    ): Comparator<DpLabel> = compareBy<DpLabel> { it.priority }
        .thenBy { it.score.total }
        .thenBy { it.diversityKey(search.space) }

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
    ) {
        val retained = grouped.entries
            .sortedWith { left, right -> comparator.compare(left.value.first(), right.value.first()) }
            .take(limit)
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
        val extremes: DpExtremeSummary,
    ) {
        private var cachedDiversityKey: String? = null

        fun diversityKey(space: FixedVoiceWritingCandidateSpace<ChordTarget>): String =
            cachedDiversityKey ?: space.diversityKey(state).also { cachedDiversityKey = it }

        fun withScore(score: FixedVoiceIncrementalScore): DpLabel =
            DpLabel(state, score, priority, previousProfile, previousVerticality, recentSignatures, extremes)
    }

    /** 一层里与前驱无关的候选常量：只算一次，供该层所有入边共享。 */
    private class DpLayerFrame(
        val frame: FixedVoiceWritingFrame<ChordTarget>,
        val profile: FrameCandidateProfile,
        val verticality: FixedVoiceVerticality,
        val verticalFindings: List<RuleFinding<EventId>>,
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
        private val recentFrames: List<DpVoiceFrameSignature>,
        private val voiceExtremes: IntArray,
    ) {
        private val hash: Int = recentFrames.hashCode() * 31 + voiceExtremes.contentHashCode()

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DpStateKey) return false
            return hash == other.hash &&
                recentFrames == other.recentFrames &&
                voiceExtremes.contentEquals(other.voiceExtremes)
        }
    }

    /** 一帧各声部 MIDI 的层常量签名；同一层的所有入边共用同一个实例。 */
    private class DpVoiceFrameSignature(private val pitches: IntArray) {
        private val hash: Int = pitches.contentHashCode()

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            return other is DpVoiceFrameSignature && pitches.contentEquals(other.pitches)
        }
    }

    private class DpExtremeSlot(
        val constraintIndex: Int,
        val extreme: VoiceExtreme,
        val voiceIndex: Int,
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
                    pitch == current -> nextOccurrences[index]++
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
}

private fun RuleProfile.canPrePrune(ruleId: RuleId): Boolean {
    val config = configFor(ruleId)
    return config.enabled &&
        (config.severityOverride ?: RuleSeverity.HARD) == RuleSeverity.HARD &&
        suppressions.none { it.suppressedRuleId == ruleId }
}
