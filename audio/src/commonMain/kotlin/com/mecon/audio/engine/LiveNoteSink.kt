package com.mecon.audio.engine

data class LiveNoteRequest(
    val midi: Int,
    val velocity: Int = 88,
    val midiBank: Int = 0,
    val midiProgram: Int = 0,
) {
    init {
        require(velocity in 1..127)
        require(midiBank >= 0)
        require(midiProgram in 0..127)
    }
}

/** Paired, low-latency note input independent of the transport and fixed-duration audition API. */
interface LiveNoteSink {
    /** Out-of-range score pitches are ignored rather than sent to the MIDI backend. */
    fun liveNoteOn(request: LiveNoteRequest)
    fun liveNoteOff(midi: Int)
    fun liveAllNotesOff()
}
