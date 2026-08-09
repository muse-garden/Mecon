package com.mecon.desktop.ui.views

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.mecon.api.primitive.EventId
import com.mecon.desktop.ui.views.pianoroll.PianoRollNote
import com.mecon.desktop.ui.views.pianoroll.PianoRollOrientation
import com.mecon.desktop.ui.views.pianoroll.PianoRollState
import kotlin.test.Test
import kotlin.test.assertEquals

class PianoRollSelectionTest {
    @Test
    fun horizontalHitTestPrefersNoteBeforeGridInsertion() {
        val state = PianoRollState().apply {
            orientation = PianoRollOrientation.HORIZONTAL
            scaleX = 128f
            scaleY = 10f
            offsetX = 0f
            offsetY = 600f
        }
        val note = PianoRollNote(
            midi = 60,
            onsetTicks = 0,
            durationTicks = 1024,
            isGrace = false,
            sourceEventId = EventId("pitch"),
            voiceEventIds = setOf(EventId("voice")),
            scoreOnset = null,
        )
        val viewport = Size(800f, 300f)
        val x = state.keyboardBasis + 10f
        val y = viewport.height - (60 * state.scaleY - state.offsetY) - state.scaleY / 2f

        val hit = state.noteAt(
            position = Offset(x, y),
            viewport = viewport,
            notes = listOf(note),
            timelineOffset = 0f,
            tickToPx = state.scaleX / TICKS_PER_QUARTER,
        )

        assertEquals(note, hit)
    }
}
