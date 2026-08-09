package com.mecon.theory.schoenberg

import com.mecon.theory.ChordArity
import com.mecon.theory.Key
import com.mecon.theory.Mode
import com.mecon.theory.NaturalTriad
import com.mecon.theory.constraint.ChordTarget
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.constraint.TargetSelector
import com.mecon.theory.harmony.HarmonicTreatmentId
import com.mecon.theory.textbook.DominantSeventhRules
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookTriadPosition

internal object SchoenbergIntegratedVocabulary {
    fun forStage(exerciseId: String, key: Key): Set<SchoenbergSymbolicChord> {
        val treatmentIds = SchoenbergCommonToneExercises
            .descriptorForExercise(exerciseId)
            .harmonicTreatmentIds
        require(treatmentIds.isNotEmpty()) {
            "Exercise $exerciseId does not define an integrated vocabulary"
        }
        return build(
            key = key,
            treatmentIds = treatmentIds,
        ).toSet()
    }

    fun build(
        key: Key,
        treatmentIds: Set<HarmonicTreatmentId>,
    ): List<SchoenbergSymbolicChord> {
        val stage = SchoenbergIntegratedStageSpec(treatmentIds)
        val includeLeadingTriad = stage.includes(SchoenbergHarmonicTreatments.LEADING_TRIAD)
        val includeFirstInversion = stage.includes(SchoenbergHarmonicTreatments.FIRST_INVERSION)
        val includeSecondInversion = stage.includes(SchoenbergHarmonicTreatments.SECOND_INVERSION)
        val includeSeventhChords = stage.includes(SchoenbergHarmonicTreatments.DIATONIC_DOMINANT)
        val includeSecondaryHarmony = stage.includes(SchoenbergHarmonicTreatments.SECONDARY_HARMONY)
        val includeDiminishedSeventh =
            stage.includes(SchoenbergHarmonicTreatments.ROOTLESS_DOMINANT_NINTH)
        val includeAugmentedSixth =
            stage.includes(SchoenbergHarmonicTreatments.AUGMENTED_SIXTH)
        val triads = exerciseTriads(key, includeLeadingTriad = includeLeadingTriad)
        val triadsWithPositions = triads.flatMap { triad ->
            triadPositions(includeFirstInversion, includeSecondInversion).map(triad::toSymbolic)
        }
        val sevenths = when {
            !includeSeventhChords -> emptyList()
            key.mode == Mode.AEOLIAN -> SchoenbergMinorChapter.minorSeventhVocabulary(key)
            else -> seventhVocabulary(key)
        }
        val secondaryHarmony = if (includeSecondaryHarmony) {
            SchoenbergSecondaryDominantChapter.exerciseChords(key)
        } else {
            emptyList()
        }
        val diminishedSevenths = if (includeDiminishedSeventh) {
            SchoenbergDiminishedSeventhChapter.exerciseChords(key)
        } else {
            emptyList()
        }
        val augmentedSixths = if (includeAugmentedSixth) {
            SchoenbergAugmentedSixthChapter.exerciseChords(key)
        } else {
            emptyList()
        }
        return triadsWithPositions + sevenths + secondaryHarmony + diminishedSevenths + augmentedSixths
    }

    fun progressionMatchesSelectors(
        progression: SchoenbergSymbolicProgression,
        key: Key,
        selectors: List<TargetSelector>,
    ): Boolean = matchesSelectors(
        chords = progression.slots,
        selectors = selectors,
        triads = exerciseTriads(key, includeLeadingTriad = true),
    )

    fun isLeadingTriad(
        chord: SchoenbergSymbolicChord,
        triads: List<NaturalTriad>,
    ): Boolean =
        chord.secondaryFamily == null &&
            chord.arity == ChordArity.TRIAD &&
            chord.triadIn(triads).isLeadingTriad()

    fun isLeadingSeventh(chord: SchoenbergSymbolicChord): Boolean =
        chord.arity == ChordArity.SEVENTH && chord.degree == LEADING_TONE_DEGREE

    fun isSixFour(chord: SchoenbergSymbolicChord): Boolean =
        chord.arity == ChordArity.TRIAD &&
            chord.augmentedSixthFamily == null &&
            chord.position == TextbookTriadPosition.SECOND_INVERSION

    fun dissonantPitchClasses(target: ChordTarget) = buildList {
        target.pitchClassFor(ChordTone.SEVENTH)?.let(::add)
        val root = target.pitchClassFor(ChordTone.ROOT)
        val fifth = target.pitchClassFor(ChordTone.FIFTH)
        if (root != null && fifth != null && (fifth.value - root.value).mod(12) == 6) add(fifth)
    }

    fun bassMovesByDiatonicStep(
        before: ChordTarget,
        after: ChordTarget,
        key: Key,
    ): Boolean {
        val from = key.scale.pitchClasses.indexOf(before.bassPitchClass)
        val to = key.scale.pitchClasses.indexOf(after.bassPitchClass)
        if (from < 0 || to < 0) return false
        return (to - from).mod(7) in setOf(0, 1, 6)
    }

    fun matchesSelectors(
        chords: List<SchoenbergSymbolicChord>,
        selectors: List<TargetSelector>,
        triads: List<NaturalTriad>,
    ): Boolean {
        fun assign(selectorIndex: Int, usedSlots: Set<Int>): Boolean {
            if (selectorIndex == selectors.size) return true
            return chords.indices.any { slot ->
                slot !in usedSlots &&
                    selectors[selectorIndex].matches(chords[slot].toTarget(triads)) &&
                    assign(selectorIndex + 1, usedSlots + slot)
            }
        }
        return assign(0, emptySet())
    }

    private fun triadPositions(
        includeFirst: Boolean,
        includeSecond: Boolean,
    ): List<TextbookTriadPosition> = buildList {
        add(TextbookTriadPosition.ROOT_POSITION)
        if (includeFirst) add(TextbookTriadPosition.FIRST_INVERSION)
        if (includeSecond) add(TextbookTriadPosition.SECOND_INVERSION)
    }

    private fun seventhVocabulary(key: Key): List<SchoenbergSymbolicChord> =
        (1..7).flatMap { degree ->
            val chord = DominantSeventhRules.seventhChordInKey(key, degree)
            TextbookSeventhPosition.entries.map { position ->
                SchoenbergSymbolicChord(
                    degree = degree,
                    quality = chord.quality,
                    arity = ChordArity.SEVENTH,
                    seventhPosition = position,
                )
            }
        }
}
