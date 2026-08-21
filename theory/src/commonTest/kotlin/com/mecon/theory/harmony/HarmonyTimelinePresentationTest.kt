package com.mecon.theory.harmony

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.PitchClass
import com.mecon.theory.Chord
import com.mecon.theory.ChordQuality
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HarmonyTimelinePresentationTest {
    private val cMajor = ModulationKey(0, KeySignatureMode.MAJOR)
    private val gMajor = ModulationKey(1, KeySignatureMode.MAJOR)

    @Test
    fun ambiguousRangeUsesAllKeysThenContinuesWithResolvedKey() {
        val range = HarmonyTonalRange(
            id = "pivot",
            start = Fraction.QUARTER,
            end = Fraction.HALF,
            keys = listOf(cMajor, gMajor),
            resolvedKey = gMajor,
        )

        assertEquals(
            listOf(cMajor, gMajor),
            HarmonyTonalTimeline.keysAt(Fraction(3, 8), listOf(range), cMajor),
        )
        assertEquals(
            listOf(gMajor),
            HarmonyTonalTimeline.keysAt(Fraction(5, 8), listOf(range), cMajor),
        )
    }

    @Test
    fun explicitContextOverridesALaterBaselineRange() {
        val ranges = listOf(
            HarmonyTonalRange(
                id = "explicit",
                start = Fraction.ZERO,
                end = Fraction.ONE,
                keys = listOf(gMajor),
                priority = 10,
            ),
            HarmonyTonalRange(
                id = "signature",
                start = Fraction.HALF,
                end = null,
                keys = listOf(cMajor),
            ),
        )

        assertEquals(
            listOf(gMajor),
            HarmonyTonalTimeline.keysAt(Fraction(3, 4), ranges, cMajor),
        )
    }

    @Test
    fun scoreTimelineCanRetainTheDefaultKeyBesideAnExplicitContext() {
        val explicit = HarmonyTonalRange(
            id = "explicit",
            start = Fraction.ZERO,
            end = Fraction.ONE,
            keys = listOf(gMajor),
            priority = 10,
        )

        assertEquals(
            listOf(cMajor, gMajor),
            HarmonyTonalTimeline.keysAt(
                time = Fraction.HALF,
                ranges = listOf(explicit),
                defaultKey = cMajor,
                includeDefaultKeyWithActive = true,
            ),
        )
    }

    @Test
    fun resolvedContextContinuesAcrossAnActiveBaselineRange() {
        val ranges = listOf(
            HarmonyTonalRange(
                id = "pivot",
                start = Fraction.ZERO,
                end = Fraction.HALF,
                keys = listOf(cMajor, gMajor),
                resolvedKey = gMajor,
                priority = 10,
            ),
            HarmonyTonalRange(
                id = "signature",
                start = Fraction.ZERO,
                end = null,
                keys = listOf(cMajor),
            ),
        )

        assertEquals(
            listOf(gMajor),
            HarmonyTonalTimeline.keysAt(Fraction(3, 4), ranges, cMajor),
        )
    }

    @Test
    fun lanesReuseAFreeRowAfterAnIntervalEnds() {
        val ranges = listOf(
            HarmonyTonalRange("a", Fraction.ZERO, Fraction.HALF, listOf(cMajor)),
            HarmonyTonalRange("b", Fraction.QUARTER, Fraction(3, 4), listOf(gMajor)),
            HarmonyTonalRange("c", Fraction.HALF, Fraction.ONE, listOf(cMajor)),
        )

        assertEquals(listOf(0, 1, 0), HarmonyTonalTimeline.lanes(ranges, Fraction.ONE))
    }

    @Test
    fun chordReadingUsesTheSharedSelectionCatalog() {
        val readings = HarmonyTimelineReadingProjector.readings(
            Chord(PitchClass.C, ChordQuality.MAJOR),
            listOf(cMajor, gMajor),
        )

        assertTrue(readings.any { it.key == cMajor && it.functionalSymbol == "I" })
        assertTrue(readings.any { it.key == gMajor && it.functionalSymbol == "IV" })
    }

    @Test
    fun chordReadingUsesTheCatalogsCanonicalChoiceInsteadOfEverySecondaryAlias() {
        val readings = HarmonyTimelineReadingProjector.readings(
            Chord(PitchClass.C, ChordQuality.MAJOR),
            listOf(cMajor),
        )

        assertEquals(listOf("I"), readings.map(HarmonyTimelineReading::functionalSymbol))
    }

    @Test
    fun overlappingExplicitRangesExposeEveryKeyInStartOrder() {
        val ranges = listOf(
            HarmonyTonalRange(
                id = "later",
                start = Fraction.QUARTER,
                end = Fraction.ONE,
                keys = listOf(gMajor),
                priority = 10,
            ),
            HarmonyTonalRange(
                id = "earlier",
                start = Fraction.ZERO,
                end = Fraction.ONE,
                keys = listOf(cMajor),
                priority = 10,
            ),
        )

        assertEquals(
            listOf(cMajor, gMajor),
            HarmonyTonalTimeline.keysAt(Fraction.HALF, ranges, cMajor),
        )
    }

    @Test
    fun coalescePreservesAnOpenEndedRange() {
        val merged = HarmonyTonalTimeline.coalesce(
            listOf(
                HarmonyTonalRange("open", Fraction.ZERO, null, listOf(cMajor)),
                HarmonyTonalRange("later", Fraction.HALF, Fraction.ONE, listOf(cMajor)),
            )
        )

        assertEquals(1, merged.size)
        assertEquals(null, merged.single().end)
    }
}
