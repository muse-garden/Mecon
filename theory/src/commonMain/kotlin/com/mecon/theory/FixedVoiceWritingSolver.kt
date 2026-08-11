package com.mecon.theory

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.PitchClass
import com.mecon.api.primitive.ScoreId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import kotlin.math.abs

data class FixedVoiceWritingFrame<T>(
    val slotIndex: Int,
    val target: T,
    val pitchesByVoiceId: Map<TrackId, Pitch>,
    val duration: Duration = Duration.QUARTER,
) {
    fun pitchFor(voice: FixedVoice): Pitch =
        pitchesByVoiceId[voice.id] ?: error("No generated pitch for voice ${voice.id.value}")

    fun pitchForRole(
        role: FixedVoiceRole,
        voices: List<FixedVoice> = standardFourPartWritingVoices(),
    ): Pitch =
        pitchFor(
            voices.firstOrNull { it.role == role }
                ?: error("No generated voice for role $role")
        )
}

data class FixedVoiceWritingState<T>(
    val frames: List<FixedVoiceWritingFrame<T>> = emptyList(),
    val localFindings: List<RuleFinding<EventId>> = emptyList(),
)

internal data class FixedVoiceIncrementalScore(
    val profiledFindings: IncrementalProfiledFindings<EventId> = IncrementalProfiledFindings(),
    val findingTotal: Double = 0.0,
    /** 随可见 finding 增删维护，避免每条转移重新扫描整条路径的 finding 列表。 */
    val hardViolations: Int = 0,
    val unweightedMotionCost: Double = 0.0,
    val motionCost: Double = 0.0,
) {
    val total: Double get() = findingTotal + motionCost
    val findings: List<RuleFinding<EventId>> get() = profiledFindings.visible
    val hasHardViolation: Boolean get() = hardViolations > 0
}

internal fun RuleFinding<EventId>.isHardViolation(): Boolean =
    kind == RuleFindingKind.VIOLATION && severity == RuleSeverity.HARD

internal data class FixedVoiceIncrementalStep<T>(
    val state: FixedVoiceWritingState<T>,
    val score: FixedVoiceIncrementalScore,
)

data class FixedVoiceWritingCandidate<T>(
    val frame: FixedVoiceWritingFrame<T>,
)

data class FixedVoiceVerticalRuleContext<T>(
    val frame: FixedVoiceWritingFrame<T>,
    val verticality: FixedVoiceVerticality,
    val state: FixedVoiceWritingState<T>,
)

data class FixedVoiceTransitionRuleContext<T>(
    val previousFrame: FixedVoiceWritingFrame<T>,
    val currentFrame: FixedVoiceWritingFrame<T>,
    val transition: FixedVoiceTransition,
    val state: FixedVoiceWritingState<T>,
)

/**
 * 全局规则上下文。[fixedVoiceScore] 惰性生成：只依赖 [state] 的 provider（约束代数、自由写作）
 * 不会为每条终层转移合成一整套 36 个事件与 EventId 字符串。
 */
class FixedVoiceScoreRuleContext<T> internal constructor(
    val state: FixedVoiceWritingState<T>,
    scoreProvider: () -> FixedVoiceScore,
) {
    constructor(
        fixedVoiceScore: FixedVoiceScore,
        state: FixedVoiceWritingState<T>,
    ) : this(state, { fixedVoiceScore })

    val fixedVoiceScore: FixedVoiceScore by lazy(LazyThreadSafetyMode.NONE, scoreProvider)
}

fun interface FixedVoiceTargetProvider<T> {
    fun targetsFor(
        state: FixedVoiceWritingState<T>,
        task: WritingTask,
    ): List<T>

    fun isComplete(
        state: FixedVoiceWritingState<T>,
        task: WritingTask,
    ): Boolean =
        targetsFor(state, task).isEmpty()
}

interface FixedVoiceWritingRuleProvider<T> {
    fun checkVertical(context: FixedVoiceVerticalRuleContext<T>): List<RuleFinding<EventId>> = emptyList()
    fun checkTransition(context: FixedVoiceTransitionRuleContext<T>): List<RuleFinding<EventId>> = emptyList()
    fun checkScore(context: FixedVoiceScoreRuleContext<T>): List<RuleFinding<EventId>> = emptyList()
}

/** Optional DP split for providers with a small prefix-sensitive vertical subset. */
internal interface DpPartitionedVerticalRuleProvider<T> : FixedVoiceWritingRuleProvider<T> {
    fun checkDpPrefixIndependentVertical(
        context: FixedVoiceVerticalRuleContext<T>,
    ): List<RuleFinding<EventId>>

