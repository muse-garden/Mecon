package com.mecon.theory.constraint

import com.mecon.api.primitive.PitchClass
import com.mecon.theory.BuiltInChordDefinitions
import com.mecon.theory.ChordQuality
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
import com.mecon.theory.harmony.TonalLens
import com.mecon.theory.schoenberg.SchoenbergHarmonicTreatments

/**
 * One functional reading of a fully diminished seventh as a dominant flat ninth with its
 * root omitted. The sounding root belongs to the written diminished chord; [omittedRootDegree]
 * is the pitch obtained by lowering one of its members and owns the dominant function.
 */
data class RootlessDominantNinthType(
    val chordId: String,
    val soundingRootDegree: Int,
    val soundingRootAlteration: Int,
    val tonicizedDegree: Int,
    val omittedRootDegree: Int,
    val omittedRootAlteration: Int,
    val loweredTone: ChordTone,
    val localLeadingTone: ChordTone,
) {
    init {
        require(soundingRootDegree in 1..7)
        require(soundingRootAlteration in -2..2)
        require(tonicizedDegree in 1..6)
        require(omittedRootDegree in 1..7)
        require(omittedRootAlteration in -2..2)
        require(loweredTone != ChordTone.BASS)
        require(localLeadingTone != ChordTone.BASS)
    }

    val id: String
        get() = buildString {
            append(chordId)
            append(".as-dominant.")
            append(tonicizedDegree)
            append('.')
            append(omittedRootDegree)
            if (omittedRootAlteration != 0) {
                append(if (omittedRootAlteration > 0) ".sharp$omittedRootAlteration" else ".flat${-omittedRootAlteration}")
            }
        }
}

object RootlessDominantNinthMetadata {
    const val CHORD_ID_NAME: String = "rootlessDominantNinthChordId"
    const val USAGE_ID_NAME: String = "rootlessDominantNinthUsageId"
    const val OMITTED_ROOT_DEGREE_NAME: String = "omittedRootDegree"
    const val OMITTED_ROOT_ALTERATION_NAME: String = "omittedRootAlteration"

    fun isRootlessDominantNinth(target: ChordTarget): Boolean =
        (target as? InterpretedChordTarget)
            ?.interpretation
            ?.tags
            ?.contains(InterpretationTag("function.rootless-dominant-ninth")) == true
}

/**
 * Shared catalog for the chapter, integrated exercise, and free solver.
 *
 * The six in-key dominant targets collapse to three symmetric pitch-class sets. A stable
 * canonical spelling is selected for each set, while every way of lowering a member retains
 * its own target identity and functional metadata.
 */
object RootlessDominantNinthVocabulary {
    private data class Seed(
        val tonicizedDegree: Int,
        val omittedRootDegree: Int,
        val omittedRootAlteration: Int,
        val raisedRoot: SpelledPitchClass,
        val pitchClasses: Set<PitchClass>,
    )

    fun types(context: TonalContext): List<RootlessDominantNinthType> {
        require(context.scale.degrees.size == 7) {
            "Rootless dominant-ninth vocabulary requires a seven-degree tonal context"
        }
        val definition = BuiltInChordDefinitions.forQuality(ChordQuality.DIMINISHED7)
        val seeds = TARGET_PRIORITY.map { tonicizedDegree ->
            val (rootDegree, rootAlteration) =
                SecondaryHarmonyVocabulary.dominantRootFor(context, tonicizedDegree)
            val omittedRoot = context.spellDegree(rootDegree, rootAlteration)
            val raisedRoot = omittedRoot.copy(chromaticOffset = omittedRoot.chromaticOffset + 1)
            Seed(
                tonicizedDegree = tonicizedDegree,
                omittedRootDegree = rootDegree,
                omittedRootAlteration = rootAlteration,
                raisedRoot = raisedRoot,
                pitchClasses = definition.instantiate(raisedRoot).pitchClasses.toSet(),
            )
        }
        return seeds
            .groupBy { seed -> seed.pitchClasses.map(PitchClass::value).sorted() }
            .values
            .flatMapIndexed { groupIndex, group ->
                val canonical = group.first()
                val chordId = "rootless-dominant-ninth.chord-${groupIndex + 1}." +
                    canonical.pitchClasses.map(PitchClass::value).sorted().joinToString("-")
                val soundingRootDegree = canonical.omittedRootDegree
                val soundingRootAlteration = canonical.omittedRootAlteration + 1
                val soundingRoot = context.spellDegree(soundingRootDegree, soundingRootAlteration)
                val sounding = definition.instantiate(soundingRoot)
                group.map { seed ->
                    val loweredPitchClass = context
                        .spellDegree(seed.omittedRootDegree, seed.omittedRootAlteration)
                        .pitchClass
                        .transpose(1)
                    val loweredTone = CHORD_TONES.first { tone ->
                        val memberIndex = CHORD_TONES.indexOf(tone)
                        sounding.memberPitchClass(definition.members[memberIndex].id) == loweredPitchClass
                    }
                    val localLeadingPitchClass = context
                        .spellDegree(seed.tonicizedDegree)
                        .pitchClass
                        .transpose(-1)
                    val localLeadingTone = CHORD_TONES.first { tone ->
                        val memberIndex = CHORD_TONES.indexOf(tone)
                        sounding.memberPitchClass(definition.members[memberIndex].id) == localLeadingPitchClass
                    }
                    RootlessDominantNinthType(
                        chordId = chordId,
                        soundingRootDegree = soundingRootDegree,
                        soundingRootAlteration = soundingRootAlteration,
                        tonicizedDegree = seed.tonicizedDegree,
                        omittedRootDegree = seed.omittedRootDegree,
                        omittedRootAlteration = seed.omittedRootAlteration,
                        loweredTone = loweredTone,
                        localLeadingTone = localLeadingTone,
                    )
                }
            }
            .sortedWith(compareBy({ it.chordId }, { TARGET_PRIORITY.indexOf(it.tonicizedDegree) }))
    }

