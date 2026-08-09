package com.mecon.theory.constraint

import com.mecon.theory.BuiltInChordDefinitions
import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.Key
import com.mecon.theory.Mode
import com.mecon.theory.SpelledPitchClass
import com.mecon.theory.TonalContext
import com.mecon.theory.harmony.ChordBuilder
import com.mecon.theory.harmony.ChordCatalog
import com.mecon.theory.harmony.ChordCatalogCollector
import com.mecon.theory.harmony.ChordConstructionContext
import com.mecon.theory.harmony.ChordInterpretation
import com.mecon.theory.harmony.ChordRecipeId
import com.mecon.theory.harmony.ConstructedChord
import com.mecon.theory.harmony.ConstructionTrace
import com.mecon.theory.harmony.FunctionalChordSymbol
import com.mecon.theory.harmony.FunctionalToneRole
import com.mecon.theory.harmony.HarmonicFunction
import com.mecon.theory.harmony.InterpretationId
import com.mecon.theory.harmony.InterpretationTag
import com.mecon.theory.harmony.InterpretationTrace
import com.mecon.theory.harmony.ModalScalePath
import com.mecon.theory.harmony.TonalLens
import com.mecon.theory.schoenberg.SchoenbergHarmonicTreatments

/**
 * Functional family of a chord derived from the church-mode route used by Schoenberg.
 *
 * [SECONDARY_DOMINANT] and [SECONDARY_LEADING] are the ordinary V(/7)/x and vii°(/7)/x
 * families. [MODAL_AUGMENTED] keeps the augmented triads exposed by the altered ascending
 * modes, while [MODAL_DESCENDING_DOMINANT] contains the Dorian/Lydian descending
 * 5-b7-2 sonority.
 */
enum class SecondaryHarmonyFamily {
    SECONDARY_DOMINANT,
    SECONDARY_LEADING,
    MODAL_AUGMENTED,
    MODAL_DESCENDING_DOMINANT,
}

/**
 * Immutable item in the shared harmony-type catalog. It is deliberately independent of
 * inversion: callers can expose a compact type list or expand it to concrete chord targets.
 */
data class SecondaryHarmonyType(
    val family: SecondaryHarmonyFamily,
    val tonicizedDegree: Int,
    val rootDegree: Int,
    val rootAlteration: Int,
    val quality: ChordQuality,
    val arity: ChordArity,
    val modalOrigins: Set<Mode>,
    val modalPath: ModalScalePath = ModalScalePath.ASCENDING,
) {
    init {
        require(tonicizedDegree in 1..7)
        require(rootDegree in 1..7)
        require(rootAlteration in -2..2)
    }

    val id: String
        get() = buildString {
            append(family.name.lowercase())
            append('.')
            append(tonicizedDegree)
            append('.')
            append(rootDegree)
            if (rootAlteration != 0) append(if (rootAlteration > 0) ".sharp$rootAlteration" else ".flat${-rootAlteration}")
            append('.')
            append(quality.name.lowercase())
            if (modalPath == ModalScalePath.DESCENDING) append(".descending")
        }
}

data class SecondaryHarmonyModalDerivation(
    val mode: Mode,
    val path: ModalScalePath,
    val degrees: List<SpelledPitchClass>,
) {
    init {
        require(degrees.size == 7)
    }
}

/** Stable metadata shared by the Schoenberg and free-solver rule adapters. */
object SecondaryHarmonyMetadata {
    const val FAMILY_NAME: String = "secondaryFamily"
    const val TONICIZED_DEGREE_NAME: String = "tonicizedDegree"
    const val ROOT_ALTERATION_NAME: String = "rootAlteration"
    const val MODAL_ORIGINS_NAME: String = "modalOrigins"

    fun familyOf(target: ChordTarget): SecondaryHarmonyFamily? =
        (target as? InterpretedChordTarget)
            ?.interpretation
            ?.attributes
            ?.get(FAMILY_NAME)
            ?.let(SecondaryHarmonyFamily::valueOf)

