package com.mecon.desktop.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mecon.api.primitive.Accidental
import com.mecon.api.primitive.BarlineType
import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.storage.BeamingInfo
import com.mecon.api.storage.Articulation
import com.mecon.api.storage.NavigationMark
import com.mecon.api.storage.tracks.Clef
import com.mecon.api.storage.events.DynamicLevel
import com.mecon.api.storage.events.HairpinStyle
import com.mecon.api.storage.events.HairpinType
import com.mecon.api.storage.events.OctaveShiftType
import com.mecon.api.storage.events.TempoMarkType
import com.mecon.api.storage.tracks.BreathMarkScope
import com.mecon.api.storage.tracks.BreathMarkShape
import com.mecon.api.storage.tracks.FermataShape
import com.mecon.api.storage.events.OrnamentKind
import com.mecon.api.storage.ArpeggioType
import com.mecon.desktop.ui.components.lefttoolbar.NotePalette
import com.mecon.desktop.ui.components.lefttoolbar.ScoreElementPalette
import com.mecon.desktop.ui.components.lefttoolbar.ToolColumn
import com.mecon.desktop.ui.rememberBravuraFont

data class LeftToolbarSelectionState(
    val notes: PaletteSelectionInfo = PaletteSelectionInfo.EMPTY,
    val clef: ClefSelectionInfo = ClefSelectionInfo.EMPTY,
    val key: KeySignatureSelectionInfo = KeySignatureSelectionInfo.EMPTY,
    val time: TimeSignatureSelectionInfo = TimeSignatureSelectionInfo.EMPTY,
    val barline: BarlineSelectionInfo = BarlineSelectionInfo.EMPTY,
)

data class NotePaletteActions(
    val editDurationBase: (DurationBase) -> Unit = {},
    val editDots: (Int) -> Unit = {},
    val editAccidental: (Accidental) -> Unit = {},
    val editTie: () -> Unit = {},
    val addSlur: () -> Unit = {},
    val editVoice: (Int) -> Unit = {},
    val applyTuplet: (Int) -> Unit = {},
    val editBeaming: (BeamingInfo?) -> Unit = {},
    val groupBeam: () -> Unit = {},
    val editArticulation: (Articulation) -> Unit = {},
    val convertToSmallNotes: () -> Unit = {},
)

data class ScoreElementPaletteActions(
    val pickClef: (Clef) -> Unit = {},
    val pickTimeSignature: (TimeSignature) -> Unit = {},
    val pickKeySignature: (KeySignature) -> Unit = {},
    val pickDynamic: (DynamicLevel) -> Unit = {},
    val pickFermata: (FermataShape) -> Unit = {},
    val pickBreath: (BreathMarkShape, BreathMarkScope) -> Unit = { _, _ -> },
    val pickHairpin: (HairpinType, HairpinStyle) -> Unit = { _, _ -> },
    val pickOctaveShift: (OctaveShiftType) -> Unit = {},
    val pickTempo: (TempoMarkType) -> Unit = {},
    val pickBarline: (BarlineType, Int) -> Unit = { _, _ -> },
    val pickVolta: (Int) -> Unit = {},
    val pickNavigation: (NavigationMark) -> Unit = {},
    val pickOrnament: (OrnamentKind, Boolean) -> Unit = { _, _ -> },
    val pickArpeggio: (ArpeggioType) -> Unit = {},
)

data class LeftToolbarActions(
    val notes: NotePaletteActions = NotePaletteActions(),
    val scoreElements: ScoreElementPaletteActions = ScoreElementPaletteActions(),
)

/**
 * Left sidebar: a narrow tool column plus an expandable note-element palette column.
 *
 * The palette serves two roles depending on the active tool. In NOTE mode it sets the *next inserted*
 * note (driven by [state]); in SELECT/MARQUEE mode with a non-empty selection it reflects and edits
 * the *selected* notes — highlight comes from [selectionInfo] and clicks fire the `onEdit*` callbacks.
 * The palette stays visible across all three tools once opened.
 *
 * The tool column, note palette, and score-element palette are split into
 * `com.mecon.desktop.ui.components.lefttoolbar`; this file only wires them together.
 */
@Composable
fun LeftToolbar(
    state: NoteToolState,
    selection: LeftToolbarSelectionState = LeftToolbarSelectionState(),
    actions: LeftToolbarActions = LeftToolbarActions(),
    showScoreElementTool: Boolean = true,
    showVoiceControls: Boolean = true,
    modifier: Modifier = Modifier
) {
    val bravura = rememberBravuraFont()
    Row(modifier = modifier.fillMaxHeight()) {
        ToolColumn(state, bravura, showScoreElementTool)
        if (state.paletteExpanded) {
            NotePalette(
                state = state,
                bravura = bravura,
                selectionInfo = selection.notes,
                actions = actions.notes,
                showVoiceControls = showVoiceControls,
            )
        }
        if (showScoreElementTool && state.scoreElementPaletteExpanded) {
            ScoreElementPalette(
                state,
                selection,
                actions.scoreElements,
            )
        }
    }
}
