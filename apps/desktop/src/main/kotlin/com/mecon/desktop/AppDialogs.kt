package com.mecon.desktop

import com.mecon.desktop.service.applyRuntimeEdit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.toStorage
import com.mecon.api.storage.PageLayoutConfig
import com.mecon.api.storage.ScoreMetadata
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.tracks.StoragePageBreak
import com.mecon.api.storage.tracks.StorageSystemBreak
import com.mecon.audio.JvmAudioEngine
import com.mecon.desktop.service.ScoreFileController
import com.mecon.desktop.service.ScoreSession
import com.mecon.desktop.service.applyMetadata
import com.mecon.desktop.service.applyPageConfig
import com.mecon.desktop.ui.dialogs.AudioSettingsDialog
import com.mecon.desktop.ui.dialogs.MeasureDeleteConfirmDialog
import com.mecon.desktop.ui.dialogs.NewScoreDialog
import com.mecon.desktop.ui.dialogs.NewReductionDialog
import com.mecon.desktop.ui.dialogs.OrchestrationSettingsDialog
import com.mecon.desktop.ui.dialogs.PageSettingsDialog
import com.mecon.desktop.ui.dialogs.ReflowConfirmDialog
import com.mecon.desktop.ui.dialogs.ScoreMetadataDialog
import com.mecon.desktop.ui.dialogs.SettingsDialog
import com.mecon.desktop.uikit.i18n.Language
import com.mecon.desktop.uikit.theme.ThemeMode

internal class AppDialogState {
    var showSettings by mutableStateOf(false)
    var showAudioSettings by mutableStateOf(false)
    var showPageSettings by mutableStateOf(false)
    var showNewScore by mutableStateOf(false)
    var showNewReduction by mutableStateOf(false)
    var showOrchestrationSettings by mutableStateOf(false)
    var showScoreMetadata by mutableStateOf(false)
    var showReflowConfirm by mutableStateOf(false)
    var pendingMeasureDeletion by mutableStateOf<Set<Int>?>(null)
}

@Composable
internal fun rememberAppDialogState(): AppDialogState = remember { AppDialogState() }

@Composable
internal fun ApplicationDialogs(
    state: AppDialogState,
    uiScale: Float,
    language: Language,
    themeMode: ThemeMode,
    audioEngine: JvmAudioEngine,
    session: ScoreSession,
    fileController: ScoreFileController,
    onUiScaleChanged: (Float) -> Unit,
    onLanguageChanged: (Language) -> Unit,
    onThemeModeChanged: (ThemeMode) -> Unit,
    deleteMeasures: (Set<Int>) -> Unit,
    onCreateReduction: (String, List<com.mecon.api.storage.tracks.Clef>) -> Unit,
    onApplyOrchestration: (List<com.mecon.desktop.service.OrchestrationInstrumentDraft>) -> Unit,
) = AppDialogs(
    state = state,
    uiScale = uiScale,
    language = language,
    themeMode = themeMode,
    audioEngine = audioEngine,
    session = session,
    actions = AppDialogActions(
        changeUiScale = onUiScaleChanged,
        changeLanguage = onLanguageChanged,
        changeThemeMode = onThemeModeChanged,
        createScore = fileController::newScore,
        applyMetadata = session::applyMetadata,
        applyPageConfig = session::applyPageConfig,
        applyRuntimeEdit = session::applyRuntimeEdit,
        deleteMeasures = deleteMeasures,
        createReduction = onCreateReduction,
        applyOrchestration = onApplyOrchestration,
    ),
)

internal data class AppDialogActions(
    val changeUiScale: (Float) -> Unit,
    val changeLanguage: (Language) -> Unit,
    val changeThemeMode: (ThemeMode) -> Unit,
    val createScore: (StorageScore) -> Unit,
    val applyMetadata: (ScoreMetadata) -> Unit,
    val applyPageConfig: (PageLayoutConfig) -> Unit,
    val applyRuntimeEdit: (RuntimeScore) -> Unit,
    val deleteMeasures: (Set<Int>) -> Unit,
    val createReduction: (String, List<com.mecon.api.storage.tracks.Clef>) -> Unit,
    val applyOrchestration: (List<com.mecon.desktop.service.OrchestrationInstrumentDraft>) -> Unit,
)

@Composable
internal fun AppDialogs(
    state: AppDialogState,
    uiScale: Float,
    language: Language,
    themeMode: ThemeMode,
    audioEngine: JvmAudioEngine,
    session: ScoreSession,
    actions: AppDialogActions,
) {
    if (state.showSettings) {
        SettingsDialog(
            uiScale = uiScale,
            onUiScaleChange = actions.changeUiScale,
            currentLanguage = language,
            onLanguageChange = actions.changeLanguage,
            currentThemeMode = themeMode,
            onThemeModeChange = actions.changeThemeMode,
            onDismiss = { state.showSettings = false },
        )
    }

    if (state.showAudioSettings) {
        AudioSettingsDialog(
            audioEngine = audioEngine,
            onDismiss = { state.showAudioSettings = false },
        )
    }

    if (state.showNewScore) {
        NewScoreDialog(
            onCreate = actions.createScore,
            onDismiss = { state.showNewScore = false },
        )
    }

    if (state.showNewReduction) {
        NewReductionDialog(
            onCreate = { title, clefs ->
                state.showNewReduction = false
                actions.createReduction(title, clefs)
            },
            onDismiss = { state.showNewReduction = false },
        )
    }

    if (state.showOrchestrationSettings) {
        session.runtimeScore?.let { runtime ->
            OrchestrationSettingsDialog(
                score = runtime.toStorage(),
                onApply = actions.applyOrchestration,
                onDismiss = { state.showOrchestrationSettings = false },
            )
        }
    }

    if (state.showScoreMetadata) {
        session.runtimeScore?.let { runtime ->
            ScoreMetadataDialog(
                metadata = runtime.metadata,
                onApply = actions.applyMetadata,
                onDismiss = { state.showScoreMetadata = false },
            )
        }
    }

    if (state.showPageSettings) {
        PageSettingsDialog(
            config = session.runtimeScore?.pageLayout ?: PageLayoutConfig.DEFAULT,
            onApply = actions.applyPageConfig,
            onDismiss = { state.showPageSettings = false },
        )
    }

    if (state.showReflowConfirm) {
        ReflowConfirmDialog(
            onConfirm = {
                session.runtimeScore?.let { runtime ->
                    actions.applyRuntimeEdit(
                        runtime.copy(
                            globalTrack = runtime.globalTrack.copy(
                                events = runtime.globalTrack.events.filterNot {
                                    it is StorageSystemBreak || it is StoragePageBreak
                                },
                            ),
                            forcedSystemBreaks = emptySet(),
                            forcedPageBreaks = emptySet(),
                        ),
                    )
                }
            },
            onDismiss = { state.showReflowConfirm = false },
        )
    }

    state.pendingMeasureDeletion?.let { measures ->
        MeasureDeleteConfirmDialog(
            onConfirm = {
                state.pendingMeasureDeletion = null
                actions.deleteMeasures(measures)
            },
            onDismiss = { state.pendingMeasureDeletion = null },
        )
    }
}
