package com.mecon.theory.schoenberg

import com.mecon.theory.ChapterId
import com.mecon.theory.Key
import com.mecon.theory.RuleConfig
import com.mecon.theory.RuleId
import com.mecon.theory.RuleProfile
import com.mecon.theory.SearchConfig
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.TargetSelector
import com.mecon.theory.textbook.FirstInversionTriadRules

object SchoenbergCommonToneExercises {
    val CHAPTER_ID: ChapterId = ChapterId("schoenberg.harmony")
    val CHAPTER_RULE_ID: RuleId = RuleId("schoenberg.harmony")

    val MAJOR_BRANCH_RULE_ID: RuleId = RuleId("schoenberg.major-connections")
    val MINOR_BRANCH_RULE_ID: RuleId = RuleId("schoenberg.minor-connections")
    val GENERAL_BRANCH_RULE_ID: RuleId = RuleId("schoenberg.general-connections")
    val MODULATION_BRANCH_RULE_ID: RuleId = RuleId("schoenberg.modulation")

    val FIRST_EXERCISE_RULE_ID: RuleId = RuleId("schoenberg.common-tone-root-position.1")
    val LEADING_TRIAD_RULE_ID: RuleId = RuleId("schoenberg.leading-triad.preparation-resolution")
    val LEADING_TRIAD_PREPARATION_RULE_ID: RuleId = RuleId("schoenberg.leading-triad.preparation")
    val LEADING_TRIAD_RESOLUTION_RULE_ID: RuleId = RuleId("schoenberg.leading-triad.resolution")
    val FIRST_INVERSION_RULE_ID: RuleId = RuleId("schoenberg.first-inversion.connections")
    val SECOND_INVERSION_RULE_ID: RuleId = RuleId("schoenberg.second-inversion.preparation-resolution")
    val SEVENTH_CHORD_RULE_ID: RuleId = RuleId("schoenberg.seventh-chord.circle")
    val INTEGRATED_MAJOR_LEADING_RULE_ID: RuleId = RuleId("schoenberg.integrated.major.leading-triad")
    val INTEGRATED_MAJOR_FIRST_INVERSION_RULE_ID: RuleId = RuleId("schoenberg.integrated.major.first-inversion")
    val INTEGRATED_MAJOR_SECOND_INVERSION_RULE_ID: RuleId = RuleId("schoenberg.integrated.major.second-inversion")
    val INTEGRATED_MAJOR_SEVENTH_CHORD_RULE_ID: RuleId = RuleId("schoenberg.integrated.major.seventh-chord")
    val INTEGRATED_MINOR_LEADING_RULE_ID: RuleId = RuleId("schoenberg.integrated.minor.leading-triad")
    val INTEGRATED_MINOR_FIRST_INVERSION_RULE_ID: RuleId = RuleId("schoenberg.integrated.minor.first-inversion")
    val INTEGRATED_MINOR_SECOND_INVERSION_RULE_ID: RuleId = RuleId("schoenberg.integrated.minor.second-inversion")
    val INTEGRATED_MINOR_SEVENTH_CHORD_RULE_ID: RuleId = RuleId("schoenberg.integrated.minor.seventh-chord")
    val NO_COMMON_TONE_MAJOR_RULE_ID: RuleId = RuleId("schoenberg.no-common-tone.major")
    val NO_COMMON_TONE_MINOR_RULE_ID: RuleId = RuleId("schoenberg.no-common-tone.minor")
    val ROOT_MOTION_AND_REPETITION_RULE_ID: RuleId =
        RuleId("schoenberg.root-motion-and-repetition")
    val CADENCE_RULE_ID: RuleId = RuleId("schoenberg.cadence")
    val FREER_SEVENTH_LEADING_RULE_ID: RuleId =
        RuleId("schoenberg.freer-seventh-leading")
    val SECONDARY_HARMONY_RULE_ID: RuleId =
        RuleId("schoenberg.secondary-harmony")
    val DIMINISHED_SEVENTH_RULE_ID: RuleId =
        RuleId("schoenberg.diminished-seventh")
    val INTEGRATED_DIMINISHED_SEVENTH_RULE_ID: RuleId =
        RuleId("schoenberg.integrated.diminished-seventh")
    val AUGMENTED_SIXTH_RULE_ID: RuleId =
        RuleId("schoenberg.augmented-sixth")
    val INTEGRATED_AUGMENTED_SIXTH_RULE_ID: RuleId =
        RuleId("schoenberg.integrated.augmented-sixth")
    val MINOR_SUBDOMINANT_CONNECTION_RULE_ID: RuleId =
        RuleId("schoenberg.minor-subdominant.connections")
    val NEAPOLITAN_CADENCE_RULE_ID: RuleId =
        RuleId("schoenberg.minor-subdominant.neapolitan-cadence")
    val ANALOGOUS_NEAPOLITAN_RULE_ID: RuleId =
        RuleId("schoenberg.minor-subdominant.analogous-neapolitan")
    val DISTANT_MODULATION_RULE_ID: RuleId = SchoenbergDistantModulationChapter.RULE_ID
    val KNOWLEDGE_LEADING_TRIAD_RULE_ID: RuleId = RuleId("solver.constraint.knowledge.leading-triad")
    val KNOWLEDGE_FIRST_INVERSION_RULE_ID: RuleId = RuleId("solver.constraint.knowledge.first-inversion")
    val KNOWLEDGE_LEADING_FIRST_INVERSION_RULE_ID: RuleId =
        RuleId("solver.constraint.knowledge.leading-first-inversion")

