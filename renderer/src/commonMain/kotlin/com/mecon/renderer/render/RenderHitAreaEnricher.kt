package com.mecon.renderer.render

import com.mecon.api.interaction.EventSection
import com.mecon.renderer.elements.ElementRenderOutput
import com.mecon.renderer.elements.SectionRegistration
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.render.spatial.HittableRegistration
import com.mecon.renderer.render.spatial.ScoreSpatialAdapter

/** Converts element-local hit areas into spatial-index registrations. */
internal object RenderHitAreaEnricher {
    fun enrich(
        output: ElementRenderOutput,
        staffIndex: Int,
        systemIndex: Int,
        measureBoundaries: List<ScoreSpatialAdapter.MeasureBoundary>,
        systemStartX: StaffSpace,
    ): List<HittableRegistration> {
        if (output.hitAreas.isEmpty()) return emptyList()
        val sectionsByElemId = output.sectionRegistrations
            .groupBy<SectionRegistration, RenderElementId, EventSection>({ it.elementId }, { it.section })
        val renderElemById = output.renderElements.associateBy { it.id }
        return output.hitAreas.mapNotNull { hitArea ->
            val renderElem = renderElemById[hitArea.elementId] ?: return@mapNotNull null
            HittableRegistration(
                elementId = hitArea.elementId,
                relativeHitBox = hitArea.relativeHitBox,
                type = renderElem.type,
                sections = sectionsByElemId[hitArea.elementId] ?: emptyList(),
                staffIndex = staffIndex,
                systemIndex = systemIndex,
                measureIndices = ScoreSpatialAdapter.findOverlappingMeasureIndices(
                    hitArea.relativeHitBox, measureBoundaries, systemStartX
                ),
                customIntersect = hitArea.customIntersect,
                metadata = renderElem.metadata,
            )
        }
    }
}
