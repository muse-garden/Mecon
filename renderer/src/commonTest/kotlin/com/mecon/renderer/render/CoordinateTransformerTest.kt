package com.mecon.renderer.render

import com.mecon.renderer.geometry.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoordinateTransformerTest {

    @Test
    fun testRelativeToAbsolute() {
        val transformer = CoordinateTransformer(
            scale = ScaleFactor(10f),  // 10 pixels per staff space
            scoreOrigin = AbsolutePoint.ZERO,
            scrollOffset = AbsolutePoint.ZERO
        )

        val relativePoint = RelativePoint(StaffSpace(5f), StaffSpace(3f))
        val absolutePoint = transformer.toAbsolute(relativePoint)

        assertEquals(50f, absolutePoint.x.value)
        assertEquals(30f, absolutePoint.y.value)
    }

    @Test
    fun testAbsoluteToRelative() {
        val transformer = CoordinateTransformer(
            scale = ScaleFactor(10f),
            scoreOrigin = AbsolutePoint.ZERO,
            scrollOffset = AbsolutePoint.ZERO
        )

        val absolutePoint = AbsolutePoint(Pixels(50f), Pixels(30f))
        val relativePoint = transformer.toRelative(absolutePoint)

        assertEquals(5f, relativePoint.x.value)
        assertEquals(3f, relativePoint.y.value)
    }

    @Test
    fun testWithScoreOrigin() {
        val transformer = CoordinateTransformer(
            scale = ScaleFactor(10f),
            scoreOrigin = AbsolutePoint(Pixels(100f), Pixels(50f)),
            scrollOffset = AbsolutePoint.ZERO
        )

        val relativePoint = RelativePoint(StaffSpace(5f), StaffSpace(3f))
        val absolutePoint = transformer.toAbsolute(relativePoint)

        assertEquals(150f, absolutePoint.x.value)  // 100 + 50
        assertEquals(80f, absolutePoint.y.value)   // 50 + 30
    }

    @Test
    fun testWithScrollOffset() {
        val transformer = CoordinateTransformer(
            scale = ScaleFactor(10f),
            scoreOrigin = AbsolutePoint.ZERO,
            scrollOffset = AbsolutePoint(Pixels(20f), Pixels(10f))
        )

        val relativePoint = RelativePoint(StaffSpace(5f), StaffSpace(3f))
        val absolutePoint = transformer.toAbsolute(relativePoint)

        assertEquals(30f, absolutePoint.x.value)  // 50 - 20
        assertEquals(20f, absolutePoint.y.value)  // 30 - 10
    }

    @Test
    fun testRectConversion() {
        val transformer = CoordinateTransformer(
            scale = ScaleFactor(10f),
            scoreOrigin = AbsolutePoint.ZERO,
            scrollOffset = AbsolutePoint.ZERO
        )

        val relativeRect = RelativeRect(
            origin = RelativePoint(StaffSpace(1f), StaffSpace(2f)),
            width = StaffSpace(5f),
            height = StaffSpace(3f)
        )

        val absoluteRect = transformer.toAbsolute(relativeRect)

        assertEquals(10f, absoluteRect.origin.x.value)
        assertEquals(20f, absoluteRect.origin.y.value)
        assertEquals(50f, absoluteRect.width.value)
        assertEquals(30f, absoluteRect.height.value)
    }

    @Test
    fun testVisibleArea() {
        val transformer = CoordinateTransformer(
            scale = ScaleFactor(10f),
            scoreOrigin = AbsolutePoint.ZERO,
            scrollOffset = AbsolutePoint.ZERO,
            viewportBounds = AbsoluteRect(
                AbsolutePoint.ZERO,
                Pixels(800f),
                Pixels(600f)
            )
        )

        val visible = transformer.visibleArea()

        assertEquals(80f, visible.width.value)   // 800 / 10
        assertEquals(60f, visible.height.value)  // 600 / 10
    }

    @Test
    fun testIsVisible() {
        val transformer = CoordinateTransformer(
            scale = ScaleFactor(10f),
            scoreOrigin = AbsolutePoint.ZERO,
            scrollOffset = AbsolutePoint.ZERO,
            viewportBounds = AbsoluteRect(
                AbsolutePoint.ZERO,
                Pixels(100f),
                Pixels(100f)
            )
        )

        // Point at (5, 5) staff spaces = (50, 50) pixels - should be visible
        assertTrue(transformer.isVisible(RelativePoint(StaffSpace(5f), StaffSpace(5f))))

        // Point at (20, 20) staff spaces = (200, 200) pixels - outside viewport
        assertTrue(!transformer.isVisible(RelativePoint(StaffSpace(20f), StaffSpace(20f))))
    }

    @Test
    fun testScrollBy() {
        val transformer = CoordinateTransformer(
            scale = ScaleFactor(10f),
            scoreOrigin = AbsolutePoint.ZERO,
            scrollOffset = AbsolutePoint.ZERO
        )

        transformer.scrollBy(AbsolutePoint(Pixels(50f), Pixels(30f)))

        assertEquals(50f, transformer.getScrollOffset().x.value)
        assertEquals(30f, transformer.getScrollOffset().y.value)
    }

    @Test
    fun testFitToViewport() {
        val contentBounds = RelativeRect(
            origin = RelativePoint.ZERO,
            width = StaffSpace(100f),
            height = StaffSpace(50f)
        )

        val viewportSize = AbsoluteRect(
            AbsolutePoint.ZERO,
            Pixels(800f),
            Pixels(600f)
        )

        val transformer = CoordinateTransformer.fitToViewport(
            contentBounds = contentBounds,
            viewportSize = viewportSize,
            padding = Pixels(0f)
        )

        // Should fit width or height, maintaining aspect ratio
        val scale = transformer.getScale()
        assertTrue(scale.pixelsPerStaffSpace > 0)
    }
}
