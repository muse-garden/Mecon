package com.mecon.desktop.ui.components.lefttoolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.TextUnit
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.BarlineType
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.StaffLayoutPreset
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.NavigationMark
import com.mecon.api.storage.tracks.Clef
import com.mecon.api.storage.events.DynamicLevel
import com.mecon.api.storage.events.HairpinStyle
import com.mecon.api.storage.events.HairpinType
import com.mecon.api.storage.events.OctaveShiftType
import com.mecon.api.storage.events.TempoMarkType
import com.mecon.desktop.ui.components.EditTool
import com.mecon.desktop.ui.components.KeySignaturePicker
import com.mecon.desktop.ui.components.LeftToolbarSelectionState
import com.mecon.desktop.ui.components.NoteToolState
import com.mecon.desktop.ui.components.ScoreElementPaletteActions
import com.mecon.desktop.ui.components.TimeSignaturePicker
import com.mecon.desktop.ui.rememberBravuraFont
import com.mecon.desktop.ui.views.SimpleScoreView
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.DynamicGlyphs
import com.mecon.renderer.smufl.GlyphInfo
import com.mecon.renderer.smufl.SmuflGlyphs
import com.mecon.api.storage.tracks.BreathMarkScope
import com.mecon.api.storage.tracks.BreathMarkShape
import com.mecon.api.storage.tracks.FermataShape
import com.mecon.desktop.ui.components.PauseMarkKind
import com.mecon.api.storage.events.OrnamentKind
import com.mecon.api.storage.ArpeggioType

