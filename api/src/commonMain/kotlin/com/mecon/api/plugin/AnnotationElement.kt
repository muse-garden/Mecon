package com.mecon.api.plugin

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.render.FormattedText
import com.mecon.api.render.RenderColor

/**
 * Horizontal alignment of an [AnnotationElement] relative to its anchor point.
 */
enum class AnnotationAlignment { LEFT, CENTER, RIGHT }

/**
 * A single element drawn on an annotation staff. Coordinates are relative:
 * - [time] selects the X position via the layout's time-slot map.
 * - [relativeY] is measured in staff spaces from the annotation staff's center (0).
 *
 * The layout engine measures element bounds to compute the annotation staff's
 * vertical extents (yMin / yMax) — plugins do not touch absolute pixel coordinates.
 *
 * [sourceEventId] propagates the originating plugin event so the renderer can
 * route selection / hit-testing back to the storage event.
 *
 * [trackId] identifies the plugin track this element belongs to for horizontal
 * collision handling. Elements on the same track are laid out sequentially;
 * elements on different tracks do not reserve space from each other.
 */
sealed class AnnotationElement {
    abstract val time: TimeCode
    abstract val relativeY: Float
    abstract val sourceEventId: EventId?
    abstract val trackId: TrackId?
    /** Whether this element responds to pointer events (click-to-select). */
    abstract val interactive: Boolean

    data class Text(
        override val time: TimeCode,
        override val relativeY: Float = 0f,
        override val sourceEventId: EventId? = null,
        override val trackId: TrackId? = null,
        /** Styled content; per-run styles are relative to [fontSize] / [color]. */
        val content: FormattedText,
        /** Base font size in pixels — the 1.0× reference for run [sizeScale]s. */
        val fontSize: Float = 14f,
        val color: RenderColor = RenderColor.BLACK,
        val alignment: AnnotationAlignment = AnnotationAlignment.CENTER,
        override val interactive: Boolean = true
    ) : AnnotationElement() {
        /** Flattened plain text — for hit-testing, logging and fallback measuring. */
        val text: String get() = content.plainText

        companion object {
            /** Convenience for unstyled text producers. */
            fun plain(
                time: TimeCode,
                text: String,
                relativeY: Float = 0f,
                sourceEventId: EventId? = null,
                trackId: TrackId? = null,
                fontSize: Float = 14f,
                color: RenderColor = RenderColor.BLACK,
                alignment: AnnotationAlignment = AnnotationAlignment.CENTER,
                interactive: Boolean = true,
            ): Text = Text(
                time = time,
                relativeY = relativeY,
                sourceEventId = sourceEventId,
                trackId = trackId,
                content = FormattedText.plain(text),
                fontSize = fontSize,
                color = color,
                alignment = alignment,
                interactive = interactive,
            )
        }
    }
}
