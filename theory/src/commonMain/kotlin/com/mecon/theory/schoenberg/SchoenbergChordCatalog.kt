package com.mecon.theory.schoenberg

import com.mecon.theory.BuiltInChordDefinitions
import com.mecon.theory.Key
import com.mecon.theory.Mode
import com.mecon.theory.NaturalTriads
import com.mecon.theory.TonalContext
import com.mecon.theory.constraint.InterpretedChordTarget
import com.mecon.theory.constraint.RootlessDominantNinthVocabulary
import com.mecon.theory.constraint.SecondaryHarmonyVocabulary
import com.mecon.theory.constraint.AugmentedSixthVocabulary
import com.mecon.theory.constraint.DiatonicChordVocabulary
import com.mecon.theory.harmony.ChordBuilder
import com.mecon.theory.harmony.ChordCatalog
import com.mecon.theory.harmony.ChordCatalogCollector
import com.mecon.theory.harmony.ChordConstructionContext
import com.mecon.theory.harmony.ChordInterpretation
import com.mecon.theory.harmony.ChordRecipeId
import com.mecon.theory.harmony.ConstructionTrace
import com.mecon.theory.harmony.ConstructedChord
import com.mecon.theory.harmony.FunctionalChordSymbol
import com.mecon.theory.harmony.HarmonicFunction
import com.mecon.theory.harmony.InterpretationId
import com.mecon.theory.harmony.InterpretationTag
import com.mecon.theory.harmony.InterpretationTrace
import com.mecon.theory.harmony.TonalLens

/**
 * Exercise-level collection boundary.
 *
 * Every enabled construction family contributes before collection, so equal spelled sonorities
 * share one physical entry while their functional readings remain separate search targets.
 */
internal object SchoenbergChordCatalog {
    fun collect(
        key: Key,
        requestedChords: List<SchoenbergSymbolicChord>,
        tonalContext: TonalContext = TonalContext.fromKey(key),
        useSelectionInterpretationIds: Boolean = false,
    ): ChordCatalog {
        val constructions = buildList {
            requestedChords
                .filter {
                    it.secondaryFamily == null &&
                        it.augmentedSixthFamily == null &&
                        !it.isRootlessDominantNinth() &&
                        Mode.AEOLIAN !in it.modalOrigins
                }
                .filterNot {
                    useSelectionInterpretationIds && key.mode == Mode.AEOLIAN &&
                        it.arity == com.mecon.theory.ChordArity.SEVENTH
                }
                .distinctBy {
                    it.diatonicInterpretationId(tonalContext, key, useSelectionInterpretationIds)
                }
                .mapTo(this) {
                    it.constructDiatonic(tonalContext, key, useSelectionInterpretationIds)
                }
            if (
                useSelectionInterpretationIds && key.mode == Mode.AEOLIAN &&
                requestedChords.any {
                    it.secondaryFamily == null && it.augmentedSixthFamily == null &&
                        !it.isRootlessDominantNinth() && Mode.AEOLIAN !in it.modalOrigins &&
                        it.arity == com.mecon.theory.ChordArity.SEVENTH
                }
            ) {
                addAll(DiatonicChordVocabulary.naturalSeventhUnionConstructions(tonalContext, key))
            }
            if (
                requestedChords.any {
                    it.secondaryFamily == null && Mode.AEOLIAN in it.modalOrigins
                }
            ) {
                addAll(SchoenbergMinorSubdominantChapter.constructedChords(tonalContext, key))
            }
            addAll(
                SecondaryHarmonyVocabulary.constructedChords(
                    context = tonalContext,
                    compatibilityKey = key,
                )
            )
            addAll(RootlessDominantNinthVocabulary.constructedChords(tonalContext))
            if (requestedChords.any { it.augmentedSixthFamily != null }) {
                addAll(AugmentedSixthVocabulary.constructedChords(tonalContext))
            }
        }
        return ChordCatalogCollector.collect(constructions)
    }

    fun targets(
        key: Key,
        chords: List<SchoenbergSymbolicChord>,
        tonalContext: TonalContext = TonalContext.fromKey(key),
        useSelectionInterpretationIds: Boolean = false,
    ): List<InterpretedChordTarget> {
        val catalog = collect(key, chords, tonalContext, useSelectionInterpretationIds)
        return targets(key, chords, catalog, tonalContext, useSelectionInterpretationIds)
    }

    fun targets(
        key: Key,
        chords: List<SchoenbergSymbolicChord>,
        catalog: ChordCatalog,
        tonalContext: TonalContext = TonalContext.fromKey(key),
        useSelectionInterpretationIds: Boolean = false,
    ): List<InterpretedChordTarget> {
        val interpretationsById = catalog.entries
            .flatMap { entry -> entry.interpretations.map { it.id to (entry to it) } }
            .toMap()
        return chords.map { chord ->
            val interpretationId = chord.interpretationId(
                tonalContext,
                key,
                useSelectionInterpretationIds,
            )
            val (entry, interpretation) = interpretationsById[interpretationId]
                ?: error("No catalog interpretation $interpretationId for $chord")
            val inversion = chord.inversionIndex()
            val bassTone = interpretation.structuralToneOrder.getOrNull(inversion)
                ?: error("Invalid inversion $inversion for $interpretationId")
            InterpretedChordTarget(key, entry, interpretation, bassTone)
        }
    }

