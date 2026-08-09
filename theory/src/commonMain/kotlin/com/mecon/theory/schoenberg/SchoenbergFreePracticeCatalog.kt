package com.mecon.theory.schoenberg

import com.mecon.api.primitive.Fraction
import com.mecon.theory.FunctionalChordSymbolFormatter
import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.ModulationCircleOfFifths
import com.mecon.theory.TonalContext
import com.mecon.theory.harmony.FunctionalChordSymbol
import com.mecon.theory.harmony.ChordCatalog
import com.mecon.theory.harmony.ChordSelectionCatalog
import com.mecon.theory.harmony.ChordSelectionChoice
import com.mecon.theory.harmony.ChordCatalogChapterDiscovery
import com.mecon.theory.constraint.AugmentedSixthFamily
import com.mecon.theory.constraint.AugmentedSixthVocabulary
import com.mecon.theory.constraint.InterpretedChordTarget
import com.mecon.theory.constraint.SecondaryHarmonyFamily
import com.mecon.theory.freepractice.WorkspaceChordChoice
import com.mecon.theory.freepractice.toWorkspaceChordChoice
import com.mecon.theory.harmony.ChordInterpretationRef
import com.mecon.theory.harmony.chordSelectionTonalContext
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookTriadPosition
import com.mecon.theory.textbook.DominantSeventhRules

data class SchoenbergFreePracticeChordFocus(
    val key: ModulationKey,
    val chordChoice: WorkspaceChordChoice,
)

data class SchoenbergFreePracticeDiscoveryRequest(
    val initialKey: ModulationKey,
    val activeKeys: List<ModulationKey>,
    val maxVariantsPerDefinition: Int = 8,
    val focus: SchoenbergFreePracticeChordFocus? = null,
    /** Builds a reusable catalog for this key without coupling enumeration to one chord focus. */
    val catalogKey: ModulationKey? = null,
    /** Skips source-scoped related material so the visible default list can be published first. */
    val onlyAvailableByDefault: Boolean = false,
    /** Includes target-key readings and source-scoped vagrant-chord connections without a focus. */
    val includeOffKey: Boolean = false,
    /** Target tonalities enumerated when [includeOffKey] is enabled. */
    val targetKeys: List<ModulationKey> = emptyList(),
) {
    init {
        require(maxVariantsPerDefinition > 0)
    }
}

class SchoenbergFreePracticeCatalogIndex internal constructor(
    private val contribution: SchoenbergFreePracticeContribution,
    private val includeOffKey: Boolean,
) {
    fun discover(
        focus: SchoenbergFreePracticeChordFocus? = null,
    ): SchoenbergFreePracticeContribution {
        val localCatalog by lazy {
            focus?.let { ChordSelectionCatalog.choices(it.key) }.orEmpty()
        }
        return contribution.copy(
            idioms = contribution.idioms
            .distinctBy { it.id }
            .filter { focus != null || it.availableByDefault || includeOffKey }
            .mapNotNull { definition ->
                if (focus == null) {
                    definition
                } else {
                    definition.variants
                        .mapNotNull { it.alignedToFocus(focus, allowEnharmonic = includeOffKey) }
                        .flatMap { variant ->
                            val viewed = variant.withGermanDominantSeventhReading(focus) {
                                localCatalog
                            }
                            listOfNotNull(
                                viewed.withLocalAlteredReadings(focus) { localCatalog },
                                viewed,
                            )
                        }
                        .takeIf { it.isNotEmpty() }
                        ?.let(definition::withFocusedVariants)
                }
            }
            .filter { it.variants.isNotEmpty() },
            pivotRecipes = contribution.pivotRecipes.distinctBy {
                listOf(
                    it.sourceExerciseId,
                    it.sourceKey.fifths,
                    it.sourceKey.mode,
                    it.targetKey.fifths,
                    it.targetKey.mode,
                    it.pitchClasses.sorted(),
                )
            },
        )
    }
}

data class SchoenbergFreePracticeIdiomVariant(
    val id: String,
    val title: String,
    val chordIdentities: List<String>,
    val durations: List<Fraction>,
    val suggestedKey: ModulationKey? = null,
    /** Signed shortest circle-of-fifths distance from the practice key to [suggestedKey]. */
    val targetKeyDistance: Int = 0,
    val parameters: Map<String, String> = emptyMap(),
    val chordChoices: List<WorkspaceChordChoice> = emptyList(),
    val anchorStepIndex: Int = 0,
    /** Step indices whose inversion is structurally required by the source chapter rules. */
    val fixedInversionStepIndices: Set<Int> = emptySet(),
    /** Chromatic steps whose catalog bass is customary but remains editable after insertion. */
    val customaryBassStepIndices: Set<Int> = emptySet(),
    /** Cadential dominant steps that should avoid second inversion without being locked. */
    val avoidSecondInversionStepIndices: Set<Int> = emptySet(),
) {
    init {
        require(id.isNotBlank())
        require(title.isNotBlank())
        require(chordIdentities.isNotEmpty())
        require(chordIdentities.size == durations.size)
        require(durations.all { it.isPositive })
        require(chordChoices.isEmpty() || chordChoices.size == chordIdentities.size)
        require(anchorStepIndex in chordIdentities.indices)
        require(fixedInversionStepIndices.all(chordIdentities.indices::contains))
        require(customaryBassStepIndices.all(chordIdentities.indices::contains))
        require(avoidSecondInversionStepIndices.all(chordIdentities.indices::contains))
    }
}

data class SchoenbergFreePracticeIdiomDefinition(
    val id: String,
    val title: String,
    val sourceExerciseId: String,
    val sourceChapterId: String,
    val variants: List<SchoenbergFreePracticeIdiomVariant>,
    val availableByDefault: Boolean = true,
) {
    init {
        require(id.isNotBlank())
        require(title.isNotBlank())
        require(sourceExerciseId.isNotBlank())
        require(sourceChapterId.isNotBlank())
        require(variants.isNotEmpty())
    }
}

private fun SchoenbergFreePracticeIdiomDefinition.withFocusedVariants(
    focusedVariants: List<SchoenbergFreePracticeIdiomVariant>,
): SchoenbergFreePracticeIdiomDefinition = copy(
    title = if (
        focusedVariants.any { variant ->
            variant.parameters[VIEWED_AS_DOMINANT_SEVENTH_PARAMETER] == "true"
        }
    ) {
        "作为属七的终止式"
    } else {
        title
    },
    variants = focusedVariants,
)

data class SchoenbergFreePracticePivotRecipe(
    val sourceExerciseId: String,
    val sourceChapterId: String,
    val sourceKey: ModulationKey,
    val targetKey: ModulationKey,
    val pitchClasses: Set<Int>,
    val sourceReading: String,
    val targetReading: String,
    val definition: String,
)

data class SchoenbergFreePracticeContribution(
    val idioms: List<SchoenbergFreePracticeIdiomDefinition> = emptyList(),
    val pivotRecipes: List<SchoenbergFreePracticePivotRecipe> = emptyList(),
) {
    operator fun plus(other: SchoenbergFreePracticeContribution) =
        SchoenbergFreePracticeContribution(
            idioms = idioms + other.idioms,
            pivotRecipes = pivotRecipes + other.pivotRecipes,
        )
}

fun SchoenbergFreePracticeContribution.forGermanDominantSeventhFocus(
    focus: SchoenbergFreePracticeChordFocus,
): SchoenbergFreePracticeContribution = copy(
    idioms = idioms.mapNotNull { definition ->
        definition.variants.filter { variant ->
            (
                variant.suggestedKey == focus.key &&
                    VIEWED_AS_SOURCE_FIFTHS_PARAMETER !in variant.parameters
                ) ||
                variant.parameters[VIEWED_AS_DOMINANT_SEVENTH_PARAMETER] == "true"
        }.takeIf { it.isNotEmpty() }?.let(definition::withFocusedVariants)
    },
)

internal const val VIEWED_AS_DOMINANT_SEVENTH_PARAMETER =
    "free-practice.viewed-as.dominant-seventh"
