package com.mecon.renderer.render

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TrackId
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.geometry.RelativeRect
import kotlinx.serialization.Serializable

/**
 * A renderable musical element with associated metadata for interaction.
 *
 * Each render element contains:
 * - The visual representation (render commands)
 * - Hit box for click/tap detection
 * - Metadata linking back to the source data
 */
@Serializable
data class RenderElement(
    /** Unique identifier for this element */
    val id: RenderElementId,
    /** Type of element */
    val type: RenderElementType,
    /** Render commands to draw this element */
    val commands: List<RenderCommand>,
    /** Hit box for interaction */
    val hitBox: AbsoluteRect,
    /** Associated event ID (for notes, rests, etc.) */
    val eventId: EventId? = null,
    /** Associated track ID */
    val trackId: TrackId? = null,
    /** Measure number (1-based) */
    val measureNumber: Int? = null,
    /** System index (0-based) */
    val systemIndex: Int? = null,
    /** Staff index within system (0-based) */
    val staffIndex: Int? = null,
    /** Whether this element is currently selected */
    val selected: Boolean = false,
    /** Whether this element is highlighted (e.g., during playback) */
    val highlighted: Boolean = false,
    /** Custom metadata */
    val metadata: Map<String, String> = emptyMap(),
    /** Optional pre-computed relative hit box for hierarchical spatial index */
    val relativeHitBox: RelativeRect? = null
) {
    /**
     * Check if a point is within this element's hit box.
     */
    fun containsPoint(point: AbsolutePoint): Boolean = point in hitBox

    /**
     * Get the center of the hit box.
     */
    val center: AbsolutePoint get() = hitBox.center
}

/**
 * Types of render elements.
 */
enum class RenderElementType {
    // Notes and rests
    NOTE,
    REST,
    CHORD,
    GRACE_NOTE,

    // Note components
    NOTEHEAD,
    STEM,
    FLAG,
    BEAM,
    DOT,
    ACCIDENTAL,
    LEDGER_LINE,

    // Articulations and ornaments
    ARTICULATION,
    ORNAMENT,
    DYNAMIC,
    FERMATA,

    // Slurs and ties
    SLUR,
    TIE,

    // Staff elements
    STAFF,
    STAFF_LINE,
    CLEF,
    KEY_SIGNATURE,
    TIME_SIGNATURE,
    BARLINE,
    VOLTA_ENDING,
    NAVIGATION_MARK,

    // System elements
    SYSTEM_BRACKET,
    SYSTEM_BRACE,

    // Text
    LYRIC,
    TEXT_ANNOTATION,
    TEMPO_MARKING,
    REHEARSAL_MARK,

    // Other
    MEASURE,
    TUPLET_BRACKET,
    PEDAL,
    HAIRPIN,
    OCTAVE_SHIFT,
    /** Non-spacing user-edit entry rendered after all engraving elements. */
    EDITOR_MARKER,

    // Container
    GROUP
}
