package com.mecon.theory.voiceleading

/**
 * The single mutable-free source of truth for every voice-leading tension weight.
 *
 * Enumeration, ranking and presentation all read this policy; platforms must not keep their own
 * constants. Weight calibration is qualitative for now (see the open questions in
 * `docs/theory/voice-leading-pathways.md`), which is exactly why it is one named, versioned object.
 */
data class VoiceLeadingTensionPolicy(
    val id: String,
    /** Roughness weight per interval class 1..6; index 0 is unused. */
    val intervalClassWeights: List<Double>,
    val dissonanceWeight: Double,
    val instabilityWeight: Double,
    val readingWeight: Double,
    val breadthWeight: Double,
    val rootMotionWeight: Double,
    val resolutionDropWeight: Double,
    val rootMotionScores: Map<SchoenbergChromaticRootMotion, Double>,
) {
    init {
        require(id.isNotBlank())
        require(intervalClassWeights.size == 7) { "Interval class weights are indexed 1..6" }
        require(intervalClassWeights.drop(1).all { it in 0.0..1.0 })
        require(dissonanceWeight + instabilityWeight > 0.0)
        require(readingWeight + breadthWeight > 0.0)
        require(SchoenbergChromaticRootMotion.entries.all { it in rootMotionScores })
    }

    companion object {
        /**
         * Ordering of the default weights: major/minor < augmented < suspended < dominant seventh
         * < diminished. ic1/ic2 and the tritone carry the tension; thirds, fourths and fifths
         * barely register.
         */
        val DEFAULT = VoiceLeadingTensionPolicy(
            id = "voice-leading.tension.v1",
            intervalClassWeights = listOf(0.0, 1.0, 0.55, 0.12, 0.12, 0.05, 0.70),
            dissonanceWeight = 0.6,
            instabilityWeight = 0.4,
            readingWeight = 0.5,
            breadthWeight = 0.5,
            rootMotionWeight = 0.5,
            resolutionDropWeight = 0.5,
            rootMotionScores = mapOf(
                SchoenbergChromaticRootMotion.RISING to 1.0,
                SchoenbergChromaticRootMotion.SUPERSTRONG to 0.7,
                SchoenbergChromaticRootMotion.DESCENDING to 0.4,
                SchoenbergChromaticRootMotion.UNCLASSIFIED to 0.3,
                SchoenbergChromaticRootMotion.REPEATED to 0.1,
            ),
        )
    }
}

data class VoiceLeadingNodeMetrics(
    val stepIndex: Int,
    val dissonance: Double,
    val instability: Double,
    val tension: Double,
    val readingCount: Int,
    /** Distinct stable pitch-class sets reachable with one further single-tone move. */
    val resolutionBreadth: Int,
    val ambiguity: Double,
)

/**
 * The tension curve of one ordered pathway — the quantity users actually shop for.
 *
 * [arc] separates "a passing chord went by" from "something clashed and then resolved";
 * [centroid] separates front-loaded (suspension) from back-loaded (anticipation) shapes.
 */
data class VoiceLeadingTensionProfile(
    val policyId: String,
    val nodes: List<VoiceLeadingNodeMetrics>,
    val peakTension: Double,
    val arc: Double,
    val centroid: Double,
    val resolutionDrop: Double,
    val monotonicRelease: Boolean,
) {
    val sourceTension: Double get() = nodes.first().tension
    val targetTension: Double get() = nodes.last().tension
}

object VoiceLeadingTension {

    /** Mean weighted interval-class content of a pitch-class set, in [0, 1]. */
    fun dissonance(
        pitchClasses: Collection<Int>,
        policy: VoiceLeadingTensionPolicy = VoiceLeadingTensionPolicy.DEFAULT,
    ): Double {
        val tones = pitchClasses.map { it.mod(12) }.distinct()
        if (tones.size < 2) return 0.0
        var total = 0.0
        var pairs = 0
        for (first in tones.indices) {
            for (second in first + 1 until tones.size) {
                val delta = (tones[first] - tones[second]).mod(12)
                val intervalClass = minOf(delta, 12 - delta)
                total += policy.intervalClassWeights[intervalClass]
                pairs++
            }
        }
        return total / pairs
    }

    /**
     * Tension of any vertical, including ones the universe cannot name.
     *
     * An unnameable sonority is treated as maximally unstable: 9-8 and 7-6 suspension verticals are
     * exactly such sonorities, and they are the most in need of resolution, not the least.
     */
    fun tension(
        pitchClasses: Collection<Int>,
        universe: VoiceLeadingUniverse,
        policy: VoiceLeadingTensionPolicy = VoiceLeadingTensionPolicy.DEFAULT,
    ): Double = tension(
        dissonance = dissonance(pitchClasses, policy),
        instability = if (universe.stabilityOfSet(pitchClasses) == VoiceLeadingStability.STABLE) 0.0 else 1.0,
        policy = policy,
    )

