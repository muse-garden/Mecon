package com.mecon.desktop.ui.exploration

import com.mecon.api.primitive.Fraction
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.freepractice.WorkspaceChordChoice
import com.mecon.theory.harmony.ChordSelectionCatalog
import com.mecon.theory.schoenberg.SchoenbergFreePracticeChordFocus
import com.mecon.theory.schoenberg.SchoenbergFreePracticeCatalog
import com.mecon.theory.schoenberg.SchoenbergFreePracticeDiscoveryRequest
import com.mecon.theory.schoenberg.SchoenbergFreePracticeIdiomDefinition
import com.mecon.theory.schoenberg.SchoenbergFreePracticeIdiomVariant
import com.mecon.theory.schoenberg.allFreePracticeTargetKeys
import com.mecon.theory.schoenberg.forGermanDominantSeventhFocus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.time.measureTimedValue

class FreePracticeTeachingMaterialLoadTest {
    @Test
    fun customaryProgressionCatalogFollowsTheSelectedTonalReading() {
        val initial = ModulationKey(0, KeySignatureMode.MAJOR)
        val selected = ModulationKey(3, KeySignatureMode.MAJOR)

        val request = defaultTeachingCatalogRequest(initial, listOf(initial, selected), selected)

        assertEquals(initial, request.initialKey)
        assertEquals(selected, request.catalogKey)
    }

    @Test
    fun offKeyPresentationIsProjectedOnceAndHidesDistanceWhenDisabled() {
        val key = ModulationKey(0, KeySignatureMode.MAJOR)
        val offKey = measureTimedValue {
            SchoenbergFreePracticeCatalog.buildIndex(
                SchoenbergFreePracticeDiscoveryRequest(
                    initialKey = key,
                    activeKeys = listOf(key),
                    includeOffKey = true,
                    targetKeys = allFreePracticeTargetKeys(),
                    maxVariantsPerDefinition = 8,
                )
            )
        }
        val contribution = offKey.value.discover()
        val distantVariant = contribution.idioms
            .flatMap { it.variants }
            .first { it.suggestedKey != key }
        val hiddenDistance = idiomVariantChipLabel(distantVariant, showOffKeyIdioms = false)
        val shownDistance = idiomVariantChipLabel(distantVariant, showOffKeyIdioms = true)

        assertFalse("目标" in hiddenDistance)
        assertFalse("偏离" in hiddenDistance)
        assertTrue("目标" in shownDistance)
        // Selecting a vagrant chord such as Ger+6 builds this index on the spot, so it is on the
        // interaction path. Per-chord catalog targets, chapter enumeration and the catalogs
        // themselves are all memoized; anything near the old multi-second cost is a regression.
        assertTrue(offKey.duration.inWholeMilliseconds < 4_000, offKey.duration.toString())

        val germanSelection = ChordSelectionCatalog.choices(key)
            .first { it.functionalSymbol == "Ger+6" }
        val unpinnedGerman = chordPickerSelectionAction(germanSelection).choice
        assertNull(unpinnedGerman.pinnedInterpretationRef)
        val germanFocus = SchoenbergFreePracticeChordFocus(key, unpinnedGerman)
        val defaultRequest = SchoenbergFreePracticeDiscoveryRequest(
            initialKey = key,
            activeKeys = listOf(key),
            catalogKey = key,
            onlyAvailableByDefault = true,
        )
        assertTrue(
            focusedTeachingCatalogRequest(defaultRequest, germanFocus, showOffKeyIdioms = false)
                .includeOffKey,
            "German-sixth dominant reinterpretation must not depend on the off-key toggle: " +
                "origin=${unpinnedGerman.origin}, selection=$germanSelection",
        )
        val germanWithoutToggle = offKey.value.discover(germanFocus)
            .forGermanDominantSeventhFocus(germanFocus)
        val localGermanNeapolitan = germanWithoutToggle.idioms
            .first { it.id == "schoenberg.cadence.authentic" }
            .variants.first {
                it.suggestedKey == key && it.title == "Ger+6 – ♭II"
            }
        assertTrue(
            localGermanNeapolitan.chordChoices.all { choice ->
                ChordSelectionCatalog.choices(key).any { catalogChoice ->
                    choice.pinnedInterpretationRef in catalogChoice.interpretationRefs
                }
            },
            "the locally viewed progression must be insertable on the current-key tonal layout",
        )
        val neapolitanRef = ChordSelectionCatalog.choices(key).asSequence()
            .flatMap { choice ->
                choice.interpretationRefs.zip(choice.interpretationSymbols).asSequence()
            }
            .first { (_, symbol) -> symbol == "♭II" }
            .first
        assertEquals(
            neapolitanRef,
            localGermanNeapolitan.chordChoices.last().pinnedInterpretationRef,
        )
        assertTrue(
            germanWithoutToggle.idioms.deduplicateConcreteVariants()
                .any { it.id == "schoenberg.cadence.authentic" },
            "display deduplication must retain the automatic dominant-seventh reading",
        )
        assertTrue(
            germanWithoutToggle.idioms.flatMap { it.variants }.all { variant ->
                variant.suggestedKey == key || variant.chordIdentities[variant.anchorStepIndex]
                    .startsWith("Ger+6")
            },
        )

        val duplicate = SchoenbergFreePracticeIdiomVariant(
            id = "duplicate",
            title = "duplicate",
            chordIdentities = listOf("I"),
            durations = listOf(Fraction.QUARTER),
            suggestedKey = key,
            chordChoices = listOf(WorkspaceChordChoice.of(setOf(0, 4, 7))),
        )
        fun definition(
            id: String,
            targetKey: ModulationKey,
            distance: Int,
        ) = SchoenbergFreePracticeIdiomDefinition(
            id = id,
            title = id,
            sourceExerciseId = id,
            sourceChapterId = id,
            variants = listOf(duplicate.copy(
                id = "$id.variant",
                suggestedKey = targetKey,
                targetKeyDistance = distance,
            )),
        )
        val closest = listOf(
            definition("far", ModulationKey(5, KeySignatureMode.MAJOR), 5),
            definition("near", ModulationKey(-1, KeySignatureMode.MAJOR), -1),
        ).deduplicateConcreteVariants()
        assertEquals(listOf("near"), closest.map { it.id })
    }

