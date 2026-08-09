package com.mecon.theory.schoenberg

import com.mecon.api.primitive.Fraction
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.SearchConfig
import com.mecon.theory.VoicePlan
import com.mecon.theory.ChordArity
import com.mecon.theory.constraint.AugmentedSixthFamily
import com.mecon.theory.constraint.AugmentedSixthMetadata
import com.mecon.theory.constraint.ChordSelectionTargetCatalog
import com.mecon.theory.constraint.ConstraintModality
import com.mecon.theory.constraint.ConstraintPredicate
import com.mecon.theory.constraint.InterpretedChordTarget
import com.mecon.theory.constraint.SecondaryHarmonyFamily
import com.mecon.theory.constraint.SecondaryHarmonyMetadata
import com.mecon.theory.constraint.atomicPredicates
import com.mecon.theory.freepractice.HarmonyWorkspaceState
import com.mecon.theory.freepractice.PracticeTeachingRuleRequest
import com.mecon.theory.freepractice.PracticeWritingScope
import com.mecon.theory.freepractice.PracticeWritingTrigger
import com.mecon.theory.freepractice.WorkspaceHarmonySlot
import com.mecon.theory.freepractice.WorkspaceIdiomInstance
import com.mecon.theory.freepractice.WorkspaceIdiomInstanceId
import com.mecon.theory.freepractice.WorkspaceSlotId
import com.mecon.theory.freepractice.WorkspaceTonalLayout
import com.mecon.theory.freepractice.WorkspaceTonalLayoutId
import com.mecon.theory.freepractice.WorkspaceVoiceSpec
import com.mecon.theory.freepractice.matchesWorkspaceChordChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SchoenbergPracticeTeachingRuleProjectorTest {
    private val key = ModulationKey(0, KeySignatureMode.MAJOR)

    @Test
    fun neapolitanNeighborMotionUsesTheHighFreePracticeWeight() {
        val descriptor = SchoenbergCommonToneExercises.descriptorForExercise(
            SchoenbergCommonToneExercises.NEAPOLITAN_CADENCE_EXERCISE_ID,
        )
        val idiom = discoverNeapolitanFreePracticeContribution(
            SchoenbergFreePracticeDiscoveryRequest(
                key,
                listOf(key),
                maxVariantsPerDefinition = 64,
            ),
            descriptor,
        ).idioms.single { it.id == "schoenberg.neapolitan.predominant" }
        val variant = idiom.variants.single { it.title == "♭II6 – I64 – V" }
        val projected = project(idiom, variant)
        val rules = projected.filter {
            it.ruleId == SchoenbergMinorSubdominantChapter.NEAPOLITAN_TO_SIX_FOUR_RULE_ID
        }

        assertTrue(rules.isNotEmpty(), "Projected rules: ${projected.map { it.ruleId }}")
        assertTrue(
            rules.all {
                it.modality == ConstraintModality.Prefer(
                    SchoenbergPracticeTeachingRuleProjector.VOICE_LEADING_RULE_WEIGHT,
                )
            },
        )
    }

    @Test
    fun minorSubdominantDerivationRemainsAnExplanatoryAnnotation() {
        val descriptor = SchoenbergCommonToneExercises.descriptorForExercise(
            SchoenbergCommonToneExercises.NEAPOLITAN_CADENCE_EXERCISE_ID,
        )
        val idiom = discoverNeapolitanFreePracticeContribution(
            SchoenbergFreePracticeDiscoveryRequest(
                key,
                listOf(key),
                maxVariantsPerDefinition = 64,
            ),
            descriptor,
        ).idioms.single { it.id == "schoenberg.neapolitan.predominant" }
        val projected = project(idiom, idiom.variants.first())
        val derivationRules = projected.filter {
            it.ruleId == SchoenbergMinorSubdominantChapter.DERIVATION_RULE_ID
        }

        assertTrue(
            derivationRules.isNotEmpty(),
            "Projected rules: ${projected.map { it.ruleId }}",
        )
        assertTrue(derivationRules.all { it.modality == ConstraintModality.Annotate })
    }

    @Test
    fun dominantSeventhAndGermanSixthReadingsKeepTheirSelectedChapterRules() {
        val discovery = SchoenbergFreePracticeDiscoveryRequest(
            key,
            listOf(key),
            maxVariantsPerDefinition = 64,
        )
        val secondaryDescriptor = SchoenbergCommonToneExercises.descriptorForExercise(
            SchoenbergCommonToneExercises.SECONDARY_HARMONY_EXERCISE_ID,
        )
        val augmentedDescriptor = SchoenbergCommonToneExercises.descriptorForExercise(
            SchoenbergCommonToneExercises.AUGMENTED_SIXTH_EXERCISE_ID,
        )
        val secondary = discoverSecondaryHarmonyFreePracticeContribution(
            discovery,
            secondaryDescriptor,
        ).idioms.first { it.id == "schoenberg.secondary-harmony.analogous-resolution" }
        val augmented = discoverAugmentedSixthFreePracticeContribution(
            discovery,
            augmentedDescriptor,
        ).idioms.first { it.id == "schoenberg.augmented-sixth.resolution" }
        val secondaryVariant = secondary.variants.firstOrNull { variant ->
            variant.chordChoices.first().target().let {
                it.arity == ChordArity.SEVENTH &&
                    SecondaryHarmonyMetadata.familyOf(it) == SecondaryHarmonyFamily.SECONDARY_DOMINANT
            }
        }
        val augmentedVariant = augmented.variants.firstOrNull { variant ->
            AugmentedSixthMetadata.familyOf(variant.chordChoices.first().target()) ==
                AugmentedSixthFamily.GERMAN
        }
        assertNotNull(secondaryVariant, "Expected a secondary-dominant seventh variant")
        assertNotNull(augmentedVariant, "Expected a German augmented-sixth variant")

        val secondaryRules = project(secondary, secondaryVariant)
        val augmentedRules = project(augmented, augmentedVariant)

        assertTrue(
            secondaryRules.any { it.ruleId?.value?.startsWith("schoenberg.secondary-harmony") == true },
            "Projected secondary rules: ${secondaryRules.map { it.ruleId?.value }}",
        )
        assertTrue(secondaryRules.none { it.ruleId?.value?.startsWith("schoenberg.augmented-sixth") == true })
        assertTrue(augmentedRules.any { it.ruleId?.value?.startsWith("schoenberg.augmented-sixth") == true })
        assertTrue(augmentedRules.none { it.ruleId?.value?.startsWith("schoenberg.secondary-harmony") == true })
        (secondaryRules + augmentedRules)
            .filter { it.modality != ConstraintModality.Annotate }
            .forEach {
                val expectedWeight = if (it.expr.atomicPredicates().any { predicate ->
                    predicate is ConstraintPredicate.NeighborTone ||
                        predicate is ConstraintPredicate.VoiceDiatonicSteps
                }
                ) {
                    SchoenbergPracticeTeachingRuleProjector.VOICE_LEADING_RULE_WEIGHT
                } else {
                    SchoenbergPracticeTeachingRuleProjector.CHAPTER_RULE_WEIGHT
                }
                assertEquals(
                    ConstraintModality.Prefer(expectedWeight),
                    it.modality,
                )
            }
    }

    /**
     * A cadential six-four only exists in a program compiled with `includeCadentialSixFour`, so the
     * variant has to carry which cadence its chapter compiled. Guessing the options back from the
     * visible chords is what the provenance record replaced.
     */
    @Test
    fun cadentialSixFourIdiomsProjectTheirSixFourChapterRule() {
        val idioms = cadenceIdioms()
        val complete = idioms.first { it.id == "schoenberg.cadence.complete-authentic" }
        val sixFourVariant = complete.variants.first { it.title.contains("I64") }

        val projected = project(complete, sixFourVariant)

        assertTrue(
            projected.any { it.ruleId == SchoenbergCadenceChapter.CADENTIAL_SIX_FOUR_RULE_ID },
            "Projected rules: ${projected.map { it.ruleId?.value }}",
        )
    }

    /** The predominant idiom drops the chapter's final chord; its rules must survive the cut. */
    @Test
    fun predominantIdiomProjectsCadenceRulesDespiteDroppingTheFinalChord() {
        val idioms = cadenceIdioms()
        val predominant = idioms.first { it.id == "schoenberg.predominant.diatonic" }
        val sixFourVariant = predominant.variants.first { it.title.contains("I64") }

        val projected = project(predominant, sixFourVariant)

        assertTrue(
            projected.any { it.ruleId?.value?.startsWith("schoenberg.cadence") == true },
            "Projected rules: ${projected.map { it.ruleId?.value }}",
        )
    }

    /** Provenance must round-trip through persistence, not just exist in memory. */
    @Test
    fun everyTruncatedCadenceVariantPersistsItsTeachingSource() {
        val truncated = cadenceIdioms()
            .first { it.id == "schoenberg.predominant.diatonic" }
            .variants
        assertTrue(truncated.isNotEmpty())
        truncated.forEach { variant ->
            val encoded = assertNotNull(
                variant.parameters[TEACHING_SOURCE_PARAMETER],
                "Variant ${variant.id} lost its teaching source",
            )
            val decoded = assertNotNull(SchoenbergTeachingSourceCodec.decode(encoded))
            assertEquals(key, decoded.key)
            assertEquals(
                variant.chordIdentities.size + 1,
                assertNotNull(decoded.progression).slots.size,
                "The dropped final chord must stay in the source progression",
            )
        }
    }

    private fun cadenceIdioms(): List<SchoenbergFreePracticeIdiomDefinition> =
        discoverCadenceFreePracticeContribution(
            SchoenbergFreePracticeDiscoveryRequest(key, listOf(key), maxVariantsPerDefinition = 64),
            SchoenbergCommonToneExercises.descriptorForExercise(
                SchoenbergCommonToneExercises.CADENCE_EXERCISE_ID,
            ),
        ).idioms

    @Test
    fun viewedAsProgressionKeepsSourceRulesAfterCurrentKeyAlteredReinterpretation() {
        val index = SchoenbergFreePracticeCatalog.buildIndex(
            SchoenbergFreePracticeDiscoveryRequest(
                initialKey = key,
                activeKeys = listOf(key),
                maxVariantsPerDefinition = 16,
                includeOffKey = true,
                targetKeys = allFreePracticeTargetKeys(),
            )
        )
        val all = index.discover()
        val german = all.idioms
            .first { it.id == "schoenberg.augmented-sixth.predominant" }
            .variants.first { it.suggestedKey == key && it.title == "Ger+6 – V" }
        val focused = index.discover(
            SchoenbergFreePracticeChordFocus(key, german.chordChoices.first())
        )
        val cadence = focused.idioms.first { it.id == "schoenberg.cadence.authentic" }
        val localVariant = cadence.variants.first {
            it.suggestedKey == key && it.title == "Ger+6 – ♭II" &&
                VIEWED_AS_RULE_PROJECTION_PARAMETER in it.parameters
        }

        val projected = project(cadence, localVariant)

        assertTrue(
            projected.any { it.ruleId?.value?.startsWith("schoenberg.cadence") == true },
            "Projected viewed-as rules: ${projected.map { it.ruleId?.value }}",
        )
    }

    private fun project(
        definition: com.mecon.theory.schoenberg.SchoenbergFreePracticeIdiomDefinition,
        variant: SchoenbergFreePracticeIdiomVariant,
    ) = workspace(definition, variant).let { workspace ->
        val targets = ChordSelectionTargetCatalog.targets(key)
        SchoenbergPracticeTeachingRuleProjector.project(
            PracticeTeachingRuleRequest(
                workspace = workspace,
                scope = PracticeWritingScope(
                    slotIds = workspace.slots.map { it.id },
                    triggerSlotId = workspace.slots.last().id,
                    leftBoundarySlotId = null,
                    trigger = PracticeWritingTrigger.IDIOM_CHANGE,
                ),
                targetsBySlotId = workspace.slots.associate { slot ->
                    slot.id to targets.filter {
                        it.matchesWorkspaceChordChoice(key, requireNotNull(slot.chordChoice))
                    }
                },
                fallbackKey = key,
                searchConfig = SearchConfig(maxResults = 1),
            )
        )
    }

    private fun workspace(
        definition: com.mecon.theory.schoenberg.SchoenbergFreePracticeIdiomDefinition,
        variant: SchoenbergFreePracticeIdiomVariant,
    ): HarmonyWorkspaceState {
        val layoutId = WorkspaceTonalLayoutId("layout")
        var onset = Fraction.ZERO
        val slots = variant.chordChoices.zip(variant.durations).mapIndexed { index, (choice, duration) ->
            WorkspaceHarmonySlot(
                id = WorkspaceSlotId("slot-$index"),
                onset = onset,
                duration = duration,
                chordChoice = choice,
                tonalLayoutId = layoutId,
            ).also { onset += duration }
        }
        return HarmonyWorkspaceState(
            voices = VoicePlan.standardFourPart().voices.map(WorkspaceVoiceSpec::fromTheory),
            slots = slots,
            tonalLayouts = listOf(
                WorkspaceTonalLayout(
                    id = layoutId,
                    fifths = key.fifths,
                    mode = com.mecon.theory.freepractice.WorkspaceKeyMode.MAJOR,
                    start = Fraction.ZERO,
                    isBaseline = true,
                )
            ),
            idiomInstances = listOf(
                WorkspaceIdiomInstance(
                    id = WorkspaceIdiomInstanceId("idiom"),
                    definitionId = definition.id,
                    variantId = variant.id,
                    sourceExerciseId = definition.sourceExerciseId,
                    sourceChapterId = definition.sourceChapterId,
                    tonalLayoutId = layoutId,
                    slotIds = slots.map { it.id },
                    parameters = variant.parameters,
                )
            ),
        )
    }

    private fun com.mecon.theory.freepractice.WorkspaceChordChoice.target(): InterpretedChordTarget =
        ChordSelectionTargetCatalog.targets(key)
            .filterIsInstance<InterpretedChordTarget>()
            .first { it.matchesWorkspaceChordChoice(key, this) }
}
