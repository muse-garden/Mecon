package com.mecon.theory.constraint

import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.BuiltInChordDefinitions
import com.mecon.theory.ChordDefinition
import com.mecon.theory.ChordDefinitionId
import com.mecon.theory.Key
import com.mecon.theory.Mode
import com.mecon.theory.NaturalTriads
import com.mecon.theory.TonalContext
import com.mecon.theory.TonalContextId
import com.mecon.theory.harmony.ChordBuilder
import com.mecon.theory.harmony.ChordCatalog
import com.mecon.theory.harmony.ChordCatalogCollector
import com.mecon.theory.harmony.ChordConstructionContext
import com.mecon.theory.harmony.ChordInterpretation
import com.mecon.theory.harmony.ChordRecipeId
import com.mecon.theory.harmony.ConstructedChord
import com.mecon.theory.harmony.ConstructionTrace
import com.mecon.theory.harmony.FunctionalChordSymbol
import com.mecon.theory.harmony.HarmonicFunction
import com.mecon.theory.harmony.InterpretationId
import com.mecon.theory.harmony.InterpretationTag
import com.mecon.theory.harmony.InterpretationTrace
import com.mecon.theory.harmony.TonalLens

/**
 * Natural tertian recipe and the collection boundary for a free-harmony vocabulary.
 *
 * Every enabled family contributes constructions before collection. This is what lets a
 * diatonic sonority and a chromatic-functional reading share one physical catalog entry.
 */
object DiatonicChordVocabulary {
    fun forContext(
        context: TonalContext,
        compatibilityKey: Key,
        includeSevenths: Boolean = true,
        includeInversions: Boolean = true,
        includeSecondaryHarmony: Boolean = false,
        includeModalSecondaryColors: Boolean = true,
        includeDiminishedSevenths: Boolean = false,
    ): List<InterpretedChordTarget> =
        catalog(
            context = context,
            compatibilityKey = compatibilityKey,
            includeSevenths = includeSevenths,
            includeSecondaryHarmony = includeSecondaryHarmony,
            includeModalSecondaryColors = includeModalSecondaryColors,
            includeDiminishedSevenths = includeDiminishedSevenths,
        ).toInterpretedTargets(compatibilityKey, includeInversions)

    fun catalog(
        context: TonalContext,
        compatibilityKey: Key,
        includeSevenths: Boolean = true,
        includeSecondaryHarmony: Boolean = false,
        includeModalSecondaryColors: Boolean = true,
        includeDiminishedSevenths: Boolean = false,
    ): ChordCatalog {
        require(context.scale.degrees.size == 7) {
            "Diatonic tertian vocabulary currently requires a seven-degree scale"
        }
        val constructions = buildList {
            addAll(constructedChords(context, includeSevenths))
            if (includeSecondaryHarmony) {
                addAll(
                    SecondaryHarmonyVocabulary.constructedChords(
                        context = context,
                        compatibilityKey = compatibilityKey,
                        includeModalColorChords = includeModalSecondaryColors,
                    )
                )
            }
            if (includeDiminishedSevenths) {
                addAll(RootlessDominantNinthVocabulary.constructedChords(context))
            }
        }
        return ChordCatalogCollector.collect(constructions)
    }

    internal fun constructedChords(
        context: TonalContext,
        includeSevenths: Boolean = true,
    ): List<ConstructedChord> {
        val constructionContext = ChordConstructionContext(context)
        val memberCounts = if (includeSevenths) listOf(3, 4) else listOf(3)
        return (1..7).flatMap { degree ->
            memberCounts.map { memberCount ->
                val recipeId = ChordRecipeId("diatonic.tertian")
                val definition = ChordBuilder.tertianDefinition(context, degree, memberCount)
                val root = context.spellDegree(degree)
                val interpretation = ChordInterpretation(
                    id = tertianInterpretationId(context, degree, memberCount),
                    lens = TonalLens(context.id, context),
                    symbol = FunctionalChordSymbol(
                        degree = degree,
                        quality = definition.compatibilityQuality,
                        arity = if (memberCount <= 3) ChordArity.TRIAD else ChordArity.SEVENTH,
                    ),
                    function = when (degree) {
                        1 -> HarmonicFunction.TONIC
                        2, 4 -> HarmonicFunction.PREDOMINANT
                        5 -> HarmonicFunction.DOMINANT
                        7 -> HarmonicFunction.LEADING
                        else -> HarmonicFunction.OTHER
                    },
                    toneRoles = ChordBuilder.structuralToneRoles(definition, root),
                    structuralToneOrder = ChordBuilder.structuralToneOrder(definition, root),
                    tags = setOf(InterpretationTag("function.diatonic")),
                    trace = InterpretationTrace(recipeId, listOf("scale-degree-$degree")),
                )
                ChordBuilder.fromDefinition(
                    context = constructionContext,
                    definition = definition,
                    rootDegree = degree,
                    rootAlteration = 0,
                    interpretation = interpretation,
                    trace = ConstructionTrace(recipeId, listOf("tertian-members-$memberCount")),
                )
            }
        }
    }

