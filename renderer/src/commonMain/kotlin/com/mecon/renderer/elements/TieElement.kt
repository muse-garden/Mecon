package com.mecon.renderer.elements

import com.mecon.api.interaction.VoiceTieSection
import com.mecon.api.render.RenderColor
import com.mecon.renderer.geometry.SlurCurveBuilder
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.layout.TieLayout
import com.mecon.renderer.render.DrawPath
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.renderElement
import com.mecon.renderer.smufl.BravuraFont

/**
 * Renders a single tie or let-ring marking as a filled lens path.
 *
 * Drawn-once, simple renderable: the geometry was fully resolved earlier by
 * [com.mecon.renderer.render.TieLayoutComputer], so this element only has to
 * build the lens path, transform to absolute coordinates, and register itself
 * against the source notehead's [VoiceNoteSection].
 *
 * Reuses [SlurCurveBuilder] so slurs will share the same lens-construction
 * code path once they're implemented; only the endpoint resolution differs.
 */
data class TieElement(
    val tieLayout: TieLayout,
    private val config: RenderLayoutConfig
) : RenderableElement {

    context(BravuraFont)
    override fun render(context: ElementRenderContext): ElementRenderOutput {
        val midpointThickness = config.engravingDefaults.tieMidpointThickness

        val relativePath = SlurCurveBuilder.buildLensPath(
            start = tieLayout.start,
            end = tieLayout.end,
            direction = tieLayout.direction,
            midpointThickness = midpointThickness,
            minHeight = tieLayout.minApexHeight,
            maxHeight = tieLayout.maxApexHeight,
            slopeDamping = tieLayout.slopeDamping,
            heightUsesHorizontalSpan = true,
            middleStraightening = tieLayout.middleStraightening,
        )
        val relativeBounds = SlurCurveBuilder.lensBounds(
            start = tieLayout.start,
            end = tieLayout.end,
            direction = tieLayout.direction,
            midpointThickness = midpointThickness,
            minHeight = tieLayout.minApexHeight,
            maxHeight = tieLayout.maxApexHeight,
            slopeDamping = tieLayout.slopeDamping,
            heightUsesHorizontalSpan = true,
            middleStraightening = tieLayout.middleStraightening,
        )

        val absolutePath = context.transformer.toAbsolute(relativePath)
        val absoluteBounds = context.transformer.toAbsolute(relativeBounds)

        val command = DrawPath(
            path = absolutePath,
            fillColor = RenderColor.BLACK,
            strokeColor = null,
            bounds = absoluteBounds
        )

        val elemId = context.idGenerator()
        val builder = renderElement(elemId, RenderElementType.TIE)
            .addCommand(command)
            .eventId(tieLayout.sourceEventId)
            .trackId(tieLayout.trackId)
            .measureNumber(tieLayout.measureNumber)
            .staffIndex(tieLayout.staffIndex)
            .metadata("sourcePitchIndex", tieLayout.sourcePitchIndex.toString())
        tieLayout.targetEventId?.let { builder.metadata("targetEventId", it.value) }
        if (tieLayout.isLetRing) {
            builder.metadata("letRing", "true")
        }
        val element = builder.build()

        // Tie is independently selectable so its direction / curvature can be edited.
        val sourceEvent = context.computedScore.getComputedEvent(tieLayout.sourceEventId)
        val targetEvent = tieLayout.targetEventId?.let(context.computedScore::getComputedEvent)
        val sections = if (sourceEvent != null) {
            listOf(SectionRegistration(
                VoiceTieSection(sourceEvent, tieLayout.sourcePitchIndex, targetEvent),
                elemId
            ))
        } else {
            emptyList()
        }

        return ElementRenderOutput(
            renderElements = listOf(element),
            sectionRegistrations = sections,
            hitAreas = listOf(ElementHitArea(elemId, relativeBounds))
        )
    }
}
