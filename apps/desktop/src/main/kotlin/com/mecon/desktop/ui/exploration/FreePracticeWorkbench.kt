package com.mecon.desktop.ui.exploration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import com.mecon.api.primitive.Fraction
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.toStorage
import com.mecon.api.computed.ComputedScore
import com.mecon.core.engine.computeScore
import com.mecon.desktop.service.HarmonyPracticeScoreHost
import com.mecon.desktop.service.EditableScoreHost
import com.mecon.desktop.service.FreePracticeFileSnapshot
import com.mecon.desktop.service.PlaybackController
import com.mecon.audio.engine.PlaybackState
import com.mecon.desktop.uikit.components.ChordToneLabelMode
import com.mecon.desktop.uikit.components.DeferredHorizontalResizeHandle
import com.mecon.desktop.uikit.components.DeferredVerticalResizeHandle
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.desktop.ui.views.rememberIdentityKey
import com.mecon.exploration.VoicePlanScoreAssembler
import com.mecon.exploration.FreePracticeWritingSettings
import com.mecon.exploration.KeyModeSpec
import com.mecon.features.freepractice.PracticeStructureView
import com.mecon.theory.freepractice.WorkspaceSlotId
import com.mecon.theory.freepractice.withTonalLayoutBaseline
import com.mecon.theory.freepractice.WorkspaceTonalLayoutId
import com.mecon.theory.ModulationKey
import com.mecon.theory.writing.GrandStaffVoiceLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

private data class PracticeScoreSeed(
    val runtime: RuntimeScore,
    val computed: ComputedScore,
)

/**
 * Free-practice composition root. It owns workspace state and command dispatch only; each column
 * lives in a separate component so notation, evaluation, and continuation features can evolve
 * independently.
 */
