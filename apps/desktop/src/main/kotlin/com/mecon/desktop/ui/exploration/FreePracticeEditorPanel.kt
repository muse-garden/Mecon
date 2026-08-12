package com.mecon.desktop.ui.exploration

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.interaction.EventSection
import com.mecon.api.interaction.VoiceEventSection
import com.mecon.api.interaction.VoiceNoteSection
import com.mecon.api.render.RenderColor
import com.mecon.api.storage.NoteRef
import com.mecon.exploration.PracticeHarmonicRole
import com.mecon.exploration.PracticeNoteheadRef
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.desktop.buildDeletions
import com.mecon.desktop.paletteInfoFor
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.desktop.service.HarmonyPracticeScoreHost
import com.mecon.desktop.service.PlaybackController
import com.mecon.audio.engine.PlaybackState
import com.mecon.desktop.input.KeybindingStore
import com.mecon.desktop.input.ScoreSelectionEditor
import com.mecon.desktop.input.ShortcutAction
import com.mecon.desktop.input.handleEditingShortcut
import com.mecon.desktop.ui.components.HorizontalScoreEditor
import com.mecon.desktop.ui.components.LeftToolbarActions
import com.mecon.desktop.ui.components.LeftToolbarSelectionState
import com.mecon.desktop.ui.components.NoteToolState
import com.mecon.desktop.ui.components.prepareInsertionCommit
import com.mecon.desktop.ui.views.*
import com.mecon.desktop.uikit.components.DeferredVerticalResizeHandle
import com.mecon.desktop.uikit.components.CollapsiblePanelHeader
import com.mecon.desktop.uikit.components.CollapsiblePanelItem
import com.mecon.desktop.uikit.components.ChordCatalogPicker
import com.mecon.desktop.uikit.components.ChordCatalogPickerChoice
import com.mecon.desktop.uikit.components.ChordCatalogPickerGroup
import com.mecon.desktop.uikit.components.ChordCatalogPickerStrings
import com.mecon.desktop.uikit.components.ChordToneLabelMode
import com.mecon.desktop.uikit.components.ChordDetailMode
import com.mecon.desktop.uikit.components.ChordDetailPanel
import com.mecon.desktop.uikit.components.ChordDetailPanelStrings
import com.mecon.desktop.uikit.components.CircleOfFifthsPopupMenu
import com.mecon.desktop.uikit.components.MeconDropdownItem
import com.mecon.desktop.uikit.components.MeconDropdownMenu
import com.mecon.desktop.uikit.components.MeconTextInputFocus
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.desktop.uikit.util.setGlobalCursor
import com.mecon.desktop.i18n.explorationText
import com.mecon.desktop.ui.harmony.ChordDetailUiMapper
import com.mecon.desktop.ui.harmony.ChordConstructionScorePreview
import com.mecon.theory.freepractice.VoiceNotationPlan
import com.mecon.theory.harmony.ChordSelectionCatalog
import com.mecon.theory.harmony.ChordSelectionChoice
import com.mecon.theory.harmony.ChordSelectionGroup
import com.mecon.theory.harmony.ChordInterpretationRef
import com.mecon.theory.harmony.ChordKnowledgeContext
import com.mecon.theory.harmony.ChordCatalogSnapshot
import com.mecon.theory.harmony.SoundingInterpretationQuery
import com.mecon.theory.freepractice.HarmonyWorkspaceState
import com.mecon.theory.freepractice.HarmonyWorkspaceEditor
import com.mecon.theory.freepractice.VoiceAssignmentSource
import com.mecon.theory.freepractice.WorkspaceIdiomInstanceId
import com.mecon.theory.freepractice.WorkspaceSlotId
import com.mecon.theory.freepractice.WorkspaceHarmonySlot
import com.mecon.theory.freepractice.WorkspaceChordChoice
import com.mecon.theory.freepractice.WorkspaceTonalLayoutId
import com.mecon.theory.ModulationKey
import com.mecon.theory.ModulationCircleOfFifths
import com.mecon.theory.ModulationPitchLabels
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.schoenberg.SchoenbergFreePracticeContribution
import com.mecon.theory.schoenberg.SchoenbergHarmonicTreatments
import com.mecon.theory.writing.GrandStaffVoiceLayout
import com.mecon.renderer.layout.AlignedTimeAxisRequest
import com.mecon.renderer.layout.ResolvedTimeAxis
import com.mecon.features.freepractice.PracticeTimelineEdit
import com.mecon.features.freepractice.FreePracticeTimelineController
import com.mecon.features.freepractice.FreePracticeViewProjector
import com.mecon.features.freepractice.PracticeTimelineAxisAnchor
import com.mecon.features.freepractice.PracticeTimelineDrawKind
import com.mecon.features.freepractice.PracticeTimelineDisplayMode
import com.mecon.features.freepractice.PracticeTimelineInput
import com.mecon.features.freepractice.PracticeTimelineInputType
import com.mecon.features.freepractice.PracticeTimelineSceneProjector
import com.mecon.features.freepractice.PracticeTimelineSceneRequest
import com.mecon.features.freepractice.PracticeTimelinePalette
import com.mecon.features.freepractice.PracticeTimelineToneLabelMode
import com.mecon.features.freepractice.PracticeTimelineView
import com.mecon.desktop.ui.views.rememberReferentialUpdatedState
import kotlinx.coroutines.launch
import java.awt.Cursor
import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class PracticeEditorState(
    val workspace: HarmonyWorkspaceState,
    val scoreHost: HarmonyPracticeScoreHost?,
    val selectedSlotId: WorkspaceSlotId,
    val selectedIdiomInstanceId: WorkspaceIdiomInstanceId?,
    val staffVoices: GrandStaffVoiceLayout,
    val inputMode: PracticeInputMode,
    val chordToneMode: ChordToneLabelMode,
    val teachingContribution: SchoenbergFreePracticeContribution,
    val gridUnit: Fraction,
    val defaultChordBeats: Int,
)

internal data class PracticeEditorActions(
    val selectSlot: (WorkspaceSlotId) -> Unit,
    val selectIdiom: (WorkspaceIdiomInstanceId) -> Unit,
    val changeInputMode: (PracticeInputMode) -> Unit,
    val insertChordRange: (Fraction, Fraction) -> Unit,
    /** Returns whether the session accepted the edit; a gesture preview is held until it does. */
    val commitTimelineEdit: (PracticeTimelineEdit) -> Boolean,
    val previewTimelineEdit: (PracticeTimelineEdit) -> PracticeTimelineView?,
    val reportError: (String) -> Unit,
    val deleteChord: () -> Unit,
    val insertTonalLayout: (ModulationKey, Fraction, Fraction?, Fraction?) -> Unit,
    val selectTonalLayout: (WorkspaceTonalLayoutId) -> Unit,
    val setTonalLayoutBounds: (WorkspaceTonalLayoutId, Fraction, Fraction?) -> Unit,
    val scoreSelectionChanged: (Set<EventSection>) -> Unit = {},
)

internal fun resolvePracticeSelectedSlotId(
    availableSlotIds: List<WorkspaceSlotId>,
    sessionSelectedSlotId: WorkspaceSlotId?,
    mirroredSelectedSlotId: WorkspaceSlotId?,
): WorkspaceSlotId {
    require(availableSlotIds.isNotEmpty()) { "Free-practice workspace must contain a chord slot" }
    return sessionSelectedSlotId?.takeIf(availableSlotIds::contains)
        ?: mirroredSelectedSlotId?.takeIf(availableSlotIds::contains)
        ?: availableSlotIds.first()
}

internal data class ResolvedPracticeTimeAxis(
    val axis: ResolvedTimeAxis,
    val coveredUntil: Fraction,
)

internal data class ChordPickerSelectionAction(
    val previewChoiceId: String?,
    val interpretationRef: ChordInterpretationRef?,
    val choice: WorkspaceChordChoice,
)

internal data class ChordBassOption(
    val pitchClass: Int,
    val label: String,
)

internal fun chordPickerSelectedIdentity(
    previewChoiceId: String?,
    selectedChoice: ChordSelectionChoice?,
): String? = previewChoiceId ?: selectedChoice?.id?.value

internal fun chordPickerSelectionAction(
    choice: ChordSelectionChoice,
    defaultBassToRoot: Boolean = false,
): ChordPickerSelectionAction =
    ChordPickerSelectionAction(
        previewChoiceId = choice.id.value,
        interpretationRef = null,
        choice = WorkspaceChordChoice.of(
            choice.pitchClasses,
            choice.origin,
            bassPitchClass = choice.rootPitchClass.takeIf { defaultBassToRoot },
        ),
    )

internal fun chordBassOptions(
    choice: ChordSelectionChoice,
    toneMode: ChordToneLabelMode,
): List<ChordBassOption> {
    val labels = when (toneMode) {
        ChordToneLabelMode.RELATIVE -> choice.relativeTones
        ChordToneLabelMode.ABSOLUTE -> choice.absoluteTones
    }
    return choice.pitchClasses.toList().zip(labels) { pitchClass, label ->
        ChordBassOption(pitchClass, label)
    }
}

