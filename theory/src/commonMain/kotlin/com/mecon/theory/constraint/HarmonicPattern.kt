package com.mecon.theory.constraint

import com.mecon.theory.RuleId
import com.mecon.theory.SlotWindow
import com.mecon.theory.TonalContextId
import kotlin.jvm.JvmInline

@JvmInline
value class HarmonicPatternId(val value: String) {
    init { require(value.isNotBlank()) }
    override fun toString(): String = value
}

@JvmInline
value class PatternRequirementId(val value: String) {
    init { require(value.isNotBlank()) }
    override fun toString(): String = value
}

sealed interface PatternContextBinding {
    data object Any : PatternContextBinding
    data class Primary(val contextId: TonalContextId) : PatternContextBinding
    data class Compatible(val contextId: TonalContextId) : PatternContextBinding
}

data class PatternStep(
    val selector: TargetSelector,
    val contextBinding: PatternContextBinding = PatternContextBinding.Any,
) {
    fun matches(target: ChordTarget): Boolean =
        selector.matches(target) &&
            when (val binding = contextBinding) {
                PatternContextBinding.Any -> true
                is PatternContextBinding.Primary ->
                    target.primaryTonalContextId() == binding.contextId
                is PatternContextBinding.Compatible ->
                    binding.contextId in target.tonalContextIds()
            }

    fun runtimeSelector(): TargetSelector =
        when (val binding = contextBinding) {
            PatternContextBinding.Any -> selector
            is PatternContextBinding.Primary ->
                selector.copy(primaryContextIds = selector.primaryContextIds + binding.contextId)
            is PatternContextBinding.Compatible ->
                selector.copy(compatibleContextIds = selector.compatibleContextIds + binding.contextId)
        }
}

data class HarmonicPattern(
    val id: HarmonicPatternId,
    val steps: List<PatternStep>,
    val directionalStrength: Double = 1.0,
) {
    init {
        require(steps.isNotEmpty())
        require(directionalStrength >= 1.0)
    }

    fun constraintsAt(
        startSlot: Int,
        ruleNamespace: String = "harmonic-pattern",
    ): List<Constraint> =
        steps.mapIndexed { offset, step ->
            val slot = startSlot + offset
            val ruleId = RuleId("$ruleNamespace.${id.value}.$offset")
            Constraint(
                expr = ConstraintExpr.Atom(
                    ConstraintPredicate.TargetMatches(
                        TargetFeatureBonusRequirement(
                            window = SlotWindow(slot, slot),
                            selector = step.runtimeSelector(),
                            ruleId = ruleId,
                            message = "进行 ${id.value} 的第 ${offset + 1} 步。",
                            bonus = 0.0,
                        )
                    )
                ),
                modality = ConstraintModality.Require,
                ruleId = ruleId,
                explanation = ConstraintExplanation(
                    satisfied = "进行 ${id.value} 的第 ${offset + 1} 步匹配。",
                    violated = "第 ${slot + 1} 槽不满足进行 ${id.value} 的功能或调性解释。",
                ),
            )
        }

    fun matcher(): HarmonicPatternMatcher = HarmonicPatternMatcher(this)
}

sealed interface PatternPlacement {
    data class Fixed(val startSlot: Int) : PatternPlacement
    data class Within(val window: SlotWindow) : PatternPlacement
    data class EndingAt(val slot: Int) : PatternPlacement
}

enum class OccurrenceRequirement {
    EXACTLY_ONCE,
    AT_LEAST_ONCE,
    OPTIONAL,
}

data class PatternRequirement(
    val id: PatternRequirementId,
    val patternId: HarmonicPatternId,
    val placement: PatternPlacement,
    val occurrence: OccurrenceRequirement = OccurrenceRequirement.AT_LEAST_ONCE,
    val after: Set<PatternRequirementId> = emptySet(),
)

enum class PatternCompletion {
    NOT_STARTED,
    PARTIAL,
    COMPLETE,
    VIOLATED,
}

data class PatternMatchState(
    val completion: PatternCompletion,
    val matchedSteps: Int,
    val minimumRemainingSlots: Int,
)

/**
 * Deterministic prefix automaton shared by enumeration, free-practice progress and continuation.
 * On mismatch it restarts from step 1 when the current target can begin a new occurrence.
 */
class HarmonicPatternMatcher internal constructor(
    private val pattern: HarmonicPattern,
) {
    fun stateFor(targets: List<ChordTarget>): PatternMatchState {
        var matched = 0
        var completed = false
        targets.forEach { target ->
            matched = when {
                pattern.steps[matched].matches(target) -> matched + 1
                pattern.steps.first().matches(target) -> 1
                else -> 0
            }
            if (matched == pattern.steps.size) {
                completed = true
                matched = 0
            }
        }
        return when {
            completed -> PatternMatchState(PatternCompletion.COMPLETE, pattern.steps.size, 0)
            matched > 0 -> PatternMatchState(
                PatternCompletion.PARTIAL,
                matched,
                pattern.steps.size - matched,
            )
            else -> PatternMatchState(
                PatternCompletion.NOT_STARTED,
                0,
                pattern.steps.size,
            )
        }
    }
}

object HarmonicPatterns {
    val AUTHENTIC_CADENCE = HarmonicPattern(
        HarmonicPatternId("authentic-cadence"),
        listOf(
            PatternStep(TargetSelector(degrees = setOf(5))),
            PatternStep(TargetSelector(degrees = setOf(1), inversions = setOf(0))),
        ),
        directionalStrength = 2.0,
    )

    val DECEPTIVE_CADENCE = HarmonicPattern(
        HarmonicPatternId("deceptive-cadence"),
        listOf(
            PatternStep(TargetSelector(degrees = setOf(5))),
            PatternStep(TargetSelector(degrees = setOf(6))),
        ),
        directionalStrength = 2.0,
    )

    val DOMINANT_SUSTAINED_WINDOW = HarmonicPattern(
        HarmonicPatternId("dominant-sustained-window"),
        listOf(
            PatternStep(TargetSelector(degrees = setOf(5))),
            PatternStep(TargetSelector(degrees = setOf(2, 4, 6))),
            PatternStep(TargetSelector(degrees = setOf(5))),
        ),
        directionalStrength = 2.5,
    )
}
