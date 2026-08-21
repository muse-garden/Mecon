package com.mecon.desktop.input

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import com.mecon.api.primitive.Accidental
import com.mecon.api.primitive.DurationBase
import com.mecon.api.storage.Articulation
import com.mecon.api.storage.BeamingInfo
import com.mecon.desktop.service.EditableScoreHost
import com.mecon.desktop.ui.components.NoteToolState

/**
 * Edits the score's *current selection's* note properties, used when a duration / dot / accidental /
 * tie shortcut fires while a selection is active in SELECT/MARQUEE mode (the keyboard mirror of
 * clicking the palette in edit mode). Implemented by `App`, which owns the selection and the engine
 * commit; the toggle decisions (set vs clear) are made there from the selection's aggregate.
 */
interface SelectionEditor {
    /** True when shortcuts should edit the selection rather than set the note-pen defaults. */
    val active: Boolean
    fun editDurationBase(base: DurationBase)
    fun editDots(dots: Int)
    fun editAccidental(accidental: Accidental)
    fun editTie()
    fun editVoice(voiceNumber: Int)
    fun editTuplet(count: Int)
    /** Set an explicit beam override on all selected events (or null = reset to auto). */
    fun editBeaming(beaming: BeamingInfo?)
    /** Apply beam group (start/middle/end) to consecutive selected notes in the same voice. */
    fun groupBeam()
    fun addSlur()
    fun editArticulation(articulation: Articulation)
    fun convertToSmallNotes()
}

/**
 * Routes a key event to the editing action bound to it. Returns true when the
 * event was consumed.
 *
 * When [editor] is active (a selection is present in SELECT/MARQUEE mode), the property actions edit
 * the selection. Otherwise they set the note-pen defaults and flip the left tool to the note pen so
 * the palette mirrors the keyboard — matching how clicking a palette button works.
 *
 * Mutating tool fields (duration/dots/accidental/rest/tie) reuses the same toggle
 * semantics as [NoteToolState] so pressing a chord twice clears it.
 */
fun handleEditingShortcut(
    event: KeyEvent,
    session: EditableScoreHost,
    noteTool: NoteToolState,
    editor: SelectionEditor? = null,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val action = KeybindingStore.actionFor(event) ?: return false
    action.apply(session, noteTool, editor)
    return true
}

