package com.mecon.theory

import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.PitchClass
import com.mecon.api.render.FormattedText
import com.mecon.api.render.TextRun
import com.mecon.api.render.TextRunStyle
import com.mecon.theory.harmony.FunctionalChordSymbol

enum class ChordSymbolDisplayStyle {
    LETTER,
    SCALE_DEGREE,
}

enum class ChordSymbolPartRole {
    ROOT,
    QUALITY,
    SEPARATOR,
    BASS,
    GAP,
}

enum class ChordSymbolPlacement {
    BASELINE,
    SUPERSCRIPT,
    SUBSCRIPT,
}

data class ChordSymbolPart(
    val text: String,
    val role: ChordSymbolPartRole,
    val placement: ChordSymbolPlacement = ChordSymbolPlacement.BASELINE,
)

data class ChordSymbol(
    val parts: List<ChordSymbolPart>,
) {
    val plainText: String = parts.joinToString(separator = "") { it.text }

    /**
     * Map this symbol's semantic [parts] to the shared [FormattedText] styling
     * convention. Style policy (the "format agreement" other renderers consume):
     *  - ROOT / BASS: bold in scale-degree mode (numerals stand out), full size.
     *  - QUALITY (m, 7, maj7, …): 0.75× size in *both* modes for clarity.
     *  - SEPARATOR / GAP: default.
     */
    fun toFormattedText(style: ChordSymbolDisplayStyle): FormattedText = FormattedText(
        parts.map { part ->
            val runStyle = when (part.role) {
                ChordSymbolPartRole.ROOT,
                ChordSymbolPartRole.BASS ->
                    TextRunStyle(bold = style == ChordSymbolDisplayStyle.SCALE_DEGREE)
                ChordSymbolPartRole.QUALITY -> TextRunStyle(sizeScale = QUALITY_SIZE_SCALE)
                ChordSymbolPartRole.SEPARATOR,
                ChordSymbolPartRole.GAP -> TextRunStyle.DEFAULT
            }
            TextRun(part.text, runStyle)
        }
    )

    companion object {
        /** Chord quality suffix shrink factor (see [toFormattedText]). */
        const val QUALITY_SIZE_SCALE: Float = 0.75f
    }
}

object ChordSymbolFormatter {
    fun format(
        chord: Chord,
        style: ChordSymbolDisplayStyle = ChordSymbolDisplayStyle.LETTER,
        keySignature: KeySignature = KeySignature.C_MAJOR,
    ): String = formatSymbol(chord, style, keySignature).plainText

    fun formatSymbol(
        chord: Chord,
        style: ChordSymbolDisplayStyle = ChordSymbolDisplayStyle.LETTER,
        keySignature: KeySignature = KeySignature.C_MAJOR,
    ): ChordSymbol = ChordSymbol(
        buildList {
            add(ChordSymbolPart(formatPitchClass(chord.root, style, keySignature), ChordSymbolPartRole.ROOT))
            val suffix = qualitySuffix(chord.quality)
            if (suffix.isNotEmpty()) {
                add(ChordSymbolPart(suffix, ChordSymbolPartRole.QUALITY))
            }
            if (chord.bass != null) {
                add(ChordSymbolPart("/", ChordSymbolPartRole.SEPARATOR))
                add(ChordSymbolPart(formatPitchClass(chord.bass, style, keySignature), ChordSymbolPartRole.BASS))
            }
        }
    )

