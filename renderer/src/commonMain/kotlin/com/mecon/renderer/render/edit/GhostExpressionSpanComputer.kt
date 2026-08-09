package com.mecon.renderer.render.edit

import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.orderedStaffs
import com.mecon.api.storage.events.HairpinStyle
import com.mecon.api.storage.events.HairpinType
import com.mecon.api.storage.events.OctaveShiftType
import com.mecon.api.storage.events.StaffAttachmentPlacement
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.geometry.HairpinGeometry
import com.mecon.renderer.geometry.IntervalAttachmentGeometry
import com.mecon.renderer.geometry.SpanEnd
import com.mecon.renderer.geometry.SpanLineStyle
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.render.RenderCommand
import com.mecon.renderer.render.RenderResult
import com.mecon.renderer.smufl.BravuraFont

/** The expression span that is being inserted, before it exists in the score. */
sealed interface ExpressionSpanKind {
    data class Hairpin(val type: HairpinType, val style: HairpinStyle) : ExpressionSpanKind
    data class Octave(val type: OctaveShiftType) : ExpressionSpanKind
}

/** A drag preview for a new hairpin or octave-shift span. */
data class GhostExpressionSpan(
    val staffTrackId: TrackId,
    val start: TimeCode,
    val end: TimeCode,
    val commands: List<RenderCommand>,
    val anchor: AbsolutePoint,
)

/**
 * Builds a new expression span with the same geometry primitives used by settled attachments.
 * This is intentionally a pure read of the displayed render result, so pointer movement does not
 * trigger a layout pass.
 */
context(BravuraFont)
class GhostExpressionSpanComputer(
    private val config: RenderLayoutConfig = RenderLayoutConfig.DEFAULT,
) {
    private val noteCentreLead = StaffSpace(0.6f)
    private val textSize = StaffSpace(1.6f)

    fun compute(
        result: RenderResult,
        runtime: RuntimeScore,
        staffTrackId: TrackId,
        start: TimeCode,
        end: TimeCode,
        kind: ExpressionSpanKind,
    ): GhostExpressionSpan? {
        if (end <= start) return null
        val staffIndex = runtime.orderedStaffs().indexOfFirst { it.id == staffTrackId }
        if (staffIndex < 0) return null
        val startPosition = result.timeCodePositions[start] ?: return null
        val endPosition = result.timeCodePositions[end] ?: return null
        val transformer = result.transformerSnapshot
        fun systemFor(position: com.mecon.renderer.render.TimeCodePosition) =
            result.spatialIndex.allSystems().firstOrNull { system ->
                val middleY = (position.topY + position.bottomY) / 2f
                val relativeY = transformer.toRelative(
                    AbsolutePoint(Pixels(position.x), Pixels(middleY))
                ).y
                relativeY in system.topY..system.bottomY
            }
        val startSystem = systemFor(startPosition) ?: return null
        val endSystem = systemFor(endPosition) ?: return null
        if (startSystem.systemIndex != endSystem.systemIndex) return null
        val centerY = startSystem.staffRegions.firstOrNull { it.staffIndex == staffIndex }?.centerY
            ?: return null
        val startX = transformer.toRelative(AbsolutePoint(Pixels(startPosition.x), Pixels(0f))).x - noteCentreLead
        val endX = transformer.toRelative(AbsolutePoint(Pixels(endPosition.x), Pixels(0f))).x - noteCentreLead
        if (endX <= startX) return null

        val geometry = when (kind) {
            is ExpressionSpanKind.Hairpin -> {
                val placement = StaffAttachmentPlacement.BELOW
                val spread = config.hairpinSpread
                val height = maxOf(spread, StaffSpace(textSize.value * 1.2f))
                val yCenter = StaffSpace(3.8f)
                val bounds = RelativeRect(
                    origin = RelativePoint(minOf(startX, endX), yCenter - height / 2f),
                    width = maxOf(startX, endX) - minOf(startX, endX),
                    height = height,
                )
                val drawable = when (kind.style) {
                    HairpinStyle.WEDGE -> HairpinGeometry(
                        startX = startX,
                        endX = endX,
                        type = kind.type,
                        yCenter = yCenter,
                        spread = spread,
                        thickness = config.hairpinThickness,
                        bounds = bounds,
                    )
                    HairpinStyle.TEXT_DASHED -> IntervalAttachmentGeometry(
                        startX = startX,
                        endX = endX,
                        yCenter = yCenter,
                        lineStyle = SpanLineStyle.DASHED,
                        startContent = SpanEnd.Text(
                            if (kind.type == HairpinType.CRESCENDO) "cresc." else "dim.",
                            widthFactor = 0.45f,
                        ),
                        endContent = SpanEnd.None,
                        placement = placement,
                        thickness = config.hairpinThickness,
                        textSize = textSize,
                        textGap = config.hairpinTextGap,
                        bounds = bounds,
                    )
                }
                drawable
            }
            is ExpressionSpanKind.Octave -> {
                val placement = if (kind.type == OctaveShiftType.OTTAVA) {
                    StaffAttachmentPlacement.ABOVE
                } else {
                    StaffAttachmentPlacement.BELOW
                }
                val height = StaffSpace(textSize.value * 1.4f)
                val yCenter = if (placement == StaffAttachmentPlacement.ABOVE) {
                    -StaffSpace(4.2f)
                } else {
                    StaffSpace(4.2f)
                }
                val bounds = RelativeRect(
                    origin = RelativePoint(minOf(startX, endX), yCenter - height / 2f),
                    width = maxOf(startX, endX) - minOf(startX, endX),
                    height = height,
                )
                val label = if (kind.type == OctaveShiftType.OTTAVA) "8va" else "8vb"
                IntervalAttachmentGeometry(
                    startX = startX - StaffSpace(0.5f),
                    endX = endX,
                    yCenter = yCenter,
                    lineStyle = SpanLineStyle.DASHED,
                    startContent = SpanEnd.Text(label, widthFactor = 0.52f),
                    endContent = SpanEnd.Hook(StaffSpace(0.6f)),
                    placement = placement,
                    thickness = config.hairpinThickness,
                    textSize = textSize,
                    textGap = config.hairpinTextGap,
                    bounds = bounds,
                )
            }
        }

        val commands = geometry.draw(RelativePoint(StaffSpace.ZERO, centerY), transformer)
        return GhostExpressionSpan(
            staffTrackId = staffTrackId,
            start = start,
            end = end,
            commands = commands,
            anchor = transformer.toAbsolute(RelativePoint(startX, centerY)),
        )
    }
}
