package com.mecon.theory.constraint

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TrackId
import com.mecon.theory.GreedyDepthFirstSolver
import com.mecon.theory.FixedVoice
import com.mecon.theory.FixedVoiceWritingCandidate
import com.mecon.theory.FixedVoiceTargetProvider
import com.mecon.theory.FixedVoiceWritingCandidateSpace
import com.mecon.theory.FixedVoiceWritingFrame
import com.mecon.theory.FixedVoiceWritingState
import com.mecon.theory.NodeBudget
import com.mecon.theory.ScoreBreakdown
import com.mecon.theory.SearchBackend
import com.mecon.theory.WritingSearchResult
import com.mecon.theory.WritingSearchTrace
import com.mecon.theory.WritingSearchTraceEntry
import com.mecon.theory.WritingTask
import com.mecon.theory.PolyphonicVoicing
import com.mecon.theory.RuleAnchorGroup
import com.mecon.theory.RuleFinding
import com.mecon.theory.RuleFindingKind
import com.mecon.theory.RuleId
import com.mecon.theory.RuleSeverity
import com.mecon.theory.solverPitchEventId
import com.mecon.theory.solverVoiceEventId
import com.mecon.theory.toFixedVoices
import com.mecon.theory.textbook.FourPartTextbookWritingRuleProvider
import com.mecon.theory.textbook.MelodyTextbookWritingRuleProvider

data class ConstraintSolution(
    val voicings: List<ChordVoicing>,
    val breakdown: ScoreBreakdown,
)

data class PolyphonicConstraintSolution(
    val voicings: List<PolyphonicVoicing<ChordTarget>>,
    val breakdown: ScoreBreakdown,
    val diversityGroupKey: String,
)

data class ConstraintSearchTrace(
    val solutions: List<ConstraintSolution>,
    val trace: WritingSearchTrace,
)

/** One already-written frame evaluated by the same providers used during solving. */
data class ObservedConstraintFrame(
    val slotIndex: Int,
    val target: ChordTarget,
    val pitchesByVoiceId: Map<TrackId, Pitch>,
    val sourceEventIdsByVoiceId: Map<TrackId, EventId> = emptyMap(),
)

/**
 * 约束程序的通用四部求解器。候选域来自 SlotDomain，章节规则由模块注册表自发现；所有声明式
 * constraint 统一交给 [ConstraintCompositeRuleProvider]，不再按 requirement 类型注册专用 provider。
 */
object ConstraintProgramSolver {
    fun solve(program: ConstraintProgram): List<ConstraintSolution> {
        val voices = program.resolvedVoicePlan.toFixedVoices()
        return solveResults(program).toConstraintSolutions(voices)
    }

    /** Voice-count-independent result path used by the free harmony compiler. */
    fun solvePolyphonic(program: ConstraintProgram): List<PolyphonicConstraintSolution> {
        val voices = program.resolvedVoicePlan.toFixedVoices()
        return solveResults(program).toPolyphonicConstraintSolutions(voices)
    }

