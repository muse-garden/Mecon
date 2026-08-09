package com.mecon.desktop.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mecon.api.primitive.Accidental
import com.mecon.api.primitive.BarlineType
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.storage.BeamingInfo
import com.mecon.api.storage.Articulation
import com.mecon.api.storage.NavigationMark
import com.mecon.api.storage.GraceNoteType
import com.mecon.api.storage.tracks.Clef
import com.mecon.api.storage.events.DynamicLevel
import com.mecon.api.storage.events.HairpinStyle
import com.mecon.api.storage.events.HairpinType
import com.mecon.api.storage.events.OctaveShiftType
import com.mecon.api.storage.events.TempoMarkType
import com.mecon.api.storage.events.GraceTimeSource
import com.mecon.api.storage.tracks.BreathMarkScope
import com.mecon.api.storage.tracks.BreathMarkShape
import com.mecon.api.storage.tracks.FermataShape
import com.mecon.desktop.ui.components.toolstate.ExpressionToolState
import com.mecon.desktop.ui.components.toolstate.NoteEntryToolState
import com.mecon.desktop.ui.components.toolstate.NotationToolState
import com.mecon.desktop.ui.components.toolstate.StructureToolState
import com.mecon.api.storage.events.OrnamentKind
import com.mecon.api.storage.ArpeggioType

enum class PauseMarkKind { FERMATA, BREATH }
enum class NoteEntryKind { NORMAL, GRACE }

/** The editing tools in the left tool column. */
enum class EditTool {
    /** Pointer — existing select / pan / zoom interaction, leaves the score unchanged. */
    SELECT,
    /**
     * Marquee — a pointer-class tool (no note palette): clicking behaves exactly like [SELECT],
     * while dragging rubber-bands a rectangle and selects every overlapping element.
     */
    MARQUEE,
    /** Note pen — hovering shows a ghost note, clicking inserts at the snapped position. */
    NOTE,
    /** Clef pen — hovering shows a clef-change preview, clicking inserts/replaces a clef. */
    CLEF,
    /** Time-signature pen — clicking a measure sets its time signature (and re-bars from there). */
    TIME,
    /** Key-signature pen — clicking a measure sets/replaces the key signature from there. */
    KEY,
    /** Point dynamic insertion (p, mf, f, ...). */
    DYNAMIC,
    /** Fermata or breath-mark point insertion. */
    PAUSE,
    /** Hairpin or cresc./dim. drag insertion. */
    HAIRPIN,
    /** 8va/8vb drag insertion. */
    OCTAVE,
    /** Point tempo mark or hidden playback keyframe. */
    TEMPO,
    /** Accelerando/ritardando range insertion. */
    TEMPO_SPAN,
    /** Point trill/mordent/turn insertion. */
    ORNAMENT,
    /** Drag insertion for a trill with a wavy continuation. */
    ORNAMENT_SPAN,
    /** Chord arpeggiation insertion. */
    ARPEGGIO,
    /** Barline pen — clicking an existing logical boundary replaces its type. */
    BARLINE,
    /** First/second ending or D.C./D.S. navigation insertion on a barline boundary. */
    REPEAT_STRUCTURE,
}

/**
 * Compose state holder for the note-editing toolbar (left sidebar) and the canvas interaction.
 *
 * Owned by `App` and shared by [LeftToolbar] (which mutates it) and the score view (which reads
 * [duration], [restMode], [accidental], [tieMode] to build an insertion). Backed by Compose
 * snapshot state so both recompose on change.
 */
class NoteToolState {
    val note = NoteEntryToolState()
    val notation = NotationToolState()
    val expression = ExpressionToolState()
    val structure = StructureToolState()

    /**
     * Active tool. Every insertion pen, including future score-element tools, is globally mutually
     * exclusive: one [tool] governs canvas clicks at a time. Palette visibility is independent —
     * switching to SELECT/MARQUEE keeps palettes open so they can edit the current selection.
     */
    var tool by mutableStateOf(EditTool.SELECT)

