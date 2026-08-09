package com.mecon.renderer.smufl

import com.mecon.renderer.geometry.StaffSpace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SmuflMetadataTest {

    private val sampleMetadataJson = """
        {
            "fontName": "TestFont",
            "fontVersion": 1.0,
            "engravingDefaults": {
                "staffLineThickness": 0.13,
                "stemThickness": 0.12,
                "beamThickness": 0.5,
                "beamSpacing": 0.25
            },
            "glyphAdvanceWidths": {
                "noteheadBlack": 1.18,
                "noteheadHalf": 1.18,
                "noteheadWhole": 1.66
            },
            "glyphBBoxes": {
                "noteheadBlack": {
                    "bBoxSW": [-0.028, -0.5],
                    "bBoxNE": [1.208, 0.5]
                }
            }
        }
    """.trimIndent()

    @Test
    fun testParseFontInfo() {
        val metadata = SmuflMetadata.fromJson(sampleMetadataJson)

        assertEquals("TestFont", metadata.fontName)
        assertEquals(1.0f, metadata.fontVersion)
    }

    @Test
    fun testParseEngravingDefaults() {
        val metadata = SmuflMetadata.fromJson(sampleMetadataJson)
        val defaults = metadata.engravingDefaults

        assertEquals(StaffSpace(0.13f), defaults.staffLineThickness)
        assertEquals(StaffSpace(0.12f), defaults.stemThickness)
        assertEquals(StaffSpace(0.5f), defaults.beamThickness)
        assertEquals(StaffSpace(0.25f), defaults.beamSpacing)
    }

    @Test
    fun testParseGlyphAdvanceWidths() {
        val metadata = SmuflMetadata.fromJson(sampleMetadataJson)

        assertEquals(StaffSpace(1.18f), metadata.getAdvanceWidth("noteheadBlack"))
        assertEquals(StaffSpace(1.18f), metadata.getAdvanceWidth("noteheadHalf"))
        assertEquals(StaffSpace(1.66f), metadata.getAdvanceWidth("noteheadWhole"))
    }

    @Test
    fun testGetUnknownAdvanceWidth() {
        val metadata = SmuflMetadata.fromJson(sampleMetadataJson)

        // Unknown glyph should return StaffSpace.ONE as default
        assertEquals(StaffSpace.ONE, metadata.getAdvanceWidth("unknownGlyph"))
    }

    @Test
    fun testParseGlyphBBox() {
        val metadata = SmuflMetadata.fromJson(sampleMetadataJson)
        val bbox = metadata.getBBox("noteheadBlack")

        assertNotNull(bbox)
        assertEquals(StaffSpace(-0.028f), bbox.southWest.x)
        assertEquals(StaffSpace(-0.5f), bbox.southWest.y)
        assertEquals(StaffSpace(1.208f), bbox.northEast.x)
        assertEquals(StaffSpace(0.5f), bbox.northEast.y)
    }

    @Test
    fun testBBoxDimensions() {
        val metadata = SmuflMetadata.fromJson(sampleMetadataJson)
        val bbox = metadata.getBBox("noteheadBlack")

        assertNotNull(bbox)
        // Width = 1.208 - (-0.028) = 1.236
        assertEquals(1.236f, bbox.width.value, 0.001f)
        // Height = 0.5 - (-0.5) = 1.0
        assertEquals(1.0f, bbox.height.value, 0.001f)
    }
}
