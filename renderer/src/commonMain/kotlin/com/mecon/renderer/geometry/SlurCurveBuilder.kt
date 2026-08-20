package com.mecon.renderer.geometry

import kotlin.math.sqrt
import kotlin.math.abs

/**
 * Builds the lens-shaped path used by both ties and slurs.
 *
 * The default path is two cubic Bezier curves sharing endpoints: an outer
 * curve that bows away from the notes and an inner curve that bows toward them.
 * The gap between them — widest at the apex, narrowing to zero at the
 * endpoints — gives the engraver's familiar "thick in the middle, pointed
 * at the ends" silhouette.
 *
 * LilyPond conventions inspire the control-point placement:
 * - Apex height scales with sqrt(span) so short ties are nearly flat and
 *   long ones bow more, but stays inside [minHeight, maxHeight].
 * - Control points sit at 1/3 and 2/3 of the span, offset perpendicular to
 *   the baseline (works for sloped slurs as well as horizontal ties).
 * - The 0.75 factor relating control-point offset to apex offset comes from
 *   evaluating a cubic Bezier with parallel control offsets at t=0.5.
 *
 * Output is a closed [RelativePath] suitable for filled rendering via
 * [com.mecon.renderer.render.DrawPath].
 */
object SlurCurveBuilder {

    /** Default base curvature factor (apex_height ≈ baseCurvature * sqrt(span)). */
    const val DEFAULT_BASE_CURVATURE = 0.5f

    /** Default lower bound on apex height (staff spaces). */
    val DEFAULT_MIN_HEIGHT = StaffSpace(0.6f)

    /** Default upper bound on apex height (staff spaces). */
    val DEFAULT_MAX_HEIGHT = StaffSpace(4f)

    /** Small pointer halo outside the painted tie/slur lens. */
    val DEFAULT_HIT_TOLERANCE = StaffSpace(0.3f)

    /**
     * Build a closed lens path between [start] and [end].
     *
     * @param midpointThickness Apex thickness (perpendicular gap between
     *                          outer and inner curves at t=0.5).
     */
    fun buildLensPath(
        start: RelativePoint,
        end: RelativePoint,
        direction: SlurDirection,
        midpointThickness: StaffSpace,
        baseCurvature: Float = DEFAULT_BASE_CURVATURE,
        minHeight: StaffSpace = DEFAULT_MIN_HEIGHT,
        maxHeight: StaffSpace = DEFAULT_MAX_HEIGHT,
        slopeDamping: Float = 1f,
        heightUsesHorizontalSpan: Boolean = false,
        middleStraightening: Float = 0f,
    ): RelativePath {
        val params = computeLensParams(
            start, end, direction, midpointThickness, baseCurvature, minHeight, maxHeight,
            slopeDamping, heightUsesHorizontalSpan
        )
        val straightening = middleStraightening.coerceIn(0f, 1f)
        if (straightening > 0f) {
            return buildStraightenedLensPath(start, end, params, straightening)
        }

        return RelativePath(listOf(
            RelativePathSegment.MoveTo(start),
            RelativePathSegment.CubicTo(params.outerCp1, params.outerCp2, end),
            RelativePathSegment.CubicTo(params.innerCp1, params.innerCp2, start),
            RelativePathSegment.Close
        ))
    }

    /**
     * Conservative bounding box for a lens with the same inputs as [buildLensPath].
     *
     * Uses the outer apex as the extreme Y value — for typical tie/slur shapes
     * this slightly over-estimates the actual extent, which is what we want for
     * hit testing and bounds calculation.
     */
    fun lensBounds(
        start: RelativePoint,
        end: RelativePoint,
        direction: SlurDirection,
        midpointThickness: StaffSpace,
        baseCurvature: Float = DEFAULT_BASE_CURVATURE,
        minHeight: StaffSpace = DEFAULT_MIN_HEIGHT,
        maxHeight: StaffSpace = DEFAULT_MAX_HEIGHT,
        slopeDamping: Float = 1f,
        heightUsesHorizontalSpan: Boolean = false,
        middleStraightening: Float = 0f,
    ): RelativeRect {
        val params = computeLensParams(
            start, end, direction, midpointThickness, baseCurvature, minHeight, maxHeight,
            slopeDamping, heightUsesHorizontalSpan
        )
        val midBaseX = (start.x.value + end.x.value) * 0.5f
        val midBaseY = (start.y.value + end.y.value) * 0.5f
        val apexOffset = visualOffset(params.outerOffset)
        val apexX = midBaseX + params.nx * apexOffset
        val apexY = midBaseY + params.ny * apexOffset

        val xs = floatArrayOf(start.x.value, end.x.value, apexX)
        val ys = floatArrayOf(start.y.value, end.y.value, apexY)
        val minX = xs.min()
        val maxX = xs.max()
        val minY = ys.min()
        val maxY = ys.max()
        return RelativeRect(
            origin = RelativePoint(StaffSpace(minX), StaffSpace(minY)),
            width = StaffSpace(maxX - minX),
            height = StaffSpace(maxY - minY)
        )
    }

