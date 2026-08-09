package com.mecon.desktop.ui.dialogs

import com.mecon.desktop.uikit.components.meconTextInputFocus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mecon.api.storage.ScoreMetadata
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.theme.MeconColors

@Composable
fun ScoreMetadataDialog(
    metadata: ScoreMetadata,
    onApply: (ScoreMetadata) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember(metadata) { mutableStateOf(metadata.title) }
    var subtitle by remember(metadata) { mutableStateOf(metadata.subtitle.orEmpty()) }
    var composer by remember(metadata) { mutableStateOf(metadata.composer.orEmpty()) }
    var arranger by remember(metadata) { mutableStateOf(metadata.arranger.orEmpty()) }
    var lyricist by remember(metadata) { mutableStateOf(metadata.lyricist.orEmpty()) }
    var copyright by remember(metadata) { mutableStateOf(metadata.copyright.orEmpty()) }

    fun buildMetadata(): ScoreMetadata = metadata.copy(
        title = title.ifBlank { "Untitled" },
        subtitle = subtitle.ifBlank { null },
        composer = composer.ifBlank { null },
        arranger = arranger.ifBlank { null },
        lyricist = lyricist.ifBlank { null },
        copyright = copyright.ifBlank { null }
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(560.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            color = MeconColors.Surface,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = i18n("dialog.metadata.title"),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = MeconColors.IconDefault)
                    }
                }

                Spacer(Modifier.height(16.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    MetadataSectionLabel(i18n("dialog.new.metadata"))
                    MetadataTextField(i18n("dialog.new.titleField"), title, Modifier.fillMaxWidth()) { title = it }

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetadataTextField(i18n("dialog.new.subtitle"), subtitle, Modifier.weight(1f)) { subtitle = it }
                        MetadataTextField(i18n("dialog.new.composer"), composer, Modifier.weight(1f)) { composer = it }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetadataTextField(i18n("dialog.new.arranger"), arranger, Modifier.weight(1f)) { arranger = it }
                        MetadataTextField(i18n("dialog.new.lyricist"), lyricist, Modifier.weight(1f)) { lyricist = it }
                    }

                    Spacer(Modifier.height(8.dp))
                    MetadataTextField(i18n("dialog.new.copyright"), copyright, Modifier.fillMaxWidth()) { copyright = it }
                }

                Spacer(Modifier.height(20.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text(i18n("dialog.page.cancel"), color = MeconColors.IconDefault)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onApply(buildMetadata())
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MeconColors.Primary,
                            contentColor = Color.White
                        )
                    ) {
                        Text(i18n("dialog.page.apply"))
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataSectionLabel(text: String) {
    Text(
        text = text,
        color = MeconColors.TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun MetadataTextField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        modifier = modifier.meconTextInputFocus(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MeconColors.Primary,
            unfocusedBorderColor = MeconColors.BorderLight,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedLabelColor = MeconColors.TextSecondary,
            unfocusedLabelColor = MeconColors.TextMuted,
            cursorColor = MeconColors.Primary
        )
    )
}
