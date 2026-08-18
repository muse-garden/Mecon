package com.mecon.theory.schoenberg

import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.harmony.ChordSelectionCatalog
import com.mecon.theory.harmony.ChordSelectionChoice
import com.mecon.theory.freepractice.WorkspaceChordChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SchoenbergFreePracticeCatalogTest {
    @Test
    fun discoversCadencesDominantPedalAndModulationPivotsFromRegisteredExercises() {
        val contribution = SchoenbergFreePracticeCatalog.discover(
            SchoenbergFreePracticeDiscoveryRequest(
                initialKey = ModulationKey(0, KeySignatureMode.MAJOR),
                activeKeys = listOf(
                    ModulationKey(0, KeySignatureMode.MAJOR),
                    ModulationKey(-4, KeySignatureMode.MAJOR),
                ),
                maxVariantsPerDefinition = 3,
            )
        )

        assertTrue(contribution.idioms.any {
            it.sourceExerciseId == SchoenbergCommonToneExercises.CADENCE_EXERCISE_ID
        })
        val dominantPedal = contribution.idioms.first {
            it.sourceExerciseId == SchoenbergCommonToneExercises.DISTANT_MODULATION_EXERCISE_ID
        }
        assertEquals("属持续音（增强转调目标调性）", dominantPedal.title)
        assertEquals("schoenberg.dominant-pedal.target-confirmation", dominantPedal.id)
        assertEquals(
            setOf(
                ModulationKey(0, KeySignatureMode.MAJOR),
                ModulationKey(-4, KeySignatureMode.MAJOR),
            ),
            dominantPedal.variants.mapNotNull { it.suggestedKey }.toSet(),
        )
        assertTrue(dominantPedal.variants.all {
            it.suggestedKey != null && it.chordIdentities.size == 3 &&
                it.durations == listOf(
                    com.mecon.api.primitive.Fraction.QUARTER,
                    com.mecon.api.primitive.Fraction.QUARTER,
                    com.mecon.api.primitive.Fraction.HALF,
                )
        })
        assertTrue(contribution.idioms.none { it.title == "远关系转调与属持续音" })
        assertTrue(contribution.idioms.flatMap { it.variants }.all {
            it.chordIdentities.size == it.durations.size
        })
        assertTrue(contribution.pivotRecipes.any {
            it.sourceKey == ModulationKey(0, KeySignatureMode.MAJOR) &&
                it.targetKey == ModulationKey(-4, KeySignatureMode.MAJOR) &&
                it.sourceReading.startsWith("I") &&
                it.targetReading.contains("3") &&
                it.targetReading.contains("♯5") &&
                it.targetReading.contains("7")
        })
    }

    @Test
    fun defaultCatalogContainsCompleteCadencesPredominantsAndInternalSixFourVariants() {
        val contribution = discoverMajor(maxVariants = 8)
        fun titles(id: String) = contribution.idioms.first { it.id == id }.variants.map { it.title }

        assertTrue(
            "V – I" in titles("schoenberg.cadence.authentic"),
            titles("schoenberg.cadence.authentic").toString(),
        )
        assertTrue("V – vi" in titles("schoenberg.cadence.deceptive"))
        assertTrue("V – iv" in titles("schoenberg.cadence.deceptive"))
        assertTrue(titles("schoenberg.cadence.complete-authentic").any { it == "ii – V – I" })
        assertTrue(titles("schoenberg.cadence.complete-authentic").any { it == "IV – V – I" })
        assertTrue(titles("schoenberg.cadence.complete-authentic").any { it == "ii – I64 – V – I" })
        assertTrue(titles("schoenberg.predominant.diatonic").any { it == "IV – I64 – V" })
        assertEquals(
            setOf(
                "V/V – V", "V/V – I64 – V",
                "V/V – V7", "V/V – I64 – V7",
                "V7/V – V", "V7/V – I64 – V",
                "V7/V – V7", "V7/V – I64 – V7",
            ),
            titles("schoenberg.secondary-dominant.predominant").toSet(),
        )
        assertEquals(
            setOf(
                "♭II6 – V", "♭II6 – I64 – V",
                "♭II6 – V7", "♭II6 – I64 – V7",
                "♭IImaj65 – V", "♭IImaj65 – I64 – V",
                "♭IImaj65 – V7", "♭IImaj65 – I64 – V7",
            ),
            titles("schoenberg.neapolitan.predominant").toSet(),
        )
        val augmented = titles("schoenberg.augmented-sixth.predominant")
        listOf("It+6", "Ger+6", "Fr+6").forEach { family ->
            assertTrue("$family – V" in augmented)
            assertTrue("$family – I64 – V" in augmented)
            assertTrue("$family – V7" in augmented, augmented.toString())
        }
        assertTrue(
            titles("schoenberg.augmented-sixth.half-diminished-neapolitan")
                .any { it.startsWith("ø+6") && it.contains("♭II") }
        )
        val completeCadences = titles("schoenberg.cadence.complete-authentic")
        assertTrue("ii7 – V7 – I" in completeCadences)
        assertTrue(completeCadences.none { it.endsWith("I7") })
        contribution.idioms.flatMap { it.variants }.filter { it.suggestedKey != null }.forEach {
            if (it.chordChoices.isNotEmpty()) {
                assertEquals(it.chordIdentities.size, it.chordChoices.size)
                assertTrue(it.chordChoices.all { choice -> choice.pinnedInterpretationRef != null })
            }
        }
    }

    @Test
    fun customaryProgressionInversionLocksComeFromStructuralCadenceRules() {
        val contribution = discoverMajor(maxVariants = 16)
        fun variant(definitionId: String, title: String) = contribution.idioms
            .first { it.id == definitionId }
            .variants.first { it.title == title }

        val authentic = variant("schoenberg.cadence.authentic", "V – I")
        assertEquals(setOf(1), authentic.fixedInversionStepIndices)
        assertEquals(setOf(0), authentic.avoidSecondInversionStepIndices)
        assertTrue(authentic.chordChoices.all { it.bassPitchClass != null })

        val complete = variant("schoenberg.cadence.complete-authentic", "ii – I64 – V – I")
        assertEquals(setOf(1, 3), complete.fixedInversionStepIndices)
        assertEquals(setOf(2), complete.avoidSecondInversionStepIndices)

        val deceptive = variant("schoenberg.cadence.deceptive", "V – vi")
        assertTrue(deceptive.fixedInversionStepIndices.isEmpty())

        val secondary = variant("schoenberg.secondary-dominant.predominant", "V/V – I64 – V")
        assertEquals(setOf(1), secondary.fixedInversionStepIndices)

        val neapolitan = variant("schoenberg.neapolitan.predominant", "♭II6 – V")
        assertTrue(neapolitan.fixedInversionStepIndices.isEmpty())
        assertEquals(setOf(0), neapolitan.customaryBassStepIndices)

        val augmented = variant("schoenberg.augmented-sixth.predominant", "It+6 – V")
        assertTrue(augmented.fixedInversionStepIndices.isEmpty())
        assertEquals(setOf(0), augmented.customaryBassStepIndices)
    }

    @Test
    fun everyPinnedIdiomInterpretationIsAvailableInItsTonalLayout() {
        listOf(
            ModulationKey(0, KeySignatureMode.MAJOR),
            ModulationKey(0, KeySignatureMode.MINOR),
        ).forEach { key ->
            val contribution = SchoenbergFreePracticeCatalog.discover(
                SchoenbergFreePracticeDiscoveryRequest(key, listOf(key), maxVariantsPerDefinition = 16)
            )
            val catalogChoicesByKey = mutableMapOf<ModulationKey, List<ChordSelectionChoice>>()
            contribution.idioms.flatMap { definition ->
                definition.variants.map { variant -> definition.id to variant }
            }.forEach { (definitionId, variant) ->
                val variantKey = variant.suggestedKey ?: key
                val choicesByRef = catalogChoicesByKey.getOrPut(variantKey) {
                    ChordSelectionCatalog.choices(variantKey)
                }
                    .flatMap { catalogChoice ->
                        catalogChoice.interpretationRefs.map { it to catalogChoice }
                    }
                    .toMap()
                variant.chordChoices.forEach { choice ->
                    val ref = choice.pinnedInterpretationRef
                    assertTrue(
                        ref in choicesByRef,
                        "$definitionId/${variant.id} contains an interpretation unavailable in $variantKey: " +
                            ref,
                    )
                    assertEquals(choice.pitchClasses.toSet(), choicesByRef[ref]?.pitchClasses)
                }
            }
        }
    }

    @Test
    fun selectedSecondaryChordReturnsItsAuthenticAndDeceptiveAnalogiesWithMatchingAnchor() {
        val all = discoverMajor(maxVariants = 16)
        val directSecondary = discoverSecondaryHarmonyFreePracticeContribution(
            SchoenbergFreePracticeDiscoveryRequest(MAJOR, listOf(MAJOR), 16),
            SchoenbergCommonToneExercises.descriptorForExercise(
                SchoenbergCommonToneExercises.SECONDARY_HARMONY_EXERCISE_ID,
            ),
        )
        assertTrue(directSecondary.idioms.isNotEmpty(), directSecondary.toString())
        assertTrue(
            directSecondary.idioms.any { it.id == "schoenberg.secondary-dominant.predominant" },
            directSecondary.toString(),
        )
        val sourceVariant = all.idioms
            .firstOrNull { it.id == "schoenberg.secondary-dominant.predominant" }
            .also { assertNotNull(it, all.idioms.map { definition -> definition.id }.toString()) }!!
            .variants
            .first { it.title == "V/V – V" }
        val sourceChoice = sourceVariant.chordChoices.first()

        val focused = SchoenbergFreePracticeCatalog.discover(
            SchoenbergFreePracticeDiscoveryRequest(
                initialKey = MAJOR,
                activeKeys = listOf(MAJOR),
                maxVariantsPerDefinition = 16,
                focus = SchoenbergFreePracticeChordFocus(MAJOR, sourceChoice),
            )
        )
        val related = assertNotNull(
            focused.idioms.firstOrNull { it.id == "schoenberg.secondary-harmony.analogous-resolution" }
        )

        assertTrue(related.variants.any { it.title == "V/V – V" })
        assertTrue(related.variants.any { it.title == "V/V – iii" })
        assertTrue(related.variants.any { it.title == "V/V – I" })
        assertTrue(related.variants.all { variant ->
            variant.chordChoices[variant.anchorStepIndex].pinnedInterpretationRef ==
                sourceChoice.pinnedInterpretationRef
        })
    }

    @Test
    fun selectedResolutionTargetDoesNotReanchorSourceScopedDiminishedProgressions() {
        val index = SchoenbergFreePracticeCatalog.buildIndex(
            SchoenbergFreePracticeDiscoveryRequest(
                initialKey = MAJOR,
                activeKeys = listOf(MAJOR),
                maxVariantsPerDefinition = 16,
                catalogKey = MAJOR,
            )
        )
        val defaults = index.discover()
        val tonicChoice = defaults.idioms
            .first { it.id == "schoenberg.cadence.authentic" }
            .variants.first { it.title == "V – I" }
            .chordChoices.last()

        val focused = index.discover(SchoenbergFreePracticeChordFocus(MAJOR, tonicChoice))

        assertTrue(
            focused.idioms.none {
                it.id == "schoenberg.diminished-seventh.analogous-dominant"
            },
            focused.idioms.map { it.id }.toString(),
        )
    }

    @Test
    fun minorDefaultCatalogKeepsTheSameFunctionalFamilies() {
        val minor = ModulationKey(0, KeySignatureMode.MINOR)
        val contribution = SchoenbergFreePracticeCatalog.discover(
            SchoenbergFreePracticeDiscoveryRequest(minor, listOf(minor), 16)
        )
        val ids = contribution.idioms.map { it.id }.toSet()

        assertTrue("schoenberg.cadence.authentic" in ids)
        assertTrue("schoenberg.cadence.complete-authentic" in ids)
        assertTrue("schoenberg.predominant.diatonic" in ids)
        assertTrue("schoenberg.secondary-dominant.predominant" in ids)
        assertTrue("schoenberg.neapolitan.predominant" in ids)
        assertTrue("schoenberg.augmented-sixth.predominant" in ids)
        assertTrue(
            contribution.idioms.first { it.id == "schoenberg.neapolitan.predominant" }
                .variants.any { it.title == "♭II6 – V" }
        )
    }

    @Test
    fun lockedDiminishedInterpretationsKeepTheirOwnResolutionRoutes() {
        val descriptor = SchoenbergCommonToneExercises.descriptorForExercise(
            SchoenbergCommonToneExercises.DIMINISHED_SEVENTH_EXERCISE_ID,
        )
        val variants = discoverDiminishedSeventhFreePracticeContribution(
            SchoenbergFreePracticeDiscoveryRequest(MAJOR, listOf(MAJOR), 16),
            descriptor,
        ).idioms.single().variants
        val sameSoundingUsages = variants.groupBy { it.chordChoices.first().pitchClasses }
            .values
            .first { group ->
                group.map { it.chordChoices.first().pinnedInterpretationRef }.distinct().size >= 2
            }
            .distinctBy { it.chordChoices.first().pinnedInterpretationRef }
            .take(2)

        val resolutions = sameSoundingUsages.map { sourceVariant ->
            val sourceChoice = sourceVariant.chordChoices.first()
            val focused = SchoenbergFreePracticeCatalog.discover(
                SchoenbergFreePracticeDiscoveryRequest(
                    initialKey = MAJOR,
                    activeKeys = listOf(MAJOR),
                    maxVariantsPerDefinition = 16,
                    focus = SchoenbergFreePracticeChordFocus(MAJOR, sourceChoice),
                )
            )
            focused.idioms
                .single { it.id == "schoenberg.diminished-seventh.analogous-dominant" }
                .variants
                .map { it.chordChoices.last().pinnedInterpretationRef }
                .toSet()
        }

        assertNotEquals(resolutions[0], resolutions[1])
    }

    @Test
    fun offKeyCatalogExposesEnharmonicGermanSixthReadingAndTargetDistance() {
        val dFlatMajor = ModulationKey(-5, KeySignatureMode.MAJOR)
        val cSharpMajor = ModulationKey(7, KeySignatureMode.MAJOR)
        val index = SchoenbergFreePracticeCatalog.buildIndex(
            SchoenbergFreePracticeDiscoveryRequest(
                initialKey = MAJOR,
                activeKeys = listOf(MAJOR),
                maxVariantsPerDefinition = 16,
                includeOffKey = true,
                targetKeys = allFreePracticeTargetKeys(),
            )
        )
        val all = index.discover()
        assertTrue(all.idioms.any { it.id == "schoenberg.diminished-seventh.analogous-dominant" })
        assertTrue(all.idioms.any { it.id == "schoenberg.secondary-harmony.analogous-resolution" })
        val repeatedSecondary = all.idioms.flatMap { definition ->
            definition.variants.filter { variant ->
                variant.suggestedKey != MAJOR && variant.chordChoices.any { choice ->
                    choice.pinnedInterpretationRef?.interpretationId?.value?.let { id ->
                        id.startsWith("secondary.secondary_dominant.") ||
                            id.startsWith("secondary.secondary_leading.")
                    } == true
                }
            }.map { variant -> definition.id to variant.suggestedKey }
        }
        assertTrue(
            repeatedSecondary.isEmpty(),
            "off-key projection repeated secondary harmony: $repeatedSecondary",
        )
        assertTrue(
            all.idioms.flatMap { it.variants }.any {
                it.suggestedKey == dFlatMajor && it.targetKeyDistance == -5
            }
        )
        assertTrue(all.idioms.none { it.id == "schoenberg.augmented-sixth.german-neapolitan" })

        val german = all.idioms
            .first { it.id == "schoenberg.augmented-sixth.predominant" }
            .variants.first { it.suggestedKey == MAJOR && it.title == "Ger+6 – V" }
        val focused = index.discover(
            SchoenbergFreePracticeChordFocus(MAJOR, german.chordChoices.first())
        )
        val viewedAsDominantSeventh = focused.idioms
            .first { it.id == "schoenberg.cadence.authentic" }
        val germanCadence = assertNotNull(
            viewedAsDominantSeventh.variants
                .firstOrNull { it.suggestedKey == dFlatMajor && it.title == "Ger+6 – ♭II" },
            "German sixth should automatically reuse the enharmonic primary-dominant cadence",
        )
        assertEquals("作为属七的终止式", viewedAsDominantSeventh.title)
        assertTrue(germanCadence.anchorStepIndex !in germanCadence.fixedInversionStepIndices)
        assertTrue(germanCadence.anchorStepIndex + 1 !in germanCadence.fixedInversionStepIndices)
        assertTrue(germanCadence.anchorStepIndex in germanCadence.customaryBassStepIndices)
        assertTrue(germanCadence.avoidSecondInversionStepIndices.isEmpty())

        val french = all.idioms
            .first { it.id == "schoenberg.augmented-sixth.predominant" }
            .variants.first { it.suggestedKey == MAJOR && it.title == "Fr+6 – V" }
        val frenchFocused = index.discover(
            SchoenbergFreePracticeChordFocus(MAJOR, french.chordChoices.first())
        )
        assertTrue(
            frenchFocused.idioms
                .first { it.id == "schoenberg.augmented-sixth.resolution" }
                .variants.any {
                    it.suggestedKey == cSharpMajor && it.title.startsWith("Fr+6/I – I")
                },
            "French sixth should expose the second augmented-sixth pair a tritone away",
        )

        val diminished = all.idioms
            .first { it.id == "schoenberg.diminished-seventh.analogous-dominant" }
            .variants.first { it.suggestedKey == MAJOR }
        val diminishedFocused = index.discover(
            SchoenbergFreePracticeChordFocus(MAJOR, diminished.chordChoices.first())
        )
        assertTrue(
            diminishedFocused.idioms.flatMap { it.variants }.any { variant ->
                variant.suggestedKey == MAJOR &&
                    VIEWED_AS_SOURCE_FIFTHS_PARAMETER in variant.parameters &&
                    variant.chordChoices.all { choice ->
                        ChordSelectionCatalog.choices(MAJOR).any { catalogChoice ->
                            choice.pinnedInterpretationRef in catalogChoice.interpretationRefs
                        }
                    }
            },
            "non-German viewed-as routes should also use available current-key altered readings",
        )
    }

    @Test
    fun offKeyGermanSixthKeepsOneTargetKeyAcrossChordSizes() {
        val index = SchoenbergFreePracticeCatalog.buildIndex(
            SchoenbergFreePracticeDiscoveryRequest(
                initialKey = MAJOR,
                activeKeys = listOf(MAJOR),
                maxVariantsPerDefinition = 64,
                includeOffKey = true,
                targetKeys = allFreePracticeTargetKeys(),
            )
        )
        val germanVariants = index.discover().idioms
            .flatMap { it.variants }
            .filter { variant ->
                variant.suggestedKey != MAJOR && variant.title.startsWith("Ger+6 – V")
            }
        val adjustableFamily = germanVariants
            .groupBy { Triple(it.structureId, it.interpretationContextId, it.suggestedKey) }
            .values
            .firstOrNull { family -> family.map { it.chordToneCounts.last() }.toSet().containsAll(setOf(3, 4)) }
            ?: error(
                germanVariants.joinToString("\n") {
                    "${it.title}; counts=${it.chordToneCounts}; context=${it.interpretationContextId}; " +
                        "target=${it.suggestedKey}"
                }
            )

        assertEquals(1, adjustableFamily.map { it.suggestedKey }.distinct().size)
        assertTrue(adjustableFamily.none { it.suggestedKey == MAJOR })
    }

    @Test
    fun ordinaryHalfDiminishedSelectionFindsItsAugmentedSixthProgression() {
        val index = SchoenbergFreePracticeCatalog.buildIndex(
            SchoenbergFreePracticeDiscoveryRequest(
                initialKey = MAJOR,
                activeKeys = listOf(MAJOR),
                maxVariantsPerDefinition = 16,
                catalogKey = MAJOR,
            )
        )
        val augmentedChoice = index.discover().idioms
            .first { it.id == "schoenberg.augmented-sixth.half-diminished-neapolitan" }
            .variants.first().chordChoices.first()
        val ordinary = ChordSelectionCatalog.choices(MAJOR)
            .first { choice ->
                choice.pitchClasses.toSet() == augmentedChoice.pitchClasses.toSet() &&
                    choice.interpretationRefs.any {
                        !it.interpretationId.value.startsWith("augmented-sixth.")
                    }
            }
        val ordinaryRef = ordinary.interpretationRefs.first {
            !it.interpretationId.value.startsWith("augmented-sixth.")
        }
        val focused = index.discover(
            SchoenbergFreePracticeChordFocus(
                MAJOR,
                WorkspaceChordChoice.of(ordinary.pitchClasses, pinnedInterpretationRef = ordinaryRef),
            )
        )

        assertTrue(
            focused.idioms.any {
                it.id == "schoenberg.augmented-sixth.half-diminished-neapolitan"
            }
        )
    }

    private fun discoverMajor(maxVariants: Int): SchoenbergFreePracticeContribution =
        SchoenbergFreePracticeCatalog.discover(
            SchoenbergFreePracticeDiscoveryRequest(
                initialKey = MAJOR,
                activeKeys = listOf(MAJOR),
                maxVariantsPerDefinition = maxVariants,
            )
        )

    private companion object {
        val MAJOR = ModulationKey(0, KeySignatureMode.MAJOR)
    }
}
