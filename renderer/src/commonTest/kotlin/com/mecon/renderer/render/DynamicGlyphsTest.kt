package com.mecon.renderer.render

import com.mecon.api.storage.events.DynamicLevel
import com.mecon.renderer.smufl.SmuflGlyphs
import kotlin.test.Test
import kotlin.test.assertEquals

class DynamicGlyphsTest {

    @Test
    fun singleLetterMapsToBaseGlyph() {
        assertEquals(listOf(SmuflGlyphs.dynamicPiano), DynamicGlyphs.glyphsFor(DynamicLevel.P))
        assertEquals(listOf(SmuflGlyphs.dynamicForte), DynamicGlyphs.glyphsFor(DynamicLevel.F))
    }

    @Test
    fun compositeMarksUsePrecomposedGlyphs() {
        // Composite marks use the ready-made SMuFL pre-composed glyphs (single glyph each).
        assertEquals(listOf(SmuflGlyphs.dynamicMF), DynamicGlyphs.glyphsFor(DynamicLevel.MF))
        assertEquals(listOf(SmuflGlyphs.dynamicPP), DynamicGlyphs.glyphsFor(DynamicLevel.PP))
        assertEquals(listOf(SmuflGlyphs.dynamicSforzato), DynamicGlyphs.glyphsFor(DynamicLevel.SFZ))
        assertEquals(listOf(SmuflGlyphs.dynamicFortePiano), DynamicGlyphs.glyphsFor(DynamicLevel.FP))
    }

    @Test
    fun extendedDynamicsUseTheirSmuflPrecomposedGlyphs() {
        assertEquals(listOf(SmuflGlyphs.dynamicPPPPPP), DynamicGlyphs.glyphsFor(DynamicLevel.PPPPPP))
        assertEquals(listOf(SmuflGlyphs.dynamicPPPPP), DynamicGlyphs.glyphsFor(DynamicLevel.PPPPP))
        assertEquals(listOf(SmuflGlyphs.dynamicPF), DynamicGlyphs.glyphsFor(DynamicLevel.PF))
        assertEquals(listOf(SmuflGlyphs.dynamicFFFFF), DynamicGlyphs.glyphsFor(DynamicLevel.FFFFF))
        assertEquals(listOf(SmuflGlyphs.dynamicFFFFFF), DynamicGlyphs.glyphsFor(DynamicLevel.FFFFFF))
        assertEquals(listOf(SmuflGlyphs.dynamicNiente), DynamicGlyphs.glyphsFor(DynamicLevel.NIENTE))
        assertEquals(listOf(SmuflGlyphs.dynamicSforzandoPianissimo), DynamicGlyphs.glyphsFor(DynamicLevel.SFPP))
        assertEquals(listOf(SmuflGlyphs.dynamicSforzatoPiano), DynamicGlyphs.glyphsFor(DynamicLevel.SFZP))
    }

    @Test
    fun everyLevelMapsToExactlyOneGlyph() {
        // Every DynamicLevel must produce exactly one SMuFL glyph — never zero or many.
        for (level in DynamicLevel.entries) {
            assertEquals(1, DynamicGlyphs.glyphsFor(level).size, "Level $level should map to one glyph")
        }
    }
}