    fun tonicizedDegreeOf(target: ChordTarget): Int? =
        (target as? InterpretedChordTarget)?.interpretation?.lens?.tonicizedDegree
}

/**
 * Shared secondary-harmony catalog.
 *
 * The ordinary type catalog follows the compact construction: for every natural target degree
 * except 7 (normally not tonicized), build a major triad, dominant seventh, diminished leading
 * triad, and leading seventh. Concrete secondary vocabularies omit the primary V/I by default
 * to avoid duplicating the diatonic dominant. The richer modal route additionally derives
 * augmented triads and the Dorian/Lydian descending 5-b7-2 sonority.
 */
object SecondaryHarmonyVocabulary {
    private data class HarmonyTypeIdentity(
        val family: SecondaryHarmonyFamily,
        val tonicizedDegree: Int,
        val rootDegree: Int,
        val rootAlteration: Int,
        val quality: ChordQuality,
        val arity: ChordArity,
        val modalPath: ModalScalePath,
    )

    private val CHURCH_MODES = listOf(
        Mode.IONIAN,
        Mode.DORIAN,
        Mode.PHRYGIAN,
        Mode.LYDIAN,
        Mode.MIXOLYDIAN,
        Mode.AEOLIAN,
        Mode.LOCRIAN,
    )
    private val MODAL_TARGET_DEGREES = 1..6

    fun harmonyTypes(
        context: TonalContext,
        sourceMode: Mode? = null,
        includeModalColorChords: Boolean = true,
    ): List<SecondaryHarmonyType> {
        require(context.scale.degrees.size == 7) {
            "Secondary harmony currently requires a seven-degree tonal context"
        }
        val resolvedSourceMode = sourceMode ?: context.builtInModeOrIonian()
        val functional = MODAL_TARGET_DEGREES.flatMap { targetDegree ->
            val mode = churchModeForTarget(resolvedSourceMode, targetDegree)
            val dominantRoot = dominantRootFor(context, targetDegree)
            val leadingRoot = degreeAndAlteration(
                context = context,
                diatonicDegree = wrapDegree(targetDegree - 1),
                desiredSemitonesFromTonic = context.scale.degree(targetDegree)!!.semitones - 1,
            )
            val ascending = alteredAscendingMode(mode)
            val modalDominantSeventhQuality = seventhQuality(tertianIntervals(ascending, 5, 4))
            val contextPitchClasses = context.scale.degrees.mapTo(linkedSetOf()) { it.semitones.mod(12) }
            val tonicOffset = context.scale.degree(targetDegree)!!.semitones
            val modalDominantIsChromatic = tertianPitchClasses(ascending, 5, 4)
                .map { (tonicOffset + it).mod(12) }
                .any { it !in contextPitchClasses }
            buildList {
                add(
                    SecondaryHarmonyType(
                        family = SecondaryHarmonyFamily.SECONDARY_DOMINANT,
                        tonicizedDegree = targetDegree,
                        rootDegree = dominantRoot.first,
                        rootAlteration = dominantRoot.second,
                        quality = ChordQuality.MAJOR,
                        arity = ChordArity.TRIAD,
                        modalOrigins = setOf(mode),
                    )
                )
                add(
                    SecondaryHarmonyType(
                        family = SecondaryHarmonyFamily.SECONDARY_DOMINANT,
                        tonicizedDegree = targetDegree,
                        rootDegree = dominantRoot.first,
                        rootAlteration = dominantRoot.second,
                        quality = ChordQuality.DOMINANT7,
                        arity = ChordArity.SEVENTH,
                        modalOrigins = setOf(mode),
                    )
                )
                if (
                    modalDominantSeventhQuality != null &&
                    modalDominantSeventhQuality != ChordQuality.DOMINANT7 &&
                    modalDominantIsChromatic
                ) {
                    add(
                        SecondaryHarmonyType(
                            family = SecondaryHarmonyFamily.SECONDARY_DOMINANT,
                            tonicizedDegree = targetDegree,
                            rootDegree = dominantRoot.first,
                            rootAlteration = dominantRoot.second,
                            quality = modalDominantSeventhQuality,
                            arity = ChordArity.SEVENTH,
                            modalOrigins = setOf(mode),
                        )
                    )
                }
                add(
                    SecondaryHarmonyType(
                        family = SecondaryHarmonyFamily.SECONDARY_LEADING,
                        tonicizedDegree = targetDegree,
                        rootDegree = leadingRoot.first,
                        rootAlteration = leadingRoot.second,
                        quality = ChordQuality.DIMINISHED,
                        arity = ChordArity.TRIAD,
                        modalOrigins = setOf(mode),
                    )
                )
                add(
                    SecondaryHarmonyType(
                        family = SecondaryHarmonyFamily.SECONDARY_LEADING,
                        tonicizedDegree = targetDegree,
                        rootDegree = leadingRoot.first,
                        rootAlteration = leadingRoot.second,
                        // The applied leading-tone seventh is defined against its local tonic:
                        // leading tone, minor third, diminished fifth, minor seventh.
                        // Its quality must not drift with the target's church-mode collection.
                        quality = ChordQuality.HALF_DIMINISHED7,
                        arity = ChordArity.SEVENTH,
                        modalOrigins = setOf(mode),
                    )
                )
            }
        }
        if (!includeModalColorChords) return functional
        return mergeModalOrigins(functional + modalColorTypes(context, resolvedSourceMode))
    }

