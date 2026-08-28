package com.mecon.theory.voiceleading

import com.mecon.theory.ChordQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val TONIC = listOf(0, 4, 7)
private val SUBDOMINANT = listOf(0, 5, 9)
private val SUPERTONIC_SEVENTH = listOf(0, 2, 5, 9)
private val DOMINANT = listOf(2, 7, 11)
private val G_SUS4 = listOf(0, 2, 7)

class VoiceLeadingSuspensionInsertionTest {

    @Test
    fun theSubdominantPreparesTheCadentialFourThreeThatNoParsimoniousPathCanReach() {
        val cadential = VoiceLeadingSuspensionInsertion.between(SUBDOMINANT, DOMINANT)
            .single { it.suspensionPitchClasses == G_SUS4 }
        assertEquals(0, cadential.suspendedPitchClass, "the retained tone is the C of IV")
        assertEquals(11, cadential.resolutionPitchClass, "it falls to the leading tone")
        assertEquals(-1, cadential.semitones)
        assertEquals(SuspensionDischarge.DOWNWARD, cadential.discharge)
        assertTrue(cadential.tensionDrop > 0.0)
        // The cadential figure is named from the chord it decorates, not from the chord that
        // prepared it: 0-2-7 over the dominant is Gsus4, i.e. a 4-3 suspension.
        assertTrue(
            cadential.readings.any { it.quality == ChordQuality.SUS4 && it.rootPitchClass == 7 },
            "expected a Gsus4 reading, got ${cadential.readings}",
        )

        // The same figure is unreachable as a two-step parsimonious pathway from IV, which is why
        // the pathway catalog alone never offered the idiomatic cadence.
        assertTrue(
            VoiceLeadingPathways.orderings(
                SUBDOMINANT,
                DOMINANT,
                VoiceLeadingPathSearchOptions(maxSteps = 3, includeTransitionalTargets = true),
            ).none { pathway -> pathway.intermediateNodes.any { it.pitchClasses == G_SUS4 } },
        )
    }

    @Test
    fun theSupertonicSeventhPreparesBothTheFourThreeAndTheNineEight() {
        val prepared = VoiceLeadingSuspensionInsertion.between(SUPERTONIC_SEVENTH, DOMINANT)
            .filter { it.discharge == SuspensionDischarge.DOWNWARD }
        assertEquals(
            listOf(0 to 11, 9 to 7),
            prepared.map { it.suspendedPitchClass to it.resolutionPitchClass }.sortedBy { it.first },
        )
        val nineEight = prepared.single { it.suspendedPitchClass == 9 }
        assertEquals(listOf(2, 9, 11), nineEight.suspensionPitchClasses)
        // A 9-8 vertical has no tertian name; that must not disqualify it.
        assertFalse(nineEight.nameable)
        assertEquals(null, nineEight.stability)
        assertTrue(nineEight.tensionDrop > 0.0)
    }

    @Test
    fun aToneThatLandsConsonantlyIsAChordChangeRatherThanASuspension() {
        // C is in the tonic and steps down to B, so I can prepare the same 4-3 over V ...
        val fromTonic = VoiceLeadingSuspensionInsertion.between(TONIC, DOMINANT)
        assertTrue(fromTonic.any { it.suspensionPitchClasses == G_SUS4 })
        // ... but E -> D produces the consonant 4-7-11, which is the mediant, not a suspension.
        assertTrue(fromTonic.none { it.suspensionPitchClasses == listOf(4, 7, 11) })
    }

    @Test
    fun theUnderlyingProgressionIsMeasuredWithoutTheSuspension() {
        val fromTonic = VoiceLeadingSuspensionInsertion.between(TONIC, DOMINANT)
            .single { it.suspensionPitchClasses == G_SUS4 }
        val fromSubdominant = VoiceLeadingSuspensionInsertion.between(SUBDOMINANT, DOMINANT)
            .single { it.suspensionPitchClasses == G_SUS4 }

        // Identical figure, identical release — the only difference is the progression it sits on.
        assertEquals(fromTonic.suspensionPitchClasses, fromSubdominant.suspensionPitchClasses)
        assertEquals(fromTonic.tensionDrop, fromSubdominant.tensionDrop, absoluteTolerance = 1e-9)
        assertEquals(
            SchoenbergChromaticRootMotion.DESCENDING,
            fromTonic.underlyingRootMotion.motion,
            "I -> V stays a weak descending progression however it is decorated",
        )
        assertEquals(
            SchoenbergChromaticRootMotion.SUPERSTRONG,
            fromSubdominant.underlyingRootMotion.motion,
            "IV -> V is a rising second, i.e. Schoenberg's superstrong progression",
        )
        val policy = VoiceLeadingTensionPolicy.DEFAULT
        assertTrue(
            policy.rootMotionScores.getValue(fromSubdominant.underlyingRootMotion.motion) >
                policy.rootMotionScores.getValue(fromTonic.underlyingRootMotion.motion),
            "the cadence the figure decorates is what separates the two, not the figure",
        )
    }

    @Test
    fun upwardResolutionsAreReportedAsRetardationsAndCanBeExcluded() {
        val all = VoiceLeadingSuspensionInsertion.between(SUPERTONIC_SEVENTH, DOMINANT)
        assertTrue(all.any { it.discharge == SuspensionDischarge.UPWARD })
        assertTrue(
            all.map { it.discharge.ordinal } == all.map { it.discharge.ordinal }.sorted(),
            "downward resolutions must be offered first",
        )
        assertTrue(
            VoiceLeadingSuspensionInsertion
                .between(SUPERTONIC_SEVENTH, DOMINANT, includeUpward = false)
                .all { it.discharge == SuspensionDischarge.DOWNWARD },
        )
    }

    @Test
    fun aPreparationWithNoRetainableToneOffersNothing() {
        // The tritone-related chord shares no tone that steps into the dominant triad.
        assertTrue(VoiceLeadingSuspensionInsertion.between(listOf(1, 5, 8), listOf(1, 5, 8)).isEmpty())
    }
}
