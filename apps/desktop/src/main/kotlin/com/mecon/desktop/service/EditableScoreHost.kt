package com.mecon.desktop.service

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mecon.api.computed.ComputedScore
import com.mecon.api.interaction.EventSection
import com.mecon.api.interaction.StaffAttachmentSection
import com.mecon.api.interaction.VoiceEventSection
import com.mecon.api.interaction.VoiceSlurSection
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.toStorage
import com.mecon.api.state.RenderHint
import com.mecon.api.state.ScoreState
import com.mecon.api.state.ScoreStateManager
import com.mecon.api.storage.Articulation
import com.mecon.core.engine.computeScore
import com.mecon.core.engine.edit.ExpressionEditEngine
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.core.engine.edit.PolyphonyLimitValidation
import com.mecon.core.engine.edit.PolyphonyLimitValidator
import com.mecon.desktop.voiceTrackIdOf
import com.mecon.exploration.VoicePlanScoreAssembler
import com.mecon.exploration.FreePracticeDocument
import com.mecon.exploration.FreePracticeSettings
import com.mecon.exploration.FreePracticeWritingSettings
import com.mecon.exploration.KeyModeSpec
import com.mecon.exploration.KeySpec
import com.mecon.features.freepractice.FreePracticeBackgroundExecutor
import com.mecon.features.freepractice.FreePracticeDispatchResult
import com.mecon.features.freepractice.FreePracticeEffectKind
import com.mecon.features.freepractice.FreePracticeIntent
import com.mecon.api.primitive.TimeSignature
import com.mecon.features.freepractice.FreePracticeSession
import com.mecon.features.freepractice.FreePracticeSelection
import com.mecon.features.freepractice.PracticeBackgroundFailure
import com.mecon.features.freepractice.PracticeBackgroundRequest
import com.mecon.features.freepractice.PracticeBackgroundResult
import com.mecon.features.freepractice.PracticeWritingOutcome
import com.mecon.features.freepractice.PracticeWritingPhase
import com.mecon.features.freepractice.PracticeFindingView
import com.mecon.features.freepractice.PracticeFindingExecutor
import com.mecon.features.freepractice.PracticeFindingRequest
import com.mecon.features.freepractice.PracticeIdiomCatalogView
import com.mecon.features.freepractice.PracticeEditPlayback
import com.mecon.features.freepractice.PracticePlanView
import com.mecon.features.freepractice.PracticeTeachingCatalogExecutor
import com.mecon.features.freepractice.PracticeTeachingCatalogRequest
import com.mecon.features.freepractice.PracticeTimelineEdit
import com.mecon.features.freepractice.PracticeTimelineView
import com.mecon.features.freepractice.PracticeTimelinePreviewRequest
import com.mecon.features.freepractice.PracticeTimelinePreviewResult
import com.mecon.features.freepractice.PracticeStructureView
import com.mecon.features.freepractice.autoWritingTriggerSlotId
import com.mecon.features.scoreediting.ScoreEditEffectKind
import com.mecon.features.scoreediting.ScoreEditIntent
import com.mecon.features.scoreediting.ScoreNoteInputTransition
import com.mecon.features.scoreediting.ScoreSelectionTarget
import com.mecon.features.scoreediting.eventIdOrNull
import com.mecon.theory.freepractice.WorkspaceChordChoice
import com.mecon.theory.freepractice.HarmonyPracticeTransaction
import com.mecon.theory.freepractice.HarmonyWorkspaceState
import com.mecon.theory.freepractice.HarmonyWorkspaceStateController
import com.mecon.theory.freepractice.WorkspaceSlotId
import com.mecon.theory.freepractice.VoiceAssignmentSource
import com.mecon.theory.ModulationKey
import com.mecon.theory.SearchCancellation
import com.mecon.theory.writing.AutomaticNotationAssigner
import com.mecon.theory.writing.NotationEventSpan
import com.mecon.theory.writing.NotationLaneSpec
import com.mecon.theory.writing.PendingNotationNote
import com.mecon.theory.writing.GrandStaffVoiceLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

data class PracticeWritingState(
    val running: Boolean = false,
    val message: String? = null,
    val outcome: PracticeWritingOutcome? = null,
    val canAlternate: Boolean = false,
    val lastScope: List<WorkspaceSlotId> = emptyList(),
)

data class PracticePlaybackCommand(
    val generation: Long,
    val playback: PracticeEditPlayback,
)

/**
 * Minimal score-editing contract shared by the document editor and embedded workbenches.
 *
 * File identity, reductions, playback and document containers intentionally stay outside this
 * contract.
 */
interface EditableScoreHost {
    val runtimeScore: RuntimeScore?
    val computedScore: ComputedScore?
    val renderHint: RenderHint?
    val documentVersion: Long
    val canUndo: Boolean
    val canRedo: Boolean

    fun undo()
    fun redo()

    fun applyNoteEdit(
        insertion: NoteEditEngine.Insertion,
        onInputTransition: (ScoreNoteInputTransition) -> Unit = {},
        onInserted: (EventSection, RuntimeScore) -> Unit = { _, _ -> },
    )
}

/**
 * Note-editing operations shared by the main document editor and embedded notation workbenches.
 *
 * Selection projection and toggle semantics live in the desktop input layer; hosts only commit the
 * immutable engine result to their own history/state boundary.
 */
interface EditableNoteHost : EditableScoreHost {
    fun applyNoteDeletes(
        deletions: List<NoteEditEngine.Deletion>,
        onAfter: (Set<EventSection>) -> Unit = {},
    )

    fun applyNoteTranspose(
        targets: List<NoteEditEngine.TransposeTarget>,
        stepDelta: Int,
        onAfter: (Set<EventSection>) -> Unit = {},
    )

    fun applyRestMove(
        targets: List<NoteEditEngine.RestMoveTarget>,
        onAfter: (Set<EventSection>) -> Unit = {},
    )

    fun applyVoiceMove(
        targets: List<NoteEditEngine.VoiceMoveTarget>,
        onAfter: (Set<EventSection>) -> Unit = {},
    )

    fun applyDurationEdits(
        edits: List<NoteEditEngine.DurationEdit>,
        onConflict: () -> Unit = {},
        onAfter: (Set<EventSection>) -> Unit = {},
    )

    fun applyTupletEdit(
        edits: List<NoteEditEngine.TupletEdit>,
        onConflict: () -> Unit = {},
        onAfter: (Set<EventSection>) -> Unit = {},
    )

    fun applySmallNoteEdits(
        edits: List<NoteEditEngine.SmallNoteEdit>,
        onConflict: () -> Unit = {},
        onAfter: (Set<EventSection>) -> Unit = {},
    )

    fun applyAccidentalEdit(
        edits: List<NoteEditEngine.AccidentalEdit>,
        onAfter: (Set<EventSection>) -> Unit = {},
    )

    fun applyTieEdit(
        edits: List<NoteEditEngine.TieEdit>,
        onAfter: (Set<EventSection>) -> Unit = {},
    )

    fun applyBeamingEdit(
        edits: List<NoteEditEngine.BeamingEdit>,
        onAfter: (Set<EventSection>) -> Unit = {},
    )

    fun addSlurs(
        targets: List<NoteEditEngine.SlurTarget>,
        onAfter: (Set<EventSection>) -> Unit = {},
    )

    fun toggleArticulation(
        targets: List<ExpressionEditEngine.NoteTarget>,
        articulation: Articulation,
        onAfter: (Set<EventSection>) -> Unit = {},
    )
}

sealed interface PracticeEditRejection {
    val message: String

    data class PolyphonyLimitExceeded(
        val validation: PolyphonyLimitValidation,
    ) : PracticeEditRejection {
        override val message: String =
            "同一时间最多允许 ${validation.limit} 个音，当前将达到 ${validation.peak} 个音。"
    }

    data object NoAvailableNotationLane : PracticeEditRejection {
        override val message: String = "当前没有可用的自动记谱通道，请先调整现有音符或谱表通道。"
    }

