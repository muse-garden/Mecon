package com.mecon.desktop.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.desktop.uikit.components.ResizablePanelItem
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.theme.MeconColors

@Composable
internal fun RangeCheckerPanel() {
    ResizablePanelItem(
        title = i18n("panel.rangeChecker"),
        icon = Icons.Default.Warning,
        initialHeight = 100.dp,
    ) {
        Text(
            i18n("panel.rangeChecker.description"),
            fontSize = 11.sp,
            color = MeconColors.TextSecondary,
        )
    }
}
