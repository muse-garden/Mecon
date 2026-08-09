package com.mecon.desktop.uikit.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.desktop.uikit.theme.MeconColors

/** Theme-aware compact choice chip for selectors and dropdown panels. */
@Composable
fun MeconChoiceChip(
    label: String,
    selected: Boolean,
    accent: Color = MeconColors.Primary,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = if (selected) accent.copy(alpha = 0.28f) else MeconColors.SurfaceDark,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, if (selected) accent else MeconColors.Border),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                color = if (selected) MeconColors.TextPrimary else MeconColors.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
    }
}
