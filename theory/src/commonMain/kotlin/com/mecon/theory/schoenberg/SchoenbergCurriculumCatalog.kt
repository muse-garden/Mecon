package com.mecon.theory.schoenberg

internal object SchoenbergCurriculumCatalog {
    val exerciseDescriptors: List<SchoenbergExerciseDescriptor> by lazy {
        with(SchoenbergCommonToneExercises) {
        listOf(
        SchoenbergExerciseDescriptor(FIRST_EXERCISE_ID, FIRST_EXERCISE_RULE_ID, MAJOR_BRANCH_RULE_ID),
        SchoenbergExerciseDescriptor(
            LEADING_TRIAD_EXERCISE_ID,
            LEADING_TRIAD_RULE_ID,
            MAJOR_BRANCH_RULE_ID,
            requiresEnumeratedProgression = true,
            enumerationWindowLimit = 3,
            // 章节规则叫 schoenberg.leading-triad.preparation / .resolution，
            // 默认前缀（练习 ruleId 全名）覆盖不到它们。
            ownedRulePrefixes = setOf("schoenberg.leading-triad"),
        ),
        SchoenbergExerciseDescriptor(
            FIRST_INVERSION_EXERCISE_ID,
            FIRST_INVERSION_RULE_ID,
            MAJOR_BRANCH_RULE_ID,
            requiresEnumeratedProgression = true,
            enumerationWindowLimit = 2,
        ),
        SchoenbergExerciseDescriptor(
            SECOND_INVERSION_EXERCISE_ID,
            SECOND_INVERSION_RULE_ID,
            MAJOR_BRANCH_RULE_ID,
            requiresEnumeratedProgression = true,
            enumerationWindowLimit = 3,
            ownedRulePrefixes = setOf("schoenberg.second-inversion"),
        ),
        SchoenbergExerciseDescriptor(
            SEVENTH_CHORD_EXERCISE_ID,
            SEVENTH_CHORD_RULE_ID,
            MAJOR_BRANCH_RULE_ID,
            requiresEnumeratedProgression = true,
            enumerationWindowLimit = 8,
            ownedRulePrefixes = setOf("schoenberg.seventh-chord"),
        ),
        SchoenbergExerciseDescriptor(
            DIMINISHED_SEVENTH_EXERCISE_ID,
            DIMINISHED_SEVENTH_RULE_ID,
            GENERAL_BRANCH_RULE_ID,
            requiresEnumeratedProgression = true,
            enumerationWindowLimit = 2,
            requiredKnowledgeTags = setOf(SchoenbergKnowledgeTag.DIMINISHED_SEVENTH),
            chapterId = "schoenberg.diminished-seventh",
            ownedRulePrefixes = setOf("schoenberg.diminished-seventh"),
        ),
        SchoenbergExerciseDescriptor(
            AUGMENTED_SIXTH_EXERCISE_ID,
            AUGMENTED_SIXTH_RULE_ID,
            GENERAL_BRANCH_RULE_ID,
            requiresEnumeratedProgression = true,
            enumerationWindowLimit = 2,
            requiredKnowledgeTags = setOf(
                SchoenbergKnowledgeTag.VAGRANT_CHORD,
                SchoenbergKnowledgeTag.AUGMENTED_SIXTH,
            ),
            chapterId = "schoenberg.augmented-sixth",
            ownedRulePrefixes = setOf("schoenberg.augmented-sixth"),
        ),
        SchoenbergExerciseDescriptor(
            MINOR_SUBDOMINANT_CONNECTION_EXERCISE_ID,
            MINOR_SUBDOMINANT_CONNECTION_RULE_ID,
            MAJOR_BRANCH_RULE_ID,
            requiresEnumeratedProgression = true,
            enumerationWindowLimit = 3,
            requiredKnowledgeTags = setOf(SchoenbergKnowledgeTag.MINOR_SUBDOMINANT),
            chapterId = "schoenberg.minor-subdominant",
            ownedRulePrefixes = setOf("schoenberg.minor-subdominant"),
        ),
        SchoenbergExerciseDescriptor(
            NEAPOLITAN_CADENCE_EXERCISE_ID,
            NEAPOLITAN_CADENCE_RULE_ID,
            MAJOR_BRANCH_RULE_ID,
            requiresEnumeratedProgression = true,
            enumerationWindowLimit = 4,
            requiredKnowledgeTags = setOf(
                SchoenbergKnowledgeTag.MINOR_SUBDOMINANT,
                SchoenbergKnowledgeTag.NEAPOLITAN,
            ),
            chapterId = "schoenberg.minor-subdominant",
            ownedRulePrefixes = setOf("schoenberg.minor-subdominant"),
        ),
        SchoenbergExerciseDescriptor(
            ANALOGOUS_NEAPOLITAN_EXERCISE_ID,
            ANALOGOUS_NEAPOLITAN_RULE_ID,
            MAJOR_BRANCH_RULE_ID,
            requiresEnumeratedProgression = true,
            enumerationWindowLimit = 3,
            requiredKnowledgeTags = setOf(
                SchoenbergKnowledgeTag.MINOR_SUBDOMINANT,
                SchoenbergKnowledgeTag.NEAPOLITAN,
            ),
            chapterId = "schoenberg.minor-subdominant",
            ownedRulePrefixes = setOf("schoenberg.minor-subdominant"),
        ),
    ) + SchoenbergIntegratedTechTree.exerciseDescriptors + listOf(
        SchoenbergExerciseDescriptor(
            NO_COMMON_TONE_MAJOR_EXERCISE_ID,
            NO_COMMON_TONE_MAJOR_RULE_ID,
            MAJOR_BRANCH_RULE_ID,
            group = SchoenbergExerciseGroup.INTEGRATED,
            requiresEnumeratedProgression = true,
            continuationChordCountRange = 3..8,
            handlerId = SchoenbergExerciseHandlerIds.NO_COMMON_TONE,
        ),
        SchoenbergExerciseDescriptor(
            NO_COMMON_TONE_MINOR_EXERCISE_ID,
            NO_COMMON_TONE_MINOR_RULE_ID,
            MINOR_BRANCH_RULE_ID,
            group = SchoenbergExerciseGroup.INTEGRATED,
            requiresEnumeratedProgression = true,
            continuationChordCountRange = 3..8,
            handlerId = SchoenbergExerciseHandlerIds.NO_COMMON_TONE,
        ),
        SchoenbergExerciseDescriptor(
            ROOT_MOTION_AND_REPETITION_EXERCISE_ID,
            ROOT_MOTION_AND_REPETITION_RULE_ID,
            GENERAL_BRANCH_RULE_ID,
            group = SchoenbergExerciseGroup.INTEGRATED,
            requiresEnumeratedProgression = true,
            continuationChordCountRange = 6..12,
            harmonicTreatmentIds = SchoenbergHarmonicTreatments.integratedDiatonicTreatments,
            ownedRulePrefixes = setOf(
                ROOT_MOTION_AND_REPETITION_RULE_ID.value,
                "schoenberg.root-motion",
                "schoenberg.repetition",
            ),
        ),
        SchoenbergExerciseDescriptor(
            CADENCE_EXERCISE_ID,
            CADENCE_RULE_ID,
            GENERAL_BRANCH_RULE_ID,
            group = SchoenbergExerciseGroup.INTEGRATED,
            requiresEnumeratedProgression = true,
            continuationChordCountRange = 6..12,
            exhaustEnumeratedPrograms = true,
            harmonicTreatmentIds = SchoenbergHarmonicTreatments.integratedDiatonicTreatments,
        ),
        SchoenbergExerciseDescriptor(
            FREER_SEVENTH_LEADING_EXERCISE_ID,
            FREER_SEVENTH_LEADING_RULE_ID,
            GENERAL_BRANCH_RULE_ID,
            group = SchoenbergExerciseGroup.INTEGRATED,
            requiresEnumeratedProgression = true,
            continuationChordCountRange = 8..14,
            exhaustEnumeratedPrograms = true,
            harmonicTreatmentIds = SchoenbergHarmonicTreatments.integratedDiatonicTreatments,
            ownedRulePrefixes = setOf(
                FREER_SEVENTH_LEADING_RULE_ID.value,
                "schoenberg.freer",
            ),
        ),
        SchoenbergExerciseDescriptor(
            SECONDARY_HARMONY_EXERCISE_ID,
            SECONDARY_HARMONY_RULE_ID,
            GENERAL_BRANCH_RULE_ID,
            group = SchoenbergExerciseGroup.INTEGRATED,
            requiresEnumeratedProgression = true,
            continuationChordCountRange = 8..14,
            requiredKnowledgeTags = setOf(SchoenbergKnowledgeTag.SECONDARY_HARMONY),
            preferredKnowledgeTags = setOf(
                SchoenbergKnowledgeTag.LEADING_TRIAD,
                SchoenbergKnowledgeTag.FIRST_INVERSION,
                SchoenbergKnowledgeTag.SECOND_INVERSION,
                SchoenbergKnowledgeTag.SEVENTH_CHORD,
                SchoenbergKnowledgeTag.SECONDARY_HARMONY,
            ),
            chapterId = "schoenberg.secondary-harmony",
            ownedRulePrefixes = setOf("schoenberg.secondary-harmony"),
            harmonicTreatmentIds = SchoenbergHarmonicTreatments.integratedSecondaryTreatments,
            handlerId = SchoenbergExerciseHandlerIds.INTEGRATED,
        ),
        SchoenbergExerciseDescriptor(
            INTEGRATED_DIMINISHED_SEVENTH_EXERCISE_ID,
            INTEGRATED_DIMINISHED_SEVENTH_RULE_ID,
            GENERAL_BRANCH_RULE_ID,
            group = SchoenbergExerciseGroup.INTEGRATED,
            requiresEnumeratedProgression = true,
            continuationChordCountRange = 8..14,
            requiredKnowledgeTags = setOf(SchoenbergKnowledgeTag.DIMINISHED_SEVENTH),
            preferredKnowledgeTags = setOf(
                SchoenbergKnowledgeTag.LEADING_TRIAD,
                SchoenbergKnowledgeTag.FIRST_INVERSION,
                SchoenbergKnowledgeTag.SECOND_INVERSION,
                SchoenbergKnowledgeTag.SEVENTH_CHORD,
                SchoenbergKnowledgeTag.SECONDARY_HARMONY,
                SchoenbergKnowledgeTag.DIMINISHED_SEVENTH,
            ),
            chapterId = "schoenberg.diminished-seventh",
            ownedRulePrefixes = setOf("schoenberg.diminished-seventh"),
            harmonicTreatmentIds = SchoenbergHarmonicTreatments.integratedDiminishedTreatments,
            handlerId = SchoenbergExerciseHandlerIds.INTEGRATED,
        ),
        SchoenbergExerciseDescriptor(
            INTEGRATED_AUGMENTED_SIXTH_EXERCISE_ID,
            INTEGRATED_AUGMENTED_SIXTH_RULE_ID,
            GENERAL_BRANCH_RULE_ID,
            group = SchoenbergExerciseGroup.INTEGRATED,
            requiresEnumeratedProgression = true,
            continuationChordCountRange = 8..14,
            requiredKnowledgeTags = setOf(SchoenbergKnowledgeTag.AUGMENTED_SIXTH),
            preferredKnowledgeTags = setOf(
                SchoenbergKnowledgeTag.LEADING_TRIAD,
                SchoenbergKnowledgeTag.FIRST_INVERSION,
                SchoenbergKnowledgeTag.SECOND_INVERSION,
                SchoenbergKnowledgeTag.SEVENTH_CHORD,
                SchoenbergKnowledgeTag.SECONDARY_HARMONY,
                SchoenbergKnowledgeTag.DIMINISHED_SEVENTH,
                SchoenbergKnowledgeTag.VAGRANT_CHORD,
                SchoenbergKnowledgeTag.AUGMENTED_SIXTH,
            ),
            chapterId = "schoenberg.augmented-sixth",
            ownedRulePrefixes = setOf("schoenberg.augmented-sixth"),
            harmonicTreatmentIds = SchoenbergHarmonicTreatments.integratedFrontierTreatments,
            handlerId = SchoenbergExerciseHandlerIds.INTEGRATED,
        ),
        SchoenbergExerciseDescriptor(
            DISTANT_MODULATION_EXERCISE_ID,
            DISTANT_MODULATION_RULE_ID,
            GENERAL_BRANCH_RULE_ID,
            group = SchoenbergExerciseGroup.INTEGRATED,
            continuationChordCountRange = 1..1,
            chapterId = DISTANT_MODULATION_RULE_ID.value,
            ownedRulePrefixes = setOf(DISTANT_MODULATION_RULE_ID.value),
            allowsChordFilters = false,
        ),
    )

        }.map(::enrich)
    }

