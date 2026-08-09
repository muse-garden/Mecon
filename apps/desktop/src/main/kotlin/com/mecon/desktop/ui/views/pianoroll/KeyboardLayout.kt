package com.mecon.desktop.ui.views.pianoroll

import androidx.compose.ui.graphics.Color
import com.mecon.api.primitive.Pitch

interface KeyboardLayout {
    fun isBlackKey(pitch: Pitch): Boolean
    fun getKeyColor(pitch: Pitch): Color
}

object NormalPianoLayout : KeyboardLayout {
    override fun isBlackKey(pitch: Pitch): Boolean {
        // C=0, C#=1, D=2, D#=3, E=4, F=5, F#=6, G=7, G#=8, A=9, A#=10, B=11
        // Black keys: 1, 3, 6, 8, 10
        val pc = pitch.pitchClass.value
        return pc == 1 || pc == 3 || pc == 6 || pc == 8 || pc == 10
    }

    override fun getKeyColor(pitch: Pitch): Color {
        return if (isBlackKey(pitch)) Color(0xFF222222) else Color(0xFFEEEEEE)
    }
}

object ChromaticLayout : KeyboardLayout {
    override fun isBlackKey(pitch: Pitch): Boolean {
        // In chromatic layout, all keys are visually the same
        return false
    }

    override fun getKeyColor(pitch: Pitch): Color {
        return Color(0xFFDDDDDD)
    }
}

/**
 * Custom Scale Layout where users define an arbitrary pattern for black/white keys in an octave.
 * @param isBlackKeyMap An array of 12 booleans where true means the pitch class is a black key.
 */
data class CustomScaleLayout(val isBlackKeyMap: BooleanArray) : KeyboardLayout {
    init {
        require(isBlackKeyMap.size == 12) { "isBlackKeyMap must hold exactly 12 values corresponding to pitch classes 0..11." }
    }

    override fun isBlackKey(pitch: Pitch): Boolean {
        return isBlackKeyMap[pitch.pitchClass.value]
    }

    override fun getKeyColor(pitch: Pitch): Color {
        return if (isBlackKey(pitch)) Color(0xFF222222) else Color(0xFFEEEEEE)
    }
    
    companion object {
        // Predefined examples
        val WholeTone = CustomScaleLayout(
            booleanArrayOf(
                false, true, false, true, false, true, 
                false, true, false, true, false, true
            )
        )
    }
}
