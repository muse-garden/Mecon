package com.mecon.desktop.ui.views.drag

import com.mecon.api.interaction.VoiceBeamSection
import com.mecon.api.storage.BeamGeometry
import com.mecon.api.storage.CrossStaffBeamBase
import com.mecon.renderer.geometry.AbsolutePath
import com.mecon.renderer.geometry.AbsolutePathSegment
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.render.DrawLine
import com.mecon.renderer.render.DrawPath
import com.mecon.renderer.render.RenderCommand
import com.mecon.renderer.render.RenderElement
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderResult

/** Hit and display sizes for the beam endpoint handles (editor chrome, not renderer output). */
internal const val BEAM_CONTROL_SIZE_DP = 8f
internal const val BEAM_CONTROL_STROKE_DP = 1.5f
internal const val BEAM_CONTROL_HIT_RADIUS = 4f

internal data class BeamControlPoints(
    val section: VoiceBeamSection,
    val start: AbsolutePoint,
    val end: AbsolutePoint,
    val staffCenters: Map<Int, Float>,
)

internal data class BeamDragPreview(
    val commands: List<RenderCommand>,
    val start: AbsolutePoint,
    val end: AbsolutePoint,
)

/** Derive endpoint controls from stem tips without adding editor chrome to renderer output. */
internal fun findBeamControlPoints(
    result: RenderResult,
    section: VoiceBeamSection,
): BeamControlPoints? {
    val stems = section.events.mapNotNull { event ->
        result.elementsForEvent(event.id).firstOrNull { element ->
            element.type == RenderElementType.STEM && element.commands.firstOrNull() is DrawLine
        }
    }.distinctBy { it.id }
    if (stems.size < 2) return null

    val systemIndex = stems.firstNotNullOfOrNull { it.systemIndex }
    val system = result.spatialIndex.allSystems().firstOrNull { it.systemIndex == systemIndex }
    fun beamTip(stem: RenderElement): AbsolutePoint {
        val line = stem.commands.first() as DrawLine
        val centerY = system?.staffRegions
            ?.firstOrNull { it.staffIndex == stem.staffIndex }
            ?.let { region ->
                result.transformerSnapshot.toAbsolute(
                    RelativePoint(StaffSpace.ZERO, region.centerY)
                ).y.value
            }
        if (centerY == null) return line.start
        return if (kotlin.math.abs(line.start.y.value - centerY) >=
            kotlin.math.abs(line.end.y.value - centerY)
        ) line.start else line.end
    }

    val tips = stems.map { beamTip(it) }.sortedBy { it.x.value }
    val usedStaffIndices = stems.mapNotNull { it.staffIndex }.distinct().sorted()
    val staffCenters = if (system != null && usedStaffIndices.size >= 2) {
        val range = usedStaffIndices.first()..usedStaffIndices.last()
        system.staffRegions.filter { it.staffIndex in range }.associate { region ->
            region.staffIndex to result.transformerSnapshot.toAbsolute(
                RelativePoint(StaffSpace.ZERO, region.centerY)
            ).y.value
        }
    } else {
        emptyMap()
    }
    return BeamControlPoints(section, tips.first(), tips.last(), staffCenters)
}

internal fun hitBeamControlPoint(
    point: AbsolutePoint,
    controls: BeamControlPoints,
    radius: Float,
): String? = hitBeamControlPoint(point, controls.start, controls.end, radius)

internal fun hitBeamControlPoint(
    point: AbsolutePoint,
    start: AbsolutePoint,
    end: AbsolutePoint,
    radius: Float,
): String? {
    val separationX = end.x.value - start.x.value
    val separationY = end.y.value - start.y.value
    // Reserve a central body-drag zone even for very short beams. Endpoint hit circles
    // may consume at most 20% of the endpoint separation from either side.
    val effectiveRadius = minOf(
        radius,
        kotlin.math.sqrt(separationX * separationX + separationY * separationY) * 0.2f,
    )
    fun hits(target: AbsolutePoint): Boolean {
        val dx = point.x.value - target.x.value
        val dy = point.y.value - target.y.value
        return dx * dx + dy * dy <= effectiveRadius * effectiveRadius
    }
    return when {
        hits(start) -> "start"
        hits(end) -> "end"
        else -> null
    }
}

