package com.mecon.renderer.geometry

import kotlin.math.max
import kotlin.math.min

/** Translation-safe precise hit geometry in score-relative coordinates. */
interface RelativeHitShape {
    val boundingBox: RelativeRect
    fun contains(point: RelativePoint): Boolean
    fun translatedBy(dx: StaffSpace, dy: StaffSpace): RelativeHitShape
}

/**
 * Builds a reusable hit shape for a filled path plus a small interaction halo.
 *
 * The path is flattened once when the render frame is assembled. Pointer queries then test the
 * painted polygon and its outline instead of accepting every point in the path's axis-aligned
 * bounds. The immutable shape can also follow cached elements translated by incremental rendering.
 */
fun RelativePath.createFilledPathHitShape(
    tolerance: StaffSpace = StaffSpace.ZERO,
): RelativeHitShape = FilledPathHitShape(flattenedPoints(), tolerance)

private class FilledPathHitShape(
    val polygon: List<RelativePoint>,
    val tolerance: StaffSpace,
) : RelativeHitShape {
    override val boundingBox: RelativeRect = polygonBounds(polygon).expand(tolerance)

    override fun contains(point: RelativePoint): Boolean {
        val toleranceSquared = tolerance.value * tolerance.value
        return pointInPolygon(point, polygon) ||
            (toleranceSquared > 0f && outlineDistanceSquared(point, polygon) <= toleranceSquared)
    }

    override fun translatedBy(dx: StaffSpace, dy: StaffSpace): RelativeHitShape = FilledPathHitShape(
        polygon = polygon.map { point -> RelativePoint(point.x + dx, point.y + dy) },
        tolerance = tolerance,
    )
}

private fun polygonBounds(polygon: List<RelativePoint>): RelativeRect {
    if (polygon.isEmpty()) return RelativeRect(RelativePoint.ZERO, StaffSpace.ZERO, StaffSpace.ZERO)
    val minX = polygon.minOf { it.x.value }
    val minY = polygon.minOf { it.y.value }
    val maxX = polygon.maxOf { it.x.value }
    val maxY = polygon.maxOf { it.y.value }
    return RelativeRect(
        origin = RelativePoint.of(minX, minY),
        width = StaffSpace(maxX - minX),
        height = StaffSpace(maxY - minY),
    )
}

private fun RelativePath.flattenedPoints(): List<RelativePoint> {
    val points = mutableListOf<RelativePoint>()
    var current: RelativePoint? = null
    var subpathStart: RelativePoint? = null

    for (segment in segments) {
        when (segment) {
            is RelativePathSegment.MoveTo -> {
                current = segment.point
                subpathStart = segment.point
                points += segment.point
            }

            is RelativePathSegment.LineTo -> {
                current = segment.point
                points += segment.point
            }

            is RelativePathSegment.QuadTo -> {
                val start = current ?: continue
                for (step in 1..CURVE_SUBDIVISIONS) {
                    val t = step.toFloat() / CURVE_SUBDIVISIONS
                    points += quadraticPoint(start, segment.control, segment.end, t)
                }
                current = segment.end
            }

            is RelativePathSegment.CubicTo -> {
                val start = current ?: continue
                for (step in 1..CURVE_SUBDIVISIONS) {
                    val t = step.toFloat() / CURVE_SUBDIVISIONS
                    points += cubicPoint(start, segment.control1, segment.control2, segment.end, t)
                }
                current = segment.end
            }

            RelativePathSegment.Close -> {
                val start = subpathStart
                if (start != null && points.lastOrNull() != start) points += start
                current = start
            }
        }
    }
    return points
}

private fun quadraticPoint(
    start: RelativePoint,
    control: RelativePoint,
    end: RelativePoint,
    t: Float,
): RelativePoint {
    val u = 1f - t
    return RelativePoint.of(
        u * u * start.x.value + 2f * u * t * control.x.value + t * t * end.x.value,
        u * u * start.y.value + 2f * u * t * control.y.value + t * t * end.y.value,
    )
}

private fun cubicPoint(
    start: RelativePoint,
    control1: RelativePoint,
    control2: RelativePoint,
    end: RelativePoint,
    t: Float,
): RelativePoint {
    val u = 1f - t
    val uu = u * u
    val tt = t * t
    return RelativePoint.of(
        uu * u * start.x.value + 3f * uu * t * control1.x.value +
            3f * u * tt * control2.x.value + tt * t * end.x.value,
        uu * u * start.y.value + 3f * uu * t * control1.y.value +
            3f * u * tt * control2.y.value + tt * t * end.y.value,
    )
}

private fun pointInPolygon(point: RelativePoint, polygon: List<RelativePoint>): Boolean {
    if (polygon.size < 3) return false
    var inside = false
    var previous = polygon.last()
    for (current in polygon) {
        val crossesY = (current.y.value > point.y.value) != (previous.y.value > point.y.value)
        if (crossesY) {
            val crossingX = (previous.x.value - current.x.value) *
                (point.y.value - current.y.value) /
                (previous.y.value - current.y.value) + current.x.value
            if (point.x.value < crossingX) inside = !inside
        }
        previous = current
    }
    return inside
}

private fun outlineDistanceSquared(point: RelativePoint, polygon: List<RelativePoint>): Float {
    if (polygon.isEmpty()) return Float.POSITIVE_INFINITY
    if (polygon.size == 1) return squaredDistance(point, polygon.single())
    var nearest = Float.POSITIVE_INFINITY
    for (index in 0 until polygon.lastIndex) {
        nearest = min(nearest, segmentDistanceSquared(point, polygon[index], polygon[index + 1]))
    }
    if (polygon.first() != polygon.last()) {
        nearest = min(nearest, segmentDistanceSquared(point, polygon.last(), polygon.first()))
    }
    return nearest
}

private fun segmentDistanceSquared(point: RelativePoint, start: RelativePoint, end: RelativePoint): Float {
    val dx = end.x.value - start.x.value
    val dy = end.y.value - start.y.value
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared <= MIN_SEGMENT_LENGTH_SQUARED) return squaredDistance(point, start)
    val projection = ((point.x.value - start.x.value) * dx + (point.y.value - start.y.value) * dy) /
        lengthSquared
    val t = max(0f, min(1f, projection))
    val projected = RelativePoint.of(start.x.value + dx * t, start.y.value + dy * t)
    return squaredDistance(point, projected)
}

private fun squaredDistance(a: RelativePoint, b: RelativePoint): Float {
    val dx = a.x.value - b.x.value
    val dy = a.y.value - b.y.value
    return dx * dx + dy * dy
}

private const val CURVE_SUBDIVISIONS = 16
private const val MIN_SEGMENT_LENGTH_SQUARED = 0.00000001f
