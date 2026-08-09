package com.mecon.api.storage

import com.mecon.api.primitive.Duration
import kotlinx.serialization.Serializable

/**
 * Stem direction specification.
 */
enum class StemDirection {
    AUTO,  // Automatic (based on pitch position)
    UP,
    DOWN
}

/**
 * Accidental display mode.
 */
enum class AccidentalDisplay {
    AUTO,         // Automatic (show when needed)
    FORCE,        // Always show
    HIDE,         // Never show
    PARENTHESES,  // Show in parentheses
    CAUTIONARY    // Show as cautionary (small)
}

/**
 * Notehead type override.
 */
enum class NoteheadOverride {
    AUTO,      // Use default for duration
    WHOLE,     // Force whole note head
    HALF,      // Force half note head
    QUARTER,   // Force quarter note head
    DIAMOND,   // Diamond notehead (harmonics)
    X,         // X notehead (percussion)
    SLASH,     // Slash notehead (rhythm)
    TRIANGLE   // Triangle notehead
}

/**
 * Rendering properties for display customization.
 * These properties override automatic rendering decisions.
 */
@Serializable
data class RenderingProps(
    /** Override stem direction */
    val stemDirection: StemDirection? = null,

    /** Override accidental display */
    val accidentalDisplay: AccidentalDisplay? = null,

    /** Override notehead type */
    val noteheadOverride: NoteheadOverride? = null,

    /** Override displayed duration (for notation vs playback) */
    val explicitDuration: Duration? = null,

    /** Color override (hex format like "#FF0000") */
    val color: String? = null,

    /** Hidden from display */
    val hidden: Boolean = false,

    /** Custom x offset in staff spaces */
    val xOffset: Float? = null,

    /** Custom y offset in staff spaces */
    val yOffset: Float? = null,

    /**
     * Display staff position for a **rest** (ignored for notes/chords).
     *
     * Rests normally sit at a fixed position derived from their type (whole rest
     * hangs from the line above the middle, others are centred). In multi-voice
     * writing the upper voice's rests are raised and the lower voice's lowered to
     * avoid collisions; this field lets the user pin a rest to any staff line/space.
     *
     * Convention matches [com.mecon.api.computed.ComputedPitchData.staffPosition]:
     * `0` = middle line, positive = up, negative = down, one step per half staff
     * space. `null` = use the type's default position. This is purely a rendering
     * choice (it never changes playback), which is why it lives here on the voice
     * event rather than on the shared pitch event.
     */
    val restStaffPosition: Int? = null,

    /** Scale factor (1.0 = normal size) */
    val scale: Float? = null,

    /** Grace note styling */
    val graceNoteType: GraceNoteType? = null,

    /**
     * Where to draw the note's articulation marks.
     *
     * Articulations themselves are a musical fact and live on [com.mecon.api.storage.events.StoragePitchEvent];
     * this is the purely typographic choice of which side to print them on.
     * `null` / [ArticulationPlacement.AUTO] = notehead side (opposite the stem).
     */
    val articulationPlacement: ArticulationPlacement? = null,

    /** Ornaments */
    val ornaments: List<Ornament>? = null,

    /** Vertical arpeggiation attached to this note/chord. */
    val arpeggio: ArpeggioType? = null,

    /** Beaming information - specifies beam connections to adjacent notes */
    val beaming: BeamingInfo? = null,

    /**
     * Render this note/chord on a neighbouring staff instead of its home staff
     * (cross-staff notation, common in piano writing).
     *
     * Signed offset in **staff display order** ([com.mecon.api.runtime.orderedStaffs]):
     * `-1` = one staff above, `+1` = one staff below; `null`/`0` = home staff.
     * The offset is clamped to the available staff range when resolved.
     *
     * The note still belongs to its home voice for beaming, slurs and ties, but:
     * - its vertical position uses the **target** staff's clef;
     * - if beamed, the beam sits between the staves with interleaved stems;
     * - a tie to a note on a different rendered staff degrades to let-ring.
     */
    val crossStaffOffset: Int? = null
) {
    companion object {
        val DEFAULT = RenderingProps()
    }
}

/**
 * Grace note types.
 */
enum class GraceNoteType {
    ACCIACCATURA,  // Slashed grace note
    APPOGGIATURA   // Non-slashed grace note
}

/**
 * Articulation marks.
 *
 * These describe how a note is played and therefore live on
 * [com.mecon.api.storage.events.StoragePitchEvent] (musical information), not on
 * [RenderingProps]. Where they are drawn (notehead vs stem side) is the only
 * typography decision and is carried by [RenderingProps.articulationPlacement].
 */
enum class Articulation {
    STACCATO,
    SPICCATO,
    STACCATISSIMO,
    TENUTO,
    ACCENT,
    MARCATO,
    FERMATA
}

/**
 * Which side of the note articulation marks are printed on.
 */
enum class ArticulationPlacement {
    /** Notehead side — opposite the stem (stem-up → below, stem-down → above). */
    AUTO,
    /** Force the notehead side regardless of stem (same as [AUTO] for now). */
    NOTEHEAD,
    /** Stem side — print at the stem-tip end instead of the notehead. */
    STEM
}

/**
 * Ornament types.
 */
enum class Ornament {
    TRILL,
    MORDENT,
    INVERTED_MORDENT,
    TURN,
    INVERTED_TURN
}

/** Direction/form of a chord arpeggiation mark. */
@Serializable
enum class ArpeggioType {
    NORMAL,
    UP,
    DOWN,
    NON_ARPEGGIATE,
}

/**
 * Beaming information for connecting notes with beams.
 *
 * This only stores whether the note is connected to adjacent notes via beams.
 * The actual number of beam levels is computed in the computed layer based on note durations.
 *
 * `RenderingProps.beaming == null` means "no explicit preference; let the computed layer
 * decide automatically". `BeamingInfo.NONE` means "explicitly do not beam this note",
 * which is distinct from `null`.
 *
 * @property beamLeft Whether this note is connected to the left neighbor via beam
 * @property beamRight Whether this note is connected to the right neighbor via beam
 */
@Serializable
data class BeamingInfo(
    val beamLeft: Boolean = false,
    val beamRight: Boolean = false
) {
    /**
     * True if this note is part of a beam group.
     */
    val isBeamed: Boolean get() = beamLeft || beamRight

    /**
     * True if this is the start of a beam group (no left connection, has right connection).
     */
    val isBeamStart: Boolean get() = !beamLeft && beamRight

    /**
     * True if this is the end of a beam group (has left connection, no right connection).
     */
    val isBeamEnd: Boolean get() = beamLeft && !beamRight

    /**
     * True if this is in the middle of a beam group (has both connections).
     */
    val isBeamMiddle: Boolean get() = beamLeft && beamRight

    companion object {
        /** Explicitly no beaming (isolated note with flag) */
        val NONE = BeamingInfo(beamLeft = false, beamRight = false)

        /** Start of a beam group */
        fun start() = BeamingInfo(beamLeft = false, beamRight = true)

        /** End of a beam group */
        fun end() = BeamingInfo(beamLeft = true, beamRight = false)

        /** Middle of a beam group */
        fun middle() = BeamingInfo(beamLeft = true, beamRight = true)
    }
}
