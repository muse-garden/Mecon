package com.mecon.desktop.ui.dialogs

import com.mecon.desktop.uikit.components.meconTextInputFocus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mecon.api.storage.PageLayoutConfig
import com.mecon.api.storage.PaperPreset
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.theme.MeconColors

/**
 * Page layout settings: paper size presets, dimensions / margins (mm), staff size
 * (score scale) and the line/page-break toggle. Editing commits a new
 * [PageLayoutConfig] which is persisted on the score and drives line-breaking.
 */
@Composable
fun PageSettingsDialog(
    config: PageLayoutConfig,
    onApply: (PageLayoutConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var paperW by remember { mutableStateOf(config.paperWidthMm.toString()) }
    var paperH by remember { mutableStateOf(config.paperHeightMm.toString()) }
    var mTop by remember { mutableStateOf(config.marginTopMm.toString()) }
    var mBottom by remember { mutableStateOf(config.marginBottomMm.toString()) }
    var mLeft by remember { mutableStateOf(config.marginLeftMm.toString()) }
    var mRight by remember { mutableStateOf(config.marginRightMm.toString()) }
    var staffSpace by remember { mutableStateOf(config.staffSpaceMm.toString()) }
    var paginated by remember { mutableStateOf(config.paginated) }
    var presetName by remember { mutableStateOf(config.presetName) }

    fun applyPreset(p: PaperPreset) {
        paperW = p.widthMm.toString()
        paperH = p.heightMm.toString()
        presetName = p.displayName
    }

    fun buildConfig(): PageLayoutConfig {
        fun f(s: String, fallback: Float) = s.trim().toFloatOrNull() ?: fallback
        return config.copy(
            paperWidthMm = f(paperW, config.paperWidthMm),
            paperHeightMm = f(paperH, config.paperHeightMm),
            marginTopMm = f(mTop, config.marginTopMm),
            marginBottomMm = f(mBottom, config.marginBottomMm),
            marginLeftMm = f(mLeft, config.marginLeftMm),
            marginRightMm = f(mRight, config.marginRightMm),
            staffSpaceMm = f(staffSpace, config.staffSpaceMm).coerceAtLeast(0.3f),
            paginated = paginated,
            presetName = presetName
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(520.dp).heightIn(max = 720.dp),
            shape = RoundedCornerShape(12.dp),
            color = MeconColors.Surface,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                // Title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = i18n("dialog.page.title"),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = MeconColors.IconDefault)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Paginate toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(i18n("dialog.page.paginate"), color = Color.White, fontSize = 14.sp)
                    Switch(
                        checked = paginated,
                        onCheckedChange = { paginated = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = MeconColors.Primary)
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Paper presets
                Text(i18n("dialog.page.preset"), color = MeconColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PaperPreset.ALL.forEach { p ->
                        PresetChip(label = p.displayName, selected = presetName == p.displayName) { applyPreset(p) }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Paper dimensions
                Text(i18n("dialog.page.paperSize"), color = MeconColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(i18n("dialog.page.width"), paperW, Modifier.weight(1f)) { paperW = it; presetName = null }
                    NumberField(i18n("dialog.page.height"), paperH, Modifier.weight(1f)) { paperH = it; presetName = null }
                }

                Spacer(Modifier.height(12.dp))

                // Margins
                Text(i18n("dialog.page.margins"), color = MeconColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(i18n("dialog.page.top"), mTop, Modifier.weight(1f)) { mTop = it }
                    NumberField(i18n("dialog.page.bottom"), mBottom, Modifier.weight(1f)) { mBottom = it }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(i18n("dialog.page.left"), mLeft, Modifier.weight(1f)) { mLeft = it }
                    NumberField(i18n("dialog.page.right"), mRight, Modifier.weight(1f)) { mRight = it }
                }

                Spacer(Modifier.height(12.dp))

                // Staff size (score scale)
                NumberField(i18n("dialog.page.staffSize"), staffSpace, Modifier.fillMaxWidth()) { staffSpace = it }

                Spacer(Modifier.height(24.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(i18n("dialog.page.cancel"), color = MeconColors.IconDefault)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onApply(buildConfig()); onDismiss() },
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
private fun PresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        color = if (selected) MeconColors.SelectedSurface else MeconColors.SurfaceLight,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = label,
            color = if (selected) MeconColors.SelectedIcon else MeconColors.IconDefault,
            fontSize = 12.sp,
            modifier = Modifier
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun NumberField(
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
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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
