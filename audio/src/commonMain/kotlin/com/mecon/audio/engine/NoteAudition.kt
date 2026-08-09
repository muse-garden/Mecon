package com.mecon.audio.engine

import com.mecon.audio.model.MidiNoteRange

/**
 * A short, transport-independent note preview used while entering, selecting, or dragging notes.
 * A new request replaces the currently sounding audition so rapid pitch drags cannot leave notes on.
 */
class NoteAudition(
    midiNumbers: List<Int>,
    val midiBank: Int = 0,
    val midiProgram: Int = 0,
    val velocity: Int = DEFAULT_VELOCITY,
    val durationMillis: Long = DEFAULT_DURATION_MILLIS,
) {
    /** Invalid score pitches are silent; valid chord tones remain audible. */
    val midiNumbers: List<Int> = MidiNoteRange.filter(midiNumbers)

    init {
        require(midiBank >= 0) { "MIDI bank must be non-negative" }
        require(midiProgram in 0..127) { "MIDI program must be in 0..127" }
        require(velocity in 1..127) { "Velocity must be in 1..127" }
        require(durationMillis > 0) { "Audition duration must be positive" }
    }

    companion object {
        const val DEFAULT_VELOCITY = 88
        const val DEFAULT_DURATION_MILLIS = 500L
    }
}