    /**
     * Chapter-facing seventh-chord vocabulary. Natural minor uses the union of seventh chords
     * obtained from natural, harmonic, and ascending melodic minor, matching the union already
     * exposed by [naturalTriadUnionConstructions]. Other modes keep their single-scale projection.
     */
    internal fun naturalSeventhUnionConstructions(
        context: TonalContext,
        key: Key,
    ): List<ConstructedChord> {
        if (key.mode != Mode.AEOLIAN) {
            return constructedChords(context, includeSevenths = true)
                .filter { it.definition.members.size == 4 }
        }

        data class SeventhSpec(
            val degree: Int,
            val rootAlteration: Int,
            val definition: ChordDefinition,
        )

        // Keep the original natural-minor constructions (and therefore their persisted sonority
        // and interpretation ids) intact. Only genuinely new harmonic/melodic sonorities receive
        // the union-specific ids below.
        val naturalConstructions = constructedChords(context, includeSevenths = true)
            .filter { it.definition.members.size == 4 }
        val naturalSignatures = naturalConstructions.mapTo(mutableSetOf()) { construction ->
            construction.spelledRoot.pitchClass.value to
                construction.definition.members.map { it.semitones }
        }
        val specs = listOf(Mode.HARMONIC_MINOR, Mode.MELODIC_MINOR)
            .flatMap { mode ->
                val formContext = TonalContext.fromKey(
                    key = Key(key.root, mode),
                    tonicSpelling = context.tonic,
                    id = TonalContextId("${context.id.value}.${mode.name.lowercase()}"),
                )
                (1..7).map { degree ->
                    val formRoot = formContext.spellDegree(degree)
                    val naturalRoot = context.spellDegree(degree)
                    val rawDelta = (formRoot.pitchClass.value - naturalRoot.pitchClass.value).mod(12)
                    val rootAlteration = if (rawDelta > 6) rawDelta - 12 else rawDelta
                    val definition = ChordBuilder.tertianDefinition(
                        context = formContext,
                        rootDegree = degree,
                        memberCount = 4,
                    )
                    SeventhSpec(degree, rootAlteration, definition)
                }
            }
            .distinctBy { spec ->
                listOf(spec.degree, spec.rootAlteration) +
                    spec.definition.members.map { it.semitones }
            }
            .filterNot { spec ->
                val rootPitchClass = context.spellDegree(spec.degree, spec.rootAlteration).pitchClass.value
                (rootPitchClass to spec.definition.members.map { it.semitones }) in naturalSignatures
            }

        val constructionContext = ChordConstructionContext(context)
        return naturalConstructions + specs.map { spec ->
            val recipeId = ChordRecipeId("diatonic.natural-seventh-union")
            val intervals = spec.definition.members.joinToString("-") { it.semitones.toString() }
            val definition = spec.definition.copy(
                id = ChordDefinitionId(
                    "${context.id.value}.minor-union.degree-${spec.degree}.4.$intervals"
                )
            )
            val root = context.spellDegree(spec.degree, spec.rootAlteration)
            val interpretation = ChordInterpretation(
                id = naturalSeventhUnionInterpretationId(
                    context = context,
                    degree = spec.degree,
                    rootAlteration = spec.rootAlteration,
                    intervals = intervals,
                ),
                lens = TonalLens(context.id, context),
                symbol = FunctionalChordSymbol(
                    degree = spec.degree,
                    alteration = 0,
                    quality = definition.compatibilityQuality,
                    arity = ChordArity.SEVENTH,
                ),
                function = when (spec.degree) {
                    1 -> HarmonicFunction.TONIC
                    2, 4 -> HarmonicFunction.PREDOMINANT
                    5 -> HarmonicFunction.DOMINANT
                    7 -> HarmonicFunction.LEADING
                    else -> HarmonicFunction.OTHER
                },
                toneRoles = ChordBuilder.structuralToneRoles(definition, root),
                structuralToneOrder = ChordBuilder.structuralToneOrder(definition, root),
                tags = setOf(InterpretationTag("function.diatonic")),
                trace = InterpretationTrace(
                    recipeId,
                    listOf("scale-degree-${spec.degree}", "intervals-$intervals"),
                ),
            )
            ChordBuilder.fromDefinition(
                context = constructionContext,
                definition = definition,
                rootDegree = spec.degree,
                rootAlteration = spec.rootAlteration,
                interpretation = interpretation,
                trace = ConstructionTrace(recipeId, listOf("tertian-members-4", "intervals-$intervals")),
            )
        }
    }

