package com.mecon.theory

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.runtime.orderedStaffs

enum class FixedVoiceRole {
    SOPRANO,
    ALTO,
    TENOR,
    BASS,
    BARITONE,
    INNER,
    OUTER,
}

enum class FourPartKeyboardDistribution {
    TREBLE_3_BASS_1,
    TREBLE_2_BASS_2,
}

data class FixedVoiceStaffLayout(
    val staffTrackId: TrackId,
    val voiceRoles: List<FixedVoiceRole?>,
) {
    init {
        require(voiceRoles.isNotEmpty()) { "A fixed-voice staff must declare at least one voice" }
    }
}

data class FixedVoiceLayout(
    val staves: List<FixedVoiceStaffLayout>,
) {
    init {
        require(staves.isNotEmpty()) { "A fixed-voice layout must declare at least one staff" }
        require(staves.map { it.staffTrackId }.toSet().size == staves.size) {
            "A fixed-voice layout cannot contain the same staff twice"
        }
    }

    companion object {
        fun fourPartKeyboard(
            score: RuntimeScore,
            distribution: FourPartKeyboardDistribution = FourPartKeyboardDistribution.TREBLE_2_BASS_2,
        ): FixedVoiceLayout {
            val orderedStaffs = score.orderedStaffs()
            require(orderedStaffs.size >= 2) { "Four-part keyboard layout requires at least two staves" }
            val (trebleRoles, bassRoles) = when (distribution) {
                FourPartKeyboardDistribution.TREBLE_3_BASS_1 ->
                    listOf(FixedVoiceRole.SOPRANO, FixedVoiceRole.ALTO, FixedVoiceRole.TENOR) to
                        listOf(FixedVoiceRole.BASS)
                FourPartKeyboardDistribution.TREBLE_2_BASS_2 ->
                    listOf(FixedVoiceRole.SOPRANO, FixedVoiceRole.ALTO) to
                        listOf(FixedVoiceRole.TENOR, FixedVoiceRole.BASS)
            }
            return FixedVoiceLayout(
                listOf(
                    FixedVoiceStaffLayout(orderedStaffs[0].id, trebleRoles),
                    FixedVoiceStaffLayout(orderedStaffs[1].id, bassRoles),
                )
            )
        }
    }
}

data class FixedVoice(
    val id: TrackId,
    val staffTrackId: TrackId,
    val staffIndex: Int,
    val voiceIndexOnStaff: Int,
    val role: FixedVoiceRole?,
)

data class FixedVoiceScoreEvent(
    val voice: FixedVoice,
    val event: RuntimeVoiceEvent,
    val pitch: Pitch?,
) {
    val id: EventId get() = event.id
    val onset: TimeCode get() = event.onset
    val endTime: TimeCode get() = event.endTime
    val isRest: Boolean get() = pitch == null
}

enum class FixedVoiceDiagnosticCode {
    STAFF_NOT_FOUND,
    STAFF_VOICE_COUNT_MISMATCH,
    CHORD_IN_MONOPHONIC_VOICE,
}

data class FixedVoiceLoadDiagnostic(
    val code: FixedVoiceDiagnosticCode,
    val message: String,
    val staffTrackId: TrackId? = null,
    val voiceTrackId: TrackId? = null,
    val eventId: EventId? = null,
)

class FixedVoiceScoreException(
    val diagnostics: List<FixedVoiceLoadDiagnostic>,
) : IllegalArgumentException(diagnostics.joinToString("; ") { it.message })

