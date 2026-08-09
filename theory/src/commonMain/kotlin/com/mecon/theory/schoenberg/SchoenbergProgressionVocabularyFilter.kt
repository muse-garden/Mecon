package com.mecon.theory.schoenberg

import com.mecon.api.primitive.PitchClass
import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.Key
import com.mecon.theory.TonalContext
import com.mecon.theory.constraint.SecondaryHarmonyFamily
import com.mecon.theory.textbook.TextbookTriadPosition

/**
 * Candidate vocabulary is a caller policy, not part of a functional connection rule. Textbook
 * exercises and free practice currently use [SchoenbergProgressionVocabularyFilters.StudiedInActiveKey];
 * later modulation-aware callers can substitute a target-key filter without changing the rule.
 */
internal fun interface SchoenbergProgressionVocabularyFilter {
    fun allows(key: Key, progression: SchoenbergSymbolicProgression): Boolean
}

internal object SchoenbergProgressionVocabularyFilters {
    val StudiedInActiveKey = SchoenbergProgressionVocabularyFilter { key, progression ->
        runCatching { SchoenbergChordCatalog.targets(key, progression.slots) }.isSuccess
    }

    val None = SchoenbergProgressionVocabularyFilter { _, _ -> true }
}

/** The class-domain relation behind the textbook's analogous-Neapolitan exercise. */
internal object AnalogousNeapolitanRelation {
    fun allows(
        sourceRoot: PitchClass,
        localTonicRoot: PitchClass,
        sourceIsMajorSixth: Boolean,
        localTonicIsSixFour: Boolean,
        appliedDominantTargetsLocalTonic: Boolean,
    ): Boolean =
        sourceIsMajorSixth &&
            localTonicIsSixFour &&
            appliedDominantTargetsLocalTonic &&
            sourceRoot == localTonicRoot.transpose(1)

    fun allows(key: Key, slots: List<SchoenbergSymbolicChord>): Boolean {
        if (slots.size != 3) return false
        val (source, localTonic, localDominant) = slots
        if (
            source.arity != ChordArity.TRIAD ||
            source.quality != ChordQuality.MAJOR ||
            source.position != TextbookTriadPosition.FIRST_INVERSION ||
            localTonic.arity != ChordArity.TRIAD ||
            localTonic.position != TextbookTriadPosition.SECOND_INVERSION ||
            localDominant.secondaryFamily != SecondaryHarmonyFamily.SECONDARY_DOMINANT ||
            localDominant.appliedToDegree != localTonic.degree
        ) {
            return false
        }
        val context = TonalContext.fromKey(key)
        val sourceRoot = context.spellDegree(source.degree, source.rootAlteration).pitchClass
        val localTonicRoot = context.spellDegree(
            localTonic.degree,
            localTonic.rootAlteration,
        ).pitchClass
        return allows(
            sourceRoot = sourceRoot,
            localTonicRoot = localTonicRoot,
            sourceIsMajorSixth = true,
            localTonicIsSixFour = true,
            appliedDominantTargetsLocalTonic = true,
        )
    }
}
