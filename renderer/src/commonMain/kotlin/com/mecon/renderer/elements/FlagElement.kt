package com.mecon.renderer.elements

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TrackId
import com.mecon.renderer.enums.StemDirection
import com.mecon.renderer.geometry.*
import com.mecon.api.interaction.VoiceEventSection
import com.mecon.api.interaction.VoiceFlagSection
import com.mecon.renderer.render.*
import com.mecon.renderer.smufl.BravuraFont
import com.mecon.renderer.smufl.GlyphInfo

/**
 * A flag element that implements [RenderableElement] but not [LayoutElement].
 *
 * Flags are decorative glyphs at the tip of unbeamed stems (8th notes and shorter).
 * They depend on stem position and do not participate in time-slot layout.
 *
 * All coordinates are absolute in the staff-space system.
 */
data class FlagElement(
    /** Event ID for section registration */
    val eventId: EventId,
    /** Track ID */
    val trackId: TrackId,
    /** Measure number */
    val measureNumber: Int,
    /** Staff index */
    val staffIndex: Int,
    /** X position of the flag (absolute in staff-space) */
    val flagX: StaffSpace,
    /** Y position of the flag (absolute in staff-space) */
    val flagY: StaffSpace,
    /** Number of flags (1=8th, 2=16th, etc.) */
    val flagCount: Int,
    /** Stem direction */
    val direction: StemDirection,
    /** Scale factor (< 1 for grace notes) */
    val scale: Float = 1f,
) : RenderableElement {

    context(BravuraFont)
    override fun render(context: ElementRenderContext): ElementRenderOutput {
        val glyph = getFlagGlyph(flagCount, direction)
        val position = context.transformer.toAbsolute(RelativePoint(flagX, flagY))
        val fontSize = context.transformer.toPixels(StaffSpace(4f * scale))
        val command = RenderHelpers.createGlyphCommand(glyph, position, fontSize)

        val elemId = context.idGenerator()
        val element = renderElement(elemId, RenderElementType.FLAG)
            .addCommand(command)
            .eventId(eventId)
            .trackId(trackId)
            .measureNumber(measureNumber)
            .staffIndex(staffIndex)
            .build()

        val sections = mutableListOf<SectionRegistration>()
        val computedEvent = context.computedScore.getComputedEvent(eventId)
        if (computedEvent != null) {
            sections.add(SectionRegistration(VoiceEventSection(computedEvent), elemId))
            sections.add(SectionRegistration(VoiceFlagSection(computedEvent), elemId))
        }

        val bbox = this@BravuraFont.getBBox(glyph)
        val relBounds = if (bbox != null) {
            RelativeRect(
                origin = RelativePoint(flagX + bbox.southWest.x, flagY - bbox.northEast.y),
                width = bbox.width,
                height = bbox.height
            )
        } else {
            RelativeRect(RelativePoint(flagX, flagY), StaffSpace.ONE, StaffSpace.ONE)
        }
        val hitAreas = listOf(ElementHitArea(elemId, relBounds))

        return ElementRenderOutput(listOf(element), sections, hitAreas)
    }

    companion object {
        fun getFlagGlyph(flagCount: Int, direction: StemDirection): GlyphInfo {
            return when {
                direction == StemDirection.UP -> when (flagCount) {
                    1 -> GlyphInfo("flag8thUp", '\uE240')
                    2 -> GlyphInfo("flag16thUp", '\uE242')
                    3 -> GlyphInfo("flag32ndUp", '\uE244')
                    4 -> GlyphInfo("flag64thUp", '\uE246')
                    else -> GlyphInfo("flag8thUp", '\uE240')
                }
                else -> when (flagCount) {
                    1 -> GlyphInfo("flag8thDown", '\uE241')
                    2 -> GlyphInfo("flag16thDown", '\uE243')
                    3 -> GlyphInfo("flag32ndDown", '\uE245')
                    4 -> GlyphInfo("flag64thDown", '\uE247')
                    else -> GlyphInfo("flag8thDown", '\uE241')
                }
            }
        }
    }
}
