package com.mecon.renderer.render

import com.mecon.api.interaction.HiddenStaffSection
import com.mecon.api.interaction.LayoutBreakSection
import com.mecon.api.interaction.StaffAttachmentSection
import com.mecon.api.render.RenderColor
import com.mecon.api.storage.tracks.MeasureRange
import com.mecon.api.storage.tracks.BracketStyle
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.HiddenStaffMarker
import com.mecon.renderer.layout.LayoutBreakMarker
import com.mecon.renderer.layout.PostLayoutMarker
import com.mecon.renderer.layout.TempoKeyframeMarker
import com.mecon.renderer.layout.RenderConstants
import com.mecon.renderer.layout.StaffKind
import com.mecon.renderer.layout.UnifiedLayoutResult

/** Painter extension point for post-layout, non-spacing editor entry markers. */
internal fun interface PostLayoutMarkerPainter {
    fun render(
        marker: PostLayoutMarker,
        layout: UnifiedLayoutResult,
        transformer: CoordinateTransformer,
        nextId: () -> RenderElementId,
    ): RichElement?
}

/** Adds editor markers after every engraving pass; markers never feed spacing or pagination. */
internal class PostLayoutMarkerRenderer(
    private val transformer: CoordinateTransformer,
    private val painters: List<PostLayoutMarkerPainter> = listOf(
        LayoutBreakMarkerPainter,
        HiddenStaffMarkerPainter,
        TempoKeyframeMarkerPainter,
    ),
) {
    fun render(
        layout: UnifiedLayoutResult,
        nextId: () -> RenderElementId,
        systemFilter: Set<Int>? = null,
        showMeasureNumbers: Boolean = true,
    ): List<RichElement> = buildList {
        for (marker in layout.postLayoutMarkers) {
            if (systemFilter != null && marker.systemIndex !in systemFilter) continue
            painters.firstNotNullOfOrNull { it.render(marker, layout, transformer, nextId) }?.let(::add)
        }
        if (showMeasureNumbers) {
            for (system in layout.systems) {
                if (systemFilter != null && system.systemIndex !in systemFilter) continue
                MeasureNumberPainter.render(system, transformer, nextId)?.let(::add)
            }
        }
    }

    /** Regenerate hidden tempo dots on cached, unaffected systems without duplicating other markers. */
    fun renderTempoKeyframes(
        layout: UnifiedLayoutResult,
        nextId: () -> RenderElementId,
        systemFilter: Set<Int>,
    ): List<RichElement> = layout.postLayoutMarkers
        .filterIsInstance<TempoKeyframeMarker>()
        .filter { it.systemIndex in systemFilter }
        .mapNotNull { TempoKeyframeMarkerPainter.render(it, layout, transformer, nextId) }
}

/** A compact blue dot: global tempo keyframes do not consume engraving space. */
private object TempoKeyframeMarkerPainter : PostLayoutMarkerPainter {
    override fun render(
        marker: PostLayoutMarker,
        layout: UnifiedLayoutResult,
        transformer: CoordinateTransformer,
        nextId: () -> RenderElementId,
    ): RichElement? {
        marker as? TempoKeyframeMarker ?: return null
        val system = layout.systems.getOrNull(marker.systemIndex) ?: return null
        val topStaff = system.staffLayouts.firstOrNull { it.kind == StaffKind.NOTATION } ?: return null
        val radius = StaffSpace(0.34f)
        val center = RelativePoint(marker.x, topStaff.contentTopY - StaffSpace(1.05f))
        val relativeBounds = RelativeRect(
            RelativePoint(center.x - radius, center.y - radius),
            radius * 2f,
            radius * 2f,
        )
        val bounds = AbsoluteRect(
            transformer.toAbsolute(relativeBounds.origin),
            transformer.toPixels(relativeBounds.width),
            transformer.toPixels(relativeBounds.height),
        )
        val command = DrawEllipse(
            center = transformer.toAbsolute(center),
            radiusX = transformer.toPixels(radius),
            radiusY = transformer.toPixels(radius),
            fillColor = RenderColor(255, 45, 125, 225),
            bounds = bounds,
        )
        val element = RenderElement(
            id = nextId(),
            type = RenderElementType.EDITOR_MARKER,
            commands = listOf(command),
            hitBox = bounds,
            eventId = marker.keyframe.id,
            measureNumber = marker.anchorMeasure,
            systemIndex = marker.systemIndex,
            staffIndex = topStaff.staffIndex,
            metadata = mapOf(
                ALWAYS_REGENERATED_STRUCTURE to "true",
                "editorMarker" to "tempoKeyframe",
            ),
            relativeHitBox = relativeBounds,
        )
        return RichElement(
            element = element,
            sections = listOf(StaffAttachmentSection(marker.keyframe)),
            hit = null,
        )
    }
}

