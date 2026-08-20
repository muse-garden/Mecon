package com.mecon.renderer.render

import com.mecon.api.primitive.TimeCode
import com.mecon.renderer.elements.NoteElement
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.interaction.SectionIndex
import com.mecon.renderer.interaction.SectionIndexBuilder
import com.mecon.renderer.layout.ArticulationLayout
import com.mecon.renderer.layout.SlurLayout
import com.mecon.renderer.layout.TieLayout
import com.mecon.renderer.layout.UnifiedLayoutResult
import com.mecon.renderer.render.spatial.ScoreSpatialAdapter
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.toPersistentMap

internal data class RenderAssembly(
    val result: RenderResult,
    val richElements: List<RichElement>,
    val paginatedRichRuns: List<PaginatedRichRun> = emptyList(),
    val incremental: Boolean,
    val geometryCapture: IncrementalGeometryCapture? = null,
)

/** Window layouts already produced by an incremental render and reusable by the live-geometry fold. */
internal data class IncrementalGeometryCapture(
    val articulations: Map<com.mecon.api.primitive.EventId, ArticulationLayout>,
    val ties: List<TieLayout>,
    val slurs: List<SlurLayout>,
)

private data class TimeCodeGeometry(
    val positions: Map<TimeCode, TimeCodePosition>,
    val noteheadRights: Map<TimeCode, Map<Pair<Int, Int>, Float>>,
    val sharedNoteheadRightsByVoice: Map<TimeCode, Map<Int, Float>>,
    val sharedNoteheadRights: Map<TimeCode, Float>,
)

/** Most common rendered column; stable top-to-bottom insertion order breaks ties. */
private fun representativeNoteheadRight(values: Iterable<Float>): Float? {
    val counts = linkedMapOf<Float, Int>()
    var best: Float? = null
    var bestCount = 0
    for (value in values) {
        val count = (counts[value] ?: 0) + 1
        counts[value] = count
        if (count > bestCount) {
            best = value
            bestCount = count
        }
    }
    return best
}

/**
 * Assembles render elements into immutable [RenderResult]s, including section/spatial indexes and pages.
 */