    fun catalog(
        context: TonalContext,
        compatibilityKey: Key,
        includeModalColorChords: Boolean = true,
        includePrimaryDominant: Boolean = false,
    ): ChordCatalog =
        ChordCatalogCollector.collect(
            constructedChords(
                context = context,
                compatibilityKey = compatibilityKey,
                includeModalColorChords = includeModalColorChords,
                includePrimaryDominant = includePrimaryDominant,
            )
        )

    internal fun constructedChords(
        context: TonalContext,
        compatibilityKey: Key,
        includeModalColorChords: Boolean = true,
        includePrimaryDominant: Boolean = false,
    ): List<ConstructedChord> = constructedChordsForContext(
        context = context,
        sourceMode = compatibilityKey.mode,
        includeModalColorChords = includeModalColorChords,
        includePrimaryDominant = includePrimaryDominant,
    )

    internal fun constructedChordsForContext(
        context: TonalContext,
        sourceMode: Mode? = null,
        includeModalColorChords: Boolean = true,
        includePrimaryDominant: Boolean = false,
    ): List<ConstructedChord> {
        val constructionContext = ChordConstructionContext(context)
        return harmonyTypes(
            context = context,
            sourceMode = sourceMode,
            includeModalColorChords = includeModalColorChords,
        )
            .filter { includePrimaryDominant || !it.isPrimaryFunctionalHarmony() }
            .map { type -> type.construct(constructionContext) }
    }

    internal fun modalDerivations(
        context: TonalContext,
        type: SecondaryHarmonyType,
    ): List<SecondaryHarmonyModalDerivation> = type.modalOrigins
        .sortedBy(CHURCH_MODES::indexOf)
        .map { mode ->
            val path = type.modalPath
            val offsets = if (path == ModalScalePath.DESCENDING) {
                descendingMode(mode)
            } else {
                alteredAscendingMode(mode)
            }
            val tonicOffset = context.scale.degree(type.tonicizedDegree)!!.semitones
            val degrees = offsets.mapIndexed { index, modalOffset ->
                val globalDegree = wrapDegree(type.tonicizedDegree + index)
                val (degree, alteration) = degreeAndAlteration(
                    context = context,
                    diatonicDegree = globalDegree,
                    desiredSemitonesFromTonic = tonicOffset + modalOffset,
                )
                context.spellDegree(degree, alteration)
            }
            SecondaryHarmonyModalDerivation(mode, path, degrees)
        }

