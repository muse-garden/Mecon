package com.mecon.theory.harmony

import com.mecon.theory.ChordDefinition
import com.mecon.theory.ChordMemberId
import com.mecon.theory.DefinedSonority
import com.mecon.theory.SpelledPitchClass

data class ChordSonority(
    val id: SonorityId,
    val toneSetKey: SpelledToneSetKey,
    val tones: List<SpelledSonorityTone>,
    /** Compatibility structural projection selected from the first construction in the group. */
    val definition: ChordDefinition,
    val spelledRoot: SpelledPitchClass,
) {
    init {
        require(tones.isNotEmpty()) { "ChordSonority must contain at least one tone" }
        require(tones.map(SpelledSonorityTone::id).toSet().size == tones.size) {
            "ChordSonority tone ids must be unique"
        }
        require(SpelledToneSetKey.from(tones.map(SpelledSonorityTone::spelling)) == toneSetKey) {
            "ChordSonority toneSetKey must match its tones"
        }
    }

    val definedSonority: DefinedSonority by lazy {
        definition.instantiate(spelledRoot)
    }

    val toneIds: Set<SonorityToneId> get() = tones.mapTo(linkedSetOf(), SpelledSonorityTone::id)

    fun tone(id: SonorityToneId): SpelledSonorityTone? =
        tones.firstOrNull { it.id == id }

    fun toneIdForMember(memberId: ChordMemberId): SonorityToneId? =
        definedSonority.spelledMembers[memberId]?.let { SonorityToneId.from(it) }
}

data class ChordCatalogEntry(
    val sonority: ChordSonority,
    val interpretations: List<ChordInterpretation>,
    val constructionTraces: List<ConstructionTrace>,
) {
    init {
        require(interpretations.isNotEmpty()) { "ChordCatalogEntry must contain an interpretation" }
        require(interpretations.map(ChordInterpretation::id).toSet().size == interpretations.size) {
            "ChordCatalogEntry interpretation ids must be unique"
        }
        require(constructionTraces.isNotEmpty()) { "ChordCatalogEntry must retain construction provenance" }
        require(
            interpretations
                .flatMap { it.toneRoles.values }
                .all { it in sonority.toneIds }
        ) {
            "Chord interpretation tone roles must refer to the collected sonority"
        }
        require(
            interpretations
                .flatMap(ChordInterpretation::structuralToneOrder)
                .all { it in sonority.toneIds }
        ) {
            "Chord interpretation structural order must refer to the collected sonority"
        }
    }
}

data class ChordCatalog(
    val entries: List<ChordCatalogEntry>,
) {
    init {
        require(entries.map { it.sonority.id }.toSet().size == entries.size) {
            "ChordCatalog sonority ids must be unique"
        }
        val interpretationIds = entries.flatMap { entry ->
            entry.interpretations.map(ChordInterpretation::id)
        }
        require(interpretationIds.toSet().size == interpretationIds.size) {
            "ChordCatalog interpretation ids must be globally unique"
        }
    }

    private val bySonorityId: Map<SonorityId, ChordCatalogEntry> by lazy {
        entries.associateBy { it.sonority.id }
    }
    private val byInterpretationId: Map<InterpretationId, Pair<ChordCatalogEntry, ChordInterpretation>> by lazy {
        buildMap {
            this@ChordCatalog.entries.forEach { entry ->
                entry.interpretations.forEach { interpretation ->
                    put(interpretation.id, entry to interpretation)
                }
            }
        }
    }

    fun entry(id: SonorityId): ChordCatalogEntry? = bySonorityId[id]

    fun interpretation(id: InterpretationId): Pair<ChordCatalogEntry, ChordInterpretation>? =
        byInterpretationId[id]
}

object ChordCatalogCollector {
    fun collect(chords: Iterable<ConstructedChord>): ChordCatalog {
        val constructed = chords.toList()
        if (constructed.isEmpty()) return ChordCatalog(emptyList())
        val entries = constructed
            .groupBy(ConstructedChord::spelledToneSetKey)
            .entries
            .sortedBy { it.key.value }
            .map { (toneSetKey, group) ->
                val canonical = group.first()
                val tones = canonical.spelledTones
                    .map { spelling ->
                        SpelledSonorityTone(SonorityToneId.from(spelling), spelling)
                    }
                    .sortedWith(
                        compareBy<SpelledSonorityTone>(
                            { it.spelling.noteName.ordinal },
                            { it.spelling.chromaticOffset },
                        )
                    )
                val sonority = ChordSonority(
                    id = SonorityId("sonority.${toneSetKey.value}"),
                    toneSetKey = toneSetKey,
                    tones = tones,
                    definition = canonical.definition,
                    spelledRoot = canonical.spelledRoot,
                )
                val interpretations = group
                    .map(ConstructedChord::interpretation)
                    .groupBy(ChordInterpretation::id)
                    .map { (id, duplicates) ->
                        require(duplicates.distinct().size == 1) {
                            "Interpretation $id has conflicting definitions in one catalog"
                        }
                        duplicates.first()
                    }
                    .sortedBy { it.id.value }
                ChordCatalogEntry(
                    sonority = sonority,
                    interpretations = interpretations,
                    constructionTraces = group.map(ConstructedChord::trace).distinct(),
                )
            }
        return ChordCatalog(entries)
    }
}