internal fun normalizeCrossStaffBeamGeometry(
    geometry: BeamGeometry,
    staffCenters: Map<Int, Float>,
): BeamGeometry {
    val sorted = staffCenters.keys.sorted()
    if (sorted.size < 2) return geometry
    val defaultPairStart = ((sorted.size - 1) / 2).coerceAtMost(sorted.lastIndex - 1)
    val defaultUpper = sorted[defaultPairStart]
    val defaultLower = sorted[defaultPairStart + 1]
    val storedPairIsValid = sorted.zipWithNext().any { (upper, lower) ->
        geometry.betweenStaffUpperIndex == upper && geometry.betweenStaffLowerIndex == lower
    }
    return when (geometry.crossStaffBase) {
        null -> geometry.copy(
            crossStaffBase = CrossStaffBeamBase.BETWEEN_STAFFS,
            crossStaffOffset = geometry.crossStaffOffset ?: 0f,
            betweenStaffUpperIndex = defaultUpper,
            betweenStaffLowerIndex = defaultLower,
        )
        CrossStaffBeamBase.BETWEEN_STAFFS -> geometry.copy(
            crossStaffOffset = geometry.crossStaffOffset ?: 0f,
            betweenStaffUpperIndex = if (storedPairIsValid) geometry.betweenStaffUpperIndex else defaultUpper,
            betweenStaffLowerIndex = if (storedPairIsValid) geometry.betweenStaffLowerIndex else defaultLower,
        )
        else -> geometry.copy(
            crossStaffOffset = geometry.crossStaffOffset ?: 0f,
            betweenStaffUpperIndex = null,
            betweenStaffLowerIndex = null,
        )
    }
}

/** Apply a pointer delta and, for a whole cross-staff beam drag, re-anchor to the nearest stable line. */
internal fun relocateBeamGeometry(
    original: BeamGeometry,
    endpoint: String?,
    deltaY: Float,
    staffCenters: Map<Int, Float>,
): BeamGeometry {
    val geometry = normalizeCrossStaffBeamGeometry(original, staffCenters)
    if (endpoint == "start") return geometry.copy(startDy = geometry.startDy + deltaY)
    if (endpoint == "end") return geometry.copy(endDy = geometry.endDy + deltaY)
    val sortedCenters = staffCenters.toSortedMap()
    if (geometry.crossStaffBase == null || sortedCenters.size < 2) {
        return geometry.copy(startDy = geometry.startDy + deltaY, endDy = geometry.endDy + deltaY)
    }

    data class Candidate(
        val base: CrossStaffBeamBase,
        val y: Float,
        val upperIndex: Int? = null,
        val lowerIndex: Int? = null,
    )

    val entries = sortedCenters.entries.toList()
    val defaultUpper = entries[(entries.size - 1) / 2].key
    val defaultLower = entries[((entries.size - 1) / 2) + 1].key
    val currentBaseY = when (geometry.crossStaffBase) {
        CrossStaffBeamBase.TOP_STAFF_MIDLINE -> entries.first().value
        CrossStaffBeamBase.BOTTOM_STAFF_MIDLINE -> entries.last().value
        CrossStaffBeamBase.BETWEEN_STAFFS -> {
            val upperY = sortedCenters[geometry.betweenStaffUpperIndex ?: defaultUpper]
                ?: sortedCenters.getValue(defaultUpper)
            val lowerY = sortedCenters[geometry.betweenStaffLowerIndex ?: defaultLower]
                ?: sortedCenters.getValue(defaultLower)
            (upperY + lowerY) / 2f
        }
        null -> return geometry.copy(startDy = geometry.startDy + deltaY, endDy = geometry.endDy + deltaY)
    }
    val targetY = currentBaseY + (geometry.crossStaffOffset ?: 0f) + deltaY
    val candidates = buildList {
        add(Candidate(CrossStaffBeamBase.TOP_STAFF_MIDLINE, entries.first().value))
        entries.zipWithNext().forEach { (upper, lower) ->
            add(Candidate(
                CrossStaffBeamBase.BETWEEN_STAFFS,
                (upper.value + lower.value) / 2f,
                upper.key,
                lower.key,
            ))
        }
        add(Candidate(CrossStaffBeamBase.BOTTOM_STAFF_MIDLINE, entries.last().value))
    }
    val nearest = candidates.minBy { kotlin.math.abs(targetY - it.y) }
    return geometry.copy(
        crossStaffBase = nearest.base,
        crossStaffOffset = targetY - nearest.y,
        betweenStaffUpperIndex = nearest.upperIndex,
        betweenStaffLowerIndex = nearest.lowerIndex,
    )
}

