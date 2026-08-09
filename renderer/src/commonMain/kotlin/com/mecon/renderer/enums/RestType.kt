package com.mecon.renderer.enums

import com.mecon.renderer.smufl.GlyphInfo
import com.mecon.renderer.smufl.SmuflGlyphs

/**
 * Type of rest based on duration.
 */
enum class RestType(
    /** SMuFL glyph for this rest type. */
    val glyph: GlyphInfo
) {
    MAXIMA(SmuflGlyphs.restMaxima),
    LONGA(SmuflGlyphs.restLonga),
    DOUBLE_WHOLE(SmuflGlyphs.restDoubleWhole),   // Breve rest
    WHOLE(SmuflGlyphs.restWhole),                 // Semibreve rest
    HALF(SmuflGlyphs.restHalf),                   // Minim rest
    QUARTER(SmuflGlyphs.restQuarter),             // Crotchet rest
    EIGHTH(SmuflGlyphs.rest8th),                  // Quaver rest
    SIXTEENTH(SmuflGlyphs.rest16th),              // Semiquaver rest
    THIRTY_SECOND(SmuflGlyphs.rest32nd),          // Demisemiquaver rest
    SIXTY_FOURTH(SmuflGlyphs.rest64th),           // Hemidemisemiquaver rest
    ONE_TWENTY_EIGHTH(SmuflGlyphs.rest128th),
    TWO_FIFTY_SIXTH(SmuflGlyphs.rest256th)
}