/** The score-element palette. Future key/time signatures and expressions will live here. */
@Composable
internal fun ScoreElementPalette(
    state: NoteToolState,
    selection: LeftToolbarSelectionState,
    actions: ScoreElementPaletteActions,
) {
    Column(
        modifier = Modifier
            .width(SCORE_ELEMENT_PALETTE_WIDTH)
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
        CollapsibleSectionLabel("延长与停顿", state.expression.pausesSectionExpanded) {
            state.expression.pausesSectionExpanded = !state.expression.pausesSectionExpanded
        }
        if (state.expression.pausesSectionExpanded) {
            val fermatas = listOf(
                FermataShape.VERY_SHORT to SmuflGlyphs.fermataVeryShortAbove,
                FermataShape.SHORT to SmuflGlyphs.fermataShortAbove,
                FermataShape.NORMAL to SmuflGlyphs.fermataAbove,
                FermataShape.LONG to SmuflGlyphs.fermataLongAbove,
                FermataShape.VERY_LONG to SmuflGlyphs.fermataVeryLongAbove,
            )
            fermatas.chunked(SCORE_ELEMENT_COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(SCORE_ELEMENT_GAP)) {
                    row.forEach { (shape, glyph) ->
                        ScoreElementGlyphButton(
                            glyph = glyph,
                            selected = state.tool == EditTool.PAUSE &&
                                state.expression.selectedPauseKind == PauseMarkKind.FERMATA &&
                                state.expression.selectedFermataShape == shape,
                            sizeSp = 27.sp,
                        ) { actions.pickFermata(shape) }
                    }
                }
            }
            val scopes = listOf(
                BreathMarkScope.VOICE to "声部",
                BreathMarkScope.STAFF to "谱表",
                BreathMarkScope.GLOBAL to "全谱",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(SCORE_ELEMENT_GAP)) {
                scopes.forEach { (scope, label) ->
                    ScoreElementTextButton(
                        text = label,
                        selected = state.expression.selectedBreathScope == scope,
                    ) { state.expression.selectedBreathScope = scope }
                }
            }
            val breaths = listOf(
                BreathMarkShape.COMMA to SmuflGlyphs.breathMarkComma,
                BreathMarkShape.TICK to SmuflGlyphs.breathMarkTick,
                BreathMarkShape.UPBOW to SmuflGlyphs.breathMarkUpbow,
                BreathMarkShape.SALZEDO to SmuflGlyphs.breathMarkSalzedo,
            )
            breaths.chunked(SCORE_ELEMENT_COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(SCORE_ELEMENT_GAP)) {
                    row.forEach { (shape, glyph) ->
                        ScoreElementGlyphButton(
                            glyph = glyph,
                            selected = state.tool == EditTool.PAUSE &&
                                state.expression.selectedPauseKind == PauseMarkKind.BREATH &&
                                state.expression.selectedBreathShape == shape,
                            sizeSp = 25.sp,
                        ) { actions.pickBreath(shape, state.expression.selectedBreathScope) }
                    }
                }
            }
        }

        CollapsibleSectionLabel("力度", state.expression.dynamicsSectionExpanded) {
            state.expression.dynamicsSectionExpanded = !state.expression.dynamicsSectionExpanded
        }
        if (state.expression.dynamicsSectionExpanded) {
            DynamicLevel.entries.chunked(SCORE_ELEMENT_COLUMNS).forEach { levels ->
                Row(horizontalArrangement = Arrangement.spacedBy(SCORE_ELEMENT_GAP)) {
                    levels.forEach { level ->
                        ScoreElementGlyphButton(
                            glyph = DynamicGlyphs.glyphsFor(level).single(),
                            selected = state.tool == EditTool.DYNAMIC &&
                                state.expression.selectedDynamic == level,
                            sizeSp = 28.sp,
                        ) { actions.pickDynamic(level) }
                    }
                    repeat(SCORE_ELEMENT_COLUMNS - levels.size) {
                        Spacer(Modifier.size(SCORE_ELEMENT_CELL_WIDTH, SCORE_ELEMENT_CELL_HEIGHT))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(SCORE_ELEMENT_GAP)) {
                ScoreElementGlyphButton(
                    glyph = SmuflGlyphs.dynamicCrescendoHairpin,
                    selected = state.tool == EditTool.HAIRPIN &&
                        state.expression.selectedHairpinType == HairpinType.CRESCENDO &&
                        state.expression.selectedHairpinStyle == HairpinStyle.WEDGE,
                    sizeSp = 30.sp,
                ) {
                    actions.pickHairpin(HairpinType.CRESCENDO, HairpinStyle.WEDGE)
                }
                ScoreElementGlyphButton(
                    glyph = SmuflGlyphs.dynamicDiminuendoHairpin,
                    selected = state.tool == EditTool.HAIRPIN &&
                        state.expression.selectedHairpinType == HairpinType.DIMINUENDO &&
                        state.expression.selectedHairpinStyle == HairpinStyle.WEDGE,
                    sizeSp = 30.sp,
                ) {
                    actions.pickHairpin(HairpinType.DIMINUENDO, HairpinStyle.WEDGE)
                }
                ScoreElementTextButton(
                    text = "cresc.",
                    selected = state.tool == EditTool.HAIRPIN &&
                        state.expression.selectedHairpinType == HairpinType.CRESCENDO &&
                        state.expression.selectedHairpinStyle == HairpinStyle.TEXT_DASHED,
                ) {
                    actions.pickHairpin(HairpinType.CRESCENDO, HairpinStyle.TEXT_DASHED)
                }
                ScoreElementTextButton(
                    text = "dim.",
                    selected = state.tool == EditTool.HAIRPIN &&
                        state.expression.selectedHairpinType == HairpinType.DIMINUENDO &&
                        state.expression.selectedHairpinStyle == HairpinStyle.TEXT_DASHED,
                ) {
                    actions.pickHairpin(HairpinType.DIMINUENDO, HairpinStyle.TEXT_DASHED)
                }
            }
        }

        CollapsibleSectionLabel("八度记号", state.expression.octaveSectionExpanded) {
            state.expression.octaveSectionExpanded = !state.expression.octaveSectionExpanded
        }
        if (state.expression.octaveSectionExpanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(SCORE_ELEMENT_GAP)) {
                ScoreElementTextButton(
                    text = "8va",
                    selected = state.tool == EditTool.OCTAVE &&
                        state.expression.selectedOctaveShift == OctaveShiftType.OTTAVA,
                ) {
                    actions.pickOctaveShift(OctaveShiftType.OTTAVA)
                }
                ScoreElementTextButton(
                    text = "8vb",
                    selected = state.tool == EditTool.OCTAVE &&
                        state.expression.selectedOctaveShift == OctaveShiftType.OTTAVA_BASSA,
                ) {
                    actions.pickOctaveShift(OctaveShiftType.OTTAVA_BASSA)
                }
            }
        }

        CollapsibleSectionLabel("速度记号", state.expression.tempoSectionExpanded) {
            state.expression.tempoSectionExpanded = !state.expression.tempoSectionExpanded
        }
        if (state.expression.tempoSectionExpanded) {
            val marks = listOf(
                TempoMarkType.METRONOME to "BPM",
                TempoMarkType.PIU_MOSSO to "più mosso",
                TempoMarkType.MENO_MOSSO to "meno mosso",
                TempoMarkType.A_TEMPO to "a tempo",
                TempoMarkType.TEMPO_I to "Tempo I",
                TempoMarkType.METRIC_MODULATION to "q = h",
                TempoMarkType.ACCELERANDO to "accel.",
                TempoMarkType.RITARDANDO to "rit.",
                TempoMarkType.KEYFRAME to "● key",
            )
            marks.chunked(SCORE_ELEMENT_COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(SCORE_ELEMENT_GAP)) {
                    row.forEach { (type, label) ->
                        ScoreElementTextButton(
                            text = label,
                            selected = state.expression.selectedTempoMark == type &&
                                state.tool in setOf(EditTool.TEMPO, EditTool.TEMPO_SPAN),
                        ) { actions.pickTempo(type) }
                    }
                    repeat(SCORE_ELEMENT_COLUMNS - row.size) {
                        Spacer(Modifier.size(SCORE_ELEMENT_CELL_WIDTH, SCORE_ELEMENT_CELL_HEIGHT))
                    }
                }
            }
        }

        CollapsibleSectionLabel("其他谱表元素", state.expression.ornamentsSectionExpanded) {
            state.expression.ornamentsSectionExpanded = !state.expression.ornamentsSectionExpanded
        }
        if (state.expression.ornamentsSectionExpanded) {
            val ornaments = listOf(
                Triple(OrnamentKind.TRILL, SmuflGlyphs.ornamentTrill, false),
                Triple(OrnamentKind.TRILL, SmuflGlyphs.wiggleTrill, true),
                Triple(OrnamentKind.MORDENT, SmuflGlyphs.ornamentMordent, false),
                Triple(OrnamentKind.INVERTED_MORDENT, SmuflGlyphs.ornamentShortTrill, false),
                Triple(OrnamentKind.TREMBLEMENT, SmuflGlyphs.ornamentTremblement, false),
                Triple(OrnamentKind.TREMBLEMENT_COUPERIN, SmuflGlyphs.ornamentTremblementCouperin, false),
                Triple(OrnamentKind.MORDENT_UPPER_PREFIX, SmuflGlyphs.ornamentPrecompMordentUpperPrefix, false),
                Triple(
                    OrnamentKind.INVERTED_MORDENT_UPPER_PREFIX,
                    SmuflGlyphs.ornamentPrecompInvertedMordentUpperPrefix,
                    false,
                ),
                Triple(OrnamentKind.MORDENT_RELEASE, SmuflGlyphs.ornamentPrecompMordentRelease, false),
                Triple(OrnamentKind.TURN, SmuflGlyphs.ornamentTurn, false),
                Triple(OrnamentKind.INVERTED_TURN, SmuflGlyphs.ornamentTurnInverted, false),
                Triple(OrnamentKind.TURN_SLASH, SmuflGlyphs.ornamentTurnSlash, false),
            )
            ornaments.chunked(SCORE_ELEMENT_COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(SCORE_ELEMENT_GAP)) {
                    row.forEach { (kind, glyph, wavy) ->
                        ScoreElementGlyphButton(
                            glyph = glyph,
                            selected = state.selectedOrnamentKind == kind &&
                                state.selectedOrnamentWavy == wavy &&
                                state.tool == if (wavy) EditTool.ORNAMENT_SPAN else EditTool.ORNAMENT,
                            sizeSp = 25.sp,
                        ) { actions.pickOrnament(kind, wavy) }
                    }
                    repeat(SCORE_ELEMENT_COLUMNS - row.size) {
                        Spacer(Modifier.size(SCORE_ELEMENT_CELL_WIDTH, SCORE_ELEMENT_CELL_HEIGHT))
                    }
                }
            }
            val arpeggios = listOf(
                ArpeggioType.NORMAL to SmuflGlyphs.arpeggiato,
                ArpeggioType.UP to SmuflGlyphs.arpeggiatoUp,
                ArpeggioType.DOWN to SmuflGlyphs.arpeggiatoDown,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(SCORE_ELEMENT_GAP)) {
                arpeggios.forEach { (type, glyph) ->
                    ScoreElementGlyphButton(
                        glyph = glyph,
                        selected = state.tool == EditTool.ARPEGGIO &&
                            state.selectedArpeggioType == type,
                        sizeSp = 25.sp,
                    ) { actions.pickArpeggio(type) }
                }
                ScoreElementTextButton(
                    text = "┌",
                    selected = state.tool == EditTool.ARPEGGIO &&
                        state.selectedArpeggioType == ArpeggioType.NON_ARPEGGIATE,
                ) { actions.pickArpeggio(ArpeggioType.NON_ARPEGGIATE) }
            }
        }

        CollapsibleSectionLabel("谱号", state.notation.clefSectionExpanded) {
            state.notation.clefSectionExpanded = !state.notation.clefSectionExpanded
        }
        if (state.notation.clefSectionExpanded) {
            val clefs = listOf(Clef.TREBLE, Clef.BASS, Clef.ALTO, Clef.TENOR)
            for (row in clefs.chunked(SCORE_ELEMENT_COLUMNS)) {
                Row(horizontalArrangement = Arrangement.spacedBy(SCORE_ELEMENT_GAP)) {
                    row.forEach { clef ->
                        ClefPaletteButton(
                            clef = clef,
                            selected = if (selection.clef.editable) {
                                selection.clef.clef == clef
                            } else {
                                state.tool == EditTool.CLEF && state.notation.selectedClef == clef
                            },
                            onClick = {
                                if (selection.clef.editable) {
                                    actions.pickClef(clef)
                                } else {
                                    state.enterClefEntry(clef)
                                }
                            }
                        )
                    }
                    repeat(SCORE_ELEMENT_COLUMNS - row.size) {
                        Spacer(Modifier.size(SCORE_ELEMENT_CELL_WIDTH, SCORE_ELEMENT_CELL_HEIGHT))
                    }
                }
            }
        }

        CollapsibleSectionLabel("调号", state.notation.keySectionExpanded) {
            state.notation.keySectionExpanded = !state.notation.keySectionExpanded
        }
        if (state.notation.keySectionExpanded) {
            KeySignaturePicker(
                selected = selection.key.keySignature ?: state.notation.selectedKeySignature,
                highlighted = when {
                    selection.key.editable -> selection.key.keySignature
                    state.tool == EditTool.KEY -> state.notation.selectedKeySignature
                    else -> null
                },
                clef = Clef.TREBLE,
                onSelect = { key ->
                    if (selection.key.editable) actions.pickKeySignature(key) else state.enterKeyEntry(key)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        CollapsibleSectionLabel("拍号", state.notation.timeSectionExpanded) {
            state.notation.timeSectionExpanded = !state.notation.timeSectionExpanded
        }
        if (state.notation.timeSectionExpanded) {
            TimeSignaturePicker(
                selected = selection.time.timeSignature ?: state.notation.selectedTimeSignature,
                highlighted = when {
                    selection.time.editable -> selection.time.timeSignature
                    state.tool == EditTool.TIME -> state.notation.selectedTimeSignature
                    else -> null
                },
                onSelect = { ts ->
                    // A selected time signature is edited in place; otherwise picking one arms the
                    // time-signature pen (enter TIME tool, remember the choice for the next click).
                    if (selection.time.editable) actions.pickTimeSignature(ts) else state.enterTimeEntry(ts)
                },
            )
        }

        // Keep barlines last: they operate on an existing structural boundary rather
        // than inserting a free-position notation event.
        CollapsibleSectionLabel("小节线与反复", state.structure.barlineSectionExpanded) {
            state.structure.barlineSectionExpanded = !state.structure.barlineSectionExpanded
        }
        if (state.structure.barlineSectionExpanded) {
            val activeType = selection.barline.type.takeIf { selection.barline.editable }
                ?: state.structure.selectedBarlineType
            val basicTypes = listOf(
                BarlineType.SINGLE,
                BarlineType.DOUBLE,
                BarlineType.FINAL,
                BarlineType.REVERSE_FINAL,
                BarlineType.DASHED,
                BarlineType.DOTTED,
            )
            basicTypes.chunked(SCORE_ELEMENT_COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(SCORE_ELEMENT_GAP)) {
                    row.forEach { type ->
                        val highlighted = if (selection.barline.editable) {
                            selection.barline.type == type
                        } else {
                            state.tool == EditTool.BARLINE &&
                                state.structure.selectedBarlineType == type
                        }
                        BarlinePaletteButton(type, highlighted) {
                            val count = if (selection.barline.editable) {
                                selection.barline.repeatCount
                            } else {
                                state.structure.selectedRepeatCount
                            }
                            if (selection.barline.editable) {
                                actions.pickBarline(type, count)
                            } else {
                                state.enterBarlineEntry(type, count)
                            }
                        }
                    }
                    repeat(SCORE_ELEMENT_COLUMNS - row.size) {
                        Spacer(Modifier.size(SCORE_ELEMENT_CELL_WIDTH, SCORE_ELEMENT_CELL_HEIGHT))
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(SCORE_ELEMENT_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val hasLeftRepeat = activeType == BarlineType.REPEAT_LEFT ||
                    activeType == BarlineType.REPEAT_BOTH
                val hasRightRepeat = activeType == BarlineType.REPEAT_RIGHT ||
                    activeType == BarlineType.REPEAT_BOTH
                listOf(true, false).forEach { left ->
                    BarlinePaletteButton(
                        type = if (left) BarlineType.REPEAT_LEFT else BarlineType.REPEAT_RIGHT,
                        selected = if (left) hasLeftRepeat else hasRightRepeat,
                    ) {
                        val nextLeft = if (left) !hasLeftRepeat else hasLeftRepeat
                        val nextRight = if (left) hasRightRepeat else !hasRightRepeat
                        val nextType = when {
                            nextLeft && nextRight -> BarlineType.REPEAT_BOTH
                            nextLeft -> BarlineType.REPEAT_LEFT
                            nextRight -> BarlineType.REPEAT_RIGHT
                            else -> BarlineType.SINGLE
                        }
                        if (selection.barline.editable) {
                            actions.pickBarline(nextType, selection.barline.repeatCount)
                        } else {
                            state.enterBarlineEntry(nextType, state.structure.selectedRepeatCount)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(SCORE_ELEMENT_GAP)) {
                ScoreElementTextButton(
                    text = "1./2.",
                    selected = state.tool == EditTool.REPEAT_STRUCTURE &&
                        state.structure.selectedVoltaNumber != null,
                ) { actions.pickVolta(1) }
                listOf(
                    NavigationMark.SEGNO to SmuflGlyphs.segno,
                    NavigationMark.CODA to SmuflGlyphs.coda,
                ).forEach { (mark, glyph) ->
                    ScoreElementGlyphButton(
                        glyph = glyph,
                        selected = state.tool == EditTool.REPEAT_STRUCTURE &&
                            state.structure.selectedNavigationMark == mark,
                        sizeSp = 34.sp,
                    ) { actions.pickNavigation(mark) }
                }
                listOf(
                    NavigationMark.DAL_SEGNO to "D.S.",
                    NavigationMark.DA_CAPO to "D.C.",
                ).forEach { (mark, label) ->
                    ScoreElementTextButton(
                        text = label,
                        selected = state.tool == EditTool.REPEAT_STRUCTURE &&
                            state.structure.selectedNavigationMark == mark,
                    ) { actions.pickNavigation(mark) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(SCORE_ELEMENT_GAP)) {
                listOf(
                    NavigationMark.FINE to "Fine",
                    NavigationMark.TO_CODA to "To Coda",
                    NavigationMark.DAL_SEGNO_AL_FINE to "D.S. al Fine",
                    NavigationMark.DA_CAPO_AL_FINE to "D.C. al Fine",
                    NavigationMark.DAL_SEGNO_AL_CODA to "D.S. al Coda",
                    NavigationMark.DA_CAPO_AL_CODA to "D.C. al Coda",
                ).forEach { (mark, label) ->
                    ScoreElementTextButton(
                        text = label,
                        selected = state.tool == EditTool.REPEAT_STRUCTURE &&
                            state.structure.selectedNavigationMark == mark,
                    ) { actions.pickNavigation(mark) }
                }
            }
        }
    }
}

private const val SCORE_ELEMENT_COLUMNS = 6
private val SCORE_ELEMENT_PALETTE_WIDTH = 424.dp
private val SCORE_ELEMENT_GAP = 4.dp
private val SCORE_ELEMENT_CELL_WIDTH = 64.dp
private val SCORE_ELEMENT_CELL_HEIGHT = 58.dp

@Composable
private fun ClefPaletteButton(
    clef: Clef,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ScoreElementButton(selected, onClick) { ink ->
        ClefStaffPreview(clef = clef, ink = ink, modifier = Modifier.fillMaxSize())
    }
}

/** Large score-element cell shared by clefs, dynamics, hairpins, and octave labels. */
@Composable
private fun ScoreElementButton(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable (Color) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(SCORE_ELEMENT_CELL_WIDTH, SCORE_ELEMENT_CELL_HEIGHT)
            .clip(RoundedCornerShape(4.dp))
            .background(cellBackground(selected, isPressed, isHovered))
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content(if (selected) MeconColors.SelectedIconOnSurface else MeconColors.IconDefault)
    }
}

@Composable
private fun ScoreElementGlyphButton(
    glyph: GlyphInfo,
    selected: Boolean,
    sizeSp: TextUnit,
    onClick: () -> Unit,
) {
    val bravura = rememberBravuraFont()
    ScoreElementButton(selected, onClick) { ink ->
        MusicGlyph(
            glyph = glyph.codepoint.toString(),
            font = bravura,
            sizeSp = sizeSp,
            color = ink,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ScoreElementTextButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ScoreElementButton(selected, onClick) { ink ->
        Text(
            text = text,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = when {
                text.length > 10 -> 9.sp
                text.length > 6 -> 11.sp
                else -> 18.sp
            },
            color = ink,
        )
    }
}

@Composable
private fun BarlinePaletteButton(
    type: BarlineType,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ScoreElementButton(selected, onClick) { ink ->
        Canvas(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 10.dp)) {
                val center = size.width / 2f
                val top = 0f
                val bottom = size.height
                val thin = 1.5.dp.toPx()
                val thick = 4.dp.toPx()
                val gap = 4.dp.toPx()
                fun line(
                    x: Float,
                    y1: Float = top,
                    y2: Float = bottom,
                    width: Float = thin,
                    effect: PathEffect? = null,
                    cap: StrokeCap = StrokeCap.Butt,
                ) = drawLine(ink, Offset(x, y1), Offset(x, y2), width, cap, effect)

                when (type) {
                    BarlineType.SINGLE -> line(center)
                    BarlineType.DOUBLE -> {
                        line(center - gap / 2f)
                        line(center + gap / 2f)
                    }
                    BarlineType.FINAL -> {
                        line(center - gap / 2f)
                        line(center + gap / 2f, width = thick)
                    }
                    BarlineType.REVERSE_FINAL -> {
                        line(center - gap / 2f, width = thick)
                        line(center + gap / 2f)
                    }
                    BarlineType.DASHED -> line(
                        center,
                        effect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 4.dp.toPx())),
                    )
                    BarlineType.DOTTED -> {
                        val radius = 1.6.dp.toPx()
                        var y = radius
                        while (y <= bottom - radius) {
                            drawCircle(ink, radius, Offset(center, y))
                            y += 7.dp.toPx()
                        }
                    }
                    BarlineType.SHORT -> line(center, bottom * 0.25f, bottom * 0.75f)
                    BarlineType.TICK -> line(center, top, bottom * 0.25f)
                    BarlineType.REPEAT_LEFT,
                    BarlineType.REPEAT_RIGHT,
                    BarlineType.REPEAT_BOTH -> {
                        val left = type == BarlineType.REPEAT_LEFT || type == BarlineType.REPEAT_BOTH
                        val right = type == BarlineType.REPEAT_RIGHT || type == BarlineType.REPEAT_BOTH
                        when {
                            left && right -> {
                                line(center - gap)
                                line(center, width = thick)
                                line(center + gap)
                            }
                            left -> {
                                line(center - gap / 2f, width = thick)
                                line(center + gap / 2f)
                            }
                            else -> {
                                line(center - gap / 2f)
                                line(center + gap / 2f, width = thick)
                            }
                        }
                        val dotOffset = 8.dp.toPx()
                        val dotRadius = 1.8.dp.toPx()
                        val dotY1 = bottom * 0.38f
                        val dotY2 = bottom * 0.62f
                        if (left) {
                            drawCircle(ink, dotRadius, Offset(center + dotOffset, dotY1))
                            drawCircle(ink, dotRadius, Offset(center + dotOffset, dotY2))
                        }
                        if (right) {
                            drawCircle(ink, dotRadius, Offset(center - dotOffset, dotY1))
                            drawCircle(ink, dotRadius, Offset(center - dotOffset, dotY2))
                        }
                    }
                }
        }
    }
}

/**
 * A cropped staff + clef thumbnail, engraved through the shared [SimpleScoreView] so it matches
 * exactly how the editor draws the chosen clef — the same approach as the new-score dialog previews.
 *
 * Every clef shares one scale and staff position by fitting to the *staff* (not the full content,
 * which a tall treble clef would inflate). [SimpleScoreView.fitScale] leaves the staff at ~half the
 * cell so the treble clef, which overhangs its staff by ~1.6 spaces top and bottom, isn't clipped.
 */
@Composable
private fun ClefStaffPreview(clef: Clef, ink: Color, modifier: Modifier) {
    val score = remember(clef) {
        // Reuse the single-staff skeleton, then retarget its staff to the desired clef.
        val base = StorageScore.create(StorageScore.CreationOptions(layout = StaffLayoutPreset.TREBLE, measureCount = 1))
        RuntimeScore.fromStorage(
            base.copy(staffTracks = base.staffTracks.mapValues { (_, staff) -> staff.copy(clef = clef) })
        )
    }
    SimpleScoreView(
        score = score,
        modifier = modifier,
        alignment = Alignment.Center,
        fitScale = CLEF_PREVIEW_FIT_SCALE,
        foreground = ink,
        visibleTypes = CLEF_PREVIEW_VISIBLE_TYPES,
        cropTypes = CLEF_PREVIEW_CROP_TYPES,
        verticalFitTypes = CLEF_PREVIEW_FIT_TYPES,
    )
}

// Show only the staff and its clef, cropped to just past the clef so no trailing staff/rest shows.
private val CLEF_PREVIEW_VISIBLE_TYPES = setOf(RenderElementType.STAFF, RenderElementType.CLEF)
private val CLEF_PREVIEW_CROP_TYPES = setOf(RenderElementType.CLEF)
// Fit every clef to its (identical) staff so scales match and staff lines align across the buttons.
private val CLEF_PREVIEW_FIT_TYPES = setOf(RenderElementType.STAFF)
// Staff ≈ half the cell height; the rest is headroom for the treble clef's overhang (7.024 staff
// spaces tall vs the 4-space staff → staff must stay ≤ 4/7.024 ≈ 0.57 of the box to show in full).
private const val CLEF_PREVIEW_FIT_SCALE = 0.55f