internal fun customaryBassGuidanceText(
    pitchClasses: Set<Int>,
    options: List<ChordBassOption>,
): String? {
    if (pitchClasses.isEmpty()) return null
    val labels = pitchClasses.map { pitchClass ->
        options.firstOrNull { it.pitchClass == pitchClass }?.label ?: pitchClass.toString()
    }
    return "进行提示：常用低音为${labels.joinToString("、")}，可自行修改。"
}

@Composable
internal fun PracticeEditorPanel(
    state: PracticeEditorState,
    actions: PracticeEditorActions,
    playback: PlaybackController,
    previewLayout: FreePracticeWorkbenchLayout = FreePracticeWorkbenchLayout.CLASSIC,
    writingSurface: FreePracticeWritingSurface = FreePracticeWritingSurface.SCORE,
    onWritingSurfaceChange: (FreePracticeWritingSurface) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val playbackState by playback.playbackState.collectAsState()
    val currentPositionTicks by playback.currentPositionTicks.collectAsState()
    val playbackShowsCursor by playback.playbackShowsCursor.collectAsState()
    val displayedPlaybackState = if (playbackShowsCursor) playbackState else PlaybackState.IDLE
    val noteTool = androidx.compose.runtime.remember { NoteToolState() }
    val focusRequester = androidx.compose.runtime.remember { FocusRequester() }
    val notationPlan = androidx.compose.runtime.remember(
        state.workspace.voices,
        state.staffVoices,
    ) {
        VoiceNotationPlan.from(state.workspace.voicePlan, state.staffVoices)
    }
    val host = state.scoreHost
    val manualAuditionEnabled = host?.practiceWritingState?.running != true &&
        playbackState != PlaybackState.PLAYING
    // The session publishes workspace and stable selection IDs together. The parent adapter mirrors
    // them into separate Compose states, so its ID can briefly be observed against the old workspace.
    // Prefer the session ID and never recover a mismatch by silently choosing slot index 0.
    val selectedSlotId = resolvePracticeSelectedSlotId(
        availableSlotIds = state.workspace.slots.map { it.id },
        sessionSelectedSlotId = host?.practiceSelection?.slotId,
        mirroredSelectedSlotId = state.selectedSlotId,
    )
    val selectedSlot = state.workspace.slots.indexOfFirst { it.id == selectedSlotId }
        .takeIf { it >= 0 }
        ?: 0
    val selectedHarmonySlot = state.workspace.slots[selectedSlot]
    val selectedLayout = state.workspace.selectedTonalLayout(selectedHarmonySlot)
        ?: state.workspace.tonalLayouts.first()
    val activeKey = selectedLayout.key
    var previewSplitRatio by androidx.compose.runtime.remember { mutableFloatStateOf(0.5f) }
    var scorePreviewCollapsed by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    var pianoRollCollapsed by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    var selection by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Set<EventSection>>(emptySet())
    }
    androidx.compose.runtime.SideEffect { actions.scoreSelectionChanged(selection) }
    val scoreSelectionInfo = host?.let {
        paletteInfoFor(selection, it.runtimeScore, it.computedScore)
    } ?: com.mecon.desktop.ui.components.PaletteSelectionInfo.EMPTY
    val scoreSelectionEditor = host?.let {
        ScoreSelectionEditor(
            host = it,
            noteTool = noteTool,
            selection = { selection },
            selectionInfo = {
                paletteInfoFor(selection, it.runtimeScore, it.computedScore)
            },
            onAfterEdit = { updated ->
                selection = updated
            },
            onDurationConflict = { actions.reportError(i18n("edit.durationConflict")) },
            onTupletConflict = { actions.reportError(i18n("edit.tupletConflict")) },
        )
    }
    var beatWidth by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(144.dp)
    }
    val gridUnit = state.gridUnit
    val timeScrollState = rememberScrollState()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var resolvedTimeAxis by androidx.compose.runtime.remember(host) {
        androidx.compose.runtime.mutableStateOf(
            value = null,
            policy = androidx.compose.runtime.referentialEqualityPolicy<ResolvedPracticeTimeAxis?>(),
        )
    }
    val pendingAxisEnds = androidx.compose.runtime.remember(host) {
        mutableMapOf<Long, Fraction>()
    }
    val beatWidthPx = with(density) { beatWidth.toPx() }
    val axisRevision = (host?.documentVersion ?: 0L) * 31L +
        beatWidthPx.toBits().toLong()
    var alignedTimeAxisRequest by androidx.compose.runtime.remember(host) {
        androidx.compose.runtime.mutableStateOf(
            value = null,
            policy = androidx.compose.runtime.referentialEqualityPolicy<AlignedTimeAxisRequest?>(),
        )
    }
    androidx.compose.runtime.LaunchedEffect(
        host,
        host?.documentVersion,
        state.workspace,
        selectedSlotId,
        axisRevision,
    ) {
        val score = host?.runtimeScore
        if (score == null) {
            alignedTimeAxisRequest = null
        } else {
            val workspaceSnapshot = state.workspace
            val nextRequest =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    freePracticeAlignedTimeAxisRequest(
                        workspace = workspaceSnapshot,
                        score = score,
                        beatWidthPx = beatWidthPx,
                        renderDensity = density.density,
                        revision = axisRevision,
                    )
                }
            // Keep the last complete request while the replacement is being built. Resetting this
            // state to null makes all three time surfaces briefly fall back to unrelated scales.
            pendingAxisEnds.clear()
            pendingAxisEnds[nextRequest.revision] = workspaceSnapshot.slots.maxOf {
                it.onset + it.duration
            }
            alignedTimeAxisRequest = nextRequest
        }
    }
    // Workspace and selection publish immediately. The previous renderer axis remains usable for
    // its covered range and is extended linearly at the new score tail by the timeline mapper.
    val activeTimeAxis = resolvedTimeAxis
    val chordSpans = androidx.compose.runtime.remember(
        state.workspace.slots,
        state.workspace.tonalLayouts,
    ) {
        freePracticeChordSpans(state.workspace)
    }
    val pianoRollProjection = activeTimeAxis?.let { settled ->
        val axis = settled.axis
        val timelineEnd = state.workspace.slots.maxOf { it.onset + it.duration }
        PianoRollTimeProjection(
            xAtTicks = { ticks ->
                val absolute = Fraction(
                    ticks.roundToInt(),
                    TICKS_PER_QUARTER * 4,
                ).simplified()
                freePracticeExtendedAxisX(
                    axis,
                    settled.coveredUntil,
                    absolute,
                    beatWidthPx,
                    density.density,
                ) - timeScrollState.value
            },
            ticksAtX = { x ->
                freePracticeExtendedAxisTime(
                    axis = axis,
                    settledEndTime = settled.coveredUntil,
                    screenPixels = x + timeScrollState.value,
                    beatWidthPx = beatWidthPx,
                    renderDensity = density.density,
                ).toPianoRollTicks()
            },
            onHorizontalDrag = { delta ->
                scope.launch { timeScrollState.scrollBy(-delta) }
            },
            contentEndX = freePracticeExtendedAxisX(
                axis = axis,
                settledEndTime = settled.coveredUntil,
                absoluteTime = timelineEnd,
                beatWidthPx = beatWidthPx,
                renderDensity = density.density,
            ) - timeScrollState.value,
        )
    }
    androidx.compose.runtime.LaunchedEffect(host) {
        if (host != null) focusRequester.requestFocus()
    }

    Column(
        modifier
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (MeconTextInputFocus.hasFocus || event.type != KeyEventType.KeyDown) {
                    false
                } else if (event.key == Key.Escape) {
                    noteTool.cancelInsertionTool()
                    selection = emptySet()
                    true
                } else if (
                    KeybindingStore.actionFor(event) == ShortcutAction.DELETE &&
                    host != null &&
                    selection.isNotEmpty()
                ) {
                    host.applyNoteDeletes(
                        buildDeletions(selection, host.runtimeScore, host.computedScore),
                    ) { selection = it }
                    true
                } else {
                    host?.let {
                        handleEditingShortcut(event, it, noteTool, scoreSelectionEditor)
                    } ?: false
                }
            }
            .drawWithContent {
                drawContent()
                val strokeWidth = 1.dp.toPx()
                drawLine(
                    MeconColors.Border,
                    Offset(strokeWidth / 2f, 0f),
                    Offset(strokeWidth / 2f, size.height),
                    strokeWidth,
                )
            }
            .fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        WorkbenchPanel(
            title = "写作区 · 2/4",
            modifier = Modifier.weight(1f),
            fillContentHeight = true,
        ) {
            SharedHarmonicTimeline(
                workspace = state.workspace,
                selectedSlotId = selectedSlotId,
                selectedIdiomInstanceId = state.selectedIdiomInstanceId,
                onSelectIdiom = actions.selectIdiom,
                idiomTitles = state.teachingContribution.idioms.associate {
                    it.id to it.title
                },
                toneMode = state.chordToneMode,
                beatWidth = beatWidth,
                onBeatWidthChange = { beatWidth = it },
                gridUnit = state.gridUnit,
                defaultChordDuration = Fraction(state.defaultChordBeats, 4).simplified(),
                scrollState = timeScrollState,
                resolvedTimeAxis = activeTimeAxis,
                onSelect = actions.selectSlot,
                onInsertRange = actions.insertChordRange,
                onCommitTimelineEdit = actions.commitTimelineEdit,
                onPreviewTimelineEdit = actions.previewTimelineEdit,
                onError = actions.reportError,
                onDelete = actions.deleteChord,
                onSelectTonalLayout = actions.selectTonalLayout,
            )
            HorizontalDivider(color = MeconColors.Border)

            PracticePreviewSplitPane(
            splitRatio = previewSplitRatio,
            onSplitRatioChange = { previewSplitRatio = it },
            scorePreviewCollapsed = scorePreviewCollapsed,
            pianoRollCollapsed = pianoRollCollapsed,
            onExpandScorePreview = { scorePreviewCollapsed = false },
            onExpandPianoRoll = { pianoRollCollapsed = false },
            previewLayout = previewLayout,
            writingSurface = writingSurface,
            onWritingSurfaceChange = onWritingSurfaceChange,
            modifier = Modifier.weight(1f),
            scorePreview = {
        PracticeWritingSection(
            title = "五线谱",
            modifier = Modifier.fillMaxHeight(),
            onCollapse = { scorePreviewCollapsed = true },
            collapsible = previewLayout == FreePracticeWorkbenchLayout.CLASSIC,
        ) {
            if (host != null) {
                val selectedNoteheads = selection.flatMapTo(linkedSetOf()) { section ->
                    when (section) {
                        is VoiceNoteSection -> listOf(PracticeNoteheadRef(section.event.id, section.pitchIndex))
                        is VoiceEventSection -> section.event.pitchData.indices.map { index ->
                            PracticeNoteheadRef(section.event.id, index)
                        }
                        else -> emptyList()
                    }
                }
                val selectedEventId = selectedNoteheads.firstOrNull()?.eventId
                val selectedVoiceId = host.runtimeScore.voiceTracks.entries
                    .firstOrNull { (_, voice) -> voice.events.any { it.id == selectedEventId } }?.key
                val selectedStaffId = host.runtimeScore.staffTracks.entries
                    .firstOrNull { (_, staff) -> staff.voiceTracks.any { it.id == selectedVoiceId } }?.key
                val selectedNotesLocked = selectedNoteheads.isNotEmpty() && selectedNoteheads.all { ref ->
                    host.practiceNoteConstraints.noteheads.firstOrNull { it.notehead == ref }?.locked == true
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        enabled = selectedNoteheads.isNotEmpty(),
                        onClick = { host.setHarmonicRole(selectedNoteheads, PracticeHarmonicRole.CHORD_TONE) },
                    ) { Text("和弦内音") }
                    Button(
                        enabled = selectedNoteheads.isNotEmpty(),
                        onClick = { host.setHarmonicRole(selectedNoteheads, PracticeHarmonicRole.NON_CHORD_TONE) },
                    ) { Text("和弦外音") }
                    Button(
                        enabled = selectedNoteheads.isNotEmpty(),
                        onClick = { host.setHarmonicRole(selectedNoteheads, null) },
                    ) { Text("清除标记") }
                    Button(
                        enabled = selectedNoteheads.isNotEmpty(),
                        onClick = { host.setNoteheadLock(selectedNoteheads, !selectedNotesLocked) },
                    ) { Text(if (selectedNotesLocked) "解锁音符" else "锁定音符") }
                    Button(
                        enabled = selectedVoiceId != null,
                        onClick = { selectedVoiceId?.let { id ->
                            host.setVoiceLock(id, id !in host.practiceNoteConstraints.lockedVoiceTrackIds)
                        } },
                    ) { Text(if (selectedVoiceId in host.practiceNoteConstraints.lockedVoiceTrackIds) "解锁声部" else "锁定声部") }
                    Button(
                        enabled = selectedStaffId != null,
                        onClick = { selectedStaffId?.let { id ->
                            host.setStaffLock(id, id !in host.practiceNoteConstraints.lockedStaffTrackIds)
                        } },
                    ) { Text(if (selectedStaffId in host.practiceNoteConstraints.lockedStaffTrackIds) "解锁谱表" else "锁定谱表") }
                    Button(onClick = {
                        val view = host.practiceNoteConstraints
                        host.setHarmonicRoleFilters(!view.chordCatalogFilterEnabled, view.idiomCatalogFilterEnabled)
                    }) { Text(if (host.practiceNoteConstraints.chordCatalogFilterEnabled) "和弦筛选：开" else "和弦筛选：关") }
                    Button(onClick = {
                        val view = host.practiceNoteConstraints
                        host.setHarmonicRoleFilters(view.chordCatalogFilterEnabled, !view.idiomCatalogFilterEnabled)
                    }) { Text(if (host.practiceNoteConstraints.idiomCatalogFilterEnabled) "进行筛选：开" else "进行筛选：关") }
                }
                HorizontalScoreEditor(
                    state = noteTool,
                    selection = LeftToolbarSelectionState(notes = scoreSelectionInfo),
                    actions = LeftToolbarActions(
                        notes = scoreSelectionEditor?.paletteActions()
                            ?: com.mecon.desktop.ui.components.NotePaletteActions(),
                    ),
                    showScoreElementTool = false,
                    voiceNumbers = (1..4).toList(),
                    voiceSelectionInfo = scoreSelectionInfo,
                    scoreViewConfig = RenderedScoreViewConfig(
                            source = RenderedScoreSource(
                                score = host.runtimeScore,
                                computed = host.computedScore,
                                documentVersion = host.documentVersion,
                                renderHint = host.renderHint,
                            ),
                            selectionConfig = RenderedScoreSelectionConfig(
                                selection = selection,
                                onSelectionChange = { selection = it },
                                noteheadBackgroundGroups = host.practiceNoteConstraints.noteheads
                                    .filter { it.inferredRole != null || it.explicitRole != null || it.locked }
                                    .groupBy { item -> when {
                                        item.conflict -> RenderColor.rgb(220, 55, 55)
                                        item.locked -> RenderColor.rgb(55, 115, 205)
                                        item.inferredRole == PracticeHarmonicRole.CHORD_TONE -> RenderColor.rgb(65, 170, 95)
                                        else -> RenderColor.rgb(225, 155, 45)
                                    } }
                                    .map { (color, items) -> RenderedScoreNoteheadBackgroundGroup(
                                        notes = items.mapTo(linkedSetOf()) { item ->
                                            NoteRef(item.notehead.eventId, item.notehead.pitchIndex)
                                        },
                                        color = color,
                                    ) },
                            ),
                            display = RenderedScoreDisplayConfig(
                                readOnly = false,
                                panEnabled = false,
                                zoomEnabled = false,
                                currentPositionTicks = currentPositionTicks,
                                playbackState = displayedPlaybackState,
                                showEditorMarkers = true,
                                showViewLabel = false,
                                showZoomIndicator = false,
                                firstSystemIndent = com.mecon.renderer.geometry.StaffSpace(3f),
                                alignedTimeAxisRequest = alignedTimeAxisRequest,
                                onResolvedTimeAxis = { axis ->
                                    if (axis != null) {
                                        pendingAxisEnds[axis.revision]?.let { coveredUntil ->
                                            resolvedTimeAxis = ResolvedPracticeTimeAxis(axis, coveredUntil)
                                        }
                                    }
                                },
                                externalHorizontalOffsetPx = timeScrollState.value.toFloat(),
                                contentPadding = 0.dp,
                                padEmptyMeasures = false,
                            ),
                            edit = RenderedScoreEditConfig(
                                notation = RenderedScoreNotationInsertion(
                                    noteTool = noteTool,
                                    onAuditionNote = {
                                            event,
                                            pitchIndices,
                                            transposedPitchIndices,
                                            stepDelta,
                                        ->
                                        if (manualAuditionEnabled && stepDelta != 0) {
                                            playback.audition(
                                                host.runtimeScore,
                                                event,
                                                pitchIndices,
                                                transposedPitchIndices,
                                                stepDelta,
                                            )
                                        }
                                    },
                                    onInsertNote = { raw ->
                                        val onInputTransition = noteTool.prepareInsertionCommit()
                                        host.applyPracticeNoteEdit(
                                            raw,
                                            VoiceAssignmentSource.MANUAL,
                                            onInputTransition = onInputTransition,
                                            onInserted = { inserted, _ ->
                                                selection = setOf(inserted)
                                            },
                                            onRejected = { rejection ->
                                                actions.reportError(rejection.message)
                                            },
                                        )
                                    },
                                ),
                                eventMovement = host.noteMovementActions {
                                    selection = it
                                },
                            ),
                        ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
            },

            pianoRoll = {
        PracticeWritingSection(
            title = "钢琴卷轴",
            modifier = Modifier.fillMaxHeight(),
            onCollapse = { pianoRollCollapsed = true },
            collapsible = previewLayout == FreePracticeWorkbenchLayout.CLASSIC,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("记谱通道自动分配", color = MeconColors.TextMuted, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                PracticeChip("点击和弦音", state.inputMode == PracticeInputMode.CHORD_TONE) {
                    actions.changeInputMode(PracticeInputMode.CHORD_TONE)
                }
                PracticeChip("自由画音符", state.inputMode == PracticeInputMode.FREE_DRAW) {
                    actions.changeInputMode(PracticeInputMode.FREE_DRAW)
                }
            }
            Text(
                if (state.inputMode == PracticeInputMode.CHORD_TONE) {
                    "点击高亮和弦音输入；拖动已有音符可改音高，音高会吸附到当前和弦音。"
                } else {
                    "横向拖动画时值范围，按上方所选自动吸附单位吸附；按住 Ctrl 拖动可平移卷轴。"
                },
                color = MeconColors.TextDark,
                fontSize = 10.sp,
            )
            PianoRollView(
                runtimeScore = host?.runtimeScore,
                computedScore = host?.computedScore,
                selection = selection,
                currentPositionTicks = currentPositionTicks,
                playbackState = displayedPlaybackState,
                chordSpansOverride = chordSpans,
                interaction = host?.let {
                    val grid = gridUnit
                    val placeholder = notationPlan.bindings.first()
                    val insert = { start: com.mecon.api.primitive.TimeCode,
                                   duration: Duration,
                                   midi: Int,
                                   tupletCount: Int? ->
                        host.applyPracticeNoteEdit(
                            NoteEditEngine.Insertion(
                                voiceTrackId = placeholder.voiceId,
                                staffTrackId = null,
                                voiceNumber = placeholder.voiceNumber,
                                start = start,
                                duration = duration,
                                pitch = if (noteTool.restMode) null else Pitch.fromMidi(
                                    midi, activeKey.fifths >= 0,
                                ),
                                isRest = noteTool.restMode,
                                trailingTie = noteTool.tieMode,
                                tupletCount = tupletCount,
                                beaming = noteTool.insertionBeaming,
                                articulations = noteTool.articulations.toList(),
                            ),
                            VoiceAssignmentSource.AUTOMATIC,
                            onInserted = { inserted, _ -> selection = setOf(inserted) },
                            onRejected = { rejection -> actions.reportError(rejection.message) },
                        )
                    }
                    PianoRollInteractionConfig(
                        onGridTap = if (state.inputMode == PracticeInputMode.CHORD_TONE) {
                            onGridTap@{ hit ->
                                val gridTime = freePracticeGridTimeWithinScore(
                                    hitTicks = hit.ticks,
                                    gridDuration = grid,
                                    score = host.runtimeScore,
                                ) ?: return@onGridTap
                                val highlighted = chordSpans.firstOrNull {
                                    gridTime.absoluteTicks >= it.onsetTicks &&
                                        gridTime.absoluteTicks < it.endTicks
                                }?.pitchClasses?.toSet().orEmpty()
                                insert(
                                    gridTime.timeCode,
                                    noteTool.duration,
                                    nearestChordToneMidi(hit.midi, highlighted),
                                    noteTool.tupletCount,
                                )
                            }
                        } else {
                            null
                        },
                        onGridRangeDrag = if (state.inputMode == PracticeInputMode.FREE_DRAW) {
                            onGridRangeDrag@{ first, last ->
                                val range = freePracticeGridRangeWithinScore(
                                    firstTicks = first.ticks,
                                    secondTicks = last.ticks,
                                    gridDuration = grid,
                                    score = host.runtimeScore,
                                ) ?: return@onGridRangeDrag
                                val duration = if (
                                    range.duration == noteTool.duration.toFraction()
                                ) {
                                    noteTool.duration
                                } else {
                                    freePracticeDuration(range.duration)
                                }
                                insert(range.start, duration, first.midi, null)
                            }
                        } else {
                            null
                        },
                        snapPitch = { note, midi ->
                            if (state.inputMode == PracticeInputMode.CHORD_TONE) {
                                val tones = chordSpans.firstOrNull {
                                    note.onsetTicks >= it.onsetTicks &&
                                        note.onsetTicks < it.endTicks
                                }?.pitchClasses?.toSet().orEmpty()
                                nearestChordToneMidi(midi, tones)
                            } else {
                                midi
                            }
                        },
                        snapTicks = { ticks ->
                            freePracticeGridTime(ticks, grid).absoluteTicks
                        },
                        gridStepTicks = grid.toPianoRollTicks(),
                        onNotePitchDrag = { note, midi ->
                            host.applyPianoRollPitchEdit(
                                eventIds = note.voiceEventIds,
                                sourceMidi = note.midi,
                                targetMidi = midi,
                            ) { selection = it }
                        },
                        onNoteRangeDrag = { note, edge, ticks ->
                            val target = freePracticeGridTime(ticks, grid).timeCode
                            host.applyPianoRollRangeBoundaryEdit(
                                eventIds = note.voiceEventIds,
                                boundary = when (edge) {
                                    PianoRollNoteEdge.START ->
                                        NoteEditEngine.RangeBoundary.START
                                    PianoRollNoteEdge.END ->
                                        NoteEditEngine.RangeBoundary.END
                                },
                                target = target,
                                minimumLength = grid,
                            ) { selection = it }
                        },
                        onSelectionChange = { selected -> selection = selected },
                    )
                },
                style = PianoRollStyleConfig(showChordLabels = false),
                timeProjection = pianoRollProjection,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
            },
        )
        }
    }
}
@Composable
private fun PracticeWritingSection(
    title: String,
    onCollapse: () -> Unit,
    collapsible: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (collapsible) {
            CollapsiblePanelHeader(
                title = title,
                collapsed = false,
                onToggle = onCollapse,
            )
        } else {
            Text(title, color = MeconColors.TextPrimary, fontSize = 12.sp)
        }
        content()
    }
}

@Composable
private fun PracticePreviewSplitPane(
    splitRatio: Float,
    onSplitRatioChange: (Float) -> Unit,
    scorePreviewCollapsed: Boolean,
    pianoRollCollapsed: Boolean,
    onExpandScorePreview: () -> Unit,
    onExpandPianoRoll: () -> Unit,
    previewLayout: FreePracticeWorkbenchLayout,
    writingSurface: FreePracticeWritingSurface,
    onWritingSurfaceChange: (FreePracticeWritingSurface) -> Unit,
    scorePreview: @Composable () -> Unit,
    pianoRoll: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
    ) {
        val availableHeight = maxHeight
        var previewScoreHeight by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf<androidx.compose.ui.unit.Dp?>(null)
        }
        Column(Modifier.fillMaxWidth().fillMaxHeight()) {
            when {
                previewLayout == FreePracticeWorkbenchLayout.WRITING_WITH_LOWER_PANELS -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PracticeChip("五线谱", writingSurface == FreePracticeWritingSurface.SCORE) {
                            onWritingSurfaceChange(FreePracticeWritingSurface.SCORE)
                        }
                        PracticeChip(
                            "钢琴卷轴",
                            writingSurface == FreePracticeWritingSurface.PIANO_ROLL,
                        ) { onWritingSurfaceChange(FreePracticeWritingSurface.PIANO_ROLL) }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        when (writingSurface) {
                            FreePracticeWritingSurface.SCORE -> scorePreview()
                            FreePracticeWritingSurface.PIANO_ROLL -> pianoRoll()
                        }
                    }
                }

                scorePreviewCollapsed -> {
                    CollapsedPracticePreview("五线谱预览", onExpandScorePreview)
                    Spacer(Modifier.height(6.dp))
                    Column(Modifier.weight(1f).fillMaxHeight()) { pianoRoll() }
                }

                pianoRollCollapsed -> {
                    Column(Modifier.weight(1f).fillMaxHeight()) { scorePreview() }
                    Spacer(Modifier.height(6.dp))
                    CollapsedPracticePreview("钢琴卷轴", onExpandPianoRoll)
                }

                else -> {
                    Column(Modifier.weight(splitRatio).fillMaxHeight()) { scorePreview() }
                    Box(Modifier.fillMaxWidth()) {
                        HorizontalDivider(
                            modifier = Modifier.align(Alignment.Center),
                            color = MeconColors.Border,
                        )
                        DeferredVerticalResizeHandle(
                            value = availableHeight * splitRatio,
                            minValue = availableHeight * 0.15f,
                            maxValue = availableHeight * 0.85f,
                            onResizePreview = { previewScoreHeight = it },
                            onResizeEnd = { height ->
                                onSplitRatioChange((height / availableHeight).coerceIn(0.15f, 0.85f))
                            },
                            modifier = Modifier.offset(y = 5.dp),
                        )
                    }
                    Column(Modifier.weight(1f - splitRatio).fillMaxHeight()) { pianoRoll() }
                }
            }
        }
        previewScoreHeight?.let { scoreHeight ->
            Surface(
                modifier = Modifier
                    .offset(y = scoreHeight)
                    .fillMaxWidth()
                    .height((availableHeight - scoreHeight).coerceAtLeast(0.dp))
                    .zIndex(5f),
                color = MeconColors.Surface.copy(alpha = 0.62f),
                border = BorderStroke(1.dp, MeconColors.PrimaryLight),
                shadowElevation = 10.dp,
            ) {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .width(28.dp)
                            .height(2.dp)
                            .background(MeconColors.BorderLight),
                    )
                }
            }
        }
    }
}