    @Test
    fun staleFocusedResultIsHiddenUntilTheCurrentChordRequestCompletes() {
        val key = ModulationKey(0, KeySignatureMode.MAJOR)
        fun request(pitchClasses: Set<Int>) = SchoenbergFreePracticeDiscoveryRequest(
            initialKey = key,
            activeKeys = listOf(key),
            focus = SchoenbergFreePracticeChordFocus(
                key,
                WorkspaceChordChoice.of(pitchClasses),
            ),
        )
        val diminishedRequest = request(setOf(2, 5, 8, 11))
        val tonicRequest = request(setOf(0, 4, 7))
        val indexRequest = diminishedRequest.copy(
            focus = null,
            catalogKey = key,
        )
        val loaded = LoadedTeachingCatalogIndex(
            diminishedRequest,
            SchoenbergFreePracticeCatalog.buildIndex(indexRequest),
        )

        assertNotNull(loaded.forRequest(diminishedRequest))
        assertNull(loaded.forRequest(tonicRequest))
        assertNull(loaded.forRequest(null))
    }

    @Test
    fun differentCatalogChordsProduceDifferentFocusedMaterial() {
        val key = ModulationKey(0, KeySignatureMode.MAJOR)
        val defaultRequest = SchoenbergFreePracticeDiscoveryRequest(
            initialKey = key,
            activeKeys = listOf(key),
            maxVariantsPerDefinition = 16,
        )
        val builtIndex = measureTimedValue {
            SchoenbergFreePracticeCatalog.buildIndex(defaultRequest)
        }
        val defaults = builtIndex.value.discover()
        val immediateIndex = measureTimedValue {
            SchoenbergFreePracticeCatalog.buildIndex(
                defaultRequest.copy(onlyAvailableByDefault = true)
            )
        }
        val immediateDefaults = immediateIndex.value.discover()
        val focusChoices = defaults.idioms
            .flatMap { it.variants }
            .flatMap { it.chordChoices }
            .distinctBy { it.pinnedInterpretationRef ?: it.pitchClasses }
            .take(12)

        val focusedResults = focusChoices.map { choice ->
            val focus = SchoenbergFreePracticeChordFocus(key, choice)
            val focused = measureTimedValue {
                builtIndex.value.discover(focus)
            }
            val ids = focused.value.idioms.map { it.id }.toSet()
            println(
                "focus=${choice.pinnedInterpretationRef ?: choice.pitchClasses} " +
                    "elapsed=${focused.duration.inWholeMilliseconds}ms ids=$ids"
            )
            ids to focused.duration
        }
        println(
            "index elapsed=${builtIndex.duration.inWholeMilliseconds}ms " +
                "immediate=${immediateIndex.duration.inWholeMilliseconds}ms " +
                "choices=${focusChoices.size}"
        )

        val tonicChoice = defaults.idioms
            .first { it.id == "schoenberg.cadence.authentic" }
            .variants.first { it.title == "V – I" }
            .chordChoices.last()
        val tonicFocusedIds = builtIndex.value
            .discover(SchoenbergFreePracticeChordFocus(key, tonicChoice))
            .idioms
            .map { it.id }

        assertTrue(focusChoices.size >= 4)
        assertEquals(
            defaults.idioms.map { it.id }.toSet(),
            immediateDefaults.idioms.map { it.id }.toSet(),
        )
        assertTrue(
            immediateIndex.duration.inWholeMilliseconds < 2_000,
            "Immediate defaults took ${immediateIndex.duration}",
        )
        assertTrue(focusedResults.map { it.first }.distinct().size > 1, focusedResults.toString())
        assertTrue(
            "schoenberg.diminished-seventh.analogous-dominant" !in tonicFocusedIds,
            tonicFocusedIds.toString(),
        )
        assertTrue(
            focusedResults.all { (_, duration) -> duration.inWholeMilliseconds < 250 },
            focusedResults.toString(),
        )
    }
}
