package com.mecon.theory.schoenberg

import com.mecon.theory.Key
import com.mecon.theory.Mode
import com.mecon.theory.RuleId
import com.mecon.theory.SearchConfig
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.TargetSelector

enum class SchoenbergDissonanceTreatment {
    STRICT,
    CADENTIAL,
    FREER,
}

object SchoenbergFreerDissonanceChapter {
    val LEADING_SUBSTITUTION_RULE_ID = RuleId("schoenberg.freer.leading-substitution")

    fun program(
        key: Key,
        continuationChordCount: Int,
        cadenceOptions: SchoenbergCadenceOptions = SchoenbergCadenceOptions(),
        progression: SchoenbergSymbolicProgression? = null,
        searchConfig: SearchConfig = SearchConfig(maxResults = 4, beamWidth = 192),
    ): ConstraintProgram {
        val base = SchoenbergIntegratedTechTree.program(
            key = key,
            continuationChordCount = continuationChordCount,
            treatmentIds = SchoenbergHarmonicTreatments.integratedDiatonicTreatments,
            progression = progression,
            searchConfig = searchConfig,
            requireAdjacentCommonTone = false,
            dissonanceTreatment = SchoenbergDissonanceTreatment.FREER,
        )
        val policy = SchoenbergCadencePolicy(
            options = cadenceOptions,
            requireFreerLeadingSubstitution = true,
            minor = key.mode == Mode.AEOLIAN,
        )
        return base.copy(
            constraints = base.constraints +
                SchoenbergCadenceChapter.inheritedConstraints(base.length, cadenceOptions) +
                SchoenbergCadenceChapter.deceptiveOuterLeadingToneConstraints(progression) +
                policy.constraints(base.length),
        )
    }

    fun enumerate(
        key: Key,
        continuationChordCount: Int,
        cadenceOptions: SchoenbergCadenceOptions = SchoenbergCadenceOptions(),
        chordSelectors: List<TargetSelector> = emptyList(),
        budget: SchoenbergIntegratedTechTree.EnumerationBudget =
            SchoenbergIntegratedTechTree.EnumerationBudget(),
    ): List<SchoenbergSymbolicProgression> =
        SchoenbergIntegratedTechTree.enumerate(
            key = key,
            options = SchoenbergIntegratedTechTree.EnumerationOptions(
                continuationChordCount = continuationChordCount,
                treatmentIds = SchoenbergHarmonicTreatments.integratedDiatonicTreatments,
                budget = budget,
                chordSelectors = chordSelectors,
                requireAdjacentCommonTone = false,
                applyRootMotionDirection = true,
                applyHarmonicRepetitionPolicy = true,
                dissonanceTreatment = SchoenbergDissonanceTreatment.FREER,
                sequencePolicy = SchoenbergCadencePolicy(
                    options = cadenceOptions,
                    requireFreerLeadingSubstitution = true,
                    minor = key.mode == Mode.AEOLIAN,
                ),
            ),
        )
}
