package com.mecon.api.storage.tracks

import com.mecon.api.primitive.*
import com.mecon.api.storage.events.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Base interface for all storage tracks.
 */
@Serializable
sealed interface StorageTrack {
    val id: TrackId
    val name: String
}

/**
 * Clef types.
 */
enum class Clef {
    TREBLE,
    BASS,
    ALTO,
    TENOR,
    PERCUSSION
}

/**
 * A mid-score clef change on a specific staff.
 *
 * Stored in [StorageStaffTrack.clefChanges]. The initial clef is set by
 * [StorageStaffTrack.clef]; only subsequent changes need entries here.
 */
@Serializable
data class StorageClefChange(
    val onset: TimeCode,
    val clef: Clef
)

/**
 * Transposition configuration for transposing instruments.
 */
@Serializable
data class TranspositionConfig(
    val interval: Interval,
    val octaveShift: Int = 0
) {
    companion object {
        val NONE = TranspositionConfig(Interval.UNISON)
        val Bb_INSTRUMENT = TranspositionConfig(Interval.MAJOR_SECOND)  // Bb clarinet, trumpet
        val Eb_INSTRUMENT = TranspositionConfig(Interval.MINOR_THIRD)   // Alto sax
        val F_INSTRUMENT = TranspositionConfig(Interval.PERFECT_FIFTH)  // French horn
    }
}

/**
 * Pitch track - contains pitch events (the actual notes).
 */
@Serializable
@SerialName("pitch")
data class StoragePitchTrack(
    override val id: TrackId,
    override val name: String = "Pitch Track",
    val events: List<StoragePitchEvent> = emptyList()
) : StorageTrack {

    fun addEvent(event: StoragePitchEvent): StoragePitchTrack =
        copy(events = events + event)

    fun removeEvent(eventId: EventId): StoragePitchTrack =
        copy(events = events.filter { it.id != eventId })

    companion object {
        fun create(name: String = "Pitch Track") = StoragePitchTrack(
            id = TrackId.generate(),
            name = name
        )
    }
}

/**
 * Voice track - groups pitch events into voices (for polyphonic music).
 *
 * Each voice track is associated with a specific pitch track (pitchTrackId).
 * The voice events reference pitch events from this associated pitch track.
 *
 * Design:
 * - voiceNumber: identifies the voice within a staff (1, 2, etc.)
 * - pitchTrackId: which pitch track this voice draws from
 * - events: VoiceEvents that define how pitches are rendered (duration, stems, etc.)
 */
@Serializable
@SerialName("voice")
data class StorageVoiceTrack(
    override val id: TrackId,
    override val name: String = "Voice",
    val voiceNumber: Int = 1,
    val pitchTrackId: TrackId,  // Associated pitch track
    val events: List<StorageVoiceEvent> = emptyList(),
    /**
     * First-class phrasing slurs with stable ids. Empty = derive slurs from the
     * events' [StorageVoiceEvent.slurStarts] / [StorageVoiceEvent.slurEnds]
     * counts (legacy encoding). See [StorageSlurEvent].
     */
    val slurs: List<StorageSlurEvent> = emptyList()
) : StorageTrack {

    fun addEvent(event: StorageVoiceEvent): StorageVoiceTrack =
        copy(events = events + event)

    fun removeEvent(eventId: EventId): StorageVoiceTrack =
        copy(events = events.filter { it.id != eventId })

    companion object {
        fun create(
            name: String = "Voice",
            voiceNumber: Int = 1,
            pitchTrackId: TrackId
        ) = StorageVoiceTrack(
            id = TrackId.generate(),
            name = name,
            voiceNumber = voiceNumber,
            pitchTrackId = pitchTrackId
        )
    }
}

/**
 * Staff track - represents a single staff with clef, key, and time signatures.
 *
 * In the new design, staff no longer directly references a pitch track.
 * Instead, each voice track references its own pitch track.
 *
 * Structure:
 * - Staff contains voice track references (voiceTrackIds)
 * - Each voice track has its own pitchTrackId
 * - This allows different voices on the same staff to draw from different pitch sources
 */
