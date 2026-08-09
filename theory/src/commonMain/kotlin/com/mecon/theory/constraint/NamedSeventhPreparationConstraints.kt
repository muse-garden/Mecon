package com.mecon.theory.constraint

import com.mecon.theory.ChordArity
import com.mecon.theory.FixedVoice
import com.mecon.theory.FixedVoiceScoreRuleContext
import com.mecon.theory.FixedVoiceWritingFrame
import com.mecon.theory.FixedVoiceWritingState
import com.mecon.theory.SlotWindow
import com.mecon.theory.toFixedVoiceScore
import com.mecon.theory.textbook.DominantSeventhRules

/**
 * M7：把七音预备五型从 DominantSeventhRules 的 when 判定迁成命名约束。
 * 四个可接受分支由 Or 命名；“上方跳进”作为同一原子词汇上的软禁止单独发射。
 */
internal fun namedSeventhPreparationConstraints(program: ConstraintProgram): List<Constraint> =
    buildList {
        (1 until program.length - 1).forEach { slot ->
            val targets = program.slotDomains[slot].targets
                .filter { it.arity == ChordArity.SEVENTH && it.pitchClassFor(ChordTone.SEVENTH) != null }
                .distinctBy { it.identityKey() }
            val acceptedBranches = mutableListOf<ConstraintBranch>()
            targets.forEach { target ->
                val seventhDegree = program.key.scale.pitchClasses.indexOf(target.pitchClassFor(ChordTone.SEVENTH))
                    .takeIf { it >= 0 }
                    ?.plus(1)
                    ?: return@forEach
                val selector = TargetSelector(
                    degrees = setOf(target.degree),
                    qualities = setOf(target.quality),
                    inversions = setOf(target.inversion),
                    arities = setOf(target.arity),
                )
                val resolution = neighborExpr(
                    slot = slot,
                    selector = selector,
                    direction = ChordToneNeighborDirection.NEXT,
                    candidateDegrees = setOf(shiftDegree(seventhDegree, -1)),
                    deltas = setOf(-1),
                )
                fun branch(
                    offsetRange: IntRange,
                    ruleId: com.mecon.theory.RuleId,
                    message: String,
                    scoreDelta: Double,
                ) {
                    val preparation = neighborExpr(
                        slot = slot,
                        selector = selector,
                        direction = ChordToneNeighborDirection.PREVIOUS,
                        candidateDegrees = offsetRange.map { shiftDegree(seventhDegree, it) }.toSet(),
                        deltas = offsetRange.toSet(),
                    )
                    acceptedBranches += ConstraintBranch(
                        expr = ConstraintExpr.And(listOf(preparation, resolution)),
                        ruleId = ruleId,
                        explanation = ConstraintExplanation(message),
                        scoreDelta = scoreDelta,
                    )
                }
                branch(0..0, DominantSeventhRules.PREPARATION_SUSPENSION, "延留音式七音预备：前一音与七音保持同音高。", -4.0)
                branch(1..1, DominantSeventhRules.PREPARATION_PASSING, "经过音式七音预备：前一音在七音上方相邻音级。", -2.0)
                branch(-1..-1, DominantSeventhRules.PREPARATION_NEIGHBOR, "邻音式七音预备：前一音在七音下方相邻音级。", 0.0)
                branch(-14..-2, DominantSeventhRules.PREPARATION_APPOGGIATURA, "倚音式七音预备：前一音在七音下方非相邻音级，使用较少。", 4.0)

                val aboveLeap = neighborExpr(
                    slot = slot,
                    selector = selector,
                    direction = ChordToneNeighborDirection.PREVIOUS,
                    candidateDegrees = (2..14).map { shiftDegree(seventhDegree, it) }.toSet(),
                    deltas = (2..14).toSet(),
                )
                add(
                    Constraint(
                        expr = ConstraintExpr.Not(ConstraintExpr.And(listOf(aboveLeap, resolution))),
                        modality = ConstraintModality.Prefer(),
                        ruleId = DominantSeventhRules.PREPARATION_ABOVE_LEAP,
                        explanation = ConstraintExplanation(
                            satisfied = "七音没有采用上方非相邻音级预备。",
                            violated = "七音前一音通常不会来自上方非相邻音级。",
                        ),
                    )
                )
            }
            if (acceptedBranches.isNotEmpty()) {
                add(Constraint(ConstraintExpr.Or(acceptedBranches), ConstraintModality.Annotate))
            }
        }
    }

private fun neighborExpr(
    slot: Int,
    selector: TargetSelector,
    direction: ChordToneNeighborDirection,
    candidateDegrees: Set<Int>,
    deltas: Set<Int>,
): ConstraintExpr = ConstraintExpr.Atom(
    ConstraintPredicate.NeighborTone(
        ChordToneNeighborRequirement(
            window = SlotWindow(slot - 1, slot + 1),
            sourceSlot = slot,
            sourceTone = ChordTone.SEVENTH,
            direction = direction,
            candidateScaleDegrees = candidateDegrees,
            allowedDiatonicStepDeltas = deltas,
            sourceSelector = selector,
        )
    )
)

private fun shiftDegree(degree: Int, offset: Int): Int = (degree - 1 + offset).mod(7) + 1

/** 供规则金标准直接验证声明式本体；生产求解路径仍由 ConstraintProgramSolver 统一调用。 */
internal fun evaluateNamedSeventhPreparations(
    frames: List<FixedVoiceWritingFrame<out ChordTarget>>,
    voices: List<FixedVoice>,
): List<com.mecon.theory.RuleFinding<com.mecon.api.primitive.EventId>> {
    if (frames.isEmpty()) return emptyList()
    val concreteFrames = frames.map { frame ->
        FixedVoiceWritingFrame<ChordTarget>(
            slotIndex = frame.slotIndex,
            target = frame.target,
            pitchesByVoiceId = frame.pitchesByVoiceId,
            duration = frame.duration,
        )
    }
    val base = ConstraintProgram(
        key = concreteFrames.first().target.key,
        slotDomains = concreteFrames.map { SlotDomain(listOf(it.target)) },
    )
    val program = base.copy(constraints = namedSeventhPreparationConstraints(base))
    val state = FixedVoiceWritingState(frames = concreteFrames)
    val context = FixedVoiceScoreRuleContext(state.toFixedVoiceScore(voices), state)
    return ConstraintAlgebraRuleProvider(program, voices).checkScore(context, emptyList())
}
