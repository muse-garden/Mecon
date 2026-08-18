package com.mecon.plugins.chord

import com.mecon.theory.ChordSymbolDisplayStyle

enum class ChordAnalysisScoreDisplayMode { CLASSIC, TIMELINE }

object ChordSymbolDisplaySettings {
    var style: ChordSymbolDisplayStyle = ChordSymbolDisplayStyle.LETTER
    var scoreDisplayMode: ChordAnalysisScoreDisplayMode = ChordAnalysisScoreDisplayMode.CLASSIC
}
