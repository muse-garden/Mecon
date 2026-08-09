package com.mecon.renderer.enums

import com.mecon.renderer.smufl.GlyphInfo
import com.mecon.renderer.smufl.SmuflGlyphs

/**
 * Accidental types.
 */
enum class AccidentalType(
    /** SMuFL glyph for this accidental type. */
    val glyph: GlyphInfo
) {
    DOUBLE_FLAT(SmuflGlyphs.accidentalDoubleFlat),
    FLAT(SmuflGlyphs.accidentalFlat),
    NATURAL(SmuflGlyphs.accidentalNatural),
    SHARP(SmuflGlyphs.accidentalSharp),
    DOUBLE_SHARP(SmuflGlyphs.accidentalDoubleSharp)
}