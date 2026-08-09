package com.mecon.renderer.render

import com.mecon.api.render.RenderColor
import kotlin.test.Test
import kotlin.test.assertEquals

class RenderColorTest {

    @Test
    fun testPredefinedColors() {
        assertEquals(255, RenderColor.BLACK.alpha)
        assertEquals(0, RenderColor.BLACK.red)
        assertEquals(0, RenderColor.BLACK.green)
        assertEquals(0, RenderColor.BLACK.blue)

        assertEquals(255, RenderColor.WHITE.alpha)
        assertEquals(255, RenderColor.WHITE.red)
        assertEquals(255, RenderColor.WHITE.green)
        assertEquals(255, RenderColor.WHITE.blue)

        assertEquals(0, RenderColor.TRANSPARENT.alpha)
    }

    @Test
    fun testToArgb() {
        val black = RenderColor.BLACK
        // 0xFF000000
        assertEquals(0xFF000000.toInt(), black.toArgb())

        val white = RenderColor.WHITE
        // 0xFFFFFFFF
        assertEquals(0xFFFFFFFF.toInt(), white.toArgb())

        val red = RenderColor.RED
        // 0xFFFF0000
        assertEquals(0xFFFF0000.toInt(), red.toArgb())
    }

    @Test
    fun testFromArgb() {
        val color = RenderColor.fromArgb(0xFF804020.toInt())

        assertEquals(255, color.alpha)
        assertEquals(128, color.red)
        assertEquals(64, color.green)
        assertEquals(32, color.blue)
    }

    @Test
    fun testRgb() {
        val color = RenderColor.rgb(100, 150, 200)

        assertEquals(255, color.alpha)
        assertEquals(100, color.red)
        assertEquals(150, color.green)
        assertEquals(200, color.blue)
    }

    @Test
    fun testRgba() {
        val color = RenderColor.rgba(100, 150, 200, 128)

        assertEquals(128, color.alpha)
        assertEquals(100, color.red)
        assertEquals(150, color.green)
        assertEquals(200, color.blue)
    }

    @Test
    fun testToHex() {
        assertEquals("#FF000000", RenderColor.BLACK.toHex())
        assertEquals("#FFFFFFFF", RenderColor.WHITE.toHex())
        assertEquals("#00000000", RenderColor.TRANSPARENT.toHex())
    }
}
