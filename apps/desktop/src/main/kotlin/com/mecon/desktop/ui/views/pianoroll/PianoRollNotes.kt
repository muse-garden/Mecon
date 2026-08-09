package com.mecon.desktop.ui.views.pianoroll

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.audio.model.MidiNoteOffEvent
import com.mecon.audio.model.MidiNoteOnEvent
import com.mecon.audio.model.MidiScore

/**
 * A single sounding note for the piano roll: rectangle in (pitch, time) space.
 *
 * Built from the playback-authoritative [MidiScore] so the visual rectangles
 * match what the player schedules — including the offset/shortening applied
 * to grace-note groups by [com.mecon.audio.converter.ScoreToMidiConverter].
 */
data class PianoRollNote(
    val midi: Int,
    val onsetTicks: Long,
    val durationTicks: Long,
    val isGrace: Boolean,
    /** PitchEvent identity used by the playback timeline. */
    val sourceEventId: EventId?,
    /** VoiceEvent identities used by score selection. */
    val voiceEventIds: Set<EventId>,
    /** Notated onset used for tonal-context lookup. */
    val scoreOnset: TimeCode?,
    /** Scale degree(s) resolved by the asynchronous analysis frame builder. */
    val degreeLabel: String? = null,
)

/**
 * Pair note-on/note-off events from each MIDI track by `(sourceEventId, midi)`
 * to produce one [PianoRollNote] per sounding note. Grace-note status is read
 * from the source pitch event's [com.mecon.api.primitive.TimeCode.grace] field.
 */
fun buildPianoRollNotes(score: RuntimeScore, midiScore: MidiScore): List<PianoRollNote> {
    val pitchEvents = score.getAllPitchEvents()
    val pitchEventById = pitchEvents.associateBy { it.id }
    val voiceEventsByPitchId = score.getAllVoiceEvents().groupBy { it.pitchEvent.id }
    val graceSourceIds = pitchEvents
        .filter { it.onset.grace != null }
        .mapTo(HashSet()) { it.id }

    val notes = mutableListOf<PianoRollNote>()
    for (track in midiScore.tracks) {
        val openByKey = HashMap<Pair<Any?, Int>, MidiNoteOnEvent>()
        for (event in track.events) {
            when (event) {
                is MidiNoteOnEvent -> {
                    openByKey[event.sourceEventId to event.midiNumber] = event
                }
                is MidiNoteOffEvent -> {
                    val key = event.sourceEventId to event.midiNumber
                    val on = openByKey.remove(key) ?: continue
                    notes.add(
                        PianoRollNote(
                            midi = on.midiNumber,
                            onsetTicks = on.absoluteTicks,
                            durationTicks = (event.absoluteTicks - on.absoluteTicks).coerceAtLeast(1L),
                            isGrace = on.sourceEventId in graceSourceIds,
                            sourceEventId = on.sourceEventId,
                            voiceEventIds = on.sourceEventId
                                ?.let(voiceEventsByPitchId::get)
                                .orEmpty()
                                .mapTo(linkedSetOf()) { it.id },
                            scoreOnset = on.sourceEventId?.let(pitchEventById::get)?.onset,
                        )
                    )
                }
                else -> Unit
            }
        }
    }
    return notes
}