    private fun SecondaryHarmonyType.construct(
        context: ChordConstructionContext,
    ): ConstructedChord {
        val recipeId = ChordRecipeId("schoenberg.secondary-harmony")
        val definition = BuiltInChordDefinitions.forQuality(quality)
        val root = context.tonalContext.spellDegree(rootDegree, rootAlteration)
        val structuralRoles = ChordBuilder.structuralToneRoles(definition, root)
        val localLeadingRole = when (family) {
            SecondaryHarmonyFamily.SECONDARY_DOMINANT -> FunctionalToneRole.STRUCTURAL_THIRD
            SecondaryHarmonyFamily.SECONDARY_LEADING -> FunctionalToneRole.STRUCTURAL_ROOT
            SecondaryHarmonyFamily.MODAL_AUGMENTED -> FunctionalToneRole.STRUCTURAL_FIFTH
            SecondaryHarmonyFamily.MODAL_DESCENDING_DOMINANT -> null
        }
        val interpretation = ChordInterpretation(
            id = interpretationId(),
            lens = TonalLens(
                contextId = context.tonalContext.id,
                context = context.tonalContext,
                tonicizedDegree = tonicizedDegree,
            ),
            symbol = FunctionalChordSymbol(
                degree = rootDegree,
                alteration = rootAlteration,
                quality = quality,
                arity = arity,
                appliedToDegree = tonicizedDegree,
            ),
            function = when (family) {
                SecondaryHarmonyFamily.SECONDARY_DOMINANT -> HarmonicFunction.DOMINANT
                SecondaryHarmonyFamily.SECONDARY_LEADING -> HarmonicFunction.LEADING
                SecondaryHarmonyFamily.MODAL_AUGMENTED,
                SecondaryHarmonyFamily.MODAL_DESCENDING_DOMINANT,
                -> HarmonicFunction.COLOR
            },
            toneRoles = buildMap {
                putAll(structuralRoles)
                localLeadingRole?.let { role ->
                    structuralRoles[role]?.let { put(FunctionalToneRole.LOCAL_LEADING_TONE, it) }
                }
            },
            structuralToneOrder = ChordBuilder.structuralToneOrder(definition, root),
            treatmentIds = buildSet {
                add(SchoenbergHarmonicTreatments.SECONDARY_HARMONY)
                if (family == SecondaryHarmonyFamily.MODAL_AUGMENTED) {
                    add(SchoenbergHarmonicTreatments.VAGRANT_CHORD)
                }
            },
            tags = buildSet {
                add(InterpretationTag("function.secondary-harmony"))
                add(InterpretationTag("secondary-family.${family.name.lowercase()}"))
                modalOrigins.forEach { add(InterpretationTag("modal-origin.${it.name.lowercase()}")) }
                if (family == SecondaryHarmonyFamily.MODAL_AUGMENTED) {
                    add(InterpretationTag("vagrant-chord"))
                }
            },
            attributes = mapOf(
                SecondaryHarmonyMetadata.FAMILY_NAME to family.name,
                SecondaryHarmonyMetadata.TONICIZED_DEGREE_NAME to tonicizedDegree.toString(),
                SecondaryHarmonyMetadata.ROOT_ALTERATION_NAME to rootAlteration.toString(),
                SecondaryHarmonyMetadata.MODAL_ORIGINS_NAME to modalOrigins.joinToString(",") { it.name },
            ),
            trace = InterpretationTrace(
                recipeId = recipeId,
                derivationSteps = listOf(
                    "tonicize-degree-$tonicizedDegree",
                    "derive-${family.name.lowercase()}",
                ),
            ),
        )
        return ChordBuilder.fromDefinition(
            context = context,
            definition = definition,
            rootDegree = rootDegree,
            rootAlteration = rootAlteration,
            interpretation = interpretation,
            trace = ConstructionTrace(
                recipeId = recipeId,
                derivationSteps = listOf(
                    "root-degree-$rootDegree-alteration-$rootAlteration",
                    "quality-${quality.name.lowercase()}",
                ),
            ),
        )
    }

    internal fun SecondaryHarmonyType.interpretationId(): InterpretationId =
        InterpretationId("secondary.$id")

