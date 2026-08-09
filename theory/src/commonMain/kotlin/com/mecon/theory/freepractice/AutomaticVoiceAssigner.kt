package com.mecon.theory.freepractice

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TrackId

data class VoiceAssignmentNote(
    val eventId: EventId,
    val onset: Fraction,
    val duration: Fraction,
    val pitch: Pitch,
    val currentVoiceId: TrackId,
    val source: VoiceAssignmentSource,
)

data class AutomaticVoiceAssignmentResult(
    val voiceByEventId: Map<EventId, TrackId>,
    val unassignedEventIds: Set<EventId>,
)

/**
 * Chronological, order-preserving assignment for sparse monodic textures.
 *
 * At each onset, sustaining voices and manual notes are fixed. Automatic notes are sorted high to
 * low and matched to an ordered subset of the remaining voices. Exhaustive subset search is tiny
 * for the supported 3-6 voices and gives a deterministic minimum for that onset.
 */
object AutomaticVoiceAssigner {
    fun assign(
        voices: List<WorkspaceVoiceSpec>,
        notes: List<VoiceAssignmentNote>,
    ): AutomaticVoiceAssignmentResult {
        val orderedVoices = voices.sortedBy { it.order }
        val orderByVoice = orderedVoices.mapIndexed { index, voice -> voice.id to index }.toMap()
        val voiceByEvent = linkedMapOf<EventId, TrackId>()
        val unassigned = linkedSetOf<EventId>()
        val previousPitch = mutableMapOf<TrackId, Int>()
        val activeUntil = mutableMapOf<TrackId, Fraction>()
        val activePitch = mutableMapOf<TrackId, Int>()

        notes.groupBy { it.onset }.entries.sortedBy { it.key }.forEach { (onset, group) ->
            activeUntil.entries
                .filter { (_, end) -> end <= onset }
                .map { it.key }
                .forEach { voiceId ->
                    activeUntil.remove(voiceId)
                    activePitch.remove(voiceId)
                }

            val manual = group.filter { it.source == VoiceAssignmentSource.MANUAL }
            manual.forEach { note ->
                voiceByEvent[note.eventId] = note.currentVoiceId
                activeUntil[note.currentVoiceId] = onset + note.duration
                activePitch[note.currentVoiceId] = note.pitch.midiNumber
            }

            val automatic = group
                .filter { it.source == VoiceAssignmentSource.AUTOMATIC }
                .sortedWith(
                    compareByDescending<VoiceAssignmentNote> { it.pitch.midiNumber }
                        .thenBy { it.eventId.value }
                )
            val available = orderedVoices.filter { it.id !in activeUntil }
            val best = orderedSubsets(available, automatic.size)
                .mapNotNull { candidates ->
                    val pairs = automatic.zip(candidates)
                    val sounding = buildList {
                        activePitch.forEach { (voiceId, pitch) ->
                            orderByVoice[voiceId]?.let { add(Triple(it, pitch, voiceId)) }
                        }
                        pairs.forEach { (note, voice) ->
                            add(Triple(orderByVoice.getValue(voice.id), note.pitch.midiNumber, voice.id))
                        }
                    }.sortedBy { it.first }
                    if (sounding.zipWithNext().any { (upper, lower) -> upper.second < lower.second }) {
                        return@mapNotNull null
                    }
                    val cost = pairs.sumOf { (note, voice) ->
                        val midi = note.pitch.midiNumber
                        val movement = previousPitch[voice.id]?.let { kotlin.math.abs(midi - it) } ?: 0
                        val rangePenalty = when {
                            midi < voice.lowest.midiNumber -> voice.lowest.midiNumber - midi
                            midi > voice.highest.midiNumber -> midi - voice.highest.midiNumber
                            else -> 0
                        }
                        movement * 100 + rangePenalty * 10_000 +
                            if (note.currentVoiceId == voice.id) 0 else 1
                    }
                    cost to pairs
                }
                .minWithOrNull(
                    compareBy<Pair<Int, List<Pair<VoiceAssignmentNote, WorkspaceVoiceSpec>>>> {
                        it.first
                    }.thenBy { (_, pairs) ->
                        pairs.joinToString("|") { it.second.id.value }
                    }
                )
                ?.second

            if (best == null) {
                unassigned += automatic.map { it.eventId }
            } else {
                best.forEach { (note, voice) ->
                    voiceByEvent[note.eventId] = voice.id
                    activeUntil[voice.id] = onset + note.duration
                    activePitch[voice.id] = note.pitch.midiNumber
                }
            }
            group.forEach { note ->
                voiceByEvent[note.eventId]?.let { voiceId ->
                    previousPitch[voiceId] = note.pitch.midiNumber
                }
            }
        }
        return AutomaticVoiceAssignmentResult(voiceByEvent, unassigned)
    }

    private fun <T> orderedSubsets(values: List<T>, count: Int): List<List<T>> {
        if (count == 0) return listOf(emptyList())
        if (count > values.size) return emptyList()
        return buildList {
            fun visit(start: Int, chosen: MutableList<T>) {
                if (chosen.size == count) {
                    add(chosen.toList())
                    return
                }
                for (index in start..values.size - (count - chosen.size)) {
                    chosen += values[index]
                    visit(index + 1, chosen)
                    chosen.removeAt(chosen.lastIndex)
                }
            }
            visit(0, mutableListOf())
        }
    }
}
