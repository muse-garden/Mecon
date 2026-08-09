package com.mecon.renderer.elements

import com.mecon.renderer.geometry.*
import com.mecon.renderer.smufl.EngravingDefaults
import kotlinx.serialization.Serializable

/**
 * Geometry for a staff (5 horizontal lines).
 */
@Serializable
data class StaffElement(
    val origin: RelativePoint,
    val width: StaffSpace,
    val lineCount: Int = 5,
    val lineThickness: StaffSpace = EngravingDefaults.BRAVURA.staffLineThickness
) {
    /**
     * Y positions of staff lines (0 = top line).
     * In a 5-line staff:
     * - Line 0 (top) = y = 0
     * - Line 1 = y = 1
     * - Line 2 (middle) = y = 2
     * - Line 3 = y = 3
     * - Line 4 (bottom) = y = 4
     */
    fun lineY(lineIndex: Int): StaffSpace {
        require(lineIndex in 0 until lineCount) { "Line index out of range: $lineIndex" }
        return origin.y + StaffSpace(lineIndex.toFloat())
    }

    /**
     * Get all staff lines as RelativeLine objects.
     */
    fun toLines(): List<RelativeLine> {
        return (0 until lineCount).map { lineIndex ->
            RelativeLine.horizontal(
                y = lineY(lineIndex),
                startX = origin.x,
                endX = origin.x + width,
                thickness = lineThickness
            )
        }
    }

    /**
     * Convert staff line number to Y position.
     * Staff position 0 = middle line (B4 in treble clef).
     * Positive = above, negative = below.
     */
    fun staffPositionToY(position: Int): StaffSpace {
        // Middle line is at index 2 for 5-line staff
        val middleLineIndex = (lineCount - 1) / 2
        // Each staff position is 0.5 staff spaces (half the distance between lines)
        return origin.y + StaffSpace(middleLineIndex.toFloat() - position * 0.5f)
    }

    /**
     * Total height of the staff (from top line to bottom line).
     */
    val height: StaffSpace get() = StaffSpace((lineCount - 1).toFloat())

    /**
     * Bounding rectangle of the staff.
     */
    val bounds: RelativeRect get() = RelativeRect(
        origin = origin,
        width = width,
        height = height
    )

    companion object {
        /**
         * Create a standard 5-line staff.
         */
        fun standard(origin: RelativePoint, width: StaffSpace): StaffElement =
            StaffElement(origin, width, lineCount = 5)

        /**
         * Create a single-line percussion staff.
         */
        fun percussion(origin: RelativePoint, width: StaffSpace): StaffElement =
            StaffElement(origin, width, lineCount = 1)
    }
}

/**
 * Geometry for a ledger line.
 */
@Serializable
data class LedgerLineElement(
    val centerX: StaffSpace,
    val y: StaffSpace,
    val noteheadWidth: StaffSpace,
    val extension: StaffSpace = EngravingDefaults.BRAVURA.legerLineExtension,
    val thickness: StaffSpace = EngravingDefaults.BRAVURA.legerLineThickness
) {
    val startX: StaffSpace get() = centerX - noteheadWidth / 2 - extension
    val endX: StaffSpace get() = centerX + noteheadWidth / 2 + extension

    fun toLine(): RelativeLine = RelativeLine.horizontal(
        y = y,
        startX = startX,
        endX = endX,
        thickness = thickness
    )

    companion object {
        /**
         * Generate ledger lines for a note at a given staff position.
         * Staff position 0 = middle line.
         * Positive = above (ledger lines above staff).
         * Negative = below (ledger lines below staff).
         */
        fun forStaffPosition(
            staffPosition: Int,
            centerX: StaffSpace,
            staffOriginY: StaffSpace,
            noteheadWidth: StaffSpace,
            extension: StaffSpace = EngravingDefaults.BRAVURA.legerLineExtension,
            thickness: StaffSpace = EngravingDefaults.BRAVURA.legerLineThickness
        ): List<LedgerLineElement> {
            val result = mutableListOf<LedgerLineElement>()

            // Above the staff (position > 5 means above top line)
            if (staffPosition > 5) {
                // Generate ledger lines from position 6 down to the actual position
                var pos = 6
                while (pos <= staffPosition) {
                    if (pos % 2 == 0) {  // Only on line positions (even positions)
                        val y = staffOriginY + StaffSpace(2f - pos * 0.5f)
                        result.add(LedgerLineElement(centerX, y, noteheadWidth, extension, thickness))
                    }
                    pos++
                }
            }

            // Below the staff (position < -5 means below bottom line)
            if (staffPosition < -5) {
                var pos = -6
                while (pos >= staffPosition) {
                    if (pos % 2 == 0) {  // Only on line positions
                        val y = staffOriginY + StaffSpace(2f - pos * 0.5f)
                        result.add(LedgerLineElement(centerX, y, noteheadWidth, extension, thickness))
                    }
                    pos--
                }
            }

            return result
        }
    }
}
