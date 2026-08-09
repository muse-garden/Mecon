package com.mecon.renderer.elements

import com.mecon.renderer.geometry.DrawableGeometry
import com.mecon.renderer.geometry.LineGeometry
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.StaffSpace
import kotlinx.serialization.Serializable

/**
 * Pre-computed geometry for note body elements (noteheads/rests, accidentals, dots, ledger lines).
 *
 * This geometry is computed at NoteElement creation time to provide:
 * 1. Accurate minimumWidth for layout calculations
 * 2. Structured sub-element info for fine-grained rendering
 * 3. Stem attachment points for both up and down directions
 *
 * All positions are relative to:
 * - X: The time code X position (X=0 is the notehead left edge; accidentals have negative X)
 * - Y: The staff center line (Y=0 = middle line, positive Y is down)
 */
@Serializable
data class NoteBodyElement(
    /** Structured notehead info (per-pitch, for fine-grained selection) */
    val noteheads: List<NoteheadRenderInfo> = emptyList(),

    /** Structured accidental info (per-pitch) */
    val accidentals: List<AccidentalRenderInfo> = emptyList(),

    /** Structured dot info (per-pitch per-dot) */
    val dots: List<DotRenderInfo> = emptyList(),

    /** Ledger line geometries (merged, not per-pitch) */
    val ledgerLineGeometries: List<LineGeometry> = emptyList(),

    /** Stem attachment point for stem-up (right side of lowest notehead) */
    val stemUpAttachment: RelativePoint,

    /** Stem attachment point for stem-down (left side of highest notehead) */
    val stemDownAttachment: RelativePoint,

    /** Left extent (for accidentals, typically negative) */
    val leftExtent: StaffSpace,

    /** Right extent (for dots) */
    val rightExtent: StaffSpace
) {
    /** Computed width from left to right extent */
    val width: StaffSpace get() = rightExtent - leftExtent

    /** All drawable geometries combined (for legacy draw() path and minimumWidth) */
    val geometryList: List<DrawableGeometry>
        get() = buildList {
            addAll(noteheads.map { it.geometry })
            addAll(accidentals.map { it.geometry })
            addAll(dots.map { it.geometry })
            addAll(ledgerLineGeometries)
        }

    companion object {
        /**
         * Empty geometry for rests or when no geometry is computed.
         */
        val EMPTY = NoteBodyElement(
            stemUpAttachment = RelativePoint.ZERO,
            stemDownAttachment = RelativePoint.ZERO,
            leftExtent = StaffSpace.ZERO,
            rightExtent = StaffSpace.ZERO
        )
    }
}
