package com.mecon.desktop.ui.components.topbar

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.runtime.Composable
import com.mecon.desktop.uikit.i18n.i18n

@Composable
internal fun EditActions(
    hasSelection: Boolean,
    hasClipboard: Boolean,
    onCopySelection: () -> Unit,
    onCutSelection: () -> Unit,
    onPasteSelection: () -> Unit,
) {
    Row {
        ToolbarButton(
            icon = Icons.Default.ContentCut,
            label = i18n("toolbar.cut"),
            enabled = hasSelection,
            onClick = onCutSelection,
        )
        ToolbarButton(
            icon = Icons.Default.ContentCopy,
            label = i18n("toolbar.copy"),
            enabled = hasSelection,
            onClick = onCopySelection,
        )
        ToolbarButton(
            icon = Icons.Default.ContentPaste,
            label = i18n("toolbar.paste"),
            enabled = hasClipboard && hasSelection,
            onClick = onPasteSelection,
        )
    }
}
