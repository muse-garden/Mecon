package com.mecon.theory.constraint

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TrackId
import com.mecon.theory.FixedVoice
import com.mecon.theory.FixedVoiceTransitionRuleContext
import com.mecon.theory.FixedVoiceVerticalRuleContext
import com.mecon.theory.FixedVoiceWritingFrame
import com.mecon.theory.FixedVoiceWritingRuleProvider
import com.mecon.theory.HarmonySlotId
import com.mecon.theory.RuleFinding
import com.mecon.theory.RuleFindingKind
import com.mecon.theory.RuleId
import com.mecon.theory.RuleSeverity
import com.mecon.theory.SearchCancellation
import com.mecon.theory.WritingSearchTrace
import com.mecon.theory.solverVoiceEventId
import kotlin.math.abs

data class FixedVoiceBoundaryFrame(
    val target: ChordTarget,
    val pitchesByVoiceId: Map<TrackId, Pitch>,
)

data class VoicePitchBaseline(
    val pitchesBySlotAndVoice: Map<HarmonySlotId, Map<TrackId, Pitch>>,
    val changeWeight: Double = 18.0,
) {
    init { require(changeWeight >= 0.0) }
}

data class ConstraintSolveContext(
    val leftBoundary: FixedVoiceBoundaryFrame? = null,
    val baseline: VoicePitchBaseline? = null,
    /** Whether a caller-owned score projection should be used as an implicit rewrite baseline. */
    val preserveProjectedBaseline: Boolean = true,
    val excludedDiversityGroupKeys: Set<String> = emptySet(),
    val cancellation: SearchCancellation = SearchCancellation.NONE,
    val relaxBoundaryLargeLeaps: Boolean = false,
)

sealed interface ConstraintSolveOutcome {
    val trace: WritingSearchTrace?

    data class Solved(
        val solutions: List<PolyphonicConstraintSolution>,
        override val trace: WritingSearchTrace,
    ) : ConstraintSolveOutcome

    data class NoSolution(override val trace: WritingSearchTrace) : ConstraintSolveOutcome
    data class BudgetExhausted(override val trace: WritingSearchTrace) : ConstraintSolveOutcome
    data class Cancelled(override val trace: WritingSearchTrace?) : ConstraintSolveOutcome
    data class Invalid(
        val diagnostics: List<ConstraintSolveDiagnostic>,
        override val trace: WritingSearchTrace? = null,
    ) : ConstraintSolveOutcome
}

enum class ConstraintSolveDiagnosticCode {
    TARGET_PREFLIGHT_REJECTED,
    UNSUPPORTED_SEARCH_BACKEND,
    INVALID_REQUEST,
}

data class ConstraintSolveDiagnostic(
    val code: ConstraintSolveDiagnosticCode,
    val message: String,
    val ruleId: RuleId? = null,
    val slotIndex: Int? = null,
)

data class SearchFeasibilityPolicy(
    val upperAdjacentSpacingSemitones: Int = 12,
    val lowestAdjacentSpacingSemitones: Int = 19,
    val largeLeapThresholdSemitones: Int = 12,
    val maxSimultaneousLargeLeapVoices: Int = 1,
)

internal data class AdjacentSpacingViolation(
    val upper: FixedVoice,
    val lower: FixedVoice,
    val distance: Int,
    val limit: Int,
)

internal fun SearchFeasibilityPolicy.adjacentSpacingViolations(
    voicesHighToLow: List<FixedVoice>,
    frame: FixedVoiceWritingFrame<*>,
): List<AdjacentSpacingViolation> =
    voicesHighToLow.zipWithNext().mapIndexedNotNull { index, (upper, lower) ->
        val distance = abs(frame.pitchFor(upper).midiNumber - frame.pitchFor(lower).midiNumber)
        val limit = if (index == voicesHighToLow.size - 2) {
            lowestAdjacentSpacingSemitones
        } else {
            upperAdjacentSpacingSemitones
        }
        if (distance <= limit) null else AdjacentSpacingViolation(upper, lower, distance, limit)
    }

internal fun SearchFeasibilityPolicy.simultaneousLargeLeapVoices(
    voicesHighToLow: List<FixedVoice>,
    previousFrame: FixedVoiceWritingFrame<*>,
    currentFrame: FixedVoiceWritingFrame<*>,
): List<FixedVoice> = voicesHighToLow.filter { voice ->
    abs(currentFrame.pitchFor(voice).midiNumber - previousFrame.pitchFor(voice).midiNumber) >
        largeLeapThresholdSemitones
}

