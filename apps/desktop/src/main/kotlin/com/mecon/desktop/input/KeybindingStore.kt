package com.mecon.desktop.input

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.input.key.KeyEvent
import java.util.prefs.Preferences

/**
 * The single source of truth for the current (possibly user-customised) editing
 * shortcuts. Bindings persist immediately to [Preferences]; reads are Compose
 * snapshot state so the settings dialog recomposes when a chord changes.
 *
 * A binding may be `null` (explicitly unbound) — distinct from "use the default".
 * The sentinel [UNBOUND] is stored for that case so a removed binding does not
 * silently fall back to its factory chord on next launch.
 */
object KeybindingStore {

    private const val UNBOUND = "none"

    private val prefs = Preferences.userRoot().node("com/mecon/desktop/keybindings")

    // action -> chord (null = explicitly unbound). Snapshot state for Compose reads.
    private val bindings = mutableStateMapOf<ShortcutAction, KeyStroke?>().apply {
        ShortcutAction.entries.forEach { action ->
            put(action, loadStored(action))
        }
    }

    private fun loadStored(action: ShortcutAction): KeyStroke? =
        when (val raw = prefs.get(action.name, null)) {
            null -> action.default
            UNBOUND -> null
            else -> KeyStroke.deserialize(raw) ?: action.default
        }

    /** Current chord for [action], or null when unbound. */
    fun binding(action: ShortcutAction): KeyStroke? = bindings[action]

    /**
     * Assign [stroke] to [action]. Any other action holding the same chord is
     * unbound first, so a chord maps to at most one action.
     */
    fun setBinding(action: ShortcutAction, stroke: KeyStroke) {
        bindings.entries
            .filter { it.key != action && it.value == stroke }
            .map { it.key }
            .forEach { conflicting ->
                bindings[conflicting] = null
                prefs.put(conflicting.name, UNBOUND)
            }
        bindings[action] = stroke
        prefs.put(action.name, stroke.serialize())
    }

    /** Remove [action]'s chord entirely (stays unbound across restarts). */
    fun clearBinding(action: ShortcutAction) {
        bindings[action] = null
        prefs.put(action.name, UNBOUND)
    }

    /** Restore [action] to its factory default. */
    fun resetToDefault(action: ShortcutAction) {
        bindings[action] = action.default
        prefs.remove(action.name)
    }

    /** Restore every action to its factory default. */
    fun resetAll() {
        ShortcutAction.entries.forEach { resetToDefault(it) }
    }

    /** The action whose chord exactly matches [event], or null. */
    fun actionFor(event: KeyEvent): ShortcutAction? =
        ShortcutAction.entries.firstOrNull { bindings[it]?.matches(event) == true }
}
