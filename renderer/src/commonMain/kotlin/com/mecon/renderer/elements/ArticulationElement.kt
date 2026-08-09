package com.mecon.renderer.elements

import com.mecon.api.interaction.VoiceArticulationSection
import com.mecon.api.interaction.VoiceEventSection
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.ArticulationLayout
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderHelpers
import com.mecon.renderer.render.renderElement
import com.mecon.renderer.smufl.BravuraFont

/**
 * Renders all articulation marks of one voice event.
 *
 * Drawn-once renderable: geometry was fully resolved by
 * [com.mecon.renderer.render.ArticulationLayoutComputer], so this element only
 * turns each [com.mecon.renderer.layout.PlacedArticulation] into a glyph command
 * and registers it against its [VoiceArticulationSection].
 */
data class ArticulationElement(
    val layout: ArticulationLayout,
) : RenderableElement {

    context(BravuraFont)
    override fun render(context: ElementRenderContext): ElementRenderOutput {
        val computedEvent = context.computedScore.getComputedEvent(layout.eventId)
        val fontSize = context.transformer.toPixels(StaffSpace(4f))

        val elements = mutableListOf<com.mecon.renderer.render.RenderElement>()
        val sections = mutableListOf<SectionRegistration>()
        val hitAreas = mutableListOf<ElementHitArea>()

        for (mark in layout.marks) {
            val position = context.transformer.toAbsolute(mark.origin)
            val command = RenderHelpers.createGlyphCommand(mark.glyph, position, fontSize)

            val elemId = context.idGenerator()
            val element = renderElement(elemId, RenderElementType.ARTICULATION)
                .addCommand(command)
                .eventId(layout.eventId)
                .trackId(layout.trackId)
                .measureNumber(layout.measureNumber)
                .staffIndex(layout.staffIndex)
                .metadata("articulationIndex", mark.index.toString())
                .build()
            elements.add(element)

            if (computedEvent != null) {
                sections.add(SectionRegistration(VoiceEventSection(computedEvent), elemId))
                sections.add(SectionRegistration(
                    VoiceArticulationSection(computedEvent, mark.index), elemId
                ))
            }
            hitAreas.add(ElementHitArea(elemId, mark.bounds))
        }

        return ElementRenderOutput(elements, sections, hitAreas)
    }
}
