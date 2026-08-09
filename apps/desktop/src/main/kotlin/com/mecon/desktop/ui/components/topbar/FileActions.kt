package com.mecon.desktop.ui.components.topbar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mecon.desktop.service.ScoreFileController
import com.mecon.desktop.uikit.components.MeconDropdownItem
import com.mecon.desktop.uikit.components.MeconDropdownMenu
import com.mecon.desktop.uikit.i18n.i18n

/**
 * File group: new / open / save / export. Open and save call [ScoreFileController]
 * directly; new opens a dialog whose visibility is owned by `App`; export opens a small
 * dropdown offering the available formats (PDF / MusicXML).
 */
@Composable
internal fun FileActions(
    fileController: ScoreFileController,
    onNewScore: () -> Unit,
    showNew: Boolean = true,
    showExport: Boolean = true,
    saveEnabled: Boolean = true,
) {
    if (showNew) {
        ToolbarButton(
            icon = Icons.Default.NoteAdd,
            label = i18n("toolbar.new"),
            onClick = onNewScore
        )
        Spacer(Modifier.width(4.dp))
    }

    ToolbarButton(
        icon = Icons.Outlined.FolderOpen,
        label = i18n("toolbar.open"),
        onClick = fileController::openFile
    )

    Spacer(Modifier.width(4.dp))

    ToolbarButton(
        icon = Icons.Default.Save,
        label = i18n("toolbar.save"),
        enabled = saveEnabled,
        onClick = fileController::saveFile
    )

    if (showExport) {
        Spacer(Modifier.width(4.dp))
        ExportMenuButton(fileController)
    }
}

/** The Export toolbar button, opening a palette-themed dropdown of output formats. */
@Composable
private fun ExportMenuButton(fileController: ScoreFileController) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        ToolbarButton(
            icon = Icons.Default.FileDownload,
            label = i18n("toolbar.export"),
            enabled = !fileController.exporting,
            onClick = { expanded = true }
        )
        MeconDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MeconDropdownItem(
                label = i18n("export.pdf"),
                icon = Icons.Default.PictureAsPdf,
                onClick = {
                    expanded = false
                    fileController.exportPdf()
                },
            )
            MeconDropdownItem(
                label = i18n("export.musicxml"),
                icon = Icons.Default.Description,
                onClick = {
                    expanded = false
                    fileController.exportMusicXml()
                },
            )
        }
    }
}
