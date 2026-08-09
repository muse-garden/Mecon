package com.mecon.theory.constraint

import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.Mode
import com.mecon.theory.RuleId
import com.mecon.theory.SlotWindow
import com.mecon.theory.textbook.DominantSeventhRules

/**
 * Target-identity and bounded multiplicity rules for the dominant-seventh chapter.
 *
 * Voice-by-voice prohibitions such as parallel fifths remain in the chapter module.
 */
internal fun namedSeventhContextConstraints(program: ConstraintProgram): List<Constraint> = buildList {
    program.slotDomains.indices.forEach { slot ->
        val hasSeventhTarget = program.slotDomains[slot].targets.any {
            it.arity == ChordArity.SEVENTH && it.pitchClassFor(ChordTone.SEVENTH) != null
        }
        if (!hasSeventhTarget) return@forEach

        val dominant = targetExpr(
            slot,
            TargetSelector(
                degrees = setOf(DOMINANT_DEGREE),
                arities = setOf(ChordArity.SEVENTH),
            )
        )
        val dominantQuality = targetExpr(
            slot,
            TargetSelector(
                degrees = setOf(DOMINANT_DEGREE),
                qualities = setOf(ChordQuality.DOMINANT7),
                arities = setOf(ChordArity.SEVENTH),
            )
        )
        add(
            Constraint(
                expr = ConstraintExpr.Or(
                    listOf(
                        ConstraintBranch(ConstraintExpr.Not(dominant)),
                        ConstraintBranch(dominantQuality),
                    )
                ),
                modality = ConstraintModality.Require,
                ruleId = DominantSeventhRules.DOMINANT_SEVENTH_QUALITY,
                explanation = ConstraintExplanation(
                    satisfied = "The dominant seventh has dominant-seventh quality.",
                    violated = "The dominant seventh should have dominant-seventh quality.",
                ),
            )
        )
        add(
            Constraint(
                expr = dominantQuality,
                modality = ConstraintModality.Annotate,
                ruleId = DominantSeventhRules.DOMINANT_SEVENTH_QUALITY,
                explanation = ConstraintExplanation(
                    satisfied = "The dominant seventh has dominant-seventh quality.",
                ),
            )
        )

        val omittedFifth = toneCompletenessExpr(
            slot = slot,
            selector = TargetSelector(arities = setOf(ChordArity.SEVENTH)),
            omittedTones = setOf(ChordTone.FIFTH),
        )
        add(
            Constraint(
                expr = omittedFifth,
                modality = ConstraintModality.Annotate,
                ruleId = DominantSeventhRules.OMIT_FIFTH_PREFERRED,
                explanation = ConstraintExplanation(
                    satisfied = "When a seventh chord omits a tone, omitting the fifth is the natural choice.",
                ),
            )
        )
        val omittedThird = toneCompletenessExpr(
            slot = slot,
            selector = TargetSelector(arities = setOf(ChordArity.SEVENTH)),
            omittedTones = setOf(ChordTone.THIRD),
        )
        add(
            Constraint(
                expr = omittedThird,
                modality = ConstraintModality.Annotate,
                ruleId = DominantSeventhRules.OMIT_THIRD_SECONDARY,
                explanation = ConstraintExplanation(
                    satisfied = "Omitting the third is secondary to omitting the fifth.",
                ),
            )
        )

        val leadingSeventh = targetExpr(
            slot,
            TargetSelector(
                degrees = setOf(LEADING_TONE_DEGREE),
                arities = setOf(ChordArity.SEVENTH),
            )
        )
        val expectedLeadingQuality = targetExpr(
            slot,
            TargetSelector(
                degrees = setOf(LEADING_TONE_DEGREE),
                qualities = setOf(
                    if (program.key.mode == Mode.IONIAN) {
                        ChordQuality.HALF_DIMINISHED7
                    } else {
                        ChordQuality.DIMINISHED7
                    }
                ),
                arities = setOf(ChordArity.SEVENTH),
            )
        )
        val leadingQualityRule = if (program.key.mode == Mode.IONIAN) {
            DominantSeventhRules.MAJOR_LEADING_HALF_DIMINISHED
        } else {
            DominantSeventhRules.MINOR_LEADING_DIMINISHED
        }
        add(
            Constraint(
                expr = ConstraintExpr.Or(
                    listOf(
                        ConstraintBranch(ConstraintExpr.Not(leadingSeventh)),
                        ConstraintBranch(expectedLeadingQuality),
                    )
                ),
                modality = ConstraintModality.Require,
                ruleId = leadingQualityRule,
                explanation = ConstraintExplanation(
                    satisfied = "The leading-tone seventh has the expected quality.",
                    violated = "The leading-tone seventh has the wrong quality.",
                ),
            )
        )
        add(
            Constraint(
                expr = expectedLeadingQuality,
                modality = ConstraintModality.Annotate,
                ruleId = leadingQualityRule,
                explanation = ConstraintExplanation(
                    satisfied = "The leading-tone seventh has the expected quality.",
                ),
            )
        )

        if (program.key.mode == Mode.AEOLIAN) {
            val raisedLeadingTone = program.key.scale.pitchClasses[LEADING_TONE_DEGREE - 1].transpose(1)
            val validMinorDominant = targetExpr(
                slot,
                TargetSelector(
                    degrees = setOf(DOMINANT_DEGREE),
                    arities = setOf(ChordArity.SEVENTH),
                    requiredPitchClasses = setOf(raisedLeadingTone),
                )
            )
            add(
                Constraint(
                    expr = ConstraintExpr.Or(
                        listOf(
                            ConstraintBranch(ConstraintExpr.Not(dominant)),
                            ConstraintBranch(validMinorDominant),
                        )
                    ),
                    modality = ConstraintModality.Require,
                    ruleId = DominantSeventhRules.MINOR_REQUIRES_LEADING_TONE,
                    explanation = ConstraintExplanation(
                        satisfied = "The minor dominant seventh contains the raised leading tone.",
                        violated = "The minor dominant seventh must contain the raised leading tone.",
                    ),
                )
            )
        }
    }

    (0 until program.length - 1).forEach { slot ->
        val v7 = targetExpr(
            slot,
            TargetSelector(
                degrees = setOf(DOMINANT_DEGREE),
                qualities = setOf(ChordQuality.DOMINANT7),
                arities = setOf(ChordArity.SEVENTH),
            )
        )
        val vi = targetExpr(slot + 1, TargetSelector(degrees = setOf(SUBMEDIANT_DEGREE)))
        add(
            Constraint(
                expr = ConstraintExpr.And(listOf(v7, vi)),
                modality = ConstraintModality.Annotate,
                ruleId = DominantSeventhRules.DECEPTIVE_RESOLUTION,
                explanation = ConstraintExplanation(
                    satisfied = "V7 resolves deceptively to vi.",
                ),
            )
        )

        val supertonic = targetExpr(slot, TargetSelector(degrees = setOf(SUPERTONIC_DEGREE)))
        add(
            Constraint(
                expr = ConstraintExpr.And(
                    listOf(supertonic, targetExpr(slot + 1, TargetSelector(degrees = setOf(DOMINANT_DEGREE))))
                ),
                modality = ConstraintModality.Annotate,
                ruleId = DominantSeventhRules.SUPERTONIC_TO_DOMINANT,
                explanation = ConstraintExplanation(
                    satisfied = "The supertonic seventh resolves to the dominant.",
                ),
            )
        )
        add(
            Constraint(
                expr = ConstraintExpr.And(
                    listOf(
                        supertonic,
                        targetExpr(
                            slot + 1,
                            TargetSelector(degrees = setOf(TONIC_DEGREE), inversions = setOf(2))
                        ),
                    )
                ),
                modality = ConstraintModality.Annotate,
                ruleId = DominantSeventhRules.SUPERTONIC_TO_CADENTIAL_SIX_FOUR,
                explanation = ConstraintExplanation(
                    satisfied = "The supertonic seventh resolves into a cadential six-four context.",
                ),
            )
        )
        add(
            Constraint(
                expr = ConstraintExpr.And(
                    listOf(supertonic, targetExpr(slot + 1, TargetSelector(degrees = setOf(LEADING_TONE_DEGREE))))
                ),
                modality = ConstraintModality.Annotate,
                ruleId = DominantSeventhRules.SUPERTONIC_TO_LEADING,
                explanation = ConstraintExplanation(
                    satisfied = "The supertonic seventh resolves to the leading-tone chord.",
                ),
            )
        )

        val leadingChord = targetExpr(slot, TargetSelector(degrees = setOf(LEADING_TONE_DEGREE)))
        add(
            Constraint(
                expr = ConstraintExpr.And(
                    listOf(
                        leadingChord,
                        targetExpr(
                            slot + 1,
                            TargetSelector(
                                degrees = setOf(DOMINANT_DEGREE),
                                qualities = setOf(ChordQuality.DOMINANT7),
                                arities = setOf(ChordArity.SEVENTH),
                            )
                        ),
                    )
                ),
                modality = ConstraintModality.Annotate,
                ruleId = DominantSeventhRules.LEADING_TO_DOMINANT_SEVENTH,
                explanation = ConstraintExplanation(
                    satisfied = "The leading-tone seventh resolves to the dominant seventh.",
                ),
            )
        )
        val tonic = targetExpr(slot + 1, TargetSelector(degrees = setOf(TONIC_DEGREE)))
        add(
            Constraint(
                expr = ConstraintExpr.And(listOf(leadingChord, tonic)),
                modality = ConstraintModality.Annotate,
                ruleId = DominantSeventhRules.LEADING_TO_TONIC,
                explanation = ConstraintExplanation(
                    satisfied = "The leading-tone seventh resolves to tonic.",
                ),
            )
        )
        add(
            Constraint(
                expr = ConstraintExpr.And(
                    listOf(
                        leadingChord,
                        tonic,
                        multiplicity(slot + 1, ChordTone.THIRD, setOf(2, 3, 4)),
                    )
                ),
                modality = ConstraintModality.Annotate,
                ruleId = DominantSeventhRules.LEADING_TONIC_DOUBLES_THIRD,
                explanation = ConstraintExplanation(
                    satisfied = "The tonic doubles its third after the leading-tone seventh.",
                ),
            )
        )
    }
    if (program.length == CIRCLE_DEGREES.size) {
        val circleDegrees = circleDegreeExpr(program)
        val leadingSlots = CIRCLE_DEGREES.indices.toList().dropLast(1)
        val allLeadingSevenths = ConstraintExpr.And(
            listOf(circleDegrees) + leadingSlots.map { slot ->
                targetExpr(slot, TargetSelector(arities = setOf(ChordArity.SEVENTH)))
            }
        )
        add(
            Constraint(
                expr = allLeadingSevenths,
                modality = ConstraintModality.Annotate,
                ruleId = DominantSeventhRules.CIRCLE_OF_FIFTHS_SEVENTHS,
                explanation = ConstraintExplanation(
                    satisfied = "The circle-of-fifths progression uses seventh chords before the final tonic.",
                ),
            )
        )

        val firstThirdAlternation = ConstraintExpr.Or(
            listOf(
                ConstraintBranch(
                    ConstraintExpr.And(
                        leadingSlots.map { slot ->
                            targetExpr(
                                slot,
                                TargetSelector(
                                    inversions = setOf(if (slot % 2 == 0) 1 else 3)
                                )
                            )
                        }
                    )
                ),
                ConstraintBranch(
                    ConstraintExpr.And(
                        leadingSlots.map { slot ->
                            targetExpr(
                                slot,
                                TargetSelector(
                                    inversions = setOf(if (slot % 2 == 0) 3 else 1)
                                )
                            )
                        }
                    )
                ),
            )
        )
        add(
            Constraint(
                expr = ConstraintExpr.And(listOf(circleDegrees, firstThirdAlternation)),
                modality = ConstraintModality.Annotate,
                ruleId = DominantSeventhRules.CIRCLE_FIRST_THIRD_INVERSION,
                explanation = ConstraintExplanation(
                    satisfied = "The circle-of-fifths progression alternates first and third inversions.",
                ),
            )
        )

        val secondRootAlternation = ConstraintExpr.Or(
            listOf(
                ConstraintBranch(
                    ConstraintExpr.And(
                        leadingSlots.map { slot ->
                            targetExpr(
                                slot,
                                TargetSelector(
                                    inversions = setOf(if (slot % 2 == 0) 2 else 0)
                                )
                            )
                        }
                    )
                ),
                ConstraintBranch(
                    ConstraintExpr.And(
                        leadingSlots.map { slot ->
                            targetExpr(
                                slot,
                                TargetSelector(
                                    inversions = setOf(if (slot % 2 == 0) 0 else 2)
                                )
                            )
                        }
                    )
                ),
            )
        )
        add(
            Constraint(
                expr = ConstraintExpr.And(listOf(circleDegrees, secondRootAlternation)),
                modality = ConstraintModality.Annotate,
                ruleId = DominantSeventhRules.CIRCLE_SECOND_ROOT_INVERSION,
                explanation = ConstraintExplanation(
                    satisfied = "The circle-of-fifths progression alternates second inversion and root position.",
                ),
            )
        )

        val completeStates = leadingSlots.map { slot -> completeExpr(slot) }
        val completeThenOmit = ConstraintExpr.And(
            completeStates.mapIndexed { index, complete ->
                if (index % 2 == 0) complete else ConstraintExpr.Not(complete)
            }
        )
        val omitThenComplete = ConstraintExpr.And(
            completeStates.mapIndexed { index, complete ->
                if (index % 2 == 0) ConstraintExpr.Not(complete) else complete
            }
        )
        val rootPosition = ConstraintExpr.And(
            leadingSlots.map { slot ->
                targetExpr(slot, TargetSelector(inversions = setOf(0)))
            }
        )
        add(
            Constraint(
                expr = ConstraintExpr.And(
                    listOf(
                        circleDegrees,
                        rootPosition,
                        ConstraintExpr.Or(
                            listOf(
                                ConstraintBranch(completeThenOmit),
                                ConstraintBranch(omitThenComplete),
                            )
                        ),
                    )
                ),
                modality = ConstraintModality.Annotate,
                ruleId = DominantSeventhRules.CIRCLE_ROOT_POSITION_ALTERNATION,
                explanation = ConstraintExplanation(
                    satisfied = "Root-position circle-of-fifths seventh chords alternate complete and fifth-omitted voicings.",
                ),
            )
        )
    }


}