    /**
     * Whether the note palette (right column) is shown. Independent of [tool]: in NOTE mode the
     * palette sets the next-inserted note; in SELECT/MARQUEE mode it reflects and edits the current
     * selection. Expanded by default. The note-pen toolbar button's highlight tracks *this* (not
     * [tool]), so it stays lit whenever the palette is open — switching to SELECT/MARQUEE keeps it lit.
     */
    var paletteExpanded by mutableStateOf(true)

    /**
     * Whether the score-element palette is shown. Independent of the note palette; when both are
     * open, the score-element palette is displayed to the right of the note palette.
     */
    var scoreElementPaletteExpanded by mutableStateOf(false)

    /** The selected base note value. */
    var durationBase: DurationBase
        get() = note.durationBase
        set(value) { note.durationBase = value }

    /** Augmentation dots (0, 1, or 2). */
    var dots: Int
        get() = note.dots
        set(value) { note.dots = value }

    /** When true, clicking inserts a rest instead of a note. */
    var restMode: Boolean
        get() = note.restMode
        set(value) { note.restMode = value }

    /** Accidental to apply to the inserted pitch, or null for none (diatonic / key spelling). */
    var accidental: Accidental?
        get() = note.accidental
        set(value) { note.accidental = value }

    /** When true, the inserted note carries a tie out to the next note. */
    var tieMode: Boolean
        get() = note.tieMode
        set(value) { note.tieMode = value }

    /** Normal or grace-note entry; small notes are inferred from the target region. */
    var noteEntryKind: NoteEntryKind
        get() = note.entryKind
        set(value) { note.entryKind = value }
    var graceTotalDurationBase: DurationBase
        get() = note.graceTotalDurationBase
        set(value) { note.graceTotalDurationBase = value }
    var graceTotalDurationDots: Int
        get() = note.graceTotalDurationDots
        set(value) { note.graceTotalDurationDots = value }
    var graceTimeSource: GraceTimeSource
        get() = note.graceTimeSource
        set(value) { note.graceTimeSource = value }
    var graceNoteType: GraceNoteType
        get() = note.graceNoteType
        set(value) { note.graceNoteType = value }

    /** Voice number edited by the note pen and the voice palette buttons. */
    var activeVoiceNumber: Int
        get() = note.activeVoiceNumber
        set(value) { note.activeVoiceNumber = value }

    /** Whether uncommon or edge durations (breve / 64th / longa / maxima / 128th) are shown. */
    var uncommonDurationsExpanded: Boolean
        get() = note.uncommonDurationsExpanded
        set(value) { note.uncommonDurationsExpanded = value }

    /** Whether the articulation controls are shown. Collapsed by default to keep toolbars compact. */
    var articulationsExpanded: Boolean
        get() = note.articulationsExpanded
        set(value) { note.articulationsExpanded = value }

    /** Tuplet count for the next inserted group; null means ordinary note entry. */
    var tupletCount: Int?
        get() = note.tupletCount
        set(value) { note.tupletCount = value }

    /**
     * Explicit beam override for the next inserted note. null = auto-beam. [BeamingInfo.NONE] and
     * [BeamingInfo.middle] persist across insertions; [BeamingInfo.start] and [BeamingInfo.end] are
     * one-shot (cleared by App after the note lands).
     */
    var insertionBeaming: BeamingInfo?
        get() = note.insertionBeaming
        set(value) { note.insertionBeaming = value }

    /** Multi-select articulation set for newly inserted notes. */
    var articulations: Set<Articulation>
        get() = note.articulations
        set(value) { note.articulations = value }

    /** Free-form count typed in the tuplet palette. */
    var customTupletText: String
        get() = note.customTupletText
        set(value) { note.customTupletText = value }

    /** Recently used tuplet counts, most recent first. */
    var recentTupletCounts: List<Int>
        get() = note.recentTupletCounts
        set(value) { note.recentTupletCounts = value }

    /** Clef selected for the next clef insertion/edit. */
    var selectedClef: Clef
        get() = notation.selectedClef
        set(value) { notation.selectedClef = value }

    /** Whether the clef section in the score-element palette is expanded. */
    var clefSectionExpanded: Boolean
        get() = notation.clefSectionExpanded
        set(value) { notation.clefSectionExpanded = value }

