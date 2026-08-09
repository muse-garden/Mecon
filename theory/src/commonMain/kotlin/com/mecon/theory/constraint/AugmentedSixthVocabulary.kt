package com.mecon.theory.constraint

import com.mecon.api.primitive.PitchClass
import com.mecon.theory.BuiltInChordDefinitions
import com.mecon.theory.ChordArity
import com.mecon.theory.ChordDefinition
import com.mecon.theory.ChordDefinitionId
import com.mecon.theory.ChordMember
import com.mecon.theory.ChordMemberId
import com.mecon.theory.ChordMemberRole
import com.mecon.theory.ChordQuality
import com.mecon.theory.SpelledPitchClass
import com.mecon.theory.TonalContext
import com.mecon.theory.harmony.ChordBuilder
import com.mecon.theory.harmony.ChordConstructionContext
import com.mecon.theory.harmony.ChordInterpretation
import com.mecon.theory.harmony.ChordRecipeId
import com.mecon.theory.harmony.ConstructionTrace
import com.mecon.theory.harmony.ConstructedChord
import com.mecon.theory.harmony.FunctionalChordSymbol
import com.mecon.theory.harmony.FunctionalToneRole
import com.mecon.theory.harmony.HarmonicFunction
import com.mecon.theory.harmony.InterpretationId
import com.mecon.theory.harmony.InterpretationTag
import com.mecon.theory.harmony.InterpretationTrace
import com.mecon.theory.harmony.SonorityToneId
import com.mecon.theory.harmony.TonalLens
import com.mecon.theory.schoenberg.SchoenbergHarmonicTreatments

enum class AugmentedSixthFamily {
    ITALIAN,
    GERMAN,
    FRENCH,
    HALF_DIMINISHED,
}

data class AugmentedSixthType(
    val family: AugmentedSixthFamily,
    val targetDegree: Int,
    val lowerDegree: Int,
    val lowerAlteration: Int,
    val upperDegree: Int,
    val upperAlteration: Int,
    val lowerTone: SpelledPitchClass,
    val supportTone: SpelledPitchClass,
    val upperTone: SpelledPitchClass,
    val colorTone: SpelledPitchClass? = null,
    val virtualRootTone: SpelledPitchClass? = null,
    val resolutionAlteration: Int = 0,
    val basisTones: List<SpelledPitchClass>,
) {
    init {
        require(targetDegree in 1..7)
        require(lowerDegree in 1..7)
        require(upperDegree in 1..7)
    }

    val id: String = "${family.name.lowercase()}.$targetDegree"
    val arity: ChordArity = if (colorTone == null) ChordArity.TRIAD else ChordArity.SEVENTH
    val resultTones: List<SpelledPitchClass> = when (family) {
        AugmentedSixthFamily.ITALIAN -> listOf(upperTone, lowerTone, supportTone)
        AugmentedSixthFamily.GERMAN -> listOf(upperTone, lowerTone, supportTone, requireNotNull(colorTone))
        AugmentedSixthFamily.FRENCH -> listOf(requireNotNull(colorTone), upperTone, lowerTone, supportTone)
        AugmentedSixthFamily.HALF_DIMINISHED -> listOf(lowerTone, supportTone, requireNotNull(colorTone), upperTone)
    }
}

object AugmentedSixthMetadata {
    const val FAMILY_NAME: String = "augmentedSixthFamily"
    const val TARGET_DEGREE_NAME: String = "augmentedSixthTargetDegree"
    const val TARGET_ALTERATION_NAME: String = "augmentedSixthTargetAlteration"

    fun familyOf(target: ChordTarget): AugmentedSixthFamily? =
        (target as? InterpretedChordTarget)
            ?.interpretation
            ?.attributes
            ?.get(FAMILY_NAME)
            ?.let(AugmentedSixthFamily::valueOf)

    fun targetDegreeOf(target: ChordTarget): Int? =
        (target as? InterpretedChordTarget)
            ?.interpretation
            ?.attributes
            ?.get(TARGET_DEGREE_NAME)
            ?.toIntOrNull()

    fun targetAlterationOf(target: ChordTarget): Int? =
        (target as? InterpretedChordTarget)
            ?.interpretation
            ?.attributes
            ?.get(TARGET_ALTERATION_NAME)
            ?.toIntOrNull()
}

/**
 * Spelling-sensitive Italian, German and French augmented-sixth constructions.
 *
 * Each target degree receives its own reading. The outer chromatic neighbours are spelled as an
 * augmented sixth and converge by semitone on that target; inner members follow Schoenberg's
 * V/V-derived German/Italian and altered ii7-derived French recipes.
 */
