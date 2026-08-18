package com.mecon.theory.voiceleading

import com.mecon.api.primitive.PitchClass
import com.mecon.theory.BuiltInChordDefinitions
import com.mecon.theory.ChordDefinition
import com.mecon.theory.ChordDefinitionId
import com.mecon.theory.ChordQuality
import com.mecon.theory.schoenberg.SchoenbergRootMotionDirection
import kotlin.jvm.JvmInline

/** Stable extension point for pitch-class voice-leading families. */
@JvmInline
value class VoiceLeadingChordFamilyId(val value: String) {
    init { require(value.isNotBlank()) { "A voice-leading family id must not be blank" } }
}

/**
 * A pitch-class-set family and its traversal limit.
 *
 * The engine only depends on [ChordDefinition], so future tertian or non-tertian definitions can
 * join by registering another family rather than changing the traversal algorithm.
 */
data class VoiceLeadingChordFamily(
    val id: VoiceLeadingChordFamilyId,
    val definitions: List<ChordDefinition>,
    val maximumTransformations: Int,
) {
    init {
        require(definitions.isNotEmpty()) { "A voice-leading family must contain definitions" }
        require(definitions.map { it.id }.distinct().size == definitions.size) {
            "Voice-leading definition ids must be unique within a family"
        }
        require(definitions.map { it.members.size }.distinct().size == 1) {
            "Every definition in a voice-leading family must have the same cardinality"
        }
        require(maximumTransformations in 1..definitions.first().members.size) {
            "The traversal limit must fit the family's chord cardinality"
        }
    }

    val cardinality: Int get() = definitions.first().members.size
}

object StandardVoiceLeadingChordFamilies {
    val TRIADS = VoiceLeadingChordFamily(
        id = VoiceLeadingChordFamilyId("tertian.triad"),
        definitions = definitions(
            ChordQuality.MAJOR,
            ChordQuality.MINOR,
            ChordQuality.AUGMENTED,
            ChordQuality.DIMINISHED,
        ),
        maximumTransformations = 2,
    )

    val SEVENTHS = VoiceLeadingChordFamily(
        id = VoiceLeadingChordFamilyId("tertian.seventh"),
        definitions = definitions(
            ChordQuality.MAJOR7,
            ChordQuality.MINOR7,
            ChordQuality.DOMINANT7,
            ChordQuality.DIMINISHED7,
            ChordQuality.HALF_DIMINISHED7,
            ChordQuality.MINOR_MAJOR7,
            ChordQuality.AUGMENTED7,
            ChordQuality.DOMINANT7_FLAT5,
            ChordQuality.DOMINANT7_SHARP5,
        ),
        maximumTransformations = 3,
    )

    val all: List<VoiceLeadingChordFamily> = listOf(TRIADS, SEVENTHS)

    fun matching(pitchClasses: Collection<Int>): VoiceLeadingChordFamily? =
        all.firstOrNull { family ->
            pitchClasses.distinct().size == family.cardinality &&
                VoiceLeadingTransformations.recognize(pitchClasses, family).isNotEmpty()
        }

    private fun definitions(vararg qualities: ChordQuality): List<ChordDefinition> =
        qualities.map(BuiltInChordDefinitions::forQuality)
}

data class VoiceLeadingChordReading(
    val definitionId: ChordDefinitionId,
    val quality: ChordQuality,
    val rootPitchClass: Int,
)

/** One ordered move of one original chord member. */
data class VoiceLeadingToneMove(
    val order: Int,
    val sourceToneIndex: Int,
    val fromPitchClass: Int,
    val toPitchClass: Int,
    val semitones: Int,
) {
    init {
        require(order > 0)
        require(sourceToneIndex >= 0)
        require(fromPitchClass in 0..11 && toPitchClass in 0..11)
        require(semitones in ALLOWED_SEMITONE_MOVES)
        require((fromPitchClass + semitones).mod(12) == toPitchClass)
    }
}

enum class VoiceLeadingParallelRisk {
    PARALLEL_FIFTH,
    PARALLEL_OCTAVE_IF_MOVED_TONE_IS_DOUBLED,
}

data class VoiceLeadingTransformPath(
    val moves: List<VoiceLeadingToneMove>,
    val parallelRisks: Set<VoiceLeadingParallelRisk>,
    /** True only for a three-step path whose three distinct tones all move in one direction. */
    val threeTonesSameDirection: Boolean,
) {
    init {
        require(moves.isNotEmpty())
        require(moves.map { it.order } == (1..moves.size).toList())
        require(moves.map { it.sourceToneIndex }.distinct().size == moves.size) {
            "A transformation path may move each original tone at most once"
        }
    }
}

enum class SchoenbergChromaticRootMotion {
    RISING,
    DESCENDING,
    SUPERSTRONG,
    REPEATED,
    UNCLASSIFIED,
}