    /** Time signature selected for the next time-signature insertion/edit. */
    var selectedTimeSignature: TimeSignature
        get() = notation.selectedTimeSignature
        set(value) { notation.selectedTimeSignature = value }

    /** Whether the time-signature section in the score-element palette is expanded. */
    var timeSectionExpanded: Boolean
        get() = notation.timeSectionExpanded
        set(value) { notation.timeSectionExpanded = value }

    /** Key signature selected for the next key-signature insertion/edit. */
    var selectedKeySignature: KeySignature
        get() = notation.selectedKeySignature
        set(value) { notation.selectedKeySignature = value }

    /** Whether the key-signature section in the score-element palette is expanded. */
    var keySectionExpanded: Boolean
        get() = notation.keySectionExpanded
        set(value) { notation.keySectionExpanded = value }

    var dynamicsSectionExpanded: Boolean
        get() = expression.dynamicsSectionExpanded
        set(value) { expression.dynamicsSectionExpanded = value }
    var pausesSectionExpanded: Boolean
        get() = expression.pausesSectionExpanded
        set(value) { expression.pausesSectionExpanded = value }
    var octaveSectionExpanded: Boolean
        get() = expression.octaveSectionExpanded
        set(value) { expression.octaveSectionExpanded = value }
    var tempoSectionExpanded: Boolean
        get() = expression.tempoSectionExpanded
        set(value) { expression.tempoSectionExpanded = value }
    var selectedDynamic: DynamicLevel
        get() = expression.selectedDynamic
        set(value) { expression.selectedDynamic = value }
    var selectedPauseKind: PauseMarkKind
        get() = expression.selectedPauseKind
        set(value) { expression.selectedPauseKind = value }
    var selectedFermataShape: FermataShape
        get() = expression.selectedFermataShape
        set(value) { expression.selectedFermataShape = value }
    var selectedBreathShape: BreathMarkShape
        get() = expression.selectedBreathShape
        set(value) { expression.selectedBreathShape = value }
    var selectedBreathScope: BreathMarkScope
        get() = expression.selectedBreathScope
        set(value) { expression.selectedBreathScope = value }
    var selectedHairpinType: HairpinType
        get() = expression.selectedHairpinType
        set(value) { expression.selectedHairpinType = value }
    var selectedHairpinStyle: HairpinStyle
        get() = expression.selectedHairpinStyle
        set(value) { expression.selectedHairpinStyle = value }
    var selectedOctaveShift: OctaveShiftType
        get() = expression.selectedOctaveShift
        set(value) { expression.selectedOctaveShift = value }
    var selectedTempoMark: TempoMarkType
        get() = expression.selectedTempoMark
        set(value) { expression.selectedTempoMark = value }
    var selectedTempoBpm: Float
        get() = expression.selectedTempoBpm
        set(value) { expression.selectedTempoBpm = value }
    var selectedOrnamentKind: OrnamentKind
        get() = expression.selectedOrnamentKind
        set(value) { expression.selectedOrnamentKind = value }
    var selectedOrnamentWavy: Boolean
        get() = expression.selectedOrnamentWavy
        set(value) { expression.selectedOrnamentWavy = value }
    var selectedArpeggioType: ArpeggioType
        get() = expression.selectedArpeggioType
        set(value) { expression.selectedArpeggioType = value }
    var barlineSectionExpanded: Boolean
        get() = structure.barlineSectionExpanded
        set(value) { structure.barlineSectionExpanded = value }
    var selectedBarlineType: BarlineType
        get() = structure.selectedBarlineType
        set(value) { structure.selectedBarlineType = value }
    var selectedRepeatCount: Int
        get() = structure.selectedRepeatCount
        set(value) { structure.selectedRepeatCount = value }
    var selectedVoltaNumber: Int?
        get() = structure.selectedVoltaNumber
        set(value) { structure.selectedVoltaNumber = value }
    var selectedNavigationMark: NavigationMark?
        get() = structure.selectedNavigationMark
        set(value) { structure.selectedNavigationMark = value }