@Serializable
@SerialName("staff")
data class StorageStaffTrack(
    override val id: TrackId,
    override val name: String = "Staff",
    val clef: Clef = Clef.TREBLE,
    val keySignature: KeySignature = KeySignature.C_MAJOR,
    val transposition: TranspositionConfig? = null,
    val voiceTrackIds: List<TrackId> = emptyList(),
    /** Per-staff label rendered left of this staff (e.g. "S." inside an SATB choir part). */
    val staffLabel: String? = null,
    val staffLabelAbbreviation: String? = null,
    /**
     * Expressive marks attached to this staff (dynamics, hairpins, …). Stored on
     * the staff — not the voice — but may target a single voice via
     * [com.mecon.api.storage.events.StorageStaffAttachment.voiceNumber].
     */
    val attachments: List<StorageStaffAttachment> = emptyList(),
    /**
     * Mid-score clef changes for this staff. The initial clef is [clef]; subsequent
     * changes at measure boundaries are listed here in onset order.
     */
    val clefChanges: List<StorageClefChange> = emptyList(),
    /**
     * Measure ranges over which this staff is hidden. Normalized (sorted, merged).
     * Hiding is independent of system/page breaks: a range may span line breaks
     * (rendered as a collapsed dashed line where fully hidden on a line, or a grey
     * region where only part of a line is hidden). See [MeasureRange] / [MeasureRanges].
     */
    val hiddenRanges: List<MeasureRange> = emptyList()
) : StorageTrack {
    companion object {
        fun create(
            name: String = "Staff",
            clef: Clef = Clef.TREBLE,
            keySignature: KeySignature = KeySignature.C_MAJOR,
            voiceTrackIds: List<TrackId> = emptyList()
        ) = StorageStaffTrack(
            id = TrackId.generate(),
            name = name,
            clef = clef,
            keySignature = keySignature,
            voiceTrackIds = voiceTrackIds
        )
    }
}


/**
 * Style of the bracket drawn on the left of one or more staves.
 *
 * - [NONE]  — no bracket; staves are just adjacent.
 * - [SQUARE] — thick square bracket with hooked top/bottom serifs (orchestral
 *   instrument family bracket).
 * - [BRACE] — curly brace (piano-style grand staff).
 * - [SUB_BRACKET] — a thinner, secondary square bracket drawn outside a
 *   [SQUARE] bracket (e.g. for sub-grouping the violins inside the strings).
 */
enum class BracketStyle {
    NONE,
    SQUARE,
    BRACE,
    SUB_BRACKET
}

/**
 * One member of a [StorageStaffGroup]: either a single staff (terminal) or a nested group.
 */
@Serializable
sealed interface StaffGroupMember {
    @Serializable
    @SerialName("staff")
    data class Staff(val staffId: TrackId) : StaffGroupMember

    @Serializable
    @SerialName("group")
    data class Group(val group: StorageStaffGroup) : StaffGroupMember
}

/**
 * Hierarchical staff group description.
 *
 * Groups describe how parts are visually bracketed and labelled together on the
 * left margin of a system, and whether their staves share a continuous barline.
 *
 * The tree is rooted in [StorageScore.staffGroups]. Each group can contain
 * either parts (terminal members) or further nested groups. Parts not mentioned
 * anywhere in the tree are rendered without any bracket and with an independent
 * barline.
 *
 * @property bracket Bracket style drawn on the left of all staves covered by this group.
 * @property label Group-level label centred vertically over the staves in this group
 *   (e.g. "Strings" or "Choir"). Drawn outside the per-part instrument names.
 * @property barlineConnect If true, barlines are drawn continuously across all staves
 *   covered by this group. If false, barlines break at this group boundary.
 *   Default is false so that adding a group does not silently merge barlines —
 *   set explicitly to true for ensembles where a single barline spans the group.
 */
@Serializable
data class StorageStaffGroup(
    val id: StaffGroupId,
    val bracket: BracketStyle = BracketStyle.NONE,
    val label: String? = null,
    val abbreviation: String? = null,
    val barlineConnect: Boolean = false,
    val members: List<StaffGroupMember> = emptyList()
) {
    fun allStaffIds(): List<TrackId> = members.flatMap { member ->
        when (member) {
            is StaffGroupMember.Staff -> listOf(member.staffId)
            is StaffGroupMember.Group -> member.group.allStaffIds()
        }
    }

    companion object {
        fun ofStaffs(
            bracket: BracketStyle = BracketStyle.SQUARE,
            label: String? = null,
            barlineConnect: Boolean = false,
            staffIds: List<TrackId>
        ) = StorageStaffGroup(
            id = StaffGroupId.generate(),
            bracket = bracket,
            label = label,
            barlineConnect = barlineConnect,
            members = staffIds.map { StaffGroupMember.Staff(it) }
        )
    }
}

/**
 * A score-wide notation change event stored on [StorageGlobalTrack].
 *
 * Subtypes are serialized with a `type` discriminator so they can coexist in a
 * single `events` list in the file format.
 */
@Serializable
sealed interface StorageGlobalEvent {
    val onset: TimeCode
}

/** Visual form of a fermata. */
@Serializable
enum class FermataShape { VERY_SHORT, SHORT, NORMAL, LONG, VERY_LONG }

