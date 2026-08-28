package com.mecon.theory.chorale

import com.mecon.api.primitive.Fraction
import com.mecon.theory.FixedVoiceRole
import com.mecon.theory.NonChordToneType
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.voiceleading.VoiceLeadingTensionPolicy

/**
 * Where a voice may attack inside one harmonic span, as fractions of that span.
 *
 * A pattern is an *option*, not an instruction: the search picks one per voice per span, which is
 * how "每个声部的节奏型" stays a partial specification.
 */
data class ChoraleRhythmPattern(
    val id: String,
    val divisions: List<Fraction>,
) {
    init {
        require(id.isNotBlank()) { "A rhythm pattern needs an id" }
        require(divisions.isNotEmpty()) { "A rhythm pattern needs at least one division" }
        require(divisions.all { it.isPositive }) { "Rhythm divisions must be positive" }
        require(divisions.reduce(Fraction::plus).simplified() == Fraction.ONE) {
            "Rhythm divisions must fill the harmonic span exactly"
        }
    }

    val size: Int get() = divisions.size

    companion object {
        val SUSTAINED = ChoraleRhythmPattern("sustained", listOf(Fraction.ONE))
        val HALVES = ChoraleRhythmPattern("halves", List(2) { Fraction.HALF })
        val QUARTERS = ChoraleRhythmPattern("quarters", List(4) { Fraction.QUARTER })

        /** Long-short: the decoration attacks late, which is where weak-beat figures live. */
        val LONG_SHORT = ChoraleRhythmPattern("long-short", listOf(Fraction(3, 4), Fraction.QUARTER))
    }
}

data class ChoraleVoicePlan(
    val role: FixedVoiceRole,
    val patterns: List<ChoraleRhythmPattern>,
) {
    init {
        require(patterns.isNotEmpty()) { "A voice needs at least one rhythm pattern" }
        require(patterns.map { it.id }.distinct().size == patterns.size) {
            "Rhythm pattern ids must be unique within a voice"
        }
    }
}

enum class ChoraleContourDirection {
    ASCENDING,
    DESCENDING,
    ARCH,
    VALLEY,
    STATIC,
}

/** A soft steer on where a voice should go, not a constraint: the user said "roughly". */
data class ChoraleContourRequest(
    val role: FixedVoiceRole,
    val window: SlotWindow,
    val direction: ChoraleContourDirection,
    val weight: Double = 1.0,
) {
    init { require(weight > 0.0) { "Contour weight must be positive" } }
}

/**
 * A conflict/resolution the user placed by hand.
 *
 * Suspensions and retardations are decided by the skeleton, so they name their voice and are
 * back-projected into stage one (`docs/theory/chorale-harmonization.md` §2.1). Insertion-type
 * figures only need a slot; stage two finds a voice that can carry them.
 */
data class ChoraleFigurationRequest(
    /** Harmonic slot whose downbeat carries the dissonance. */
    val slot: Int,
    val types: Set<NonChordToneType>,
    val role: FixedVoiceRole? = null,
    val required: Boolean = true,
) {
    init {
        require(slot >= 0) { "Figuration slot must be non-negative" }
        require(types.isNotEmpty()) { "A figuration request needs at least one type" }
        require(role != null || types.none { it in SKELETON_DECIDED }) {
            "Suspensions and retardations are decided by the skeleton, so they must name a voice"
        }
        require(slot > 0 || types.none { it in SKELETON_DECIDED }) {
            "A suspension needs a preceding slot to be prepared from"
        }
    }

    val requiresSuspension: Boolean get() = types.any { it in SKELETON_DECIDED }

    companion object {
        val SKELETON_DECIDED = setOf(NonChordToneType.SUSPENSION, NonChordToneType.RETARDATION)
    }
}

data class ChoraleFigurationDensity(
    val maxPerSpan: Int = 2,
    val maxPerVoice: Int = 8,
) {
    init {
        require(maxPerSpan >= 0 && maxPerVoice >= 0) { "Density budgets must be non-negative" }
    }
}

/** Every weight the chorale engine uses, in one immutable place. */
data class ChoraleScoringPolicy(
    val id: String,
    /**
     * Reward for a non-chord tone that answers a [ChoraleFigurationRequest].
     *
     * Unrequested figuration earns nothing: the user steers with conflict positions, so a plain
     * setting must win by default instead of the engine decorating everything it legally can.
     */
    val requestedFigurationBonus: Double,
    /** Reward per unit of tension arc where the user asked for a conflict. */
    val requestedArcBonus: Double,
    /** Penalty per unit of tension arc the user did not ask for. */
    val unrequestedArcPenalty: Double,
    /** Penalty for a requested conflict that stayed flat. */
    val missingArcPenalty: Double,
    val contourPenalty: Double,
    /**
     * Cost of every extra attack the user did not ask for, including consonant chordal skips.
     *
     * Without it the plainest setting merely ties with every busier one, and an arbitrary
     * tie-break decides what survives the beam — which is how the simple answer gets lost.
     */
    val activityCost: Double,
) {
    companion object {
        val DEFAULT = ChoraleScoringPolicy(
            id = "chorale.scoring.v1",
            requestedFigurationBonus = 1.0,
            requestedArcBonus = 2.0,
            unrequestedArcPenalty = 0.5,
            missingArcPenalty = 3.0,
            contourPenalty = 1.5,
            activityCost = 0.05,
        )
    }
}

/**
 * Harmonize a chorale: a fully specified progression, partially specified everything else.
 *
 * [skeleton] carries the complete chord specification with its real time spans, the key plan, voice
 * ranges and any pinned melody — it is an ordinary [ConstraintProgram], deliberately not a
 * re-modelled copy of one.
 */
data class ChoraleTask(
    val skeleton: ConstraintProgram,
    val voices: List<ChoraleVoicePlan>,
    val figuration: List<ChoraleFigurationRequest> = emptyList(),
    val contour: List<ChoraleContourRequest> = emptyList(),
    val density: ChoraleFigurationDensity = ChoraleFigurationDensity(),
    val search: SearchConfig = SearchConfig(maxResults = 5, beamWidth = 48),
    val tensionPolicy: VoiceLeadingTensionPolicy = VoiceLeadingTensionPolicy.DEFAULT,
    val scoringPolicy: ChoraleScoringPolicy = ChoraleScoringPolicy.DEFAULT,
    /** Cap on pitch sequences enumerated per voice per span, before scoring. */
    val maxFillingsPerVoiceSpan: Int = 24,
) {
    init {
        require(voices.isNotEmpty()) { "A chorale task needs voices" }
        require(voices.map { it.role }.distinct().size == voices.size) { "Voice roles must be unique" }
        require(figuration.all { it.slot in skeleton.slotDomains.indices }) {
            "Figuration slot out of range"
        }
        require(contour.all { request -> skeleton.slotDomains.indices.any(request.window::contains) }) {
            "Contour window out of range"
        }
        require(contour.all { request -> voices.any { it.role == request.role } }) {
            "Contour requests must name a planned voice"
        }
        require(figuration.all { request -> request.role == null || voices.any { it.role == request.role } }) {
            "Figuration requests must name a planned voice"
        }
        require(maxFillingsPerVoiceSpan >= 1) { "Filling cap must be positive" }
    }

    val slotCount: Int get() = skeleton.slotDomains.size
}
