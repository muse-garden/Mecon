package com.mecon.desktop.input

import com.mecon.input.PerformanceInputEvent
import javax.sound.midi.ShortMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class JvmMidiInputServiceTest {
    @Test
    fun `velocity zero note-on normalizes to note-off`() {
        val message = ShortMessage().apply {
            setMessage(ShortMessage.NOTE_ON, 2, 64, 0)
        }
        val event = normalizeMidiMessage("kbd", message, 99L, acceptedChannel = null, velocityThreshold = 1)

        assertIs<PerformanceInputEvent.NoteOff>(event)
        assertEquals(64, event.key)
        assertEquals(99L, event.atNanos)
    }

    @Test
    fun `channel and velocity filters are applied before dispatch`() {
        val message = ShortMessage().apply {
            setMessage(ShortMessage.NOTE_ON, 2, 60, 12)
        }

        assertNull(normalizeMidiMessage("kbd", message, 0L, acceptedChannel = 1, velocityThreshold = 1))
        assertNull(normalizeMidiMessage("kbd", message, 0L, acceptedChannel = 2, velocityThreshold = 20))
        assertIs<PerformanceInputEvent.NoteOn>(
            normalizeMidiMessage("kbd", message, 0L, acceptedChannel = 2, velocityThreshold = 12),
        )
    }
}
