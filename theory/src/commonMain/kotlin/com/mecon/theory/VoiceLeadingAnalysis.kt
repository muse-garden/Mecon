package com.mecon.theory

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import kotlin.math.abs

enum class VoiceMotionDirection {
    STATIONARY,
    UP,
    DOWN,
}

enum class VoicePairMotionKind {
    HOLD,
    OBLIQUE,
    CONTRARY,
    SIMILAR,
    PARALLEL,
}

enum class VerticalArrangement {
    DENSE,
    OPEN,
}

data class FixedVoiceVerticality(
    val time: TimeCode,
    val notes: List<FixedVoiceScoreEvent>,
)

data class VoiceCrossing(
    val lowerBefore: FixedVoiceScoreEvent,
    val higherBefore: FixedVoiceScoreEvent,
    val higherAfter: FixedVoiceScoreEvent,
    val lowerAfter: FixedVoiceScoreEvent,
)

data class VoicePairSpacing(
    val upper: FixedVoiceScoreEvent,
    val lower: FixedVoiceScoreEvent,
    val semitones: Int,
)

data class VoicePairMotion(
    val firstBefore: FixedVoiceScoreEvent,
    val secondBefore: FixedVoiceScoreEvent,
    val firstAfter: FixedVoiceScoreEvent,
    val secondAfter: FixedVoiceScoreEvent,
    val firstDirection: VoiceMotionDirection,
    val secondDirection: VoiceMotionDirection,
    val kind: VoicePairMotionKind,
    val beforeInterval: SpelledInterval,
    val afterInterval: SpelledInterval,
)

data class VoiceRange(
    val lowest: Pitch,
    val highest: Pitch,
) {
    init {
        require(lowest.midiNumber <= highest.midiNumber) { "Voice range lowest pitch must not exceed highest pitch" }
    }

    operator fun contains(pitch: Pitch): Boolean =
        pitch.midiNumber in lowest.midiNumber..highest.midiNumber
}

data class VoiceRangeProfile(
    val ranges: Map<FixedVoiceRole, VoiceRange>,
) {
    fun rangeFor(role: FixedVoiceRole?): VoiceRange? =
        role?.let { ranges[it] }

    companion object {
        fun humanFourPart(
            soprano: VoiceRange = VoiceRange(Pitch.fromName("C4"), Pitch.fromName("G5")),
            alto: VoiceRange = VoiceRange(Pitch.fromName("G3"), Pitch.fromName("D5")),
            tenor: VoiceRange = VoiceRange(Pitch.fromName("C3"), Pitch.fromName("E4")),
            lowVoice: VoiceRange = VoiceRange(Pitch.fromName("E2"), Pitch.fromName("C4")),
        ): VoiceRangeProfile =
            VoiceRangeProfile(
                mapOf(
                    FixedVoiceRole.SOPRANO to soprano,
                    FixedVoiceRole.ALTO to alto,
                    FixedVoiceRole.TENOR to tenor,
                    FixedVoiceRole.BASS to lowVoice,
                    FixedVoiceRole.BARITONE to lowVoice,
                )
            )
    }
}

object VoiceLeadingAnalysis {
    fun verticalities(score: FixedVoiceScore): List<FixedVoiceVerticality> {
        val times = score.eventsByVoice.values
            .flatten()
            .flatMap { listOf(it.onset, it.endTime) }
            .distinct()
            .sorted()
        return times.mapNotNull { time ->
            val notes = score.notesSoundingAt(time).orderedLike(score)
            if (notes.isEmpty()) null else FixedVoiceVerticality(time, notes)
        }
    }

    fun arrangementOf(notes: List<FixedVoiceScoreEvent>): VerticalArrangement {
        val upperVoiceNotes = notes
            .filterNot { it.voice.role.isLowVoiceRole() }
            .sortedWith(fixedVoiceEventComparator())
        return if (adjacentPairs(upperVoiceNotes).any { (upper, lower) ->
                abs(upper.requiredPitch().midiNumber - lower.requiredPitch().midiNumber) > PERFECT_FOURTH_SEMITONES
            }
        ) {
            VerticalArrangement.OPEN
        } else {
            VerticalArrangement.DENSE
        }
    }

    fun arrangements(score: FixedVoiceScore): List<Pair<FixedVoiceVerticality, VerticalArrangement>> =
        verticalities(score).map { it to arrangementOf(it.notes) }