    /**
     * Evaluates a complete observed realization without enumerating replacement candidates.
     * Synthetic solver anchors are translated back to the source score event ids supplied by each
     * frame, so consumers can use the result for the same highlighting/navigation contract as a
     * generated solution.
     *
     * Both layers [solvePolyphonicOutcome] rejects on are covered: the symbolic preflight is
     * re-evaluated against the observed chord choice, and the voicing itself is scored by the same
     * rule providers. Restrictions the solver expresses as a candidate *domain* rather than a rule —
     * voice ranges and voice crossing, which [ChordTargetCandidateFactory] never enumerates outside
     * of — produce no finding here; callers that must report them check the written pitches
     * directly.
     */
    fun checkObserved(
        program: ConstraintProgram,
        frames: List<ObservedConstraintFrame>,
        context: ConstraintSolveContext = ConstraintSolveContext(),
    ): ScoreBreakdown {
        require(frames.size == program.length) {
            "Observed realization must contain exactly ${program.length} frames"
        }
        val ordered = frames.sortedBy(ObservedConstraintFrame::slotIndex)
        require(ordered.map(ObservedConstraintFrame::slotIndex) == program.slotDomains.indices.toList()) {
            "Observed frame slot indices must cover 0 until ${program.length}"
        }
        ordered.forEach { frame ->
            require(
                program.slotDomains[frame.slotIndex].targets.any {
                    it.identityKey() == frame.target.identityKey()
                }
            ) { "Observed target is outside slot ${frame.slotIndex}" }
        }

        val search = buildSearch(program, context)
        var state = FixedVoiceWritingState<ChordTarget>()
        ordered.forEach { observed ->
            state = search.space.apply(
                state,
                FixedVoiceWritingCandidate(
                    FixedVoiceWritingFrame(
                        slotIndex = observed.slotIndex,
                        target = observed.target,
                        pitchesByVoiceId = observed.pitchesByVoiceId,
                        duration = program.durationAt(observed.slotIndex),
                    )
                ),
            )
        }
        val breakdown = search.space.score(state, search.task)
        val sourceBySyntheticId = buildMap {
            ordered.forEach { frame ->
                frame.sourceEventIdsByVoiceId.forEach { (voiceId, eventId) ->
                    put(solverVoiceEventId(frame.slotIndex, voiceId), eventId)
                    put(solverPitchEventId(frame.slotIndex, voiceId), eventId)
                }
            }
        }
        // The chord choice itself is checked against the same target-only hard rules the solver
        // preflights, evaluated on the observed reading rather than on the whole slot domain.
        val observedDomains = ordered.map { SlotDomain(listOf(it.target)) }
        val observedProgram = program.copy(
            slotDomains = observedDomains,
            slots = program.slots.mapIndexed { index, slot ->
                slot.copy(domain = observedDomains[index])
            },
        )
        val symbolicFindings = targetOnlyHardViolations(observedProgram).map { violation ->
            RuleFinding<EventId>(
                ruleId = violation.ruleId ?: SYMBOLIC_PREFLIGHT_RULE_ID,
                kind = RuleFindingKind.VIOLATION,
                severity = RuleSeverity.HARD,
                message = violation.explanation?.violated
                    ?: "${violation.ruleId?.value ?: "constraint"} rejected the chord choice",
                anchors = ordered.flatMap { it.sourceEventIdsByVoiceId.values }.distinct(),
            )
        }
        return breakdown.copy(
            findings = symbolicFindings +
                breakdown.findings.map { it.remapAnchors(sourceBySyntheticId) },
        )
    }

    fun solvePolyphonicOutcome(
        program: ConstraintProgram,
        context: ConstraintSolveContext = ConstraintSolveContext(),
        maxResults: Int = program.searchConfig.maxResults,
        maxTraceEntries: Int = 128,
    ): ConstraintSolveOutcome {
        require(maxResults > 0) { "maxResults must be positive" }
        val preflight = targetOnlyHardViolations(program)
        if (preflight.isNotEmpty()) {
            return ConstraintSolveOutcome.Invalid(
                preflight.map { violation ->
                    ConstraintSolveDiagnostic(
                        code = ConstraintSolveDiagnosticCode.TARGET_PREFLIGHT_REJECTED,
                        message = violation.explanation?.violated
                            ?: "${violation.ruleId?.value ?: "constraint"} rejected the chord targets",
                        ruleId = violation.ruleId,
                    )
                },
            )
        }
        val effective = program.copy(
            searchConfig = program.searchConfig.copy(maxResults = maxResults),
        )
        val search = buildSearch(effective, context)
        val capability = LayeredDpCapability.analyze(search.program)
        val exactGlobalRerank = effective.searchConfig.backend == SearchBackend.LAYERED_DP &&
            effective.searchConfig.dynamicProgramming.mode == com.mecon.theory.DynamicProgrammingSearchMode.EXACT &&
            capability.requiresBoundedGlobalRerank
        if (effective.searchConfig.backend == SearchBackend.LAYERED_DP && (!capability.supported || exactGlobalRerank)) {
            return ConstraintSolveOutcome.Invalid(
                diagnostics = listOf(
                    ConstraintSolveDiagnostic(
                        code = ConstraintSolveDiagnosticCode.UNSUPPORTED_SEARCH_BACKEND,
                        message = if (exactGlobalRerank) {
                            "EXACT layered DP cannot merge terminal-only global rules; use BOUNDED or remove those rules"
                        } else {
                            requireNotNull(capability.reason)
                        },
                    )
                ),
            )
        }
        val run = runSearchWithTrace(search, context, maxTraceEntries)
        if (run.trace.cancelled) return ConstraintSolveOutcome.Cancelled(run.trace)
        val solutions = run.results.toPolyphonicConstraintSolutions(
            effective.resolvedVoicePlan.toFixedVoices(),
        )
        if (solutions.isNotEmpty()) return ConstraintSolveOutcome.Solved(solutions, run.trace)
        return if (run.trace.exhaustedBudget) {
            ConstraintSolveOutcome.BudgetExhausted(run.trace)
        } else {
            ConstraintSolveOutcome.NoSolution(run.trace)
        }
    }

