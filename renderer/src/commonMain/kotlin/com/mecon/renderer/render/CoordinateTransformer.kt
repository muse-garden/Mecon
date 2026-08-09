package com.mecon.renderer.render

import com.mecon.renderer.geometry.*

/**
 * Handles coordinate transformations between different coordinate systems.
 *
 * Coordinate systems:
 * - Relative (StaffSpace): Score-independent, uses staff spaces
 * - Absolute (Pixels): Screen coordinates, depends on scale and scroll
 * - Viewport: Visible portion of the score
 */
class CoordinateTransformer(
    /** Pixels per staff space */
    private var scale: ScaleFactor = ScaleFactor.DEFAULT,
    /** Origin of the score in screen coordinates */
    private var scoreOrigin: AbsolutePoint = AbsolutePoint.ZERO,
    /** Current scroll offset */
    private var scrollOffset: AbsolutePoint = AbsolutePoint.ZERO,
    /** Viewport bounds (visible area) */
    private var viewportBounds: AbsoluteRect = AbsoluteRect(
        AbsolutePoint.ZERO,
        Pixels(800f),
        Pixels(600f)
    )
) {
    // ===================
    // Relative -> Absolute
    // ===================

    /**
     * Convert a point from relative (staff space) to absolute (pixel) coordinates.
     */
    fun toAbsolute(point: RelativePoint): AbsolutePoint {
        return AbsolutePoint(
            x = scoreOrigin.x + scale.toPixels(point.x) - scrollOffset.x,
            y = scoreOrigin.y + scale.toPixels(point.y) - scrollOffset.y
        )
    }

    /**
     * Convert a rect from relative to absolute coordinates.
     */
    fun toAbsolute(rect: RelativeRect): AbsoluteRect {
        val origin = toAbsolute(rect.origin)
        return AbsoluteRect(
            origin = origin,
            width = scale.toPixels(rect.width),
            height = scale.toPixels(rect.height)
        )
    }

    /**
     * Convert a line from relative to absolute coordinates.
     */
    fun toAbsolute(line: RelativeLine): AbsoluteLine {
        return AbsoluteLine(
            start = toAbsolute(line.start),
            end = toAbsolute(line.end),
            thickness = scale.toPixels(line.thickness)
        )
    }

    /**
     * Convert a path from relative to absolute coordinates.
     */
    fun toAbsolute(path: RelativePath): AbsolutePath {
        return path.toAbsolute(scale, scoreOrigin - scrollOffset)
    }

    /**
     * Convert a bezier curve from relative to absolute coordinates.
     */
    fun toAbsolute(bezier: RelativeCubicBezier): AbsoluteCubicBezier {
        return bezier.toAbsolute(scale, scoreOrigin - scrollOffset)
    }

    /**
     * Convert a staff space value to pixels.
     */
    fun toPixels(staffSpace: StaffSpace): Pixels = scale.toPixels(staffSpace)

    // ===================
    // Absolute -> Relative
    // ===================

    /**
     * Convert a point from absolute (pixel) to relative (staff space) coordinates.
     */
    fun toRelative(point: AbsolutePoint): RelativePoint {
        val adjusted = AbsolutePoint(
            x = point.x + scrollOffset.x - scoreOrigin.x,
            y = point.y + scrollOffset.y - scoreOrigin.y
        )
        return RelativePoint(
            x = scale.toStaffSpace(adjusted.x),
            y = scale.toStaffSpace(adjusted.y)
        )
    }

    /**
     * Convert a pixel value to staff spaces.
     */
    fun toStaffSpace(pixels: Pixels): StaffSpace = scale.toStaffSpace(pixels)

    // ===================
    // Viewport operations
    // ===================

    /**
     * Get the visible area in relative coordinates.
     */
    fun visibleArea(): RelativeRect {
        val topLeft = toRelative(viewportBounds.origin)
        return RelativeRect(
            origin = topLeft,
            width = scale.toStaffSpace(viewportBounds.width),
            height = scale.toStaffSpace(viewportBounds.height)
        )
    }

    /**
     * Check if a relative rect is visible in the viewport.
     */
    fun isVisible(rect: RelativeRect): Boolean {
        val absoluteRect = toAbsolute(rect)
        return rectsIntersect(absoluteRect, viewportBounds)
    }

    /**
     * Check if a relative point is visible in the viewport.
     */
    fun isVisible(point: RelativePoint): Boolean {
        val absolutePoint = toAbsolute(point)
        return absolutePoint in viewportBounds
    }

    // ===================
    // Configuration
    // ===================

    /**
     * Set the scale factor.
     */
    fun setScale(newScale: ScaleFactor) {
        scale = newScale
    }

    /**
     * Get the current scale factor.
     */
    fun getScale(): ScaleFactor = scale

    /**
     * Set the scroll offset.
     */
    fun setScrollOffset(offset: AbsolutePoint) {
        scrollOffset = offset
    }

    /**
     * Get the current scroll offset.
     */
    fun getScrollOffset(): AbsolutePoint = scrollOffset

    /**
     * Set the viewport bounds.
     */
    fun setViewportBounds(bounds: AbsoluteRect) {
        viewportBounds = bounds
    }

    /**
     * Get the viewport bounds.
     */
    fun getViewportBounds(): AbsoluteRect = viewportBounds

    /**
     * Set the score origin.
     */
    fun setScoreOrigin(origin: AbsolutePoint) {
        scoreOrigin = origin
    }

    /**
     * Get the score origin.
     */
    fun getScoreOrigin(): AbsolutePoint = scoreOrigin

    /**
     * Scroll by a delta amount.
     */
    fun scrollBy(delta: AbsolutePoint) {
        scrollOffset = AbsolutePoint(
            scrollOffset.x + delta.x,
            scrollOffset.y + delta.y
        )
    }

    /**
     * Scroll to make a relative point visible, centering it if possible.
     */
    fun scrollToCenter(point: RelativePoint) {
        val targetAbsolute = AbsolutePoint(
            scoreOrigin.x + scale.toPixels(point.x),
            scoreOrigin.y + scale.toPixels(point.y)
        )
        scrollOffset = AbsolutePoint(
            targetAbsolute.x - viewportBounds.width / 2f,
            targetAbsolute.y - viewportBounds.height / 2f
        )
    }

    /**
     * Zoom to a specific scale, keeping a point fixed on screen.
     */
    fun zoomTo(newScale: ScaleFactor, fixedPoint: AbsolutePoint) {
        // Get the relative position at the fixed point
        val relativePoint = toRelative(fixedPoint)

        // Update scale
        scale = newScale

        // Adjust scroll to keep the relative point at the same screen position
        val newAbsolute = AbsolutePoint(
            scoreOrigin.x + newScale.toPixels(relativePoint.x),
            scoreOrigin.y + newScale.toPixels(relativePoint.y)
        )
        scrollOffset = AbsolutePoint(
            scrollOffset.x + (newAbsolute.x - fixedPoint.x),
            scrollOffset.y + (newAbsolute.y - fixedPoint.y)
        )
    }

    /**
     * Zoom by a factor, keeping a point fixed.
     */
    fun zoomBy(factor: Float, fixedPoint: AbsolutePoint) {
        val newScale = ScaleFactor(scale.pixelsPerStaffSpace * factor)
        zoomTo(newScale, fixedPoint)
    }

    // ===================
    // Utility
    // ===================

    /**
     * Create a copy of this transformer.
     */
    fun copy(): CoordinateTransformer = CoordinateTransformer(
        scale = scale,
        scoreOrigin = scoreOrigin,
        scrollOffset = scrollOffset,
        viewportBounds = viewportBounds
    )

    private fun rectsIntersect(a: AbsoluteRect, b: AbsoluteRect): Boolean {
        return a.origin.x.value < b.origin.x.value + b.width.value &&
                a.origin.x.value + a.width.value > b.origin.x.value &&
                a.origin.y.value < b.origin.y.value + b.height.value &&
                a.origin.y.value + a.height.value > b.origin.y.value
    }

    companion object {
        /**
         * Create a transformer with default settings.
         */
        fun default(): CoordinateTransformer = CoordinateTransformer()

        /**
         * Create a transformer that fits content to a viewport.
         */
        fun fitToViewport(
            contentBounds: RelativeRect,
            viewportSize: AbsoluteRect,
            padding: Pixels = Pixels(20f)
        ): CoordinateTransformer {
            val availableWidth = viewportSize.width - padding * 2
            val availableHeight = viewportSize.height - padding * 2

            val scaleX = availableWidth.value / contentBounds.width.value
            val scaleY = availableHeight.value / contentBounds.height.value
            val scale = ScaleFactor(minOf(scaleX, scaleY))

            val scaledWidth = scale.toPixels(contentBounds.width)
            val scaledHeight = scale.toPixels(contentBounds.height)

            val originX = padding + (availableWidth - scaledWidth) / 2f
            val originY = padding + (availableHeight - scaledHeight) / 2f

            return CoordinateTransformer(
                scale = scale,
                scoreOrigin = AbsolutePoint(
                    originX - scale.toPixels(contentBounds.origin.x),
                    originY - scale.toPixels(contentBounds.origin.y)
                ),
                scrollOffset = AbsolutePoint.ZERO,
                viewportBounds = viewportSize
            )
        }
    }
}
