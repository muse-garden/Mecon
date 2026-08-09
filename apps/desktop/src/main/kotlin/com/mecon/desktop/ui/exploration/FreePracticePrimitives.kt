package com.mecon.desktop.ui.exploration

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.desktop.uikit.components.MeconChoiceChip
import com.mecon.desktop.uikit.components.CollapsiblePanelItem

@Composable
internal fun WorkbenchPanel(
    title: String,
    modifier: Modifier = Modifier,
    onCollapse: (() -> Unit)? = null,
    fillContentHeight: Boolean = false,
    initiallyCollapsed: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!fillContentHeight) {
        CollapsiblePanelItem(
            title = title,
            modifier = modifier,
            initiallyCollapsed = initiallyCollapsed,
            contentModifier = Modifier.padding(8.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(9.dp),
                content = content,
            )
        }
        return
    }
    Column(
        modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                title,
                color = MeconColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            if (onCollapse != null) {
                Spacer(Modifier.weight(1f))
                Text(
                    "折叠",
                    color = MeconColors.TextMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.clickable(onClick = onCollapse),
                )
            }
        }
        content()
    }
}

@Composable
internal fun PracticeChip(
    label: String,
    selected: Boolean,
    accent: Color = MeconColors.Primary,
    onClick: () -> Unit,
) {
    MeconChoiceChip(label = label, selected = selected, accent = accent, onClick = onClick)
}
