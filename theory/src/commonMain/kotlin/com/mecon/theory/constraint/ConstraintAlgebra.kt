package com.mecon.theory.constraint

import com.mecon.api.primitive.EventId
import com.mecon.theory.RequirementMode
import com.mecon.theory.RuleFindingKind
import com.mecon.theory.RuleId
import com.mecon.theory.RuleRequirement
import com.mecon.theory.SlotWindow

/** Kleene 三值逻辑：未落定的未来槽不会把仍可满足的前缀误剪掉。 */
enum class ConstraintTruth { SATISFIED, VIOLATED, UNDETERMINED }

/** 约束强度同时决定剪枝、计分与解释如何由同一表达式发射。 */
sealed interface ConstraintModality {
    data object Require : ConstraintModality
    data class Prefer(val weight: Double? = null) : ConstraintModality
    data class Reward(val bonus: Double) : ConstraintModality
    data object Annotate : ConstraintModality
}

data class ConstraintExplanation(
    val satisfied: String,
    val violated: String = satisfied,
)

/** Constraint 自身的适用域；原子内的旧 window/selector 与它取交集。 */
data class ConstraintScope(
    val window: SlotWindow? = null,
    val selector: TargetSelector = TargetSelector(),
) {
    fun matches(slot: Int, target: ChordTarget): Boolean =
        (window == null || window.contains(slot)) && selector.matches(target)
}

/** expr 是唯一判定本体；ruleId / explanation 只负责稳定身份与教学解释。 */
data class Constraint(
    val expr: ConstraintExpr,
    val modality: ConstraintModality = ConstraintModality.Require,
    val ruleId: RuleId? = null,
    val explanation: ConstraintExplanation? = null,
    val scope: ConstraintScope = ConstraintScope(),
)

sealed interface ConstraintExpr {
    data class Atom(val predicate: ConstraintPredicate) : ConstraintExpr
    data class And(val terms: List<ConstraintExpr>) : ConstraintExpr {
        init { require(terms.isNotEmpty()) { "And requires at least one term" } }
    }
    data class Or(val branches: List<ConstraintBranch>) : ConstraintExpr {
        init { require(branches.isNotEmpty()) { "Or requires at least one branch" } }
    }
    data class Not(val term: ConstraintExpr) : ConstraintExpr
}

/** Or 分支可独立命名；命中后由通用发射器采用该分支的 ruleId / 文案。 */
data class ConstraintBranch(
    val expr: ConstraintExpr,
    val ruleId: RuleId? = null,
    val explanation: ConstraintExplanation? = null,
    val scoreDelta: Double? = null,
)

/**
 * M6 原子词汇。既有 requirement 只作为原子参数对象，不再是 ConstraintProgram 的平行列表字段。
 */
