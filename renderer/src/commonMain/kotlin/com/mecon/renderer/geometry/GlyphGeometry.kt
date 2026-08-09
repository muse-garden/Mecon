package com.mecon.renderer.geometry

import com.mecon.renderer.render.CoordinateTransformer
import com.mecon.renderer.render.RenderCommand
import com.mecon.renderer.render.RenderHelpers
import com.mecon.renderer.smufl.BravuraFont
import com.mecon.renderer.smufl.GlyphBBox
import com.mecon.renderer.smufl.GlyphInfo
import kotlinx.serialization.Serializable

/**
 * A glyph to be rendered at a specific position.
 *
 * @property glyph The SMuFL glyph to render
 * @property position Position relative to the event's anchor point (Y=0 is staff center)
 * @property bounds Computed bounding box for collision detection and width calculation
 */
@Serializable
data class GlyphGeometry(
    val glyph: GlyphInfo,
    val position: RelativePoint,
    override val bounds: RelativeRect,
    /** Visual scale multiplier applied to font size and bounds (1.0 = normal). */
    val scale: Float = 1f
) : DrawableGeometry {

    context(BravuraFont)
    override fun draw(
        offset: RelativePoint,
        transformer: CoordinateTransformer
    ): List<RenderCommand> {
        // Apply offset to position
        val finalPosition = RelativePoint(
            position.x + offset.x,
            position.y + offset.y
        )

        // Convert to absolute coordinates
        val absPosition = transformer.toAbsolute(finalPosition)
        val fontSize = transformer.toPixels(StaffSpace(4f * scale))

        // Create glyph command using helper
        val command = RenderHelpers.createGlyphCommand(glyph, absPosition, fontSize)

        return listOf(command)
    }

    companion object {
        /**
         * Create a GlyphGeometry from a glyph and position, computing bounds from bbox.
         *
         * @param glyph The glyph to render
         * @param position The position for the glyph
         * @param bbox The glyph's bounding box (from font metadata)
         * @param scale Visual scale multiplier (1.0 = normal). Used by grace notes.
         */
        fun fromBBox(
            glyph: GlyphInfo,
            position: RelativePoint,
            bbox: GlyphBBox?,
            scale: Float = 1f
        ): GlyphGeometry {
            val bounds = if (bbox != null) {
                // Convert SMuFL bbox (Y-up) to render coordinates (Y-down).
                // bbox is in unscaled staff spaces; scale around `position`.
                RelativeRect(
                    origin = RelativePoint(
                        position.x + bbox.southWest.x * scale,
                        position.y - bbox.northEast.y * scale
                    ),
                    width = bbox.width * scale,
                    height = bbox.height * scale
                )
            } else {
                // Fallback: estimate bounds
                RelativeRect(position, StaffSpace.ONE, StaffSpace.ONE)
            }
            return GlyphGeometry(glyph, position, bounds, scale)
        }
    }
}
