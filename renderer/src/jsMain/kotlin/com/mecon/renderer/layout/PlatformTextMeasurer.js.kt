package com.mecon.renderer.layout

import kotlin.math.ceil

/**
 * Deterministic browser-safe fallback used by the layout engine.
 *
 * The engine facade deliberately does not read DOM state: browser font metrics differ by
 * browser and loading phase. Frozen music glyph geometry comes from SMuFL metadata; ordinary
 * text uses this stable approximation so the worker can run without a canvas or document.
 */
internal actual object PlatformTextMeasurer {
    actual fun measure(
        text: String,
        fontFamily: String,
        fontSizePx: Float,
    ): TextMeasurement {
        if (fontSizePx <= 0f) return TextMeasurement(0f, 0f)
        val widthFactor = when {
            fontFamily.contains("mono", ignoreCase = true) -> 0.62f
            fontFamily.contains("serif", ignoreCase = true) -> 0.56f
            else -> 0.54f
        }
        val width = text.sumOf { character ->
            when {
                character.isWhitespace() -> (fontSizePx * 0.32f).toDouble()
                character.code >= 0x2E80 -> fontSizePx.toDouble()
                character.isUpperCase() -> (fontSizePx * (widthFactor + 0.08f)).toDouble()
                else -> (fontSizePx * widthFactor).toDouble()
            }
        }
        return TextMeasurement(
            widthPx = ceil(width).toFloat(),
            heightPx = ceil(fontSizePx * 1.2f),
        )
    }
}
