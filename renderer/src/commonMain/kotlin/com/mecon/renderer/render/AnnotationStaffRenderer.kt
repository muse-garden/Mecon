package com.mecon.renderer.render

import com.mecon.api.plugin.AnnotationAlignment
import com.mecon.api.plugin.AnnotationElement
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.AnnotationElementMeasurer
import com.mecon.renderer.layout.PlacedAnnotationElement
import com.mecon.renderer.layout.UnifiedLayoutResult

/**
 * Renders [AnnotationElement]s placed by [AnnotationStaffLayoutComputer] as
 * [RenderElement]s (one per annotation element, type [RenderElementType.TEXT_ANNOTATION]).
 *
 * Coordinates: the layout pass produced relative (staff-space) [PlacedAnnotationElement.x] /
 * [PlacedAnnotationElement.centerY]; this renderer converts them to absolute pixels
 * via [transformer] and builds [DrawText] commands.
 */
internal class AnnotationStaffRenderer(
    private val transformer: CoordinateTransformer
) {
    private val elementMeasurer = AnnotationElementMeasurer()

    fun render(
        layoutResult: UnifiedLayoutResult,
        idGenerator: () -> RenderElementId,
        predicate: ((PlacedAnnotationElement) -> Boolean)? = null,
    ): List<RenderElement> {
        if (layoutResult.annotationElementLayouts.isEmpty()) return emptyList()
        val out = mutableListOf<RenderElement>()
        for ((_, placedList) in layoutResult.annotationElementLayouts) {
            for (placed in placedList) {
                if (predicate == null || predicate(placed)) {
                    renderElement(placed, idGenerator)?.let(out::add)
                }
            }
        }
        return out
    }

    private fun renderElement(
        placed: PlacedAnnotationElement,
        idGenerator: () -> RenderElementId
    ): RenderElement? {
        return when (val element = placed.element) {
            is AnnotationElement.Text -> renderText(placed, element, idGenerator)
            is AnnotationElement.Range -> renderRange(placed, element, idGenerator)
        }
    }

    private fun renderText(
        placed: PlacedAnnotationElement,
        element: AnnotationElement.Text,
        idGenerator: () -> RenderElementId
    ): RenderElement {
        val absPos = transformer.toAbsolute(RelativePoint(placed.x, placed.centerY))
        val bounds = elementMeasurer.bounds(element)
        val hitBox = if (element.interactive) {
            val hitOriginX = when (element.alignment) {
                AnnotationAlignment.LEFT -> absPos.x.value
                AnnotationAlignment.CENTER -> absPos.x.value - bounds.widthPx / 2f
                AnnotationAlignment.RIGHT -> absPos.x.value - bounds.widthPx
            }
            // position.y is used as topLeft.y in ComposeScoreRenderer — hitBox must start there
            AbsoluteRect(
                origin = AbsolutePoint(
                    x = Pixels(hitOriginX),
                    y = Pixels(absPos.y.value)
                ),
                width = Pixels(bounds.widthPx),
                height = Pixels(bounds.heightPx)
            )
        } else {
            AbsoluteRect(origin = AbsolutePoint.ZERO, width = Pixels(0f), height = Pixels(0f))
        }
        val cmd = DrawText(
            text = element.text,
            position = absPos,
            fontFamily = AnnotationElementMeasurer.DEFAULT_TEXT_FONT_FAMILY,
            fontSize = Pixels(element.fontSize),
            color = element.color,
            alignment = when (element.alignment) {
                AnnotationAlignment.LEFT -> TextAlignment.LEFT
                AnnotationAlignment.CENTER -> TextAlignment.CENTER
                AnnotationAlignment.RIGHT -> TextAlignment.RIGHT
            },
            richText = element.content,
            bounds = hitBox
        )
        return RenderElement(
            id = idGenerator(),
            type = RenderElementType.TEXT_ANNOTATION,
            commands = listOf(cmd),
            hitBox = hitBox,
            eventId = element.sourceEventId,
            measureNumber = element.time.measure,
            systemIndex = placed.systemIndex,
        )
    }

    private fun renderRange(
        placed: PlacedAnnotationElement,
        element: AnnotationElement.Range,
        idGenerator: () -> RenderElementId,
    ): RenderElement {
        val resolvedEndX = placed.endX ?: placed.x + elementMeasurer.widthStaffSpace(element)
        val left = placed.x + StaffSpace(element.horizontalInset)
        val right = resolvedEndX - StaffSpace(element.horizontalInset)
        val safeRight = maxOf(right, left + StaffSpace(0.25f))
        val topLeft = transformer.toAbsolute(RelativePoint(left, placed.centerY))
        val bottomRight = transformer.toAbsolute(
            RelativePoint(safeRight, placed.centerY + StaffSpace(element.height))
        )
        val rect = AbsoluteRect(
            origin = topLeft,
            width = Pixels((bottomRight.x.value - topLeft.x.value).coerceAtLeast(1f)),
            height = Pixels((bottomRight.y.value - topLeft.y.value).coerceAtLeast(1f)),
        )
        val commands = mutableListOf<RenderCommand>()
        commands += DrawRect(
            rect = rect,
            fillColor = element.fillColor,
            strokeColor = element.strokeColor,
            strokeThickness = Pixels(element.strokeWidth),
        )

        if (element.lines.isNotEmpty()) {
            val lineHeights = element.lines.map { line ->
                elementMeasurer.bounds(
                    AnnotationElement.Text(
                        time = element.time,
                        content = line.content,
                        fontSize = line.fontSize,
                    )
                ).heightPx
            }
            val totalTextHeight = lineHeights.sum()
            var y = rect.origin.y.value + (rect.height.value - totalTextHeight) / 2f
            element.lines.forEachIndexed { index, line ->
                val lineHeight = lineHeights[index]
                commands += DrawText(
                    text = line.content.plainText,
                    position = AbsolutePoint(
                        x = Pixels(rect.origin.x.value + rect.width.value / 2f),
                        y = Pixels(y),
                    ),
                    fontFamily = AnnotationElementMeasurer.DEFAULT_TEXT_FONT_FAMILY,
                    fontSize = Pixels(line.fontSize),
                    color = line.color,
                    alignment = TextAlignment.CENTER,
                    richText = line.content,
                    bounds = rect,
                )
                y += lineHeight
            }
        }

        return RenderElement(
            id = idGenerator(),
            type = RenderElementType.TEXT_ANNOTATION,
            commands = commands,
            hitBox = if (element.interactive) rect else AbsoluteRect(
                origin = AbsolutePoint.ZERO,
                width = Pixels(0f),
                height = Pixels(0f),
            ),
            eventId = element.sourceEventId,
            measureNumber = element.time.measure,
            endMeasureNumber = element.measureSpan.last,
            systemIndex = placed.systemIndex,
        )
    }
}
