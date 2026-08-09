package com.mecon.theory

import kotlin.jvm.JvmInline

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeRange
import com.mecon.api.primitive.TrackId

enum class WritingTexture {
    FOUR_PART_FIXED_VOICE,
    MELODY_HARMONIZATION,
    COUNTERPOINT,
    FREE_POLYPHONY,
}

sealed interface MaterialConstraint {
    data class FixedVoiceEvents(
        val voiceId: TrackId,
        val eventIds: List<EventId>,
    ) : MaterialConstraint

    data class FixedPitch(
        val voiceId: TrackId,
        val time: TimeCode,
        val pitch: Pitch,
    ) : MaterialConstraint
}

sealed interface WritingTarget {
    val range: TimeRange

    data class Harmonic(
        override val range: TimeRange,
        val chord: Chord? = null,
        val functionLabel: String? = null,
    ) : WritingTarget

    data class Melodic(
        override val range: TimeRange,
        val voiceId: TrackId? = null,
    ) : WritingTarget

    data class Contrapuntal(
        override val range: TimeRange,
        val species: String? = null,
    ) : WritingTarget

    data class Cadence(
        override val range: TimeRange,
        val typeId: ConnectionTypeId? = null,
    ) : WritingTarget
}

data class WritingTimeline(
    val range: TimeRange,
    val slots: List<TimeCode>,
) {
    init {
        require(slots.isNotEmpty()) { "A writing timeline must contain at least one slot" }
        require(slots.all { it in range }) { "All writing slots must be inside the task range" }
    }
}

/** 重合后如何裁剪变异分支（diverse-search.md §5）。 */
enum class RejoinPolicy {
    /** 默认：达到距离门槛前的过早重合剪枝，之后允许偶尔重合。 */
    BEFORE_MIN_DISTANCE,

    /** 实验：只接受单一连续变异区间，首次重合即定型。 */
    FIRST_REJOIN,
}

/**
 * 多样化重启搜索配置（diverse-search.md §7）。[enabled] 关闭时求解器保持既有确定性贪心
 * DFS；开启时在确定性首解之后进入强制变异重启阶段，用 [seed] 保证可复现。
 */
data class DiversitySearchConfig(
    val enabled: Boolean = false,
    val seed: Long = 0L,
    val restartBudget: Int = 32,
    val mutationPoolSize: Int = 4,
    val minChangedSlotRatio: Double = 0.35,
    val minChangedVoiceCellRatio: Double = 0.20,
    val earlyMutationBias: Double = 1.0,
    /** Prefer mutating the chord immediately before a large positive prefix-score increment. */
    val penaltyMutationBias: Double = 2.0,
    val rejoinPolicy: RejoinPolicy = RejoinPolicy.BEFORE_MIN_DISTANCE,
) {
    init {
        require(restartBudget > 0) { "restartBudget must be positive" }
        require(mutationPoolSize > 0) { "mutationPoolSize must be positive" }
        require(minChangedSlotRatio in 0.0..1.0) { "minChangedSlotRatio must be in 0.0..1.0" }
        require(minChangedVoiceCellRatio in 0.0..1.0) { "minChangedVoiceCellRatio must be in 0.0..1.0" }
        require(earlyMutationBias >= 0.0) { "earlyMutationBias must be non-negative" }
        require(penaltyMutationBias >= 0.0) { "penaltyMutationBias must be non-negative" }
    }
}

/**
 * Keeps several strong but locally different prefixes alive at every search depth.
 * Unlike result diversification, this affects the deterministic first solution and is therefore
 * opt-in for callers that need continuation-aware automatic writing.
 */
data class PrefixDiversitySearchConfig(
    val enabled: Boolean = false,
    val frontierWidth: Int = 32,
    val similarityWeight: Double = 4.0,
    val scoreTolerance: Double = 12.0,
) {
    init {
        require(frontierWidth > 0) { "frontierWidth must be positive" }
        require(similarityWeight >= 0.0) { "similarityWeight must be non-negative" }
        require(scoreTolerance >= 0.0) { "scoreTolerance must be non-negative" }
    }
}

enum class SearchBackend {
    /** Prefer layered dynamic programming when the active rules are supported, otherwise use DFS. */
    AUTO,

    GREEDY_DFS,
    LAYERED_DP,
}

enum class DynamicProgrammingSearchMode {
    /** Do not truncate a layer. Intended for small diagnostic and differential-test programs. */
    EXACT,

    /** Keep deterministic, explicitly bounded candidate and frontier sets. */
    BOUNDED,
}

