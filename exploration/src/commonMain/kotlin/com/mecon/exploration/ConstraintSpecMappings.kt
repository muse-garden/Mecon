package com.mecon.exploration

import com.mecon.theory.ChordArity
import com.mecon.api.primitive.PitchClass
import com.mecon.theory.ChordQuality
import com.mecon.theory.NaturalTriads
import com.mecon.theory.RequirementMode
import com.mecon.theory.RuleCatalog
import com.mecon.theory.RuleId
import com.mecon.theory.RuleProfile
import com.mecon.theory.RuleRequirement
import com.mecon.theory.SceneMatcher
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.constraint.Constraint
import com.mecon.theory.constraint.ConstraintBranch
import com.mecon.theory.constraint.ConstraintExplanation
import com.mecon.theory.constraint.ConstraintExpr
import com.mecon.theory.constraint.ConstraintModality
import com.mecon.theory.constraint.ConstraintPredicate
import com.mecon.theory.constraint.ConstraintScope
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.AdjacentCommonToneRequirement
import com.mecon.theory.constraint.AllDifferentRequirement
import com.mecon.theory.constraint.AvoidDoublingRequirement
import com.mecon.theory.constraint.AvoidScaleDegreeDoublingRequirement
import com.mecon.theory.constraint.ChordTarget
import com.mecon.theory.constraint.ChordToneNeighborDirection
import com.mecon.theory.constraint.ChordToneNeighborRequirement
import com.mecon.theory.constraint.ChordToneVoiceFilter
import com.mecon.theory.constraint.DoublingRequirement
import com.mecon.theory.constraint.SlotDomain
import com.mecon.theory.constraint.SpacingRequirement
import com.mecon.theory.constraint.TargetFeatureBonusRequirement
import com.mecon.theory.constraint.TargetSelector
import com.mecon.theory.constraint.ToneCompletenessRequirement
import com.mecon.theory.constraint.SpacingPreference as TheorySpacingPreference
import com.mecon.theory.textbook.DominantSeventhRules
import com.mecon.theory.textbook.SeventhFifthConstraint
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookSeventhTarget
import com.mecon.theory.textbook.TextbookTriadPosition
import com.mecon.theory.textbook.TextbookTriadTarget
import com.mecon.theory.textbook.TextbookTriadConstraintPreset
import com.mecon.theory.textbook.TextbookTriadConstraintRequirements
import com.mecon.theory.textbook.TextbookTriadWritingSlot
import com.mecon.theory.textbook.requirementsFor
import com.mecon.theory.textbook.textbookTriadInKey
internal fun RequirementModeSpec.toTheory(): RequirementMode =
    when (this) {
        RequirementModeSpec.REQUIRE_INDICATION -> RequirementMode.REQUIRE_INDICATION
        RequirementModeSpec.REQUIRE_VIOLATION -> RequirementMode.REQUIRE_VIOLATION
        RequirementModeSpec.FORBID -> RequirementMode.FORBID
    }

internal fun SlotWindowSpec.toTheory(): SlotWindow = SlotWindow(start, end)

internal fun SlotWindowSpec.overlaps(length: Int): Boolean =
    (0 until length).any { slot -> slot >= start && (end == null || slot <= end) }

internal fun ChordToneSpec.toTheory(): ChordTone =
    when (this) {
        ChordToneSpec.ROOT -> ChordTone.ROOT
        ChordToneSpec.THIRD -> ChordTone.THIRD
        ChordToneSpec.FIFTH -> ChordTone.FIFTH
        ChordToneSpec.SEVENTH -> ChordTone.SEVENTH
        ChordToneSpec.BASS -> ChordTone.BASS
    }

internal fun ConstraintAtSpec.toTheoryConstraint(
    diagnostics: MutableList<SolverDiagnostic>,
): Constraint? {
    val compiledExpr = expr.toTheoryExpr(diagnostics) ?: return null
    val theoryModality = when (modality) {
        ConstraintModalitySpec.REQUIRE -> ConstraintModality.Require
        ConstraintModalitySpec.PREFER -> ConstraintModality.Prefer(weight)
        ConstraintModalitySpec.REWARD -> ConstraintModality.Reward(requireNotNull(bonus))
        ConstraintModalitySpec.ANNOTATE -> ConstraintModality.Annotate
    }
    return Constraint(
        expr = compiledExpr,
        modality = theoryModality,
        ruleId = ruleId?.let(::RuleId),
        explanation = message?.let(::ConstraintExplanation),
        scope = ConstraintScope(
            window = window?.toTheory(),
            selector = selector.toTheory(diagnostics),
        ),
    )
}

