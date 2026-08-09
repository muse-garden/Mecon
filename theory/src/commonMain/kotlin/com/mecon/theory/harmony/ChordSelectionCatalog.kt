package com.mecon.theory.harmony

import kotlin.jvm.JvmInline

import com.mecon.theory.FunctionalChordSymbolFormatter
import com.mecon.theory.Key
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.SpelledPitchClass
import com.mecon.theory.TonalContext
import com.mecon.theory.theoryMemoMap
import kotlinx.serialization.Serializable

data class ChordCatalogCategoryDescriptor(
    val id: String,
    val order: Int,
    val titleKey: String,
    val descriptionKey: String,
) {
    init {
        require(id.isNotBlank())
        require(order >= 0)
        require(titleKey.isNotBlank())
        require(descriptionKey.isNotBlank())
    }
}

data class ChordChapterDescriptor(
    val id: String,
    val order: Int,
) {
    init {
        require(id.isNotBlank())
        require(order >= 0)
    }
}

/**
 * A chapter-owned named subset extracted from a broader catalog contribution.
 *
 * This is intended for structurally meaningful special families (for example the Neapolitan
 * chord), without teaching the catalog or UI how to recognize any particular family.
 */
data class ChordCatalogNamedSubset(
    val category: ChordCatalogCategoryDescriptor,
    val matches: (ChordInterpretation) -> Boolean,
)

/**
 * One category contributed by a theory chapter.
 *
 * The chapter owns category metadata, construction selection, and any projection needed to turn
 * its structural symbol into the functional symbol users expect (for example V7/V).
 */
data class ChordCatalogContribution(
    val category: ChordCatalogCategoryDescriptor,
    val chapter: ChordChapterDescriptor = ChordChapterDescriptor(category.id, category.order),
    val orderWithinChapter: Int = 0,
    val construct: (TonalContext, Key) -> List<ConstructedChord>,
    val symbolProjection: (ChordInterpretation) -> FunctionalChordSymbol = { it.symbol },
    val symbolLabelProjection: ((ChordInterpretation) -> String)? = null,
    val choiceOrderProjection: ((ChordInterpretation) -> Int)? = null,
    val choiceProjection: ChordChoiceProjection = ChordChoiceProjection.ByInterpretation,
    val namedSubsets: List<ChordCatalogNamedSubset> = emptyList(),
) {
    init {
        require(orderWithinChapter >= 0)
        require(namedSubsets.map { it.category.id }.distinct().size == namedSubsets.size) {
            "Named subset category ids must be unique within a contribution"
        }
    }
}

sealed interface ChordChoiceProjection {
    data object ByInterpretation : ChordChoiceProjection

    data class BySoundingClass(
        val familyId: VagrantChordFamilyId,
    ) : ChordChoiceProjection
}

