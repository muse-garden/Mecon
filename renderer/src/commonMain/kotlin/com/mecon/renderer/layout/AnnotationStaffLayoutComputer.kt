package com.mecon.renderer.layout

import com.mecon.api.computed.ComputedScore
import com.mecon.api.plugin.AnnotationElement
import com.mecon.api.plugin.AnnotationAlignment
import com.mecon.api.plugin.AnnotationLayoutContext
import com.mecon.api.plugin.AnnotationStaffProvider
import com.mecon.api.plugin.PluginRegistry
import com.mecon.api.plugin.StaffAnchor
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.tracks.Clef
import com.mecon.renderer.geometry.StaffSpace

/**
 * Layout pass that pulls every registered [AnnotationStaffProvider] from
 * [PluginRegistry], invokes it with an [AnnotationLayoutContext], and produces
 * annotation [StaffLayoutInfo]s + [PlacedAnnotationElement]s sitting above or
 * below the notation staves.
 *
 * v1 limitations (per plan):
 * - [StaffAnchor.AboveStaff] / [StaffAnchor.BelowStaff] still fall back to
 *   [StaffAnchor.BelowAllStaves]; score-wide top/bottom anchors are implemented.
 * - Element bounds are measured per element and cached inside this layout computer, so annotation
 *   spacing follows the actual text metrics instead of a fixed character-count estimate.
 */