    fun trace(program: ConstraintProgram, maxEntries: Int = 128): ConstraintSearchTrace {
        val preflightViolations = targetOnlyHardViolations(program)
        if (preflightViolations.isNotEmpty()) {
            return ConstraintSearchTrace(
                solutions = emptyList(),
                trace = WritingSearchTrace(
                    nodeBudget = program.searchConfig.nodeBudget,
                    visitedNodes = 0,
                    exhaustedBudget = false,
                    entries = preflightViolations.take(maxEntries).map { constraint ->
                        WritingSearchTraceEntry(
                            kind = com.mecon.theory.WritingSearchTraceEventKind.HARD_PRUNED,
                            depth = 0,
                            state = "target-only preflight",
                            hardViolations = listOf(
                                "${constraint.ruleId?.value ?: "constraint"}: " +
                                    (constraint.explanation?.violated ?: "目标和弦序列不满足硬约束。")
                            ),
                        )
                    },
                ),
            )
        }
        val search = buildSearch(program)
        val traced = runSearchWithTrace(search, ConstraintSolveContext(), maxEntries)
        return ConstraintSearchTrace(
            solutions = traced.results.toConstraintSolutions(program.resolvedVoicePlan.toFixedVoices()),
            trace = traced.trace,
        )
    }

    /**
     * “任意符号进行”入口：在同一数量级的总节点预算内依次探测若干已排序进行，首条无解时继续下一条。
     * 显式选定进行仍调用 [solve] 并独享完整预算。
     */
    fun solveFirstFeasible(
        programs: List<ConstraintProgram>,
        maxProgramAttempts: Int = 8,
    ): List<ConstraintSolution> {
        require(maxProgramAttempts > 0) { "maxProgramAttempts must be positive" }
        if (programs.isEmpty()) return emptyList()
        val eligible = programs.asSequence()
            .filter { targetOnlyHardViolations(it).isEmpty() }
            .take(maxProgramAttempts)
            .toList()
        if (eligible.isEmpty()) return emptyList()

        val totalBudget = programs.first().searchConfig.nodeBudget
        val probeFloor = eligible.maxOf { it.length } * MIN_COMPLETE_PATH_PROBES
        val attemptCount = minOf(
            eligible.size,
            maxOf(1, totalBudget / probeFloor),
        )
        val attemptedPrograms = eligible.take(attemptCount)
        val budgetPerProgram = totalBudget / attemptedPrograms.size
        attemptedPrograms.forEach { program ->
            val search = buildSearch(program)
            val run = GreedyDepthFirstSolver.coreRun(
                task = search.task,
                space = search.space,
                describe = search.space::diversityKey,
                budget = NodeBudget(budgetPerProgram),
                maxResults = program.searchConfig.maxResults,
            )
            if (run.solutions.isNotEmpty()) {
                return run.toResults().toConstraintSolutions(program.resolvedVoicePlan.toFixedVoices())
            }
        }
        return emptyList()
    }