internal const val VIEWED_AS_SOURCE_FIFTHS_PARAMETER = "free-practice.viewed-as.source-fifths"
internal const val VIEWED_AS_SOURCE_MODE_PARAMETER = "free-practice.viewed-as.source-mode"
internal const val VIEWED_AS_RULE_PROJECTION_PARAMETER = "free-practice.viewed-as.rule-projection"
internal const val VIEWED_AS_SOURCE_INTERPRETATIONS_PARAMETER =
    "free-practice.viewed-as.source-interpretations"
internal const val TEACHING_SOURCE_PARAMETER = "free-practice.teaching-source"

/**
 * Free practice asks the chapter registry for teaching material. It never carries its own list of
 * cadences, modulation paths, or pivot recipes.
 */
object SchoenbergFreePracticeCatalog {
    fun buildIndex(
        request: SchoenbergFreePracticeDiscoveryRequest,
    ): SchoenbergFreePracticeCatalogIndex {
        require(request.focus == null) { "A catalog index must not be tied to one chord focus" }
        val targetKeys = if (request.includeOffKey) {
            request.targetKeys.ifEmpty { allDisplayKeys() }.distinct()
        } else {
            listOf(request.practiceKey)
        }
        val enumerationKeys = if (request.includeOffKey) {
            targetKeys.map(ModulationKey::mode).distinct().map { mode ->
                ModulationKey(request.initialKey.fifths, mode)
            }
        } else {
            listOf(request.practiceKey)
        }
        val enumerated = enumerationKeys.flatMapIndexed { keyIndex, enumerationKey ->
            val targetRequest = request.copy(catalogKey = enumerationKey, focus = null)
            SchoenbergChapterRegistry.exerciseRegistrations.mapNotNull { registration ->
                if (
                    keyIndex > 0 && registration.definition.exerciseId ==
                    SchoenbergCommonToneExercises.DISTANT_MODULATION_EXERCISE_ID
                ) {
                    return@mapNotNull null
                }
                registration.handler.freePracticeContribution?.invoke(targetRequest, registration.definition)
            }
        }.fold(SchoenbergFreePracticeContribution(), SchoenbergFreePracticeContribution::plus)
        val contribution = (if (request.includeOffKey) {
            enumerated.projectToTargetKeys(
                initialKey = request.initialKey,
                enumerationKeys = enumerationKeys,
                targetKeys = targetKeys,
            )
        } else {
            enumerated
        })
            .withTargetDistances(request.initialKey)
            .mergeDefinitions()
        return SchoenbergFreePracticeCatalogIndex(contribution, request.includeOffKey)
    }

    fun discover(
        request: SchoenbergFreePracticeDiscoveryRequest,
    ): SchoenbergFreePracticeContribution {
        val focus = request.focus
        val indexRequest = request.copy(
            focus = null,
            catalogKey = focus?.key ?: request.catalogKey,
        )
        return buildIndex(indexRequest).discover(focus)
    }
}

private fun SchoenbergFreePracticeIdiomVariant.alignedToFocus(
    focus: SchoenbergFreePracticeChordFocus,
    allowEnharmonic: Boolean,
): SchoenbergFreePracticeIdiomVariant? {
    val focusedRef = focus.chordChoice.pinnedInterpretationRef
    val anchorChoice = chordChoices.getOrNull(anchorStepIndex) ?: return null
    val augmentedSixthReading = anchorChoice.pinnedInterpretationRef.isAugmentedSixthReading() ||
        focusedRef.isAugmentedSixthReading()
    val matches = if (
        (allowEnharmonic || augmentedSixthReading) &&
        anchorChoice.pitchClasses == focus.chordChoice.pitchClasses
    ) {
        true
    } else if (focusedRef != null) {
        anchorChoice.pinnedInterpretationRef == focusedRef
    } else {
        anchorChoice.pitchClasses == focus.chordChoice.pitchClasses
    }
    return takeIf { matches }
}

fun SchoenbergFreePracticeChordFocus.isGermanAugmentedSixth(): Boolean {
    chordChoice.pinnedInterpretationRef?.let { pinned ->
        return pinned.interpretationId.value.startsWith("augmented-sixth.german.")
    }
    if (chordChoice.origin?.categoryId?.value !in AUGMENTED_SIXTH_CATEGORY_IDS) return false
    val pitchClasses = chordChoice.pitchClasses.toSet()
    return AugmentedSixthVocabulary.constructedChords(TonalContext.fromKey(key.key)).any { chord ->
        chord.interpretation.id.value.startsWith("augmented-sixth.german.") &&
            chord.spelledTones.mapTo(linkedSetOf()) { it.pitchClass.value } == pitchClasses
    }
}

private val AUGMENTED_SIXTH_CATEGORY_IDS = setOf(
    "augmented-sixths",
    "dominant-augmented-sixths",
)

private fun ChordInterpretationRef?.isAugmentedSixthReading(): Boolean =
    this?.interpretationId?.value?.startsWith("augmented-sixth.") == true

fun allFreePracticeTargetKeys(): List<ModulationKey> = allDisplayKeys()

private fun allDisplayKeys(): List<ModulationKey> =
    KeySignatureMode.entries.flatMap { mode ->
        (-7..7).map { fifths -> ModulationKey(fifths, mode) }
    }

private fun SchoenbergFreePracticeContribution.withTargetDistances(
    initialKey: ModulationKey,
): SchoenbergFreePracticeContribution = copy(
    idioms = idioms.map { definition ->
        definition.copy(
            variants = definition.variants.map { variant ->
                variant.copy(
                    targetKeyDistance = variant.suggestedKey?.let { target ->
                        ModulationCircleOfFifths.signedDistance(initialKey, target)
                    } ?: 0,
                )
            },
        )
    },
)

private fun SchoenbergFreePracticeContribution.mergeDefinitions(): SchoenbergFreePracticeContribution = copy(
    idioms = idioms.groupBy(SchoenbergFreePracticeIdiomDefinition::id).map { (_, definitions) ->
        val first = definitions.first()
        val variants = definitions.flatMap(SchoenbergFreePracticeIdiomDefinition::variants)
            .distinctBy { variant ->
                listOf(
                    variant.suggestedKey,
                    variant.chordChoices.map { choice ->
                        choice.pinnedInterpretationRef to choice.bassPitchClass
                    },
                    variant.durations,
                )
            }
            .groupBy(SchoenbergFreePracticeIdiomVariant::id)
            .flatMap { (_, sameId) ->
                if (sameId.size == 1) sameId else sameId.map { variant ->
                    val key = requireNotNull(variant.suggestedKey)
                    variant.copy(id = "${variant.id}.target-${key.fifths}-${key.mode.name.lowercase()}")
                }
            }
        first.copy(
            variants = variants,
            availableByDefault = definitions.any(SchoenbergFreePracticeIdiomDefinition::availableByDefault),
        )
    },
)

private fun SchoenbergFreePracticeContribution.projectToTargetKeys(
    initialKey: ModulationKey,
    enumerationKeys: List<ModulationKey>,
    targetKeys: List<ModulationKey>,
): SchoenbergFreePracticeContribution {
    val enumerationKeyByMode = enumerationKeys.associateBy(ModulationKey::mode)
    val providers = ChordCatalogChapterDiscovery.discover()
    val targetCatalogs = targetKeys.associateWith { key ->
        SoundingChoiceIndex.of(ChordSelectionCatalog.choices(key, providers))
    }
    return copy(
        idioms = idioms.mapNotNull { definition ->
            val projected = definition.variants.flatMap { variant ->
                val sourceKey = variant.suggestedKey?.let { enumerationKeyByMode[it.mode] }
                    ?: enumerationKeyByMode[initialKey.mode]
                    ?: return@flatMap emptyList()
                targetKeys.asSequence()
                    .filter { it.mode == sourceKey.mode }
                    .mapNotNull { targetKey ->
                        variant.projectToTargetKey(
                            sourceKey = sourceKey,
                            targetKey = targetKey,
                            catalog = targetCatalogs.getValue(targetKey),
                        )
                            ?.takeUnless {
                                targetKey != initialKey && it.usesSecondaryHarmony()
                            }
                    }
                    .toList()
            }
            projected.takeIf { it.isNotEmpty() }?.let { definition.copy(variants = it) }
        },
    )
}

