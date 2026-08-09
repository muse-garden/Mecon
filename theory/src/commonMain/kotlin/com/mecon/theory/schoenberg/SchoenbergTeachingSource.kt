package com.mecon.theory.schoenberg

import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.Mode
import com.mecon.theory.ModulationKey
import com.mecon.theory.constraint.AugmentedSixthFamily
import com.mecon.theory.constraint.SecondaryHarmonyFamily
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookTriadPosition
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Provenance of the chapter material a free-practice idiom variant was cut from.
 *
 * Variants routinely show only part of their teaching program — the Neapolitan cadence without its
 * final tonic, a predominant group without its cadence, a secondary-dominant connection without its
 * preparation. Rule projection recompiles the original program from this record instead of guessing
 * it back from the visible chords, so a truncated span can never be mistaken for a shorter
 * progression of a different kind.
 */
internal data class SchoenbergTeachingSource(
    val key: ModulationKey? = null,
    val progression: SchoenbergSymbolicProgression? = null,
    /** Slot of [progression] the visible idiom starts at. */
    val start: Int = 0,
    val cadenceOptions: SchoenbergCadenceOptions = SchoenbergCadenceOptions(),
) {
    init {
        require(start >= 0) { "A teaching source start must be non-negative" }
        require(progression == null || start < progression.slots.size) {
            "A teaching source start must address a slot of its progression"
        }
    }

    /** Chapters compile from a continuation count; the source progression owns the real length. */
    val continuationChordCount: Int? get() = progression?.let { it.slots.size - 1 }
}

/** Stable persistence codec for the chapter-owned provenance embedded in idiom parameters. */
internal object SchoenbergTeachingSourceCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(source: SchoenbergTeachingSource): String =
        json.encodeToString(TeachingSourceSnapshot.from(source))

    fun decode(value: String): SchoenbergTeachingSource? = runCatching {
        json.decodeFromString<TeachingSourceSnapshot>(value).toTheory()
    }.getOrNull()
}

@Serializable
private data class TeachingSourceSnapshot(
    val fifths: Int? = null,
    val mode: String? = null,
    val progression: ProgressionSnapshot? = null,
    val start: Int = 0,
    val includeDeceptiveCadence: Boolean = false,
    val includeCadentialSixFour: Boolean = false,
) {
    fun toTheory(): SchoenbergTeachingSource = SchoenbergTeachingSource(
        key = fifths?.let { value ->
            mode?.let { ModulationKey(value, enumValueOf<KeySignatureMode>(it)) }
        },
        progression = progression?.toTheory(),
        start = start,
        cadenceOptions = SchoenbergCadenceOptions(
            includeDeceptiveCadence = includeDeceptiveCadence,
            includeCadentialSixFour = includeCadentialSixFour,
        ),
    )

    companion object {
        fun from(value: SchoenbergTeachingSource) = TeachingSourceSnapshot(
            fifths = value.key?.fifths,
            mode = value.key?.mode?.name,
            progression = value.progression?.let(ProgressionSnapshot::from),
            start = value.start,
            includeDeceptiveCadence = value.cadenceOptions.includeDeceptiveCadence,
            includeCadentialSixFour = value.cadenceOptions.includeCadentialSixFour,
        )
    }
}

@Serializable
private data class ProgressionSnapshot(
    val slots: List<ChordSnapshot>,
    val kind: String,
    val knowledgeTags: List<String> = emptyList(),
) {
    fun toTheory(): SchoenbergSymbolicProgression = SchoenbergSymbolicProgression(
        slots = slots.map(ChordSnapshot::toTheory),
        kind = enumValueOf(kind),
        knowledgeTags = knowledgeTags.mapTo(linkedSetOf()) {
            enumValueOf<SchoenbergKnowledgeTag>(it)
        },
    )

    companion object {
        fun from(value: SchoenbergSymbolicProgression) = ProgressionSnapshot(
            slots = value.slots.map(ChordSnapshot::from),
            kind = value.kind.name,
            knowledgeTags = value.knowledgeTags.map(SchoenbergKnowledgeTag::name).sorted(),
        )
    }
}

@Serializable
private data class ChordSnapshot(
    val degree: Int,
    val quality: String,
    val position: String,
    val arity: String,
    val seventhPosition: String? = null,
    val rootAlteration: Int = 0,
    val appliedToDegree: Int? = null,
    val secondaryFamily: String? = null,
    val augmentedSixthFamily: String? = null,
    val modalOrigins: List<String> = emptyList(),
    val rootlessDominantNinthChordId: String? = null,
    val rootlessDominantNinthUsageId: String? = null,
    val omittedRootDegree: Int? = null,
    val omittedRootAlteration: Int = 0,
) {
    fun toTheory() = SchoenbergSymbolicChord(
        degree = degree,
        quality = enumValueOf<ChordQuality>(quality),
        position = enumValueOf<TextbookTriadPosition>(position),
        arity = enumValueOf<ChordArity>(arity),
        seventhPosition = seventhPosition?.let {
            enumValueOf<TextbookSeventhPosition>(it)
        },
        rootAlteration = rootAlteration,
        appliedToDegree = appliedToDegree,
        secondaryFamily = secondaryFamily?.let { enumValueOf<SecondaryHarmonyFamily>(it) },
        augmentedSixthFamily = augmentedSixthFamily?.let {
            enumValueOf<AugmentedSixthFamily>(it)
        },
        modalOrigins = modalOrigins.mapTo(linkedSetOf()) { enumValueOf<Mode>(it) },
        rootlessDominantNinthChordId = rootlessDominantNinthChordId,
        rootlessDominantNinthUsageId = rootlessDominantNinthUsageId,
        omittedRootDegree = omittedRootDegree,
        omittedRootAlteration = omittedRootAlteration,
    )

    companion object {
        fun from(value: SchoenbergSymbolicChord) = ChordSnapshot(
            degree = value.degree,
            quality = value.quality.name,
            position = value.position.name,
            arity = value.arity.name,
            seventhPosition = value.seventhPosition?.name,
            rootAlteration = value.rootAlteration,
            appliedToDegree = value.appliedToDegree,
            secondaryFamily = value.secondaryFamily?.name,
            augmentedSixthFamily = value.augmentedSixthFamily?.name,
            modalOrigins = value.modalOrigins.map(Mode::name).sorted(),
            rootlessDominantNinthChordId = value.rootlessDominantNinthChordId,
            rootlessDominantNinthUsageId = value.rootlessDominantNinthUsageId,
            omittedRootDegree = value.omittedRootDegree,
            omittedRootAlteration = value.omittedRootAlteration,
        )
    }
}
