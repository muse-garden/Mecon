package com.mecon.audio.model

import com.mecon.api.primitive.EventId
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * MIDI channel (0-15).
 */
@JvmInline
@Serializable
value class MidiChannel(val value: Int) {
    init {
        require(value in 0..15) { "MIDI channel must be 0-15, got $value" }
    }

    companion object {
        val DEFAULT = MidiChannel(0)
    }
}

/**
 * MIDI velocity (0-127).
 */
@JvmInline
@Serializable
value class Velocity(val value: Int) {
    init {
        require(value in 0..127) { "Velocity must be 0-127, got $value" }
    }

    companion object {
        val MIN = Velocity(0)
        val MAX = Velocity(127)
        val DEFAULT = Velocity(80)
        val FORTE = Velocity(100)
        val PIANO = Velocity(60)
        val MEZZO_FORTE = Velocity(80)
    }
}

/**
 * MIDI event - base interface for all MIDI events.
 *
 * MIDI events are derived from PitchEvents only (not VoiceEvents).
 * VoiceEvents are for score rendering, while MidiEvents are for audio playback.
 */
sealed interface MidiEvent {
    /** Absolute position in ticks from start of score */
    val absoluteTicks: Long

    /** Link back to source PitchEvent (for highlighting during playback) */
    val sourceEventId: EventId?
}

/**
 * MIDI Note On event - starts a note.
 */
@Serializable
data class MidiNoteOnEvent(
    override val absoluteTicks: Long,
    val midiNumber: Int,
    val velocity: Velocity,
    val channel: MidiChannel = MidiChannel.DEFAULT,
    override val sourceEventId: EventId? = null
) : MidiEvent {
    init {
        require(MidiNoteRange.contains(midiNumber)) {
            "MIDI number must be ${MidiNoteRange.MIN}-${MidiNoteRange.MAX}, got $midiNumber"
        }
    }
}

/**
 * MIDI Note Off event - ends a note.
 */
@Serializable
data class MidiNoteOffEvent(
    override val absoluteTicks: Long,
    val midiNumber: Int,
    val channel: MidiChannel = MidiChannel.DEFAULT,
    override val sourceEventId: EventId? = null
) : MidiEvent {
    init {
        require(MidiNoteRange.contains(midiNumber)) {
            "MIDI number must be ${MidiNoteRange.MIN}-${MidiNoteRange.MAX}, got $midiNumber"
        }
    }
}

/**
 * MIDI Tempo change event.
 */
@Serializable
data class MidiTempoEvent(
    override val absoluteTicks: Long,
    val bpm: Float,
    override val sourceEventId: EventId? = null
) : MidiEvent {
    init {
        require(bpm > 0) { "BPM must be positive, got $bpm" }
    }

    /** Microseconds per quarter note (standard MIDI tempo format) */
    val microsecondsPerQuarter: Int get() = (60_000_000 / bpm).toInt()
}

/**
 * MIDI Program Change event - changes instrument.
 */
@Serializable
data class MidiProgramChangeEvent(
    override val absoluteTicks: Long,
    val program: Int,
    val channel: MidiChannel = MidiChannel.DEFAULT,
    override val sourceEventId: EventId? = null
) : MidiEvent {
    init {
        require(program in 0..127) { "Program must be 0-127, got $program" }
    }
}

/**
 * MIDI Control Change event.
 */
@Serializable
data class MidiControlChangeEvent(
    override val absoluteTicks: Long,
    val controller: Int,
    val value: Int,
    val channel: MidiChannel = MidiChannel.DEFAULT,
    override val sourceEventId: EventId? = null
) : MidiEvent {
    init {
        require(controller in 0..127) { "Controller must be 0-127, got $controller" }
        require(value in 0..127) { "Value must be 0-127, got $value" }
    }

    companion object {
        // Common controller numbers
        const val VOLUME = 7
        const val PAN = 10
        const val EXPRESSION = 11
        const val SUSTAIN = 64
        const val ALL_NOTES_OFF = 123
    }
}
