package com.mecon.desktop.ui.views.drag

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import com.mecon.renderer.render.DrawLine

/** Hit and display sizes for the volta endpoint handles. */
internal const val VOLTA_CONTROL_HIT_RADIUS = 18f
internal const val VOLTA_CONTROL_SIZE_DP = 12f

internal enum class VoltaEndpoint { START, END }

internal data class VoltaDragState(
    val endpoint: VoltaEndpoint,
    val originalStartMeasure: Int,
    val currentStartMeasure: Int,
    val originalEndMeasure: Int,
    val currentEndMeasure: Int,
)

/**
 * Family H over a family-B target — the volta (ending house) semantic handle. Only the outer edge of
 * a pair is draggable: the first house owns its start, the second its end, and both snap to whole
 * measure boundaries rather than to pixels.
 *
 * Commits one `ResizeFirstVoltaStart` / `ResizeSecondVolta` on release.
 */
internal class VoltaHandleDragHandler : ScoreDragHandler {

    fun start(context: ScoreDragContext, pick: ScoreDragPick): ScoreDragHandler? {
        val section = pick.volta ?: return null
        val point = pick.point ?: return null
        val endpoint = when (section.ending.numbers) {
            setOf(1) -> VoltaEndpoint.START
            setOf(2) -> VoltaEndpoint.END
            else -> return null
        }
        if (context.readOnly) return null
        val elements = context.result.sectionIndex.elementsFor(section).elementIds
            .mapNotNull(context.result::elementById)
        val element = when (endpoint) {
            VoltaEndpoint.START -> elements.firstOrNull()
            VoltaEndpoint.END -> elements.lastOrNull()
        } ?: return null
        // The horizontal bar carries the handles; brackets and text are ignored.
        val line = element.commands.filterIsInstance<DrawLine>()
            .maxByOrNull { kotlin.math.abs(it.end.x.value - it.start.x.value) }
            ?: return null
        val handle = when (endpoint) {
            VoltaEndpoint.START -> line.start
            VoltaEndpoint.END -> line.end
        }
        val radius = VOLTA_CONTROL_HIT_RADIUS / context.scale
        if (kotlin.math.abs(point.x.value - handle.x.value) > radius ||
            kotlin.math.abs(point.y.value - handle.y.value) > radius
        ) return null
        context.ensureSelected(section)
        context.previews.volta.value = VoltaDragState(
            endpoint = endpoint,
            originalStartMeasure = section.ending.startMeasure,
            currentStartMeasure = section.ending.startMeasure,
            originalEndMeasure = section.ending.endMeasure,
            currentEndMeasure = section.ending.endMeasure,
        )
        return this
    }

    override fun drag(context: ScoreDragContext, change: PointerInputChange, dragAmount: Offset) {
        val drag = context.previews.volta.value
        val point = context.toAbsolute(change.position)
        if (drag != null && point != null) {
            val relativeX = context.toRelative(point).x.value
            val nearestSystem = context.nearestSystem(change.position)
            val candidates = context.result.measureBounds.filter {
                val inRange = when (drag.endpoint) {
                    VoltaEndpoint.START -> it.measureNumber <= drag.originalEndMeasure
                    VoltaEndpoint.END -> it.measureNumber >= drag.originalStartMeasure
                }
                inRange && (nearestSystem == null || it.systemIndex == nearestSystem)
            }
            val target = candidates.minByOrNull {
                val handleX = when (drag.endpoint) {
                    VoltaEndpoint.START -> it.leftX
                    VoltaEndpoint.END -> it.rightX
                }
                kotlin.math.abs(handleX.value - relativeX)
            }?.measureNumber
            if (target != null) {
                context.previews.volta.value = when (drag.endpoint) {
                    VoltaEndpoint.START -> drag.copy(currentStartMeasure = target)
                    VoltaEndpoint.END -> drag.copy(currentEndMeasure = target)
                }
            }
        }
        change.consume()
    }

    override fun end(context: ScoreDragContext) {
        context.previews.volta.value?.let { drag ->
            when (drag.endpoint) {
                VoltaEndpoint.START -> if (drag.currentStartMeasure != drag.originalStartMeasure) {
                    context.actions.structure.resizeFirstVoltaStart(
                        drag.originalStartMeasure,
                        drag.currentStartMeasure,
                    )
                }
                VoltaEndpoint.END -> if (drag.currentEndMeasure != drag.originalEndMeasure) {
                    context.actions.structure.resizeSecondVolta(
                        drag.originalStartMeasure,
                        drag.currentEndMeasure,
                    )
                }
            }
        }
        // The volta drag has no committed-frame hold: the structural edit re-engraves the house.
        context.previews.volta.value = null
    }
}
