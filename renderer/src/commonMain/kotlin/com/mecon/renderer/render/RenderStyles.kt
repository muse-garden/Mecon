package com.mecon.renderer.render

import kotlinx.serialization.Serializable

/**
 * Line cap styles.
 */
enum class LineCap {
    BUTT,
    ROUND,
    SQUARE
}

/**
 * Font weight.
 */
enum class FontWeight {
    THIN,
    LIGHT,
    NORMAL,
    MEDIUM,
    BOLD,
    BLACK
}

/**
 * Font style.
 */
enum class FontStyle {
    NORMAL,
    ITALIC
}

/**
 * Text alignment.
 */
enum class TextAlignment {
    LEFT,
    CENTER,
    RIGHT
}