    fun checkDpPrefixSensitiveVertical(
        context: FixedVoiceVerticalRuleContext<T>,
    ): List<RuleFinding<EventId>>
}

fun interface FixedVoiceCandidateFactory<T> {
    fun candidates(
        state: FixedVoiceWritingState<T>,
        slotIndex: Int,
        target: T,
        task: WritingTask,
    ): List<FixedVoiceWritingFrame<T>>
}

/** Optional search-order policy supplied by domain candidate factories. */
interface FixedVoiceCandidatePriority<T> {
    fun localPriority(
        previous: FixedVoiceWritingFrame<T>?,
        frame: FixedVoiceWritingFrame<T>,
    ): SearchPriority

    fun pathPriority(
        initialBoundary: FixedVoiceWritingFrame<T>?,
        frames: List<FixedVoiceWritingFrame<T>>,
    ): SearchPriority
}

data class FixedVoiceWritingScorePolicy(
    val hardCost: Double = 1_000_000.0,
    val softCost: Double = 100.0,
    val warningCost: Double = 25.0,
    val hintCost: Double = 5.0,
    val indicationBonus: Double = -12.0,
    val motionCostWeight: Double = 1.0,
    val bassMotionWeight: Double = 0.5,
) {
    fun findingScore(finding: RuleFinding<EventId>): Double =
        when {
            finding.scoreDelta != 0.0 -> finding.scoreDelta
            finding.scoreIntent == RuleScoreIntent.EXPLANATORY -> 0.0
            else -> when (finding.kind) {
                RuleFindingKind.INDICATION -> indicationBonus
                RuleFindingKind.HINT -> hintCost
                RuleFindingKind.WARNING -> warningCost
                RuleFindingKind.VIOLATION -> when (finding.severity) {
                    RuleSeverity.HARD -> hardCost
                    RuleSeverity.SOFT -> softCost
                    RuleSeverity.HINT -> hintCost
                }
            }
        }

    fun motionCost(
        state: FixedVoiceWritingState<*>,
        voices: List<FixedVoice>,
        initialBoundary: FixedVoiceWritingFrame<*>? = null,
    ): Double =
        (listOfNotNull(initialBoundary) + state.frames).zipWithNext().sumOf { (before, after) ->
            unweightedTransitionMotionCost(before, after, voices)
        } * motionCostWeight

    internal fun unweightedTransitionMotionCost(
        before: FixedVoiceWritingFrame<*>,
        after: FixedVoiceWritingFrame<*>,
        voices: List<FixedVoice>,
    ): Double = voices.sumOf { voice ->
        val distance = abs(after.pitchFor(voice).midiNumber - before.pitchFor(voice).midiNumber).toDouble()
        val cost = motionStepCost(distance)
        if (voice.role == FixedVoiceRole.BASS || voice.role == FixedVoiceRole.BARITONE) {
            cost * bassMotionWeight
        } else {
            cost
        }
    }

    internal fun weightedMotionCost(unweighted: Double): Double = unweighted * motionCostWeight

    private fun motionStepCost(distance: Double): Double =
        when {
            distance == 0.0 -> 0.0
            distance <= 2.0 -> 0.1
            distance <= 4.0 -> 1.5
            distance <= 7.0 -> 6.0 + (distance - 5.0).coerceAtLeast(0.0) * 1.5
            else -> 12.0 + (distance - 7.0) * 3.0
        }
}