    private fun modalColorTypes(
        context: TonalContext,
        sourceMode: Mode,
    ): List<SecondaryHarmonyType> = buildList {
        MODAL_TARGET_DEGREES.forEach { targetDegree ->
            val mode = churchModeForTarget(sourceMode, targetDegree)
            val ascending = alteredAscendingMode(mode)
            (1..7).forEach { localRoot ->
                if (triadQuality(tertianIntervals(ascending, localRoot, 3)) == ChordQuality.AUGMENTED) {
                    val globalRoot = wrapDegree(targetDegree + localRoot - 1)
                    val desiredRoot = context.scale.degree(targetDegree)!!.semitones + ascending[localRoot - 1]
                    val root = degreeAndAlteration(context, globalRoot, desiredRoot)
                    add(
                        SecondaryHarmonyType(
                            family = SecondaryHarmonyFamily.MODAL_AUGMENTED,
                            tonicizedDegree = targetDegree,
                            rootDegree = root.first,
                            rootAlteration = root.second,
                            quality = ChordQuality.AUGMENTED,
                            arity = ChordArity.TRIAD,
                            modalOrigins = setOf(mode),
                        )
                    )
                }
            }
        }
        val contextPitchClasses = context.scale.degrees
            .mapTo(linkedSetOf()) { it.semitones.mod(12) }
        listOf(Mode.DORIAN, Mode.LYDIAN).forEach { mode ->
            val descending = descendingMode(mode)
            (1..7).forEach { localRoot ->
                val quality = triadQuality(tertianIntervals(descending, localRoot, 3))
                val chordPitchClasses = tertianPitchClasses(descending, localRoot, 3)
                if (
                    quality == ChordQuality.AUGMENTED &&
                    chordPitchClasses.any { it !in contextPitchClasses }
                ) {
                    val root = degreeAndAlteration(context, localRoot, descending[localRoot - 1])
                    add(
                        SecondaryHarmonyType(
                            family = SecondaryHarmonyFamily.MODAL_AUGMENTED,
                            tonicizedDegree = 1,
                            rootDegree = root.first,
                            rootAlteration = root.second,
                            quality = quality,
                            arity = ChordArity.TRIAD,
                            modalOrigins = setOf(mode),
                            modalPath = ModalScalePath.DESCENDING,
                        )
                    )
                }
            }
            val localRoot = 5
            val chordPitchClasses = tertianPitchClasses(descending, localRoot, 3)
            if (chordPitchClasses.any { it !in contextPitchClasses }) {
                val root = degreeAndAlteration(context, localRoot, descending[localRoot - 1])
                val quality = triadQuality(tertianIntervals(descending, localRoot, 3)) ?: return@forEach
                add(
                    SecondaryHarmonyType(
                        family = SecondaryHarmonyFamily.MODAL_DESCENDING_DOMINANT,
                        tonicizedDegree = 1,
                        rootDegree = root.first,
                        rootAlteration = root.second,
                        quality = quality,
                        arity = ChordArity.TRIAD,
                        modalOrigins = setOf(mode),
                        modalPath = ModalScalePath.DESCENDING,
                    )
                )
            }
        }
    }

    /**
     * Raise the modal leading tone to 11 semitones. If that creates an augmented second above
     * degree 6, raise degree 6 as well. Lydian's lowered seventh belongs to the separately
     * modelled descending color route; unaltered results from this ascending route are filtered.
     */
    private fun alteredAscendingMode(mode: Mode): List<Int> {
        val scale = mode.semitones().toMutableList()
        scale[6] = 11
        if (scale[6] - scale[5] > 2) scale[5] += 1
        return scale
    }

    private fun descendingMode(mode: Mode): List<Int> =
        mode.semitones().toMutableList().apply {
            if (mode == Mode.LYDIAN) this[6] = 10
        }