private fun ShortcutAction.apply(
    session: EditableScoreHost,
    noteTool: NoteToolState,
    editor: SelectionEditor?,
) {
    val editing = editor?.active == true
    when (this) {
        // File actions need App-owned dialog and controller state; App intercepts them.
        ShortcutAction.NEW_SCORE, ShortcutAction.OPEN_SCORE, ShortcutAction.SAVE_SCORE -> {}
        // App owns the input session/caret and intercepts this before the generic dispatcher.
        ShortcutAction.NOTE_INPUT -> {}
        ShortcutAction.NOTE_WHOLE -> pickDuration(DurationBase.WHOLE, noteTool, editor, editing)
        ShortcutAction.NOTE_HALF -> pickDuration(DurationBase.HALF, noteTool, editor, editing)
        ShortcutAction.NOTE_QUARTER -> pickDuration(DurationBase.QUARTER, noteTool, editor, editing)
        ShortcutAction.NOTE_EIGHTH -> pickDuration(DurationBase.EIGHTH, noteTool, editor, editing)
        ShortcutAction.NOTE_SIXTEENTH -> pickDuration(DurationBase.SIXTEENTH, noteTool, editor, editing)
        ShortcutAction.NOTE_THIRTY_SECOND -> pickDuration(DurationBase.THIRTY_SECOND, noteTool, editor, editing)
        // Rest is an input-only mode (sets the next inserted element), not an editable property.
        ShortcutAction.REST -> { noteTool.activate(); noteTool.restMode = !noteTool.restMode }
        ShortcutAction.DOT -> if (editing) editor!!.editDots(1) else { noteTool.activate(); noteTool.toggleDots(1) }
        ShortcutAction.DOUBLE_DOT -> if (editing) editor!!.editDots(2) else { noteTool.activate(); noteTool.toggleDots(2) }
        ShortcutAction.TIE -> if (editing) editor!!.editTie() else { noteTool.activate(); noteTool.tieMode = !noteTool.tieMode }
        ShortcutAction.TUPLET_2 -> pickTuplet(2, noteTool, editor, editing)
        ShortcutAction.TUPLET_3 -> pickTuplet(3, noteTool, editor, editing)
        ShortcutAction.TUPLET_4 -> pickTuplet(4, noteTool, editor, editing)
        ShortcutAction.TUPLET_5 -> pickTuplet(5, noteTool, editor, editing)
        ShortcutAction.TUPLET_6 -> pickTuplet(6, noteTool, editor, editing)
        ShortcutAction.TUPLET_7 -> pickTuplet(7, noteTool, editor, editing)
        ShortcutAction.TUPLET_8 -> pickTuplet(8, noteTool, editor, editing)
        ShortcutAction.TUPLET_9 -> pickTuplet(9, noteTool, editor, editing)
        ShortcutAction.SHARP -> pickAccidental(Accidental.SHARP, noteTool, editor, editing)
        ShortcutAction.DOUBLE_SHARP -> pickAccidental(Accidental.DOUBLE_SHARP, noteTool, editor, editing)
        ShortcutAction.FLAT -> pickAccidental(Accidental.FLAT, noteTool, editor, editing)
        ShortcutAction.DOUBLE_FLAT -> pickAccidental(Accidental.DOUBLE_FLAT, noteTool, editor, editing)
        ShortcutAction.NATURAL -> pickAccidental(Accidental.NATURAL, noteTool, editor, editing)
        ShortcutAction.VOICE_1 -> pickVoice(1, noteTool, editor, editing)
        ShortcutAction.VOICE_2 -> pickVoice(2, noteTool, editor, editing)
        ShortcutAction.VOICE_3 -> pickVoice(3, noteTool, editor, editing)
        ShortcutAction.VOICE_4 -> pickVoice(4, noteTool, editor, editing)
        ShortcutAction.UNDO -> session.undo()
        ShortcutAction.REDO -> session.redo()
        // The owning RenderedScoreView intercepts viewport navigation through its controller.
        ShortcutAction.SCORE_SYSTEM_UP, ShortcutAction.SCORE_SYSTEM_DOWN -> {}
        // Selection clipboard actions need composition-owned selection state; App intercepts them
        // before reaching here, so there is nothing to do at this layer.
        ShortcutAction.CUT -> {}
        ShortcutAction.COPY -> {}
        ShortcutAction.PASTE -> {}
        // Delete needs the current selection, which lives in the composition; App intercepts this
        // action before reaching here (see App.onKeyEvent), so nothing to do at this layer.
        ShortcutAction.DELETE -> {}
    }
}

private fun pickDuration(base: DurationBase, noteTool: NoteToolState, editor: SelectionEditor?, editing: Boolean) {
    if (editing) editor!!.editDurationBase(base) else noteTool.selectDuration(base)
}

private fun pickAccidental(value: Accidental, noteTool: NoteToolState, editor: SelectionEditor?, editing: Boolean) {
    if (editing) editor!!.editAccidental(value) else { noteTool.activate(); noteTool.toggleAccidental(value) }
}

private fun pickVoice(voiceNumber: Int, noteTool: NoteToolState, editor: SelectionEditor?, editing: Boolean) {
    if (editing) editor!!.editVoice(voiceNumber) else {
        noteTool.activate()
        noteTool.activeVoiceNumber = voiceNumber
    }
}

private fun pickTuplet(count: Int, noteTool: NoteToolState, editor: SelectionEditor?, editing: Boolean) {
    if (editing) editor!!.editTuplet(count) else {
        noteTool.activate()
        noteTool.toggleTupletCount(count)
    }
}

/** Switch to the note pen (and open the palette so it mirrors the keyboard) for immediate note entry. */
private fun NoteToolState.activate() = enterNoteEntry()

private fun NoteToolState.selectDuration(base: DurationBase) {
    activate()
    pickDuration(base)
}
