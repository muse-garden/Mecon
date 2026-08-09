package com.mecon.theory.harmony

import kotlin.jvm.JvmInline

import com.mecon.theory.TonalContext
import com.mecon.theory.SpelledPitchClass
import com.mecon.theory.FunctionalChordSymbolFormatter
import com.mecon.theory.Mode

@JvmInline
value class ChordKnowledgeContributionId(val value: String) {
    init {
        require(value.isNotBlank()) { "ChordKnowledgeContributionId must not be blank" }
    }
}

enum class TheoryClaimKind {
    PRIMARY_SOURCE,
    PROJECT_INFERENCE,
    PRODUCT_RECOMMENDATION,
}

data class TheorySourceRef(
    val sourceId: String,
    val edition: String,
    val chapterOrTopic: String,
    val locator: String? = null,
    val claimKind: TheoryClaimKind = TheoryClaimKind.PRIMARY_SOURCE,
) {
    init {
        require(sourceId.isNotBlank()) { "Theory source id must not be blank" }
        require(edition.isNotBlank()) { "Theory source edition must not be blank" }
        require(chapterOrTopic.isNotBlank()) { "Theory source chapter/topic must not be blank" }
        require(locator == null || locator.isNotBlank()) { "Theory source locator must not be blank" }
    }
}

enum class TendencyDirection {
    ASCENDING,
    DESCENDING,
    COMMON_TONE,
    CONTEXTUAL,
}

data class TendencyToneDetail(
    val toneId: SonorityToneId,
    val role: FunctionalToneRole,
    val direction: TendencyDirection,
    val targetDegree: Int? = null,
    val descriptionKey: String,
) {
    init {
        require(targetDegree == null || targetDegree > 0)
        require(descriptionKey.isNotBlank())
    }
}

data class ImpliedToneDetail(
    val degree: Int,
    val alteration: Int = 0,
    val omitted: Boolean,
    val descriptionKey: String,
) {
    init {
        require(degree > 0)
        require(descriptionKey.isNotBlank())
    }
}

data class ConstructionRoute(
    val id: ConstructionRouteId,
    val interpretationRef: ChordInterpretationRef,
    val routeOrder: Int = 0,
    val formulaKey: String,
    val steps: List<ConstructionOperation>,
    val impliedOrOmittedTones: List<ImpliedToneDetail> = emptyList(),
    val tendencyTones: List<TendencyToneDetail> = emptyList(),
    val connectionRefs: List<HarmonicTreatmentId> = emptyList(),
    val functionRelations: List<ChordFunctionRelation> = emptyList(),
    val construction: ChordConstructionDetail? = null,
    val sourceRefs: List<TheorySourceRef> = emptyList(),
) {
    init {
        require(routeOrder >= 0)
        require(formulaKey.isNotBlank())
        require(connectionRefs.distinct().size == connectionRefs.size)
        require(functionRelations.distinct().size == functionRelations.size)
        require(sourceRefs.distinct().size == sourceRefs.size)
    }
}

sealed interface ChordFunctionRelation {
    data class SubstitutesFor(
        val targetTreatmentId: HarmonicTreatmentId,
        val function: HarmonicFunction,
        val tonicizedDegree: Int? = null,
    ) : ChordFunctionRelation {
        init {
            require(tonicizedDegree == null || tonicizedDegree > 0)
        }
    }
}

sealed interface ChordConstructionDetail {
    data class OmittedFromFormula(
        val basis: ChordConstructionBasisRef,
        val tones: List<ChordConstructionTone>,
    ) : ChordConstructionDetail {
        init {
            require(tones.isNotEmpty())
        }
    }

    data class ModalScaleDegrees(
        val mode: Mode,
        val path: ModalScalePath,
        val tonicizedDegree: Int,
        val keySignatureFifths: Int?,
        val degrees: List<ModalScaleConstructionTone>,
    ) : ChordConstructionDetail {
        init {
            require(tonicizedDegree in 1..7)
            require(keySignatureFifths == null || keySignatureFifths in -7..7)
            require(degrees.size == 7)
            require(degrees.map(ModalScaleConstructionTone::degree).toSet() == (1..7).toSet())
            require(degrees.any(ModalScaleConstructionTone::chordTone))
        }
    }

