package com.mecon.renderer.render

import com.mecon.api.render.FormattedText
import com.mecon.api.render.RenderColor
import com.mecon.renderer.geometry.*
import com.mecon.renderer.smufl.GlyphInfo
import kotlinx.serialization.Serializable

/**
 * Base interface for all render commands.
 *
 * Render commands are platform-agnostic drawing instructions that can be
 * executed by different rendering backends (Compose Canvas, SVG, PDF, etc.)
 */
@Serializable
sealed interface RenderCommand {
    /** Bounding box of this command in absolute coordinates */
    val bounds: AbsoluteRect
}

/**
 * Draw a SMuFL glyph from the music font.
 */
@Serializable
data class DrawGlyph(
    /** Position (baseline origin) in absolute coordinates */
    val position: AbsolutePoint,
    /** Glyph to draw */
    val glyph: GlyphInfo,
    /** Font size in pixels */
    val fontSize: Pixels,
    /** Horizontal scale applied around [position]. Defaults to the natural glyph width. */
    val scaleX: Float = 1f,
    /** Vertical scale applied around [position]. Defaults to the natural glyph height. */
    val scaleY: Float = 1f,
    /** Color (ARGB) */
    val color: RenderColor = RenderColor.BLACK,
    override val bounds: AbsoluteRect
) : RenderCommand

/**
 * Draw a straight line.
 */
@Serializable
data class DrawLine(
    /** Start point */
    val start: AbsolutePoint,
    /** End point */
    val end: AbsolutePoint,
    /** Line thickness */
    val thickness: Pixels,
    /** Color */
    val color: RenderColor = RenderColor.BLACK,
    /** Line cap style */
    val cap: LineCap = LineCap.BUTT,
    /**
     * Optional dash pattern in pixels (on, off, on, off, …). Null = solid line.
     * Used for hairpin `cresc.`/`dim.` continuation lines.
     */
    val dashIntervals: List<Float>? = null,
    override val bounds: AbsoluteRect
) : RenderCommand

/**
 * Draw a filled rectangle.
 */
@Serializable
data class DrawRect(
    /** Rectangle bounds */
    val rect: AbsoluteRect,
    /** Fill color (null for no fill) */
    val fillColor: RenderColor? = RenderColor.BLACK,
    /** Stroke color (null for no stroke) */
    val strokeColor: RenderColor? = null,
    /** Stroke thickness */
    val strokeThickness: Pixels = Pixels(1f),
    override val bounds: AbsoluteRect = rect
) : RenderCommand

/**
 * Draw a filled path.
 */
@Serializable
data class DrawPath(
    /** Path to draw */
    val path: AbsolutePath,
    /** Fill color (null for no fill) */
    val fillColor: RenderColor? = RenderColor.BLACK,
    /** Stroke color (null for no stroke) */
    val strokeColor: RenderColor? = null,
    /** Stroke thickness */
    val strokeThickness: Pixels = Pixels(1f),
    override val bounds: AbsoluteRect
) : RenderCommand

/**
 * Draw a cubic bezier curve.
 */
@Serializable
data class DrawBezier(
    /** Bezier curve */
    val curve: AbsoluteCubicBezier,
    /** Stroke color */
    val color: RenderColor = RenderColor.BLACK,
    /** Stroke thickness at endpoints */
    val endpointThickness: Pixels,
    /** Stroke thickness at midpoint */
    val midpointThickness: Pixels,
    /** Whether to fill the curve (for thick slurs/ties) */
    val filled: Boolean = true,
    override val bounds: AbsoluteRect
) : RenderCommand

/**
 * Draw text (for lyrics, dynamics text, etc.)
 */
@Serializable
data class DrawText(
    /** Position (baseline origin) */
    val position: AbsolutePoint,
    /** Text content */
    val text: String,
    /** Font family */
    val fontFamily: String = "serif",
    /** Font size */
    val fontSize: Pixels,
    /** Font weight */
    val fontWeight: FontWeight = FontWeight.NORMAL,
    /** Font style */
    val fontStyle: FontStyle = FontStyle.NORMAL,
    /** Color */
    val color: RenderColor = RenderColor.BLACK,
    /** Text alignment */
    val alignment: TextAlignment = TextAlignment.LEFT,
    /**
     * Optional styled content. When non-null the backend renders per-run styling
     * ([fontSize] is the 1.0× base; [color]/[fontWeight]/[fontStyle] are the
     * inherited defaults) instead of the plain [text]. [text] still carries the
     * flattened string for measuring / accessibility fallbacks.
     */
    val richText: FormattedText? = null,
    override val bounds: AbsoluteRect
) : RenderCommand

/**
 * Draw an ellipse/circle.
 */
@Serializable
data class DrawEllipse(
    /** Center point */
    val center: AbsolutePoint,
    /** Horizontal radius */
    val radiusX: Pixels,
    /** Vertical radius */
    val radiusY: Pixels,
    /** Fill color (null for no fill) */
    val fillColor: RenderColor? = RenderColor.BLACK,
    /** Stroke color (null for no stroke) */
    val strokeColor: RenderColor? = null,
    /** Stroke thickness */
    val strokeThickness: Pixels = Pixels(1f),
    override val bounds: AbsoluteRect
) : RenderCommand

/**
 * Group of render commands (for logical grouping and transformations).
 */
@Serializable
data class RenderGroup(
    /** Child commands */
    val commands: List<RenderCommand>,
    /** Optional clip rectangle */
    val clipRect: AbsoluteRect? = null,
    /** Optional opacity (0.0 to 1.0) */
    val opacity: Float = 1.0f,
    override val bounds: AbsoluteRect
) : RenderCommand

