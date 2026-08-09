package com.mecon.renderer.elements

import com.mecon.renderer.enums.NoteheadType
import com.mecon.renderer.geometry.*
import com.mecon.renderer.smufl.BravuraFont
import com.mecon.renderer.smufl.GlyphInfo
import com.mecon.renderer.smufl.SmuflGlyphs
import com.mecon.renderer.smufl.SmuflMetadata
import kotlinx.serialization.Serializable

/**
 * Geometry for a notehead.
 *
 * The origin point represents the SMuFL glyph origin:
 * - x: left edge of the notehead
 * - y: vertical center (staff line position)
 */
@Serializable
data class NoteheadElement(
    val origin: RelativePoint,
    val type: NoteheadType,
    val width: StaffSpace,
    val height: StaffSpace
) {
    /**
     * Left edge X coordinate.
     */
    val left: StaffSpace get() = origin.x

    /**
     * Right edge X coordinate.
     */
    val right: StaffSpace get() = origin.x + width

    /**
     * Top edge Y coordinate (screen coords: Y down).
     */
    val top: StaffSpace get() = origin.y - height / 2

    /**
     * Bottom edge Y coordinate (screen coords: Y down).
     */
    val bottom: StaffSpace get() = origin.y + height / 2

    /**
     * Visual center point.
     */
    val center: RelativePoint get() = RelativePoint(origin.x + width / 2, origin.y)

    /**
     * Bounding box.
     */
    val bounds: RelativeRect get() = RelativeRect(
        origin = RelativePoint(left, top),
        width = width,
        height = height
    )

    /**
     * Get the SMuFL glyph for this notehead type.
     */
    fun getGlyph(): GlyphInfo = type.glyph

    /**
     * Stem attachment point for stem-up notes.
     * Returns the right edge at the center height.
     */
    val stemUpAttachment: RelativePoint get() = RelativePoint(right, origin.y)

    /**
     * Stem attachment point for stem-down notes.
     * Returns the left edge at the center height.
     */
    val stemDownAttachment: RelativePoint get() = RelativePoint(left, origin.y)

    companion object {
        /**
         * Get width for a notehead type from BravuraFont metadata.
         */
        context(BravuraFont)
        fun defaultWidth(type: NoteheadType): StaffSpace {
            return this@BravuraFont.getNoteheadWidth(type.glyphName) ?: SmuflMetadata.DEFAULT_NOTEHEAD_WIDTH
        }

        /**
         * Get height for a notehead type from BravuraFont metadata.
         */
        context(BravuraFont)
        fun defaultHeight(type: NoteheadType): StaffSpace {
            return this@BravuraFont.getNoteheadHeight(type.glyphName) ?: SmuflMetadata.DEFAULT_NOTEHEAD_HEIGHT
        }

        /**
         * Create a notehead at a specific staff position.
         *
         * @param staffPosition Staff position (0 = middle line, positive = up, negative = down)
         * @param staffOriginY Y coordinate of staff top line
         * @param x X coordinate for the left edge of the notehead
         * @param type Notehead type
         */
        context(BravuraFont)
        fun atStaffPosition(
            staffPosition: Int,
            staffOriginY: StaffSpace,
            x: StaffSpace,
            type: NoteheadType
        ): NoteheadElement {
            // Convert staff position to Y coordinate (center line of notehead)
            val y = staffOriginY + StaffSpace(2f - staffPosition * 0.5f)
            return NoteheadElement(
                origin = RelativePoint(x, y),
                type = type,
                width = defaultWidth(type),
                height = defaultHeight(type)
            )
        }
    }
}

/**
 * Geometry for an augmentation dot.
 */
@Serializable
data class AugmentationDotElement(
    val center: RelativePoint,
    val dotIndex: Int = 0  // 0 for first dot, 1 for second (double dotted), etc.
) {
    fun getGlyph(): GlyphInfo = SmuflGlyphs.augmentationDot

    companion object {
        /** Standard spacing between dots */
        val DOT_SPACING = StaffSpace(0.25f)

        /** Offset from notehead to first dot */
        val NOTEHEAD_DOT_OFFSET = StaffSpace(0.4f)

        /**
         * Generate dot geometries for a dotted note.
         */
        fun forDottedNote(
            noteheadRight: StaffSpace,
            noteheadCenterY: StaffSpace,
            dotCount: Int,
            isOnLine: Boolean = false
        ): List<AugmentationDotElement> {
            // If note is on a line, dots are placed in the space above
            val dotY = if (isOnLine) noteheadCenterY - StaffSpace(0.5f) else noteheadCenterY

            return (0 until dotCount).map { index ->
                val dotX = noteheadRight + NOTEHEAD_DOT_OFFSET + DOT_SPACING * index
                AugmentationDotElement(
                    center = RelativePoint(dotX, dotY),
                    dotIndex = index
                )
            }
        }
    }
}