private fun SchoenbergFreePracticeIdiomVariant.usesSecondaryHarmony(): Boolean =
    chordChoices.any { choice ->
        val id = choice.pinnedInterpretationRef?.interpretationId?.value.orEmpty()
        id.startsWith("secondary.secondary_dominant.") ||
            id.startsWith("secondary.secondary_leading.")
    }

/**
 * Groups catalog choices by their sounding pitch-class set.
 *
 * A chord is a 12-bit mask on the pitch-class circle and a transposition is a rotation of that
 * mask, so every target key can look its projected chords up in constant time instead of scanning
 * the whole catalog once per chord per key. Grouping preserves catalog order, so the candidate
 * list a caller sees is exactly the one a linear scan produced.
 */
internal class SoundingChoiceIndex private constructor(
    private val byMask: Map<Int, List<ChordSelectionChoice>>,
) {
    fun matching(mask: Int): List<ChordSelectionChoice> = byMask[mask].orEmpty()

    companion object {
        fun of(choices: List<ChordSelectionChoice>): SoundingChoiceIndex =
            SoundingChoiceIndex(choices.groupBy { it.pitchClasses.pitchClassMask() })
    }
}

internal fun Iterable<Int>.pitchClassMask(): Int = fold(0) { mask, pitchClass ->
    mask or (1 shl pitchClass.mod(12))
}

internal fun Int.rotatedBySemitones(semitones: Int): Int {
    val shift = semitones.mod(12)
    if (shift == 0) return this
    return ((this shl shift) or (this ushr (12 - shift))) and PITCH_CLASS_MASK_BITS
}

private const val PITCH_CLASS_MASK_BITS = 0xFFF

private fun SchoenbergFreePracticeIdiomVariant.projectToTargetKey(
    sourceKey: ModulationKey,
    targetKey: ModulationKey,
    catalog: SoundingChoiceIndex,
): SchoenbergFreePracticeIdiomVariant? {
    if (chordChoices.isEmpty()) return null
    val semitones = (
        targetKey.tonicSpelling().pitchClass.value - sourceKey.tonicSpelling().pitchClass.value
    ).mod(12)
    val projectedChoices = chordChoices.mapIndexed { index, sourceChoice ->
        val pitchClasses = sourceChoice.pitchClasses.map { (it + semitones).mod(12) }.toSet()
        val catalogIdentity = chordIdentities[index].catalogIdentity()
        val matches = catalog.matching(
            sourceChoice.pitchClasses.pitchClassMask().rotatedBySemitones(semitones)
        )
        val reading = matches.asSequence().mapNotNull { choice ->
            choice.interpretationSymbols.indexOf(catalogIdentity)
                .takeIf { it >= 0 }
                ?.let { readingIndex -> choice to choice.interpretationRefs[readingIndex] }
        }.firstOrNull() ?: matches.firstOrNull { it.functionalSymbol == catalogIdentity }
            ?.let { choice -> choice to choice.interpretationRefs.first() }
            ?: return null
        WorkspaceChordChoice.of(
            pitchClasses = pitchClasses,
            origin = reading.first.origin,
            pinnedInterpretationRef = reading.second,
            bassPitchClass = sourceChoice.bassPitchClass?.let { (it + semitones).mod(12) },
        )
    }
    return copy(
        id = "$id.target-${targetKey.fifths}-${targetKey.mode.name.lowercase()}",
        suggestedKey = targetKey,
        chordChoices = projectedChoices,
    )
}

private fun String.catalogIdentity(): String {
    if (startsWith("It+6") || startsWith("Ger+6") || startsWith("Fr+6") || startsWith("ø+6")) {
        return this
    }
    val slash = indexOf('/')
    val head = if (slash >= 0) substring(0, slash) else this
    val suffix = if (slash >= 0) substring(slash) else ""
    val normalized = when {
        head.endsWith("64") -> head.dropLast(2)
        head.endsWith("65") -> head.dropLast(2) + "7"
        head.endsWith("43") -> head.dropLast(2) + "7"
        head.endsWith("42") -> head.dropLast(2) + "7"
        head.endsWith("6") -> head.dropLast(1)
        else -> head
    }
    return normalized + suffix
}

private fun SchoenbergFreePracticeIdiomVariant.withGermanDominantSeventhReading(
    focus: SchoenbergFreePracticeChordFocus,
    localCatalog: () -> List<ChordSelectionChoice>,
): SchoenbergFreePracticeIdiomVariant {
    if (
        !focus.isGermanAugmentedSixth() ||
        chordIdentities.getOrNull(anchorStepIndex)?.catalogIdentity() != "V7" ||
        chordChoices.getOrNull(anchorStepIndex)?.pinnedInterpretationRef ==
        focus.chordChoice.pinnedInterpretationRef
    ) {
        return this
    }
    val choices = localCatalog()
    val identities = chordChoices.mapIndexed { index, choice ->
        if (index == anchorStepIndex) {
            choices.symbolFor(choice.pitchClasses, focus.chordChoice.pinnedInterpretationRef)
                ?: "Ger+6"
        } else {
            val original = chordIdentities[index]
            choices.preferredLocalSymbol(choice.pitchClasses)
                ?.withPositionFrom(original)
                ?: original
        }
    }
    return copy(
        title = identities.joinToString(" – "),
        chordIdentities = identities,
        parameters = parameters + (VIEWED_AS_DOMINANT_SEVENTH_PARAMETER to "true"),
        fixedInversionStepIndices = fixedInversionStepIndices - (anchorStepIndex + 1),
        customaryBassStepIndices = customaryBassStepIndices + anchorStepIndex,
        avoidSecondInversionStepIndices = avoidSecondInversionStepIndices - anchorStepIndex,
    )
}

private data class LocalChordReading(
    val choice: ChordSelectionChoice,
    val interpretationRef: ChordInterpretationRef,
    val symbol: String,
)

private fun SchoenbergFreePracticeIdiomVariant.withLocalAlteredReadings(
    focus: SchoenbergFreePracticeChordFocus,
    localCatalog: () -> List<ChordSelectionChoice>,
): SchoenbergFreePracticeIdiomVariant? {
    val sourceKey = suggestedKey ?: return null
    if (
        sourceKey == focus.key ||
        chordChoices.getOrNull(anchorStepIndex)?.pitchClasses?.toSet() !=
        focus.chordChoice.pitchClasses.toSet()
    ) return null
    val catalog = localCatalog()
    val focusReading = catalog.readingForFocus(focus) ?: return null
    val readings = chordChoices.mapIndexed { index, choice ->
        if (index == anchorStepIndex) focusReading
        else catalog.preferredLocalReading(choice.pitchClasses) ?: return null
    }
    val identities = readings.mapIndexed { index, reading ->
        reading.symbol.withPositionFrom(chordIdentities[index])
    }
    val remappedChoices = chordChoices.zip(readings) { source, reading ->
        WorkspaceChordChoice.of(
            pitchClasses = source.pitchClasses,
            origin = reading.choice.origin,
            pinnedInterpretationRef = reading.interpretationRef,
            bassPitchClass = source.bassPitchClass,
        )
    }
    val sourceInterpretationIds = chordChoices.map { choice ->
        choice.pinnedInterpretationRef?.interpretationId?.value ?: return null
    }
    return copy(
        id = "$id.viewed-as-${focus.key.fifths}-${focus.key.mode.name.lowercase()}",
        title = identities.joinToString(" – "),
        chordIdentities = identities,
        suggestedKey = focus.key,
        targetKeyDistance = 0,
        parameters = parameters + mapOf(
            VIEWED_AS_SOURCE_FIFTHS_PARAMETER to sourceKey.fifths.toString(),
            VIEWED_AS_SOURCE_MODE_PARAMETER to sourceKey.mode.name,
            VIEWED_AS_RULE_PROJECTION_PARAMETER to "true",
            VIEWED_AS_SOURCE_INTERPRETATIONS_PARAMETER to sourceInterpretationIds.joinToString("|"),
        ),
        chordChoices = remappedChoices,
    )
}

