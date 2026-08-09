package com.mecon.renderer.render

import com.mecon.api.interaction.EventSection
import com.mecon.renderer.elements.ElementRenderOutput
import com.mecon.renderer.elements.SectionRegistration
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.render.spatial.ScoreSpatialAdapter

/** Collects regenerated window elements for incremental render splicing. */
internal class SpliceWindowCollector(
    private val target: MutableList<RichElement>,
    private val window: MutableList<RichElement>,
    private val measureBoundaries: List<ScoreSpatialAdapter.MeasureBoundary>,
    private val systemStartX: StaffSpace,
    generation: Int,
) {
    private val idAllocator = RenderElementIdAllocator(generation)

    fun collect(output: ElementRenderOutput, staffIndex: Int, systemIndex: Int) {
        val idMap = output.renderElements.associate { element ->
            val owner = element.systemIndex ?: systemIndex
            element.id to idAllocator.next(owner)
        }
        val stamped = output.copy(
            renderElements = output.renderElements.map { element ->
                element.copy(id = idMap.getValue(element.id), systemIndex = element.systemIndex ?: systemIndex)
            },
            sectionRegistrations = output.sectionRegistrations.map { registration ->
                registration.copy(elementId = idMap.getValue(registration.elementId))
            },
            hitAreas = output.hitAreas.map { hit ->
                hit.copy(elementId = idMap.getValue(hit.elementId))
            },
        )
        val hitByElem = RenderHitAreaEnricher.enrich(
            stamped, staffIndex, systemIndex, measureBoundaries, systemStartX
        )
            .associateBy { it.elementId }
        val sectionsByElem = stamped.sectionRegistrations
            .groupBy<SectionRegistration, RenderElementId, EventSection>({ it.elementId }, { it.section })
        for (el in stamped.renderElements) {
            val rich = RichElement(el, sectionsByElem[el.id] ?: emptyList(), hitByElem[el.id])
            target.add(rich)
            window.add(rich)
        }
    }

    /** Re-register a cached element in an affected system's rebuilt indexes. */
    fun collectRich(rich: RichElement) {
        val id = idAllocator.next(rich.element.systemIndex)
        val remapped = rich.copy(
            element = rich.element.copy(id = id),
            hit = rich.hit?.copy(elementId = id),
        )
        target.add(remapped)
        window.add(remapped)
    }
}
