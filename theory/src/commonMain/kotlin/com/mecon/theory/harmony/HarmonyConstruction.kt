package com.mecon.theory.harmony

import kotlin.jvm.JvmInline

import com.mecon.theory.ChordArity
import com.mecon.theory.ChordDefinition
import com.mecon.theory.ChordDefinitionId
import com.mecon.theory.ChordMember
import com.mecon.theory.ChordMemberId
import com.mecon.theory.ChordMemberRole
import com.mecon.theory.ChordQuality
import com.mecon.theory.DefinedSonority
import com.mecon.theory.ScaleDegreeDefinition
import com.mecon.theory.SpelledPitchClass
import com.mecon.theory.TonalContext
import com.mecon.theory.TonalContextId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class ChordRecipeId(val value: String) {
    init {
        require(value.isNotBlank()) { "ChordRecipeId must not be blank" }
    }

    override fun toString(): String = value
}

@Serializable
@JvmInline
value class SonorityId(val value: String) {
    init {
        require(value.isNotBlank()) { "SonorityId must not be blank" }
    }

    override fun toString(): String = value
}

@Serializable
@JvmInline
value class InterpretationId(val value: String) {
    init {
        require(value.isNotBlank()) { "InterpretationId must not be blank" }
    }

    override fun toString(): String = value
}

@Serializable
@JvmInline
value class SonorityToneId(val value: String) {
    init {
        require(value.isNotBlank()) { "SonorityToneId must not be blank" }
    }

    override fun toString(): String = value

    companion object {
        fun from(spelling: SpelledPitchClass): SonorityToneId =
            SonorityToneId(
                "tone.${spelling.noteName.name.lowercase()}.${alterationToken(spelling.chromaticOffset)}"
            )
    }
}

@Serializable
@JvmInline
value class HarmonicTreatmentId(val value: String) {
    init {
        require(value.isNotBlank()) { "HarmonicTreatmentId must not be blank" }
    }

    override fun toString(): String = value
}

@Serializable
@JvmInline
value class InterpretationTag(val value: String) {
    init {
        require(value.isNotBlank()) { "InterpretationTag must not be blank" }
    }

    override fun toString(): String = value
}

@Serializable
@JvmInline
value class VagrantChordFamilyId(val value: String) {
    init {
        require(value.isNotBlank()) { "VagrantChordFamilyId must not be blank" }
    }

    override fun toString(): String = value
}

@Serializable
@JvmInline
value class SoundingClassId(val value: String) {
    init {
        require(value.isNotBlank()) { "SoundingClassId must not be blank" }
    }

    override fun toString(): String = value
}

/** A spelling-neutral, inversion-neutral key used only for audible discovery. */
@Serializable
@JvmInline
value class AudibleSonorityKey(val value: String) {
    init {
        require(value.isNotBlank()) { "AudibleSonorityKey must not be blank" }
    }

    override fun toString(): String = value

    companion object {
        fun from(pitchClasses: Collection<Int>): AudibleSonorityKey {
            require(pitchClasses.isNotEmpty()) { "An audible sonority must contain pitch classes" }
            require(pitchClasses.all { it in 0..11 }) { "Pitch classes must be in 0..11" }
            return AudibleSonorityKey(
                pitchClasses.distinct().sorted().joinToString("-")
            )
        }
    }
}

@Serializable
@JvmInline
value class ChordExplanationId(val value: String) {
    init {
        require(value.isNotBlank()) { "ChordExplanationId must not be blank" }
    }

    override fun toString(): String = value
}

@Serializable
@JvmInline
value class ChordCatalogCategoryId(val value: String) {
    init {
        require(value.isNotBlank()) { "ChordCatalogCategoryId must not be blank" }
    }

    override fun toString(): String = value
}

@Serializable
data class ChordSelectionOriginRef(
    val categoryId: ChordCatalogCategoryId,
    val choiceId: ChordSelectionId,
)

