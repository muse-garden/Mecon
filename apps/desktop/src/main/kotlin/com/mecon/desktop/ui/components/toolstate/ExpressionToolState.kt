package com.mecon.desktop.ui.components.toolstate

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mecon.api.storage.events.DynamicLevel
import com.mecon.api.storage.events.HairpinStyle
import com.mecon.api.storage.events.HairpinType
import com.mecon.api.storage.events.OctaveShiftType
import com.mecon.api.storage.events.TempoMarkType
import com.mecon.api.storage.tracks.BreathMarkScope
import com.mecon.api.storage.tracks.BreathMarkShape
import com.mecon.api.storage.tracks.FermataShape
import com.mecon.desktop.ui.components.PauseMarkKind
import com.mecon.api.storage.events.OrnamentKind
import com.mecon.api.storage.ArpeggioType

/** State owned by dynamics, pauses, octave shifts and tempo tools. */
class ExpressionToolState {
    var dynamicsSectionExpanded by mutableStateOf(true)
    var pausesSectionExpanded by mutableStateOf(true)
    var octaveSectionExpanded by mutableStateOf(true)
    var tempoSectionExpanded by mutableStateOf(true)
    var ornamentsSectionExpanded by mutableStateOf(true)
    var selectedDynamic by mutableStateOf(DynamicLevel.MF)
    var selectedPauseKind by mutableStateOf(PauseMarkKind.FERMATA)
    var selectedFermataShape by mutableStateOf(FermataShape.NORMAL)
    var selectedBreathShape by mutableStateOf(BreathMarkShape.COMMA)
    var selectedBreathScope by mutableStateOf(BreathMarkScope.VOICE)
    var selectedHairpinType by mutableStateOf(HairpinType.CRESCENDO)
    var selectedHairpinStyle by mutableStateOf(HairpinStyle.WEDGE)
    var selectedOctaveShift by mutableStateOf(OctaveShiftType.OTTAVA)
    var selectedTempoMark by mutableStateOf(TempoMarkType.METRONOME)
    var selectedTempoBpm by mutableStateOf(120f)
    var selectedOrnamentKind by mutableStateOf(OrnamentKind.TRILL)
    var selectedOrnamentWavy by mutableStateOf(false)
    var selectedArpeggioType by mutableStateOf(ArpeggioType.NORMAL)

    fun reset() {
        dynamicsSectionExpanded = true
        pausesSectionExpanded = true
        octaveSectionExpanded = true
        tempoSectionExpanded = true
        ornamentsSectionExpanded = true
        selectedDynamic = DynamicLevel.MF
        selectedPauseKind = PauseMarkKind.FERMATA
        selectedFermataShape = FermataShape.NORMAL
        selectedBreathShape = BreathMarkShape.COMMA
        selectedBreathScope = BreathMarkScope.VOICE
        selectedHairpinType = HairpinType.CRESCENDO
        selectedHairpinStyle = HairpinStyle.WEDGE
        selectedOctaveShift = OctaveShiftType.OTTAVA
        selectedTempoMark = TempoMarkType.METRONOME
        selectedTempoBpm = 120f
        selectedOrnamentKind = OrnamentKind.TRILL
        selectedOrnamentWavy = false
        selectedArpeggioType = ArpeggioType.NORMAL
    }
}
