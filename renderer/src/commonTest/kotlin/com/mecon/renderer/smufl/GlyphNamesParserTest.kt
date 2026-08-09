package com.mecon.renderer.smufl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GlyphNamesParserTest {

    private val sampleGlyphNamesJson = """
        {
            "noteheadBlack": {
                "codepoint": "U+E0A4",
                "description": "Filled (black) note"
            },
            "noteheadHalf": {
                "codepoint": "U+E0A3",
                "description": "Half note (minim)"
            },
            "gClef": {
                "codepoint": "U+E050",
                "description": "G clef (treble clef)"
            }
        }
    """.trimIndent()

    @Test
    fun testParseGlyphNames() {
        val glyphNames = GlyphNamesParser.parse(sampleGlyphNamesJson)

        assertEquals(3, glyphNames.size)
        assertNotNull(glyphNames["noteheadBlack"])
        assertNotNull(glyphNames["noteheadHalf"])
        assertNotNull(glyphNames["gClef"])
    }

    @Test
    fun testGlyphNameEntry() {
        val glyphNames = GlyphNamesParser.parse(sampleGlyphNamesJson)

        val noteheadBlack = glyphNames["noteheadBlack"]
        assertNotNull(noteheadBlack)
        assertEquals("U+E0A4", noteheadBlack.codepoint)
        assertEquals("Filled (black) note", noteheadBlack.description)
    }

    @Test
    fun testCodepointConversion() {
        val glyphNames = GlyphNamesParser.parse(sampleGlyphNamesJson)

        val noteheadBlack = glyphNames["noteheadBlack"]
        assertNotNull(noteheadBlack)
        assertEquals('\uE0A4', noteheadBlack.toChar())

        val gClef = glyphNames["gClef"]
        assertNotNull(gClef)
        assertEquals('\uE050', gClef.toChar())
    }

    @Test
    fun testGetCodepoint() {
        val glyphNames = GlyphNamesParser.parse(sampleGlyphNamesJson)

        val codepoint = GlyphNamesParser.getCodepoint(glyphNames, "noteheadHalf")
        assertEquals('\uE0A3', codepoint)
    }
}
