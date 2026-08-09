package com.mecon.api.storage.events

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Accidental
import com.mecon.api.primitive.TimeCode
import com.mecon.api.storage.tracks.BreathMarkShape
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A standard dynamic level (p, f, mf, pp, …).
 *
 * [letters] is the MusicXML-compatible spelling of this mark. The renderer maps
 * every supported level to one Bravura SMuFL glyph; compound levels use the
 * ready-made pre-composed glyphs — see `renderer/dynamics.md`.
 */
@Serializable
enum class DynamicLevel(val letters: String) {
    PPPPPP("pppppp"),
    PPPPP("ppppp"),
    PPPP("pppp"),
    PPP("ppp"),
    PP("pp"),
    P("p"),
    MP("mp"),
    MF("mf"),
    PF("pf"),
    F("f"),
    FF("ff"),
    FFF("fff"),
    FFFF("ffff"),
    FFFFF("fffff"),
    FFFFFF("ffffff"),
    NIENTE("n"),
    FP("fp"),
    SF("sf"),
    SFP("sfp"),
    SFPP("sfpp"),
    SFZ("sfz"),
    SFZP("sfzp"),
    SFFZ("sffz"),
    FZ("fz"),
    RF("rf"),
    RFZ("rfz"),
}

/**
 * Whether a staff attachment sits above or below its staff.
 *
 * Future work will allow precise / user-adjusted offsets; for now placement is a
 * coarse side selection and the renderer stacks marks within the chosen band.
 */
@Serializable
enum class StaffAttachmentPlacement { ABOVE, BELOW }

/** Hairpin direction. */
@Serializable
enum class HairpinType { CRESCENDO, DIMINUENDO }

/**
 * How a hairpin is drawn.
 *
 * - [WEDGE]: the drawn angle-bracket "arrow" (`<` / `>`), rendered as two lines.
 * - [TEXT_DASHED]: the word `cresc.` / `dim.` followed by a dashed continuation
 *   line.
 */
@Serializable
enum class HairpinStyle { WEDGE, TEXT_DASHED }

/** Engraved ornament glyph/playing pattern. */
@Serializable
enum class OrnamentKind {
    TRILL,
    MORDENT,
    INVERTED_MORDENT,
    TREMBLEMENT,
    TREMBLEMENT_COUPERIN,
    MORDENT_UPPER_PREFIX,
    INVERTED_MORDENT_UPPER_PREFIX,
    MORDENT_RELEASE,
    TURN,
    INVERTED_TURN,
    TURN_SLASH,
}

/** Whether a point ornament is centred on its note or delayed between two notes. */
@Serializable
enum class OrnamentAnchor { ON_NOTE, BETWEEN_NOTES }

/** Requested playback route for a trill. */
@Serializable
enum class TrillPlaybackMode { AUTO, EXPANDED, CONTROL_FLOW }

/**
 * Base type for marks attached to a staff that are not part of the note stream
 * itself — dynamics, hairpins, and (in future) other expressive symbols / text.
 *
 * Attachments are stored on the [com.mecon.api.storage.tracks.StorageStaffTrack]
 * (not the voice track), optionally narrowed to a single [voiceNumber]. They are
 * the storage-side anchor for the reusable "extra symbol / text" rendering
 * abstraction.
 */
@Serializable
sealed interface StorageStaffAttachment {
    val id: EventId
    val onset: TimeCode

    /** Voice this attachment applies to within the staff; null = whole staff. */
    val voiceNumber: Int?

    /** Coarse above/below-staff placement. */
    val placement: StaffAttachmentPlacement
}

/**
 * A note-related ornament stored as a staff attachment so point and interval
 * variants share one selectable/editable identity.
 *
 * [elementDuration] is measured in quarter-note beats. Null accidentals mean
 * "use the current key signature"; a non-null value is an explicit spelling.
 */
