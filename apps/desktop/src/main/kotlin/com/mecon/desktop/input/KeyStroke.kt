package com.mecon.desktop.input

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key

/**
 * A single, fully-qualified keyboard chord: a base [Key] plus the exact modifier
 * combination held with it. Modifier match is exact, so `Shift+.` and `.` are
 * distinct bindings (needed to split single vs. double dot, undo vs. redo, …).
 *
 * Stored as the raw `keyCode` (Long) so it survives [serialize]/[deserialize]
 * round-trips into [java.util.prefs.Preferences].
 */
data class KeyStroke(
    val keyCode: Long,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
) {
    /** True when [event] is exactly this chord (same key, same modifier set). */
    fun matches(event: KeyEvent): Boolean =
        event.key.keyCode == keyCode &&
            event.isCtrlPressed == ctrl &&
            event.isShiftPressed == shift &&
            event.isAltPressed == alt

    /** Round-trippable string form for preference storage. */
    fun serialize(): String = "$keyCode;${b(ctrl)};${b(shift)};${b(alt)}"

    /** Human-readable label, e.g. `Ctrl+Shift+Z`, `4`, `.`. */
    fun displayLabel(): String = buildString {
        if (ctrl) append("Ctrl+")
        if (shift) append("Shift+")
        if (alt) append("Alt+")
        append(keyLabel(keyCode))
    }

    companion object {
        private fun b(v: Boolean) = if (v) "1" else "0"

        /** Build a chord from a [Key] plus modifier flags. */
        fun of(key: Key, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false): KeyStroke =
            KeyStroke(key.keyCode, ctrl, shift, alt)

        /** Build a chord from a captured key event (ignores nothing — caller filters bare modifiers). */
        fun fromEvent(event: KeyEvent): KeyStroke =
            KeyStroke(event.key.keyCode, event.isCtrlPressed, event.isShiftPressed, event.isAltPressed)

        fun deserialize(s: String): KeyStroke? {
            val parts = s.split(";")
            if (parts.size != 4) return null
            val code = parts[0].toLongOrNull() ?: return null
            return KeyStroke(code, parts[1] == "1", parts[2] == "1", parts[3] == "1")
        }
    }
}

/** Keys that are modifiers themselves — never accepted as a binding's base key. */
fun Key.isModifierKey(): Boolean = this in MODIFIER_KEYS

private val MODIFIER_KEYS = setOf(
    Key.CtrlLeft, Key.CtrlRight,
    Key.ShiftLeft, Key.ShiftRight,
    Key.AltLeft, Key.AltRight,
    Key.MetaLeft, Key.MetaRight,
)

/** Best-effort display name for a raw key code. Falls back to a generic token. */
private fun keyLabel(keyCode: Long): String = KEY_LABELS[keyCode] ?: "Key($keyCode)"

private val KEY_LABELS: Map<Long, String> = buildMap {
    put(Key.Zero.keyCode, "0"); put(Key.One.keyCode, "1"); put(Key.Two.keyCode, "2")
    put(Key.Three.keyCode, "3"); put(Key.Four.keyCode, "4"); put(Key.Five.keyCode, "5")
    put(Key.Six.keyCode, "6"); put(Key.Seven.keyCode, "7"); put(Key.Eight.keyCode, "8")
    put(Key.Nine.keyCode, "9")
    put(Key.A.keyCode, "A"); put(Key.B.keyCode, "B"); put(Key.C.keyCode, "C")
    put(Key.D.keyCode, "D"); put(Key.E.keyCode, "E"); put(Key.F.keyCode, "F")
    put(Key.G.keyCode, "G"); put(Key.H.keyCode, "H"); put(Key.I.keyCode, "I")
    put(Key.J.keyCode, "J"); put(Key.K.keyCode, "K"); put(Key.L.keyCode, "L")
    put(Key.M.keyCode, "M"); put(Key.N.keyCode, "N"); put(Key.O.keyCode, "O")
    put(Key.P.keyCode, "P"); put(Key.Q.keyCode, "Q"); put(Key.R.keyCode, "R")
    put(Key.S.keyCode, "S"); put(Key.T.keyCode, "T"); put(Key.U.keyCode, "U")
    put(Key.V.keyCode, "V"); put(Key.W.keyCode, "W"); put(Key.X.keyCode, "X")
    put(Key.Y.keyCode, "Y"); put(Key.Z.keyCode, "Z")
    put(Key.Period.keyCode, "."); put(Key.Comma.keyCode, ",")
    put(Key.Minus.keyCode, "-"); put(Key.Equals.keyCode, "=")
    put(Key.Slash.keyCode, "/"); put(Key.Backslash.keyCode, "\\")
    put(Key.Semicolon.keyCode, ";"); put(Key.Apostrophe.keyCode, "'")
    put(Key.LeftBracket.keyCode, "["); put(Key.RightBracket.keyCode, "]")
    put(Key.Grave.keyCode, "`")
    put(Key.Spacebar.keyCode, "Space"); put(Key.Enter.keyCode, "Enter")
    put(Key.Tab.keyCode, "Tab"); put(Key.Backspace.keyCode, "Backspace")
    put(Key.Delete.keyCode, "Delete"); put(Key.Escape.keyCode, "Esc")
    put(Key.DirectionUp.keyCode, "↑"); put(Key.DirectionDown.keyCode, "↓")
    put(Key.DirectionLeft.keyCode, "←"); put(Key.DirectionRight.keyCode, "→")
}