internal class WindowFeasibilityRuleProvider(
    private val voicesHighToLow: List<FixedVoice>,
    private val policy: SearchFeasibilityPolicy = SearchFeasibilityPolicy(),
    private val relaxBoundaryLargeLeaps: Boolean,
) : FixedVoiceWritingRuleProvider<ChordTarget> {
    override fun checkVertical(
        context: FixedVoiceVerticalRuleContext<ChordTarget>,
    ): List<RuleFinding<EventId>> =
        policy.adjacentSpacingViolations(voicesHighToLow, context.frame).map { violation ->
            hardFinding(
                id = ADJACENT_SPACING,
                message = "相邻声部间距 ${violation.distance} 个半音，超过搜索上限 ${violation.limit}。",
                anchors = listOf(
                    solverVoiceEventId(context.frame.slotIndex, violation.upper.id),
                    solverVoiceEventId(context.frame.slotIndex, violation.lower.id),
                ),
            )
        }

    override fun checkTransition(
        context: FixedVoiceTransitionRuleContext<ChordTarget>,
    ): List<RuleFinding<EventId>> {
        val leaping = policy.simultaneousLargeLeapVoices(
            voicesHighToLow,
            context.previousFrame,
            context.currentFrame,
        )
        if (leaping.size <= policy.maxSimultaneousLargeLeapVoices) return emptyList()
        val boundaryTransition = context.previousFrame.slotIndex < 0
        return listOf(
            RuleFinding(
                ruleId = SIMULTANEOUS_LARGE_LEAPS,
                kind = RuleFindingKind.VIOLATION,
                severity = if (boundaryTransition && relaxBoundaryLargeLeaps) {
                    RuleSeverity.SOFT
                } else {
                    RuleSeverity.HARD
                },
                message = "前后有 ${leaping.size} 个声部同时做超过八度的跳进。",
                anchors = leaping.map { solverVoiceEventId(context.currentFrame.slotIndex, it.id) },
                scoreDelta = if (boundaryTransition && relaxBoundaryLargeLeaps) 140.0 else 0.0,
            ),
        )
    }

    private fun hardFinding(
        id: RuleId,
        message: String,
        anchors: List<EventId>,
    ): RuleFinding<EventId> =
        RuleFinding(id, RuleFindingKind.VIOLATION, RuleSeverity.HARD, message, anchors)

    companion object {
        val ADJACENT_SPACING = RuleId("free.feasibility.adjacent-spacing")
        val SIMULTANEOUS_LARGE_LEAPS = RuleId("free.feasibility.simultaneous-large-leaps")

        /** Every rule this provider can emit; the DP declaration completeness guard reads it. */
        val ALL_RULE_IDS: Set<RuleId> = setOf(ADJACENT_SPACING, SIMULTANEOUS_LARGE_LEAPS)

        internal fun dpStateDeclarations(): List<LayeredDpRuleStateDeclaration> = listOf(
            LayeredDpRuleStateDeclaration(ADJACENT_SPACING),
            LayeredDpRuleStateDeclaration(SIMULTANEOUS_LARGE_LEAPS, recentFrames = 1),
        )
    }
}

internal class BaselineSimilarityRuleProvider(
    private val program: ConstraintProgram,
    private val voices: List<FixedVoice>,
    private val baseline: VoicePitchBaseline,
) : FixedVoiceWritingRuleProvider<ChordTarget> {
    override fun checkVertical(
        context: FixedVoiceVerticalRuleContext<ChordTarget>,
    ): List<RuleFinding<EventId>> {
        val slotId = program.slots[context.frame.slotIndex].id
        val expected = baseline.pitchesBySlotAndVoice[slotId] ?: return emptyList()
        return voices.mapNotNull { voice ->
            val oldPitch = expected[voice.id] ?: return@mapNotNull null
            val newPitch = context.frame.pitchFor(voice)
            if (oldPitch == newPitch) return@mapNotNull null
            val distance = abs(newPitch.midiNumber - oldPitch.midiNumber)
            RuleFinding(
                ruleId = BASELINE_DISTANCE,
                kind = RuleFindingKind.VIOLATION,
                severity = RuleSeverity.SOFT,
                message = "重写改变了声部 ${voice.id.value} 的原有音高。",
                anchors = listOf(solverVoiceEventId(context.frame.slotIndex, voice.id)),
                scoreDelta = baseline.changeWeight * (1.0 + distance.coerceAtMost(12) / 12.0),
            )
        }
    }

    companion object {
        val BASELINE_DISTANCE = RuleId("solver.refine.baseline-distance")

        /** Every rule this provider can emit; the DP declaration completeness guard reads it. */
        val ALL_RULE_IDS: Set<RuleId> = setOf(BASELINE_DISTANCE)

        internal fun dpStateDeclarations(): List<LayeredDpRuleStateDeclaration> =
            listOf(LayeredDpRuleStateDeclaration(BASELINE_DISTANCE))
    }
}