    internal fun buildSearch(
        program: ConstraintProgram,
        context: ConstraintSolveContext = ConstraintSolveContext(),
    ): ConstraintSearch {
        val derivedTextbookConstraints = if (program.includeDerivedTextbookConstraints) {
            namedTriadConstraints(program) +
                namedSeventhPreparationConstraints(program) +
                namedV7MotionConstraints(program) +
                namedSeventhContextConstraints(program) +
                namedSixFourConstraints(program) +
                namedV7ResolutionConstraints(program)
        } else {
            emptyList()
        }
        val effectiveProgram = program.copy(constraints = program.constraints + derivedTextbookConstraints)
        val voices = program.resolvedVoicePlan.toFixedVoices()
        val initialBoundary = context.leftBoundary?.let { boundary ->
            FixedVoiceWritingFrame(
                slotIndex = -1,
                target = boundary.target,
                pitchesByVoiceId = boundary.pitchesByVoiceId,
            )
        }
        val task = effectiveProgram.toWritingTask()
        val feasibilityPolicy = when (effectiveProgram.writingRulePreset) {
            WritingRulePreset.FREE_CLASSICAL,
            WritingRulePreset.FREE_JAZZ,
            -> SearchFeasibilityPolicy()
            else -> null
        }
        val delegates = buildList {
            add(
                ChordRuleDispatcher(
                    effectiveProgram.ruleModules ?: defaultChordRuleModules(
                        key = effectiveProgram.key,
                        slotCount = effectiveProgram.length,
                        finalTonicMayOmitFifth = effectiveProgram.finalTonicMayOmitFifth,
                    )
                )
            )
            when (effectiveProgram.writingRulePreset) {
                WritingRulePreset.TEXTBOOK -> {
                    add(FourPartTextbookWritingRuleProvider<ChordTarget>(effectiveProgram.rangeProfile))
                    add(MelodyTextbookWritingRuleProvider(effectiveProgram.key))
                }
                WritingRulePreset.SCHOENBERG_GENERAL ->
                    add(FourPartTextbookWritingRuleProvider<ChordTarget>(effectiveProgram.rangeProfile))
                WritingRulePreset.FREE_CLASSICAL ->
                    add(FreeHarmonyRuleProvider(
                        effectiveProgram, voices, classicalCounterpointPreferences = true,
                        voiceLeadingRelaxation = context.voiceLeadingRelaxation,
                    ))
                WritingRulePreset.FREE_JAZZ ->
                    add(FreeHarmonyRuleProvider(
                        effectiveProgram, voices, classicalCounterpointPreferences = false,
                        voiceLeadingRelaxation = context.voiceLeadingRelaxation,
                    ))
                WritingRulePreset.NONE -> Unit
            }
            if (effectiveProgram.writingRulePreset == WritingRulePreset.FREE_CLASSICAL ||
                effectiveProgram.writingRulePreset == WritingRulePreset.FREE_JAZZ
            ) {
                add(
                    WindowFeasibilityRuleProvider(
                        voicesHighToLow = voices,
                        policy = requireNotNull(feasibilityPolicy),
                        relaxBoundaryLargeLeaps = context.relaxBoundaryLargeLeaps,
                        voiceLeadingRelaxation = context.voiceLeadingRelaxation,
                    ),
                )
                context.baseline?.let { add(BaselineSimilarityRuleProvider(effectiveProgram, voices, it)) }
            }
        }
        val candidateFactory = ChordTargetCandidateFactory(
            effectiveProgram,
            voices,
            initialBoundary,
            context.cancellation,
        )
        return ConstraintSearch(
            task = task,
            program = effectiveProgram,
            voices = voices,
            candidateFactory = candidateFactory,
            initialBoundary = initialBoundary,
            feasibilityPolicy = feasibilityPolicy,
            relaxBoundaryLargeLeaps = context.relaxBoundaryLargeLeaps,
            voiceLeadingRelaxation = context.voiceLeadingRelaxation,
            space = FixedVoiceWritingCandidateSpace(
                targetProvider = SlotDomainTargetProvider(effectiveProgram, voices),
                voices = voices,
                candidateFactory = candidateFactory,
                ruleProviders = listOf(ConstraintCompositeRuleProvider(delegates, effectiveProgram, voices)),
                initialBoundary = initialBoundary,
            ),
        )
    }

    internal data class ConstraintSearch(
        val task: WritingTask,
        val program: ConstraintProgram,
        val voices: List<FixedVoice>,
        val candidateFactory: ChordTargetCandidateFactory,
        val initialBoundary: FixedVoiceWritingFrame<ChordTarget>?,
        val feasibilityPolicy: SearchFeasibilityPolicy?,
        val relaxBoundaryLargeLeaps: Boolean,
        val voiceLeadingRelaxation: VoiceLeadingRelaxationPlan,
        val space: FixedVoiceWritingCandidateSpace<ChordTarget>,
    )

