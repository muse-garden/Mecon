package com.mecon.renderer.layout

import com.mecon.renderer.geometry.StaffSpace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RenderLayoutConfigTest {

    @Test
    fun testDefaultConfig() {
        val config = RenderLayoutConfig.DEFAULT

        assertEquals(1.6f, config.spacingRatio)
        assertEquals(5, config.staffLineCount)
    }

    @Test
    fun testCompactConfig() {
        val config = RenderLayoutConfig.COMPACT

        assertTrue(config.minimumNoteSpacing < RenderLayoutConfig.DEFAULT.minimumNoteSpacing)
        assertTrue(config.baseSpacingUnit < RenderLayoutConfig.DEFAULT.baseSpacingUnit)
    }

    @Test
    fun testSpaciousConfig() {
        val config = RenderLayoutConfig.SPACIOUS

        assertTrue(config.minimumNoteSpacing > RenderLayoutConfig.DEFAULT.minimumNoteSpacing)
        assertTrue(config.partSpacing > RenderLayoutConfig.DEFAULT.partSpacing)
    }

    @Test
    fun testCustomConfig() {
        val config = RenderLayoutConfig(
            minimumNoteSpacing = StaffSpace(2.0f),
            baseSpacingUnit = StaffSpace(1.5f),
            spacingRatio = 2.0f
        )

        assertEquals(StaffSpace(2.0f), config.minimumNoteSpacing)
        assertEquals(StaffSpace(1.5f), config.baseSpacingUnit)
        assertEquals(2.0f, config.spacingRatio)
    }
}
