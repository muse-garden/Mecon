package com.mecon.renderer.smufl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SmuflGlyphsTest {

    @Test
    fun testBraceAlternateCodepoints() {
        assertEquals('\uE000', SmuflGlyphs.brace.codepoint)
        assertEquals('\uF400', SmuflGlyphs.braceSmall.codepoint)
        assertEquals('\uF401', SmuflGlyphs.braceLarge.codepoint)
        assertEquals('\uF402', SmuflGlyphs.braceLarger.codepoint)
        assertEquals('\uF403', SmuflGlyphs.braceFlat.codepoint)
    }

    @Test
    fun testNoteheadCodepoints() {
        // Verify standard notehead codepoints
        assertEquals('\uE0A2', SmuflGlyphs.noteheadWhole.codepoint)
        assertEquals('\uE0A3', SmuflGlyphs.noteheadHalf.codepoint)
        assertEquals('\uE0A4', SmuflGlyphs.noteheadBlack.codepoint)
    }

    @Test
    fun testClefCodepoints() {
        assertEquals('\uE050', SmuflGlyphs.gClef.codepoint)
        assertEquals('\uE05C', SmuflGlyphs.cClef.codepoint)
        assertEquals('\uE062', SmuflGlyphs.fClef.codepoint)
    }

    @Test
    fun testAccidentalCodepoints() {
        assertEquals('\uE260', SmuflGlyphs.accidentalFlat.codepoint)
        assertEquals('\uE261', SmuflGlyphs.accidentalNatural.codepoint)
        assertEquals('\uE262', SmuflGlyphs.accidentalSharp.codepoint)
        assertEquals('\uE263', SmuflGlyphs.accidentalDoubleSharp.codepoint)
        assertEquals('\uE264', SmuflGlyphs.accidentalDoubleFlat.codepoint)
    }

    @Test
    fun testRestCodepoints() {
        assertEquals('\uE4E2', SmuflGlyphs.restDoubleWhole.codepoint)
        assertEquals('\uE4E3', SmuflGlyphs.restWhole.codepoint)
        assertEquals('\uE4E4', SmuflGlyphs.restHalf.codepoint)
        assertEquals('\uE4E5', SmuflGlyphs.restQuarter.codepoint)
        assertEquals('\uE4E6', SmuflGlyphs.rest8th.codepoint)
    }

    @Test
    fun testTimeSigDigit() {
        assertEquals('\uE080', SmuflGlyphs.timeSigDigit(0).codepoint)
        assertEquals('\uE084', SmuflGlyphs.timeSigDigit(4).codepoint)
        assertEquals('\uE089', SmuflGlyphs.timeSigDigit(9).codepoint)
    }

    @Test
    fun testTimeSigDigitInvalid() {
        assertFailsWith<IllegalArgumentException> {
            SmuflGlyphs.timeSigDigit(10)
        }
        assertFailsWith<IllegalArgumentException> {
            SmuflGlyphs.timeSigDigit(-1)
        }
    }

    @Test
    fun testTupletDigit() {
        assertEquals('\uE880', SmuflGlyphs.tupletDigit(0).codepoint)
        assertEquals('\uE883', SmuflGlyphs.tupletDigit(3).codepoint)
    }

    @Test
    fun testFlagCodepoints() {
        assertEquals('\uE240', SmuflGlyphs.flag8thUp.codepoint)
        assertEquals('\uE241', SmuflGlyphs.flag8thDown.codepoint)
        assertEquals('\uE242', SmuflGlyphs.flag16thUp.codepoint)
        assertEquals('\uE243', SmuflGlyphs.flag16thDown.codepoint)
    }

    @Test
    fun testDynamicsCodepoints() {
        assertEquals('\uE520', SmuflGlyphs.dynamicPiano.codepoint)
        assertEquals('\uE522', SmuflGlyphs.dynamicForte.codepoint)
        assertEquals('\uE52B', SmuflGlyphs.dynamicPP.codepoint)
        assertEquals('\uE52F', SmuflGlyphs.dynamicFF.codepoint)
    }

    @Test
    fun testArticulationCodepoints() {
        assertEquals('\uE4A0', SmuflGlyphs.articAccentAbove.codepoint)
        assertEquals('\uE4A2', SmuflGlyphs.articStaccatoAbove.codepoint)
        assertEquals('\uE4A4', SmuflGlyphs.articTenutoAbove.codepoint)
    }

    @Test
    fun testFermataCodepoints() {
        assertEquals('\uE4C0', SmuflGlyphs.fermataAbove.codepoint)
        assertEquals('\uE4C1', SmuflGlyphs.fermataBelow.codepoint)
    }
}
