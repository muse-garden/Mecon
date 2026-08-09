package com.mecon.renderer.elements

import com.mecon.renderer.enums.StemDirection
import com.mecon.renderer.geometry.*
import com.mecon.api.interaction.VoiceBeamSection
import com.mecon.api.render.RenderColor
import com.mecon.renderer.render.*
import com.mecon.renderer.smufl.BravuraFont

/**
 * A beam group element that implements [RenderableElement] but not [LayoutElement].
 *
 * Beam groups span multiple notes and do not participate in time-slot layout.
 * They are created from [BeamGroupProcessor] output after layout is complete.
 *
 * The beam rendering logic (beam levels, hooks, segments) is self-contained
 * within this element, extracted from the former [BeamRenderer].
 */
data class BeamGroupElement(
    /** Notes in the beam group, sorted by X position */
    val notes: List<BeamNoteInfo>,
    /** Common stem direction for the group */
    val stemDirection: StemDirection,
    /** Beam thickness */
    val beamThickness: StaffSpace,
    /** Beam spacing (between beam levels) */
    val beamSpacing: StaffSpace,
    /** Stem thickness (for alignment) */
    val stemThickness: StaffSpace,
) : RenderableElement {

    context(BravuraFont)
    override fun render(context: ElementRenderContext): ElementRenderOutput {
        if (notes.size < 2) return ElementRenderOutput.EMPTY

        val elements = mutableListOf<RenderElement>()
        val sections = mutableListOf<SectionRegistration>()
        val hitAreas = mutableListOf<ElementHitArea>()

        // Calculate beam slope from first and last notes
        val firstNote = notes.first()
        val lastNote = notes.last()
        val slope = if (lastNote.x.value != firstNote.x.value) {
            (lastNote.stemTipY.value - firstNote.stemTipY.value) / (lastNote.x.value - firstNote.x.value)
        } else {
            0f
        }

        // Calculate the maximum beam level in this group
        val maxBeamLevel = notes.maxOf { it.beamInfo.totalBeamCount } - 1

        // Render each beam level
        for (level in 0..maxBeamLevel) {
            val levelResults = renderBeamLevelWithHitAreas(
                notes = notes,
                level = level,
                slope = slope,
                transformer = context.transformer,
                idGenerator = context.idGenerator
            )
            for ((elem, hitArea) in levelResults) {
                elements.add(elem)
                hitAreas.add(hitArea)
            }
        }

        // Treat a multi-level beam as one selectable ribbon. The extra hit area has no
        // render command and therefore does not affect engraving bounds or snapshots.
        if (maxBeamLevel > 0 && elements.isNotEmpty()) {
            val halfThickness = beamThickness / 2f
            val stackOffset = maxBeamLevel * (beamThickness.value + beamSpacing.value) *
                if (stemDirection == StemDirection.UP) 1f else -1f
            val startX = firstNote.x - stemThickness / 2f
            val endX = lastNote.x + stemThickness / 2f
            val outerStartY = firstNote.stemTipY.value + stackOffset
            val outerEndY = lastNote.stemTipY.value + stackOffset
            val minY = minOf(
                firstNote.stemTipY.value, lastNote.stemTipY.value,
                outerStartY, outerEndY,
            ) - halfThickness.value
            val maxY = maxOf(
                firstNote.stemTipY.value, lastNote.stemTipY.value,
                outerStartY, outerEndY,
            ) + halfThickness.value
            hitAreas.add(ElementHitArea(
                elements.first().id,
                RelativeRect(
                    RelativePoint(startX, StaffSpace(minY)),
                    endX - startX,
                    StaffSpace(maxY - minY),
                ),
            ))
        }

        // Register VoiceBeamSection for all beam elements
        val groupId = notes.firstOrNull()?.beamInfo?.groupId
        if (groupId != null) {
            val beamEvents = notes.mapNotNull { noteInfo ->
                noteInfo.eventId?.let { context.computedScore.getComputedEvent(it) }
            }
            if (beamEvents.isNotEmpty()) {
                val beamSection = VoiceBeamSection(beamEvents, groupId)
                for (elem in elements) {
                    sections.add(SectionRegistration(beamSection, elem.id))
                }
            }
        }

        return ElementRenderOutput(elements, sections, hitAreas)
    }

    private fun renderBeamLevelWithHitAreas(
        notes: List<BeamNoteInfo>,
        level: Int,
        slope: Float,
        transformer: CoordinateTransformer,
        idGenerator: () -> RenderElementId
    ): List<Pair<RenderElement, ElementHitArea>> {
        val results = mutableListOf<Pair<RenderElement, ElementHitArea>>()
        val halfStemThickness = stemThickness / 2f

        // Y offset for this beam level
        val levelOffset = if (stemDirection == StemDirection.UP) {
            StaffSpace(level * (beamThickness.value + beamSpacing.value))
        } else {
            StaffSpace(-level * (beamThickness.value + beamSpacing.value))
        }

        var i = 0
        while (i < notes.size) {
            val note = notes[i]
            val beamInfo = note.beamInfo
            val beamLevel = level + 1

            if (beamInfo.totalBeamCount < beamLevel) {
                i++
                continue
            }

            val hasBeamLeft = beamInfo.beamsLeft >= beamLevel
            val hasBeamRight = beamInfo.beamsRight >= beamLevel

            when {
                hasBeamLeft && hasBeamRight -> {
                    // Through beam - handled when starting the segment
                    i++
                }
                !hasBeamLeft && hasBeamRight -> {
                    // Starting point of a beam segment
                    var endIndex = i + 1
                    while (endIndex < notes.size) {
                        val endNote = notes[endIndex]
                        val endBeamInfo = endNote.beamInfo
                        if (endBeamInfo.beamsLeft < beamLevel) {
                            endIndex--
                            break
                        }
                        if (endBeamInfo.beamsRight < beamLevel) {
                            break
                        }
                        endIndex++
                    }
                    endIndex = minOf(endIndex, notes.size - 1)

                    results.add(renderBeamSegmentWithHitArea(
                        startNote = note,
                        endNote = notes[endIndex],
                        level = level,
                        levelOffset = levelOffset,
                        halfStemThickness = halfStemThickness,
                        transformer = transformer,
                        idGenerator = idGenerator
                    ))
                    i = endIndex + 1
                }
                hasBeamLeft && !hasBeamRight -> {
                    if (i > 0 && beamInfo.totalBeamCount > beamInfo.beamsLeft) {
                        results.add(renderBeamHookWithHitArea(
                            note = note,
                            level = level,
                            levelOffset = levelOffset,
                            isForward = false,
                            slope = slope,
                            halfStemThickness = halfStemThickness,
                            transformer = transformer,
                            idGenerator = idGenerator
                        ))
                    }
                    i++
                }
                !hasBeamLeft && !hasBeamRight -> {
                    if (i < notes.size - 1 && beamInfo.totalBeamCount > beamInfo.beamsRight) {
                        results.add(renderBeamHookWithHitArea(
                            note = note,
                            level = level,
                            levelOffset = levelOffset,
                            isForward = true,
                            slope = slope,
                            halfStemThickness = halfStemThickness,
                            transformer = transformer,
                            idGenerator = idGenerator
                        ))
                    } else if (i > 0 && beamInfo.totalBeamCount > beamInfo.beamsLeft) {
                        results.add(renderBeamHookWithHitArea(
                            note = note,
                            level = level,
                            levelOffset = levelOffset,
                            isForward = false,
                            slope = slope,
                            halfStemThickness = halfStemThickness,
                            transformer = transformer,
                            idGenerator = idGenerator
                        ))
                    }
                    i++
                }
            }
        }

        return results
    }

    private fun renderBeamSegmentWithHitArea(
        startNote: BeamNoteInfo,
        endNote: BeamNoteInfo,
        level: Int,
        levelOffset: StaffSpace,
        halfStemThickness: StaffSpace,
        transformer: CoordinateTransformer,
        idGenerator: () -> RenderElementId
    ): Pair<RenderElement, ElementHitArea> {
        val startY = startNote.stemTipY + levelOffset
        val endY = endNote.stemTipY + levelOffset

        val startX = startNote.x - halfStemThickness
        val endX = endNote.x + halfStemThickness

        val halfBeamThickness = beamThickness / 2f
        val beamPath = createBeamPath(startX, startY, endX, endY, halfBeamThickness)
        val absPath = transformer.toAbsolute(beamPath)
        val bounds = calculateBeamBounds(startX, startY, endX, endY, halfBeamThickness)
        val absBounds = transformer.toAbsolute(bounds)

        val command = DrawPath(
            path = absPath,
            fillColor = RenderColor.BLACK,
            strokeColor = null,
            bounds = absBounds
        )

        val elemId = idGenerator()
        val element = renderElement(elemId, RenderElementType.BEAM)
            .addCommand(command)
            .metadata("beamLevel", level.toString())
            .metadata("groupId", startNote.beamInfo.groupId.value)
            .build()

        return element to ElementHitArea(elemId, bounds)
    }

    private fun renderBeamHookWithHitArea(
        note: BeamNoteInfo,
        level: Int,
        levelOffset: StaffSpace,
        isForward: Boolean,
        slope: Float,
        halfStemThickness: StaffSpace,
        transformer: CoordinateTransformer,
        idGenerator: () -> RenderElementId
    ): Pair<RenderElement, ElementHitArea> {
        val hookLength = StaffSpace(1.0f)
        val y = note.stemTipY + levelOffset

        val startX: StaffSpace
        val endX: StaffSpace
        if (isForward) {
            startX = note.x - halfStemThickness
            endX = startX + hookLength + stemThickness
        } else {
            endX = note.x + halfStemThickness
            startX = endX - hookLength - stemThickness
        }

        val startY = StaffSpace(y.value + slope * (startX.value - note.x.value))
        val endY = StaffSpace(y.value + slope * (endX.value - note.x.value))

        val halfBeamThickness = beamThickness / 2f
        val beamPath = createBeamPath(startX, startY, endX, endY, halfBeamThickness)
        val absPath = transformer.toAbsolute(beamPath)
        val bounds = calculateBeamBounds(startX, y, endX, y, halfBeamThickness)
        val absBounds = transformer.toAbsolute(bounds)

        val command = DrawPath(
            path = absPath,
            fillColor = RenderColor.BLACK,
            strokeColor = null,
            bounds = absBounds
        )

        val hookType = if (isForward) "forward" else "backward"
        val elemId = idGenerator()
        val element = renderElement(elemId, RenderElementType.BEAM)
            .addCommand(command)
            .metadata("beamLevel", level.toString())
            .metadata("hookType", hookType)
            .metadata("groupId", note.beamInfo.groupId.value)
            .build()

        return element to ElementHitArea(elemId, bounds)
    }

    companion object {
        fun createBeamPath(
            startX: StaffSpace,
            startY: StaffSpace,
            endX: StaffSpace,
            endY: StaffSpace,
            halfThickness: StaffSpace
        ): RelativePath {
            val startTop = RelativePoint(startX, startY - halfThickness)
            val startBottom = RelativePoint(startX, startY + halfThickness)
            val endTop = RelativePoint(endX, endY - halfThickness)
            val endBottom = RelativePoint(endX, endY + halfThickness)

            return RelativePath(
                listOf(
                    RelativePathSegment.MoveTo(startTop),
                    RelativePathSegment.LineTo(endTop),
                    RelativePathSegment.LineTo(endBottom),
                    RelativePathSegment.LineTo(startBottom),
                    RelativePathSegment.Close
                )
            )
        }

        fun calculateBeamBounds(
            startX: StaffSpace,
            startY: StaffSpace,
            endX: StaffSpace,
            endY: StaffSpace,
            halfThickness: StaffSpace
        ): RelativeRect {
            val minX = minOf(startX.value, endX.value)
            val maxX = maxOf(startX.value, endX.value)
            val minY = minOf(
                (startY - halfThickness).value,
                (startY + halfThickness).value,
                (endY - halfThickness).value,
                (endY + halfThickness).value
            )
            val maxY = maxOf(
                (startY - halfThickness).value,
                (startY + halfThickness).value,
                (endY - halfThickness).value,
                (endY + halfThickness).value
            )

            return RelativeRect(
                origin = RelativePoint(StaffSpace(minX), StaffSpace(minY)),
                width = StaffSpace(maxX - minX),
                height = StaffSpace(maxY - minY)
            )
        }
    }
}
