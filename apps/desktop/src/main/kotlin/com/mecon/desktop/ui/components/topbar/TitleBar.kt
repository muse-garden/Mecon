package com.mecon.desktop.ui.components.topbar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.desktop.uikit.theme.MeconColors

/** The slim window title bar: app brand, current file name, and window controls. */
@Composable
internal fun TitleBar(currentFileName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(MeconColors.SurfaceDark)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.LibraryMusic,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = MeconColors.Primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Mecon",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MeconColors.TextPrimary
        )
        Spacer(Modifier.width(8.dp))
        Text("|", fontSize = 11.sp, color = MeconColors.TextDark)
        Spacer(Modifier.width(8.dp))
        Text(
            currentFileName,
            fontSize = 11.sp,
            color = MeconColors.TextMuted
        )
        Spacer(Modifier.weight(1f))

        WindowControlButton(icon = Icons.Default.Minimize, contentDescription = "Minimize", onClick = {})
        Spacer(Modifier.width(8.dp))
        WindowControlButton(icon = Icons.Default.Fullscreen, contentDescription = "Maximize", onClick = {})
    }
}

/** A single 10dp window control icon (minimize / maximize) with hover tint. */
@Composable
private fun WindowControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = Modifier
            .size(10.dp)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        tint = if (isHovered) MeconColors.SelectedIcon else MeconColors.IconDefault
    )
}