sealed interface ConstraintPredicate {
    data class ToneCompleteness(val requirement: ToneCompletenessRequirement) : ConstraintPredicate
    data class ToneDoubled(val requirement: DoublingRequirement) : ConstraintPredicate
    data class ToneNotDoubled(val requirement: AvoidDoublingRequirement) : ConstraintPredicate
    data class ScaleDegreeNotDoubled(val requirement: AvoidScaleDegreeDoublingRequirement) : ConstraintPredicate
    data class Spacing(val requirement: SpacingRequirement) : ConstraintPredicate
    data class DistinctIdentities(val requirement: AllDifferentRequirement) : ConstraintPredicate
    data class CommonToneWithPrevious(val requirement: AdjacentCommonToneRequirement) : ConstraintPredicate
    data class NeighborTone(val requirement: ChordToneNeighborRequirement) : ConstraintPredicate
    data class TargetMatches(val requirement: TargetFeatureBonusRequirement) : ConstraintPredicate
    data class SameSonority(val slots: List<Int>) : ConstraintPredicate {
        init { require(slots.size >= 2) { "SameSonority requires at least two slots" } }
    }
    data class VoiceDiatonicSteps(
        val voiceFilter: ChordToneVoiceFilter,
        val slots: List<Int>,
        val allowedDeltas: List<Set<Int>>,
    ) : ConstraintPredicate {
        init {
            require(slots.size >= 2) { "VoiceDiatonicSteps requires at least two slots" }
            require(allowedDeltas.size == slots.size - 1) { "VoiceDiatonicSteps delta count mismatch" }
        }
    }
    data class VoicePitchClassCardinality(
        val voiceFilter: ChordToneVoiceFilter,
        val slots: List<Int>,
        val allowedCounts: Set<Int>,
    ) : ConstraintPredicate
    data class ToneMultiplicity(
        val slot: Int,
        val tone: ChordTone,
        val allowedCounts: Set<Int>,
    ) : ConstraintPredicate
    data class ToneInVoiceFilter(
        val slot: Int,
        val tone: ChordTone,
        val voiceFilter: ChordToneVoiceFilter,
    ) : ConstraintPredicate
    /**
     * Directed root motion measured upward modulo seven in the program key.
     * For example, V-I is +3 while I-V is +4.
     */
    data class RootDiatonicMotion(
        val fromSlot: Int,
        val toSlot: Int,
        val allowedDeltas: Set<Int>,
    ) : ConstraintPredicate {
        init {
            require(fromSlot >= 0 && toSlot > fromSlot) {
                "RootDiatonicMotion requires ordered non-negative slots"
            }
            require(allowedDeltas.isNotEmpty() && allowedDeltas.all { it in 0..6 }) {
                "RootDiatonicMotion deltas must be in 0..6"
            }
        }
    }
    data class UniqueVoiceExtreme(
        val voiceFilter: ChordToneVoiceFilter,
        val extreme: VoiceExtreme,
        val maxOccurrences: Int = 1,
    ) : ConstraintPredicate {
        init {
            require(maxOccurrences > 0) { "UniqueVoiceExtreme maxOccurrences must be positive" }
        }
    }
    data class NoRepeatedVoicePattern(
        val voiceFilter: ChordToneVoiceFilter,
        val minPatternNotes: Int = 2,
        val maxPatternNotes: Int = 5,
        val penaltyScale: Double = 1.0,
    ) : ConstraintPredicate {
        init {
            require(minPatternNotes >= 2) { "NoRepeatedVoicePattern requires at least two notes" }
            require(maxPatternNotes >= minPatternNotes) {
                "NoRepeatedVoicePattern maxPatternNotes must be >= minPatternNotes"
            }
            require(penaltyScale > 0.0) { "NoRepeatedVoicePattern penaltyScale must be positive" }
        }
    }
    /**
     * Treats chords with the same root as similar, regardless of inversion, arity, or quality.
     * Two similar chords must be at least [minimumSlotDistance] slots apart.
     */
    data class MinimumSimilarChordDistance(
        val minimumSlotDistance: Int = 3,
    ) : ConstraintPredicate {
        init {
            require(minimumSlotDistance >= 2) {
                "MinimumSimilarChordDistance must leave room between similar chords"
            }
        }
    }
    /** No directed pair of similar chord roots may occur twice in the same exercise. */
    data object DistinctSimilarChordProgressions : ConstraintPredicate
    data class RootProgressionPreference(
        val scoringPolicy: RootProgressionScoringPolicy,
    ) : ConstraintPredicate
    data class RuleFound(
        val ruleId: RuleId,
        val kind: RuleFindingKind? = null,
        val window: SlotWindow? = null,
    ) : ConstraintPredicate
}

enum class VoiceExtreme {
    HIGHEST,
    LOWEST,
}

internal data class ConstraintEvaluation(
    val truth: ConstraintTruth,
    val active: Boolean = true,
    val anchors: List<EventId> = emptyList(),
    val branchRuleId: RuleId? = null,
    val branchExplanation: ConstraintExplanation? = null,
    val branchScoreDelta: Double? = null,
)

internal fun ConstraintExpr.atomicPredicates(): Sequence<ConstraintPredicate> = sequence {
    when (this@atomicPredicates) {
        is ConstraintExpr.Atom -> yield(predicate)
        is ConstraintExpr.And -> terms.forEach { yieldAll(it.atomicPredicates()) }
        is ConstraintExpr.Or -> branches.forEach { yieldAll(it.expr.atomicPredicates()) }
        is ConstraintExpr.Not -> yieldAll(term.atomicPredicates())
    }
}

/** 与运行时求值器相同的纯 Kleene 传播，供脚本预检与组合表达式测试。 */
fun ConstraintExpr.evaluateTruth(atom: (ConstraintPredicate) -> ConstraintTruth): ConstraintTruth =
    when (this) {
        is ConstraintExpr.Atom -> atom(predicate)
        is ConstraintExpr.And -> {
            val values = terms.map { it.evaluateTruth(atom) }
            when {
                ConstraintTruth.VIOLATED in values -> ConstraintTruth.VIOLATED
                ConstraintTruth.UNDETERMINED in values -> ConstraintTruth.UNDETERMINED
                else -> ConstraintTruth.SATISFIED
            }
        }
        is ConstraintExpr.Or -> {
            val values = branches.map { it.expr.evaluateTruth(atom) }
            when {
                ConstraintTruth.SATISFIED in values -> ConstraintTruth.SATISFIED
                ConstraintTruth.UNDETERMINED in values -> ConstraintTruth.UNDETERMINED
                else -> ConstraintTruth.VIOLATED
            }
        }
        is ConstraintExpr.Not -> when (term.evaluateTruth(atom)) {
            ConstraintTruth.SATISFIED -> ConstraintTruth.VIOLATED
            ConstraintTruth.VIOLATED -> ConstraintTruth.SATISFIED
            ConstraintTruth.UNDETERMINED -> ConstraintTruth.UNDETERMINED
        }
    }

