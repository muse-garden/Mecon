package com.mecon.renderer.render

import com.mecon.api.interaction.EventSection
import com.mecon.renderer.elements.ElementRenderOutput
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.render.spatial.HittableRegistration
import com.mecon.renderer.render.spatial.ScoreSpatialAdapter

/** Accumulates raw element render output and converts it to [RichElement]s. */
internal class RenderElementCollector(
    private val measureBoundaries: List<ScoreSpatialAdapter.MeasureBoundary>,
    private val systemStartX: StaffSpace,
) {
    private val idAllocator = RenderElementIdAllocator()
    private val elements = mutableListOf<RenderElement>()
    private val elementSections = LinkedHashMap<RenderElementId, MutableList<EventSection>>()
    private val hittableRegistrations = mutableListOf<HittableRegistration>()

    fun addElements(newElements: List<RenderElement>) {
        for (element in newElements) {
            // Global/plugin elements keep the caller's ID so streaming renders can share one generator
            // across per-page collectors. System-owned elements are rebased into dense system ordinals.
            elements += if (element.systemIndex == null) element else {
                element.copy(id = idAllocator.next(element.systemIndex))
            }
        }
    }

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
        elements.addAll(stamped.renderElements)
        for (reg in stamped.sectionRegistrations) {
            elementSections.getOrPut(reg.elementId) { mutableListOf() }.add(reg.section)
        }
        hittableRegistrations.addAll(
            RenderHitAreaEnricher.enrich(stamped, staffIndex, systemIndex, measureBoundaries, systemStartX)
        )
    }

    /** Append an already-rich non-spacing overlay while rebasing it into this collector's dense IDs. */
    fun collectRich(rich: RichElement) {
        val id = idAllocator.next(rich.element.systemIndex)
        val element = rich.element.copy(id = id)
        elements += element
        if (rich.sections.isNotEmpty()) elementSections.getOrPut(id) { mutableListOf() }.addAll(rich.sections)
        rich.hit?.let { hittableRegistrations += it.copy(elementId = id) }
    }

    fun toRichElements(): List<RichElement> {
        val hitByElem = hittableRegistrations.associateBy { it.elementId }
        return elements.map { el ->
            RichElement(el, elementSections[el.id] ?: emptyList(), hitByElem[el.id])
        }
    }
}
