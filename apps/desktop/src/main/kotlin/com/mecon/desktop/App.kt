package com.mecon.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.model.NoteId
import com.mecon.api.model.Score
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TrackId
import com.mecon.api.primitive.ReductionId
import com.mecon.api.primitive.PlayerId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.toStorage
import com.mecon.api.runtime.orderedStaffs
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.core.engine.computeScore
import com.mecon.core.engine.edit.MeasureEditEngine
import com.mecon.core.engine.edit.LayoutBreakEditEngine
import com.mecon.core.engine.edit.StaffVisibilityEditEngine
import com.mecon.core.engine.edit.ExpressionEditEngine
import com.mecon.core.analysis.ReductionEngine
import com.mecon.api.storage.Articulation
import com.mecon.api.storage.events.HairpinStyle
import com.mecon.api.storage.events.HairpinType
import com.mecon.api.storage.events.OctaveShiftType
import com.mecon.api.storage.tracks.MeasureRange
import com.mecon.desktop.input.KeybindingStore
import com.mecon.desktop.input.GlobalShortcutDispatcher
import com.mecon.desktop.input.SelectionEditor
import com.mecon.desktop.input.ScoreSelectionEditor
import com.mecon.desktop.input.ShortcutAction
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.NoteRef
import com.mecon.renderer.interaction.*
import com.mecon.api.interaction.*
import com.mecon.desktop.ui.components.*
import com.mecon.desktop.ui.dialogs.DocumentSafetyDialogs
import com.mecon.desktop.input.handleEditingShortcut
import com.mecon.desktop.input.NoteInputController
import com.mecon.desktop.input.NoteInputState
import com.mecon.desktop.input.JvmMidiInputService
import com.mecon.desktop.uikit.i18n.I18nRegistry
import com.mecon.desktop.uikit.plugin.PluginPanelContext
import com.mecon.desktop.service.ScoreFileService
import com.mecon.desktop.service.PlaybackController
import com.mecon.desktop.service.ScoreSession
import com.mecon.desktop.service.EditableScoreHost
import com.mecon.desktop.service.ScoreFileController
import com.mecon.desktop.service.FreePracticeFileSnapshot
import com.mecon.desktop.service.HarmonyPracticeScoreHost
import com.mecon.desktop.service.activeFreePracticeSnapshot
import com.mecon.desktop.service.auditionSingleEditedEvent
import com.mecon.desktop.service.addPluginEvent
import com.mecon.desktop.service.applyMetadata
import com.mecon.desktop.service.applyPageConfig
import com.mecon.desktop.service.applyStorageEdit
import com.mecon.desktop.service.deletePluginEvent
import com.mecon.desktop.service.replacePluginEvents
import com.mecon.desktop.service.toggleMeasureNumbers
import com.mecon.desktop.service.updatePluginEvent
import com.mecon.audio.JvmAudioEngine
import kotlinx.coroutines.launch
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.desktop.uikit.theme.MeconDimensions
import com.mecon.desktop.uikit.theme.ThemeMode
import com.mecon.desktop.uikit.components.MeconTextInputFocus
import com.mecon.desktop.ui.exploration.ExplorationView
import com.mecon.desktop.ui.exploration.FreePracticeToolbarController
import com.mecon.desktop.ui.exploration.ExplorationToolbarController
import com.mecon.desktop.ui.components.topbar.ScoreViewMode
import com.mecon.api.primitive.TimeCode