    const val FIRST_EXERCISE_ID: String = "schoenberg.common-tone-root-position.1"
    const val LEADING_TRIAD_EXERCISE_ID: String = "schoenberg.leading-triad.preparation-resolution"
    const val FIRST_INVERSION_EXERCISE_ID: String = "schoenberg.first-inversion.connections"
    const val SECOND_INVERSION_EXERCISE_ID: String = "schoenberg.second-inversion.preparation-resolution"
    const val SEVENTH_CHORD_EXERCISE_ID: String = "schoenberg.seventh-chord.circle"
    const val INTEGRATED_MAJOR_LEADING_EXERCISE_ID: String = "schoenberg.integrated.major.leading-triad"
    const val INTEGRATED_MAJOR_FIRST_INVERSION_EXERCISE_ID: String = "schoenberg.integrated.major.first-inversion"
    const val INTEGRATED_MAJOR_SECOND_INVERSION_EXERCISE_ID: String = "schoenberg.integrated.major.second-inversion"
    const val INTEGRATED_MAJOR_SEVENTH_CHORD_EXERCISE_ID: String = "schoenberg.integrated.major.seventh-chord"
    const val INTEGRATED_MINOR_LEADING_EXERCISE_ID: String = "schoenberg.integrated.minor.leading-triad"
    const val INTEGRATED_MINOR_FIRST_INVERSION_EXERCISE_ID: String = "schoenberg.integrated.minor.first-inversion"
    const val INTEGRATED_MINOR_SECOND_INVERSION_EXERCISE_ID: String = "schoenberg.integrated.minor.second-inversion"
    const val INTEGRATED_MINOR_SEVENTH_CHORD_EXERCISE_ID: String = "schoenberg.integrated.minor.seventh-chord"
    const val NO_COMMON_TONE_MAJOR_EXERCISE_ID: String = "schoenberg.no-common-tone.major"
    const val NO_COMMON_TONE_MINOR_EXERCISE_ID: String = "schoenberg.no-common-tone.minor"
    const val ROOT_MOTION_AND_REPETITION_EXERCISE_ID: String =
        "schoenberg.root-motion-and-repetition"
    const val CADENCE_EXERCISE_ID: String = "schoenberg.cadence"
    const val FREER_SEVENTH_LEADING_EXERCISE_ID: String =
        "schoenberg.freer-seventh-leading"
    const val SECONDARY_HARMONY_EXERCISE_ID: String =
        "schoenberg.secondary-harmony"
    const val DIMINISHED_SEVENTH_EXERCISE_ID: String =
        "schoenberg.diminished-seventh"
    const val INTEGRATED_DIMINISHED_SEVENTH_EXERCISE_ID: String =
        "schoenberg.integrated.diminished-seventh"
    const val AUGMENTED_SIXTH_EXERCISE_ID: String =
        "schoenberg.augmented-sixth"
    const val INTEGRATED_AUGMENTED_SIXTH_EXERCISE_ID: String =
        "schoenberg.integrated.augmented-sixth"
    const val MINOR_SUBDOMINANT_CONNECTION_EXERCISE_ID: String =
        "schoenberg.minor-subdominant.connections"
    const val NEAPOLITAN_CADENCE_EXERCISE_ID: String =
        "schoenberg.minor-subdominant.neapolitan-cadence"
    const val ANALOGOUS_NEAPOLITAN_EXERCISE_ID: String =
        "schoenberg.minor-subdominant.analogous-neapolitan"
    const val DISTANT_MODULATION_EXERCISE_ID: String =
        SchoenbergDistantModulationChapter.EXERCISE_ID
    const val POLICY_ID: String = "schoenberg-common-tone-root-position"

    val SCHOENBERG_PROFILE: RuleProfile = RuleProfile(
        id = "schoenberg-harmony",
        overrides = mapOf(
            FirstInversionTriadRules.DIMINISHED_TRIAD_FIRST_INVERSION to RuleConfig(enabled = false),
        ),
    )

