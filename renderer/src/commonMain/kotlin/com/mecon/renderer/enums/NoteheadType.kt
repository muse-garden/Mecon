package com.mecon.renderer.enums

import com.mecon.renderer.smufl.GlyphInfo
import com.mecon.renderer.smufl.SmuflGlyphs

/**
 * Type of notehead.
 */
enum class NoteheadType(
    /** SMuFL glyph for this notehead type. */
    val glyph: GlyphInfo
) {
    DOUBLE_WHOLE(SmuflGlyphs.noteheadDoubleWhole),   // Breve
    WHOLE(SmuflGlyphs.noteheadWhole),                 // Semibreve
    HALF(SmuflGlyphs.noteheadHalf),                   // Minim
    BLACK(SmuflGlyphs.noteheadBlack);                 // Quarter and shorter

    /** SMuFL glyph name for this notehead type. */
    val glyphName: String get() = glyph.name
}