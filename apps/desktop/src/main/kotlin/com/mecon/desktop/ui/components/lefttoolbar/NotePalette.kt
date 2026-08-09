package com.mecon.desktop.ui.components.lefttoolbar

import com.mecon.desktop.uikit.components.meconTextInputFocus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.primitive.Accidental
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.api.storage.BeamingInfo
import com.mecon.api.storage.Articulation
import com.mecon.api.storage.GraceNoteType
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.features.freepractice.FreePracticeToolbarSpec
import com.mecon.desktop.ui.components.EditTool
import com.mecon.desktop.ui.components.NotePaletteActions
import com.mecon.desktop.ui.components.NoteToolState
import com.mecon.desktop.ui.components.NoteEntryKind
import com.mecon.desktop.ui.components.PaletteSelectionInfo
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.desktop.uikit.components.MeconDropdownItem
import com.mecon.desktop.uikit.components.MeconDropdownMenu

/** The expandable palette column: note durations, rest, dots, accidentals, tie and beam toggles. */
@Composable
internal fun NotePalette(
    state: NoteToolState,
    bravura: FontFamily?,
    selectionInfo: PaletteSelectionInfo,
    actions: NotePaletteActions,
    showVoiceControls: Boolean = true,
) {
    // Two distinct conditions (mirroring ShortcutDispatcher / SelectionEditor.active so palette clicks
    // and keyboard behave identically):
    //   reflectSelection — highlight comes from the selection (SELECT/MARQUEE); empty selection lights
    //                      nothing. In NOTE mode highlight shows the entry defaults instead.
    //   editingSelection — a click edits the selection. Only when one actually exists; otherwise the
    //                      click sets a default and *starts note entry* (enterNoteEntry).
    val reflectSelection = state.tool != EditTool.NOTE
    val editingSelection = reflectSelection && selectionInfo.editable
    val dotSelected = { n: Int -> if (reflectSelection) selectionInfo.dots == n else state.dots == n }
    val onDotClick = { n: Int ->
        if (editingSelection) actions.editDots(n) else { state.enterNoteEntry(); state.toggleDots(n) }
    }
    Column(
        modifier = Modifier
            .width(118.dp)
            .fillMaxHeight()
            .background(MeconColors.Background)
            .drawBehind {
                val strokeWidth = 1.dp.toPx()
                drawLine(
                    color = MeconColors.Border,
                    start = Offset(size.width - strokeWidth / 2, 0f),
                    end = Offset(size.width - strokeWidth / 2, size.height),
                    strokeWidth = strokeWidth
                )
            }
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SectionLabel("声部")
        if (showVoiceControls) {
        PaletteRow {
            VoiceActionButton(1, state, reflectSelection, editingSelection, selectionInfo, actions.editVoice)
            VoiceActionButton(2, state, reflectSelection, editingSelection, selectionInfo, actions.editVoice)
            Spacer(Modifier.size(CELL))
        }
        PaletteRow {
            VoiceActionButton(3, state, reflectSelection, editingSelection, selectionInfo, actions.editVoice)
            VoiceActionButton(4, state, reflectSelection, editingSelection, selectionInfo, actions.editVoice)
            Spacer(Modifier.size(CELL))
        }

        }
        Spacer(Modifier.height(2.dp))
        SectionLabel("时值")
        // Keep the everyday range visible. Breve and 64th join the genuinely uncommon values
        // behind the chevron so the default palette stays compact.
        PaletteRow {
            DurationButton(state, DurationBase.WHOLE, Smufl.NOTE_WHOLE, bravura, reflectSelection, editingSelection, selectionInfo, actions.editDurationBase)
            DurationButton(state, DurationBase.HALF, Smufl.NOTE_HALF, bravura, reflectSelection, editingSelection, selectionInfo, actions.editDurationBase)
            DurationButton(state, DurationBase.QUARTER, Smufl.NOTE_QUARTER, bravura, reflectSelection, editingSelection, selectionInfo, actions.editDurationBase)
        }
        PaletteRow {
            DurationButton(state, DurationBase.EIGHTH, Smufl.NOTE_8TH, bravura, reflectSelection, editingSelection, selectionInfo, actions.editDurationBase)
            DurationButton(state, DurationBase.SIXTEENTH, Smufl.NOTE_16TH, bravura, reflectSelection, editingSelection, selectionInfo, actions.editDurationBase)
            DurationButton(state, DurationBase.THIRTY_SECOND, Smufl.NOTE_32ND, bravura, reflectSelection, editingSelection, selectionInfo, actions.editDurationBase)
        }
        PaletteRow {
            // Rest is an input-only mode (sets the next inserted element); it isn't an editable property,
            // so it highlights only in NOTE mode, and clicking it starts note entry with rest toggled.
            GlyphToggleButton(
                glyph = Smufl.REST_QUARTER,
                font = bravura,
                selected = !reflectSelection && state.restMode,
                tooltip = "休止符模式",
            ) {
                state.enterNoteEntry(); state.restMode = !state.restMode
            }
            Spacer(Modifier.size(CELL))
            Spacer(Modifier.size(CELL))
        }
        PaletteRow {
            GlyphToggleButton(
                glyph = Smufl.AUGMENTATION_DOT,
                font = bravura,
                selected = dotSelected(1),
                sizeSp = DOT_GLYPH_SIZE,
                tooltip = "单附点",
            ) { onDotClick(1) }
            GlyphToggleButton(
                glyph = Smufl.AUGMENTATION_DOT.repeat(2),
                font = bravura,
                selected = dotSelected(2),
                sizeSp = DOT_GLYPH_SIZE,
                letterSpacing = 3.sp,
                tooltip = "双附点",
            ) { onDotClick(2) }
            ChevronButton(
                expanded = state.uncommonDurationsExpanded,
                tooltip = if (state.uncommonDurationsExpanded) "收起更多时值" else "展开更多时值",
            ) {
                state.uncommonDurationsExpanded = !state.uncommonDurationsExpanded
            }
        }
        if (state.uncommonDurationsExpanded) {
            PaletteRow {
                DurationButton(state, DurationBase.BREVE, Smufl.NOTE_DOUBLE_WHOLE, bravura, reflectSelection, editingSelection, selectionInfo, actions.editDurationBase)
                DurationButton(state, DurationBase.SIXTY_FOURTH, Smufl.NOTE_64TH, bravura, reflectSelection, editingSelection, selectionInfo, actions.editDurationBase)
                DurationButton(state, DurationBase.LONGA, Smufl.NOTE_LONGA, bravura, reflectSelection, editingSelection, selectionInfo, actions.editDurationBase)
            }
            PaletteRow {
                DurationButton(state, DurationBase.MAXIMA, Smufl.NOTE_MAXIMA, bravura, reflectSelection, editingSelection, selectionInfo, actions.editDurationBase)
                DurationButton(state, DurationBase.ONE_TWENTY_EIGHTH, Smufl.NOTE_128TH, bravura, reflectSelection, editingSelection, selectionInfo, actions.editDurationBase)
                // 256th has no DurationBase yet — shown for layout parity, disabled.
                DisabledGlyph(Smufl.NOTE_256TH, bravura)
            }
        }

        Spacer(Modifier.height(2.dp))
        SectionLabel("变音记号")
        PaletteRow {
            AccidentalButton(state, Accidental.SHARP, Smufl.ACC_SHARP, bravura, reflectSelection, editingSelection, selectionInfo, actions.editAccidental)
            AccidentalButton(state, Accidental.FLAT, Smufl.ACC_FLAT, bravura, reflectSelection, editingSelection, selectionInfo, actions.editAccidental)
            AccidentalButton(state, Accidental.NATURAL, Smufl.ACC_NATURAL, bravura, reflectSelection, editingSelection, selectionInfo, actions.editAccidental)
        }
        PaletteRow {
            AccidentalButton(state, Accidental.DOUBLE_SHARP, Smufl.ACC_DOUBLE_SHARP, bravura, reflectSelection, editingSelection, selectionInfo, actions.editAccidental)
            AccidentalButton(state, Accidental.DOUBLE_FLAT, Smufl.ACC_DOUBLE_FLAT, bravura, reflectSelection, editingSelection, selectionInfo, actions.editAccidental)
            DisabledGlyph(null, bravura) // accidental expand — no logic yet
        }

        Spacer(Modifier.height(2.dp))
        SectionLabel("连音线 / 圆滑线")
        PaletteRow {
            CurveNotePairButton(
                samePitch = true,
                font = bravura,
                selected = if (reflectSelection) selectionInfo.tieOut == true else state.tieMode,
                enabled = true,
                tooltip = "连音线",
                onClick = { if (editingSelection) actions.editTie() else { state.enterNoteEntry(); state.tieMode = !state.tieMode } },
            )
            CurveNotePairButton(
                samePitch = false,
                font = bravura,
                selected = false,
                enabled = editingSelection && selectionInfo.canAddSlur,
                tooltip = "圆滑线",
                onClick = actions.addSlur,
            )
        }

        Spacer(Modifier.height(2.dp))
        SectionLabel("装饰音 / 小音符")
        PaletteRow {
            GraceModeButton(
                icon = GraceModeIcon.APPOGGIATURA,
                font = bravura,
                selected = !reflectSelection &&
                    state.noteEntryKind == NoteEntryKind.GRACE &&
                    state.graceNoteType == GraceNoteType.APPOGGIATURA,
                tooltip = "倚音：在主音前输入占时装饰音",
            ) {
                if (!editingSelection) {
                    if (
                        state.noteEntryKind == NoteEntryKind.GRACE &&
                        state.graceNoteType == GraceNoteType.APPOGGIATURA
                    ) {
                        state.enterNormalEntry()
                    } else {
                        state.graceNoteType = GraceNoteType.APPOGGIATURA
                        state.enterGraceEntry()
                    }
                }
            }
            GraceModeButton(
                icon = GraceModeIcon.ACCIACCATURA,
                font = bravura,
                selected = !reflectSelection &&
                    state.noteEntryKind == NoteEntryKind.GRACE &&
                    state.graceNoteType == GraceNoteType.ACCIACCATURA,
                tooltip = "短倚音：输入带斜线的短装饰音",
            ) {
                if (!editingSelection) {
                    if (
                        state.noteEntryKind == NoteEntryKind.GRACE &&
                        state.graceNoteType == GraceNoteType.ACCIACCATURA
                    ) {
                        state.enterNormalEntry()
                    } else {
                        state.graceNoteType = GraceNoteType.ACCIACCATURA
                        state.enterGraceEntry()
                    }
                }
            }
            GraceModeButton(
                icon = GraceModeIcon.SMALL_NOTE,
                font = bravura,
                // This is a one-shot "convert selection" action. The input controller may remain
                // scoped to the resulting region, but the toolbar action itself is never latched.
                selected = false,
                tooltip = "小音符：先选择休止符区域，再按任意时值细分输入",
            ) {
                if (editingSelection && selectionInfo.allRests) {
                    actions.convertToSmallNotes()
                }
            }
        }

        Spacer(Modifier.height(2.dp))
        SectionLabel("连音组")
        val tupletTotal = if (reflectSelection && selectionInfo.durationBase != null) {
            Duration(selectionInfo.durationBase, selectionInfo.dots ?: 0)
        } else {
            state.duration
        }
        val suggestedTuplets = suggestedTupletCounts(tupletTotal)
        PaletteRow {
            suggestedTuplets.take(3).forEach { count ->
                TupletButton(count, reflectSelection, selectionInfo, state, editingSelection, actions.applyTuplet)
            }
            repeat(3 - suggestedTuplets.take(3).size) { Spacer(Modifier.size(CELL)) }
        }
        val recent = state.recentTupletCounts.filter { it !in suggestedTuplets }.take(3)
        if (recent.isNotEmpty()) {
            PaletteRow {
                recent.forEach { count ->
                    TupletButton(count, reflectSelection, selectionInfo, state, editingSelection, actions.applyTuplet)
                }
                repeat(3 - recent.size) { Spacer(Modifier.size(CELL)) }
            }
        }
        PaletteRow {
            TupletCountField(
                value = state.customTupletText,
                onValueChange = { state.customTupletText = it.filter(Char::isDigit).take(2) },
            )
            TextToggleButton("✓", selected = false, tooltip = "应用自定义连音组") {
                val count = state.customTupletText.toIntOrNull()
                if (count != null && count > 1) {
                    if (editingSelection) actions.applyTuplet(count) else { state.enterNoteEntry(); state.pickTupletCount(count) }
                }
            }
            TextToggleButton(
                "×",
                selected = !reflectSelection && state.tupletCount == null,
                tooltip = "清除连音组",
            ) {
                state.tupletCount = null
            }
        }

        Spacer(Modifier.height(2.dp))
        SectionLabel("符杠")

        // Effective beam state: from selection (SELECT mode) or from insertion beaming (NOTE mode).
        val beamNoneActive = if (reflectSelection)
            selectionInfo.effectiveBeamLeft == false && selectionInfo.effectiveBeamRight == false
        else
            state.insertionBeaming == BeamingInfo.NONE
        val beamBothActive = if (reflectSelection)
            selectionInfo.effectiveBeamLeft == true && selectionInfo.effectiveBeamRight == true
        else
            state.insertionBeaming == BeamingInfo.middle()
        val beamRightActive = if (reflectSelection)
            selectionInfo.effectiveBeamLeft == false && selectionInfo.effectiveBeamRight == true
        else
            state.insertionBeaming == BeamingInfo.start()
        val beamLeftActive = if (reflectSelection)
            selectionInfo.effectiveBeamLeft == true && selectionInfo.effectiveBeamRight == false
        else
            state.insertionBeaming == BeamingInfo.end()

        // Row 1: isolated / both-connected / group
        PaletteRow {
            // 独立音符，无符杠 — uses SMuFL eighth note (has flag, no beam stub)
            BeamGlyphButton(
                glyph = Smufl.NOTE_8TH,
                font = bravura,
                selected = beamNoneActive,
                tooltip = "独立音符，无符杠",
                onClick = {
                    if (editingSelection) actions.editBeaming(BeamingInfo.NONE)
                    else { state.enterNoteEntry(); state.toggleInsertionBeaming(BeamingInfo.NONE) }
                }
            )
            // 左右都连符杠 — custom icon
            BeamPatternButton(
                beamLeft = true, beamRight = true, isGroup = false,
                font = bravura,
                selected = beamBothActive,
                tooltip = "左右都连符杠",
                onClick = {
                    if (editingSelection) actions.editBeaming(BeamingInfo.middle())
                    else { state.enterNoteEntry(); state.toggleInsertionBeaming(BeamingInfo.middle()) }
                }
            )
            // 将选中音符作为一个符杠组 — disabled in NOTE mode
            BeamPatternButton(
                beamLeft = false, beamRight = false, isGroup = true,
                font = bravura,
                selected = false,
                enabled = editingSelection && selectionInfo.canGroupBeam,
                tooltip = "将选中音符作为一个符杠组",
                onClick = { if (editingSelection && selectionInfo.canGroupBeam) actions.groupBeam() }
            )
        }
        // Row 2: right-only / left-only (one-shot in insert mode)
        PaletteRow {
            BeamPatternButton(
                beamLeft = false, beamRight = true, isGroup = false,
                font = bravura,
                selected = beamRightActive,
                tooltip = "符杠仅连右",
                onClick = {
                    if (editingSelection) actions.editBeaming(BeamingInfo.start())
                    else { state.enterNoteEntry(); state.toggleInsertionBeaming(BeamingInfo.start()) }
                }
            )
            BeamPatternButton(
                beamLeft = true, beamRight = false, isGroup = false,
                font = bravura,
                selected = beamLeftActive,
                tooltip = "符杠仅连左",
                onClick = {
                    if (editingSelection) actions.editBeaming(BeamingInfo.end())
                    else { state.enterNoteEntry(); state.toggleInsertionBeaming(BeamingInfo.end()) }
                }
            )
            Spacer(Modifier.size(CELL))
        }

        Spacer(Modifier.height(2.dp))
        // The main editor keeps articulation choices visible; only the free-practice horizontal
        // palette uses a collapse control to protect its limited vertical space.
        SectionLabel("演奏法")
        articulationGlyphs().chunked(3).forEach { row ->
            PaletteRow {
                row.forEach { (articulation, glyph) ->
                    val selected = if (editingSelection) articulation in selectionInfo.articulations
                    else articulation in state.articulations
                    GlyphToggleButton(
                        glyph = glyph,
                        font = bravura,
                        selected = selected,
                        tooltip = articulationTooltip(articulation),
                    ) {
                        if (editingSelection) actions.editArticulation(articulation)
                        else {
                            state.enterNoteEntry()
                            state.toggleArticulation(articulation)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Horizontal, wrapping presentation of the same note-entry/edit actions used by [NotePalette].
 *
 * Groups stay flat and compact while [FlowRow] moves whole groups onto another line when the
 * editor becomes narrow. Optional numbered voice controls use the same visual language as the
 * main editor's note palette.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HorizontalNotePalette(
    state: NoteToolState,
    bravura: FontFamily?,
    selectionInfo: PaletteSelectionInfo,
    actions: NotePaletteActions,
    showScoreElementTool: Boolean,
    voiceNumbers: List<Int>,
    voiceSelectionInfo: PaletteSelectionInfo,
    onVoiceEdit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reflectSelection = state.tool != EditTool.NOTE
    val editingSelection = reflectSelection && selectionInfo.editable
    val dotSelected = { count: Int ->
        if (reflectSelection) selectionInfo.dots == count else state.dots == count
    }
    val onDotClick = { count: Int ->
        if (editingSelection) actions.editDots(count)
        else {
            state.enterNoteEntry()
            state.toggleDots(count)
        }
    }

    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .background(MeconColors.Background)
            .border(1.dp, MeconColors.Border)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ToolbarGroup("tool") {
            ToolButtonRow(state, bravura, showScoreElementTool)
        }
        if (state.paletteExpanded) {
        if (voiceNumbers.isNotEmpty()) {
            ToolbarSeparator()
            ToolbarGroup("voice") {
                voiceNumbers.forEach { number ->
                    VoiceActionButton(
                        number = number,
                        state = state,
                        reflectSelection = reflectSelection,
                        editingSelection = reflectSelection && voiceSelectionInfo.editable,
                        selectionInfo = voiceSelectionInfo,
                        onEdit = onVoiceEdit,
                    )
                }
            }
        }

        ToolbarSeparator()
        ToolbarGroup("duration") {
            DurationButton(state, DurationBase.WHOLE, Smufl.NOTE_WHOLE, bravura, reflectSelection, editingSelection, selectionInfo, actions.editDurationBase)
            DurationButton(state, DurationBase.HALF, Smufl.NOTE_HALF, bravura, reflectSelection, editingSelection, selectionInfo, actions.editDurationBase)
            DurationButton(state, DurationBase.QUARTER, Smufl.NOTE_QUARTER, bravura, reflectSelection, editingSelection, selectionInfo, actions.editDurationBase)
            DurationButton(state, DurationBase.EIGHTH, Smufl.NOTE_8TH, bravura, reflectSelection, editingSelection, selectionInfo, actions.editDurationBase)
            DurationButton(state, DurationBase.SIXTEENTH, Smufl.NOTE_16TH, bravura, reflectSelection, editingSelection, selectionInfo, actions.editDurationBase)
            DurationButton(state, DurationBase.THIRTY_SECOND, Smufl.NOTE_32ND, bravura, reflectSelection, editingSelection, selectionInfo, actions.editDurationBase)
            GlyphToggleButton(
                glyph = Smufl.REST_QUARTER,
                font = bravura,
                selected = !reflectSelection && state.restMode,
                tooltip = "休止符模式",
            ) {
                state.enterNoteEntry()
                state.restMode = !state.restMode
            }
            GlyphToggleButton(
                glyph = Smufl.AUGMENTATION_DOT,
                font = bravura,
                selected = dotSelected(1),
                sizeSp = DOT_GLYPH_SIZE,
                tooltip = "单附点",
            ) {
                onDotClick(1)
            }
            GlyphToggleButton(
                glyph = Smufl.AUGMENTATION_DOT.repeat(2),
                font = bravura,
                selected = dotSelected(2),
                sizeSp = DOT_GLYPH_SIZE,
                letterSpacing = 3.sp,
                tooltip = "双附点",
            ) {
                onDotClick(2)
            }
            ChevronButton(
                expanded = state.uncommonDurationsExpanded,
                horizontal = true,
                tooltip = if (state.uncommonDurationsExpanded) "收起更多时值" else "展开更多时值",
            ) {
                state.uncommonDurationsExpanded = !state.uncommonDurationsExpanded
            }
            if (state.uncommonDurationsExpanded) {
                DurationButton(state, DurationBase.BREVE, Smufl.NOTE_DOUBLE_WHOLE, bravura, reflectSelection, editingSelection, selectionInfo, actions.editDurationBase)
                DurationButton(state, DurationBase.SIXTY_FOURTH, Smufl.NOTE_64TH, bravura, reflectSelection, editingSelection, selectionInfo, actions.editDurationBase)
                DurationButton(state, DurationBase.LONGA, Smufl.NOTE_LONGA, bravura, reflectSelection, editingSelection, selectionInfo, actions.editDurationBase)
                DurationButton(state, DurationBase.MAXIMA, Smufl.NOTE_MAXIMA, bravura, reflectSelection, editingSelection, selectionInfo, actions.editDurationBase)
                DurationButton(state, DurationBase.ONE_TWENTY_EIGHTH, Smufl.NOTE_128TH, bravura, reflectSelection, editingSelection, selectionInfo, actions.editDurationBase)
            }
        }

        ToolbarSeparator()
        ToolbarGroup("accidental") {
            AccidentalButton(state, Accidental.SHARP, Smufl.ACC_SHARP, bravura, reflectSelection, editingSelection, selectionInfo, actions.editAccidental)
            AccidentalButton(state, Accidental.FLAT, Smufl.ACC_FLAT, bravura, reflectSelection, editingSelection, selectionInfo, actions.editAccidental)
            AccidentalButton(state, Accidental.NATURAL, Smufl.ACC_NATURAL, bravura, reflectSelection, editingSelection, selectionInfo, actions.editAccidental)
            AccidentalButton(state, Accidental.DOUBLE_SHARP, Smufl.ACC_DOUBLE_SHARP, bravura, reflectSelection, editingSelection, selectionInfo, actions.editAccidental)
            AccidentalButton(state, Accidental.DOUBLE_FLAT, Smufl.ACC_DOUBLE_FLAT, bravura, reflectSelection, editingSelection, selectionInfo, actions.editAccidental)
        }

        ToolbarSeparator()
        ToolbarGroup("curve") {
            CurveNotePairButton(
                samePitch = true,
                font = bravura,
                selected = if (reflectSelection) selectionInfo.tieOut == true else state.tieMode,
                enabled = true,
                tooltip = "连音线",
                onClick = {
                    if (editingSelection) actions.editTie()
                    else {
                        state.enterNoteEntry()
                        state.tieMode = !state.tieMode
                    }
                },
            )
            CurveNotePairButton(
                samePitch = false,
                font = bravura,
                selected = false,
                enabled = editingSelection && selectionInfo.canAddSlur,
                tooltip = "圆滑线",
                onClick = actions.addSlur,
            )
        }

        ToolbarSeparator()
        ToolbarGroup("grace") {
            GraceModeButton(
                icon = GraceModeIcon.APPOGGIATURA,
                font = bravura,
                selected = !reflectSelection &&
                    state.noteEntryKind == NoteEntryKind.GRACE &&
                    state.graceNoteType == GraceNoteType.APPOGGIATURA,
                tooltip = "倚音",
            ) {
                if (!editingSelection) {
                    if (
                        state.noteEntryKind == NoteEntryKind.GRACE &&
                        state.graceNoteType == GraceNoteType.APPOGGIATURA
                    ) state.enterNormalEntry()
                    else {
                        state.graceNoteType = GraceNoteType.APPOGGIATURA
                        state.enterGraceEntry()
                    }
                }
            }
            GraceModeButton(
                icon = GraceModeIcon.ACCIACCATURA,
                font = bravura,
                selected = !reflectSelection &&
                    state.noteEntryKind == NoteEntryKind.GRACE &&
                    state.graceNoteType == GraceNoteType.ACCIACCATURA,
                tooltip = "短倚音",
            ) {
                if (!editingSelection) {
                    if (
                        state.noteEntryKind == NoteEntryKind.GRACE &&
                        state.graceNoteType == GraceNoteType.ACCIACCATURA
                    ) state.enterNormalEntry()
                    else {
                        state.graceNoteType = GraceNoteType.ACCIACCATURA
                        state.enterGraceEntry()
                    }
                }
            }
            GraceModeButton(
                icon = GraceModeIcon.SMALL_NOTE,
                font = bravura,
                selected = false,
                tooltip = "小音符",
            ) {
                if (editingSelection && selectionInfo.allRests) actions.convertToSmallNotes()
            }
        }

        val tupletTotal = if (reflectSelection && selectionInfo.durationBase != null) {
            Duration(selectionInfo.durationBase, selectionInfo.dots ?: 0)
        } else {
            state.duration
        }
        ToolbarSeparator()
        ToolbarGroup("tuplet") {
            HorizontalTupletControl(
                total = tupletTotal,
                reflectSelection = reflectSelection,
                selectionInfo = selectionInfo,
                state = state,
                editingSelection = editingSelection,
                onApply = actions.applyTuplet,
            )
        }

        val beamNoneActive = if (reflectSelection) {
            selectionInfo.effectiveBeamLeft == false && selectionInfo.effectiveBeamRight == false
        } else state.insertionBeaming == BeamingInfo.NONE
        val beamBothActive = if (reflectSelection) {
            selectionInfo.effectiveBeamLeft == true && selectionInfo.effectiveBeamRight == true
        } else state.insertionBeaming == BeamingInfo.middle()
        val beamRightActive = if (reflectSelection) {
            selectionInfo.effectiveBeamLeft == false && selectionInfo.effectiveBeamRight == true
        } else state.insertionBeaming == BeamingInfo.start()
        val beamLeftActive = if (reflectSelection) {
            selectionInfo.effectiveBeamLeft == true && selectionInfo.effectiveBeamRight == false
        } else state.insertionBeaming == BeamingInfo.end()
        ToolbarSeparator()
        ToolbarGroup("beam") {
            BeamGlyphButton(
                glyph = Smufl.NOTE_8TH,
                font = bravura,
                selected = beamNoneActive,
                tooltip = "独立音符",
            ) {
                if (editingSelection) actions.editBeaming(BeamingInfo.NONE)
                else {
                    state.enterNoteEntry()
                    state.toggleInsertionBeaming(BeamingInfo.NONE)
                }
            }
            BeamPatternButton(
                beamLeft = true,
                beamRight = true,
                isGroup = false,
                font = bravura,
                selected = beamBothActive,
                tooltip = "左右都连符杠",
            ) {
                if (editingSelection) actions.editBeaming(BeamingInfo.middle())
                else {
                    state.enterNoteEntry()
                    state.toggleInsertionBeaming(BeamingInfo.middle())
                }
            }
            BeamPatternButton(
                beamLeft = false,
                beamRight = true,
                isGroup = false,
                font = bravura,
                selected = beamRightActive,
                tooltip = "符杠仅连右",
            ) {
                if (editingSelection) actions.editBeaming(BeamingInfo.start())
                else {
                    state.enterNoteEntry()
                    state.toggleInsertionBeaming(BeamingInfo.start())
                }
            }
            BeamPatternButton(
                beamLeft = true,
                beamRight = false,
                isGroup = false,
                font = bravura,
                selected = beamLeftActive,
                tooltip = "符杠仅连左",
            ) {
                if (editingSelection) actions.editBeaming(BeamingInfo.end())
                else {
                    state.enterNoteEntry()
                    state.toggleInsertionBeaming(BeamingInfo.end())
                }
            }
            BeamPatternButton(
                beamLeft = false,
                beamRight = false,
                isGroup = true,
                font = bravura,
                selected = false,
                enabled = editingSelection && selectionInfo.canGroupBeam,
                tooltip = "将选中音符组成符杠组",
                onClick = actions.groupBeam,
            )
        }

        ToolbarSeparator()
        ToolbarGroup("articulation") {
            ChevronButton(
                expanded = state.articulationsExpanded,
                horizontal = true,
                tooltip = if (state.articulationsExpanded) "收起演奏法" else "展开演奏法",
            ) {
                state.articulationsExpanded = !state.articulationsExpanded
            }
            if (state.articulationsExpanded) {
                articulationGlyphs().forEach { (articulation, glyph) ->
                    val selected = if (editingSelection) {
                        articulation in selectionInfo.articulations
                    } else {
                        articulation in state.articulations
                    }
                    GlyphToggleButton(
                        glyph = glyph,
                        font = bravura,
                        selected = selected,
                        tooltip = articulationTooltip(articulation),
                    ) {
                        if (editingSelection) actions.editArticulation(articulation)
                        else {
                            state.enterNoteEntry()
                            state.toggleArticulation(articulation)
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ToolbarGroup(
    id: String,
    content: @Composable FlowRowScope.() -> Unit,
) {
    require(FreePracticeToolbarSpec.descriptor.score.groups.any { it.id == id }) {
        "Unknown free-practice score toolbar group: $id"
    }
    FlowRow(
        modifier = Modifier.testTag("free-practice-score-group:$id"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        content()
    }
}

@Composable
private fun ToolbarSeparator() {
    Box(
        Modifier
            .width(1.dp)
            .height(24.dp)
            .background(MeconColors.Border),
    )
}

/** Horizontal free-practice tuplet control: a count menu followed by one explicit action. */
@Composable
private fun HorizontalTupletControl(
    total: Duration,
    reflectSelection: Boolean,
    selectionInfo: PaletteSelectionInfo,
    state: NoteToolState,
    editingSelection: Boolean,
    onApply: (Int) -> Unit,
) {
    val suggested = suggestedTupletCounts(total)
    val defaultCount = suggested.minOrNull() ?: 2
    val existingCount = if (reflectSelection) selectionInfo.tupletCount else state.tupletCount
    var selectedCount by remember(total, existingCount, defaultCount) {
        mutableStateOf(existingCount?.takeIf { it > 1 } ?: defaultCount)
    }
    var changedSinceOpen by remember(total, existingCount, defaultCount) { mutableStateOf(false) }
    val options = (2..9).toList()

    Box {
        var expanded by remember { mutableStateOf(false) }
        TextToggleButton(
            text = "$selectedCount ▾",
            selected = false,
            tooltip = "选择连音数",
        ) { expanded = true }
        MeconDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { count ->
                MeconDropdownItem(
                    label = count.toString(),
                    onClick = {
                        expanded = false
                        selectedCount = count
                        state.customTupletText = count.toString()
                        if (editingSelection) {
                            changedSinceOpen = true
                        } else {
                            // Selecting a count is the horizontal equivalent of choosing one
                            // of the old numbered tuplet buttons: it must activate note-entry,
                            // not only update the pressed-state preview of the action button.
                            state.enterNoteEntry()
                            state.pickTupletCount(count)
                            changedSinceOpen = false
                        }
                    },
                )
            }
        }
    }
    TextToggleButton(
        text = "连音",
        selected = changedSinceOpen || existingCount == selectedCount,
        tooltip = "应用 $selectedCount 连音",
    ) {
        if (editingSelection) {
            onApply(selectedCount)
        } else {
            state.enterNoteEntry()
            state.toggleTupletCount(selectedCount)
        }
        changedSinceOpen = false
    }
}

private fun articulationGlyphs() = listOf(
    Articulation.STACCATO to Smufl.ARTIC_STACCATO,
    Articulation.TENUTO to Smufl.ARTIC_TENUTO,
    Articulation.ACCENT to Smufl.ARTIC_ACCENT,
    Articulation.MARCATO to Smufl.ARTIC_MARCATO,
    Articulation.STACCATISSIMO to Smufl.ARTIC_STACCATISSIMO,
)

/** Augmentation dots are tiny at the base size, so the dot buttons render at 2×. */
private val DOT_GLYPH_SIZE = 40.sp

@Composable
private fun DurationButton(
    state: NoteToolState,
    base: DurationBase,
    glyph: String,
    font: FontFamily?,
    reflectSelection: Boolean,
    editingSelection: Boolean,
    selectionInfo: PaletteSelectionInfo,
    onEdit: (DurationBase) -> Unit,
) {
    val selected = if (reflectSelection) selectionInfo.durationBase == base else state.durationBase == base
    GlyphToggleButton(glyph, font, selected, tooltip = durationTooltip(base)) {
        if (editingSelection) onEdit(base) else { state.enterNoteEntry(); state.pickDuration(base) }
    }
}

@Composable
private fun AccidentalButton(
    state: NoteToolState,
    value: Accidental,
    glyph: String,
    font: FontFamily?,
    reflectSelection: Boolean,
    editingSelection: Boolean,
    selectionInfo: PaletteSelectionInfo,
    onEdit: (Accidental) -> Unit,
) {
    val selected = if (reflectSelection) selectionInfo.accidental == value else state.accidental == value
    GlyphToggleButton(glyph, font, selected, tooltip = accidentalTooltip(value)) {
        if (editingSelection) onEdit(value) else { state.enterNoteEntry(); state.toggleAccidental(value) }
    }
}

private fun selectedVoiceNumber(
    reflectSelection: Boolean,
    selectionInfo: PaletteSelectionInfo,
    state: NoteToolState,
): Int? = if (reflectSelection) selectionInfo.voiceNumber else state.activeVoiceNumber

@Composable
private fun VoiceActionButton(
    number: Int,
    state: NoteToolState,
    reflectSelection: Boolean,
    editingSelection: Boolean,
    selectionInfo: PaletteSelectionInfo,
    onEdit: (Int) -> Unit,
) {
    VoiceToggleButton(
        number = number,
        selected = selectedVoiceNumber(reflectSelection, selectionInfo, state) == number,
        tooltip = "声部 $number",
    ) {
        if (editingSelection) {
            onEdit(number)
        } else {
            state.enterNoteEntry()
            state.activeVoiceNumber = number
        }
    }
}

@Composable
private fun TupletButton(
    count: Int,
    reflectSelection: Boolean,
    selectionInfo: PaletteSelectionInfo,
    state: NoteToolState,
    editingSelection: Boolean,
    onApply: (Int) -> Unit,
) {
    val selected = if (reflectSelection) selectionInfo.tupletCount == count else state.tupletCount == count
    TextToggleButton(count.toString(), selected, tooltip = "$count 连音") {
        if (editingSelection) onApply(count) else { state.enterNoteEntry(); state.toggleTupletCount(count) }
    }
}

private fun durationTooltip(base: DurationBase): String = when (base) {
    DurationBase.WHOLE -> "全音符"
    DurationBase.HALF -> "二分音符"
    DurationBase.QUARTER -> "四分音符"
    DurationBase.EIGHTH -> "八分音符"
    DurationBase.SIXTEENTH -> "十六分音符"
    DurationBase.THIRTY_SECOND -> "三十二分音符"
    DurationBase.BREVE -> "倍全音符"
    DurationBase.SIXTY_FOURTH -> "六十四分音符"
    DurationBase.LONGA -> "长音符"
    DurationBase.MAXIMA -> "最大音符"
    DurationBase.ONE_TWENTY_EIGHTH -> "一百二十八分音符"
}

private fun accidentalTooltip(value: Accidental): String = when (value) {
    Accidental.SHARP -> "升号"
    Accidental.FLAT -> "降号"
    Accidental.NATURAL -> "还原号"
    Accidental.DOUBLE_SHARP -> "重升"
    Accidental.DOUBLE_FLAT -> "重降"
}

private fun articulationTooltip(value: Articulation): String = when (value) {
    Articulation.STACCATO -> "断奏"
    Articulation.SPICCATO -> "跳弓"
    Articulation.TENUTO -> "保持音"
    Articulation.ACCENT -> "重音"
    Articulation.MARCATO -> "特重音"
    Articulation.STACCATISSIMO -> "极断奏"
    Articulation.FERMATA -> "延长记号"
}

@Composable
private fun TupletCountField(value: String, onValueChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = TextStyle(fontSize = 13.sp, color = MeconColors.TextPrimary),
        modifier = Modifier
            .size(width = CELL * 1.35f, height = CELL)
            .clip(RoundedCornerShape(4.dp))
            .background(MeconColors.SurfaceDark.copy(alpha = 0.25f))
            .border(1.dp, MeconColors.Border, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .meconTextInputFocus(),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text("n", fontSize = 13.sp, color = MeconColors.TextMuted)
                inner()
            }
        }
    )
}

private fun suggestedTupletCounts(total: Duration): List<Int> {
    val preferred = if (total.dots > 0) listOf(2, 4, 3, 5, 6) else listOf(3, 5, 6, 2, 4)
    return preferred.filter { NoteEditEngine.tupletSpecFor(total.toFraction(), it) != null }.take(3)
}