class FixedVoiceWritingCandidateSpace<T>(
    private val targetProvider: FixedVoiceTargetProvider<T>,
    private val voices: List<FixedVoice> = standardFourPartWritingVoices(),
    private val candidateFactory: FixedVoiceCandidateFactory<T>,
    private val ruleProviders: List<FixedVoiceWritingRuleProvider<T>>,
    private val scorePolicy: FixedVoiceWritingScorePolicy = FixedVoiceWritingScorePolicy(),
    private val scoreId: ScoreId = ScoreId("fixed-voice-writing-solver"),
    private val initialBoundary: FixedVoiceWritingFrame<T>? = null,
) : ScoredCandidateSpace<FixedVoiceWritingState<T>, FixedVoiceWritingCandidate<T>> {
    constructor(
        targets: List<T>,
        voices: List<FixedVoice> = standardFourPartWritingVoices(),
        candidateFactory: FixedVoiceCandidateFactory<T>,
        ruleProviders: List<FixedVoiceWritingRuleProvider<T>>,
        scorePolicy: FixedVoiceWritingScorePolicy = FixedVoiceWritingScorePolicy(),
        scoreId: ScoreId = ScoreId("fixed-voice-writing-solver"),
        initialBoundary: FixedVoiceWritingFrame<T>? = null,
    ) : this(
        targetProvider = fixedVoiceTargetSequence(targets),
        voices = voices,
        candidateFactory = candidateFactory,
        ruleProviders = ruleProviders,
        scorePolicy = scorePolicy,
        scoreId = scoreId,
        initialBoundary = initialBoundary,
    )

    init {
        require(voices.isNotEmpty()) { "A fixed-voice writing space must include at least one voice" }
    }

    override fun initial(task: WritingTask): FixedVoiceWritingState<T> =
        FixedVoiceWritingState()

    override fun candidates(
        state: FixedVoiceWritingState<T>,
        task: WritingTask,
    ): List<FixedVoiceWritingCandidate<T>> {
        val slotIndex = state.frames.size
        return targetProvider.targetsFor(state, task).flatMap { target ->
            candidateFactory.candidates(state, slotIndex, target, task)
                .map { FixedVoiceWritingCandidate(it) }
        }
    }

    override fun apply(
        state: FixedVoiceWritingState<T>,
        candidate: FixedVoiceWritingCandidate<T>,
    ): FixedVoiceWritingState<T> {
        val next = state.copy(frames = state.frames + candidate.frame)
        return next.copy(
            localFindings = state.localFindings + localFindingsForNewFrame(next, candidate.frame)
        )
    }

    override fun isComplete(
        state: FixedVoiceWritingState<T>,
        task: WritingTask,
    ): Boolean =
        state.frames.size >= task.timeline.slots.size

    override fun searchPriority(
        state: FixedVoiceWritingState<T>,
        task: WritingTask,
    ): SearchPriority =
        @Suppress("UNCHECKED_CAST")
        (candidateFactory as? FixedVoiceCandidatePriority<T>)
            ?.pathPriority(initialBoundary, state.frames)
            ?: SearchPriority.NEUTRAL

    override fun score(
        state: FixedVoiceWritingState<T>,
        task: WritingTask,
    ): ScoreBreakdown {
        val findings = findingsFor(state)
            .applyProfile(task.ruleProfile)
            .applyRequirements(
                requirements = task.ruleProfile.requirements,
                isComplete = isComplete(state, task),
                frameCount = state.frames.size,
                anchorSlots = anchorSlotsOf(state),
            )
        val contributions = buildList {
            findings.forEach { finding ->
                val amount = scorePolicy.findingScore(finding)
                if (amount != 0.0) {
                    add(RuleScoreContribution(finding.ruleId, amount, finding.message))
                }
            }
            val motionCost = scorePolicy.motionCost(state, voices, initialBoundary)
            if (motionCost != 0.0) {
                add(RuleScoreContribution(MOTION_COST_RULE_ID, motionCost, "声部移动距离"))
            }
        }
        return ScoreBreakdown(
            total = contributions.sumOf { it.amount },
            findings = findings,
            contributions = contributions,
        )
    }

    internal fun dpPrefixIndependentVerticalFindings(
        representativeState: FixedVoiceWritingState<T>,
        frame: FixedVoiceWritingFrame<T>,
        verticality: FixedVoiceVerticality = frame.toVerticality(voices),
    ): List<RuleFinding<EventId>> {
        val context = FixedVoiceVerticalRuleContext(frame, verticality, representativeState)
        return ruleProviders.flatMap { provider ->
            @Suppress("UNCHECKED_CAST")
            val partitioned = provider as? DpPartitionedVerticalRuleProvider<T>
            partitioned?.checkDpPrefixIndependentVertical(context) ?: provider.checkVertical(context)
        }
    }

    internal fun dpPrefixSensitiveVerticalFindings(
        representativeState: FixedVoiceWritingState<T>,
        frame: FixedVoiceWritingFrame<T>,
        verticality: FixedVoiceVerticality = frame.toVerticality(voices),
    ): List<RuleFinding<EventId>> {
        val context = FixedVoiceVerticalRuleContext(frame, verticality, representativeState)
        return ruleProviders.flatMap { provider ->
            @Suppress("UNCHECKED_CAST")
            val partitioned = provider as? DpPartitionedVerticalRuleProvider<T>
            partitioned?.checkDpPrefixSensitiveVertical(context).orEmpty()
        }
    }

    /** 一帧的合成事件视图；DP 每层只构建一次，供该层所有入边共享。 */
    internal fun verticalityOf(frame: FixedVoiceWritingFrame<T>): FixedVoiceVerticality =
        frame.toVerticality(voices)

    /** Adds one DP frame and scores only newly decided local/global rules plus one motion edge. */
    internal fun dpApplyAndScore(
        state: FixedVoiceWritingState<T>,
        frame: FixedVoiceWritingFrame<T>,
        verticalFindings: List<RuleFinding<EventId>>,
        previousScore: FixedVoiceIncrementalScore,
        task: WritingTask,
        includeGlobalFindings: Boolean,
        verticality: FixedVoiceVerticality? = null,
        previousVerticality: FixedVoiceVerticality? = null,
    ): FixedVoiceIncrementalStep<T> {
        require(task.ruleProfile.requirements.isEmpty()) {
            "Incremental DP scoring does not support RuleProfile requirements"
        }
        val nextWithFrames = state.copy(frames = state.frames + frame)
        val newLocalFindings = verticalFindings + transitionFindingsForNewFrame(
            state = nextWithFrames,
            frame = frame,
            verticality = verticality,
            previousVerticality = previousVerticality,
        )
        val nextState = nextWithFrames.copy(
            localFindings = state.localFindings + newLocalFindings,
        )
        var nextScore = appendIncrementalFindings(previousScore, newLocalFindings, task.ruleProfile)
        val previousFrame = state.frames.lastOrNull() ?: initialBoundary
        if (previousFrame != null) {
            val unweighted = previousScore.unweightedMotionCost +
                scorePolicy.unweightedTransitionMotionCost(previousFrame, frame, voices)
            nextScore = nextScore.copy(
                unweightedMotionCost = unweighted,
                motionCost = scorePolicy.weightedMotionCost(unweighted),
            )
        }
        if (includeGlobalFindings) {
            nextScore = dpAppendGlobalFindings(nextState, nextScore, task)
        }
        return FixedVoiceIncrementalStep(nextState, nextScore)
    }

    /**
     * 终层全局规则的独立入口。DP 先只算基础分，再按可采纳下界只为可能进入 top-k 的完整路径
     * 展开这些规则；不再对每条终边都在整条路径上求值一次。
     */
    internal fun dpAppendGlobalFindings(
        state: FixedVoiceWritingState<T>,
        score: FixedVoiceIncrementalScore,
        task: WritingTask,
    ): FixedVoiceIncrementalScore {
        return appendIncrementalFindings(score, scoreFindingsFor(state), task.ruleProfile)
    }

    internal val dpScorePolicy: FixedVoiceWritingScorePolicy get() = scorePolicy

    internal fun dpBreakdown(score: FixedVoiceIncrementalScore): ScoreBreakdown {
        val contributions = buildList {
            score.findings.forEach { finding ->
                val amount = scorePolicy.findingScore(finding)
                if (amount != 0.0) {
                    add(RuleScoreContribution(finding.ruleId, amount, finding.message))
                }
            }
            if (score.motionCost != 0.0) {
                add(RuleScoreContribution(MOTION_COST_RULE_ID, score.motionCost, "声部移动距离"))
            }
        }
        return ScoreBreakdown(
            total = contributions.sumOf { it.amount },
            findings = score.findings,
            contributions = contributions,
        )
    }

    override fun diversityKey(state: FixedVoiceWritingState<T>): String =
        state.frames.joinToString("|") { frame ->
            voices.joinToString(",") { voice -> frame.pitchFor(voice).format() }
        }

    override fun diversityGroupKey(state: FixedVoiceWritingState<T>): String =
        state.frames.joinToString("|") { frame ->
            voices.joinToString(",") { voice -> frame.pitchFor(voice).pitchClass.value.toString() }
        }

    /**
     * 每帧结构 key = 目标身份 + 各声部 pitch class（diverse-search.md §4）。八度不同但目标与
     * 声部音级配置相同的帧视为同结构，用于多样化搜索的槽距离与强制变异。
     */
    override fun slotDiversityKeys(state: FixedVoiceWritingState<T>): List<String> =
        state.frames.map { frame ->
            frame.target.toString() + "#" +
                voices.joinToString(",") { voice -> frame.pitchFor(voice).pitchClass.value.toString() }
        }

    override fun similarity(
        left: FixedVoiceWritingState<T>,
        right: FixedVoiceWritingState<T>,
    ): Double {
        val leftFrames = left.frames
        val rightFrames = right.frames
        if (leftFrames.size != rightFrames.size || leftFrames.isEmpty()) return 0.0
        val total = leftFrames.size * voices.size
        val same = leftFrames.indices.sumOf { index ->
            voices.count { voice ->
                leftFrames[index].pitchFor(voice).pitchClass == rightFrames[index].pitchFor(voice).pitchClass
            }
        }
        return same.toDouble() / total.toDouble()
    }

    override fun prefixSimilarity(
        left: FixedVoiceWritingState<T>,
        right: FixedVoiceWritingState<T>,
    ): Double {
        val leftFrames = left.frames
        val rightFrames = right.frames
        if (leftFrames.size != rightFrames.size || leftFrames.isEmpty()) return 0.0
        val total = leftFrames.size * voices.size
        val same = leftFrames.indices.sumOf { index ->
            voices.count { voice ->
                leftFrames[index].pitchFor(voice).midiNumber ==
                    rightFrames[index].pitchFor(voice).midiNumber
            }
        }
        return same.toDouble() / total.toDouble()
    }

    override fun prefixDiversityGroupKey(state: FixedVoiceWritingState<T>): String {
        val frame = state.frames.lastOrNull() ?: return "empty"
        val soprano = voices.firstOrNull { it.role == FixedVoiceRole.SOPRANO }
        val bass = voices.firstOrNull { it.role == FixedVoiceRole.BASS }
        return buildString {
            append(frame.target)
            append('#')
            append(soprano?.let { frame.pitchFor(it).midiNumber } ?: "-")
            append('/')
            append(bass?.let { frame.pitchFor(it).midiNumber } ?: "-")
        }
    }

    /**
     * anchor(EventId) → 槽位映射，供 [applyRequirements] 的窗口投影把 finding 归到所在槽。
     * 复用 [eventFor] 的 `solver-{voice,pitch}-$slotIndex-$voiceId` 命名（见 [solverVoiceEventId] /
     * [solverPitchEventId]）。
     */
    private fun anchorSlotsOf(state: FixedVoiceWritingState<T>): Map<EventId, Int> =
        buildMap {
            state.frames.forEach { frame ->
                voices.forEach { voice ->
                    put(solverVoiceEventId(frame.slotIndex, voice.id), frame.slotIndex)
                    put(solverPitchEventId(frame.slotIndex, voice.id), frame.slotIndex)
                }
            }
        }

    private fun findingsFor(state: FixedVoiceWritingState<T>): List<RuleFinding<EventId>> =
        state.localFindings + scoreFindingsFor(state)

    private fun scoreFindingsFor(state: FixedVoiceWritingState<T>): List<RuleFinding<EventId>> {
        if (state.frames.isEmpty()) return emptyList()
        val context = FixedVoiceScoreRuleContext(state) { state.toFixedVoiceScore(voices, scoreId) }
        return ruleProviders.flatMap { it.checkScore(context) }
    }

    private fun localFindingsForNewFrame(
        state: FixedVoiceWritingState<T>,
        frame: FixedVoiceWritingFrame<T>,
    ): List<RuleFinding<EventId>> =
        verticalFindingsForNewFrame(state, frame) + transitionFindingsForNewFrame(state, frame)

    private fun verticalFindingsForNewFrame(
        state: FixedVoiceWritingState<T>,
        frame: FixedVoiceWritingFrame<T>,
        verticality: FixedVoiceVerticality? = null,
    ): List<RuleFinding<EventId>> {
        val verticalContext = FixedVoiceVerticalRuleContext(
            frame = frame,
            verticality = verticality ?: frame.toVerticality(voices),
            state = state,
        )
        return ruleProviders.flatMap { it.checkVertical(verticalContext) }
    }

    private fun transitionFindingsForNewFrame(
        state: FixedVoiceWritingState<T>,
        frame: FixedVoiceWritingFrame<T>,
        verticality: FixedVoiceVerticality? = null,
        previousVerticality: FixedVoiceVerticality? = null,
    ): List<RuleFinding<EventId>> {
        val previous = state.frames.getOrNull(state.frames.lastIndex - 1)
            ?: initialBoundary.takeIf { state.frames.size == 1 }
        if (previous == null) return emptyList()
        val transitionContext = FixedVoiceTransitionRuleContext(
            previousFrame = previous,
            currentFrame = frame,
            transition = FixedVoiceTransition(
                previousVerticality ?: previous.toVerticality(voices),
                verticality ?: frame.toVerticality(voices),
            ),
            state = state,
        )
        return ruleProviders.flatMap { it.checkTransition(transitionContext) }
    }

    /**
     * 只按新增/移除的可见 finding 更新总分与硬违规计数。发生抑制回撤（罕见）时退回整段求和，
     * 保证与一次性 [applyProfile] 的浮点结果逐位一致。
     */
    private fun appendIncrementalFindings(
        score: FixedVoiceIncrementalScore,
        findings: List<RuleFinding<EventId>>,
        profile: RuleProfile,
    ): FixedVoiceIncrementalScore {
        val update = score.profiledFindings.append(findings, profile)
        if (update.result === score.profiledFindings) return score
        val findingTotal = if (update.removedVisible.isEmpty()) {
            update.addedVisible.fold(score.findingTotal) { total, finding ->
                total + scorePolicy.findingScore(finding)
            }
        } else {
            update.result.visible.sumOf(scorePolicy::findingScore)
        }
        return score.copy(
            profiledFindings = update.result,
            findingTotal = findingTotal,
            hardViolations = score.hardViolations -
                update.removedVisible.count { it.isHardViolation() } +
                update.addedVisible.count { it.isHardViolation() },
        )
    }
}

