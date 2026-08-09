package com.mecon.core.analysis

import com.mecon.api.primitive.Pitch
import com.mecon.api.storage.NoteRef
import com.mecon.api.storage.StorageNoteLink
import com.mecon.api.storage.StorageReduction
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.ReductionLayerKind
import com.mecon.api.storage.events.StoragePitchEvent
import com.mecon.api.storage.events.StorageVoiceEvent

/** Keeps reduction -> content line -> written score links aligned after either side is edited. */
object ReductionSyncEngine {
    fun synchronize(previous: StorageScore, candidate: StorageScore): StorageScore {
        var result = synchronizeReductions(previous, candidate)
        result = synchronizeOrchestration(previous, result)
        // The first orchestration pass may have changed a content line after a written-score edit.
        result = synchronizeReductions(previous, result)
        // The second reduction pass may have changed a content line after a reduction edit.
        return synchronizeOrchestration(previous, result)
    }

    private fun synchronizeReductions(previous: StorageScore, candidate: StorageScore): StorageScore {
        var result = candidate
        candidate.reductions.forEach { candidateReduction ->
            val previousReduction = previous.getReduction(candidateReduction.id) ?: return@forEach
            var nested = candidateReduction.notationScore
            candidateReduction.links.forEach { link ->
                val oldSource = note(previous, link.source)
                val newSource = note(candidate, link.source)
                val oldTarget = note(previousReduction.notationScore, link.target)
                val newTarget = note(candidateReduction.notationScore, link.target)
                val sourceChanged = oldSource != newSource
                val targetChanged = oldTarget != newTarget
                when {
                    sourceChanged && !targetChanged && newSource != null -> {
                        nested = updateNote(
                            nested,
                            link.target,
                            pitch = newSource.pitch.transpose(-link.octaveShift * 12),
                            onset = newSource.event.onset,
                            duration = newSource.event.duration,
                        )
                    }
                    targetChanged && !sourceChanged && newTarget != null -> {
                        result = updateNote(
                            result,
                            link.source,
                            pitch = newTarget.pitch.transpose(link.octaveShift * 12),
                            onset = newTarget.event.onset,
                            duration = newTarget.event.duration,
                        )
                    }
                }
            }
            if (nested != candidateReduction.notationScore) {
                result = result.copy(reductions = result.reductions.map { reduction ->
                    if (reduction.id == candidateReduction.id) {
                        reduction.updateLayerScore(ReductionLayerKind.NOTATION, nested)
                    } else {
                        reduction
                    }
                })
            }
        }
        return result
    }

    private fun synchronizeOrchestration(previous: StorageScore, candidate: StorageScore): StorageScore {
        val previousOrchestration = previous.orchestration ?: return candidate
        val candidateOrchestration = candidate.orchestration ?: return candidate
        var result = candidate
        candidateOrchestration.links.forEach { link ->
            val oldLine = note(previous, link.source)
            val newLine = note(candidate, link.source)
            val oldWritten = note(previous, link.target)
            val newWritten = note(candidate, link.target)
            val lineChanged = oldLine != newLine
            val writtenChanged = oldWritten != newWritten
            when {
                lineChanged && !writtenChanged && newLine != null -> {
                    result = updateNote(
                        result,
                        link.target,
                        pitch = newLine.pitch.transpose(-link.octaveShift * 12),
                        onset = newLine.event.onset,
                        duration = newLine.event.duration,
                    )
                }
                writtenChanged && !lineChanged && newWritten != null -> {
                    result = updateNote(
                        result,
                        link.source,
                        pitch = newWritten.pitch.transpose(link.octaveShift * 12),
                        onset = newWritten.event.onset,
                        duration = newWritten.event.duration,
                    )
                }
            }
        }
        return result
    }

    private data class NoteSnapshot(
        val event: StorageVoiceEvent,
        val pitch: Pitch,
    )

    private fun note(score: StorageScore, ref: NoteRef): NoteSnapshot? {
        score.voiceTracks.values.forEach { voice ->
            val event = voice.events.firstOrNull { it.id == ref.eventId } ?: return@forEach
            val pitchEvent = score.findPitchEvent(event.pitchEventId) ?: return@forEach
            val pitch = pitchEvent.pitches.getOrNull(ref.pitchIndex) ?: return@forEach
            return NoteSnapshot(event, pitch)
        }
        return null
    }

    private fun updateNote(
        score: StorageScore,
        ref: NoteRef,
        pitch: Pitch,
        onset: com.mecon.api.primitive.TimeCode,
        duration: com.mecon.api.primitive.Duration,
    ): StorageScore {
        val voiceEntry = score.voiceTracks.entries.firstOrNull { (_, voice) -> voice.events.any { it.id == ref.eventId } }
            ?: return score
        val voice = voiceEntry.value
        val event = voice.events.firstOrNull { it.id == ref.eventId } ?: return score
        val pitchEvent = score.findPitchEvent(event.pitchEventId) ?: return score
        if (ref.pitchIndex !in pitchEvent.pitches.indices) return score
        val pitches = pitchEvent.pitches.toMutableList().also { it[ref.pitchIndex] = pitch }
        return score
            .updatePitchTrack(voice.pitchTrackId) { track ->
                track.copy(events = track.events.map { item ->
                    if (item.id == pitchEvent.id) item.copy(onset = onset, pitches = pitches) else item
                })
            }
            .updateVoiceTrack(voice.id) { track ->
                track.copy(events = track.events.map { item ->
                    if (item.id == event.id) item.copy(onset = onset, duration = duration) else item
                })
            }
    }
}
