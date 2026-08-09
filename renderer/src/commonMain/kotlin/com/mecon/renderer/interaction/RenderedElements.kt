package com.mecon.renderer.interaction

import com.mecon.api.primitive.EventId
import com.mecon.api.render.RenderColor
import com.mecon.renderer.render.RenderResult
import com.mecon.renderer.render.HierarchicalHitTestResult
import com.mecon.renderer.render.RenderElementId

/**
 * A wrapper around a list of render element IDs, providing
 * convenient methods to build style directives.
 *
 * Obtained from [SectionIndex.elementsFor] or constructed directly.
 */
class RenderedElements(val elementIds: List<RenderElementId>) {

    val isEmpty: Boolean get() = elementIds.isEmpty()

    // Legacy style builders removed

    /**
     * Combine with another [RenderedElements], deduplicating IDs.
     */
    operator fun plus(other: RenderedElements): RenderedElements =
        RenderedElements((elementIds + other.elementIds).distinct())

    companion object {
        val EMPTY = RenderedElements(emptyList())

        /**
         * Create from a hit test result.
         */
        fun from(hitResult: HierarchicalHitTestResult): RenderedElements =
            RenderedElements(hitResult.elements.map { it.elementId })

        /**
         * Create from all elements associated with an event ID in a render result.
         */
        fun fromEvent(eventId: EventId, renderResult: RenderResult): RenderedElements =
            RenderedElements(renderResult.elementsForEvent(eventId).map { it.id })

        /** Create from specific element IDs. */
        fun of(ids: Iterable<RenderElementId>): RenderedElements = RenderedElements(ids.toList())
    }
}
