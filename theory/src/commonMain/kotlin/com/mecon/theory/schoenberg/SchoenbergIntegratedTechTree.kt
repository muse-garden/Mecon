package com.mecon.theory.schoenberg

import com.mecon.theory.Key
import com.mecon.theory.Mode
import com.mecon.theory.NaturalTriad
import com.mecon.theory.RuleId
import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.constraint.AdjacentCommonToneRequirement
import com.mecon.theory.constraint.AllDifferentRequirement
import com.mecon.theory.constraint.ChordToneNeighborRequirement
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.SlotDomain
import com.mecon.theory.constraint.TargetFeatureBonusRequirement
import com.mecon.theory.constraint.TargetSelector
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.constraint.ToneCompletenessRequirement
import com.mecon.theory.constraint.RootlessDominantNinthMetadata
import com.mecon.theory.constraint.SecondaryHarmonyMetadata
import com.mecon.theory.constraint.InterpretedChordTarget
import com.mecon.theory.harmony.HarmonicTreatmentId
import com.mecon.theory.textbook.DominantSeventhRules
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookTriadPosition

object SchoenbergIntegratedTechTree {
    /** 一般四部写作规则：三和弦应保持完整（根/三音必到、五音尽量在场）。不属于具体和弦教学，随勋伯格模式保留。 */
    val TRIAD_COMPLETE_RULE_ID = RuleId("schoenberg.four-part.triad-complete")

    /** 根音上行纯四度（下行纯五度）对应的调内音级步数，用于七和弦解决的根音进行判定。 */
    private const val PERFECT_FOURTH_UP_DEGREES = 3
    private const val DOMINANT_DEGREE = 5
    private const val SUBDOMINANT_DEGREE = 4

    data class EnumerationBudget(
        val maxResults: Int = 64,
        val maxVisitedNodes: Int = 50_000,
    ) {
        init {
            require(maxResults > 0) { "maxResults must be positive" }
            require(maxVisitedNodes > 0) { "maxVisitedNodes must be positive" }
        }
    }

    data class EnumerationOptions(
        val continuationChordCount: Int,
        val treatmentIds: Set<HarmonicTreatmentId>,
        val selectedSecondaryHarmony: SchoenbergSymbolicChord? = null,
        val requiredKnowledgeTags: Set<SchoenbergKnowledgeTag> = emptySet(),
        val preferredKnowledgeTags: Set<SchoenbergKnowledgeTag> = requiredKnowledgeTags,
        val budget: EnumerationBudget = EnumerationBudget(),
        val chordSelectors: List<TargetSelector> = emptyList(),
        val requireAdjacentCommonTone: Boolean = true,
        val applyRootMotionDirection: Boolean = false,
        val applyHarmonicRepetitionPolicy: Boolean = false,
        val dissonanceTreatment: SchoenbergDissonanceTreatment = SchoenbergDissonanceTreatment.STRICT,
        val sequencePolicy: SchoenbergSymbolicSequencePolicy? = null,
        val shouldContinue: () -> Boolean = { true },
    )

    val exerciseDescriptors: List<SchoenbergExerciseDescriptor>
        get() = SchoenbergIntegratedStages.exerciseDescriptors

    fun programForStage(
        exerciseId: String,
        key: Key,
        continuationChordCount: Int,
        progression: SchoenbergSymbolicProgression? = null,
        searchConfig: SearchConfig = SearchConfig(maxResults = 4, beamWidth = 160),
    ): ConstraintProgram {
        val stage = stageFor(exerciseId)
        return program(
            key = key,
            continuationChordCount = continuationChordCount,
            treatmentIds = stage.treatmentIds,
            progression = progression,
            searchConfig = searchConfig,
        )
    }

