package com.mecon.desktop.ui.views

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.mecon.api.interaction.EventSection
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.render.RenderPage
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderResult
import kotlin.math.abs

/** Imperative bridge used only by the desktop shortcut dispatcher. */
class RenderedScoreViewportController {
    private var navigator: ((Int) -> Boolean)? = null

    fun moveBySystem(delta: Int): Boolean =
        if (delta == 0) false else navigator?.invoke(delta) == true

    internal fun attach(value: (Int) -> Boolean) {
        navigator = value
    }

    internal fun detach(value: (Int) -> Boolean) {
        if (navigator === value) navigator = null
    }
}

/** One rendered score row in the page-grid design coordinate space. */
internal data class DisplayedScoreSystem(
    val systemIndex: Int,
    val firstMeasure: Int,
    val bounds: Rect,
)

internal fun displayedScoreSystems(
    result: RenderResult,
    paginated: Boolean,
    pages: List<RenderPage>,
    pageSlots: List<Offset>,
): List<DisplayedScoreSystem> = result.spatialIndex.allSystems().mapNotNull { system ->
    val left = system.startX.value
    val right = system.startX.value + system.totalWidth().value
    val top = system.staffRegions.minOfOrNull { it.centerY.value - 2f } ?: return@mapNotNull null
    val bottom = system.staffRegions.maxOfOrNull { it.centerY.value + 2f } ?: return@mapNotNull null
    val transformer = result.transformerSnapshot
    val absoluteLeftTop = transformer.toAbsolute(
        RelativePoint(StaffSpace(left), StaffSpace(top)),
    )
    val absoluteRightBottom = transformer.toAbsolute(
        RelativePoint(StaffSpace(right), StaffSpace(bottom)),
    )
    val designLeftTop = if (paginated) {
        globalToDesign(
            absoluteLeftTop.x.value,
            absoluteLeftTop.y.value,
            pages,
            pageSlots,
        )
    } else Offset(absoluteLeftTop.x.value, absoluteLeftTop.y.value)
    val designRightBottom = if (paginated) {
        globalToDesign(
            absoluteRightBottom.x.value,
            absoluteRightBottom.y.value,
            pages,
            pageSlots,
        )
    } else Offset(absoluteRightBottom.x.value, absoluteRightBottom.y.value)
    if (designLeftTop == null || designRightBottom == null) return@mapNotNull null
    DisplayedScoreSystem(
        systemIndex = system.systemIndex,
        firstMeasure = system.measureNumbers.firstOrNull() ?: return@mapNotNull null,
        bounds = Rect(
            left = minOf(designLeftTop.x, designRightBottom.x),
            top = minOf(designLeftTop.y, designRightBottom.y),
            right = maxOf(designLeftTop.x, designRightBottom.x),
            bottom = maxOf(designLeftTop.y, designRightBottom.y),
        ),
    )
}.sortedBy { it.systemIndex }

/**
 * In insertion mode only page-exterior space and the left/right margins beside a staff row pan.
 * Interior page whitespace remains owned by the active pen.
 */
internal fun canStartInsertionPan(
    raw: Offset,
    offset: Offset,
    scale: Float,
    density: Float,
    paginated: Boolean,
    pages: List<RenderPage>,
    pageSlots: List<Offset>,
    systems: List<DisplayedScoreSystem>,
): Boolean {
    val factor = scale * density
    if (factor <= 0f) return false
    val design = Offset((raw.x - offset.x) / factor, (raw.y - offset.y) / factor)
    if (paginated) {
        val insidePage = pages.indices.any { index ->
            val page = pages[index]
            val slot = pageSlots.getOrNull(index) ?: return@any false
            design.x in slot.x..(slot.x + page.width.value) &&
                design.y in slot.y..(slot.y + page.height.value)
        }
        if (!insidePage) return true
    }
    return systems.any { row ->
        design.y in row.bounds.top..row.bounds.bottom &&
            (design.x < row.bounds.left || design.x > row.bounds.right)
    }
}

/** Move the adjacent rendered row onto the current row's on-screen anchor. */
internal fun viewportOffsetAfterSystemMove(
    systems: List<DisplayedScoreSystem>,
    currentOffset: Offset,
    scale: Float,
    density: Float,
    viewportSize: Size,
    delta: Int,
): Offset? {
    if (systems.isEmpty() || delta == 0 || viewportSize == Size.Zero) return null
    val factor = scale * density
    if (factor <= 0f) return null
    val viewport = Rect(Offset.Zero, viewportSize)
    val current = systems.minWithOrNull(
        compareByDescending<DisplayedScoreSystem> { row ->
            intersectionArea(row.bounds.toRaw(factor, currentOffset), viewport)
        }.thenBy { row ->
            val raw = row.bounds.toRaw(factor, currentOffset)
            abs(raw.center.x - viewport.center.x) + abs(raw.center.y - viewport.center.y)
        },
    ) ?: return null
    val currentOrdinal = systems.indexOf(current)
    val target = systems.getOrNull(currentOrdinal + delta) ?: return null
    return currentOffset + Offset(
        x = (current.bounds.left - target.bounds.left) * factor,
        y = (current.bounds.top - target.bounds.top) * factor,
    )
}