@Composable
fun App() {
    val compositionStartedAt = System.nanoTime()
    SideEffect {
        com.mecon.renderer.debug.PerfLog.log("compose.app") {
            "composition=${(System.nanoTime() - compositionStartedAt) / 1_000_000}ms"
        }
    }
    val coroutineScope = rememberCoroutineScope()

    // Services — own the logic behind the toolbar's hooks. App only wires them up.
    val audioEngine = remember { JvmAudioEngine(coroutineScope) }
    val playback = remember { PlaybackController(audioEngine, coroutineScope) }
    val session = remember { ScoreSession(coroutineScope) }
    val midiInput = remember { JvmMidiInputService(coroutineScope) }
    val fileController = remember {
        ScoreFileController(coroutineScope, ScoreFileService.instance, session, playback)
    }

    LaunchedEffect(fileController) { fileController.start() }
    DisposableEffect(fileController) {
        DesktopApplicationLifecycle.install(
            close = fileController::requestExit,
            recover = fileController::emergencyAutosaveBlocking,
        )
        onDispose { DesktopApplicationLifecycle.clear() }
    }

    val playbackState by playback.playbackState.collectAsState()
    val currentPositionTicks by playback.currentPositionTicks.collectAsState()
    val midiDevices by midiInput.devices.collectAsState()
    val selectedMidiDeviceId by midiInput.selectedDeviceId.collectAsState()
    val selectedMidiDeviceName = midiDevices.firstOrNull { it.id == selectedMidiDeviceId }?.name

    // Audio engine lifecycle
    LaunchedEffect(Unit) {
        playback.initialize()
        midiInput.start()
    }
    DisposableEffect(Unit) {
        onDispose {
            midiInput.close()
            coroutineScope.launch { playback.shutdown() }
        }
    }

    // UI-only state (selection, active tab, tool) stays in the composition.
    val fileMenuTab = i18n("menu.file")
    var activeMenuTab by remember { mutableStateOf(fileMenuTab) }
    val explorationMenuTab = i18n("menu.exploration")
    var explorationScoreHost by remember { mutableStateOf<EditableScoreHost?>(null) }
    var latestFreePracticeSnapshot by remember {
        mutableStateOf<FreePracticeFileSnapshot?>(null)
    }
    var freePracticeFile by remember { mutableStateOf<java.io.File?>(null) }
    var freePracticeOpenGeneration by remember { mutableStateOf(0L) }
    var freePracticeModeActive by remember { mutableStateOf(false) }
    var freePracticeToolbarController by remember {
        mutableStateOf<FreePracticeToolbarController?>(null)
    }
    var explorationToolbarController by remember {
        mutableStateOf<ExplorationToolbarController?>(null)
    }
    val activeHistoryHost: EditableScoreHost? =
        if (activeMenuTab == explorationMenuTab) explorationScoreHost else session
    SideEffect {
        fileController.activeFreePracticeSnapshot = {
            val practiceHost = explorationScoreHost as? HarmonyPracticeScoreHost
            latestFreePracticeSnapshot.takeIf {
                activeMenuTab == explorationMenuTab &&
                    freePracticeModeActive &&
                    practiceHost?.hasPendingWorkspaceCommit != true
            }
        }
        fileController.isFreePracticeActive = {
            activeMenuTab == explorationMenuTab && freePracticeModeActive
        }
        fileController.isExplorationActive = {
            activeMenuTab == explorationMenuTab
        }
        fileController.activeFreePracticeFile = {
            freePracticeFile.takeIf {
                activeMenuTab == explorationMenuTab && freePracticeModeActive
            }
        }
        fileController.onContainerOpened = { document, file ->
            val opened = document.activeFreePracticeSnapshot()
            if (opened != null) {
                latestFreePracticeSnapshot = opened
                freePracticeFile = file
                freePracticeOpenGeneration += 1
                activeMenuTab = explorationMenuTab
                fileController.markFreePracticeOpened(opened)
            } else {
                latestFreePracticeSnapshot = null
                freePracticeFile = null
                freePracticeModeActive = false
                activeMenuTab = fileMenuTab
            }
            opened != null
        }
        fileController.onStandaloneDocumentOpened = {
            latestFreePracticeSnapshot = null
            freePracticeFile = null
            freePracticeModeActive = false
            activeMenuTab = fileMenuTab
        }
        fileController.onFreePracticeSaved = { document, file ->
            latestFreePracticeSnapshot = document.activeFreePracticeSnapshot()
                ?: latestFreePracticeSnapshot
            freePracticeFile = file
        }
        fileController.onRecoveredFreePractice = { document, file ->
            val recovered = document.activeFreePracticeSnapshot()
            latestFreePracticeSnapshot = recovered
            freePracticeFile = file
            freePracticeOpenGeneration += 1
            freePracticeModeActive = recovered != null
            activeMenuTab = if (recovered != null) explorationMenuTab else fileMenuTab
        }
    }
    var scoreViewMode by remember { mutableStateOf(ScoreViewMode.EDIT) }
    val score by remember { mutableStateOf(Score.createDemo()) }
    var noteSelection by remember { mutableStateOf<NoteId?>(null) }
    // Multi-selection: a set of selected sections. Single-select is just a set of size ≤ 1.
    // Iteration order is insertion order (setOf / `+` preserve it), so lastOrNull() is the most
    // recently selected target — the one fed to single-target consumers (top bar, right panel).
    val eventSelectionState = remember { mutableStateOf<Set<EventSection>>(emptySet()) }
    var eventSelection by eventSelectionState
    var selectedAnnotationEventId by remember { mutableStateOf<EventId?>(null) }
    var latestRenderedGeometry by remember { mutableStateOf<com.mecon.api.storage.ScoreGeometry?>(null) }
    LaunchedEffect(session.documentVersion) { latestRenderedGeometry = null }
    var noteClipboard by remember { mutableStateOf<NoteEditEngine.NoteClipboard?>(null) }
    var expressionClipboard by remember { mutableStateOf<ExpressionEditEngine.Clipboard?>(null) }
    var copiedExpressionSourceIds by remember { mutableStateOf<Set<EventId>>(emptySet()) }
    val noteTool = remember { NoteToolState() }
    val noteInput = remember { NoteInputState() }

    // The selection rides the undo history: register it as an editor state so undo/redo restores the
    // selection that was active at each step (so e.g. multi-select → drag → undo leaves the multi-
    // selection intact, not collapsed to one note). Designed for more states to be added the same way.
    LaunchedEffect(session) {
        session.registerEditorState("selection", object : com.mecon.api.state.EditorStateController {
            override fun capture(): Any? = eventSelectionState.value
            @Suppress("UNCHECKED_CAST")
            override fun restore(snapshot: Any?) {
                eventSelectionState.value = (snapshot as? Set<EventSection>) ?: emptySet()
            }
        })
    }

    // Palette edit-mode plumbing: aggregate the selection's properties (drives the palette highlight)
    // and a transient banner for rejected edits.
    val paletteInfo = paletteInfoFor(eventSelection, session.runtimeScore, session.computedScore)
    val clefInfo = clefInfoFor(eventSelection)
    val keyInfo = keyInfoFor(eventSelection)
    val timeInfo = timeInfoFor(eventSelection)
    val barlineInfo = barlineInfoFor(eventSelection, session.runtimeScore)
    var editMessage by remember { mutableStateOf<String?>(null) }
    val dialogState = rememberAppDialogState()
    LaunchedEffect(session.documentVersion) {
        eventSelectionState.value = emptySet()
        selectedAnnotationEventId = null
        noteSelection = null
        editMessage = null
        noteClipboard = null
        expressionClipboard = null
        copiedExpressionSourceIds = emptySet()
        noteTool.resetForDocumentSwitch()
        noteInput.resetForDocumentSwitch()
    }
    LaunchedEffect(editMessage) {
        if (editMessage != null) { kotlinx.coroutines.delay(2500); editMessage = null }
    }

    // Delete the current section selection (notes / rests). Shared by the Delete key and the
    // right-panel button; resolves sections → deletions against the live runtime, then re-points
    // the selection at the resulting rests.
    fun deleteMeasures(measures: Set<Int>) {
        session.deleteMeasures(measures) {
            eventSelection = emptySet()
            selectedAnnotationEventId = null
        }
    }
    fun applyExpressionResult(
        result: ExpressionEditEngine.Result?,
        onAfter: () -> Unit = {},
    ) {
        result ?: return
        session.applyExpressionEdit(result) { newSelection ->
            eventSelection = newSelection
            selectedAnnotationEventId = null
            onAfter()
        }
    }
    val deleteSelection: () -> Unit = {
        deleteScoreSelection(
            session = session,
            selection = eventSelection,
            onSelectionChange = { eventSelection = it },
            onAnnotationSelectionChange = { selectedAnnotationEventId = it },
            onApplyExpressionResult = ::applyExpressionResult,
            onDeleteMeasures = ::deleteMeasures,
            onConfirmMeasureDeletion = { dialogState.pendingMeasureDeletion = it },
        )
    }
    fun copySelectionToClipboard(): Boolean {
        val rt = session.runtimeScore ?: return false
        val targets = buildCopyTargets(eventSelection, rt, session.computedScore)
        noteClipboard = NoteEditEngine.copyNotes(rt, targets)
        val selectedNotes = selectedEvents(eventSelection, rt, session.computedScore).filterNot { it.isRest }
        val selectedIds = selectedNotes.mapTo(HashSet()) { it.id }
        val ranges = selectedNotes.groupBy { event ->
            val voiceId = event.originVoiceTrackId ?: rt.voiceTrackIdOf(event.id)
            rt.staffTracks.values.firstOrNull { staff -> staff.voiceTracks.any { it.id == voiceId } }?.id
        }.filterKeys { it != null }.mapNotNull { (staffId, events) ->
            staffId?.let { it to (events.minOf { e -> e.onset } to events.maxOf { e -> e.endTime }) }
        }.toMap()
        val directIds = eventSelection.filterIsInstance<StaffAttachmentSection>()
            .mapNotNullTo(LinkedHashSet()) { section ->
                val breath = section.attachment as? com.mecon.api.computed.ComputedBreathMark
                section.attachment.id.takeUnless { breath?.isGlobal == true }
            }
        val autoIds = LinkedHashSet<EventId>()
        val completeOctaves = LinkedHashSet<EventId>()
        for (attachment in session.computedScore?.staffAttachments.orEmpty()) {
            val range = ranges[attachment.staffTrackId] ?: continue
            when (attachment) {
                is com.mecon.api.computed.ComputedDynamicMark -> if (attachment.time in range.first..range.second) autoIds += attachment.id
                is com.mecon.api.computed.ComputedHairpin -> if (attachment.endTime >= range.first && attachment.time <= range.second) autoIds += attachment.id
                is com.mecon.api.computed.ComputedOctaveShift -> {
                    val staff = rt.staffTracks[attachment.staffTrackId] ?: continue
                    val allInside = staff.voiceTracks.flatMap { it.events.toList() }
                        .filter { !it.isRest && it.onset >= attachment.time && it.onset < attachment.endTime }
                    if (allInside.isNotEmpty() && allInside.all { it.id in selectedIds }) {
                        autoIds += attachment.id
                        completeOctaves += attachment.id
                    }
                }
                is com.mecon.api.computed.ComputedBreathMark -> {
                    if (!attachment.isGlobal && attachment.time in range.first..range.second) {
                        autoIds += attachment.id
                    }
                }
                is com.mecon.api.computed.ComputedOrnamentMark -> {
                    if (attachment.time in range.first..range.second) autoIds += attachment.id
                }
                is com.mecon.api.computed.ComputedTempoKeyframe -> {}
                is com.mecon.api.computed.ComputedVoltaAttachment -> {}
            }
        }
        val expressionIds = directIds + autoIds
        expressionClipboard = ExpressionEditEngine.copyAttachments(
            rt, expressionIds, completeOctaves, ranges,
        )
        copiedExpressionSourceIds = expressionIds.filterTo(LinkedHashSet()) { id ->
            val attachment = session.computedScore?.staffAttachments?.firstOrNull { it.id == id }
            attachment !is com.mecon.api.computed.ComputedOctaveShift || id in completeOctaves
        }
        return noteClipboard?.isEmpty == false || expressionClipboard?.isEmpty == false
    }
    val copySelection: () -> Unit = {
        copySelectionToClipboard()
    }
    val cutSelection: () -> Unit = {
        val rt = session.runtimeScore
        if (rt != null && copySelectionToClipboard()) {
            // CUT is an editing command, not an alias for the top-bar delete button. In particular,
            // a MeasureStaffSection may expand to copyable notes, but it must not make Ctrl+X delete
            // the measure itself. Delete only the note/rest material that was copied.
            val deletions = buildCutDeletions(eventSelection, rt, session.computedScore)
            if (deletions.isNotEmpty() && copiedExpressionSourceIds.isNotEmpty()) {
                var scoreAfterNotes: RuntimeScore = rt
                val measures = ArrayList<Int>()
                for (deletion in deletions) {
                    val result = NoteEditEngine.delete(scoreAfterNotes, deletion) ?: continue
                    scoreAfterNotes = result.score
                    measures += result.editInterval.start.measure
                    measures += result.editInterval.end.measure
                }
                val expressions = ExpressionEditEngine.deleteAttachments(scoreAfterNotes, copiedExpressionSourceIds)
                if (expressions != null) {
                    val range = (measures + expressions.affectedMeasures.toList())
                    applyExpressionResult(expressions.copy(affectedMeasures = range.min()..range.max()))
                }
            } else if (deletions.isNotEmpty()) {
                session.applyNoteDeletes(deletions) { newSelection ->
                    eventSelection = newSelection
                    selectedAnnotationEventId = null
                }
            } else if (copiedExpressionSourceIds.isNotEmpty()) {
                applyExpressionResult(ExpressionEditEngine.deleteAttachments(rt, copiedExpressionSourceIds))
            }
        }
    }
    val pasteSelection: () -> Unit = paste@{
        if (noteClipboard == null && expressionClipboard == null) return@paste
        val target = buildPasteTarget(eventSelection, session.runtimeScore) ?: return@paste
        session.applyEditorPaste(
            noteClipboard,
            expressionClipboard,
            target,
            onTupletCrossesBarline = { editMessage = i18n("edit.pasteTupletCrossBarline") },
        ) { newSelection ->
            eventSelection = newSelection
            selectedAnnotationEventId = null
        }
    }

    // ---- Staff show/hide ----
    // Reveal (un-hide) a set of staves over a measure range (right-click menu / property panel).
    val revealStaff: (List<TrackId>, MeasureRange) -> Unit = reveal@{ staffIds, range ->
        val rt = session.runtimeScore ?: return@reveal
        val updated = StaffVisibilityEditEngine.show(rt, staffIds.toSet(), range) ?: return@reveal
        session.applyMeasureEdit(updated, range.from..range.to) {
            eventSelection = emptySet()
            selectedAnnotationEventId = null
        }
    }
    // Hide the given per-staff measure cells (top edit panel). Blocked by the engine when notes exist.
    val hideStaffCells: (Map<TrackId, Set<Int>>) -> Unit = hide@{ cells ->
        val rt = session.runtimeScore ?: return@hide
        val updated = StaffVisibilityEditEngine.hideCells(rt, cells) ?: return@hide
        val measures = cells.values.flatten()
        val range = (measures.minOrNull() ?: 1)..(measures.maxOrNull() ?: 1)
        session.applyMeasureEdit(updated, range) {
            eventSelection = emptySet()
            selectedAnnotationEventId = null
        }
    }
    // Selection → the (staff, measure) cells the "hide" buttons act on. Measure-staff cells hide
    // themselves; a lone barline hides that measure across every staff (see plan).
    val maxMeasureForHide = session.runtimeScore?.measures?.maxOfOrNull { it.value.number } ?: 0
    val hideCellsSelection: Map<TrackId, Set<Int>> = run {
        val rt = session.runtimeScore
        val cells = eventSelection.filterIsInstance<MeasureStaffSection>()
        when {
            cells.isNotEmpty() -> cells.groupBy({ it.staffTrackId }, { it.measureNumber }).mapValues { it.value.toSet() }
            else -> {
                val barline = eventSelection.singleOrNull() as? BarlineSection
                if (barline != null && rt != null) {
                    rt.orderedStaffs().associate { it.id to setOf(barline.barline.measureNumber) }
                } else emptyMap()
            }
        }
    }
    val hideFollowingSelection: Map<TrackId, Set<Int>> = hideCellsSelection.mapValues { (_, measures) ->
        val start = measures.minOrNull() ?: return@mapValues measures
        (start..maxMeasureForHide).toSet()
    }
    fun canHide(cells: Map<TrackId, Set<Int>>): Boolean =
        cells.isNotEmpty() && session.runtimeScore?.let { !StaffVisibilityEditEngine.hasNotesInCells(it, cells) } == true
    val selectedBreakBoundary = when (val section = eventSelection.singleOrNull()) {
        is LayoutBreakSection -> section.beforeMeasure
        is BarlineSection -> section.barline.measureNumber + 1
        else -> null
    }?.takeIf { boundary ->
        val max = session.runtimeScore?.measures?.maxOfOrNull { it.value.number } ?: 0
        boundary in 2..max
    }
    val selectedBreakKind = selectedBreakBoundary?.let { boundary ->
        when {
            boundary in session.runtimeScore?.forcedPageBreaks.orEmpty() -> LayoutBreakKind.PAGE
            boundary in session.runtimeScore?.forcedSystemBreaks.orEmpty() -> LayoutBreakKind.SYSTEM
            else -> null
        }
    }
    fun auditionSingleEditedEvent(
        sel: Set<EventSection>,
        score: RuntimeScore? = session.runtimeScore,
    ) = playback.auditionSingleEditedEvent(sel, score)

    val noteInputController = remember(session, noteTool, noteInput) {
        NoteInputController(
            scope = coroutineScope,
            state = noteInput,
            session = session,
            noteTool = noteTool,
            onInserted = { inserted, committed ->
                eventSelection = setOf(inserted)
                selectedAnnotationEventId = null
                auditionSingleEditedEvent(setOf(inserted), committed)
            },
            onLiveNoteOn = { midi, velocity ->
                playback.liveNoteOn(
                    session.runtimeScore,
                    noteInput.caret?.staffTrackId,
                    midi,
                    velocity,
                )
            },
            onLiveNoteOff = playback::liveNoteOff,
            onLiveAllNotesOff = playback::liveAllNotesOff,
            onMetronomeTick = playback::metronomeTick,
        )
    }
    LaunchedEffect(noteInput.phase, noteInputController) {
        noteInputController.onPhaseChanged()
    }
    DisposableEffect(noteInputController, midiInput) {
        midiInput.setListener { event ->
            coroutineScope.launch { noteInputController.handlePerformanceEvent(event) }
        }
        onDispose { midiInput.setListener(null) }
    }

    // Re-point the selection at the edited events after a property edit commits and audition a
    // single edited note/chord. Multi-event batch edits stay silent because there is no unique focus.
    val onAfterEdit: (Set<EventSection>) -> Unit = { sel ->
        eventSelection = sel
        selectedAnnotationEventId = null
        auditionSingleEditedEvent(sel)
    }
    // The main editor and embedded notation workbenches share the same selection projection,
    // property toggles and palette callbacks. Their hosts differ only in how an engine result is
    // committed to history.
    val selectionEditor: SelectionEditor = ScoreSelectionEditor(
        host = session,
        noteTool = noteTool,
        selection = { eventSelection },
        selectionInfo = { paletteInfo },
        onAfterEdit = onAfterEdit,
        onDurationConflict = { editMessage = i18n("edit.durationConflict") },
        onTupletConflict = { editMessage = i18n("edit.tupletConflict") },
    )
    fun selectedExpressionEvents(): List<ComputedVoiceEvent> {
        val rt = session.runtimeScore ?: return emptyList()
        return selectedEvents(eventSelection, rt, session.computedScore).filterNot { it.isRest }
    }
    fun staffIdForEvent(event: ComputedVoiceEvent): TrackId? {
        val rt = session.runtimeScore ?: return null
        val voiceId = event.originVoiceTrackId ?: rt.voiceTrackIdOf(event.id) ?: return null
        return rt.staffTracks.values.firstOrNull { staff -> staff.voiceTracks.any { it.id == voiceId } }?.id
    }

    fun addSelectedSpan(
        hairpin: Pair<HairpinType, HairpinStyle>? = null,
        octave: OctaveShiftType? = null,
    ): Boolean {
        var rt = session.runtimeScore ?: return false
        val grouped = selectedExpressionEvents().groupBy(::staffIdForEvent).filterKeys { it != null }
        if (grouped.isEmpty()) return false
        var combined: ExpressionEditEngine.Result? = null
        for ((staffId0, events) in grouped) {
            val staffId = staffId0 ?: continue
            val sorted = events.sortedBy { it.onset }
            val start = sorted.first().onset
            val end = sorted.last().onset
            val next = when {
                hairpin != null -> ExpressionEditEngine.addHairpin(rt, staffId, start, end, hairpin.first, hairpin.second)
                octave != null -> ExpressionEditEngine.addOctaveShift(rt, staffId, start, end, octave)
                else -> null
            } ?: continue
            rt = next.score
            combined = if (combined == null) next else next.copy(
                affectedMeasures = minOf(combined.affectedMeasures.first, next.affectedMeasures.first)..
                    maxOf(combined.affectedMeasures.last, next.affectedMeasures.last),
                selectedAttachmentIds = combined.selectedAttachmentIds + next.selectedAttachmentIds,
            )
        }
        applyExpressionResult(combined) {
            noteTool.cancelInsertionTool()
        }
        return combined != null
    }

    // Note style refresh — incremented when a plugin panel toggles note coloring
    var noteStyleNonce by remember { mutableStateOf(0) }
    // Full render refresh for plugin display preferences that affect annotation layout.
    var pluginRenderNonce by remember { mutableStateOf(0) }
    // Repaint-only refresh for plugin selection overlays; never starts score layout.
    var pluginSelectionOverlayNonce by remember { mutableStateOf(0) }

    // Layout state
    var isSplitView by remember { mutableStateOf(false) }
    var activeReductionId by remember { mutableStateOf<ReductionId?>(null) }
    var reductionSelection by remember { mutableStateOf<Set<EventSection>>(emptySet()) }
    var activeReductionVoiceId by remember { mutableStateOf<TrackId?>(null) }
    var activePlayerId by remember { mutableStateOf<PlayerId?>(null) }
    val deleteReductionSelection: () -> Unit = {
        val reduction = activeReductionId?.let { id ->
            session.runtimeScore?.reductions?.firstOrNull { it.id == id }
        }
        if (reduction != null && reductionSelection.isNotEmpty()) {
            val nested = RuntimeScore.fromStorage(reduction.notationScore)
            val computed = computeScore(nested)
            val deletions = buildDeletions(reductionSelection, nested, computed)
            session.applyReductionNoteDeletes(reduction.id, deletions)
            reductionSelection = emptySet()
        }
    }
    var splitRatio by remember { mutableStateOf(0.5f) }
    var rightPanelWidth by remember { mutableStateOf(MeconDimensions.RightPanelDefaultWidth.dp) }
    var bottomPanelHeight by remember { mutableStateOf(MeconDimensions.BottomPanelDefaultHeight.dp) }
    var pianoRollSideWidth by remember { mutableStateOf(MeconDimensions.RightPanelDefaultWidth.dp) }
    var pianoRollDock by remember { mutableStateOf(PianoRollDock.BOTTOM) }
    var pianoRollChordOverlay by remember { mutableStateOf(false) }
    var isRightCollapsed by remember { mutableStateOf(false) }
    var isBottomCollapsed by remember { mutableStateOf(false) }
    var activeBottomPlugin by remember { mutableStateOf(BottomPlugin.PIANO_ROLL) }
    var refreshKey by remember { mutableStateOf(0) }
    // Current UI language — drives the settings dialog's live selection.
    var language by remember { mutableStateOf(I18nRegistry.getCurrentLanguage()) }
    // Current UI color skin — persisted; applied once up front so there's no flash-then-switch on launch.
    var themeMode by remember { mutableStateOf(AppSettings.themeMode) }
    remember(Unit) { MeconColors.setTheme(themeMode) }

    LaunchedEffect(session.runtimeScore?.reductions) {
        val ids = session.runtimeScore?.reductions.orEmpty().map { it.id }
        if (activeReductionId !in ids) activeReductionId = ids.firstOrNull()
    }
    LaunchedEffect(activeReductionId) {
        reductionSelection = emptySet()
        activeReductionVoiceId = null
    }
    LaunchedEffect(activeReductionId, session.runtimeScore?.reductions) {
        val reduction = activeReductionId?.let { id ->
            session.runtimeScore?.reductions?.firstOrNull { it.id == id }?.migrated()
        }
        val voiceIds = reduction?.notationScore?.staffTracks?.values
            .orEmpty()
            .flatMap { it.voiceTrackIds }
        if (activeReductionVoiceId != null && activeReductionVoiceId !in voiceIds) {
            activeReductionVoiceId = null
        }
    }
    LaunchedEffect(session.runtimeScore?.orchestration?.players) {
        val playerIds = session.runtimeScore?.orchestration?.players.orEmpty().map { it.id }
        if (activePlayerId !in playerIds) activePlayerId = playerIds.firstOrNull()
    }

    // UI scale — persisted; outside key(refreshKey) so it survives language changes
    var uiScale by remember { mutableStateOf(AppSettings.uiScale) }
    val systemDensity = LocalDensity.current
    val scaledDensity = remember(uiScale, systemDensity) {
        Density(systemDensity.density * uiScale, systemDensity.fontScale)
    }

    // Keyboard shortcut focus
    val rootFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { rootFocusRequester.requestFocus() }
    val currentGlobalShortcutHandler = rememberUpdatedState<(KeyEvent) -> Boolean> { event ->
        if (MeconTextInputFocus.hasFocus || event.type != KeyEventType.KeyDown) {
            false
        } else {
            when (KeybindingStore.actionFor(event)) {
                ShortcutAction.NEW_SCORE -> {
                    fileController.requestNewScore { dialogState.showNewScore = true }
                    true
                }
                ShortcutAction.OPEN_SCORE -> {
                    fileController.openFile()
                    true
                }
                ShortcutAction.SAVE_SCORE -> {
                    fileController.saveFile()
                    true
                }
                ShortcutAction.UNDO -> activeHistoryHost?.let { host ->
                    host.undo()
                    true
                } ?: false
                ShortcutAction.REDO -> activeHistoryHost?.let { host ->
                    host.redo()
                    true
                } ?: false
                ShortcutAction.COPY -> eventSelection.isNotEmpty().also { if (it) copySelection() }
                ShortcutAction.CUT -> eventSelection.isNotEmpty().also { if (it) cutSelection() }
                ShortcutAction.PASTE -> eventSelection.isNotEmpty().also { if (it) pasteSelection() }
                ShortcutAction.DELETE -> when {
                    reductionSelection.isNotEmpty() -> {
                        deleteReductionSelection()
                        true
                    }
                    eventSelection.isNotEmpty() -> {
                        deleteSelection()
                        true
                    }
                    else -> false
                }
                else -> false
            }
        }
    }
    DisposableEffect(Unit) {
        GlobalShortcutDispatcher.install { event ->
            currentGlobalShortcutHandler.value(event)
        }
        onDispose { GlobalShortcutDispatcher.clear() }
    }

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
    // Force recomposition when language changes
    key(refreshKey) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MeconColors.Background)
                .focusRequester(rootFocusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (MeconTextInputFocus.hasFocus) {
                        false
                    } else if (noteInput.active) {
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            if (noteInput.entryMode == com.mecon.desktop.input.NoteInputEntryMode.REALTIME) {
                                noteInputController.stopAndExit(commit = true)
                            } else {
                                noteInputController.flushPending()
                                noteInputController.cancel()
                            }
                            eventSelection = emptySet()
                            selectedAnnotationEventId = null
                            true
                        } else if (noteInputController.handle(event)) {
                            true
                        } else if (event.type != KeyEventType.KeyDown) {
                            false
                        } else {
                            handleEditingShortcut(event, session, noteTool, selectionEditor)
                        }
                    // Window preview handles editing commands even when a child control owns focus.
                    // Keep these as a content-focus fallback; Esc remains local to the score workspace.
                    } else if (event.type != KeyEventType.KeyDown) {
                        false
                    } else if (KeybindingStore.actionFor(event) == ShortcutAction.NEW_SCORE) {
                        fileController.requestNewScore { dialogState.showNewScore = true }
                        true
                    } else if (KeybindingStore.actionFor(event) == ShortcutAction.OPEN_SCORE) {
                        fileController.openFile()
                        true
                    } else if (KeybindingStore.actionFor(event) == ShortcutAction.SAVE_SCORE) {
                        fileController.saveFile()
                        true
                    } else if (KeybindingStore.actionFor(event) == ShortcutAction.UNDO) {
                        activeHistoryHost?.let { it.undo(); true } ?: false
                    } else if (KeybindingStore.actionFor(event) == ShortcutAction.REDO) {
                        activeHistoryHost?.let { it.redo(); true } ?: false
                    } else if (KeybindingStore.actionFor(event) == ShortcutAction.NOTE_INPUT) {
                        session.runtimeScore?.let { runtime ->
                            noteTool.cancelInsertionTool()
                            noteInput.activate(runtime, eventSelection, noteTool.activeVoiceNumber)
                        }
                        true
                    } else if (KeybindingStore.actionFor(event) == ShortcutAction.COPY) {
                        copySelection()
                        true
                    } else if (KeybindingStore.actionFor(event) == ShortcutAction.CUT) {
                        cutSelection()
                        true
                    } else if (KeybindingStore.actionFor(event) == ShortcutAction.PASTE) {
                        pasteSelection()
                        true
                    } else if (KeybindingStore.actionFor(event) == ShortcutAction.DELETE) {
                        if (reductionSelection.isNotEmpty()) deleteReductionSelection() else deleteSelection()
                        true
                    } else if (event.key == Key.Escape) {
                        // Esc clears the selection everywhere; from any pen it also returns to SELECT.
                        noteTool.cancelInsertionTool()
                        eventSelection = emptySet()
                        reductionSelection = emptySet()
                        selectedAnnotationEventId = null
                        true
                    } else {
                        handleEditingShortcut(event, session, noteTool, selectionEditor)
                    }
                }
        ) {
            // Top bar — services are handed down; each group calls them directly.
            // Only App-owned composition state stays as callbacks here.
            TopBar(
                session = session,
                historyHost = activeHistoryHost,
                playback = playback,
                fileController = fileController,
                freePracticeController = freePracticeToolbarController.takeIf {
                    activeMenuTab == explorationMenuTab && freePracticeModeActive
                },
                explorationController = explorationToolbarController.takeIf {
                    activeMenuTab == explorationMenuTab
                },
                state = TopBarUiState(
                    activeTab = activeMenuTab,
                    splitView = isSplitView,
                    selection = ToolbarSelectionState(
                        timeCode = eventSelection.lastOrNull()?.timeCode,
                        hasSelection = eventSelection.isNotEmpty() &&
                            eventSelection.none { it is LayoutBreakSection },
                        hasClipboard = noteClipboard?.isEmpty == false ||
                            expressionClipboard?.isEmpty == false,
                    ),
                    measure = MeasureToolbarState(
                        selectedBarlineMeasure =
                            (eventSelection.singleOrNull() as? BarlineSection)?.barline?.measureNumber,
                        hasMeasureSelection = eventSelection.any { it is MeasureStaffSection },
                    ),
                    staffVisibility = StaffVisibilityToolbarState(
                        hideMeasuresEnabled = canHide(hideCellsSelection),
                        hideFollowingEnabled = canHide(hideFollowingSelection),
                        blockedByNotes = hideCellsSelection.isNotEmpty() && !canHide(hideCellsSelection),
                    ),
                    view = ViewToolbarState(
                        mode = scoreViewMode,
                        showMeasureNumbers = session.showMeasureNumbers,
                        splitView = isSplitView,
                    ),
                    analysis = AnalysisToolbarState(
                        hasSelection = eventSelection.any {
                            it is VoiceEventSection || it is VoiceNoteSection
                        },
                        hasReductionSelection = reductionSelection.any {
                            it is VoiceEventSection || it is VoiceNoteSection
                        },
                        reductionCount = session.runtimeScore?.reductions?.size ?: 0,
                        lineCount = session.runtimeScore?.orchestration?.lines?.size ?: 0,
                        selectedReductionId = activeReductionId,
                        orchestrationEnabled = session.runtimeScore?.orchestration != null,
                    ),
                    layoutBreak = LayoutBreakToolbarState(
                        enabled = selectedBreakBoundary != null,
                        selectedKind = selectedBreakKind,
                    ),
                ),
                actions = TopBarActions(
                    selectTab = { activeMenuTab = it },
                    edit = EditToolbarActions(
                        copySelection = copySelection,
                        cutSelection = cutSelection,
                        pasteSelection = pasteSelection,
                    ),
                    document = DocumentToolbarActions(
                        newScore = { fileController.requestNewScore { dialogState.showNewScore = true } },
                        openAudioSettings = { dialogState.showAudioSettings = true },
                        openScoreMetadata = { dialogState.showScoreMetadata = true },
                        openPageSettings = { dialogState.showPageSettings = true },
                        openSettings = { dialogState.showSettings = true },
                        reflow = { dialogState.showReflowConfirm = true },
                    ),
                    measure = MeasureToolbarActions(
                        insertMeasures = insertMeasures@{ count ->
                            val selectedBarline =
                                eventSelection.singleOrNull() as? BarlineSection
                                    ?: return@insertMeasures
                            val after = selectedBarline.barline.measureNumber
                            val side =
                                if (selectedBarline.visualPlacement == BarlineVisualPlacement.SYSTEM_START) {
                                    MeasureEditEngine.BoundaryInsertion.AFTER_BREAK
                                } else {
                                    MeasureEditEngine.BoundaryInsertion.BEFORE_BREAK
                                }
                            session.insertMeasures(after, count, side) {
                                eventSelection = emptySet()
                            }
                        },
                        deleteMeasures = deleteSelection,
                    ),
                    staffVisibility = StaffVisibilityToolbarActions(
                        hideMeasures = { hideStaffCells(hideCellsSelection) },
                        hideFollowingMeasures = { hideStaffCells(hideFollowingSelection) },
                    ),
                    view = ViewToolbarActions(
                        changeMode = { scoreViewMode = it },
                        toggleMeasureNumbers = session::toggleMeasureNumbers,
                        toggleSplitView = { isSplitView = !isSplitView },
                    ),
                    analysis = AnalysisToolbarActions(
                        createReduction = {
                            dialogState.showNewReduction = true
                        },
                        enableOrchestration = {
                            if (session.runtimeScore?.orchestration == null) session.enableOrchestration()
                            dialogState.showOrchestrationSettings = true
                        },
                    ),
                    layoutBreak = LayoutBreakToolbarActions(
                        toggle = toggleLayoutBreak@{ requested ->
                            val boundary = selectedBreakBoundary ?: return@toggleLayoutBreak
                            val runtime = session.runtimeScore ?: return@toggleLayoutBreak
                            val next = requested.takeUnless { it == selectedBreakKind }
                            val updated = LayoutBreakEditEngine.set(runtime, boundary, next)
                                ?: return@toggleLayoutBreak
                            session.applyMeasureEdit(updated, (boundary - 1)..boundary) {
                                eventSelection = next
                                    ?.let { setOf(LayoutBreakSection(boundary, it)) }
                                    .orEmpty()
                                selectedAnnotationEventId = null
                            }
                        },
                    ),
                ),
            )

            // Error message display
            fileController.loadError?.let { error ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFB71C1C))
                        .clickable { fileController.loadError = null }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = error,
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }
            }

            // Transient export status: "exporting…" while writing, then an auto-dismissed success line.
            fileController.exportMessage?.let { message ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MeconColors.SelectedSurface)
                        .clickable { fileController.exportMessage = null }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(text = message, color = MeconColors.TextPrimary, fontSize = 13.sp)
                }
            }

            // Transient banner for a rejected duration edit (e.g. two adjacent notes both grown so
            // they would overlap). Mirrors the load-error bar above; auto-dismisses after a moment.
            editMessage?.let { message ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MeconColors.Surface)
                        .clickable { editMessage = null }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(text = message, color = MeconColors.TextSecondary, fontSize = 13.sp)
                }
            }
            session.analysisMessage?.let { message ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MeconColors.SelectedSurface)
                        .clickable { session.analysisMessage = null }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(text = message, color = MeconColors.TextPrimary, fontSize = 13.sp)
                }
            }

            // Main content area
            val explorationTab = explorationMenuTab
            val analysisTab = i18n("menu.analysis")
            if (activeMenuTab == explorationTab) {
                ExplorationView(
                    playback = playback,
                    onEditableScoreHostChange = { explorationScoreHost = it },
                    initialFreePractice = latestFreePracticeSnapshot,
                    freePracticeOpenGeneration = freePracticeOpenGeneration,
                    onFreePracticeSnapshotChange = {
                        latestFreePracticeSnapshot = it
                        fileController.noteFreePracticeChanged(it)
                    },
                    onFreePracticeModeChange = { freePracticeModeActive = it },
                    onExplorationToolbarControllerChange = { explorationToolbarController = it },
                    onFreePracticeToolbarControllerChange = { freePracticeToolbarController = it },
                    modifier = Modifier.weight(1f),
                )
            } else Column(modifier = Modifier.weight(1f)) {
                if (activeMenuTab == analysisTab) {
                    AnalysisOverviewPanel(
                        session = session,
                        selectedReductionId = activeReductionId,
                        onReductionSelected = { activeReductionId = it },
                    )
                }
                Row(modifier = Modifier.weight(1f)) {
                    AppLeftToolbar(
                    AppLeftToolbarRequest(
                        session = session,
                        noteTool = noteTool,
                        toolbarSelection = LeftToolbarSelectionState(
                            notes = paletteInfo,
                            clef = clefInfo,
                            key = keyInfo,
                            time = timeInfo,
                            barline = barlineInfo,
                        ),
                        selectionEditor = selectionEditor,
                        state = AppLeftToolbarState(
                            selection = { eventSelection },
                            setSelection = { eventSelection = it },
                            selectedAnnotationId = { selectedAnnotationEventId },
                            setSelectedAnnotationId = { selectedAnnotationEventId = it },
                        ),
                        actions = AppLeftToolbarActions(
                            applyExpressionResult = ::applyExpressionResult,
                            selectedExpressionEvents = ::selectedExpressionEvents,
                            staffIdForEvent = ::staffIdForEvent,
                            addHairpin = { type, style -> addSelectedSpan(hairpin = type to style) },
                            addOctave = { type -> addSelectedSpan(octave = type) },
                        ),
                    )
                    )
                    AppCenterWorkspace(
                    AppCenterWorkspaceRequest(
                        document = AppMainScoreDocument(session, fileController),
                        playback = AppMainScorePlayback(playback, currentPositionTicks, playbackState),
                        ui = AppMainScoreUi(
                            noteTool = noteTool,
                            noteInput = noteInput,
                            midiDeviceName = selectedMidiDeviceName,
                            onCycleMidiDevice = midiInput::cycleDevice,
                            noteStyleNonce = noteStyleNonce,
                            pluginRenderNonce = pluginRenderNonce,
                            selectionOverlayNonce = pluginSelectionOverlayNonce,
                            scoreViewMode = scoreViewMode,
                        ),
                        scoreState = AppMainScoreState(
                            geometry = { latestRenderedGeometry },
                            setGeometry = { latestRenderedGeometry = it },
                            selection = { eventSelection },
                            setSelection = { eventSelection = it },
                            selectedAnnotationId = { selectedAnnotationEventId },
                            setSelectedAnnotationId = { selectedAnnotationEventId = it },
                        ),
                        layout = AppCenterLayoutState(
                            isSplitView = isSplitView,
                            splitRatio = { splitRatio },
                            setSplitRatio = { splitRatio = it },
                            bottomPanelHeight = { bottomPanelHeight },
                            setBottomPanelHeight = { bottomPanelHeight = it },
                            pianoRollSideWidth = { pianoRollSideWidth },
                            setPianoRollSideWidth = { pianoRollSideWidth = it },
                            pianoRollDock = { pianoRollDock },
                            setPianoRollDock = { pianoRollDock = it },
                            pianoRollChordOverlay = { pianoRollChordOverlay },
                            bottomCollapsed = { isBottomCollapsed },
                            setBottomCollapsed = { isBottomCollapsed = it },
                            activeBottomPlugin = { activeBottomPlugin },
                            setActiveBottomPlugin = { activeBottomPlugin = it },
                            referenceScore = {
                                activeReductionId?.let { id ->
                                    session.runtimeScore?.reductions?.firstOrNull { it.id == id }
                                        ?.notationScore?.let(RuntimeScore::fromStorage)
                                }
                            },
                            referenceReductionId = { activeReductionId },
                            reductionSelection = { reductionSelection },
                            setReductionSelection = { reductionSelection = it },
                            activeReductionVoiceId = { activeReductionVoiceId },
                            setActiveReductionVoiceId = { activeReductionVoiceId = it },
                            activePlayerId = { activePlayerId },
                            setActivePlayerId = { activePlayerId = it },
                        ),
                        actions = AppMainScoreActions(
                            applyExpressionResult = ::applyExpressionResult,
                            auditionEditedEvent = ::auditionSingleEditedEvent,
                            revealStaff = revealStaff,
                        ),
                    )
                    )
                    // Right panel
                    RightPanel(
                        state = RightPanelUiState(
                            width = rightPanelWidth,
                            collapsed = isRightCollapsed,
                        ),
                        actions = RightPanelActions(
                            toggleCollapse = { isRightCollapsed = !isRightCollapsed },
                            changeWidth = { delta ->
                                val newWidth = rightPanelWidth - delta.dp
                                rightPanelWidth = newWidth.coerceIn(
                                    MeconDimensions.MinPanelWidth.dp,
                                    MeconDimensions.MaxPanelWidth.dp,
                                )
                            },
                        ),
                        selectionContext = selectionInspectorContext(
                            selection = eventSelection,
                            session = session,
                            maxMeasure = maxMeasureForHide,
                        ),
                        selectionActions = selectionInspectorActions(
                            session = session,
                            selection = eventSelection,
                            onSelectionChange = { eventSelection = it },
                            onApplyExpressionResult = ::applyExpressionResult,
                            onAfterEdit = onAfterEdit,
                            revealStaff = revealStaff,
                            deleteSelection = deleteSelection,
                        ),
                        pluginContext = PluginPanelContext(
                            score = score,
                            selection = noteSelection,
                            eventSelection = eventSelection,
                            selectedAnnotationEventId = selectedAnnotationEventId,
                            runtimeScore = session.runtimeScore,
                            targetTimeCode = eventSelection.lastOrNull()?.timeCode,
                            onRequestNoteStyleRecompute = { noteStyleNonce++ },
                            onRequestRender = { pluginRenderNonce++ },
                            onRequestSelectionOverlayRefresh = { pluginSelectionOverlayNonce++ },
                            pianoRollChordOverlayEnabled = pianoRollChordOverlay,
                            onShowPianoRollChords = { enabled ->
                                pianoRollChordOverlay = enabled
                                if (enabled) {
                                    activeBottomPlugin = BottomPlugin.PIANO_ROLL
                                    isBottomCollapsed = false
                                }
                            },
                            onAddPluginEvent = session::addPluginEvent,
                            onUpdatePluginEvent = session::updatePluginEvent,
                            onDeletePluginEvent = session::deletePluginEvent,
                            onReplacePluginEvents = session::replacePluginEvents,
                        ),
                    )
                }
            }
        }
    }

    ApplicationDialogs(
        state = dialogState,
        uiScale = uiScale,
        language = language,
        themeMode = themeMode,
        audioEngine = audioEngine,
        session = session,
        fileController = fileController,
        onUiScaleChanged = { AppSettings.uiScale = it; uiScale = it },
        onLanguageChanged = { selected ->
            AppSettings.language = selected
            I18nRegistry.setLanguage(selected)
            language = selected
            refreshKey++
        },
        onThemeModeChanged = { selected ->
            AppSettings.themeMode = selected
            MeconColors.setTheme(selected)
            themeMode = selected
        },
        deleteMeasures = ::deleteMeasures,
        onCreateReduction = { title, clefs ->
            session.applyStorageEdit { storage ->
                if (storage.reductions.isNotEmpty()) return@applyStorageEdit storage
                val reduction = ReductionEngine.createFixed(storage, title, clefs)
                storage.copy(reductions = listOf(reduction))
            }
            isSplitView = true
        },
        onApplyOrchestration = session::configureOrchestration,
    )
    DocumentSafetyDialogs(fileController)

    } // CompositionLocalProvider
}
