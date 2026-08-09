package com.mecon.theory.schoenberg

import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.Mode
import com.mecon.theory.RuleId
import com.mecon.theory.harmony.HarmonicTreatmentId
import com.mecon.theory.constraint.SecondaryHarmonyFamily
import com.mecon.theory.constraint.AugmentedSixthFamily
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookTriadPosition

data class SchoenbergSymbolicChord(
    val degree: Int,
    val quality: ChordQuality,
    val position: TextbookTriadPosition = TextbookTriadPosition.ROOT_POSITION,
    val arity: ChordArity = ChordArity.TRIAD,
    val seventhPosition: TextbookSeventhPosition? = null,
    val rootAlteration: Int = 0,
    val appliedToDegree: Int? = null,
    val secondaryFamily: SecondaryHarmonyFamily? = null,
    val augmentedSixthFamily: AugmentedSixthFamily? = null,
    val modalOrigins: Set<Mode> = emptySet(),
    val rootlessDominantNinthChordId: String? = null,
    val rootlessDominantNinthUsageId: String? = null,
    val omittedRootDegree: Int? = null,
    val omittedRootAlteration: Int = 0,
) {
    init {
        require(degree in 1..7)
        require(rootAlteration in -2..2)
        require(secondaryFamily == null || appliedToDegree in 1..7) {
            "Secondary harmony requires a tonicized degree"
        }
        require(augmentedSixthFamily == null || appliedToDegree in 1..7) {
            "Augmented-sixth harmony requires a resolution degree"
        }
        require(secondaryFamily == null || augmentedSixthFamily == null) {
            "A symbolic chord cannot be both secondary harmony and augmented-sixth harmony"
        }
        require(rootlessDominantNinthUsageId == null || rootlessDominantNinthChordId != null)
        require(rootlessDominantNinthUsageId == null || omittedRootDegree in 1..7)
        require(omittedRootAlteration in -2..2)
    }
}

enum class SchoenbergConnectionKind {
    ROOT_COMMON_TONE,
    LEADING_PREPARATION_RESOLUTION,
    ROOT_TO_FIRST_INVERSION,
    FIRST_TO_FIRST_INVERSION,
    SECOND_INVERSION_PREPARATION_RESOLUTION,
    SEVENTH_CIRCLE,
    SECONDARY_FUNCTION,
    DIMINISHED_SEVENTH_FUNCTION,
    MINOR_SUBDOMINANT_CONNECTION,
    NEAPOLITAN_CADENCE,
    ANALOGOUS_NEAPOLITAN,
    AUGMENTED_SIXTH_RESOLUTION,
    ENHARMONIC_AUGMENTED_SIXTH_RESOLUTION,
    INTEGRATED,
}

enum class SchoenbergKnowledgeTag {
    COMMON_TONE,
    LEADING_TRIAD,
    FIRST_INVERSION,
    LEADING_FIRST_INVERSION,
    SECOND_INVERSION,
    SEVENTH_CHORD,
    SECONDARY_HARMONY,
    DIMINISHED_SEVENTH,
    MINOR_SUBDOMINANT,
    NEAPOLITAN,
    VAGRANT_CHORD,
    AUGMENTED_SIXTH,
}

enum class SchoenbergExerciseGroup {
    INDEPENDENT,
    INTEGRATED,
}

object SchoenbergExerciseSelectionKeys {
    const val SECONDARY_HARMONY = "secondary-harmony"
    const val DIMINISHED_SEVENTH_CHORD = "diminished-seventh-chord"
    const val DIMINISHED_SEVENTH_USAGE = "diminished-seventh-usage"
    const val DISTANT_MODULATION_PATH = "distant-modulation-path"
    const val TONAL_CONFIRMATION = "tonal-confirmation"
}

object SchoenbergExerciseHandlerIds {
    const val NO_COMMON_TONE = "schoenberg.handler.no-common-tone"
    const val INTEGRATED = "schoenberg.handler.integrated"
}

data class SchoenbergExerciseSelectionDefinition(
    val key: String,
    val required: Boolean = true,
    val minValues: Int = if (required) 1 else 0,
    val maxValues: Int = 1,
) {
    init {
        require(key.isNotBlank())
        require(minValues >= 0)
        require(maxValues >= minValues)
    }
}

enum class SchoenbergExerciseOption {
    DECEPTIVE_CADENCE,
    CADENTIAL_SIX_FOUR,
}

data class SchoenbergSymbolicProgression(
    val slots: List<SchoenbergSymbolicChord>,
    val kind: SchoenbergConnectionKind = SchoenbergConnectionKind.ROOT_COMMON_TONE,
    val knowledgeTags: Set<SchoenbergKnowledgeTag> = emptySet(),
)

data class SchoenbergExerciseDescriptor(
    val exerciseId: String,
    val ruleId: RuleId,
    val parentId: RuleId,
    val group: SchoenbergExerciseGroup = SchoenbergExerciseGroup.INDEPENDENT,
    val requiresEnumeratedProgression: Boolean = false,
    val enumerationWindowLimit: Int? = null,
    val continuationChordCountRange: IntRange = 1..5,
    val requiredKnowledgeTags: Set<SchoenbergKnowledgeTag> = emptySet(),
    val preferredKnowledgeTags: Set<SchoenbergKnowledgeTag> = requiredKnowledgeTags,
    val exhaustEnumeratedPrograms: Boolean = false,
    val chapterId: String = ruleId.value,
    val ownedRulePrefixes: Set<String> = setOf(ruleId.value),
    val scoreTitle: String = "Schoenberg harmony exercise",
    val selectionDefinitions: List<SchoenbergExerciseSelectionDefinition> = emptyList(),
    val supportedOptions: Set<SchoenbergExerciseOption> = emptySet(),
    val allowsChordFilters: Boolean = group == SchoenbergExerciseGroup.INTEGRATED,
    val harmonicTreatmentIds: Set<HarmonicTreatmentId> = emptySet(),
    val handlerId: String = exerciseId,
) {
    fun selectionValidationError(
        selections: Map<String, List<String>>,
        hasChordFilters: Boolean,
        enabledOptions: Set<SchoenbergExerciseOption> = emptySet(),
    ): String? {
        val definitionsByKey = selectionDefinitions.associateBy { it.key }
        val unknownSelections = selections
            .filterValues { it.isNotEmpty() }
            .keys - definitionsByKey.keys
        if (unknownSelections.isNotEmpty()) {
            return "当前练习不接受选择：${unknownSelections.sorted().joinToString()}。"
        }
        selectionDefinitions.forEach { definition ->
            val valueCount = selections[definition.key].orEmpty().distinct().size
            if (valueCount !in definition.minValues..definition.maxValues) {
                return "选择 ${definition.key} 需要 ${definition.minValues}..${definition.maxValues} 个值。"
            }
        }
        if (hasChordFilters && !allowsChordFilters) {
            return "当前练习不接受和弦筛选。"
        }
        val unsupportedOptions = enabledOptions - supportedOptions
        if (unsupportedOptions.isNotEmpty()) {
            return "当前练习不支持选项：${unsupportedOptions.sortedBy { it.name }.joinToString { it.name }}。"
        }
        return null
    }
}
