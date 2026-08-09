package com.mecon.theory.writing

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TrackId
import kotlin.math.abs

/** A writable notation lane; it may contain chord events and is not an analytical voice. */
data class NotationLaneSpec(
    val id: TrackId,
    val order: Int,
    val lowest: Pitch,
    val highest: Pitch,
)

data class NotationEventSpan(
    val laneId: TrackId,
    val onset: Fraction,
    val duration: Fraction,
    val pitches: List<Pitch>,
) {
    val end: Fraction get() = onset + duration
}

data class PendingNotationNote(
    val onset: Fraction,
    val duration: Fraction,
    val pitch: Pitch,
) {
    val end: Fraction get() = onset + duration
}

/**
 * Selects a notation lane for one newly entered pitch without moving existing material.
 *
 * An exact onset/duration match is preferred because the edit can become another notehead in the
 * same chord event. Otherwise only lanes whose existing pitched spans do not overlap the request
 * are considered. The deterministic cost favours the lane's configured range and nearby melodic
 * continuity; it never treats the result as an analytical voice assignment.
 */
object AutomaticNotationAssigner {
    fun assign(
        lanes: List<NotationLaneSpec>,
        events: List<NotationEventSpan>,
        pending: PendingNotationNote,
    ): TrackId? {
        if (lanes.isEmpty()) return null
        val eventsByLane = events.groupBy(NotationEventSpan::laneId)
        val candidates = lanes.filter { lane ->
            val laneEvents = eventsByLane[lane.id].orEmpty()
            val exactChord = laneEvents.any { event ->
                event.onset == pending.onset &&
                    event.duration == pending.duration &&
                    event.pitches.isNotEmpty()
            }
            exactChord || laneEvents.none { event ->
                event.pitches.isNotEmpty() &&
                    event.onset < pending.end &&
                    event.end > pending.onset
            }
        }
        // A run drawn one cell at a time is one melodic gesture. Once it has a directly adjacent
        // lane, keep using that lane instead of allowing tiny range-cost differences to scatter the
        // cells across analytical voices. At a chord onset there is no preceding adjacency, so all
        // non-overlapping default lanes remain candidates and their configured ranges decide the
        // upper/lower-staff placement independently of note-entry order.
        val continuous = candidates.filter { lane ->
            eventsByLane[lane.id].orEmpty().any { event ->
                event.pitches.isNotEmpty() && event.end == pending.onset
            }
        }
        return continuous.ifEmpty { candidates }.minWithOrNull(
            compareBy<NotationLaneSpec> { lane ->
                laneCost(lane, eventsByLane[lane.id].orEmpty(), pending)
            }.thenBy(NotationLaneSpec::order)
                .thenBy { it.id.value }
        )?.id
    }

    private fun laneCost(
        lane: NotationLaneSpec,
        events: List<NotationEventSpan>,
        pending: PendingNotationNote,
    ): Int {
        val midi = pending.pitch.midiNumber
        val rangePenalty = when {
            midi < lane.lowest.midiNumber -> lane.lowest.midiNumber - midi
            midi > lane.highest.midiNumber -> midi - lane.highest.midiNumber
            else -> 0
        }
        val neighbours = events.asSequence()
            .filter { it.pitches.isNotEmpty() }
            .filter { it.end <= pending.onset || it.onset >= pending.end }
            .flatMap { it.pitches.asSequence() }
            .map { abs(it.midiNumber - midi) }
            .minOrNull()
            ?: 0
        return rangePenalty * 10_000 + neighbours * 100
    }
}
