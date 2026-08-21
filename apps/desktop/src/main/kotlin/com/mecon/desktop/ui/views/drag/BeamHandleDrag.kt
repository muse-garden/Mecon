package com.mecon.desktop.ui.views.drag

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import com.mecon.api.storage.BeamGeometry
import com.mecon.renderer.render.RenderResult

internal data class BeamDragState(
    val groupId: String,
    val endpoint: String?,
    val start: BeamGeometry,
    val current: BeamGeometry,
    val staffCenters: Map<Int, Float> = emptyMap(),
    val deltaY: Float = 0f,
    val committing: Boolean = false,
    val commitBaseline: RenderResult? = null,
)

/**
 * Family H — the beam semantic handle. Grabbing an endpoint tilts the beam; grabbing its body moves
 * the whole beam and, across staves, re-anchors it to the nearest stable line
 * (see [relocateBeamGeometry]).
 *
 * Commits one `SetBeamGeometry` on release; determinstic alternative: the beam geometry inspector.
 */
internal class BeamHandleDragHandler : ScoreDragHandler {
    private var startRelY = 0f

    fun start(context: ScoreDragContext, pick: ScoreDragPick): ScoreDragHandler? {
        val section = pick.beam ?: return null
        val score = context.document.score ?: return null
        val point = pick.point ?: return null
        if (context.readOnly) return null
        val groupId = section.groupId.value
        val stored = context.document.beamGeometry?.beams?.get(groupId)
            ?: score.geometry?.beams?.get(groupId)
            ?: BeamGeometry(0f, 0f)
        val controls = pick.beamControls
            ?: context.document.selectedBeamControls?.takeIf { it.section.groupId == section.groupId }
            ?: findBeamControlPoints(context.result, section)
        val staffCenters = controls?.staffCenters.orEmpty()
        val dragStart = normalizeCrossStaffBeamGeometry(stored, staffCenters)
        context.ensureSelected(section)
        startRelY = context.relativeY(point)
        context.previews.beam = BeamDragState(
            groupId = groupId,
            endpoint = pick.beamEndpoint,
            start = dragStart,
            current = dragStart,
            staffCenters = staffCenters,
        )
        return this
    }

    override fun drag(context: ScoreDragContext, change: PointerInputChange, dragAmount: Offset) {
        val drag = context.previews.beam
        val point = context.toAbsolute(change.position)
        if (drag != null && point != null) {
            val deltaY = context.relativeY(point) - startRelY
            context.previews.beam = drag.copy(
                current = relocateBeamGeometry(drag.start, drag.endpoint, deltaY, drag.staffCenters),
                deltaY = deltaY,
            )
        }
        change.consume()
    }

    override fun end(context: ScoreDragContext) {
        val drag = context.previews.beam ?: return
        if (drag.current == drag.start) {
            context.previews.beam = null
            return
        }
        context.previews.beam = drag.copy(committing = true, commitBaseline = context.result)
        context.actions.notes.moveBeam(drag.groupId, drag.current.copy(manuallyAdjusted = true))
    }
}