    private fun enrich(descriptor: SchoenbergExerciseDescriptor): SchoenbergExerciseDescriptor = descriptor.copy(
        scoreTitle = scoreTitles[descriptor.exerciseId] ?: descriptor.scoreTitle,
        selectionDefinitions = selectionDefinitions[descriptor.exerciseId].orEmpty(),
        supportedOptions = supportedOptions[descriptor.exerciseId].orEmpty(),
    )

    private val selectionDefinitions = mapOf(
        SchoenbergCommonToneExercises.SECONDARY_HARMONY_EXERCISE_ID to listOf(
            SchoenbergExerciseSelectionDefinition(SchoenbergExerciseSelectionKeys.SECONDARY_HARMONY),
        ),
        SchoenbergCommonToneExercises.DIMINISHED_SEVENTH_EXERCISE_ID to listOf(
            SchoenbergExerciseSelectionDefinition(SchoenbergExerciseSelectionKeys.DIMINISHED_SEVENTH_CHORD),
            SchoenbergExerciseSelectionDefinition(SchoenbergExerciseSelectionKeys.DIMINISHED_SEVENTH_USAGE),
        ),
        SchoenbergCommonToneExercises.DISTANT_MODULATION_EXERCISE_ID to listOf(
            SchoenbergExerciseSelectionDefinition(SchoenbergExerciseSelectionKeys.DISTANT_MODULATION_PATH),
            SchoenbergExerciseSelectionDefinition(SchoenbergExerciseSelectionKeys.TONAL_CONFIRMATION),
        ),
    )

