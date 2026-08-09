package com.mecon.core.engine.edit

import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.TimeIndexedList
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.runtime.tracks.RuntimePitchTrack
import com.mecon.api.runtime.tracks.RuntimeStaffGroup
import com.mecon.api.runtime.tracks.RuntimeStaffGroupMember
import com.mecon.api.runtime.tracks.RuntimeStaffTrack
import com.mecon.api.runtime.tracks.RuntimeVoiceTrack

/**
 * Resolves and rebuilds the voice/staff track hierarchy of a [RuntimeScore]. Every note-edit
 * feature ends by handing its new event list to [replaceVoice], which keeps the pitch track, the
 * staff track map, and [RuntimeScore.staffGroups] (which embeds its own staff copies — see
 * [replaceStaffsInGroup]) consistent with each other.
 */
internal object StaffTrackOps {

    data class ResolvedVoice(val score: RuntimeScore, val voice: RuntimeVoiceTrack)

    fun resolveInsertionVoice(runtime: RuntimeScore, insertion: NoteEditEngine.Insertion): ResolvedVoice? {
        val staffId = insertion.staffTrackId
        if (staffId != null) return ensureVoiceTrack(runtime, staffId, insertion.voiceNumber)
        val voice = runtime.getVoiceTrack(insertion.voiceTrackId) ?: return null
        return ResolvedVoice(runtime, voice)
    }

    fun staffForVoice(runtime: RuntimeScore, voiceTrackId: TrackId): RuntimeStaffTrack? =
        runtime.staffTracks.values.firstOrNull { staff -> staff.voiceTracks.any { it.id == voiceTrackId } }

    fun ensureVoiceTrack(
        runtime: RuntimeScore,
        staffTrackId: TrackId,
        voiceNumber: Int,
    ): ResolvedVoice? {
        val staff = runtime.staffTracks[staffTrackId] ?: return null
        staff.voiceTracks.firstOrNull { it.voiceNumber == voiceNumber }?.let {
            return ResolvedVoice(runtime, it)
        }

        val pitchTrack = RuntimePitchTrack.create("${staff.name} Voice $voiceNumber Notes")
        val voice = RuntimeVoiceTrack.create(
            name = "Voice $voiceNumber",
            voiceNumber = voiceNumber,
            pitchTrack = pitchTrack,
        )
        val updatedStaff = staff.copy(
            voiceTracks = (staff.voiceTracks + voice).sortedBy { it.voiceNumber }
        )
        val newStaffTracks = runtime.staffTracks + (staff.id to updatedStaff)
        val newRuntime = runtime.copy(
            pitchTracks = runtime.pitchTracks + (pitchTrack.id to pitchTrack),
            voiceTracks = runtime.voiceTracks + (voice.id to voice),
            staffTracks = newStaffTracks,
            staffGroups = runtime.staffGroups.map { replaceStaffsInGroup(it, newStaffTracks) },
        )
        return ResolvedVoice(newRuntime, voice)
    }

    /**
     * Rebuild [runtime] with [voice]'s events replaced by [newEvents], keeping the score's
     * track hierarchy (pitch track, voice track lists on staves) consistent. Each voice event
     * owns its own pitch event, so the voice's pitch track is rebuilt from those (unrelated pitch
     * events, if any share the track, are preserved).
     */
    fun replaceVoice(
        runtime: RuntimeScore,
        voice: RuntimeVoiceTrack,
        newEvents: List<RuntimeVoiceEvent>,
    ): RuntimeScore {
        val oldVoicePitchIds = voice.events.toList().map { it.pitchEvent.id }.toSet()
        val newPitchEvents = newEvents.map { it.pitchEvent }
        val pitchTrack = runtime.getPitchTrack(voice.pitchTrackId)

        val rebuiltPitchTrack: RuntimePitchTrack? = pitchTrack?.let { pt ->
            val unrelated = pt.events.toList().filter { it.id !in oldVoicePitchIds }
            pt.copy(events = TimeIndexedList.of(unrelated + newPitchEvents))
        }

        val newVoiceTrack = voice.copy(
            events = TimeIndexedList.of(newEvents),
            pitchTrack = rebuiltPitchTrack ?: voice.pitchTrack,
        )

        val newVoiceTracks = runtime.voiceTracks + (voice.id to newVoiceTrack)
        val newPitchTracks = if (rebuiltPitchTrack != null)
            runtime.pitchTracks + (voice.pitchTrackId to rebuiltPitchTrack)
        else runtime.pitchTracks

        val newStaffTracks = runtime.staffTracks.mapValues { (_, staff) ->
            val updated = staff.voiceTracks.map { v -> newVoiceTracks[v.id] ?: v }
            if (updated != staff.voiceTracks) staff.copy(voiceTracks = updated) else staff
        }

        // [staffGroups] embeds its own RuntimeStaffTrack copies (it is the source [orderedStaffs]
        // reads). Re-point each embedded staff at the rebuilt track so the canonical map and the
        // grouped view stay consistent — otherwise orderedStaffs() yields pre-edit voice events.
        val newStaffGroups = runtime.staffGroups.map { replaceStaffsInGroup(it, newStaffTracks) }

        return runtime.copy(
            voiceTracks = newVoiceTracks,
            pitchTracks = newPitchTracks,
            staffTracks = newStaffTracks,
            staffGroups = newStaffGroups,
        )
    }

    /** Replace a staff-level property while keeping the canonical map and embedded group copies aligned. */
    fun replaceStaff(runtime: RuntimeScore, staff: RuntimeStaffTrack): RuntimeScore {
        val newStaffTracks = runtime.staffTracks + (staff.id to staff)
        return runtime.copy(
            staffTracks = newStaffTracks,
            staffGroups = runtime.staffGroups.map { replaceStaffsInGroup(it, newStaffTracks) },
        )
    }

    /**
     * Recursively replace each embedded staff with its rebuilt counterpart from [staffTracks].
     * Shared with [ClefEditEngine], the only other engine that mutates staff tracks directly.
     */
    fun replaceStaffsInGroup(
        group: RuntimeStaffGroup,
        staffTracks: Map<TrackId, RuntimeStaffTrack>,
    ): RuntimeStaffGroup = group.copy(
        members = group.members.map { member ->
            when (member) {
                is RuntimeStaffGroupMember.Staff ->
                    RuntimeStaffGroupMember.Staff(staffTracks[member.staff.id] ?: member.staff)
                is RuntimeStaffGroupMember.Group ->
                    RuntimeStaffGroupMember.Group(replaceStaffsInGroup(member.group, staffTracks))
            }
        }
    )
}