@Serializable
@JvmInline
value class ConstructionRouteId(val value: String) {
    init {
        require(value.isNotBlank()) { "ConstructionRouteId must not be blank" }
    }

    override fun toString(): String = value
}

@Serializable
data class ChordInterpretationRef(
    val sonorityId: SonorityId,
    val interpretationId: InterpretationId,
)

@Serializable
sealed interface ConstructionOperation {
    @Serializable
    @SerialName("alter")
    data class Alter(
        val toneId: SonorityToneId,
        val semitones: Int,
    ) : ConstructionOperation {
        init {
            require(semitones != 0) { "An alteration must change the tone" }
        }
    }

    @Serializable
    @SerialName("add")
    data class Add(val toneId: SonorityToneId) : ConstructionOperation

    @Serializable
    @SerialName("omit")
    data class Omit(val toneId: SonorityToneId) : ConstructionOperation

    @Serializable
    @SerialName("stack")
    data class StackThirds(val memberCount: Int) : ConstructionOperation {
        init {
            require(memberCount > 0) { "A tertian stack must contain members" }
        }
    }

    @Serializable
    @SerialName("respell")
    data class Respell(
        val from: SonorityToneId,
        val to: SonorityToneId,
    ) : ConstructionOperation

    /**
     * Migration-only adapter for old raw construction traces. UI code must not parse [step].
     */
    @Serializable
    @SerialName("legacy")
    data class LegacyTrace(val step: String) : ConstructionOperation {
        init {
            require(step.isNotBlank()) { "A legacy construction step must not be blank" }
        }
    }
}

/**
 * A spelling-preserving, order-independent key for the actual members of a chord.
 *
 * This is deliberately different from a pitch-class-set key: C-sharp and D-flat remain distinct.
 */
@JvmInline
value class SpelledToneSetKey(val value: String) {
    init {
        require(value.isNotBlank()) { "SpelledToneSetKey must not be blank" }
    }

    override fun toString(): String = value

    companion object {
        fun from(tones: Collection<SpelledPitchClass>): SpelledToneSetKey {
            require(tones.isNotEmpty()) { "A spelled tone set must not be empty" }
            require(tones.distinct().size == tones.size) { "A spelled tone set must not contain duplicates" }
            return SpelledToneSetKey(
                tones
                    .sortedWith(
                        compareBy<SpelledPitchClass>(
                            { it.noteName.ordinal },
                            { it.chromaticOffset },
                        )
                    )
                    .joinToString(".") {
                        "${it.noteName.name.lowercase()}-${alterationToken(it.chromaticOffset)}"
                    }
            )
        }
    }
}

data class SpelledSonorityTone(
    val id: SonorityToneId,
    val spelling: SpelledPitchClass,
)

enum class HarmonicFunction {
    TONIC,
    PREDOMINANT,
    DOMINANT,
    LEADING,
    COLOR,
    OTHER,
}

/**
 * Typed semantic roles resolve functional rules to an actual member of the sounding sonority.
 */
enum class FunctionalToneRole {
    STRUCTURAL_ROOT,
    STRUCTURAL_THIRD,
    STRUCTURAL_FIFTH,
    CHORDAL_SEVENTH,
    LOCAL_LEADING_TONE,
    ALTERED_TONE,
    OMITTED_DOMINANT_ROOT_NEIGHBOR,
}

data class TonalLens(
    val contextId: TonalContextId,
    val context: TonalContext? = null,
    val tonicizedDegree: Int? = null,
) {
    init {
        require(context == null || context.id == contextId) {
            "TonalLens context must match contextId"
        }
        require(tonicizedDegree == null || tonicizedDegree > 0) {
            "TonalLens tonicizedDegree must be positive"
        }
    }
}

