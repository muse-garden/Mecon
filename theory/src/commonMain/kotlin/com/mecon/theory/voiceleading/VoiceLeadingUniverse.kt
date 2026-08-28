package com.mecon.theory.voiceleading

import com.mecon.theory.BuiltInChordDefinitions
import com.mecon.theory.ChordDefinition
import com.mecon.theory.ChordDefinitionId
import com.mecon.theory.ChordMemberRole
import com.mecon.theory.ChordQuality
import kotlin.jvm.JvmInline

/**
 * Whether a recognized sonority may end a voice-leading pathway.
 *
 * The distinction is the computable form of the Schenkerian *Stufe* / *Durchgang* split: a stable
 * node reads as a chord in its own right, a transitional node reads as figuration (see
 * `docs/theory/voice-leading-pathways.md`).
 */
enum class VoiceLeadingStability {
    STABLE,
    TRANSITIONAL,
}

@JvmInline
value class VoiceLeadingUniverseId(val value: String) {
    init { require(value.isNotBlank()) { "A voice-leading universe id must not be blank" } }
}

/** One registered set class plus the role it may play on a pathway. */
data class VoiceLeadingSetClassRegistration(
    val definition: ChordDefinition,
    val stability: VoiceLeadingStability,
)

/**
 * A cross-cardinality vocabulary for pathway search.
 *
 * [VoiceLeadingChordFamily] fixes one cardinality because the base adjacency graph never changes
 * the number of tones. Pathways may split and fuse tones, so the vocabulary has to span
 * cardinalities while still answering "is this node nameable?" — the only effective pruning.
 */
data class VoiceLeadingUniverse(
    val id: VoiceLeadingUniverseId,
    val registrations: List<VoiceLeadingSetClassRegistration>,
) {
    init {
        require(registrations.isNotEmpty()) { "A voice-leading universe must register set classes" }
        require(registrations.map { it.definition.id }.distinct().size == registrations.size) {
            "Voice-leading universe definition ids must be unique"
        }
        require(registrations.any { it.stability == VoiceLeadingStability.STABLE }) {
            "A voice-leading universe needs at least one stable set class to end pathways on"
        }
    }

    val cardinalities: Set<Int> =
        registrations.map { it.definition.members.size }.distinct().sorted().toSet()

    private val stabilityById: Map<ChordDefinitionId, VoiceLeadingStability> =
        registrations.associate { it.definition.id to it.stability }

    private val definitionById: Map<ChordDefinitionId, ChordDefinition> =
        registrations.associate { it.definition.id to it.definition }

    fun stabilityOf(definitionId: ChordDefinitionId): VoiceLeadingStability =
        stabilityById[definitionId]
            ?: error("${definitionId.value} is not registered in ${id.value}")

    fun definitionOf(definitionId: ChordDefinitionId): ChordDefinition =
        definitionById[definitionId]
            ?: error("${definitionId.value} is not registered in ${id.value}")

    /**
     * Every registered transposition indexed by its 12-bit pitch-class mask.
     *
     * Pathway search calls recognition once per generated node, so the vocabulary is expanded
     * eagerly (13 definitions x 12 roots) instead of rescanning definitions per node.
     */
    private val readingsByMask: Map<Int, List<VoiceLeadingChordReading>> = buildMap {
        registrations.forEach { registration ->
            val definition = registration.definition
            (0..11).forEach { root ->
                val mask = definition.members.fold(0) { acc, member ->
                    acc or (1 shl (root + member.semitones).mod(12))
                }
                if (mask.countOneBits() != definition.members.size) return@forEach
                val reading = VoiceLeadingChordReading(
                    definitionId = definition.id,
                    quality = definition.compatibilityQuality,
                    rootPitchClass = root,
                )
                put(mask, (get(mask).orEmpty() + reading))
            }
        }
        keys.toList().forEach { mask ->
            put(
                mask,
                getValue(mask).distinct()
                    .sortedWith(compareBy({ it.quality.ordinal }, { it.rootPitchClass })),
            )
        }
    }

    /** Every reading of [pitchClasses] across all cardinalities, in deterministic order. */
    fun recognize(pitchClasses: Collection<Int>): List<VoiceLeadingChordReading> =
        readingsByMask[pitchClassMask(pitchClasses)].orEmpty()

    internal fun readingsForMask(mask: Int): List<VoiceLeadingChordReading> =
        readingsByMask[mask].orEmpty()

    /**
     * The most stable role any reading of [pitchClasses] can take, or null when unrecognized.
     *
     * A set that is nameable as a stable chord is treated as stable even when it also has a
     * transitional reading; the transitional label only fires for sonorities with no stable name.
     */
    fun stabilityOfSet(pitchClasses: Collection<Int>): VoiceLeadingStability? {
        val readings = recognize(pitchClasses)
        if (readings.isEmpty()) return null
        return if (readings.any { stabilityOf(it.definitionId) == VoiceLeadingStability.STABLE }) {
            VoiceLeadingStability.STABLE
        } else {
            VoiceLeadingStability.TRANSITIONAL
        }
    }

    /**
     * Pitch classes carrying [ChordMemberRole.SUSPENSION] under [reading].
     *
     * The suspended member is already declared on the built-in sus definitions, so the pathway
     * layer reads it instead of re-deriving "which tone wants to resolve".
     */
    fun suspendedPitchClasses(reading: VoiceLeadingChordReading): List<Int> =
        definitionOf(reading.definitionId).members
            .filter { it.role == ChordMemberRole.SUSPENSION }
            .map { (reading.rootPitchClass + it.semitones).mod(12) }
}

/** 12-bit pitch-class-set identity used as the node key throughout the pathway layer. */
internal fun pitchClassMask(pitchClasses: Collection<Int>): Int =
    pitchClasses.fold(0) { acc, pitchClass -> acc or (1 shl pitchClass.mod(12)) }

internal fun maskToPitchClasses(mask: Int): List<Int> = (0..11).filter { (mask shr it) and 1 == 1 }

object StandardVoiceLeadingUniverses {
    /**
     * Triads and sevenths as destinations, suspended sonorities as transit only.
     *
     * sus2 and sus4 are the same pitch-class set; registering both definitions keeps both root
     * readings available, which is exactly what makes `1-2-5` readable as Gsus4 when it resolves
     * to V and as Csus2 when it is heard over the tonic.
     */
    val TERTIAN_WITH_SUSPENSIONS = VoiceLeadingUniverse(
        id = VoiceLeadingUniverseId("tertian.with-suspensions"),
        registrations = buildList {
            StandardVoiceLeadingChordFamilies.all.forEach { family ->
                family.definitions.forEach { definition ->
                    add(VoiceLeadingSetClassRegistration(definition, VoiceLeadingStability.STABLE))
                }
            }
            listOf(ChordQuality.SUS2, ChordQuality.SUS4).forEach { quality ->
                add(
                    VoiceLeadingSetClassRegistration(
                        definition = BuiltInChordDefinitions.forQuality(quality),
                        stability = VoiceLeadingStability.TRANSITIONAL,
                    )
                )
            }
        },
    )

    /** Stable-only universe; reproduces the base adjacency graph's vocabulary. */
    val TERTIAN_STABLE_ONLY = VoiceLeadingUniverse(
        id = VoiceLeadingUniverseId("tertian.stable-only"),
        registrations = TERTIAN_WITH_SUSPENSIONS.registrations
            .filter { it.stability == VoiceLeadingStability.STABLE },
    )
}