private fun List<ChordSelectionChoice>.readingForFocus(
    focus: SchoenbergFreePracticeChordFocus,
): LocalChordReading? {
    val pitchClasses = focus.chordChoice.pitchClasses.toSet()
    focus.chordChoice.pinnedInterpretationRef?.let { pinned ->
        return asSequence().filter { it.pitchClasses == pitchClasses }
            .mapNotNull { it.reading(pinned) }
            .firstOrNull()
    }
    val origin = focus.chordChoice.origin ?: return null
    return asSequence()
        .filter { it.pitchClasses == pitchClasses && it.origin == origin }
        .mapNotNull { choice ->
            val index = choice.interpretationSymbols.indexOf(choice.functionalSymbol)
                .takeIf { it >= 0 } ?: 0
            choice.interpretationRefs.getOrNull(index)?.let { ref ->
                LocalChordReading(choice, ref, choice.interpretationSymbols[index])
            }
        }
        .firstOrNull()
}

private fun List<ChordSelectionChoice>.preferredLocalReading(
    pitchClasses: Collection<Int>,
): LocalChordReading? = asSequence()
    .filter { it.pitchClasses == pitchClasses.toSet() }
    .flatMap { choice ->
        choice.interpretationRefs.indices.asSequence().map { index ->
            LocalChordReading(
                choice,
                choice.interpretationRefs[index],
                choice.interpretationSymbols[index],
            )
        }
    }
    .filterNot { it.interpretationRef.isAppliedDominantOrLeading() }
    .minByOrNull { reading ->
        when {
            '/' in reading.symbol -> 3
            reading.symbol.startsWith("It+6") || reading.symbol.startsWith("Ger+6") ||
                reading.symbol.startsWith("Fr+6") -> 2
            reading.symbol.startsWith("vii") -> 1
            else -> 0
        }
    }

private fun ChordSelectionChoice.reading(
    interpretationRef: ChordInterpretationRef,
): LocalChordReading? {
    val index = interpretationRefs.indexOf(interpretationRef).takeIf { it >= 0 } ?: return null
    return LocalChordReading(this, interpretationRef, interpretationSymbols[index])
}

private fun ChordInterpretationRef.isAppliedDominantOrLeading(): Boolean =
    interpretationId.value.startsWith("secondary.secondary_dominant.") ||
        interpretationId.value.startsWith("secondary.secondary_leading.")

private fun List<ChordSelectionChoice>.symbolFor(
    pitchClasses: Collection<Int>,
    interpretationRef: ChordInterpretationRef?,
): String? = asSequence()
    .filter { it.pitchClasses == pitchClasses.toSet() }
    .mapNotNull { choice ->
        choice.interpretationRefs.indexOf(interpretationRef)
            .takeIf { it >= 0 }
            ?.let(choice.interpretationSymbols::get)
    }
    .firstOrNull()

private fun List<ChordSelectionChoice>.preferredLocalSymbol(
    pitchClasses: Collection<Int>,
): String? = asSequence()
    .filter { it.pitchClasses == pitchClasses.toSet() }
    .flatMap { it.interpretationSymbols.asSequence() }
    .filterNot { it.startsWith("It+6") || it.startsWith("Ger+6") || it.startsWith("Fr+6") }
    .minByOrNull { symbol ->
        when {
            '/' in symbol -> 2
            symbol.startsWith("vii") -> 1
            else -> 0
        }
    }

private fun String.withPositionFrom(original: String): String {
    val slash = original.indexOf('/')
    val head = if (slash >= 0) original.substring(0, slash) else original
    val figure = listOf("64", "65", "43", "42", "6", "7")
        .firstOrNull(head::endsWith)
        .orEmpty()
    return if (figure.isEmpty() || endsWith(figure)) this else this + figure
}

internal val SchoenbergFreePracticeDiscoveryRequest.practiceKey: ModulationKey
    get() = focus?.key ?: catalogKey ?: initialKey

internal fun discoverCadenceFreePracticeContribution(
    request: SchoenbergFreePracticeDiscoveryRequest,
    descriptor: SchoenbergExerciseDescriptor,
): SchoenbergFreePracticeContribution {
    val key = request.practiceKey
    val direct = cadenceSuffixes(key, includeSixFour = false)
        .filter { it.slots.first().degree in setOf(2, 4) }
    val throughSixFour = cadenceSuffixes(key, includeSixFour = true)
        .filter { it.slots.first().degree in setOf(2, 4) }
    val dominant = direct.firstOrNull()?.slots?.getOrNull(1)
        ?: return SchoenbergFreePracticeContribution()
    val tonic = direct.first().slots.last()
    val triads = exerciseTriads(key.key, includeLeadingTriad = true)
    val vi = triads.firstOrNull { it.degree == 6 && !it.isLeadingTriad() }
        ?.toSymbolic(TextbookTriadPosition.ROOT_POSITION)
    val iv = when (key.mode) {
        KeySignatureMode.MAJOR -> SchoenbergMinorSubdominantChapter.borrowedChords(
            key.key,
            includeSevenths = false,
        ).firstOrNull {
            it.degree == 4 && it.quality == ChordQuality.MINOR && it.arity == ChordArity.TRIAD
        }
        KeySignatureMode.MINOR -> triads.firstOrNull {
            it.degree == 4 && it.quality == ChordQuality.MINOR
        }?.toSymbolic(TextbookTriadPosition.ROOT_POSITION)
    }

    fun definition(
        id: String,
        title: String,
        progressions: List<CadenceIdiomSource>,
        authenticEnding: Boolean = false,
    ): SchoenbergFreePracticeIdiomDefinition? {
        val variants = progressions
            .flatMap { source ->
                seventhChordOptions(
                    key = key,
                    slots = source.visibleSlots(),
                    keepFinalTonicTriad = authenticEnding,
                ).map { visible -> visible to source.withVisibleSlots(visible) }
            }
            .mapNotNull { (slots, source) ->
                idiomVariant(
                    key = key,
                    id = "$id.${slots.variantToken()}",
                    slots = slots,
                    fixedInversionStepIndices = SchoenbergCadenceChapter.fixedInversionSlots(
                        progression = slots,
                        minor = key.mode == KeySignatureMode.MINOR,
                        authenticEnding = authenticEnding,
                    ),
                    avoidSecondInversionStepIndices =
                        SchoenbergCadenceChapter.advisoryDominantSlots(
                            progression = slots,
                            minor = key.mode == KeySignatureMode.MINOR,
                            authenticEnding = authenticEnding,
                        ),
                    teachingSource = source.toTeachingSource(key),
                )
            }
            .distinctBy { it.chordChoices.map(WorkspaceChordChoice::pinnedInterpretationRef) }
            .take(request.maxVariantsPerDefinition * 4)
        return variants.takeIf(List<SchoenbergFreePracticeIdiomVariant>::isNotEmpty)?.let {
            SchoenbergFreePracticeIdiomDefinition(
                id = id,
                title = title,
                sourceExerciseId = descriptor.exerciseId,
                sourceChapterId = descriptor.chapterId,
                variants = it,
            )
        }
    }

    val directSources = direct.map(::CadenceIdiomSource)
    val sixFourSources = throughSixFour.map { progression ->
        CadenceIdiomSource(
            progression = progression,
            cadenceOptions = SchoenbergCadenceOptions(includeCadentialSixFour = true),
        )
    }
    val definitions = listOfNotNull(
        definition(
            "schoenberg.cadence.authentic",
            "正格终止",
            listOf(
                direct.first().let {
                    CadenceIdiomSource(it, visibleCount = 2, start = it.slots.size - 2)
                }
            ),
            authenticEnding = true,
        ),
        definition(
            "schoenberg.cadence.complete-authentic",
            "完整的正格终止",
            directSources + sixFourSources,
            authenticEnding = true,
        ),
        definition(
            "schoenberg.cadence.deceptive",
            "阻碍终止",
            listOfNotNull(vi, iv).map { resolution ->
                CadenceIdiomSource(
                    progression = SchoenbergSymbolicProgression(listOf(dominant, resolution)),
                    cadenceOptions = SchoenbergCadenceOptions(includeDeceptiveCadence = true),
                )
            },
        ),
        definition(
            "schoenberg.predominant.diatonic",
            "属前功能",
            (directSources + sixFourSources).map(CadenceIdiomSource::withoutFinalChord),
        ),
    )
    return SchoenbergFreePracticeContribution(idioms = definitions)
}

