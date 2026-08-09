package com.mecon.desktop.ui.views

import androidx.compose.ui.geometry.Offset
import com.mecon.api.storage.PageArrangement
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.render.RenderPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The renderer deliberately leaves inter-page arrangement to the UI (so the renderer stays
 * reusable for e.g. PDF export). That makes [designToGlobal] / [globalToDesign] the seam that
 * keeps paginated hit testing consistent with what is drawn: they MUST be exact inverses, or a
 * click resolves to the wrong place. These tests pin that invariant for both arrangements.
 */
class PageMappingTest {

    // Two pages, each 200×300 design px. Globally the layout stacks pages vertically with a
    // 20px gap, so page 1's content sits at global Y 320 (contentOffsetY). X is never shifted
    // during slicing, so page-local X == global X.
    private val pages = listOf(
        RenderPage(0, Pixels(200f), Pixels(300f), Pixels(0f), emptyList()),
        RenderPage(1, Pixels(200f), Pixels(300f), Pixels(320f), emptyList()),
    )

    @Test
    fun pageSlotOffsets_vertical_stacksTopToBottom() {
        val slots = pageSlotOffsets(pages, PageArrangement.VERTICAL)
        assertEquals(Offset(0f, 0f), slots[0])
        // y advances by page height + the inter-page gap.
        assertEquals(Offset(0f, 300f + PAGE_GAP_DESIGN), slots[1])
    }

    @Test
    fun pageSlotOffsets_horizontal_laysLeftToRight() {
        val slots = pageSlotOffsets(pages, PageArrangement.HORIZONTAL)
        assertEquals(Offset(0f, 0f), slots[0])
        assertEquals(Offset(200f + PAGE_GAP_DESIGN, 0f), slots[1])
    }

    @Test
    fun globalToDesign_thenDesignToGlobal_isIdentity_vertical() {
        assertRoundTrip(PageArrangement.VERTICAL, globalX = 50f, globalY = 400f) // inside page 1
        assertRoundTrip(PageArrangement.VERTICAL, globalX = 10f, globalY = 10f)  // inside page 0
    }

    @Test
    fun globalToDesign_thenDesignToGlobal_isIdentity_horizontal() {
        assertRoundTrip(PageArrangement.HORIZONTAL, globalX = 50f, globalY = 400f)
        assertRoundTrip(PageArrangement.HORIZONTAL, globalX = 10f, globalY = 10f)
    }

    @Test
    fun designToGlobal_outsideEveryPage_returnsNull() {
        val slots = pageSlotOffsets(pages, PageArrangement.HORIZONTAL)
        // x past the right edge of the last horizontal page slot (224 + 200 = 424).
        assertNull(designToGlobal(Offset(500f, 50f), pages, slots))
    }

    @Test
    fun globalToDesign_inInterPageGap_returnsNull() {
        val slots = pageSlotOffsets(pages, PageArrangement.VERTICAL)
        // Global Y 310 falls in the gap between page 0 (0..300) and page 1 (320..620).
        assertNull(globalToDesign(globalX = 10f, globalY = 310f, pages = pages, slots = slots))
    }

    private fun assertRoundTrip(arrangement: PageArrangement, globalX: Float, globalY: Float) {
        val slots = pageSlotOffsets(pages, arrangement)
        val design = globalToDesign(globalX, globalY, pages, slots)
        assertTrue(design != null, "expected a page to own the global point")
        val back = designToGlobal(design, pages, slots)
        assertTrue(back != null, "expected the design point to map back onto a page")
        assertEquals(globalX, back.x.value, 1e-3f)
        assertEquals(globalY, back.y.value, 1e-3f)
    }
}
