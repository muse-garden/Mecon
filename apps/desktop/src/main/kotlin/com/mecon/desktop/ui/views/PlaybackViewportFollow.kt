package com.mecon.desktop.ui.views

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

private const val PIANO_ROLL_PLAYHEAD_ANCHOR = 0.33f
private const val SCORE_PLAYHEAD_X_ANCHOR = 0.33f

internal fun scorePlayheadFollowOffset(
    currentOffset: Offset,
    playheadTop: Offset,
    playheadBottom: Offset,
    viewportSize: Size,
    scale: Float,
    density: Float,
): Offset {
    val screenScale = scale * density
    val screenX = playheadTop.x * screenScale + currentOffset.x
    val screenTop = playheadTop.y * screenScale + currentOffset.y
    val screenBottom = playheadBottom.y * screenScale + currentOffset.y

    val nextX = if (screenX < 0f || screenX > viewportSize.width) {
        viewportSize.width * SCORE_PLAYHEAD_X_ANCHOR - playheadTop.x * screenScale
    } else {
        currentOffset.x
    }

    val nextY = if (screenTop < 0f || screenBottom > viewportSize.height) {
        val playheadHeight = (playheadBottom.y - playheadTop.y) * screenScale
        val targetTop = ((viewportSize.height - playheadHeight) / 2f).coerceAtLeast(0f)
        targetTop - playheadTop.y * screenScale
    } else {
        currentOffset.y
    }

    return Offset(nextX, nextY)
}

internal fun pianoRollFollowOffset(
    playheadContentPx: Float,
    viewportExtentPx: Float,
    keyboardExtentPx: Float,
): Float {
    val usableExtent = (viewportExtentPx - keyboardExtentPx).coerceAtLeast(0f)
    return (playheadContentPx - usableExtent * PIANO_ROLL_PLAYHEAD_ANCHOR).coerceAtLeast(0f)
}
