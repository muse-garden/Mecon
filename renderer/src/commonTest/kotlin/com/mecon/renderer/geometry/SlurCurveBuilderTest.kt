package com.mecon.renderer.geometry

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlurCurveBuilderTest {

    @Test
    fun lensBoundsRespectCallerSuppliedMaxHeight() {
        val bounds = SlurCurveBuilder.lensBounds(
            start = RelativePoint.of(0f, 0f),
            end = RelativePoint.of(64f, 0f),
            direction = SlurDirection.BELOW,
            midpointThickness = StaffSpace(0.22f),
            minHeight = StaffSpace(8f),
            maxHeight = StaffSpace(2f),
        )

        assertTrue(bounds.height.value <= 2.2f)
    }

    @Test
    fun slopeDampingReducesHorizontalControlTwistOnSlopedSlurs() {
        val undamped = SlurCurveBuilder.buildLensPath(
            start = RelativePoint.of(0f, 0f),
            end = RelativePoint.of(40f, 12f),
            direction = SlurDirection.BELOW,
            midpointThickness = StaffSpace(0.22f),
        )
        val damped = SlurCurveBuilder.buildLensPath(
            start = RelativePoint.of(0f, 0f),
            end = RelativePoint.of(40f, 12f),
            direction = SlurDirection.BELOW,
            midpointThickness = StaffSpace(0.22f),
            slopeDamping = 0.4f,
        )

        val undampedOuter = undamped.segments[1] as RelativePathSegment.CubicTo
        val dampedOuter = damped.segments[1] as RelativePathSegment.CubicTo

        val nominalThirdX = 40f / 3f
        val undampedTwist = kotlin.math.abs(undampedOuter.control1.x.value - nominalThirdX)
        val dampedTwist = kotlin.math.abs(dampedOuter.control1.x.value - nominalThirdX)

        assertTrue(dampedTwist < undampedTwist)
        assertEquals(undampedOuter.end, dampedOuter.end)
    }

    @Test
    fun defaultHeightFollowsHorizontalSpanInsteadOfEndpointDistance() {
        val flat = SlurCurveBuilder.lensBounds(
            start = RelativePoint.of(0f, 0f),
            end = RelativePoint.of(8f, 0f),
            direction = SlurDirection.BELOW,
            midpointThickness = StaffSpace(0.22f),
        )
        val steep = SlurCurveBuilder.lensBounds(
            start = RelativePoint.of(0f, 0f),
            end = RelativePoint.of(8f, 12f),
            direction = SlurDirection.BELOW,
            midpointThickness = StaffSpace(0.22f),
            slopeDamping = 0f,
            heightUsesHorizontalSpan = true,
        )

        val flatApexDepth = flat.height.value
        val steepApexDepth = steep.height.value - 12f

        assertTrue(steepApexDepth <= flatApexDepth + 0.1f)
    }

    @Test
    fun middleStraighteningKeepsShallowCurvatureThroughCenter() {
        val path = SlurCurveBuilder.buildLensPath(
            start = RelativePoint.of(0f, 0f),
            end = RelativePoint.of(80f, 16f),
            direction = SlurDirection.BELOW,
            midpointThickness = StaffSpace(0.22f),
            heightUsesHorizontalSpan = true,
            middleStraightening = 1f,
        )

        assertEquals(18, path.segments.size)
        val startCap = path.segments[1] as RelativePathSegment.CubicTo
        val quarter = path.segments[2] as RelativePathSegment.CubicTo
        val leftMiddle = path.segments[3] as RelativePathSegment.CubicTo
        val center = path.segments[4] as RelativePathSegment.CubicTo
        val rightMiddle = path.segments[5] as RelativePathSegment.CubicTo
        val threeQuarter = path.segments[6] as RelativePathSegment.CubicTo

        val quarterExcursion = excursionFromBaseline(quarter.end)
        val leftMiddleExcursion = excursionFromBaseline(leftMiddle.end)
        val apexExcursion = excursionFromBaseline(center.end)
        val rightShoulderExcursion = excursionFromBaseline(rightMiddle.end)
        val threeQuarterExcursion = excursionFromBaseline(threeQuarter.end)

        assertTrue(leftMiddleExcursion > quarterExcursion)
        assertTrue(apexExcursion > leftMiddleExcursion)
        assertTrue(apexExcursion > rightShoulderExcursion)
        assertTrue(rightShoulderExcursion > threeQuarterExcursion)
        assertTrue(apexExcursion - leftMiddleExcursion < apexExcursion * 0.18f)
        assertTrue(apexExcursion - rightShoulderExcursion < apexExcursion * 0.2f)
        assertTrue(excursionFromBaseline(leftMiddle.control1) > quarterExcursion)
        assertTrue(startCap.control1.y.value < startCap.end.y.value)
    }

    @Test
    fun filledPathHitTesterRejectsEmptyInteriorOfSlurBounds() {
        val path = SlurCurveBuilder.buildLensPath(
            start = RelativePoint.of(0f, 0f),
            end = RelativePoint.of(24f, 0f),
            direction = SlurDirection.ABOVE,
            midpointThickness = StaffSpace(0.22f),
            heightUsesHorizontalSpan = true,
        )
        val hit = path.createFilledPathHitShape(SlurCurveBuilder.DEFAULT_HIT_TOLERANCE)

        assertTrue(hit.contains(RelativePoint.of(12f, -1.8f)), "painted apex should remain selectable")
        assertTrue(hit.contains(RelativePoint.of(12f, -2.1f)), "small halo outside the apex should be selectable")
        assertTrue(!hit.contains(RelativePoint.of(12f, -0.4f)), "empty space inside the AABB must not select the slur")
    }

    @Test
    fun filledPathHitTesterFollowsStraightenedSlurSegments() {
        val path = SlurCurveBuilder.buildLensPath(
            start = RelativePoint.of(0f, 0f),
            end = RelativePoint.of(80f, 16f),
            direction = SlurDirection.BELOW,
            midpointThickness = StaffSpace(0.22f),
            heightUsesHorizontalSpan = true,
            middleStraightening = 1f,
        )
        val hit = path.createFilledPathHitShape(SlurCurveBuilder.DEFAULT_HIT_TOLERANCE)
        val outerCenterSegment = path.segments[4] as RelativePathSegment.CubicTo

        assertTrue(hit.contains(outerCenterSegment.end), "multi-cubic slur path should remain selectable")
        assertTrue(!hit.contains(RelativePoint.of(40f, 8f)), "baseline interior should not select a bowed slur")
    }

    @Test
    fun filledPathHitShapeFollowsIncrementalTranslation() {
        val path = SlurCurveBuilder.buildLensPath(
            start = RelativePoint.of(0f, 0f),
            end = RelativePoint.of(24f, 0f),
            direction = SlurDirection.ABOVE,
            midpointThickness = StaffSpace(0.22f),
            heightUsesHorizontalSpan = true,
        )
        val original = path.createFilledPathHitShape(SlurCurveBuilder.DEFAULT_HIT_TOLERANCE)
        val translated = original.translatedBy(StaffSpace(7f), StaffSpace(3f))

        assertTrue(translated.contains(RelativePoint.of(19f, 1.2f)))
        assertTrue(!translated.contains(RelativePoint.of(12f, -1.8f)))
    }

    private fun excursionFromBaseline(point: RelativePoint): Float =
        abs(point.y.value - baselineY(point.x.value))

    private fun baselineY(x: Float): Float = x * LONG_SLUR_SLOPE

    private companion object {
        private const val LONG_SLUR_SLOPE = 16f / 80f
    }
}
