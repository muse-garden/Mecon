package com.mecon.renderer.render.spatial

import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.api.interaction.EventSection
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderElementId

/**
 * Enriched hit area ready for insertion into the spatial index.
 *
 * Produced by the RenderEngine from [com.mecon.renderer.elements.ElementHitArea]
 * by adding staff/measure/section context that only the engine knows.
 *
 * @param elementId Matches the corresponding RenderElement.id
 * @param relativeHitBox Bounding box in score-relative coordinates (StaffSpace)
 * @param type Type of the render element
 * @param sections All EventSections associated with this element
 * @param staffIndex Staff index (0-based) resolved by the engine
 * @param systemIndex Visual system that owns the element; null for legacy/global registrations
 * @param measureIndices Measure indices this element overlaps (may span multiple measures)
 * @param customIntersect Optional custom intersection test for non-rectangular shapes
 */
data class HittableRegistration(
    val elementId: RenderElementId,
    val relativeHitBox: RelativeRect,
    val type: RenderElementType,
    val sections: List<EventSection>,
    val staffIndex: Int,
    val systemIndex: Int? = null,
    val measureIndices: List<Int>,
    val customIntersect: ((RelativePoint) -> Boolean)? = null,
    val metadata: Map<String, String> = emptyMap(),
)
