package com.mecon.renderer.elements

import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.TimeCode
import com.mecon.api.storage.tracks.Clef
import com.mecon.renderer.geometry.GlyphGeometry
import com.mecon.renderer.snapshot.loadFont
import kotlin.test.Test
import kotlin.test.assertEquals

class KeySignatureElementPositionTest {
    @Test
    fun trebleSharpsConvertCalculatedUpwardPositionsToRendererY() {
        val font = loadFont() ?: return
        with(font) {
            val element = KeySignatureElement.create(
                time = TimeCode.ZERO,
                staffIndex = 0,
                keySignature = KeySignature.majorByFifths(7),
                isInitial = true,
                clef = Clef.TREBLE,
            )

            assertEquals(
                listOf(-2f, -0.5f, -2.5f, -1f, 0.5f, -1.5f, 0f),
                element.geometryList.filterIsInstance<GlyphGeometry>().map { it.position.y.value },
            )
        }
    }

    @Test
    fun bassFlatsUseBassClefOctaveInsteadOfTrebleOrFixedShiftCoordinates() {
        val font = loadFont() ?: return
        with(font) {
            val element = KeySignatureElement.create(
                time = TimeCode.ZERO,
                staffIndex = 0,
                keySignature = KeySignature.majorByFifths(-7),
                isInitial = true,
                clef = Clef.BASS,
            )

            assertEquals(
                listOf(1f, -0.5f, 1.5f, 0f, 2f, 0.5f, 2.5f),
                element.geometryList.filterIsInstance<GlyphGeometry>().map { it.position.y.value },
            )
        }
    }
}
