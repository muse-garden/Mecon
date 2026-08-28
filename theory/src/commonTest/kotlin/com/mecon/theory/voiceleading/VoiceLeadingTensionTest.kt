package com.mecon.theory.voiceleading

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val C_MAJOR = listOf(0, 4, 7)
private val G_MAJOR = listOf(2, 7, 11)
private val E_MINOR = listOf(4, 7, 11)
private val C_SUS2 = listOf(0, 2, 7)
private val UNIVERSE = StandardVoiceLeadingUniverses.TERTIAN_WITH_SUSPENSIONS

class VoiceLeadingTensionTest {

    @Test
    fun dissonanceRanksConsonantTriadsBelowSuspensionsAndDiminishedSonorities() {
        val major = VoiceLeadingTension.dissonance(C_MAJOR)
        val minor = VoiceLeadingTension.dissonance(listOf(0, 3, 7))
        val augmented = VoiceLeadingTension.dissonance(listOf(0, 4, 8))
        val suspended = VoiceLeadingTension.dissonance(C_SUS2)
        val dominantSeventh = VoiceLeadingTension.dissonance(listOf(0, 4, 7, 10))
        val diminished = VoiceLeadingTension.dissonance(listOf(0, 3, 6))
        assertEquals(major, minor, absoluteTolerance = 1e-9)
        assertTrue(
            major < augmented && augmented < suspended &&
                suspended < dominantSeventh && dominantSeventh < diminished,
            "unexpected order: $major, $augmented, $suspended, $dominantSeventh, $diminished",
        )
    }

    @Test
    fun theSuspendedOrderingHasATensionArchAndTheMediantOrderingDoesNot() {
        val orderings = VoiceLeadingPathways.orderings(C_MAJOR, G_MAJOR).filter { it.stepCount == 2 }
        val viaSuspension = orderings.single { it.intermediateNodes.single().pitchClasses == C_SUS2 }
        val viaMediant = orderings.single { it.intermediateNodes.single().pitchClasses == E_MINOR }

        val suspensionProfile = VoiceLeadingTension.profile(viaSuspension, UNIVERSE)
        val mediantProfile = VoiceLeadingTension.profile(viaMediant, UNIVERSE)

        assertTrue(suspensionProfile.arc > 0.0, "a suspension must rise above both endpoints")
        assertTrue(mediantProfile.arc <= 0.0, "a consonant mediant is no more tense than the chords")
        assertTrue(suspensionProfile.peakTension > mediantProfile.peakTension)
        assertTrue(suspensionProfile.resolutionDrop > mediantProfile.resolutionDrop)
        assertTrue(suspensionProfile.monotonicRelease)
    }

    @Test
    fun theSuspensionCompensatesTheWeakRootProgressionFromTonicToDominant() {
        val orderings = VoiceLeadingPathways.orderings(C_MAJOR, G_MAJOR).filter { it.stepCount == 2 }
        val viaSuspension = orderings.single { it.intermediateNodes.single().pitchClasses == C_SUS2 }
        val viaMediant = orderings.single { it.intermediateNodes.single().pitchClasses == E_MINOR }
        // Identical endpoints, so the whole difference in drive comes from the tension drop.
        assertTrue(
            VoiceLeadingTension.drive(
                viaSuspension,
                VoiceLeadingTension.profile(viaSuspension, UNIVERSE),
            ) > VoiceLeadingTension.drive(
                viaMediant,
                VoiceLeadingTension.profile(viaMediant, UNIVERSE),
            )
        )
    }

    @Test
    fun theCentroidSeparatesFrontLoadedFromBackLoadedThreeStepPathways() {
        val pathways = VoiceLeadingPathways.search(
            C_MAJOR,
            VoiceLeadingPathSearchOptions(maxSteps = 3, maxPathwaysPerTarget = 64),
        ).filter { it.stepCount == 3 && it.intermediateNodes.size == 2 }
        val frontLoaded = pathways.filter { pathway ->
            pathway.intermediateNodes.first().stability == VoiceLeadingStability.TRANSITIONAL &&
                pathway.intermediateNodes.last().stability == VoiceLeadingStability.STABLE
        }
        val backLoaded = pathways.filter { pathway ->
            pathway.intermediateNodes.first().stability == VoiceLeadingStability.STABLE &&
                pathway.intermediateNodes.last().stability == VoiceLeadingStability.TRANSITIONAL
        }
        assertTrue(frontLoaded.isNotEmpty() && backLoaded.isNotEmpty())
        val frontCentroid = frontLoaded.map { VoiceLeadingTension.profile(it, UNIVERSE).centroid }
        val backCentroid = backLoaded.map { VoiceLeadingTension.profile(it, UNIVERSE).centroid }
        assertTrue(
            frontCentroid.max() < backCentroid.min(),
            "front-loaded $frontCentroid must all precede back-loaded $backCentroid",
        )
    }

    @Test
    fun symmetricSonoritiesAreReportedAsMoreAmbiguousThanAMajorTriad() {
        fun ambiguityOf(pitchClasses: List<Int>): Double {
            val readings = UNIVERSE.recognize(pitchClasses)
            val node = VoiceLeadingPathNode(
                stepIndex = 0,
                pitchClasses = pitchClasses.sorted(),
                columns = pitchClasses.sorted().mapIndexed { index, pitchClass ->
                    VoiceLeadingPathColumn(index, listOf(index), pitchClass, 0)
                },
                readings = readings,
                stability = UNIVERSE.stabilityOfSet(pitchClasses)!!,
            )
            return VoiceLeadingTension.nodeMetrics(node, UNIVERSE).ambiguity
        }
        assertTrue(ambiguityOf(listOf(0, 4, 8)) > ambiguityOf(C_MAJOR), "augmented triad is symmetric")
        assertTrue(ambiguityOf(listOf(0, 3, 6, 9)) > ambiguityOf(listOf(0, 4, 7, 10)))
    }

    @Test
    fun everyPolicyWeightComesFromTheSingleNamedPolicy() {
        assertEquals("voice-leading.tension.v1", VoiceLeadingTensionPolicy.DEFAULT.id)
        val quiet = VoiceLeadingTensionPolicy.DEFAULT.copy(
            id = "test.instability-only",
            dissonanceWeight = 0.0,
            instabilityWeight = 1.0,
        )
        val viaSuspension = VoiceLeadingPathways.orderings(C_MAJOR, G_MAJOR)
            .single { it.stepCount == 2 && it.intermediateNodes.single().pitchClasses == C_SUS2 }
        val profile = VoiceLeadingTension.profile(viaSuspension, UNIVERSE, quiet)
        assertEquals("test.instability-only", profile.policyId)
        assertEquals(1.0, profile.peakTension, absoluteTolerance = 1e-9)
        assertEquals(0.0, profile.targetTension, absoluteTolerance = 1e-9)
    }
}
