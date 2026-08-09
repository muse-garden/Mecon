package com.mecon.desktop.uikit.util

import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * Sets the cursor on all visible JFrame windows and their child components recursively.
 * Uses SwingUtilities.invokeLater to ensure thread safety.
 * This ensures the cursor persists during drag even when mouse leaves the drag handle.
 *
 * @param cursor The cursor to set, or null to reset and allow pointerHoverIcon to work
 */
fun setGlobalCursor(cursor: Cursor?) {
    SwingUtilities.invokeLater {
        java.awt.Window.getWindows()
            .filterIsInstance<JFrame>()
            .filter { it.isVisible }
            .forEach { frame ->
                if (cursor != null) {
                    setCursorRecursively(frame, cursor)
                } else {
                    clearCursorRecursively(frame)
                }
            }
    }
}

fun resetGlobalCursor() {
    setGlobalCursor(null)
}

private fun setCursorRecursively(component: Component, cursor: Cursor) {
    component.cursor = cursor
    if (component is Container) {
        component.components.forEach { child ->
            setCursorRecursively(child, cursor)
        }
    }
}

private fun clearCursorRecursively(component: Component) {
    component.cursor = if (component is JFrame) Cursor.getDefaultCursor() else null
    if (component is Container) {
        component.components.forEach { child ->
            clearCursorRecursively(child)
        }
    }
}
