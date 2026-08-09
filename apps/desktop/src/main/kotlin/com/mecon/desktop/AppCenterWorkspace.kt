package com.mecon.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mecon.api.interaction.*
import com.mecon.api.model.Score
import com.mecon.api.interaction.StyleOverride
import com.mecon.api.primitive.PlayerId
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.toStorage
import com.mecon.desktop.ui.components.*
import com.mecon.desktop.ui.components.topbar.ScoreViewMode
import com.mecon.desktop.ui.views.RenderedScoreDisplayConfig
import com.mecon.desktop.ui.views.RenderedScoreSource
import com.mecon.desktop.ui.views.RenderedScoreView
import com.mecon.desktop.ui.views.RenderedScoreViewConfig
import com.mecon.desktop.uikit.components.HorizontalResizeHandle
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.desktop.uikit.theme.MeconDimensions
import com.mecon.plugins.chord.PolyphonyDisplaySettings
import com.mecon.renderer.interaction.*

internal data class AppCenterLayoutState(
    val isSplitView: Boolean,
    val splitRatio: () -> Float,
    val setSplitRatio: (Float) -> Unit,
    val bottomPanelHeight: () -> Dp,
    val setBottomPanelHeight: (Dp) -> Unit,
    val pianoRollSideWidth: () -> Dp,
    val setPianoRollSideWidth: (Dp) -> Unit,
    val pianoRollDock: () -> PianoRollDock,
    val setPianoRollDock: (PianoRollDock) -> Unit,
    val pianoRollChordOverlay: () -> Boolean,
    val bottomCollapsed: () -> Boolean,
    val setBottomCollapsed: (Boolean) -> Unit,
    val activeBottomPlugin: () -> BottomPlugin,
    val setActiveBottomPlugin: (BottomPlugin) -> Unit,
    val referenceScore: () -> RuntimeScore?,
    val referenceReductionId: () -> com.mecon.api.primitive.ReductionId?,
    val reductionSelection: () -> Set<EventSection>,
    val setReductionSelection: (Set<EventSection>) -> Unit,
    val activeReductionVoiceId: () -> TrackId?,
    val setActiveReductionVoiceId: (TrackId?) -> Unit,
    val activePlayerId: () -> PlayerId?,
    val setActivePlayerId: (PlayerId?) -> Unit,
)

internal data class AppCenterWorkspaceRequest(
    val document: AppMainScoreDocument,
    val playback: AppMainScorePlayback,
    val ui: AppMainScoreUi,
    val scoreState: AppMainScoreState,
    val layout: AppCenterLayoutState,
    val actions: AppMainScoreActions,
)

