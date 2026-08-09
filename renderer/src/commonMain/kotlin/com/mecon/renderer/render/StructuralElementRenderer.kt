package com.mecon.renderer.render

import com.mecon.api.computed.ComputedScore
import com.mecon.api.interaction.BarlineSection
import com.mecon.api.interaction.BarlineVisualPlacement
import com.mecon.api.interaction.NavigationMarkSection
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.StaffKind
import com.mecon.renderer.layout.SystemLayout
import com.mecon.renderer.layout.UnifiedLayoutResult
import com.mecon.renderer.render.spatial.HittableRegistration

internal const val ALWAYS_REGENERATED_STRUCTURE = "mecon.splice.alwaysRegeneratedStructure"
internal const val REUSABLE_SYSTEM_STRUCTURE = "mecon.splice.reusableSystemStructure"

/** Renders hit-less staff/system structure shared by full and incremental render paths. */
internal class StructuralElementRenderer(
    private val systemRenderer: SystemRenderer,
) {
    fun renderStaffLineElements(
        layoutResult: UnifiedLayoutResult,
        nextId: () -> RenderElementId,
        systemFilter: (SystemLayout) -> Boolean = { true },
    ): List<RenderElement> {
        val out = mutableListOf<RenderElement>()
        for (system in layoutResult.systems) {
            if (!systemFilter(system)) continue
            for (staffLayout in system.staffLayouts) {
                if (staffLayout.kind != StaffKind.NOTATION) continue
                out.addAll(
                    systemRenderer.renderStaffLines(
                        staffCenterY = staffLayout.centerY,
                        startX = system.lineStartX,
                        endX = system.lineEndX,
                        trackId = staffLayout.trackId,
                        idGenerator = nextId
                    ).map { it.asAlwaysRegeneratedStructure(system.systemIndex, staffLayout.staffIndex) }
                )
            }
        }
        return out
    }

    fun renderSystemStructuralLines(
        layoutResult: UnifiedLayoutResult,
        computedScore: ComputedScore,
        nextId: () -> RenderElementId,
        systemFilter: (SystemLayout) -> Boolean = { true },
    ): List<RichElement> {
        val out = mutableListOf<RichElement>()
        for (system in layoutResult.systems) {
            if (!systemFilter(system)) continue
            val notation = system.staffLayouts.filter { it.kind == StaffKind.NOTATION }
            if (notation.isEmpty()) continue
            val staffByIndex = notation.associateBy { it.staffIndex }
            if (system.systemIndex > 0) {
                val boundary = computedScore.barlines.firstOrNull {
                    it.measureNumber == system.measureRange.first - 1
                }
                val startType = when (boundary?.type) {
                    com.mecon.api.primitive.BarlineType.REPEAT_LEFT,
                    com.mecon.api.primitive.BarlineType.REPEAT_BOTH ->
                        com.mecon.api.primitive.BarlineType.REPEAT_LEFT
                    else -> com.mecon.api.primitive.BarlineType.SINGLE
                }
                out.addAll(
                    systemRenderer.renderSystemStartLine(
                        type = startType,
                        measureNumber = boundary?.measureNumber ?: system.measureRange.first - 1,
                        x = system.lineStartX,
                        topY = notation.first().topY,
                        bottomY = notation.last().bottomY,
                        staffByIndex = staffByIndex,
                        idGenerator = nextId
                    ).map { element ->
                        RichElement(
                            element = element.asAlwaysRegeneratedStructure(system.systemIndex),
                            sections = boundary?.let {
                                listOf(BarlineSection(it, system.systemIndex, BarlineVisualPlacement.SYSTEM_START))
                            }.orEmpty(),
                            hit = null,
                        )
                    }
                )
            }
            system.closingBarline?.let { closing ->
                val boundary = computedScore.barlines.firstOrNull {
                    it.measureNumber == system.measureRange.last
                }
                val endType = when (closing.type) {
                    com.mecon.api.primitive.BarlineType.REPEAT_LEFT ->
                        com.mecon.api.primitive.BarlineType.SINGLE
                    com.mecon.api.primitive.BarlineType.REPEAT_BOTH ->
                        com.mecon.api.primitive.BarlineType.REPEAT_RIGHT
                    else -> closing.type
                }
                out.addAll(
                    systemRenderer.renderClosingBarline(
                        type = endType,
                        measureNumber = boundary?.measureNumber ?: system.measureRange.last,
                        x = closing.x,
                        topY = notation.first().topY,
                        bottomY = notation.last().bottomY,
                        staffByIndex = staffByIndex,
                        idGenerator = nextId
                    ).map { element ->
                        RichElement(
                            element = element.asAlwaysRegeneratedStructure(system.systemIndex),
                            sections = boundary?.let {
                                listOf(BarlineSection(it, system.systemIndex, BarlineVisualPlacement.SYSTEM_END))
                            }.orEmpty(),
                            hit = null,
                        )
                    }
                )
            }

            val topY = notation.first().topY
            val barlineElement: (Int) -> com.mecon.renderer.elements.BarlineElement? = { boundary ->
                computedScore.barlines.firstOrNull { it.measureNumber == boundary }?.let { barline ->
                    layoutResult.timeSlotMap.atTime(barline.time)?.barlineEvents()?.firstOrNull {
                        it.measureNumber == boundary
                    }
                }
            }
            val barlineX: (Int) -> StaffSpace = { boundary ->
                when {
                    boundary < system.measureRange.first -> system.lineStartX
                    boundary >= system.measureRange.last -> system.lineEndX
                    else -> barlineElement(boundary)?.let { element ->
                        layoutResult.timeSlotMap.atTime(element.time)?.let { slot ->
                            slot.x + element.relativeX
                        }
                    } ?: system.lineStartX
                }
            }
            for (navigation in computedScore.navigationMarks) {
                if (navigation.boundaryMeasure !in system.measureRange) continue
                val x = barlineX(navigation.boundaryMeasure) + StaffSpace(navigation.offset.dx)
                // The coordinate is the bottom edge of the mark, kept clear of the top staff line.
                val y = topY - StaffSpace(0.85f) + StaffSpace(navigation.offset.dy)
                out.addAll(
                    systemRenderer.renderNavigationMark(
                        x = x,
                        y = y,
                        mark = navigation.mark,
                        staffIndex = notation.first().staffIndex,
                        idGenerator = nextId,
                    ).map { element ->
                        val section = NavigationMarkSection(navigation)
                        RichElement(
                            element.copy(
                                measureNumber = navigation.boundaryMeasure,
                                metadata = element.metadata + ("navigationMark" to navigation.mark.name),
                            ).asAlwaysRegeneratedStructure(system.systemIndex),
                            listOf(section),
                            HittableRegistration(
                                elementId = element.id,
                                relativeHitBox = RelativeRect(
                                    RelativePoint(x - StaffSpace(4f), y - StaffSpace(4f)),
                                    StaffSpace(8f),
                                    StaffSpace(4.5f),
                                ),
                                type = RenderElementType.NAVIGATION_MARK,
                                sections = listOf(section),
                                staffIndex = notation.first().staffIndex,
                                systemIndex = system.systemIndex,
                                measureIndices = listOf(navigation.boundaryMeasure),
                            ),
                        )
                    }
                )
            }
        }
        return out
    }

    fun renderHeaderBracketsAndLabels(
        layoutResult: UnifiedLayoutResult,
        nextId: () -> RenderElementId,
        systemFilter: (SystemLayout) -> Boolean = { true },
    ): List<RenderElement> {
        val out = mutableListOf<RenderElement>()
        for (system in layoutResult.systems) {
            if (!systemFilter(system)) continue
            for (bracket in system.headerBrackets) {
                out.addAll(systemRenderer.renderHeaderBracket(bracket, nextId).map {
                    it.asReusableSystemStructure(system.systemIndex)
                })
            }
            for (label in system.headerLabels) {
                out.addAll(systemRenderer.renderHeaderLabel(label, nextId).map {
                    it.asReusableSystemStructure(system.systemIndex)
                })
            }
        }
        return out
    }

    private fun RenderElement.asAlwaysRegeneratedStructure(
        systemIndex: Int,
        staffIndex: Int? = this.staffIndex,
    ): RenderElement = copy(
        systemIndex = systemIndex,
        staffIndex = staffIndex,
        metadata = metadata + (ALWAYS_REGENERATED_STRUCTURE to "true"),
    )

    private fun RenderElement.asReusableSystemStructure(systemIndex: Int): RenderElement = copy(
        systemIndex = systemIndex,
        metadata = metadata + (REUSABLE_SYSTEM_STRUCTURE to "true"),
    )
}
