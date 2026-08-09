package com.mecon.renderer.elements

import com.mecon.api.primitive.BarlineType
import com.mecon.api.primitive.TimeCode
import com.mecon.renderer.geometry.GlyphGeometry
import com.mecon.renderer.geometry.LineGeometry
import com.mecon.renderer.smufl.BravuraFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BarlineElementTest {
    private val font = BravuraFont.fromJson(
        metadataJson = """
            {
              "fontName": "Test Bravura",
              "fontVersion": 1.0,
              "glyphAdvanceWidths": { "repeatDots": 0.4 },
              "glyphBBoxes": {
                "repeatDots": {
                  "bBoxNE": [0.4, 2.68],
                  "bBoxSW": [0.0, 1.272]
                }
              }
            }
        """.trimIndent(),
        glyphNamesJson = "{}",
    )

    @Test
    fun dashedAndDottedBarlinesCarryDistinctStrokePatterns() = with(font) {
        val dashed = BarlineElement.create(TimeCode.ZERO, BarlineType.DASHED, 1)
            .geometryList.single() as LineGeometry
        val dotted = BarlineElement.create(TimeCode.ZERO, BarlineType.DOTTED, 1)
            .geometryList.single() as LineGeometry

        assertNotNull(dashed.dashIntervals)
        assertNotNull(dotted.dashIntervals)
        assertEquals(2, dashed.dashIntervals!!.size)
        assertEquals(2, dotted.dashIntervals!!.size)
    }

    @Test
    fun repeatBothContainsConnectedRulesAndDotsForEachSide() = with(font) {
        val geometry = BarlineElement.create(TimeCode.ZERO, BarlineType.REPEAT_BOTH, 1).geometryList

        assertEquals(3, geometry.filterIsInstance<LineGeometry>().size)
        assertEquals(2, geometry.filterIsInstance<GlyphGeometry>().size)
    }
}
