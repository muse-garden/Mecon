package com.mecon.renderer.render

import com.mecon.api.computed.StaffIndexRange
import com.mecon.api.primitive.BarlineType
import com.mecon.api.primitive.TimeCode
import com.mecon.api.storage.NavigationMark
import com.mecon.api.storage.tracks.BracketStyle
import com.mecon.renderer.elements.BarlineElement
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.layout.StaffHeaderLayoutComputer
import com.mecon.renderer.smufl.BravuraFont
import com.mecon.renderer.smufl.SmuflGlyphs
import kotlin.test.Test
import kotlin.test.assertEquals

class SystemRendererTest {
    private val font = BravuraFont.fromJson(
        metadataJson = """
            {
              "fontName": "Test Bravura",
              "fontVersion": 1.0,
              "glyphBBoxes": {
                "brace": {
                  "bBoxNE": [0.328, 3.988],
                  "bBoxSW": [0.008, 0.0]
                },
                "braceSmall": {
                  "bBoxNE": [0.412, 3.988],
                  "bBoxSW": [0.0, 0.0]
                },
                "braceLarge": {
                  "bBoxNE": [0.268, 3.992],
                  "bBoxSW": [0.0, 0.004]
                },
                "braceLarger": {
                  "bBoxNE": [0.24, 3.988],
                  "bBoxSW": [0.0, 0.0]
                },
                "braceFlat": {
                  "bBoxNE": [0.224, 4.0],
                  "bBoxSW": [0.0, 0.004]
                }
              }
            }
        """.trimIndent(),
        glyphNamesJson = "{}"
    )

    @Test
    fun systemBarlinesPreserveMeasureBoundaryMetadata() {
        val transformer = CoordinateTransformer.default()

        val element = with(font) {
            val renderer = SystemRenderer(RenderLayoutConfig.DEFAULT, transformer)
            renderer.renderSystemBarline(
                barlineElement = BarlineElement.create(TimeCode.ZERO, BarlineType.SINGLE, 7),
                slotX = StaffSpace(4f),
                firstStaffTopY = StaffSpace.ZERO,
                lastStaffBottomY = StaffSpace(12f),
                connectedRanges = emptyList(),
                staffByIndex = emptyMap(),
                idGenerator = { RenderElementId.global(1) },
            )
        }.single()

        assertEquals(7, element.measureNumber)
    }

    @Test
    fun syntheticSystemEdgesUseTheirActualMeasureBoundary() {
        val transformer = CoordinateTransformer.default()

        val closing = with(font) {
            val renderer = SystemRenderer(RenderLayoutConfig.DEFAULT, transformer)
            renderer.renderClosingBarline(
                type = BarlineType.SINGLE,
                measureNumber = 11,
                x = StaffSpace(20f),
                topY = StaffSpace.ZERO,
                bottomY = StaffSpace(12f),
                staffByIndex = emptyMap(),
                idGenerator = { RenderElementId.global(1) },
            )
        }.single()
        val opening = with(font) {
            val renderer = SystemRenderer(RenderLayoutConfig.DEFAULT, transformer)
            renderer.renderSystemStartLine(
                type = BarlineType.SINGLE,
                measureNumber = 11,
                x = StaffSpace(2f),
                topY = StaffSpace.ZERO,
                bottomY = StaffSpace(12f),
                staffByIndex = emptyMap(),
                idGenerator = { RenderElementId.global(2) },
            )
        }.single()

        assertEquals(11, closing.measureNumber)
        assertEquals(11, opening.measureNumber)
    }

    @Test
    fun navigationMarkPreservesItsAnchorStaff() {
        val element = with(font) {
            val renderer = SystemRenderer(RenderLayoutConfig.DEFAULT, CoordinateTransformer.default())
            renderer.renderNavigationMark(
                x = StaffSpace(8f),
                y = StaffSpace(2f),
                mark = NavigationMark.DA_CAPO,
                staffIndex = 3,
                idGenerator = { RenderElementId.global(1) },
            )
        }.single()

        assertEquals(3, element.staffIndex)
    }

    @Test
    fun braceScalesProportionallyAndUsesAlternateForLargeSpan() {
        val transformer = CoordinateTransformer.default()
        val glyph = renderBrace(20f, transformer)

        assertEquals(SmuflGlyphs.braceLarger.codepoint, glyph.glyph.codepoint)
        assertEquals(20f / 3.988f, glyph.scaleX, 0.001f)
        assertEquals(glyph.scaleX, glyph.scaleY, 0.0001f)

        val pixelsPerStaffSpace = transformer.toPixels(StaffSpace.ONE).value
        assertEquals(pixelsPerStaffSpace * 0.24f * glyph.scaleX, glyph.bounds.width.value, 0.001f)
        assertEquals(pixelsPerStaffSpace * 20f, glyph.bounds.height.value, 0.001f)
    }

    @Test
    fun braceAlternateSelectionFollowsGeometricSpan() {
        val transformer = CoordinateTransformer.default()

        assertEquals(SmuflGlyphs.braceSmall.codepoint, renderBrace(4f, transformer).glyph.codepoint)
        assertEquals(SmuflGlyphs.brace.codepoint, renderBrace(8f, transformer).glyph.codepoint)
        assertEquals(SmuflGlyphs.braceLarge.codepoint, renderBrace(12f, transformer).glyph.codepoint)
        assertEquals(SmuflGlyphs.braceLarger.codepoint, renderBrace(20f, transformer).glyph.codepoint)
        assertEquals(SmuflGlyphs.braceFlat.codepoint, renderBrace(40f, transformer).glyph.codepoint)
    }

    private fun renderBrace(spanSS: Float, transformer: CoordinateTransformer): DrawGlyph {
        val bracket = StaffHeaderLayoutComputer.PlacedBracket(
            style = BracketStyle.BRACE,
            x = StaffSpace(8f),
            topY = StaffSpace(0f),
            bottomY = StaffSpace(spanSS),
            thickness = StaffSpace(0.5f),
            staffRange = StaffIndexRange(0, 1),
            depth = 0,
            sourceId = "brace"
        )

        val element = with(font) {
            val renderer = SystemRenderer(RenderLayoutConfig.DEFAULT, transformer)
            renderer.renderHeaderBracket(bracket) { RenderElementId.global(1) }
        }.single()
        return element.commands.single() as DrawGlyph
    }
}