/** Root reading chosen from all symmetric source/target readings for the most stable connection. */
data class VoiceLeadingRootConnection(
    val sourceRootPitchClass: Int,
    val targetRootPitchClass: Int,
    val directedSemitones: Int,
    val motion: SchoenbergChromaticRootMotion,
)

data class VoiceLeadingTransformCandidate(
    val targetPitchClasses: List<Int>,
    val readings: List<VoiceLeadingChordReading>,
    val transformationCount: Int,
    /** All shortest, identity-preserving paths to this target, in deterministic order. */
    val paths: List<VoiceLeadingTransformPath>,
    val rootConnection: VoiceLeadingRootConnection,
)

object VoiceLeadingTransformations {
    fun recognize(
        pitchClasses: Collection<Int>,
        family: VoiceLeadingChordFamily,
    ): List<VoiceLeadingChordReading> {
        val observed = pitchClasses.map { it.mod(12) }.distinct().sorted()
        if (observed.size != family.cardinality) return emptyList()
        val observedSet = observed.toSet()
        return family.definitions.flatMap { definition ->
            (0..11).mapNotNull { root ->
                val defined = definition.members
                    .mapTo(linkedSetOf()) { member -> (root + member.semitones).mod(12) }
                if (defined == observedSet) {
                    VoiceLeadingChordReading(
                        definitionId = definition.id,
                        quality = definition.compatibilityQuality,
                        rootPitchClass = root,
                    )
                } else null
            }
        }.distinct().sortedWith(compareBy({ it.quality.ordinal }, { it.rootPitchClass }))
    }

    fun enumerate(
        sourcePitchClasses: Collection<Int>,
        family: VoiceLeadingChordFamily,
    ): List<VoiceLeadingTransformCandidate> {
        val source = sourcePitchClasses.map { it.mod(12) }.distinct().sorted()
        require(source.size == family.cardinality) {
            "Source cardinality ${source.size} does not match family cardinality ${family.cardinality}"
        }
        val sourceReadings = recognize(source, family)
        require(sourceReadings.isNotEmpty()) { "Source pitch classes are not part of ${family.id.value}" }

        var frontier = listOf(PathState(source, emptySet(), emptyList()))
        val shortestDepthByTarget = mutableMapOf<List<Int>, Int>()
        val pathsByTarget = linkedMapOf<List<Int>, MutableList<VoiceLeadingTransformPath>>()
        val readingsByTarget = mutableMapOf<List<Int>, List<VoiceLeadingChordReading>>()

        for (depth in 1..family.maximumTransformations) {
            val next = mutableListOf<PathState>()
            frontier.forEach { state ->
                state.tones.indices.filterNot { it in state.usedSourceToneIndices }.forEach { toneIndex ->
                    ALLOWED_SEMITONE_MOVES.forEach { semitones ->
                        val moved = state.tones.toMutableList()
                        val from = moved[toneIndex]
                        val to = (from + semitones).mod(12)
                        moved[toneIndex] = to
                        if (moved.distinct().size != moved.size) return@forEach
                        val target = moved.sorted()
                        val readings = recognize(target, family)
                        // Every edge in the graph must itself end at a recognized chord.
                        if (readings.isEmpty()) return@forEach
                        val move = VoiceLeadingToneMove(
                            order = depth,
                            sourceToneIndex = toneIndex,
                            fromPitchClass = from,
                            toPitchClass = to,
                            semitones = semitones,
                        )
                        val nextState = PathState(
                            tones = moved,
                            usedSourceToneIndices = state.usedSourceToneIndices + toneIndex,
                            moves = state.moves + move,
                        )
                        next += nextState
                        if (target == source) return@forEach
                        val knownDepth = shortestDepthByTarget[target]
                        if (knownDepth == null || depth < knownDepth) {
                            shortestDepthByTarget[target] = depth
                            pathsByTarget[target] = mutableListOf(path(source, nextState.moves))
                            readingsByTarget[target] = readings
                        } else if (depth == knownDepth) {
                            pathsByTarget.getValue(target) += path(source, nextState.moves)
                        }
                    }
                }
            }
            frontier = next.distinctBy { state -> state.identity() }
        }

        return pathsByTarget.map { (target, rawPaths) ->
            val readings = readingsByTarget.getValue(target)
            val paths = rawPaths.distinctBy(::pathIdentity).sortedBy(::pathIdentity)
            VoiceLeadingTransformCandidate(
                targetPitchClasses = target,
                readings = readings,
                transformationCount = shortestDepthByTarget.getValue(target),
                paths = paths,
                rootConnection = mostStableRootConnection(sourceReadings, readings),
            )
        }.sortedWith(
            compareBy<VoiceLeadingTransformCandidate>(
                VoiceLeadingTransformCandidate::transformationCount,
                { rootMotionRank(it.rootConnection.motion) },
                { it.rootConnection.directedSemitones },
                { it.targetPitchClasses.joinToString(",") },
            )
        )
    }

