package com.mecon.theory.constraint

import com.mecon.api.primitive.EventId
import com.mecon.theory.ChordArity
import com.mecon.theory.FixedVoice
import com.mecon.theory.FixedVoiceRole
import com.mecon.theory.FixedVoiceScoreRuleContext
import com.mecon.theory.FixedVoiceWritingFrame
import com.mecon.theory.FixedVoiceWritingState
import com.mecon.theory.RuleFinding
import com.mecon.theory.RuleId
import com.mecon.theory.SlotWindow
import com.mecon.theory.toFixedVoiceScore
import com.mecon.theory.textbook.SecondInversionTriadRules

/** M7：四六和弦四种语境的命名 Or；识别、unsupported 剪枝与上方声部平稳性共享同一表达式。 */
internal fun namedSixFourConstraints(
    program: ConstraintProgram,
    includeUnsupported: Boolean = true,
): List<Constraint> = buildList {
    program.slotDomains.indices.forEach { slot ->
        if (program.slotDomains[slot].targets.none { it.arity == ChordArity.TRIAD && it.inversion == 2 }) return@forEach

        val currentSecondInversion = targetExpr(slot, TargetSelector(inversions = setOf(2), arities = setOf(ChordArity.TRIAD)))
        val smoothnessConstraints = mutableListOf<Constraint>()
        val branches = buildList {
            if (slot < program.length - 1) {
                add(
                    ConstraintBranch(
                        expr = ConstraintExpr.And(
                            listOf(
                                currentSecondInversion,
                                targetExpr(slot, TargetSelector(degrees = setOf(1), inversions = setOf(2), arities = setOf(ChordArity.TRIAD))),
                                targetExpr(slot + 1, TargetSelector(degrees = setOf(5), inversions = setOf(0), arities = setOf(ChordArity.TRIAD))),
                            )
                        ),
                        ruleId = SecondInversionTriadRules.CADENTIAL_SIX_FOUR,
                        explanation = ConstraintExplanation(
                            "终止四六和弦：在 V 到来之前插入 I(46)，延迟属和弦。当前节拍模型尚未记录强弱位，因此只检查 I(46)-V 语境。"
                        ),
                    )
                )
            }
            if (slot > 0 && slot < program.length - 1) {
                val threeSlots = listOf(slot - 1, slot, slot + 1)
                val sameChord = ConstraintExpr.And(
                    listOf(
                        currentSecondInversion,
                        ConstraintExpr.Atom(ConstraintPredicate.SameSonority(threeSlots)),
                        ConstraintExpr.Atom(
                            ConstraintPredicate.VoicePitchClassCardinality(
                                ChordToneVoiceFilter.BASS,
                                threeSlots,
                                setOf(2, 3),
                            )
                        ),
                    )
                )
                add(
                    ConstraintBranch(
                        sameChord,
                        SecondInversionTriadRules.SAME_CHORD_INVERSION_INSERTION,
                        ConstraintExplanation("相同和弦的不同转位之间可以插入同一和弦的四六形态。"),
                    )
                )

                val passing = ConstraintExpr.And(
                    listOf(
                        currentSecondInversion,
                        ConstraintExpr.Or(
                            listOf(
                                ConstraintBranch(voiceSteps(ChordToneVoiceFilter.BASS, threeSlots, listOf(setOf(1), setOf(1)))),
                                ConstraintBranch(voiceSteps(ChordToneVoiceFilter.BASS, threeSlots, listOf(setOf(-1), setOf(-1)))),
                            )
                        ),
                    )
                )
                add(
                    ConstraintBranch(
                        passing,
                        SecondInversionTriadRules.PASSING_SIX_FOUR,
                        ConstraintExplanation("经过四六和弦：低音级进通过中间的四六和弦，上方声部应尽量平稳连接。"),
                    )
                )

                val pedal = ConstraintExpr.And(
                    listOf(
                        currentSecondInversion,
                        targetExpr(slot - 1, TargetSelector(inversions = setOf(0), arities = setOf(ChordArity.TRIAD))),
                        targetExpr(slot + 1, TargetSelector(inversions = setOf(0), arities = setOf(ChordArity.TRIAD))),
                        ConstraintExpr.Atom(ConstraintPredicate.SameSonority(listOf(slot - 1, slot + 1))),
                        ConstraintExpr.Atom(
                            ConstraintPredicate.VoicePitchClassCardinality(
                                ChordToneVoiceFilter.BASS,
                                threeSlots,
                                setOf(1),
                            )
                        ),
                    )
                )
                add(
                    ConstraintBranch(
                        pedal,
                        SecondInversionTriadRules.PEDAL_SIX_FOUR,
                        ConstraintExplanation("持续音四六和弦：在持续低音上装饰原位三和弦，三音和五音上行级进后再回到原和弦。"),
                    )
                )

                val passingOrPedal = ConstraintExpr.Or(listOf(ConstraintBranch(passing), ConstraintBranch(pedal)))
                smoothnessConstraints += smoothnessConstraintsFor(passingOrPedal, slot)
            }
        }
        if (branches.isEmpty()) {
            if (includeUnsupported) {
                add(
                    Constraint(
                        ConstraintExpr.Not(currentSecondInversion),
                        ConstraintModality.Require,
                        SecondInversionTriadRules.UNSUPPORTED_SECOND_INVERSION,
                        ConstraintExplanation(
                            satisfied = "第二转位位于受支持的装饰语境。",
                            violated = "第二转位不能脱离终止、经过、持续音或同和弦转位装饰语境单独使用。",
                        ),
                    )
                )
            }
            return@forEach
        }
        val recognized = ConstraintExpr.Or(branches)
        add(Constraint(recognized, ConstraintModality.Annotate))
        addAll(smoothnessConstraints)
        if (includeUnsupported) {
            add(
                Constraint(
                    ConstraintExpr.Or(
                        listOf(
                            ConstraintBranch(ConstraintExpr.Not(currentSecondInversion)),
                            ConstraintBranch(recognized),
                        )
                    ),
                    ConstraintModality.Require,
                    SecondInversionTriadRules.UNSUPPORTED_SECOND_INVERSION,
                    ConstraintExplanation(
                        satisfied = "第二转位位于受支持的装饰语境。",
                        violated = "第二转位不能像第一转位那样自由使用；请放入终止、经过、持续音或同和弦转位装饰语境。",
                    ),
                )
            )
        }
    }
}

