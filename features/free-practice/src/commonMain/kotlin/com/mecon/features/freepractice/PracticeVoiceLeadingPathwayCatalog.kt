package com.mecon.features.freepractice

import com.mecon.theory.voiceleading.StandardVoiceLeadingUniverses
import com.mecon.theory.voiceleading.VoiceLeadingChordReading
import com.mecon.theory.voiceleading.VoiceLeadingPathSearchOptions
import com.mecon.theory.voiceleading.VoiceLeadingPathway
import com.mecon.theory.voiceleading.VoiceLeadingPathways
import com.mecon.theory.voiceleading.VoiceLeadingStability
import com.mecon.theory.voiceleading.VoiceLeadingTension
import com.mecon.theory.voiceleading.VoiceLeadingTensionProfile
import com.mecon.theory.voiceleading.VoiceLeadingTransformations

/**
 * The one place that decides which pathways free practice offers and in what order.
 *
 * The view projector and the session both go through it, so a candidate that is presented is
 * exactly a candidate the session will accept, and the deterministic [VoiceLeadingPathway.identity]
 * is a stable handle for the intent.
 */
internal object PracticeVoiceLeadingPathwayCatalog {

    const val SUSPENSION_GROUP_ID = "suspension"
    const val PASSING_GROUP_ID = "passing"

    /** Presented per group; the projector reports the total so nothing is dropped silently. */
    const val MAX_PER_GROUP = 12

    val universe = StandardVoiceLeadingUniverses.TERTIAN_WITH_SUSPENSIONS

    /**
     * Two and three step connections in which every tone moves at most once.
     *
     * Re-moving a tone (which is what produces passing and neighbour tones) is deliberately left
     * out of the v1 catalog: it belongs to the figuration placement, which is not enabled yet.
     */
    val options = VoiceLeadingPathSearchOptions(
        universe = universe,
        maxSteps = 3,
        maxMovesPerColumn = 1,
        maxPathwaysPerTarget = 4,
    )

    data class Entry(
        val groupId: String,
        val pathway: VoiceLeadingPathway,
        val profile: VoiceLeadingTensionProfile,
        val drive: Double,
        /** Preferred root reading per node, chosen for the connection from the previous node. */
        val nodeRootPitchClasses: List<Int>,
    ) {
        val id: String get() = pathway.identity
    }

    /** Every offered pathway, best first within its group. */
    fun entries(sourcePitchClasses: List<Int>): List<Entry> {
        if (universe.recognize(sourcePitchClasses).isEmpty()) return emptyList()
        if (sourcePitchClasses.size !in universe.cardinalities) return emptyList()
        return VoiceLeadingPathways.search(sourcePitchClasses, options)
            .filter { it.intermediateNodes.isNotEmpty() }
            .map { pathway ->
                val profile = VoiceLeadingTension.profile(pathway, universe)
                Entry(
                    groupId = if (
                        pathway.intermediateNodes.any {
                            it.stability == VoiceLeadingStability.TRANSITIONAL
                        }
                    ) SUSPENSION_GROUP_ID else PASSING_GROUP_ID,
                    pathway = pathway,
                    profile = profile,
                    drive = VoiceLeadingTension.drive(pathway, profile),
                    nodeRootPitchClasses = nodeRootPitchClasses(pathway),
                )
            }
            .sortedWith(compareByDescending<Entry> { it.drive }.thenBy { it.id })
    }

    fun grouped(sourcePitchClasses: List<Int>): Map<String, List<Entry>> =
        entries(sourcePitchClasses).groupBy { it.groupId }

    /** Resolves an intent's pathway id against a freshly enumerated catalog. */
    fun find(sourcePitchClasses: List<Int>, pathwayId: String): Entry? =
        entries(sourcePitchClasses).firstOrNull { it.id == pathwayId }

    /**
     * Each node is named by the root reading that best explains its connection from the previous
     * node, so `0-2-7` after a C major chord reads as Gsus4 rather than Csus2.
     */
    private fun nodeRootPitchClasses(pathway: VoiceLeadingPathway): List<Int> {
        // Once a node has been named, the next connection is measured from that name only;
        // otherwise a symmetric or twice-readable sonority would keep every reading alive and the
        // chain could hop between incompatible root interpretations.
        var previous: List<VoiceLeadingChordReading> = listOf(pathway.sourceNode.readings.first())
        return pathway.nodes.mapIndexed { index, node ->
            if (index == 0) {
                previous.first().rootPitchClass
            } else {
                val root = VoiceLeadingTransformations
                    .mostStableRootConnection(previous, node.readings).targetRootPitchClass
                previous = node.readings.filter { it.rootPitchClass == root }
                    .ifEmpty { node.readings }
                root
            }
        }
    }
}
