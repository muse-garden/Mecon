package com.mecon.desktop.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mecon.desktop.service.AutosaveEntry
import com.mecon.desktop.service.ScoreFileController
import com.mecon.desktop.ui.views.SimpleScoreView
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.theme.MeconColors
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun DocumentSafetyDialogs(fileController: ScoreFileController) {
    if (fileController.showUnsavedChangesPrompt) {
        AlertDialog(
            onDismissRequest = fileController::cancelPendingDocumentAction,
            title = { Text(i18n("dialog.unsaved.title")) },
            text = { Text(i18n("dialog.unsaved.message")) },
            confirmButton = {
                Button(onClick = fileController::saveAndContinue) {
                    Text(i18n("dialog.unsaved.save"))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = fileController::discardAndContinue) {
                        Text(i18n("dialog.unsaved.discard"))
                    }
                    TextButton(onClick = fileController::cancelPendingDocumentAction) {
                        Text(i18n("dialog.unsaved.cancel"))
                    }
                }
            },
        )
    }

    if (fileController.showRecoveryStartupPrompt) {
        AlertDialog(
            onDismissRequest = { fileController.showRecoveryStartupPrompt = false },
            title = { Text(i18n("dialog.recovery.availableTitle")) },
            text = { Text(i18n("dialog.recovery.availableMessage")) },
            confirmButton = {
                Button(onClick = fileController::openRecoveryCenter) {
                    Text(i18n("dialog.recovery.view"))
                }
            },
            dismissButton = {
                TextButton(onClick = { fileController.showRecoveryStartupPrompt = false }) {
                    Text(i18n("dialog.recovery.later"))
                }
            },
        )
    }

    if (fileController.showRecoveryCenter) RecoveryCenterDialog(fileController)

    fileController.pendingRecoveryDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { fileController.pendingRecoveryDelete = null },
            title = { Text(i18n("dialog.recovery.deleteTitle")) },
            text = { Text(i18n("dialog.recovery.deleteMessage").replace("{name}", entry.fileName)) },
            confirmButton = {
                Button(
                    onClick = fileController::confirmDeleteRecovery,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
                ) { Text(i18n("dialog.recovery.delete")) }
            },
            dismissButton = {
                TextButton(onClick = { fileController.pendingRecoveryDelete = null }) {
                    Text(i18n("dialog.unsaved.cancel"))
                }
            },
        )
    }

    fileController.pendingDivergedRecovery?.let { entry ->
        AlertDialog(
            onDismissRequest = { fileController.pendingDivergedRecovery = null },
            title = { Text(i18n("dialog.recovery.divergedTitle")) },
            text = { Text(i18n("dialog.recovery.divergedMessage").replace("{name}", entry.fileName)) },
            confirmButton = {
                Button(onClick = fileController::confirmDivergedRecovery) {
                    Text(i18n("dialog.recovery.restoreAnyway"))
                }
            },
            dismissButton = {
                TextButton(onClick = { fileController.pendingDivergedRecovery = null }) {
                    Text(i18n("dialog.unsaved.cancel"))
                }
            },
        )
    }
}

@Composable
private fun RecoveryCenterDialog(controller: ScoreFileController) {
    Dialog(onDismissRequest = { controller.showRecoveryCenter = false }) {
        Surface(
            modifier = Modifier.width(920.dp).height(620.dp),
            shape = RoundedCornerShape(12.dp),
            color = MeconColors.Surface,
            tonalElevation = 8.dp,
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(i18n("dialog.recovery.title"), style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = { controller.showRecoveryCenter = false }) {
                        Text(i18n("dialog.settings.close"))
                    }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Row(Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier.width(300.dp).fillMaxHeight().padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (controller.recoveryEntries.isEmpty()) {
                            item { Text(i18n("dialog.recovery.empty"), color = MeconColors.TextSecondary) }
                        }
                        items(controller.recoveryEntries, key = AutosaveEntry::id) { entry ->
                            RecoveryRow(
                                entry = entry,
                                selected = controller.selectedRecovery?.id == entry.id,
                                onClick = { controller.selectRecovery(entry) },
                            )
                        }
                    }
                    VerticalDivider(Modifier.fillMaxHeight().width(1.dp))
                    Box(Modifier.weight(1f).fillMaxHeight().padding(start = 20.dp, top = 12.dp)) {
                        val preview = controller.recoveryPreview
                        when {
                            controller.recoveryPreviewLoading ->
                                CircularProgressIndicator(Modifier.align(Alignment.Center))
                            preview == null -> Text(
                                i18n("dialog.recovery.selectHint"),
                                color = MeconColors.TextSecondary,
                                modifier = Modifier.align(Alignment.Center),
                            )
                            else -> Column(Modifier.fillMaxSize()) {
                                Text(preview.entry.fileName, color = MeconColors.TextPrimary, fontSize = 17.sp)
                                preview.entry.originalPath?.let {
                                    Text(it, color = MeconColors.TextSecondary, fontSize = 12.sp)
                                }
                                Spacer(Modifier.height(12.dp))
                                SimpleScoreView(
                                    score = preview.runtimeScore,
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    background = MeconColors.ScoreBackground,
                                    foreground = MeconColors.ScoreInk,
                                    fitScale = 0.9f,
                                )
                                Spacer(Modifier.height(12.dp))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    OutlinedButton(onClick = { controller.requestDeleteRecovery(preview.entry) }) {
                                        Text(i18n("dialog.recovery.delete"))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Button(onClick = { controller.requestRestoreRecovery(preview.entry) }) {
                                        Text(i18n("dialog.recovery.restore"))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecoveryRow(entry: AutosaveEntry, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MeconColors.SelectedSurface else MeconColors.Background,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Text(entry.fileName, color = MeconColors.TextPrimary, fontSize = 14.sp)
        Text(
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.savedAt)),
            color = MeconColors.TextSecondary,
            fontSize = 12.sp,
        )
        entry.originalPath?.let { path ->
            Text(
                File(path).parent.orEmpty(),
                color = MeconColors.TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}
