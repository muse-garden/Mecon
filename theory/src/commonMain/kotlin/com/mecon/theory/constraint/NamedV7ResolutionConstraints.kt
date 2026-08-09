package com.mecon.theory.constraint

import com.mecon.api.primitive.EventId
import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.FixedVoice
import com.mecon.theory.FixedVoiceScoreRuleContext
import com.mecon.theory.FixedVoiceWritingFrame
import com.mecon.theory.FixedVoiceWritingState
import com.mecon.theory.RuleFinding
import com.mecon.theory.RuleId
import com.mecon.theory.SlotWindow
import com.mecon.theory.toFixedVoiceScore
import com.mecon.theory.textbook.DominantSeventhRules

/** M7：原位 V7-I 的三种排布形态改为命名约束，平行五度等算法性违规仍由规则模块处理。 */
internal fun namedV7ResolutionConstraints(program: ConstraintProgram): List<Constraint> = buildList {
    (0 until program.length - 1).forEach { slot ->
        val hasRootV7 = program.slotDomains[slot].targets.any {
            it.degree == 5 && it.quality == ChordQuality.DOMINANT7 && it.inversion == 0 &&
                it.arity == ChordArity.SEVENTH
        }
        val hasTonic = program.slotDomains[slot + 1].targets.any { it.degree == 1 && it.inversion == 0 }
        if (!hasRootV7 || !hasTonic) return@forEach

        val context = listOf(
            targetExpr(slot, TargetSelector(degrees = setOf(5), qualities = setOf(ChordQuality.DOMINANT7), inversions = setOf(0), arities = setOf(ChordArity.SEVENTH))),
            targetExpr(slot + 1, TargetSelector(degrees = setOf(1), inversions = setOf(0))),
        )
        val completeToOmitted = ConstraintExpr.And(
            context + listOf(
                multiplicity(slot + 1, ChordTone.ROOT, setOf(3)),
                multiplicity(slot + 1, ChordTone.THIRD, setOf(1)),
                multiplicity(slot + 1, ChordTone.FIFTH, setOf(0)),
            )
        )
        add(
            Constraint(
                completeToOmitted,
                ConstraintModality.Annotate,
                DominantSeventhRules.ROOT_V7_TO_I_OMITTED_FIFTH,
                ConstraintExplanation("完整 V7-I 常解决到省略五音、三根一三音的 I。"),
            )
        )

        val incompleteToComplete = ConstraintExpr.And(
            context + listOf(
                multiplicity(slot, ChordTone.FIFTH, setOf(0)),
                multiplicity(slot, ChordTone.ROOT, setOf(2, 3, 4)),
                multiplicity(slot + 1, ChordTone.FIFTH, setOf(1, 2, 3, 4)),
                multiplicity(slot + 1, ChordTone.ROOT, setOf(1, 2, 3, 4)),
                multiplicity(slot + 1, ChordTone.THIRD, setOf(1, 2, 3, 4)),
            )
        )
        add(
            Constraint(
                incompleteToComplete,
                ConstraintModality.Annotate,
                DominantSeventhRules.INCOMPLETE_V7_TO_COMPLETE_I,
                ConstraintExplanation("不完全 V7 省略五音并重复根音，可解决到完整 I。"),
            )
        )

        val innerLeadingToneToComplete = ConstraintExpr.And(
            context + listOf(
                ConstraintExpr.Atom(
                    ConstraintPredicate.ToneInVoiceFilter(slot, ChordTone.THIRD, ChordToneVoiceFilter.INNER)
                ),
                multiplicity(slot + 1, ChordTone.FIFTH, setOf(1, 2, 3, 4)),
                multiplicity(slot + 1, ChordTone.ROOT, setOf(1, 2, 3, 4)),
                multiplicity(slot + 1, ChordTone.THIRD, setOf(1, 2, 3, 4)),
            )
        )
        add(
            Constraint(
                innerLeadingToneToComplete,
                ConstraintModality.Annotate,
                DominantSeventhRules.INNER_LEADING_TONE_COMPLETE_I,
                ConstraintExplanation("完全 V7 若将导音放在内声部，可解决到完整 I。"),
            )
        )
    }
}

private fun multiplicity(slot: Int, tone: ChordTone, counts: Set<Int>): ConstraintExpr =
    ConstraintExpr.Atom(ConstraintPredicate.ToneMultiplicity(slot, tone, counts))

private fun targetExpr(slot: Int, selector: TargetSelector): ConstraintExpr = ConstraintExpr.Atom(
    ConstraintPredicate.TargetMatches(
        TargetFeatureBonusRequirement(
            window = SlotWindow(slot, slot),
            selector = selector,
            ruleId = RuleId("solver.constraint.target-at"),
            message = "目标匹配。",
            bonus = 0.0,
        )
    )
)

internal fun evaluateNamedV7ResolutionConstraints(
    frames: List<FixedVoiceWritingFrame<out ChordTarget>>,
    voices: List<FixedVoice>,
): List<RuleFinding<EventId>> {
    if (frames.isEmpty()) return emptyList()
    val concreteFrames = frames.map { frame ->
        FixedVoiceWritingFrame<ChordTarget>(frame.slotIndex, frame.target, frame.pitchesByVoiceId, frame.duration)
    }
    val base = ConstraintProgram(
        key = concreteFrames.first().target.key,
        slotDomains = concreteFrames.map { SlotDomain(listOf(it.target)) },
    )
    val program = base.copy(constraints = namedV7ResolutionConstraints(base))
    val state = FixedVoiceWritingState(frames = concreteFrames)
    return ConstraintAlgebraRuleProvider(program, voices).checkScore(
        FixedVoiceScoreRuleContext(state.toFixedVoiceScore(voices), state),
        emptyList(),
    )
}
