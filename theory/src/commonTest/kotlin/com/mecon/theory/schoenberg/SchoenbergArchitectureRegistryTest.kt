package com.mecon.theory.schoenberg

import com.mecon.api.primitive.PitchClass
import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.Key
import com.mecon.theory.RuleId
import com.mecon.theory.harmony.HarmonicRuleFamilyId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SchoenbergArchitectureRegistryTest {
    private val key = Key.major(PitchClass.C)

    @Test
    fun registryDerivesChapterOwnershipFromExerciseDescriptors() {
        assertEquals(
            "schoenberg.secondary-harmony",
            SchoenbergChapterRegistry
                .chapterFor(SchoenbergSecondaryDominantChapter.LEADING_TONE_RULE_ID)
                ?.id,
        )
        assertEquals(
            "schoenberg.diminished-seventh",
            SchoenbergChapterRegistry
                .chapterFor(SchoenbergDiminishedSeventhChapter.ALTERED_TONE_STEP_RULE_ID)
                ?.id,
        )
        assertEquals(null, SchoenbergChapterRegistry.chapterFor(RuleId("solver.parallel-fifth")))
    }

    @Test
    fun everyExerciseDescriptorHasExactlyOneExecutableHandler() {
        assertEquals(
            SchoenbergCommonToneExercises.exerciseDescriptors
                .mapTo(linkedSetOf()) { it.exerciseId },
            SchoenbergChapterRegistry.registeredExerciseIds,
        )
    }

    @Test
    fun exerciseLevelCatalogMergesPhysicalSonorityAcrossFamilies() {
        val tonic = SchoenbergSymbolicChord(
            degree = 1,
            quality = ChordQuality.MAJOR,
            arity = ChordArity.TRIAD,
        )
        val catalog = SchoenbergChordCatalog.collect(key, listOf(tonic))
        val diatonic = catalog.entries
            .firstOrNull { entry ->
                entry.interpretations.any { it.id.value.startsWith("diatonic.1.") }
            }
        assertNotNull(diatonic)
        assertTrue(
            diatonic.interpretations.any { interpretation ->
                interpretation.id.value.startsWith("secondary.")
            },
            "C major must retain both its diatonic reading and an applied/modal reading.",
        )
    }

    @Test
    fun rootlessTreatmentInheritsReferenceAndAddsChromaticRules() {
        val resolved = SchoenbergHarmonicTreatments.registry.resolve(
            setOf(SchoenbergHarmonicTreatments.ROOTLESS_DOMINANT_NINTH)
        )

        assertTrue(SchoenbergHarmonicTreatments.SECONDARY_HARMONY in resolved.includedTreatmentIds)
        assertTrue(SchoenbergHarmonicTreatments.DIATONIC_DOMINANT in resolved.substitutionTargets)
        assertTrue(
            HarmonicRuleFamilyId("dominant.leading-tone-resolution") in resolved.ruleFamilies
        )
        assertTrue(
            HarmonicRuleFamilyId("chromatic.altered-tone-step") in resolved.ruleFamilies
        )
        assertEquals(
            SchoenbergExerciseHandlerIds.INTEGRATED,
            SchoenbergCommonToneExercises
                .descriptorForExercise(
                    SchoenbergCommonToneExercises.INTEGRATED_MAJOR_LEADING_EXERCISE_ID
                )
                .handlerId,
        )
        assertEquals(
            SchoenbergExerciseHandlerIds.INTEGRATED,
            SchoenbergCommonToneExercises
                .descriptorForExercise(SchoenbergCommonToneExercises.SECONDARY_HARMONY_EXERCISE_ID)
                .handlerId,
        )
    }

    @Test
    fun integratedDescriptorsDeclareVocabularyThroughTreatments() {
        val integratedIds = SchoenbergIntegratedTechTree.exerciseDescriptors
            .mapTo(linkedSetOf(), SchoenbergExerciseDescriptor::exerciseId) + setOf(
                SchoenbergCommonToneExercises.ROOT_MOTION_AND_REPETITION_EXERCISE_ID,
                SchoenbergCommonToneExercises.CADENCE_EXERCISE_ID,
                SchoenbergCommonToneExercises.FREER_SEVENTH_LEADING_EXERCISE_ID,
                SchoenbergCommonToneExercises.SECONDARY_HARMONY_EXERCISE_ID,
                SchoenbergCommonToneExercises.INTEGRATED_DIMINISHED_SEVENTH_EXERCISE_ID,
            )

        integratedIds.forEach { exerciseId ->
            assertTrue(
                SchoenbergCommonToneExercises
                    .descriptorForExercise(exerciseId)
                    .harmonicTreatmentIds
                    .isNotEmpty(),
                "$exerciseId must declare its integrated vocabulary treatments",
            )
        }
    }

    @Test
    fun integratedTreatmentSetsComposeVocabularyCapabilities() {
        val seventhStage = SchoenbergIntegratedStageSpec(
            SchoenbergHarmonicTreatments.integratedDiatonicTreatments
        )
        val diminishedStage = SchoenbergIntegratedStageSpec(
            SchoenbergHarmonicTreatments.integratedDiminishedTreatments
        )

        assertTrue(seventhStage.includes(SchoenbergHarmonicTreatments.LEADING_TRIAD))
        assertTrue(seventhStage.includes(SchoenbergHarmonicTreatments.FIRST_INVERSION))
        assertTrue(seventhStage.includes(SchoenbergHarmonicTreatments.SECOND_INVERSION))
        assertTrue(seventhStage.includes(SchoenbergHarmonicTreatments.DIATONIC_DOMINANT))
        assertTrue(diminishedStage.includes(SchoenbergHarmonicTreatments.SECONDARY_HARMONY))
        assertTrue(diminishedStage.includes(SchoenbergHarmonicTreatments.ROOTLESS_DOMINANT_NINTH))
    }
}