internal class AnnotationStaffLayoutComputer(
    private val config: RenderLayoutConfig
) {
    private data class AnchoredAnnotation(
        val element: AnnotationElement,
        val x: StaffSpace,
        val systemIndex: Int,
        val order: Int
    )

    private data class HorizontalExtent(
        val left: StaffSpace,
        val right: StaffSpace
    )

    data class Result(
        val staffLayouts: List<StaffLayoutInfo>,
        val placedElements: Map<com.mecon.api.plugin.PluginStaffId, List<PlacedAnnotationElement>>,
        val extraHeight: StaffSpace
    ) {
        companion object {
            val EMPTY = Result(emptyList(), emptyMap(), StaffSpace.ZERO)
        }
    }

    private var loggedFallback = false
    private val elementMeasurer = AnnotationElementMeasurer()

    fun compute(
        computedScore: ComputedScore,
        timeSlotMap: UnifiedTimeSlotMap,
        notationStaffLayouts: List<StaffLayoutInfo>
    ): Result {
        val providers = PluginRegistry.annotationStaffProviders()
        if (providers.isEmpty()) return Result.EMPTY

        val pluginTrackTypesInScore: Set<String> =
            computedScore.pluginTracks.values.map { it.type }.toSet()

        val applicableProviders = providers.filter { it.appliesTo(pluginTrackTypesInScore) }
        if (applicableProviders.isEmpty()) return Result.EMPTY

        val ctx = TimeSlotAnnotationLayoutContext(computedScore, timeSlotMap)

        val notationTopY = notationStaffLayouts
            .filter { it.kind == StaffKind.NOTATION }
            .minOfOrNull { it.contentTopY }
            ?: StaffSpace.ZERO
        val notationBottomY = notationStaffLayouts
            .filter { it.kind == StaffKind.NOTATION }
            .maxOfOrNull { it.contentBottomY }
            ?: StaffSpace.ZERO

        val placedByStaff = mutableMapOf<com.mecon.api.plugin.PluginStaffId, MutableList<PlacedAnnotationElement>>()
        val staffLayouts = mutableListOf<StaffLayoutInfo>()
        var topCursorY = notationTopY
        var bottomCursorY = notationBottomY

        for (provider in applicableProviders) {
            val elements = provider.layout(ctx)
            if (elements.isEmpty()) continue

            val anchor = provider.supportedAnchor()
            if (anchor != provider.anchor && !loggedFallback) {
                println(
                    "[AnnotationStaffLayout] Anchor ${provider.anchor} not yet supported; " +
                        "falling back to BelowAllStaves for plugin staff ${provider.staffId}."
                )
                loggedFallback = true
            }

            val (topExtent, bottomExtent) = elementMeasurer.extents(elements)

            val centerY = when (anchor) {
                StaffAnchor.AboveAllStaves -> topCursorY - config.interStaffGap - bottomExtent
                else -> bottomCursorY + config.interStaffGap + topExtent
            }
            val staffTopY = centerY - topExtent
            val staffBottomY = centerY + bottomExtent

            staffLayouts.add(
                StaffLayoutInfo(
                    trackId = ANNOTATION_PLACEHOLDER_TRACK_ID,
                    staffIndex = -1,
                    partIndex = -1,
                    centerY = centerY,
                    topY = staffTopY,
                    bottomY = staffBottomY,
                    contentTopY = staffTopY,
                    contentBottomY = staffBottomY,
                    clef = ANNOTATION_PLACEHOLDER_CLEF,
                    kind = StaffKind.ANNOTATION,
                    pluginStaffId = provider.staffId
                )
            )

            val placed = placedByStaff.getOrPut(provider.staffId) { mutableListOf() }
            placed += placeTrackLocal(provider, elements) { element ->
                ctx.xForTime(element.time)?.let { xValue -> 0 to StaffSpace(xValue) }
            }.map { anchored ->
                PlacedAnnotationElement(
                    staffId = provider.staffId,
                    x = anchored.x,
                    centerY = centerY + StaffSpace(anchored.element.relativeY),
                    element = anchored.element
                )
            }

            if (anchor is StaffAnchor.AboveAllStaves) {
                topCursorY = staffTopY
            } else {
                bottomCursorY = staffBottomY
            }
        }

        val extraHeight = bottomCursorY - notationBottomY

        return Result(
            staffLayouts = staffLayouts,
            placedElements = placedByStaff.mapValues { it.value.toList() },
            extraHeight = extraHeight
        )
    }

    /**
     * Paginated per-system placement: re-resolve every annotation element against the **post-break**
     * [timeSlotMap] (per-system justified X + [UnifiedTimeSlot.systemIndex]) and place it below the
     * notation staves **of the system it belongs to**, rather than the single global baseline that
     * [compute] produces (which is only correct for system 0).
     *
     * Each annotation is anchored to a time slot, so its system and X come straight from that slot — the
     * "render like a fixed-position note" model. Y is stacked below the owning system's notation bottom,
     * one band per provider, per system.
     *
     * v1 limitation: this positions annotations but does **not** reserve vertical room for them in the
     * page packing (unlike continuous mode's `extraHeight`); they sit in the inter-system gap. Folding the
     * band height into [SystemBreaker]'s vertical pass is future work.
     */
    fun computePaginated(
        computedScore: ComputedScore,
        timeSlotMap: UnifiedTimeSlotMap,
        systems: List<SystemLayout>,
    ): Map<com.mecon.api.plugin.PluginStaffId, List<PlacedAnnotationElement>> {
        val providers = PluginRegistry.annotationStaffProviders()
        if (providers.isEmpty()) return emptyMap()

        val pluginTrackTypesInScore = computedScore.pluginTracks.values.map { it.type }.toSet()
        val applicableProviders = providers.filter { it.appliesTo(pluginTrackTypesInScore) }
        if (applicableProviders.isEmpty()) return emptyMap()

        val ctx = TimeSlotAnnotationLayoutContext(computedScore, timeSlotMap)

        val notationTopBySystem = systems.associate { sys ->
            sys.systemIndex to (
                sys.staffLayouts.filter { it.kind == StaffKind.NOTATION }
                    .minOfOrNull { it.contentTopY } ?: StaffSpace.ZERO
                )
        }
        val notationBottomBySystem = systems.associate { sys ->
            sys.systemIndex to (
                sys.staffLayouts.filter { it.kind == StaffKind.NOTATION }
                    .maxOfOrNull { it.contentBottomY } ?: StaffSpace.ZERO
                )
        }
        val topCursorYBySystem = notationTopBySystem.toMutableMap()
        val bottomCursorYBySystem = notationBottomBySystem.toMutableMap()

        val placedByStaff = mutableMapOf<com.mecon.api.plugin.PluginStaffId, MutableList<PlacedAnnotationElement>>()

        for (provider in applicableProviders) {
            val elements = provider.layout(ctx)
            if (elements.isEmpty()) continue
            val anchor = provider.supportedAnchor()

            val resolved = placeTrackLocal(provider, elements) { element ->
                val slot = timeSlotMap.atTime(element.time) ?: return@placeTrackLocal null
                val noteLeftX = slot.noteEvents().minOfOrNull { (slot.x + it.relativeX).value } ?: slot.x.value
                slot.systemIndex to StaffSpace(noteLeftX)
            }
            if (resolved.isEmpty()) continue

            val placed = placedByStaff.getOrPut(provider.staffId) { mutableListOf() }
            // One band per system: extents computed from that system's elements and stacked outward.
            for ((systemIndex, group) in resolved.groupBy { it.systemIndex }) {
                val (topExtent, bottomExtent) = elementMeasurer.extents(group.map { it.element })
                val centerY = if (anchor is StaffAnchor.AboveAllStaves) {
                    val baseY = topCursorYBySystem[systemIndex]
                        ?: notationTopBySystem[systemIndex]
                        ?: StaffSpace.ZERO
                    baseY - config.interStaffGap - bottomExtent
                } else {
                    val baseY = bottomCursorYBySystem[systemIndex]
                        ?: notationBottomBySystem[systemIndex]
                        ?: StaffSpace.ZERO
                    baseY + config.interStaffGap + topExtent
                }
                for (r in group) {
                    placed += PlacedAnnotationElement(
                        staffId = provider.staffId,
                        x = r.x,
                        centerY = centerY + StaffSpace(r.element.relativeY),
                        element = r.element,
                        systemIndex = systemIndex,
                    )
                }
                if (anchor is StaffAnchor.AboveAllStaves) {
                    topCursorYBySystem[systemIndex] = centerY - topExtent
                } else {
                    bottomCursorYBySystem[systemIndex] = centerY + bottomExtent
                }
            }
        }

        return placedByStaff.mapValues { it.value.toList() }
    }

    /**
     * Build the per-line annotation band height function used by [SystemBreaker] to reserve vertical room
     * below each line's notation staves (so paginated annotations don't collide with the next system).
     *
     * Captures every applicable provider's elements **once** (content only — positions aren't needed) and
     * returns `range -> Σ_providers (interStaffGap + topExtent + bottomExtent)` over the provider's elements
     * whose [AnnotationElement.time] measure falls in `range`. This exactly mirrors the stacking in
     * [computePaginated] (each provider adds `interStaffGap + extents` below the running cursor), so the
     * reserved room equals the space the placed elements occupy. Returns a constant-ZERO function when no
     * provider applies (the common no-plugin case), which [SystemBreaker] treats as a no-op.
     */
    fun perLineExtentsFn(computedScore: ComputedScore): (IntRange) -> AnnotationLineExtents {
        val providers = PluginRegistry.annotationStaffProviders()
        if (providers.isEmpty()) return { AnnotationLineExtents.ZERO }

        val pluginTrackTypesInScore = computedScore.pluginTracks.values.map { it.type }.toSet()
        val applicableProviders = providers.filter { it.appliesTo(pluginTrackTypesInScore) }
        if (applicableProviders.isEmpty()) return { AnnotationLineExtents.ZERO }

        val dummyCtx = object : AnnotationLayoutContext {
            override val computedScore: ComputedScore = computedScore
            override fun xForTime(time: TimeCode): Float? = null
        }
        val providerElements: List<List<AnnotationElement>> = applicableProviders.map { it.layout(dummyCtx) }
        if (providerElements.all { it.isEmpty() }) return { AnnotationLineExtents.ZERO }

        return { range ->
            var above = 0f
            var below = 0f
            for ((index, elements) in providerElements.withIndex()) {
                val inLine = elements.filter { it.time.measure in range }
                if (inLine.isEmpty()) continue
                val (topExtent, bottomExtent) = elementMeasurer.extents(inLine)
                val extent = config.interStaffGap.value + topExtent.value + bottomExtent.value
                if (applicableProviders[index].supportedAnchor() is StaffAnchor.AboveAllStaves) {
                    above += extent
                } else {
                    below += extent
                }
            }
            AnnotationLineExtents(StaffSpace(above), StaffSpace(below))
        }
    }

    private fun placeTrackLocal(
        provider: AnnotationStaffProvider,
        elements: List<AnnotationElement>,
        resolveAnchor: (AnnotationElement) -> Pair<Int, StaffSpace>?
    ): List<AnchoredAnnotation> {
        val fallbackTrackId = TrackId("__annotation__:${provider.staffId.value}")
        val anchored = elements.mapIndexedNotNull { index, element ->
            val (systemIndex, x) = resolveAnchor(element) ?: return@mapIndexedNotNull null
            AnchoredAnnotation(element, x, systemIndex, index)
        }
        if (anchored.size <= 1) return anchored

        val placed = mutableListOf<AnchoredAnnotation>()
        val minGap = StaffSpace(MIN_HORIZONTAL_GAP_STAFF_SPACE)
        for ((_, group) in anchored.groupBy { it.systemIndex to (it.element.trackId ?: fallbackTrackId) }) {
            var lastRight: StaffSpace? = null
            for (item in group.sortedWith(compareBy<AnchoredAnnotation> { it.element.time }.thenBy { it.order })) {
                var x = item.x
                var extent = horizontalExtent(item.element, x)
                val minLeft = lastRight?.let { it + minGap }
                if (minLeft != null && extent.left < minLeft) {
                    x += minLeft - extent.left
                    extent = horizontalExtent(item.element, x)
                }
                lastRight = lastRight?.let { maxOf(it, extent.right) } ?: extent.right
                placed += item.copy(x = x)
            }
        }
        return placed.sortedBy { it.order }
    }

    private fun AnnotationStaffProvider.appliesTo(pluginTrackTypesInScore: Set<String>): Boolean =
        pluginTrackTypes.isEmpty() || pluginTrackTypes.any { it in pluginTrackTypesInScore }

    private fun AnnotationStaffProvider.supportedAnchor(): StaffAnchor = when (anchor) {
        StaffAnchor.AboveAllStaves,
        StaffAnchor.BelowAllStaves -> anchor
        is StaffAnchor.AboveStaff,
        is StaffAnchor.BelowStaff -> StaffAnchor.BelowAllStaves
    }

    private fun horizontalExtent(element: AnnotationElement, x: StaffSpace): HorizontalExtent {
        val width = elementMeasurer.widthStaffSpace(element)
        return when (element) {
            is AnnotationElement.Text -> when (element.alignment) {
                AnnotationAlignment.LEFT -> HorizontalExtent(x, x + width)
                AnnotationAlignment.CENTER -> HorizontalExtent(x - StaffSpace(width.value / 2f), x + StaffSpace(width.value / 2f))
                AnnotationAlignment.RIGHT -> HorizontalExtent(x - width, x)
            }
        }
    }

    private class TimeSlotAnnotationLayoutContext(
        override val computedScore: ComputedScore,
        private val timeSlotMap: UnifiedTimeSlotMap
    ) : AnnotationLayoutContext {
        override fun xForTime(time: TimeCode): Float? {
            val slot = timeSlotMap.atTime(time) ?: return null
            // slot.x is the right edge; note relativeX is negative, so slot.x + relativeX = note left edge
            val noteLeftX = slot.noteEvents().minOfOrNull { (slot.x + it.relativeX).value }
            return noteLeftX ?: slot.x.value
        }
    }

    companion object {
        /** Minimum gap between adjacent annotation elements, in staff spaces. */
        private const val MIN_HORIZONTAL_GAP_STAFF_SPACE: Float = 0.5f

        private val ANNOTATION_PLACEHOLDER_TRACK_ID = TrackId("__annotation__")
        private val ANNOTATION_PLACEHOLDER_CLEF = Clef.TREBLE
    }
}

internal data class AnnotationLineExtents(
    val above: StaffSpace,
    val below: StaffSpace,
) {
    val total: StaffSpace get() = above + below

    companion object {
        val ZERO = AnnotationLineExtents(StaffSpace.ZERO, StaffSpace.ZERO)
    }
}
