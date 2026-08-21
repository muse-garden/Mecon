package com.mecon.desktop.ui.views.drag

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import com.mecon.api.interaction.VoiceSlurSection
import com.mecon.api.interaction.VoiceTieSection
import com.mecon.api.primitive.EventId
import com.mecon.api.storage.SlurGeometry
import com.mecon.api.storage.TieGeometry

/**
 * Family H — the tie/slur semantic handle. Dragging away from the notes raises the curve's apex;
 * dragging back flattens it. Slope damping and middle straightening stay as engraved: only the apex
 * is under the pointer.
 *
 * Commits one `SetTieGeometry` / `SetSlurGeometry` on release; deterministic alternative: the curve
 * geometry fields in the inspector.
 */
internal class CurveHandleDragHandler : ScoreDragHandler {
    private var startRelY = 0f
    private var apex = 0f
    private var tieStart: Pair<EventId, TieGeometry>? = null
    private var slurStart: Pair<EventId, SlurGeometry>? = null

    fun start(context: ScoreDragContext, pick: ScoreDragPick): ScoreDragHandler? {
        if (context.readOnly) return null
        val point = pick.point ?: return null
        val section = pick.section ?: return null
        // Prefer the in-flight geometry overlay so successive drags stack instead of snapping back.
        val geometry = context.document.beamGeometry
            ?: context.document.score?.geometry
            ?: return null
        val result = context.result
        val elementIds = result.sectionIndex.elementsFor(section).elementIds
        when (section) {
            is VoiceTieSection -> {
                val tie = geometry.ties[section.sourceEvent.id]
                    ?.firstOrNull { it.sourcePitchIndex == section.sourcePitchIndex }
                    ?: return null
                tieStart = section.sourceEvent.id to tie
                slurStart = null
                apex = tie.minApex
                context.previews.curve.value = CurveDragState(
                    kind = CurveKind.TIE,
                    sectionId = section.id,
                    elementIds = elementIds,
                    above = tie.above,
                    startApex = tie.minApex,
                    currentApex = tie.minApex,
                    slopeDamping = tie.slopeDamping,
                    middleStraightening = tie.middleStraightening,
                )
            }
            is VoiceSlurSection -> {
                val slurId = section.slurId ?: return null
                val slur = geometry.slurs[slurId] ?: return null
                slurStart = slurId to slur
                tieStart = null
                apex = slur.minApex
                context.previews.curve.value = CurveDragState(
                    kind = CurveKind.SLUR,
                    sectionId = section.id,
                    elementIds = elementIds,
                    above = slur.above,
                    startApex = slur.minApex,
                    currentApex = slur.minApex,
                    slopeDamping = slur.slopeDamping,
                    middleStraightening = slur.middleStraightening,
                )
            }
            else -> return null
        }
        context.ensureSelected(section)
        startRelY = context.relativeY(point)
        return this
    }

    override fun drag(context: ScoreDragContext, change: PointerInputChange, dragAmount: Offset) {
        val point = context.toAbsolute(change.position)
        if (point != null) {
            val above = tieStart?.second?.above ?: slurStart?.second?.above
            val startApex = tieStart?.second?.minApex ?: slurStart?.second?.minApex
            if (above != null && startApex != null) {
                val y = context.relativeY(point)
                // "Outward" is away from the notes: up for a curve above, down for one below.
                val outwardDelta = if (above) startRelY - y else y - startRelY
                apex = (startApex + outwardDelta)
                    .coerceIn(SlurGeometry.MIN_APEX, SlurGeometry.MAX_APEX)
                context.previews.curve.value = context.previews.curve.value?.copy(currentApex = apex)
            }
        }
        change.consume()
    }

    override fun end(context: ScoreDragContext) {
        val changed = context.previews.curve.value?.let { it.currentApex != it.startApex } == true
        if (changed) {
            context.previews.curve.value = context.previews.curve.value?.copy(
                committing = true,
                commitBaseline = context.result,
            )
        }
        tieStart?.let { (sourceId, original) ->
            if (apex != original.minApex) {
                context.actions.expressions.adjustTieCurve(
                    sourceId,
                    original.copy(minApex = apex, maxApex = apex),
                )
            }
        }
        slurStart?.let { (slurId, original) ->
            if (apex != original.minApex) {
                context.actions.expressions.adjustSlurCurve(
                    slurId,
                    original.copy(minApex = apex, maxApex = apex),
                )
            }
        }
        if (!changed) context.previews.curve.value = null
        tieStart = null
        slurStart = null
    }

    override fun cancel(context: ScoreDragContext) {
        tieStart = null
        slurStart = null
    }
}