@Composable
private fun CollapsedPracticePreview(title: String, onExpand: () -> Unit) {
    CollapsiblePanelItem(
        title = title,
        collapsed = true,
        onCollapsedChange = { collapsed -> if (!collapsed) onExpand() },
        content = {},
    )
}

/** Compose adapter for the common raw timeline scene and input reducer. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SharedHarmonicTimeline(
    workspace: HarmonyWorkspaceState,
    selectedSlotId: WorkspaceSlotId,
    selectedIdiomInstanceId: WorkspaceIdiomInstanceId?,
    onSelectIdiom: (WorkspaceIdiomInstanceId) -> Unit,
    idiomTitles: Map<String, String>,
    toneMode: ChordToneLabelMode,
    beatWidth: androidx.compose.ui.unit.Dp,
    onBeatWidthChange: (androidx.compose.ui.unit.Dp) -> Unit,
    gridUnit: Fraction,
    defaultChordDuration: Fraction,
    scrollState: ScrollState,
    resolvedTimeAxis: ResolvedPracticeTimeAxis?,
    onSelect: (WorkspaceSlotId) -> Unit,
    onInsertRange: (Fraction, Fraction) -> Unit,
    onCommitTimelineEdit: (PracticeTimelineEdit) -> Boolean,
    onPreviewTimelineEdit: (PracticeTimelineEdit) -> PracticeTimelineView?,
    onError: (String) -> Unit,
    onDelete: () -> Unit,
    onSelectTonalLayout: (WorkspaceTonalLayoutId) -> Unit,
) {
    val density = LocalDensity.current
    var gesture by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<com.mecon.features.freepractice.PracticeTimelineGestureState?>(null)
    }
    // The live gesture keeps its *edit*, not a projected timeline. The controller only emits a
    // preview when the quantized edit changes, so a pointer that stays inside one grid cell emits
    // nothing for many events; remembering a projection pinned to the base it was made from meant
    // any workspace republication during those events (auto-writing, an undo, a background result)
    // retired the pin and the preview vanished for the rest of the drag.
    var previewEdit by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<PracticeTimelineEdit?>(null)
    }
    // Committed gestures are different: the session republishes the workspace asynchronously, so the
    // frame right after a commit still carries the pre-drag layout. Freeze what the gesture last
    // showed and hold it until its own base is retired, which makes that handover atomic.
    var heldPreview by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<PracticeTimelineView?>(null)
    }
    var heldPreviewBase by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<PracticeTimelineView?>(null)
    }
    // Pointer feedback is the scene's own contract: the shell only remembers which hover target the
    // controller resolved and replays its cursor and overlay.
    var hoveredHitId by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    var displayMode by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(PracticeTimelineDisplayMode.FULL)
    }
    val baseTimeline = androidx.compose.runtime.remember(workspace, idiomTitles) {
        val projected = FreePracticeViewProjector.timeline(workspace)
        projected.copy(idioms = projected.idioms.map { idiom ->
            idiom.copy(title = idiomTitles[idiom.definitionId] ?: idiom.title)
        })
    }
    // Re-projected whenever the edit or the base changes, so the preview survives republications.
    val livePreview = androidx.compose.runtime.remember(previewEdit, baseTimeline) {
        previewEdit?.let(onPreviewTimelineEdit)
    }
    val activeTimeline = livePreview
        ?: heldPreview?.takeIf { heldPreviewBase === baseTimeline }
        ?: baseTimeline
    // Pointer callbacks outlive the composition that created them; read the current base, never the
    // one that happened to be captured when the handler was built.
    val currentBaseTimeline by rememberReferentialUpdatedState(baseTimeline)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = {
                    PlainTooltip {
                        Text(
                            "灰色边缘调整单个和弦，蓝色中线联动两侧；Ctrl+拖动平移右侧整体，滚轮缩放每拍宽度。",
                            fontSize = 11.sp,
                        )
                    }
                },
                state = rememberTooltipState(),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "和声时间轴",
                        color = MeconColors.TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = "和声时间轴操作说明",
                        tint = MeconColors.TextMuted,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            gesture?.mode?.let { mode ->
                Text(
                    "正在拖动：${when (mode) {
                        com.mecon.features.freepractice.PracticeTimelineGestureMode.TRANSLATE -> "移动和弦"
                        com.mecon.features.freepractice.PracticeTimelineGestureMode.SHARED_BOUNDARY -> "联动边界"
                        com.mecon.features.freepractice.PracticeTimelineGestureMode.TONAL_START,
                        com.mecon.features.freepractice.PracticeTimelineGestureMode.TONAL_END -> "调整调性线"
                        else -> "调整和弦边缘"
                    }}",
                    color = if (mode == com.mecon.features.freepractice.PracticeTimelineGestureMode.SHARED_BOUNDARY) {
                        MeconColors.PrimaryLight
                    } else {
                        MeconColors.OrangeLight
                    },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.weight(1f))
            val selected = baseTimeline.slots.firstOrNull { it.id == selectedSlotId }
            if (selected?.capabilities?.canTranslate == false) {
                Text("惯用进行已锁定 · 请在右侧调整", color = MeconColors.OrangeLight, fontSize = 10.sp)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        // The scene is laid out in density-independent units, exactly like the browser shell's CSS
        // pixels: its own constants (row heights, handle widths, font sizes) are design units, so
        // feeding it raw device pixels made every intrinsic dimension shrink by the display scale
        // while axis-driven widths kept their size. Compose restores the scale on the way out, and
        // `x dp == x * density px` keeps horizontal geometry identical to the notation surface.
        val viewportUnits = maxWidth.value
        val axisAnchors = resolvedTimeAxis?.axis?.anchors?.map { anchor ->
            PracticeTimelineAxisAnchor(
                anchor.absoluteTime,
                freePracticeAxisSceneUnits(anchor.x),
            )
        }.orEmpty()
        val axisEnd = resolvedTimeAxis?.let {
            freePracticeAxisSceneUnits(it.axis.contentEndX)
        } ?: 0f
        val request = PracticeTimelineSceneRequest(
            revision = workspace.hashCode().toLong(),
            axisRevision = resolvedTimeAxis?.axis?.revision ?: 0L,
            viewportWidth = viewportUnits,
            scrollLeft = with(density) { scrollState.value.toDp().value },
            axisAnchors = axisAnchors,
            axisContentEndX = axisEnd,
            pixelsPerWhole = beatWidth.value * 4f,
            timeline = activeTimeline,
            selectedSlotId = selectedSlotId.value,
            selectedIdiomId = selectedIdiomInstanceId?.value,
            gridUnit = gridUnit,
            defaultChordDuration = defaultChordDuration,
            toneLabelMode = when (toneMode) {
                ChordToneLabelMode.RELATIVE -> PracticeTimelineToneLabelMode.RELATIVE
                ChordToneLabelMode.ABSOLUTE -> PracticeTimelineToneLabelMode.ABSOLUTE
            },
            displayMode = displayMode,
            palette = desktopTimelinePalette(),
            showRemoveAction = false,
            gesture = gesture,
        )
        val scene = PracticeTimelineSceneProjector.project(request)
        // Resolve against the current scene: a re-projection can retire the remembered target.
        val hovered = hoveredHitId?.let { id -> scene.hoverTargets.firstOrNull { it.hitId == id } }
        val drawObjects = if (hovered == null) {
            scene.drawObjects
        } else {
            (scene.drawObjects + hovered.overlay).sortedBy { it.z }
        }

        fun applyInteraction(result: com.mecon.features.freepractice.PracticeTimelineInteractionResult) {
            if (!result.accepted) {
                if (!result.ignored) result.reasonKey?.let(onError)
                return
            }
            gesture = result.gesture
            result.previewEdit?.let { edit -> previewEdit = edit }
            fun dropPreview() {
                previewEdit = null
                heldPreview = null
                heldPreviewBase = null
            }
            val commit = result.commitEdit
            if (commit != null) {
                // Project before committing: afterwards the session resolves against a base that
                // already contains the edit, and re-projecting would apply it twice.
                val frozen = onPreviewTimelineEdit(commit)
                val frozenBase = currentBaseTimeline
                previewEdit = null
                // Keep the preview until the committed workspace reaches this composable; only a
                // rejected commit means the pre-drag layout is the truth again.
                if (onCommitTimelineEdit(commit)) {
                    heldPreview = frozen
                    heldPreviewBase = frozenBase
                } else {
                    dropPreview()
                }
            } else if (result.effects.any { it.type == "releasePointer" }) {
                dropPreview()
            }
            result.appendAt?.let { onInsertRange(it, result.appendDuration ?: defaultChordDuration) }
            result.removeSlotId?.let { onDelete() }
            result.selectSlotId?.let { id ->
                if (result.gesture == null && result.commitEdit == null) onSelect(WorkspaceSlotId(id))
            }
            result.selectTonalLayoutId?.let {
                if (result.gesture == null && result.commitEdit == null) {
                    onSelectTonalLayout(WorkspaceTonalLayoutId(it))
                }
            }
            result.selectIdiomId?.let { onSelectIdiom(WorkspaceIdiomInstanceId(it)) }
            result.effects.firstOrNull { it.type == "cursor" || it.type == "capturePointer" }
                ?.cursor?.let { cursor -> setGlobalCursor(Cursor(timelineAwtCursor(cursor))) }
            if (result.effects.any { it.type == "releasePointer" }) setGlobalCursor(null)
        }

        // Named once: inside a DrawScope `density` is the receiver's own Float property.
        val displayScale = density.density
        val currentScene by rememberReferentialUpdatedState(scene)
        val currentRequest by rememberReferentialUpdatedState(request)
        val currentApplyInteraction by rememberReferentialUpdatedState<(com.mecon.features.freepractice.PracticeTimelineInteractionResult) -> Unit> {
            applyInteraction(it)
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(scene.contentHeight.dp)
                // A chord/handle drag and viewport panning must never own the same horizontal
                // pointer stream. If the scrollable stays enabled after the timeline controller
                // captures a target, it moves the child underneath the stationary pointer; the
                // next scene-local x then includes that scroll delta and the preview visibly
                // oscillates left and right. Blank-space drags still pan while no gesture is active.
                .horizontalScroll(scrollState, enabled = gesture == null)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Scroll) {
                                val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                if (delta != 0f) {
                                    onBeatWidthChange((beatWidth - (delta / 2f).dp).coerceIn(44.dp, 176.dp))
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                }
        ) {
            Box(
                Modifier
                    .width(scene.contentWidth.dp)
                    .height(scene.contentHeight.dp)
                    .pointerHoverIcon(
                        PointerIcon(Cursor(timelineAwtCursor(hovered?.cursor))),
                    )
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull() ?: continue
                                if (event.type == PointerEventType.Exit) hoveredHitId = null
                                // Pointer input is device pixels; the scene is laid out in scene units.
                                val sceneX = change.position.x / displayScale
                                val sceneY = change.position.y / displayScale
                                if (event.type == PointerEventType.Move && gesture == null) {
                                    val next = FreePracticeTimelineController.hoverTarget(
                                        currentScene,
                                        sceneX,
                                        sceneY,
                                    )?.hitId
                                    if (next != hoveredHitId) hoveredHitId = next
                                }
                                val type = when (event.type) {
                                    PointerEventType.Press -> PracticeTimelineInputType.DOWN
                                    PointerEventType.Move -> PracticeTimelineInputType.MOVE.takeIf {
                                        gesture != null && change.pressed
                                    }
                                    PointerEventType.Release -> PracticeTimelineInputType.UP
                                    PointerEventType.Exit -> gesture?.let { PracticeTimelineInputType.CANCEL }
                                    else -> null
                                } ?: continue
                                val activeScene = currentScene
                                val result =
                                    FreePracticeTimelineController.handle(
                                        activeScene,
                                        currentRequest.copy(gesture = gesture),
                                        PracticeTimelineInput(
                                            type = type,
                                            sceneGeneration = activeScene.generation,
                                            pointerId = change.id.value,
                                            x = sceneX,
                                            y = sceneY,
                                            ctrl = event.keyboardModifiers.isCtrlPressed,
                                        ),
                                    )
                                currentApplyInteraction(result)
                                if (result.accepted) change.consume()
                            }
                        }
                    }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    // One scale for the whole scene: coordinates, radii and stroke widths are all
                    // expressed in the same density-independent units.
                    scale(displayScale, pivot = Offset.Zero) {
                        drawObjects.forEach { item ->
                            val bounds = item.bounds
                            when (item.kind) {
                                PracticeTimelineDrawKind.RECT -> {
                                    item.fill?.let { fill -> drawRect(
                                        color = timelineSceneColor(fill),
                                        topLeft = Offset(bounds.x, bounds.y),
                                        size = androidx.compose.ui.geometry.Size(bounds.width, bounds.height),
                                    ) }
                                    item.stroke?.let { stroke -> drawRect(
                                        color = timelineSceneColor(stroke),
                                        topLeft = Offset(bounds.x, bounds.y),
                                        size = androidx.compose.ui.geometry.Size(bounds.width, bounds.height),
                                        style = Stroke(item.strokeWidth),
                                    ) }
                                }
                                PracticeTimelineDrawKind.ROUND_RECT -> {
                                    item.fill?.let { fill -> drawRoundRect(
                                        color = timelineSceneColor(fill),
                                        topLeft = Offset(bounds.x, bounds.y),
                                        size = androidx.compose.ui.geometry.Size(bounds.width, bounds.height),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(item.radius),
                                    ) }
                                    item.stroke?.let { stroke -> drawRoundRect(
                                        color = timelineSceneColor(stroke),
                                        topLeft = Offset(bounds.x, bounds.y),
                                        size = androidx.compose.ui.geometry.Size(bounds.width, bounds.height),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(item.radius),
                                        style = Stroke(item.strokeWidth),
                                    ) }
                                }
                                PracticeTimelineDrawKind.CIRCLE -> item.fill?.let { fill ->
                                    drawCircle(
                                        color = timelineSceneColor(fill),
                                        radius = bounds.width / 2f,
                                        center = Offset(bounds.x + bounds.width / 2f, bounds.y + bounds.height / 2f),
                                    )
                                }
                                PracticeTimelineDrawKind.LINE -> drawLine(
                                    color = timelineSceneColor(item.stroke),
                                    start = Offset(bounds.x, bounds.y),
                                    end = Offset(bounds.x + bounds.width, bounds.y + bounds.height),
                                    strokeWidth = item.strokeWidth,
                                    pathEffect = item.dashPattern.takeIf { it.isNotEmpty() }?.let { pattern ->
                                        PathEffect.dashPathEffect(pattern.toFloatArray())
                                    },
                                )
                                PracticeTimelineDrawKind.BRACKET -> {
                                    val color = timelineSceneColor(item.stroke)
                                    drawLine(color, Offset(bounds.x, bounds.y + bounds.height), Offset(bounds.x, bounds.y + 4f), item.strokeWidth)
                                    drawLine(color, Offset(bounds.x, bounds.y + 4f), Offset(bounds.x + bounds.width, bounds.y + 4f), item.strokeWidth)
                                    drawLine(color, Offset(bounds.x + bounds.width, bounds.y + 4f), Offset(bounds.x + bounds.width, bounds.y + bounds.height), item.strokeWidth)
                                }
                                PracticeTimelineDrawKind.TEXT -> Unit
                            }
                        }
                    }
                }
                drawObjects.filter { it.kind == PracticeTimelineDrawKind.TEXT || it.text != null }.forEach { item ->
                    // Scene text bounds describe alignment boxes, while Compose's platform font
                    // line box sits slightly lower and can extend beyond them. Compensate for that
                    // baseline difference and leave room for numerals, CJK glyphs and descenders.
                    val verticalTextOverflow = 4f
                    // Baseline correction, calibrated in device pixels: it answers where Compose's
                    // platform font line box lands inside the layout box, which is a rounding-scale
                    // artifact rather than a share of the font size. Scaling it with the display
                    // made it 4.5 px at 150 % and pulled every timeline label visibly high.
                    val verticalTextShift = -3f / displayScale
                    Box(
                        contentAlignment = if (item.textAlign == "center") {
                            Alignment.Center
                        } else {
                            Alignment.CenterStart
                        },
                        modifier = Modifier
                            .offset(
                                item.bounds.x.dp,
                                (item.bounds.y - verticalTextOverflow + verticalTextShift).dp,
                            )
                            .width(item.bounds.width.dp)
                            .height((item.bounds.height + verticalTextOverflow * 2f).dp),
                    ) {
                        Text(
                            text = item.text.orEmpty(),
                            color = timelineSceneColor(item.fill ?: item.stroke),
                            fontSize = item.fontSize.coerceAtLeast(1f).sp,
                            fontWeight = if (item.fontWeight >= 600) FontWeight.Bold else FontWeight.Normal,
                            textAlign = if (item.textAlign == "center") TextAlign.Center else TextAlign.Start,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                scene.accessibility.forEach { item ->
                    val keyName: (Key) -> String? = { key ->
                        when (key) {
                            Key.DirectionLeft -> "ArrowLeft"
                            Key.DirectionRight -> "ArrowRight"
                            Key.Enter -> "Enter"
                            Key.Spacebar -> " "
                            Key.Delete -> "Delete"
                            Key.Backspace -> "Backspace"
                            Key.Escape -> "Escape"
                            else -> null
                        }
                    }
                    Box(
                        Modifier
                            .offset(item.bounds.x.dp, item.bounds.y.dp)
                            .width(item.bounds.width.dp)
                            .height(item.bounds.height.dp)
                            .semantics {
                                contentDescription = item.label
                                role = Role.Button
                                selected = item.selected
                                if (item.disabled) disabled()
                                onClick {
                                    applyInteraction(
                                        FreePracticeTimelineController.handle(
                                            scene,
                                            request.copy(gesture = gesture),
                                            PracticeTimelineInput(
                                                type = PracticeTimelineInputType.ACTIVATE,
                                                sceneGeneration = scene.generation,
                                                actionTargetId = item.id,
                                            ),
                                        ),
                                    )
                                    true
                                }
                            }
                            .focusable(!item.disabled)
                            .onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                val key = keyName(event.key) ?: return@onKeyEvent false
                                applyInteraction(
                                    FreePracticeTimelineController.handle(
                                        scene,
                                        request.copy(gesture = gesture),
                                        PracticeTimelineInput(
                                            type = PracticeTimelineInputType.KEY,
                                            sceneGeneration = scene.generation,
                                            actionTargetId = item.id,
                                            key = key,
                                            ctrl = event.isCtrlPressed,
                                            meta = event.isMetaPressed,
                                        ),
                                    ),
                                )
                                true
                            },
                    )
                }
            }
        }
        VerticalTimelineDisplaySwitch(
            compact = displayMode == PracticeTimelineDisplayMode.COMPACT,
            onCompactChange = { compact ->
                displayMode = if (compact) {
                    PracticeTimelineDisplayMode.COMPACT
                } else {
                    PracticeTimelineDisplayMode.FULL
                }
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxHeight()
                .zIndex(300f),
        )
        }
    }
}

@Composable
private fun VerticalTimelineDisplaySwitch(
    compact: Boolean,
    onCompactChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(36.dp)
            .background(MeconColors.Surface),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.Top),
    ) {
        Text("完整", color = MeconColors.TextMuted, fontSize = 8.sp)
        Box(
            Modifier
                .size(width = 18.dp, height = 34.dp)
                .background(
                    if (compact) MeconColors.Primary else MeconColors.SurfaceLight,
                    RoundedCornerShape(9.dp),
                )
                .semantics {
                    contentDescription = "时间轴显示模式"
                    stateDescription = if (compact) "精简" else "完整"
                }
                .toggleable(
                    value = compact,
                    role = Role.Switch,
                    onValueChange = onCompactChange,
                ),
        ) {
            Box(
                Modifier
                    .offset(x = 2.dp, y = if (compact) 18.dp else 2.dp)
                    .size(14.dp)
                    .background(MeconColors.TextPrimary, CircleShape),
            )
        }
        Text("精简", color = MeconColors.TextMuted, fontSize = 8.sp)
    }
}

/** Sole mapping from the scene's CSS-style cursor names to AWT cursors. */
private fun timelineAwtCursor(cursor: String?): Int = when (cursor) {
    "ew-resize" -> Cursor.E_RESIZE_CURSOR
    "grab" -> Cursor.MOVE_CURSOR
    "pointer" -> Cursor.HAND_CURSOR
    else -> Cursor.DEFAULT_CURSOR
}

