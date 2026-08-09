package com.mecon.desktop.ui.views

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackViewportFollowTest {
    @Test
    fun visibleScorePlayhead_preservesCurrentOffset() {
        val offset = scorePlayheadFollowOffset(
            currentOffset = Offset(-100f, -120f),
            playheadTop = Offset(300f, 200f),
            playheadBottom = Offset(300f, 300f),
            viewportSize = Size(800f, 700f),
            scale = 1f,
            density = 1f,
        )

        assertEquals(-100f, offset.x)
        assertEquals(-120f, offset.y)
    }

    @Test
    fun scorePlayheadPastRightEdge_realignsXOnly() {
        val offset = scorePlayheadFollowOffset(
            currentOffset = Offset(-50f, -120f),
            playheadTop = Offset(900f, 200f),
            playheadBottom = Offset(900f, 300f),
            viewportSize = Size(800f, 700f),
            scale = 1f,
            density = 1f,
        )

        assertEquals(-636f, offset.x)
        assertEquals(-120f, offset.y)
    }

    @Test
    fun scorePlayheadLinePastBottomEdge_realignsCurrentSystemYOnly() {
        val offset = scorePlayheadFollowOffset(
            currentOffset = Offset(-100f, -250f),
            playheadTop = Offset(300f, 900f),
            playheadBottom = Offset(300f, 1000f),
            viewportSize = Size(800f, 700f),
            scale = 1f,
            density = 1f,
        )

        assertEquals(-100f, offset.x)
        assertEquals(-600f, offset.y)
    }

    @Test
    fun pianoRollFollow_keepsStartAtZeroThenAnchorsPlayhead() {
        assertEquals(0f, pianoRollFollowOffset(40f, 600f, 60f))
        assertEquals(321.8f, pianoRollFollowOffset(500f, 600f, 60f), 0.001f)
    }
}