/**
 * One cadence idiom variant together with the chapter progression it was cut from.
 *
 * [visibleCount] and [start] describe the window the idiom shows; [cadenceOptions] records which
 * cadence the chapter compiled, because a program built without `includeCadentialSixFour` neither
 * offers the I64 slot nor carries the six-four rules the variant is meant to teach.
 */
private data class CadenceIdiomSource(
    val progression: SchoenbergSymbolicProgression,
    val visibleCount: Int = progression.slots.size,
    val start: Int = 0,
    val cadenceOptions: SchoenbergCadenceOptions = SchoenbergCadenceOptions(),
) {
    fun visibleSlots(): List<SchoenbergSymbolicChord> =
        progression.slots.subList(start, start + visibleCount)

    fun withVisibleSlots(slots: List<SchoenbergSymbolicChord>): CadenceIdiomSource = copy(
        progression = progression.copy(
            slots = progression.slots.take(start) + slots +
                progression.slots.drop(start + visibleCount),
        ),
        visibleCount = slots.size,
    )

    fun withoutFinalChord(): CadenceIdiomSource = copy(visibleCount = visibleCount - 1)

    fun toTeachingSource(key: ModulationKey) = SchoenbergTeachingSource(
        key = key,
        progression = progression,
        start = start,
        cadenceOptions = cadenceOptions,
    )
}

internal fun discoverIntegratedFreePracticeContribution(
    request: SchoenbergFreePracticeDiscoveryRequest,
    descriptor: SchoenbergExerciseDescriptor,
): SchoenbergFreePracticeContribution =
    if (descriptor.exerciseId == SchoenbergCommonToneExercises.SECONDARY_HARMONY_EXERCISE_ID) {
        discoverSecondaryHarmonyFreePracticeContribution(request, descriptor)
    } else {
        SchoenbergFreePracticeContribution()
    }

internal fun discoverSecondaryHarmonyFreePracticeContribution(
    request: SchoenbergFreePracticeDiscoveryRequest,
    descriptor: SchoenbergExerciseDescriptor,
): SchoenbergFreePracticeContribution {
    val key = request.practiceKey
    val variants = if (request.onlyAvailableByDefault) {
        emptyList()
    } else {
        val variantSources = SchoenbergSecondaryDominantChapter.harmonyChoices(key.key)
            .flatMap { choice ->
                SchoenbergSecondaryDominantChapter.enumerate(key.key, choice.id)
            }
            .flatMap { source ->
                seventhChordOptions(key, source.slots.drop(1)).map { visibleSlots ->
                    visibleSlots to source.copy(slots = source.slots.take(1) + visibleSlots)
                }
            }
            .distinctBy { (slots, _) -> slots.variantToken() }
            .map { (slots, source) ->
                IdiomVariantSource(
                    id = "secondary.${slots.variantToken()}",
                    slots = slots,
                    anchorStepIndex = 0,
                    // The chapter's preparation chord stays outside the visible idiom.
                    teachingSource = SchoenbergTeachingSource(
                        key = key,
                        progression = source,
                        start = 1,
                    ),
                )
            }
        idiomVariants(key, variantSources)
            .distinctBy { it.chordChoices.map(WorkspaceChordChoice::pinnedInterpretationRef) }
    }
    val appliedDominantOfDominant = with(SchoenbergSecondaryDominantChapter) {
        harmonyTypes(key.key).map { it.toSymbolic() }
    }
        .firstOrNull {
            it.secondaryFamily == SecondaryHarmonyFamily.SECONDARY_DOMINANT &&
                it.appliedToDegree == 5 &&
                it.arity == ChordArity.TRIAD
        }
    val cadenceDominant = cadenceSuffixes(key, includeSixFour = false)
        .firstOrNull()
        ?.slots
        ?.getOrNull(1)
    val canonicalSlots = if (
        appliedDominantOfDominant != null &&
        cadenceDominant != null &&
        SchoenbergSecondaryDominantChapter.allowsResolution(
            appliedDominantOfDominant,
            cadenceDominant,
            key.key,
            exerciseTriads(key.key, includeLeadingTriad = true),
        )
    ) {
        seventhChordOptions(key, listOf(appliedDominantOfDominant, cadenceDominant))
    } else {
        emptyList()
    }
    val canonical = canonicalSlots.mapNotNull { slots ->
        idiomVariant(key, "secondary.canonical-dominant.${slots.variantToken()}", slots)
    }
    val defaultVariants = (canonical + variants.filter { variant ->
        val source = variant.chordIdentities.first()
        source in setOf("V/V", "V7/V") && variant.chordIdentities.last() in setOf("V", "V7")
    }).distinctBy { it.chordChoices }.flatMap { base ->
        listOf(base) + base.withCadentialSixFourBeforeLast(key)
    }
    return SchoenbergFreePracticeContribution(
        idioms = listOfNotNull(
            defaultVariants.takeIf { it.isNotEmpty() }?.let {
                SchoenbergFreePracticeIdiomDefinition(
                    id = "schoenberg.secondary-dominant.predominant",
                    title = "副属属前功能",
                    sourceExerciseId = descriptor.exerciseId,
                    sourceChapterId = descriptor.chapterId,
                    variants = it,
                )
            },
            variants.takeIf { it.isNotEmpty() && !request.onlyAvailableByDefault }?.let {
                SchoenbergFreePracticeIdiomDefinition(
                    id = "schoenberg.secondary-harmony.analogous-resolution",
                    title = "副属和弦的类比解决",
                    sourceExerciseId = descriptor.exerciseId,
                    sourceChapterId = descriptor.chapterId,
                    variants = it,
                    availableByDefault = false,
                )
            },
        )
    )
}

internal fun discoverDiminishedSeventhFreePracticeContribution(
    request: SchoenbergFreePracticeDiscoveryRequest,
    descriptor: SchoenbergExerciseDescriptor,
): SchoenbergFreePracticeContribution {
    if (request.onlyAvailableByDefault) return SchoenbergFreePracticeContribution()
    val key = request.practiceKey
    val variants = idiomVariants(
        key,
        SchoenbergDiminishedSeventhChapter.enumerate(key.key)
            .flatMap { progression -> seventhChordOptions(key, progression.slots) }
            .map { slots ->
            IdiomVariantSource(
                id = "diminished.${slots.variantToken()}",
                slots = slots,
                anchorStepIndex = 0,
            )
        }
    )
        .distinctBy { it.chordChoices.map(WorkspaceChordChoice::pinnedInterpretationRef) }
    return variants.takeIf { it.isNotEmpty() }?.let {
        SchoenbergFreePracticeContribution(
            idioms = listOf(
                SchoenbergFreePracticeIdiomDefinition(
                    id = "schoenberg.diminished-seventh.analogous-dominant",
                    title = "减七和弦的属功能解决",
                    sourceExerciseId = descriptor.exerciseId,
                    sourceChapterId = descriptor.chapterId,
                    variants = it,
                    availableByDefault = false,
                )
            )
        )
    } ?: SchoenbergFreePracticeContribution()
}

