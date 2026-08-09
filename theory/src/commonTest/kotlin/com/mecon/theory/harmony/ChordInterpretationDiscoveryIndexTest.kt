package com.mecon.theory.harmony

import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.schoenberg.SchoenbergHarmonicTreatments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChordInterpretationDiscoveryIndexTest {
    private val key = ModulationKey(0, KeySignatureMode.MAJOR)

    @Test
    fun selectedCategoryLeadsCrossContributionAudibleDiscovery() {
        val groups = ChordSelectionCatalog.groups(key)
        val duplicate = groups
            .flatMap { group -> group.chords.map { group to it } }
            .groupBy { it.second.audibleKey }
            .values
            .firstOrNull { matches -> matches.map { it.first.category.id }.distinct().size > 1 }
        val matches = assertNotNull(duplicate, "Fixture needs an audible sonority contributed by multiple categories")
        val selected = matches.last().second
        val index = ChordInterpretationDiscoveryIndex.create(groups)

        val discovered = index.discover(
            SoundingInterpretationQuery(selected.audibleKey, selected.origin)
        )

        assertTrue(discovered.isNotEmpty())
        assertTrue(discovered.all { it.audibleKey == selected.audibleKey })
        assertTrue(discovered.first().origins.any { it.categoryId == selected.origin.categoryId })
        assertEquals(discovered.map { it.ref }.distinct(), discovered.map { it.ref })
    }

    @Test
    fun diminishedAudibleChoiceCollapsesCommonExplanationButKeepsEveryRoute() {
        val snapshot = ChordCatalogSnapshot.create(
            key,
            treatmentRegistry = SchoenbergHarmonicTreatments.registry,
        )
        val choice = snapshot.selectionGroups
            .single { it.category.id == "rootless-dominant-ninth" }
            .chords
            .first { it.interpretationRefs.size > 1 }
        val context = ChordKnowledgeContext(key.tonalContext("discovery-test"))

        val detail = snapshot.resolve(
            SoundingInterpretationQuery(choice.audibleKey, choice.origin),
            context,
        )

        assertEquals(1, detail.explanations.size)
        assertEquals(
            choice.interpretationRefs.toSet(),
            detail.explanations.single().interpretationRefs.toSet(),
        )
    }

    @Test
    fun pinningFiltersInsteadOfMergingInterpretationRules() {
        val snapshot = ChordCatalogSnapshot.create(
            key,
            treatmentRegistry = SchoenbergHarmonicTreatments.registry,
        )
        val choice = snapshot.selectionGroups
            .single { it.category.id == "rootless-dominant-ninth" }
            .chords
            .first { it.interpretationRefs.size > 1 }
        val pinned = choice.interpretationRefs.last()

        val discovered = snapshot.discoveryIndex.discover(
            SoundingInterpretationQuery(choice.audibleKey, choice.origin, pinned)
        )

        assertEquals(listOf(pinned), discovered.map { it.ref })
    }
}