object AugmentedSixthVocabulary {
    private val RECIPE_ID = ChordRecipeId("schoenberg.augmented-sixth")

    fun types(context: TonalContext): List<AugmentedSixthType> =
        (1..7).flatMap { targetDegree ->
            val target = context.spellDegree(targetDegree)
            val upperDegree = wrapDegree(targetDegree - 1)
            val lowerDegree = wrapDegree(targetDegree + 1)
            val frenchColorDegree = wrapDegree(targetDegree + 4)
            val appliedDominantTones = appliedDominantFlatNinthTones(context, targetDegree)
            val virtualRootTone = appliedDominantTones[0]
            val upperTone = appliedDominantTones[1]
            val sourceLowerTone = appliedDominantTones[2]
            val lowerTone = sourceLowerTone.copy(
                chromaticOffset = sourceLowerTone.chromaticOffset - 1,
            )
            val supportTone = appliedDominantTones[3]
            val germanColorTone = appliedDominantTones[4]
            listOf(
                AugmentedSixthType(
                    family = AugmentedSixthFamily.ITALIAN,
                    targetDegree = targetDegree,
                    lowerDegree = lowerDegree,
                    lowerAlteration = context.alterationOf(lowerDegree, lowerTone),
                    upperDegree = upperDegree,
                    upperAlteration = context.alterationOf(upperDegree, upperTone),
                    lowerTone = lowerTone,
                    supportTone = supportTone,
                    upperTone = upperTone,
                    virtualRootTone = virtualRootTone,
                    basisTones = listOf(
                        upperTone,
                        sourceLowerTone,
                        supportTone,
                    ),
                ),
                AugmentedSixthType(
                    family = AugmentedSixthFamily.GERMAN,
                    targetDegree = targetDegree,
                    lowerDegree = lowerDegree,
                    lowerAlteration = context.alterationOf(lowerDegree, lowerTone),
                    upperDegree = upperDegree,
                    upperAlteration = context.alterationOf(upperDegree, upperTone),
                    lowerTone = lowerTone,
                    supportTone = supportTone,
                    upperTone = upperTone,
                    colorTone = germanColorTone,
                    virtualRootTone = virtualRootTone,
                    basisTones = listOf(
                        upperTone,
                        sourceLowerTone,
                        supportTone,
                        germanColorTone,
                    ),
                ),
                AugmentedSixthType(
                    family = AugmentedSixthFamily.FRENCH,
                    targetDegree = targetDegree,
                    lowerDegree = lowerDegree,
                    lowerAlteration = context.alterationOf(lowerDegree, lowerTone),
                    upperDegree = upperDegree,
                    upperAlteration = context.alterationOf(upperDegree, upperTone),
                    lowerTone = lowerTone,
                    supportTone = supportTone,
                    upperTone = upperTone,
                    colorTone = context.spellPitch(frenchColorDegree, target.pitchClass.transpose(7)),
                    basisTones = listOf(
                        context.spellPitch(frenchColorDegree, target.pitchClass.transpose(7)),
                        context.spellDegree(upperDegree),
                        context.spellDegree(lowerDegree),
                        supportTone,
                    ),
                ),
            )
        } + halfDiminishedType(context)

    fun constructedChords(context: TonalContext): List<ConstructedChord> =
        types(context).map { type -> construct(context, type) }

    fun interpretationId(type: AugmentedSixthType): InterpretationId =
        InterpretationId("augmented-sixth.${type.id}")