@Serializable
@SerialName("ornamentMark")
data class StorageOrnamentMark(
    override val id: EventId,
    override val onset: TimeCode,
    val sourceEventId: EventId,
    val kind: OrnamentKind,
    val anchor: OrnamentAnchor = OrnamentAnchor.ON_NOTE,
    val endOnset: TimeCode? = null,
    val upperAccidental: Accidental? = null,
    val lowerAccidental: Accidental? = null,
    val elementDuration: Fraction = Fraction(1, 4),
    val oscillations: Int = 1,
    val trillPlaybackMode: TrillPlaybackMode = TrillPlaybackMode.AUTO,
    override val placement: StaffAttachmentPlacement = StaffAttachmentPlacement.ABOVE,
    override val voiceNumber: Int? = null,
) : StorageStaffAttachment {
    init {
        require(elementDuration.isPositive) { "Ornament element duration must be positive" }
        require(oscillations in 1..16) { "Ornament oscillations must be in 1..16" }
        require(endOnset == null || endOnset > onset) { "Ornament span must end after its onset" }
    }

    companion object {
        fun create(
            onset: TimeCode,
            sourceEventId: EventId,
            kind: OrnamentKind,
            anchor: OrnamentAnchor = OrnamentAnchor.ON_NOTE,
            endOnset: TimeCode? = null,
            elementDuration: Fraction = Fraction(1, 4),
            voiceNumber: Int? = null,
        ) = StorageOrnamentMark(
            id = EventId.generate(),
            onset = onset,
            sourceEventId = sourceEventId,
            kind = kind,
            anchor = anchor,
            endOnset = endOnset,
            elementDuration = elementDuration.simplified(),
            voiceNumber = voiceNumber,
        )
    }
}

/**
 * Breath mark for one voice ([voiceNumber] != null) or the whole staff
 * ([voiceNumber] == null). [onset] is the time immediately after the affected
 * event and [pause] is playback silence, not written duration.
 */
@Serializable
@SerialName("breathMark")
data class StorageBreathMark(
    override val id: EventId,
    override val onset: TimeCode,
    /** Silent quarter-note beats; 1/2 means half a beat. */
    val pause: Fraction = Fraction.HALF,
    val shape: BreathMarkShape = BreathMarkShape.COMMA,
    override val placement: StaffAttachmentPlacement = StaffAttachmentPlacement.ABOVE,
    override val voiceNumber: Int? = null,
) : StorageStaffAttachment {
    init {
        require(pause.isPositive) { "Breath pause must be positive" }
    }

    companion object {
        fun create(
            onset: TimeCode,
            pause: Fraction = Fraction.HALF,
            shape: BreathMarkShape = BreathMarkShape.COMMA,
            voiceNumber: Int? = null,
        ) = StorageBreathMark(
            id = EventId.generate(),
            onset = onset,
            pause = pause.simplified(),
            shape = shape,
            voiceNumber = voiceNumber,
        )
    }
}

/**
 * A fixed dynamic letter mark (p, f, mf, …) drawn at a single [onset].
 *
 * [controllerEventId] links to the [com.mecon.api.storage.tracks.StorageControllerEvent]
 * that records the corresponding playback transition. In the first version that
 * controller event is a blank placeholder (no actual effect).
 */
@Serializable
@SerialName("dynamicMark")
data class StorageDynamicMark(
    override val id: EventId,
    override val onset: TimeCode,
    val level: DynamicLevel,
    override val placement: StaffAttachmentPlacement = StaffAttachmentPlacement.BELOW,
    override val voiceNumber: Int? = null,
    val controllerEventId: EventId? = null,
) : StorageStaffAttachment {
    companion object {
        fun create(
            onset: TimeCode,
            level: DynamicLevel,
            placement: StaffAttachmentPlacement = StaffAttachmentPlacement.BELOW,
            voiceNumber: Int? = null,
            controllerEventId: EventId? = null,
        ) = StorageDynamicMark(
            id = EventId.generate(),
            onset = onset,
            level = level,
            placement = placement,
            voiceNumber = voiceNumber,
            controllerEventId = controllerEventId,
        )
    }
}

