package com.mecon.renderer.layout

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TrackId
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.SlurDirection
import com.mecon.renderer.geometry.StaffSpace
import kotlinx.serialization.Serializable

/**
 * Pre-computed layout for a single phrasing slur.
 *
 * Like [TieLayout], positions are absolute in the score's relative-coordinate
 * space — time-slot X has already been folded into [start]/[end].x and the
 * staff center Y has been folded into the y values. The renderer only has to
 * hand these to [com.mecon.renderer.geometry.SlurCurveBuilder].
 *
 * Anchor pitch indices differ from ties: a slur anchors on the **outermost**
 * notehead in its bow direction (top of the chord for an above-bowed slur,
 * bottom for below) so the curve clears the chord even when the chord is
 * thick.
 */
@Serializable
data class SlurLayout(
    /** Stable slur identity (geometry overlay key). See [com.mecon.api.computed.ComputedSlur.slurId]. */
    val slurId: EventId,
    /** Voice event where the slur opens. */
    val startEventId: EventId,
    /** Pitch in the start event the slur anchors on (top or bottom of chord). */
    val startPitchIndex: Int,
    /** Voice event where the slur closes. */
    val endEventId: EventId,
    /** Pitch in the end event the slur anchors on. */
    val endPitchIndex: Int,

    /** Left endpoint (just past the start notehead's outer edge). */
    val start: RelativePoint,
    /** Right endpoint (just before the end notehead's outer edge). */
    val end: RelativePoint,

    /**
     * Lower bound on apex height the curve builder must respect.
     * Pre-inflated to account for nesting depth and intermediate-notehead
     * collisions, so the renderer doesn't need to know those details.
     */
    val minApexHeight: StaffSpace,

    /**
     * Upper bound on apex height for this phrasing slur. Collision avoidance
     * may ask for more, but long slurs should stay visually shallow instead
     * of consuming an entire staff band.
     */
    val maxApexHeight: StaffSpace = StaffSpace(3.0f),

    /**
     * How much the curve's perpendicular control direction should inherit
     * from the endpoint slope. Long or steep slurs use a lower value so the
     * middle of the curve stays calmer instead of twisting with the baseline.
     */
    val slopeDamping: Float = 1f,

    /**
     * Long slurs can ask the shared curve builder to split the lens into
     * endpoint curves plus a flatter middle that runs parallel to the endpoint
     * baseline. 0 keeps the historical single-cubic shape.
     */
    val middleStraightening: Float = 0f,

    /** Above bows upward at the apex; below bows downward. */
    val direction: SlurDirection,

    /** Nesting depth at the time the slur opened (0 = outermost on the track). */
    val nestingLevel: Int,

    /** Staff hosting the slur (used for hit-test enrichment). */
    val staffIndex: Int,
    /** System hosting this concrete stub. Cross-system slurs have one layout per endpoint system. */
    val systemIndex: Int,
    /** Staff track ID. */
    val trackId: TrackId,
    /** Measure hosting this concrete stub (start measure for the source stub, end measure for the target stub). */
    val measureNumber: Int,
)
