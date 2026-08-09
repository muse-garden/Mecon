package com.mecon.renderer.render

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.interaction.SectionIndex
import com.mecon.renderer.layout.ResolvedTimeAxis
import com.mecon.renderer.render.spatial.HierarchicalSpatialIndex
import com.mecon.renderer.render.spatial.ScoreHittableElement
import com.mecon.renderer.render.spatial.StaffHit

/**
 * Result of rendering a score section.
 */
data class RenderResult(
    /** All render elements */
    val elements: List<RenderElement>,
    /** Total bounds of the rendered content */
    val bounds: AbsoluteRect,
    /** First system index */
    val firstSystem: Int,
    /** Last system index */
    val lastSystem: Int,
    /** First measure number */
    val firstMeasure: Int,
    /** Last measure number */
    val lastMeasure: Int,
    /** Bidirectional index from EventSection to element IDs, built during rendering */
    val sectionIndex: SectionIndex = SectionIndex.EMPTY,
    /** Immutable render-time index; interactive lookups must not scan [elements]. */
    internal val elementIndex: Map<RenderElementId, RenderElement> = emptyMap(),
    /** Map of TimeCode to absolute positions for rendering playhead */
    val timeCodePositions: Map<TimeCode, TimeCodePosition> = emptyMap(),
    /** Collision-safe continuous time projection from the same complete render generation. */
    val resolvedTimeAxis: ResolvedTimeAxis? = null,
    /**
     * Spatial index for hit testing, built from the *same* render pass as [elements].
     * Bundling it into the result (rather than a mutable engine field) guarantees that
     * whatever is queried matches exactly what is drawn — there is no window where the
     * displayed elements and the queryable index disagree. See [hitTest].
     */
    val spatialIndex: HierarchicalSpatialIndex = HierarchicalSpatialIndex(),
    /**
     * Snapshot of the coordinate transformer used to build [spatialIndex]. Captured here
     * so [hitTest] converts absolute → relative with the exact transform the index assumes.
     */
    val transformerSnapshot: CoordinateTransformer = CoordinateTransformer(),
    /**
     * Whether this result was produced in paginated mode. When true, [pages] holds the
     * per-page element groups in page-local coordinates; the UI lays the pages out.
     */
    val paginated: Boolean = false,
    /**
     * Per-page element groups, populated only in paginated mode. Each [RenderPage] carries
     * its elements in *page-local* coordinates (Y measured from that page's top edge) so the
     * UI can place each page anywhere on the canvas (stacked vertically or side by side).
     *
     * Hit testing still goes through [hitTest] against the global [spatialIndex]: the UI maps
     * a page-local click back to global coordinates using [RenderPage.contentOffsetY].
     */
    val pages: List<RenderPage> = emptyList(),
    /** Exact horizontal measure spans derived from barline-bearing time slots. */
    val measureBounds: List<RenderedMeasureBounds> = emptyList(),
    /** Cached invariant for paginated splice; avoids rescanning every rich element before each edit. */
    internal val paginatedSpliceSafe: Boolean = false,
) {
    /** System index range */
    val systemRange: IntRange get() = firstSystem..lastSystem
    /** Measure range */
    val measureRange: IntRange get() = firstMeasure..lastMeasure
    /**
     * Get all elements of a specific type.
     */
    fun elementsOfType(type: RenderElementType): List<RenderElement> =
        elements.filter { it.type == type }

    /**
     * Get element by ID.
     */
    fun elementById(id: RenderElementId): RenderElement? =
        elementIndex[id] ?: if (elementIndex.isEmpty()) elements.find { it.id == id } else null

    /**
     * Get elements associated with an event ID.
     */
    fun elementsForEvent(eventId: EventId): List<RenderElement> =
        elements.filter { it.eventId == eventId }

    /**
     * Get elements in a measure.
     */
    fun elementsInMeasure(measureNumber: Int): List<RenderElement> =
        elements.filter { it.measureNumber == measureNumber }

    /**
     * Get elements in a system.
     */
    fun elementsInSystem(systemIndex: Int): List<RenderElement> =
        elements.filter { it.systemIndex == systemIndex }

    /**
     * Get all render commands (flattened).
     */
    fun allCommands(): List<RenderCommand> =
        elements.flatMap { it.commands }

    /**
     * Find elements containing a point.
     */
    fun elementsAtPoint(point: AbsolutePoint): List<RenderElement> =
        elements.filter { it.containsPoint(point) }

    /**
     * Hit test at an absolute (pixel) point against this result's own [spatialIndex].
     *
     * Stateless and side-effect free: the result owns an immutable index instance, so this
     * is safe to call directly from the UI thread without locking. Because the index ships
     * with the drawn [elements], a pick can never resolve against a different generation than
     * what the user sees.
     */
    fun hitTest(point: AbsolutePoint): HierarchicalHitTestResult {
        val relPoint = transformerSnapshot.toRelative(point)
        val scoreElements = spatialIndex.query(relPoint).filterIsInstance<ScoreHittableElement>()
        return HierarchicalHitTestResult(
            elements = scoreElements,
            topmost = scoreElements.lastOrNull(),
            point = point
        )
    }

    /**
     * Resolve the score cell under [point], including empty staff background.
     *
     * The pair is `measureNumber to staffIndex`. Keeping this tiny hit result on Kotlin's stable
     * runtime type also avoids introducing a renderer-only DTO at this UI hot path.
     */
    fun measureStaffAt(point: AbsolutePoint): Pair<Int, Int>? {
        val relative = transformerSnapshot.toRelative(point)
        // Expanded element bands may make visual systems overlap. Resolve the system and
        // visible five-line staff together instead of accepting the first coarse Y match.
        for (system in spatialIndex.allSystems()) {
            if (relative.y < system.topY || relative.y > system.bottomY) continue
            val staff = system.staffRegions.firstOrNull { it.containsStaffLinesY(relative.y) } ?: continue
            val measure = measureBounds.firstOrNull {
                it.systemIndex == system.systemIndex && relative.x >= it.leftX && relative.x <= it.rightX
            } ?: continue
            return measure.measureNumber to staff.staffIndex
        }
        return null
    }

    /**
     * Resolve a rendered measure boundary near [point]. Boundaries come from [measureBounds], whose
     * X coordinates are computed from barline-bearing time slots, so this also covers paginated line
     * endings where the visible closing rule is emitted as a system structural element.
     */
    fun barlineMeasureAt(point: AbsolutePoint, tolerance: Pixels = Pixels(8f)): Int? =
        barlineHitAt(point, tolerance)?.measureNumber

    /** Resolve both the logical boundary and whether this click used its previous-line or next-line copy. */
    fun barlineHitAt(point: AbsolutePoint, tolerance: Pixels = Pixels(8f)): RenderedBarlineHit? {
        val relative = transformerSnapshot.toRelative(point)
        val system = spatialIndex.allSystems()
            .filter { relative.y >= it.topY && relative.y <= it.bottomY }
            .minByOrNull { candidate ->
                candidate.staffRegions.minOf { kotlin.math.abs(it.centerY.value - relative.y.value) }
            } ?: return null
        val measures = measureBounds.filter { it.systemIndex == system.systemIndex }
        if (measures.isEmpty()) return null
        val candidates = buildList {
            add(Triple(
                measures.first().leftX,
                (measures.first().measureNumber - 1).coerceAtLeast(0),
                if (system.systemIndex > 0) com.mecon.api.interaction.BarlineVisualPlacement.SYSTEM_START
                else com.mecon.api.interaction.BarlineVisualPlacement.INLINE,
            ))
            measures.forEach { measure ->
                add(Triple(
                    measure.rightX,
                    measure.measureNumber,
                    if (measure.measureNumber == measures.last().measureNumber && system.systemIndex < lastSystem) {
                        com.mecon.api.interaction.BarlineVisualPlacement.SYSTEM_END
                    } else com.mecon.api.interaction.BarlineVisualPlacement.INLINE,
                ))
            }
        }
        return candidates.asSequence()
            .map { (x, measureNumber, placement) ->
                val absoluteX = transformerSnapshot.toAbsolute(RelativePoint(x, relative.y)).x
                RenderedBarlineHit(measureNumber, system.systemIndex, placement) to
                    kotlin.math.abs(absoluteX.value - point.x.value)
            }
            .filter { (_, distance) -> distance <= tolerance.value }
            .minByOrNull { (_, distance) -> distance }
            ?.first
    }

    /**
     * Region (marquee / box) hit test: every [ScoreHittableElement] whose bounding box overlaps the
     * absolute (pixel) rectangle [rect], optionally narrowed to a set of [RenderElementType]s.
     *
     * Like [hitTest] this is stateless and side-effect free, querying this result's own immutable
     * [spatialIndex] so a marquee can never resolve against a different generation than what is
     * drawn. The rectangle is converted to staff-space via [transformerSnapshot] (the same
     * transform [hitTest] uses) and normalised, so callers may pass either corner ordering.
     *
     * In paginated mode the caller works in global score coordinates (mapping each page slot back
     * with `designToGlobal`); pass one rectangle per page and union the results.
     *
     * @param rect Selection rectangle in absolute (pixel) coordinates
     * @param types When non-null, only elements of these types are returned (e.g. notes / rests);
     *   null returns every overlapping element. This is the configurable marquee scope.
     */
    fun hitTestRegion(
        rect: AbsoluteRect,
        types: Set<RenderElementType>? = null,
    ): List<ScoreHittableElement> {
        val a = transformerSnapshot.toRelative(rect.origin)
        val b = transformerSnapshot.toRelative(rect.bottomRight)
        val relRect = RelativeRect(
            origin = RelativePoint(
                StaffSpace(minOf(a.x.value, b.x.value)),
                StaffSpace(minOf(a.y.value, b.y.value)),
            ),
            width = StaffSpace(kotlin.math.abs(b.x.value - a.x.value)),
            height = StaffSpace(kotlin.math.abs(b.y.value - a.y.value)),
        )
        val hits = spatialIndex.queryRegion(relRect).filterIsInstance<ScoreHittableElement>()
        return if (types == null) hits else hits.filter { it.type in types }
    }

    companion object {
        val EMPTY = RenderResult(
            elements = emptyList(),
            bounds = AbsoluteRect(AbsolutePoint.ZERO, Pixels.ZERO, Pixels.ZERO),
            firstSystem = 0,
            lastSystem = 0,
            firstMeasure = 0,
            lastMeasure = 0,
            timeCodePositions = emptyMap()
        )
    }
}

