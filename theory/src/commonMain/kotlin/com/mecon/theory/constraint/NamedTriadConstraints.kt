package com.mecon.theory.constraint

import com.mecon.api.primitive.PitchClass

import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.Mode
import com.mecon.theory.RuleId
import com.mecon.theory.SlotWindow
import com.mecon.theory.textbook.FirstInversionTriadRules
import com.mecon.theory.textbook.RootPositionTriadRules

/**
 * Named triad rules whose semantics are target identity or bounded tone multiplicity.
 *
 * Connection-pattern rules remain in the triad module because they need per-voice motion
 * witnesses. These constraints own the vertical rules and target-only transition exception.
 */
internal fun namedTriadConstraints(program: ConstraintProgram): List<Constraint> = buildList {
    program.slotDomains.forEachIndexed { slot, domain ->
        if (domain.targets.none { it.arity == ChordArity.TRIAD }) return@forEachIndexed

        val firstInversion = targetExpr(
            slot,
            TargetSelector(inversions = setOf(1), arities = setOf(ChordArity.TRIAD)),
        )
        add(
            Constraint(
                expr = firstInversion,
                modality = ConstraintModality.Annotate,
                ruleId = FirstInversionTriadRules.FIRST_INVERSION_BASS_LINE,
                explanation = ConstraintExplanation(
                    satisfied = "First inversion can enrich the bass line.",
                ),
            )
        )

        val diminished = targetExpr(
            slot,
            TargetSelector(qualities = setOf(ChordQuality.DIMINISHED), arities = setOf(ChordArity.TRIAD)),
        )
        val diminishedFirstInversion = targetExpr(
            slot,
            TargetSelector(
                qualities = setOf(ChordQuality.DIMINISHED),
                inversions = setOf(1),
                arities = setOf(ChordArity.TRIAD),
            ),
        )
        add(
            Constraint(
                expr = diminishedFirstInversion,
                modality = ConstraintModality.Annotate,
                ruleId = FirstInversionTriadRules.DIMINISHED_TRIAD_FIRST_INVERSION,
                explanation = ConstraintExplanation(
                    satisfied = "The diminished triad uses first inversion.",
                    violated = "The diminished triad should normally use first inversion.",
                ),
            )
        )
        add(
            Constraint(
                expr = ConstraintExpr.Or(
                    listOf(
                        ConstraintBranch(ConstraintExpr.Not(diminished)),
                        ConstraintBranch(diminishedFirstInversion),
                    )
                ),
                modality = ConstraintModality.Require,
                ruleId = FirstInversionTriadRules.DIMINISHED_TRIAD_FIRST_INVERSION,
                explanation = ConstraintExplanation(
                    satisfied = "The diminished-triad inversion is valid.",
                    violated = "Classical writing almost exclusively uses the diminished triad in first inversion.",
                ),
            )
        )
    }


    (0 until program.length - 1).forEach { slot ->
        val sourceSelector = TargetSelector(
            degrees = setOf(DOMINANT_DEGREE),
            qualities = setOf(ChordQuality.MAJOR),
            inversions = setOf(0),
            arities = setOf(ChordArity.TRIAD),
        )
        if (!program.slotDomains[slot].targets.any { sourceSelector.matches(it) }) return@forEach

        val innerLeadingLeap = triadNeighborExpr(
            slot = slot,
            sourceSelector = sourceSelector,
            neighborSelector = TargetSelector(
                degrees = setOf(TONIC_DEGREE, SUPERTONIC_DEGREE),
                arities = setOf(ChordArity.TRIAD),
            ),
            candidateScaleDegrees = (1..7).toSet(),
            allowedDiatonicStepDeltas = (-14..14).filter { kotlin.math.abs(it) > 1 }.toSet(),
        )
        add(
            Constraint(
                expr = innerLeadingLeap,
                modality = ConstraintModality.Annotate,
                ruleId = RootPositionTriadRules.INNER_LEADING_TONE_LEAP,
                explanation = ConstraintExplanation(
                    satisfied = "An inner-voice leading tone resolves by a leap.",
                ),
            )
        )

        val leadingToneToSixth = triadNeighborExpr(
            slot = slot,
            sourceSelector = sourceSelector,
            neighborSelector = TargetSelector(
                degrees = setOf(SIXTH_DEGREE),
                qualities = setOf(ChordQuality.MINOR),
                arities = setOf(ChordArity.TRIAD),
            ),
            candidateScaleDegrees = setOf(SIXTH_DEGREE),
            allowedDiatonicStepDeltas = emptySet(),
        )
        add(
            Constraint(
                expr = leadingToneToSixth,
                modality = ConstraintModality.Annotate,
                ruleId = RootPositionTriadRules.MAJOR_DOMINANT_TO_SIXTH_INNER_LEADING_TONE,
                explanation = ConstraintExplanation(
                    satisfied = "An inner-voice leading tone resolves to scale degree six.",
                ),
            )
        )
    }


    if (program.key.mode.signatureTonicDegree == 6) {
        val sourceSelector = TargetSelector(
            degrees = setOf(DOMINANT_DEGREE),
            inversions = setOf(0),
            arities = setOf(ChordArity.TRIAD),
        )
        (0 until program.length - 1).forEach { slot ->
            val forbiddenRelation = triadNeighborExpr(
                slot = slot,
                sourceSelector = sourceSelector,
                neighborSelector = TargetSelector(
                    degrees = setOf(SIXTH_DEGREE),
                    arities = setOf(ChordArity.TRIAD),
                ),
                candidateScaleDegrees = setOf(FOURTH_DEGREE),
                allowedDiatonicStepDeltas = emptySet(),
                required = true,
                sourceTone = ChordTone.FIFTH,
                sourcePitchClasses = setOf(
                    program.key.scale.pitchClasses[FIFTH_DEGREE - 1].transpose(1)
                ),
            )
            add(
                Constraint(
                    expr = ConstraintExpr.Not(forbiddenRelation),
                    modality = ConstraintModality.Require,
                    ruleId = RootPositionTriadRules.MINOR_RAISED_FIFTH_TO_FOURTH,
                    explanation = ConstraintExplanation(
                        satisfied = "The raised fifth does not resolve to scale degree four in the forbidden context.",
                        violated = "In minor, the raised fifth must not resolve to scale degree four in this context.",
                    ),
                )
            )
        }
    }

    if (program.finalTonicMayOmitFifth) {
        val finalSlot = program.length - 1
        val tonicRoot = targetExpr(
            finalSlot,
            TargetSelector(
                degrees = setOf(TONIC_DEGREE),
                inversions = setOf(0),
                arities = setOf(ChordArity.TRIAD),
            ),
        )
        val complete = ConstraintExpr.Atom(
            ConstraintPredicate.ToneCompleteness(
                ToneCompletenessRequirement(
                    window = SlotWindow(finalSlot, finalSlot),
                    requiredTones = setOf(ChordTone.ROOT, ChordTone.THIRD, ChordTone.FIFTH),
                    selector = TargetSelector(
                        degrees = setOf(TONIC_DEGREE),
                        inversions = setOf(0),
                        arities = setOf(ChordArity.TRIAD),
                    ),
                    required = false,
                )
            )
        )
        val allowedOmission = ConstraintExpr.And(
            listOf(
                tonicRoot,
                multiplicity(finalSlot, ChordTone.ROOT, setOf(3)),
                multiplicity(finalSlot, ChordTone.THIRD, setOf(1)),
                multiplicity(finalSlot, ChordTone.FIFTH, setOf(0)),
            )
        )
        add(
            Constraint(
                expr = ConstraintExpr.Or(
                    listOf(
                        ConstraintBranch(ConstraintExpr.Not(tonicRoot)),
                        ConstraintBranch(complete),
                        ConstraintBranch(allowedOmission),
                    )
                ),
                modality = ConstraintModality.Prefer(),
                ruleId = RootPositionTriadRules.FINAL_TONIC_SPACING,
                explanation = ConstraintExplanation(
                    satisfied = "The final tonic omission pattern is valid.",
                    violated = "When the final tonic omits the fifth, retain three roots and one third.",
                ),
            )
        )
    }

    if (program.key.mode == Mode.IONIAN) {
        (0 until program.length - 1).forEach { slot ->
            val forbidden = ConstraintExpr.And(
                listOf(
                    targetExpr(
                        slot,
                        TargetSelector(
                            degrees = setOf(DOMINANT_DEGREE),
                            qualities = setOf(ChordQuality.MAJOR),
                            inversions = setOf(0),
                            arities = setOf(ChordArity.TRIAD),
                        )
                    ),
                    targetExpr(
                        slot + 1,
                        TargetSelector(
                            degrees = setOf(SIXTH_DEGREE),
                            qualities = setOf(ChordQuality.MINOR),
                            inversions = setOf(1),
                            arities = setOf(ChordArity.TRIAD),
                        )
                    ),
                )
            )
            add(
                Constraint(
                    expr = ConstraintExpr.Not(forbidden),
                    modality = ConstraintModality.Require,
                    ruleId = FirstInversionTriadRules.MAJOR_ROOT_DOMINANT_TO_MINOR_SIXTH,
                    explanation = ConstraintExplanation(
                        satisfied = "The major root-position dominant does not resolve directly to minor vi.",
                        violated = "In major, the root-position dominant must not be followed by first-inversion minor vi.",
                    ),
                )
            )
        }
    }
}

