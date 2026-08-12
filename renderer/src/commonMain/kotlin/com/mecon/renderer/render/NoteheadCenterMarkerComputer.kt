package com.mecon.renderer.render

import com.mecon.api.primitive.EventId
import com.mecon.api.render.RenderColor
import com.mecon.api.storage.NoteRef
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.Pixels
import kotlin.math.min

/**
 * A transient state marker placed inside an already engraved notehead.
 *
 * This is deliberately an overlay rather than a new score element: lock state belongs to an editor
 * session, must not alter engraving geometry, and therefore must not participate in render splicing.
 */
data class NoteheadCenterMarker(
    val eventId: EventId,
    val pitchIndex: Int,
    val center: AbsolutePoint,
    val radius: Pixels,
    val color: RenderColor,
    val systemIndex: Int?,
    val staffIndex: Int?,
)

object NoteheadCenterMarkerComputer {
    private const val RADIUS_RATIO = 0.16f
    private const val MIN_RADIUS = 1.25f
    private const val MAX_RADIUS = 2.2f

    /**
     * Resolves stable notehead references to centered contrast dots using renderer metadata.
     * Filled (quarter and shorter) heads receive a white dot; hollow heads receive a black dot.
     */
    fun compute(
        elements: List<RenderElement>,
        notes: Set<NoteRef>,
    ): List<NoteheadCenterMarker> {
        if (notes.isEmpty()) return emptyList()
        return elements.mapNotNull { element ->
            if (element.type != RenderElementType.NOTEHEAD) return@mapNotNull null
            val eventId = element.eventId ?: return@mapNotNull null
            val pitchIndex = element.metadata["pitchIndex"]?.toIntOrNull() ?: return@mapNotNull null
            if (NoteRef(eventId, pitchIndex) !in notes) return@mapNotNull null
            val filled = element.metadata["noteheadFilled"] == "true"
            NoteheadCenterMarker(
                eventId = eventId,
                pitchIndex = pitchIndex,
                center = element.hitBox.center,
                radius = Pixels(
                    (min(element.hitBox.width.value, element.hitBox.height.value) * RADIUS_RATIO)
                        .coerceIn(MIN_RADIUS, MAX_RADIUS),
                ),
                color = if (filled) RenderColor.WHITE else RenderColor.BLACK,
                systemIndex = element.systemIndex,
                staffIndex = element.staffIndex,
            )
        }
    }
}
