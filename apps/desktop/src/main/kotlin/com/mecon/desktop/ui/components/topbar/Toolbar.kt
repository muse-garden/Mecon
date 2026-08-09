package com.mecon.desktop.ui.components.topbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.mecon.desktop.service.PlaybackController
import com.mecon.desktop.service.ScoreFileController
import com.mecon.desktop.service.ScoreSession
import com.mecon.desktop.service.EditableScoreHost
import com.mecon.desktop.ui.components.TopBarActions
import com.mecon.desktop.ui.components.TopBarUiState
import com.mecon.desktop.ui.exploration.FreePracticeToolbarController
import com.mecon.desktop.ui.exploration.ExplorationToolbarController
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.features.freepractice.FreePracticeToolbarSpec

/**
 * The main toolbar row. It only lays out the toolbar groups, the dividers
 * between them, and the trailing controls — each group reads the service it
 * needs and owns its own interaction.
 */
@Composable
internal fun Toolbar(
    session: ScoreSession,
    historyHost: EditableScoreHost?,
    playback: PlaybackController,
    fileController: ScoreFileController,
    state: TopBarUiState,
    actions: TopBarActions,
    freePracticeController: FreePracticeToolbarController?,
    explorationController: ExplorationToolbarController?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(MeconColors.Background)
            .drawBehind {
                // Only draw bottom border
                val strokeWidth = 1.dp.toPx()
                drawLine(
                    color = MeconColors.Border,
                    start = Offset(0f, size.height - strokeWidth / 2),
                    end = Offset(size.width, size.height - strokeWidth / 2),
                    strokeWidth = strokeWidth
                )
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state.activeTab == i18n("menu.edit")) {
            // Edit is a focused editing surface: retain history and clipboard controls, plus
            // the measure controls, without unrelated document/playback/view actions.
            HistoryActions(session = historyHost)
            ToolbarDivider()
            MeasureActions(
                selectedBarlineMeasure = state.measure.selectedBarlineMeasure,
                hasMeasureSelection = state.measure.hasMeasureSelection,
                onInsertMeasures = actions.measure.insertMeasures,
                onDeleteMeasures = actions.measure.deleteMeasures,
            )
            ToolbarDivider()
            StaffVisibilityActions(
                hideEnabled = state.staffVisibility.hideMeasuresEnabled,
                hideFollowingEnabled = state.staffVisibility.hideFollowingEnabled,
                blockedByNotes = state.staffVisibility.blockedByNotes,
                onHide = actions.staffVisibility.hideMeasures,
                onHideFollowing = actions.staffVisibility.hideFollowingMeasures,
            )
            ToolbarDivider()
            LayoutBreakActions(
                enabled = state.layoutBreak.enabled,
                selectedKind = state.layoutBreak.selectedKind,
                onToggle = actions.layoutBreak.toggle,
            )
            ToolbarDivider()
            EditActions(
                hasSelection = state.selection.hasSelection,
                hasClipboard = state.selection.hasClipboard,
                onCopySelection = actions.edit.copySelection,
                onCutSelection = actions.edit.cutSelection,
                onPasteSelection = actions.edit.pasteSelection,
            )
        } else if (state.activeTab == i18n("menu.view")) {
            ViewActions(
                mode = state.view.mode,
                onModeChange = actions.view.changeMode,
                showMeasureNumbers = state.view.showMeasureNumbers,
                onToggleMeasureNumbers = actions.view.toggleMeasureNumbers,
                splitView = state.splitView,
                onToggleSplitView = actions.view.toggleSplitView,
            )
        } else if (state.activeTab == i18n("menu.analysis")) {
            AnalysisActions(state = state.analysis, actions = actions.analysis)
        } else if (state.activeTab == i18n("menu.exploration")) {
            val groups = FreePracticeToolbarSpec.descriptor.top.groups.filter { group ->
                when (group.id) {
                    "mode" -> explorationController != null
                    "writing", "playback" -> freePracticeController != null
                    "settings" -> false
                    else -> true
                }
            }
            groups.forEachIndexed { index, group ->
                if (index > 0) ToolbarDivider()
                when (group.id) {
                    "file" -> FileActions(
                        fileController = fileController,
                        onNewScore = actions.document.newScore,
                        showNew = false,
                        showExport = false,
                        saveEnabled = historyHost != null,
                    )
                    "history" -> HistoryActions(session = historyHost)
                    "mode" -> explorationController?.let { ExplorationModeToolbar(it) }
                    "writing" -> freePracticeController?.let { FreePracticeToolbar(it) }
                    "playback" -> freePracticeController?.let {
                        PlaybackControls(
                            playback = playback,
                            score = historyHost?.runtimeScore,
                            tempoBpm = it.writingSettings.playbackTempoBpm,
                            selectionTimeCode = it.selectionTimeCode,
                            hasSelection = it.hasSelection,
                            onOpenAudioSettings = actions.document.openAudioSettings,
                        )
                    }
                }
            }
        } else {
            FileActions(fileController = fileController, onNewScore = actions.document.newScore)
            ToolbarDivider()
            HistoryActions(session = historyHost)
            ToolbarDivider()
            EditActions(
                hasSelection = state.selection.hasSelection,
                hasClipboard = state.selection.hasClipboard,
                onCopySelection = actions.edit.copySelection,
                onCutSelection = actions.edit.cutSelection,
                onPasteSelection = actions.edit.pasteSelection,
            )
            ToolbarDivider()
            PlaybackControls(
                playback = playback,
                score = session.runtimeScore,
                selectionTimeCode = state.selection.timeCode,
                hasSelection = state.selection.hasSelection,
                onOpenAudioSettings = actions.document.openAudioSettings
            )
            ToolbarDivider()
            PageControls(
                session = session,
                onOpenScoreMetadata = actions.document.openScoreMetadata,
                onOpenPageSettings = actions.document.openPageSettings,
                onReflow = actions.document.reflow
            )
        }

        Spacer(Modifier.weight(1f))
        if (
            state.activeTab != i18n("menu.edit") &&
            state.activeTab != i18n("menu.view") &&
            (
                state.activeTab != i18n("menu.exploration") ||
                    FreePracticeToolbarSpec.descriptor.top.groups.any {
                        it.id == "settings" && it.trailing
                    }
                )
        ) {
            SettingsButton(onOpenSettings = actions.document.openSettings)
        }
    }
}

/** Opens the unified settings dialog (UI scale, language, keyboard shortcuts). */
@Composable
private fun SettingsButton(onOpenSettings: () -> Unit) {
    ToolbarButton(
        icon = Icons.Default.Settings,
        label = i18n("toolbar.settings"),
        onClick = onOpenSettings
    )
}