internal fun discoverNeapolitanFreePracticeContribution(
    request: SchoenbergFreePracticeDiscoveryRequest,
    descriptor: SchoenbergExerciseDescriptor,
): SchoenbergFreePracticeContribution {
    val key = request.practiceKey
    val variants = SchoenbergMinorSubdominantChapter.enumerateNeapolitanCadences(key.key)
        .flatMap { source ->
            seventhChordOptions(key, source.slots.dropLast(1)).map { visibleSlots ->
                visibleSlots to source.copy(slots = visibleSlots + source.slots.last())
            }
        }
        .mapNotNull { (slots, source) ->
            idiomVariant(
                key = key,
                id = "neapolitan.${slots.variantToken()}",
                slots = slots,
                teachingSource = SchoenbergTeachingSource(key = key, progression = source),
            )
        }
        .distinctBy { it.chordChoices.map(WorkspaceChordChoice::pinnedInterpretationRef) }
        .take(request.maxVariantsPerDefinition)
    return variants.takeIf { it.isNotEmpty() }?.let {
        SchoenbergFreePracticeContribution(
            idioms = listOf(
                SchoenbergFreePracticeIdiomDefinition(
                    id = "schoenberg.neapolitan.predominant",
                    title = "拿坡里属前功能",
                    sourceExerciseId = descriptor.exerciseId,
                    sourceChapterId = descriptor.chapterId,
                    variants = it,
                )
            )
        )
    } ?: SchoenbergFreePracticeContribution()
}

internal fun discoverAnalogousNeapolitanFreePracticeContribution(
    request: SchoenbergFreePracticeDiscoveryRequest,
    descriptor: SchoenbergExerciseDescriptor,
): SchoenbergFreePracticeContribution {
    if (request.onlyAvailableByDefault) return SchoenbergFreePracticeContribution()
    val key = request.practiceKey
    if (key.mode != KeySignatureMode.MAJOR) return SchoenbergFreePracticeContribution()
    val variants = idiomVariants(
        key,
        SchoenbergMinorSubdominantChapter.enumerateAnalogousNeapolitanConnections(key.key)
            .flatMap { progression -> seventhChordOptions(key, progression.slots) }
            .map { slots ->
                IdiomVariantSource(
                    id = "analogous-neapolitan.${slots.variantToken()}",
                    slots = slots,
                    anchorStepIndex = 0,
                )
            }
    ).take(request.maxVariantsPerDefinition * 2)
    return variants.takeIf { it.isNotEmpty() }?.let {
        SchoenbergFreePracticeContribution(
            idioms = listOf(
                SchoenbergFreePracticeIdiomDefinition(
                    id = "schoenberg.analogous-neapolitan.connection",
                    title = "类拿坡里连接",
                    sourceExerciseId = descriptor.exerciseId,
                    sourceChapterId = descriptor.chapterId,
                    variants = it,
                    availableByDefault = false,
                )
            )
        )
    } ?: SchoenbergFreePracticeContribution()
}

internal fun discoverAugmentedSixthFreePracticeContribution(
    request: SchoenbergFreePracticeDiscoveryRequest,
    descriptor: SchoenbergExerciseDescriptor,
): SchoenbergFreePracticeContribution {
    val key = request.practiceKey
    val progressions = SchoenbergAugmentedSixthChapter.enumerate(key.key)
    val catalog = freePracticeChordCatalog(
        key,
        progressions.flatMap { progression ->
            seventhChordOptions(key, progression.slots).flatten()
        },
    )
    val all = progressions
        .flatMap { progression ->
            seventhChordOptions(key, progression.slots).map { slots -> progression.kind to slots }
        }
        .mapNotNull { (kind, slots) ->
            idiomVariant(
                key = key,
                id = "augmented-sixth.${kind.name.lowercase()}.${slots.variantToken()}",
                slots = slots,
                anchorStepIndex = 0,
                catalog = catalog,
            )
        }
        .distinctBy { it.chordChoices.map(WorkspaceChordChoice::pinnedInterpretationRef) }
    val canonical = progressions
        .filter { progression ->
            val source = progression.slots.first()
            val rootPosition = when (source.arity) {
                ChordArity.TRIAD -> source.position == TextbookTriadPosition.ROOT_POSITION
                ChordArity.SEVENTH -> source.seventhPosition == TextbookSeventhPosition.ROOT_POSITION
            }
            progression.kind == SchoenbergConnectionKind.AUGMENTED_SIXTH_RESOLUTION &&
                source.augmentedSixthFamily in setOf(
                    AugmentedSixthFamily.ITALIAN,
                    AugmentedSixthFamily.GERMAN,
                    AugmentedSixthFamily.FRENCH,
                ) &&
                source.appliedToDegree == 5 &&
                rootPosition &&
                progression.slots.last().degree == 5
        }
        .flatMap { progression -> seventhChordOptions(key, progression.slots) }
        .mapNotNull { slots ->
            idiomVariant(
                key = key,
                id = "augmented-sixth.canonical.${slots.variantToken()}",
                slots = slots,
                catalog = catalog,
            )
        }
        .flatMap { base -> listOf(base) + base.withCadentialSixFourBeforeLast(key) }
    return SchoenbergFreePracticeContribution(
        idioms = listOfNotNull(
            canonical.takeIf { it.isNotEmpty() }?.let {
                SchoenbergFreePracticeIdiomDefinition(
                    id = "schoenberg.augmented-sixth.predominant",
                    title = "增六属前功能",
                    sourceExerciseId = descriptor.exerciseId,
                    sourceChapterId = descriptor.chapterId,
                    variants = it.take(request.maxVariantsPerDefinition * 4),
                )
            },
            all.filter { variant -> variant.chordIdentities.first().startsWith("ø+6") }
                .takeIf { it.isNotEmpty() }
                ?.let {
                    SchoenbergFreePracticeIdiomDefinition(
                        id = "schoenberg.augmented-sixth.half-diminished-neapolitan",
                        title = "半减七增六到拿坡里",
                        sourceExerciseId = descriptor.exerciseId,
                        sourceChapterId = descriptor.chapterId,
                        variants = it.take(request.maxVariantsPerDefinition),
                    )
                },
            all.takeIf { it.isNotEmpty() }?.let {
                if (request.onlyAvailableByDefault) return@let null
                SchoenbergFreePracticeIdiomDefinition(
                    id = "schoenberg.augmented-sixth.resolution",
                    title = "增六和弦解决",
                    sourceExerciseId = descriptor.exerciseId,
                    sourceChapterId = descriptor.chapterId,
                    variants = it,
                    availableByDefault = false,
                )
            },
        )
    )
}

internal fun discoverDistantModulationFreePracticeContribution(
    request: SchoenbergFreePracticeDiscoveryRequest,
    descriptor: SchoenbergExerciseDescriptor,
): SchoenbergFreePracticeContribution {
    val source = request.initialKey
    val idiomVariants = request.activeKeys
        .ifEmpty { listOf(request.practiceKey) }
        .distinct()
        .mapNotNull { key ->
            idiomVariant(
                key = key,
                id = "dominant-pedal.${key.fifths}.${key.mode.name.lowercase()}",
                slots = SchoenbergDistantModulationChapter.dominantSustainedProgression(key),
                parameters = dominantPedalTeachingParameters(key),
            )?.let { variant ->
                variant.copy(
                    title = "${key.displayName} · ${variant.title}",
                    durations = SchoenbergDistantModulationChapter.dominantSustainedDurations(),
                )
            }
        }
        .take(request.maxVariantsPerDefinition)
    val idioms = idiomVariants.takeIf { it.isNotEmpty() }?.let { variants ->
        listOf(
            SchoenbergFreePracticeIdiomDefinition(
                id = "schoenberg.dominant-pedal.target-confirmation",
                title = "属持续音（增强转调目标调性）",
                sourceExerciseId = descriptor.exerciseId,
                sourceChapterId = descriptor.chapterId,
                variants = variants,
            )
        )
    }.orEmpty()

    val activeKeys = request.activeKeys.distinct()
    val pivots = activeKeys.flatMapIndexed { sourceIndex, from ->
        activeKeys.drop(sourceIndex + 1).flatMap { to ->
            listOf(from to to, to to from).flatMap { (source, target) ->
                SchoenbergDistantModulationChapter.pivotRecipes(source, target).map { recipe ->
                    SchoenbergFreePracticePivotRecipe(
                        sourceExerciseId = descriptor.exerciseId,
                        sourceChapterId = descriptor.chapterId,
                        sourceKey = source,
                        targetKey = target,
                        pitchClasses = recipe.pitchClasses,
                        sourceReading = recipe.sourceReading,
                        targetReading = recipe.targetReading,
                        definition = recipe.definition,
                    )
                }
            }
        }
    }
    return SchoenbergFreePracticeContribution(idioms = idioms, pivotRecipes = pivots)
}