private fun triadNeighborExpr(
    slot: Int,
    sourceSelector: TargetSelector,
    neighborSelector: TargetSelector,
    candidateScaleDegrees: Set<Int>,
    allowedDiatonicStepDeltas: Set<Int>,
    required: Boolean = false,
    sourceTone: ChordTone = ChordTone.THIRD,
    sourcePitchClasses: Set<PitchClass> = emptySet(),
): ConstraintExpr = ConstraintExpr.Atom(
    ConstraintPredicate.NeighborTone(
        ChordToneNeighborRequirement(
            window = SlotWindow(slot, slot + 1),
            sourceSlot = slot,
            sourceTone = sourceTone,
            direction = ChordToneNeighborDirection.NEXT,
            candidateScaleDegrees = candidateScaleDegrees,
            allowedDiatonicStepDeltas = allowedDiatonicStepDeltas,
            voiceFilter = ChordToneVoiceFilter.INNER,
            sourceSelector = sourceSelector,
            neighborSelector = neighborSelector,
            sourcePitchClasses = sourcePitchClasses,
            required = required,
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

private fun multiplicity(slot: Int, tone: ChordTone, counts: Set<Int>): ConstraintExpr =
    ConstraintExpr.Atom(ConstraintPredicate.ToneMultiplicity(slot, tone, counts))

private const val TONIC_DEGREE = 1
private const val SUPERTONIC_DEGREE = 2
private const val DOMINANT_DEGREE = 5
private const val SIXTH_DEGREE = 6
private const val FOURTH_DEGREE = 4
private const val FIFTH_DEGREE = 5
