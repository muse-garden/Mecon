package com.mecon.desktop.ui.views.drag

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange

/**
 * Not an interaction family: panning moves the viewport, never the score. It is the fallback when
 * no handle and no marquee claimed the gesture, and it is unavailable in embedded editors whose
 * horizontal offset is owned by a sibling timeline.
 *
 * Panning by hand also opts out of playback following until the gesture ends.
 */
internal class ViewportPanDragHandler : ScoreDragHandler {

    fun start(context: ScoreDragContext): ScoreDragHandler? =
        if (context.mode.panEnabled) this else null

    override fun drag(context: ScoreDragContext, change: PointerInputChange, dragAmount: Offset) {
        context.followPlayback = false
        context.offset += dragAmount
    }

    override fun end(context: ScoreDragContext) {
        context.followPlayback = true
    }

    override fun cancel(context: ScoreDragContext) {
        context.followPlayback = true
    }
}
