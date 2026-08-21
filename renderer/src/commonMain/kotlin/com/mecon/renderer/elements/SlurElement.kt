package com.mecon.renderer.elements

import com.mecon.api.interaction.VoiceSlurSection
import com.mecon.api.render.RenderColor
import com.mecon.renderer.geometry.SlurCurveBuilder
import com.mecon.renderer.geometry.createFilledPathHitShape
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.layout.SlurLayout
import com.mecon.renderer.render.DrawPath
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.renderElement
import com.mecon.renderer.smufl.BravuraFont

/**
 * Renders a single phrasing slur as a filled lens path.
 *
 * Identical in structure to [TieElement] — both use [SlurCurveBuilder] — but
 * uses [com.mecon.renderer.smufl.EngravingDefaults.slurMidpointThickness] for
 * the apex width and threads [SlurLayout.minApexHeight] through as the
 * curve's minimum height so the pre-computed nesting and collision bumps
 * actually take effect.
 *
 * Section registration uses [VoiceSlurSection] keyed by both endpoints plus
 * the nesting level, so overlapping nested slurs are individually selectable.
 */
data class SlurElement(
    val slurLayout: SlurLayout,
    private val config: RenderLayoutConfig
) : RenderableElement {

    context(BravuraFont)
    override fun render(context: ElementRenderContext): ElementRenderOutput {
        val midpointThickness = config.engravingDefaults.slurMidpointThickness

        val relativePath = SlurCurveBuilder.buildLensPath(
            start = slurLayout.start,
            end = slurLayout.end,
            direction = slurLayout.direction,
            midpointThickness = midpointThickness,
            minHeight = slurLayout.minApexHeight,
            maxHeight = slurLayout.maxApexHeight,
            slopeDamping = slurLayout.slopeDamping,
            heightUsesHorizontalSpan = true,
            middleStraightening = slurLayout.middleStraightening,
        )
        val relativeBounds = SlurCurveBuilder.lensBounds(
            start = slurLayout.start,
            end = slurLayout.end,
            direction = slurLayout.direction,
            midpointThickness = midpointThickness,
            minHeight = slurLayout.minApexHeight,
            maxHeight = slurLayout.maxApexHeight,
            slopeDamping = slurLayout.slopeDamping,
            heightUsesHorizontalSpan = true,
            middleStraightening = slurLayout.middleStraightening,
        )

        val absolutePath = context.transformer.toAbsolute(relativePath)
        val absoluteBounds = context.transformer.toAbsolute(relativeBounds)
        val hitTolerance = SlurCurveBuilder.DEFAULT_HIT_TOLERANCE
        val hitShape = relativePath.createFilledPathHitShape(hitTolerance)

        val command = DrawPath(
            path = absolutePath,
            fillColor = RenderColor.BLACK,
            strokeColor = null,
            bounds = absoluteBounds
        )

        val elemId = context.idGenerator()
        val element = renderElement(elemId, RenderElementType.SLUR)
            .addCommand(command)
            .eventId(slurLayout.startEventId)
            .trackId(slurLayout.trackId)
            .measureNumber(slurLayout.measureNumber)
            .staffIndex(slurLayout.staffIndex)
            .metadata("slurId", slurLayout.slurId.value)
            .metadata("startEventId", slurLayout.startEventId.value)
            .metadata("endEventId", slurLayout.endEventId.value)
            .metadata("nestingLevel", slurLayout.nestingLevel.toString())
            .metadata("hitShape", "filledPath")
            .metadata("hitTolerancePx", context.transformer.toPixels(hitTolerance).value.toString())
            .build()

        val startEvent = context.computedScore.getComputedEvent(slurLayout.startEventId)
        val endEvent = context.computedScore.getComputedEvent(slurLayout.endEventId)
        val sections = if (startEvent != null && endEvent != null) {
            listOf(SectionRegistration(
                VoiceSlurSection(startEvent, endEvent, slurLayout.nestingLevel, slurLayout.slurId),
                elemId
            ))
        } else {
            emptyList()
        }

        return ElementRenderOutput(
            renderElements = listOf(element),
            sectionRegistrations = sections,
            hitAreas = listOf(ElementHitArea(
                elementId = elemId,
                relativeHitBox = hitShape.boundingBox,
                hitShape = hitShape,
            ))
        )
    }
}
