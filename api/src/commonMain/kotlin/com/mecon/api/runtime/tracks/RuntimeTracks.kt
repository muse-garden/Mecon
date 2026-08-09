package com.mecon.api.runtime.tracks

import com.mecon.api.primitive.*
import com.mecon.api.runtime.TimeIndexedList
import com.mecon.api.runtime.events.*
import com.mecon.api.storage.events.StoragePluginEvent
import com.mecon.api.storage.events.StorageStaffAttachment
import com.mecon.api.storage.tracks.BracketStyle
import com.mecon.api.storage.tracks.Clef
import com.mecon.api.storage.tracks.InstrumentPlayback
import com.mecon.api.storage.tracks.MeasureRange
import com.mecon.api.storage.tracks.MeasureRanges
import com.mecon.api.storage.tracks.StorageClefChange
import com.mecon.api.storage.tracks.StoragePitchTrack
import com.mecon.api.storage.tracks.StorageStaffTrack
import com.mecon.api.storage.tracks.TranspositionConfig

/**
 * Runtime pitch track - contains all pitch events in time-indexed order.
 */
data class RuntimePitchTrack(
    val id: TrackId,
    val name: String,
    val events: TimeIndexedList<RuntimePitchEvent>
) {
    /**
     * Get events in time range.
     */
    fun eventsInRange(start: TimeCode, end: TimeCode): List<RuntimePitchEvent> =
        events.range(start, end)

    /**
     * Get events at exact time.
     */
    fun eventsAt(time: TimeCode): List<RuntimePitchEvent> =
        events.at(time)

    /**
     * Add an event.
     */
    fun addEvent(event: RuntimePitchEvent): RuntimePitchTrack =
        copy(events = events.insert(event))

    /**
     * Remove an event by ID.
     */
    fun removeEvent(eventId: EventId): RuntimePitchTrack =
        copy(events = events.removeWhere { it.id == eventId })

    /**
     * Find event by ID.
     */
    fun findEvent(eventId: EventId): RuntimePitchEvent? =
        events.find { it.id == eventId }

    companion object {
        fun fromStorage(storage: StoragePitchTrack): RuntimePitchTrack {
            val runtimeEvents = storage.events.map { RuntimePitchEvent(it) }
            return RuntimePitchTrack(
                id = storage.id,
                name = storage.name,
                events = TimeIndexedList.of(runtimeEvents)
            )
        }

        fun create(name: String = "Pitch Track") = RuntimePitchTrack(
            id = TrackId.generate(),
            name = name,
            events = TimeIndexedList.empty()
        )
    }
}

/**
 * Resolved phrasing slur on a voice track. [id] is stable — taken from the
 * source [com.mecon.api.storage.events.StorageSlurEvent], or a deterministic
 * derived id when synthesised from legacy slurStarts/slurEnds counts.
 */
data class RuntimeSlur(
    val id: EventId,
    val startEventId: EventId,
    val endEventId: EventId,
)

/**
 * Runtime voice track - organizes pitch events into a single voice.
 *
 * Each voice track is associated with a specific pitch track (pitchTrackId).
 * The pitchTrack field is the resolved reference for convenience.
 */
data class RuntimeVoiceTrack(
    val id: TrackId,
    val name: String,
    val voiceNumber: Int,
    val pitchTrackId: TrackId,
    val pitchTrack: RuntimePitchTrack,  // Resolved reference
    val events: TimeIndexedList<RuntimeVoiceEvent>,
    /**
     * First-class phrasing slurs (from explicit storage). Empty = derive from
     * the events' slurStarts/slurEnds counts. See [RuntimeSlur].
     */
    val slurs: List<RuntimeSlur> = emptyList()
) {
    /**
     * Get events in time range.
     */
    fun eventsInRange(start: TimeCode, end: TimeCode): List<RuntimeVoiceEvent> =
        events.range(start, end)

    /**
     * Get events at exact time.
     */
    fun eventsAt(time: TimeCode): List<RuntimeVoiceEvent> =
        events.at(time)

    /**
     * Add a voice event.
     */
    fun addEvent(event: RuntimeVoiceEvent): RuntimeVoiceTrack =
        copy(events = events.insert(event))

    /**
     * Remove a voice event by ID.
     */
    fun removeEvent(eventId: EventId): RuntimeVoiceTrack =
        copy(events = events.removeWhere { it.id == eventId })

    companion object {
        fun create(
            name: String = "Voice",
            voiceNumber: Int = 1,
            pitchTrack: RuntimePitchTrack
        ) = RuntimeVoiceTrack(
            id = TrackId.generate(),
            name = name,
            voiceNumber = voiceNumber,
            pitchTrackId = pitchTrack.id,
            pitchTrack = pitchTrack,
            events = TimeIndexedList.empty()
        )
    }
}

/**
 * Runtime staff track - a single staff with clef, key signature, and associated tracks.
 *
 * In the new design, staff no longer directly references a pitch track.
 * Pitch tracks are accessed through voice tracks.
 */
data class RuntimeInstrument(
    val id: InstrumentId,
    val name: String,
    val abbreviation: String?,
    val catalogId: String? = null,
    val staves: List<RuntimeStaffTrack>,
    val playback: InstrumentPlayback
)

