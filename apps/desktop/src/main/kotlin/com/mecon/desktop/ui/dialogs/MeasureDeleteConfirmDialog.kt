package com.mecon.desktop.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.theme.MeconColors

@Composable
fun MeasureDeleteConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = MeconColors.Surface, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(i18n("dialog.deleteMeasures.title"), style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Spacer(Modifier.height(16.dp))
                Text(i18n("dialog.deleteMeasures.message"), color = MeconColors.TextSecondary)
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(i18n("dialog.page.cancel"), color = MeconColors.IconDefault) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C))) {
                        Text(i18n("dialog.deleteMeasures.confirm"))
                    }
                }
            }
        }
    }
}