    private fun tension(
        dissonance: Double,
        instability: Double,
        policy: VoiceLeadingTensionPolicy,
    ): Double = normalized(
        policy.dissonanceWeight * dissonance + policy.instabilityWeight * instability,
        policy.dissonanceWeight + policy.instabilityWeight,
    )

    fun nodeMetrics(
        node: VoiceLeadingPathNode,
        universe: VoiceLeadingUniverse,
        policy: VoiceLeadingTensionPolicy = VoiceLeadingTensionPolicy.DEFAULT,
    ): VoiceLeadingNodeMetrics {
        val dissonance = dissonance(node.pitchClasses, policy)
        val instability = if (node.stability == VoiceLeadingStability.TRANSITIONAL) 1.0 else 0.0
        val breadth = resolutionBreadth(node.pitchClasses, universe)
        return VoiceLeadingNodeMetrics(
            stepIndex = node.stepIndex,
            dissonance = dissonance,
            instability = instability,
            tension = tension(dissonance, instability, policy),
            readingCount = node.readings.size,
            resolutionBreadth = breadth,
            ambiguity = normalized(
                policy.readingWeight * diminishing(node.readings.size) +
                    policy.breadthWeight * diminishing(breadth),
                policy.readingWeight + policy.breadthWeight,
            ),
        )
    }

    /**
     * How many different stable chords the node could still go to in one move.
     *
     * A wide breadth is the graph-theoretic face of "we cannot yet tell where this is heading";
     * a suspension that can only fall one way has breadth close to one.
     */
    fun resolutionBreadth(
        pitchClasses: Collection<Int>,
        universe: VoiceLeadingUniverse,
    ): Int {
        val tones = pitchClasses.map { it.mod(12) }.distinct()
        val ownMask = pitchClassMask(tones)
        val reachable = mutableSetOf<Int>()
        tones.forEach { tone ->
            ALLOWED_SEMITONE_MOVES.forEach { semitones ->
                val moved = (tone + semitones).mod(12)
                if (moved in tones) return@forEach
                val mask = ownMask and (1 shl tone).inv() or (1 shl moved)
                if (mask == ownMask) return@forEach
                val readings = universe.readingsForMask(mask)
                if (readings.any {
                        universe.stabilityOf(it.definitionId) == VoiceLeadingStability.STABLE
                    }
                ) reachable += mask
            }
        }
        return reachable.size
    }

    fun profile(
        pathway: VoiceLeadingPathway,
        universe: VoiceLeadingUniverse,
        policy: VoiceLeadingTensionPolicy = VoiceLeadingTensionPolicy.DEFAULT,
    ): VoiceLeadingTensionProfile {
        val nodes = pathway.nodes.map { nodeMetrics(it, universe, policy) }
        val intermediates = if (nodes.size <= 2) emptyList() else nodes.subList(1, nodes.size - 1)
        val peak = intermediates.maxOfOrNull { it.tension } ?: 0.0
        val endpoints = maxOf(nodes.first().tension, nodes.last().tension)
        val weightSum = intermediates.sumOf { it.tension }
        val centroid = if (intermediates.isEmpty() || weightSum <= 0.0) 0.5 else {
            intermediates.sumOf { it.stepIndex.toDouble() / pathway.stepCount * it.tension } /
                weightSum
        }
        val peakIndex = nodes.indices.maxByOrNull { nodes[it].tension } ?: 0
        return VoiceLeadingTensionProfile(
            policyId = policy.id,
            nodes = nodes,
            peakTension = peak,
            arc = peak - endpoints,
            centroid = centroid,
            resolutionDrop = nodes[nodes.size - 2].tension - nodes.last().tension,
            monotonicRelease = (peakIndex until nodes.size - 1).all {
                nodes[it].tension >= nodes[it + 1].tension
            },
        )
    }

    /**
     * Ranking scalar for a connection: Schoenberg root-progression strength plus the pull of the
     * final resolution. This is why `I -> V` is a weak root progression yet an unmistakable
     * cadence when it runs through a suspension.
     */
    fun drive(
        pathway: VoiceLeadingPathway,
        profile: VoiceLeadingTensionProfile,
        policy: VoiceLeadingTensionPolicy = VoiceLeadingTensionPolicy.DEFAULT,
    ): Double {
        val connection = VoiceLeadingTransformations.mostStableRootConnection(
            sourceReadings = pathway.sourceNode.readings,
            targetReadings = pathway.targetNode.readings,
        )
        val rootScore = policy.rootMotionScores.getValue(connection.motion)
        return normalized(
            policy.rootMotionWeight * rootScore +
                policy.resolutionDropWeight * profile.resolutionDrop.coerceAtLeast(0.0),
            policy.rootMotionWeight + policy.resolutionDropWeight,
        )
    }

    private fun normalized(weighted: Double, weightSum: Double): Double =
        (weighted / weightSum).coerceIn(0.0, 1.0)

    /** 1 -> 0, 2 -> 0.5, 3 -> 0.67 ... saturating measure of "how many readings". */
    private fun diminishing(count: Int): Double =
        if (count <= 1) 0.0 else 1.0 - 1.0 / count
}