private fun List<RuleFinding<EventId>>.applyRequirements(
    requirements: List<RuleRequirement>,
    isComplete: Boolean,
    frameCount: Int,
    anchorSlots: Map<EventId, Int>,
): List<RuleFinding<EventId>> {
    if (requirements.isEmpty()) return this

    // finding 是否落在 requirement 窗口内：window=null 恒真（全局语义）；无可解析锚点的 finding
    // （如 score 级无锚 finding）保守视为在窗口内，避免因锚点缺失而漏判满足。
    fun RuleFinding<EventId>.inWindow(window: SlotWindow?): Boolean {
        if (window == null) return true
        val slots = anchors.mapNotNull { anchorSlots[it] }
        if (slots.isEmpty()) return true
        return slots.any { window.contains(it) }
    }

    val violationReqs = requirements.filter { it.mode == RequirementMode.REQUIRE_VIOLATION }
    val adjusted = map { finding ->
        val demote = finding.kind == RuleFindingKind.VIOLATION &&
            violationReqs.any { it.ruleId == finding.ruleId && finding.inWindow(it.window) }
        if (demote) {
            finding.copy(
                severity = RuleSeverity.HINT,
                message = "演示目标：" + finding.message,
            )
        } else {
            finding
        }
    }
    if (!isComplete) return adjusted

    val missing = requirements.mapNotNull { requirement ->
        // 生成期投影：窗口起点仍在未来（前缀尚未覆盖）时不裁决——完整解处 frameCount 已覆盖全部槽位，
        // 但开放 / 末端窗口仍以此兜底，杜绝"末端 requirement 在中途被判缺失"。
        val decidable = requirement.window?.let { frameCount > it.start } ?: true
        if (!decidable) return@mapNotNull null
        val matched = adjusted.any { finding ->
            finding.ruleId == requirement.ruleId && finding.inWindow(requirement.window) &&
                when (requirement.mode) {
                    RequirementMode.REQUIRE_INDICATION -> finding.kind == RuleFindingKind.INDICATION
                    RequirementMode.REQUIRE_VIOLATION -> finding.kind == RuleFindingKind.VIOLATION
                    RequirementMode.FORBID -> false
                }
        }
        val presentInWindow = adjusted.any { it.ruleId == requirement.ruleId && it.inWindow(requirement.window) }
        when {
            requirement.mode == RequirementMode.FORBID && presentInWindow ->
                requirement.unsatisfiedFinding("出现了被禁止的写法。")
            requirement.mode != RequirementMode.FORBID && !matched ->
                requirement.unsatisfiedFinding("候选没有满足指定的规则要求。")
            else -> null
        }
    }
    return adjusted + missing
}

