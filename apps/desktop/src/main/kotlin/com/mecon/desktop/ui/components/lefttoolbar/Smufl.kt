package com.mecon.desktop.ui.components.lefttoolbar

/**
 * SMuFL (Bravura) codepoints used by the palette icons.
 *
 * Note glyphs come from the "Individual notes" range (U+E1Dx); accidentals from the
 * "Standard accidentals (12-EDO)" range (U+E26x). Longa/maxima have no stemmed single-note
 * glyph in SMuFL, so we fall back to their mensural noteheads.
 */
internal object Smufl {
    // Clef used as the "score elements" tool-column icon.
    const val CLEF_TREBLE = "\uE050" // gClef
    // Individual notes (stem up)
    const val NOTE_DOUBLE_WHOLE = "" // noteDoubleWhole (breve)
    const val NOTE_WHOLE        = "" // noteWhole
    const val NOTE_HALF         = "" // noteHalfUp
    const val NOTE_QUARTER      = "" // noteQuarterUp
    const val NOTE_8TH          = "" // note8thUp
    const val NOTE_16TH         = "" // note16thUp
    const val NOTE_32ND         = "" // note32ndUp
    const val NOTE_64TH         = "" // note64thUp
    const val NOTE_128TH        = "" // note128thUp
    const val NOTE_256TH        = "" // note256thUp
    const val AUGMENTATION_DOT  = "" // augmentationDot
    // Longa / maxima — mensural noteheads (no individual-note equivalent)
    const val NOTE_LONGA        = "" // mensuralNoteheadLongaBlack
    const val NOTE_MAXIMA       = "" // mensuralNoteheadMaximaBlack
    // Rest
    const val REST_QUARTER      = "" // restQuarter
    // Standard accidentals
    const val ACC_SHARP         = "" // accidentalSharp
    const val ACC_FLAT          = "" // accidentalFlat
    const val ACC_NATURAL       = "" // accidentalNatural
    const val ACC_DOUBLE_SHARP  = "" // accidentalDoubleSharp
    const val ACC_DOUBLE_FLAT   = "" // accidentalDoubleFlat
    // Articulations (above variants are suitable for palette previews).
    const val ARTIC_ACCENT      = "\uE4A0"
    const val ARTIC_STACCATO    = "\uE4A2"
    const val ARTIC_TENUTO      = "\uE4A4"
    const val ARTIC_STACCATISSIMO = "\uE4A6"
    const val ARTIC_MARCATO     = "\uE4AC"
    const val FERMATA           = "\uE4C0"
}