/** Renders the optional row-start measure number after layout has completely settled. */
private object MeasureNumberPainter {
    private val FONT_SIZE = StaffSpace(1.6f)
    private val TOP_GAP = StaffSpace(2.2f)
    private val BRACKET_GAP = StaffSpace(0.3f)
    private const val APPROX_CHAR_WIDTH = 0.62f

    fun render(
        system: com.mecon.renderer.layout.SystemLayout,
        transformer: CoordinateTransformer,
        nextId: () -> RenderElementId,
    ): RichElement? {
        val firstStaff = system.staffLayouts.firstOrNull { it.kind == StaffKind.NOTATION } ?: return null
        val text = system.measureRange.first.toString()
        val width = StaffSpace(text.length * FONT_SIZE.value * APPROX_CHAR_WIDTH)
        val top = firstStaff.topY - TOP_GAP
        // A square bracket's top glyph projects rightward into the staff/header area. Measure numbers
        // are a final overlay, so place the whole number to the left of every bracket that starts at the
        // first visible notation staff; moving it right would collide with the line-start clef instead.
        val bracketLeftX = system.headerBrackets
            .asSequence()
            .filter { it.staffRange.first == firstStaff.staffIndex }
            .mapNotNull { bracket ->
                when (bracket.style) {
                    BracketStyle.SQUARE, BracketStyle.SUB_BRACKET ->
                        bracket.x - RenderConstants.SQUARE_BRACKET_STAFF_GAP - bracket.thickness * 0.5f
                    BracketStyle.BRACE, BracketStyle.NONE -> null
                }
            }
            .minByOrNull { it.value }
        val left = bracketLeftX?.let { it - BRACKET_GAP - width } ?: system.lineStartX
        val relativeBounds = RelativeRect(
            origin = RelativePoint(left, top),
            width = width,
            height = FONT_SIZE,
        )
        val bounds = AbsoluteRect(
            origin = transformer.toAbsolute(relativeBounds.origin),
            width = transformer.toPixels(width),
            height = transformer.toPixels(FONT_SIZE),
        )
        val command = DrawText(
            position = bounds.origin,
            text = text,
            fontFamily = "Arial",
            fontSize = transformer.toPixels(FONT_SIZE),
            color = RenderColor.BLACK,
            alignment = TextAlignment.LEFT,
            bounds = bounds,
        )
        val element = RenderElement(
            id = nextId(),
            type = RenderElementType.MEASURE,
            commands = listOf(command),
            hitBox = bounds,
            measureNumber = system.measureRange.first,
            systemIndex = system.systemIndex,
            staffIndex = firstStaff.staffIndex,
            metadata = mapOf(
                REUSABLE_SYSTEM_STRUCTURE to "true",
                "postLayout" to "measureNumber",
            ),
            relativeHitBox = relativeBounds,
        )
        return RichElement(element = element, sections = emptyList(), hit = null)
    }
}

private object LayoutBreakMarkerPainter : PostLayoutMarkerPainter {
    override fun render(
        marker: PostLayoutMarker,
        layout: UnifiedLayoutResult,
        transformer: CoordinateTransformer,
        nextId: () -> RenderElementId,
    ): RichElement? {
        marker as? LayoutBreakMarker ?: return null
        val system = layout.systems.getOrNull(marker.systemIndex) ?: return null
        val topStaff = system.staffLayouts.firstOrNull { it.kind == StaffKind.NOTATION } ?: return null
        val left = system.lineEndX - StaffSpace(1.9f)
        val top = topStaff.contentTopY - StaffSpace(0.35f)
        val width = StaffSpace(1.55f)
        val height = StaffSpace(1.25f)
        val thickness = transformer.toPixels(StaffSpace(0.12f))

        fun point(x: Float, y: Float): AbsolutePoint = transformer.toAbsolute(
            RelativePoint(left + StaffSpace(x), top + StaffSpace(y))
        )
        fun line(x1: Float, y1: Float, x2: Float, y2: Float): DrawLine {
            val start = point(x1, y1)
            val end = point(x2, y2)
            val minX = minOf(start.x.value, end.x.value)
            val minY = minOf(start.y.value, end.y.value)
            val w = maxOf(kotlin.math.abs(end.x.value - start.x.value), thickness.value)
            val h = maxOf(kotlin.math.abs(end.y.value - start.y.value), thickness.value)
            return DrawLine(start, end, thickness, bounds = AbsoluteRect(
                AbsolutePoint(Pixels(minX), Pixels(minY)), Pixels(w), Pixels(h)
            ))
        }
        val commands = when (marker.kind) {
            com.mecon.api.interaction.LayoutBreakKind.SYSTEM -> listOf(
                line(1.25f, 0.05f, 1.25f, 0.72f),
                line(1.25f, 0.72f, 0.25f, 0.72f),
                line(0.25f, 0.72f, 0.55f, 0.43f),
                line(0.25f, 0.72f, 0.55f, 1.01f),
            )
            com.mecon.api.interaction.LayoutBreakKind.PAGE -> listOf(
                line(0.78f, 0.18f, 0.78f, 1.08f),
                line(0.78f, 0.18f, 0.18f, 0.03f),
                line(0.18f, 0.03f, 0.18f, 0.88f),
                line(0.18f, 0.88f, 0.78f, 1.08f),
                line(0.78f, 0.18f, 1.38f, 0.03f),
                line(1.38f, 0.03f, 1.38f, 0.88f),
                line(1.38f, 0.88f, 0.78f, 1.08f),
            )
        }
        val relativeHitBox = RelativeRect(RelativePoint(left, top), width, height)
        val origin = transformer.toAbsolute(relativeHitBox.origin)
        val hitBox = AbsoluteRect(origin, transformer.toPixels(width), transformer.toPixels(height))
        val id = nextId()
        val element = RenderElement(
            id = id,
            type = RenderElementType.EDITOR_MARKER,
            commands = commands,
            hitBox = hitBox,
            measureNumber = marker.anchorMeasure,
            systemIndex = marker.systemIndex,
            staffIndex = topStaff.staffIndex,
            metadata = mapOf(
                REUSABLE_SYSTEM_STRUCTURE to "true",
                "editorMarker" to "layoutBreak",
            ),
            relativeHitBox = relativeHitBox,
        )
        return RichElement(
            element = element,
            sections = listOf(LayoutBreakSection(marker.beforeMeasure, marker.kind)),
            hit = null,
        )
    }
}