data class FunctionalChordSymbol(
    val degree: Int,
    val alteration: Int = 0,
    val quality: ChordQuality,
    val arity: ChordArity,
    val appliedToDegree: Int? = null,
) {
    init {
        require(degree > 0) { "FunctionalChordSymbol degree must be positive" }
        require(appliedToDegree == null || appliedToDegree > 0) {
            "FunctionalChordSymbol appliedToDegree must be positive"
        }
    }
}

data class InterpretationTrace(
    val recipeId: ChordRecipeId,
    val derivationSteps: List<String> = emptyList(),
)

data class ConstructionTrace(
    val recipeId: ChordRecipeId,
    val derivationSteps: List<String> = emptyList(),
)

data class ChordInterpretation(
    val id: InterpretationId,
    val lens: TonalLens,
    /** Additional tonal contexts in which the same selected reading is valid, e.g. a pivot chord. */
    val compatibleContextIds: Set<TonalContextId> = emptySet(),
    val symbol: FunctionalChordSymbol,
    val function: HarmonicFunction = HarmonicFunction.OTHER,
    val toneRoles: Map<FunctionalToneRole, SonorityToneId> = emptyMap(),
    val structuralToneOrder: List<SonorityToneId> = emptyList(),
    val treatmentIds: Set<HarmonicTreatmentId> = emptySet(),
    val tags: Set<InterpretationTag> = emptySet(),
    val attributes: Map<String, String> = emptyMap(),
    val trace: InterpretationTrace,
) {
    init {
        require(structuralToneOrder.distinct().size == structuralToneOrder.size) {
            "ChordInterpretation structuralToneOrder must not contain duplicates"
        }
    }
}

data class ChordConstructionContext(
    val tonalContext: TonalContext,
)

/**
 * One recipe result before equal-sonority constructions are collected.
 */
data class ConstructedChord(
    val definition: ChordDefinition,
    val spelledRoot: SpelledPitchClass,
    val interpretation: ChordInterpretation,
    val trace: ConstructionTrace,
) {
    val definedSonority: DefinedSonority get() = definition.instantiate(spelledRoot)

    val spelledTones: List<SpelledPitchClass>
        get() = definition.members.map { member ->
            definedSonority.spelledMembers.getValue(member.id)
        }

    val spelledToneSetKey: SpelledToneSetKey
        get() = SpelledToneSetKey.from(spelledTones)
}

interface ChordRecipe {
    val id: ChordRecipeId

    fun construct(context: ChordConstructionContext): Sequence<ConstructedChord>
}

/**
 * Small shared construction primitives. Chapter recipes compose these rather than deriving pitches again.
 */
object ChordBuilder {
    fun fromDefinition(
        context: ChordConstructionContext,
        definition: ChordDefinition,
        rootDegree: Int,
        rootAlteration: Int,
        interpretation: ChordInterpretation,
        trace: ConstructionTrace,
    ): ConstructedChord =
        fromSpelledRoot(
            definition = definition,
            spelledRoot = context.tonalContext.spellDegree(rootDegree, rootAlteration),
            interpretation = interpretation,
            trace = trace,
        )

    fun fromSpelledRoot(
        definition: ChordDefinition,
        spelledRoot: SpelledPitchClass,
        interpretation: ChordInterpretation,
        trace: ConstructionTrace,
    ): ConstructedChord =
        ConstructedChord(
            definition = definition,
            spelledRoot = spelledRoot,
            interpretation = interpretation,
            trace = trace,
        )

