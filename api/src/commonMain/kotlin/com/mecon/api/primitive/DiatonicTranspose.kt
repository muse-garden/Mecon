package com.mecon.api.primitive

/**
 * Diatonic-transposition helpers shared by the edit engine ([com.mecon.core] `NoteEditEngine.transpose`)
 * and the drag preview ([com.mecon.renderer] `TransposePreviewComputer`), so both spell moved pitches
 * identically and keep them inside the playable MIDI range.
 */
object DiatonicTranspose {

    /** Valid MIDI note numbers (a note outside this can't be played and crashes the audio converter). */
    val MIDI_RANGE = 0..127

    /** The pitch at [diatonicSteps] spelled with [key]'s default accidental for that note name (i.e.
     *  dropping any temporary accidental — the "平移后默认删去临时升降号" rule). */
    fun spell(key: KeySignature, diatonicSteps: Int): Pitch =
        Pitch(diatonicSteps, key.accidentalFor(NoteName.fromIndex(diatonicSteps)).offset)

    /**
     * Clamp [requested] diatonic-step delta toward 0 so that transposing every (pitch, key) in [moved]
     * keeps its sounding note within [MIDI_RANGE]. Returns 0 when even a single step would push any
     * note out of range (so the caller treats it as a no-op). MIDI number is monotonic in diatonic
     * steps under key spelling, so shrinking the magnitude is sufficient.
     */
    fun clampDelta(moved: List<Pair<Pitch, KeySignature>>, requested: Int): Int {
        if (requested == 0 || moved.isEmpty()) return requested
        val step = if (requested > 0) 1 else -1
        var d = requested
        while (d != 0 && moved.any { (p, k) -> spell(k, p.diatonicSteps + d).midiNumber !in MIDI_RANGE }) {
            d -= step
        }
        return d
    }
}
