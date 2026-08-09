package com.mecon.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mecon.api.interaction.LayoutBreakKind
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.ReductionId
import com.mecon.desktop.service.PlaybackController
import com.mecon.desktop.service.ScoreFileController
import com.mecon.desktop.service.ScoreSession
import com.mecon.desktop.service.EditableScoreHost
import com.mecon.desktop.ui.exploration.FreePracticeToolbarController
import com.mecon.desktop.ui.exploration.ExplorationToolbarController
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.desktop.ui.components.topbar.MenuTabs
import com.mecon.desktop.ui.components.topbar.TitleBar
import com.mecon.desktop.ui.components.topbar.Toolbar
import com.mecon.desktop.ui.components.topbar.ScoreViewMode

data class ToolbarSelectionState(
    val timeCode: TimeCode?,
    val hasSelection: Boolean,
    val hasClipboard: Boolean,
)

data class MeasureToolbarState(
    val selectedBarlineMeasure: Int?,
    val hasMeasureSelection: Boolean,
)

data class StaffVisibilityToolbarState(
    val hideMeasuresEnabled: Boolean,
    val hideFollowingEnabled: Boolean,
    val blockedByNotes: Boolean,
)

data class ViewToolbarState(
    val mode: ScoreViewMode,
    val showMeasureNumbers: Boolean,
    val splitView: Boolean,
)

data class AnalysisToolbarState(
    val hasSelection: Boolean,
    val hasReductionSelection: Boolean,
    val reductionCount: Int,
    val lineCount: Int,
    val selectedReductionId: ReductionId?,
    val orchestrationEnabled: Boolean,
)

data class LayoutBreakToolbarState(
    val enabled: Boolean,
    val selectedKind: LayoutBreakKind?,
)

data class TopBarUiState(
    val activeTab: String,
    val splitView: Boolean,
    val selection: ToolbarSelectionState,
    val measure: MeasureToolbarState,
    val staffVisibility: StaffVisibilityToolbarState,
    val view: ViewToolbarState,
    val analysis: AnalysisToolbarState,
    val layoutBreak: LayoutBreakToolbarState,
)

data class EditToolbarActions(
    val copySelection: () -> Unit,
    val cutSelection: () -> Unit,
    val pasteSelection: () -> Unit,
)

data class DocumentToolbarActions(
    val newScore: () -> Unit,
    val openAudioSettings: () -> Unit,
    val openScoreMetadata: () -> Unit,
    val openPageSettings: () -> Unit,
    val openSettings: () -> Unit,
    val reflow: () -> Unit,
)

data class MeasureToolbarActions(
    val insertMeasures: (Int) -> Unit,
    val deleteMeasures: () -> Unit,
)

data class StaffVisibilityToolbarActions(
    val hideMeasures: () -> Unit,
    val hideFollowingMeasures: () -> Unit,
)

data class ViewToolbarActions(
    val changeMode: (ScoreViewMode) -> Unit,
    val toggleMeasureNumbers: () -> Unit,
    val toggleSplitView: () -> Unit,
)

data class AnalysisToolbarActions(
    val createReduction: () -> Unit,
    val enableOrchestration: () -> Unit,
)

data class LayoutBreakToolbarActions(
    val toggle: (LayoutBreakKind) -> Unit,
)

data class TopBarActions(
    val selectTab: (String) -> Unit,
    val edit: EditToolbarActions,
    val document: DocumentToolbarActions,
    val measure: MeasureToolbarActions,
    val staffVisibility: StaffVisibilityToolbarActions,
    val view: ViewToolbarActions,
    val analysis: AnalysisToolbarActions,
    val layoutBreak: LayoutBreakToolbarActions,
)

/**
 * The application's top bar. It only stacks the three rows — title bar, menu
 * tabs, and toolbar — and hands the services down. Each toolbar group reads the
 * service it needs and calls it directly; `App` no longer wires per-button hooks.
 *
 * The remaining callbacks are genuine composition state owned by `App` (dialog
 * visibility, active tab, split view, UI scale) and so cannot live in a service.
 */
@Composable
fun TopBar(
    session: ScoreSession,
    historyHost: EditableScoreHost?,
    playback: PlaybackController,
    fileController: ScoreFileController,
    state: TopBarUiState,
    actions: TopBarActions,
    freePracticeController: FreePracticeToolbarController? = null,
    explorationController: ExplorationToolbarController? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MeconColors.Background)
    ) {
        TitleBar(currentFileName = session.currentFileName)

        MenuTabs(state.activeTab, actions.selectTab)

        Toolbar(
            session = session,
            historyHost = historyHost,
            playback = playback,
            fileController = fileController,
            state = state,
            actions = actions,
            freePracticeController = freePracticeController,
            explorationController = explorationController,
        )
    }
}