    fun tertianDefinition(
        context: TonalContext,
        rootDegree: Int,
        memberCount: Int,
        id: ChordDefinitionId = ChordDefinitionId(
            "${context.id.value}.degree-$rootDegree.$memberCount"
        ),
    ): ChordDefinition {
        require(context.scale.degrees.size == 7) {
            "Tertian chord construction currently requires a seven-degree scale"
        }
        require(rootDegree in 1..7) { "Tertian chord rootDegree must be in 1..7" }
        require(memberCount in 1..7) { "Tertian chord memberCount must be in 1..7" }
        val scale = context.scale.degrees.sortedBy(ScaleDegreeDefinition::number)
        val rootIndex = rootDegree - 1
        val rootSemitones = scale[rootIndex].semitones
        val members = (0 until memberCount).map { memberIndex ->
            val unwrappedIndex = rootIndex + memberIndex * 2
            val scaleDegree = scale[unwrappedIndex.mod(scale.size)]
            val semitones = scaleDegree.semitones + (unwrappedIndex / scale.size) * 12 - rootSemitones
            ChordMember(
                id = ChordMemberId(TERTIAN_MEMBER_NAMES.getOrElse(memberIndex) { "member-${memberIndex + 1}" }),
                diatonicNumber = memberIndex * 2 + 1,
                semitones = semitones,
                role = ChordMemberRole.STRUCTURAL,
                omissionPriority = if (memberIndex == 2 && memberCount > 3) 1 else 0,
            )
        }
        return ChordDefinition(
            id = id,
            members = members,
            compatibilityQuality = compatibilityQuality(members.map(ChordMember::semitones)),
        )
    }

    fun structuralToneRoles(
        definition: ChordDefinition,
        root: SpelledPitchClass,
    ): Map<FunctionalToneRole, SonorityToneId> {
        val spelled = definition.instantiate(root).spelledMembers
        return buildMap {
            definition.members.getOrNull(0)?.let {
                put(FunctionalToneRole.STRUCTURAL_ROOT, SonorityToneId.from(spelled.getValue(it.id)))
            }
            definition.members.getOrNull(1)?.let {
                put(FunctionalToneRole.STRUCTURAL_THIRD, SonorityToneId.from(spelled.getValue(it.id)))
            }
            definition.members.getOrNull(2)?.let {
                put(FunctionalToneRole.STRUCTURAL_FIFTH, SonorityToneId.from(spelled.getValue(it.id)))
            }
            definition.members.getOrNull(3)?.let {
                put(FunctionalToneRole.CHORDAL_SEVENTH, SonorityToneId.from(spelled.getValue(it.id)))
            }
        }
    }

    fun structuralToneOrder(
        definition: ChordDefinition,
        root: SpelledPitchClass,
    ): List<SonorityToneId> {
        val spelled = definition.instantiate(root).spelledMembers
        return definition.members.map { member ->
            SonorityToneId.from(spelled.getValue(member.id))
        }
    }

    private fun compatibilityQuality(intervals: List<Int>): ChordQuality =
        when (intervals) {
            listOf(0, 4, 7) -> ChordQuality.MAJOR
            listOf(0, 3, 7) -> ChordQuality.MINOR
            listOf(0, 3, 6) -> ChordQuality.DIMINISHED
            listOf(0, 4, 8) -> ChordQuality.AUGMENTED
            listOf(0, 4, 7, 11) -> ChordQuality.MAJOR7
            listOf(0, 4, 7, 10) -> ChordQuality.DOMINANT7
            listOf(0, 3, 7, 10) -> ChordQuality.MINOR7
            listOf(0, 3, 6, 10) -> ChordQuality.HALF_DIMINISHED7
            listOf(0, 3, 6, 9) -> ChordQuality.DIMINISHED7
            listOf(0, 3, 7, 11) -> ChordQuality.MINOR_MAJOR7
            listOf(0, 4, 8, 10) -> ChordQuality.AUGMENTED7
            else -> ChordQuality.CUSTOM
        }

    private val TERTIAN_MEMBER_NAMES = listOf(
        "root",
        "third",
        "fifth",
        "seventh",
        "ninth",
        "eleventh",
        "thirteenth",
    )
}

private fun alterationToken(alteration: Int): String =
    when {
        alteration == 0 -> "natural"
        alteration > 0 -> "sharp$alteration"
        else -> "flat${-alteration}"
    }
