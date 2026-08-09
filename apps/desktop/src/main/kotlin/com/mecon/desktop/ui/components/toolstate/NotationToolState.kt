package com.mecon.desktop.ui.components.toolstate

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.storage.tracks.Clef

/** State owned by clef, key-signature and time-signature tools. */
class NotationToolState {
    var selectedClef by mutableStateOf(Clef.TREBLE)
    var clefSectionExpanded by mutableStateOf(true)
    var selectedTimeSignature by mutableStateOf(TimeSignature.COMMON)
    var timeSectionExpanded by mutableStateOf(true)
    var selectedKeySignature by mutableStateOf(KeySignature.C_MAJOR)
    var keySectionExpanded by mutableStateOf(true)

    fun reset() {
        selectedClef = Clef.TREBLE
        clefSectionExpanded = true
        selectedTimeSignature = TimeSignature.COMMON
        timeSectionExpanded = true
        selectedKeySignature = KeySignature.C_MAJOR
        keySectionExpanded = true
    }
}
