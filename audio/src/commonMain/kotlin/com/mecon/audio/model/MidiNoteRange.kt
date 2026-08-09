package com.mecon.audio.model

/**
 * The note-number range representable by standard MIDI messages.
 *
 * Score pitches may intentionally extend beyond this range. Audio boundaries must skip those
 * pitches instead of clamping them, because clamping would play a different note.
 */
object MidiNoteRange {
    const val MIN = 0
    const val MAX = 127

    fun contains(midiNumber: Int): Boolean = midiNumber in MIN..MAX

    fun filter(midiNumbers: Iterable<Int>): List<Int> = midiNumbers.filter(::contains)
}
