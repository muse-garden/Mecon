package com.mecon.desktop.uikit.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.desktop.uikit.theme.MeconColors

/**
 * Compact, reusable fifth-circle menu for choosing or changing a tonal key.
 *
 * Callers own the anchor, domain conversion, labels and insertion semantics. The optional
 * terminate toggle is intended for inserting an overlapping modulation line.
 */
@Composable
fun CircleOfFifthsPopupMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    currentKey: FifthsKey,
    onKeyClick: (FifthsKey) -> Unit,
    label: (FifthsKey) -> String,
    modifier: Modifier = Modifier,
    title: String = "选择调性",
    caption: String? = null,
    terminatePrevious: Boolean? = null,
    onTerminatePreviousChange: ((Boolean) -> Unit)? = null,
) {
    MeconDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.width(304.dp),
        containerColor = MeconColors.DialogBackground,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, color = MeconColors.TextPrimary, fontSize = 13.sp)
            caption?.let {
                Text(it, color = MeconColors.TextMuted, fontSize = 9.sp)
            }
            if (terminatePrevious != null && onTerminatePreviousChange != null) {
                Row(
                    modifier = Modifier.width(276.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("终止之前调性", color = MeconColors.TextPrimary, fontSize = 11.sp)
                        Text("前后调性仍在当前和弦重叠", color = MeconColors.TextMuted, fontSize = 9.sp)
                    }
                    MeconSwitch(checked = terminatePrevious, onCheckedChange = onTerminatePreviousChange)
                }
            }
            CircleOfFifthsPicker(
                currentKey = currentKey,
                selectedKeys = emptySet(),
                size = 276.dp,
                centerLabel = label(currentKey),
                label = label,
                onKeyClick = onKeyClick,
            )
        }
    }
}
