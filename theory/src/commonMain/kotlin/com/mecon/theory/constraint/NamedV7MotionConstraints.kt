package com.mecon.theory.constraint

import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.RuleId
import com.mecon.theory.SlotWindow
import com.mecon.theory.textbook.DominantSeventhRules

/**
 * Declarative motion constraints for seventh-chord resolution.
 *
 * The old checker remains available for direct compatibility calls, but the
 * ConstraintProgram path owns these findings so demonstration requests do not
 * observe two verdicts for the same rule.
 */
internal fun namedV7MotionConstraints(program: ConstraintProgram): List<Constraint> = buildList {
    (0 until program.length - 1).forEach { slot ->
        val seventhTargets = program.slotDomains[slot].targets
            .filter { it.arity == ChordArity.SEVENTH && it.pitchClassFor(ChordTone.SEVENTH) != null }
            .distinctBy { it.identityKey() }

        seventhTargets.forEach { target ->
            val seventhDegree = program.key.scale.pitchClasses
                .indexOf(target.pitchClassFor(ChordTone.SEVENTH))
                .takeIf { it >= 0 }
                ?.plus(1)
                ?: return@forEach
            val selector = exactSelector(target)
            val resolvesDown = neighborExpr(
                program = program,
                slot = slot,
                sourceSelector = selector,
                sourceTone = ChordTone.SEVENTH,
                voiceFilter = ChordToneVoiceFilter.ANY,
                candidateScaleDegrees = setOf(shiftDegree(seventhDegree, -1)),
                allowedDiatonicStepDeltas = setOf(-1),
                candidateAlterations = (-2..2).toSet(),
                required = true,
            )
            val ascends = neighborExpr(
                program = program,
                slot = slot,
                sourceSelector = selector,
                sourceTone = ChordTone.SEVENTH,
                voiceFilter = ChordToneVoiceFilter.ANY,
                candidateScaleDegrees = (1..7).toSet(),
                allowedDiatonicStepDeltas = setOf(1),
                candidateAlterations = (-2..2).toSet(),
            )
            add(
                Constraint(
                    expr = ConstraintExpr.Or(
                        listOf(
                            ConstraintBranch(resolvesDown),
                            ConstraintBranch(ascends),
                        )
                    ),
                    modality = ConstraintModality.Require,
                    ruleId = DominantSeventhRules.SEVENTH_RESOLVES_DOWN,
                    explanation = ConstraintExplanation(
                        satisfied = "The seventh resolves down by step or uses the ascending special case.",
                        violated = "The seventh should resolve down by step.",
                    ),
                )
            )
            add(
                Constraint(
                    expr = ConstraintExpr.Not(
                        ConstraintExpr.And(
                            listOf(
                                ConstraintExpr.Not(resolvesDown),
                                ascends,
                            )
                        )
                    ),
                    modality = ConstraintModality.Prefer(),
                    ruleId = DominantSeventhRules.SEVENTH_ASCENDS,
                    explanation = ConstraintExplanation(
                        satisfied = "The seventh does not ascend by step.",
                        violated = "Ascending resolution of the seventh is normally a special case.",
                    ),
                )
            )
        }


        val tonicSelector = TargetSelector(degrees = setOf(TONIC_DEGREE))
        val invertedV7Selector = TargetSelector(
            degrees = setOf(DOMINANT_DEGREE),
            qualities = setOf(ChordQuality.DOMINANT7),
            inversions = setOf(1, 2, 3),
            arities = setOf(ChordArity.SEVENTH),
        )
        val tendencyTones = ConstraintExpr.And(
            listOf(
                neighborExpr(
                    program = program,
                    slot = slot,
                    sourceSelector = invertedV7Selector,
                    sourceTone = ChordTone.THIRD,
                    voiceFilter = ChordToneVoiceFilter.ANY,
                    candidateScaleDegrees = setOf(TONIC_DEGREE),
                    allowedDiatonicStepDeltas = setOf(1),
                    candidateAlterations = (-2..2).toSet(),
                    neighborSelector = tonicSelector,
                ),
                neighborExpr(
                    program = program,
                    slot = slot,
                    sourceSelector = invertedV7Selector,
                    sourceTone = ChordTone.SEVENTH,
                    voiceFilter = ChordToneVoiceFilter.ANY,
                    candidateScaleDegrees = setOf(3),
                    allowedDiatonicStepDeltas = setOf(-1),
                    candidateAlterations = (-2..2).toSet(),
                    neighborSelector = tonicSelector,
                ),
            )
        )
        add(
            Constraint(
                expr = tendencyTones,
                modality = ConstraintModality.Annotate,
                ruleId = DominantSeventhRules.INVERSION_TENDENCY_TONES,
                explanation = ConstraintExplanation(
                    satisfied = "The inverted dominant seventh resolves its tendency tones correctly.",
                ),
            )
        )
        val secondInversionPassing = ConstraintExpr.And(
            listOf(
                tendencyTones,
                targetExpr(
                    slot,
                    TargetSelector(
                        degrees = setOf(DOMINANT_DEGREE),
                        qualities = setOf(ChordQuality.DOMINANT7),
                        inversions = setOf(2),
                        arities = setOf(ChordArity.SEVENTH),
                    )
                ),
                targetExpr(slot + 1, tonicSelector),
                voiceSteps(ChordToneVoiceFilter.BASS, listOf(slot, slot + 1), setOf(-1, 1)),
                voiceSteps(ChordToneVoiceFilter.SOPRANO, listOf(slot, slot + 1), (-1..1).toSet()),
                voiceSteps(ChordToneVoiceFilter.ALTO, listOf(slot, slot + 1), (-1..1).toSet()),
                voiceSteps(ChordToneVoiceFilter.TENOR, listOf(slot, slot + 1), (-1..1).toSet()),
            )
        )
        add(
            Constraint(
                expr = secondInversionPassing,
                modality = ConstraintModality.Annotate,
                ruleId = DominantSeventhRules.SECOND_INVERSION_PASSING,
                explanation = ConstraintExplanation(
                    satisfied = "The second-inversion dominant seventh resolves as a passing sonority.",
                ),
            )
        )
        add(
            Constraint(
                expr = ConstraintExpr.And(
                    listOf(
                        targetExpr(
                            slot,
                            TargetSelector(
                                degrees = setOf(DOMINANT_DEGREE),
                                qualities = setOf(ChordQuality.DOMINANT7),
                                inversions = setOf(3),
                                arities = setOf(ChordArity.SEVENTH),
                            )
                        ),
                        targetExpr(
                            slot + 1,
                            TargetSelector(
                                degrees = setOf(TONIC_DEGREE),
                                inversions = setOf(1),
                            )
                        ),
                    )
                ),
                modality = ConstraintModality.Annotate,
                ruleId = DominantSeventhRules.THIRD_INVERSION_TO_I6,
                explanation = ConstraintExplanation(
                    satisfied = "Third-inversion V7 resolves to first-inversion tonic.",
                ),
            )
        )

        val hasDominantSeventh = program.slotDomains[slot].targets.any {
            it.arity == ChordArity.SEVENTH &&
                it.degree == DOMINANT_DEGREE &&
                it.quality == ChordQuality.DOMINANT7
        }
        if (!hasDominantSeventh) return@forEach

        val outerLeadingTone = neighborExpr(
            program = program,
            slot = slot,
            sourceSelector = TargetSelector(
                degrees = setOf(DOMINANT_DEGREE),
                qualities = setOf(ChordQuality.DOMINANT7),
                arities = setOf(ChordArity.SEVENTH),
            ),
            sourceTone = ChordTone.THIRD,
            voiceFilter = ChordToneVoiceFilter.OUTER,
            candidateScaleDegrees = setOf(TONIC_DEGREE),
            allowedDiatonicStepDeltas = setOf(1),
            candidateAlterations = (-2..2).toSet(),
            required = true,
        )
        add(
            Constraint(
                expr = outerLeadingTone,
                modality = ConstraintModality.Require,
                ruleId = DominantSeventhRules.OUTER_LEADING_TONE_RESOLUTION,
                explanation = ConstraintExplanation(
                    satisfied = "The outer-voice leading tone resolves up to tonic.",
                    violated = "The outer-voice leading tone must resolve up by step to tonic.",
                ),
            )
        )
        add(
            Constraint(
                expr = outerLeadingTone,
                modality = ConstraintModality.Annotate,
                ruleId = DominantSeventhRules.OUTER_LEADING_TONE_RESOLUTION,
                explanation = ConstraintExplanation(
                    satisfied = "The outer-voice leading tone resolves up to tonic.",
                ),
            )
        )
    }
}

