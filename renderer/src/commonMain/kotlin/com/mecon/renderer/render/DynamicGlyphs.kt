package com.mecon.renderer.render

import com.mecon.api.storage.events.DynamicLevel
import com.mecon.renderer.smufl.GlyphInfo
import com.mecon.renderer.smufl.SmuflGlyphs

/**
 * Maps a [DynamicLevel] to the SMuFL glyph(s) that render it.
 *
 * Every standard level maps to a **single** SMuFL glyph. Compound levels use
 * pre-composed glyphs (U+E527 onward) whose internal letter spacing is already
 * correct in Bravura; standalone niente uses the SMuFL `dynamicNiente` glyph.
 */
object DynamicGlyphs {

    /** Single SMuFL glyph for [level]. */
    fun glyphsFor(level: DynamicLevel): List<GlyphInfo> = listOf(
        when (level) {
            DynamicLevel.PPPPPP -> SmuflGlyphs.dynamicPPPPPP
            DynamicLevel.PPPPP  -> SmuflGlyphs.dynamicPPPPP
            DynamicLevel.PPPP  -> SmuflGlyphs.dynamicPPPP
            DynamicLevel.PPP   -> SmuflGlyphs.dynamicPPP
            DynamicLevel.PP    -> SmuflGlyphs.dynamicPP
            DynamicLevel.P     -> SmuflGlyphs.dynamicPiano
            DynamicLevel.MP    -> SmuflGlyphs.dynamicMP
            DynamicLevel.MF    -> SmuflGlyphs.dynamicMF
            DynamicLevel.PF    -> SmuflGlyphs.dynamicPF
            DynamicLevel.F     -> SmuflGlyphs.dynamicForte
            DynamicLevel.FF    -> SmuflGlyphs.dynamicFF
            DynamicLevel.FFF   -> SmuflGlyphs.dynamicFFF
            DynamicLevel.FFFF  -> SmuflGlyphs.dynamicFFFF
            DynamicLevel.FFFFF -> SmuflGlyphs.dynamicFFFFF
            DynamicLevel.FFFFFF -> SmuflGlyphs.dynamicFFFFFF
            DynamicLevel.NIENTE -> SmuflGlyphs.dynamicNiente
            DynamicLevel.FP    -> SmuflGlyphs.dynamicFortePiano
            DynamicLevel.SF    -> SmuflGlyphs.dynamicSforzando1
            DynamicLevel.SFP   -> SmuflGlyphs.dynamicSforzandoPiano
            DynamicLevel.SFPP  -> SmuflGlyphs.dynamicSforzandoPianissimo
            DynamicLevel.SFZ   -> SmuflGlyphs.dynamicSforzato
            DynamicLevel.SFZP  -> SmuflGlyphs.dynamicSforzatoPiano
            DynamicLevel.SFFZ  -> SmuflGlyphs.dynamicSforzatoFF
            DynamicLevel.FZ    -> SmuflGlyphs.dynamicForzando
            DynamicLevel.RF    -> SmuflGlyphs.dynamicRinforzando1
            DynamicLevel.RFZ   -> SmuflGlyphs.dynamicRinforzando2
        }
    )
}
