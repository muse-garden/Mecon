package com.mecon.theory.harmony

import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ChordSelectionCatalogTest {
    @Test
    fun discoversAndClassifiesEveryEnabledHarmonyFamily() {
        val groups = ChordSelectionCatalog.groups(ModulationKey(0, KeySignatureMode.MAJOR))
        val ids = groups.map { it.category.id }.toSet()

        assertTrue("diatonic-triads" in ids)
        assertTrue("diatonic-sevenths" in ids)
        assertTrue("secondary-dominants" in ids)
        assertTrue("secondary-leading" in ids)
        assertTrue("augmented-triads" in ids)
        assertTrue("rootless-dominant-ninth" in ids)
        assertTrue("neapolitan" in ids)
        assertTrue("minor-subdominant-related" in ids)
        assertTrue(groups.flatMap { it.chords }.any { it.functionalSymbol == "V7/V" })
        assertTrue(groups.flatMap { it.chords }.any { it.functionalSymbol.contains("°7/") })
    }

    @Test
    fun derivesAbsoluteAndRelativeToneNamesFromSelectedKey() {
        val groups = ChordSelectionCatalog.groups(ModulationKey(1, KeySignatureMode.MAJOR))
        val tonic = groups.flatMap { it.chords }.first { it.functionalSymbol == "I" }

        assertEquals(listOf("G", "B", "D"), tonic.absoluteTones)
        assertEquals(listOf("1", "3", "5"), tonic.relativeTones)
        assertEquals(setOf(7, 11, 2), tonic.pitchClasses)
    }

    @Test
    fun secondaryLeadingSeventhsAreHalfDiminishedForEveryDisplayedTarget() {
        listOf(
            ModulationKey(0, KeySignatureMode.MAJOR),
            ModulationKey(0, KeySignatureMode.MINOR),
        ).forEach { key ->
            val sevenths = ChordSelectionCatalog.groups(key)
                .single { it.category.id == "secondary-leading" }
                .chords
                .filter { "7/" in it.functionalSymbol }

            assertEquals(5, sevenths.size)
            assertTrue(sevenths.all { "ø7/" in it.functionalSymbol })
            assertTrue(sevenths.any { it.functionalSymbol.endsWith("/III") })
            assertTrue(sevenths.any { it.functionalSymbol.endsWith("/IV") })
        }
    }

    @Test
    fun minorRelativeTonesUseTheKeySignatureMajorTonic() {
        val groups = ChordSelectionCatalog.groups(ModulationKey(0, KeySignatureMode.MINOR))
        val naturalTriads = groups.first { it.category.id == "diatonic-triads" }.chords
        val tonic = naturalTriads.first { it.functionalSymbol == "i" }

        assertEquals(13, naturalTriads.size)
        assertEquals(listOf("A", "C", "E"), tonic.absoluteTones)
        assertEquals(listOf("6", "1", "3"), tonic.relativeTones)
        assertEquals(
            listOf("7", "2", "♯4"),
            naturalTriads.first { it.functionalSymbol == "ii" }.relativeTones,
        )
        assertEquals(
            listOf("1", "3", "♯5"),
            naturalTriads.first { it.functionalSymbol == "III+" }.relativeTones,
        )
        assertEquals(
            listOf("2", "♯4", "6"),
            naturalTriads.first { it.functionalSymbol == "IV" }.relativeTones,
        )
        assertEquals(
            listOf("3", "♯5", "7"),
            naturalTriads.first { it.functionalSymbol == "V" }.relativeTones,
        )
        assertTrue(naturalTriads.any { it.functionalSymbol == "vi°" })
        assertTrue(naturalTriads.any { it.functionalSymbol == "vii°" })
    }

    @Test
    fun minorDiatonicSeventhsIncludeHarmonicAndMelodicForms() {
        val sevenths = ChordSelectionCatalog.groups(ModulationKey(0, KeySignatureMode.MINOR))
            .single { it.category.id == "diatonic-sevenths" }
            .chords

        assertEquals(15, sevenths.size)
        assertTrue(sevenths.any { "♯4" in it.relativeTones }, "missing raised sixth in minor")
        assertTrue(sevenths.any { "♯5" in it.relativeTones }, "missing raised seventh in minor")
        assertTrue(sevenths.any { it.functionalSymbol == "V7" })
        assertTrue(sevenths.any { it.functionalSymbol == "vii°7" })
    }

    @Test
    fun discoversContributionsFromAnnotatedChapterObjects() {
        val providers = ChordCatalogChapterDiscovery.discover()

        assertTrue(providers.size >= 4)
        assertEquals(
            setOf(
                "diatonic-triads",
                "diatonic-sevenths",
                "secondary-dominants",
                "secondary-leading",
                "augmented-triads",
                "augmented-sixths",
                "rootless-dominant-ninth",
                "modal-colors",
                "minor-subdominant-related",
            ),
            providers
                .flatMap(ChordCatalogChapterProvider::chordCatalogContributions)
                .map { it.category.id }
                .toSet(),
        )
        assertEquals(
            setOf("neapolitan", "dominant-augmented-sixths"),
            providers
                .flatMap(ChordCatalogChapterProvider::chordCatalogContributions)
                .flatMap(ChordCatalogContribution::namedSubsets)
                .map { it.category.id }
                .toSet(),
        )
    }

    @Test
    fun namedSubsetExtractsAChapterOwnedSpecialCaseWithoutDuplicatingIt() {
        val groups = ChordSelectionCatalog.groups(ModulationKey(0, KeySignatureMode.MAJOR))
        val neapolitanGroup = groups.single { it.category.id == "neapolitan" }
        val relatedGroup = groups.single { it.category.id == "minor-subdominant-related" }
        val neapolitans = neapolitanGroup.chords
        val neapolitanRefs = neapolitans.flatMap { it.interpretationRefs }

        assertEquals(
            setOf(
                "minor-subdominant.2.-1.major.triad",
                "minor-subdominant.2.-1.major7.seventh",
            ),
            neapolitanRefs.map { it.interpretationId.value }.toSet(),
        )
        assertTrue(neapolitans.all { it.origin.categoryId == ChordCatalogCategoryId("neapolitan") })
        assertFalse(relatedGroup.chords.any { related ->
            neapolitanRefs.any { it in related.interpretationRefs }
        })
        assertTrue(groups.indexOf(neapolitanGroup) < groups.indexOf(relatedGroup))
    }

    @Test
    fun diminishedSeventhsProjectToThreeSoundingChoicesWithoutLosingRoutes() {
        listOf(
            ModulationKey(0, KeySignatureMode.MAJOR),
            ModulationKey(0, KeySignatureMode.MINOR),
        ).forEach { key ->
            val diminished = ChordSelectionCatalog.groups(key)
                .single { it.category.id == "rootless-dominant-ninth" }
                .chords

            assertEquals(3, diminished.size)
            assertEquals(3, diminished.mapNotNull { it.soundingClassId }.distinct().size)
            assertEquals(6, diminished.sumOf { it.interpretationRefs.size })
            assertTrue(diminished.any { it.interpretationRefs.size > 1 })
            assertTrue(diminished.all { it.routeIds.size == it.interpretationRefs.size })
        }
    }

    @Test
    fun ordinaryContributionsRemainInterpretationProjected() {
        val tonic = ChordSelectionCatalog.groups(ModulationKey(0, KeySignatureMode.MAJOR))
            .single { it.category.id == "diatonic-triads" }
            .chords
            .first { it.functionalSymbol == "I" }

        assertEquals(1, tonic.interpretationRefs.size)
        assertEquals(1, tonic.interpretationRefs.size)
        assertEquals(null, tonic.soundingClassId)
        assertEquals(tonic.identity, tonic.functionalSymbol)
    }

    @Test
    fun everyCategorySortsChoicesByCanonicalRootPitchClass() {
        ChordSelectionCatalog.groups(ModulationKey(0, KeySignatureMode.MAJOR))
            .filterNot {
                it.category.id in setOf("dominant-augmented-sixths", "augmented-sixths")
            }
            .forEach { group ->
            assertEquals(
                group.chords.map(ChordSelectionChoice::rootPitchClass).sorted(),
                group.chords.map(ChordSelectionChoice::rootPitchClass),
                "${group.category.id} must be ordered by root pitch",
            )
            }
    }

    @Test
    fun augmentedTriadsUseFamilyScopedSoundingClassesWithoutDuplicateCards() {
        val augmented = ChordSelectionCatalog.groups(ModulationKey(0, KeySignatureMode.MAJOR))
            .single { it.category.id == "augmented-triads" }
            .chords

        assertEquals(4, augmented.size)
        assertTrue(augmented.all { it.soundingClassId != null })
        assertEquals(augmented.size, augmented.map { it.soundingClassId }.distinct().size)
        assertEquals(augmented.size, augmented.map { it.audibleKey }.distinct().size)
        assertTrue(augmented.any { it.interpretationRefs.size > 1 })
    }

    @Test
    fun modalColorsAreDerivedChromaticSonoritiesRatherThanDiatonicOrMisanchoredChords() {
        val majorColors = ChordSelectionCatalog.groups(ModulationKey(0, KeySignatureMode.MAJOR))
            .single { it.category.id == "modal-colors" }
            .chords

        assertEquals(1, majorColors.size)
        assertEquals(listOf("5", "♭7", "2"), majorColors.single().relativeTones)
        assertTrue(
            ChordSelectionCatalog.groups(ModulationKey(0, KeySignatureMode.MINOR))
                .none { it.category.id == "modal-colors" }
        )
    }

    @Test
    fun sharpTwoSoundingClassRetainsThirdAndDominantRoutes() {
        val choice = ChordSelectionCatalog.groups(ModulationKey(0, KeySignatureMode.MAJOR))
            .single { it.category.id == "rootless-dominant-ninth" }
            .chords
            .first { it.relativeTones.toSet() == setOf("♯2", "♯4", "6", "1") }
        val ids = choice.interpretationRefs.map { it.interpretationId.value }

        assertTrue(ids.any { ".as-dominant.3." in it })
        assertTrue(ids.any { ".as-dominant.5." in it })
        assertEquals(choice.interpretationRefs.size, choice.routeIds.size)
    }
}
