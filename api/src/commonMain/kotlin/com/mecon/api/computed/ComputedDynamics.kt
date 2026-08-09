package com.mecon.api.computed

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.events.DynamicLevel
import com.mecon.api.storage.events.HairpinStyle
import com.mecon.api.storage.events.HairpinType
import com.mecon.api.storage.events.StaffAttachmentPlacement
import com.mecon.api.storage.events.OctaveShiftType
import com.mecon.api.storage.events.StorageDynamicMark
import com.mecon.api.storage.events.StorageHairpin
import com.mecon.api.storage.events.StorageOctaveShiftStart
import com.mecon.api.storage.events.StorageBreathMark
import com.mecon.api.storage.events.StorageOrnamentMark
import com.mecon.api.storage.events.OrnamentKind
import com.mecon.api.storage.events.OrnamentAnchor
import com.mecon.api.storage.events.TrillPlaybackMode
import com.mecon.api.primitive.Accidental
import com.mecon.api.storage.tracks.BreathMarkShape

/**
 * Computed form of a staff attachment (dynamic / hairpin / …).
 *
 * The Computed layer resolves which staff (by display index) the attachment
 * belongs to and its effective placement; the renderer then turns it into
 * geometry. This is the data side of the reusable "extra symbol / text"
 * abstraction — new attachment kinds extend this sealed family.
 */
sealed interface ComputedStaffAttachment {
    val id: EventId
    val time: TimeCode
    val staffTrackId: TrackId
    /** Display-order staff index (matches the renderer's staff layout). */
    val staffIndex: Int
    val placement: StaffAttachmentPlacement
    /** Voice this attachment targets within the staff; null = whole staff. */
    val voiceNumber: Int?
}

/** A fixed dynamic letter mark (p, f, mf, …) resolved to a staff. */
data class ComputedDynamicMark(
    override val id: EventId,
    override val time: TimeCode,
    override val staffTrackId: TrackId,
    override val staffIndex: Int,
    override val placement: StaffAttachmentPlacement,
    override val voiceNumber: Int?,
    val level: DynamicLevel,
    val source: StorageDynamicMark,
) : ComputedStaffAttachment

/** An 8va / 8vb bracket resolved to a staff with a concrete start–end span. */
data class ComputedOctaveShift(
    override val id: EventId,
    override val time: TimeCode,
    /** Where the bracket ends (the [StorageOctaveShiftEnd] onset). */
    val endTime: TimeCode,
    override val staffTrackId: TrackId,
    override val staffIndex: Int,
    override val placement: StaffAttachmentPlacement,
    override val voiceNumber: Int?,
    val shiftType: OctaveShiftType,
    val source: StorageOctaveShiftStart,
) : ComputedStaffAttachment

/**
 * A first/second ending projected into the shared interval-attachment layout path.
 *
 * [ending] remains the semantic computed object; this wrapper supplies the staff,
 * time span and stable editor identity required by attachment packing.
 */
data class ComputedVoltaAttachment(
    override val id: EventId,
    override val time: TimeCode,
    val endTime: TimeCode,
    override val staffTrackId: TrackId,
    override val staffIndex: Int,
    override val placement: StaffAttachmentPlacement = StaffAttachmentPlacement.ABOVE,
    override val voiceNumber: Int? = null,
    val ending: ComputedVoltaEnding,
) : ComputedStaffAttachment

/** A crescendo / diminuendo hairpin resolved to a staff and a concrete span. */
data class ComputedHairpin(
    override val id: EventId,
    override val time: TimeCode,
    /** End of the hairpin span (where it reaches its widest / its point). */
    val endTime: TimeCode,
    override val staffTrackId: TrackId,
    override val staffIndex: Int,
    override val placement: StaffAttachmentPlacement,
    override val voiceNumber: Int?,
    val type: HairpinType,
    val style: HairpinStyle,
    val source: StorageHairpin,
) : ComputedStaffAttachment

/** A voice-, staff-, or score-wide breath mark projected onto one staff. */
data class ComputedBreathMark(
    override val id: EventId,
    override val time: TimeCode,
    override val staffTrackId: TrackId,
    override val staffIndex: Int,
    override val placement: StaffAttachmentPlacement = StaffAttachmentPlacement.ABOVE,
    override val voiceNumber: Int?,
    val pause: com.mecon.api.primitive.Fraction,
    val shape: BreathMarkShape,
    /** Null for a global breath expanded from the global track. */
    val source: StorageBreathMark? = null,
    val isGlobal: Boolean = false,
    /** Original global event id when [isGlobal] is true. */
    val globalEventId: EventId? = null,
) : ComputedStaffAttachment

/** A resolved point ornament or trill interval. */
data class ComputedOrnamentMark(
    override val id: EventId,
    override val time: TimeCode,
    val endTime: TimeCode?,
    override val staffTrackId: TrackId,
    override val staffIndex: Int,
    override val placement: StaffAttachmentPlacement,
    override val voiceNumber: Int?,
    val sourceEventId: EventId,
    val kind: OrnamentKind,
    val anchor: OrnamentAnchor,
    val upperAccidental: Accidental?,
    val lowerAccidental: Accidental?,
    val elementDuration: com.mecon.api.primitive.Fraction,
    val oscillations: Int,
    val trillPlaybackMode: TrillPlaybackMode,
    val source: StorageOrnamentMark,
) : ComputedStaffAttachment

/** Stable per-staff identity for one score-wide breath mark's computed projection. */
fun globalBreathComputedId(globalEventId: EventId, staffTrackId: TrackId): EventId =
    EventId("${globalEventId.value}:staff:${staffTrackId.value}")
