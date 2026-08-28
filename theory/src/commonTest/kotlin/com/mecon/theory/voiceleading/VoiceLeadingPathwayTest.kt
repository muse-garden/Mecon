package com.mecon.theory.voiceleading

import com.mecon.theory.ChordQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** C major, E minor, G major and the suspended sonority between them, as pitch-class sets. */
private val C_MAJOR = listOf(0, 4, 7)
private val E_MINOR = listOf(4, 7, 11)
private val G_MAJOR = listOf(2, 7, 11)
private val C_SUS2 = listOf(0, 2, 7)
private val F_MAJOR = listOf(0, 5, 9)
private val C_SUS4 = listOf(0, 5, 7)

class VoiceLeadingPathwayTest {

    @Test
    fun suspendedSonoritiesAreTransitionalAndTertianChordsAreStable() {
        val universe = StandardVoiceLeadingUniverses.TERTIAN_WITH_SUSPENSIONS
        assertEquals(VoiceLeadingStability.STABLE, universe.stabilityOfSet(C_MAJOR))
        assertEquals(VoiceLeadingStability.STABLE, universe.stabilityOfSet(listOf(0, 4, 7, 10)))
        assertEquals(VoiceLeadingStability.TRANSITIONAL, universe.stabilityOfSet(C_SUS2))
        assertEquals(VoiceLeadingStability.TRANSITIONAL, universe.stabilityOfSet(C_SUS4))
        // 0-1-2 is not nameable in this universe and must never appear on a pathway.
        assertEquals(null, universe.stabilityOfSet(listOf(0, 1, 2)))
    }

    @Test
    fun theSuspendedSetKeepsBothItsSus2AndSus4Readings() {
        val readings = StandardVoiceLeadingUniverses.TERTIAN_WITH_SUSPENSIONS.recognize(C_SUS2)
        assertEquals(
            setOf(ChordQuality.SUS2 to 0, ChordQuality.SUS4 to 7),
            readings.mapTo(hashSetOf()) { it.quality to it.rootPitchClass },
        )
    }

    @Test
    fun stableOnlyUniverseReproducesTheBaseGraphVocabulary() {
        val stableOnly = StandardVoiceLeadingUniverses.TERTIAN_STABLE_ONLY
        assertEquals(null, stableOnly.stabilityOfSet(C_SUS2))
        val targets = VoiceLeadingPathways.search(
            C_MAJOR,
            VoiceLeadingPathSearchOptions(universe = stableOnly, maxSteps = 2),
        ).mapTo(hashSetOf()) { it.targetPitchClasses }
        val baseTargets = VoiceLeadingTransformations
            .enumerate(C_MAJOR, StandardVoiceLeadingChordFamilies.TRIADS)
            .mapTo(hashSetOf()) { it.targetPitchClasses }
        assertTrue(
            targets.containsAll(baseTargets),
            "Pathway search must reach every base-graph target; missing ${baseTargets - targets}",
        )
    }

    @Test
    fun tonicToDominantIsReachableThroughEitherAMediantOrASuspension() {
        val orderings = VoiceLeadingPathways.orderings(C_MAJOR, G_MAJOR)
            .filter { it.stepCount == 2 }
        val intermediates = orderings.map { it.intermediateNodes.single().pitchClasses }.toSet()
        assertTrue(E_MINOR in intermediates, "expected the mediant ordering, got $intermediates")
        assertTrue(C_SUS2 in intermediates, "expected the suspension ordering, got $intermediates")

        val viaSuspension = orderings.single { it.intermediateNodes.single().pitchClasses == C_SUS2 }
        // Doing the second move first: 3 -> 2 produces the suspension, then 1 -> 7 resolves it.
        assertEquals(listOf(4 to 2, 0 to 11), viaSuspension.steps.map { it.fromPitchClass to it.toPitchClass })
        assertEquals(
            VoiceLeadingStability.TRANSITIONAL,
            viaSuspension.intermediateNodes.single().stability,
        )
    }

    @Test
    fun theSuspendedFourthOnTheTonicIsTheSubdominantsSuspendedSecond() {
        val fromSubdominant = VoiceLeadingPathways.orderings(F_MAJOR, C_MAJOR)
            .single { it.stepCount == 2 && it.intermediateNodes.single().pitchClasses == C_SUS4 }
        // IV: 6 -> 5 makes 1-4-5, then 4 -> 3 resolves onto the tonic triad.
        assertEquals(listOf(9 to 7, 5 to 4), fromSubdominant.steps.map { it.fromPitchClass to it.toPitchClass })
        val readings = fromSubdominant.intermediateNodes.single().readings
        assertEquals(
            setOf(ChordQuality.SUS2 to 5, ChordQuality.SUS4 to 0),
            readings.mapTo(hashSetOf()) { it.quality to it.rootPitchClass },
        )
    }

