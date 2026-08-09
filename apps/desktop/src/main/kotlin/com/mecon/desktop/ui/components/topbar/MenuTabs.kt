package com.mecon.desktop.ui.components.topbar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.desktop.uikit.i18n.i18n

/** The File / Edit / View … menu tab strip. */
@Composable
internal fun MenuTabs(activeTab: String, onTabSelected: (String) -> Unit) {
    val tabs = listOf(
        i18n("menu.file"),
        i18n("menu.edit"),
        i18n("menu.view"),
        i18n("menu.analysis"),
        i18n("menu.exploration"),
        i18n("menu.tools"),
        i18n("menu.help")
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeconColors.SurfaceDark)
            .padding(start = 8.dp, top = 2.dp)
    ) {
        tabs.forEach { tab ->
            MenuTab(
                label = tab,
                isActive = tab == activeTab,
                onClick = { onTabSelected(tab) }
            )
        }
    }
}

/** A single menu tab; when active it draws a top/side border that joins the toolbar. */
@Composable
private fun MenuTab(label: String, isActive: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
            .background(
                when {
                    isActive -> MeconColors.Surface
                    isPressed -> MeconColors.HoverBackgroundLight
                    isHovered -> MeconColors.HoverBackground.copy(alpha = 0.5f)
                    else -> Color.Transparent
                }
            )
            .then(
                if (isActive) Modifier.drawBehind {
                    // Draw only top and side borders (no bottom) for seamless connection with toolbar
                    val strokeWidth = 1.dp.toPx()
                    val cornerRadius = 4.dp.toPx()
                    val halfStroke = strokeWidth / 2

                    // Left border
                    drawLine(
                        color = MeconColors.Border,
                        start = Offset(halfStroke, size.height),
                        end = Offset(halfStroke, cornerRadius),
                        strokeWidth = strokeWidth
                    )
                    // Top border
                    drawLine(
                        color = MeconColors.Border,
                        start = Offset(cornerRadius, halfStroke),
                        end = Offset(size.width - cornerRadius, halfStroke),
                        strokeWidth = strokeWidth
                    )
                    // Right border
                    drawLine(
                        color = MeconColors.Border,
                        start = Offset(size.width - halfStroke, cornerRadius),
                        end = Offset(size.width - halfStroke, size.height),
                        strokeWidth = strokeWidth
                    )
                } else Modifier
            )
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
            color = if (isActive) MeconColors.SelectedIconOnSurface else MeconColors.IconDefault
        )
    }
}