    private fun SchoenbergSymbolicChord.interpretationId(
        context: TonalContext,
        key: Key,
        useSelectionInterpretationIds: Boolean,
    ): InterpretationId {
        rootlessDominantNinthUsageId?.let { usageId ->
            val type = RootlessDominantNinthVocabulary.types(context)
                .firstOrNull { it.id == usageId }
                ?: error("Unknown rootless dominant-ninth use $usageId")
            return with(RootlessDominantNinthVocabulary) { type.interpretationId() }
        }
        secondaryFamily?.let { family ->
            val type = SecondaryHarmonyVocabulary.harmonyTypes(context)
                .firstOrNull {
                    it.family == family &&
                        it.tonicizedDegree == appliedToDegree &&
                        it.rootDegree == degree &&
                        it.rootAlteration == rootAlteration &&
                        it.quality == quality &&
                        it.arity == arity &&
                        it.modalOrigins == modalOrigins
                }
                ?: error("Unknown secondary-harmony interpretation for $this")
            return with(SecondaryHarmonyVocabulary) { type.interpretationId() }
        }
        augmentedSixthFamily?.let { family ->
            val type = AugmentedSixthVocabulary.types(context)
                .firstOrNull {
                    it.family == family &&
                        it.targetDegree == appliedToDegree
                }
                ?: error("Unknown augmented-sixth interpretation for $this")
            return AugmentedSixthVocabulary.interpretationId(type)
        }
        if (Mode.AEOLIAN in modalOrigins) {
            return SchoenbergMinorSubdominantChapter.interpretationId(this)
        }
        return diatonicInterpretationId(context, key, useSelectionInterpretationIds)
    }

    private fun SchoenbergSymbolicChord.inversionIndex(): Int =
        if (arity == com.mecon.theory.ChordArity.TRIAD) {
            position.ordinal
        } else {
            (seventhPosition ?: com.mecon.theory.textbook.TextbookSeventhPosition.ROOT_POSITION).ordinal
        }

    private fun SchoenbergSymbolicChord.constructDiatonic(
        tonalContext: TonalContext,
        key: Key,
        useSelectionInterpretationIds: Boolean,
    ): ConstructedChord {
        val recipeId = ChordRecipeId("schoenberg.diatonic")
        val definition = BuiltInChordDefinitions.forQuality(quality)
        val resolvedRootAlteration = resolvedDiatonicRootAlteration(tonalContext, key)
        val root = tonalContext.spellDegree(degree, resolvedRootAlteration)
        val interpretation = ChordInterpretation(
            id = diatonicInterpretationId(tonalContext, key, useSelectionInterpretationIds),
            lens = TonalLens(tonalContext.id, tonalContext),
            symbol = FunctionalChordSymbol(
                degree = degree,
                alteration = rootAlteration,
                quality = quality,
                arity = arity,
            ),
            function = when (degree) {
                1 -> HarmonicFunction.TONIC
                4 -> HarmonicFunction.PREDOMINANT
                5, 7 -> HarmonicFunction.DOMINANT
                else -> HarmonicFunction.OTHER
            },
            toneRoles = ChordBuilder.structuralToneRoles(definition, root),
            structuralToneOrder = ChordBuilder.structuralToneOrder(definition, root),
            treatmentIds = setOf(
                if (degree in setOf(5, 7)) {
                    SchoenbergHarmonicTreatments.DIATONIC_DOMINANT
                } else {
                    SchoenbergHarmonicTreatments.DIATONIC
                }
            ),
            tags = setOf(InterpretationTag("function.diatonic")),
            trace = InterpretationTrace(recipeId, listOf("scale-degree-$degree")),
        )
        return ChordBuilder.fromDefinition(
            context = ChordConstructionContext(tonalContext),
            definition = definition,
            rootDegree = degree,
            rootAlteration = resolvedRootAlteration,
            interpretation = interpretation,
            trace = ConstructionTrace(recipeId, listOf("quality-${quality.name.lowercase()}")),
        )
    }

    private fun SchoenbergSymbolicChord.diatonicInterpretationId(
        context: TonalContext,
        key: Key,
        useSelectionInterpretationIds: Boolean,
    ): InterpretationId {
        if (!useSelectionInterpretationIds) {
            return InterpretationId(
                "diatonic.$degree.$rootAlteration.${quality.name.lowercase()}.${arity.name.lowercase()}"
            )
        }
        if (arity == com.mecon.theory.ChordArity.SEVENTH) {
            if (key.mode == Mode.AEOLIAN) {
                val resolvedRootAlteration = resolvedDiatonicRootAlteration(context, key)
                return DiatonicChordVocabulary.naturalSeventhUnionConstructions(context, key)
                    .firstOrNull { construction ->
                        val interpretation = construction.interpretation
                        val naturalRoot = context.spellDegree(degree).chromaticOffset
                        interpretation.symbol.degree == degree &&
                            construction.definition.compatibilityQuality == quality &&
                            construction.spelledRoot.chromaticOffset - naturalRoot == resolvedRootAlteration
                    }
                    ?.interpretation
                    ?.id
                    ?: error("No natural-minor seventh interpretation for $this")
            }
            return DiatonicChordVocabulary.tertianInterpretationId(context, degree, memberCount = 4)
        }
        return DiatonicChordVocabulary.naturalTriadUnionInterpretationId(
            context = context,
            degree = degree,
            rootAlteration = resolvedDiatonicRootAlteration(context, key),
            quality = quality,
        )
    }

    private fun SchoenbergSymbolicChord.resolvedDiatonicRootAlteration(
        context: TonalContext,
        key: Key,
    ): Int = if (rootAlteration != 0) {
        rootAlteration
    } else {
        NaturalTriads.inKey(key)
            .firstOrNull { it.degree == degree && it.quality == quality }
            ?.let { triad ->
                val natural = context.spellDegree(degree).pitchClass.value
                val rawDelta = (triad.root.value - natural).mod(12)
                if (rawDelta > 6) rawDelta - 12 else rawDelta
            }
            ?: 0
    }
}
