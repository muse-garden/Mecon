package com.mecon.renderer.layout

import com.mecon.api.computed.BeamGroupId
import com.mecon.api.primitive.Accidental
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.renderer.enums.StemDirection
import com.mecon.renderer.geometry.StaffSpace
import kotlinx.serialization.Serializable

/**
 * Complete layout for a voice event.
 *
 * Contains the primary element (notehead or rest) plus all related components:
 * stem, flag, accidentals, dots, and beam information.
 *
 * All positions are relative to:
 * - X: The time code X position
 * - Y: The staff center line (middle line of the staff)
 */
@Serializable
data class VoiceEventLayout(
    /** The event ID */
    val eventId: EventId,
    /** The track ID (staff track) */
    val trackId: TrackId,
    /** Owning voice-track ID. Defaults to [trackId] for legacy/synthetic layouts. */
    val voiceTrackId: TrackId = trackId,
    /** The time code of this event */
    val time: TimeCode,
    /** Staff index within the system */
    val staffIndex: Int,
    /** Measure number */
    val measureNumber: Int,
    /** Cross-staff render offset (0 = home staff). Borrowed notes' stems live in the inter-staff gap. */
    val crossStaffOffset: Int = 0,

    /** Primary element (notehead or rest) */
    val primary: ElementLayout,

    /** Additional noteheads for chords (excludes primary) */
    val chordNotes: List<NoteheadLayout> = emptyList(),

    /** Stem layout (null for whole notes and rests) */
    val stem: StemLayout? = null,

    /** Flag layout (null if beamed or no flags needed) */
    val flag: FlagLayout? = null,

    /** Accidental layouts for all notes in this event */
    val accidentals: List<AccidentalLayout> = emptyList(),

    /** Augmentation dot layouts for all notes in this event */
    val dots: List<DotLayout> = emptyList(),

    /** Beam information for beam group rendering */
    val beamInfo: BeamLayoutInfo? = null,

    /** Ledger line layouts for notes outside the staff */
    val ledgerLines: List<LedgerLineLayout> = emptyList()
) {
    /**
     * Whether this is a rest.
     */
    val isRest: Boolean get() = primary is RestLayout

    /**
     * Whether this is a chord (multiple noteheads).
     */
    val isChord: Boolean get() = chordNotes.isNotEmpty()

    /**
     * Whether this event is part of a beam group.
     */
    val isBeamed: Boolean get() = beamInfo != null

    /**
     * Whether this event has a stem.
     */
    val hasStem: Boolean get() = stem != null

    /**
     * Whether this event has flags (not beamed, but shorter than quarter).
     */
    val hasFlags: Boolean get() = flag != null
}

/**
 * Layout for a stem.
 *
 * Positions are relative to the time code X position.
 */
@Serializable
data class StemLayout(
    /** X offset from time code X */
    val relativeX: StaffSpace,
    /** Top Y position (always the smaller Y value) */
    val topY: StaffSpace,
    /** Bottom Y position (always the larger Y value) */
    val bottomY: StaffSpace,
    /** Stem direction */
    val direction: StemDirection,
    /** X attachment to use if an edited beam reverses this stem after note layout. */
    val oppositeRelativeX: StaffSpace? = null,
) {
    /**
     * Length of the stem.
     */
    val length: StaffSpace get() = bottomY - topY

    /**
     * Y position of the stem tip (end opposite the notehead).
     */
    val tipY: StaffSpace get() = when (direction) {
        StemDirection.UP -> topY
        StemDirection.DOWN -> bottomY
    }

    /**
     * Y position of the stem attachment (notehead end).
     */
    val attachmentY: StaffSpace get() = when (direction) {
        StemDirection.UP -> bottomY
        StemDirection.DOWN -> topY
    }
}

/**
 * Layout for a flag.
 *
 * Positioned at the stem tip.
 */
@Serializable
data class FlagLayout(
    /** X offset from time code X (same as stem X) */
    val relativeX: StaffSpace,
    /** Y position (at stem tip) */
    val relativeY: StaffSpace,
    /** Number of flags (1 for 8th, 2 for 16th, etc.) */
    val flagCount: Int,
    /** Direction of the flag (matches stem direction) */
    val direction: StemDirection
)

/**
 * Layout for an accidental.
 */
@Serializable
data class AccidentalLayout(
    /** X offset from time code X (typically negative, to the left of notehead) */
    val relativeX: StaffSpace,
    /** Y offset from staff center line */
    val relativeY: StaffSpace,
    /** The accidental type */
    val accidental: Accidental,
    /** Staff position of the note this accidental belongs to */
    val staffPosition: Int
)

/**
 * Layout for an augmentation dot.
 */
@Serializable
data class DotLayout(
    /** X offset from time code X (to the right of notehead) */
    val relativeX: StaffSpace,
    /** Y offset from staff center line */
    val relativeY: StaffSpace,
    /** Dot index (0 = first dot, 1 = second dot for double-dotted, etc.) */
    val dotIndex: Int,
    /** Staff position of the note this dot belongs to */
    val staffPosition: Int
)

/**
 * Layout for a ledger line.
 */
@Serializable
data class LedgerLineLayout(
    /** X offset from time code X (centered on notehead) */
    val relativeX: StaffSpace,
    /** Y offset from staff center line */
    val relativeY: StaffSpace,
    /** Width of the ledger line */
    val width: StaffSpace,
    /** Staff position where this ledger line is drawn */
    val staffPosition: Int
)

/**
 * Beam layout information for connecting to adjacent notes.
 *
 * This is used by the beam renderer to draw beam groups.
 */
@Serializable
data class BeamLayoutInfo(
    /** Beam group ID */
    val groupId: BeamGroupId,
    /** Total beam count for this note's duration */
    val totalBeamCount: Int,
    /** Number of beams connecting to the left */
    val beamsLeft: Int,
    /** Number of beams connecting to the right */
    val beamsRight: Int
) {
    /**
     * Number of through beams (connecting both left and right).
     */
    val throughBeamCount: Int get() = minOf(beamsLeft, beamsRight)

    /**
     * Number of left hooks (partial beams pointing left).
     */
    val leftHookCount: Int get() = when {
        beamsRight == 0 -> 0
        beamsLeft == 0 -> 0
        else -> maxOf(0, totalBeamCount - beamsRight)
    }

    /**
     * Number of right hooks (partial beams pointing right).
     */
    val rightHookCount: Int get() = when {
        beamsLeft == 0 -> 0
        beamsRight == 0 -> 0
        else -> maxOf(0, totalBeamCount - beamsLeft)
    }

    /**
     * True if this is the start of a beam group.
     */
    val isGroupStart: Boolean get() = beamsLeft == 0 && beamsRight > 0

    /**
     * True if this is the end of a beam group.
     */
    val isGroupEnd: Boolean get() = beamsLeft > 0 && beamsRight == 0

    /**
     * True if this is in the middle of a beam group.
     */
    val isGroupMiddle: Boolean get() = beamsLeft > 0 && beamsRight > 0
}