/** 旧 RuleAt 的二阶 requirement 可无损降糖为 RuleFound（FORBID = Not）。 */
fun RuleRequirement.toConstraint(): Constraint {
    val atom = ConstraintExpr.Atom(
        ConstraintPredicate.RuleFound(
            ruleId = ruleId,
            kind = when (mode) {
                RequirementMode.REQUIRE_INDICATION -> RuleFindingKind.INDICATION
                RequirementMode.REQUIRE_VIOLATION -> RuleFindingKind.VIOLATION
                RequirementMode.FORBID -> null
            },
            window = window,
        )
    )
    return Constraint(
        expr = if (mode == RequirementMode.FORBID) ConstraintExpr.Not(atom) else atom,
        ruleId = ruleId,
        explanation = ConstraintExplanation(
            satisfied = "候选满足指定的规则要求。",
            violated = if (mode == RequirementMode.FORBID) "出现了被禁止的写法。" else "候选没有满足指定的规则要求。",
        ),
    )
}

internal fun desugarRequirements(
    ruleRequirements: List<RuleRequirement>,
    toneCompleteness: List<ToneCompletenessRequirement>,
    doublings: List<DoublingRequirement>,
    avoidDoublings: List<AvoidDoublingRequirement>,
    avoidScaleDegreeDoublings: List<AvoidScaleDegreeDoublingRequirement>,
    spacings: List<SpacingRequirement>,
    allDifferent: List<AllDifferentRequirement>,
    adjacentCommonTones: List<AdjacentCommonToneRequirement>,
    chordToneNeighbors: List<ChordToneNeighborRequirement>,
    targetFeatureBonuses: List<TargetFeatureBonusRequirement>,
): List<Constraint> = buildList {
    addAll(ruleRequirements.map { it.toConstraint() })
    addAll(toneCompleteness.map {
        Constraint(
            expr = ConstraintExpr.Atom(ConstraintPredicate.ToneCompleteness(it)),
            modality = if (it.required) ConstraintModality.Require else ConstraintModality.Prefer(),
            ruleId = it.ruleId,
            explanation = it.explanation,
        )
    })
    addAll(doublings.map {
        Constraint(
            expr = ConstraintExpr.Atom(ConstraintPredicate.ToneDoubled(it)),
            modality = if (it.required) ConstraintModality.Require else ConstraintModality.Prefer(),
            ruleId = it.ruleId,
            explanation = it.explanation,
        )
    })
    addAll(avoidDoublings.map {
        Constraint(
            expr = ConstraintExpr.Atom(ConstraintPredicate.ToneNotDoubled(it)),
            modality = if (it.required) ConstraintModality.Require else ConstraintModality.Prefer(),
            ruleId = it.ruleId,
            explanation = it.explanation,
        )
    })
    addAll(avoidScaleDegreeDoublings.map {
        Constraint(
            expr = ConstraintExpr.Atom(ConstraintPredicate.ScaleDegreeNotDoubled(it)),
            modality = if (it.required) ConstraintModality.Require else ConstraintModality.Prefer(),
            ruleId = it.ruleId,
            explanation = it.explanation,
        )
    })
    addAll(spacings.filter { it.preference != SpacingPreference.ANY }.map {
        Constraint(ConstraintExpr.Atom(ConstraintPredicate.Spacing(it)), ConstraintModality.Prefer())
    })
    addAll(allDifferent.map { Constraint(ConstraintExpr.Atom(ConstraintPredicate.DistinctIdentities(it))) })
    addAll(adjacentCommonTones.map { Constraint(ConstraintExpr.Atom(ConstraintPredicate.CommonToneWithPrevious(it))) })
    chordToneNeighbors.forEach { requirement ->
        val atom = ConstraintExpr.Atom(ConstraintPredicate.NeighborTone(requirement))
        add(Constraint(atom, if (requirement.required) ConstraintModality.Require else ConstraintModality.Prefer(), requirement.ruleId, requirement.explanation))
        add(Constraint(atom, ConstraintModality.Annotate, requirement.ruleId, requirement.explanation))
    }
    addAll(targetFeatureBonuses.map {
        Constraint(
            ConstraintExpr.Atom(ConstraintPredicate.TargetMatches(it)),
            ConstraintModality.Reward(it.bonus),
            it.ruleId,
            ConstraintExplanation(it.message),
        )
    })
}
