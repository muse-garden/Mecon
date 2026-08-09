package com.mecon.renderer.render

import com.mecon.api.storage.Articulation
import com.mecon.api.storage.tracks.FermataShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArticulationGlyphsTest {

    @Test
    fun mapsRequestedArticulationsToCorrectAboveGlyphs() {
        assertEquals("articStaccatoAbove", ArticulationGlyphs.glyphFor(Articulation.STACCATO, above = true)?.name)
        assertEquals("articTenutoAbove", ArticulationGlyphs.glyphFor(Articulation.TENUTO, above = true)?.name)
        assertEquals("articAccentAbove", ArticulationGlyphs.glyphFor(Articulation.ACCENT, above = true)?.name)
        assertEquals("articMarcatoAbove", ArticulationGlyphs.glyphFor(Articulation.MARCATO, above = true)?.name)
        // Spiccato uses the staccatissimo teardrop.
        assertEquals("articStaccatissimoAbove", ArticulationGlyphs.glyphFor(Articulation.SPICCATO, above = true)?.name)
    }

    @Test
    fun mapsToBelowGlyphsWhenNotAbove() {
        assertEquals("articStaccatoBelow", ArticulationGlyphs.glyphFor(Articulation.STACCATO, above = false)?.name)
        assertEquals("articAccentBelow", ArticulationGlyphs.glyphFor(Articulation.ACCENT, above = false)?.name)
        assertEquals("articMarcatoBelow", ArticulationGlyphs.glyphFor(Articulation.MARCATO, above = false)?.name)
        assertEquals("articStaccatissimoBelow", ArticulationGlyphs.glyphFor(Articulation.SPICCATO, above = false)?.name)
    }

    @Test
    fun spiccatoGlyphIsTeardropCodepoint() {
        assertEquals('', ArticulationGlyphs.glyphFor(Articulation.SPICCATO, above = true)?.codepoint)
        assertEquals('', ArticulationGlyphs.glyphFor(Articulation.SPICCATO, above = false)?.codepoint)
    }

    @Test
    fun mapsFermataShapesAboveAndBelow() {
        assertEquals(
            "fermataAbove",
            ArticulationGlyphs.glyphFor(Articulation.FERMATA, above = true)?.name,
        )
        assertEquals(
            "fermataShortBelow",
            ArticulationGlyphs.glyphFor(
                Articulation.FERMATA,
                above = false,
                fermataShape = FermataShape.SHORT,
            )?.name,
        )
        assertEquals(
            "fermataVeryLongAbove",
            ArticulationGlyphs.glyphFor(
                Articulation.FERMATA,
                above = true,
                fermataShape = FermataShape.VERY_LONG,
            )?.name,
        )
    }

    @Test
    fun stackingOrderPutsStaccatoInsideAccentInsideMarcato() {
        val staccato = ArticulationGlyphs.stackRank(Articulation.STACCATO)
        val accent = ArticulationGlyphs.stackRank(Articulation.ACCENT)
        val marcato = ArticulationGlyphs.stackRank(Articulation.MARCATO)
        assertTrue(staccato < accent, "staccato should sit nearer the note than accent")
        assertTrue(accent < marcato, "accent should sit nearer the note than marcato")
    }
}
