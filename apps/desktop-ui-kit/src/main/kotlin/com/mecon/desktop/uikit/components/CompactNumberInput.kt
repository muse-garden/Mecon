package com.mecon.desktop.uikit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.desktop.uikit.theme.MeconColors

/**
 * Compact bounded integer input shared by score creation forms and toolbars.
 *
 * The draft stays local while the user edits, while valid values are emitted immediately and
 * the stepper buttons always remain clamped to [range].
 */
@Composable
fun CompactNumberInput(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
) {
    var draft by remember(value) { mutableStateOf(value.toString()) }
    val height = if (dense) 24.dp else 30.dp
    val buttonSize = if (dense) 22.dp else 27.dp
    Row(
        modifier = modifier
            .height(height)
            .border(1.dp, MeconColors.BorderLight, RoundedCornerShape(6.dp))
            .background(MeconColors.Surface, RoundedCornerShape(6.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            enabled = value > range.first,
            onClick = { onValueChange(value - 1) },
            modifier = Modifier.size(buttonSize),
        ) {
            Icon(
                Icons.Default.Remove,
                contentDescription = "减少",
                tint = if (value > range.first) MeconColors.IconDefault else MeconColors.TextDark,
                modifier = Modifier.size(13.dp),
            )
        }
        BasicTextField(
            value = draft,
            onValueChange = { input ->
                if (input.length <= range.last.toString().length && input.all(Char::isDigit)) {
                    draft = input
                    input.toIntOrNull()?.takeIf { it in range }?.let(onValueChange)
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = LocalTextStyle.current.copy(
                color = MeconColors.TextPrimary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            ),
            cursorBrush = SolidColor(MeconColors.Primary),
            modifier = Modifier.weight(1f).meconTextInputFocus(),
        )
        IconButton(
            enabled = value < range.last,
            onClick = { onValueChange(value + 1) },
            modifier = Modifier.size(buttonSize),
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "增加",
                tint = if (value < range.last) MeconColors.IconDefault else MeconColors.TextDark,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}
