package com.mecon.theory.voiceleading

import com.mecon.theory.ChordQuality
import com.mecon.theory.NonChordToneType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val C_MAJOR = listOf(0, 4, 7)
private val G_MAJOR = listOf(2, 7, 11)
private val E_MINOR = listOf(4, 7, 11)
private val C_SUS2 = listOf(0, 2, 7)

class VoiceLeadingFigurationTest {

    private fun tonicToDominant(intermediate: List<Int>): VoiceLeadingPathway =
        VoiceLeadingPathways.orderings(C_MAJOR, G_MAJOR)
            .single { it.stepCount == 2 && it.intermediateNodes.single().pitchClasses == intermediate }

    @Test
    fun placingTheSuspendedNodeOnTheTargetMakesTheHeldToneASuspension() {
        val figuration = VoiceLeadingFigurationProjector.project(
            tonicToDominant(C_SUS2),
            VoiceLeadingFigurationPlacement.SUSPENSION_BEFORE_TARGET,
        )
        val node = figuration.nodes.single()
        assertEquals(G_MAJOR, node.governingPitchClasses)
        assertFalse(node.readAsChord)
        assertEquals(
            listOf(0 to NonChordToneType.SUSPENSION),
            node.nonChordTones.map { it.pitchClass to it.nonChordTone },
        )
    }

    @Test
    fun placingTheSameNodeOnTheSourceMakesTheMovedToneAnAnticipation() {
        val figuration = VoiceLeadingFigurationProjector.project(
            tonicToDominant(C_SUS2),
            VoiceLeadingFigurationPlacement.ANTICIPATION_AFTER_SOURCE,
        )
        val node = figuration.nodes.single()
        assertEquals(C_MAJOR, node.governingPitchClasses)
        assertEquals(
            listOf(2 to NonChordToneType.ANTICIPATION),
            node.nonChordTones.map { it.pitchClass to it.nonChordTone },
        )
    }

    @Test
    fun anUpwardResolutionIsARetardationRatherThanASuspension() {
        // C major -> Csus2 read the other way round: hold the tonic while the third rises.
        val pathway = VoiceLeadingPathways.orderings(
            listOf(0, 4, 7),
            listOf(0, 5, 9),
            VoiceLeadingPathSearchOptions(maxPathwaysPerTarget = 64),
        ).single { it.stepCount == 2 && it.intermediateNodes.single().pitchClasses == listOf(0, 5, 7) }
        val node = VoiceLeadingFigurationProjector
            .project(pathway, VoiceLeadingFigurationPlacement.SUSPENSION_BEFORE_TARGET)
            .nodes.single()
        assertEquals(
            listOf(7 to NonChordToneType.RETARDATION),
            node.nonChordTones.map { it.pitchClass to it.nonChordTone },
        )
    }

    @Test
    fun aStableIntermediateReadsAsAPassingChordWithNoNonChordTones() {
        val figuration = VoiceLeadingFigurationProjector.project(
            tonicToDominant(E_MINOR),
            VoiceLeadingFigurationPlacement.PASSING_CHORD,
        )
        val node = figuration.nodes.single()
        assertTrue(node.readAsChord)
        assertEquals(E_MINOR, node.governingPitchClasses)
        assertTrue(node.nonChordTones.isEmpty())
        assertTrue(figuration.types.isEmpty())
    }

    @Test
    fun aTransitionalNodeCannotBeAPassingChordAndFallsBackToTheSuspensionReading() {
        val asPassingChord = VoiceLeadingFigurationProjector.project(
            tonicToDominant(C_SUS2),
            VoiceLeadingFigurationPlacement.PASSING_CHORD,
        )
        val asSuspension = VoiceLeadingFigurationProjector.project(
            tonicToDominant(C_SUS2),
            VoiceLeadingFigurationPlacement.SUSPENSION_BEFORE_TARGET,
        )
        assertFalse(asPassingChord.nodes.single().readAsChord)
        assertEquals(asSuspension.nodes.map { it.roles }, asPassingChord.nodes.map { it.roles })
    }

    @Test
    fun tonesForeignToBothChordsBecomePassingOrNeighbourTones() {
        val pathways = VoiceLeadingPathways.search(
            C_MAJOR,
            VoiceLeadingPathSearchOptions(
                maxSteps = 3,
                maxMovesPerColumn = 2,
                maxPathwaysPerTarget = 256,
            ),
        )
        val types = pathways.flatMapTo(linkedSetOf()) { pathway ->
            VoiceLeadingFigurationProjector
                .project(pathway, VoiceLeadingFigurationPlacement.SUSPENSION_BEFORE_TARGET)
                .types
        }
        assertTrue(NonChordToneType.PASSING in types, "found $types")
        assertTrue(NonChordToneType.NEIGHBOR in types, "found $types")

        // A tone that leaves and returns while another voice moves is a neighbour, not a passing tone.
        val neighbourPathway = pathways.single { pathway ->
            pathway.nodes.map { it.pitchClasses } ==
                listOf(C_MAJOR, listOf(0, 4, 8), listOf(0, 3, 8), listOf(0, 3, 7))
        }
        val neighbourRoles = VoiceLeadingFigurationProjector
            .project(neighbourPathway, VoiceLeadingFigurationPlacement.SUSPENSION_BEFORE_TARGET)
            .nodes.flatMap { it.nonChordTones }
        assertEquals(
            listOf(NonChordToneType.NEIGHBOR),
            neighbourRoles.filter { it.pitchClass == 8 }.map { it.nonChordTone }.distinct(),
        )
        // The third, still sitting on E while C minor sounds, is a plain suspension in the same node.
        assertTrue(
            neighbourRoles.any { it.pitchClass == 4 && it.nonChordTone == NonChordToneType.SUSPENSION },
        )
    }

    @Test
    fun suspensionEnumerationNamesTheChordByTheToneThatActuallyResolves() {
        val candidate = VoiceLeadingSuspensions.enumerate(C_MAJOR)
            .single { it.pathway.targetPitchClasses == G_MAJOR && it.pathway.stepCount == 2 }
        assertEquals(C_SUS2, candidate.suspensionNode.pitchClasses)
        assertEquals(listOf(0), candidate.suspendedPitchClasses)
        assertEquals(listOf(0), candidate.preparedPitchClasses)
        assertTrue(candidate.fullyPrepared)
        assertEquals(mapOf(0 to 11), candidate.resolutions)
        // 0-2-7 is both Csus2 and Gsus4; only the Gsus4 reading has the resolving tone as its
        // suspended member, which is the reading a 4-3 cadential suspension is heard as.
        assertEquals(
            listOf(ChordQuality.SUS4 to 7),
            candidate.explainingReadings.map { it.quality to it.rootPitchClass },
        )
    }

    @Test
    fun everySuspensionCandidateResolvesIntoAStableChord() {
        val candidates = VoiceLeadingSuspensions.enumerate(
            C_MAJOR,
            VoiceLeadingPathSearchOptions(maxSteps = 3, maxPathwaysPerTarget = 64),
        )
        assertTrue(candidates.isNotEmpty())
        candidates.forEach { candidate ->
            assertEquals(VoiceLeadingStability.TRANSITIONAL, candidate.suspensionNode.stability)
            assertEquals(VoiceLeadingStability.STABLE, candidate.pathway.targetNode.stability)
            assertTrue(candidate.suspendedPitchClasses.isNotEmpty())
            candidate.resolutions.forEach { (suspended, resolution) ->
                assertTrue(
                    resolution in candidate.pathway.targetPitchClasses,
                    "$suspended must resolve into the target chord",
                )
            }
        }
    }
}