    private const val APEX_FACTOR = 0.75f
    private const val MIN_LENGTH = 0.0001f

    private data class LensParams(
        val nx: Float,
        val ny: Float,
        val outerOffset: Float,
        val innerOffset: Float,
        val outerCp1: RelativePoint,
        val outerCp2: RelativePoint,
        val innerCp1: RelativePoint,
        val innerCp2: RelativePoint
    )

    private fun computeLensParams(
        start: RelativePoint,
        end: RelativePoint,
        direction: SlurDirection,
        midpointThickness: StaffSpace,
        baseCurvature: Float,
        minHeight: StaffSpace,
        maxHeight: StaffSpace,
        slopeDamping: Float,
        heightUsesHorizontalSpan: Boolean,
    ): LensParams {
        val dx = end.x.value - start.x.value
        val dy = end.y.value - start.y.value
        val length = sqrt(dx * dx + dy * dy).coerceAtLeast(MIN_LENGTH)
        val span = abs(dx).coerceAtLeast(MIN_LENGTH)
        val normalDy = dy * slopeDamping.coerceIn(0f, 1f)
        val normalLength = sqrt(dx * dx + normalDy * normalDy).coerceAtLeast(MIN_LENGTH)

        // Perpendicular unit vector pointing in the requested direction.
        // Screen Y is down, so ABOVE means smaller Y at the apex.
        val sign = if (direction == SlurDirection.ABOVE) 1f else -1f
        val nx = sign * normalDy / normalLength
        val ny = -sign * dx / normalLength

        val effectiveMin = minOf(minHeight.value, maxHeight.value)
        val heightLength = if (heightUsesHorizontalSpan) span else length
        val nominalHeight = (baseCurvature * sqrt(heightLength)).coerceIn(effectiveMin, maxHeight.value)
        // Apex gap = (outerOffset - innerOffset) * APEX_FACTOR. Solve for the
        // offset that produces the requested midpoint thickness.
        val halfGap = midpointThickness.value / (2f * APEX_FACTOR)
        val outerOffset = nominalHeight + halfGap
        val innerOffset = (nominalHeight - halfGap).coerceAtLeast(0f)

        fun ctrl(t: Float, perpOffset: Float): RelativePoint {
            val bx = start.x.value + dx * t
            val by = start.y.value + dy * t
            return RelativePoint(
                StaffSpace(bx + nx * perpOffset),
                StaffSpace(by + ny * perpOffset)
            )
        }

        return LensParams(
            nx = nx,
            ny = ny,
            outerOffset = outerOffset,
            innerOffset = innerOffset,
            outerCp1 = ctrl(1f / 3f, outerOffset),
            outerCp2 = ctrl(2f / 3f, outerOffset),
            // Inner curve is traced end → start so segments connect cleanly.
            innerCp1 = ctrl(2f / 3f, innerOffset),
            innerCp2 = ctrl(1f / 3f, innerOffset)
        )
    }

    private fun buildStraightenedLensPath(
        start: RelativePoint,
        end: RelativePoint,
        params: LensParams,
        straightening: Float,
    ): RelativePath {
        val segments = mutableListOf<RelativePathSegment>(RelativePathSegment.MoveTo(start))
        segments.addAll(
            continuousFlattenedSegments(
                start = start,
                end = end,
                params = params,
                visualOffset = visualOffset(params.outerOffset),
                straightening = straightening,
                reverse = false
            )
        )
        segments.addAll(
            continuousFlattenedSegments(
                start = start,
                end = end,
                params = params,
                visualOffset = visualOffset(params.innerOffset),
                straightening = straightening,
                reverse = true
            )
        )
        segments.add(RelativePathSegment.Close)
        return RelativePath(segments)
    }