    fun enumerateForStage(
        exerciseId: String,
        key: Key,
        continuationChordCount: Int,
        requiredKnowledgeTags: Set<SchoenbergKnowledgeTag> = emptySet(),
        chordSelectors: List<TargetSelector> = emptyList(),
        selections: Map<String, List<String>> = emptyMap(),
        budget: EnumerationBudget = EnumerationBudget(),
        shouldContinue: () -> Boolean = { true },
    ): List<SchoenbergSymbolicProgression> {
        val stage = stageFor(exerciseId)
        val descriptor = SchoenbergCommonToneExercises.descriptorForExercise(exerciseId)
        val requiredTargets = descriptor.requiredKnowledgeTags + requiredKnowledgeTags
        val preferredTargets = descriptor.preferredKnowledgeTags + requiredTargets
        val selectedSecondaryHarmony =
            selections[SchoenbergExerciseSelectionKeys.SECONDARY_HARMONY]?.singleOrNull()?.let { selectedId ->
            SchoenbergSecondaryDominantChapter.harmonyChoices(key)
                .firstOrNull { it.id == selectedId }
                ?.chord
                ?: error("Unknown secondary-harmony chord $selectedId in the current key")
            }
        return enumerate(
            key = key,
            options = EnumerationOptions(
                continuationChordCount = continuationChordCount,
                treatmentIds = stage.treatmentIds,
                selectedSecondaryHarmony = selectedSecondaryHarmony,
                requiredKnowledgeTags = requiredTargets,
                preferredKnowledgeTags = preferredTargets,
                budget = budget,
                chordSelectors = chordSelectors,
                shouldContinue = shouldContinue,
            ),
        )
    }

    fun program(
        key: Key,
        continuationChordCount: Int,
        treatmentIds: Set<HarmonicTreatmentId>,
        progression: SchoenbergSymbolicProgression? = null,
        searchConfig: SearchConfig = SearchConfig(maxResults = 4, beamWidth = 160),
        requireAdjacentCommonTone: Boolean = true,
        dissonanceTreatment: SchoenbergDissonanceTreatment = SchoenbergDissonanceTreatment.STRICT,
    ): ConstraintProgram = SchoenbergIntegratedProgramBuilder.build(
        key = key,
        continuationChordCount = continuationChordCount,
        treatmentIds = treatmentIds,
        progression = progression,
        searchConfig = searchConfig,
        requireAdjacentCommonTone = requireAdjacentCommonTone,
        dissonanceTreatment = dissonanceTreatment,
    )


    fun enumerate(
        key: Key,
        options: EnumerationOptions,
    ): List<SchoenbergSymbolicProgression> =
        SchoenbergIntegratedEnumerator.enumerate(key, options)

    internal fun allowsIntegratedStep(
        prefix: List<SchoenbergSymbolicChord>,
        after: SchoenbergSymbolicChord,
        triads: List<NaturalTriad>,
        key: Key,
        includeLeadingTriad: Boolean,
        minor: Boolean,
        requireAdjacentCommonTone: Boolean = true,
        applyRootMotionDirection: Boolean = false,
        applyHarmonicRepetitionPolicy: Boolean = false,
        dissonanceTreatment: SchoenbergDissonanceTreatment = SchoenbergDissonanceTreatment.STRICT,
        sequencePolicy: SchoenbergSymbolicSequencePolicy? = null,
        totalLength: Int,
    ): Boolean = SchoenbergIntegratedTransitionPolicy.allows(
        prefix = prefix,
        after = after,
        triads = triads,
        key = key,
        includeLeadingTriad = includeLeadingTriad,
        minor = minor,
        requireAdjacentCommonTone = requireAdjacentCommonTone,
        applyRootMotionDirection = applyRootMotionDirection,
        applyHarmonicRepetitionPolicy = applyHarmonicRepetitionPolicy,
        dissonanceTreatment = dissonanceTreatment,
        sequencePolicy = sequencePolicy,
        totalLength = totalLength,
    )


    fun vocabularyForStage(
        exerciseId: String,
        key: Key,
    ): Set<SchoenbergSymbolicChord> =
        SchoenbergIntegratedVocabulary.forStage(exerciseId, key)

    fun progressionMatchesSelectors(
        progression: SchoenbergSymbolicProgression,
        key: Key,
        selectors: List<TargetSelector>,
    ): Boolean = SchoenbergIntegratedVocabulary.progressionMatchesSelectors(
        progression,
        key,
        selectors,
    )

    internal fun vocabulary(
        key: Key,
        treatmentIds: Set<HarmonicTreatmentId>,
    ): List<SchoenbergSymbolicChord> = SchoenbergIntegratedVocabulary.build(
        key = key,
        treatmentIds = treatmentIds,
    )

    private fun stageFor(exerciseId: String): SchoenbergIntegratedStageSpec {
        val treatmentIds = SchoenbergCommonToneExercises
            .descriptorForExercise(exerciseId)
            .harmonicTreatmentIds
        require(treatmentIds.isNotEmpty()) {
            "Exercise $exerciseId does not define an integrated harmonic treatment"
        }
        return SchoenbergIntegratedStageSpec(treatmentIds)
    }

}