internal fun desktopTimelinePalette(): PracticeTimelinePalette = PracticeTimelinePalette(
    surface = MeconColors.Surface.timelineHex(),
    surfaceLight = MeconColors.SurfaceLight.timelineHex(),
    surfaceDark = MeconColors.SurfaceDark.timelineHex(),
    primaryDark = MeconColors.PrimaryDark.timelineHex(),
    primaryLight = MeconColors.PrimaryLight.timelineHex(),
    selectedSurface = MeconColors.SelectedSurface.timelineHex(),
    selectedBorder = MeconColors.SelectedBorder.timelineHex(),
    textPrimary = MeconColors.TextPrimary.timelineHex(),
    textMuted = MeconColors.TextMuted.timelineHex(),
    border = MeconColors.Border.timelineHex(),
    borderLight = MeconColors.BorderLight.timelineHex(),
    emerald = MeconColors.Emerald.timelineHex(),
    emeraldLight = MeconColors.EmeraldLight.timelineHex(),
    orange = MeconColors.Orange.timelineHex(),
    orangeLight = MeconColors.OrangeLight.timelineHex(),
    white = MeconColors.White.timelineHex(),
)

private fun androidx.compose.ui.graphics.Color.timelineHex(): String = buildString(7) {
    append('#')
    listOf(red, green, blue).forEach { channel ->
        append((channel.coerceIn(0f, 1f) * 255f).roundToInt().toString(16).padStart(2, '0').uppercase())
    }
}

