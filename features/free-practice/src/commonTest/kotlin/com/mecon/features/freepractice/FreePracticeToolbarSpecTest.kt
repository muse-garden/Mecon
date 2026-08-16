package com.mecon.features.freepractice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FreePracticeToolbarSpecTest {
    @Test
    fun descriptorHasStableUniqueDesktopAuthorityOrder() {
        val descriptor = FreePracticeToolbarSpec.descriptor
        assertEquals(
            listOf("file", "history", "mode", "writing", "structure", "playback", "settings"),
            descriptor.top.groups.map { it.id },
        )
        assertEquals(
            listOf(
                "tool", "voice", "duration", "accidental",
                "curve", "grace", "tuplet", "beam", "articulation",
            ),
            descriptor.score.groups.map { it.id },
        )
        assertEquals(FreePracticeToolbarSpec.topControlIds.distinct(), FreePracticeToolbarSpec.topControlIds)
        assertEquals(FreePracticeToolbarSpec.scoreControlIds.distinct(), FreePracticeToolbarSpec.scoreControlIds)
        assertEquals("file.new", FreePracticeToolbarSpec.topControlIds.first())
        assertEquals("settings.application", FreePracticeToolbarSpec.topControlIds.last())
        assertEquals("tool.select", FreePracticeToolbarSpec.scoreControlIds.first())
        assertEquals("articulation.staccatissimo", FreePracticeToolbarSpec.scoreControlIds.last())
        assertTrue(descriptor.top.groups.last().trailing)
        assertEquals(64f, descriptor.tokens.topHeight)
        assertEquals(28f, descriptor.tokens.paletteButtonSize)
    }
}