    fun classifyRootMotion(sourceRootPitchClass: Int, targetRootPitchClass: Int): VoiceLeadingRootConnection {
        require(sourceRootPitchClass in 0..11 && targetRootPitchClass in 0..11)
        val delta = (targetRootPitchClass - sourceRootPitchClass).mod(12)
        val motion = when (SchoenbergRootMotionDirection.fromChromaticSemitoneDelta(delta)) {
            SchoenbergRootMotionDirection.RISING -> SchoenbergChromaticRootMotion.RISING
            SchoenbergRootMotionDirection.DESCENDING -> SchoenbergChromaticRootMotion.DESCENDING
            SchoenbergRootMotionDirection.SUPERSTRONG -> SchoenbergChromaticRootMotion.SUPERSTRONG
            SchoenbergRootMotionDirection.REPEATED -> SchoenbergChromaticRootMotion.REPEATED
            null -> SchoenbergChromaticRootMotion.UNCLASSIFIED
        }
        return VoiceLeadingRootConnection(sourceRootPitchClass, targetRootPitchClass, delta, motion)
    }

    private fun mostStableRootConnection(
        sourceReadings: List<VoiceLeadingChordReading>,
        targetReadings: List<VoiceLeadingChordReading>,
    ): VoiceLeadingRootConnection = sourceReadings.flatMap { source ->
        targetReadings.map { target ->
            classifyRootMotion(source.rootPitchClass, target.rootPitchClass)
        }
    }.minWith(
        compareBy<VoiceLeadingRootConnection>(
            { rootMotionRank(it.motion) },
            { intervalPreference(it.directedSemitones) },
            VoiceLeadingRootConnection::sourceRootPitchClass,
            VoiceLeadingRootConnection::targetRootPitchClass,
        )
    )

    private fun path(source: List<Int>, moves: List<VoiceLeadingToneMove>): VoiceLeadingTransformPath {
        val sameDirectionGroups = moves.groupBy { it.semitones.compareTo(0) }.values
            .filter { it.size >= 2 }
        val fifthRisk = sameDirectionGroups.any { group ->
            group.indices.any { first ->
                (first + 1 until group.size).any { second ->
                    val firstMove = group[first]
                    val secondMove = group[second]
                    isPerfectFifth(
                        source[firstMove.sourceToneIndex],
                        source[secondMove.sourceToneIndex],
                    ) && isPerfectFifth(firstMove.toPitchClass, secondMove.toPitchClass)
                }
            }
        }
        val risks = buildSet {
            if (fifthRisk) add(VoiceLeadingParallelRisk.PARALLEL_FIFTH)
            // Pitch-class paths cannot know which chord member is doubled in a later realization.
            if (sameDirectionGroups.isNotEmpty()) {
                add(VoiceLeadingParallelRisk.PARALLEL_OCTAVE_IF_MOVED_TONE_IS_DOUBLED)
            }
        }
        return VoiceLeadingTransformPath(
            moves = moves,
            parallelRisks = risks,
            threeTonesSameDirection = moves.size == 3 &&
                moves.map { it.semitones.compareTo(0) }.distinct().size == 1,
        )
    }

    private fun isPerfectFifth(first: Int, second: Int): Boolean =
        (first - second).mod(12) in setOf(5, 7)

    private fun pathIdentity(path: VoiceLeadingTransformPath): String = path.moves.joinToString("|") {
        "${it.sourceToneIndex}:${it.fromPitchClass}:${it.toPitchClass}:${it.semitones}"
    }

    private fun PathState.identity(): String =
        tones.joinToString(",") + "/" + usedSourceToneIndices.sorted().joinToString(",") + "/" +
            moves.joinToString(",") { it.sourceToneIndex.toString() }

    private fun rootMotionRank(motion: SchoenbergChromaticRootMotion): Int = when (motion) {
        SchoenbergChromaticRootMotion.RISING -> 0
        SchoenbergChromaticRootMotion.DESCENDING -> 1
        SchoenbergChromaticRootMotion.SUPERSTRONG -> 2
        SchoenbergChromaticRootMotion.REPEATED -> 3
        SchoenbergChromaticRootMotion.UNCLASSIFIED -> 4
    }

    private fun intervalPreference(delta: Int): Int = when (delta) {
        5 -> 0 // up a fourth / down a fifth
        8, 9 -> 1 // down a third
        3, 4 -> 2 // up a third
        7 -> 3 // down a fourth
        1, 2, 10, 11 -> 4
        0 -> 5
        else -> 6
    }

    private data class PathState(
        /** Current pitch classes in original-tone identity order, not pitch order. */
        val tones: List<Int>,
        val usedSourceToneIndices: Set<Int>,
        val moves: List<VoiceLeadingToneMove>,
    )
}

private val ALLOWED_SEMITONE_MOVES = listOf(-2, -1, 1, 2)
