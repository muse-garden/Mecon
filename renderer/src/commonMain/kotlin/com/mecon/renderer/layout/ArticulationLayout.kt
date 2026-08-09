package com.mecon.renderer.layout

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.Articulation
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.smufl.GlyphInfo
import kotlinx.serialization.Serializable

/**
 * Pre-computed layout for all articulation marks attached to one voice event.
 *
 * Like [SlurLayout] / [TieLayout], every coordinate is already absolute in the
 * score's relative-coordinate space — the time-slot X (plus the note's own
 * relativeX) and the staff center Y have already been folded into the points.
 * The renderer ([com.mecon.renderer.elements.ArticulationElement]) only has to
 * draw the glyphs; the slur computer reads [PlacedArticulation.bounds] to avoid
 * them.
 */
@Serializable
data class ArticulationLayout(
    val eventId: EventId,
    val trackId: TrackId,
    val staffIndex: Int,
    val measureNumber: Int,
    /** Placed marks, innermost (nearest the note) first. */
    val marks: List<PlacedArticulation>,
)

/**
 * One placed articulation glyph.
 *
 * @property index Index into the owning [com.mecon.api.computed.ComputedVoiceEvent.articulations]
 *                 (used for fine-grained section registration).
 * @property origin SMuFL glyph origin (reference point) in score-relative space.
 * @property above True if drawn above the note, false if below.
 * @property bounds Score-relative bounding box (for hit testing and slur avoidance).
 */
@Serializable
data class PlacedArticulation(
    val articulation: Articulation,
    val glyph: GlyphInfo,
    val index: Int,
    val origin: RelativePoint,
    val above: Boolean,
    val bounds: RelativeRect,
)
