package com.mecon.renderer.frozen

import com.mecon.renderer.render.DrawGlyph
import com.mecon.renderer.snapshot.loadFont
import com.mecon.renderer.snapshot.paginatedScoreFiles
import com.mecon.renderer.snapshot.renderScoreFile
import com.mecon.renderer.snapshot.scoreFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the frozen-geometry pipeline: a real [com.mecon.renderer.render.RenderResult] projects to
 * a [FrozenScoreBundle] that (a) carries the drawable elements a viewer needs and (b) survives a JSON
 * round-trip byte-for-value. This is the geometry the `.mecon` container ships for the lightweight web
 * viewer, so its stability is a format guarantee.
 */
class FrozenScoreProjectorTest {

    @Test
    fun continuousScoreProjectsToSingleSurfaceAndRoundTrips() {
        val font = loadFont() ?: return // skip when font assets are unavailable
        val scoreFile = scoreFiles().firstOrNull { it.name.startsWith("01_") } ?: return
        val result = renderScoreFile(scoreFile, font)

        val bundle = FrozenScoreProjector.project(result, engineVersion = "test", fontFingerprint = "Bravura-1.0")

        assertTrue(!bundle.paginated, "continuous score should not be paginated")
        assertEquals(1, bundle.surfaces.size, "continuous score should have exactly one surface")
        val elements = bundle.surfaces.single().elements
        assertTrue(elements.isNotEmpty(), "surface should carry render elements")
        // A real engraving replays glyphs (noteheads / clefs / accidentals).
        assertTrue(
            elements.any { el -> el.commands.any { it is DrawGlyph } },
            "frozen surface should contain at least one glyph draw command",
        )
        // No transient selection/highlight leaks into the frozen picture.
        assertTrue(elements.none { it.selected || it.highlighted }, "frozen elements must be selection-free")

        val decoded = FrozenScoreCodec.decode(FrozenScoreCodec.encode(bundle))
        assertEquals(bundle, decoded, "frozen bundle must survive a JSON round-trip unchanged")
    }

    @Test
    fun paginatedScoreProjectsOneSurfacePerPage() {
        val font = loadFont() ?: return
        val scoreFile = paginatedScoreFiles().firstOrNull() ?: return
        val result = renderScoreFile(scoreFile, font)
        if (!result.paginated) return // fixture rendered continuous on this config; nothing to assert

        val bundle = FrozenScoreProjector.project(result, engineVersion = "test", fontFingerprint = "Bravura-1.0")

        assertTrue(bundle.paginated, "paginated score should be marked paginated")
        assertEquals(result.pages.size, bundle.surfaces.size, "one frozen surface per rendered page")
        assertTrue(bundle.surfaces.all { it.elements.isNotEmpty() }, "each page surface should carry elements")

        val decoded = FrozenScoreCodec.decode(FrozenScoreCodec.encode(bundle))
        assertEquals(bundle, decoded)
    }
}
