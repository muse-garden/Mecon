package com.mecon.renderer.render.edit

import com.mecon.api.interaction.BarlineSection
import com.mecon.api.primitive.TimeCode
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderResult
import com.mecon.renderer.render.TimeCodePosition
import kotlin.math.abs

/** The two visual boundary kinds shared by clefs and breath marks. */
enum class InsertionBoundaryKind {
    NOTE_GAP,
    BARLINE,
}

/** A rendered X boundary resolved to the stable musical time on its right. */
data class InsertionBoundary(
    val time: TimeCode,
    val absoluteX: Float,
    val kind: InsertionBoundaryKind,
)

/**
 * Return the visual boundaries on one system: exact barlines and midpoints between adjacent note
 * slots. A boundary between two note slots addresses the note on its right, matching the stored
 * onset used by clef changes and the `afterTime` used by breath marks.
 */
fun RenderResult.insertionBoundaries(systemIndex: Int?): List<InsertionBoundary> {
    if (insertionBoundariesBySystem.isNotEmpty()) {
        return if (systemIndex == null) {
            insertionBoundariesBySystem.entries.sortedBy { it.key }.flatMap { it.value }
        } else {
            insertionBoundariesBySystem[systemIndex].orEmpty()
        }
    }
    // Compatibility fallback for small hand-built RenderResult fixtures that predate the index.
    val positions = timeCodePositions.values
        .filter { it.isOnSystem(this, systemIndex) }
    val barlines = elements.asSequence()
        .filter { it.type == RenderElementType.BARLINE }
        .filter { systemIndex == null || it.systemIndex == systemIndex }
        .flatMap { element ->
            sectionIndex.sectionsFor(element.id).asSequence()
                .filterIsInstance<BarlineSection>()
                .map { section ->
                    InsertionBoundary(
                        time = section.barline.time,
                        absoluteX = element.center.x.value,
                        kind = InsertionBoundaryKind.BARLINE,
                    )
                }
        }
        .distinctBy { it.time to it.absoluteX }
        .toList()
    return buildInsertionBoundaries(positions, barlines)
}

internal fun buildInsertionBoundaries(
    positions: List<TimeCodePosition>,
    barlines: List<InsertionBoundary>,
): List<InsertionBoundary> {
    val distinctBarlines = barlines.distinctBy { it.time to it.absoluteX }
    val gaps = positions.sortedBy { it.x }.zipWithNext().mapNotNull { (left, right) ->
        // A real barline owns the interval it crosses. Do not also expose a note-gap candidate
        // with a different structural TimeCode for the same visual boundary.
        if (distinctBarlines.any { it.absoluteX in left.x..right.x }) return@mapNotNull null
        InsertionBoundary(
            time = right.timeCode,
            absoluteX = (left.x + right.x) / 2f,
            kind = InsertionBoundaryKind.NOTE_GAP,
        )
    }
    return (gaps + distinctBarlines).distinctBy { Triple(it.time, it.absoluteX, it.kind) }
}

fun RenderResult.nearestInsertionBoundary(
    absoluteX: Float,
    systemIndex: Int?,
): InsertionBoundary? = insertionBoundaries(systemIndex)
    .minByOrNull { abs(it.absoluteX - absoluteX) }

private fun TimeCodePosition.isOnSystem(
    result: RenderResult,
    systemIndex: Int?,
): Boolean {
    if (systemIndex == null) return true
    val system = result.spatialIndex.allSystems()
        .firstOrNull { it.systemIndex == systemIndex } ?: return false
    val middleY = (topY + bottomY) / 2f
    val relativeY = result.transformerSnapshot.toRelative(
        AbsolutePoint(Pixels(x), Pixels(middleY)),
    ).y
    return relativeY >= system.topY && relativeY <= system.bottomY
}
