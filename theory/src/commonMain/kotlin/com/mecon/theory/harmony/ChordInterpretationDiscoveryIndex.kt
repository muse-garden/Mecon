package com.mecon.theory.harmony

data class DiscoveredChordInterpretation(
    val ref: ChordInterpretationRef,
    val audibleKey: AudibleSonorityKey,
    val origins: Set<ChordSelectionOriginRef>,
    internal val placements: List<ChordInterpretationPlacement>,
)

data class ChordInterpretationPlacement(
    val origin: ChordSelectionOriginRef,
    val chapter: ChordChapterDescriptor,
    val categoryOrderWithinChapter: Int,
)

/** Global, spelling-neutral reverse index built from every selection contribution. */
class ChordInterpretationDiscoveryIndex private constructor(
    private val byAudibleKey: Map<AudibleSonorityKey, List<DiscoveredChordInterpretation>>,
    private val audibleKeyByRef: Map<ChordInterpretationRef, AudibleSonorityKey>,
) {
    fun discover(query: SoundingInterpretationQuery): List<DiscoveredChordInterpretation> {
        val discovered = byAudibleKey[query.audibleKey].orEmpty()
        query.pinnedInterpretationRef?.let { pinned ->
            require(audibleKeyByRef[pinned] == query.audibleKey) {
                "Pinned interpretation does not match the selected audible sonority"
            }
            return discovered.filter { it.ref == pinned }
        }
        val selectedCategory = query.selectedOrigin?.categoryId
        return discovered.sortedWith(
            compareBy<DiscoveredChordInterpretation>(
                { item -> if (selectedCategory != null && item.origins.any { it.categoryId == selectedCategory }) 0 else 1 },
                { item -> item.placements.minOf { it.chapter.order } },
                { item -> item.placements.minOf { it.categoryOrderWithinChapter } },
                { item -> item.ref.interpretationId.value },
                { item -> item.ref.sonorityId.value },
            )
        )
    }

    fun audibleKey(ref: ChordInterpretationRef): AudibleSonorityKey? = audibleKeyByRef[ref]

    companion object {
        val EMPTY: ChordInterpretationDiscoveryIndex = ChordInterpretationDiscoveryIndex(emptyMap(), emptyMap())

        fun create(groups: List<ChordSelectionGroup>): ChordInterpretationDiscoveryIndex {
            data class Raw(
                val ref: ChordInterpretationRef,
                val audibleKey: AudibleSonorityKey,
                val placement: ChordInterpretationPlacement,
            )

            val raw = groups.flatMap { group ->
                group.chords.flatMap { choice ->
                    choice.interpretationRefs.map { ref ->
                        Raw(
                            ref = ref,
                            audibleKey = choice.audibleKey,
                            placement = ChordInterpretationPlacement(
                                origin = choice.origin,
                                chapter = group.chapter,
                                categoryOrderWithinChapter = group.orderWithinChapter,
                            ),
                        )
                    }
                }
            }
            val conflicting = raw.groupBy(Raw::ref).filterValues { records ->
                records.map(Raw::audibleKey).distinct().size > 1
            }
            require(conflicting.isEmpty()) {
                "An interpretation cannot belong to multiple audible sonorities: ${conflicting.keys}"
            }
            val discovered = raw.groupBy { it.audibleKey to it.ref }.map { (key, records) ->
                DiscoveredChordInterpretation(
                    ref = key.second,
                    audibleKey = key.first,
                    origins = records.mapTo(linkedSetOf()) { it.placement.origin },
                    placements = records.map(Raw::placement).distinct(),
                )
            }
            return ChordInterpretationDiscoveryIndex(
                byAudibleKey = discovered.groupBy(DiscoveredChordInterpretation::audibleKey),
                audibleKeyByRef = discovered.associate { it.ref to it.audibleKey },
            )
        }
    }
}

data class ChordCatalogSnapshot(
    val selectionGroups: List<ChordSelectionGroup>,
    val discoveryIndex: ChordInterpretationDiscoveryIndex,
    val knowledgeCatalog: ChordKnowledgeCatalog,
) {
    fun resolve(
        query: SoundingInterpretationQuery,
        context: ChordKnowledgeContext,
    ): ChordDetailModel = knowledgeCatalog.resolve(query, context)

    companion object {
        fun create(
            key: com.mecon.theory.ModulationKey,
            selectionProviders: List<ChordCatalogChapterProvider> = ChordCatalogChapterDiscovery.discover(),
            knowledgeProviders: List<ChordKnowledgeChapterProvider> = ChordKnowledgeChapterDiscovery.discover(),
            treatmentRegistry: HarmonicTreatmentRegistry,
        ): ChordCatalogSnapshot {
            val groups = ChordSelectionCatalog.groups(key, selectionProviders)
            val index = ChordInterpretationDiscoveryIndex.create(groups)
            // Selection contributions currently derive interpretation ids in this stable lens.
            // Knowledge must use the same lens or exact refs cannot join across the snapshot.
            val context = ChordKnowledgeContext(key.chordSelectionTonalContext())
            return ChordCatalogSnapshot(
                selectionGroups = groups,
                discoveryIndex = index,
                knowledgeCatalog = ChordKnowledgeCatalog.create(
                    context,
                    knowledgeProviders,
                    index,
                    treatmentRegistry,
                ),
            )
        }
    }
}
