package com.mecon.core.engine.edit

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeRange
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.core.engine.edit.StaffTrackOps.staffForVoice

/** Moving selected notes/chord pitches between notation voices, including across staves. */
internal object VoiceMoveEngine {

    /**
     * Move selected notes to [NoteEditEngine.VoiceMoveTarget.targetVoiceNumber]. A partial chord
     * selection is split: selected pitches are removed from the source event and added to the
     * target voice, while the unselected pitches remain in the original voice.
     */
    fun moveVoices(runtime: RuntimeScore, targets: List<NoteEditEngine.VoiceMoveTarget>): NoteEditEngine.VoiceMoveResult? {
        if (targets.isEmpty()) return null
        moveWholeEventPermutation(runtime, targets)?.let { return it }
        var current = runtime
        val intervals = ArrayList<TimeRange>()
        val moved = ArrayList<NoteEditEngine.VoiceMovedEvent>()

        for (target in targets) {
            val sourceVoice = current.getVoiceTrack(target.voiceTrackId) ?: continue
            val sourceStaff = staffForVoice(current, sourceVoice.id) ?: continue
            val targetStaff = target.targetStaffId
                ?.let(current.staffTracks::get)
                ?: sourceStaff
            if (
                sourceStaff.id == targetStaff.id &&
                sourceVoice.voiceNumber == target.targetVoiceNumber
            ) continue
            val event = sourceVoice.events.toList().firstOrNull { it.id == target.eventId } ?: continue
            if (event.isRest || event.pitches.isEmpty()) continue

            val selected = (target.pitchIndices ?: event.pitches.indices.toSet())
                .filter { it in event.pitches.indices }
                .toSet()
            if (selected.isEmpty()) continue

            val movedPitches = selected.sorted().map { index ->
                MovedPitch(
                    pitch = event.pitches[index],
                    trailingTie = event.ties.any { it.pitchIndex == index },
                )
            }
            val deleteResult = NoteDeletion.delete(
                current,
                NoteEditEngine.Deletion(
                    voiceTrackId = sourceVoice.id,
                    eventId = event.id,
                    pitchIndices = if (selected.size >= event.pitches.size) null else selected,
                ),
            ) ?: continue
            current = deleteResult.score
            intervals.add(deleteResult.editInterval)

            for (movedPitch in movedPitches) {
                // Moving into another voice keeps its historical merge semantics: if that target
                // already sounds the pitch, select the existing head instead of manufacturing a
                // duplicate. Direct chord entry is intentionally different and may create unisons.
                if (targetContainsSoundingPitch(
                        current,
                        targetStaff.id,
                        target.targetVoiceNumber,
                        event.onset,
                        event.duration,
                        movedPitch.pitch,
                    )
                ) {
                    recordExistingMovedPitch(
                        runtime = current,
                        staffTrackId = targetStaff.id,
                        voiceNumber = target.targetVoiceNumber,
                        onset = event.onset,
                        duration = event.duration,
                        pitch = movedPitch.pitch,
                        moved = moved,
                    )
                    continue
                }
                val insertion = NoteEditEngine.Insertion(
                    voiceTrackId = sourceVoice.id,
                    start = event.onset,
                    duration = event.duration,
                    pitch = movedPitch.pitch,
                    isRest = false,
                    trailingTie = movedPitch.trailingTie,
                    staffTrackId = targetStaff.id,
                    voiceNumber = target.targetVoiceNumber,
                )
                val insertResult = NoteInsertion.insert(
                    current,
                    insertion,
                    NoteEditEngine.InsertionPolicy.CHORDAL,
                )
                if (insertResult != null) {
                    current = insertResult.score
                    intervals.add(insertResult.editInterval)
                    recordMovedPitch(current, insertResult.insertedEventId, movedPitch.pitch, moved)
                } else {
                    recordExistingMovedPitch(
                        runtime = current,
                        staffTrackId = targetStaff.id,
                        voiceNumber = target.targetVoiceNumber,
                        onset = event.onset,
                        duration = event.duration,
                        pitch = movedPitch.pitch,
                        moved = moved,
                    )
                }
            }
        }

        return if (moved.isEmpty()) null
        else NoteEditEngine.VoiceMoveResult(current, intervals.distinct(), moved.coalesced())
    }