private fun neighborExpr(
    program: ConstraintProgram,
    slot: Int,
    sourceSelector: TargetSelector,
    sourceTone: ChordTone,
    voiceFilter: ChordToneVoiceFilter,
    candidateScaleDegrees: Set<Int>,
    allowedDiatonicStepDeltas: Set<Int>,
    candidateAlterations: Set<Int>,
    required: Boolean = false,
    neighborSelector: TargetSelector = TargetSelector(),
): ConstraintExpr = ConstraintExpr.Atom(
    ConstraintPredicate.NeighborTone(
        ChordToneNeighborRequirement(
            window = SlotWindow(slot, slot + 1),
            sourceSlot = slot,
            sourceTone = sourceTone,
            direction = ChordToneNeighborDirection.NEXT,
            candidateScaleDegrees = candidateScaleDegrees,
            allowedDiatonicStepDeltas = allowedDiatonicStepDeltas,
            candidateAlterations = candidateAlterations,
            voiceFilter = voiceFilter,
            sourceSelector = sourceSelector,
            neighborSelector = neighborSelector,
            required = required,
        )
    )
)

private fun targetExpr(slot: Int, selector: TargetSelector): ConstraintExpr = ConstraintExpr.Atom(
    ConstraintPredicate.TargetMatches(
        TargetFeatureBonusRequirement(
            window = SlotWindow(slot, slot),
            selector = selector,
            ruleId = RuleId("solver.constraint.target-at"),
            message = "Target matches.",
            bonus = 0.0,
        )
    )
)

private fun voiceSteps(voice: ChordToneVoiceFilter, slots: List<Int>, allowedDeltas: Set<Int>): ConstraintExpr =
    ConstraintExpr.Atom(ConstraintPredicate.VoiceDiatonicSteps(voice, slots, listOf(allowedDeltas)))

private fun exactSelector(target: ChordTarget): TargetSelector =
    TargetSelector(
        degrees = setOf(target.degree),
        qualities = setOf(target.quality),
        inversions = setOf(target.inversion),
        arities = setOf(target.arity),
    )

private fun shiftDegree(degree: Int, offset: Int): Int = (degree - 1 + offset).mod(7) + 1

private const val TONIC_DEGREE = 1
private const val DOMINANT_DEGREE = 5
