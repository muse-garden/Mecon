package com.mecon.theory

import com.mecon.api.primitive.PitchClass

fun Chord.Companion.parse(symbol: String): Chord {
    val regex = Regex("""^([A-G][#b]?)(.*)$""")
    val match = regex.find(symbol) ?: return Chord.create(PitchClass.C, ChordQuality.MAJOR)
    val rootStr = match.groupValues[1]
    val qualityStr = match.groupValues[2]

    val rootMapping = mapOf(
        "C" to PitchClass.C, "C#" to PitchClass(1), "Db" to PitchClass(1),
        "D" to PitchClass.D, "D#" to PitchClass(3), "Eb" to PitchClass(3),
        "E" to PitchClass.E, "F" to PitchClass.F, "F#" to PitchClass(6), "Gb" to PitchClass(6),
        "G" to PitchClass.G, "G#" to PitchClass(8), "Ab" to PitchClass(8),
        "A" to PitchClass.A, "A#" to PitchClass(10), "Bb" to PitchClass(10),
        "B" to PitchClass.B
    )
    val root = rootMapping[rootStr] ?: PitchClass.C

    val quality = when (qualityStr) {
        "m", "min", "-" -> ChordQuality.MINOR
        "dim", "o" -> ChordQuality.DIMINISHED
        "aug", "+" -> ChordQuality.AUGMENTED
        "maj7", "M7" -> ChordQuality.MAJOR7
        "m7", "min7", "-7" -> ChordQuality.MINOR7
        "7", "dom7" -> ChordQuality.DOMINANT7
        "m7b5", "h" -> ChordQuality.HALF_DIMINISHED7
        "dim7", "o7" -> ChordQuality.DIMINISHED7
        else -> ChordQuality.MAJOR
    }
    return Chord.create(root, quality)
}