private fun RuleRequirement.unsatisfiedFinding(message: String): RuleFinding<EventId> =
    RuleFinding(
        ruleId = ruleId,
        kind = RuleFindingKind.VIOLATION,
        severity = RuleSeverity.HARD,
        message = message,
    )

fun <T> fixedVoiceTargetSequence(targets: List<T>): FixedVoiceTargetProvider<T> =
    FixedVoiceTargetProvider { state, _ ->
        targets.getOrNull(state.frames.size)?.let { listOf(it) }.orEmpty()
    }

data class FixedVoicePitchClassChoice(
    val voice: FixedVoice,
    val pitchClasses: Set<PitchClass>,
    val range: VoiceRange,
    /** Optional notation-preserving spellings for pitch classes in this choice. */
    val spellings: Map<PitchClass, SpelledPitchClass> = emptyMap(),
)

object FixedVoiceVoicingEnumerator {
    fun <T> enumerate(
        slotIndex: Int,
        target: T,
        choices: List<FixedVoicePitchClassChoice>,
        duration: Duration = Duration.QUARTER,
        requireNoCrossing: Boolean = true,
        allowFrame: (FixedVoiceWritingFrame<T>) -> Boolean = { true },
        shouldContinue: () -> Boolean = { true },
    ): List<FixedVoiceWritingFrame<T>> =
        sequence(
            slotIndex = slotIndex,
            target = target,
            choices = choices,
            duration = duration,
            requireNoCrossing = requireNoCrossing,
            allowFrame = allowFrame,
            shouldContinue = shouldContinue,
        ).sortedWith(
            compareBy<FixedVoiceWritingFrame<T>> { it.verticalSpan(choices.map { choice -> choice.voice }) }
                .thenBy { it.adjacentUpperDistance(FixedVoiceRole.SOPRANO, FixedVoiceRole.ALTO, choices.map { choice -> choice.voice }) }
                .thenBy { it.adjacentUpperDistance(FixedVoiceRole.ALTO, FixedVoiceRole.TENOR, choices.map { choice -> choice.voice }) }
                .thenBy { frame ->
                    choices.firstOrNull { it.voice.role == FixedVoiceRole.BASS }
                        ?.let { frame.pitchFor(it.voice).midiNumber }
                        ?: Int.MAX_VALUE
                }
        ).toList()

