package com.mecon.theory.freepractice

import com.mecon.api.primitive.Fraction
import com.mecon.theory.ModulationKey
import com.mecon.theory.harmony.ChordSelectionCatalog

/** Resolves the default input key at an idiom boundary without modifying manual key lines. */
fun HarmonyWorkspaceState.idiomSourceKeyAt(onset: Fraction): ModulationKey? {
    val atOnset = slots.firstOrNull { it.onset == onset }
    atOnset?.tonality?.let { tonality ->
        // Replacing a transition starts from its explicit source interpretation. A regular
        // one-key chord has no alternate and therefore continues from its primary key.
        return tonality.alternates.firstOrNull()?.key ?: tonality.primary.key
    }
    val preceding = slots
        .filter { it.onset + it.duration <= onset }
        .maxByOrNull { it.onset + it.duration }
    return preceding?.let(::continuationKey)
        ?: atOnset?.let(::continuationKey)
        ?: activeTonalLayouts(onset).firstOrNull()?.key
}

/**
 * Builds chord-owned key readings for an idiom. A genuine tonicization gives only its first
 * chord the source-key alternate; all later chords continue solely in the target key.
 */
fun HarmonyWorkspaceState.resolveIdiomTonalities(
    onset: Fraction,
    chordChoices: List<WorkspaceChordChoice>,
    sourceKey: ModulationKey,
    targetKey: ModulationKey,
): List<WorkspaceChordTonality>? {
    if (chordChoices.isEmpty()) return emptyList()
    val targetReadings = chordChoices.map { choice ->
        WorkspaceChordTonalReading.of(
            key = targetKey,
            interpretationRef = choice.pinnedInterpretationRef ?: return null,
        )
    }
    if (sourceKey == targetKey) {
        return targetReadings.map { WorkspaceChordTonality(primary = it) }
    }

    val firstChoice = chordChoices.first()
    val existing = slots.firstOrNull {
        it.onset == onset && it.chordChoice?.pitchClasses == firstChoice.pitchClasses
    }
    val existingReading = existing?.tonality?.readingFor(sourceKey)?.interpretationRef
    val legacyReading = existing?.chordChoice?.pinnedInterpretationRef?.takeIf { ref ->
        ChordSelectionCatalog.choices(sourceKey).any { choice ->
            choice.pitchClasses == firstChoice.pitchClasses.toSet() &&
                ref in choice.interpretationRefs
        }
    }
    val catalogReading = ChordSelectionCatalog.choices(sourceKey)
        .asSequence()
        .filter { it.pitchClasses == firstChoice.pitchClasses.toSet() }
        .mapNotNull { it.confirmedInterpretationRef }
        .firstOrNull()
    val sourceReading = WorkspaceChordTonalReading.of(
        key = sourceKey,
        interpretationRef = existingReading ?: legacyReading ?: catalogReading ?: return null,
    )
    return targetReadings.mapIndexed { index, targetReading ->
        WorkspaceChordTonality(
            primary = targetReading,
            alternates = if (index == 0) listOf(sourceReading) else emptyList(),
        )
    }
}