@Composable
internal fun RowScope.AppCenterWorkspace(request: AppCenterWorkspaceRequest) {
    val session = request.document.session
    val fileController = request.document.fileController
    val playback = request.playback.controller
    val currentPositionTicks = request.playback.currentPositionTicks
    val playbackState = request.playback.state
    val noteTool = request.ui.noteTool
    val noteInput = request.ui.noteInput
    val noteStyleNonce = request.ui.noteStyleNonce
    val pluginRenderNonce = request.ui.pluginRenderNonce
    val scoreViewMode = request.ui.scoreViewMode
    val isSplitView = request.layout.isSplitView
    val referenceScore = request.layout.referenceScore()
    val referenceReductionId = request.layout.referenceReductionId()
    val selectFromPianoRoll: (Set<EventSection>) -> Unit = { selection ->
        request.scoreState.setSelection(selection)
        request.scoreState.setSelectedAnnotationId(null)
    }
    val rootStorage = session.runtimeScore?.toStorage()
    val activeReduction = referenceReductionId?.let { id ->
        rootStorage?.reductions?.firstOrNull { it.id == id }?.migrated()
    }
    var splitRatio by com.mecon.desktop.ui.views.MutableLiveValue(
        request.layout.splitRatio,
        request.layout.setSplitRatio,
    )
    var bottomPanelHeight by com.mecon.desktop.ui.views.MutableLiveValue(
        request.layout.bottomPanelHeight,
        request.layout.setBottomPanelHeight,
    )
    var pianoRollSideWidth by com.mecon.desktop.ui.views.MutableLiveValue(
        request.layout.pianoRollSideWidth,
        request.layout.setPianoRollSideWidth,
    )
    var pianoRollDock by com.mecon.desktop.ui.views.MutableLiveValue(
        request.layout.pianoRollDock,
        request.layout.setPianoRollDock,
    )
    var isBottomCollapsed by com.mecon.desktop.ui.views.MutableLiveValue(
        request.layout.bottomCollapsed,
        request.layout.setBottomCollapsed,
    )
    var activeBottomPlugin by com.mecon.desktop.ui.views.MutableLiveValue(
        request.layout.activeBottomPlugin,
        request.layout.setActiveBottomPlugin,
    )
Column(modifier = Modifier.weight(1f)) {
        // Score view area
        Row(modifier = Modifier.weight(1f)) {
            // Main score view
            Column(
                modifier = Modifier
                    .weight(if (isSplitView) splitRatio else 1f)
                    .fillMaxHeight()
            ) {
                if (isSplitView && activeReduction != null && rootStorage != null) {
                    ReductionVoiceTargetBar(
                        reduction = activeReduction,
                        selectedVoiceId = request.layout.activeReductionVoiceId(),
                        canSync = request.scoreState.selection().isNotEmpty() &&
                            request.layout.activePlayerId() != null,
                        onVoiceClick = { target ->
                            request.layout.setActiveReductionVoiceId(target.voiceId)
                            val playerId = request.layout.activePlayerId()
                            if (playerId != null) {
                                buildWrittenToReductionRequest(
                                    rootStorage,
                                    activeReduction,
                                    request.scoreState.selection(),
                                    playerId,
                                    target,
                                )?.let(session::bindReductionSelection)
                                request.scoreState.setSelection(emptySet())
                            }
                        },
                    )
                }
                Box(Modifier.weight(1f)) {
                    val activeEventIds = rootStorage
                        ?.let { mainEventIdsForPlayer(it, request.layout.activePlayerId()) }
                        .orEmpty()
                    val playerNumberById = rootStorage
                        ?.let(::performerGroups)
                        .orEmpty()
                        .flatMap { it.players }
                        .associate { it.id to it.number }
                    val mainSelectorAssignments = rootStorage?.orchestration?.staffAssignments
                        .orEmpty()
                        .filter { it.lineId == null && it.staffId != null }
                        .groupBy { it.staffId!! }
                    val synchronizationColors = rootStorage?.synchronizationGroupColors().orEmpty()
                    val writtenBackgroundGroups = rootStorage?.orchestration?.links
                        .orEmpty()
                        .groupBy { link ->
                            rootStorage?.synchronizationGroupKey(link.source.eventId)
                                ?: "event:${link.source.eventId.value}"
                        }
                        .map { (groupKey, links) ->
                            com.mecon.desktop.ui.views.RenderedScoreNoteheadBackgroundGroup(
                                notes = links.mapTo(linkedSetOf()) { it.target },
                                color = synchronizationColors[groupKey]
                                    ?: com.mecon.api.render.RenderColor.rgba(58, 166, 157, 96),
                            )
                        }
                    AppMainScoreView(
                        AppMainScoreRequest(
                            document = request.document,
                            playback = request.playback,
                            ui = request.ui,
                            state = request.scoreState,
                            actions = request.actions,
                            syncMode = if (isSplitView && activeReduction != null) {
                                AppMainScoreSyncMode(
                                    selectableEventIds = activeEventIds,
                                    noteheadBackgroundGroups = writtenBackgroundGroups,
                                    staffSelectors = com.mecon.desktop.ui.views.RenderedScoreStaffSelectorConfig(
                                        choicesByStaffId = mainSelectorAssignments.mapValues { (_, assignments) ->
                                            assignments.distinctBy { it.playerId }.map { assignment ->
                                                com.mecon.desktop.ui.views.RenderedScoreStaffSelectorChoice(
                                                    key = assignment.playerId.value,
                                                    label = playerNumberById[assignment.playerId]?.toString() ?: "•",
                                                    selected = assignment.playerId == request.layout.activePlayerId(),
                                                )
                                            }
                                        },
                                        onSelect = { key ->
                                            rootStorage?.orchestration?.players
                                                ?.firstOrNull { it.id.value == key }
                                                ?.let {
                                                    request.layout.setActivePlayerId(it.id)
                                                    request.scoreState.setSelection(emptySet())
                                                }
                                        },
                                    ),
                                )
                            } else null,
                        )
                    )
                    NoteInputHud(
                        state = noteInput,
                        runtime = session.runtimeScore,
                        midiDeviceName = request.ui.midiDeviceName,
                        onCycleMidiDevice = request.ui.onCycleMidiDevice,
                        onToggleEntryMode = {
                            val next = if (noteInput.entryMode == com.mecon.desktop.input.NoteInputEntryMode.STEP) {
                                com.mecon.desktop.input.NoteInputEntryMode.REALTIME
                            } else {
                                com.mecon.desktop.input.NoteInputEntryMode.STEP
                            }
                            noteInput.setEntryMode(
                                next,
                                session.runtimeScore,
                                request.scoreState.selection(),
                                noteTool.activeVoiceNumber,
                            )
                        },
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                    )
                }
            }

            // Split view divider and reference view
            if (isSplitView) {
                // Divider
                HorizontalResizeHandle(
                    onDrag = { delta ->
                        splitRatio = (splitRatio + delta / 1000f).coerceIn(0.2f, 0.8f)
                    }
                )

                // Reference view
                Box(
                    modifier = Modifier
                        .weight(1f - splitRatio)
                        .fillMaxHeight()
                        .background(MeconColors.SurfaceDark.copy(alpha = 0.2f))
                ) {
                    if (referenceReductionId != null) {
                        ReductionEditorView(
                            session = session,
                            reductionId = referenceReductionId,
                            noteTool = noteTool,
                            playback = playback,
                            currentPositionTicks = currentPositionTicks,
                            playbackState = playbackState,
                            refreshKey = pluginRenderNonce,
                            selection = request.layout.reductionSelection,
                            setSelection = request.layout.setReductionSelection,
                            activeVoiceId = request.layout.activeReductionVoiceId,
                            setActiveVoiceId = request.layout.setActiveReductionVoiceId,
                            activePlayerId = request.layout.activePlayerId,
                            setActivePlayerId = request.layout.setActivePlayerId,
                        )
                    } else {
                        RenderedScoreView(
                            config = RenderedScoreViewConfig(
                                source = RenderedScoreSource(referenceScore ?: session.runtimeScore),
                                display = RenderedScoreDisplayConfig(
                                    isReference = true,
                                    renderRefreshKey = pluginRenderNonce,
                                    currentPositionTicks = currentPositionTicks,
                                    playbackState = playbackState,
                                    arrangement = session.pageArrangement,
                                    showEditorMarkers = false,
                                ),
                            ),
                        )
                    }
                }
            }

            if (pianoRollDock == PianoRollDock.RIGHT) {
                BottomPanel(
                    size = pianoRollSideWidth,
                    dock = pianoRollDock,
                    isCollapsed = isBottomCollapsed,
                    onToggleCollapse = { isBottomCollapsed = !isBottomCollapsed },
                    onSizeChange = { delta ->
                        pianoRollSideWidth = (pianoRollSideWidth + delta.dp).coerceIn(
                            MeconDimensions.MinPanelWidth.dp,
                            MeconDimensions.MaxPanelWidth.dp,
                        )
                    },
                    onDockChange = { pianoRollDock = it },
                    runtimeScore = session.runtimeScore,
                    computedScore = session.computedScore,
                    selection = request.scoreState.selection(),
                    onSelectionChange = selectFromPianoRoll,
                    showChordOverlay = request.layout.pianoRollChordOverlay(),
                    showScaleDegrees = PolyphonyDisplaySettings.isEnabled,
                    analysisRefreshKey = pluginRenderNonce,
                    currentPositionTicks = currentPositionTicks,
                    playbackState = playbackState,
                    activePlugin = activeBottomPlugin,
                    onPluginSelected = { activeBottomPlugin = it },
                )
            }
        }

        if (pianoRollDock == PianoRollDock.BOTTOM) {
            BottomPanel(
                size = bottomPanelHeight,
                dock = pianoRollDock,
                isCollapsed = isBottomCollapsed,
                onToggleCollapse = { isBottomCollapsed = !isBottomCollapsed },
                onSizeChange = { delta ->
                    val newHeight = bottomPanelHeight + delta.dp
                    bottomPanelHeight = newHeight.coerceIn(
                        MeconDimensions.MinPanelHeight.dp,
                        MeconDimensions.MaxPanelHeight.dp
                    )
                },
                onDockChange = { pianoRollDock = it },
                runtimeScore = session.runtimeScore,
                computedScore = session.computedScore,
                selection = request.scoreState.selection(),
                onSelectionChange = selectFromPianoRoll,
                showChordOverlay = request.layout.pianoRollChordOverlay(),
                showScaleDegrees = PolyphonyDisplaySettings.isEnabled,
                analysisRefreshKey = pluginRenderNonce,
                currentPositionTicks = currentPositionTicks,
                playbackState = playbackState,
                activePlugin = activeBottomPlugin,
                onPluginSelected = { activeBottomPlugin = it }
            )
        }
    }
}