private fun timelineSceneColor(value: String?): androidx.compose.ui.graphics.Color {
    if (value == null || !value.startsWith('#') || (value.length != 7 && value.length != 9)) {
        return androidx.compose.ui.graphics.Color.Transparent
    }
    return runCatching {
        val rgb = value.substring(1, 7).toLong(16)
        val alpha = if (value.length == 9) value.substring(7, 9).toLong(16) else 0xFFL
        androidx.compose.ui.graphics.Color((alpha shl 24) or rgb)
    }.getOrDefault(androidx.compose.ui.graphics.Color.Transparent)
}


@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ChordToolbar(
    selectedChordChoice: WorkspaceChordChoice?,
    selectedInterpretationRef: ChordInterpretationRef?,
    legacyChordSymbol: String?,
    groups: List<ChordSelectionGroup>,
    catalogGroups: List<com.mecon.features.freepractice.PracticeChordCatalogGroupView> = emptyList(),
    groupsByKey: Map<ModulationKey, List<ChordSelectionGroup>>,
    activeLayouts: List<com.mecon.theory.freepractice.WorkspaceTonalLayout>,
    selectedLayoutId: WorkspaceTonalLayoutId,
    isPivotChord: Boolean,
    pivotRecipes: List<com.mecon.theory.schoenberg.SchoenbergFreePracticePivotRecipe>,
    chordLocked: Boolean,
    inversionLocked: Boolean,
    customaryBassGuidance: Set<Int>,
    cadentialDominantGuidance: Boolean,
    defaultBassToRoot: Boolean,
    toneMode: ChordToneLabelMode,
    onToneModeChange: (ChordToneLabelMode) -> Unit,
    onChord: (WorkspaceChordChoice) -> Unit,
    onBass: (Int?) -> Unit,
    onSelectLayout: (WorkspaceTonalLayoutId) -> Unit,
    onSetPivot: (Boolean) -> Unit,
    showSelector: Boolean = true,
    showDetails: Boolean = true,
    sharedDetail: com.mecon.features.freepractice.PracticeChordDetailView? = null,
) {
    val selectedKey = activeLayouts.firstOrNull { it.id == selectedLayoutId }?.key
        ?: activeLayouts.first().key
    val choicesByKey = groupsByKey.mapValues { (_, keyGroups) ->
        keyGroups.flatMap(ChordSelectionGroup::chords)
    }
    val selectedChoice = choicesByKey[selectedKey]?.matchingChoice(
        committed = selectedChordChoice,
        legacyInterpretationRef = selectedInterpretationRef,
        legacySymbol = legacyChordSymbol,
    )
    var previewChoiceId by androidx.compose.runtime.remember(
        selectedKey,
        selectedInterpretationRef,
        selectedChordChoice,
        legacyChordSymbol,
    ) {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    var selectedRouteId by androidx.compose.runtime.remember(previewChoiceId) {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    val previewChoice = choicesByKey[selectedKey]
        ?.firstOrNull { it.id.value == previewChoiceId }
    val alternateReadings = selectedChoice?.let { selected ->
        choicesByKey.mapNotNull { (key, choices) ->
            if (key == selectedKey) return@mapNotNull null
            choices.firstOrNull { it.pitchClasses == selected.pitchClasses }
                ?.let { key to it }
        }
    }.orEmpty()
    val applicablePivotRecipes = selectedChoice?.let { selected ->
        pivotRecipes.filter { recipe ->
            recipe.pitchClasses == selected.pitchClasses
        }
    }.orEmpty()
    val activeKeys = activeLayouts.mapTo(linkedSetOf()) { it.key }
    val recommendedPivotChoices = if (activeKeys.size > 1) {
        pivotRecipes
            .filter { recipe -> recipe.sourceKey in activeKeys && recipe.targetKey in activeKeys }
            .groupBy { it.pitchClasses }
            .mapNotNull { (pitchClasses, recipes) ->
                choicesByKey[selectedKey]
                    ?.firstOrNull { it.pitchClasses == pitchClasses }
                    ?.let { it to recipes }
            }
    } else {
        emptyList()
    }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (showSelector) {
        if (recommendedPivotChoices.isNotEmpty()) {
            Text("转调枢纽和弦推荐", color = MeconColors.OrangeLight, fontSize = 10.sp)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                recommendedPivotChoices.forEach { (choice, _) ->
                    PracticeChip(
                        choice.functionalSymbol,
                        selectedChoice?.pitchClasses == choice.pitchClasses && isPivotChord,
                        MeconColors.Orange,
                    ) {
                        if (!chordLocked) {
                            val action = chordPickerSelectionAction(choice, defaultBassToRoot)
                            previewChoiceId = action.previewChoiceId
                            selectedRouteId = null
                            onChord(action.choice)
                            onSetPivot(true)
                        }
                    }
                }
            }
            recommendedPivotChoices
                .flatMap { (_, recipes) -> recipes }
                .distinctBy { listOf(it.sourceKey, it.targetKey, it.pitchClasses) }
                .take(4)
                .forEach { recipe ->
                    Text(
                        "${recipe.sourceKey.displayName} → ${recipe.targetKey.displayName}：" +
                            recipe.definition,
                        color = MeconColors.TextMuted,
                        fontSize = 9.sp,
                    )
                }
        }
        if (activeLayouts.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("按哪个调选和弦", color = MeconColors.TextMuted, fontSize = 10.sp)
                activeLayouts.forEach { layout ->
                    PracticeChip(layout.key.displayName, layout.id == selectedLayoutId) {
                        onSelectLayout(layout.id)
                    }
                }
            }
        }
        if (chordLocked) {
            Text(
                if (inversionLocked) {
                    "该和弦属于惯用进行，和弦及转位由章节规则固定。"
                } else {
                    "该和弦属于惯用进行；和弦身份已锁定，但可在下方修改低音。"
                },
                color = MeconColors.OrangeLight,
                fontSize = 10.sp,
            )
        }
        if (selectedChoice != null) {
            val bassOptions = androidx.compose.runtime.remember(selectedChoice, toneMode) {
                chordBassOptions(selectedChoice, toneMode)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                fun commitBass(pitchClass: Int?) {
                    if (inversionLocked) return
                    if (chordLocked) {
                        onBass(pitchClass)
                        return
                    }
                    val currentChoice = selectedChordChoice ?: WorkspaceChordChoice.of(
                        pitchClasses = selectedChoice.pitchClasses,
                        origin = selectedChoice.origin,
                        pinnedInterpretationRef = selectedInterpretationRef,
                    )
                    onChord(currentChoice.copy(bassPitchClass = pitchClass))
                }
                Text(
                    "低音",
                    color = MeconColors.TextMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                PracticeChip("任意", selectedChordChoice?.bassPitchClass == null) {
                    commitBass(null)
                }
                bassOptions.forEach { option ->
                    PracticeChip(
                        option.label,
                        selectedChordChoice?.bassPitchClass == option.pitchClass,
                    ) {
                        commitBass(option.pitchClass)
                    }
                }
                PracticeChip(
                    explorationText("modulation.pitch.relative"),
                    toneMode == ChordToneLabelMode.RELATIVE,
                ) { onToneModeChange(ChordToneLabelMode.RELATIVE) }
                PracticeChip(
                    explorationText("modulation.pitch.absolute"),
                    toneMode == ChordToneLabelMode.ABSOLUTE,
                ) { onToneModeChange(ChordToneLabelMode.ABSOLUTE) }
            }
            customaryBassGuidanceText(customaryBassGuidance, bassOptions)?.let { guidance ->
                Text(
                    guidance,
                    color = MeconColors.OrangeLight,
                    fontSize = 10.sp,
                )
            }
            if (cadentialDominantGuidance) {
                Text(
                    "进行提示：终止式的 V 不建议使用第二转位。",
                    color = MeconColors.OrangeLight,
                    fontSize = 10.sp,
                )
            }
        }
        ChordCatalogPicker(
            groups = if (catalogGroups.isNotEmpty()) catalogGroups.map { group ->
                ChordCatalogPickerGroup(
                    id = group.id,
                    title = group.titleLabel,
                    description = group.descriptionLabel,
                    choices = group.choices.map { chord ->
                        ChordCatalogPickerChoice(
                            identity = chord.id,
                            functionalSymbol = chord.symbol,
                            absoluteTones = chord.absoluteTones,
                            relativeTones = chord.relativeTones,
                        )
                    },
                )
            } else groups.map { group ->
                ChordCatalogPickerGroup(
                    id = group.category.id,
                    title = i18n(group.category.titleKey),
                    description = i18n(group.category.descriptionKey),
                    choices = group.chords.map { chord ->
                        val otherKeyCount = choicesByKey
                            .filterKeys { it != selectedKey }
                            .count { (_, choices) ->
                                choices.any { it.pitchClasses == chord.pitchClasses }
                            }
                        ChordCatalogPickerChoice(
                            identity = chord.id.value,
                            functionalSymbol = chord.functionalSymbol,
                            absoluteTones = chord.absoluteTones,
                            relativeTones = chord.relativeTones,
                            accent = if (otherKeyCount > 0) {
                                when (otherKeyCount.mod(3)) {
                                    0 -> MeconColors.PrimaryLight
                                    1 -> MeconColors.EmeraldLight
                                    else -> MeconColors.OrangeLight
                                }
                            } else {
                                null
                            },
                            annotation = buildList {
                                otherKeyCount.takeIf { it > 0 }?.let { add("另 $it 调") }
                                if (chord.interpretationRefs.size > 1) {
                                    add("${chord.interpretationRefs.size} 条线路")
                                }
                            }.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                        )
                    },
                )
            },
            selectedIdentity = chordPickerSelectedIdentity(previewChoiceId, selectedChoice),
            strings = ChordCatalogPickerStrings(
                chooseChord = explorationText("freePractice.chords.choose"),
                currentChord = { explorationText("freePractice.chords.current", it) },
                chordTones = explorationText("freePractice.chords.tones"),
                relativePitch = explorationText("modulation.pitch.relative"),
                absolutePitch = explorationText("modulation.pitch.absolute"),
            ),
            onSelect = { selectionId ->
                if (!chordLocked) {
                    val sharedChoice = catalogGroups.asSequence().flatMap { it.choices.asSequence() }
                        .firstOrNull { it.id == selectionId }
                    val choice = choicesByKey[selectedKey]?.firstOrNull { it.id.value == selectionId }
                    if (sharedChoice != null) {
                        previewChoiceId = selectionId
                        selectedRouteId = null
                        onChord(sharedChoice.choice)
                    } else
                    if (choice != null) {
                        val action = chordPickerSelectionAction(choice, defaultBassToRoot)
                        previewChoiceId = action.previewChoiceId
                        selectedRouteId = null
                        onChord(action.choice)
                    }
                }
            },
            toneMode = toneMode,
            onToneModeChange = onToneModeChange,
            showToneModeControls = false,
        )
        }
        if (showDetails) {
        val detailChoice = previewChoice ?: selectedChoice
        if (sharedDetail != null || detailChoice != null) {
            val knowledgeContext = androidx.compose.runtime.remember(selectedKey) {
                ChordKnowledgeContext(selectedKey.tonalContext("chord-selection"))
            }
            val snapshot = androidx.compose.runtime.remember(selectedKey) {
                ChordCatalogSnapshot.create(
                    selectedKey,
                    treatmentRegistry = SchoenbergHarmonicTreatments.registry,
                )
            }
            val detail = detailChoice?.let { choice -> snapshot.resolve(
                SoundingInterpretationQuery(
                    audibleKey = choice.audibleKey,
                    selectedOrigin = choice.origin,
                    pinnedInterpretationRef = selectedChordChoice?.pinnedInterpretationRef,
                ),
                knowledgeContext,
            ) }
            ChordDetailPanel(
                model = sharedDetail?.let(ChordDetailUiMapper::map)
                    ?: ChordDetailUiMapper.map(requireNotNull(detail), ::i18n),
                mode = if (sharedDetail != null) ChordDetailMode.VIEW else ChordDetailMode.PRE_COMMIT,
                strings = ChordDetailPanelStrings(
                    routes = i18n("exploration.chordDetail.routes"),
                    routeHint = i18n("exploration.chordDetail.routeHint"),
                    sources = i18n("exploration.chordDetail.sources"),
                    applyInterpretation = i18n("exploration.chordDetail.apply"),
                    chooseRoute = i18n("exploration.chordDetail.chooseRoute"),
                ),
                selectedRouteId = selectedRouteId ?: selectedChordChoice?.pinnedInterpretationRef?.let { pinned ->
                    detail?.explanations?.flatMap { it.routes }?.firstOrNull { it.interpretationRef == pinned }?.id?.value
                },
                onSelectRoute = { selectedRouteId = it },
                onConfirmRoute = { routeId ->
                    detail?.explanations?.flatMap { it.routes }
                        ?.firstOrNull { it.id.value == routeId }
                        ?.interpretationRef
                        ?.let { ref ->
                            val choice = requireNotNull(detailChoice)
                            onChord(
                                WorkspaceChordChoice.of(
                                    pitchClasses = choice.pitchClasses,
                                    origin = choice.origin,
                                    pinnedInterpretationRef = ref,
                                    bassPitchClass = if (selectedChordChoice != null) {
                                        selectedChordChoice.bassPitchClass
                                    } else {
                                        choice.rootPitchClass.takeIf { defaultBassToRoot }
                                    },
                                )
                            )
                            previewChoiceId = null
                        }
                },
                pinned = selectedChordChoice?.pinnedInterpretationRef != null,
                onRestoreFreeInterpretation = {
                    val choice = requireNotNull(detailChoice)
                    onChord(
                        WorkspaceChordChoice.of(
                            pitchClasses = choice.pitchClasses,
                            origin = choice.origin,
                            bassPitchClass = if (selectedChordChoice != null) {
                                selectedChordChoice.bassPitchClass
                            } else {
                                choice.rootPitchClass.takeIf { defaultBassToRoot }
                            },
                        )
                    )
                },
                constructionPreview = { construction ->
                    ChordConstructionScorePreview(construction)
                },
            )
        }
        if (selectedChoice != null || legacyChordSymbol != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PracticeChip("枢纽和弦", isPivotChord, MeconColors.Orange) {
                    onSetPivot(!isPivotChord)
                }
                if (applicablePivotRecipes.isNotEmpty()) {
                    Text(
                        "教材定义 ${applicablePivotRecipes.size} 条",
                        color = MeconColors.OrangeLight,
                        fontSize = 9.sp,
                    )
                } else {
                    Text("枢纽标记仅作视觉标注", color = MeconColors.TextDark, fontSize = 9.sp)
                }
            }
            if (alternateReadings.isNotEmpty()) {
                Text("在其他重合调性中的解释", color = MeconColors.TextMuted, fontSize = 10.sp)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    alternateReadings.forEach { (key, reading) ->
                        Surface(
                            color = MeconColors.Emerald.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, MeconColors.Emerald),
                        ) {
                            Text(
                                "${key.displayName}：${reading.functionalSymbol} · " +
                                    reading.relativeTones.joinToString("–"),
                                color = MeconColors.EmeraldLight,
                                fontSize = 9.sp,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
            applicablePivotRecipes.take(3).forEach { recipe ->
                Text(
                    "${recipe.sourceKey.displayName} ↔ ${recipe.targetKey.displayName}：${recipe.definition}",
                    color = MeconColors.OrangeLight,
                    fontSize = 9.sp,
                )
            }
        }
        }
    }
}