    data class MinorSubdominantRelation(
        val sourceMode: Mode,
        val sourceTonic: SpelledPitchClass,
        val sourceKeySignatureFifths: Int?,
        val referenceFunction: HarmonicFunction,
        val referenceTones: List<ChordConstructionTone>,
        val borrowedTones: List<ChordConstructionTone>,
    ) : ChordConstructionDetail {
        init {
            require(sourceKeySignatureFifths == null || sourceKeySignatureFifths in -7..7)
            require(referenceFunction == HarmonicFunction.TONIC || referenceFunction == HarmonicFunction.DOMINANT)
            require(referenceTones.isNotEmpty())
            require(borrowedTones.isNotEmpty())
            require(referenceTones.all { it.presence == ConstructionTonePresence.SOUNDING })
            require(borrowedTones.all { it.presence == ConstructionTonePresence.SOUNDING })
        }
    }

    data class AugmentedSixthDerivation(
        val kind: AugmentedSixthConstructionKind,
        val origin: AugmentedSixthConstructionOrigin,
        val augmentedSixthTones: List<ChordConstructionTone>,
        val descendingEndpoint: SpelledPitchClass,
        val ascendingEndpoint: SpelledPitchClass,
        val resolutionTone: ChordConstructionTone,
        val resultSymbol: String,
        val alterationDescriptionKey: String,
    ) : ChordConstructionDetail {
        init {
            require(augmentedSixthTones.isNotEmpty())
            require(augmentedSixthTones.all { it.presence == ConstructionTonePresence.SOUNDING })
            val resultSpellings = augmentedSixthTones.map(ChordConstructionTone::spelling)
            require(descendingEndpoint in resultSpellings)
            require(ascendingEndpoint in resultSpellings)
            require(descendingEndpoint != ascendingEndpoint)
            require(resolutionTone.presence == ConstructionTonePresence.SOUNDING)
            require(resultSymbol.isNotBlank())
            require(alterationDescriptionKey.isNotBlank())
        }
    }
}

sealed interface AugmentedSixthConstructionOrigin {
    val tones: List<ChordConstructionTone>

    data class RootlessAppliedChord(
        val basis: ChordConstructionBasisRef,
        override val tones: List<ChordConstructionTone>,
        val rootlessResultNameKey: String,
    ) : AugmentedSixthConstructionOrigin {
        init {
            require(tones.count { it.presence == ConstructionTonePresence.OMITTED } == 1)
            require(tones.single { it.presence == ConstructionTonePresence.OMITTED }.role == ConstructionToneRole.ROOT)
            require(rootlessResultNameKey.isNotBlank())
        }
    }

    data class NamedChord(
        val symbol: String,
        override val tones: List<ChordConstructionTone>,
    ) : AugmentedSixthConstructionOrigin {
        init {
            require(symbol.isNotBlank())
            require(tones.isNotEmpty())
            require(tones.all { it.presence == ConstructionTonePresence.SOUNDING })
        }
    }
}

enum class AugmentedSixthConstructionKind {
    ITALIAN,
    GERMAN,
    FRENCH,
    HALF_DIMINISHED,
}

enum class ModalScalePath { ASCENDING, DESCENDING }

data class ModalScaleConstructionTone(
    val degree: Int,
    val spelling: SpelledPitchClass,
    val chordTone: Boolean,
) {
    init {
        require(degree in 1..7)
    }
}

@JvmInline
value class ChordConstructionBasisId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

data class ChordConstructionBasisDefinition(
    val id: ChordConstructionBasisId,
    val primaryNameKey: String,
    val secondaryNameKey: String,
    val romanNumeral: String,
) {
    init {
        require(primaryNameKey.isNotBlank())
        require(secondaryNameKey.isNotBlank())
        require(romanNumeral.isNotBlank())
    }
}

data class ChordConstructionBasisRef(
    val definition: ChordConstructionBasisDefinition,
    val tonicizedDegree: Int,
) {
    init {
        require(tonicizedDegree in 1..7)
    }

    val symbol: String
        get() = definition.romanNumeral + if (tonicizedDegree == 1) {
            ""
        } else {
            "/${FunctionalChordSymbolFormatter.romanDegree(tonicizedDegree)}"
        }
}

enum class ConstructionToneRole { ROOT, THIRD, FIFTH, SEVENTH, NINTH, OTHER }

enum class ConstructionTonePresence { SOUNDING, OMITTED }

data class ChordConstructionTone(
    val degree: Int,
    val alteration: Int,
    val spelling: SpelledPitchClass,
    val role: ConstructionToneRole,
    val presence: ConstructionTonePresence,
) {
    init {
        require(degree > 0)
    }
}

