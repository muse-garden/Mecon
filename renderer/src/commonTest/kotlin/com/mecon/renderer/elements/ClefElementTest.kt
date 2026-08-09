package com.mecon.renderer.elements

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.storage.tracks.Clef
import com.mecon.renderer.geometry.GlyphGeometry
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.RenderConstants
import com.mecon.renderer.smufl.SmuflGlyphs
import kotlin.test.Test
import kotlin.test.assertEquals

class ClefElementTest {

    @Test
    fun minimumWidthUsesUnscaledGlyphBoundsForScaledClefChanges() {
        val scaledGeometry = GlyphGeometry(
            glyph = SmuflGlyphs.gClef,
            position = RelativePoint(StaffSpace.ZERO, StaffSpace.ZERO),
            bounds = RelativeRect(
                origin = RelativePoint(StaffSpace.ZERO, StaffSpace.ZERO),
                width = StaffSpace(1.6f),
                height = StaffSpace.ONE
            ),
            scale = RenderConstants.INLINE_CLEF_CHANGE_SCALE
        )

        val change = ClefElement(
            time = TimeCode.of(1, Fraction.ZERO),
            staffIndex = 0,
            clef = Clef.TREBLE,
            isInitial = false,
            geometryList = listOf(scaledGeometry)
        )

        assertEquals(StaffSpace(2.0f), change.minimumWidth)
    }

    @Test
    fun minimumWidthFallsBackToReservedConstantsWithoutGeometry() {
        val initial = ClefElement(
            time = TimeCode.of(1, Fraction.ZERO),
            staffIndex = 0,
            clef = Clef.TREBLE,
            isInitial = true,
            geometryList = emptyList()
        )
        val change = initial.copy(isInitial = false)

        assertEquals(RenderConstants.INITIAL_CLEF_WIDTH, initial.minimumWidth)
        assertEquals(RenderConstants.CLEF_CHANGE_WIDTH, change.minimumWidth)
    }
}