    private fun visualOffset(rawOffset: Float): Float = rawOffset * APEX_FACTOR

    private fun continuousFlattenedSegments(
        start: RelativePoint,
        end: RelativePoint,
        params: LensParams,
        visualOffset: Float,
        straightening: Float,
        reverse: Boolean,
    ): List<RelativePathSegment.CubicTo> {
        val relief = CENTER_CURVATURE_RELIEF * straightening
        val knots = FLATTENED_SAMPLE_T_VALUES.map { t ->
            val (offsetFactor, derivativeFactor) = flattenedOffsetFactors(t, relief)
            CurveKnot(
                t = t,
                offset = visualOffset * offsetFactor,
                offsetDerivative = visualOffset * derivativeFactor
            )
        }
        val segments = knots.zipWithNext { left, right ->
            hermiteSegment(start, end, params, left, right)
        }
        return if (reverse) segments.asReversed().map { it.reversed() } else segments.map { it.asPathSegment() }
    }

    private fun flattenedOffsetFactors(t: Float, relief: Float): Pair<Float, Float> {
        val q = 4f * t * (1f - t)
        val dq = 4f - 8f * t
        // q is the original single-cubic offset shape. Adding q^2(1-q)
        // preserves the endpoint tangent and center apex while easing the
        // center curvature, so long slurs never grow a visible shoulder.
        val shoulderlessRelief = q * q * (1f - q)
        val dShoulderlessRelief = dq * q * (2f - 3f * q)
        return (q + relief * shoulderlessRelief) to (dq + relief * dShoulderlessRelief)
    }

    private data class CurveKnot(
        val t: Float,
        val offset: Float,
        val offsetDerivative: Float,
    )

    private data class CubicSegment(
        val start: RelativePoint,
        val control1: RelativePoint,
        val control2: RelativePoint,
        val end: RelativePoint,
    ) {
        fun asPathSegment(): RelativePathSegment.CubicTo =
            RelativePathSegment.CubicTo(control1, control2, end)

        fun reversed(): RelativePathSegment.CubicTo =
            RelativePathSegment.CubicTo(control2, control1, start)
    }

    private fun hermiteSegment(
        start: RelativePoint,
        end: RelativePoint,
        params: LensParams,
        left: CurveKnot,
        right: CurveKnot,
    ): CubicSegment {
        val dt = right.t - left.t
        fun pointAt(knot: CurveKnot): RelativePoint {
            val x = start.x.value + (end.x.value - start.x.value) * knot.t + params.nx * knot.offset
            val y = start.y.value + (end.y.value - start.y.value) * knot.t + params.ny * knot.offset
            return RelativePoint(StaffSpace(x), StaffSpace(y))
        }
        fun derivativeAt(knot: CurveKnot): Pair<Float, Float> {
            val dx = end.x.value - start.x.value + params.nx * knot.offsetDerivative
            val dy = end.y.value - start.y.value + params.ny * knot.offsetDerivative
            return dx to dy
        }

        val p0 = pointAt(left)
        val p1 = pointAt(right)
        val (d0x, d0y) = derivativeAt(left)
        val (d1x, d1y) = derivativeAt(right)
        val cp1 = RelativePoint(
            StaffSpace(p0.x.value + d0x * dt / 3f),
            StaffSpace(p0.y.value + d0y * dt / 3f)
        )
        val cp2 = RelativePoint(
            StaffSpace(p1.x.value - d1x * dt / 3f),
            StaffSpace(p1.y.value - d1y * dt / 3f)
        )
        return CubicSegment(p0, cp1, cp2, p1)
    }

    private val FLATTENED_SAMPLE_T_VALUES = listOf(0f, 0.125f, 0.25f, 0.375f, 0.5f, 0.625f, 0.75f, 0.875f, 1f)
    private const val CENTER_CURVATURE_RELIEF = 0.72f
}
