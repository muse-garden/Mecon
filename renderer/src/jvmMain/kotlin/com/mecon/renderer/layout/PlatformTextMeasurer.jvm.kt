package com.mecon.renderer.layout

import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import kotlin.math.ceil

internal actual object PlatformTextMeasurer {
    private val fontRenderContext = FontRenderContext(AffineTransform(), true, true)

    actual fun measure(
        text: String,
        fontFamily: String,
        fontSizePx: Float
    ): TextMeasurement {
        if (fontSizePx <= 0f) return TextMeasurement(widthPx = 0f, heightPx = 0f)

        val family = fontFamily.ifBlank { Font.SANS_SERIF }
        val font = Font(family, Font.PLAIN, 1).deriveFont(fontSizePx)
        val measuredText = text.ifEmpty { " " }
        val bounds = font.getStringBounds(text, fontRenderContext)
        val lineMetrics = font.getLineMetrics(measuredText, fontRenderContext)

        return TextMeasurement(
            widthPx = ceil(bounds.width).toFloat().coerceAtLeast(0f),
            heightPx = ceil(lineMetrics.height).toFloat().coerceAtLeast(fontSizePx)
        )
    }
}