data class RenderedBarlineHit(
    val measureNumber: Int,
    val systemIndex: Int,
    val placement: com.mecon.api.interaction.BarlineVisualPlacement,
)

/** One measure's exact visual span on a system, anchored by rendered barline slots. */
data class RenderedMeasureBounds(
    val systemIndex: Int,
    val measureNumber: Int,
    val leftX: StaffSpace,
    val rightX: StaffSpace,
)

/**
 * One page of a paginated render, with its elements in page-local coordinates.
 *
 * [width] / [height] are the paper size in pixels (for drawing the sheet rectangle).
 * [elements] have been translated so Y is measured from this page's top edge (X is
 * already page-local). [contentOffsetY] is the page's Y origin in the global render
 * space — adding it back to a page-local point yields the global coordinate used by
 * [RenderResult.hitTest].
 */
data class RenderPage(
    val pageIndex: Int,
    val width: Pixels,
    val height: Pixels,
    val contentOffsetY: Pixels,
    val elements: List<RenderElement>
)

/**
 * Absolute position of a TimeCode on the score, used for drawing the playhead.
 */
data class TimeCodePosition(
    val timeCode: TimeCode,
    val x: Float,
    val topY: Float,
    val bottomY: Float,
    /**
     * Absolute X of the slot's **leftmost** element edge (slot.x + the most negative element
     * relativeX). This is the barline when the slot opens a measure (barlines are the highest-priority,
     * left-most element in a slot) and the note group's left edge otherwise — i.e. exactly where a
     * clef inserted at this onset begins to take effect. Defaults to [x] for callers that don't compute
     * it. See [com.mecon.renderer.render.edit.GhostClefComputer].
     */
    val leftX: Float = x,
)
