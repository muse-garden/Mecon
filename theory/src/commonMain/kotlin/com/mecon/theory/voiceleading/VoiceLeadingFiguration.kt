package com.mecon.theory.voiceleading

import com.mecon.theory.NonChordToneType

/**
 * Where the intermediate nodes of a pathway sound relative to the two harmonies.
 *
 * The pathway itself has no rhythm; placement is the choice that turns the same ordered set of
 * moves into a suspension figure, an anticipation figure, or a chord of its own.
 */
enum class VoiceLeadingFigurationPlacement {
    /** Intermediates sound on the strong beat of the target slot; the target arrives late. */
    SUSPENSION_BEFORE_TARGET,

    /** Intermediates sound on the weak end of the source slot, over the source harmony. */
    ANTICIPATION_AFTER_SOURCE,

    /** Each intermediate owns a slot and is heard as a chord — unless it has no stable name. */
    PASSING_CHORD,
}

data class VoiceLeadingColumnRole(
    val columnId: Int,
    val pitchClass: Int,
    /** null when the tone belongs to the harmony governing this node. */
    val nonChordTone: NonChordToneType?,
)

data class VoiceLeadingFigurationNode(
    val stepIndex: Int,
    val pitchClasses: List<Int>,
    /** The sonority heard as the harmony while this node sounds. */
    val governingPitchClasses: List<Int>,
    val roles: List<VoiceLeadingColumnRole>,
    /** True when the node has a stable name and can be presented as a chord rather than figuration. */
    val readAsChord: Boolean,
) {
    val nonChordTones: List<VoiceLeadingColumnRole> get() = roles.filter { it.nonChordTone != null }
}

data class VoiceLeadingFiguration(
    val placement: VoiceLeadingFigurationPlacement,
    val nodes: List<VoiceLeadingFigurationNode>,
) {
    val types: Set<NonChordToneType>
        get() = nodes.flatMapTo(linkedSetOf()) { node ->
            node.roles.mapNotNull { it.nonChordTone }
        }
}

/**
 * Projects a pathway's intermediate nodes onto the non-chord-tone vocabulary.
 *
 * The vocabulary is [NonChordToneType], owned by the figuration layer; this projector only decides
 * which column plays which role at the skeleton level. Metre, sub-slot durations and the four-part
 * realization stay with `docs/theory/figuration.md`.
 */
object VoiceLeadingFigurationProjector {

    fun project(
        pathway: VoiceLeadingPathway,
        placement: VoiceLeadingFigurationPlacement,
    ): VoiceLeadingFiguration {
        val source = pathway.sourcePitchClasses.toSet()
        val target = pathway.targetPitchClasses.toSet()
        val nodes = pathway.intermediateNodes.map { node ->
            val readAsChord = node.stability == VoiceLeadingStability.STABLE
            val effectivePlacement =
                if (placement == VoiceLeadingFigurationPlacement.PASSING_CHORD && !readAsChord) {
                    // An unnameable-as-a-chord node cannot be a passing chord; it is figuration
                    // over the harmony it is heading for.
                    VoiceLeadingFigurationPlacement.SUSPENSION_BEFORE_TARGET
                } else placement
            val governing = when (effectivePlacement) {
                VoiceLeadingFigurationPlacement.SUSPENSION_BEFORE_TARGET -> target
                VoiceLeadingFigurationPlacement.ANTICIPATION_AFTER_SOURCE -> source
                VoiceLeadingFigurationPlacement.PASSING_CHORD -> node.pitchClasses.toSet()
            }
            VoiceLeadingFigurationNode(
                stepIndex = node.stepIndex,
                pitchClasses = node.pitchClasses,
                governingPitchClasses = governing.sorted(),
                roles = node.columns.map { column ->
                    VoiceLeadingColumnRole(
                        columnId = column.id,
                        pitchClass = column.pitchClass,
                        nonChordTone = roleOf(
                            pathway = pathway,
                            node = node,
                            column = column,
                            placement = effectivePlacement,
                            source = source,
                            target = target,
                            governing = governing,
                        ),
                    )
                },
                readAsChord = readAsChord,
            )
        }
        return VoiceLeadingFiguration(placement, nodes)
    }

