package com.mecon.desktop.uikit.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.desktop.uikit.theme.MeconColors

/**
 * A padded, theme-aware dropdown surface for compact selectors.
 *
 * The panel deliberately owns only placement, spacing, and palette. Callers provide the domain
 * choices, so the same surface can host tonal, notation, or plugin settings.
 */
@Composable
fun MeconDropdownPanel(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    MeconDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        containerColor = MeconColors.DialogBackground,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** Small heading style intended for sections inside a [MeconDropdownPanel]. */
@Composable
fun MeconDropdownPanelTitle(text: String) {
    Text(text, color = MeconColors.TextMuted, fontSize = 10.sp)
}
