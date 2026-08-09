package com.mecon.renderer.render

import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.render.spatial.HierarchicalSpatialIndex
import com.mecon.renderer.render.spatial.ReadWriteLock
import com.mecon.api.interaction.EventSection
import com.mecon.renderer.render.spatial.ScoreHittableElement
import com.mecon.renderer.render.spatial.withLock

/**
 * Thread-safe hit testing service using a [ReadWriteLock].
 *
 * Queries take a read lock (concurrent), index updates take a write lock (exclusive).
 * The [hitTest] method is non-suspend and can be called from any thread.
 */
class HitTestService {
    private var index: HierarchicalSpatialIndex? = null
    private var transformer: CoordinateTransformer? = null
    private val lock = ReadWriteLock()

    /**
     * Perform a hit test at the given absolute (pixel) point.
     *
     * Takes a read lock — multiple concurrent queries are allowed.
     *
     * @param point Point in absolute (pixel) coordinates
     * @return Hit test result with matched elements
     */
    fun hitTest(point: AbsolutePoint): HierarchicalHitTestResult {
        lock.readLock().withLock {
            val xformer = transformer ?: return HierarchicalHitTestResult.empty(point)
            val idx = index ?: return HierarchicalHitTestResult.empty(point)

            val relPoint = xformer.toRelative(point)
            val hittableElements = idx.query(relPoint)

            val scoreElements = hittableElements.filterIsInstance<ScoreHittableElement>()
            return HierarchicalHitTestResult(
                elements = scoreElements,
                topmost = scoreElements.lastOrNull(),
                point = point
            )
        }
    }

    /**
     * Perform a hit test at the given relative (staff space) point.
     *
     * @param point Point in relative (staff space) coordinates
     * @return Hit test result with matched elements
     */
    fun hitTestRelative(point: RelativePoint): HierarchicalHitTestResult {
        lock.readLock().withLock {
            val idx = index ?: return HierarchicalHitTestResult.empty(
                AbsolutePoint.ZERO
            )

            val hittableElements = idx.query(point)
            val scoreElements = hittableElements.filterIsInstance<ScoreHittableElement>()
            return HierarchicalHitTestResult(
                elements = scoreElements,
                topmost = scoreElements.lastOrNull(),
                point = transformer?.toAbsolute(point) ?: AbsolutePoint.ZERO
            )
        }
    }

    /**
     * Update the spatial index. Takes a write lock — exclusive access.
     *
     * @param newIndex The new hierarchical spatial index
     * @param newTransformer The new coordinate transformer
     */
    fun updateIndex(newIndex: HierarchicalSpatialIndex, newTransformer: CoordinateTransformer) {
        lock.writeLock().withLock {
            index = newIndex
            transformer = newTransformer
        }
    }

    /**
     * Lock a system (prevents its elements from being returned in queries).
     */
    fun lockSystem(systemIndex: Int) {
        lock.writeLock().withLock {
            index?.lockSystem(systemIndex)
        }
    }

    /**
     * Unlock a system.
     */
    fun unlockSystem(systemIndex: Int) {
        lock.writeLock().withLock {
            index?.unlockSystem(systemIndex)
        }
    }

    /**
     * Check if the service has an index.
     */
    fun hasIndex(): Boolean {
        lock.readLock().withLock {
            return index != null
        }
    }
}

/**
 * Hit test result from the hierarchical spatial index.
 *
 * Contains [ScoreHittableElement]s with full music-domain metadata.
 */
data class HierarchicalHitTestResult(
    /** All elements at the hit point */
    val elements: List<ScoreHittableElement>,
    /** The topmost (last) element */
    val topmost: ScoreHittableElement?,
    /** The query point in absolute coordinates */
    val point: AbsolutePoint
) {
    val isEmpty: Boolean get() = elements.isEmpty()
    val isNotEmpty: Boolean get() = elements.isNotEmpty()

    /**
     * Get elements of a specific type.
     */
    fun ofType(type: RenderElementType): List<ScoreHittableElement> =
        elements.filter { it.type == type }

    /**
     * Get the first element of a specific type.
     */
    fun firstOfType(type: RenderElementType): ScoreHittableElement? =
        elements.firstOrNull { it.type == type }

    /**
     * Get all sections from all hit elements, deduplicated.
     *
     * This replaces the indirect lookup via SectionIndex.sectionsForHit(),
     * since sections are now directly stored in ScoreHittableElement.
     */
    fun allSections(): List<EventSection> =
        elements.flatMap { it.sections }.distinct()

    companion object {
        fun empty(point: AbsolutePoint) = HierarchicalHitTestResult(emptyList(), null, point)
    }
}