private fun dominantPedalTeachingParameters(key: ModulationKey): Map<String, String> {
    if (key.mode != KeySignatureMode.MAJOR || key.fifths - 3 !in -7..7) return emptyMap()
    val source = ModulationKey(key.fifths - 3, KeySignatureMode.MAJOR)
    return mapOf(
        SchoenbergExerciseSelectionKeys.DISTANT_MODULATION_PATH to
            SchoenbergDistantTonalPaths.THREE_SHARPS.id.value,
        SchoenbergExerciseSelectionKeys.TONAL_CONFIRMATION to
            TonalConfirmationLevel.ESTABLISHED.name,
        TEACHING_SOURCE_PARAMETER to SchoenbergTeachingSourceCodec.encode(
            SchoenbergTeachingSource(key = source),
        ),
    )
}

/**
 * Expands a customary progression with the corresponding seventh chords. Cadential six-four is
 * structural rather than a replaceable tonic, and an authentic cadence keeps its final I/i as a
 * triad; every other chord may use the seventh counterpart supplied by the owning vocabulary.
 */
private fun seventhChordOptions(
    key: ModulationKey,
    slots: List<SchoenbergSymbolicChord>,
    keepFinalTonicTriad: Boolean = false,
): List<List<SchoenbergSymbolicChord>> {
    val alternatives = slots.mapIndexed { index, chord ->
        val fixedCadentialSixFour = chord.arity == ChordArity.TRIAD &&
            chord.degree == 1 && chord.position == TextbookTriadPosition.SECOND_INVERSION
        val fixedFinalTonic = keepFinalTonicTriad && index == slots.lastIndex &&
            chord.degree == 1 && chord.rootAlteration == 0
        if (fixedCadentialSixFour || fixedFinalTonic) null else chord.seventhCounterpart(key)
    }
    val result = mutableListOf<List<SchoenbergSymbolicChord>>()
    fun expand(index: Int, current: MutableList<SchoenbergSymbolicChord>) {
        if (index == slots.size) {
            result += current.toList()
            return
        }
        current += slots[index]
        expand(index + 1, current)
        current.removeAt(current.lastIndex)
        alternatives[index]?.let { seventh ->
            current += seventh
            expand(index + 1, current)
            current.removeAt(current.lastIndex)
        }
    }
    expand(0, mutableListOf())
    return result.distinctBy(List<SchoenbergSymbolicChord>::variantToken)
}

private fun SchoenbergSymbolicChord.seventhCounterpart(
    key: ModulationKey,
): SchoenbergSymbolicChord? {
    if (arity != ChordArity.TRIAD || augmentedSixthFamily != null) return null
    if (secondaryFamily != null) {
        return SchoenbergSecondaryDominantChapter.harmonyTypes(key.key)
            .firstOrNull { type ->
                type.family == secondaryFamily &&
                    type.tonicizedDegree == appliedToDegree &&
                    type.rootDegree == degree &&
                    type.rootAlteration == rootAlteration &&
                    type.arity == ChordArity.SEVENTH
            }
            ?.let { type ->
                copy(
                    quality = type.quality,
                    arity = ChordArity.SEVENTH,
                    seventhPosition = position.toSeventhPosition(),
                )
            }
    }
    if (key.mode == KeySignatureMode.MAJOR && (rootAlteration != 0 || modalOrigins.isNotEmpty())) {
        return SchoenbergMinorSubdominantChapter.borrowedChords(key.key, includeSevenths = true)
            .firstOrNull { candidate ->
                candidate.degree == degree &&
                    candidate.rootAlteration == rootAlteration &&
                    candidate.arity == ChordArity.SEVENTH
            }?.copy(seventhPosition = position.toSeventhPosition())
    }
    if (rootAlteration != 0) return null
    val seventh = DominantSeventhRules.seventhChordInKey(key.key, degree)
    return copy(
        quality = seventh.quality,
        arity = ChordArity.SEVENTH,
        seventhPosition = position.toSeventhPosition(),
    )
}

private fun TextbookTriadPosition.toSeventhPosition(): TextbookSeventhPosition = when (this) {
    TextbookTriadPosition.ROOT_POSITION -> TextbookSeventhPosition.ROOT_POSITION
    TextbookTriadPosition.FIRST_INVERSION -> TextbookSeventhPosition.FIRST_INVERSION
    TextbookTriadPosition.SECOND_INVERSION -> TextbookSeventhPosition.SECOND_INVERSION
}

private fun cadenceSuffixes(
    key: ModulationKey,
    includeSixFour: Boolean,
): List<SchoenbergSymbolicProgression> {
    val triads = exerciseTriads(key.key, includeLeadingTriad = true)
    val tonic = triads.first { it.degree == 1 && !it.isLeadingTriad() }
    val dominant = triads.first {
        it.degree == 5 && it.quality in setOf(ChordQuality.MAJOR, ChordQuality.DOMINANT7)
    }
    val policy = SchoenbergCadencePolicy(
        options = SchoenbergCadenceOptions(includeCadentialSixFour = includeSixFour),
        minor = key.mode == KeySignatureMode.MINOR,
    )
    return triads
        .filter { it.degree in setOf(2, 4) && !it.isLeadingTriad() }
        .mapNotNull { predominant ->
            val slots = buildList {
                add(predominant.toSymbolic(TextbookTriadPosition.ROOT_POSITION))
                if (includeSixFour) {
                    add(tonic.toSymbolic(TextbookTriadPosition.SECOND_INVERSION))
                }
                add(dominant.toSymbolic(TextbookTriadPosition.ROOT_POSITION))
                add(tonic.toSymbolic(TextbookTriadPosition.ROOT_POSITION))
            }
            SchoenbergSymbolicProgression(slots).takeIf {
                policy.allowsPrefix(slots, slots.size)
            }
        }
}

private data class IdiomVariantSource(
    val id: String,
    val slots: List<SchoenbergSymbolicChord>,
    val anchorStepIndex: Int,
    val teachingSource: SchoenbergTeachingSource? = null,
)

private fun idiomVariants(
    key: ModulationKey,
    sources: List<IdiomVariantSource>,
): List<SchoenbergFreePracticeIdiomVariant> {
    if (sources.isEmpty()) return emptyList()
    val catalog = freePracticeChordCatalog(key, sources.flatMap { it.slots })
    return sources.mapNotNull { source ->
        idiomVariant(
            key = key,
            id = source.id,
            slots = source.slots,
            anchorStepIndex = source.anchorStepIndex,
            catalog = catalog,
            teachingSource = source.teachingSource,
        )
    }
}

private fun freePracticeChordCatalog(
    key: ModulationKey,
    chords: List<SchoenbergSymbolicChord>,
): ChordCatalog = SchoenbergChordCatalog.collect(
    key = key.key,
    requestedChords = chords,
    tonalContext = key.chordSelectionTonalContext(),
    useSelectionInterpretationIds = true,
)

