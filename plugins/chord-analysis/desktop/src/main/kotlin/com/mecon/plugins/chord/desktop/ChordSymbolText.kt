package com.mecon.plugins.chord.desktop

import androidx.compose.ui.text.AnnotatedString
import com.mecon.desktop.uikit.text.toAnnotatedString
import com.mecon.theory.ChordSymbol
import com.mecon.theory.ChordSymbolDisplayStyle

/**
 * Render a chord [ChordSymbol] as a styled [AnnotatedString] using the shared
 * [FormattedText][com.mecon.api.render.FormattedText] styling convention — the
 * same one the on-staff annotation renderer consumes, so the panel and the score
 * stay visually consistent (degree numerals bold, quality suffix at 0.75×).
 */
internal fun chordSymbolAnnotatedText(
    symbol: ChordSymbol,
    displayStyle: ChordSymbolDisplayStyle,
): AnnotatedString = symbol.toFormattedText(displayStyle).toAnnotatedString()
