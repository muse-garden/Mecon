package com.mecon.theory.schoenberg

import com.mecon.theory.ChordArity
import com.mecon.theory.Mode
import com.mecon.theory.constraint.AugmentedSixthMetadata
import com.mecon.theory.constraint.ChordTarget
import com.mecon.theory.constraint.InterpretedChordTarget
import com.mecon.theory.constraint.RootlessDominantNinthMetadata
import com.mecon.theory.constraint.SecondaryHarmonyMetadata
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookTriadPosition

/**
 * Single semantic conversion from a solver target back to a chapter symbolic chord.
 *
 * The result is compared against chords the chapters build themselves, so it must spell inversions
 * exactly the way they do: a seventh chord carries its inversion in [SchoenbergSymbolicChord
 * .seventhPosition] and leaves [SchoenbergSymbolicChord.position] at root, because the triad
 * position axis does not describe it.
 */
internal fun ChordTarget.toSchoenbergSymbolicChord(): SchoenbergSymbolicChord {
    val interpreted = this as? InterpretedChordTarget
        ?: return SchoenbergSymbolicChord(
            degree = degree,
            quality = quality,
            position = triadPositionOf(arity, inversion),
            arity = arity,
            seventhPosition = seventhPositionOf(arity, inversion),
        )
    val interpretation = interpreted.interpretation
    val symbol = interpretation.symbol
    val augmentedSixthFamily = AugmentedSixthMetadata.familyOf(this)
    val attributes = interpretation.attributes
    val modalOrigins = attributes[SecondaryHarmonyMetadata.MODAL_ORIGINS_NAME]
        ?.split(',')
        ?.mapNotNull { name -> runCatching { Mode.valueOf(name) }.getOrNull() }
        ?.toSet()
        .orEmpty() + if (
        interpretation.treatmentIds.any {
            it == SchoenbergHarmonicTreatments.MINOR_SUBDOMINANT ||
                it == SchoenbergHarmonicTreatments.NEAPOLITAN
        }
    ) setOf(Mode.AEOLIAN) else emptySet()
    val appliedToDegree = augmentedSixthFamily?.let { AugmentedSixthMetadata.targetDegreeOf(this) }
        ?: symbol.appliedToDegree
        ?: SecondaryHarmonyMetadata.tonicizedDegreeOf(this)
    val secondaryFamily = SecondaryHarmonyMetadata.familyOf(this)
        // A secondary reading is only meaningful with the degree it tonicizes.
        ?.takeIf { appliedToDegree != null }
    return SchoenbergSymbolicChord(
        degree = symbol.degree,
        quality = symbol.quality,
        position = triadPositionOf(symbol.arity, interpreted.inversion),
        arity = symbol.arity,
        seventhPosition = seventhPositionOf(symbol.arity, interpreted.inversion),
        rootAlteration = symbol.alteration,
        appliedToDegree = appliedToDegree,
        secondaryFamily = secondaryFamily,
        augmentedSixthFamily = augmentedSixthFamily,
        modalOrigins = modalOrigins,
        rootlessDominantNinthChordId = attributes[RootlessDominantNinthMetadata.CHORD_ID_NAME],
        rootlessDominantNinthUsageId = attributes[RootlessDominantNinthMetadata.USAGE_ID_NAME],
        omittedRootDegree = attributes[RootlessDominantNinthMetadata.OMITTED_ROOT_DEGREE_NAME]
            ?.toIntOrNull(),
        omittedRootAlteration = attributes[RootlessDominantNinthMetadata.OMITTED_ROOT_ALTERATION_NAME]
            ?.toIntOrNull() ?: 0,
    )
}

private fun triadPositionOf(arity: ChordArity, inversion: Int): TextbookTriadPosition =
    if (arity == ChordArity.TRIAD) {
        TextbookTriadPosition.entries.getOrElse(inversion) { TextbookTriadPosition.ROOT_POSITION }
    } else {
        TextbookTriadPosition.ROOT_POSITION
    }

private fun seventhPositionOf(arity: ChordArity, inversion: Int): TextbookSeventhPosition? =
    if (arity == ChordArity.SEVENTH) {
        TextbookSeventhPosition.entries.getOrNull(inversion)
    } else {
        null
    }
