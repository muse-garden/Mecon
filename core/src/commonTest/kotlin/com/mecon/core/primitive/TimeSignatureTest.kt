package com.mecon.api.primitive

import kotlin.test.*

class TimeSignatureTest {

    // ===== beatGrouping / defaultBeatGroups =====

    @Test
    fun simpleMeterGroupsPerBeat() {
        assertEquals(listOf(1, 1, 1, 1), TimeSignature(4, 4).beatGrouping())
        assertEquals(listOf(1, 1, 1), TimeSignature(3, 4).beatGrouping())
    }

    @Test
    fun compoundMeterGroupsByThree() {
        assertEquals(listOf(3, 3), TimeSignature(6, 8).beatGrouping())
        assertEquals(listOf(3, 3, 3, 3), TimeSignature(12, 8).beatGrouping())
    }

    @Test
    fun explicitBeatGroupsOverrideDefault() {
        assertEquals(listOf(2, 2, 3), TimeSignature(7, 8, beatGroups = listOf(2, 2, 3)).beatGrouping())
        assertEquals(listOf(3, 2, 2), TimeSignature(7, 8, beatGroups = listOf(3, 2, 2)).beatGrouping())
    }

    @Test
    fun invalidBeatGroupsFallBackToDefault() {
        // Sum (6) != numerator (7) → ignored.
        assertEquals(TimeSignature(7, 8).defaultBeatGroups(), TimeSignature(7, 8, beatGroups = listOf(2, 2, 2)).beatGrouping())
    }

    // ===== beatGroupCandidates =====

    @Test
    fun simpleMetersOfferSingleCandidate() {
        assertEquals(1, TimeSignature(4, 4).beatGroupCandidates().size)
        assertEquals(1, TimeSignature(3, 4).beatGroupCandidates().size)
    }

    @Test
    fun sevenEightOffersThreeGroupings() {
        val candidates = TimeSignature(7, 8).beatGroupCandidates()
        assertTrue(candidates.contains(listOf(2, 2, 3)))
        assertTrue(candidates.contains(listOf(2, 3, 2)))
        assertTrue(candidates.contains(listOf(3, 2, 2)))
        // Canonical default first.
        assertEquals(listOf(2, 2, 3), candidates.first())
        candidates.forEach { assertEquals(7, it.sum()) }
    }

    @Test
    fun sixEightOffersThreesAndTwos() {
        val candidates = TimeSignature(6, 8).beatGroupCandidates()
        assertEquals(listOf(3, 3), candidates.first())
        assertTrue(candidates.contains(listOf(2, 2, 2)))
    }

    // ===== beatGroupIndexOf =====

    @Test
    fun beatGroupIndexForCompound() {
        val ts = TimeSignature(6, 8) // groups [3,3] in eighth-units
        assertEquals(0, ts.beatGroupIndexOf(Fraction(0, 8)))
        assertEquals(0, ts.beatGroupIndexOf(Fraction(2, 8)))
        assertEquals(1, ts.beatGroupIndexOf(Fraction(3, 8)))
        assertEquals(1, ts.beatGroupIndexOf(Fraction(5, 8)))
    }

    @Test
    fun beatGroupIndexForSevenEight() {
        val ts = TimeSignature(7, 8, beatGroups = listOf(2, 2, 3)) // boundaries at 2,4,7
        assertEquals(0, ts.beatGroupIndexOf(Fraction(1, 8)))
        assertEquals(1, ts.beatGroupIndexOf(Fraction(2, 8)))
        assertEquals(2, ts.beatGroupIndexOf(Fraction(4, 8)))
        assertEquals(2, ts.beatGroupIndexOf(Fraction(6, 8)))
    }

    @Test
    fun beatGroupIndexPastEndClampsToLast() {
        val ts = TimeSignature(4, 4) // groups [1,1,1,1]
        assertEquals(3, ts.beatGroupIndexOf(Fraction(5, 4)))
    }
}
