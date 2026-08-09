package com.mecon.core.engine.edit

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.core.engine.edit.EditGeometry.absolute
import com.mecon.core.engine.edit.StaffTrackOps.replaceVoice
import com.mecon.core.engine.edit.VoiceSpanEditing.fillGaps

/**
 * Batch event reassignment across arbitrary voice tracks/staves.
 *
 * Unlike [VoiceMoveEngine], this operation preserves event and pitch-event identity. It rebuilds
 * rests after all sounding events have been assigned, making it suitable for automatic voice
 * allocation and atomic voice swaps.
 */
internal object VoiceReassignmentEngine {
    fun reassign(
        runtime: RuntimeScore,
        requested: Map<EventId, TrackId>,
    ): NoteEditEngine.VoiceReassignmentResult? {
        if (requested.isEmpty()) {
            return NoteEditEngine.VoiceReassignmentResult(runtime, emptyMap())
        }
        if (requested.values.any { it !in runtime.voiceTracks }) return null

        val sourceByEvent = buildMap {
            runtime.voiceTracks.forEach { (voiceId, voice) ->
                voice.events.toList().filterNot { it.isRest }.forEach { put(it.id, voiceId) }
            }
        }
        if (requested.keys.any { it !in sourceByEvent }) return null
        val effective = requested.filter { (eventId, targetVoiceId) ->
            sourceByEvent[eventId] != targetVoiceId
        }
        if (effective.isEmpty()) {
            return NoteEditEngine.VoiceReassignmentResult(runtime, requested)
        }

        val involvedVoices = buildSet {
            effective.forEach { (eventId, targetVoiceId) ->
                add(sourceByEvent.getValue(eventId))
                add(targetVoiceId)
            }
        }
        val assignedByVoice = involvedVoices.associateWith { mutableListOf<RuntimeVoiceEvent>() }
        runtime.voiceTracks.forEach { (sourceVoiceId, voice) ->
            voice.events.toList().filterNot { it.isRest }.forEach { event ->
                val targetVoiceId = effective[event.id] ?: sourceVoiceId
                if (targetVoiceId in involvedVoices) {
                    assignedByVoice.getValue(targetVoiceId) += event
                }
            }
        }

        for (events in assignedByVoice.values) {
            val sorted = events.sortedBy { absolute(runtime, it.onset) }
            if (sorted.zipWithNext().any { (left, right) ->
                    absolute(runtime, left.endTime) > absolute(runtime, right.onset)
                }
            ) return null
        }

        val lastMeasure = runtime.measures.maxOfOrNull { it.value.number } ?: 1
        var current = runtime
        involvedVoices.forEach { voiceId ->
            val voice = current.voiceTracks[voiceId] ?: return null
            val sounding = assignedByVoice.getValue(voiceId)
            val rebuilt = fillGaps(current, sounding, 1, lastMeasure)
            current = replaceVoice(current, voice, rebuilt)
        }
        return NoteEditEngine.VoiceReassignmentResult(
            score = current,
            voiceByEventId = requested,
        )
    }
}
