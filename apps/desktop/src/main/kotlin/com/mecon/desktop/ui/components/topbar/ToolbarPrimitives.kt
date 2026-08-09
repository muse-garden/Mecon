package com.mecon.desktop.ui.components.topbar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.desktop.uikit.theme.MeconColors

/**
 * Shared toolbar building blocks reused by every toolbar group.
 *
 * Groups assemble these primitives; they never re-implement the hover/press
 * styling themselves.
 */

/** A vertical icon-over-label toolbar button with hover / press / active styling. */
@Composable
internal fun ToolbarButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val contentColor = when {
        !enabled -> MeconColors.TextMuted
        isActive -> MeconColors.SelectedIconOnSurface
        else -> MeconColors.IconDefault
    }

    ToolbarTooltip(label) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(
                    when {
                        isActive -> MeconColors.SelectedSurface
                        isPressed -> MeconColors.HoverBackgroundLight
                        isHovered -> MeconColors.HoverBackground
                        else -> Color.Transparent
                    }
                )
                .hoverable(interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                )
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(18.dp),
                tint = contentColor
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                fontSize = 9.sp,
                color = contentColor,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolbarTooltip(text: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(text, fontSize = 11.sp) } },
        state = rememberTooltipState(),
        content = content,
    )
}

/** Spacing + a thin vertical rule used to separate toolbar groups. */
@Composable
internal fun ToolbarDivider() {
    Spacer(Modifier.width(12.dp))
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(32.dp)
            .background(MeconColors.BorderLight.copy(alpha = 0.5f))
    )
    Spacer(Modifier.width(12.dp))
}
