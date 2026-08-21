package com.mecon.desktop.ui.views

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import com.mecon.desktop.input.ShortcutAction
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.render.RenderPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScoreViewportNavigationTest {
    @Test
    fun scoreSystemNavigation_hasRebindablePageKeyDefaults() {
        assertEquals(Key.PageUp.keyCode, ShortcutAction.SCORE_SYSTEM_UP.default.keyCode)
        assertEquals(Key.PageDown.keyCode, ShortcutAction.SCORE_SYSTEM_DOWN.default.keyCode)
    }

    @Test
    fun insertionPan_startsOnlyBesideSystemOrOutsidePage() {
        val systems = listOf(system(0, 1, Rect(100f, 100f, 500f, 200f)))
        assertTrue(canStartInsertionPan(
            raw = Offset(50f, 150f),
            offset = Offset.Zero,
            scale = 1f,
            density = 1f,
            paginated = false,
            pages = emptyList(),
            pageSlots = emptyList(),
            systems = systems,
        ))
        assertFalse(canStartInsertionPan(
            raw = Offset(250f, 150f),
            offset = Offset.Zero,
            scale = 1f,
            density = 1f,
            paginated = false,
            pages = emptyList(),
            pageSlots = emptyList(),
            systems = systems,
        ))
        assertFalse(canStartInsertionPan(
            raw = Offset(50f, 50f),
            offset = Offset.Zero,
            scale = 1f,
            density = 1f,
            paginated = false,
            pages = emptyList(),
            pageSlots = emptyList(),
            systems = systems,
        ))

        val page = RenderPage(0, Pixels(600f), Pixels(800f), Pixels.ZERO, emptyList())
        assertTrue(canStartInsertionPan(
            raw = Offset(650f, 400f),
            offset = Offset.Zero,
            scale = 1f,
            density = 1f,
            paginated = true,
            pages = listOf(page),
            pageSlots = listOf(Offset.Zero),
            systems = systems,
        ))
    }

    @Test
    fun pageDown_preservesCurrentSystemAnchor() {
        val systems = listOf(
            system(0, 1, Rect(100f, 100f, 500f, 160f)),
            system(1, 5, Rect(100f, 300f, 500f, 360f)),
        )
        val next = viewportOffsetAfterSystemMove(
            systems = systems,
            currentOffset = Offset.Zero,
            scale = 1f,
            density = 1f,
            viewportSize = Size(600f, 240f),
            delta = 1,
        )
        assertEquals(Offset(0f, -200f), next)
    }

    @Test
    fun pageDown_canJumpToSystemOnHorizontalNextPage() {
        val systems = listOf(
            system(0, 1, Rect(100f, 100f, 500f, 160f)),
            system(1, 5, Rect(724f, 100f, 1124f, 160f)),
        )
        val next = viewportOffsetAfterSystemMove(
            systems = systems,
            currentOffset = Offset.Zero,
            scale = 1f,
            density = 1f,
            viewportSize = Size(600f, 240f),
            delta = 1,
        )
        assertEquals(Offset(-624f, 0f), next)
    }

    @Test
    fun wrappedInsertion_revealsNewSystemStartWithoutCursorAlignment() {
        val next = revealSystemStartOffset(
            system = system(2, 9, Rect(100f, 500f, 500f, 560f)),
            currentOffset = Offset.Zero,
            scale = 1f,
            density = 1f,
            viewportSize = Size(600f, 300f),
        )
        assertEquals(Offset(0f, -284f), next)
    }

    @Test
    fun regularInsertion_ignoresSmallPostEngravingMovement() {
        val next = softAlignDesignPointToCursorOffset(
            designPoint = Offset(100f, 200f),
            cursorRaw = Offset(112f, 220f),
            currentOffset = Offset.Zero,
            scale = 1f,
            density = 1f,
        )
        assertEquals(Offset.Zero, next)
    }

    @Test
    fun regularInsertion_correctsOnlyMovementBeyondDeadZone() {
        val next = softAlignDesignPointToCursorOffset(
            designPoint = Offset(100f, 200f),
            cursorRaw = Offset(400f, 300f),
            currentOffset = Offset.Zero,
            scale = 2f,
            density = 1f,
        )
        assertEquals(Offset(186f, -76f), next)
    }

    private fun system(index: Int, firstMeasure: Int, bounds: Rect) =
        DisplayedScoreSystem(index, firstMeasure, bounds)
}
