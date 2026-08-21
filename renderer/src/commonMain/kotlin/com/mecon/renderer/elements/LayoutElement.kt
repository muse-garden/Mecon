package com.mecon.renderer.elements

import com.mecon.api.primitive.TimeCode
import com.mecon.renderer.geometry.StaffSpace
import kotlinx.serialization.Serializable

/**
 * Sealed interface for all elements that participate in time-slot layout.
 *
 * All elements are processed together in time code order to ensure proper alignment.
 * This includes notes, rests, barlines, clefs, key signatures, and time signatures.
 *
 * Each element has:
 * - time: The time code for positioning
 * - staffIndex: Which staff it belongs to (or -1 for system-wide elements like barlines)
 * - priority: For determining X order when elements share the same time code
 * - minimumWidth: Minimum width needed for layout
 * - relativeX: X offset from time slot position (calculated during layout)
 *
 * Rendering is handled by the [RenderableElement] interface, which all
 * LayoutElement implementations also implement.
 */
@Serializable
sealed interface LayoutElement {
    /** Time code for this element */
    val time: TimeCode

    /** Staff index (-1 for system-wide elements) */
    val staffIndex: Int

    /** Priority for X ordering (lower = further left) */
    val priority: Int

    /** Minimum width this element needs */
    val minimumWidth: StaffSpace

    /**
     * How far this element extends to the left of its draw anchor (X=0 in body space).
     * For notes with accidentals, this is the distance the accidental extends
     * beyond the notehead's left edge. Used by the layout engine to prevent
     * overlap with preceding elements (e.g., barlines).
     */
    val leftOverhang: StaffSpace get() = StaffSpace.ZERO

    /** X offset from time slot X position (calculated during layout) */
    val relativeX: StaffSpace

    companion object {
        // Priority constants for X ordering within a time slot
        // Lower number = further left in the layout
        // Mid-score clef changes precede a coincident barline; initial/system-header clefs follow it.
        const val PRIORITY_CLEF_CHANGE = -10
        const val PRIORITY_BARLINE = 0
        const val PRIORITY_CLEF = 10
        const val PRIORITY_KEY_SIGNATURE = 20
        const val PRIORITY_TIME_SIGNATURE = 30
        const val PRIORITY_NOTE = 100
    }
}
