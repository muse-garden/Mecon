package com.mecon.theory.schoenberg

import com.mecon.theory.harmony.HarmonicTreatmentId

internal data class SchoenbergIntegratedStageSpec(
    val treatmentIds: Set<HarmonicTreatmentId>,
) {
    private val includedTreatmentIds: Set<HarmonicTreatmentId> =
        SchoenbergHarmonicTreatments.registry.resolve(treatmentIds).includedTreatmentIds

    fun includes(treatmentId: HarmonicTreatmentId): Boolean =
        treatmentId in includedTreatmentIds
}

internal object SchoenbergIntegratedStages {
    val exerciseDescriptors: List<SchoenbergExerciseDescriptor> by lazy {
        listOf(
            descriptor(
                exerciseId = SchoenbergCommonToneExercises.INTEGRATED_MAJOR_LEADING_EXERCISE_ID,
                ruleId = SchoenbergCommonToneExercises.INTEGRATED_MAJOR_LEADING_RULE_ID,
                parentId = SchoenbergCommonToneExercises.MAJOR_BRANCH_RULE_ID,
                continuationRange = 4..6,
                preferredTags = setOf(SchoenbergKnowledgeTag.LEADING_TRIAD),
                treatments = setOf(SchoenbergHarmonicTreatments.LEADING_TRIAD),
            ),
            descriptor(
                exerciseId = SchoenbergCommonToneExercises.INTEGRATED_MAJOR_FIRST_INVERSION_EXERCISE_ID,
                ruleId = SchoenbergCommonToneExercises.INTEGRATED_MAJOR_FIRST_INVERSION_RULE_ID,
                parentId = SchoenbergCommonToneExercises.MAJOR_BRANCH_RULE_ID,
                continuationRange = 4..12,
                preferredTags = setOf(
                    SchoenbergKnowledgeTag.LEADING_TRIAD,
                    SchoenbergKnowledgeTag.FIRST_INVERSION,
                ),
                treatments = setOf(
                    SchoenbergHarmonicTreatments.LEADING_TRIAD,
                    SchoenbergHarmonicTreatments.FIRST_INVERSION,
                ),
            ),
            descriptor(
                exerciseId = SchoenbergCommonToneExercises.INTEGRATED_MAJOR_SECOND_INVERSION_EXERCISE_ID,
                ruleId = SchoenbergCommonToneExercises.INTEGRATED_MAJOR_SECOND_INVERSION_RULE_ID,
                parentId = SchoenbergCommonToneExercises.MAJOR_BRANCH_RULE_ID,
                continuationRange = 5..12,
                preferredTags = setOf(
                    SchoenbergKnowledgeTag.LEADING_TRIAD,
                    SchoenbergKnowledgeTag.FIRST_INVERSION,
                    SchoenbergKnowledgeTag.SECOND_INVERSION,
                ),
                treatments = setOf(
                    SchoenbergHarmonicTreatments.LEADING_TRIAD,
                    SchoenbergHarmonicTreatments.FIRST_INVERSION,
                    SchoenbergHarmonicTreatments.SECOND_INVERSION,
                ),
            ),
            descriptor(
                exerciseId = SchoenbergCommonToneExercises.INTEGRATED_MAJOR_SEVENTH_CHORD_EXERCISE_ID,
                ruleId = SchoenbergCommonToneExercises.INTEGRATED_MAJOR_SEVENTH_CHORD_RULE_ID,
                parentId = SchoenbergCommonToneExercises.MAJOR_BRANCH_RULE_ID,
                continuationRange = 7..12,
                preferredTags = setOf(
                    SchoenbergKnowledgeTag.LEADING_TRIAD,
                    SchoenbergKnowledgeTag.FIRST_INVERSION,
                    SchoenbergKnowledgeTag.SECOND_INVERSION,
                    SchoenbergKnowledgeTag.SEVENTH_CHORD,
                ),
                treatments = SchoenbergHarmonicTreatments.integratedDiatonicTreatments,
            ),
            descriptor(
                exerciseId = SchoenbergCommonToneExercises.INTEGRATED_MINOR_LEADING_EXERCISE_ID,
                ruleId = SchoenbergCommonToneExercises.INTEGRATED_MINOR_LEADING_RULE_ID,
                parentId = SchoenbergCommonToneExercises.MINOR_BRANCH_RULE_ID,
                continuationRange = 4..6,
                preferredTags = setOf(SchoenbergKnowledgeTag.LEADING_TRIAD),
                treatments = setOf(SchoenbergHarmonicTreatments.LEADING_TRIAD),
            ),
            descriptor(
                exerciseId = SchoenbergCommonToneExercises.INTEGRATED_MINOR_FIRST_INVERSION_EXERCISE_ID,
                ruleId = SchoenbergCommonToneExercises.INTEGRATED_MINOR_FIRST_INVERSION_RULE_ID,
                parentId = SchoenbergCommonToneExercises.MINOR_BRANCH_RULE_ID,
                continuationRange = 4..12,
                preferredTags = setOf(
                    SchoenbergKnowledgeTag.LEADING_TRIAD,
                    SchoenbergKnowledgeTag.FIRST_INVERSION,
                ),
                treatments = setOf(
                    SchoenbergHarmonicTreatments.LEADING_TRIAD,
                    SchoenbergHarmonicTreatments.FIRST_INVERSION,
                ),
            ),
            descriptor(
                exerciseId = SchoenbergCommonToneExercises.INTEGRATED_MINOR_SECOND_INVERSION_EXERCISE_ID,
                ruleId = SchoenbergCommonToneExercises.INTEGRATED_MINOR_SECOND_INVERSION_RULE_ID,
                parentId = SchoenbergCommonToneExercises.MINOR_BRANCH_RULE_ID,
                continuationRange = 5..12,
                preferredTags = setOf(
                    SchoenbergKnowledgeTag.LEADING_TRIAD,
                    SchoenbergKnowledgeTag.FIRST_INVERSION,
                    SchoenbergKnowledgeTag.SECOND_INVERSION,
                ),
                treatments = setOf(
                    SchoenbergHarmonicTreatments.LEADING_TRIAD,
                    SchoenbergHarmonicTreatments.FIRST_INVERSION,
                    SchoenbergHarmonicTreatments.SECOND_INVERSION,
                ),
            ),
            descriptor(
                exerciseId = SchoenbergCommonToneExercises.INTEGRATED_MINOR_SEVENTH_CHORD_EXERCISE_ID,
                ruleId = SchoenbergCommonToneExercises.INTEGRATED_MINOR_SEVENTH_CHORD_RULE_ID,
                parentId = SchoenbergCommonToneExercises.MINOR_BRANCH_RULE_ID,
                continuationRange = 7..12,
                preferredTags = setOf(
                    SchoenbergKnowledgeTag.LEADING_TRIAD,
                    SchoenbergKnowledgeTag.FIRST_INVERSION,
                    SchoenbergKnowledgeTag.SECOND_INVERSION,
                    SchoenbergKnowledgeTag.SEVENTH_CHORD,
                ),
                treatments = SchoenbergHarmonicTreatments.integratedDiatonicTreatments,
            ),
        )
    }

    private fun descriptor(
        exerciseId: String,
        ruleId: com.mecon.theory.RuleId,
        parentId: com.mecon.theory.RuleId,
        continuationRange: IntRange,
        preferredTags: Set<SchoenbergKnowledgeTag>,
        treatments: Set<HarmonicTreatmentId>,
    ) = SchoenbergExerciseDescriptor(
        exerciseId = exerciseId,
        ruleId = ruleId,
        parentId = parentId,
        group = SchoenbergExerciseGroup.INTEGRATED,
        requiresEnumeratedProgression = true,
        continuationChordCountRange = continuationRange,
        requiredKnowledgeTags = setOf(SchoenbergKnowledgeTag.LEADING_TRIAD),
        preferredKnowledgeTags = preferredTags,
        harmonicTreatmentIds = treatments,
        handlerId = SchoenbergExerciseHandlerIds.INTEGRATED,
    )
}