data class FixedVoiceScore(
    val scoreId: com.mecon.api.primitive.ScoreId,
    val voices: List<FixedVoice>,
    val eventsByVoice: Map<TrackId, List<FixedVoiceScoreEvent>>,
) {
    private val eventIndex: Map<EventId, FixedVoiceScoreEvent> =
        eventsByVoice.values.flatten().associateBy { it.id }

    fun event(eventId: EventId): FixedVoiceScoreEvent? = eventIndex[eventId]

    fun eventsForVoice(voiceId: TrackId): List<FixedVoiceScoreEvent> =
        eventsByVoice[voiceId].orEmpty()

    fun eventsForVoice(voice: FixedVoice): List<FixedVoiceScoreEvent> =
        eventsForVoice(voice.id)

    fun noteEventsForVoice(voiceId: TrackId): List<FixedVoiceScoreEvent> =
        eventsForVoice(voiceId).filterNot { it.isRest }

    fun noteEventsForVoice(voice: FixedVoice): List<FixedVoiceScoreEvent> =
        noteEventsForVoice(voice.id)

    fun previousInVoice(anchor: FixedVoiceScoreEvent): FixedVoiceScoreEvent? {
        val events = eventsByVoice[anchor.voice.id].orEmpty()
        val index = events.indexOfFirst { it.id == anchor.id }
        return if (index > 0) events[index - 1] else null
    }

    fun nextInVoice(anchor: FixedVoiceScoreEvent): FixedVoiceScoreEvent? {
        val events = eventsByVoice[anchor.voice.id].orEmpty()
        val index = events.indexOfFirst { it.id == anchor.id }
        return if (index >= 0 && index < events.lastIndex) events[index + 1] else null
    }

    fun eventsSoundingAt(time: TimeCode): List<FixedVoiceScoreEvent> =
        eventsByVoice.values
            .flatMap { it }
            .filter { it.onset <= time && time < it.endTime }
            .sortedWith(compareBy({ it.voice.staffIndex }, { it.voice.voiceIndexOnStaff }, { it.onset }))

    fun notesSoundingAt(time: TimeCode): List<FixedVoiceScoreEvent> =
        eventsSoundingAt(time).filterNot { it.isRest }

    fun simultaneousEvents(
        anchor: FixedVoiceScoreEvent,
        includeSameStaff: Boolean = true,
        includeAnchor: Boolean = false,
    ): List<FixedVoiceScoreEvent> =
        eventsSoundingAt(anchor.onset).filter { candidate ->
            (includeAnchor || candidate.id != anchor.id) &&
                (includeSameStaff || candidate.voice.staffTrackId != anchor.voice.staffTrackId)
        }

    fun simultaneousNotes(
        anchor: FixedVoiceScoreEvent,
        includeSameStaff: Boolean = true,
        includeAnchor: Boolean = false,
    ): List<FixedVoiceScoreEvent> =
        simultaneousEvents(anchor, includeSameStaff, includeAnchor).filterNot { it.isRest }

    companion object {
        fun load(score: RuntimeScore, layout: FixedVoiceLayout): FixedVoiceScore {
            val diagnostics = validate(score, layout)
            if (diagnostics.isNotEmpty()) throw FixedVoiceScoreException(diagnostics)

            val orderedStaffs = score.orderedStaffs()
            val staffIndexById = orderedStaffs.mapIndexed { index, staff -> staff.id to index }.toMap()
            val voices = layout.staves.flatMap { staffLayout ->
                val staff = score.getStaffTrack(staffLayout.staffTrackId)!!
                staff.voiceTracks.mapIndexed { voiceIndex, voiceTrack ->
                    FixedVoice(
                        id = voiceTrack.id,
                        staffTrackId = staff.id,
                        staffIndex = staffIndexById[staff.id] ?: 0,
                        voiceIndexOnStaff = voiceIndex,
                        role = staffLayout.voiceRoles[voiceIndex],
                    )
                }
            }
            val voiceByTrack = voices.associateBy { it.id }
            val eventsByVoice = voices.associate { voice ->
                val runtimeVoice = score.getVoiceTrack(voice.id)!!
                voice.id to runtimeVoice.events.toList().map { event ->
                    FixedVoiceScoreEvent(
                        voice = voiceByTrack.getValue(runtimeVoice.id),
                        event = event,
                        pitch = event.pitches.singleOrNull(),
                    )
                }
            }
            return FixedVoiceScore(score.id, voices, eventsByVoice)
        }

        fun validate(score: RuntimeScore, layout: FixedVoiceLayout): List<FixedVoiceLoadDiagnostic> =
            buildList {
                for (staffLayout in layout.staves) {
                    val staff = score.getStaffTrack(staffLayout.staffTrackId)
                    if (staff == null) {
                        add(
                            FixedVoiceLoadDiagnostic(
                                code = FixedVoiceDiagnosticCode.STAFF_NOT_FOUND,
                                message = "Staff ${staffLayout.staffTrackId} does not exist",
                                staffTrackId = staffLayout.staffTrackId,
                            )
                        )
                        continue
                    }
                    if (staff.voiceTracks.size != staffLayout.voiceRoles.size) {
                        add(
                            FixedVoiceLoadDiagnostic(
                                code = FixedVoiceDiagnosticCode.STAFF_VOICE_COUNT_MISMATCH,
                                message = "Staff ${staff.id} has ${staff.voiceTracks.size} voices, expected ${staffLayout.voiceRoles.size}",
                                staffTrackId = staff.id,
                            )
                        )
                    }
                    for (voiceTrack in staff.voiceTracks) {
                        for (event in voiceTrack.events) {
                            if (event.pitches.size > 1) {
                                add(
                                    FixedVoiceLoadDiagnostic(
                                        code = FixedVoiceDiagnosticCode.CHORD_IN_MONOPHONIC_VOICE,
                                        message = "Voice ${voiceTrack.id} contains chord event ${event.id}",
                                        staffTrackId = staff.id,
                                        voiceTrackId = voiceTrack.id,
                                        eventId = event.id,
                                    )
                                )
                            }
                        }
                    }
                }
            }
    }
}