    /**
     * Enumerates frames lazily. [choices] must be ordered from highest to lowest voice when
     * [requireNoCrossing] is enabled; crossing branches are rejected before their descendants
     * are constructed.
     */
    fun <T> sequence(
        slotIndex: Int,
        target: T,
        choices: List<FixedVoicePitchClassChoice>,
        duration: Duration = Duration.QUARTER,
        requireNoCrossing: Boolean = true,
        allowFrame: (FixedVoiceWritingFrame<T>) -> Boolean = { true },
        shouldContinue: () -> Boolean = { true },
    ): Sequence<FixedVoiceWritingFrame<T>> {
        require(choices.isNotEmpty()) { "Voicing enumeration requires at least one voice choice" }
        return sequence {
            suspend fun SequenceScope<FixedVoiceWritingFrame<T>>.visit(
                index: Int,
                pitchesByVoiceId: Map<TrackId, Pitch>,
                previousPitch: Pitch?,
            ) {
                if (!shouldContinue()) return
                if (index == choices.size) {
                    val frame = FixedVoiceWritingFrame(
                        slotIndex = slotIndex,
                        target = target,
                        pitchesByVoiceId = pitchesByVoiceId,
                        duration = duration,
                    )
                    if ((!requireNoCrossing || frame.hasNoCrossing(choices.map { it.voice })) && allowFrame(frame)) {
                        yield(frame)
                    }
                    return
                }
                val choice = choices[index]
                choice.pitchClasses
                    .flatMap { pitchClass ->
                        pitchesFor(pitchClass, choice.range, choice.spellings[pitchClass])
                    }
                    .forEach { pitch ->
                        if (!shouldContinue()) return@forEach
                        if (!requireNoCrossing || previousPitch == null || previousPitch.midiNumber >= pitch.midiNumber) {
                            visit(index + 1, pitchesByVoiceId + (choice.voice.id to pitch), pitch)
                        }
                    }
            }
            visit(0, emptyMap(), null)
        }
    }

