package com.mecon.desktop.ui.views.pianoroll

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

enum class PianoRollOrientation {
    HORIZONTAL,
    VERTICAL
}

class PianoRollState {
    var orientation by mutableStateOf(PianoRollOrientation.HORIZONTAL)
    var keyboardLayout by mutableStateOf<KeyboardLayout>(NormalPianoLayout)
    
    var showNoteNames by mutableStateOf(true)
    var useRelativePitch by mutableStateOf(false)
    
    // Time scaling (pixels per quarter note, or per tick)
    // We'll map 1 quarter note = 480 ticks. Let's say 1 quarter note = 60 pixels by default.
    var scaleX by mutableStateOf(60f) 
    
    // Pitch scaling (pixels per semitone)
    var scaleY by mutableStateOf(20f)
    
    // Viewport offsets
    var offsetX by mutableStateOf(0f)
    
    // Default Y offset: center around middle C (MIDI 60). 
    // Midi range 0..127. 60 is roughly in the middle.
    // 127 total semitones, 127 * 20 = 2540 pixels. Middle C is at (127-60)*20 = 1340.
    var offsetY by mutableStateOf(1000f)
    
    // Keyboard dimension
    val keyboardBasis: Float = 60f
    
    fun onDrag(dragAmount: Offset, viewportSize: Size) {
        val newOffsetX = offsetX - dragAmount.x
        val newOffsetY = offsetY + dragAmount.y
        
        val maxOffsetY = (128 * scaleY) - viewportSize.height
        val nextOffsetX = newOffsetX.coerceAtLeast(0f)
        val nextOffsetY = newOffsetY.coerceIn(0f, maxOffsetY.coerceAtLeast(0f))
        
        offsetX = nextOffsetX
        offsetY = nextOffsetY
    }

    fun onZoom(zoomFactor: Float, centroid: Offset, isXAxis: Boolean, isYAxis: Boolean) {
        if (isXAxis) {
            val oldScale = scaleX
            scaleX = (scaleX * zoomFactor).coerceIn(10f, 300f)
            val ratio = scaleX / oldScale
            offsetX = (offsetX + centroid.x) * ratio - centroid.x
            offsetX = offsetX.coerceAtLeast(0f)
        }
        if (isYAxis) {
            val oldScale = scaleY
            scaleY = (scaleY * zoomFactor).coerceIn(10f, 50f)
            val ratio = scaleY / oldScale
            val newOffsetY = (offsetY + centroid.y) * ratio - centroid.y
            offsetY = newOffsetY.coerceAtLeast(0f)
            // Note: max bounds recalculation is tricky during zoom without viewportSize, 
            // so we'll let the next drag or frame clamp it perfectly if needed, or we just rely on the 0f bound.
        }
    }
}

val LocalPianoRollState = staticCompositionLocalOf<PianoRollState> { error("No PianoRollState provided") }
