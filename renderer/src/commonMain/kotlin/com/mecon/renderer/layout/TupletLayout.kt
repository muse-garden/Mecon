package com.mecon.renderer.layout

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.events.TupletDisplayStyle
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.SlurDirection
import com.mecon.renderer.geometry.StaffSpace
import kotlinx.serialization.Serializable

/**
 * Pre-computed layout for a single tuplet span (bracket / slur / number).
 *
 * Like [TieLayout], all coordinates are already resolved against the post-layout
 * X positions of the first and last events in the group, with the staff center
 * folded into Y. The [TupletElement] renderer only needs to translate the style
 * into concrete strokes/glyphs.
 *
 * [direction] follows the same sign convention as ties — ABOVE = bow upward
 * (smaller Y at the apex), BELOW = bow downward. For [TupletDisplayStyle.BRACKET_AND_NUMBER]
 * the angled bracket hooks face toward the staff, opposite of [direction].
 */
@Serializable
data class TupletLayout(
    val startEventId: EventId,
    val endEventId: EventId,
    val trackId: TrackId,
    val staffIndex: Int,
    val measureNumber: Int,

    /** Left bracket/slur endpoint (just outside the first note). */
    val start: RelativePoint,
    /** Right bracket/slur endpoint (just outside the last note). */
    val end: RelativePoint,

    /** Bracket/number on which side of the notes. */
    val direction: SlurDirection,

    /** Effective display style (already demoted to NUMBER_ONLY for beamed groups). */
    val displayStyle: TupletDisplayStyle,

    /** Text to draw (typically "3", "5", "6", ...). */
    val numberText: String,

    /** Approximate visual height the number reserves in the middle of the bracket. */
    val numberSize: StaffSpace,
)