private fun smoothnessConstraintsFor(
    passingOrPedal: ConstraintExpr,
    slot: Int,
): List<Constraint> = buildList {
    listOf(
        ChordToneVoiceFilter.SOPRANO,
        ChordToneVoiceFilter.ALTO,
        ChordToneVoiceFilter.TENOR,
    ).forEach { voice ->
        listOf(listOf(slot - 1, slot), listOf(slot, slot + 1)).forEach { pair ->
            val smooth = voiceSteps(voice, pair, listOf((-1..1).toSet()))
            add(Constraint(
                expr = ConstraintExpr.Or(
                    listOf(
                        ConstraintBranch(ConstraintExpr.Not(passingOrPedal)),
                        ConstraintBranch(smooth),
                    )
                ),
                modality = ConstraintModality.Prefer(),
                ruleId = SecondInversionTriadRules.UPPER_VOICE_LEAP,
                explanation = ConstraintExplanation(
                    satisfied = "经过或持续音四六和弦上方声部保持平稳。",
                    violated = "经过或持续音四六和弦上方声部应尽量级进或保持，避免跳进。",
                ),
            ))
        }
    }
}

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

private fun voiceSteps(
    voice: ChordToneVoiceFilter,
    slots: List<Int>,
    deltas: List<Set<Int>>,
): ConstraintExpr = ConstraintExpr.Atom(ConstraintPredicate.VoiceDiatonicSteps(voice, slots, deltas))

internal fun evaluateNamedSixFourConstraints(
    frames: List<FixedVoiceWritingFrame<out ChordTarget>>,
    voices: List<FixedVoice>,
    includeUnsupported: Boolean = true,
): List<RuleFinding<EventId>> {
    if (frames.isEmpty()) return emptyList()
    val concreteFrames = frames.map { frame ->
        FixedVoiceWritingFrame<ChordTarget>(frame.slotIndex, frame.target, frame.pitchesByVoiceId, frame.duration)
    }
    val base = ConstraintProgram(
        key = concreteFrames.first().target.key,
        slotDomains = concreteFrames.map { SlotDomain(listOf(it.target)) },
    )
    val program = base.copy(constraints = namedSixFourConstraints(base, includeUnsupported))
    val state = FixedVoiceWritingState(frames = concreteFrames)
    return ConstraintAlgebraRuleProvider(program, voices).checkScore(
        FixedVoiceScoreRuleContext(state.toFixedVoiceScore(voices), state),
        emptyList(),
    )
}
