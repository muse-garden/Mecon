package com.mecon.theory.harmony

class ChordKnowledgeCatalog private constructor(
    private val recordsByRef: Map<ChordInterpretationRef, DefinitionRecord>,
    private val discoveryIndex: ChordInterpretationDiscoveryIndex,
) {
    private data class DefinitionRecord(
        val detail: ChordDetailDefinition,
        val chapter: ChordChapterDescriptor,
    )

    fun resolve(
        query: SoundingInterpretationQuery,
        context: ChordKnowledgeContext,
    ): ChordDetailModel {
        val discovered = discoveryIndex.discover(query)
        return modelFor(
            refs = discovered.map(DiscoveredChordInterpretation::ref),
            audibleKey = query.audibleKey,
            selectedOrigin = query.selectedOrigin,
            selectedOriginRefs = discovered
                .filter { item -> query.selectedOrigin?.categoryId?.let { category -> item.origins.any { it.categoryId == category } } == true }
                .mapTo(hashSetOf(), DiscoveredChordInterpretation::ref),
        )
    }

    private fun modelFor(
        refs: List<ChordInterpretationRef>,
        audibleKey: AudibleSonorityKey?,
        selectedOrigin: ChordSelectionOriginRef?,
        selectedOriginRefs: Set<ChordInterpretationRef>,
    ): ChordDetailModel {
        val records = refs.mapNotNull(recordsByRef::get)
        val explanations = records
            .groupBy { it.detail.explanationId }
            .map { (id, grouped) ->
                val first = grouped.first()
                val details = grouped.map(DefinitionRecord::detail)
                require(details.all { it.summary == first.detail.summary }) {
                    "Chord explanation $id has conflicting summaries"
                }
                require(details.all { it.structure == first.detail.structure }) {
                    "Chord explanation $id has conflicting structures"
                }
                require(details.all { it.function == first.detail.function }) {
                    "Chord explanation $id has conflicting functions"
                }
                ChordExplanationDefinition(
                    id = id,
                    chapter = first.chapter,
                    sourceCategoryIds = details.flatMapTo(linkedSetOf()) { it.sourceCategoryIds },
                    orderWithinChapter = details.minOf { it.orderWithinChapter },
                    summary = first.detail.summary,
                    structure = first.detail.structure,
                    function = first.detail.function,
                    routes = details.flatMap(ChordDetailDefinition::routes)
                        .distinctBy(ConstructionRoute::id)
                        .sortedWith(
                            compareBy<ConstructionRoute>(
                                { route -> if (route.interpretationRef in selectedOriginRefs) 0 else 1 },
                                ConstructionRoute::routeOrder,
                                { it.id.value },
                            )
                        ),
                    sourceRefs = details.flatMap(ChordDetailDefinition::sourceRefs).distinct(),
                )
            }
            .sortedWith(
                compareBy<ChordExplanationDefinition>(
                    { explanation -> if (explanation.routes.any { it.interpretationRef in selectedOriginRefs }) 0 else 1 },
                    { it.chapter.order },
                    ChordExplanationDefinition::orderWithinChapter,
                    { it.id.value },
                )
            )
        return ChordDetailModel(
            audibleKey = audibleKey,
            explanations = explanations,
            missingKnowledgeRefs = refs.filterNot(recordsByRef::containsKey),
        )
    }

    companion object {
        fun create(
            context: ChordKnowledgeContext,
            providers: List<ChordKnowledgeChapterProvider> = ChordKnowledgeChapterDiscovery.discover(),
            discoveryIndex: ChordInterpretationDiscoveryIndex = ChordInterpretationDiscoveryIndex.EMPTY,
            treatmentRegistry: HarmonicTreatmentRegistry? = null,
        ): ChordKnowledgeCatalog {
            val contributions = providers.flatMap(ChordKnowledgeChapterProvider::chordKnowledgeContributions)
            require(contributions.map { it.id }.distinct().size == contributions.size) {
                "Chord knowledge contribution ids must be unique"
            }
            val records = contributions.flatMap { contribution ->
                val catalog = ChordCatalogCollector.collect(contribution.construct(context.tonalContext))
                val availableRefs = catalog.entries.flatMapTo(linkedSetOf()) { entry ->
                    entry.interpretations.map { ChordInterpretationRef(entry.sonority.id, it.id) }
                }
                val treatmentIdsByRef = buildMap {
                    catalog.entries.forEach { entry ->
                        entry.interpretations.forEach { interpretation ->
                            put(ChordInterpretationRef(entry.sonority.id, interpretation.id), interpretation.treatmentIds)
                        }
                    }
                }
                contribution.details(context, catalog).map { detail ->
                    require(detail.interpretationRef in availableRefs) {
                        "Chapter ${contribution.chapterId} references an interpretation outside its construction result: ${detail.interpretationRef}"
                    }
                    require(detail.routes.isNotEmpty()) { "Chord detail ${detail.interpretationRef} must have a construction route" }
                    require(detail.sourceRefs.isNotEmpty()) { "Chord detail ${detail.interpretationRef} must have a theory source" }
                    val availableTreatments = treatmentIdsByRef.getValue(detail.interpretationRef)
                    val referencedTreatments = detail.routes.flatMap(ConstructionRoute::connectionRefs)
                    require(referencedTreatments.all { it in availableTreatments }) {
                        "Chord detail ${detail.interpretationRef} refers to a treatment not owned by its interpretation"
                    }
                    require(detail.routes.flatMap(ConstructionRoute::sourceRefs).all { it in detail.sourceRefs }) {
                        "Chord detail ${detail.interpretationRef} has a dangling route source"
                    }
                    detail.routes.forEach { route ->
                        val substitutions = route.functionRelations
                            .filterIsInstance<ChordFunctionRelation.SubstitutesFor>()
                        if (substitutions.isNotEmpty()) {
                            val registry = requireNotNull(treatmentRegistry) {
                                "Chord detail ${detail.interpretationRef} declares function substitutions without a treatment registry"
                            }
                            val resolved = registry.resolve(route.connectionRefs.toSet())
                            require(substitutions.all { it.targetTreatmentId in resolved.substitutionTargets }) {
                                "Chord detail ${detail.interpretationRef} declares a substitution not provided by its treatments"
                            }
                        }
                        val sonorityPitchClasses = catalog.entries.single { entry ->
                            entry.sonority.id == detail.interpretationRef.sonorityId
                        }.sonority.tones.map { it.spelling.pitchClass.value }.toSet()
                        when (val construction = route.construction) {
                            null -> Unit
                            is ChordConstructionDetail.OmittedFromFormula -> {
                                require(construction.tones.count { it.presence == ConstructionTonePresence.OMITTED } == 1) {
                                    "Omitted-tone construction must declare exactly one omitted tone"
                                }
                                require(construction.tones.single { it.presence == ConstructionTonePresence.OMITTED }.role == ConstructionToneRole.ROOT) {
                                    "The first R4B omitted-tone construction must omit the formula root"
                                }
                                require(
                                    construction.tones
                                        .filter { it.presence == ConstructionTonePresence.SOUNDING }
                                        .map { it.spelling.pitchClass.value }
                                        .toSet() == sonorityPitchClasses
                                ) { "Construction sounding tones must equal the route sonority" }
                            }
                            is ChordConstructionDetail.ModalScaleDegrees -> {
                                val highlighted = construction.degrees
                                    .filter(ModalScaleConstructionTone::chordTone)
                                    .map { it.spelling.pitchClass.value }
                                    .toSet()
                                require(highlighted == sonorityPitchClasses) {
                                    "Highlighted modal degrees $highlighted must equal route sonority " +
                                        "$sonorityPitchClasses for ${detail.interpretationRef}"
                                }
                            }
                            is ChordConstructionDetail.MinorSubdominantRelation -> require(
                                construction.borrowedTones
                                    .map { it.spelling.pitchClass.value }
                                    .toSet() == sonorityPitchClasses
                            ) { "Borrowed construction tones must equal the route sonority" }
                            is ChordConstructionDetail.AugmentedSixthDerivation -> require(
                                construction.augmentedSixthTones
                                    .map { it.spelling.pitchClass.value }
                                    .toSet() == sonorityPitchClasses
                            ) { "Augmented-sixth construction tones must equal the route sonority" }
                        }
                    }
                    DefinitionRecord(detail, contribution.chapter)
                }
            }
            require(records.map { it.detail.interpretationRef }.distinct().size == records.size) {
                "Chord knowledge interpretation references must be unique"
            }
            val routeIds = records.flatMap { it.detail.routes }.map(ConstructionRoute::id)
            require(routeIds.distinct().size == routeIds.size) { "Construction route ids must be globally unique" }
            return ChordKnowledgeCatalog(records.associateBy { it.detail.interpretationRef }, discoveryIndex)
        }
    }
}