private fun circleDegreeExpr(program: ConstraintProgram): ConstraintExpr =
    ConstraintExpr.And(
        CIRCLE_DEGREES.mapIndexed { slot, degree ->
            targetExpr(slot, TargetSelector(degrees = setOf(degree)))
        }
    )

private fun completeExpr(slot: Int): ConstraintExpr =
    ConstraintExpr.Atom(
        ConstraintPredicate.ToneCompleteness(
            ToneCompletenessRequirement(
                window = SlotWindow(slot, slot),
                requiredTones = setOf(
                    ChordTone.ROOT,
                    ChordTone.THIRD,
                    ChordTone.FIFTH,
                    ChordTone.SEVENTH,
                ),
                selector = TargetSelector(arities = setOf(ChordArity.SEVENTH)),
                required = false,
            )
        )
    )

private fun targetExpr(slot: Int, selector: TargetSelector): ConstraintExpr =
    ConstraintExpr.Atom(
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

private fun toneCompletenessExpr(
    slot: Int,
    selector: TargetSelector,
    omittedTones: Set<ChordTone>,
): ConstraintExpr = ConstraintExpr.Atom(
    ConstraintPredicate.ToneCompleteness(
        ToneCompletenessRequirement(
            window = SlotWindow(slot, slot),
            omittedTones = omittedTones,
            selector = selector,
            required = false,
        )
    )
)

private fun multiplicity(slot: Int, tone: ChordTone, counts: Set<Int>): ConstraintExpr =
    ConstraintExpr.Atom(ConstraintPredicate.ToneMultiplicity(slot, tone, counts))

private val CIRCLE_DEGREES = listOf(4, 7, 3, 6, 2, 5, 1)

private const val TONIC_DEGREE = 1
private const val SUPERTONIC_DEGREE = 2
private const val SUBMEDIANT_DEGREE = 6
private const val DOMINANT_DEGREE = 5
private const val LEADING_TONE_DEGREE = 7