internal fun ConstraintExprSpec.toTheoryExpr(
    diagnostics: MutableList<SolverDiagnostic>,
): ConstraintExpr? = when (this) {
    is ConstraintAtomExprSpec -> predicate.toTheoryExpr(diagnostics)
    is ConstraintAndExprSpec -> terms.mapNotNull { it.toTheoryExpr(diagnostics) }
        .takeIf { it.size == terms.size }
        ?.let(ConstraintExpr::And)
    is ConstraintOrExprSpec -> branches.mapNotNull { branch ->
        branch.expr.toTheoryExpr(diagnostics)?.let { expr ->
            ConstraintBranch(
                expr = expr,
                ruleId = branch.ruleId?.let(::RuleId),
                explanation = branch.message?.let(::ConstraintExplanation),
                scoreDelta = branch.scoreDelta,
            )
        }
    }.takeIf { it.size == branches.size }?.let(ConstraintExpr::Or)
    is ConstraintNotExprSpec -> term.toTheoryExpr(diagnostics)?.let(ConstraintExpr::Not)
}

internal fun ConstraintExprSpec.atomicSpecs(): List<SlotConstraintSpec> = when (this) {
    is ConstraintAtomExprSpec -> listOf(predicate)
    is ConstraintAndExprSpec -> terms.flatMap { it.atomicSpecs() }
    is ConstraintOrExprSpec -> branches.flatMap { it.expr.atomicSpecs() }
    is ConstraintNotExprSpec -> term.atomicSpecs()
}

internal fun SlotConstraintSpec.toTheoryExpr(
    diagnostics: MutableList<SolverDiagnostic>,
): ConstraintExpr? {
    val expression = when (this) {
        is RuleAtSpec -> {
            val atom = ConstraintExpr.Atom(
                ConstraintPredicate.RuleFound(
                    ruleId = RuleId(ruleId),
                    kind = when (mode) {
                        RequirementModeSpec.REQUIRE_INDICATION -> com.mecon.theory.RuleFindingKind.INDICATION
                        RequirementModeSpec.REQUIRE_VIOLATION -> com.mecon.theory.RuleFindingKind.VIOLATION
                        RequirementModeSpec.FORBID -> null
                    },
                    window = window.toTheory(),
                )
            )
            if (mode == RequirementModeSpec.FORBID) ConstraintExpr.Not(atom) else atom
        }
        is DoublingAtSpec -> ConstraintExpr.Atom(
            ConstraintPredicate.ToneDoubled(DoublingRequirement(slot, tone.toTheory(), required, selector.toTheory(diagnostics)))
        )
        is AvoidDoublingAtSpec -> ConstraintExpr.Atom(
            ConstraintPredicate.ToneNotDoubled(AvoidDoublingRequirement(slot, tone.toTheory(), required, selector.toTheory(diagnostics)))
        )
        is AvoidScaleDegreeDoublingAtSpec -> ConstraintExpr.Atom(
            ConstraintPredicate.ScaleDegreeNotDoubled(
                AvoidScaleDegreeDoublingRequirement(slot, degree, alteration, required, selector.toTheory(diagnostics))
            )
        )
        is ToneCompletenessAtSpec -> ConstraintExpr.Atom(
            ConstraintPredicate.ToneCompleteness(
                ToneCompletenessRequirement(
                    window.toTheory(),
                    requiredTones.map { it.toTheory() }.toSet(),
                    omittedTones.map { it.toTheory() }.toSet(),
                    selector.toTheory(diagnostics),
                )
            )
        )
        is SpacingAtSpec -> ConstraintExpr.Atom(
            ConstraintPredicate.Spacing(SpacingRequirement(window.toTheory(), preference.toTheorySpacing()))
        )
        is FifthAtSpec -> ConstraintExpr.Atom(ConstraintPredicate.ToneCompleteness(fifth.toToneCompleteness(slot)))
        is AllDifferentSpec -> ConstraintExpr.Atom(
            ConstraintPredicate.DistinctIdentities(
                AllDifferentRequirement(window.toTheory(), identityMode)
            )
        )
        is AdjacentCommonToneSpec -> ConstraintExpr.Atom(
            ConstraintPredicate.CommonToneWithPrevious(AdjacentCommonToneRequirement(window.toTheory(), holdInSameVoice))
        )
        is ChordToneNeighborSpec -> ConstraintExpr.Atom(
            ConstraintPredicate.NeighborTone(
                ChordToneNeighborRequirement(
                    window = window.toTheory(),
                    sourceSlot = sourceSlot,
                    sourceTone = sourceTone.toTheory(),
                    direction = direction.toTheory(),
                    candidateScaleDegrees = candidateScaleDegrees,
                    allowedDiatonicStepDeltas = allowedDiatonicStepDeltas,
                    voiceFilter = voiceFilter.toTheory(),
                    required = required,
                    candidateAlterations = candidateAlterations,
                    sourceSelector = sourceSelector.toTheory(diagnostics),
                    neighborSelector = neighborSelector.toTheory(diagnostics),
                )
            )
        )
        is TargetFeatureBonusSpec -> ConstraintExpr.Atom(
            ConstraintPredicate.TargetMatches(
                TargetFeatureBonusRequirement(window.toTheory(), selector.toTheory(diagnostics), RuleId(ruleId), message, bonus)
            )
        )
        is ChordAtSpec, is ConstraintAtSpec -> {
            diagnostics += Diagnostics.constraintInvalid("constraint-at 的 Atom 不支持 ${this::class.simpleName}。")
            null
        }
    }
    return expression
}