    private fun pitchesFor(
        pitchClass: PitchClass,
        range: VoiceRange,
        spelling: SpelledPitchClass?,
    ): List<Pitch> {
        if (spelling != null) {
            return ((range.lowest.octave - 1)..(range.highest.octave + 1))
                .map(spelling::pitchAt)
                .filter { it.midiNumber in range.lowest.midiNumber..range.highest.midiNumber }
                .distinctBy { it.midiNumber }
        }
        return (range.lowest.midiNumber..range.highest.midiNumber)
            .filter { it.mod(OCTAVE_SEMITONES) == pitchClass.value }
            .map { Pitch.fromMidi(it, preferSharps = true) }
    }
}

fun standardFourPartWritingVoices(): List<FixedVoice> =
    STANDARD_FOUR_PART_WRITING_VOICES

fun <T> FixedVoiceWritingState<T>.toFixedVoiceScore(
    voices: List<FixedVoice> = standardFourPartWritingVoices(),
    scoreId: ScoreId = ScoreId("fixed-voice-writing-solver"),
): FixedVoiceScore =
    FixedVoiceScore(
        scoreId = scoreId,
        voices = voices,
        eventsByVoice = voices.associate { voice ->
            voice.id to frames.map { frame -> frame.eventFor(voice) }
        },
    )

