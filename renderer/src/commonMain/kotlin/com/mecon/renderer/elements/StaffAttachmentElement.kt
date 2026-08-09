package com.mecon.renderer.elements

import com.mecon.api.computed.ComputedHairpin
import com.mecon.api.computed.ComputedOctaveShift
import com.mecon.api.computed.ComputedTempoKeyframe
import com.mecon.api.computed.ComputedVoltaAttachment
import com.mecon.api.computed.ComputedBreathMark
import com.mecon.api.computed.ComputedOrnamentMark
import com.mecon.api.interaction.StaffAttachmentSection
import com.mecon.api.interaction.VoltaEndingSection
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.layout.PlacedStaffAttachment
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.renderElement
import com.mecon.renderer.smufl.BravuraFont

/**
 * Renders a single [PlacedStaffAttachment] (dynamic mark or hairpin).
 *
 * Geometry was fully resolved by
 * [com.mecon.renderer.layout.StaffAttachmentLayoutComputer] with Y relative to
 * the staff centre; this element only adds the staff's `centerY` (supplied as
 * `context.offset`) and turns each [com.mecon.renderer.geometry.DrawableGeometry]
 * into commands, registering the result against its [StaffAttachmentSection].
 */
data class StaffAttachmentElement(
    val placed: PlacedStaffAttachment,
) : RenderableElement {

    context(BravuraFont)
    override fun render(context: ElementRenderContext): ElementRenderOutput {
        val offset = context.offset
        val commands = placed.geometries.flatMap { it.draw(offset, context.transformer) }
        if (commands.isEmpty()) return ElementRenderOutput.EMPTY

        val type = when (placed.attachment) {
            is ComputedBreathMark -> RenderElementType.ARTICULATION
            is ComputedOrnamentMark -> RenderElementType.ORNAMENT
            is ComputedHairpin -> RenderElementType.HAIRPIN
            is ComputedOctaveShift -> RenderElementType.OCTAVE_SHIFT
            is ComputedTempoKeyframe -> RenderElementType.TEMPO_MARKING
            is ComputedVoltaAttachment -> RenderElementType.VOLTA_ENDING
            else -> RenderElementType.DYNAMIC
        }

        val elemId = context.idGenerator()
        val builder = renderElement(elemId, type)
            .eventId(placed.attachment.id)
            .trackId(placed.attachment.staffTrackId)
            .measureNumber(placed.measureNumber)
            .staffIndex(placed.staffIndex)
            .addCommands(commands)
        (placed.attachment as? ComputedVoltaAttachment)?.ending?.let { ending ->
            builder
                .metadata("voltaStartMeasure", ending.startMeasure.toString())
                .metadata("voltaEndMeasure", ending.endMeasure.toString())
                .metadata("voltaNumbers", ending.numbers.sorted().joinToString(","))
        }
        val element = builder.build()

        // Mixed text/SMuFL tempo marks have different baselines. Derive their hit area from the
        // commands that are actually painted so clicking follows the visible ink instead of the
        // layout band's approximation.
        val hitBox = if (placed.attachment is ComputedTempoKeyframe) {
            val minX = commands.minOf { it.bounds.origin.x.value }
            val minY = commands.minOf { it.bounds.origin.y.value }
            val maxX = commands.maxOf { it.bounds.bottomRight.x.value }
            val maxY = commands.maxOf { it.bounds.bottomRight.y.value }
            val topLeft = context.transformer.toRelative(AbsolutePoint(Pixels(minX), Pixels(minY)))
            val bottomRight = context.transformer.toRelative(AbsolutePoint(Pixels(maxX), Pixels(maxY)))
            RelativeRect(topLeft, bottomRight.x - topLeft.x, bottomRight.y - topLeft.y)
        } else {
            val b = placed.relativeBounds
            RelativeRect(
                origin = RelativePoint(b.origin.x + offset.x, b.origin.y + offset.y),
                width = b.width,
                height = b.height,
            )
        }

        val section = when (val attachment = placed.attachment) {
            is ComputedVoltaAttachment -> VoltaEndingSection(attachment.ending)
            else -> StaffAttachmentSection(attachment)
        }
        return ElementRenderOutput(
            renderElements = listOf(element),
            sectionRegistrations = listOf(
                SectionRegistration(section, elemId)
            ),
            hitAreas = listOf(ElementHitArea(elemId, hitBox))
        )
    }
}
