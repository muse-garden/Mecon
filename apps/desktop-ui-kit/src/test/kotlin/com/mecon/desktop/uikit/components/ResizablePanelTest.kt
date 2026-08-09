package com.mecon.desktop.uikit.components

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class ResizablePanelTest {
    @Test
    fun `deferred resize applies pointer movement once at drag end`() {
        assertEquals(
            450.dp,
            deferredResizeValue(400.dp, 50.dp, 240.dp, 720.dp, inverted = false),
        )
        assertEquals(
            450.dp,
            deferredResizeValue(400.dp, (-50).dp, 240.dp, 720.dp, inverted = true),
        )
    }

    @Test
    fun `deferred resize clamps only the committed target`() {
        assertEquals(
            240.dp,
            deferredResizeValue(400.dp, 500.dp, 240.dp, 720.dp, inverted = true),
        )
        assertEquals(
            720.dp,
            deferredResizeValue(400.dp, (-500).dp, 240.dp, 720.dp, inverted = true),
        )
    }
}