/** Draws a merged horizontal dashed line across a system where one or more staves are fully hidden. */
private object HiddenStaffMarkerPainter : PostLayoutMarkerPainter {
    override fun render(
        marker: PostLayoutMarker,
        layout: UnifiedLayoutResult,
        transformer: CoordinateTransformer,
        nextId: () -> RenderElementId,
    ): RichElement? {
        marker as? HiddenStaffMarker ?: return null
        val system = layout.systems.getOrNull(marker.systemIndex) ?: return null
        val present = system.staffLayouts.filter { it.kind == StaffKind.NOTATION }
        if (present.isEmpty() || marker.staffIndices.isEmpty()) return null
        val minHidden = marker.staffIndices.min()
        val maxHidden = marker.staffIndices.max()
        val above = present.filter { it.staffIndex < minHidden }.maxByOrNull { it.staffIndex }
        val below = present.filter { it.staffIndex > maxHidden }.minByOrNull { it.staffIndex }
        // Gap Y is derived from the present neighbours so the marker survives Y / page shifts.
        val y: StaffSpace = when {
            above != null && below != null -> StaffSpace((above.bottomY.value + below.topY.value) / 2f)
            above != null -> above.bottomY + StaffSpace(2f)
            below != null -> below.topY - StaffSpace(2f)
            else -> return null
        }
        val leftX = system.lineStartX
        val rightX = system.lineEndX
        if (rightX.value <= leftX.value) return null
        val thickness = transformer.toPixels(StaffSpace(0.12f))
        val start = transformer.toAbsolute(RelativePoint(leftX, y))
        val end = transformer.toAbsolute(RelativePoint(rightX, y))
        val on = transformer.toPixels(StaffSpace(0.9f)).value
        val off = transformer.toPixels(StaffSpace(0.6f)).value
        val lineBounds = AbsoluteRect(
            AbsolutePoint(Pixels(start.x.value), Pixels(start.y.value - thickness.value / 2f)),
            Pixels(end.x.value - start.x.value),
            Pixels(thickness.value),
        )
        val commands = listOf(DrawLine(start, end, thickness, dashIntervals = listOf(on, off), bounds = lineBounds))

        val hitTop = y - StaffSpace(0.7f)
        val hitHeight = StaffSpace(1.4f)
        val relativeHitBox = RelativeRect(RelativePoint(leftX, hitTop), rightX - leftX, hitHeight)
        val hitBox = AbsoluteRect(
            transformer.toAbsolute(relativeHitBox.origin),
            transformer.toPixels(rightX - leftX),
            transformer.toPixels(hitHeight),
        )
        val element = RenderElement(
            id = nextId(),
            type = RenderElementType.EDITOR_MARKER,
            commands = commands,
            hitBox = hitBox,
            measureNumber = marker.fromMeasure,
            systemIndex = marker.systemIndex,
            staffIndex = above?.staffIndex ?: below?.staffIndex ?: 0,
            metadata = mapOf(
                REUSABLE_SYSTEM_STRUCTURE to "true",
                "editorMarker" to "hiddenStaff",
            ),
            relativeHitBox = relativeHitBox,
        )
        return RichElement(
            element = element,
            sections = listOf(HiddenStaffSection(
                systemIndex = marker.systemIndex,
                staffTrackIds = marker.staffTrackIds,
                range = MeasureRange(marker.fromMeasure, marker.toMeasure),
            )),
            hit = null,
        )
    }
}
