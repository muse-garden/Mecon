package com.mecon.desktop.uikit.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import com.mecon.desktop.uikit.theme.MeconColors

/**
 * Theme-aware dropdown menu + item, styled entirely from [MeconColors] instead of the ambient
 * Material color scheme, so both honour the app skin (menu surface, text and icon tints all come
 * from the palette). Use these in place of the raw Material3 [DropdownMenu] / [DropdownMenuItem] for
 * any in-app menu so restyling the palette restyles every menu at once.
 *
 * The Material menu still owns positioning, dismiss and animation; only its colours are overridden.
 * [containerColor] defaults to the standard surface but can be a different palette role (e.g.
 * [MeconColors.DialogBackground] for menus opened from a dialog).
 */
@Composable
fun MeconDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MeconColors.Surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        containerColor = containerColor,
        content = content,
    )
}

/**
 * A [MeconDropdownMenu] entry with palette-driven text and (optional) leading-icon colours.
 *
 * @param label   the item text
 * @param onClick invoked when the item is chosen
 * @param icon    optional leading icon, tinted [MeconColors.IconDefault]
 * @param enabled when false the item is greyed ([MeconColors.TextMuted]) and non-interactive
 */
@Composable
fun MeconDropdownItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    DropdownMenuItem(
        modifier = modifier,
        enabled = enabled,
        text = { Text(label, fontSize = 12.sp) },
        leadingIcon = icon?.let { { Icon(it, contentDescription = null) } },
        onClick = onClick,
        colors = MenuDefaults.itemColors(
            textColor = MeconColors.TextPrimary,
            leadingIconColor = MeconColors.IconDefault,
            trailingIconColor = MeconColors.IconDefault,
            disabledTextColor = MeconColors.TextMuted,
            disabledLeadingIconColor = MeconColors.TextMuted,
            disabledTrailingIconColor = MeconColors.TextMuted,
        ),
    )
}
