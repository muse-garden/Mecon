package com.mecon.renderer.render.edit

import com.mecon.api.computed.ComputedScore
import com.mecon.api.primitive.EventId
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.NoteBodyElementBuilder
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderResult
import com.mecon.renderer.smufl.BravuraFont

/**
 * A live drag-to-move-rest preview: each dragged rest re-engraved at its new display staff position,
 * in absolute (global) render coordinates ready to draw over the score. The originals are hidden by
 * the caller (a `hidden` style override on each moved rest), so only this preview is seen while
 * dragging — mirroring the way [TransposePreviewComputer] works for note pitch drags.
 *
 * Reuses [TransposePreview] as the output shape so the desktop view can draw it with the same two-layer
 * painter: a rest has no "unmoved" parts, so [TransposePreview.baseCommands] is always empty and the
 * rest glyph lands in [TransposePreview.movedCommands] (drawn in the selection colour).
 *
 * Pure and stateless: reads only its arguments and allocates fresh geometry, so it is safe to call
 * from the pointer-event coroutine on every drag step.
 */
context(BravuraFont)
class RestMovePreviewComputer(private val config: RenderLayoutConfig = RenderLayoutConfig.DEFAULT) {

    private val noteBodyBuilder = NoteBodyElementBuilder(config)

    /**
     * Build the preview for [targets] (event id → new absolute display staff position). Returns null
     * when nothing could be previewed (no resolvable rest on screen).
     */
    fun compute(
        result: RenderResult,
        computed: ComputedScore,
        targets: Map<EventId, Int>,
    ): TransposePreview? {
        if (targets.isEmpty()) return null
        val transformer = result.transformerSnapshot
        val moved = mutableListOf<com.mecon.renderer.render.RenderCommand>()
        var firstAnchor: AbsolutePoint? = null

        for ((eventId, staffPosition) in targets) {
            val event = computed.getComputedEvent(eventId) ?: continue
            if (!event.isRest) continue

            // Locate the rest on screen via its REST element (absolute geometry).
            val restEl = result.elementsForEvent(eventId)
                .firstOrNull { it.type == RenderElementType.REST } ?: continue
            val box = restEl.hitBox
            val centerPoint = transformer.toRelative(
                AbsolutePoint(
                    Pixels(box.origin.x.value + box.width.value / 2f),
                    Pixels(box.origin.y.value + box.height.value / 2f),
                )
            )
            val staffHit = result.spatialIndex.staffAt(centerPoint) ?: continue
            // Left edge of the original rest (relative) — the preview keeps the same X, only Y moves.
            val originLeftRel = transformer.toRelative(
                AbsolutePoint(Pixels(box.origin.x.value), Pixels(0f))
            ).x

            // Re-engrave the rest glyph at the new staff position. Same glyph as the original, so its
            // left bbox extent matches; offset by it to line the preview up exactly over the original.
            val body = noteBodyBuilder.buildRestElement(event.duration, staffPosition)
            val minX = body.noteheads.minOfOrNull { it.geometry.bounds.origin.x.value } ?: 0f
            val drawOffset = RelativePoint(originLeftRel - StaffSpace(minX), staffHit.centerY)

            for (geometry in body.geometryList) moved += geometry.draw(drawOffset, transformer)
            if (firstAnchor == null) firstAnchor = transformer.toAbsolute(drawOffset)
        }

        val anchor = firstAnchor ?: return null
        if (moved.isEmpty()) return null
        return TransposePreview(baseCommands = emptyList(), movedCommands = moved, anchor = anchor)
    }
}
