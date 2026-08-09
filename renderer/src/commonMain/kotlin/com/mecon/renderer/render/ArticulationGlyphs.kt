package com.mecon.renderer.render

import com.mecon.api.storage.Articulation
import com.mecon.renderer.smufl.GlyphInfo
import com.mecon.renderer.smufl.SmuflGlyphs
import com.mecon.api.storage.tracks.FermataShape

/**
 * Maps [Articulation]s to SMuFL glyphs and defines their stacking order.
 *
 * Kept separate from [ArticulationLayoutComputer] so the (pure) mapping can be
 * unit-tested without the full layout pipeline.
 */
internal object ArticulationGlyphs {

    /** The above/below SMuFL glyph for an articulation and fermata shape. */
    fun glyphFor(
        art: Articulation,
        above: Boolean,
        fermataShape: FermataShape = FermataShape.NORMAL,
    ): GlyphInfo? = when (art) {
        Articulation.STACCATO ->
            if (above) SmuflGlyphs.articStaccatoAbove else SmuflGlyphs.articStaccatoBelow
        Articulation.SPICCATO, Articulation.STACCATISSIMO ->
            if (above) SmuflGlyphs.articStaccatissimoAbove else SmuflGlyphs.articStaccatissimoBelow
        Articulation.TENUTO ->
            if (above) SmuflGlyphs.articTenutoAbove else SmuflGlyphs.articTenutoBelow
        Articulation.ACCENT ->
            if (above) SmuflGlyphs.articAccentAbove else SmuflGlyphs.articAccentBelow
        Articulation.MARCATO ->
            if (above) SmuflGlyphs.articMarcatoAbove else SmuflGlyphs.articMarcatoBelow
        Articulation.FERMATA -> when (fermataShape) {
            FermataShape.VERY_SHORT ->
                if (above) SmuflGlyphs.fermataVeryShortAbove else SmuflGlyphs.fermataVeryShortBelow
            FermataShape.SHORT ->
                if (above) SmuflGlyphs.fermataShortAbove else SmuflGlyphs.fermataShortBelow
            FermataShape.NORMAL ->
                if (above) SmuflGlyphs.fermataAbove else SmuflGlyphs.fermataBelow
            FermataShape.LONG ->
                if (above) SmuflGlyphs.fermataLongAbove else SmuflGlyphs.fermataLongBelow
            FermataShape.VERY_LONG ->
                if (above) SmuflGlyphs.fermataVeryLongAbove else SmuflGlyphs.fermataVeryLongBelow
        }
    }

    /** Stacking rank: lower = nearer the note, higher = further out. */
    fun stackRank(art: Articulation): Int = when (art) {
        Articulation.STACCATO, Articulation.SPICCATO, Articulation.STACCATISSIMO, Articulation.TENUTO -> 0
        Articulation.ACCENT -> 1
        Articulation.MARCATO -> 2
        Articulation.FERMATA -> 3
    }
}
