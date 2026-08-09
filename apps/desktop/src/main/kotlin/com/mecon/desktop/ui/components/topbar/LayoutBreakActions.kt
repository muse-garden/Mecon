package com.mecon.desktop.ui.components.topbar

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.runtime.Composable
import com.mecon.api.interaction.LayoutBreakKind
import com.mecon.desktop.uikit.i18n.i18n

@Composable
internal fun LayoutBreakActions(
    enabled: Boolean,
    selectedKind: LayoutBreakKind?,
    onToggle: (LayoutBreakKind) -> Unit,
) {
    Row {
        ToolbarButton(
            icon = Icons.Default.KeyboardReturn,
            label = i18n("toolbar.systemBreak"),
            enabled = enabled,
            isActive = selectedKind == LayoutBreakKind.SYSTEM,
            onClick = { onToggle(LayoutBreakKind.SYSTEM) },
        )
        ToolbarButton(
            icon = Icons.Default.MenuBook,
            label = i18n("toolbar.pageBreak"),
            enabled = enabled,
            isActive = selectedKind == LayoutBreakKind.PAGE,
            onClick = { onToggle(LayoutBreakKind.PAGE) },
        )
    }
}