internal fun TextbookTriadConstraintRequirements.toSpecConstraints(): List<SlotConstraintSpec> =
    toneCompleteness.map { requirement ->
        ToneCompletenessAtSpec(
            window = SlotWindowSpec(requirement.window.start, requirement.window.end),
            requiredTones = requirement.requiredTones.map { it.toSpec() }.toSet(),
            omittedTones = requirement.omittedTones.map { it.toSpec() }.toSet(),
            selector = requirement.selector.toSpec(),
        )
    } + doublings.map { requirement ->
        DoublingAtSpec(
            slot = requirement.slot,
            tone = requirement.tone.toSpec(),
            required = requirement.required,
            selector = requirement.selector.toSpec(),
        )
    } + avoidScaleDegreeDoublings.map { requirement ->
        AvoidScaleDegreeDoublingAtSpec(
            slot = requirement.slot,
            degree = requirement.degree,
            alteration = requirement.alteration,
            required = requirement.required,
            selector = requirement.selector.toSpec(),
        )
    }

internal fun ChordTone.toSpec(): ChordToneSpec =
    when (this) {
        ChordTone.ROOT -> ChordToneSpec.ROOT
        ChordTone.THIRD -> ChordToneSpec.THIRD
        ChordTone.FIFTH -> ChordToneSpec.FIFTH
        ChordTone.SEVENTH -> ChordToneSpec.SEVENTH
        ChordTone.BASS -> ChordToneSpec.BASS
    }

internal fun TargetSelector.toSpec(): TargetSelectorSpec =
    TargetSelectorSpec(
        degrees = degrees,
        qualities = qualities.map { it.name }.toSet(),
        inversions = inversions,
        arities = arities.map { it.toSpec() }.toSet(),
        requiredPitchClasses = requiredPitchClasses.mapTo(linkedSetOf()) { it.value },
        identityKeys = identityKeys,
        sonorityIdentityKeys = sonorityIdentityKeys,
        interpretationIdentityKeys = interpretationIdentityKeys,
    )

internal fun ChordArity.toSpec(): ChordAritySpec =
    when (this) {
        ChordArity.TRIAD -> ChordAritySpec.TRIAD
        ChordArity.SEVENTH -> ChordAritySpec.SEVENTH
    }