/** Visual form of a breath mark. */
@Serializable
enum class BreathMarkShape { COMMA, TICK, UPBOW, SALZEDO }

/** Editing/storage scope of a breath pause. */
@Serializable
enum class BreathMarkScope { VOICE, STAFF, GLOBAL }

/**
 * Score-wide fermata stored at the time immediately after the held events.
 *
 * Every voice resolves its last non-grace event strictly before [onset]. The
 * written duration remains unchanged; [extension] is playback time added to
 * that resolved event.
 */
@Serializable
@SerialName("fermata")
data class StorageFermata(
    val id: EventId,
    override val onset: TimeCode,
    /** Additional quarter-note beats; 1/1 means one beat. */
    val extension: Fraction = Fraction.ONE,
    val shape: FermataShape = FermataShape.NORMAL,
) : StorageGlobalEvent {
    init {
        require(extension.isPositive) { "Fermata extension must be positive" }
    }

    companion object {
        fun create(
            onset: TimeCode,
            extension: Fraction = Fraction.ONE,
            shape: FermataShape = FermataShape.NORMAL,
        ) = StorageFermata(EventId.generate(), onset, extension.simplified(), shape)
    }
}

/**
 * Score-wide breath mark stored at the time immediately after the affected
 * events. It is expanded to every displayed staff by the Computed layer.
 */
@Serializable
@SerialName("globalBreathMark")
data class StorageGlobalBreathMark(
    val id: EventId,
    override val onset: TimeCode,
    /** Silent quarter-note beats; 1/2 means half a beat. */
    val pause: Fraction = Fraction.HALF,
    val shape: BreathMarkShape = BreathMarkShape.COMMA,
) : StorageGlobalEvent {
    init {
        require(pause.isPositive) { "Breath pause must be positive" }
    }

    companion object {
        fun create(
            onset: TimeCode,
            pause: Fraction = Fraction.HALF,
            shape: BreathMarkShape = BreathMarkShape.COMMA,
        ) = StorageGlobalBreathMark(EventId.generate(), onset, pause.simplified(), shape)
    }
}

/** Key signature change at [onset]. */
@Serializable
@SerialName("keySignatureChange")
data class StorageKeySignatureChange(
    override val onset: TimeCode,
    val keySignature: KeySignature
) : StorageGlobalEvent

/** Time signature change at [onset]. */
@Serializable
@SerialName("timeSignatureChange")
data class StorageTimeSignatureChange(
    override val onset: TimeCode,
    val timeSignature: TimeSignature
) : StorageGlobalEvent

/**
 * Forced system (line) break. Layout starts a new system at the measure
 * [onset].measure — i.e. the break happens *before* that measure.
 */
@Serializable
@SerialName("systemBreak")
data class StorageSystemBreak(
    override val onset: TimeCode
) : StorageGlobalEvent

/**
 * Forced page break. Layout starts a new page at the measure [onset].measure
 * (the break happens *before* that measure). Implies a system break too.
 */
@Serializable
@SerialName("pageBreak")
data class StoragePageBreak(
    override val onset: TimeCode
) : StorageGlobalEvent

/**
 * Global track - contains score-wide events (tempo, key/time signature changes).
 *
 * Key and time signature changes are stored in the unified [events] list using
 * [StorageKeySignatureChange] and [StorageTimeSignatureChange]. Each event carries
 * a `type` discriminator in the file format.
 */
@Serializable
@SerialName("global")
data class StorageGlobalTrack(
    override val id: TrackId,
    override val name: String = "Global",
    val tempoEvents: List<StorageTempoEvent> = emptyList(),
    val events: List<StorageGlobalEvent> = emptyList()
) : StorageTrack {
    companion object {
        fun create(initialTempo: Float? = null) = StorageGlobalTrack(
            id = TrackId.generate(),
            tempoEvents = initialTempo?.let { bpm ->
                listOf(StorageTempoEvent.create(
                    onset = TimeCode.of(1, Fraction.ZERO),
                    bpm = bpm,
                    markType = TempoMarkType.KEYFRAME,
                    displayStyle = TempoDisplayStyle.HIDDEN,
                ))
            }.orEmpty(),
        )
    }
}

/**
 * Storage plugin track - contains events defined by plugins.
 */
@Serializable
@SerialName("plugin")
data class StoragePluginTrack(
    override val id: TrackId,
    override val name: String = "Plugin",
    val type: String, // Identifies the plugin
    val events: List<StoragePluginEvent> = emptyList()
) : StorageTrack {
    companion object {
        fun create(type: String, name: String = "Plugin") = StoragePluginTrack(
            id = TrackId.generate(),
            name = name,
            type = type
        )
    }
}