    private val supportedOptions = setOf(
        SchoenbergCommonToneExercises.CADENCE_EXERCISE_ID,
        SchoenbergCommonToneExercises.FREER_SEVENTH_LEADING_EXERCISE_ID,
    ).associateWith {
        setOf(
            SchoenbergExerciseOption.DECEPTIVE_CADENCE,
            SchoenbergExerciseOption.CADENTIAL_SIX_FOUR,
        )
    }

    private val scoreTitles = mapOf(
        SchoenbergCommonToneExercises.FIRST_EXERCISE_ID to "Schoenberg common-tone root-position connections",
        SchoenbergCommonToneExercises.LEADING_TRIAD_EXERCISE_ID to "Schoenberg leading-triad preparation and resolution",
        SchoenbergCommonToneExercises.FIRST_INVERSION_EXERCISE_ID to "Schoenberg sixth-chord connections",
        SchoenbergCommonToneExercises.SECOND_INVERSION_EXERCISE_ID to "Schoenberg six-four preparation and resolution",
        SchoenbergCommonToneExercises.SEVENTH_CHORD_EXERCISE_ID to "Schoenberg seventh-chord circle of fifths",
        SchoenbergCommonToneExercises.INTEGRATED_MAJOR_LEADING_EXERCISE_ID to "Schoenberg integrated connections: leading triad",
        SchoenbergCommonToneExercises.INTEGRATED_MAJOR_FIRST_INVERSION_EXERCISE_ID to "Schoenberg integrated connections: leading triad and sixth chords",
        SchoenbergCommonToneExercises.INTEGRATED_MAJOR_SECOND_INVERSION_EXERCISE_ID to "Schoenberg integrated connections: through six-four chords",
        SchoenbergCommonToneExercises.INTEGRATED_MAJOR_SEVENTH_CHORD_EXERCISE_ID to "Schoenberg integrated connections: through seventh chords",
        SchoenbergCommonToneExercises.INTEGRATED_MINOR_LEADING_EXERCISE_ID to "Schoenberg minor connections: diminished triad",
        SchoenbergCommonToneExercises.INTEGRATED_MINOR_FIRST_INVERSION_EXERCISE_ID to "Schoenberg minor connections: diminished triad and sixth chords",
        SchoenbergCommonToneExercises.INTEGRATED_MINOR_SECOND_INVERSION_EXERCISE_ID to "Schoenberg minor connections: through six-four chords",
        SchoenbergCommonToneExercises.INTEGRATED_MINOR_SEVENTH_CHORD_EXERCISE_ID to "Schoenberg minor connections: through seventh chords",
        SchoenbergCommonToneExercises.NO_COMMON_TONE_MAJOR_EXERCISE_ID to "Schoenberg connections without common tones (major)",
        SchoenbergCommonToneExercises.NO_COMMON_TONE_MINOR_EXERCISE_ID to "Schoenberg connections without common tones (minor)",
        SchoenbergCommonToneExercises.ROOT_MOTION_AND_REPETITION_EXERCISE_ID to "Schoenberg root-motion direction and repetition",
        SchoenbergCommonToneExercises.CADENCE_EXERCISE_ID to "Schoenberg cadence",
        SchoenbergCommonToneExercises.FREER_SEVENTH_LEADING_EXERCISE_ID to "Schoenberg freer seventh and leading-chord treatment",
        SchoenbergCommonToneExercises.SECONDARY_HARMONY_EXERCISE_ID to "Schoenberg integrated connections: through secondary harmony",
        SchoenbergCommonToneExercises.DIMINISHED_SEVENTH_EXERCISE_ID to "Schoenberg diminished seventh as a rootless dominant ninth",
        SchoenbergCommonToneExercises.INTEGRATED_DIMINISHED_SEVENTH_EXERCISE_ID to "Schoenberg integrated connections: through diminished sevenths",
        SchoenbergCommonToneExercises.AUGMENTED_SIXTH_EXERCISE_ID to
            "Schoenberg augmented-sixth resolutions at the frontiers of tonality",
        SchoenbergCommonToneExercises.INTEGRATED_AUGMENTED_SIXTH_EXERCISE_ID to
            "Schoenberg integrated connections: at the frontiers of tonality",
        SchoenbergCommonToneExercises.MINOR_SUBDOMINANT_CONNECTION_EXERCISE_ID to
            "Schoenberg minor-subdominant related chord connections",
        SchoenbergCommonToneExercises.NEAPOLITAN_CADENCE_EXERCISE_ID to
            "Schoenberg Neapolitan chord into a cadence",
        SchoenbergCommonToneExercises.ANALOGOUS_NEAPOLITAN_EXERCISE_ID to
            "Schoenberg analogous Neapolitan connections",
        SchoenbergCommonToneExercises.DISTANT_MODULATION_EXERCISE_ID to
            "Schoenberg modulation by three or four key-signature steps",
    )
}