private fun idiomVariant(
    key: ModulationKey,
    id: String,
    slots: List<SchoenbergSymbolicChord>,
    anchorStepIndex: Int = 0,
    parameters: Map<String, String> = emptyMap(),
    catalog: ChordCatalog? = null,
    fixedInversionStepIndices: Set<Int> = emptySet(),
    avoidSecondInversionStepIndices: Set<Int> = emptySet(),
    teachingSource: SchoenbergTeachingSource? = null,
): SchoenbergFreePracticeIdiomVariant? = runCatching {
    val tonalContext = key.chordSelectionTonalContext()
    val targets = if (catalog == null) {
        SchoenbergChordCatalog.targets(
            key = key.key,
            chords = slots,
            tonalContext = tonalContext,
            useSelectionInterpretationIds = true,
        )
    } else {
        SchoenbergChordCatalog.targets(
            key = key.key,
            chords = slots,
            catalog = catalog,
            tonalContext = tonalContext,
            useSelectionInterpretationIds = true,
        )
    }
    val identities = slots.map(SchoenbergSymbolicChord::freePracticeIdentity)
    SchoenbergFreePracticeIdiomVariant(
        id = id,
        title = identities.joinToString(" – "),
        chordIdentities = identities,
        durations = List(slots.size) { Fraction.QUARTER },
        suggestedKey = key,
        parameters = parameters + teachingSource
            ?.let { mapOf(TEACHING_SOURCE_PARAMETER to SchoenbergTeachingSourceCodec.encode(it)) }
            .orEmpty(),
        chordChoices = targets.map(InterpretedChordTarget::toWorkspaceChordChoice),
        anchorStepIndex = anchorStepIndex,
        fixedInversionStepIndices = fixedInversionStepIndices,
        customaryBassStepIndices = slots.mapIndexedNotNullTo(linkedSetOf()) { index, chord ->
            index.takeIf {
                index !in fixedInversionStepIndices &&
                    (chord.augmentedSixthFamily != null ||
                        (chord.degree == 2 && chord.rootAlteration < 0))
            }
        },
        avoidSecondInversionStepIndices = avoidSecondInversionStepIndices,
    )
}.getOrNull()

private fun SchoenbergFreePracticeIdiomVariant.withCadentialSixFourBeforeLast(
    key: ModulationKey,
): List<SchoenbergFreePracticeIdiomVariant> {
    if (chordIdentities.isEmpty()) return emptyList()
    val sixFourSlot = cadenceSuffixes(key, includeSixFour = true)
        .firstOrNull()
        ?.slots
        ?.getOrNull(1)
        ?: return emptyList()
    val sixFour = idiomVariant(
        key = key,
        id = "cadential-six-four",
        slots = listOf(sixFourSlot),
        fixedInversionStepIndices = setOf(0),
    ) ?: return emptyList()
    val insertion = chordIdentities.lastIndex
    return listOf(
        copy(
            id = "$id.with-i64",
            title = (chordIdentities.toMutableList().apply {
                add(insertion, sixFour.chordIdentities.single())
            }).joinToString(" – "),
            chordIdentities = chordIdentities.toMutableList().apply {
                add(insertion, sixFour.chordIdentities.single())
            },
            durations = durations.toMutableList().apply {
                add(insertion, sixFour.durations.single())
            },
            chordChoices = chordChoices.toMutableList().apply {
                add(insertion, sixFour.chordChoices.single())
            },
            anchorStepIndex = if (anchorStepIndex >= insertion) anchorStepIndex + 1 else anchorStepIndex,
            fixedInversionStepIndices = fixedInversionStepIndices.mapTo(linkedSetOf()) { index ->
                if (index >= insertion) index + 1 else index
            } + insertion,
            customaryBassStepIndices = customaryBassStepIndices.mapTo(linkedSetOf()) { index ->
                if (index >= insertion) index + 1 else index
            },
            avoidSecondInversionStepIndices =
                avoidSecondInversionStepIndices.mapTo(linkedSetOf()) { index ->
                    if (index >= insertion) index + 1 else index
                },
        )
    )
}

private fun List<SchoenbergSymbolicChord>.variantToken(): String =
    joinToString("_") { chord ->
        chord.transitionToken().replace(Regex("[^A-Za-z0-9.-]"), "-")
    }

private fun SchoenbergSymbolicChord.freePracticeIdentity(): String {
    augmentedSixthFamily?.let { family ->
        val name = when (family) {
            AugmentedSixthFamily.ITALIAN -> "It+6"
            AugmentedSixthFamily.GERMAN -> "Ger+6"
            AugmentedSixthFamily.FRENCH -> "Fr+6"
            AugmentedSixthFamily.HALF_DIMINISHED -> "ø+6"
        }
        return name + appliedToDegree
            ?.takeUnless { it == 5 }
            ?.let { "/${FunctionalChordSymbolFormatter.romanDegree(it)}" }
            .orEmpty()
    }
    if (secondaryFamily == SecondaryHarmonyFamily.SECONDARY_DOMINANT) {
        val target = appliedToDegree?.let(FunctionalChordSymbolFormatter::romanDegree).orEmpty()
        val numeral = if (rootlessDominantNinthUsageId != null) "vii°" else "V"
        val figure = when (arity) {
            ChordArity.TRIAD -> when (position) {
                TextbookTriadPosition.ROOT_POSITION -> ""
                TextbookTriadPosition.FIRST_INVERSION -> "6"
                TextbookTriadPosition.SECOND_INVERSION -> "64"
            }
            ChordArity.SEVENTH -> when (seventhPosition) {
                TextbookSeventhPosition.ROOT_POSITION, null -> "7"
                TextbookSeventhPosition.FIRST_INVERSION -> "65"
                TextbookSeventhPosition.SECOND_INVERSION -> "43"
                TextbookSeventhPosition.THIRD_INVERSION -> "42"
            }
        }
        return "$numeral$figure/$target"
    }
    if (secondaryFamily == SecondaryHarmonyFamily.SECONDARY_LEADING) {
        val target = appliedToDegree?.let(FunctionalChordSymbolFormatter::romanDegree).orEmpty()
        val qualityMark = if (quality == ChordQuality.HALF_DIMINISHED7) "ø" else "°"
        val figure = when (arity) {
            ChordArity.TRIAD -> when (position) {
                TextbookTriadPosition.ROOT_POSITION -> ""
                TextbookTriadPosition.FIRST_INVERSION -> "6"
                TextbookTriadPosition.SECOND_INVERSION -> "64"
            }
            ChordArity.SEVENTH -> when (seventhPosition) {
                TextbookSeventhPosition.ROOT_POSITION, null -> "7"
                TextbookSeventhPosition.FIRST_INVERSION -> "65"
                TextbookSeventhPosition.SECOND_INVERSION -> "43"
                TextbookSeventhPosition.THIRD_INVERSION -> "42"
            }
        }
        return "vii$qualityMark$figure/$target"
    }
    val base = functionalIdentity()
    return when (arity) {
        ChordArity.TRIAD -> base + when (position) {
            TextbookTriadPosition.ROOT_POSITION -> ""
            TextbookTriadPosition.FIRST_INVERSION -> "6"
            TextbookTriadPosition.SECOND_INVERSION -> "64"
        }
        ChordArity.SEVENTH -> base.removeSuffix("7") + when (seventhPosition) {
            TextbookSeventhPosition.ROOT_POSITION, null -> "7"
            TextbookSeventhPosition.FIRST_INVERSION -> "65"
            TextbookSeventhPosition.SECOND_INVERSION -> "43"
            TextbookSeventhPosition.THIRD_INVERSION -> "42"
        }
    }
}

private fun SchoenbergSymbolicChord.functionalIdentity(): String =
    FunctionalChordSymbolFormatter.format(
        FunctionalChordSymbol(
            degree = degree,
            alteration = rootAlteration,
            quality = quality,
            arity = arity,
            appliedToDegree = appliedToDegree,
        )
    )

private fun com.mecon.theory.constraint.ChordTarget.functionalIdentity(): String =
    when (this) {
        is InterpretedChordTarget -> FunctionalChordSymbolFormatter.format(interpretation.symbol)
        else -> FunctionalChordSymbolFormatter.format(
            FunctionalChordSymbol(
                degree = degree,
                quality = quality,
                arity = arity,
            )
        )
    }
