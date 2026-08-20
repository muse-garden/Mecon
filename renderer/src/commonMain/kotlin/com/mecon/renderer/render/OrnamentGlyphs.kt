package com.mecon.renderer.render

import com.mecon.api.storage.events.OrnamentKind
import com.mecon.renderer.smufl.GlyphInfo
import com.mecon.renderer.smufl.SmuflGlyphs

/** Single source of truth for point and settled ornament glyph selection. */
object OrnamentGlyphs {
    fun glyphFor(kind: OrnamentKind): GlyphInfo = when (kind) {
        OrnamentKind.TRILL -> SmuflGlyphs.ornamentTrill
        OrnamentKind.MORDENT -> SmuflGlyphs.ornamentMordent
        OrnamentKind.INVERTED_MORDENT -> SmuflGlyphs.ornamentShortTrill
        OrnamentKind.TREMBLEMENT -> SmuflGlyphs.ornamentTremblement
        OrnamentKind.TREMBLEMENT_COUPERIN -> SmuflGlyphs.ornamentTremblementCouperin
        OrnamentKind.MORDENT_UPPER_PREFIX -> SmuflGlyphs.ornamentPrecompMordentUpperPrefix
        OrnamentKind.INVERTED_MORDENT_UPPER_PREFIX -> SmuflGlyphs.ornamentPrecompInvertedMordentUpperPrefix
        OrnamentKind.MORDENT_RELEASE -> SmuflGlyphs.ornamentPrecompMordentRelease
        OrnamentKind.TURN -> SmuflGlyphs.ornamentTurn
        OrnamentKind.INVERTED_TURN -> SmuflGlyphs.ornamentTurnInverted
        OrnamentKind.TURN_SLASH -> SmuflGlyphs.ornamentTurnSlash
    }
}
