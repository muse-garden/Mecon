package com.mecon.renderer.render

import com.mecon.api.interaction.EventSection
import com.mecon.renderer.geometry.ScaleFactor
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.render.spatial.HittableRegistration

/**
 * One rendered element bundled with everything the [RenderResult] indexes need: its
 * [EventSection]s for the section index and its enriched [HittableRegistration] for the spatial index.
 *
 * This is the cached unit used by the incremental render path: unchanged prefix/tail elements can be
 * reused directly, translated, or dropped and regenerated for an edited window.
 */
internal data class RichElement(
    val element: RenderElement,
    val sections: List<EventSection>,
    val hit: HittableRegistration?,
)

/** Ordered ownership run over the flat rich-element list; preserves exact painter order. */
internal data class PaginatedRichRun(
    val kind: Kind,
    val systemIndex: Int?,
    val fromIndex: Int,
    val toIndexExclusive: Int,
    val bounds: AbsoluteRect,
) {
    enum class Kind { SYSTEM, ANNOTATION, DROP }
}

internal data class PaginatedRichSummary(
    val elements: List<RenderElement>,
    val bounds: AbsoluteRect,
    val runs: List<PaginatedRichRun>,
    val aggregateReused: Boolean = false,
)

/**
 * Build a compact ordered routing directory for paginated splices. Most score passes emit long
 * same-system runs; an unaffected run can therefore be copied with one bulk `addAll(subList)` while
 * retaining the flat list's exact z-order.
 */
internal fun buildPaginatedRichSummary(
    richElements: List<RichElement>,
    layout: com.mecon.renderer.layout.UnifiedLayoutResult,
    cachedRuns: List<PaginatedRichRun>? = null,
): PaginatedRichSummary {
    if (richElements.isEmpty()) return PaginatedRichSummary(
        emptyList(), AbsoluteRect(AbsolutePoint.ZERO, Pixels.ZERO, Pixels.ZERO), emptyList()
    )
    if (cachedRuns != null && cachedRuns.coverExactly(richElements.size)) {
        val bounds = BoundsAccumulator().apply {
            for (run in cachedRuns) add(run.bounds)
        }.build()
        return PaginatedRichSummary(
            RenderElementListView(richElements), bounds, cachedRuns, aggregateReused = true
        )
    }
    data class Band(val systemIndex: Int, val top: Float, val bottom: Float)
    val bands = layout.systems.mapNotNull { system ->
        val notation = system.staffLayouts.filter { it.kind == com.mecon.renderer.layout.StaffKind.NOTATION }
        if (notation.isEmpty()) null else Band(
            system.systemIndex,
            notation.minOf { it.contentTopY.value },
            notation.maxOf { it.contentBottomY.value },
        )
    }
    fun classify(rich: RichElement): Pair<PaginatedRichRun.Kind, Int?> {
        if (rich.element.metadata[ALWAYS_REGENERATED_STRUCTURE] == "true") {
            return PaginatedRichRun.Kind.DROP to rich.element.systemIndex
        }
        if (rich.element.metadata[REUSABLE_SYSTEM_STRUCTURE] == "true") {
            return PaginatedRichRun.Kind.SYSTEM to rich.element.systemIndex
        }
        if (rich.hit == null) {
            val systemIndex = rich.element.systemIndex
            return when {
                rich.element.type == RenderElementType.TEXT_ANNOTATION && systemIndex != null ->
                    PaginatedRichRun.Kind.ANNOTATION to systemIndex
                rich.element.type == RenderElementType.BARLINE && systemIndex != null ->
                    PaginatedRichRun.Kind.SYSTEM to systemIndex
                else -> PaginatedRichRun.Kind.DROP to systemIndex
            }
        }
        val systemIndex = rich.element.systemIndex ?: run {
            val y = rich.hit.relativeHitBox.center.y.value
            com.mecon.renderer.render.spatial.YBandRouting.nearestSorted(
                bands, y, { it.top }, { it.bottom }
            )?.systemIndex
        }
        return if (systemIndex == null) PaginatedRichRun.Kind.DROP to null
        else PaginatedRichRun.Kind.SYSTEM to systemIndex
    }

    val elements = ArrayList<RenderElement>(richElements.size)
    val allBounds = BoundsAccumulator()
    for (rich in richElements) {
        val element = rich.element
        elements.add(element)
        allBounds.add(element.hitBox)
    }
    val bounds = allBounds.build()
    if (!layout.paginated) return PaginatedRichSummary(elements, bounds, emptyList())

    val runs = ArrayList<PaginatedRichRun>()
    var start = 0
    var current = classify(richElements[0])
    var currentBounds = BoundsAccumulator().apply { add(richElements[0].element.hitBox) }
    for (index in 1 until richElements.size) {
        val next = classify(richElements[index])
        if (next != current) {
            runs += PaginatedRichRun(current.first, current.second, start, index, currentBounds.build())
            start = index
            current = next
            currentBounds = BoundsAccumulator()
        }
        currentBounds.add(richElements[index].element.hitBox)
    }
    runs += PaginatedRichRun(
        current.first, current.second, start, richElements.size, currentBounds.build()
    )
    return PaginatedRichSummary(elements, bounds, runs)
}