    private fun tertianIntervals(scale: List<Int>, degree: Int, count: Int): List<Int> {
        val rootIndex = degree - 1
        val root = scale[rootIndex]
        return (0 until count).map { member ->
            val unwrapped = rootIndex + member * 2
            scale[unwrapped.mod(7)] + (unwrapped / 7) * 12 - root
        }
    }

    private fun tertianPitchClasses(scale: List<Int>, degree: Int, count: Int): Set<Int> {
        val rootIndex = degree - 1
        return (0 until count).mapTo(linkedSetOf()) { member ->
            val unwrapped = rootIndex + member * 2
            (scale[unwrapped.mod(7)] + (unwrapped / 7) * 12).mod(12)
        }
    }

    private fun mergeModalOrigins(types: List<SecondaryHarmonyType>): List<SecondaryHarmonyType> =
        types.groupBy { type ->
            HarmonyTypeIdentity(
                family = type.family,
                tonicizedDegree = type.tonicizedDegree,
                rootDegree = type.rootDegree,
                rootAlteration = type.rootAlteration,
                quality = type.quality,
                arity = type.arity,
                modalPath = type.modalPath,
            )
        }.values.map { equivalent ->
            equivalent.first().copy(
                modalOrigins = equivalent.flatMapTo(linkedSetOf(), SecondaryHarmonyType::modalOrigins)
            )
        }

    private fun SecondaryHarmonyType.isPrimaryFunctionalHarmony(): Boolean =
        tonicizedDegree == 1 && family in setOf(
            SecondaryHarmonyFamily.SECONDARY_DOMINANT,
            SecondaryHarmonyFamily.SECONDARY_LEADING,
        )

    private fun triadQuality(intervals: List<Int>): ChordQuality? = when (intervals) {
        listOf(0, 4, 7) -> ChordQuality.MAJOR
        listOf(0, 3, 7) -> ChordQuality.MINOR
        listOf(0, 3, 6) -> ChordQuality.DIMINISHED
        listOf(0, 4, 8) -> ChordQuality.AUGMENTED
        else -> null
    }

    private fun seventhQuality(intervals: List<Int>): ChordQuality? = when (intervals) {
        listOf(0, 4, 7, 11) -> ChordQuality.MAJOR7
        listOf(0, 4, 7, 10) -> ChordQuality.DOMINANT7
        listOf(0, 3, 7, 10) -> ChordQuality.MINOR7
        listOf(0, 3, 6, 10) -> ChordQuality.HALF_DIMINISHED7
        listOf(0, 3, 6, 9) -> ChordQuality.DIMINISHED7
        else -> null
    }

    private fun degreeAndAlteration(
        context: TonalContext,
        diatonicDegree: Int,
        desiredSemitonesFromTonic: Int,
    ): Pair<Int, Int> {
        val natural = context.scale.degree(diatonicDegree)!!.semitones
        val desired = desiredSemitonesFromTonic.mod(12)
        val alteration = (-2..2).firstOrNull { (natural + it).mod(12) == desired }
            ?: error("Secondary root must remain within two chromatic steps of its diatonic spelling")
        return diatonicDegree to alteration
    }

    private fun wrapDegree(degree: Int): Int = (degree - 1).mod(7) + 1

    internal fun dominantRootFor(
        context: TonalContext,
        tonicizedDegree: Int,
    ): Pair<Int, Int> =
        degreeAndAlteration(
            context = context,
            diatonicDegree = wrapDegree(tonicizedDegree + 4),
            desiredSemitonesFromTonic = context.scale.degree(tonicizedDegree)!!.semitones + 7,
        )

    private fun churchModeForTarget(sourceMode: Mode, targetDegree: Int): Mode {
        val signatureTonic = sourceMode.signatureTonicDegree ?: 1
        val signatureDegree = wrapDegree(signatureTonic + targetDegree - 1)
        return CHURCH_MODES[signatureDegree - 1]
    }

    private fun TonalContext.builtInModeOrIonian(): Mode =
        Mode.entries.firstOrNull {
            scale.id.value == "mode.${it.name.lowercase()}"
        } ?: Mode.IONIAN
}