    fun crossingsBetween(
        previous: FixedVoiceVerticality,
        current: FixedVoiceVerticality,
    ): List<VoiceCrossing> {
        val previousByVoice = previous.notes.associateBy { it.voice.id }
        val currentByVoice = current.notes.associateBy { it.voice.id }
        val sharedVoiceIds = previousByVoice.keys.intersect(currentByVoice.keys).toList()
        return sharedVoiceIds
            .flatMapIndexed { index, leftVoiceId ->
                sharedVoiceIds.drop(index + 1).mapNotNull { rightVoiceId ->
                    val leftBefore = previousByVoice.getValue(leftVoiceId)
                    val rightBefore = previousByVoice.getValue(rightVoiceId)
                    val leftAfter = currentByVoice.getValue(leftVoiceId)
                    val rightAfter = currentByVoice.getValue(rightVoiceId)
                    val before = leftBefore.requiredPitch().midiNumber.compareTo(rightBefore.requiredPitch().midiNumber)
                    val after = leftAfter.requiredPitch().midiNumber.compareTo(rightAfter.requiredPitch().midiNumber)
                    if (before * after >= 0) {
                        null
                    } else if (before < 0) {
                        VoiceCrossing(
                            lowerBefore = leftBefore,
                            higherBefore = rightBefore,
                            higherAfter = leftAfter,
                            lowerAfter = rightAfter,
                        )
                    } else {
                        VoiceCrossing(
                            lowerBefore = rightBefore,
                            higherBefore = leftBefore,
                            higherAfter = rightAfter,
                            lowerAfter = leftAfter,
                        )
                    }
                }
            }
    }

    fun crossings(score: FixedVoiceScore): List<VoiceCrossing> =
        verticalities(score).zipWithNext().flatMap { (previous, current) ->
            crossingsBetween(previous, current)
        }

    fun pairMotionsBetween(
        previous: FixedVoiceVerticality,
        current: FixedVoiceVerticality,
    ): List<VoicePairMotion> {
        val previousByVoice = previous.notes.associateBy { it.voice.id }
        val currentByVoice = current.notes.associateBy { it.voice.id }
        val sharedVoiceIds = previousByVoice.keys.intersect(currentByVoice.keys).toList()
        return sharedVoiceIds.flatMapIndexed { index, leftVoiceId ->
            sharedVoiceIds.drop(index + 1).map { rightVoiceId ->
                val leftBefore = previousByVoice.getValue(leftVoiceId)
                val rightBefore = previousByVoice.getValue(rightVoiceId)
                val leftAfter = currentByVoice.getValue(leftVoiceId)
                val rightAfter = currentByVoice.getValue(rightVoiceId)
                val leftDirection = motionDirection(leftBefore.requiredPitch(), leftAfter.requiredPitch())
                val rightDirection = motionDirection(rightBefore.requiredPitch(), rightAfter.requiredPitch())
                VoicePairMotion(
                    firstBefore = leftBefore,
                    secondBefore = rightBefore,
                    firstAfter = leftAfter,
                    secondAfter = rightAfter,
                    firstDirection = leftDirection,
                    secondDirection = rightDirection,
                    kind = pairMotionKind(
                        leftBefore = leftBefore.requiredPitch(),
                        leftAfter = leftAfter.requiredPitch(),
                        rightBefore = rightBefore.requiredPitch(),
                        rightAfter = rightAfter.requiredPitch(),
                        leftDirection = leftDirection,
                        rightDirection = rightDirection,
                    ),
                    beforeInterval = SpelledInterval.between(leftBefore.requiredPitch(), rightBefore.requiredPitch()),
                    afterInterval = SpelledInterval.between(leftAfter.requiredPitch(), rightAfter.requiredPitch()),
                )
            }
        }
    }

    fun pairMotions(score: FixedVoiceScore): List<VoicePairMotion> =
        transitions(score).flatMap { (previous, current) ->
            pairMotionsBetween(previous, current)
        }

    fun transitions(score: FixedVoiceScore): List<FixedVoiceTransition> =
        verticalities(score).zipWithNext().map { (previous, current) ->
            FixedVoiceTransition(previous, current)
        }

    fun transitionsTouching(
        score: FixedVoiceScore,
        eventIds: Set<EventId>,
    ): List<FixedVoiceTransition> {
        if (eventIds.isEmpty()) return transitions(score)
        return transitions(score).filter { transition ->
            eventIds.any { transition.containsAnchor(it) }
        }
    }