    /** The resolved [Duration] from the current base + dots. */
    val duration: Duration get() = note.duration
    val graceTotalDuration: Duration
        get() = note.graceTotalDuration

    /** Switch to the pointer and cancel any active insertion pen. */
    fun selectPointerTool() {
        tool = EditTool.SELECT
    }

    /** Switch to marquee selection and cancel any active insertion pen. */
    fun selectMarqueeTool() {
        tool = EditTool.MARQUEE
    }

    /** Cancel note/score-element entry while preserving palette defaults for the next use. */
    fun cancelInsertionTool() {
        if (tool != EditTool.SELECT && tool != EditTool.MARQUEE) {
            tool = EditTool.SELECT
        }
    }

    /**
     * Click handler for the note-pen tool button — a **pure palette switch**: it only toggles
     * [paletteExpanded], never touching [tool]. Note-entry mode (`tool == NOTE`) is entered by
     * interacting with a palette button or a note-entry shortcut (see [enterNoteEntry]), not by this
     * button. Esc / the SELECT button leave note-entry.
     */
    fun togglePalette() {
        paletteExpanded = !paletteExpanded
    }

    /**
     * Enter note-entry mode and ensure the palette is visible. Called when a palette button or a
     * keyboard shortcut sets an insertion default while there is no selection to edit — so picking a
     * value from the palette starts note entry (the "点击调板音符按钮进入编辑状态" behavior).
     */
    fun enterNoteEntry() {
        tool = EditTool.NOTE
        paletteExpanded = true
    }

    fun enterGraceEntry() {
        enterNoteEntry()
        noteEntryKind = NoteEntryKind.GRACE
        restMode = false
        tupletCount = null
    }

    fun enterNormalEntry() {
        enterNoteEntry()
        noteEntryKind = NoteEntryKind.NORMAL
    }

    /** Toggle the score-element palette without changing the active edit tool. */
    fun toggleScoreElementPalette() {
        scoreElementPaletteExpanded = !scoreElementPaletteExpanded
    }

    /** Enter clef-entry mode and ensure the score-element palette is visible. */
    fun enterClefEntry(clef: Clef = selectedClef) {
        selectedClef = clef
        tool = EditTool.CLEF
        scoreElementPaletteExpanded = true
    }

    /** Enter time-signature-entry mode and ensure the score-element palette is visible. */
    fun enterTimeEntry(timeSignature: TimeSignature = selectedTimeSignature) {
        selectedTimeSignature = timeSignature
        tool = EditTool.TIME
        scoreElementPaletteExpanded = true
    }

    /** Enter key-signature-entry mode and ensure the score-element palette is visible. */
    fun enterKeyEntry(keySignature: KeySignature = selectedKeySignature) {
        selectedKeySignature = keySignature
        tool = EditTool.KEY
        scoreElementPaletteExpanded = true
    }

    fun enterDynamicEntry(level: DynamicLevel) {
        selectedDynamic = level
        tool = EditTool.DYNAMIC
        scoreElementPaletteExpanded = true
    }

    fun enterFermataEntry(shape: FermataShape) {
        selectedPauseKind = PauseMarkKind.FERMATA
        selectedFermataShape = shape
        tool = EditTool.PAUSE
        scoreElementPaletteExpanded = true
    }

    fun enterBreathEntry(shape: BreathMarkShape, scope: BreathMarkScope = selectedBreathScope) {
        selectedPauseKind = PauseMarkKind.BREATH
        selectedBreathShape = shape
        selectedBreathScope = scope
        tool = EditTool.PAUSE
        scoreElementPaletteExpanded = true
    }

    fun enterHairpinEntry(type: HairpinType, style: HairpinStyle) {
        selectedHairpinType = type
        selectedHairpinStyle = style
        tool = EditTool.HAIRPIN
        scoreElementPaletteExpanded = true
    }

    fun enterOctaveEntry(type: OctaveShiftType) {
        selectedOctaveShift = type
        tool = EditTool.OCTAVE
        scoreElementPaletteExpanded = true
    }

