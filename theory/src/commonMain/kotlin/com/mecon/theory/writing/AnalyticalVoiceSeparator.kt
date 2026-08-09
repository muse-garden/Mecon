package com.mecon.theory.writing

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import kotlin.math.abs

/** Stable identity of one notehead inside a possibly chordal source event. */
data class SourceNoteheadId(
    val eventId: EventId,
    val pitchIndex: Int,
)

data class AnalyticalNoteSpan(
    val source: SourceNoteheadId,
    val onset: Fraction,
    val duration: Fraction,
    val pitch: Pitch,
) {
    val end: Fraction get() = onset + duration
}

data class AnalyticalVoiceSeparation(
    /** Voice index is ordered from high to low and exists only in the analysis frame. */
    val voiceByNotehead: Map<SourceNoteheadId, Int>,
    val unassigned: Set<SourceNoteheadId>,
)

/**
 * Expands chord events into independent noteheads and connects them into ordered monodic paths.
 *
 * Source notation lanes are deliberately absent from this API. The result is an immutable analysis
 * projection and never rewrites the user's staff organization.
 */
object AnalyticalVoiceSeparator {
    fun separate(
        notes: List<AnalyticalNoteSpan>,
        voiceCount: Int,
    ): AnalyticalVoiceSeparation {
        require(voiceCount > 0) { "Analytical voice count must be positive" }
        val assignments = linkedMapOf<SourceNoteheadId, Int>()
        val unassigned = linkedSetOf<SourceNoteheadId>()
        val activeUntil = mutableMapOf<Int, Fraction>()
        val activePitch = mutableMapOf<Int, Int>()
        val previousPitch = mutableMapOf<Int, Int>()

        notes.groupBy(AnalyticalNoteSpan::onset).entries.sortedBy { it.key }.forEach { (onset, group) ->
            activeUntil.entries
                .filter { it.value <= onset }
                .map { it.key }
                .forEach { voice ->
                    activeUntil.remove(voice)
                    activePitch.remove(voice)
                }
            val orderedNotes = group.sortedWith(
                compareByDescending<AnalyticalNoteSpan> { it.pitch.midiNumber }
                    .thenBy { it.source.eventId.value }
                    .thenBy { it.source.pitchIndex }
            )
            val available = (0 until voiceCount).filterNot(activeUntil::containsKey)
            val best = orderedSubsets(available, orderedNotes.size)
                .mapNotNull { voices ->
                    val pairs = orderedNotes.zip(voices)
                    val sounding = buildList {
                        activePitch.forEach { (voice, pitch) -> add(voice to pitch) }
                        pairs.forEach { (note, voice) -> add(voice to note.pitch.midiNumber) }
                    }.sortedBy { it.first }
                    if (sounding.zipWithNext().any { (upper, lower) -> upper.second < lower.second }) {
                        return@mapNotNull null
                    }
                    val cost = pairs.sumOf { (note, voice) ->
                        previousPitch[voice]?.let { abs(it - note.pitch.midiNumber) } ?: 0
                    }
                    cost to pairs
                }
                .minWithOrNull(
                    compareBy<Pair<Int, List<Pair<AnalyticalNoteSpan, Int>>>> { it.first }
                        .thenBy { (_, pairs) -> pairs.joinToString(",") { it.second.toString() } }
                )
                ?.second
            if (best == null) {
                unassigned += orderedNotes.map(AnalyticalNoteSpan::source)
            } else {
                best.forEach { (note, voice) ->
                    assignments[note.source] = voice
                    activeUntil[voice] = note.end
                    activePitch[voice] = note.pitch.midiNumber
                    previousPitch[voice] = note.pitch.midiNumber
                }
            }
        }
        return AnalyticalVoiceSeparation(assignments, unassigned)
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
