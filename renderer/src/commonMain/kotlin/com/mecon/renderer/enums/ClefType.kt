package com.mecon.renderer.enums

import com.mecon.api.storage.tracks.Clef
import com.mecon.renderer.smufl.GlyphInfo
import com.mecon.renderer.smufl.SmuflGlyphs

/**
 * Types of clefs.
 */
enum class ClefType(
    /** SMuFL glyph for this clef type. */
    val glyph: GlyphInfo
) {
    TREBLE(SmuflGlyphs.gClef),                 // G clef on line 2
    TREBLE_8VA(SmuflGlyphs.gClef8va),          // G clef ottava alta
    TREBLE_8VB(SmuflGlyphs.gClef8vb),          // G clef ottava bassa
    TREBLE_15MA(SmuflGlyphs.gClef15ma),        // G clef quindicesima alta
    TREBLE_15MB(SmuflGlyphs.gClef15mb),        // G clef quindicesima bassa
    BASS(SmuflGlyphs.fClef),                   // F clef on line 4
    BASS_8VA(SmuflGlyphs.fClef8va),            // F clef ottava alta
    BASS_8VB(SmuflGlyphs.fClef8vb),            // F clef ottava bassa
    BASS_15MA(SmuflGlyphs.fClef15ma),          // F clef quindicesima alta
    BASS_15MB(SmuflGlyphs.fClef15mb),          // F clef quindicesima bassa
    ALTO(SmuflGlyphs.cClef),                   // C clef on line 3
    TENOR(SmuflGlyphs.cClef),                  // C clef on line 4
    SOPRANO(SmuflGlyphs.cClef),                // C clef on line 1
    MEZZO_SOPRANO(SmuflGlyphs.cClef),          // C clef on line 2
    BARITONE(SmuflGlyphs.cClef),               // C clef on line 5
    PERCUSSION_1(SmuflGlyphs.unpitchedPercussionClef1),   // Unpitched percussion clef variant 1
    PERCUSSION_2(SmuflGlyphs.unpitchedPercussionClef2)    // Unpitched percussion clef variant 2
}

/**
 * Get the staff line where this clef type's reference pitch is placed.
 * Line numbering: 1 = bottom line, 5 = top line.
 */
fun ClefType.getReferenceLine(): Int = when (this) {
    ClefType.TREBLE, ClefType.TREBLE_8VA, ClefType.TREBLE_8VB,
    ClefType.TREBLE_15MA, ClefType.TREBLE_15MB -> 2  // G on line 2
    ClefType.BASS, ClefType.BASS_8VA, ClefType.BASS_8VB,
    ClefType.BASS_15MA, ClefType.BASS_15MB -> 4     // F on line 4
    ClefType.ALTO -> 3                              // C on line 3
    ClefType.TENOR -> 4                             // C on line 4
    ClefType.SOPRANO -> 1                           // C on line 1
    ClefType.MEZZO_SOPRANO -> 2                     // C on line 2
    ClefType.BARITONE -> 5                          // C on line 5
    ClefType.PERCUSSION_1, ClefType.PERCUSSION_2 -> 3  // Center
}

/**
 * Convert storage Clef to renderer ClefType.
 */
fun Clef.toClefType(): ClefType = when (this) {
    Clef.TREBLE -> ClefType.TREBLE
    Clef.BASS -> ClefType.BASS
    Clef.ALTO -> ClefType.ALTO
    Clef.TENOR -> ClefType.TENOR
    Clef.PERCUSSION -> ClefType.PERCUSSION_1
}