    fun catalog(
        context: TonalContext,
    ): ChordCatalog =
        ChordCatalogCollector.collect(constructedChords(context))

    internal fun constructedChords(
        context: TonalContext,
    ): List<ConstructedChord> {
        val constructionContext = ChordConstructionContext(context)
        return types(context).map { type -> type.construct(constructionContext) }
    }

    private fun RootlessDominantNinthType.construct(
        context: ChordConstructionContext,
    ): ConstructedChord {
        val recipeId = ChordRecipeId("schoenberg.rootless-dominant-ninth")
        val definition = BuiltInChordDefinitions.forQuality(ChordQuality.DIMINISHED7)
        val root = context.tonalContext.spellDegree(soundingRootDegree, soundingRootAlteration)
        val structuralRoles = ChordBuilder.structuralToneRoles(definition, root)
        val loweredToneId = structuralRoles.getValue(loweredTone.functionalStructuralRole())
        val localLeadingToneId = structuralRoles.getValue(localLeadingTone.functionalStructuralRole())
        val interpretation = ChordInterpretation(
            id = interpretationId(),
            lens = TonalLens(
                contextId = context.tonalContext.id,
                context = context.tonalContext,
                tonicizedDegree = tonicizedDegree,
            ),
            symbol = FunctionalChordSymbol(
                degree = soundingRootDegree,
                alteration = soundingRootAlteration,
                quality = ChordQuality.DIMINISHED7,
                arity = com.mecon.theory.ChordArity.SEVENTH,
                appliedToDegree = tonicizedDegree,
            ),
            function = HarmonicFunction.DOMINANT,
            toneRoles = structuralRoles + mapOf(
                FunctionalToneRole.LOCAL_LEADING_TONE to localLeadingToneId,
                FunctionalToneRole.OMITTED_DOMINANT_ROOT_NEIGHBOR to loweredToneId,
                FunctionalToneRole.ALTERED_TONE to loweredToneId,
            ),
            structuralToneOrder = ChordBuilder.structuralToneOrder(definition, root),
            treatmentIds = setOf(
                SchoenbergHarmonicTreatments.ROOTLESS_DOMINANT_NINTH,
                SchoenbergHarmonicTreatments.VAGRANT_CHORD,
            ),
            tags = setOf(
                InterpretationTag("function.secondary-harmony"),
                InterpretationTag("function.rootless-dominant-ninth"),
                InterpretationTag("vagrant-chord"),
            ),
            attributes = mapOf(
                SecondaryHarmonyMetadata.FAMILY_NAME to SecondaryHarmonyFamily.SECONDARY_DOMINANT.name,
                SecondaryHarmonyMetadata.TONICIZED_DEGREE_NAME to tonicizedDegree.toString(),
                RootlessDominantNinthMetadata.CHORD_ID_NAME to chordId,
                RootlessDominantNinthMetadata.USAGE_ID_NAME to id,
                RootlessDominantNinthMetadata.OMITTED_ROOT_DEGREE_NAME to omittedRootDegree.toString(),
                RootlessDominantNinthMetadata.OMITTED_ROOT_ALTERATION_NAME to omittedRootAlteration.toString(),
            ),
            trace = InterpretationTrace(
                recipeId = recipeId,
                derivationSteps = listOf(
                    "dominant-flat-nine-of-$tonicizedDegree",
                    "omit-root-$omittedRootDegree-$omittedRootAlteration",
                ),
            ),
        )
        return ChordBuilder.fromDefinition(
            context = context,
            definition = definition,
            rootDegree = soundingRootDegree,
            rootAlteration = soundingRootAlteration,
            interpretation = interpretation,
            trace = ConstructionTrace(
                recipeId = recipeId,
                derivationSteps = listOf(
                    "raise-omitted-root-to-diminished-member",
                    "collect-symmetric-diminished-seventh",
                ),
            ),
        )
    }

    internal fun RootlessDominantNinthType.interpretationId(): InterpretationId =
        InterpretationId("rootless-dominant-ninth.$id")

    private fun ChordTone.functionalStructuralRole(): FunctionalToneRole =
        when (this) {
            ChordTone.ROOT -> FunctionalToneRole.STRUCTURAL_ROOT
            ChordTone.THIRD -> FunctionalToneRole.STRUCTURAL_THIRD
            ChordTone.FIFTH -> FunctionalToneRole.STRUCTURAL_FIFTH
            ChordTone.SEVENTH -> FunctionalToneRole.CHORDAL_SEVENTH
            ChordTone.BASS -> error("Bass is not a structural diminished-seventh member")
        }

    private val TARGET_PRIORITY = listOf(5, 2, 1, 3, 4, 6)
    private val CHORD_TONES = listOf(
        ChordTone.ROOT,
        ChordTone.THIRD,
        ChordTone.FIFTH,
        ChordTone.SEVENTH,
    )
}
