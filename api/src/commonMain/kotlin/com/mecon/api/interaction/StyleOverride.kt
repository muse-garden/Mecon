package com.mecon.api.interaction

import com.mecon.api.primitive.EventId
import com.mecon.api.render.RenderColor

/**
 * Style override data that can be applied to render elements.
 *
 * Non-null fields override the element's default rendering.
 * Multiple StyleOverrides can be merged via [mergeOver], where
 * the argument's non-null fields take precedence.
 */
data class StyleOverride(
    /** Override fill/stroke color for glyphs, lines, text, etc. */
    val fillColor: RenderColor? = null,
    /** Background color drawn behind the element's hit box */
    val backgroundColor: RenderColor? = null,
    /**
     * When true, the element is not drawn at all. Used by transient interactions (e.g. drag-to-
     * transpose) that redraw a moved note at a new position and need to suppress the original.
     * Checked independently of the colour pick (see [com.mecon.renderer.interaction.StyleSnapshot]).
     */
    val hidden: Boolean = false
) {
    /**
     * Merge [other] over this override.
     * Non-null fields in [other] take precedence.
     */
    fun mergeOver(other: StyleOverride): StyleOverride = StyleOverride(
        fillColor = other.fillColor ?: this.fillColor,
        backgroundColor = other.backgroundColor ?: this.backgroundColor,
        hidden = other.hidden || this.hidden
    )

    val hasAnyOverride: Boolean get() = fillColor != null || backgroundColor != null || hidden

    companion object {
        val NONE = StyleOverride()
    }
}

