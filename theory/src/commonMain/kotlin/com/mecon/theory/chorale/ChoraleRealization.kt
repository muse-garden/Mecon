package com.mecon.theory.chorale

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.theory.FixedVoiceRole
import com.mecon.theory.NonChordToneType
import com.mecon.theory.ScoreBreakdown
import com.mecon.theory.constraint.ChordVoicing

/**
 * One sounding note of a realized voice.
 *
 * [nonChordTone] is null for chord tones. It is filled by [com.mecon.theory.NonChordToneClassifier],
 * never by the generator, so a note that cannot be named is never produced in the first place.
 */
data class ChoraleNote(
    val onset: TimeCode,
    val duration: Fraction,
    val pitch: Pitch,
    /** Harmonic slot this note begins in. */
    val slot: Int,
    val nonChordTone: NonChordToneType? = null,
) {
    init { require(duration.isPositive) { "A chorale note needs a positive duration" } }

    /** Content identity, independent of which rhythm pattern happened to produce the note. */
    internal val identity: String
        get() = "${pitch.midiNumber}@$onset+${duration.simplified()}" +
            (nonChordTone?.abbreviation ?: "")
}

data class ChoraleLine(
    val role: FixedVoiceRole,
    val notes: List<ChoraleNote>,
) {
    val nonChordTones: List<ChoraleNote> get() = notes.filter { it.nonChordTone != null }
}

/** Tension of one sounding vertical, keyed by the moment it starts. */
data class ChoraleTensionPoint(
    val onset: TimeCode,
    val slot: Int,
    val pitchClasses: List<Int>,
    val tension: Double,
    /** True at a harmonic downbeat, i.e. a point the skeleton is responsible for. */
    val structural: Boolean,
)

/**
 * Tension shape of one harmonic transition.
 *
 * [arc] above zero is a real conflict-then-release inside the span; at or below zero the span went
 * by without a breath, however many notes were added.
 */
data class ChoraleTensionArc(
    val slot: Int,
    val peak: Double,
    val arc: Double,
)

data class ChoraleRealization(
    val skeleton: List<ChordVoicing>,
    val lines: List<ChoraleLine>,
    val tensionCurve: List<ChoraleTensionPoint>,
    val tensionArcs: List<ChoraleTensionArc>,
    val breakdown: ScoreBreakdown,
) {
    fun line(role: FixedVoiceRole): ChoraleLine =
        lines.first { it.role == role }

    /** Decoration signature: what kind of figure each voice took in each span. */
    val figurationSignature: String
        get() = lines.joinToString("|") { line ->
            line.role.name.first() + ":" + line.notes.joinToString(",") { note ->
                note.nonChordTone?.abbreviation ?: "-"
            }
        }
}

/** Why no realization could be produced. Never a silent empty list. */
data class ChoraleDiagnostic(
    val code: String,
    val message: String,
)

data class ChoraleResult(
    val realizations: List<ChoraleRealization>,
    val diagnostics: List<ChoraleDiagnostic> = emptyList(),
)
