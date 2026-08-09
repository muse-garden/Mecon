package com.mecon.api.render

import kotlinx.serialization.Serializable

/**
 * Vertical baseline placement of a [TextRun] relative to the surrounding text.
 * Superscript / subscript runs are shifted up / down without changing the run's
 * advance width (used for chord extensions, figured-bass numerals, etc.).
 */
@Serializable
enum class TextBaseline { NORMAL, SUPERSCRIPT, SUBSCRIPT }

/**
 * Style applied to a single [TextRun]. All fields are relative to the *base*
 * style of the host element (its font size / color), so the same [FormattedText]
 * can be rendered at any base size and any inherited color.
 *
 * @property sizeScale multiple of the base font size (e.g. 0.75 for a chord suffix)
 * @property color null = inherit the host element's color
 */
@Serializable
data class TextRunStyle(
    val sizeScale: Float = 1f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val baseline: TextBaseline = TextBaseline.NORMAL,
    val color: RenderColor? = null,
) {
    companion object {
        val DEFAULT = TextRunStyle()
    }
}

/** A contiguous span of text sharing one [TextRunStyle]. */
@Serializable
data class TextRun(
    val text: String,
    val style: TextRunStyle = TextRunStyle.DEFAULT,
)

/**
 * Platform-agnostic rich-text model: an ordered list of styled [TextRun]s.
 *
 * This is the shared "formatted text" convention for every textual mark on the
 * score — chord symbols today, expression terms / tempo marks / lyrics in the
 * future. Producers (e.g. `ChordSymbolFormatter` in `theory`) emit a
 * [FormattedText]; consumers (annotation staff renderer, right-hand panels)
 * apply the runs against their own base size / color. Because it lives in `api`
 * it is reachable from every module without creating a dependency cycle.
 */
@Serializable
data class FormattedText(
    val runs: List<TextRun>,
) {
    val plainText: String get() = runs.joinToString(separator = "") { it.text }
    val isBlank: Boolean get() = plainText.isBlank()

    companion object {
        val EMPTY = FormattedText(emptyList())

        /** A single unstyled run — convenience for plain-text producers. */
        fun plain(text: String): FormattedText = FormattedText(listOf(TextRun(text)))
    }
}
