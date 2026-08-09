package com.mecon.desktop.ui.components.topbar

import com.mecon.desktop.uikit.components.meconTextInputFocus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.theme.MeconColors

/** Controls activated by the Insert tab for a selected barline or measure cell. */
@Composable
internal fun MeasureActions(
    selectedBarlineMeasure: Int?,
    hasMeasureSelection: Boolean,
    onInsertMeasures: (Int) -> Unit,
    onDeleteMeasures: () -> Unit,
) {
    var countText by remember { mutableStateOf("1") }
    val count = countText.toIntOrNull()?.takeIf { it > 0 }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            BasicTextField(
                value = countText,
                onValueChange = { value -> countText = value.filter(Char::isDigit).take(3) },
                modifier = Modifier
                    .width(56.dp)
                    .height(20.dp)
                    .border(1.dp, MeconColors.BorderLight)
                    .background(MeconColors.Background)
                    .padding(horizontal = 4.dp)
                    .meconTextInputFocus(),
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center),
            )
            TextButton(
                enabled = selectedBarlineMeasure != null && count != null,
                onClick = { count?.let(onInsertMeasures) },
                modifier = Modifier.height(36.dp),
            ) {
                val color = if (selectedBarlineMeasure != null && count != null) {
                    MeconColors.IconDefault
                } else MeconColors.TextMuted
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
                Spacer(Modifier.width(4.dp))
                Text(i18n("toolbar.insertMeasures"), color = color, fontSize = 9.sp)
            }
        }
        ToolbarButton(
            icon = Icons.Default.Delete,
            label = i18n("toolbar.deleteMeasures"),
            enabled = hasMeasureSelection,
            onClick = onDeleteMeasures,
        )
    }
}
