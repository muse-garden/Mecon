package com.mecon.desktop.ui.components.topbar

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mecon.desktop.service.EditableScoreHost
import com.mecon.desktop.uikit.i18n.i18n

/**
 * History group: undo / redo, driven straight off the [EditableScoreHost]. Buttons dim
 * and no-op when their action is unavailable.
 */
@Composable
internal fun HistoryActions(session: EditableScoreHost?) {
    ToolbarButton(
        icon = Icons.Default.Undo,
        label = i18n("toolbar.undo"),
        enabled = session?.canUndo == true,
        onClick = { session?.undo() }
    )

    Spacer(Modifier.width(4.dp))

    ToolbarButton(
        icon = Icons.Default.Redo,
        label = i18n("toolbar.redo"),
        enabled = session?.canRedo == true,
        onClick = { session?.redo() }
    )
}
