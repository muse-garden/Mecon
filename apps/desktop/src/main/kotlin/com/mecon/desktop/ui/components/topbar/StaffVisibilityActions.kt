package com.mecon.desktop.ui.components.topbar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.theme.MeconColors

/**
 * Edit-tab controls that hide the selected measure cells / all following measures on their staves.
 * Buttons grey out when the region carries notes; a tooltip then explains why.
 */
@Composable
internal fun StaffVisibilityActions(
    hideEnabled: Boolean,
    hideFollowingEnabled: Boolean,
    blockedByNotes: Boolean,
    onHide: () -> Unit,
    onHideFollowing: () -> Unit,
) {
    Row {
        HideButton(
            label = i18n("toolbar.hideMeasures"),
            enabled = hideEnabled,
            blockedByNotes = blockedByNotes,
            onClick = onHide,
        )
        HideButton(
            label = i18n("toolbar.hideFollowingMeasures"),
            enabled = hideFollowingEnabled,
            blockedByNotes = blockedByNotes,
            onClick = onHideFollowing,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HideButton(label: String, enabled: Boolean, blockedByNotes: Boolean, onClick: () -> Unit) {
    val button = @Composable {
        ToolbarButton(
            icon = Icons.Default.VisibilityOff,
            label = label,
            enabled = enabled,
            onClick = onClick,
        )
    }
    if (blockedByNotes) {
        TooltipArea(tooltip = {
            Text(
                i18n("toolbar.hideMeasures.hasNotesTooltip"),
                fontSize = 11.sp,
                color = MeconColors.TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MeconColors.Surface)
                    .padding(8.dp),
            )
        }) { button() }
    } else {
        button()
    }
}