    data object InvalidInsertion : PracticeEditRejection {
        override val message: String = "无法在该位置写入音符，请检查时值与乐谱范围。"
    }
}

private class SharedWorkspaceController(
    private val session: FreePracticeSession,
) {
    val state: HarmonyWorkspaceState get() = session.workspaceState
}

private class SharedPracticeTransaction(
    private val session: FreePracticeSession,
) {
    fun commit(
        runtimeScore: RuntimeScore,
        computedScore: ComputedScore,
        workspaceState: HarmonyWorkspaceState,
    ): Boolean = session.commitExternal(runtimeScore, computedScore, workspaceState)
}

/**
 * Embedded score host whose score and harmony workspace share one undo/redo boundary.
 */
class HarmonyPracticeScoreHost(
    parentScope: CoroutineScope,
    initialRuntimeScore: RuntimeScore,
    initialComputedScore: ComputedScore,
    initialWorkspace: HarmonyWorkspaceState,
    private var polyphonyLimit: Int = initialWorkspace.voices.size,
    initialKey: ModulationKey = ModulationKey(0, com.mecon.theory.KeySignatureMode.MAJOR),
    initialWritingSettings: FreePracticeWritingSettings = FreePracticeWritingSettings(),
    initialStaffVoices: GrandStaffVoiceLayout = GrandStaffVoiceLayout.defaultFor(initialWorkspace.voices.size),
    initialDocument: FreePracticeDocument? = null,
) : EditableNoteHost {
    /**
     * Writing, findings and catalog work run as independent jobs: a failure in one background pass
     * must be reported, not take the practice session's remaining interactions down with it.
     */
    private val scope: CoroutineScope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]),
    )
    private val manager = ScoreStateManager(initialRuntimeScore, initialComputedScore)
    private val freePracticeSession = FreePracticeSession.open(
        initialDocument?.copy(workspace = initialWorkspace) ?: FreePracticeDocument(
            settings = FreePracticeSettings(
                polyphonyLimit = polyphonyLimit,
                staffVoices = initialStaffVoices,
                initialKey = KeySpec(
                    initialKey.fifths,
                    if (initialKey.mode == com.mecon.theory.KeySignatureMode.MAJOR) {
                        KeyModeSpec.MAJOR
                    } else {
                        KeyModeSpec.MINOR
                    },
                ),
                writing = initialWritingSettings,
            ),
            workspace = initialWorkspace,
        ),
        manager,
    )
    private val workspaceController = SharedWorkspaceController(freePracticeSession)
    private val transaction = SharedPracticeTransaction(freePracticeSession)
    private val editMutex = Mutex()
    private val writingGeneration = AtomicLong(0L)
    private val findingGeneration = AtomicLong(0L)

    private var state: ScoreState by mutableStateOf(manager.currentState)
    private var pendingWorkspaceCommits: Int by mutableIntStateOf(0)
    var practiceWritingState: PracticeWritingState by mutableStateOf(PracticeWritingState())
        private set
    var practiceFindings: List<PracticeFindingView> by mutableStateOf(freePracticeSession.frame().findings.items)
        private set
    var practiceIdiomCatalog: PracticeIdiomCatalogView by mutableStateOf(
        freePracticeSession.frame().plan.idiomCatalog
    )
        private set
    var practicePlan: PracticePlanView by mutableStateOf(freePracticeSession.frame().plan)
        private set
    var practiceSelection: FreePracticeSelection by mutableStateOf(freePracticeSession.frame().selection)
        private set
    var practiceStructure: PracticeStructureView by mutableStateOf(freePracticeSession.frame().structure)
        private set
    var practiceTimeline: PracticeTimelineView by mutableStateOf(freePracticeSession.frame().timeline)
        private set
    var practiceNoteConstraints: com.mecon.features.freepractice.PracticeNoteConstraintView by mutableStateOf(
        freePracticeSession.frame().noteConstraints
    )
        private set
    var practicePlaybackCommand: PracticePlaybackCommand? by mutableStateOf(null)
        private set
    private var practicePlaybackGeneration = 0L

    /**
     * The workspace the session itself edits against — the pending auto-writing request while one is
     * in flight, the committed state otherwise (see `FreePracticeSession.editBase`).
     *
     * [workspace] is the committed state alone, so it disagrees with the session for as long as a
     * background job runs. A shell that renders it has to hold it back until nothing is pending,
     * and then an undo during a solve (or during the optimisation pass that follows it) leaves the
     * timeline drawing chords the session no longer has: the next drag resolves against a different
     * origin, so its preview lands where the stale chord already was and the gesture looks dead.
     */
    var practiceWorkspace: HarmonyWorkspaceState by mutableStateOf(freePracticeSession.frame().document.workspace)
        private set
    var practiceDocument: FreePracticeDocument by mutableStateOf(freePracticeSession.frame().document)
        private set
    override var documentVersion: Long by mutableStateOf(0L)
        private set

    override val runtimeScore: RuntimeScore get() = state.runtimeScore
    override val computedScore: ComputedScore get() = state.computedScore
    override val renderHint: RenderHint? get() = state.renderHint
    override var canUndo: Boolean by mutableStateOf(manager.canUndo())
        private set
    override var canRedo: Boolean by mutableStateOf(manager.canRedo())
        private set
    val workspace: HarmonyWorkspaceState get() = workspaceController.state
    val hasPendingWorkspaceCommit: Boolean get() = pendingWorkspaceCommits > 0

    init {
        val initial = freePracticeSession.initialUpdate()
        launchTeachingCatalog(initial.catalogRequests)
        launchFindings(initial.findingRequests)
    }

    override fun undo() {
        writingGeneration.incrementAndGet()
        practiceWritingState = PracticeWritingState(message = "写作已取消。")
        manager.undo()
        freePracticeSession.notifyExternalHistoryChange()
        publishCurrentState()
    }

    override fun redo() {
        writingGeneration.incrementAndGet()
        practiceWritingState = PracticeWritingState(message = "写作已取消。")
        manager.redo()
        freePracticeSession.notifyExternalHistoryChange()
        publishCurrentState()
    }

    fun commit(
        runtimeScore: RuntimeScore,
        computedScore: ComputedScore,
        workspace: HarmonyWorkspaceState,
    ) {
        writingGeneration.incrementAndGet()
        practiceWritingState = PracticeWritingState()
        if (transaction.commit(runtimeScore, computedScore, workspace)) {
            publishCurrentState()
        }
    }

    /**
     * Commits a workspace-only timeline edit after extending the notation score on a worker.
     * This keeps the staff preview and piano-roll time domain on the same measure structure.
     */
    fun commitWorkspace(workspace: HarmonyWorkspaceState) {
        writingGeneration.incrementAndGet()
        practiceWritingState = PracticeWritingState()
        pendingWorkspaceCommits += 1
        scope.launch {
            try {
                editMutex.withLock {
                    val current = state
                    val synchronizedRuntime = VoicePlanScoreAssembler.ensureTimelineMeasures(
                        current.runtimeScore,
                        workspace,
                    )
                    val synchronizedComputed = if (synchronizedRuntime === current.runtimeScore) {
                        current.computedScore
                    } else {
                        withContext(Dispatchers.Default) { computeScore(synchronizedRuntime) }
                    }
                    val changed = transaction.commit(
                        runtimeScore = synchronizedRuntime,
                        computedScore = synchronizedComputed,
                        workspaceState = workspace,
                    )
                    if (changed) publishCurrentState()
                }
            } finally {
                pendingWorkspaceCommits -= 1
            }
        }
    }

    fun commitWorkspaceWithAutoWriting(
        workspace: HarmonyWorkspaceState,
        triggerSlotId: WorkspaceSlotId,
        configuredBacktrack: Int,
        fallbackKey: ModulationKey,
        requiredSlotIds: List<WorkspaceSlotId>? = null,
        onComplete: (String?) -> Unit = {},
    ) {
        val generation = writingGeneration.incrementAndGet()
        pendingWorkspaceCommits += 1
        val requested = freePracticeSession.requestWritingForWorkspace(
            nextWorkspace = workspace,
            triggerSlotId = triggerSlotId,
            configuredBacktrack = configuredBacktrack,
            requiredSlotIds = requiredSlotIds,
        )
        publishWritingFrame(requested)
        val request = requested.requests.singleOrNull()
        if (request == null) {
            pendingWorkspaceCommits -= 1
            onComplete(practiceWritingState.message)
            return
        }
        scope.launch {
            val applied = try {
                val background = solveInBackground(request, generation)
                editMutex.withLock {
                    if (generation != writingGeneration.get()) return@withLock null
                    background?.let {
                        freePracticeSession.applyBackgroundResult(it).also(::publishWritingFrame)
                    }
                }
            } finally {
                pendingWorkspaceCommits -= 1
            }
            if (generation == writingGeneration.get()) {
                onComplete(practiceWritingState.message)
                applied?.requests?.forEach { launchOptimization(generation, it) }
            }
        }
    }

    fun rewriteSelection(
        onComplete: (String?) -> Unit = {},
    ) {
        val generation = writingGeneration.incrementAndGet()
        val requested = freePracticeSession.dispatch(
            FreePracticeIntent.RewriteSelection(
                freePracticeSession.frame().revision,
            )
        )
        publishWritingFrame(requested)
        val request = requested.requests.singleOrNull()
        if (request == null) {
            onComplete(practiceWritingState.message)
            return
        }
        scope.launch {
            val background = solveInBackground(request, generation)
            val applied = editMutex.withLock {
                if (generation != writingGeneration.get()) return@withLock null
                background?.let {
                    freePracticeSession.applyBackgroundResult(it).also(::publishWritingFrame)
                }
            }
            if (generation == writingGeneration.get()) {
                onComplete(practiceWritingState.message)
                applied?.requests?.forEach { launchOptimization(generation, it) }
            }
        }
    }

    fun alternateLastWriting(onComplete: (String?) -> Unit = {}) {
        writingGeneration.incrementAndGet()
        val result = freePracticeSession.dispatch(
            FreePracticeIntent.AlternateWriting(freePracticeSession.frame().revision)
        )
        publishWritingFrame(result)
        onComplete(practiceWritingState.message)
    }

    fun cancelWriting(): Boolean {
        writingGeneration.incrementAndGet()
        val result = freePracticeSession.dispatch(
            FreePracticeIntent.CancelWriting(freePracticeSession.frame().revision)
        )
        publishWritingFrame(result)
        return result.effect.kind == FreePracticeEffectKind.WRITING_CANCELLED
    }

    fun replaceChord(
        slotId: WorkspaceSlotId,
        chordChoice: WorkspaceChordChoice?,
        onComplete: (String?) -> Unit = {},
    ) = dispatchWorkspaceIntent(
        FreePracticeIntent.ReplaceChord(freePracticeSession.frame().revision, slotId, chordChoice),
        onComplete,
    )

    fun setChordBass(
        slotId: WorkspaceSlotId,
        bassPitchClass: Int?,
        onComplete: (String?) -> Unit = {},
    ) = dispatchWorkspaceIntent(
        FreePracticeIntent.SetChordBass(freePracticeSession.frame().revision, slotId, bassPitchClass),
        onComplete,
    )

    fun setChordTonality(slotId: WorkspaceSlotId, tonality: com.mecon.theory.freepractice.WorkspaceChordTonality?): Boolean =
        dispatchImmediate { revision -> FreePracticeIntent.SetChordTonality(revision, slotId, tonality) }

    fun selectSlot(slotId: WorkspaceSlotId): Boolean =
        dispatchImmediate { revision -> FreePracticeIntent.SelectSlot(revision, slotId) }

    fun selectTonalLayout(tonalLayoutId: com.mecon.theory.freepractice.WorkspaceTonalLayoutId): Boolean =
        dispatchImmediate { revision -> FreePracticeIntent.SelectTonalLayout(revision, tonalLayoutId) }

    fun selectIdiomTonalLayout(tonalLayoutId: com.mecon.theory.freepractice.WorkspaceTonalLayoutId): Boolean =
        dispatchImmediate { revision -> FreePracticeIntent.SelectIdiomTonalLayout(revision, tonalLayoutId) }

    fun selectIdiom(idiomInstanceId: com.mecon.theory.freepractice.WorkspaceIdiomInstanceId): Boolean =
        dispatchImmediate { revision -> FreePracticeIntent.SelectIdiom(revision, idiomInstanceId) }

    fun selectChordTonalLayout(
        slotId: WorkspaceSlotId,
        tonalLayoutId: com.mecon.theory.freepractice.WorkspaceTonalLayoutId,
    ): Boolean = dispatchImmediate { revision ->
        FreePracticeIntent.SelectChordTonalLayout(revision, slotId, tonalLayoutId)
    }

    fun setPivotChord(slotId: WorkspaceSlotId, selected: Boolean): Boolean =
        dispatchImmediate { revision -> FreePracticeIntent.SetPivotChord(revision, slotId, selected) }

    fun setTonalLayoutKey(
        tonalLayoutId: com.mecon.theory.freepractice.WorkspaceTonalLayoutId,
        key: ModulationKey,
    ): Boolean = dispatchImmediate { revision ->
        FreePracticeIntent.SetTonalLayoutKey(
            revision,
            tonalLayoutId,
            key.fifths,
            com.mecon.theory.freepractice.WorkspaceKeyMode.fromTheory(key.mode),
        )
    }

    fun removeTonalLayout(tonalLayoutId: com.mecon.theory.freepractice.WorkspaceTonalLayoutId): Boolean =
        dispatchImmediate { revision -> FreePracticeIntent.RemoveTonalLayout(revision, tonalLayoutId) }

    fun insertChordRange(onset: Fraction, duration: Fraction): WorkspaceSlotId? {
        writingGeneration.incrementAndGet()
        val before = workspace.slots.mapTo(hashSetOf()) { it.id }
        val result = freePracticeSession.dispatch(
            FreePracticeIntent.InsertChordRange(freePracticeSession.frame().revision, onset, duration)
        )
        publishWritingFrame(result)
        return result.frame.document.workspace.slots.firstOrNull { it.id !in before }?.id
    }

    fun removeChordRange(slotId: WorkspaceSlotId): Boolean =
        dispatchImmediate { revision -> FreePracticeIntent.RemoveChordRange(revision, slotId) }

    fun setDefaultChordDuration(duration: Fraction): Boolean = dispatchImmediate { revision ->
        FreePracticeIntent.SetDefaultChordDuration(revision, duration)
    }

    fun setPracticeTimeSignature(timeSignature: TimeSignature): Boolean =
        dispatchImmediate { revision ->
            FreePracticeIntent.SetPracticeTimeSignature(revision, timeSignature)
        }

    /** Main-editor time-signature pen path: a clicked measure becomes an inner score intent. */
    fun applyPracticeTimeSignatureEdit(
        measureNumber: Int,
        timeSignature: TimeSignature,
        onAfter: (Set<EventSection>) -> Unit = {},
    ) {
        dispatchScoreEdit(
            intent = { revision ->
                ScoreEditIntent.SetTimeSignature(revision, measureNumber, timeSignature)
            },
        ) { result ->
            if (result.effect.kind == FreePracticeEffectKind.APPLIED) {
                val score = result.frame.score
                val targets = score.selection.filterIsInstance<ScoreSelectionTarget.TimeSignature>()
                onAfter(score.computedScore.timeSignatures.filter { signature ->
                    targets.any { target ->
                        target.staffTrackId == signature.staffTrackId && target.onset == signature.time
                    }
                }.mapTo(linkedSetOf()) { signature ->
                    com.mecon.api.interaction.TimeSignatureSection(signature)
                })
            }
        }
    }

    fun insertPracticeMeasures(
        position: FreePracticeIntent.MeasureInsertionPosition,
        count: Int,
        chordDuration: Fraction,
    ): Boolean = dispatchImmediate { revision ->
        FreePracticeIntent.InsertPracticeMeasures(revision, position, count, chordDuration)
    }

    /**
     * Returns whether the session accepted the edit. Callers showing a gesture preview keep it on
     * screen until the accepted commit reaches them, so a rejected edit has to be reported here.
     */
    fun commitTimelineEdit(
        edit: PracticeTimelineEdit,
        onComplete: (String?) -> Unit = {},
    ): Boolean {
        // One intent for every timeline gesture: the session owns which of them may trigger a
        // re-solve, so this adapter no longer restates the edit-to-intent mapping.
        if (edit.autoWritingTriggerSlotId == null) {
            val accepted = dispatchImmediate { revision -> FreePracticeIntent.TimelineEdit(revision, edit) }
            onComplete(practiceWritingState.message)
            return accepted
        }
        return dispatchWorkspaceIntent(
            FreePracticeIntent.TimelineEdit(freePracticeSession.frame().revision, edit),
            onComplete,
        )
    }

    fun previewTimelineEdit(
        edit: PracticeTimelineEdit,
        requestId: Long = System.nanoTime(),
    ): PracticeTimelinePreviewResult = freePracticeSession.previewTimelineEdit(
        PracticeTimelinePreviewRequest(
            requestId = requestId,
            baseRevision = freePracticeSession.frame().revision,
            edit = edit,
        ),
    )

    fun insertTonalLayout(
        key: ModulationKey,
        start: Fraction,
        end: Fraction?,
        terminatePreviousAt: Fraction?,
    ): com.mecon.theory.freepractice.WorkspaceTonalLayoutId? {
        writingGeneration.incrementAndGet()
        val before = workspace.tonalLayouts.mapTo(hashSetOf()) { it.id }
        val result = freePracticeSession.dispatch(
            FreePracticeIntent.InsertTonalLayout(
                expectedRevision = freePracticeSession.frame().revision,
                fifths = key.fifths,
                mode = com.mecon.theory.freepractice.WorkspaceKeyMode.fromTheory(key.mode),
                start = start,
                end = end,
                terminatePreviousAt = terminatePreviousAt,
            )
        )
        publishWritingFrame(result)
        return result.frame.document.workspace.tonalLayouts.firstOrNull { it.id !in before }?.id
    }

    fun setTonalLayoutBounds(
        tonalLayoutId: com.mecon.theory.freepractice.WorkspaceTonalLayoutId,
        start: Fraction,
        end: Fraction?,
    ): Boolean = dispatchImmediate { revision ->
        FreePracticeIntent.TimelineEdit(
            revision,
            PracticeTimelineEdit.SetTonalLayoutBounds(tonalLayoutId, start, end),
        )
    }

    fun insertIdiom(
        anchorSlotId: WorkspaceSlotId,
        definitionId: String,
        variantId: String,
        onComplete: (String?) -> Unit = {},
    ) = dispatchWorkspaceIntent(
        FreePracticeIntent.InsertIdiom(
            freePracticeSession.frame().revision,
            anchorSlotId,
            definitionId,
            variantId,
        ),
        onComplete,
    )

    fun insertVoiceLeadingChord(
        sourceSlotId: WorkspaceSlotId,
        targetPitchClasses: List<Int>,
        pathIndex: Int,
        onComplete: (String?) -> Unit = {},
    ) = dispatchWorkspaceIntent(
        FreePracticeIntent.InsertVoiceLeadingChord(
            expectedRevision = freePracticeSession.frame().revision,
            sourceSlotId = sourceSlotId,
            targetPitchClasses = targetPitchClasses,
            pathIndex = pathIndex,
        ),
        onComplete,
    )

    fun replaceIdiom(
        idiomInstanceId: com.mecon.theory.freepractice.WorkspaceIdiomInstanceId,
        definitionId: String,
        variantId: String,
        onComplete: (String?) -> Unit = {},
    ) = dispatchWorkspaceIntent(
        FreePracticeIntent.ReplaceIdiom(
            freePracticeSession.frame().revision,
            idiomInstanceId,
            definitionId,
            variantId,
        ),
        onComplete,
    )

    fun setIdiomChordToneCount(
        idiomInstanceId: com.mecon.theory.freepractice.WorkspaceIdiomInstanceId,
        stepIndex: Int,
        toneCount: Int,
        onComplete: (String?) -> Unit = {},
    ) = dispatchWorkspaceIntent(
        FreePracticeIntent.SetIdiomChordToneCount(
            freePracticeSession.frame().revision,
            idiomInstanceId,
            stepIndex,
            toneCount,
        ),
        onComplete,
    )

    fun removeIdiom(idiomInstanceId: com.mecon.theory.freepractice.WorkspaceIdiomInstanceId): Boolean =
        dispatchImmediate { revision -> FreePracticeIntent.RemoveIdiom(revision, idiomInstanceId) }

    fun updateWritingSettings(settings: FreePracticeWritingSettings) {
        publishWritingFrame(
            freePracticeSession.dispatch(
                FreePracticeIntent.UpdateWritingSettings(freePracticeSession.frame().revision, settings)
            )
        )
    }

    fun setCatalogFilter(includeOffKey: Boolean): Boolean =
        dispatchImmediate { revision -> FreePracticeIntent.SetCatalogFilter(revision, includeOffKey) }

    fun setHarmonicRole(
        noteheads: Set<com.mecon.exploration.PracticeNoteheadRef>,
        role: com.mecon.exploration.PracticeHarmonicRole?,
    ): Boolean = dispatchImmediate { revision ->
        FreePracticeIntent.SetHarmonicRole(revision, noteheads, role)
    }

    fun setHarmonicRoleFilters(chords: Boolean, idioms: Boolean): Boolean =
        dispatchImmediate { revision -> FreePracticeIntent.SetHarmonicRoleFilters(revision, chords, idioms) }

    fun setNoteheadLock(
        noteheads: Set<com.mecon.exploration.PracticeNoteheadRef>,
        locked: Boolean,
    ): Boolean = dispatchImmediate { revision ->
        FreePracticeIntent.SetNoteheadLock(revision, noteheads, locked)
    }

    fun setVoiceLock(voiceTrackId: TrackId, locked: Boolean): Boolean =
        dispatchImmediate { revision -> FreePracticeIntent.SetVoiceLock(revision, voiceTrackId, locked) }

    fun setVoiceLocks(voiceTrackIds: Set<TrackId>, locked: Boolean): Boolean =
        dispatchImmediate { revision -> FreePracticeIntent.SetVoiceLocks(revision, voiceTrackIds, locked) }

    fun setStaffLock(staffTrackId: TrackId, locked: Boolean): Boolean =
        dispatchImmediate { revision -> FreePracticeIntent.SetStaffLock(revision, staffTrackId, locked) }

    fun setStaffLocks(staffTrackIds: Set<TrackId>, locked: Boolean): Boolean =
        dispatchImmediate { revision -> FreePracticeIntent.SetStaffLocks(revision, staffTrackIds, locked) }

    fun rebuildPractice(polyphonyLimit: Int, key: ModulationKey): Boolean {
        writingGeneration.incrementAndGet()
        val result = freePracticeSession.dispatch(
            FreePracticeIntent.RebuildPractice(
                expectedRevision = freePracticeSession.frame().revision,
                polyphonyLimit = polyphonyLimit,
                fifths = key.fifths,
                mode = com.mecon.theory.freepractice.WorkspaceKeyMode.fromTheory(key.mode),
            )
        )
        publishWritingFrame(result)
        val rebuilt = result.effect.kind == FreePracticeEffectKind.PRACTICE_REBUILT
        if (rebuilt) this.polyphonyLimit = polyphonyLimit
        return rebuilt
    }

    private fun dispatchImmediate(intent: (Long) -> FreePracticeIntent): Boolean {
        writingGeneration.incrementAndGet()
        val result = freePracticeSession.dispatch(intent(freePracticeSession.frame().revision))
        publishWritingFrame(result)
        return result.effect.kind == FreePracticeEffectKind.APPLIED ||
            result.effect.kind == FreePracticeEffectKind.SELECTION_CHANGED ||
            result.effect.kind == FreePracticeEffectKind.PRACTICE_REBUILT ||
            result.effect.kind == FreePracticeEffectKind.NO_OP
    }

    private fun dispatchScoreEdit(
        intent: (Long) -> ScoreEditIntent,
        onResult: (FreePracticeDispatchResult) -> Unit = {},
    ) {
        scope.launch {
            editMutex.withLock {
                onResult(dispatchScoreEditLocked(intent))
            }
        }
    }

    private fun dispatchScoreEditLocked(intent: (Long) -> ScoreEditIntent): FreePracticeDispatchResult {
        writingGeneration.incrementAndGet()
        val before = freePracticeSession.frame()
        return freePracticeSession.dispatch(
            FreePracticeIntent.Score(
                expectedRevision = before.revision,
                inner = intent(before.score.revision),
            ),
        ).also(::publishWritingFrame)
    }

    /** Returns whether the session accepted the intent, before any background writing completes. */
    private fun dispatchWorkspaceIntent(
        intent: FreePracticeIntent,
        onComplete: (String?) -> Unit,
    ): Boolean {
        val generation = writingGeneration.incrementAndGet()
        val dispatched = freePracticeSession.dispatch(intent)
        publishWritingFrame(dispatched)
        val request = dispatched.requests.singleOrNull()
        if (request == null) {
            onComplete(practiceWritingState.message)
            return dispatched.effect.kind.acceptedByWorkspace()
        }
        pendingWorkspaceCommits += 1
        val accepted = dispatched.effect.kind.acceptedByWorkspace()
        scope.launch {
            val applied = try {
                val background = solveInBackground(request, generation)
                editMutex.withLock {
                    if (generation != writingGeneration.get()) return@withLock null
                    background?.let {
                        freePracticeSession.applyBackgroundResult(it).also(::publishWritingFrame)
                    }
                }
            } finally {
                pendingWorkspaceCommits -= 1
            }
            if (generation == writingGeneration.get()) {
                onComplete(practiceWritingState.message)
                applied?.requests?.forEach { launchOptimization(generation, it) }
            }
        }
        return accepted
    }

    private fun FreePracticeEffectKind.acceptedByWorkspace(): Boolean =
        this == FreePracticeEffectKind.APPLIED ||
            this == FreePracticeEffectKind.SELECTION_CHANGED ||
            this == FreePracticeEffectKind.PRACTICE_REBUILT ||
            this == FreePracticeEffectKind.WRITING_REQUESTED ||
            this == FreePracticeEffectKind.WRITING_APPLIED ||
            this == FreePracticeEffectKind.NO_OP

    private fun launchOptimization(generation: Long, request: PracticeBackgroundRequest) {
        scope.launch {
            val background = solveInBackground(request, generation) ?: return@launch
            editMutex.withLock {
                if (generation == writingGeneration.get()) {
                    publishWritingFrame(freePracticeSession.applyBackgroundResult(background))
                }
            }
        }
    }

    /**
     * Runs one background solve and turns a crash into the shared failure channel.
     *
     * Letting the exception escape the coroutine would leave the request active in the session:
     * the workbench would stay in [PracticeWritingPhase.RUNNING] with the uncommitted workspace
     * showing and no way back. The session owns the rollback; this only has to report the crash.
     */
    private suspend fun solveInBackground(
        request: PracticeBackgroundRequest,
        generation: Long,
    ): PracticeBackgroundResult? = try {
        withContext(Dispatchers.Default) {
            FreePracticeBackgroundExecutor.execute(
                request,
                SearchCancellation { generation != writingGeneration.get() },
            )
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        editMutex.withLock {
            publishWritingFrame(
                freePracticeSession.applyBackgroundFailure(
                    PracticeBackgroundFailure(request.requestId, error.describeForUser()),
                )
            )
        }
        null
    }

    private fun Throwable.describeForUser(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "未知错误"

    private fun publishWritingFrame(result: FreePracticeDispatchResult) {
        val status = result.frame.writing
        practiceWritingState = PracticeWritingState(
            running = status.phase == PracticeWritingPhase.RUNNING,
            message = localizedWritingMessage(
                status.outcome,
                result.effect.kind,
                result.effect.messageKey,
            ),
            outcome = status.outcome,
            canAlternate = status.canAlternate,
            lastScope = status.lastScope,
        )
        practiceIdiomCatalog = result.frame.plan.idiomCatalog
        practicePlan = result.frame.plan
        practiceSelection = result.frame.selection
        practiceStructure = result.frame.structure
        practiceTimeline = result.frame.timeline
        practiceNoteConstraints = result.frame.noteConstraints
        result.editPlayback?.let { playback ->
            practicePlaybackCommand = PracticePlaybackCommand(++practicePlaybackGeneration, playback)
        }
        launchTeachingCatalog(result.catalogRequests)
        launchFindings(result.findingRequests)
        publishCurrentState()
    }

    private fun launchTeachingCatalog(requests: List<PracticeTeachingCatalogRequest>) {
        requests.lastOrNull()?.let { request ->
            scope.launch {
                val catalog = try {
                    withContext(Dispatchers.Default) {
                        PracticeTeachingCatalogExecutor.execute(request)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    // Without this the catalog view stays `loading` forever: the pending request
                    // is exactly what suppresses the next one for the same fingerprint.
                    editMutex.withLock {
                        publishWritingFrame(
                            freePracticeSession.applyTeachingCatalogFailure(
                                PracticeBackgroundFailure(request.requestId, error.describeForUser()),
                            )
                        )
                    }
                    return@launch
                }
                editMutex.withLock {
                    publishWritingFrame(freePracticeSession.applyTeachingCatalogResult(catalog))
                }
            }
        }
    }

    private fun launchFindings(requests: List<PracticeFindingRequest>) {
        requests.lastOrNull()?.let { request ->
            val generation = findingGeneration.incrementAndGet()
            scope.launch {
                val result = try {
                    withContext(Dispatchers.Default) {
                        PracticeFindingExecutor.execute(
                            request,
                            SearchCancellation { generation != findingGeneration.get() },
                        )
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    editMutex.withLock {
                        if (generation == findingGeneration.get()) {
                            publishWritingFrame(
                                freePracticeSession.applyFindingFailure(
                                    PracticeBackgroundFailure(request.requestId, error.describeForUser()),
                                )
                            )
                        }
                    }
                    return@launch
                }
                editMutex.withLock {
                    if (generation == findingGeneration.get()) {
                        publishWritingFrame(freePracticeSession.applyFindingResult(result))
                    }
                }
            }
        }
    }

    private fun localizedWritingMessage(
        outcome: PracticeWritingOutcome?,
        effect: FreePracticeEffectKind,
        messageKey: String? = null,
    ): String? = when (outcome) {
        is PracticeWritingOutcome.Solved -> "自动写作完成"
        PracticeWritingOutcome.NoSolution -> "当前范围没有可用写法，原音符未改动。"
        PracticeWritingOutcome.BudgetExhausted -> "搜索预算已耗尽，原音符未改动。"
        PracticeWritingOutcome.Cancelled -> "写作已取消。"
        is PracticeWritingOutcome.Invalid -> "写作请求无效，原音符未改动。"
        is PracticeWritingOutcome.Failed ->
            "自动写作出错，已回退到上一个正常状态：${outcome.reason}"
        null -> when {
            effect == FreePracticeEffectKind.WRITING_REQUESTED -> "正在自动写作…"
            messageKey == "freePractice.harmonicRole.conflict" ->
                "所选和弦不符合已标记的和弦内音/和弦外音，请更换和弦或调整音符标记。"
            effect == FreePracticeEffectKind.INVALID -> "当前操作不符合自由练习约束。"
            else -> null
        }
    }

    fun close() {
        writingGeneration.incrementAndGet()
        findingGeneration.incrementAndGet()
        practiceWritingState = PracticeWritingState()
    }

    fun reconfigureGrandStaff(
        layout: GrandStaffVoiceLayout,
        onComplete: () -> Unit = {},
    ) {
        scope.launch {
            editMutex.withLock {
                val result = withContext(Dispatchers.Default) {
                    freePracticeSession.dispatch(
                        FreePracticeIntent.UpdateStaffVoices(freePracticeSession.frame().revision, layout)
                    )
                }
                publishWritingFrame(result)
                onComplete()
            }
        }
    }

    override fun applyNoteEdit(
        insertion: NoteEditEngine.Insertion,
        onInputTransition: (ScoreNoteInputTransition) -> Unit,
        onInserted: (EventSection, RuntimeScore) -> Unit,
    ) = applyPracticeNoteEdit(insertion, VoiceAssignmentSource.MANUAL, onInputTransition, onInserted)

    fun applyPracticeNoteEdit(
        insertion: NoteEditEngine.Insertion,
        source: VoiceAssignmentSource,
        onInputTransition: (ScoreNoteInputTransition) -> Unit = {},
        onInserted: (EventSection, RuntimeScore) -> Unit = { _, _ -> },
        onRejected: (PracticeEditRejection) -> Unit = {},
    ) {
        scope.launch {
            var rejection: PracticeEditRejection? = null
            editMutex.withLock {
                val outcome = withContext(Dispatchers.Default) {
                    preparePracticeInsertion(state.runtimeScore, insertion, source)
                }
                if (outcome is PracticeInsertionOutcome.Rejected) {
                    rejection = outcome.reason
                    return@withLock
                }
                val prepared = (outcome as PracticeInsertionOutcome.Prepared).value
                if (source == VoiceAssignmentSource.MANUAL) {
                    val dispatched = dispatchScoreEditLocked { revision ->
                        ScoreEditIntent.InsertNote(
                            expectedRevision = revision,
                            voiceTrackId = insertion.voiceTrackId,
                            start = insertion.start,
                            duration = insertion.duration,
                            pitch = insertion.pitch,
                            isRest = insertion.isRest,
                            trailingTie = insertion.trailingTie,
                            staffTrackId = insertion.staffTrackId,
                            voiceNumber = insertion.voiceNumber,
                            tupletCount = insertion.tupletCount,
                            beaming = insertion.beaming,
                            articulations = insertion.articulations,
                            grace = insertion.grace?.let {
                                ScoreEditIntent.GraceInsertion(it.totalDuration, it.stealFrom, it.noteType)
                            },
                            smallNoteAppendStartEventId = insertion.smallNoteAppendStartEventId,
                        )
                    }
                    if (dispatched.effect.kind == FreePracticeEffectKind.APPLIED) {
                        dispatched.scoreUpdate?.noteInputTransition?.let(onInputTransition)
                        val score = dispatched.frame.score
                        score.selection.firstNotNullOfOrNull { it.eventIdOrNull }
                            ?.let(score.computedScore::getComputedEvent)
                            ?.let { onInserted(VoiceEventSection(it), score.runtimeScore) }
                    } else {
                        rejection = PracticeEditRejection.InvalidInsertion
                    }
                    return@withLock
                }
                val (result, nextWorkspace) = prepared
                val computed = withContext(Dispatchers.Default) { computeScore(result.score) }
                val changed = transaction.commit(
                    runtimeScore = result.score,
                    computedScore = computed,
                    workspaceState = nextWorkspace,
                )
                if (changed) publishCurrentState()
                result.insertedEventId
                    ?.let(computed::getComputedEvent)
                    ?.let { onInserted(VoiceEventSection(it), result.score) }
            }
            rejection?.let(onRejected)
        }
    }

    fun swapEventVoice(
        eventId: EventId,
        targetVoiceId: TrackId,
        onAfter: (Set<EventSection>) -> Unit = {},
    ) {
        scope.launch {
            editMutex.withLock {
                val live = state.runtimeScore
                val sourceVoiceId = live.voiceTrackIdOf(eventId) ?: return@withLock
                if (sourceVoiceId == targetVoiceId) return@withLock
                val sourceEvent = live.voiceTracks[sourceVoiceId]?.events?.toList()
                    ?.firstOrNull { it.id == eventId && !it.isRest }
                    ?: return@withLock
                fun location(voiceId: TrackId): Pair<TrackId, Int>? {
                    val voiceNumber = live.voiceTracks[voiceId]?.voiceNumber ?: return null
                    val staffId = live.staffTracks.values
                        .firstOrNull { staff -> staff.voiceTracks.any { it.id == voiceId } }
                        ?.id
                        ?: return null
                    return staffId to voiceNumber
                }
                val sourceLocation = location(sourceVoiceId) ?: return@withLock
                val targetLocation = location(targetVoiceId) ?: return@withLock
                val targetEvent = live.voiceTracks[targetVoiceId]?.events?.toList()?.firstOrNull {
                    !it.isRest && it.onset <= sourceEvent.onset && it.endTime > sourceEvent.onset
                }
                val targets = buildList {
                    add(
                        ScoreEditIntent.VoiceMoveTarget(
                            sourceVoiceId,
                            eventId,
                            targetLocation.second,
                            targetStaffId = targetLocation.first,
                        ),
                    )
                    targetEvent?.let {
                        add(
                            ScoreEditIntent.VoiceMoveTarget(
                                targetVoiceId,
                                it.id,
                                sourceLocation.second,
                                targetStaffId = sourceLocation.first,
                            ),
                        )
                    }
                }
                val result = dispatchScoreEditLocked { revision ->
                    ScoreEditIntent.MoveVoices(revision, targets)
                }
                val selection = if (result.effect.kind == FreePracticeEffectKind.APPLIED) {
                    result.frame.score.computedScore.getComputedEvent(eventId)
                        ?.let { setOf(VoiceEventSection(it)) }
                        .orEmpty()
                } else {
                    emptySet()
                }
                onAfter(selection)
            }
        }
    }

    override fun applyNoteDeletes(
        deletions: List<NoteEditEngine.Deletion>,
        onAfter: (Set<EventSection>) -> Unit,
    ) {
        if (deletions.isEmpty()) return
        dispatchScoreEdit(
            intent = { revision ->
                ScoreEditIntent.DeleteNotes(
                    revision,
                    deletions.map {
                        ScoreEditIntent.EventTarget(it.voiceTrackId, it.eventId, it.pitchIndices)
                    },
                )
            },
        ) { result ->
            if (result.effect.kind == FreePracticeEffectKind.APPLIED) {
                val score = result.frame.score
                onAfter(score.computedScore.voiceEventSections(score.selection.mapNotNull { it.eventIdOrNull }))
            }
        }
    }

    override fun applyNoteTranspose(
        targets: List<NoteEditEngine.TransposeTarget>,
        stepDelta: Int,
        onAfter: (Set<EventSection>) -> Unit,
    ) {
        if (targets.isEmpty() || stepDelta == 0) return
        dispatchScoreEdit(
            intent = { revision ->
                ScoreEditIntent.TransposeNotes(
                    expectedRevision = revision,
                    targets = targets.map {
                        ScoreEditIntent.EventTarget(it.voiceTrackId, it.eventId, it.pitchIndices)
                    },
                    stepDelta = stepDelta,
                )
            },
        ) { result ->
            if (result.effect.kind == FreePracticeEffectKind.APPLIED) {
                val score = result.frame.score
                onAfter(
                    score.computedScore.movedEventSections(
                        score.selection.filterIsInstance<ScoreSelectionTarget.Event>()
                            .map { it.eventId to it.pitchIndices },
                    ),
                )
            }
        }
    }

    fun applyPianoRollPitchEdit(
        eventIds: Set<EventId>,
        sourceMidi: Int,
        targetMidi: Int,
        onAfter: (Set<EventSection>) -> Unit = {},
    ) {
        if (sourceMidi == targetMidi || eventIds.isEmpty()) return
        scope.launch {
            editMutex.withLock {
                val prepared = withContext(Dispatchers.Default) {
                    val live = state.runtimeScore
                    val target = eventIds.firstNotNullOfOrNull { eventId ->
                        val voiceId = live.voiceTrackIdOf(eventId) ?: return@firstNotNullOfOrNull null
                        val event = live.voiceTracks[voiceId]?.events?.toList()
                            ?.firstOrNull { it.id == eventId && !it.isRest }
                            ?: return@firstNotNullOfOrNull null
                        val pitchIndex = event.pitches.indexOfFirst { it.midiNumber == sourceMidi }
                        if (pitchIndex < 0) return@firstNotNullOfOrNull null
                        val preferSharps = live.getKeySignatureAt(event.onset.measure).fifths >= 0
                        NoteEditEngine.ExactPitchEdit(
                            voiceTrackId = voiceId,
                            eventId = eventId,
                            pitchIndex = pitchIndex,
                            pitch = Pitch.fromMidi(targetMidi.coerceIn(0, 127), preferSharps),
                        )
                    } ?: return@withContext null
                    val result = NoteEditEngine.editExactPitches(live, listOf(target))
                        ?: return@withContext null
                    Triple(result, computeScore(result.score), workspaceController.state)
                } ?: return@withLock
                val (result, computed, nextWorkspace) = prepared
                if (transaction.commit(result.score, computed, nextWorkspace)) {
                    publishCurrentState()
                }
                onAfter(
                    computed.movedEventSections(
                        result.movedEvents.map { it.eventId to it.pitchIndices }
                    )
                )
            }
        }
    }

    fun applyPianoRollRangeBoundaryEdit(
        eventIds: Set<EventId>,
        boundary: NoteEditEngine.RangeBoundary,
        target: com.mecon.api.primitive.TimeCode,
        minimumLength: Fraction,
        onAfter: (Set<EventSection>) -> Unit = {},
    ) {
        if (eventIds.isEmpty()) return
        scope.launch {
            editMutex.withLock {
                val prepared = withContext(Dispatchers.Default) {
                    val live = state.runtimeScore
                    val eventId = eventIds.firstOrNull { live.voiceTrackIdOf(it) != null }
                        ?: return@withContext null
                    val voiceId = live.voiceTrackIdOf(eventId) ?: return@withContext null
                    val result = NoteEditEngine.editRangeBoundary(
                        live,
                        NoteEditEngine.RangeBoundaryEdit(
                            voiceTrackId = voiceId,
                            eventId = eventId,
                            boundary = boundary,
                            target = target,
                            minimumLength = minimumLength,
                        ),
                    ) ?: return@withContext null
                    val oldSources = workspaceController.state.voiceAssignmentSources
                    val nextSources = buildMap {
                        putAll(oldSources)
                        result.replacementEventIds.forEach { (oldId, newId) ->
                            val source = oldSources[oldId] ?: return@forEach
                            remove(oldId)
                            put(newId, source)
                        }
                    }
                    val nextWorkspace = workspaceController.state.copy(
                        voiceAssignmentSources = nextSources,
                    )
                    Triple(result, computeScore(result.score), nextWorkspace)
                } ?: return@withLock
                val (result, computed, nextWorkspace) = prepared
                if (transaction.commit(result.score, computed, nextWorkspace)) {
                    publishCurrentState()
                }
                onAfter(computed.voiceEventSections(result.resultEventIds))
            }
        }
    }

    override fun applyRestMove(
        targets: List<NoteEditEngine.RestMoveTarget>,
        onAfter: (Set<EventSection>) -> Unit,
    ) {
        if (targets.isEmpty()) return
        dispatchScoreEdit(
            intent = { revision ->
                ScoreEditIntent.MoveRests(
                    expectedRevision = revision,
                    targets = targets.map {
                        ScoreEditIntent.RestPositionTarget(it.voiceTrackId, it.eventId, it.staffPosition)
                    },
                )
            },
        ) { result ->
            if (result.effect.kind == FreePracticeEffectKind.APPLIED) {
                val score = result.frame.score
                onAfter(score.computedScore.voiceEventSections(score.selection.mapNotNull { it.eventIdOrNull }))
            }
        }
    }

    override fun applyDurationEdits(
        edits: List<NoteEditEngine.DurationEdit>,
        onConflict: () -> Unit,
        onAfter: (Set<EventSection>) -> Unit,
    ) = dispatchScoreEdit(
        intent = { revision ->
            ScoreEditIntent.SetDurations(
                revision,
                edits.map { ScoreEditIntent.DurationTarget(it.voiceTrackId, it.eventId, it.duration) },
            )
        },
        onResult = { result ->
            when (result.scoreUpdate?.effect?.kind) {
                ScoreEditEffectKind.CONFLICT -> onConflict()
                ScoreEditEffectKind.APPLIED -> {
                    val score = result.frame.score
                    onAfter(score.computedScore.voiceEventSections(score.selection.mapNotNull { it.eventIdOrNull }))
                }
                else -> Unit
            }
        },
    )

    override fun applyTupletEdit(
        edits: List<NoteEditEngine.TupletEdit>,
        onConflict: () -> Unit,
        onAfter: (Set<EventSection>) -> Unit,
    ) = dispatchScoreEdit(
        intent = { revision ->
            ScoreEditIntent.ApplyTuplets(
                revision,
                edits.map { ScoreEditIntent.EventGroupTarget(it.voiceTrackId, it.eventIds, it.count) },
            )
        },
        onResult = { result ->
            when (result.scoreUpdate?.effect?.kind) {
                ScoreEditEffectKind.CONFLICT -> onConflict()
                ScoreEditEffectKind.APPLIED -> {
                    val score = result.frame.score
                    onAfter(score.computedScore.voiceEventSections(score.selection.mapNotNull { it.eventIdOrNull }))
                }
                else -> Unit
            }
        },
    )

    override fun applySmallNoteEdits(
        edits: List<NoteEditEngine.SmallNoteEdit>,
        onConflict: () -> Unit,
        onAfter: (Set<EventSection>) -> Unit,
    ) = dispatchScoreEdit(
        intent = { revision ->
            ScoreEditIntent.CreateSmallNoteRegions(
                revision,
                edits.map { ScoreEditIntent.EventGroupTarget(it.voiceTrackId, it.eventIds) },
            )
        },
        onResult = { result ->
            when (result.scoreUpdate?.effect?.kind) {
                ScoreEditEffectKind.CONFLICT -> onConflict()
                ScoreEditEffectKind.APPLIED -> {
                    val score = result.frame.score
                    onAfter(score.computedScore.voiceEventSections(score.selection.mapNotNull { it.eventIdOrNull }))
                }
                else -> Unit
            }
        },
    )

    override fun applyAccidentalEdit(
        edits: List<NoteEditEngine.AccidentalEdit>,
        onAfter: (Set<EventSection>) -> Unit,
    ) = dispatchScoreEdit(
        intent = { revision ->
            ScoreEditIntent.SetAccidentals(
                revision,
                edits.map {
                    ScoreEditIntent.AccidentalTarget(
                        it.voiceTrackId,
                        it.eventId,
                        it.accidental,
                        it.pitchIndices,
                    )
                },
            )
        },
        onResult = { result ->
            if (result.effect.kind == FreePracticeEffectKind.APPLIED) {
                val score = result.frame.score
                onAfter(score.computedScore.voiceEventSections(score.selection.mapNotNull { it.eventIdOrNull }))
            }
        },
    )

    override fun applyTieEdit(
        edits: List<NoteEditEngine.TieEdit>,
        onAfter: (Set<EventSection>) -> Unit,
    ) = dispatchScoreEdit(
        intent = { revision ->
            ScoreEditIntent.SetTies(
                revision,
                edits.map {
                    ScoreEditIntent.TieTarget(it.voiceTrackId, it.eventId, it.tieOut, it.pitchIndices)
                },
            )
        },
        onResult = { result ->
            if (result.effect.kind == FreePracticeEffectKind.APPLIED) {
                val score = result.frame.score
                onAfter(score.computedScore.voiceEventSections(score.selection.mapNotNull { it.eventIdOrNull }))
            }
        },
    )

    override fun applyBeamingEdit(
        edits: List<NoteEditEngine.BeamingEdit>,
        onAfter: (Set<EventSection>) -> Unit,
    ) = dispatchScoreEdit(
        intent = { revision ->
            ScoreEditIntent.SetBeaming(
                revision,
                edits.map { ScoreEditIntent.BeamingTarget(it.voiceTrackId, it.eventId, it.beaming) },
            )
        },
        onResult = { result ->
            if (result.effect.kind == FreePracticeEffectKind.APPLIED) {
                val score = result.frame.score
                onAfter(score.computedScore.voiceEventSections(score.selection.mapNotNull { it.eventIdOrNull }))
            }
        },
    )

    override fun addSlurs(
        targets: List<NoteEditEngine.SlurTarget>,
        onAfter: (Set<EventSection>) -> Unit,
    ) = dispatchScoreEdit(
        intent = { revision ->
            ScoreEditIntent.AddSlurs(
                revision,
                targets.map {
                    ScoreEditIntent.SlurTarget(it.voiceTrackId, it.startEventId, it.endEventId)
                },
            )
        },
        onResult = { result ->
            if (result.effect.kind == FreePracticeEffectKind.APPLIED) {
                val computed = result.frame.score.computedScore
                onAfter(result.frame.score.selection.mapNotNullTo(linkedSetOf()) { target ->
                    val slurId = (target as? ScoreSelectionTarget.Slur)?.slurId
                        ?: return@mapNotNullTo null
                    val slur = computed.slurs.firstOrNull { it.slurId == slurId }
                        ?: return@mapNotNullTo null
                    val start = computed.getComputedEvent(slur.startEventId)
                        ?: return@mapNotNullTo null
                    val end = computed.getComputedEvent(slur.endEventId)
                        ?: return@mapNotNullTo null
                    VoiceSlurSection(start, end, slur.nestingLevel)
                })
            }
        },
    )

    override fun toggleArticulation(
        targets: List<ExpressionEditEngine.NoteTarget>,
        articulation: Articulation,
        onAfter: (Set<EventSection>) -> Unit,
    ) = dispatchScoreEdit(
        intent = { revision ->
            ScoreEditIntent.ToggleArticulation(
                revision,
                targets.map { ScoreEditIntent.EventTarget(it.voiceTrackId, it.eventId) },
                articulation,
            )
        },
        onResult = { result ->
            if (result.effect.kind == FreePracticeEffectKind.APPLIED) {
                val score = result.frame.score
                onAfter(score.computedScore.voiceEventSections(score.selection.mapNotNull { it.eventIdOrNull }))
            }
        },
    )

    private sealed interface PracticeInsertionOutcome {
        data class Prepared(
            val value: Pair<NoteEditEngine.Result, HarmonyWorkspaceState>,
        ) : PracticeInsertionOutcome

        data class Rejected(
            val reason: PracticeEditRejection,
        ) : PracticeInsertionOutcome
    }

    private fun preparePracticeInsertion(
        runtime: RuntimeScore,
        insertion: NoteEditEngine.Insertion,
        source: VoiceAssignmentSource,
    ): PracticeInsertionOutcome {
        var effectiveInsertion = insertion
        val insertionPitch = insertion.pitch
        if (source == VoiceAssignmentSource.AUTOMATIC && insertionPitch != null && !insertion.isRest) {
            val targetVoiceId = AutomaticNotationAssigner.assign(
                lanes = workspaceController.state.voices.map { voice ->
                    NotationLaneSpec(
                        id = voice.id,
                        order = voice.order,
                        lowest = voice.lowest,
                        highest = voice.highest,
                    )
                },
                events = notationEvents(runtime),
                pending = PendingNotationNote(
                onset = absolute(runtime, insertion.start),
                duration = insertion.duration.toFraction(),
                pitch = insertionPitch,
                ),
            ) ?: return PracticeInsertionOutcome.Rejected(
                PracticeEditRejection.NoAvailableNotationLane,
            )
            effectiveInsertion = insertion.copy(
                voiceTrackId = targetVoiceId,
                staffTrackId = null,
                voiceNumber = 1,
            )
        }
        val inserted = NoteEditEngine.insert(
            runtime,
            effectiveInsertion,
            NoteEditEngine.InsertionPolicy.CHORDAL,
        ) ?: return PracticeInsertionOutcome.Rejected(PracticeEditRejection.InvalidInsertion)
        val validation = PolyphonyLimitValidator.validate(inserted.score, polyphonyLimit)
        if (!validation.isValid) {
            return PracticeInsertionOutcome.Rejected(
                PracticeEditRejection.PolyphonyLimitExceeded(validation),
            )
        }
        if (inserted.insertedEventId == null) {
            return PracticeInsertionOutcome.Rejected(PracticeEditRejection.InvalidInsertion)
        }
        val insertedId = inserted.insertedEventId
        val nextWorkspace = if (insertedId == null) {
            workspaceController.state
        } else {
            val existing = workspaceController.state.voiceAssignmentSources[insertedId]
            workspaceController.state.copy(
                voiceAssignmentSources = workspaceController.state.voiceAssignmentSources +
                    (insertedId to if (existing == VoiceAssignmentSource.MANUAL) existing else source),
            )
        }
        return PracticeInsertionOutcome.Prepared(inserted to nextWorkspace)
    }

    override fun applyVoiceMove(
        targets: List<NoteEditEngine.VoiceMoveTarget>,
        onAfter: (Set<EventSection>) -> Unit,
    ) {
        if (targets.isEmpty()) return
        dispatchScoreEdit(
            intent = { revision ->
                ScoreEditIntent.MoveVoices(
                    revision,
                    targets.map {
                        ScoreEditIntent.VoiceMoveTarget(
                            it.voiceTrackId,
                            it.eventId,
                            it.targetVoiceNumber,
                            it.pitchIndices,
                            it.targetStaffId,
                        )
                    },
                )
            },
        ) { result ->
            if (result.effect.kind == FreePracticeEffectKind.APPLIED) {
                val score = result.frame.score
                onAfter(
                    score.computedScore.movedEventSections(
                        score.selection.filterIsInstance<ScoreSelectionTarget.Event>()
                            .map { it.eventId to it.pitchIndices },
                    ),
                )
            }
        }
    }

    private fun notationEvents(runtime: RuntimeScore): List<NotationEventSpan> =
        runtime.voiceTracks.flatMap { (voiceId, voice) ->
            voice.events.toList().filterNot { it.isRest || it.isGrace }.map { event ->
                NotationEventSpan(
                    laneId = voiceId,
                    onset = absolute(runtime, event.onset),
                    duration = event.duration.toFraction(),
                    pitches = event.pitches,
                )
            }
        }

    private fun absolute(runtime: RuntimeScore, time: com.mecon.api.primitive.TimeCode): Fraction {
        var result = Fraction.ZERO
        for (measure in 1 until time.measure) {
            result += runtime.getTimeSignatureAt(measure).measureDuration()
        }
        return result + (time.beat ?: Fraction.ZERO)
    }

    private fun publishCurrentState() {
        state = manager.currentState
        val frame = freePracticeSession.frame()
        practiceFindings = frame.findings.items
        practicePlan = frame.plan
        practiceSelection = frame.selection
        practiceStructure = frame.structure
        practiceTimeline = frame.timeline
        practiceNoteConstraints = frame.noteConstraints
        practiceIdiomCatalog = frame.plan.idiomCatalog
        practiceWorkspace = frame.document.workspace
        practiceDocument = frame.document
        canUndo = manager.canUndo()
        canRedo = manager.canRedo()
        documentVersion += 1
    }
}