@Composable
internal fun FreePracticeWorkbench(
    initialSnapshot: FreePracticeFileSnapshot? = null,
    onSnapshotChange: (FreePracticeFileSnapshot) -> Unit = {},
    onEditableScoreHostChange: (EditableScoreHost?) -> Unit,
    playback: PlaybackController,
    onToolbarControllerChange: (FreePracticeToolbarController?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val initialSettings = initialSnapshot?.document?.settings
    val initialKey = initialSettings?.initialKey?.let { key ->
        com.mecon.theory.ModulationKey(
            key.fifths,
            if (key.mode == KeyModeSpec.MAJOR) {
                com.mecon.theory.KeySignatureMode.MAJOR
            } else {
                com.mecon.theory.KeySignatureMode.MINOR
            },
        )
    } ?: com.mecon.theory.ModulationKey(0, com.mecon.theory.KeySignatureMode.MAJOR)
    var currentInitialKey by remember { mutableStateOf(initialKey) }
    var voiceCount by remember { mutableIntStateOf(initialSettings?.polyphonyLimit ?: 4) }
    var staffVoices by remember {
        mutableStateOf(initialSettings?.staffVoices ?: GrandStaffVoiceLayout.defaultFor(voiceCount))
    }
    var writingSettings by remember {
        mutableStateOf(initialSettings?.writing ?: FreePracticeWritingSettings())
    }
    var gridUnit by remember { mutableStateOf(Fraction.EIGHTH) }
    var defaultChordBeats by remember {
        val beats = initialSettings?.defaultChordDuration?.div(Fraction.QUARTER)
        mutableIntStateOf(
            beats?.takeIf { it.denominator == 1 }?.numerator?.coerceIn(1, 16) ?: 1,
        )
    }
    var timeSignatureToolSerial by remember { mutableIntStateOf(0) }
    var timeSignatureToolRequest by remember {
        mutableStateOf<PracticeTimeSignatureToolRequest?>(null)
    }
    var route by remember { mutableStateOf(initialTonalRoute(initialKey)) }
    var inputMode by remember { mutableStateOf(PracticeInputMode.CHORD_TONE) }
    var chordToneMode by remember { mutableStateOf(ChordToneLabelMode.RELATIVE) }
    var showOffKeyIdioms by remember { mutableStateOf(false) }
    var selectedIdiomTargetKey by remember { mutableStateOf<com.mecon.theory.ModulationKey?>(null) }
    var workspace by remember {
        mutableStateOf(
            initialSnapshot?.document?.workspace?.withTonalLayoutBaseline(initialKey)
                ?: initialWorkspace(voiceCount, route.source)
        )
    }
    var selectedSlotId by remember {
        mutableStateOf(workspace.slots.first().id)
    }
    var scoreSelection by remember {
        mutableStateOf<Set<com.mecon.api.interaction.EventSection>>(emptySet())
    }
    val selectedSlot = workspace.slots.indexOfFirst { it.id == selectedSlotId }
        .coerceAtLeast(0)
    var selectedTonalLayoutId by remember {
        mutableStateOf(workspace.tonalLayouts.firstOrNull()?.id)
    }
    var selectedIdiomInstanceId by remember {
        mutableStateOf<com.mecon.theory.freepractice.WorkspaceIdiomInstanceId?>(null)
    }
    var lastUserEditedOnset by remember { mutableStateOf<Fraction?>(null) }
    var scoreGeneration by remember { mutableIntStateOf(0) }
    var operationError by remember {
        mutableStateOf(
            initialSnapshot?.document?.migrationDiagnostics
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString("；") {
                    "槽位 ${it.slotId.value} 的旧和弦“${it.legacySymbol}”无法唯一解析（${it.candidateCount} 个候选）"
                }
        )
    }
    val scope = rememberCoroutineScope()
    val scoreSeed by androidx.compose.runtime.produceState<PracticeScoreSeed?>(
        initialValue = null,
        scoreGeneration,
    ) {
        val snapshot = workspace
        val keySignature = route.source.keySignature
        value = withContext(Dispatchers.Default) {
            val storage = if (scoreGeneration == 0 && initialSnapshot != null) {
                initialSnapshot.score
            } else {
                VoicePlanScoreAssembler.emptyPracticeScore(snapshot, keySignature, staffVoices)
            }
            val runtime = RuntimeScore.fromStorage(storage)
            PracticeScoreSeed(runtime, computeScore(runtime))
        }
    }
    val seedIdentityKey = rememberIdentityKey(scoreSeed?.runtime)
    val host = scoreSeed?.let { seed ->
        remember(scoreGeneration, seedIdentityKey) {
            HarmonyPracticeScoreHost(
                parentScope = scope,
                initialRuntimeScore = seed.runtime,
                initialComputedScore = seed.computed,
                initialWorkspace = workspace,
                polyphonyLimit = voiceCount,
                initialKey = currentInitialKey,
                initialWritingSettings = writingSettings,
                initialStaffVoices = staffVoices,
                initialDocument = initialSnapshot?.document,
            )
        }
    }
    // Timeline gesture callbacks can survive the initial host-loading composition. Keep a stable
    // state cell so even such a callback resolves the host that is current at invocation time.
    val currentHost = androidx.compose.runtime.rememberUpdatedState(host)
    androidx.compose.runtime.DisposableEffect(host) {
        onEditableScoreHostChange(host)
        onDispose {
            onEditableScoreHostChange(null)
            host?.close()
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { onToolbarControllerChange(null) }
    }
    // Follow the workspace the session edits against, not the committed one. Waiting for background
    // jobs to drain used to be the only way to avoid snapping back to the pre-edit state mid-solve,
    // but optimisation passes keep something pending almost continuously, so an undo could leave
    // this panel showing chords the session had already dropped.
    androidx.compose.runtime.LaunchedEffect(host?.documentVersion, host?.practiceWorkspace) {
        if (host != null) {
            val restored = host.practiceWorkspace
            val fallbackIndex = selectedSlot.coerceIn(0, restored.slots.lastIndex)
            workspace = restored
            selectedSlotId = host.practiceSelection.slotId
                ?.takeIf { id -> restored.slots.any { it.id == id } }
                ?: restored.slots[fallbackIndex].id
            selectedTonalLayoutId = host.practiceSelection.tonalLayoutId
                ?.takeIf { id -> restored.tonalLayouts.any { it.id == id } }
                ?: selectedSlotId.let { id -> restored.slots.firstOrNull { it.id == id } }
                    ?.let(restored::selectedTonalLayout)?.id
            selectedIdiomInstanceId = host.practiceSelection.idiomInstanceId
                ?.takeIf { id -> restored.idiomInstances.any { it.id == id } }
        }
    }
    androidx.compose.runtime.LaunchedEffect(
        host?.documentVersion,
        host?.hasPendingWorkspaceCommit,
        voiceCount,
        staffVoices,
        route.source,
        writingSettings,
    ) {
        if (host != null && !host.hasPendingWorkspaceCommit) {
            onSnapshotChange(
                FreePracticeFileSnapshot(
                    document = host.practiceDocument,
                    score = host.runtimeScore.toStorage(),
                    moduleId = initialSnapshot?.moduleId
                        ?: com.mecon.exploration.DEFAULT_FREE_PRACTICE_MODULE_ID,
                )
            )
        }
    }

    fun rebuild(nextVoiceCount: Int = voiceCount, key: com.mecon.theory.ModulationKey = route.source) {
        val activeHost = currentHost.value
        if (activeHost != null) {
            if (!activeHost.rebuildPractice(nextVoiceCount, key)) {
                operationError = "共享自由练习会话未能重建练习。"
                return
            }
            voiceCount = nextVoiceCount
            staffVoices = GrandStaffVoiceLayout.defaultFor(nextVoiceCount)
            route = initialTonalRoute(key)
            currentInitialKey = key
            workspace = activeHost.practiceWorkspace
            selectedSlotId = workspace.slots.first().id
            selectedTonalLayoutId = workspace.tonalLayouts.first().id
            selectedIdiomInstanceId = null
            lastUserEditedOnset = null
            operationError = null
            return
        }
        voiceCount = nextVoiceCount
        staffVoices = GrandStaffVoiceLayout.defaultFor(nextVoiceCount)
        route = initialTonalRoute(key)
        currentInitialKey = key
        workspace = initialWorkspace(nextVoiceCount, key)
        selectedSlotId = workspace.slots.first().id
        selectedTonalLayoutId = workspace.tonalLayouts.first().id
        selectedIdiomInstanceId = null
        lastUserEditedOnset = null
        scoreGeneration += 1
    }

    fun replay(range: com.mecon.features.freepractice.PracticeReplayRange) {
        val activeHost = currentHost.value ?: return
        val timeMap = com.mecon.api.runtime.ScoreTimeMap.from(activeHost.runtimeScore)
        playback.playExcerpt(
            score = activeHost.runtimeScore,
            start = timeMap.timeCodeAt(range.start),
            end = timeMap.timeCodeAt(range.end),
            tempoBpm = range.tempoBpm,
        )
    }

    fun completeWritingOperation(message: String?) {
        val outcome = currentHost.value?.practiceWritingState?.outcome
        operationError = writingOperationError(message, outcome)
    }

    val playbackCommand = host?.practicePlaybackCommand
    androidx.compose.runtime.LaunchedEffect(playbackCommand?.generation) {
        val request = playbackCommand?.playback
        when (request) {
            is com.mecon.features.freepractice.PracticeEditPlayback.Excerpt -> replay(request.range)
            is com.mecon.features.freepractice.PracticeEditPlayback.Audition -> {
                if (playback.playbackState.value != PlaybackState.PLAYING) {
                    playback.auditionMidiNumbers(request.midiNumbers)
                }
            }
            null -> Unit
        }
    }

    fun selectIdiom(id: com.mecon.theory.freepractice.WorkspaceIdiomInstanceId) {
        currentHost.value?.selectIdiom(id)
        val instance = workspace.idiomInstances.firstOrNull { it.id == id } ?: return
        val firstSlot = instance.slotIds.mapNotNull { memberId ->
            workspace.slots.firstOrNull { it.id == memberId }
        }.minByOrNull { it.onset } ?: return
        selectedIdiomInstanceId = id
        selectedSlotId = firstSlot.id
    }

    fun selectHarmonySlot(id: WorkspaceSlotId) {
        currentHost.value?.selectSlot(id)
        selectedSlotId = id
        workspace.slots.firstOrNull { it.id == id }?.let { slot ->
            slot.tonalLayoutId?.let { selectedTonalLayoutId = it }
            val startingIdioms = workspace.idiomInstancesForSlot(slot.id).filter { instance ->
                instance.slotIds.mapNotNull { memberId ->
                    workspace.slots.firstOrNull { it.id == memberId }
                }.minByOrNull { it.onset }?.id == slot.id
            }
            selectedIdiomInstanceId = selectedIdiomInstanceId
                ?.takeIf { selected -> startingIdioms.any { it.id == selected } }
                ?: startingIdioms.firstOrNull()?.id
        }
    }

    fun replaceChordWithOptionalWriting(choice: com.mecon.theory.freepractice.WorkspaceChordChoice) {
        val slot = workspace.slots.getOrNull(selectedSlot) ?: return
        lastUserEditedOnset = slot.onset
        currentHost.value?.replaceChord(slot.id, choice) { message ->
            completeWritingOperation(message)
        }
    }

    fun setChordBassWithOptionalWriting(bassPitchClass: Int?) {
        val slot = workspace.slots.getOrNull(selectedSlot) ?: return
        lastUserEditedOnset = slot.onset
        currentHost.value?.setChordBass(slot.id, bassPitchClass) { message ->
            completeWritingOperation(message)
        }
    }

    val findings = host?.practiceFindings.orEmpty().map(::localizedPracticeFinding)

    val planState = PracticePlanState(
        view = host?.practicePlan ?: com.mecon.features.freepractice.FreePracticeViewProjector.plan(
            workspace = workspace,
            selectedSlotId = selectedSlotId,
            selectedTonalLayoutId = selectedTonalLayoutId,
            catalog = com.mecon.features.freepractice.PracticeCatalogView("desktop-loading", emptyList()),
        ),
        voiceCount = voiceCount,
        staffVoices = staffVoices,
        initialKey = currentInitialKey,
        workspace = workspace,
        selectedSlotId = selectedSlotId,
        selectedIdiomInstanceId = selectedIdiomInstanceId,
        chordToneMode = chordToneMode,
        showOffKeyIdioms = showOffKeyIdioms,
        selectedIdiomTargetKey = selectedIdiomTargetKey,
        insertionOnset = workspace.slots[selectedSlot].onset,
    )
    val planActions = PracticePlanActions(
        changeVoiceCount = ::rebuild,
        changeChordToneMode = { chordToneMode = it },
        changeShowOffKeyIdioms = { enabled ->
            showOffKeyIdioms = enabled
            currentHost.value?.setCatalogFilter(enabled)
            if (!enabled) selectedIdiomTargetKey = null
        },
        selectIdiomTargetKey = { selectedIdiomTargetKey = it },
        selectIdiomTonalLayout = { id ->
            currentHost.value?.selectIdiomTonalLayout(id)
                ?: reportMissingPracticeHost { operationError = it }
        },
        replaceChord = ::replaceChordWithOptionalWriting,
        setChordBass = ::setChordBassWithOptionalWriting,
        setChordTonality = { tonality ->
            val slotId = workspace.slots[selectedSlot].id
            currentHost.value?.setChordTonality(slotId, tonality)
                ?: reportMissingPracticeHost { operationError = it }
        },
        selectChordTonalLayout = { id ->
            val slotId = workspace.slots[selectedSlot].id
            val succeeded = currentHost.value?.selectChordTonalLayout(slotId, id)
                ?: reportMissingPracticeHost { operationError = it }
            if (succeeded) {
                selectedTonalLayoutId = id
                selectedIdiomTargetKey = null
            }
        },
        setPivotChord = { selected ->
            val slotId = workspace.slots[selectedSlot].id
            currentHost.value?.setPivotChord(slotId, selected)
                ?: reportMissingPracticeHost { operationError = it }
        },
        changeStaffVoices = { layout ->
            if (host == null) {
                staffVoices = layout
            } else {
                host.reconfigureGrandStaff(layout) {
                    staffVoices = layout
                }
            }
        },
        changeInitialKey = { key -> rebuild(key = key) },
        changeTonalLayoutKey = { id, key ->
            currentHost.value?.setTonalLayoutKey(id, key)
                ?: reportMissingPracticeHost { operationError = it }
        },
        removeTonalLayout = { id ->
            val succeeded = currentHost.value?.removeTonalLayout(id)
                ?: reportMissingPracticeHost { operationError = it }
            if (succeeded) {
                selectedTonalLayoutId = workspace.tonalLayouts.firstOrNull()?.id
            }
        },
        insertTonalLayout = insertTonalLayout@ { key, terminatePrevious ->
            val slot = workspace.slots[selectedSlot]
            val activeHost = currentHost.value ?: run {
                reportMissingPracticeHost { operationError = it }
                return@insertTonalLayout
            }
            activeHost.insertTonalLayout(
                key = key,
                start = slot.onset,
                end = null,
                terminatePreviousAt = (slot.onset + slot.duration).takeIf { terminatePrevious },
            )?.let { insertedId ->
                activeHost.selectChordTonalLayout(slot.id, insertedId)
                selectedTonalLayoutId = insertedId
            }
        },
        insertIdiom = insertIdiom@ { definitionId, variantId ->
            val activeHost = currentHost.value ?: run {
                reportMissingPracticeHost { operationError = it }
                return@insertIdiom
            }
            activeHost.insertIdiom(
                anchorSlotId = workspace.slots[selectedSlot].id,
                definitionId = definitionId,
                variantId = variantId,
            ) { message ->
                completeWritingOperation(message)
                activeHost.practiceWorkspace.idiomInstances.lastOrNull()?.let { inserted ->
                    selectedIdiomInstanceId = inserted.id
                    inserted.slotIds.firstOrNull()?.let { selectedSlotId = it }
                }
            }
            return@insertIdiom
        },
        selectIdiom = ::selectIdiom,
        replaceIdiom = replaceIdiom@ { instance, definitionId, variantId ->
            val activeHost = currentHost.value ?: run {
                reportMissingPracticeHost { operationError = it }
                return@replaceIdiom
            }
            activeHost.replaceIdiom(instance.id, definitionId, variantId) { message ->
                completeWritingOperation(message)
                activeHost.practiceWorkspace.idiomInstances.firstOrNull { it.id == instance.id }?.let { replaced ->
                    selectedIdiomInstanceId = replaced.id
                    replaced.slotIds.firstOrNull()?.let { selectedSlotId = it }
                }
            }
            return@replaceIdiom
        },
        setIdiomChordToneCount = { instanceId, stepIndex, toneCount ->
            currentHost.value?.setIdiomChordToneCount(instanceId, stepIndex, toneCount) { message ->
                completeWritingOperation(message)
            } ?: reportMissingPracticeHost { operationError = it }
        },
        removeIdiom = { id ->
            val succeeded = currentHost.value?.removeIdiom(id)
                ?: reportMissingPracticeHost { operationError = it }
            if (succeeded) {
                selectedIdiomInstanceId = null
            }
        },
        selectSlot = ::selectHarmonySlot,
        appendChord = appendChord@ {
            val activeHost = currentHost.value ?: run {
                reportMissingPracticeHost { operationError = it }
                return@appendChord
            }
            val lastEnd = workspace.slots.last().let { it.onset + it.duration }
            activeHost.insertChordRange(
                lastEnd,
                Fraction(defaultChordBeats, 4).simplified(),
            )?.let { insertedSlotId ->
                workspace = activeHost.practiceWorkspace
                selectedSlotId = insertedSlotId
            }
        },
        deleteChord = {
            val slotId = workspace.slots[selectedSlot].id
            currentHost.value?.removeChordRange(slotId)
                ?: reportMissingPracticeHost { operationError = it }
        },
    )
    val feedbackState = PracticeFeedbackState(
        findings = findings,
    )
    val feedbackActions = PracticeFeedbackActions
    var workbenchLayout by remember {
        mutableStateOf(FreePracticeWorkbenchLayout.CLASSIC)
    }
    var writingSurface by remember {
        mutableStateOf(FreePracticeWritingSurface.SCORE)
    }
    val editorState = PracticeEditorState(
        workspace = workspace,
        scoreHost = host,
        selectedSlotId = selectedSlotId,
        selectedIdiomInstanceId = selectedIdiomInstanceId,
        staffVoices = staffVoices,
        inputMode = inputMode,
        chordToneMode = chordToneMode,
        idiomTitles = host?.practicePlan?.idiomCatalog?.definitions
            .orEmpty()
            .associate { it.id to it.title },
        gridUnit = gridUnit,
        defaultChordBeats = defaultChordBeats,
        timeSignatureToolRequest = timeSignatureToolRequest,
    )
    val editorActions = PracticeEditorActions(
        selectSlot = ::selectHarmonySlot,
        selectIdiom = ::selectIdiom,
        changeInputMode = { inputMode = it },
        insertChordRange = { onset, duration ->
            val activeHost = currentHost.value
            activeHost?.insertChordRange(onset, duration)?.let { insertedSlotId ->
                workspace = activeHost.practiceWorkspace
                selectedSlotId = insertedSlotId
            } ?: reportMissingPracticeHost { operationError = it }
        },
        commitTimelineEdit = { edit ->
            currentHost.value?.commitTimelineEdit(edit, ::completeWritingOperation) == true
        },
        previewTimelineEdit = { edit ->
            currentHost.value?.previewTimelineEdit(edit)?.takeIf { it.accepted }?.timeline
        },
        reportError = { operationError = it },
        deleteChord = {
            val slotId = workspace.slots[selectedSlot].id
            currentHost.value?.removeChordRange(slotId)
                ?: reportMissingPracticeHost { operationError = it }
        },
        insertTonalLayout = { key, start, end, terminatePreviousAt ->
            currentHost.value?.insertTonalLayout(key, start, end, terminatePreviousAt)?.let {
                selectedTonalLayoutId = it
            } ?: reportMissingPracticeHost { operationError = it }
        },
        selectTonalLayout = { id ->
            currentHost.value?.selectTonalLayout(id)
            selectedTonalLayoutId = id
        },
        setTonalLayoutBounds = { id, start, end ->
            currentHost.value?.setTonalLayoutBounds(id, start, end)
                ?: reportMissingPracticeHost { operationError = it }
        },
        scoreSelectionChanged = { scoreSelection = it },
    )
    androidx.compose.runtime.SideEffect {
        onToolbarControllerChange(
            FreePracticeToolbarController(
                workbenchLayout = workbenchLayout,
                voiceCount = voiceCount,
                staffVoices = staffVoices,
                initialKey = currentInitialKey,
                writingSettings = writingSettings,
                writingState = host?.practiceWritingState
                    ?: com.mecon.desktop.service.PracticeWritingState(),
                selectionTimeCode = scoreSelection.lastOrNull()?.timeCode(),
                hasSelection = scoreSelection.isNotEmpty(),
                gridUnit = gridUnit,
                defaultChordBeats = defaultChordBeats,
                structure = host?.practiceStructure ?: PracticeStructureView(),
                changeWorkbenchLayout = { workbenchLayout = it },
                changeVoiceCount = { rebuild(it) },
                changeStaffVoices = planActions.changeStaffVoices,
                changeInitialKey = { rebuild(key = it) },
                changeWritingSettings = {
                    writingSettings = it
                    currentHost.value?.updateWritingSettings(it)
                },
                changeGridUnit = { gridUnit = it },
                changeDefaultChordBeats = {
                    defaultChordBeats = it
                    currentHost.value?.setDefaultChordDuration(Fraction(it, 4).simplified())
                },
                setTimeSignature = { timeSignature ->
                    currentHost.value?.let { activeHost ->
                        if (activeHost.practiceStructure.pristine) {
                            activeHost.setPracticeTimeSignature(timeSignature)
                        } else {
                            timeSignatureToolSerial += 1
                            timeSignatureToolRequest = PracticeTimeSignatureToolRequest(
                                timeSignatureToolSerial,
                                timeSignature,
                            )
                        }
                    }
                },
                insertMeasures = { position, count, chordBeats ->
                    currentHost.value?.insertPracticeMeasures(
                        position,
                        count,
                        Fraction(chordBeats, 4).simplified(),
                    )
                },
                rewriteSelection = {
                    host?.rewriteSelection { message ->
                        completeWritingOperation(message)
                    }
                },
                alternate = {
                    host?.alternateLastWriting { message ->
                        completeWritingOperation(message)
                    }
                },
                cancelWriting = {
                    host?.cancelWriting()
                },
            ),
        )
    }
    var rightPanelWidth by remember { mutableStateOf(392.dp) }
    var rightPanelPreviewWidth by remember { mutableStateOf<androidx.compose.ui.unit.Dp?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        operationError?.let { message ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                color = MeconColors.ErrorSurface,
                contentColor = MeconColors.OnErrorSurface,
            ) {
                Text(
                    text = "操作未完成，已恢复之前的状态：$message",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
            ) {
                if (workbenchLayout == FreePracticeWorkbenchLayout.CLASSIC) {
                    PracticeEditorPanel(
                        state = editorState,
                        actions = editorActions,
                        playback = playback,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    PartitionedPracticeMain(
                        editor = {
                            PracticeEditorPanel(
                                state = editorState,
                                actions = editorActions,
                                playback = playback,
                                previewLayout = workbenchLayout,
                                writingSurface = writingSurface,
                                onWritingSurfaceChange = { writingSurface = it },
                                modifier = Modifier.fillMaxSize(),
                            )
                        },
                        harmony = {
                            PracticePlanPanel(
                                planState,
                                planActions,
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                showTonality = false,
                                showChordDetails = false,
                                showIdioms = false,
                            )
                        },
                        idioms = {
                            PracticePlanPanel(
                                planState,
                                planActions,
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                showTonality = false,
                                showHarmony = false,
                                showChordDetails = false,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                Box(
                    modifier = Modifier
                        .width(rightPanelWidth)
                        .fillMaxHeight(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        host?.let {
                            PracticeNotePropertiesPanel(
                                host = it,
                                selection = scoreSelection,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        androidx.compose.runtime.key(workbenchLayout) {
                            PracticePlanPanel(
                                planState,
                                planActions,
                                Modifier.fillMaxWidth(),
                                showHarmony = workbenchLayout == FreePracticeWorkbenchLayout.CLASSIC,
                                showIdioms = workbenchLayout == FreePracticeWorkbenchLayout.CLASSIC,
                                chordDetailsInitiallyCollapsed =
                                    workbenchLayout == FreePracticeWorkbenchLayout.CLASSIC,
                            )
                        }
                        PracticeFeedbackPanel(feedbackState, feedbackActions, Modifier.fillMaxWidth())
                    }
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(MeconColors.Border)
                            .zIndex(1f),
                    )
                    DeferredHorizontalResizeHandle(
                        value = rightPanelWidth,
                        minValue = 240.dp,
                        maxValue = 720.dp,
                        inverted = true,
                        onResizePreview = { rightPanelPreviewWidth = it },
                        onResizeEnd = { rightPanelWidth = it },
                        modifier = Modifier.align(Alignment.CenterStart).zIndex(2f),
                    )
                }
            }
            rightPanelPreviewWidth?.let { previewWidth ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(previewWidth)
                        .fillMaxHeight()
                        .zIndex(5f),
                    color = MeconColors.Surface.copy(alpha = 0.62f),
                    border = BorderStroke(1.dp, MeconColors.PrimaryLight),
                    shadowElevation = 12.dp,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .width(2.dp)
                                .height(28.dp)
                                .background(MeconColors.BorderLight),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PartitionedPracticeMain(
    editor: @Composable () -> Unit,
    harmony: @Composable () -> Unit,
    idioms: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var upperRatio by remember { mutableStateOf(0.62f) }
    var previewUpperHeight by remember {
        mutableStateOf<androidx.compose.ui.unit.Dp?>(null)
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val availableHeight = maxHeight
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(upperRatio).fillMaxWidth()) { editor() }
            Box(Modifier.fillMaxWidth()) {
                androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier.align(Alignment.Center),
                    color = MeconColors.Border,
                )
                DeferredVerticalResizeHandle(
                    value = availableHeight * upperRatio,
                    minValue = availableHeight * 0.35f,
                    maxValue = availableHeight * 0.8f,
                    onResizePreview = { previewUpperHeight = it },
                    onResizeEnd = { height ->
                        upperRatio = (height / availableHeight).coerceIn(0.35f, 0.8f)
                    },
                )
            }
            Row(Modifier.weight(1f - upperRatio).fillMaxWidth()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(MeconColors.Surface),
                ) { harmony() }
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MeconColors.Border),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(MeconColors.Surface),
                ) { idioms() }
            }
        }
        previewUpperHeight?.let { upperHeight ->
            Surface(
                modifier = Modifier
                    .offset(y = upperHeight)
                    .fillMaxWidth()
                    .height((availableHeight - upperHeight).coerceAtLeast(0.dp))
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

private fun reportMissingPracticeHost(report: (String) -> Unit): Boolean {
    report("共享自由练习会话尚未就绪")
    return false
}

internal fun writingOperationError(
    message: String?,
    outcome: com.mecon.features.freepractice.PracticeWritingOutcome?,
): String? = message.takeUnless {
    outcome is com.mecon.features.freepractice.PracticeWritingOutcome.Solved
}

/** Long enough to collapse a burst of chord or note edits, short enough to feel immediate. */
private const val FINDINGS_ANALYSIS_DEBOUNCE_MS = 250L
