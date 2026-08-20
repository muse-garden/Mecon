package com.mecon.renderer.elements

import com.mecon.api.computed.ComputedScore
import com.mecon.renderer.geometry.DrawableGeometry
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.api.interaction.EventSection
import com.mecon.renderer.geometry.RelativeHitShape
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.render.CoordinateTransformer
import com.mecon.renderer.render.RenderElement
import com.mecon.renderer.render.RenderElementId

/**
 * Association between an [EventSection] and a render element ID.
 *
 * Used by [ElementRenderOutput] to declare which sections should be
 * registered for each element produced by a [RenderableElement].
 */
data class SectionRegistration(
    val section: EventSection,
    val elementId: RenderElementId
)

/**
 * Hit area produced by an element's render() method.
 *
 * Contains only the element ID and score-relative bounding box.
 * The RenderEngine enriches this with staff/measure/section info
 * to produce a [com.mecon.renderer.render.spatial.HittableRegistration].
 *
 * @param elementId Matches the corresponding [RenderElement.id]
 * @param relativeHitBox Bounding box in score-relative coordinates (StaffSpace)
 * @param hitShape Optional precise, translation-safe shape for non-rectangular elements
 */
data class ElementHitArea(
    val elementId: RenderElementId,
    val relativeHitBox: RelativeRect,
    val hitShape: RelativeHitShape? = null,
)

/**
 * The output of [RenderableElement.render], containing rendered elements,
 * their section registrations, and hit areas for spatial indexing.
 *
 * This replaces the manual section registration in RenderEngine by letting
 * each element declare its own sections alongside its render elements.
 */
data class ElementRenderOutput(
    val renderElements: List<RenderElement>,
    val sectionRegistrations: List<SectionRegistration>,
    val hitAreas: List<ElementHitArea> = emptyList()
) {
    operator fun plus(other: ElementRenderOutput): ElementRenderOutput =
        ElementRenderOutput(
            renderElements = renderElements + other.renderElements,
            sectionRegistrations = sectionRegistrations + other.sectionRegistrations,
            hitAreas = hitAreas + other.hitAreas
        )

    companion object {
        val EMPTY = ElementRenderOutput(emptyList(), emptyList(), emptyList())
    }
}

/**
 * Compute the score-relative bounding box for a [DrawableGeometry]
 * given the draw offset applied during rendering.
 */
fun DrawableGeometry.scoreRelativeBounds(drawOffset: RelativePoint): RelativeRect =
    RelativeRect(
        origin = RelativePoint(
            drawOffset.x + bounds.origin.x,
            drawOffset.y + bounds.origin.y
        ),
        width = bounds.width,
        height = bounds.height
    )

/**
 * Compute the merged score-relative bounding box for multiple [DrawableGeometry] instances.
 *
 * Returns null if the list is empty.
 */
fun List<DrawableGeometry>.mergedScoreRelativeBounds(drawOffset: RelativePoint): RelativeRect? {
    if (isEmpty()) return null
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = Float.MIN_VALUE
    var maxY = Float.MIN_VALUE
    for (geom in this) {
        val b = geom.bounds
        val ox = drawOffset.x.value + b.origin.x.value
        val oy = drawOffset.y.value + b.origin.y.value
        if (ox < minX) minX = ox
        if (oy < minY) minY = oy
        val rx = ox + b.width.value
        val ry = oy + b.height.value
        if (rx > maxX) maxX = rx
        if (ry > maxY) maxY = ry
    }
    return RelativeRect(
        origin = RelativePoint(StaffSpace(minX), StaffSpace(minY)),
        width = StaffSpace(maxX - minX),
        height = StaffSpace(maxY - minY)
    )
}

/**
 * Context passed to [RenderableElement.render] providing everything
 * needed to produce [ElementRenderOutput].
 *
 * ## Coordinate convention
 *
 * - **Non-spanning elements** (notes, clefs, barlines, etc.):
 *   `offset.x = slot.x`, `offset.y = staffCenterY`.
 *   The element is responsible for adding its own `relativeX`.
 *
 * - **Spanning elements** (beam groups):
 *   `offset.x = first note's slot.x`, `offset.y = staffCenterY`.
 */
data class ElementRenderContext(
    /** Position: (slot.x, staffCenterY) — does NOT include relativeX */
    val offset: RelativePoint,
    /** Coordinate transformer for relative → absolute conversion */
    val transformer: CoordinateTransformer,
    /** Generates unique element IDs */
    val idGenerator: () -> RenderElementId,
    /** Computed score for section lookup */
    val computedScore: ComputedScore
)