    private fun solveResults(
        program: ConstraintProgram,
    ): List<WritingSearchResult<FixedVoiceWritingState<ChordTarget>>> {
        if (targetOnlyHardViolations(program).isNotEmpty()) return emptyList()
        val search = buildSearch(program)
        val capability = LayeredDpCapability.analyze(search.program)
        if (program.searchConfig.backend == SearchBackend.LAYERED_DP) {
            require(capability.supported) { capability.reason ?: "Layered DP is unsupported" }
            require(
                program.searchConfig.dynamicProgramming.mode !=
                    com.mecon.theory.DynamicProgrammingSearchMode.EXACT ||
                    !capability.requiresBoundedGlobalRerank
            ) {
                "EXACT layered DP cannot merge terminal-only global rules; use BOUNDED or remove those rules"
            }
        }
        return runSearchWithTrace(search, ConstraintSolveContext(), maxEntries = 0).results
    }

    private fun runSearchWithTrace(
        search: ConstraintSearch,
        context: ConstraintSolveContext,
        maxEntries: Int,
    ): com.mecon.theory.WritingSearchTraceResult<FixedVoiceWritingState<ChordTarget>> {
        val requested = search.task.searchConfig.backend
        val capability = LayeredDpCapability.analyze(search.program)
        if (requested == SearchBackend.LAYERED_DP) {
            require(capability.supported) { capability.reason ?: "Layered DP is unsupported" }
            require(
                search.task.searchConfig.dynamicProgramming.mode !=
                    com.mecon.theory.DynamicProgrammingSearchMode.EXACT ||
                    !capability.requiresBoundedGlobalRerank
            ) {
                "EXACT layered DP cannot merge terminal-only global rules; use BOUNDED or remove those rules"
            }
        }
        val useLayeredDp =
            (requested == SearchBackend.LAYERED_DP && capability.supported) ||
                (requested == SearchBackend.AUTO && capability.autoPreferred)
        if (useLayeredDp) {
            return ConstraintLayeredDynamicProgrammingSolver.solveWithTrace(
                search = search,
                context = context,
                maxEntries = maxEntries,
            )
        }
        val fallbackReason = if (requested == SearchBackend.AUTO) {
            capability.reason ?: "Layered DP is available only when explicitly requested for this rule preset"
        } else {
            null
        }
        val result = GreedyDepthFirstSolver.solveWithTrace(
            task = search.task,
            space = search.space,
            maxEntries = maxEntries,
            describe = ::describeState,
            depthOf = { it.frames.size },
            cancellation = context.cancellation,
            excludedDiversityGroups = context.excludedDiversityGroupKeys,
        )
        return result.copy(
            trace = result.trace.copy(
                backend = SearchBackend.GREEDY_DFS,
                fallbackReason = fallbackReason,
            ),
        )
    }

    private fun describeState(state: FixedVoiceWritingState<ChordTarget>): String =
        state.frames.joinToString(" -> ") { frame ->
            "slot${frame.slotIndex}: degree=${frame.target.degree}, " +
                "inversion=${frame.target.inversion}, ${frame.target.quality}"
        }.ifEmpty { "start" }

    /**
     * 固定符号进行的目标级硬约束在生成任何四部排列前求值。它们与声部音高无关，不应在每个
     * voicing 前缀上重复计算；若已确定违反，搜索以 0 个访问节点结束。
     */
    internal fun targetOnlyHardViolations(program: ConstraintProgram): List<Constraint> {
        val targets = program.slotDomains.map { domain -> domain.targets.singleOrNull() ?: return emptyList() }
        return program.constraints.filter { constraint ->
            constraint.modality == ConstraintModality.Require &&
                constraint.ruleId !in program.demonstratedViolationRuleIds &&
                constraint.isChordSelectionOnly &&
                constraint.expr.evaluateTruth { predicate ->
                    predicate.evaluateTargetOnly(program, targets, constraint.scope)
                } == ConstraintTruth.VIOLATED
        }
    }

    private const val MIN_COMPLETE_PATH_PROBES = 16

    /** Fallback attribution for an unnamed target-only hard constraint. */
    private val SYMBOLIC_PREFLIGHT_RULE_ID = RuleId("constraint.target-preflight")
}

