package com.mecon.renderer.layout

internal data class TextMeasurement(
    val widthPx: Float,
    val heightPx: Float
)

internal expect object PlatformTextMeasurer {
    fun measure(
        text: String,
        fontFamily: String,
        fontSizePx: Float
    ): TextMeasurement
}
