package com.mecon.desktop.uikit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.desktop.uikit.theme.MeconColors

/**
 * Pill-style boolean toggle shared by every "on/off" option in the app (chord-tone
 * coloring, free-practice idiom filters, key-popup overlap flags, etc.), so they all
 * share one visual language instead of each screen hand-rolling a Box + Row pill or
 * falling back to the default-themed Material3 [androidx.compose.material3.Checkbox].
 */
@Composable
fun MeconSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(32.dp, 18.dp)
            .clickable { onCheckedChange(!checked) }
            .background(
                if (checked) MeconColors.PrimaryLight.copy(alpha = 0.85f) else MeconColors.Border,
                RoundedCornerShape(9.dp),
            )
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .background(MeconColors.White, RoundedCornerShape(7.dp)),
        )
    }
}

/**
 * [MeconSwitch] paired with a leading label in a space-between row — the common
 * "settings row" shape (label on the left, switch on the right), replacing the
 * Material3 [androidx.compose.material3.Checkbox] rows that used to render with the
 * unstyled default checkbox palette instead of [MeconColors].
 */
@Composable
fun MeconLabeledSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    labelColor: Color = MeconColors.TextSecondary,
    fontSize: TextUnit = 10.sp,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = fontSize, color = labelColor)
        MeconSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
