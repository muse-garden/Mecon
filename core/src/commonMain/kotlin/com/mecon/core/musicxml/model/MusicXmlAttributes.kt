package com.mecon.core.musicxml.model

/**
 * Attributes element containing key/time signatures, divisions, clefs, etc.
 */
data class MusicXmlAttributes(
    /** Divisions per quarter note (timing resolution) */
    val divisions: Int? = null,
    /** Key signature(s) */
    val keys: List<MusicXmlKey> = emptyList(),
    /** Time signature(s) */
    val times: List<MusicXmlTime> = emptyList(),
    /** Number of staves */
    val staves: Int = 1,
    /** Clef(s) */
    val clefs: List<MusicXmlClef> = emptyList(),
    /** Staff details */
    val staffDetails: List<MusicXmlStaffDetails> = emptyList(),
    /** Transpose info for transposing instruments */
    val transpose: MusicXmlTranspose? = null,
    /** Part symbol (for grouping staves) */
    val partSymbol: MusicXmlPartSymbol? = null,
    /** Directive attribute */
    val directive: Boolean = false
) : MusicXmlMeasureElement

/**
 * Key signature.
 */
data class MusicXmlKey(
    /** Number of sharps (positive) or flats (negative), or null for explicit */
    val fifths: Int? = null,
    /** Mode: major, minor, dorian, etc. */
    val mode: String? = null,
    /** Staff number if different per staff */
    val staff: Int? = null,
    /** For non-traditional keys: explicit accidentals */
    val keySteps: List<String> = emptyList(),
    val keyAlters: List<Int> = emptyList(),
    val keyOctaves: List<Int> = emptyList(),
    /** Cancel previous key signature */
    val cancel: Int? = null,
    /** Print-object attribute */
    val printObject: Boolean = true
)

/**
 * Time signature.
 */
data class MusicXmlTime(
    /** Beats numerator (can have multiple for composite) */
    val beats: List<String> = emptyList(),  // String to handle "3+2+2"
    /** Beat type denominator (can have multiple for composite) */
    val beatType: List<Int> = emptyList(),
    /** Symbol: common, cut, single-number, note, dotted-note, normal */
    val symbol: String? = null,
    /** Staff number if different per staff */
    val staff: Int? = null,
    /** Senza misura (no time signature) */
    val senzaMisura: Boolean = false,
    /** Print-object attribute */
    val printObject: Boolean = true
) {
    /**
     * Get simple numerator (first beats value as Int).
     */
    fun getNumerator(): Int = beats.firstOrNull()?.toIntOrNull() ?: 4

    /**
     * Get simple denominator (first beatType value).
     */
    fun getDenominator(): Int = beatType.firstOrNull() ?: 4
}

/**
 * Clef definition.
 */
data class MusicXmlClef(
    /** Sign: G, F, C, percussion, TAB, jianpu, none */
    val sign: String,
    /** Line number (1 = bottom) */
    val line: Int? = null,
    /** Octave change (-2 to +2) */
    val clefOctaveChange: Int = 0,
    /** Staff number (for multi-staff parts) */
    val staff: Int? = null,
    /** Print-object attribute */
    val printObject: Boolean = true,
    /** After-barline placement */
    val afterBarline: Boolean = false,
    /** Additional context */
    val additional: Boolean = false
) {
    companion object {
        val TREBLE = MusicXmlClef(sign = "G", line = 2)
        val BASS = MusicXmlClef(sign = "F", line = 4)
        val ALTO = MusicXmlClef(sign = "C", line = 3)
        val TENOR = MusicXmlClef(sign = "C", line = 4)
        val PERCUSSION = MusicXmlClef(sign = "percussion")
    }
}

/**
 * Staff details for special staff configurations.
 */
data class MusicXmlStaffDetails(
    val staff: Int? = null,
    val staffType: String? = null,  // ossia, cue, editorial, regular, alternate
    val staffLines: Int = 5,
    val staffTuning: List<MusicXmlStaffTuning> = emptyList(),
    val capo: Int? = null,
    val staffSize: Float? = null,
    val showFrets: String? = null,  // numbers, letters
    val printObject: Boolean = true,
    val printSpacing: Boolean? = null
)

/**
 * Staff tuning for tablature.
 */
data class MusicXmlStaffTuning(
    val line: Int,
    val tuningStep: String,
    val tuningAlter: Int = 0,
    val tuningOctave: Int
)

/**
 * Transposition for transposing instruments.
 */
data class MusicXmlTranspose(
    /** Chromatic transposition in semitones */
    val chromatic: Int,
    /** Diatonic transposition in steps */
    val diatonic: Int? = null,
    /** Octave change */
    val octaveChange: Int = 0,
    /** Double (transposition applies in octaves) */
    val double: Boolean = false,
    /** Staff number */
    val staff: Int? = null
)

/**
 * Part symbol for staff grouping.
 */
data class MusicXmlPartSymbol(
    val value: String,  // none, brace, line, bracket, square
    val topStaff: Int? = null,
    val bottomStaff: Int? = null
)