/** Lazy immutable projection; paginated drawing consumes page buckets, so the flat compatibility list
 * need not be copied again on every splice. */
private class RenderElementListView(
    private val richElements: List<RichElement>,
) : AbstractList<RenderElement>() {
    override val size: Int get() = richElements.size
    override fun get(index: Int): RenderElement = richElements[index].element
}

private class BoundsAccumulator {
    private var empty = true
    private var minX = Float.MAX_VALUE
    private var minY = Float.MAX_VALUE
    private var maxX = -Float.MAX_VALUE
    private var maxY = -Float.MAX_VALUE

    fun add(bounds: AbsoluteRect) {
        empty = false
        minX = minOf(minX, bounds.origin.x.value)
        minY = minOf(minY, bounds.origin.y.value)
        maxX = maxOf(maxX, bounds.origin.x.value + bounds.width.value)
        maxY = maxOf(maxY, bounds.origin.y.value + bounds.height.value)
    }

    fun build(): AbsoluteRect = if (empty) {
        AbsoluteRect(AbsolutePoint.ZERO, Pixels.ZERO, Pixels.ZERO)
    } else {
        AbsoluteRect(
            AbsolutePoint(Pixels(minX), Pixels(minY)), Pixels(maxX - minX), Pixels(maxY - minY)
        )
    }
}

private fun List<PaginatedRichRun>.coverExactly(elementCount: Int): Boolean {
    var cursor = 0
    for (run in this) {
        if (run.fromIndex != cursor || run.toIndexExclusive <= run.fromIndex) return false
        cursor = run.toIndexExclusive
    }
    return cursor == elementCount
}

/** Build runs for a newly rendered subrange, shifting its local indices into the final flat list. */
internal fun buildPaginatedRichRunsForRange(
    richElements: List<RichElement>,
    layout: com.mecon.renderer.layout.UnifiedLayoutResult,
    fromIndex: Int,
    toIndexExclusive: Int,
): List<PaginatedRichRun> {
    if (fromIndex >= toIndexExclusive) return emptyList()
    return buildPaginatedRichSummary(richElements.subList(fromIndex, toIndexExclusive), layout).runs.map { run ->
        run.copy(
            fromIndex = run.fromIndex + fromIndex,
            toIndexExclusive = run.toIndexExclusive + fromIndex,
        )
    }
}

/** A [RichElement] with its element and hit box shifted by (deltaX, deltaY) staff spaces. */
internal fun RichElement.translated(deltaX: StaffSpace, deltaY: StaffSpace, scale: ScaleFactor): RichElement =
    RichElement(
        element = element.translate(deltaX, deltaY, scale),
        sections = sections,
        hit = hit?.let { h ->
            h.copy(
                relativeHitBox = h.relativeHitBox.copy(
                    origin = h.relativeHitBox.origin.copy(
                        x = h.relativeHitBox.origin.x + deltaX,
                        y = h.relativeHitBox.origin.y + deltaY
                    )
                )
            )
        }
    )