data class DynamicProgrammingSearchConfig(
    val mode: DynamicProgrammingSearchMode = DynamicProgrammingSearchMode.BOUNDED,
    val maxCandidatesPerTarget: Int = 128,
    val maxLabelsPerState: Int = 4,
    val maxFrontierStates: Int = 4_096,
    /** Hard cap for scored DP edges. Unlike SearchConfig.nodeBudget, this measures actual DP work. */
    val maxTransitionEvaluations: Int = 262_144,
) {
    init {
        require(maxCandidatesPerTarget > 0) { "maxCandidatesPerTarget must be positive" }
        require(maxLabelsPerState > 0) { "maxLabelsPerState must be positive" }
        require(maxFrontierStates > 0) { "maxFrontierStates must be positive" }
        require(maxTransitionEvaluations > 0) { "maxTransitionEvaluations must be positive" }
    }
}

data class SearchConfig(
    val maxResults: Int = 8,
    val beamWidth: Int = 32,
    val diversityWeight: Double = 0.0,
    val diversity: DiversitySearchConfig = DiversitySearchConfig(),
    val prefixDiversity: PrefixDiversitySearchConfig = PrefixDiversitySearchConfig(),
    val backend: SearchBackend = SearchBackend.AUTO,
    val dynamicProgramming: DynamicProgrammingSearchConfig = DynamicProgrammingSearchConfig(),
) {
    init {
        require(maxResults > 0) { "maxResults must be positive" }
        require(beamWidth > 0) { "beamWidth must be positive" }
        require(diversityWeight >= 0.0) { "diversityWeight must be non-negative" }
    }

    /**
     * 每目标/每层保留的候选上限。迁移期沿用 [beamWidth]（diverse-search.md §7 允许的兼容映射），
     * 后续可与 [nodeBudget] 彻底解耦。
     */
    val candidateLimit: Int get() = beamWidth

    /** 搜索访问节点总预算，覆盖首解与全部重启。语义与既有 DFS 内公式保持一致。 */
    val nodeBudget: Int get() {
        val activeWidth = if (prefixDiversity.enabled) {
            maxOf(maxResults, prefixDiversity.frontierWidth)
        } else {
            maxResults
        }
        return maxOf(MIN_NODE_BUDGET, beamWidth * activeWidth * NODE_BUDGET_MULTIPLIER)
    }

    private companion object {
        const val MIN_NODE_BUDGET = 2_048
        const val NODE_BUDGET_MULTIPLIER = 32
    }
}

data class WritingTask(
    val texture: WritingTexture,
    val timeline: WritingTimeline,
    val fixedMaterial: List<MaterialConstraint> = emptyList(),
    val targets: List<WritingTarget> = emptyList(),
    val ruleProfile: RuleProfile = RuleProfile("default"),
    val searchConfig: SearchConfig = SearchConfig(),
)

@JvmInline
value class WritingTaskStageId(val value: String) {
    override fun toString(): String = value
}

data class WritingTaskStage(
    val id: WritingTaskStageId,
    val task: WritingTask,
    val dependsOn: Set<WritingTaskStageId> = emptySet(),
    val outputLabel: String? = null,
)

data class WritingTaskPlan(
    val stages: List<WritingTaskStage>,
) {
    init {
        require(stages.isNotEmpty()) { "A writing task plan must contain at least one stage" }
        val ids = stages.map { it.id }
        require(ids.toSet().size == ids.size) { "Writing task stage ids must be unique" }
        val knownIds = ids.toSet()
        stages.forEach { stage ->
            require(stage.dependsOn.all { it in knownIds }) {
                "Writing task stage ${stage.id} depends on an unknown stage"
            }
        }
    }
}

data class CandidateConstraintResult<Candidate>(
    val candidates: List<Candidate>,
    val findings: List<RuleFinding<EventId>> = emptyList(),
)

interface CandidateSpace<State, Candidate> {
    fun initial(task: WritingTask): State
    fun candidates(state: State, task: WritingTask): List<Candidate>
    fun apply(state: State, candidate: Candidate): State
}

@JvmInline
value class ConnectionTypeId(val value: String) {
    override fun toString(): String = value
}

data class HarmonicState(
    val time: TimeCode,
    val chord: Chord? = null,
    val key: Key? = null,
    val label: String? = null,
)

data class HarmonicConnection(
    val before: HarmonicState,
    val after: HarmonicState,
    val typeIds: Set<ConnectionTypeId> = emptySet(),
)

data class ConnectionType(
    val id: ConnectionTypeId,
    val matcher: (TransitionContext) -> Boolean,
)

data class FixedVoiceTransition(
    val previous: FixedVoiceVerticality,
    val current: FixedVoiceVerticality,
) {
    val pairMotions: List<VoicePairMotion> by lazy {
        VoiceLeadingAnalysis.pairMotionsBetween(previous, current)
    }

    val crossings: List<VoiceCrossing> by lazy {
        VoiceLeadingAnalysis.crossingsBetween(previous, current)
    }

    fun containsAnchor(eventId: EventId): Boolean =
        previous.notes.any { it.id == eventId } || current.notes.any { it.id == eventId }
}

data class TransitionContext(
    val fixedVoice: FixedVoiceTransition? = null,
    val harmonicConnection: HarmonicConnection? = null,
)