    /**
     * Chapter-facing triad vocabulary. In minor this intentionally uses the union of natural,
     * harmonic, and melodic-minor forms owned by [NaturalTriads], while [constructedChords]
     * remains the strict tertian projection of one [TonalContext] scale.
     */
    internal fun naturalTriadUnionConstructions(
        context: TonalContext,
        key: Key,
    ): List<ConstructedChord> {
        val constructionContext = ChordConstructionContext(context)
        return NaturalTriads.inKey(key).map { triad ->
            val recipeId = ChordRecipeId("diatonic.natural-triad-union")
            val definition = BuiltInChordDefinitions.forQuality(triad.quality)
            val naturalRoot = context.spellDegree(triad.degree)
            val rawDelta = (triad.root.value - naturalRoot.pitchClass.value).mod(12)
            val rootAlteration = if (rawDelta > 6) rawDelta - 12 else rawDelta
            val root = context.spellDegree(triad.degree, rootAlteration)
            val interpretation = ChordInterpretation(
                id = naturalTriadUnionInterpretationId(
                    context = context,
                    degree = triad.degree,
                    rootAlteration = rootAlteration,
                    quality = triad.quality,
                ),
                lens = TonalLens(context.id, context),
                symbol = FunctionalChordSymbol(
                    degree = triad.degree,
                    // Raised minor forms are native variants of the local degree (V, vii°),
                    // not chromatic applied roots such as ♯V or ♯vii°.
                    alteration = 0,
                    quality = triad.quality,
                    arity = ChordArity.TRIAD,
                ),
                function = when (triad.degree) {
                    1 -> HarmonicFunction.TONIC
                    2, 4 -> HarmonicFunction.PREDOMINANT
                    5 -> HarmonicFunction.DOMINANT
                    7 -> HarmonicFunction.LEADING
                    else -> HarmonicFunction.OTHER
                },
                toneRoles = ChordBuilder.structuralToneRoles(definition, root),
                structuralToneOrder = ChordBuilder.structuralToneOrder(definition, root),
                tags = setOf(InterpretationTag("function.diatonic")),
                trace = InterpretationTrace(
                    recipeId,
                    buildList {
                        add("scale-degree-${triad.degree}")
                        triad.scaleForms.forEach { add("minor-form-${it.name.lowercase()}") }
                        triad.minorAlterations.forEach { add(it.name.lowercase()) }
                    },
                ),
            )
            ChordBuilder.fromDefinition(
                context = constructionContext,
                definition = definition,
                rootDegree = triad.degree,
                rootAlteration = rootAlteration,
                interpretation = interpretation,
                trace = ConstructionTrace(
                    recipeId,
                    listOf("quality-${triad.quality.name.lowercase()}"),
                ),
            )
        }
    }

    internal fun tertianInterpretationId(
        context: TonalContext,
        degree: Int,
        memberCount: Int,
    ): InterpretationId =
        InterpretationId("diatonic.${context.id.value}.$degree.$memberCount")

    internal fun naturalTriadUnionInterpretationId(
        context: TonalContext,
        degree: Int,
        rootAlteration: Int,
        quality: ChordQuality,
    ): InterpretationId =
        InterpretationId(
            "diatonic-union.${context.id.value}.$degree." +
                "$rootAlteration.${quality.name.lowercase()}"
        )

    internal fun naturalSeventhUnionInterpretationId(
        context: TonalContext,
        degree: Int,
        rootAlteration: Int,
        intervals: String,
    ): InterpretationId =
        InterpretationId(
            "diatonic-seventh-union.${context.id.value}.$degree.$rootAlteration.$intervals"
        )
}