@Serializable
@JvmInline
value class ChordSelectionId(val value: String) {
    init {
        require(value.isNotBlank()) { "ChordSelectionId must not be blank" }
    }

    override fun toString(): String = value
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class DiscoverableChordCatalogChapter

interface ChordCatalogChapterProvider {
    val chordCatalogContributions: List<ChordCatalogContribution>
}

object ChordCatalogChapterDiscovery {
    fun discover(): List<ChordCatalogChapterProvider> = GeneratedChordChapterRegistry.catalogProviders
}

/** Stable tonal-context identity shared by selection, persisted interpretation refs, and solving. */
internal fun ModulationKey.chordSelectionTonalContext() = tonalContext("chord-selection")

data class ChordSelectionChoice(
    val id: ChordSelectionId,
    /**
     * Compatibility projection for pre-v4 hosts. New code must commit an [interpretationRefs]
     * item rather than persist this display-oriented value.
     */
    val identity: String,
    val functionalSymbol: String,
    val absoluteTones: List<String>,
    val relativeTones: List<String>,
    /** Pitch class of the displayed structural root; symmetric families use their canonical reading. */
    val rootPitchClass: Int,
    val pitchClasses: Set<Int>,
    val origin: ChordSelectionOriginRef,
    val audibleKey: AudibleSonorityKey,
    val soundingClassId: SoundingClassId? = null,
    val interpretationRefs: List<ChordInterpretationRef>,
    /** Legacy v1-v3 display symbols aligned with [interpretationRefs], used only for migration. */
    val interpretationSymbols: List<String>,
    val routeIds: List<ConstructionRouteId>,
) {
    init {
        require(interpretationRefs.isNotEmpty())
        require(interpretationRefs.distinct().size == interpretationRefs.size)
        require(interpretationSymbols.size == interpretationRefs.size)
        require(routeIds.size == interpretationRefs.size)
        require(rootPitchClass in 0..11)
        require(audibleKey == AudibleSonorityKey.from(pitchClasses))
    }

    val confirmedInterpretationRef: ChordInterpretationRef?
        get() = interpretationRefs.singleOrNull()
}

data class ChordSelectionGroup(
    val category: ChordCatalogCategoryDescriptor,
    val chords: List<ChordSelectionChoice>,
    val chapter: ChordChapterDescriptor = ChordChapterDescriptor(category.id, category.order),
    val orderWithinChapter: Int = 0,
)

/**
 * Shared, UI-neutral chord-selection catalog for free practice, analysis, and future consumers.
 */
object ChordSelectionCatalog {
    private data class ProjectedChoice(
        val choice: ChordSelectionChoice,
        val interpretations: List<ChordInterpretation>,
        val order: Int,
    )

    private val groupMemo =
        theoryMemoMap<Pair<ModulationKey, List<ChordCatalogChapterProvider>>, List<ChordSelectionGroup>>()

    /**
     * Building one key's catalog constructs every chapter's chords, and callers ask for the same
     * key repeatedly — free practice alone builds all 30 display keys per off-key projection. The
     * result is a pure, immutable function of (key, providers), so it is memoized.
     */
    fun groups(
        key: ModulationKey,
        providers: List<ChordCatalogChapterProvider> = ChordCatalogChapterDiscovery.discover(),
    ): List<ChordSelectionGroup> = groupMemo.getOrPut(key to providers) { buildGroups(key, providers) }

    private fun buildGroups(
        key: ModulationKey,
        providers: List<ChordCatalogChapterProvider>,
    ): List<ChordSelectionGroup> {
        val context = key.chordSelectionTonalContext()
        val contributions = providers
            .flatMap(ChordCatalogChapterProvider::chordCatalogContributions)
        val categories = contributions.flatMap { contribution ->
            listOf(contribution.category) + contribution.namedSubsets.map(ChordCatalogNamedSubset::category)
        }
        require(categories.distinctBy(ChordCatalogCategoryDescriptor::id).size == categories.size) {
            "Chord catalog category ids must be unique"
        }
        require(categories.distinctBy(ChordCatalogCategoryDescriptor::order).size == categories.size) {
            "Chord catalog category order numbers must be unique"
        }
        return contributions
            .flatMap { contribution ->
                val catalog = ChordCatalogCollector.collect(
                    contribution.construct(context, key.key)
                )
                val choicesByCategory = projectChoices(key, catalog, contribution)
                    .groupBy { projected -> categoryFor(projected, contribution) }
                (contribution.namedSubsets.map(ChordCatalogNamedSubset::category) + contribution.category)
                    .mapNotNull { category ->
                        val choices = choicesByCategory[category].orEmpty()
                            .distinctBy { it.choice.identity }
                            .sortedWith(
                                compareBy<ProjectedChoice>(
                                    ProjectedChoice::order,
                                    { it.choice.rootPitchClass },
                                    { it.choice.functionalSymbol },
                                    { it.choice.id.value },
                                )
                            )
                            .map { it.choice.inCategory(category.id) }
                        choices.takeIf(List<ChordSelectionChoice>::isNotEmpty)?.let {
                            ChordSelectionGroup(
                                category = category,
                                chords = it,
                                chapter = contribution.chapter,
                                orderWithinChapter = contribution.orderWithinChapter,
                            )
                        }
                    }
            }
            .sortedWith(compareBy({ it.category.order }, { it.category.id }))
    }

    fun choices(
        key: ModulationKey,
        providers: List<ChordCatalogChapterProvider> = ChordCatalogChapterDiscovery.discover(),
    ): List<ChordSelectionChoice> = groups(key, providers).flatMap(ChordSelectionGroup::chords)

    fun interpretation(
        key: ModulationKey,
        ref: ChordInterpretationRef,
        providers: List<ChordCatalogChapterProvider> = ChordCatalogChapterDiscovery.discover(),
    ): ChordSelectionChoice? =
        choices(key, providers).firstOrNull { ref in it.interpretationRefs }

    private fun projectChoices(
        key: ModulationKey,
        catalog: ChordCatalog,
        contribution: ChordCatalogContribution,
    ): List<ProjectedChoice> =
        when (val projection = contribution.choiceProjection) {
            ChordChoiceProjection.ByInterpretation ->
                catalog.entries.flatMap { entry ->
                    entry.interpretations.map { interpretation ->
                        ProjectedChoice(
                            choice = choice(
                                key = key,
                                contribution = contribution,
                                entry = entry,
                                interpretations = listOf(interpretation),
                                symbol = contribution.symbolProjection(interpretation),
                                functionalSymbol = contribution.symbolLabel(interpretation),
                                soundingClassId = null,
                            ),
                            interpretations = listOf(interpretation),
                            order = contribution.choiceOrderProjection?.invoke(interpretation) ?: 0,
                        )
                    }
                }

            is ChordChoiceProjection.BySoundingClass ->
                catalog.entries
                    .flatMap { entry ->
                        entry.interpretations.map { interpretation -> entry to interpretation }
                    }
                    .groupBy { (entry, _) ->
                        soundingClassId(projection.familyId, entry)
                    }
                    .entries
                    .sortedBy { it.key.value }
                    .map { (soundingClassId, unsortedReadings) ->
                        val readings = unsortedReadings.sortedWith(
                            compareBy<Pair<ChordCatalogEntry, ChordInterpretation>>(
                                { (entry, _) -> entry.sonority.spelledRoot.pitchClass.value },
                                { (_, interpretation) -> interpretation.id.value },
                            )
                        )
                        val (entry, interpretation) = readings.first()
                        val interpretations = readings.map { it.second }
                        ProjectedChoice(
                            choice = choice(
                                key = key,
                                contribution = contribution,
                                entry = entry,
                                interpretations = interpretations,
                                refs = readings.map { (readingEntry, reading) ->
                                    ChordInterpretationRef(readingEntry.sonority.id, reading.id)
                                },
                                symbol = contribution.symbolProjection(interpretation),
                                functionalSymbol = contribution.symbolLabel(interpretation),
                                interpretationSymbols = readings.map { (_, reading) ->
                                    contribution.symbolLabel(reading)
                                },
                                soundingClassId = soundingClassId,
                            ),
                            interpretations = interpretations,
                            order = contribution.choiceOrderProjection?.invoke(interpretation) ?: 0,
                        )
                    }
        }

    private fun categoryFor(
        projected: ProjectedChoice,
        contribution: ChordCatalogContribution,
    ): ChordCatalogCategoryDescriptor {
        val matchingSubsets = contribution.namedSubsets.filter { subset ->
            val results = projected.interpretations.map(subset.matches).distinct()
            require(results.size == 1) {
                "Named subset ${subset.category.id} splits projected choice ${projected.choice.id}"
            }
            results.single()
        }
        require(matchingSubsets.size <= 1) {
            "Projected choice ${projected.choice.id} matches multiple named subsets: " +
                matchingSubsets.joinToString { it.category.id }
        }
        return matchingSubsets.singleOrNull()?.category ?: contribution.category
    }

    private fun ChordSelectionChoice.inCategory(categoryId: String): ChordSelectionChoice = copy(
        origin = origin.copy(categoryId = ChordCatalogCategoryId(categoryId)),
    )

    private fun choice(
        key: ModulationKey,
        contribution: ChordCatalogContribution,
        entry: ChordCatalogEntry,
        interpretations: List<ChordInterpretation>,
        refs: List<ChordInterpretationRef> = interpretations.map {
            ChordInterpretationRef(entry.sonority.id, it.id)
        },
        symbol: FunctionalChordSymbol,
        functionalSymbol: String = FunctionalChordSymbolFormatter.format(symbol),
        interpretationSymbols: List<String> = listOf(functionalSymbol),
        soundingClassId: SoundingClassId?,
    ): ChordSelectionChoice {
        val interpretation = interpretations.first()
        val orderedTones = interpretation.structuralToneOrder.mapNotNull(entry.sonority::tone)
        val selectionId = ChordSelectionId(
                soundingClassId?.let { "selection.${it.value}" }
                    ?: "selection.${refs.single().interpretationId.value}"
            )
        val pitchClasses = orderedTones.mapTo(linkedSetOf()) { it.spelling.pitchClass.value }
        return ChordSelectionChoice(
            id = selectionId,
            identity = functionalSymbol,
            functionalSymbol = functionalSymbol,
            absoluteTones = orderedTones.map { it.spelling.displayName() },
            relativeTones = orderedTones.map { relativeToneName(key, it.spelling) },
            rootPitchClass = entry.sonority.spelledRoot.pitchClass.value,
            pitchClasses = pitchClasses,
            origin = ChordSelectionOriginRef(
                categoryId = ChordCatalogCategoryId(contribution.category.id),
                choiceId = selectionId,
            ),
            audibleKey = AudibleSonorityKey.from(pitchClasses),
            soundingClassId = soundingClassId,
            interpretationRefs = refs,
            interpretationSymbols = interpretationSymbols,
            routeIds = refs.map {
                ConstructionRouteId("route.${it.interpretationId.value}")
            },
        )
    }

    private fun ChordCatalogContribution.symbolLabel(
        interpretation: ChordInterpretation,
    ): String = symbolLabelProjection?.invoke(interpretation)
        ?: FunctionalChordSymbolFormatter.format(symbolProjection(interpretation))

    private fun soundingClassId(
        familyId: VagrantChordFamilyId,
        entry: ChordCatalogEntry,
    ): SoundingClassId {
        val pitchClasses = entry.sonority.tones
            .map { it.spelling.pitchClass.value }
            .distinct()
            .sorted()
            .joinToString("-")
        return SoundingClassId("${familyId.value}.$pitchClasses")
    }

    /**
     * Relative pitch is relative to the key-signature major tonic, not the local minor tonic.
     * Thus A-minor i is 6-1-3 under the zero-accidental C-major signature.
     */
    private fun relativeToneName(key: ModulationKey, pitch: SpelledPitchClass): String {
        val signatureKey = ModulationKey(key.fifths, KeySignatureMode.MAJOR)
        val signatureContext = signatureKey.tonalContext("chord-selection-relative")
        val tonic = signatureKey.tonicSpelling()
        val degree = (pitch.noteName.ordinal - tonic.noteName.ordinal).mod(7) + 1
        val expected = signatureContext.spellDegree(degree)
        val raw = (pitch.pitchClass.value - expected.pitchClass.value).mod(12)
        val alteration = if (raw > 6) raw - 12 else raw
        return when {
            alteration > 0 -> "♯".repeat(alteration) + degree
            alteration < 0 -> "♭".repeat(-alteration) + degree
            else -> degree.toString()
        }
    }
}