data class RuntimeStaffTrack(
    val id: TrackId,
    val name: String,
    val clef: Clef,
    val keySignature: KeySignature,
    val transposition: TranspositionConfig?,
    val voiceTracks: List<RuntimeVoiceTrack>,
    val staffLabel: String? = null,
    val staffLabelAbbreviation: String? = null,
    /** Expressive marks attached to this staff (dynamics, hairpins). Pass-through value data. */
    val attachments: List<StorageStaffAttachment> = emptyList(),
    /** Mid-score clef changes in onset order. The initial clef is [clef]. */
    val clefChanges: List<StorageClefChange> = emptyList(),
    /** Measure ranges over which this staff is hidden (normalized). See [MeasureRange]. */
    val hiddenRanges: List<MeasureRange> = emptyList()
) {
    /** Whether this staff is hidden at the given measure number. */
    fun isHidden(measure: Int): Boolean = MeasureRanges.contains(hiddenRanges, measure)
    /**
     * Get all pitch tracks referenced by voice tracks on this staff.
     * Returns distinct pitch tracks.
     */
    fun getPitchTracks(): List<RuntimePitchTrack> =
        voiceTracks.map { it.pitchTrack }.distinctBy { it.id }

    /**
     * Get the primary pitch track (from voice 1).
     */
    val primaryPitchTrack: RuntimePitchTrack? get() =
        voiceTracks.firstOrNull()?.pitchTrack

    /**
     * Get all pitch events across all voices.
     */
    fun getAllPitchEvents(): List<RuntimePitchEvent> =
        getPitchTracks().flatMap { it.events.toList() }

    /**
     * Get pitch events in time range.
     */
    fun getPitchEventsInRange(start: TimeCode, end: TimeCode): List<RuntimePitchEvent> =
        getPitchTracks().flatMap { it.eventsInRange(start, end) }

    /**
     * Get all voice events across all voices.
     */
    fun getAllVoiceEvents(): List<RuntimeVoiceEvent> =
        voiceTracks.flatMap { it.events.toList() }

    companion object {
        fun fromStorage(
            storage: StorageStaffTrack,
            voiceTracks: List<RuntimeVoiceTrack>
        ): RuntimeStaffTrack = RuntimeStaffTrack(
            id = storage.id,
            name = storage.name,
            clef = storage.clef,
            keySignature = storage.keySignature,
            transposition = storage.transposition,
            voiceTracks = voiceTracks,
            staffLabel = storage.staffLabel,
            staffLabelAbbreviation = storage.staffLabelAbbreviation,
            attachments = storage.attachments,
            clefChanges = storage.clefChanges,
            hiddenRanges = storage.hiddenRanges
        )
    }
}

/**
 * Runtime mirror of [StorageStaffGroup] with [StaffGroupMember.Staff] references
 * resolved to actual [RuntimeStaffTrack] instances.
 */
data class RuntimeStaffGroup(
    val id: StaffGroupId,
    val bracket: BracketStyle,
    val label: String?,
    val abbreviation: String?,
    val barlineConnect: Boolean,
    val members: List<RuntimeStaffGroupMember>
) {
    /** All staff tracks contained in this group (recursively, depth-first order). */
    fun allStaffs(): List<RuntimeStaffTrack> = members.flatMap {
        when (it) {
            is RuntimeStaffGroupMember.Staff -> listOf(it.staff)
            is RuntimeStaffGroupMember.Group -> it.group.allStaffs()
        }
    }
}

sealed interface RuntimeStaffGroupMember {
    data class Staff(val staff: RuntimeStaffTrack) : RuntimeStaffGroupMember
    data class Group(val group: RuntimeStaffGroup) : RuntimeStaffGroupMember
}

/**
 * Runtime plugin track - contains all plugin events in time-indexed order.
 */
data class RuntimePluginTrack<T : StoragePluginEvent>(
    val id: TrackId,
    val name: String,
    val type: String,
    val events: TimeIndexedList<RuntimePluginEvent<T>>
) {
    /**
     * Get events in time range.
     */
    fun eventsInRange(start: TimeCode, end: TimeCode): List<RuntimePluginEvent<T>> =
        events.range(start, end)

    /**
     * Get events at exact time.
     */
    fun eventsAt(time: TimeCode): List<RuntimePluginEvent<T>> =
        events.at(time)

    fun findEventById(id: EventId): RuntimePluginEvent<T>? = events.find { it.id == id }

    fun prevEvent(time: TimeCode): RuntimePluginEvent<T>? = events.lastBefore(time)

    fun nextEvent(time: TimeCode): RuntimePluginEvent<T>? = events.firstAfter(time)

    fun lastEventAtOrBefore(time: TimeCode): RuntimePluginEvent<T>? = events.lastAtOrBefore(time)

    /**
     * Add an event.
     */
    fun addEvent(event: RuntimePluginEvent<T>): RuntimePluginTrack<T> =
        copy(events = events.insert(event))

    /**
     * Remove an event by ID.
     */
    fun removeEvent(eventId: EventId): RuntimePluginTrack<T> =
        copy(events = events.removeWhere { it.id == eventId })
}