fun <T> FixedVoiceWritingFrame<T>.toVerticality(
    voices: List<FixedVoice> = standardFourPartWritingVoices(),
): FixedVoiceVerticality =
    FixedVoiceVerticality(
        time = TimeCode.of(1, slotIndex, 4),
        notes = voices.map { voice -> eventFor(voice) },
    )

/** 求解器合成事件的稳定 id 方案，[eventFor] 与 anchor→slot 映射共用，避免命名漂移。 */
internal fun solverVoiceEventId(slotIndex: Int, voiceId: TrackId): EventId =
    EventId("solver-voice-$slotIndex-${voiceId.value}")

internal fun solverPitchEventId(slotIndex: Int, voiceId: TrackId): EventId =
    EventId("solver-pitch-$slotIndex-${voiceId.value}")

private fun <T> FixedVoiceWritingFrame<T>.eventFor(voice: FixedVoice): FixedVoiceScoreEvent {
    val onset = TimeCode.of(1, slotIndex, 4)
    val pitch = pitchFor(voice)
    val pitchEvent = RuntimePitchEvent(
        id = solverPitchEventId(slotIndex, voice.id),
        onset = onset,
        pitches = listOf(pitch),
    )
    return FixedVoiceScoreEvent(
        voice = voice,
        event = RuntimeVoiceEvent(
            id = solverVoiceEventId(slotIndex, voice.id),
            onset = onset,
            pitchEvent = pitchEvent,
            duration = duration,
        ),
        pitch = pitch,
    )
}

private fun <T> FixedVoiceWritingFrame<T>.hasNoCrossing(voices: List<FixedVoice>): Boolean =
    voices.zipWithNext().all { (upper, lower) ->
        pitchFor(upper).midiNumber >= pitchFor(lower).midiNumber
    }

private fun <T> FixedVoiceWritingFrame<T>.verticalSpan(voices: List<FixedVoice>): Int {
    val pitches = voices.map { pitchFor(it).midiNumber }
    return (pitches.maxOrNull() ?: 0) - (pitches.minOrNull() ?: 0)
}

private fun <T> FixedVoiceWritingFrame<T>.adjacentUpperDistance(
    upperRole: FixedVoiceRole,
    lowerRole: FixedVoiceRole,
    voices: List<FixedVoice>,
): Int {
    val upper = voices.firstOrNull { it.role == upperRole } ?: return Int.MAX_VALUE
    val lower = voices.firstOrNull { it.role == lowerRole } ?: return Int.MAX_VALUE
    return abs(pitchFor(upper).midiNumber - pitchFor(lower).midiNumber)
}

fun VoicePlan.toFixedVoices(): List<FixedVoice> =
    orderedHighToLow.map { spec ->
        FixedVoice(
            id = spec.id,
            staffTrackId = TrackId("solver-staff-${spec.order}"),
            staffIndex = spec.order,
            voiceIndexOnStaff = 0,
            role = spec.legacyRole ?: when (spec.boundary) {
                VoiceBoundary.UPPER_OUTER -> FixedVoiceRole.SOPRANO
                VoiceBoundary.INNER -> FixedVoiceRole.INNER
                VoiceBoundary.LOWER_OUTER -> FixedVoiceRole.BASS
            },
        )
    }

private val MOTION_COST_RULE_ID = RuleId("solver.motion-cost")

private val STANDARD_FOUR_PART_WRITING_VOICES = listOf(
    FixedVoice(
        id = TrackId("solver-soprano"),
        staffTrackId = TrackId("solver-upper-staff"),
        staffIndex = 0,
        voiceIndexOnStaff = 0,
        role = FixedVoiceRole.SOPRANO,
    ),
    FixedVoice(
        id = TrackId("solver-alto"),
        staffTrackId = TrackId("solver-upper-staff"),
        staffIndex = 0,
        voiceIndexOnStaff = 1,
        role = FixedVoiceRole.ALTO,
    ),
    FixedVoice(
        id = TrackId("solver-tenor"),
        staffTrackId = TrackId("solver-lower-staff"),
        staffIndex = 1,
        voiceIndexOnStaff = 0,
        role = FixedVoiceRole.TENOR,
    ),
    FixedVoice(
        id = TrackId("solver-bass"),
        staffTrackId = TrackId("solver-lower-staff"),
        staffIndex = 1,
        voiceIndexOnStaff = 1,
        role = FixedVoiceRole.BASS,
    ),
)

private const val OCTAVE_SEMITONES = 12