    @Test
    fun everyPathwayNodeIsNameableAndNoToneMovesBeyondItsBudget() {
        val pathways = VoiceLeadingPathways.search(
            C_MAJOR,
            VoiceLeadingPathSearchOptions(
                maxSteps = 3,
                maxMovesPerColumn = 2,
                allowSplit = true,
                allowFuse = true,
            ),
        )
        assertTrue(pathways.isNotEmpty())
        val universe = StandardVoiceLeadingUniverses.TERTIAN_WITH_SUSPENSIONS
        pathways.forEach { pathway ->
            pathway.nodes.forEach { node ->
                assertTrue(
                    universe.recognize(node.pitchClasses).isNotEmpty(),
                    "unnameable node ${node.pitchClasses} on ${pathway.identity}",
                )
                node.columns.forEach { column ->
                    assertTrue(column.moveCount <= 2, "column budget exceeded on ${pathway.identity}")
                }
            }
            assertEquals(VoiceLeadingStability.STABLE, pathway.targetNode.stability)
            // Pathways never revisit a sonority, so they cannot cycle.
            assertEquals(
                pathway.nodes.size,
                pathway.nodes.map { it.pitchClasses }.distinct().size,
                "cycling pathway ${pathway.identity}",
            )
        }
    }

    @Test
    fun splittingATonePassesThroughASeventhChordThatShiftsAloneCannotReach() {
        val pathways = VoiceLeadingPathways.search(
            C_MAJOR,
            VoiceLeadingPathSearchOptions(
                maxSteps = 1,
                allowSplit = true,
            ),
        )
        val aMinorSeventh = assertNotNull(
            pathways.firstOrNull { it.targetPitchClasses == listOf(0, 4, 7, 9) },
            "splitting the fifth up to A should reach Am7",
        )
        val split = aMinorSeventh.steps.single() as VoiceLeadingStep.Split
        assertEquals(7, split.fromPitchClass)
        assertEquals(9, split.toPitchClass)
        // The branch keeps the lineage of the tone it grew out of.
        val branch = aMinorSeventh.targetNode.columns.single { it.id == split.branchColumnId }
        val parent = aMinorSeventh.targetNode.columns.single { it.id == split.columnId }
        assertEquals(parent.sourceToneIndices, branch.sourceToneIndices)
        assertTrue(
            VoiceLeadingPathways.search(C_MAJOR, VoiceLeadingPathSearchOptions(maxSteps = 3))
                .none { it.targetPitchClasses.size != 3 },
            "cardinality must stay fixed when splitting is off",
        )
    }

    @Test
    fun fusingTwoAdjacentTonesCollapsesASeventhChordBackToATriad() {
        val pathways = VoiceLeadingPathways.search(
            listOf(0, 4, 7, 9),
            VoiceLeadingPathSearchOptions(maxSteps = 1, allowFuse = true),
        )
        val fused = assertNotNull(
            pathways.firstOrNull { pathway -> pathway.steps.single() is VoiceLeadingStep.Fuse },
            "Am7 must be able to fuse its adjacent tones back into a triad",
        )
        assertEquals(3, fused.targetPitchClasses.size)
        val step = fused.steps.single() as VoiceLeadingStep.Fuse
        val merged = fused.targetNode.columns.single { it.id == step.intoColumnId }
        assertEquals(2, merged.sourceToneIndices.size, "a fused column carries both lineages")
    }

    @Test
    fun transitionalTargetsAreOptInSoTheSuspensionItselfCanBeOffered() {
        val withoutSus = VoiceLeadingPathways.search(C_MAJOR)
        assertFalse(withoutSus.any { it.targetPitchClasses == C_SUS2 })
        val withSus = VoiceLeadingPathways.search(
            C_MAJOR,
            VoiceLeadingPathSearchOptions(includeTransitionalTargets = true),
        )
        assertTrue(withSus.any { it.targetPitchClasses == C_SUS2 && it.stepCount == 1 })
    }

    @Test
    fun searchIsDeterministic() {
        val options = VoiceLeadingPathSearchOptions(
            maxSteps = 3,
            maxMovesPerColumn = 2,
            allowSplit = true,
            allowFuse = true,
        )
        assertEquals(
            VoiceLeadingPathways.search(C_MAJOR, options).map { it.identity },
            VoiceLeadingPathways.search(C_MAJOR, options).map { it.identity },
        )
    }
}
