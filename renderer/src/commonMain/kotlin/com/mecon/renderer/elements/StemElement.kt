package com.mecon.renderer.elements

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TrackId
import com.mecon.renderer.enums.StemDirection
import com.mecon.renderer.geometry.*
import com.mecon.api.interaction.VoiceEventSection
import com.mecon.api.interaction.VoiceStemSection
import com.mecon.api.render.RenderColor
import com.mecon.renderer.render.*
import com.mecon.renderer.smufl.BravuraFont

/**
 * A stem element that implements [RenderableElement] but not [LayoutElement].
 *
 * Stems depend on beam adjustments and do not participate in time-slot layout.
 * They are created after layout is complete, using the resolved stem coordinates.
 *
 * All coordinates are absolute in the staff-space system (not relative to a slot).
 */
data class StemElement(
    /** Event ID for section registration */
    val eventId: EventId,
    /** Track ID */
    val trackId: TrackId,
    /** Measure number */
    val measureNumber: Int,
    /** Staff index */
    val staffIndex: Int,
    /** X position of the stem (absolute in staff-space) */
    val stemX: StaffSpace,
    /** Top Y position of the stem */
    val topY: StaffSpace,
    /** Bottom Y position of the stem */
    val bottomY: StaffSpace,
    /** Stem direction */
    val direction: StemDirection,
    /** Stem thickness */
    val thickness: StaffSpace
) : RenderableElement {

    context(BravuraFont)
    override fun render(context: ElementRenderContext): ElementRenderOutput {
        val stemLine = RelativeLine.vertical(
            x = stemX,
            startY = topY,
            endY = bottomY,
            thickness = thickness
        )
        val absStemLine = context.transformer.toAbsolute(stemLine)

        val elemId = context.idGenerator()
        val element = renderElement(elemId, RenderElementType.STEM)
            .addCommand(DrawLine(
                start = absStemLine.start,
                end = absStemLine.end,
                thickness = absStemLine.thickness,
                color = RenderColor.BLACK,
                bounds = RenderHelpers.calculateLineBounds(absStemLine)
            ))
            .eventId(eventId)
            .trackId(trackId)
            .measureNumber(measureNumber)
            .staffIndex(staffIndex)
            .build()

        val sections = mutableListOf<SectionRegistration>()
        val computedEvent = context.computedScore.getComputedEvent(eventId)
        if (computedEvent != null) {
            sections.add(SectionRegistration(VoiceEventSection(computedEvent), elemId))
            sections.add(SectionRegistration(VoiceStemSection(computedEvent), elemId))
        }

        val minY = minOf(topY, bottomY)
        val maxY = maxOf(topY, bottomY)
        val relBounds = RelativeRect(
            origin = RelativePoint(stemX - thickness / 2f, minY),
            width = thickness,
            height = StaffSpace(maxY.value - minY.value)
        )
        val hitAreas = listOf(ElementHitArea(elemId, relBounds))

        return ElementRenderOutput(listOf(element), sections, hitAreas)
    }
}