    /**
     * A reciprocal swap/cycle must be resolved from one immutable snapshot. Applying its legs in
     * sequence would let the first insertion clear the event that a later leg still needs to move.
     * Keep the historical split/merge path for ordinary moves, but route whole-event permutations
     * through the identity-preserving batch reassignment primitive.
     */
    private fun moveWholeEventPermutation(
        runtime: RuntimeScore,
        targets: List<NoteEditEngine.VoiceMoveTarget>,
    ): NoteEditEngine.VoiceMoveResult? {
        if (targets.size < 2 || targets.any { it.pitchIndices != null }) return null
        val resolved = targets.map { target ->
            val sourceVoice = runtime.getVoiceTrack(target.voiceTrackId) ?: return null
            val sourceStaff = staffForVoice(runtime, sourceVoice.id) ?: return null
            val targetStaff = target.targetStaffId?.let(runtime.staffTracks::get) ?: sourceStaff
            val targetVoice = targetStaff.voiceTracks.firstOrNull {
                it.voiceNumber == target.targetVoiceNumber
            } ?: return null
            val event = sourceVoice.events.toList().firstOrNull {
                it.id == target.eventId && !it.isRest && it.pitches.isNotEmpty()
            } ?: return null
            Triple(target, targetVoice.id, event)
        }
        val sourceVoiceIds = resolved.mapTo(linkedSetOf()) { it.first.voiceTrackId }
        val targetVoiceIds = resolved.mapTo(linkedSetOf()) { it.second }
        if (sourceVoiceIds.size != targets.size || targetVoiceIds != sourceVoiceIds) return null

        val assignment = resolved.associate { (target, targetVoiceId, _) ->
            target.eventId to targetVoiceId
        }
        val reassigned = VoiceReassignmentEngine.reassign(runtime, assignment) ?: return null
        if (reassigned.score === runtime) return null
        return NoteEditEngine.VoiceMoveResult(
            score = reassigned.score,
            intervals = resolved.map { (_, _, event) -> TimeRange(event.onset, event.endTime) }.distinct(),
            movedEvents = resolved.map { (target, _, _) ->
                NoteEditEngine.VoiceMovedEvent(target.eventId, null)
            },
        )
    }

    private data class MovedPitch(val pitch: Pitch, val trailingTie: Boolean)

    private fun targetContainsSoundingPitch(
        runtime: RuntimeScore,
        staffTrackId: TrackId,
        voiceNumber: Int,
        onset: TimeCode,
        duration: Duration,
        pitch: Pitch,
    ): Boolean {
        val voice = runtime.staffTracks[staffTrackId]
            ?.voiceTracks
            ?.firstOrNull { it.voiceNumber == voiceNumber }
            ?: return false
        return voice.eventsAt(onset).any { event ->
            !event.isRest && event.duration == duration &&
                event.pitches.any { it.midiNumber == pitch.midiNumber }
        }
    }

    private fun recordMovedPitch(
        runtime: RuntimeScore,
        eventId: EventId?,
        pitch: Pitch,
        moved: MutableList<NoteEditEngine.VoiceMovedEvent>,
    ) {
        val id = eventId ?: return
        val event = runtime.voiceTracks.values
            .asSequence()
            .flatMap { it.events.toList().asSequence() }
            .firstOrNull { it.id == id }
            ?: return
        val index = event.pitches.indexOf(pitch).takeIf { it >= 0 } ?: return
        moved.add(NoteEditEngine.VoiceMovedEvent(id, setOf(index)))
    }

    private fun recordExistingMovedPitch(
        runtime: RuntimeScore,
        staffTrackId: TrackId,
        voiceNumber: Int,
        onset: TimeCode,
        duration: Duration,
        pitch: Pitch,
        moved: MutableList<NoteEditEngine.VoiceMovedEvent>,
    ) {
        val voice = runtime.staffTracks[staffTrackId]
            ?.voiceTracks
            ?.firstOrNull { it.voiceNumber == voiceNumber }
            ?: return
        val event = voice.eventsAt(onset)
            .firstOrNull {
                !it.isRest && it.duration == duration &&
                    it.pitches.any { candidate -> candidate.midiNumber == pitch.midiNumber }
            }
            ?: return
        val index = event.pitches.indexOfFirst { it.midiNumber == pitch.midiNumber }
            .takeIf { it >= 0 } ?: return
        moved.add(NoteEditEngine.VoiceMovedEvent(event.id, setOf(index)))
    }

    /**
     * Coalesce adjacent per-pitch move selections by event. Moving multiple chord notes through
     * [NoteInsertion.insert] records one selection per pitch; the UI wants a single selection set
     * with all moved noteheads selected.
     */
    private fun List<NoteEditEngine.VoiceMovedEvent>.coalesced(): List<NoteEditEngine.VoiceMovedEvent> {
        val byEvent = LinkedHashMap<EventId, MutableSet<Int>?>()
        for (event in this) {
            val existing = byEvent[event.eventId]
            when {
                event.pitchIndices == null -> byEvent[event.eventId] = null
                existing == null && event.eventId in byEvent -> {}
                existing == null -> byEvent[event.eventId] = event.pitchIndices.toMutableSet()
                else -> existing += event.pitchIndices
            }
        }
        return byEvent.map { (eventId, indices) -> NoteEditEngine.VoiceMovedEvent(eventId, indices) }
    }
}
