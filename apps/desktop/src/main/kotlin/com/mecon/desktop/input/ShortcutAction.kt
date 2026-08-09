package com.mecon.desktop.input

import androidx.compose.ui.input.key.Key

/** Display grouping for the shortcut list in the settings dialog. */
enum class ShortcutCategory(val labelKey: String) {
    FILE("dialog.settings.category.file"),
    INPUT("dialog.settings.category.input"),
    DURATION("dialog.settings.category.duration"),
    MODIFIER("dialog.settings.category.modifier"),
    TUPLET("dialog.settings.category.tuplet"),
    ACCIDENTAL("dialog.settings.category.accidental"),
    VOICE("dialog.settings.category.voice"),
    HISTORY("dialog.settings.category.history"),
    EDIT("dialog.settings.category.edit"),
}

/**
 * Every user-rebindable editing action, with its i18n label key and factory-default
 * chord. Defaults follow the agreed layout: 1/2/4/8/6/3 durations, 0 rest, `.` /
 * `Shift+.` dots, s/f/n accidentals (Shift for doubles), `-` tie, Ctrl+Z / Ctrl+Y
 * undo/redo.
 *
 * Actual (possibly customised) bindings live in [KeybindingStore]; this enum only
 * supplies the immutable defaults and metadata.
 */
enum class ShortcutAction(
    val category: ShortcutCategory,
    val labelKey: String,
    val default: KeyStroke,
) {
    NEW_SCORE(ShortcutCategory.FILE, "shortcut.newScore", KeyStroke.of(Key.N, ctrl = true)),
    OPEN_SCORE(ShortcutCategory.FILE, "shortcut.openScore", KeyStroke.of(Key.O, ctrl = true)),
    SAVE_SCORE(ShortcutCategory.FILE, "shortcut.saveScore", KeyStroke.of(Key.S, ctrl = true)),

    NOTE_INPUT(ShortcutCategory.INPUT, "shortcut.noteInput", KeyStroke.of(Key.I)),

    NOTE_WHOLE(ShortcutCategory.DURATION, "shortcut.note.whole", KeyStroke.of(Key.One)),
    NOTE_HALF(ShortcutCategory.DURATION, "shortcut.note.half", KeyStroke.of(Key.Two)),
    NOTE_QUARTER(ShortcutCategory.DURATION, "shortcut.note.quarter", KeyStroke.of(Key.Four)),
    NOTE_EIGHTH(ShortcutCategory.DURATION, "shortcut.note.eighth", KeyStroke.of(Key.Eight)),
    NOTE_SIXTEENTH(ShortcutCategory.DURATION, "shortcut.note.sixteenth", KeyStroke.of(Key.Six)),
    NOTE_THIRTY_SECOND(ShortcutCategory.DURATION, "shortcut.note.thirtySecond", KeyStroke.of(Key.Three)),
    REST(ShortcutCategory.DURATION, "shortcut.rest", KeyStroke.of(Key.Zero)),

    DOT(ShortcutCategory.MODIFIER, "shortcut.dot", KeyStroke.of(Key.Period)),
    DOUBLE_DOT(ShortcutCategory.MODIFIER, "shortcut.doubleDot", KeyStroke.of(Key.Period, shift = true)),
    TIE(ShortcutCategory.MODIFIER, "shortcut.tie", KeyStroke.of(Key.Minus)),

    TUPLET_2(ShortcutCategory.TUPLET, "shortcut.tuplet.2", KeyStroke.of(Key.Two, alt = true)),
    TUPLET_3(ShortcutCategory.TUPLET, "shortcut.tuplet.3", KeyStroke.of(Key.Three, alt = true)),
    TUPLET_4(ShortcutCategory.TUPLET, "shortcut.tuplet.4", KeyStroke.of(Key.Four, alt = true)),
    TUPLET_5(ShortcutCategory.TUPLET, "shortcut.tuplet.5", KeyStroke.of(Key.Five, alt = true)),
    TUPLET_6(ShortcutCategory.TUPLET, "shortcut.tuplet.6", KeyStroke.of(Key.Six, alt = true)),
    TUPLET_7(ShortcutCategory.TUPLET, "shortcut.tuplet.7", KeyStroke.of(Key.Seven, alt = true)),
    TUPLET_8(ShortcutCategory.TUPLET, "shortcut.tuplet.8", KeyStroke.of(Key.Eight, alt = true)),
    TUPLET_9(ShortcutCategory.TUPLET, "shortcut.tuplet.9", KeyStroke.of(Key.Nine, alt = true)),

    SHARP(ShortcutCategory.ACCIDENTAL, "shortcut.sharp", KeyStroke.of(Key.S)),
    DOUBLE_SHARP(ShortcutCategory.ACCIDENTAL, "shortcut.doubleSharp", KeyStroke.of(Key.S, shift = true)),
    FLAT(ShortcutCategory.ACCIDENTAL, "shortcut.flat", KeyStroke.of(Key.F)),
    DOUBLE_FLAT(ShortcutCategory.ACCIDENTAL, "shortcut.doubleFlat", KeyStroke.of(Key.F, shift = true)),
    NATURAL(ShortcutCategory.ACCIDENTAL, "shortcut.natural", KeyStroke.of(Key.N)),

    VOICE_1(ShortcutCategory.VOICE, "shortcut.voice.1", KeyStroke.of(Key.One, ctrl = true)),
    VOICE_2(ShortcutCategory.VOICE, "shortcut.voice.2", KeyStroke.of(Key.Two, ctrl = true)),
    VOICE_3(ShortcutCategory.VOICE, "shortcut.voice.3", KeyStroke.of(Key.Three, ctrl = true)),
    VOICE_4(ShortcutCategory.VOICE, "shortcut.voice.4", KeyStroke.of(Key.Four, ctrl = true)),

    UNDO(ShortcutCategory.HISTORY, "shortcut.undo", KeyStroke.of(Key.Z, ctrl = true)),
    REDO(ShortcutCategory.HISTORY, "shortcut.redo", KeyStroke.of(Key.Y, ctrl = true)),

    CUT(ShortcutCategory.EDIT, "shortcut.cut", KeyStroke.of(Key.X, ctrl = true)),
    COPY(ShortcutCategory.EDIT, "shortcut.copy", KeyStroke.of(Key.C, ctrl = true)),
    PASTE(ShortcutCategory.EDIT, "shortcut.paste", KeyStroke.of(Key.V, ctrl = true)),
    DELETE(ShortcutCategory.EDIT, "shortcut.delete", KeyStroke.of(Key.Delete)),
}
