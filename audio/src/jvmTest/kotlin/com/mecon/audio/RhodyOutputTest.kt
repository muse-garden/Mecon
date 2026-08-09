package com.mecon.audio

import javax.sound.midi.MidiMessage
import javax.sound.midi.Receiver
import javax.sound.midi.ShortMessage
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.math.pow

class RhodyOutputTest {
    @Test
    fun `default library setting accepts rhody and ms basic`() {
        assertEquals(DefaultTimbreLibrary.RHODY, DefaultTimbreLibrary.parse("rhody"))
        assertEquals(DefaultTimbreLibrary.MS_BASIC, DefaultTimbreLibrary.parse("ms basic"))
        assertEquals(DefaultTimbreLibrary.MS_BASIC, DefaultTimbreLibrary.parse("MS_BASIC"))
        assertNull(DefaultTimbreLibrary.parse("unknown"))
        assertEquals(DefaultTimbreLibrary.RHODY, DefaultTimbreLibrary.fromSystem(null, null))
    }

    @Test
    fun `catalog falls back for unimplemented instruments and percussion`() {
        assertEquals(RhodyPatch(0, 0), RhodyProgramCatalog.resolve(channel = 0, bank = 0, program = 73))
        assertEquals(RhodyPatch(2, 0), RhodyProgramCatalog.resolve(channel = 0, bank = 0, program = 0))
        assertNull(RhodyProgramCatalog.resolve(channel = 0, bank = 0, program = 40))
        assertNull(RhodyProgramCatalog.resolve(channel = 9, bank = 0, program = 0))
        assertNull(RhodyProgramCatalog.resolve(channel = 0, bank = 1, program = 73))
    }

    @Test
    fun `implemented program receives pitch velocity and note duration boundary`() {
        val rhody = RecordingRhodyOutput()
        val fallback = RecordingReceiver()
        val receiver = RhodyMidiReceiver(rhody, fallback)

        receiver.send(programChange(channel = 2, program = 73), -1L)
        receiver.send(noteOn(channel = 2, midi = 69, velocity = 96), -1L)
        receiver.send(noteOff(channel = 2, midi = 69), -1L)

        assertEquals(listOf("patch:2:0:0", "on:2:69:96", "off:2:69"), rhody.events)
        assertEquals(1, fallback.messages.size) // program change only, kept warm for fallback
        assertEquals(440.0, RhodyOutput.midiToHz(69), 1e-9)
    }

    @Test
    fun `unimplemented program sends notes entirely to ms basic`() {
        val rhody = RecordingRhodyOutput()
        val fallback = RecordingReceiver()
        val receiver = RhodyMidiReceiver(rhody, fallback)

        receiver.send(programChange(channel = 1, program = 40), -1L)
        receiver.send(noteOn(channel = 1, midi = 67, velocity = 80), -1L)
        receiver.send(noteOff(channel = 1, midi = 67), -1L)

        assertEquals(emptyList(), rhody.events)
        assertEquals(3, fallback.messages.size)
    }

    @Test
    fun `nonzero bank switches an implemented channel back to fallback`() {
        val rhody = RecordingRhodyOutput()
        val fallback = RecordingReceiver()
        val receiver = RhodyMidiReceiver(rhody, fallback)

        receiver.send(programChange(channel = 3, program = 73), -1L)
        receiver.send(controlChange(channel = 3, controller = 0, value = 1), -1L)
        receiver.send(noteOn(channel = 3, midi = 69, velocity = 90), -1L)

        assertEquals(listOf("patch:3:0:0", "all-off:3"), rhody.events)
        assertEquals(3, fallback.messages.size)
    }

    @Test
    fun `channel volume is routed to an active rhody patch`() {
        val rhody = RecordingRhodyOutput()
        val fallback = RecordingReceiver()
        val receiver = RhodyMidiReceiver(rhody, fallback)

        receiver.send(programChange(channel = 4, program = 73), -1L)
        receiver.send(controlChange(channel = 4, controller = 7, value = 96), -1L)

        assertEquals(listOf("patch:4:0:0", "volume:4:96"), rhody.events)
        assertEquals(1, fallback.messages.size) // program change only
    }

    @Test
    fun `lookahead limiter catches an impulse before pcm conversion`() {
        val limiter = LookaheadLimiter(sampleRate = 1_000f, lookaheadMs = 5f)
        val block = FloatArray(24).also { it[0] = 4f }

        limiter.process(block)

        val ceiling = 10f.pow(-1f / 20f)
        assertTrue(block.take(5).all { it == 0f })
        assertTrue(block.maxOf { kotlin.math.abs(it) } <= ceiling + 1e-6f)
        assertTrue(block.any { it != 0f })
    }

    @Test
    fun `adjacent optional rhody library is loadable when present`() {
        val candidate = RhodyNativeLoader.candidates(
            explicit = null,
            workingDirectory = Paths.get("").toAbsolutePath(),
        ).firstOrNull(Files::isRegularFile) ?: return

        val api = RhodyNativeLoader.load()
        val handle = assertNotNull(api.rhody_create(44_100.0), "Could not create engine from $candidate")
        try {
            api.rhody_set_family(handle, 2)
            api.rhody_set_preset(handle, 0)
            assertEquals(-6.0, api.rhody_calibration_gain_db(2, 0))
            assertEquals(0.0, api.rhody_orchestral_gain_db(2, 0))
            api.rhody_set_mix_gain_db(handle, -3.0)
            api.rhody_note_on_velocity(handle, 440.0, 0.8)
            val samples = FloatArray(1024)
            assertEquals(samples.size, api.rhody_render(handle, samples, samples.size))
            assertTrue(samples.all(Float::isFinite))
            assertTrue(samples.any { it != 0f })
            api.rhody_note_off_pitch(handle, 440.0)
        } finally {
            api.rhody_destroy(handle)
        }
    }

    private class RecordingRhodyOutput : RhodyNoteOutput {
        val events = mutableListOf<String>()
        override fun selectPatch(channel: Int, patch: RhodyPatch) {
            events += "patch:$channel:${patch.family}:${patch.preset}"
        }
        override fun noteOn(channel: Int, midi: Int, velocity: Int) {
            events += "on:$channel:$midi:$velocity"
        }
        override fun noteOff(channel: Int, midi: Int) {
            events += "off:$channel:$midi"
        }
        override fun allNotesOff(channel: Int) {
            events += "all-off:$channel"
        }

        override fun setChannelVolume(channel: Int, value: Int) {
            events += "volume:$channel:$value"
        }
    }

    private class RecordingReceiver : Receiver {
        val messages = mutableListOf<MidiMessage>()
        override fun send(message: MidiMessage, timeStamp: Long) {
            messages += message
        }
        override fun close() = Unit
    }

    private fun programChange(channel: Int, program: Int) = ShortMessage().apply {
        setMessage(ShortMessage.PROGRAM_CHANGE, channel, program, 0)
    }

    private fun noteOn(channel: Int, midi: Int, velocity: Int) = ShortMessage().apply {
        setMessage(ShortMessage.NOTE_ON, channel, midi, velocity)
    }

    private fun noteOff(channel: Int, midi: Int) = ShortMessage().apply {
        setMessage(ShortMessage.NOTE_OFF, channel, midi, 0)
    }

    private fun controlChange(channel: Int, controller: Int, value: Int) = ShortMessage().apply {
        setMessage(ShortMessage.CONTROL_CHANGE, channel, controller, value)
    }
}
