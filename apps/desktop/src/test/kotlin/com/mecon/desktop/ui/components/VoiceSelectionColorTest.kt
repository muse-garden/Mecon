package com.mecon.desktop.ui.components

import com.mecon.desktop.uikit.theme.MeconColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class VoiceSelectionColorTest {

    @Test
    fun voiceColors_cycleAfterFourVoices() {
        assertNotEquals(MeconColors.voiceSelectionColor(1), MeconColors.voiceSelectionColor(2))
        assertNotEquals(MeconColors.voiceSelectionColor(2), MeconColors.voiceSelectionColor(3))
        assertNotEquals(MeconColors.voiceSelectionColor(3), MeconColors.voiceSelectionColor(4))
        assertEquals(MeconColors.voiceSelectionColor(1), MeconColors.voiceSelectionColor(5))
        assertEquals(MeconColors.voiceSelectionColor(2), MeconColors.voiceSelectionColor(6))
        assertEquals(MeconColors.voiceToolbarColor(2), MeconColors.voiceToolbarColor(6))
        assertNotEquals(MeconColors.voiceToolbarColor(2), MeconColors.voiceSelectionColor(2))
    }
}
