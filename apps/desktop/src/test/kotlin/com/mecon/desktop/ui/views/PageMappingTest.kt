package com.mecon.desktop.ui.views

import androidx.compose.ui.geometry.Offset
import com.mecon.api.storage.PageArrangement
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.geometry.ScaleFactor
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.render.CoordinateTransformer
import com.mecon.renderer.render.RenderPage
import com.mecon.renderer.render.RenderResult
import com.mecon.renderer.render.spatial.HierarchicalSpatialIndex
import com.mecon.renderer.render.spatial.StaffRegion
import com.mecon.renderer.render.spatial.SystemNode
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

    @Test
    fun nearestSystemUsesPageColumnWhenHorizontalPagesShareDisplayedRow() {
        val index = HierarchicalSpatialIndex().apply {
            addSystem(system(index = 0, centerY = 10f))
            addSystem(system(index = 1, centerY = 330f))
        }
        val result = RenderResult.EMPTY.copy(
            spatialIndex = index,
            transformerSnapshot = CoordinateTransformer(scale = ScaleFactor(1f)),
        )
        val slots = pageSlotOffsets(pages, PageArrangement.HORIZONTAL)

        assertEquals(
            1,
            nearestDisplayedSystemByStaffCore(
                result = result,
                raw = Offset(300f, 10f),
                offset = Offset.Zero,
                scale = 1f,
                density = 1f,
                paginated = true,
                pages = pages,
                slots = slots,
            ),
        )
    }

    @Test
    fun annotationSystemUsesWholeRowBeforeRetargetingUpward() {
        val index = HierarchicalSpatialIndex().apply {
            addSystem(system(index = 0, centerY = 10f, topY = -10f, bottomY = 40f))
            addSystem(system(index = 1, centerY = 100f, topY = 45f, bottomY = 130f))
        }
        val result = RenderResult.EMPTY.copy(
            spatialIndex = index,
            transformerSnapshot = CoordinateTransformer(scale = ScaleFactor(1f)),
        )
        val pointerInsideSecondSystemTop = Offset(50f, 50f)

        assertEquals(
            0,
            nearestDisplayedSystemByStaffCore(
                result, pointerInsideSecondSystemTop, Offset.Zero, 1f, 1f,
                false, emptyList(), emptyList(),
            ),
            "the old core-distance rule would already jump to the row above",
        )
        assertEquals(
            1,
            nearestDisplayedSystemByFullRange(
                result, pointerInsideSecondSystemTop, Offset.Zero, 1f, 1f,
                false, emptyList(), emptyList(),
            ),
            "an annotation endpoint stays with the row whose full range contains the pointer",
        )
    }

    @Test
    fun annotationSystemKeepsCurrentRowWhenFullRangesOverlap() {
        val index = HierarchicalSpatialIndex().apply {
            addSystem(system(index = 0, centerY = 10f, topY = -10f, bottomY = 60f))
            addSystem(system(index = 1, centerY = 100f, topY = 45f, bottomY = 130f))
        }
        val result = RenderResult.EMPTY.copy(
            spatialIndex = index,
            transformerSnapshot = CoordinateTransformer(scale = ScaleFactor(1f)),
        )

        assertEquals(
            1,
            nearestDisplayedSystemByFullRange(
                result = result,
                raw = Offset(50f, 50f),
                offset = Offset.Zero,
                scale = 1f,
                density = 1f,
                paginated = false,
                pages = emptyList(),
                slots = emptyList(),
                preferredSystemIndex = 1,
            ),
            "overlapping full ranges must keep the current target row stable",
        )
    }

    @Test
    fun annotationSystemDoesNotJumpAgainWhilePointerIsBetweenRows() {
        val index = HierarchicalSpatialIndex().apply {
            addSystem(system(index = 0, centerY = 15f, topY = 0f, bottomY = 40f))
            addSystem(system(index = 1, centerY = 80f, topY = 60f, bottomY = 110f))
        }
        val result = RenderResult.EMPTY.copy(
            spatialIndex = index,
            transformerSnapshot = CoordinateTransformer(scale = ScaleFactor(1f)),
        )

        assertEquals(
            1,
            nearestDisplayedSystemByFullRange(
                result = result,
                raw = Offset(50f, 41f),
                offset = Offset.Zero,
                scale = 1f,
                density = 1f,
                paginated = false,
                pages = emptyList(),
                slots = emptyList(),
                preferredSystemIndex = 1,
            ),
            "the gap above the current row must not choose the nearer upper row",
        )
        assertEquals(
            0,
            nearestDisplayedSystemByFullRange(
                result = result,
                raw = Offset(50f, 35f),
                offset = Offset.Zero,
                scale = 1f,
                density = 1f,
                paginated = false,
                pages = emptyList(),
                slots = emptyList(),
                preferredSystemIndex = 1,
            ),
            "the target changes only after entering the upper row's full range",
        )
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

    private fun system(
        index: Int,
        centerY: Float,
        topY: Float = centerY - 6f,
        bottomY: Float = centerY + 6f,
    ) = SystemNode(
        systemIndex = index,
        measureCount = 1,
        staffRegions = listOf(
            StaffRegion(
                staffIndex = 0,
                centerY = StaffSpace(centerY),
                topY = StaffSpace(centerY - 4f),
                bottomY = StaffSpace(centerY + 4f),
            )
        ),
        topY = StaffSpace(topY),
        bottomY = StaffSpace(bottomY),
        startX = StaffSpace.ZERO,
    )
}