    private fun construct(
        context: TonalContext,
        type: AugmentedSixthType,
    ): ConstructedChord {
        val tones = buildList {
            add("root" to type.lowerTone)
            add("third" to type.supportTone)
            type.colorTone?.let { add("fifth" to it) }
            add((if (type.colorTone == null) "fifth" else "seventh") to type.upperTone)
        }
        val definition = ChordDefinition(
            id = ChordDefinitionId("augmented-sixth.${type.id}"),
            members = tones.map { (id, tone) -> member(type.lowerTone, id, tone) },
            compatibilityQuality = ChordQuality.CUSTOM,
        )
        val toneIds = tones.associate { (id, tone) -> id to SonorityToneId.from(tone) }
        val toneRoles = buildMap {
            put(FunctionalToneRole.STRUCTURAL_ROOT, toneIds.getValue("root"))
            put(FunctionalToneRole.STRUCTURAL_THIRD, toneIds.getValue("third"))
            put(FunctionalToneRole.STRUCTURAL_FIFTH, toneIds.getValue("fifth"))
            toneIds["seventh"]?.let { put(FunctionalToneRole.CHORDAL_SEVENTH, it) }
            put(FunctionalToneRole.ALTERED_TONE, toneIds.getValue(if (type.colorTone == null) "fifth" else "seventh"))
        }
        val interpretation = ChordInterpretation(
            id = interpretationId(type),
            lens = TonalLens(context.id, context, tonicizedDegree = type.targetDegree),
            symbol = FunctionalChordSymbol(
                degree = type.lowerDegree,
                alteration = type.lowerAlteration,
                quality = ChordQuality.CUSTOM,
                arity = type.arity,
                appliedToDegree = type.targetDegree,
            ),
            function = HarmonicFunction.PREDOMINANT,
            toneRoles = toneRoles,
            structuralToneOrder = tones.map { (_, tone) -> SonorityToneId.from(tone) },
            treatmentIds = setOf(SchoenbergHarmonicTreatments.AUGMENTED_SIXTH),
            tags = setOf(
                InterpretationTag("function.augmented-sixth"),
                InterpretationTag("vagrant-chord"),
            ),
            attributes = mapOf(
                AugmentedSixthMetadata.FAMILY_NAME to type.family.name,
                AugmentedSixthMetadata.TARGET_DEGREE_NAME to type.targetDegree.toString(),
                AugmentedSixthMetadata.TARGET_ALTERATION_NAME to type.resolutionAlteration.toString(),
            ),
            trace = InterpretationTrace(
                RECIPE_ID,
                listOf("${type.family.name.lowercase()}-toward-${type.targetDegree}"),
            ),
        )
        return ChordBuilder.fromSpelledRoot(
            definition = definition,
            spelledRoot = type.lowerTone,
            interpretation = interpretation,
            trace = ConstructionTrace(
                RECIPE_ID,
                listOf("augmented-sixth-endpoints", "target-degree-${type.targetDegree}"),
            ),
        )
    }

    private fun member(
        root: SpelledPitchClass,
        id: String,
        tone: SpelledPitchClass,
    ): ChordMember = ChordMember(
        id = ChordMemberId(id),
        diatonicNumber = (tone.noteName.ordinal - root.noteName.ordinal).mod(7) + 1,
        semitones = (tone.pitchClass.value - root.pitchClass.value).mod(12),
        role = ChordMemberRole.STRUCTURAL,
    )

    private fun wrapDegree(degree: Int): Int = (degree - 1).mod(7) + 1

    /**
     * The rootless source is a dominant seventh plus a minor third above its seventh
     * (the traditional dominant-flat-ninth source of a diminished seventh). Instantiating
     * the typed chord definition keeps every member correctly spelled even for V9/VII.
     */
    private fun appliedDominantFlatNinthTones(
        context: TonalContext,
        targetDegree: Int,
    ): List<SpelledPitchClass> {
        val (rootDegree, rootAlteration) =
            SecondaryHarmonyVocabulary.dominantRootFor(context, targetDegree)
        val root = context.spellDegree(rootDegree, rootAlteration)
        val definition = BuiltInChordDefinitions.forQuality(ChordQuality.DOMINANT7_FLAT9)
        val sonority = definition.instantiate(root)
        return definition.members.map { member -> sonority.spelledMembers.getValue(member.id) }
    }

    private fun halfDiminishedType(context: TonalContext): AugmentedSixthType {
        val root = context.spellDegree(2)
        val third = context.spellDegree(4)
        val fifth = context.spellPitch(6, context.tonic.pitchClass.transpose(8))
        val seventh = context.spellDegree(1)
        val tones = listOf(root, third, fifth, seventh)
        return AugmentedSixthType(
            family = AugmentedSixthFamily.HALF_DIMINISHED,
            targetDegree = 2,
            lowerDegree = 2,
            lowerAlteration = context.alterationOf(2, root),
            upperDegree = 1,
            upperAlteration = context.alterationOf(1, seventh),
            lowerTone = root,
            supportTone = third,
            upperTone = seventh,
            colorTone = fifth,
            resolutionAlteration = -1,
            basisTones = tones,
        )
    }

    private fun TonalContext.spellPitch(degree: Int, pitchClass: PitchClass): SpelledPitchClass {
        val natural = spellDegree(degree)
        val alteration = centeredDelta(natural.pitchClass, pitchClass)
        return spellDegree(degree, alteration)
    }

    private fun TonalContext.alterationOf(degree: Int, tone: SpelledPitchClass): Int =
        tone.chromaticOffset - spellDegree(degree).chromaticOffset

    private fun centeredDelta(from: PitchClass, to: PitchClass): Int {
        val raw = (to.value - from.value).mod(12)
        return if (raw > 6) raw - 12 else raw
    }
}
