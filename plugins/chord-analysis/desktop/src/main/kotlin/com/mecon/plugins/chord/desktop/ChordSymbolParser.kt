package com.mecon.plugins.chord.desktop

import com.mecon.theory.ChordQuality

/**
 * Parses simple chord symbols ("C", "Dm", "G7", "F/A") into (rootPc, quality, bassPc?).
 *
 * Supported quality suffixes (case-sensitive on the leading letter):
 *  - "" → MAJOR
 *  - "m" → MINOR
 *  - "dim" / "°" → DIMINISHED
 *  - "aug" / "+" → AUGMENTED
 *  - "sus2", "sus4"
 *  - "maj7", "m7", "7", "dim7", "m(maj7)"
 *
 * Unrecognized suffixes return `null`.
 */
object ChordSymbolParser {

    data class Parsed(val root: Int, val quality: ChordQuality, val bass: Int?)

    fun parse(input: String): Parsed? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        val (head, bassPart) = trimmed.split("/", limit = 2).let {
            if (it.size == 2) it[0] to it[1] else it[0] to null
        }

        val rootEnd = if (head.length >= 2 && (head[1] == '#' || head[1] == 'b' || head[1] == '♯' || head[1] == '♭')) 2 else 1
        if (rootEnd > head.length) return null
        val rootName = head.substring(0, rootEnd)
        val qualityPart = head.substring(rootEnd)

        val root = pitchClassOf(rootName) ?: return null
        val quality = qualityOf(qualityPart) ?: return null
        val bass = bassPart?.let { pitchClassOf(it.trim()) }
        return Parsed(root, quality, bass)
    }

    private fun pitchClassOf(name: String): Int? = when (name) {
        "C" -> 0
        "C#", "C♯", "Db", "D♭" -> 1
        "D" -> 2
        "D#", "D♯", "Eb", "E♭" -> 3
        "E" -> 4
        "F" -> 5
        "F#", "F♯", "Gb", "G♭" -> 6
        "G" -> 7
        "G#", "G♯", "Ab", "A♭" -> 8
        "A" -> 9
        "A#", "A♯", "Bb", "B♭" -> 10
        "B" -> 11
        else -> null
    }

    private fun qualityOf(suffix: String): ChordQuality? = when (suffix) {
        "" -> ChordQuality.MAJOR
        "m", "min", "-" -> ChordQuality.MINOR
        "dim", "°" -> ChordQuality.DIMINISHED
        "aug", "+" -> ChordQuality.AUGMENTED
        "sus2" -> ChordQuality.SUS2
        "sus4", "sus" -> ChordQuality.SUS4
        "maj7", "M7", "Δ", "Δ7" -> ChordQuality.MAJOR7
        "m7", "min7", "-7" -> ChordQuality.MINOR7
        "7" -> ChordQuality.DOMINANT7
        "dim7", "°7" -> ChordQuality.DIMINISHED7
        "m7b5", "ø", "ø7" -> ChordQuality.HALF_DIMINISHED7
        "m(maj7)", "mM7", "-Δ7" -> ChordQuality.MINOR_MAJOR7
        else -> null
    }
}