data class ChordSummary(
    val nameKey: String,
    val descriptionKey: String? = null,
    val tags: List<String> = emptyList(),
) {
    init {
        require(nameKey.isNotBlank())
        require(descriptionKey == null || descriptionKey.isNotBlank())
        require(tags.none(String::isBlank))
    }
}

data class ChordStructureDetail(
    val toneIds: List<SonorityToneId>,
    val propertyKeys: List<String> = emptyList(),
) {
    init {
        require(toneIds.isNotEmpty())
        require(toneIds.distinct().size == toneIds.size)
        require(propertyKeys.none(String::isBlank))
    }
}

data class ChordFunctionDetail(
    val function: HarmonicFunction,
    val descriptionKey: String? = null,
)

data class ChordVoiceLeadingDetail(
    val tendencyTones: List<TendencyToneDetail> = emptyList(),
    val connectionRefs: List<HarmonicTreatmentId> = emptyList(),
)

data class ChordDetailDefinition(
    val interpretationRef: ChordInterpretationRef,
    val explanationId: ChordExplanationId = ChordExplanationId(
        "explanation.${interpretationRef.interpretationId.value}"
    ),
    val orderWithinChapter: Int = 0,
    val sourceCategoryIds: Set<ChordCatalogCategoryId> = emptySet(),
    val summary: ChordSummary,
    val structure: ChordStructureDetail,
    val function: ChordFunctionDetail,
    val voiceLeading: ChordVoiceLeadingDetail,
    val routes: List<ConstructionRoute>,
    val sourceRefs: List<TheorySourceRef>,
) {
    init {
        require(orderWithinChapter >= 0)
        require(routes.all { it.interpretationRef == interpretationRef })
        require(routes.map(ConstructionRoute::id).distinct().size == routes.size)
    }
}

/** Catalog output: one common explanation with every precise construction route underneath it. */
data class ChordExplanationDefinition(
    val id: ChordExplanationId,
    val chapter: ChordChapterDescriptor,
    val sourceCategoryIds: Set<ChordCatalogCategoryId>,
    val orderWithinChapter: Int,
    val summary: ChordSummary,
    val structure: ChordStructureDetail,
    val function: ChordFunctionDetail,
    val routes: List<ConstructionRoute>,
    val sourceRefs: List<TheorySourceRef>,
) {
    init {
        require(routes.isNotEmpty())
        require(routes.map(ConstructionRoute::id).distinct().size == routes.size)
    }

    val interpretationRefs: List<ChordInterpretationRef>
        get() = routes.map(ConstructionRoute::interpretationRef).distinct()
}

data class ChordKnowledgeContext(
    val tonalContext: TonalContext,
    val previousInterpretation: ChordInterpretationRef? = null,
    val nextInterpretation: ChordInterpretationRef? = null,
    val exerciseId: String? = null,
)

data class SoundingInterpretationQuery(
    val audibleKey: AudibleSonorityKey,
    val selectedOrigin: ChordSelectionOriginRef? = null,
    val pinnedInterpretationRef: ChordInterpretationRef? = null,
)

data class ChordDetailModel(
    val audibleKey: AudibleSonorityKey? = null,
    val explanations: List<ChordExplanationDefinition>,
    val missingKnowledgeRefs: List<ChordInterpretationRef> = emptyList(),
) {
    constructor(
        definitions: List<ChordExplanationDefinition>,
        missingKnowledgeRefs: List<ChordInterpretationRef> = emptyList(),
    ) : this(
        audibleKey = null,
        explanations = definitions,
        missingKnowledgeRefs = missingKnowledgeRefs,
    )

    /** Source-compatible name used by R4 hosts. */
    val definitions: List<ChordExplanationDefinition> get() = explanations

}

data class ChordKnowledgeContribution(
    val id: ChordKnowledgeContributionId,
    val chapterId: String,
    val chapter: ChordChapterDescriptor = ChordChapterDescriptor(chapterId, 0),
    val familyId: VagrantChordFamilyId? = null,
    val construct: (TonalContext) -> List<ConstructedChord>,
    val details: (ChordKnowledgeContext, ChordCatalog) -> List<ChordDetailDefinition>,
) {
    init {
        require(chapterId.isNotBlank())
    }
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class DiscoverableChordKnowledgeChapter

interface ChordKnowledgeChapterProvider {
    val chordKnowledgeContributions: List<ChordKnowledgeContribution>
}

object ChordKnowledgeChapterDiscovery {
    fun discover(): List<ChordKnowledgeChapterProvider> = GeneratedChordChapterRegistry.knowledgeProviders
}

