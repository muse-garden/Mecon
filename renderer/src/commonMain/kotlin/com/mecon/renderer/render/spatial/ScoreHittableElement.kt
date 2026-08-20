package com.mecon.renderer.render.spatial

import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.geometry.RelativeHitShape
import com.mecon.api.interaction.EventSection
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderElementId

/**
 * Music-domain implementation of [HittableElement].
 *
 * Wraps score-specific metadata via [sections] (fine-grained [EventSection] list)
 * along with the spatial information needed for hit testing.
 *
 * @param elementId Unique identifier (matches RenderElement.id)
 * @param type Type of render element
 * @param sections All EventSections associated with this element
 * @param relativeBounds Bounding box in cell-relative coordinates
 * @param hitShape Optional precise shape for non-rectangular elements
 */
data class ScoreHittableElement(
    override val elementId: RenderElementId,
    val type: RenderElementType,
    val sections: List<EventSection>,
    private val relativeBounds: RelativeRect,
    private val hitShape: RelativeHitShape? = null,
    val metadata: Map<String, String> = emptyMap(),
) : HittableElement {

    override fun boundingBox(): RelativeRect = relativeBounds

    override fun intersect(point: RelativePoint): Boolean {
        if (point !in relativeBounds) return false
        return hitShape?.contains(point) ?: true
    }
}