internal fun TargetSelectorSpec.toTheory(diagnostics: MutableList<SolverDiagnostic>): TargetSelector =
    TargetSelector(
        degrees = degrees,
        qualities = parseSelectorQualities(qualities, diagnostics),
        inversions = inversions,
        arities = arities.map { it.toTheoryArity() }.toSet(),
        requiredPitchClasses = requiredPitchClasses.mapTo(linkedSetOf(), ::PitchClass),
        identityKeys = identityKeys,
        sonorityIdentityKeys = sonorityIdentityKeys,
        interpretationIdentityKeys = interpretationIdentityKeys,
    )

internal fun parseSelectorQualities(
    names: Collection<String>,
    diagnostics: MutableList<SolverDiagnostic>,
): Set<ChordQuality> =
    names.mapNotNull { name ->
        runCatching { ChordQuality.valueOf(name) }.getOrElse {
            diagnostics += Diagnostics.constraintInvalid("未知和弦性质 $name。")
            null
        }
    }.toSet()

internal fun ChordAritySpec.toTheoryArity(): ChordArity =
    when (this) {
        ChordAritySpec.TRIAD -> ChordArity.TRIAD
        ChordAritySpec.SEVENTH -> ChordArity.SEVENTH
    }

internal fun ChordToneNeighborDirectionSpec.toTheory(): ChordToneNeighborDirection =
    when (this) {
        ChordToneNeighborDirectionSpec.PREVIOUS -> ChordToneNeighborDirection.PREVIOUS
        ChordToneNeighborDirectionSpec.NEXT -> ChordToneNeighborDirection.NEXT
    }

internal fun ChordToneVoiceFilterSpec.toTheory(): ChordToneVoiceFilter =
    when (this) {
        ChordToneVoiceFilterSpec.ANY -> ChordToneVoiceFilter.ANY
        ChordToneVoiceFilterSpec.OUTER -> ChordToneVoiceFilter.OUTER
        ChordToneVoiceFilterSpec.INNER -> ChordToneVoiceFilter.INNER
        ChordToneVoiceFilterSpec.SOPRANO -> ChordToneVoiceFilter.SOPRANO
        ChordToneVoiceFilterSpec.ALTO -> ChordToneVoiceFilter.ALTO
        ChordToneVoiceFilterSpec.TENOR -> ChordToneVoiceFilter.TENOR
        ChordToneVoiceFilterSpec.BASS -> ChordToneVoiceFilter.BASS
    }

internal fun SpacingPreference.toTheorySpacing(): TheorySpacingPreference =
    when (this) {
        SpacingPreference.ANY -> TheorySpacingPreference.ANY
        SpacingPreference.CLOSE -> TheorySpacingPreference.CLOSE
        SpacingPreference.OPEN -> TheorySpacingPreference.OPEN
    }

internal fun FifthConstraintSpec.toTheory(): SeventhFifthConstraint =
    when (this) {
        FifthConstraintSpec.REQUIRE_FIFTH -> SeventhFifthConstraint.REQUIRE_FIFTH
        FifthConstraintSpec.OMIT_FIFTH -> SeventhFifthConstraint.OMIT_FIFTH
    }

internal fun FifthConstraintSpec.toToneCompleteness(slot: Int): ToneCompletenessRequirement =
    when (this) {
        FifthConstraintSpec.REQUIRE_FIFTH -> ToneCompletenessRequirement(
            window = SlotWindow(slot, slot),
            requiredTones = setOf(ChordTone.ROOT, ChordTone.THIRD, ChordTone.FIFTH, ChordTone.SEVENTH),
            selector = TargetSelector(arities = setOf(ChordArity.SEVENTH)),
        )
        FifthConstraintSpec.OMIT_FIFTH -> ToneCompletenessRequirement(
            window = SlotWindow(slot, slot),
            requiredTones = setOf(ChordTone.ROOT, ChordTone.SEVENTH),
            omittedTones = setOf(ChordTone.FIFTH),
            selector = TargetSelector(arities = setOf(ChordArity.SEVENTH)),
        )
    }

internal fun SeventhFifthConstraint.toSpec(): FifthConstraintSpec =
    when (this) {
        SeventhFifthConstraint.REQUIRE_FIFTH -> FifthConstraintSpec.REQUIRE_FIFTH
        SeventhFifthConstraint.OMIT_FIFTH -> FifthConstraintSpec.OMIT_FIFTH
    }
