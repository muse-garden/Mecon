package com.mecon.plugins.chord

import com.mecon.api.primitive.Pitch
import com.mecon.theory.ModulationCircleOfFifths
import com.mecon.theory.ModulationKey
import com.mecon.theory.ModulationPitchLabels
import com.mecon.theory.SpelledPitchClass
import kotlin.math.abs

/** A key candidate inferred from the spelling of the selected score notes. */
data class TonalRegionKeyCandidate(
    val key: ModulationKey,
    val degreeLabels: List<String>,
    val alteredToneCount: Int,
    val uncommonAlterationCount: Int,
    val totalAlterationMagnitude: Int,
)

/**
 * Shared key-inference policy for score-analysis tonal regions.
 *
 * Fewer altered selected tones always win. Ties prefer familiar chromatic degrees (notably
 * raised 4 and raised 5), then the nearest key on the circle of fifths. The UI only presents the
 * result and never duplicates this ordering policy.
 */
object TonalRegionKeyInference {
    fun candidates(
        pitches: Collection<Pitch>,
        referenceKey: ModulationKey,
        limit: Int? = null,
    ): List<TonalRegionKeyCandidate> {
        val spellings = pitches
            .map { SpelledPitchClass(it.noteName, it.chromaticOffset) }
            .distinct()
            .sortedWith(compareBy<SpelledPitchClass> { it.noteName.ordinal }.thenBy { it.chromaticOffset })
        if (spellings.isEmpty()) return emptyList()

        val ranked = ModulationKey.circleOfFifths.map { key ->
            val labels = spellings.map { ModulationPitchLabels.relativePitchLabel(key, it) }
            val alterations = labels.map(::alteration)
            TonalRegionKeyCandidate(
                key = key,
                degreeLabels = labels,
                alteredToneCount = alterations.count { it.magnitude > 0 },
                uncommonAlterationCount = alterations.count {
                    it.magnitude > 0 && it.label !in COMMON_CHROMATIC_DEGREES
                },
                totalAlterationMagnitude = alterations.sumOf(Alteration::magnitude),
            )
        }.sortedWith(
            compareBy<TonalRegionKeyCandidate> { it.alteredToneCount }
                .thenBy { it.uncommonAlterationCount }
                .thenBy { it.totalAlterationMagnitude }
                .thenBy { abs(ModulationCircleOfFifths.signedDistance(referenceKey, it.key)) }
                .thenBy { if (it.key.mode == referenceKey.mode) 0 else 1 }
                .thenBy { abs(it.key.fifths - referenceKey.fifths) }
                .thenBy { it.key.fifths }
                .thenBy { it.key.mode.ordinal },
        )
        return limit?.let(ranked::take) ?: ranked
    }

    /** Natural degrees first, then single common alterations, for the single-note picker. */
    fun singlePitchChoices(
        pitch: Pitch,
        referenceKey: ModulationKey,
    ): List<TonalRegionKeyCandidate> = candidates(listOf(pitch), referenceKey).sortedWith(
        compareBy<TonalRegionKeyCandidate> { it.totalAlterationMagnitude }
            .thenBy { it.uncommonAlterationCount }
            .thenBy { degreeNumber(it.degreeLabels.single()) }
            .thenBy { abs(ModulationCircleOfFifths.signedDistance(referenceKey, it.key)) }
            .thenBy { if (it.key.mode == referenceKey.mode) 0 else 1 }
            .thenBy { it.key.fifths },
    )

    private fun alteration(label: String): Alteration = Alteration(
        label = label,
        magnitude = label.count { it == '♯' || it == '♭' },
    )

    private fun degreeNumber(label: String): Int =
        label.takeLastWhile(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE

    private data class Alteration(val label: String, val magnitude: Int)

    private val COMMON_CHROMATIC_DEGREES = setOf("♯4", "♯5", "♭2", "♭3", "♭6", "♭7")
}

/** Immutable insertion semantics shared by the desktop adapter and common tests. */
object TonalRegionEditPolicy {
    fun insert(
        existing: List<StorageTonalRegionEvent>,
        region: StorageTonalRegionEvent,
        terminatePrevious: Boolean,
    ): List<StorageTonalRegionEvent> {
        require(existing.none { it.id == region.id }) { "Duplicate tonal-region id ${region.id.value}" }
        val prepared = if (!terminatePrevious) {
            existing
        } else {
            existing.map { previous ->
                if (previous.onset < region.onset && previous.contains(region.onset)) {
                    previous.copy(
                        endOnset = minOf(previous.endOnset, region.endOnset),
                        resolvedKey = null,
                    )
                } else {
                    previous
                }
            }
        }
        return (prepared + region).sortedWith(
            compareBy<StorageTonalRegionEvent> { it.onset }.thenBy { it.id.value },
        )
    }
}
