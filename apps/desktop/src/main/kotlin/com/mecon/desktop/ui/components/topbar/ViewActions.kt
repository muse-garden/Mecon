package com.mecon.desktop.ui.components.topbar

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.runtime.Composable
import com.mecon.desktop.uikit.i18n.i18n

enum class ScoreViewMode { EDIT, PREVIEW }

@Composable
internal fun ViewActions(
    mode: ScoreViewMode,
    onModeChange: (ScoreViewMode) -> Unit,
    showMeasureNumbers: Boolean,
    onToggleMeasureNumbers: () -> Unit,
    splitView: Boolean,
    onToggleSplitView: () -> Unit,
) {
    Row {
        ToolbarButton(
            icon = Icons.Default.Edit,
            label = i18n("toolbar.editMode"),
            isActive = mode == ScoreViewMode.EDIT,
            onClick = { onModeChange(ScoreViewMode.EDIT) },
        )
        ToolbarButton(
            icon = Icons.Default.Visibility,
            label = i18n("toolbar.previewMode"),
            isActive = mode == ScoreViewMode.PREVIEW,
            onClick = { onModeChange(ScoreViewMode.PREVIEW) },
        )
        ToolbarButton(
            icon = Icons.Default.FormatListNumbered,
            label = i18n("toolbar.measureNumbers"),
            isActive = showMeasureNumbers,
            onClick = onToggleMeasureNumbers,
        )
        ToolbarButton(
            icon = Icons.Default.ViewModule,
            label = "分屏对照",
            isActive = splitView,
            onClick = onToggleSplitView,
        )
    }
}