/** Keep the next row's first bar visible without forcing the inserted note under the pointer. */
internal fun revealSystemStartOffset(
    system: DisplayedScoreSystem,
    currentOffset: Offset,
    scale: Float,
    density: Float,
    viewportSize: Size,
    marginPx: Float = 24f,
): Offset {
    if (viewportSize == Size.Zero) return currentOffset
    val factor = scale * density
    if (factor <= 0f) return currentOffset
    val raw = system.bounds.toRaw(factor, currentOffset)
    val targetRight = (viewportSize.width - marginPx).coerceAtLeast(marginPx)
    val targetBottom = (viewportSize.height - marginPx).coerceAtLeast(marginPx)
    val dx = when {
        raw.left < marginPx -> marginPx - raw.left
        raw.left > targetRight -> marginPx - raw.left
        else -> 0f
    }
    val dy = when {
        raw.height > targetBottom - marginPx -> marginPx - raw.top
        raw.top < marginPx -> marginPx - raw.top
        raw.bottom > targetBottom -> targetBottom - raw.bottom
        else -> 0f
    }
    return currentOffset + Offset(dx, dy)
}

/**
 * Nudge a committed note back towards its insertion cursor without pixel-perfect snapping.
 * Each axis keeps a dead zone, and larger discrepancies correct only the excess, so ordinary
 * engraving changes do not produce a distracting micro-pan after every note.
 */
internal fun softAlignDesignPointToCursorOffset(
    designPoint: Offset,
    cursorRaw: Offset,
    currentOffset: Offset,
    scale: Float,
    density: Float,
    horizontalDeadZonePx: Float = 14f,
    verticalDeadZonePx: Float = 24f,
): Offset {
    val renderedRaw = designPoint * (scale * density) + currentOffset
    val delta = cursorRaw - renderedRaw
    fun excess(value: Float, deadZone: Float): Float = when {
        value > deadZone -> value - deadZone
        value < -deadZone -> value + deadZone
        else -> 0f
    }
    return currentOffset + Offset(
        excess(delta.x, horizontalDeadZonePx),
        excess(delta.y, verticalDeadZonePx),
    )
}

internal fun insertedSectionDesignAnchor(
    result: RenderResult,
    section: EventSection,
    cursorRaw: Offset,
    currentOffset: Offset,
    scale: Float,
    density: Float,
    paginated: Boolean,
    pages: List<RenderPage>,
    pageSlots: List<Offset>,
): Pair<Offset, Int>? {
    val factor = scale * density
    val ids = result.sectionIndex.elementsFor(section).elementIds
    val candidates = ids.asSequence()
        .mapNotNull(result::elementById)
        .filter {
            it.type == RenderElementType.NOTEHEAD ||
                it.type == RenderElementType.REST ||
                it.type == RenderElementType.GRACE_NOTE ||
                it.type == RenderElementType.NOTE ||
                it.type == RenderElementType.CHORD
        }
        .mapNotNull { element ->
            val hit = element.hitBox
            val anchorX = if (element.type == RenderElementType.NOTEHEAD) {
                hit.origin.x.value + hit.width.value
            } else hit.center.x.value
            val anchorY = hit.center.y.value
            val design = if (paginated) {
                globalToDesign(anchorX, anchorY, pages, pageSlots)
            } else Offset(anchorX, anchorY)
            design?.let {
                InsertionAnchorCandidate(
                    design = it,
                    systemIndex = element.systemIndex,
                    typePriority = when (element.type) {
                        RenderElementType.NOTEHEAD -> 0
                        RenderElementType.REST, RenderElementType.GRACE_NOTE -> 1
                        else -> 2
                    },
                    cursorYDistance = abs(it.y * factor + currentOffset.y - cursorRaw.y),
                )
            }
        }
        .toList()
    val preferred = candidates.minWithOrNull(
        compareBy<InsertionAnchorCandidate> { it.typePriority }
            .thenBy { it.cursorYDistance },
    ) ?: return null
    return preferred.design to (preferred.systemIndex ?: return null)
}

private data class InsertionAnchorCandidate(
    val design: Offset,
    val systemIndex: Int?,
    val typePriority: Int,
    val cursorYDistance: Float,
)

private fun Rect.toRaw(factor: Float, offset: Offset): Rect = Rect(
    left = left * factor + offset.x,
    top = top * factor + offset.y,
    right = right * factor + offset.x,
    bottom = bottom * factor + offset.y,
)

private fun intersectionArea(a: Rect, b: Rect): Float {
    val width = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f)
    val height = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
    return width * height
}