internal class RenderResultAssembler(
    private val transformer: CoordinateTransformer,
    private val scoreSpatialAdapter: ScoreSpatialAdapter,
    private val pageBuilder: RenderPageBuilder,
) {
    fun assemble(
        richElements: List<RichElement>,
        layoutResult: UnifiedLayoutResult,
        isCancelled: () -> Boolean = { false },
    ): RenderAssembly {
        val summary = buildPaginatedRichSummary(richElements, layoutResult)
        val elements = summary.elements

        val sectionBuilder = SectionIndexBuilder()
        val elementIndexBuilder = persistentHashMapOf<RenderElementId, RenderElement>().builder()
        var paginatedSpliceSafe = true
        for (rich in richElements) {
            if (!rich.isPaginatedSpliceSafe()) paginatedSpliceSafe = false
            if (rich.sections.isNotEmpty()) {
                elementIndexBuilder[rich.element.id] = rich.element
            }
            for (section in rich.sections) sectionBuilder.register(section, rich.element.id)
        }
        val hittableRegistrations = richElements.mapNotNull { it.hit }

        val barlines = layoutResult.barlineLayouts.all()
        val measureBoundaries = ScoreSpatialAdapter.computeMeasureBoundaries(barlines, layoutResult.width)
        val bounds = summary.bounds
        val timeCodeGeometry = computeTimeCodeGeometry(layoutResult, bounds)

        isCancelled.throwIfCancelled() // before the whole-score spatial index rebuild (O(N), the heaviest step)
        val hierarchicalIndex = scoreSpatialAdapter.buildIndex(
            layoutResult, measureBoundaries, hittableRegistrations
        )
        val transformerSnapshot = transformer.copy()
        val pages = if (layoutResult.paginated && layoutResult.pages.isNotEmpty()) {
            pageBuilder.build(elements, layoutResult)
        } else emptyList()

        return RenderAssembly(
            result = buildResult(
                elements = elements,
                bounds = bounds,
                layoutResult = layoutResult,
                sectionIndex = sectionBuilder.build(),
                elementIndex = elementIndexBuilder.build(),
                timeCodePositions = timeCodeGeometry.positions,
                noteheadRightPositions = timeCodeGeometry.noteheadRights,
                sharedNoteheadRightPositionsByVoice = timeCodeGeometry.sharedNoteheadRightsByVoice,
                sharedNoteheadRightPositions = timeCodeGeometry.sharedNoteheadRights,
                spatialIndex = hierarchicalIndex,
                transformerSnapshot = transformerSnapshot,
                pages = pages,
                paginatedSpliceSafe = paginatedSpliceSafe,
            ),
            richElements = richElements,
            paginatedRichRuns = summary.runs,
            incremental = false
        )
    }

    fun assembleIncremental(
        richElements: List<RichElement>,
        layoutResult: UnifiedLayoutResult,
        cachedResult: RenderResult?,
        removedWindow: List<RichElement>,
        windowRich: List<RichElement>,
        isCancelled: () -> Boolean = { false },
    ): RenderAssembly? {
        if (cachedResult == null || layoutResult.paginated) return null

        isCancelled.throwIfCancelled()
        val barlines = layoutResult.barlineLayouts.all()
        val measureBoundaries = ScoreSpatialAdapter.computeMeasureBoundaries(barlines, layoutResult.width)
        val spatialIndex = scoreSpatialAdapter.buildIndexIncremental(
            cached = cachedResult.spatialIndex,
            layoutResult = layoutResult,
            measureBoundaries = measureBoundaries,
            removedWindowHittables = removedWindow.mapNotNull { it.hit },
            newWindowHittables = windowRich.mapNotNull { it.hit },
        ) ?: return null

        val sectionIndex = spliceSectionIndex(cachedResult.sectionIndex, removedWindow, windowRich)
        val elementIndex = spliceElementIndex(cachedResult.elementIndex, removedWindow, windowRich)
        val summary = buildPaginatedRichSummary(richElements, layoutResult)
        val elements = summary.elements
        val bounds = summary.bounds
        val timeCodeGeometry = computeTimeCodeGeometry(layoutResult, bounds)
        val transformerSnapshot = transformer.copy()

        return RenderAssembly(
            result = buildResult(
                elements = elements,
                bounds = bounds,
                layoutResult = layoutResult,
                sectionIndex = sectionIndex,
                elementIndex = elementIndex,
                timeCodePositions = timeCodeGeometry.positions,
                noteheadRightPositions = timeCodeGeometry.noteheadRights,
                sharedNoteheadRightPositionsByVoice = timeCodeGeometry.sharedNoteheadRightsByVoice,
                sharedNoteheadRightPositions = timeCodeGeometry.sharedNoteheadRights,
                spatialIndex = spatialIndex,
                transformerSnapshot = transformerSnapshot,
                pages = emptyList(),
                paginated = false
            ),
            richElements = richElements,
            paginatedRichRuns = summary.runs,
            incremental = true
        )
    }

    fun assembleIncrementalPaginated(
        richElements: List<RichElement>,
        layoutResult: UnifiedLayoutResult,
        cachedResult: RenderResult?,
        removedHit: List<RichElement>,
        windowRich: List<RichElement>,
        affectedSystems: Set<Int>,
        deltaYBySystem: Map<Int, StaffSpace>,
        translatedIndexUpdates: List<RichElement> = emptyList(),
        precomputedRuns: List<PaginatedRichRun>? = null,
        isCancelled: () -> Boolean = { false },
    ): RenderAssembly? {
        if (cachedResult == null || !layoutResult.paginated) return null

        isCancelled.throwIfCancelled()
        val _tIndex = kotlin.time.TimeSource.Monotonic.markNow()
        val _tSpatial = kotlin.time.TimeSource.Monotonic.markNow()
        val spatialIndex = scoreSpatialAdapter.buildIndexIncrementalPaginated(
            cached = cachedResult.spatialIndex,
            layoutResult = layoutResult,
            affectedSystems = affectedSystems,
            deltaYBySystem = deltaYBySystem,
            newHittables = windowRich.mapNotNull { it.hit },
        ) ?: return null
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "assemble.spatial=${_tSpatial.elapsedNow().inWholeMilliseconds}ms newHittables=${windowRich.count { it.hit != null }}"
        }

        val _tSection = kotlin.time.TimeSource.Monotonic.markNow()
        val sectionIndex = cachedResult.sectionIndex.replaceSystems(
            affectedSystems = affectedSystems,
            added = windowRich,
            elementId = { it.element.id },
            sections = { it.sections },
        )
        val elementIndex = spliceElementIndex(
            cachedResult.elementIndex, removedHit, windowRich, translatedIndexUpdates,
        )
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "assemble.section=${_tSection.elapsedNow().inWholeMilliseconds}ms removed=${removedHit.size} added=${windowRich.size}"
        }
        val _tElements = kotlin.time.TimeSource.Monotonic.markNow()
        val summary = buildPaginatedRichSummary(richElements, layoutResult, precomputedRuns)
        val elements = summary.elements
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "assemble.summary=${_tElements.elapsedNow().inWholeMilliseconds}ms elements=${elements.size} " +
                "runs=${summary.runs.size} aggregateReused=${summary.aggregateReused}"
        }
        val bounds = summary.bounds
        val _tTimeCodes = kotlin.time.TimeSource.Monotonic.markNow()
        val timeCodeGeometry = computeTimeCodeGeometry(layoutResult, bounds)
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "assemble.timeCodes=${_tTimeCodes.elapsedNow().inWholeMilliseconds}ms slots=${timeCodeGeometry.positions.size}"
        }
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "assemble.paginated index+section+bounds+tcp=${_tIndex.elapsedNow().inWholeMilliseconds}ms elements=${elements.size}"
        }
        // Pages: reuse cached RenderPages by reference for every page that neither regenerated nor shifted
        // a system (so the per-page Skia cache replays them); re-slice only the affected pages. Falls back
        // to a full build on a partition-shape mismatch (reflow). A page is affected when it holds a system
        // in the splice window or one that moved vertically (Δy ≠ 0; height changes propagate down a page).
        val _tPages = kotlin.time.TimeSource.Monotonic.markNow()
        val pages = if (layoutResult.pages.isNotEmpty()) {
            val affectedPages = layoutResult.systems
                .filter {
                    it.systemIndex in affectedSystems ||
                        (deltaYBySystem[it.systemIndex] ?: StaffSpace.ZERO) != StaffSpace.ZERO
                }
                .mapTo(HashSet()) { it.pageIndex }
            pageBuilder.buildIncremental(
                richElements, summary.runs, layoutResult, cachedResult.pages, affectedPages
            )
                ?: pageBuilder.build(elements, layoutResult)
        } else emptyList()
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "assemble.pages=${_tPages.elapsedNow().inWholeMilliseconds}ms pages=${pages.size} elements=${elements.size}"
        }
        val transformerSnapshot = transformer.copy()

        return RenderAssembly(
            result = buildResult(
                elements = elements,
                bounds = bounds,
                layoutResult = layoutResult,
                sectionIndex = sectionIndex,
                elementIndex = elementIndex,
                timeCodePositions = timeCodeGeometry.positions,
                noteheadRightPositions = timeCodeGeometry.noteheadRights,
                sharedNoteheadRightPositionsByVoice = timeCodeGeometry.sharedNoteheadRightsByVoice,
                sharedNoteheadRightPositions = timeCodeGeometry.sharedNoteheadRights,
                spatialIndex = spatialIndex,
                transformerSnapshot = transformerSnapshot,
                pages = pages,
                paginated = true,
                paginatedSpliceSafe = true,
            ),
            richElements = richElements,
            paginatedRichRuns = summary.runs,
            incremental = true
        )
    }

    private fun spliceSectionIndex(
        cached: SectionIndex,
        removed: List<RichElement>,
        addedRich: List<RichElement>,
    ): SectionIndex {
        val removedIds = removed.mapTo(HashSet()) { it.element.id }
        return cached.spliceWindowEntries(
            removedElementIds = removedIds,
            added = addedRich,
            elementId = { it.element.id },
            sections = { it.sections },
        )
    }

    private fun spliceElementIndex(
        cached: Map<RenderElementId, RenderElement>,
        removed: List<RichElement>,
        added: List<RichElement>,
        updated: List<RichElement> = emptyList(),
    ): Map<RenderElementId, RenderElement> {
        val builder = ((cached as? PersistentMap<RenderElementId, RenderElement>)
            ?: cached.toPersistentMap()).builder()
        for (rich in removed) builder.remove(rich.element.id)
        for (rich in added) {
            if (rich.sections.isNotEmpty()) builder[rich.element.id] = rich.element
        }
        for (rich in updated) builder[rich.element.id] = rich.element
        return builder.build()
    }

    private fun computeTimeCodeGeometry(
        layoutResult: UnifiedLayoutResult,
        bounds: AbsoluteRect,
    ): TimeCodeGeometry {
        val timeCodePositions = mutableMapOf<TimeCode, TimeCodePosition>()
        val noteheadRightPositions = mutableMapOf<TimeCode, MutableMap<Pair<Int, Int>, Float>>()
        val fullTopY = bounds.origin.y.value
        val fullBottomY = fullTopY + bounds.height.value
        val systemBands: Map<Int, Pair<Float, Float>> = layoutResult.systems.associate { sys ->
            val tops = sys.staffLayouts.map {
                transformer.toAbsolute(RelativePoint(StaffSpace.ZERO, it.topY)).y.value
            }
            val bottoms = sys.staffLayouts.map {
                transformer.toAbsolute(RelativePoint(StaffSpace.ZERO, it.bottomY)).y.value
            }
            sys.systemIndex to Pair(tops.minOrNull() ?: fullTopY, bottoms.maxOrNull() ?: fullBottomY)
        }
        // leftX (see [TimeCodePosition.leftX]) is the leftmost element edge among a note slot AND any
        // non-note slots (barline / clef / key / time signature) sitting in the gap since the previous
        // note slot — so a clef inserted on a measure downbeat snaps to that measure's opening barline
        // (a separate, earlier slot), while a mid-measure onset snaps to its own note group's left edge.
        // A single time-ordered pass suffices: within a system, time order is X-ascending and the
        // measure-opening barline slot immediately precedes the downbeat note slot; the accumulator
        // resets at every note slot and on each system change (paginated lines restart X near the margin).
        // slot.x + min(event.relativeX) is the slot's leftmost element edge (relativeX is negative,
        // measured leftward from the slot's right edge slot.x).
        var pendingLeftRel = Float.POSITIVE_INFINITY
        var currentSystem = Int.MIN_VALUE
        for (slot in layoutResult.timeSlotMap.all()) {
            if (slot.systemIndex != currentSystem) {
                currentSystem = slot.systemIndex
                pendingLeftRel = Float.POSITIVE_INFINITY
            }
            // Capture the final note column in the same whole-layout pass as the time positions.
            // `slot.x` encloses all slot content; the head bounds retain the collision-resolved local
            // offset after accidentals, dots and simultaneous voices have expanded that content.
            for (event in slot.events) {
                if (event !is NoteElement || event.isRest || event.noteBody.noteheads.isEmpty()) continue
                val noteheadRight = event.noteBody.noteheads.maxOf { notehead ->
                    notehead.geometry.bounds.origin.x.value + notehead.geometry.bounds.width.value
                }
                val relativeX = slot.x + event.relativeX + StaffSpace(noteheadRight)
                val absoluteX = transformer.toAbsolute(RelativePoint(relativeX, StaffSpace.ZERO)).x.value
                val byStaffVoice = noteheadRightPositions.getOrPut(slot.time) { mutableMapOf() }
                val key = event.staffIndex to event.voiceNumber
                byStaffVoice[key] = maxOf(byStaffVoice[key] ?: Float.NEGATIVE_INFINITY, absoluteX)
            }
            val slotLeftRel = slot.x.value + (slot.events.minOfOrNull { it.relativeX.value } ?: 0f)
            if (!slot.hasNotes()) {
                pendingLeftRel = minOf(pendingLeftRel, slotLeftRel)
                continue
            }
            val absX = transformer.toAbsolute(RelativePoint(slot.x, StaffSpace.ZERO)).x.value
            val leftRel = minOf(pendingLeftRel, slotLeftRel)
            val leftAbsX = transformer.toAbsolute(RelativePoint(StaffSpace(leftRel), StaffSpace.ZERO)).x.value
            pendingLeftRel = Float.POSITIVE_INFINITY
            val band = systemBands[slot.systemIndex]
            timeCodePositions[slot.time] = TimeCodePosition(
                timeCode = slot.time,
                x = absX,
                topY = band?.first ?: fullTopY,
                bottomY = band?.second ?: fullBottomY,
                leftX = leftAbsX,
            )
        }
        val immutableNoteheadRights = noteheadRightPositions.mapValues { (_, byStaffVoice) ->
            byStaffVoice.toMap()
        }
        val sharedByVoice = immutableNoteheadRights.mapValues { (_, byStaffVoice) ->
            byStaffVoice.entries
                .groupBy { (staffVoice, _) -> staffVoice.second }
                .mapValues { (_, entries) ->
                    checkNotNull(representativeNoteheadRight(entries.map { it.value }))
                }
        }
        val shared = immutableNoteheadRights.mapValues { (_, byStaffVoice) ->
            checkNotNull(representativeNoteheadRight(byStaffVoice.values))
        }
        return TimeCodeGeometry(
            positions = timeCodePositions,
            noteheadRights = immutableNoteheadRights,
            sharedNoteheadRightsByVoice = sharedByVoice,
            sharedNoteheadRights = shared,
        )
    }

    private fun buildResult(
        elements: List<RenderElement>,
        bounds: AbsoluteRect,
        layoutResult: UnifiedLayoutResult,
        sectionIndex: SectionIndex,
        elementIndex: Map<RenderElementId, RenderElement>,
        timeCodePositions: Map<TimeCode, TimeCodePosition>,
        noteheadRightPositions: Map<TimeCode, Map<Pair<Int, Int>, Float>>,
        sharedNoteheadRightPositionsByVoice: Map<TimeCode, Map<Int, Float>>,
        sharedNoteheadRightPositions: Map<TimeCode, Float>,
        spatialIndex: com.mecon.renderer.render.spatial.HierarchicalSpatialIndex,
        transformerSnapshot: CoordinateTransformer,
        pages: List<RenderPage>,
        paginated: Boolean = layoutResult.paginated,
        paginatedSpliceSafe: Boolean = false,
    ): RenderResult {
        val _tBuildResult = kotlin.time.TimeSource.Monotonic.markNow()
        val _tMeasureBounds = kotlin.time.TimeSource.Monotonic.markNow()
        val measureBounds = computeRenderedMeasureBounds(layoutResult)
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "assemble.measureBounds=${_tMeasureBounds.elapsedNow().inWholeMilliseconds}ms measures=${measureBounds.size}"
        }
        val result = RenderResult(
            elements = elements,
            bounds = bounds,
            firstSystem = 0,
            lastSystem = (layoutResult.systems.size - 1).coerceAtLeast(0),
            firstMeasure = 1,
            lastMeasure = layoutResult.barlineLayouts.size.coerceAtLeast(1),
            sectionIndex = sectionIndex,
            elementIndex = elementIndex,
            timeCodePositions = timeCodePositions,
            noteheadRightPositions = noteheadRightPositions,
            sharedNoteheadRightPositionsByVoice = sharedNoteheadRightPositionsByVoice,
            sharedNoteheadRightPositions = sharedNoteheadRightPositions,
            resolvedTimeAxis = layoutResult.resolvedTimeAxis,
            spatialIndex = spatialIndex,
            transformerSnapshot = transformerSnapshot,
            paginated = paginated,
            pages = pages,
            measureBounds = measureBounds,
            paginatedSpliceSafe = paginatedSpliceSafe,
        )
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "assemble.buildResult=${_tBuildResult.elapsedNow().inWholeMilliseconds}ms elements=${elements.size}"
        }
        return result
    }

    /**
     * Build measure spans from the actual barline events' time slots. Slot X is the cluster's right
     * edge, so the engraved rule is `slot.x + barline.relativeX`; never substitute a note slot.
     */
    private fun computeRenderedMeasureBounds(layout: UnifiedLayoutResult): List<RenderedMeasureBounds> {
        val closingBySystemAndMeasure = HashMap<Pair<Int, Int>, StaffSpace>()
        for (slot in layout.timeSlotMap.all()) {
            for (barline in slot.barlineEvents()) {
                if (barline.measureNumber > 0) {
                    closingBySystemAndMeasure[slot.systemIndex to barline.measureNumber] =
                        slot.x + barline.relativeX
                }
            }
        }
        val result = ArrayList<RenderedMeasureBounds>()
        for (system in layout.systems) {
            var left = system.lineStartX
            for (measure in system.measureRange) {
                val right = when {
                    measure == system.measureRange.last && system.closingBarline != null ->
                        system.closingBarline.x
                    else -> closingBySystemAndMeasure[system.systemIndex to measure]
                        ?: layout.barlineLayouts.forMeasure(measure)?.x
                        ?: system.lineEndX
                }
                if (right > left) {
                    result += RenderedMeasureBounds(system.systemIndex, measure, left, right)
                }
                left = right
            }
        }
        return result
    }
}
