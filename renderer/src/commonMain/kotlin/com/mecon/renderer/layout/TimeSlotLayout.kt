package com.mecon.renderer.layout

import com.mecon.api.primitive.Accidental
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.renderer.enums.NoteheadType
import com.mecon.renderer.enums.RestType
import com.mecon.renderer.geometry.StaffSpace
import kotlinx.serialization.Serializable

/**
 * Base interface for element layouts with relative coordinates.
 *
 * All positions are relative to:
 * - X: The time code X position (from TimeSlotLayout or TimeCoordinateMap)
 * - Y: The staff center line (middle line of the staff)
 *
 * This design allows:
 * 1. Element selection by filtering time code X first, then checking relative positions
 * 2. Multi-line layout adjustments without recalculating element offsets
 * 3. Clean separation between time mapping and element positioning
 */
@Serializable
sealed interface ElementLayout {
    /** The event ID this element belongs to */
    val eventId: EventId

    /** The track ID (staff track) this element is on */
    val trackId: TrackId

    /** X offset from time code X position (usually 0, can be negative for accidentals) */
    val relativeX: StaffSpace

    /** Y offset from staff center line (0 = middle line, negative = up, positive = down) */
    val relativeY: StaffSpace

    /** Staff index within the system */
    val staffIndex: Int
}

/**
 * Layout for a notehead element.
 *
 * The primary element for notes. Chords have multiple NoteheadLayout instances.
 */
@Serializable
data class NoteheadLayout(
    override val eventId: EventId,
    override val trackId: TrackId,
    override val relativeX: StaffSpace,
    override val relativeY: StaffSpace,
    override val staffIndex: Int,
    /** The notehead type (whole, half, black, etc.) */
    val noteheadType: NoteheadType,
    /** Staff position (0 = middle line, positive = above, negative = below) */
    val staffPosition: Int,
    /** Accidental to display (null = no accidental needed) */
    val accidental: Accidental? = null,
    /** Whether this notehead needs ledger lines */
    val needsLedgerLines: Boolean = false,
    /** Number of ledger lines needed (positive = above, negative = below) */
    val ledgerLineCount: Int = 0
) : ElementLayout {
    /**
     * Check if this note is on a staff line (as opposed to a space).
     * Used for dot placement.
     */
    val isOnLine: Boolean get() = staffPosition % 2 == 0

    companion object {
        /**
         * Calculate relative Y from staff position.
         * Staff position 0 = middle line = relativeY 0
         * Each staff position step = 0.5 staff spaces
         * Positive staff positions go up (negative Y)
         */
        fun relativeYFromStaffPosition(staffPosition: Int): StaffSpace =
            StaffSpace(-staffPosition * 0.5f)

        /**
         * Calculate ledger line count from staff position.
         * Returns positive for lines above staff, negative for lines below.
         * For a standard 5-line staff: lines at positions -4, -2, 0, 2, 4
         * Position 5 = 1 ledger line above (position 6)
         * Position -5 = 1 ledger line below (position -6)
         */
        fun ledgerLineCountFromStaffPosition(staffPosition: Int, staffLineCount: Int = 5): Int {
            val halfStaffHeight = staffLineCount - 1  // 4 for 5-line staff
            return when {
                staffPosition > halfStaffHeight -> (staffPosition - halfStaffHeight + 1) / 2
                staffPosition < -halfStaffHeight -> (staffPosition + halfStaffHeight - 1) / 2
                else -> 0
            }
        }
    }
}

/**
 * Layout for a rest element.
 */
@Serializable
data class RestLayout(
    override val eventId: EventId,
    override val trackId: TrackId,
    override val relativeX: StaffSpace,
    override val relativeY: StaffSpace,
    override val staffIndex: Int,
    /** The rest type (whole, half, quarter, etc.) */
    val restType: RestType
) : ElementLayout {
    companion object {
        /**
         * Get the default staff position for a rest type (0 = middle line, +up, -down;
         * one step per half staff space). Whole rests hang from the line above the
         * middle (position 2); other rests sit centred on the middle line (position 0).
         *
         * This is the fallback used when [com.mecon.api.storage.RenderingProps.restStaffPosition]
         * is null. The convention matches [com.mecon.api.computed.ComputedPitchData.staffPosition].
         */
        fun defaultRestStaffPosition(type: RestType): Int = when (type) {
            RestType.WHOLE -> 2     // Hanging from the line above the middle
            else -> 0               // Centred on the middle line
        }

        /** Default staff position for a rest of [duration] (resolves the rest type first). */
        fun defaultRestStaffPosition(duration: Duration): Int =
            defaultRestStaffPosition(restTypeFromDuration(duration))

        /** Relative Y (staff spaces; +down) for a rest at [staffPosition]. */
        fun relativeY(staffPosition: Int): StaffSpace = StaffSpace(-staffPosition * 0.5f)

        /**
         * Get the default relative Y for a rest type.
         * Most rests are centered, but whole rests hang and half rests sit.
         */
        fun defaultRelativeY(type: RestType): StaffSpace =
            relativeY(defaultRestStaffPosition(type))
    }
}