    fun outerBoundaryCrossings(verticality: FixedVoiceVerticality): List<VoicePairSpacing> {
        val soprano = verticality.notes.firstOrNull { it.voice.role == FixedVoiceRole.SOPRANO }
        val lowVoice = verticality.notes.firstOrNull { it.voice.role.isLowVoiceRole() }
        return buildList {
            if (soprano != null) {
                val sopranoPitch = soprano.requiredPitch()
                verticality.notes
                    .filter { it.voice.id != soprano.voice.id }
                    .filter { it.requiredPitch().midiNumber > sopranoPitch.midiNumber }
                    .forEach { offender ->
                        add(
                            VoicePairSpacing(
                                upper = offender,
                                lower = soprano,
                                semitones = offender.requiredPitch().midiNumber - sopranoPitch.midiNumber,
                            )
                        )
                    }
            }
            if (lowVoice != null) {
                val lowPitch = lowVoice.requiredPitch()
                verticality.notes
                    .filter { it.voice.id != lowVoice.voice.id }
                    .filter { it.requiredPitch().midiNumber < lowPitch.midiNumber }
                    .forEach { offender ->
                        add(
                            VoicePairSpacing(
                                upper = lowVoice,
                                lower = offender,
                                semitones = lowPitch.midiNumber - offender.requiredPitch().midiNumber,
                            )
                        )
                    }
            }
        }
    }

    fun nonLowAdjacentSpacingViolations(
        verticality: FixedVoiceVerticality,
        maxSemitones: Int = OCTAVE_SEMITONES,
    ): List<VoicePairSpacing> =
        adjacentPairs(
            verticality.notes
                .filterNot { it.voice.role.isLowVoiceRole() }
                .sortedWith(fixedVoiceEventComparator())
        ).mapNotNull { (upper, lower) ->
            val semitones = abs(upper.requiredPitch().midiNumber - lower.requiredPitch().midiNumber)
            if (semitones > maxSemitones) VoicePairSpacing(upper, lower, semitones) else null
        }

    internal fun FixedVoiceRole?.isLowVoiceRole(): Boolean =
        this == FixedVoiceRole.BASS || this == FixedVoiceRole.BARITONE

    private fun List<FixedVoiceScoreEvent>.orderedLike(score: FixedVoiceScore): List<FixedVoiceScoreEvent> {
        val voiceOrder = score.voices.mapIndexed { index, voice -> voice.id to index }.toMap()
        return sortedWith(compareBy({ voiceOrder[it.voice.id] ?: Int.MAX_VALUE }, { it.onset }))
    }

    private fun fixedVoiceEventComparator(): Comparator<FixedVoiceScoreEvent> =
        compareBy({ it.voice.staffIndex }, { it.voice.voiceIndexOnStaff }, { it.onset })

    private fun motionDirection(from: Pitch, to: Pitch): VoiceMotionDirection =
        when {
            to.midiNumber > from.midiNumber -> VoiceMotionDirection.UP
            to.midiNumber < from.midiNumber -> VoiceMotionDirection.DOWN
            else -> VoiceMotionDirection.STATIONARY
        }

    private fun pairMotionKind(
        leftBefore: Pitch,
        leftAfter: Pitch,
        rightBefore: Pitch,
        rightAfter: Pitch,
        leftDirection: VoiceMotionDirection,
        rightDirection: VoiceMotionDirection,
    ): VoicePairMotionKind =
        when {
            leftDirection == VoiceMotionDirection.STATIONARY &&
                rightDirection == VoiceMotionDirection.STATIONARY -> VoicePairMotionKind.HOLD
            leftDirection == VoiceMotionDirection.STATIONARY ||
                rightDirection == VoiceMotionDirection.STATIONARY -> VoicePairMotionKind.OBLIQUE
            leftDirection != rightDirection -> VoicePairMotionKind.CONTRARY
            abs(leftAfter.midiNumber - leftBefore.midiNumber) ==
                abs(rightAfter.midiNumber - rightBefore.midiNumber) -> VoicePairMotionKind.PARALLEL
            else -> VoicePairMotionKind.SIMILAR
        }

    private fun FixedVoiceScoreEvent.requiredPitch(): Pitch =
        pitch ?: error("Expected note event ${id} to have a pitch")

    private fun <T> adjacentPairs(items: List<T>): List<Pair<T, T>> =
        items.zipWithNext()

    private const val PERFECT_FOURTH_SEMITONES = 5
    private const val OCTAVE_SEMITONES = 12
}