    val exerciseDescriptors: List<SchoenbergExerciseDescriptor>
        get() = SchoenbergCurriculumCatalog.exerciseDescriptors

    val exerciseRuleIds: List<RuleId> = exerciseDescriptors.map { it.ruleId }

    fun isExerciseRuleId(ruleId: RuleId): Boolean =
        exerciseDescriptors.any { it.ruleId == ruleId }

    fun exerciseIdForRule(ruleId: RuleId): String? =
        exerciseDescriptors.firstOrNull { it.ruleId == ruleId }?.exerciseId

    fun ruleIdForExercise(exerciseId: String): RuleId =
        descriptorForExercise(exerciseId).ruleId

    fun descriptorForExercise(exerciseId: String): SchoenbergExerciseDescriptor =
        exerciseDescriptors.firstOrNull { it.exerciseId == exerciseId }
            ?: error("Unknown Schoenberg exercise $exerciseId")

    fun continuationChordCountRange(exerciseId: String): IntRange =
        descriptorForExercise(exerciseId).continuationChordCountRange

    fun minContinuationChordCount(exerciseId: String): Int =
        continuationChordCountRange(exerciseId).first

    fun progressionSelectionError(
        progression: SchoenbergSymbolicProgression,
        key: Key,
        chordSelectors: List<TargetSelector> = emptyList(),
        selections: Map<String, List<String>> = emptyMap(),
    ): String? {
        val selectedSecondaryHarmony = selections[
            SchoenbergExerciseSelectionKeys.SECONDARY_HARMONY
        ]?.singleOrNull()
        val selectedDiminishedChord = selections[
            SchoenbergExerciseSelectionKeys.DIMINISHED_SEVENTH_CHORD
        ]?.singleOrNull()
        val selectedDiminishedUsage = selections[
            SchoenbergExerciseSelectionKeys.DIMINISHED_SEVENTH_USAGE
        ]?.singleOrNull()
        if (
            selectedSecondaryHarmony != null &&
            !SchoenbergSecondaryDominantChapter.progressionUsesHarmony(
                progression,
                selectedSecondaryHarmony,
                key,
            )
        ) {
            return "所选进行不包含当前选择的具体副属和弦。"
        }
        if (
            selectedDiminishedChord != null &&
            selectedDiminishedUsage != null &&
            !SchoenbergDiminishedSeventhChapter.progressionUsesSelection(
                progression,
                selectedDiminishedChord,
                selectedDiminishedUsage,
            )
        ) {
            return "所选进行不包含当前选择的减七和弦用法。"
        }
        if (!SchoenbergIntegratedTechTree.progressionMatchesSelectors(progression, key, chordSelectors)) {
            return "所选进行不满足全部和弦性质筛选。"
        }
        return null
    }

    fun programForExercise(
        exerciseId: String,
        key: Key,
        continuationChordCount: Int,
        progression: SchoenbergSymbolicProgression? = null,
        searchConfig: SearchConfig = SearchConfig(maxResults = 4, beamWidth = 128),
        cadenceOptions: SchoenbergCadenceOptions = SchoenbergCadenceOptions(),
        sourceModulationKey: com.mecon.theory.ModulationKey? = null,
        selections: Map<String, List<String>> = emptyMap(),
    ): ConstraintProgram =
        SchoenbergChapterRegistry.program(
            SchoenbergProgramRequest(
                exerciseId = exerciseId,
                key = key,
                continuationChordCount = continuationChordCount,
                progression = progression,
                searchConfig = searchConfig,
                cadenceOptions = cadenceOptions,
                sourceModulationKey = sourceModulationKey,
                selections = selections,
            )
        )

    fun enumerateForExercise(
        exerciseId: String,
        key: Key,
        continuationChordCount: Int,
        chordSelectors: List<TargetSelector> = emptyList(),
        cadenceOptions: SchoenbergCadenceOptions = SchoenbergCadenceOptions(),
        selections: Map<String, List<String>> = emptyMap(),
        budget: SchoenbergIntegratedTechTree.EnumerationBudget =
            SchoenbergIntegratedTechTree.EnumerationBudget(),
        shouldContinue: () -> Boolean = { true },
    ): List<SchoenbergSymbolicProgression> =
        SchoenbergChapterRegistry.enumerate(
            SchoenbergEnumerationRequest(
                exerciseId = exerciseId,
                key = key,
                continuationChordCount = continuationChordCount,
                chordSelectors = chordSelectors,
                cadenceOptions = cadenceOptions,
                selections = selections,
                budget = budget,
                shouldContinue = shouldContinue,
            )
        )

    fun scoreTitle(exerciseId: String): String =
        descriptorForExercise(exerciseId).scoreTitle

}
