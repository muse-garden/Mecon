package com.mecon.renderer.layout

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TrackId
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.SlurDirection
import com.mecon.renderer.geometry.StaffSpace
import kotlinx.serialization.Serializable

/**
 * Pre-computed layout for a single tie (or let-ring marking).
 *
 * Positions are absolute within the rendered score (i.e. the time-slot X
 * positions have already been resolved and the staff center Y has already
 * been folded into the Y values).
 *
 * Let-ring ties carry a synthetic [end] point: no target notehead exists,
 * so the curve trails off into space a fixed distance to the right of the
 * source notehead.
 */
@Serializable
data class TieLayout(
    /** Source voice event (the one that "starts" the tie). */
    val sourceEventId: EventId,
    /** Index into the source event's pitch data — identifies which note in a chord. */
    val sourcePitchIndex: Int,
    /** Target voice event for normal ties, null for let-ring. */
    val targetEventId: EventId?,

    /** Left endpoint (just past the source notehead's right edge). */
    val start: RelativePoint,
    /** Right endpoint (just before the target notehead's left edge, or synthesized for let-ring). */
    val end: RelativePoint,

    /** Collision-aware lower bound for the curve apex. */
    val minApexHeight: StaffSpace = StaffSpace(0.5f),
    /** Upper bound; collision avoidance may raise this together with [minApexHeight]. */
    val maxApexHeight: StaffSpace = StaffSpace(2.5f),
    val slopeDamping: Float = 1f,
    val middleStraightening: Float = 0f,

    /** Above bows downward at the apex; below bows upward. */
    val direction: SlurDirection,

    /** True if this is a laissez vibrer marking with no target. */
    val isLetRing: Boolean,

    /** Staff hosting the source note (used for hit-test enrichment). */
    val staffIndex: Int,
    /** Track ID of the source note. */
    val trackId: TrackId,
    /** Measure number of the source note. */
    val measureNumber: Int
)