    fun enterTempoEntry(type: TempoMarkType) {
        selectedTempoMark = type
        tool = if (type == TempoMarkType.ACCELERANDO || type == TempoMarkType.RITARDANDO) {
            EditTool.TEMPO_SPAN
        } else {
            EditTool.TEMPO
        }
        scoreElementPaletteExpanded = true
    }

    fun enterOrnamentEntry(kind: OrnamentKind, wavy: Boolean = false) {
        selectedOrnamentKind = kind
        selectedOrnamentWavy = wavy
        tool = if (wavy) EditTool.ORNAMENT_SPAN else EditTool.ORNAMENT
        scoreElementPaletteExpanded = true
    }

    fun enterArpeggioEntry(type: ArpeggioType) {
        selectedArpeggioType = type
        tool = EditTool.ARPEGGIO
        scoreElementPaletteExpanded = true
    }

    /** Arm the barline pen. The pen edits only an existing barline hit. */
    fun enterBarlineEntry(
        type: BarlineType = selectedBarlineType,
        repeatCount: Int = selectedRepeatCount,
    ) {
        selectedBarlineType = type
        selectedRepeatCount = repeatCount.coerceIn(2, 4)
        tool = EditTool.BARLINE
        scoreElementPaletteExpanded = true
    }

    fun enterVoltaEntry(number: Int) {
        selectedVoltaNumber = number
        selectedNavigationMark = null
        tool = EditTool.REPEAT_STRUCTURE
        scoreElementPaletteExpanded = true
    }

    fun enterNavigationEntry(mark: NavigationMark) {
        selectedVoltaNumber = null
        selectedNavigationMark = mark
        tool = EditTool.REPEAT_STRUCTURE
        scoreElementPaletteExpanded = true
    }

    /**
     * Select a base note value. Changing the duration cancels any active dot — the dot belonged to
     * the previous value, so carrying it onto a freshly chosen duration would silently alter it.
     */
    fun pickDuration(base: DurationBase) {
        if (base != durationBase) dots = 0
        durationBase = base
    }

    /** Toggle a dot count: clicking the active one clears it. */
    fun toggleDots(value: Int) {
        dots = if (dots == value) 0 else value
    }

    /** Toggle an accidental: clicking the active one clears it. */
    fun toggleAccidental(value: Accidental) {
        accidental = if (accidental == value) null else value
    }

    fun pickTupletCount(count: Int) {
        if (count <= 1) return
        tupletCount = count
        rememberTupletCount(count)
    }

    fun toggleTupletCount(count: Int) {
        if (count <= 1) return
        tupletCount = if (tupletCount == count) null else count
        rememberTupletCount(count)
    }

    fun rememberTupletCount(count: Int) {
        if (count <= 1) return
        recentTupletCounts = (listOf(count) + recentTupletCounts.filter { it != count }).take(3)
    }

    /** Toggle the insertion beam override: clicking the active value returns to auto (null). */
    fun toggleInsertionBeaming(value: BeamingInfo) {
        insertionBeaming = if (insertionBeaming == value) null else value
    }

    fun toggleArticulation(value: Articulation) {
        articulations = if (value in articulations) articulations - value else articulations + value
    }

    /**
     * Reset note-entry state when switching to a different document so insertion defaults from the
     * previous score do not leak into the newly opened one.
     */
    fun resetForDocumentSwitch() {
        tool = EditTool.SELECT
        note.reset()
        notation.reset()
        expression.reset()
        structure.reset()
        scoreElementPaletteExpanded = false
    }
}

typealias PaletteSelectionInfo = com.mecon.desktop.ui.components.toolstate.PaletteSelectionInfo
typealias ClefSelectionInfo = com.mecon.desktop.ui.components.toolstate.ClefSelectionInfo
typealias TimeSignatureSelectionInfo = com.mecon.desktop.ui.components.toolstate.TimeSignatureSelectionInfo
typealias KeySignatureSelectionInfo = com.mecon.desktop.ui.components.toolstate.KeySignatureSelectionInfo
typealias BarlineSelectionInfo = com.mecon.desktop.ui.components.toolstate.BarlineSelectionInfo