    private fun roleOf(
        pathway: VoiceLeadingPathway,
        node: VoiceLeadingPathNode,
        column: VoiceLeadingPathColumn,
        placement: VoiceLeadingFigurationPlacement,
        source: Set<Int>,
        target: Set<Int>,
        governing: Set<Int>,
    ): NonChordToneType? {
        val pitchClass = column.pitchClass
        if (pitchClass in governing) return null
        return when {
            placement == VoiceLeadingFigurationPlacement.SUSPENSION_BEFORE_TARGET &&
                pitchClass in source -> {
                // The tone is still where the previous chord left it while the new chord sounds:
                // a suspension when it falls, a retardation when it rises.
                val departure = pathway.departureMotion(column.id, node.stepIndex)
                if (departure != null && departure > 0) NonChordToneType.RETARDATION
                else NonChordToneType.SUSPENSION
            }

            placement == VoiceLeadingFigurationPlacement.ANTICIPATION_AFTER_SOURCE &&
                pitchClass in target -> NonChordToneType.ANTICIPATION

            else -> {
                // Foreign to both chords: only reachable with more than one move per column, and
                // every move here is a step, so it is a passing tone or a neighbour.
                val arrival = pathway.arrivalMotion(column.id, node.stepIndex)
                val departure = pathway.departureMotion(column.id, node.stepIndex)
                if (arrival != null && departure != null && arrival * departure < 0) {
                    NonChordToneType.NEIGHBOR
                } else NonChordToneType.PASSING
            }
        }
    }
}

/**
 * A pathway whose last intermediate node has no stable name, i.e. a suspension chord followed by
 * its resolution.
 *
 * Nothing here encodes "sus4 resolves down to 3": the resolution is simply the pathway's remaining
 * step, so the same code covers 4-3, 9-8, 2-3 and the sus2-into-dominant reading alike.
 */
data class VoiceLeadingSuspensionCandidate(
    val pathway: VoiceLeadingPathway,
    val suspensionNode: VoiceLeadingPathNode,
    /** Tones of the suspension chord that are foreign to the resolution chord. */
    val suspendedPitchClasses: List<Int>,
    /** Suspended tones already sounding in the source chord, i.e. properly prepared. */
    val preparedPitchClasses: List<Int>,
    /** Suspended tone -> where the final step takes it. */
    val resolutions: Map<Int, Int>,
    /** Readings that name the suspension node with the resolving tone as its suspended member. */
    val explainingReadings: List<VoiceLeadingChordReading>,
) {
    val fullyPrepared: Boolean get() = preparedPitchClasses.size == suspendedPitchClasses.size
}

object VoiceLeadingSuspensions {

    /**
     * Every pathway from [sourcePitchClasses] that passes through a suspension immediately before
     * its resolution.
     *
     * With the default options this is precisely "take a two-step connection and do the last move
     * first" — the reordering that turns `I -> iii -> V` into `I -> Gsus4 -> V`.
     */
    fun enumerate(
        sourcePitchClasses: Collection<Int>,
        options: VoiceLeadingPathSearchOptions = VoiceLeadingPathSearchOptions(),
    ): List<VoiceLeadingSuspensionCandidate> {
        val universe = options.universe
        return VoiceLeadingPathways.search(sourcePitchClasses, options)
            .filter { it.stepCount >= 2 }
            .mapNotNull { pathway ->
                val suspensionNode = pathway.nodes[pathway.nodes.size - 2]
                if (suspensionNode.stability != VoiceLeadingStability.TRANSITIONAL) return@mapNotNull null
                if (pathway.targetNode.stability != VoiceLeadingStability.STABLE) return@mapNotNull null
                val target = pathway.targetPitchClasses.toSet()
                val source = pathway.sourcePitchClasses.toSet()
                val suspended = suspensionNode.pitchClasses.filterNot { it in target }
                if (suspended.isEmpty()) return@mapNotNull null
                val finalStep = pathway.steps.last()
                val resolutions = suspended.associateWith { pitchClass ->
                    if (finalStep.fromPitchClass == pitchClass) finalStep.toPitchClass else pitchClass
                }
                VoiceLeadingSuspensionCandidate(
                    pathway = pathway,
                    suspensionNode = suspensionNode,
                    suspendedPitchClasses = suspended,
                    preparedPitchClasses = suspended.filter { it in source },
                    resolutions = resolutions,
                    explainingReadings = suspensionNode.readings.filter { reading ->
                        universe.suspendedPitchClasses(reading).any { it in suspended }
                    },
                )
            }
    }
}
