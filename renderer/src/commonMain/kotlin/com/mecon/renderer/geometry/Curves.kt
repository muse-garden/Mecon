package com.mecon.renderer.geometry

import kotlinx.serialization.Serializable

/**
 * A cubic Bezier curve in relative coordinates.
 *
 * Used for slurs, ties, and other curved elements.
 * Defined by four points: start (p0), control1 (p1), control2 (p2), end (p3)
 */
@Serializable
data class RelativeCubicBezier(
    val p0: RelativePoint,  // Start point
    val p1: RelativePoint,  // Control point 1
    val p2: RelativePoint,  // Control point 2
    val p3: RelativePoint   // End point
) {
    /**
     * Convert to absolute coordinates.
     */
    fun toAbsolute(scale: ScaleFactor, origin: AbsolutePoint = AbsolutePoint.ZERO): AbsoluteCubicBezier =
        AbsoluteCubicBezier(
            p0 = p0.toAbsolute(scale, origin),
            p1 = p1.toAbsolute(scale, origin),
            p2 = p2.toAbsolute(scale, origin),
            p3 = p3.toAbsolute(scale, origin)
        )

    /**
     * Calculate point on curve at parameter t (0 to 1).
     */
    fun pointAt(t: Float): RelativePoint {
        val mt = 1 - t
        val mt2 = mt * mt
        val mt3 = mt2 * mt
        val t2 = t * t
        val t3 = t2 * t

        val x = mt3 * p0.x.value + 3 * mt2 * t * p1.x.value +
                3 * mt * t2 * p2.x.value + t3 * p3.x.value
        val y = mt3 * p0.y.value + 3 * mt2 * t * p1.y.value +
                3 * mt * t2 * p2.y.value + t3 * p3.y.value

        return RelativePoint(StaffSpace(x), StaffSpace(y))
    }

    /**
     * Get approximate bounding box.
     */
    fun boundingBox(): RelativeRect {
        return RelativeRect.boundingBox(listOf(p0, p1, p2, p3))
            ?: RelativeRect(p0, StaffSpace.ZERO, StaffSpace.ZERO)
    }

    companion object {
        /**
         * Create a curve for a slur/tie between two points.
         */
        fun slur(
            start: RelativePoint,
            end: RelativePoint,
            curvature: Float = 0.5f,
            direction: SlurDirection = SlurDirection.ABOVE
        ): RelativeCubicBezier {
            val midX = (start.x.value + end.x.value) / 2
            val dx = end.x.value - start.x.value

            // Control points offset based on direction and curvature
            val yOffset = StaffSpace(dx * curvature * if (direction == SlurDirection.ABOVE) -1 else 1)

            return RelativeCubicBezier(
                p0 = start,
                p1 = RelativePoint(StaffSpace(midX - dx * 0.2f), start.y + yOffset),
                p2 = RelativePoint(StaffSpace(midX + dx * 0.2f), end.y + yOffset),
                p3 = end
            )
        }
    }
}

/**
 * A cubic Bezier curve in absolute coordinates.
 */
@Serializable
data class AbsoluteCubicBezier(
    val p0: AbsolutePoint,
    val p1: AbsolutePoint,
    val p2: AbsolutePoint,
    val p3: AbsolutePoint
)

/**
 * A quadratic Bezier curve in relative coordinates.
 */
@Serializable
data class RelativeQuadraticBezier(
    val p0: RelativePoint,  // Start point
    val p1: RelativePoint,  // Control point
    val p2: RelativePoint   // End point
) {
    fun toAbsolute(scale: ScaleFactor, origin: AbsolutePoint = AbsolutePoint.ZERO): AbsoluteQuadraticBezier =
        AbsoluteQuadraticBezier(
            p0 = p0.toAbsolute(scale, origin),
            p1 = p1.toAbsolute(scale, origin),
            p2 = p2.toAbsolute(scale, origin)
        )

    fun pointAt(t: Float): RelativePoint {
        val mt = 1 - t
        val mt2 = mt * mt
        val t2 = t * t

        val x = mt2 * p0.x.value + 2 * mt * t * p1.x.value + t2 * p2.x.value
        val y = mt2 * p0.y.value + 2 * mt * t * p1.y.value + t2 * p2.y.value

        return RelativePoint(StaffSpace(x), StaffSpace(y))
    }
}

/**
 * A quadratic Bezier curve in absolute coordinates.
 */
@Serializable
data class AbsoluteQuadraticBezier(
    val p0: AbsolutePoint,
    val p1: AbsolutePoint,
    val p2: AbsolutePoint
)

/**
 * Slur/tie direction.
 */
enum class SlurDirection {
    ABOVE,  // Curve opens downward (above notes)
    BELOW   // Curve opens upward (below notes)
}
