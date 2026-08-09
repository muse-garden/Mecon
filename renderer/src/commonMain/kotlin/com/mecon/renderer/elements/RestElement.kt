package com.mecon.renderer.elements

import com.mecon.api.interaction.VoiceEventSection
import com.mecon.renderer.enums.RestType
import com.mecon.renderer.geometry.*
import com.mecon.renderer.render.*
import com.mecon.renderer.smufl.GlyphInfo
import com.mecon.renderer.smufl.SmuflGlyphs
import kotlinx.serialization.Serializable

/**
 * Geometry for a rest symbol.
 */
@Serializable
data class RestElement(
    val position: RelativePoint,
    val type: RestType
) {
    /**
     * Get the SMuFL glyph for this rest type.
     */
    fun getGlyph(): GlyphInfo = type.glyph

    companion object {
        /**
         * Get the default staff position for a rest type.
         * Most rests are centered on the staff.
         */
        fun defaultStaffPosition(type: RestType): Int = when (type) {
            RestType.WHOLE -> 4      // Hanging from line 4
            RestType.HALF -> 2       // Sitting on line 3
            else -> 0                // Centered on middle line
        }

        /**
         * Create a rest at the standard position for its type.
         */
        fun atDefaultPosition(
            type: RestType,
            x: StaffSpace,
            staffOriginY: StaffSpace
        ): RestElement {
            val staffPosition = defaultStaffPosition(type)
            val y = staffOriginY + StaffSpace(2f - staffPosition * 0.5f)
            return RestElement(
                position = RelativePoint(x, y),
                type = type
            )
        }

        /**
         * Convert duration denominator to rest type.
         */
        fun fromDurationDenominator(denominator: Int): RestType = when (denominator) {
            1 -> RestType.WHOLE
            2 -> RestType.HALF
            4 -> RestType.QUARTER
            8 -> RestType.EIGHTH
            16 -> RestType.SIXTEENTH
            32 -> RestType.THIRTY_SECOND
            64 -> RestType.SIXTY_FOURTH
            128 -> RestType.ONE_TWENTY_EIGHTH
            256 -> RestType.TWO_FIFTY_SIXTH
            else -> RestType.QUARTER
        }
    }
}
