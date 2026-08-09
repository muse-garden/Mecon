package com.mecon.api.render

import kotlinx.serialization.Serializable

/**
 * Color representation (ARGB).
 */
@Serializable
data class RenderColor(
    val alpha: Int,
    val red: Int,
    val green: Int,
    val blue: Int
) {
    init {
        require(alpha in 0..255) { "Alpha must be 0-255" }
        require(red in 0..255) { "Red must be 0-255" }
        require(green in 0..255) { "Green must be 0-255" }
        require(blue in 0..255) { "Blue must be 0-255" }
    }

    /** Convert to ARGB integer */
    fun toArgb(): Int = (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    /** Convert to hex string */
    fun toHex(): String = buildString(9) {
        append('#')
        append(alpha.hexByte())
        append(red.hexByte())
        append(green.hexByte())
        append(blue.hexByte())
    }

    companion object {
        val BLACK = RenderColor(255, 0, 0, 0)
        val WHITE = RenderColor(255, 255, 255, 255)
        val RED = RenderColor(255, 255, 0, 0)
        val GREEN = RenderColor(255, 0, 255, 0)
        val BLUE = RenderColor(255, 0, 0, 255)
        val TRANSPARENT = RenderColor(0, 0, 0, 0)

        /** Create from ARGB integer */
        fun fromArgb(argb: Int): RenderColor = RenderColor(
            alpha = (argb shr 24) and 0xFF,
            red = (argb shr 16) and 0xFF,
            green = (argb shr 8) and 0xFF,
            blue = argb and 0xFF
        )

        /** Create from RGB with full opacity */
        fun rgb(red: Int, green: Int, blue: Int): RenderColor =
            RenderColor(255, red, green, blue)

        /** Create with alpha */
        fun rgba(red: Int, green: Int, blue: Int, alpha: Int): RenderColor =
            RenderColor(alpha, red, green, blue)
    }
}

private fun Int.hexByte(): String = toString(16).padStart(2, '0').uppercase()
