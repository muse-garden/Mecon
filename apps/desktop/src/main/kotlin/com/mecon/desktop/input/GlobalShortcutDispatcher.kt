package com.mecon.desktop.input

import androidx.compose.ui.input.key.KeyEvent

/** Window-level shortcut bridge; runs before whichever child currently owns keyboard focus. */
object GlobalShortcutDispatcher {
    private var handler: ((KeyEvent) -> Boolean)? = null

    fun install(value: (KeyEvent) -> Boolean) {
        handler = value
    }

    fun clear() {
        handler = null
    }

    fun dispatch(event: KeyEvent): Boolean = handler?.invoke(event) == true
}