/** Octave-transposition bracket direction. */
@Serializable
enum class OctaveShiftType {
    /** 8va — play one octave higher than written. */
    OTTAVA,
    /** 8vb — play one octave lower than written. */
    OTTAVA_BASSA,
}

/**
 * Start marker of an 8va / 8vb bracket.
 *
 * The end marker is a separate [StorageOctaveShiftEnd] whose [id] is referenced
 * by [endEventId]. Both events are stored in [StorageStaffTrack.attachments].
 */
@Serializable
@SerialName("octaveShiftStart")
data class StorageOctaveShiftStart(
    override val id: EventId,
    override val onset: TimeCode,
    val shiftType: OctaveShiftType,
    /** ID of the matching [StorageOctaveShiftEnd] event on this staff. */
    val endEventId: EventId,
    override val placement: StaffAttachmentPlacement = StaffAttachmentPlacement.ABOVE,
    override val voiceNumber: Int? = null,
) : StorageStaffAttachment {
    companion object {
        fun create(
            onset: TimeCode,
            shiftType: OctaveShiftType,
            endEventId: EventId,
            placement: StaffAttachmentPlacement = StaffAttachmentPlacement.ABOVE,
            voiceNumber: Int? = null,
        ) = StorageOctaveShiftStart(
            id = EventId.generate(),
            onset = onset,
            shiftType = shiftType,
            endEventId = endEventId,
            placement = placement,
            voiceNumber = voiceNumber,
        )
    }
}

/**
 * End marker of an 8va / 8vb bracket.
 *
 * Paired with a [StorageOctaveShiftStart] that references this event via
 * [StorageOctaveShiftStart.endEventId].
 */
@Serializable
@SerialName("octaveShiftEnd")
data class StorageOctaveShiftEnd(
    override val id: EventId,
    override val onset: TimeCode,
    override val placement: StaffAttachmentPlacement = StaffAttachmentPlacement.ABOVE,
    override val voiceNumber: Int? = null,
) : StorageStaffAttachment {
    companion object {
        fun create(
            onset: TimeCode,
            placement: StaffAttachmentPlacement = StaffAttachmentPlacement.ABOVE,
            voiceNumber: Int? = null,
        ) = StorageOctaveShiftEnd(
            id = EventId.generate(),
            onset = onset,
            placement = placement,
            voiceNumber = voiceNumber,
        )
    }
}

/**
 * A crescendo / diminuendo hairpin spanning [onset] (inclusive) to [endOnset]
 * (where the opening reaches its widest / the closing reaches its point).
 *
 * The hairpin is recorded at its starting [onset]. It links to a **pair** of
 * controller events — [controllerStartId] / [controllerEndId] — describing the
 * ramp's start and end (blank placeholders in the first version).
 */
@Serializable
@SerialName("hairpin")
data class StorageHairpin(
    override val id: EventId,
    override val onset: TimeCode,
    val endOnset: TimeCode,
    // Named `direction` (not `type`) to avoid clashing with the sealed-attachment
    // polymorphism discriminator, which kaml writes as a `type` property.
    val direction: HairpinType,
    val style: HairpinStyle = HairpinStyle.WEDGE,
    override val placement: StaffAttachmentPlacement = StaffAttachmentPlacement.BELOW,
    override val voiceNumber: Int? = null,
    val controllerStartId: EventId? = null,
    val controllerEndId: EventId? = null,
) : StorageStaffAttachment {
    companion object {
        fun create(
            onset: TimeCode,
            endOnset: TimeCode,
            direction: HairpinType,
            style: HairpinStyle = HairpinStyle.WEDGE,
            placement: StaffAttachmentPlacement = StaffAttachmentPlacement.BELOW,
            voiceNumber: Int? = null,
            controllerStartId: EventId? = null,
            controllerEndId: EventId? = null,
        ) = StorageHairpin(
            id = EventId.generate(),
            onset = onset,
            endOnset = endOnset,
            direction = direction,
            style = style,
            placement = placement,
            voiceNumber = voiceNumber,
            controllerStartId = controllerStartId,
            controllerEndId = controllerEndId,
        )
    }
}