/** Build a transient beam + stem preview from the displayed frame; no document mutation. */
internal fun createBeamDragPreview(
    result: RenderResult,
    controls: BeamControlPoints,
    drag: BeamDragState,
): BeamDragPreview {
    val zeroY = result.transformerSnapshot.toAbsolute(
        RelativePoint(StaffSpace.ZERO, StaffSpace.ZERO)
    ).y.value
    val deltaPixels = result.transformerSnapshot.toAbsolute(
        RelativePoint(StaffSpace.ZERO, StaffSpace(drag.deltaY))
    ).y.value - zeroY
    val startShift = if (drag.endpoint == "end") 0f else deltaPixels
    val endShift = if (drag.endpoint == "start") 0f else deltaPixels
    val x0 = controls.start.x.value
    val x1 = controls.end.x.value

    fun yShiftAt(x: Float): Float {
        if (x1 == x0) return startShift
        val fraction = ((x - x0) / (x1 - x0)).coerceIn(0f, 1f)
        return startShift + (endShift - startShift) * fraction
    }
    fun move(point: AbsolutePoint): AbsolutePoint = point.copy(
        y = Pixels(point.y.value + yShiftAt(point.x.value))
    )
    fun movePath(path: AbsolutePath): AbsolutePath = AbsolutePath(path.segments.map { segment ->
        when (segment) {
            is AbsolutePathSegment.MoveTo -> AbsolutePathSegment.MoveTo(move(segment.point))
            is AbsolutePathSegment.LineTo -> AbsolutePathSegment.LineTo(move(segment.point))
            is AbsolutePathSegment.QuadTo -> AbsolutePathSegment.QuadTo(
                move(segment.control), move(segment.end)
            )
            is AbsolutePathSegment.CubicTo -> AbsolutePathSegment.CubicTo(
                move(segment.control1), move(segment.control2), move(segment.end)
            )
            AbsolutePathSegment.Close -> AbsolutePathSegment.Close
        }
    })

    val commands = ArrayList<RenderCommand>()
    result.sectionIndex.elementsFor(controls.section).elementIds
        .mapNotNull(result::elementById)
        .flatMapTo(commands) { element ->
            element.commands.mapNotNull { command ->
                (command as? DrawPath)?.let { it.copy(path = movePath(it.path)) }
            }
        }
    controls.section.events.mapNotNull { event ->
        result.elementsForEvent(event.id).firstOrNull { it.type == RenderElementType.STEM }
    }.distinctBy { it.id }.forEach { stem ->
        val line = stem.commands.firstOrNull() as? DrawLine ?: return@forEach
        val expectedBeamY = controls.start.y.value +
            (controls.end.y.value - controls.start.y.value) *
            (if (x1 == x0) 0f else ((line.start.x.value - x0) / (x1 - x0)).coerceIn(0f, 1f))
        val startIsBeamTip = kotlin.math.abs(line.start.y.value - expectedBeamY) <=
            kotlin.math.abs(line.end.y.value - expectedBeamY)
        commands += if (startIsBeamTip) {
            line.copy(start = move(line.start))
        } else {
            line.copy(end = move(line.end))
        }
    }

    return BeamDragPreview(
        commands = commands,
        start = move(controls.start),
        end = move(controls.end),
    )
}