private fun RuleFinding<EventId>.remapAnchors(
    sourceBySyntheticId: Map<EventId, EventId>,
): RuleFinding<EventId> = copy(
    anchors = anchors.map { sourceBySyntheticId[it] ?: it },
    relatedAnchors = relatedAnchors.map { group ->
        RuleAnchorGroup(
            role = group.role,
            anchors = group.anchors.map { sourceBySyntheticId[it] ?: it },
            label = group.label,
        )
    },
)

private fun ConstraintPredicate.evaluateTargetOnly(
    program: ConstraintProgram,
    targets: List<ChordTarget>,
    scope: ConstraintScope,
): ConstraintTruth {
    val applicable = targets.withIndex().filter { (slot, target) -> scope.matches(slot, target) }
    return when (this) {
        is ConstraintPredicate.RootDiatonicMotion -> {
            val from = targets.getOrNull(fromSlot) ?: return ConstraintTruth.UNDETERMINED
            val to = targets.getOrNull(toSlot) ?: return ConstraintTruth.UNDETERMINED
            if (!scope.matches(fromSlot, from) && !scope.matches(toSlot, to)) {
                ConstraintTruth.UNDETERMINED
            } else {
                ((to.degree - from.degree).mod(program.key.scale.pitchClasses.size) in allowedDeltas).toTruth()
            }
        }
        is ConstraintPredicate.MinimumSimilarChordDistance -> {
            applicable.indices.none { earlierIndex ->
                val earlier = applicable[earlierIndex]
                applicable.drop(earlierIndex + 1).any { later ->
                    later.index - earlier.index < minimumSlotDistance &&
                        later.value.degree == earlier.value.degree
                }
            }.toTruth()
        }
        ConstraintPredicate.DistinctSimilarChordProgressions -> {
            val pairs = targets.zipWithNext().mapIndexedNotNull { slot, (before, after) ->
                if (scope.matches(slot, before) && scope.matches(slot + 1, after)) {
                    before.degree to after.degree
                } else {
                    null
                }
            }
            (pairs.size == pairs.distinct().size).toTruth()
        }
        is ConstraintPredicate.RootProgressionPreference -> {
            val score = scoringPolicy.score(applicable.map { it.value.degree })
            (score.total <= 0.0).toTruth()
        }
        else -> ConstraintTruth.UNDETERMINED
    }
}

private fun Boolean.toTruth(): ConstraintTruth =
    if (this) ConstraintTruth.SATISFIED else ConstraintTruth.VIOLATED

private fun List<WritingSearchResult<FixedVoiceWritingState<ChordTarget>>>.toConstraintSolutions(
    voices: List<FixedVoice>,
): List<ConstraintSolution> =
    map { result ->
        ConstraintSolution(
            voicings = result.state.frames.map { it.toChordVoicing(voices) },
            breakdown = result.breakdown,
        )
    }

private fun List<WritingSearchResult<FixedVoiceWritingState<ChordTarget>>>.toPolyphonicConstraintSolutions(
    voices: List<FixedVoice>,
): List<PolyphonicConstraintSolution> =
    map { result ->
        PolyphonicConstraintSolution(
            voicings = result.state.frames.map { frame ->
                PolyphonicVoicing(
                    slotIndex = frame.slotIndex,
                    target = frame.target,
                    pitchesByVoiceId = voices.associate { voice -> voice.id to frame.pitchFor(voice) },
                )
            },
            breakdown = result.breakdown,
            diversityGroupKey = result.state.frames.joinToString("|") { frame ->
                voices.joinToString(",") { voice ->
                    frame.pitchFor(voice).pitchClass.value.toString()
                }
            },
        )
    }

private class SlotDomainTargetProvider(
    private val program: ConstraintProgram,
    private val voices: List<FixedVoice>,
) : FixedVoiceTargetProvider<ChordTarget> {
    override fun targetsFor(
        state: FixedVoiceWritingState<ChordTarget>,
        task: WritingTask,
    ): List<ChordTarget> =
        program.slotDomains.getOrNull(state.frames.size)
            ?.targets
            .orEmpty()
            .filter { target -> relationConstraintsAllowTarget(program, voices, state, state.frames.size, target) }
}
