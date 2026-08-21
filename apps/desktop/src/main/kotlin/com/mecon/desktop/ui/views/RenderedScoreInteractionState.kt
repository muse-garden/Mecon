package com.mecon.desktop.ui.views

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.mecon.renderer.render.edit.GhostClef
import com.mecon.renderer.render.edit.GhostExpressionSpan
import com.mecon.renderer.render.edit.GhostKeySignature
import com.mecon.renderer.render.edit.GhostNote
import com.mecon.renderer.render.edit.GhostTimeSignature

/** Viewport and pointer-modifier state; independent of score content. */
internal class RenderedScoreViewportState {
    val scale = mutableStateOf(1f)
    val offset = mutableStateOf(Offset.Zero)
    val viewportSize = mutableStateOf(Size.Zero)
    val followPlayback = mutableStateOf(true)
    val shiftHeld = mutableStateOf(false)
    val ctrlHeld = mutableStateOf(false)
    val marqueeRect = mutableStateOf<Rect?>(null)
}

/** Ghost geometry owned only by insertion tools. */
internal class RenderedScoreInsertionPreviewState {
    val note = mutableStateOf<GhostNote?>(null)
    val clef = mutableStateOf<GhostClef?>(null)
    val timeSignature = mutableStateOf<GhostTimeSignature?>(null)
    val keySignature = mutableStateOf<GhostKeySignature?>(null)
    val expressionSpan = mutableStateOf<GhostExpressionSpan?>(null)
}