    fun qualitySuffix(quality: ChordQuality): String = when (quality) {
        ChordQuality.MAJOR -> ""
        ChordQuality.MINOR -> "m"
        ChordQuality.DIMINISHED -> "°"
        ChordQuality.AUGMENTED -> "+"
        ChordQuality.SUS2 -> "sus2"
        ChordQuality.SUS4 -> "sus4"
        ChordQuality.MAJOR7 -> "maj7"
        ChordQuality.MINOR7 -> "m7"
        ChordQuality.DOMINANT7 -> "7"
        ChordQuality.DIMINISHED7 -> "°7"
        ChordQuality.HALF_DIMINISHED7 -> "ø7"
        ChordQuality.MINOR_MAJOR7 -> "m(maj7)"
        ChordQuality.AUGMENTED7 -> "+7"
        ChordQuality.ADD9 -> "add9"
        ChordQuality.ADD11 -> "add11"
        ChordQuality.MAJOR9 -> "maj9"
        ChordQuality.MINOR9 -> "m9"
        ChordQuality.DOMINANT9 -> "9"
        ChordQuality.MAJOR11 -> "maj11"
        ChordQuality.MINOR11 -> "m11"
        ChordQuality.DOMINANT11 -> "11"
        ChordQuality.MAJOR13 -> "maj13"
        ChordQuality.MINOR13 -> "m13"
        ChordQuality.DOMINANT13 -> "13"
        ChordQuality.DOMINANT7_FLAT5 -> "7♭5"
        ChordQuality.DOMINANT7_SHARP5 -> "7♯5"
        ChordQuality.DOMINANT7_FLAT9 -> "7♭9"
        ChordQuality.DOMINANT7_SHARP9 -> "7♯9"
        ChordQuality.DOMINANT7_SHARP11 -> "7♯11"
        ChordQuality.ALTERED -> "alt"
        ChordQuality.CUSTOM -> ""
    }

    fun formatPitchClass(
        pitchClass: PitchClass,
        style: ChordSymbolDisplayStyle,
        keySignature: KeySignature,
    ): String = when (style) {
        ChordSymbolDisplayStyle.LETTER -> pitchClass.toNoteName(preferSharps = true)
        ChordSymbolDisplayStyle.SCALE_DEGREE -> scaleDegreeName(pitchClass, keySignature)
    }

    private fun scaleDegreeName(pitchClass: PitchClass, keySignature: KeySignature): String {
        val offset = (pitchClass.value - keySignature.root.value).mod(12)
        val semitones = keySignature.mode.semitones()
        val degreeIndex = semitones.indexOf(offset)
        if (degreeIndex >= 0) return (degreeIndex + 1).toString()

        val lowerIndex = semitones.indexOfLast { it < offset }
        if (lowerIndex >= 0) return "♯${lowerIndex + 1}"

        val upperIndex = semitones.indexOfFirst { it > offset }
        return if (upperIndex >= 0) "♭${upperIndex + 1}" else "♭1"
    }
}

/**
 * Canonical formatter for functional Roman-numeral symbols.
 *
 * Chord-quality suffix spelling delegates to [ChordSymbolFormatter]; this object only applies
 * Roman-numeral casing and the conventional omission of "m" for lower-case minor numerals.
 */
object FunctionalChordSymbolFormatter {
    fun romanDegree(degree: Int): String =
        ROMAN_NUMERALS.getOrElse(degree - 1) { degree.toString() }

    fun format(symbol: FunctionalChordSymbol): String {
        val lowerCase = symbol.quality in LOWER_CASE_QUALITIES
        val numeral = romanDegree(symbol.degree)
            .let { if (lowerCase) it.lowercase() else it }
        val prefix = when {
            symbol.alteration > 0 -> "♯".repeat(symbol.alteration)
            symbol.alteration < 0 -> "♭".repeat(-symbol.alteration)
            else -> ""
        }
        val suffix = when (symbol.quality) {
            ChordQuality.MAJOR, ChordQuality.MINOR -> ""
            ChordQuality.DIMINISHED -> "°"
            ChordQuality.AUGMENTED -> "+"
            ChordQuality.MINOR7, ChordQuality.DOMINANT7 -> "7"
            ChordQuality.DIMINISHED7 -> "°7"
            ChordQuality.HALF_DIMINISHED7 -> "ø7"
            else -> ChordSymbolFormatter.qualitySuffix(symbol.quality)
        }
        val applied = symbol.appliedToDegree?.let { degree ->
            "/" + romanDegree(degree)
        }.orEmpty()
        return "$prefix$numeral$suffix$applied"
    }

    private val ROMAN_NUMERALS = listOf("I", "II", "III", "IV", "V", "VI", "VII")
    private val LOWER_CASE_QUALITIES = setOf(
        ChordQuality.MINOR,
        ChordQuality.DIMINISHED,
        ChordQuality.MINOR7,
        ChordQuality.DIMINISHED7,
        ChordQuality.HALF_DIMINISHED7,
        ChordQuality.MINOR_MAJOR7,
    )
}
