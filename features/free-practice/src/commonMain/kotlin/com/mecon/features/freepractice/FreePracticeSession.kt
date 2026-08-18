package com.mecon.features.freepractice

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.ScoreTimeMap
import com.mecon.api.runtime.toStorage
import com.mecon.api.state.ScoreStateManager
import com.mecon.api.state.EditorStateController
import com.mecon.core.engine.computeScore
import com.mecon.core.engine.edit.PolyphonyLimitValidator
import com.mecon.core.engine.edit.MeasureEditEngine
import com.mecon.core.engine.edit.TimeSignatureEditEngine
import com.mecon.exploration.FreePracticeDocument
import com.mecon.exploration.FreePracticeSettings
import com.mecon.exploration.PracticeNoteConstraintState
import com.mecon.exploration.VoicePlanScoreAssembler
import com.mecon.features.scoreediting.ScoreEditEffectKind
import com.mecon.features.scoreediting.ScoreEditIntent
import com.mecon.features.scoreediting.ScoreSelectionTarget
import com.mecon.features.scoreediting.ScoreEditingCommitPolicy
import com.mecon.features.scoreediting.ScoreEditingSession
import com.mecon.features.scoreediting.eventIdOrNull
import com.mecon.theory.freepractice.FreePracticeMaterialProjector
import com.mecon.theory.freepractice.HarmonyPracticeTransaction
import com.mecon.theory.freepractice.HarmonyWorkspaceCommand
import com.mecon.theory.freepractice.HarmonyWorkspaceEditor
import com.mecon.theory.freepractice.HarmonyWorkspaceState
import com.mecon.theory.freepractice.HarmonyWorkspaceStateController
import com.mecon.theory.freepractice.PracticeWritingScope
import com.mecon.theory.freepractice.PracticeWritingScopePlanner
import com.mecon.theory.freepractice.WorkspaceSlotId
import com.mecon.theory.freepractice.WorkspaceIdiomInstanceId
import com.mecon.theory.freepractice.WorkspaceKeyMode
import com.mecon.theory.freepractice.WorkspaceTonalLayoutId
import com.mecon.theory.freepractice.VoiceAssignmentSource
import com.mecon.theory.freepractice.idiomSourceKeyAt
import com.mecon.theory.freepractice.resolveIdiomTonalities
import com.mecon.theory.harmony.ChordSelectionCatalog
import com.mecon.theory.harmony.ChordSelectionChoice

private data class FreePracticeSelectionSnapshot(
    val slotId: WorkspaceSlotId?,
    val tonalLayoutId: WorkspaceTonalLayoutId?,
    val idiomTonalLayoutId: WorkspaceTonalLayoutId?,
    val idiomInstanceId: WorkspaceIdiomInstanceId?,
)

/** Serial, Compose-free owner of score + free-writing state. It never launches background work. */
class FreePracticeSession private constructor(
    private val manager: ScoreStateManager,
    initialDocument: FreePracticeDocument,
) {
    private val workspaceController = HarmonyWorkspaceStateController(initialDocument.workspace)
    private val transaction = HarmonyPracticeTransaction(manager, workspaceController)
    private val scoreSession = ScoreEditingSession.open(
        manager,
        ScoreEditingCommitPolicy(
            reject = { _, candidate ->
                if (PolyphonyLimitValidator.validate(candidate, settings.polyphonyLimit).isValid) {
                    null
                } else {
                    "freePractice.polyphonyLimitExceeded"
                }
            },
            updateCompanionState = { previous, committed ->
                val previousIds = previous.voiceTracks.values
                    .flatMapTo(linkedSetOf()) { voice -> voice.events.filterNot { it.isRest }.map { it.id } }
                val committedIds = committed.voiceTracks.values
                    .flatMapTo(linkedSetOf()) { voice -> voice.events.filterNot { it.isRest }.map { it.id } }
                val retainedSources = workspaceController.state.voiceAssignmentSources
                    .filterKeys { it in committedIds }
                val insertedSources = (committedIds - previousIds)
                    .associateWith { VoiceAssignmentSource.MANUAL }
                workspaceController.replace(
                    workspaceController.state.copy(
                        voiceAssignmentSources = retainedSources + insertedSources,
                    ),
                )
            },
        ),
    )
    private var revision = 0L
    private var settings = initialDocument.settings
    private var noteConstraints = initialDocument.noteConstraints
    private var migrationDiagnostics = initialDocument.migrationDiagnostics
    private var selectedSlotId: WorkspaceSlotId? = initialDocument.workspace.slots.firstOrNull()?.id
    private var selectedTonalLayoutId = selectedSlotId
        ?.let { id -> initialDocument.workspace.slots.firstOrNull { it.id == id } }
        ?.let(initialDocument.workspace::selectedTonalLayout)?.id
    private var selectedIdiomInstanceId = selectedSlotId
        ?.let { id -> initialDocument.workspace.idiomInstanceStartingAt(id) }?.id
    private var writing = PracticeWritingStatus()
    private var findingsGeneration = 0L
    private var findings = PracticeFindingsView(0L)
    private var findingsFingerprint: String? = null
    private var activeFindingRequest: PracticeFindingRequest? = null
    private var requestCounter = 0L
    private var candidates: List<PracticeVoicingCandidate> = emptyList()
    private var nextCandidateIndex = 1
    private var activeRequest: PracticeBackgroundRequest? = null
    private var teachingCatalog = PracticeIdiomCatalogView()
    private var activeTeachingCatalogRequest: PracticeTeachingCatalogRequest? = null
    private var selectedIdiomTonalLayoutId: WorkspaceTonalLayoutId? = null
    private var catalogIncludeOffKey: Boolean = false
    private var chordCatalogRoleFilterEnabled: Boolean = false
    private var idiomCatalogRoleFilterEnabled: Boolean = false
    private var operationBaseWorkspace = initialDocument.workspace
    private var operationBaseSettings = initialDocument.settings
    private var operationBaseNoteConstraints = initialDocument.noteConstraints
    private var findingInputWorkspace: HarmonyWorkspaceState? = null
    private var findingInputRuntime: RuntimeScore? = null
    private var findingInputPendingScore: com.mecon.api.storage.StorageScore? = null
    private var findingInputSettings: FreePracticeSettings? = null
    private var findingInputGeneration = 0L

    init {
        manager.registerEditorState("free-practice-settings", object : EditorStateController {
            override fun capture(): Any = settings

            override fun restore(snapshot: Any?) {
                (snapshot as? FreePracticeSettings)?.let { settings = it }
            }
        })
        manager.registerEditorState("free-practice-note-constraints", object : EditorStateController {
            override fun capture(): Any = noteConstraints

            override fun restore(snapshot: Any?) {
                (snapshot as? PracticeNoteConstraintState)?.let { noteConstraints = it }
            }
        })
        manager.registerEditorState("free-practice-selection", object : EditorStateController {
            override fun capture(): Any = FreePracticeSelectionSnapshot(
                selectedSlotId,
                selectedTonalLayoutId,
                selectedIdiomTonalLayoutId,
                selectedIdiomInstanceId,
            )

            override fun restore(snapshot: Any?) {
                val restored = snapshot as? FreePracticeSelectionSnapshot ?: return
                selectedSlotId = restored.slotId
                selectedTonalLayoutId = restored.tonalLayoutId
                selectedIdiomTonalLayoutId = restored.idiomTonalLayoutId
                selectedIdiomInstanceId = restored.idiomInstanceId
            }
        })
    }

    fun frame(): FreePracticeFrame {
        val currentDocument = document()
        val currentCatalog = catalog()
        val scoreFrame = scoreSession.frame()
        val currentNoteConstraints = PracticeNoteConstraintProjector.view(
            noteConstraints,
            currentDocument.workspace,
            manager.currentState.runtimeScore,
            chordCatalogRoleFilterEnabled,
            idiomCatalogRoleFilterEnabled,
        )
        val validSlotId = selectedSlotId?.takeIf { id -> visibleWorkspace.slots.any { it.id == id } }
        val selectedSlot = validSlotId?.let { id -> visibleWorkspace.slots.first { it.id == id } }
        val validTonalLayoutId = selectedTonalLayoutId
            ?.takeIf { id -> visibleWorkspace.tonalLayouts.any { it.id == id } }
            ?: selectedSlot?.let(visibleWorkspace::selectedTonalLayout)?.id
        val validIdiomInstanceId = selectedIdiomInstanceId
            ?.takeIf { id ->
                visibleWorkspace.idiomInstances.any { instance ->
                    instance.id == id && visibleWorkspace.firstSlotId(instance.id) == validSlotId
                }
            }
            ?: validSlotId?.let { id -> visibleWorkspace.idiomInstanceStartingAt(id) }?.id
        val selection = FreePracticeSelection(
            slotId = validSlotId,
            tonalLayoutId = validTonalLayoutId,
            idiomInstanceId = validIdiomInstanceId,
            scoreTargets = scoreFrame.selection,
        )
        val currentTimeline = FreePracticeViewProjector.timeline(
            currentDocument.workspace,
            teachingCatalog,
            VoicePlanScoreAssembler.scoreDuration(manager.currentState.runtimeScore),
            measureBoundaries(manager.currentState.runtimeScore),
            currentDocument.settings.defaultChordDuration,
        )
        val currentPlan = FreePracticeViewProjector.plan(
            currentDocument.workspace,
            selectedSlotId,
            validTonalLayoutId,
            currentCatalog,
            selectedIdiomCatalogLayout()?.id,
            teachingCatalog,
            validIdiomInstanceId,
        )
        return FreePracticeFrame(
        revision = revision,
        document = currentDocument,
        score = scoreFrame,
        selection = selection,
        selectedSlotId = validSlotId,
        findings = findings,
        catalog = currentCatalog,
        noteConstraints = currentNoteConstraints,
        timeline = currentTimeline,
        structure = structureView(scoreFrame.selection),
        plan = currentPlan,
        writing = writing,
    )
    }

    val workspaceState: HarmonyWorkspaceState get() = workspace

    /** Internal platform-adapter snapshot; wire APIs still expose only serialized DTOs. */
    fun currentScore(): RuntimeScore = manager.currentState.runtimeScore

    /** In-process adapter for notation operations while desktop editing migrates intent by intent. */
    fun commitExternal(
        runtimeScore: RuntimeScore,
        computedScore: com.mecon.api.computed.ComputedScore,
        workspaceState: HarmonyWorkspaceState,
    ): Boolean {
        beginOperation()
        val changed = transaction.commit(runtimeScore, computedScore, workspaceState)
        if (changed) {
            scoreSession.notifyExternalCommit()
            selectedSlotId = selectedSlotId?.takeIf { id -> workspace.slots.any { it.id == id } }
                ?: workspace.slots.firstOrNull()?.id
            cancelPending(PracticeWritingOutcome.Cancelled)
            revision++
        }
        return changed
    }

    /** Called after the shared manager has moved through history in a desktop adapter. */
    fun notifyExternalHistoryChange() {
        beginOperation()
        scoreSession.notifyExternalCommit()
        selectedSlotId = selectedSlotId?.takeIf { id -> workspace.slots.any { it.id == id } }
            ?: workspace.slots.firstOrNull()?.id
        cancelPending(PracticeWritingOutcome.Cancelled)
        revision++
    }

    fun initialUpdate(): FreePracticeUpdate {
        beginOperation()
        return update(
            baseRevision = null,
            effect = FreePracticeEffect(FreePracticeEffectKind.APPLIED),
        )
    }

    /**
     * Opens one workbench operation. Score material is committed through [transaction] rather than
     * `scoreSession.dispatch`, so the score session has to be told where this operation started;
     * otherwise its `scoreChanged` stays true forever after the first commit and every later
     * selection or background result re-lays out the whole score on the client.
     */
    private fun beginOperation() {
        scoreSession.beginExternalOperation()
        operationBaseWorkspace = workspace
        operationBaseSettings = settings
        operationBaseNoteConstraints = noteConstraints
    }

    /** Desktop adapter entry for a workspace edit whose auto-writing result shares one history item. */
    fun requestWritingForWorkspace(
        nextWorkspace: HarmonyWorkspaceState,
        triggerSlotId: WorkspaceSlotId,
        configuredBacktrack: Int,
        requiredSlotIds: List<WorkspaceSlotId>? = null,
        trimEmptyTail: Boolean = false,
    ): FreePracticeDispatchResult {
        beginOperation()
        val baseRevision = revision
        if (nextWorkspace.slots.none { it.id == triggerSlotId }) return staleTarget(baseRevision, triggerSlotId)
        val synchronized = synchronizedTimelineScore(
            manager.currentState.runtimeScore,
            nextWorkspace,
            trimEmptyTail,
        )
        val projection = FreePracticeMaterialProjector.project(nextWorkspace, synchronized)
        val completionEligible = completionEligibleSlotIds(nextWorkspace, synchronized, projection)
        val scope = if (requiredSlotIds != null) {
            PracticeWritingScopePlanner.idiom(
                nextWorkspace, requiredSlotIds, projection, completionEligible,
            )
                ?: return invalidScope(baseRevision)
        } else {
            // A slot without a chord has nothing to realize, but the workspace edit that reached
            // here — dragging or resizing an empty chord box — is still a real geometry change and
            // must commit instead of being dropped with the writing request.
            PracticeWritingScopePlanner.automatic(
                nextWorkspace,
                projection,
                triggerSlotId,
                configuredBacktrack,
                completionEligible,
            ) ?: return commitPreparedWorkspace(
                baseRevision,
                nextWorkspace,
                trimEmptyTail = trimEmptyTail,
            )
        }
        candidates = emptyList()
        nextCandidateIndex = 1
        revision++
        writing = PracticeWritingStatus(
            phase = PracticeWritingPhase.RUNNING,
            lastScope = scope.slotIds,
        )
        val request = backgroundRequest(
            scope = scope,
            kind = PracticeBackgroundRequestKind.FIRST_SOLVE,
            requestWorkspace = nextWorkspace,
            requestScore = synchronized,
        )
        activeRequest = request
        return result(
            baseRevision,
            FreePracticeEffect(FreePracticeEffectKind.WRITING_REQUESTED, "freePractice.writing.running"),
            listOf(request),
        )
    }

    fun dispatch(intent: FreePracticeIntent): FreePracticeDispatchResult {
        beginOperation()
        if (intent.expectedRevision != revision) {
            return result(
                intent.expectedRevision,
                FreePracticeEffect(
                    kind = FreePracticeEffectKind.STALE_REVISION,
                    messageKey = "freePractice.staleRevision",
                    expectedRevision = intent.expectedRevision,
                    actualRevision = revision,
                ),
            )
        }
        // Candidate optimization is speculative: once the primary writing result is visible the
        // workbench is READY and user input must win. Drop the optional request before handling
        // any new intent so a selection revision cannot leave an obsolete request permanently
        // blocking a later idiom insert or replacement.
        if (activeRequest?.kind == PracticeBackgroundRequestKind.OPTIMIZE_CANDIDATES) {
            activeRequest = null
        }
        val baseRevision = revision
        return when (intent) {
            is FreePracticeIntent.Score -> dispatchScore(intent, baseRevision)
            is FreePracticeIntent.SelectSlot -> selectSlot(intent, baseRevision)
            is FreePracticeIntent.SelectTonalLayout -> selectTonalLayout(intent, baseRevision)
            is FreePracticeIntent.SelectIdiomTonalLayout -> selectIdiomTonalLayout(intent, baseRevision)
            is FreePracticeIntent.SelectIdiom -> selectIdiom(intent, baseRevision)
            is FreePracticeIntent.ReplaceChord -> {
                val constraints = roleConstraintsAt(intent.slotId)
                if (chordCatalogRoleFilterEnabled && intent.chordChoice != null &&
                    !PracticeNoteConstraintProjector.accepts(intent.chordChoice, constraints)
                ) {
                    result(baseRevision, FreePracticeEffect(
                        FreePracticeEffectKind.INVALID,
                        "freePractice.harmonicRole.conflict",
                    ))
                } else {
                    workspaceCommandWithOptionalWriting(baseRevision, intent.slotId) { index ->
                        HarmonyWorkspaceCommand.ReplaceChord(index, chordChoice = intent.chordChoice)
                    }
                }
            }
            is FreePracticeIntent.SetChordBass -> workspaceCommandWithOptionalWriting(
                baseRevision,
                intent.slotId,
            ) { index -> HarmonyWorkspaceCommand.SetChordBass(index, intent.bassPitchClass) }
            is FreePracticeIntent.SetChordTonality -> workspaceCommand(
                baseRevision,
                intent.slotId,
            ) { index -> HarmonyWorkspaceCommand.SetChordTonality(index, intent.tonality) }
            is FreePracticeIntent.SetPivotChord -> workspaceCommand(
                baseRevision,
                intent.slotId,
            ) { index -> HarmonyWorkspaceCommand.SetPivotChord(index, intent.selected) }
            is FreePracticeIntent.SetTonalLayoutKey -> applyWorkspaceCommand(
                baseRevision,
                HarmonyWorkspaceCommand.SetTonalLayoutKey(
                    intent.tonalLayoutId,
                    com.mecon.theory.ModulationKey(intent.fifths, intent.mode.toTheory()),
                ),
            ) { _, _ -> selectedTonalLayoutId = intent.tonalLayoutId }
            is FreePracticeIntent.InsertTonalLayout -> applyWorkspaceCommand(
                baseRevision,
                HarmonyWorkspaceCommand.InsertTonalLayout(
                    key = com.mecon.theory.ModulationKey(intent.fifths, intent.mode.toTheory()),
                    start = intent.start,
                    end = intent.end,
                    terminatePreviousAt = intent.terminatePreviousAt,
                ),
            ) { before, after ->
                selectedTonalLayoutId = after.tonalLayouts.firstOrNull { candidate ->
                    before.tonalLayouts.none { it.id == candidate.id }
                }?.id ?: selectedTonalLayoutId
            }
            is FreePracticeIntent.RemoveTonalLayout -> applyWorkspaceCommand(
                baseRevision,
                HarmonyWorkspaceCommand.RemoveTonalLayout(intent.tonalLayoutId),
            ) { _, after ->
                if (selectedTonalLayoutId == intent.tonalLayoutId) {
                    selectedTonalLayoutId = selectedSlotId
                        ?.let { id -> after.slots.firstOrNull { it.id == id } }
                        ?.let(after::selectedTonalLayout)?.id
                        ?: after.tonalLayouts.firstOrNull()?.id
                }
            }
            is FreePracticeIntent.SelectChordTonalLayout -> workspaceCommand(
                baseRevision,
                intent.slotId,
                afterCommit = { _, _ -> selectedTonalLayoutId = intent.tonalLayoutId },
            ) { index -> HarmonyWorkspaceCommand.SelectChordTonalLayout(index, intent.tonalLayoutId) }
            is FreePracticeIntent.RemoveIdiom -> applyWorkspaceCommand(
                baseRevision,
                HarmonyWorkspaceCommand.RemoveIdiom(intent.idiomInstanceId),
            ) { _, after ->
                if (selectedIdiomInstanceId == intent.idiomInstanceId) {
                    selectedIdiomInstanceId = selectedSlotId
                        ?.let(after::idiomInstancesForSlot)?.firstOrNull()?.id
                }
            }
            is FreePracticeIntent.InsertIdiom -> insertIdiom(intent, baseRevision)
            is FreePracticeIntent.ReplaceIdiom -> replaceIdiom(intent, baseRevision)
            is FreePracticeIntent.SetIdiomChordToneCount -> setIdiomChordToneCount(intent, baseRevision)
            is FreePracticeIntent.InsertChordRange -> applyWorkspaceCommand(
                baseRevision,
                HarmonyWorkspaceCommand.InsertChordRange(intent.onset, intent.duration),
            ) { before, after ->
                selectedSlotId = after.slots.firstOrNull { candidate ->
                    before.slots.none { it.id == candidate.id }
                    }?.id ?: selectedSlotId
                }
            is FreePracticeIntent.SetDefaultChordDuration ->
                setDefaultChordDuration(intent, baseRevision)
            is FreePracticeIntent.SetPracticeTimeSignature ->
                setPracticeTimeSignature(intent, baseRevision)
            is FreePracticeIntent.InsertPracticeMeasures ->
                insertPracticeMeasures(intent, baseRevision)
            is FreePracticeIntent.TimelineEdit -> timelineEdit(intent.edit, baseRevision)
            is FreePracticeIntent.RemoveChordRange -> workspaceCommand(
                baseRevision,
                intent.slotId,
            ) { index -> HarmonyWorkspaceCommand.RemoveChordRange(index) }
            is FreePracticeIntent.RunWriting -> requestWriting(
                baseRevision = baseRevision,
                triggerSlotId = intent.triggerSlotId,
                requiredSlotIds = intent.requiredSlotIds,
            )
            is FreePracticeIntent.RewriteSelection -> rewriteSelection(baseRevision)
            is FreePracticeIntent.AlternateWriting -> alternate(baseRevision)
            is FreePracticeIntent.CancelWriting -> cancelWriting(baseRevision)
            is FreePracticeIntent.UpdateWritingSettings -> {
                if (intent.settings == settings.writing) return noOp(baseRevision)
                settings = settings.copy(writing = intent.settings)
                cancelPending(PracticeWritingOutcome.Cancelled)
                revision++
                result(baseRevision, FreePracticeEffect(FreePracticeEffectKind.APPLIED))
            }
            is FreePracticeIntent.UpdateStaffVoices -> updateStaffVoices(intent, baseRevision)
            is FreePracticeIntent.SetCatalogFilter -> {
                if (intent.includeOffKey == catalogIncludeOffKey) return noOp(baseRevision)
                catalogIncludeOffKey = intent.includeOffKey
                activeTeachingCatalogRequest = null
                revision++
                result(baseRevision, FreePracticeEffect(FreePracticeEffectKind.SELECTION_CHANGED))
            }
            is FreePracticeIntent.SetHarmonicRole -> setHarmonicRole(intent, baseRevision)
            is FreePracticeIntent.SetHarmonicRoleFilters -> {
                if (intent.chordCatalogEnabled == chordCatalogRoleFilterEnabled &&
                    intent.idiomCatalogEnabled == idiomCatalogRoleFilterEnabled
                ) return noOp(baseRevision)
                chordCatalogRoleFilterEnabled = intent.chordCatalogEnabled
                idiomCatalogRoleFilterEnabled = intent.idiomCatalogEnabled
                activeTeachingCatalogRequest = null
                revision++
                result(baseRevision, FreePracticeEffect(FreePracticeEffectKind.SELECTION_CHANGED))
            }
            is FreePracticeIntent.SetNoteheadLock -> setNoteheadLock(intent, baseRevision)
            is FreePracticeIntent.SetVoiceLock -> setVoiceLock(intent, baseRevision)
            is FreePracticeIntent.SetVoiceLocks -> setVoiceLocks(intent, baseRevision)
            is FreePracticeIntent.SetStaffLock -> setStaffLock(intent, baseRevision)
            is FreePracticeIntent.SetStaffLocks -> setStaffLocks(intent, baseRevision)
            is FreePracticeIntent.RebuildPractice -> rebuildPractice(intent, baseRevision)
            is FreePracticeIntent.Undo -> undo(baseRevision)
            is FreePracticeIntent.Redo -> redo(baseRevision)
        }
    }

    private fun setDefaultChordDuration(
        intent: FreePracticeIntent.SetDefaultChordDuration,
        baseRevision: Long,
    ): FreePracticeDispatchResult {
        if (!intent.duration.isPositive) {
            return result(
                baseRevision,
                FreePracticeEffect(FreePracticeEffectKind.INVALID, "freePractice.chordDuration.invalid"),
            )
        }
        if (settings.defaultChordDuration == intent.duration) return noOp(baseRevision)
        val resizeInitial = isPristinePractice()
        settings = settings.copy(defaultChordDuration = intent.duration)
        if (resizeInitial) {
            val nextWorkspace = workspace.copy(
                slots = workspace.slots.mapIndexed { index, slot ->
                    if (index == 0) slot.copy(duration = intent.duration) else slot
                },
            )
            val runtime = VoicePlanScoreAssembler.ensureTimelineMeasures(
                manager.currentState.runtimeScore,
                nextWorkspace,
            )
            transaction.commit(runtime, computeScore(runtime), nextWorkspace)
            scoreSession.notifyExternalCommit()
        }
        cancelPending(PracticeWritingOutcome.Cancelled)
        revision++
        return result(baseRevision, FreePracticeEffect(FreePracticeEffectKind.APPLIED))
    }

    private fun setPracticeTimeSignature(
        intent: FreePracticeIntent.SetPracticeTimeSignature,
        baseRevision: Long,
    ): FreePracticeDispatchResult {
        val current = manager.currentState.runtimeScore
        if (!isPristinePractice()) {
            return result(
                baseRevision,
                FreePracticeEffect(
                    FreePracticeEffectKind.INVALID,
                    "freePractice.timeSignature.scoreToolRequired",
                ),
            )
        }
        val edited = TimeSignatureEditEngine.setOverallTimeSignature(current, intent.timeSignature)
            ?: return noOp(baseRevision)
        transaction.commit(edited, computeScore(edited), workspace)
        scoreSession.notifyExternalCommit()
        cancelPending(PracticeWritingOutcome.Cancelled)
        revision++
        return result(baseRevision, FreePracticeEffect(FreePracticeEffectKind.APPLIED))
    }

    private fun insertPracticeMeasures(
        intent: FreePracticeIntent.InsertPracticeMeasures,
        baseRevision: Long,
    ): FreePracticeDispatchResult {
        if (intent.count !in 1..999 || !intent.chordDuration.isPositive) {
            return result(
                baseRevision,
                FreePracticeEffect(FreePracticeEffectKind.INVALID, "freePractice.measure.invalid"),
            )
        }
        val current = manager.currentState.runtimeScore
        val structure = structureView(scoreSession.frame().selection)
        val afterMeasure = when (intent.position) {
            FreePracticeIntent.MeasureInsertionPosition.END -> structure.lastMeasure
            FreePracticeIntent.MeasureInsertionPosition.AFTER_SELECTED_NOTE ->
                structure.selectedNoteMeasure ?: return result(
                    baseRevision,
                    FreePracticeEffect(FreePracticeEffectKind.STALE_TARGET, "freePractice.measure.noteSelectionRequired"),
                )
            FreePracticeIntent.MeasureInsertionPosition.AT_SELECTED_BARLINE ->
                structure.selectedBarlineMeasure ?: return result(
                    baseRevision,
                    FreePracticeEffect(FreePracticeEffectKind.STALE_TARGET, "freePractice.measure.barlineSelectionRequired"),
                )
        }
        val insertionOnset = measureBoundary(current, afterMeasure)
        val editedScore = MeasureEditEngine.insertAfter(current, afterMeasure, intent.count)
            ?: return result(
                baseRevision,
                FreePracticeEffect(FreePracticeEffectKind.INVALID, "freePractice.measure.invalidTarget"),
            )
        val measureDurations = (afterMeasure + 1..afterMeasure + intent.count).map { measure ->
            editedScore.getTimeSignatureAt(measure).measureDuration()
        }
        val workspaceEdit = HarmonyWorkspaceEditor.applyResult(
            workspace,
            HarmonyWorkspaceCommand.InsertMeasureSpace(
                onset = insertionOnset,
                measureDurations = measureDurations,
                chordDuration = intent.chordDuration,
            ),
        )
        if (!workspaceEdit.succeeded) {
            return result(
                baseRevision,
                FreePracticeEffect(
                    FreePracticeEffectKind.INVALID,
                    "freePractice.workspace.invalid",
                    workspaceEdit.errorMessage?.let { mapOf("reason" to it) }.orEmpty(),
                ),
            )
        }
        transaction.commit(editedScore, computeScore(editedScore), workspaceEdit.state)
        scoreSession.notifyExternalCommit()
        cancelPending(PracticeWritingOutcome.Cancelled)
        revision++
        return result(baseRevision, FreePracticeEffect(FreePracticeEffectKind.APPLIED))
    }

    private fun setHarmonicRole(
        intent: FreePracticeIntent.SetHarmonicRole,
        baseRevision: Long,
    ): FreePracticeDispatchResult {
        val valid = manager.currentState.runtimeScore.getAllVoiceEvents()
            .flatMap { event -> event.pitches.indices.map { index ->
                com.mecon.exploration.PracticeNoteheadRef(event.id, index)
            } }
            .toSet()
        if (intent.noteheads.isEmpty() || !valid.containsAll(intent.noteheads)) {
            return result(baseRevision, FreePracticeEffect(
                FreePracticeEffectKind.STALE_TARGET,
                "freePractice.notehead.staleTarget",
            ))
        }
        val nextRoles = noteConstraints.harmonicRoles.associate { it.notehead to it.role }.toMutableMap().apply {
            intent.noteheads.forEach { notehead ->
                if (intent.role == null) remove(notehead) else put(notehead, intent.role)
            }
        }
        val next = noteConstraints.copy(harmonicRoles = nextRoles.map { (notehead, role) ->
            com.mecon.exploration.PracticeHarmonicRoleMark(notehead, role)
        })
        if (next == noteConstraints) return noOp(baseRevision)
        val runtime = manager.currentState.runtimeScore
        val timeMap = ScoreTimeMap.from(runtime)
        val eventById = runtime.getAllVoiceEvents().associateBy { it.id }
        val markedSlots = intent.noteheads.mapNotNullTo(linkedSetOf()) { notehead ->
            eventById[notehead.eventId]?.let { event ->
                val onset = timeMap.absolute(event.onset)
                workspace.slots.firstOrNull { onset >= it.onset && onset < it.onset + it.duration }?.id
            }
        }
        if (markedSlots.size == 1) selectedSlotId = markedSlots.single()
        return commitNoteConstraints(baseRevision, next)
    }

    private fun setNoteheadLock(
        intent: FreePracticeIntent.SetNoteheadLock,
        baseRevision: Long,
    ): FreePracticeDispatchResult {
        val valid = manager.currentState.runtimeScore.getAllVoiceEvents().flatMap { event ->
            event.pitches.indices.map { index -> com.mecon.exploration.PracticeNoteheadRef(event.id, index) }
        }.toSet()
        if (intent.noteheads.isEmpty() || !valid.containsAll(intent.noteheads)) {
            return result(baseRevision, FreePracticeEffect(
                FreePracticeEffectKind.STALE_TARGET,
                "freePractice.notehead.staleTarget",
            ))
        }
        val locks = noteConstraints.lockedNoteheads.toMutableSet().apply {
            if (intent.locked) addAll(intent.noteheads) else removeAll(intent.noteheads)
        }
        return commitNoteConstraints(baseRevision, noteConstraints.copy(lockedNoteheads = locks))
    }

    private fun setVoiceLock(
        intent: FreePracticeIntent.SetVoiceLock,
        baseRevision: Long,
    ): FreePracticeDispatchResult = setVoiceLocks(
        FreePracticeIntent.SetVoiceLocks(baseRevision, setOf(intent.voiceTrackId), intent.locked),
        baseRevision,
    )

    private fun setVoiceLocks(
        intent: FreePracticeIntent.SetVoiceLocks,
        baseRevision: Long,
    ): FreePracticeDispatchResult {
        if (intent.voiceTrackIds.isEmpty() ||
            !manager.currentState.runtimeScore.voiceTracks.keys.containsAll(intent.voiceTrackIds)
        ) {
            return result(baseRevision, FreePracticeEffect(
                FreePracticeEffectKind.STALE_TARGET,
                "freePractice.voice.staleTarget",
            ))
        }
        val locks = noteConstraints.lockedVoiceTrackIds.toMutableSet().apply {
            if (intent.locked) addAll(intent.voiceTrackIds) else removeAll(intent.voiceTrackIds)
        }
        return commitNoteConstraints(baseRevision, noteConstraints.copy(lockedVoiceTrackIds = locks))
    }

    private fun setStaffLock(
        intent: FreePracticeIntent.SetStaffLock,
        baseRevision: Long,
    ): FreePracticeDispatchResult = setStaffLocks(
        FreePracticeIntent.SetStaffLocks(baseRevision, setOf(intent.staffTrackId), intent.locked),
        baseRevision,
    )

    private fun setStaffLocks(
        intent: FreePracticeIntent.SetStaffLocks,
        baseRevision: Long,
    ): FreePracticeDispatchResult {
        if (intent.staffTrackIds.isEmpty() ||
            !manager.currentState.runtimeScore.staffTracks.keys.containsAll(intent.staffTrackIds)
        ) {
            return result(baseRevision, FreePracticeEffect(
                FreePracticeEffectKind.STALE_TARGET,
                "freePractice.staff.staleTarget",
            ))
        }
        val locks = noteConstraints.lockedStaffTrackIds.toMutableSet().apply {
            if (intent.locked) addAll(intent.staffTrackIds) else removeAll(intent.staffTrackIds)
        }
        return commitNoteConstraints(baseRevision, noteConstraints.copy(lockedStaffTrackIds = locks))
    }

    private fun commitNoteConstraints(
        baseRevision: Long,
        next: PracticeNoteConstraintState,
    ): FreePracticeDispatchResult {
        if (next == noteConstraints) return noOp(baseRevision)
        val current = manager.currentState
        manager.commitNewState(current.runtimeScore, current.computedScore) { noteConstraints = next }
        scoreSession.notifyExternalCommit()
        cancelPending(PracticeWritingOutcome.Cancelled)
        activeTeachingCatalogRequest = null
        revision++
        return result(baseRevision, FreePracticeEffect(FreePracticeEffectKind.APPLIED))
    }

    private fun rebuildPractice(
        intent: FreePracticeIntent.RebuildPractice,
        baseRevision: Long,
    ): FreePracticeDispatchResult {
        val key = com.mecon.theory.ModulationKey(intent.fifths, intent.mode.toTheory())
        val rebuilt = FreePracticePreset.document(intent.polyphonyLimit, key)
        val keySignature = when (intent.mode) {
            WorkspaceKeyMode.MAJOR -> KeySignature.majorByFifths(intent.fifths)
            WorkspaceKeyMode.MINOR -> KeySignature.minorByFifths(intent.fifths)
        }
        val runtime = RuntimeScore.fromStorage(
            VoicePlanScoreAssembler.emptyPracticeScore(
                rebuilt.workspace,
                keySignature,
                rebuilt.settings.staffVoices,
            )
        )
        if (!transaction.commit(runtime, computeScore(runtime), rebuilt.workspace)) return noOp(baseRevision)
        scoreSession.notifyExternalCommit()
        settings = rebuilt.settings.copy(writing = settings.writing)
        noteConstraints = PracticeNoteConstraintState()
        migrationDiagnostics = emptyList()
        selectedSlotId = rebuilt.workspace.slots.firstOrNull()?.id
        cancelPending(PracticeWritingOutcome.Cancelled)
        teachingCatalog = PracticeIdiomCatalogView(includeOffKey = catalogIncludeOffKey)
        activeTeachingCatalogRequest = null
        revision++
        return result(
            baseRevision,
            FreePracticeEffect(
                FreePracticeEffectKind.PRACTICE_REBUILT,
                "freePractice.rebuilt",
            ),
        )
    }

    private fun updateStaffVoices(
        intent: FreePracticeIntent.UpdateStaffVoices,
        baseRevision: Long,
    ): FreePracticeDispatchResult {
        if (intent.staffVoices.capacity != settings.polyphonyLimit) {
            return result(
                baseRevision,
                FreePracticeEffect(FreePracticeEffectKind.INVALID, "freePractice.staffVoices.invalidCapacity"),
            )
        }
        if (intent.staffVoices == settings.staffVoices) return noOp(baseRevision)
        val storage = VoicePlanScoreAssembler.migrateToGrandStaff(
            score = manager.currentState.runtimeScore.toStorage(),
            workspace = workspace,
            staffVoices = intent.staffVoices,
        )
        val runtime = RuntimeScore.fromStorage(storage)
        if (!transaction.commit(runtime, computeScore(runtime), workspace)) return noOp(baseRevision)
        scoreSession.notifyExternalCommit()
        settings = settings.copy(staffVoices = intent.staffVoices)
        cancelPending(PracticeWritingOutcome.Cancelled)
        revision++
        return result(baseRevision, FreePracticeEffect(FreePracticeEffectKind.APPLIED))
    }

    /** Pure timeline projection for pointer/keyboard previews; never mutates history or revisions. */
    fun previewTimelineEdit(request: PracticeTimelinePreviewRequest): PracticeTimelinePreviewResult {
        if (request.baseRevision != revision) {
            return PracticeTimelinePreviewResult(
                requestId = request.requestId,
                baseRevision = request.baseRevision,
                accepted = false,
                reasonKey = "freePractice.staleRevision",
            )
        }
        val base = editBase
        val command = timelineCommand(request.edit, base)
            ?: return PracticeTimelinePreviewResult(
                requestId = request.requestId,
                baseRevision = request.baseRevision,
                accepted = false,
                reasonKey = "freePractice.staleTarget",
            )
        val edit = HarmonyWorkspaceEditor.applyResult(base, command)
        if (!edit.succeeded) {
            return PracticeTimelinePreviewResult(
                requestId = request.requestId,
                baseRevision = request.baseRevision,
                accepted = false,
                reasonKey = "freePractice.workspace.invalid",
            )
        }
        val previewScore = synchronizedTimelineScore(
            manager.currentState.runtimeScore,
            edit.state,
            trimEmptyTail = true,
        )
        return PracticeTimelinePreviewResult(
            requestId = request.requestId,
            baseRevision = request.baseRevision,
            accepted = true,
            timeline = FreePracticeViewProjector.timeline(
                edit.state,
                teachingCatalog,
                VoicePlanScoreAssembler.scoreDuration(previewScore),
                measureBoundaries(previewScore),
                settings.defaultChordDuration,
            ),
        )
    }

    fun applyBackgroundResult(value: PracticeBackgroundResult): FreePracticeDispatchResult {
        beginOperation()
        val request = activeRequest
        if (request == null || value.requestId != request.requestId ||
            value.baseRevision != revision || value.scopeFingerprint != request.scopeFingerprint
        ) {
            return result(
                value.baseRevision,
                FreePracticeEffect(
                    FreePracticeEffectKind.STALE_BACKGROUND_RESULT,
                    "freePractice.staleBackgroundResult",
                ),
            )
        }
        activeRequest = null
        if (value.kind == PracticeBackgroundRequestKind.OPTIMIZE_CANDIDATES) {
            candidates = (candidates + value.candidates).distinctBy { it.diversityGroupKey }
            writing = writing.copy(
                phase = PracticeWritingPhase.READY,
                canAlternate = nextCandidateIndex < candidates.size,
            )
            revision++
            return result(revision - 1, FreePracticeEffect(FreePracticeEffectKind.APPLIED))
        }
        val targetWorkspace = request.document.workspace
        if (value.outcome !is PracticeWritingOutcome.Solved || value.candidates.isEmpty()) {
            val synchronized = RuntimeScore.fromStorage(request.score)
            transaction.commit(synchronized, computeScore(synchronized), targetWorkspace)
            scoreSession.notifyExternalCommit()
            writing = PracticeWritingStatus(
                phase = PracticeWritingPhase.READY,
                outcome = value.outcome,
                lastScope = request.scopeSlotIds,
            )
            revision++
            return result(
                revision - 1,
                FreePracticeEffect(
                    FreePracticeEffectKind.INVALID,
                    messageKeyFor(value.outcome),
                ),
            )
        }
        val primary = value.candidates.first()
        val materialized = FreePracticeVoicingMaterializer.materialize(
            RuntimeScore.fromStorage(request.score),
            targetWorkspace,
            primary,
            request.document.noteConstraints,
        )
        val computed = computeScore(materialized.score)
        transaction.commit(materialized.score, computed, targetWorkspace)
        scoreSession.notifyExternalCommit()
        candidates = value.candidates
        nextCandidateIndex = 1
        val replay = replayRange(request.scopeSlotIds, request.replayWholeScope)
        val outcome = PracticeWritingOutcome.Solved(request.scopeSlotIds, replay)
        writing = PracticeWritingStatus(
            phase = PracticeWritingPhase.READY,
            outcome = outcome,
            canAlternate = nextCandidateIndex < candidates.size,
            lastScope = request.scopeSlotIds,
        )
        revision++
        val optimize = backgroundRequest(
            scope = request.toScope(),
            kind = PracticeBackgroundRequestKind.OPTIMIZE_CANDIDATES,
            excluded = candidates.mapTo(linkedSetOf()) { it.diversityGroupKey },
        )
        activeRequest = optimize
        return result(
            revision - 1,
            FreePracticeEffect(FreePracticeEffectKind.WRITING_APPLIED, "freePractice.writing.solved"),
            listOf(optimize),
            editPlayback = replay?.let(PracticeEditPlayback::Excerpt),
        )
    }

    /**
     * Releases a writing request whose background channel crashed.
     *
     * A first solve is never committed while it runs — [requestWritingForWorkspace] only parks the
     * prepared workspace on the request — so dropping the request *is* the rollback to the last
     * good state: the visible workspace falls back to the committed one and the score was never
     * touched. Do not commit the request's own snapshot here: after a crash the pending workspace
     * is exactly what we cannot vouch for.
     *
     * A crashed candidate optimization is different. Its first solve already committed, so this
     * only unlocks the workbench and keeps the applied writing and its alternates.
     */
    fun applyBackgroundFailure(value: PracticeBackgroundFailure): FreePracticeDispatchResult {
        beginOperation()
        val request = activeRequest
        if (request == null || value.requestId != request.requestId) {
            return result(
                revision,
                FreePracticeEffect(
                    FreePracticeEffectKind.STALE_BACKGROUND_RESULT,
                    "freePractice.staleBackgroundResult",
                ),
            )
        }
        activeRequest = null
        val baseRevision = revision
        val arguments = mapOf("reason" to value.reason)
        if (request.kind == PracticeBackgroundRequestKind.OPTIMIZE_CANDIDATES) {
            writing = writing.copy(
                phase = PracticeWritingPhase.READY,
                canAlternate = nextCandidateIndex < candidates.size,
            )
            revision++
            return result(
                baseRevision,
                FreePracticeEffect(
                    FreePracticeEffectKind.INVALID,
                    "freePractice.writing.alternateFailed",
                    arguments,
                ),
            )
        }
        candidates = emptyList()
        nextCandidateIndex = 1
        writing = PracticeWritingStatus(
            phase = PracticeWritingPhase.READY,
            outcome = PracticeWritingOutcome.Failed(value.reason),
            lastScope = writing.lastScope,
        )
        revision++
        return result(
            baseRevision,
            FreePracticeEffect(
                FreePracticeEffectKind.INVALID,
                "freePractice.writing.failed",
                arguments,
            ),
        )
    }

    fun applyTeachingCatalogResult(value: PracticeTeachingCatalogResult): FreePracticeDispatchResult {
        beginOperation()
        val request = activeTeachingCatalogRequest
        // Same rule as findings: the catalog fingerprint already describes its inputs exactly, so
        // an intervening revision must not discard an otherwise valid answer.
        if (request == null || value.requestId != request.requestId ||
            value.baseRevision != request.baseRevision ||
            value.fingerprint != request.fingerprint ||
            value.fingerprint != teachingCatalogFingerprint()
        ) {
            return result(
                value.baseRevision,
                FreePracticeEffect(
                    FreePracticeEffectKind.STALE_BACKGROUND_RESULT,
                    "freePractice.staleCatalogResult",
                ),
            )
        }
        activeTeachingCatalogRequest = null
        teachingCatalog = PracticeIdiomCatalogView(
            generation = teachingCatalog.generation + 1,
            requestKey = value.fingerprint,
            loading = false,
            errorKey = value.errorKey,
            includeOffKey = catalogIncludeOffKey,
            definitions = value.definitions,
            pivotRecipes = value.pivotRecipes,
        )
        return result(
            value.baseRevision,
            FreePracticeEffect(FreePracticeEffectKind.CATALOG_UPDATED),
        )
    }

    fun applyFindingResult(value: PracticeFindingResult): FreePracticeDispatchResult {
        beginOperation()
        val request = activeFindingRequest
        // Validated against the inputs it was computed from, not against the current revision:
        // a selection made while findings were running does not invalidate them, and rejecting the
        // answer would leave the panel permanently stale because no new request is due either.
        if (request == null || value.requestId != request.requestId ||
            value.baseRevision != request.baseRevision ||
            value.fingerprint != request.fingerprint ||
            value.fingerprint != findingInputFingerprint()
        ) {
            return result(
                value.baseRevision,
                FreePracticeEffect(
                    FreePracticeEffectKind.STALE_BACKGROUND_RESULT,
                    "freePractice.staleFindingResult",
                ),
            )
        }
        activeFindingRequest = null
        findingsFingerprint = value.fingerprint
        findingsGeneration++
        findings = PracticeFindingsView(findingsGeneration, stale = false, items = value.items)
        return result(
            value.baseRevision,
            FreePracticeEffect(FreePracticeEffectKind.FINDINGS_UPDATED),
        )
    }

    /**
     * Releases a crashed teaching-catalog request. Without this the view stays `loading` forever:
     * the pending request is what suppresses the next one for the same fingerprint.
     */
    fun applyTeachingCatalogFailure(value: PracticeBackgroundFailure): FreePracticeDispatchResult {
        beginOperation()
        val request = activeTeachingCatalogRequest
        if (request == null || value.requestId != request.requestId) {
            return result(
                revision,
                FreePracticeEffect(
                    FreePracticeEffectKind.STALE_BACKGROUND_RESULT,
                    "freePractice.staleCatalogResult",
                ),
            )
        }
        activeTeachingCatalogRequest = null
        teachingCatalog = teachingCatalog.copy(
            generation = teachingCatalog.generation + 1,
            loading = false,
            errorKey = "freePractice.catalog.failed",
        )
        return result(
            request.baseRevision,
            FreePracticeEffect(
                FreePracticeEffectKind.INVALID,
                "freePractice.catalog.failed",
                mapOf("reason" to value.reason),
            ),
        )
    }

    /**
     * Releases a crashed finding request. The fingerprint is recorded as attempted so the very
     * next [result] does not immediately re-issue the same request and spin on a reproducible
     * crash; the panel stays marked stale and any later input change asks again.
     */
    fun applyFindingFailure(value: PracticeBackgroundFailure): FreePracticeDispatchResult {
        beginOperation()
        val request = activeFindingRequest
        if (request == null || value.requestId != request.requestId) {
            return result(
                revision,
                FreePracticeEffect(
                    FreePracticeEffectKind.STALE_BACKGROUND_RESULT,
                    "freePractice.staleFindingResult",
                ),
            )
        }
        activeFindingRequest = null
        findingsFingerprint = request.fingerprint
        findings = findings.copy(stale = true)
        return result(
            request.baseRevision,
            FreePracticeEffect(
                FreePracticeEffectKind.INVALID,
                "freePractice.findings.failed",
                mapOf("reason" to value.reason),
            ),
        )
    }

    /** Serializes an already-computed result; [FreePracticeDispatchResult.frame] is never rebuilt. */
    fun toWireUpdate(result: FreePracticeDispatchResult): FreePracticeUpdate = update(
        baseRevision = result.baseRevision,
        effect = result.effect,
        requests = result.requests,
        catalogRequests = result.catalogRequests,
        findingRequests = result.findingRequests,
        scoreUpdate = result.scoreUpdate,
        editPlayback = result.editPlayback,
        prepared = result.frame,
    )

    private fun dispatchScore(
        intent: FreePracticeIntent.Score,
        baseRevision: Long,
    ): FreePracticeDispatchResult {
        val inner = scoreSession.dispatch(intent.inner)
        val innerUpdate = scoreSession.wireUpdate(inner)
        val audition = scoreAudition(inner.frame.selection)
        if (inner.effect.kind == ScoreEditEffectKind.STALE_REVISION ||
            inner.effect.kind == ScoreEditEffectKind.CONFLICT
        ) {
            return result(
                baseRevision,
                FreePracticeEffect(
                    FreePracticeEffectKind.INVALID,
                    inner.effect.messageKey ?: "freePractice.scoreEditRejected",
                ),
                scoreUpdate = innerUpdate,
            )
        }
        val harmonySelectionChanged = intent.inner is ScoreEditIntent.SetSelection &&
            selectHarmonySlotAtScoreSelection(inner.frame.selection)
        if (inner.effect.kind == ScoreEditEffectKind.NO_OP && !harmonySelectionChanged) {
            return result(
                baseRevision,
                FreePracticeEffect(FreePracticeEffectKind.NO_OP, inner.effect.messageKey),
                scoreUpdate = innerUpdate,
                editPlayback = audition.takeIf { intent.inner is ScoreEditIntent.SetSelection },
            )
        }
        revision++
        if (inner.frame.scoreChanged) cancelPending(PracticeWritingOutcome.Cancelled)
        val effectKind = if (inner.effect.kind == ScoreEditEffectKind.SELECTION_CHANGED ||
            harmonySelectionChanged
        ) {
            FreePracticeEffectKind.SELECTION_CHANGED
        } else {
            FreePracticeEffectKind.APPLIED
        }
        return result(
            baseRevision,
            FreePracticeEffect(effectKind, inner.effect.messageKey),
            scoreUpdate = innerUpdate,
            editPlayback = audition,
        )
    }

    /** Keep the harmony focus aligned with a score-note selection, including an unfilled target slot. */
    private fun selectHarmonySlotAtScoreSelection(selection: List<ScoreSelectionTarget>): Boolean {
        val eventIds = selection.filterIsInstance<ScoreSelectionTarget.Event>()
            .map { it.eventId }
            .distinct()
        if (eventIds.isEmpty()) return false
        val eventsById = manager.currentState.runtimeScore.getAllVoiceEvents().associateBy { it.id }
        val selectedEvents = eventIds.mapNotNull(eventsById::get)
        if (selectedEvents.size != eventIds.size || selectedEvents.any { it.isRest }) return false
        val timeMap = ScoreTimeMap.from(manager.currentState.runtimeScore)
        val matchingSlots = selectedEvents.mapNotNullTo(linkedSetOf()) { event ->
            val onset = timeMap.absolute(event.onset)
            workspace.slots.firstOrNull { slot ->
                onset >= slot.onset && onset < slot.onset + slot.duration
            }
        }
        val slot = matchingSlots.singleOrNull() ?: return false
        return selectSlotState(slot)
    }

    private fun scoreAudition(selection: List<ScoreSelectionTarget>): PracticeEditPlayback.Audition? {
        val eventId = selection.mapNotNull { it.eventIdOrNull }.distinct().singleOrNull() ?: return null
        val event = scoreSession.frame().computedScore.getComputedEvent(eventId) ?: return null
        if (event.isRest) return null
        val selectedPitchIndices = (selection.singleOrNull() as? ScoreSelectionTarget.Event)?.pitchIndices
        val indices = selectedPitchIndices?.filter { it in event.pitchData.indices }
            ?: event.pitchData.indices.toList()
        val midiNumbers = indices.map { event.pitchData[it].midiPitch }.distinct()
        return midiNumbers.takeIf { it.isNotEmpty() }?.let(PracticeEditPlayback::Audition)
    }

    private fun selectSlot(
        intent: FreePracticeIntent.SelectSlot,
        baseRevision: Long,
    ): FreePracticeDispatchResult {
        if (workspace.slots.none { it.id == intent.slotId }) return staleTarget(baseRevision, intent.slotId)
        val slot = workspace.slots.first { it.id == intent.slotId }
        if (!selectSlotState(slot)) return noOp(baseRevision)
        revision++
        return result(baseRevision, FreePracticeEffect(FreePracticeEffectKind.SELECTION_CHANGED))
    }

    private fun selectSlotState(
        slot: com.mecon.theory.freepractice.WorkspaceHarmonySlot,
    ): Boolean {
        val tonalLayoutId = workspace.selectedTonalLayout(slot)?.id
        // A slot may be both the tail of one customary progression and the start of another.
        // Only the first chord carries implicit progression selection; middle/tail chords remain
        // plain chord selections so choosing a catalog item there inserts a continuation.
        val idiomInstanceId = workspace.idiomInstanceStartingAt(slot.id)?.id
        if (selectedSlotId == slot.id && selectedTonalLayoutId == tonalLayoutId &&
            selectedIdiomInstanceId == idiomInstanceId
        ) return false
        selectedSlotId = slot.id
        selectedTonalLayoutId = tonalLayoutId
        selectedIdiomInstanceId = idiomInstanceId
        return true
    }

    private fun selectTonalLayout(
        intent: FreePracticeIntent.SelectTonalLayout,
        baseRevision: Long,
    ): FreePracticeDispatchResult {
        if (workspace.tonalLayouts.none { it.id == intent.tonalLayoutId }) {
            return staleTonalLayoutTarget(baseRevision, intent.tonalLayoutId)
        }
        if (selectedTonalLayoutId == intent.tonalLayoutId) return noOp(baseRevision)
        selectedTonalLayoutId = intent.tonalLayoutId
        revision++
        return result(baseRevision, FreePracticeEffect(FreePracticeEffectKind.SELECTION_CHANGED))
    }

    private fun selectIdiomTonalLayout(
        intent: FreePracticeIntent.SelectIdiomTonalLayout,
        baseRevision: Long,
    ): FreePracticeDispatchResult {
        val selected = selectedSlotId?.let { id -> workspace.slots.firstOrNull { it.id == id } }
            ?: return noOp(baseRevision)
        if (workspace.activeTonalLayouts(selected.onset).none { it.id == intent.tonalLayoutId }) {
            return staleTonalLayoutTarget(baseRevision, intent.tonalLayoutId)
        }
        if (selectedIdiomTonalLayoutId == intent.tonalLayoutId) return noOp(baseRevision)
        selectedIdiomTonalLayoutId = intent.tonalLayoutId
        activeTeachingCatalogRequest = null
        revision++
        return result(baseRevision, FreePracticeEffect(FreePracticeEffectKind.SELECTION_CHANGED))
    }

    private fun selectIdiom(
        intent: FreePracticeIntent.SelectIdiom,
        baseRevision: Long,
    ): FreePracticeDispatchResult {
        val instance = workspace.idiomInstances.firstOrNull { it.id == intent.idiomInstanceId }
            ?: return staleIdiomTarget(baseRevision, intent.idiomInstanceId)
        val firstSlotId = instance.slotIds.mapNotNull { id -> workspace.slots.firstOrNull { it.id == id } }
            .minByOrNull { it.onset }?.id
            ?: return staleIdiomTarget(baseRevision, intent.idiomInstanceId)
        if (selectedIdiomInstanceId == instance.id && selectedSlotId == firstSlotId) return noOp(baseRevision)
        selectedIdiomInstanceId = instance.id
        selectedSlotId = firstSlotId
        revision++
        return result(baseRevision, FreePracticeEffect(FreePracticeEffectKind.SELECTION_CHANGED))
    }

    private fun HarmonyWorkspaceState.idiomInstanceStartingAt(
        slotId: WorkspaceSlotId,
    ) = idiomInstancesForSlot(slotId).firstOrNull { instance ->
        firstSlotId(instance.id) == slotId
    }

    private fun HarmonyWorkspaceState.firstSlotId(
        idiomInstanceId: WorkspaceIdiomInstanceId,
    ): WorkspaceSlotId? = idiomInstances.firstOrNull { it.id == idiomInstanceId }
        ?.slotIds
        ?.mapNotNull { id -> slots.firstOrNull { it.id == id } }
        ?.minByOrNull { it.onset }
        ?.id

    private fun workspaceCommand(
        baseRevision: Long,
        slotId: WorkspaceSlotId,
        afterCommit: (HarmonyWorkspaceState, HarmonyWorkspaceState) -> Unit = { _, _ -> },
        command: (Int) -> HarmonyWorkspaceCommand,
    ): FreePracticeDispatchResult {
        val index = editBase.slots.indexOfFirst { it.id == slotId }
        if (index < 0) return staleTarget(baseRevision, slotId)
        return applyWorkspaceCommand(
            baseRevision,
            command(index),
            afterCommit = afterCommit,
        )
    }

    /**
     * Commits the same [PracticeTimelineEdit] shape the platform previewed, through the same
     * [timelineCommand] mapping. Only whether a re-solve is triggered differs, and that is derived
     * from the edit rather than restated per intent.
     */
    private fun timelineEdit(
        edit: PracticeTimelineEdit,
        baseRevision: Long,
    ): FreePracticeDispatchResult {
        val trigger = edit.autoWritingTriggerSlotId
        val command = timelineCommand(edit, editBase) ?: return when {
            trigger != null -> staleTarget(baseRevision, trigger)
            edit is PracticeTimelineEdit.SetTonalLayoutBounds ->
                staleTonalLayoutTarget(baseRevision, edit.tonalLayoutId)
            else -> invalidScope(baseRevision)
        }
        return if (trigger == null) {
            applyWorkspaceCommand(baseRevision, command, trimEmptyTail = true)
        } else {
            workspaceCommandWithOptionalWriting(
                baseRevision,
                trigger,
                command,
                trimEmptyTail = true,
            )
        }
    }

    private fun timelineCommand(
        edit: PracticeTimelineEdit,
        source: HarmonyWorkspaceState,
    ): HarmonyWorkspaceCommand? = when (edit) {
        is PracticeTimelineEdit.PlaceChordRange -> source.slotIndex(edit.slotId)?.let { index ->
            HarmonyWorkspaceCommand.PlaceChordRange(index, edit.onset, edit.duration)
        }
        is PracticeTimelineEdit.MoveSharedBoundary -> source.slotIndex(edit.leftSlotId)?.let { index ->
            HarmonyWorkspaceCommand.MoveSharedBoundary(index, edit.boundary)
        }
        is PracticeTimelineEdit.TranslateChordRange -> source.slotIndex(edit.slotId)?.let { index ->
            HarmonyWorkspaceCommand.TranslateChordRange(index, edit.delta, edit.includeFollowing)
        }
        is PracticeTimelineEdit.MoveBoundaryWithFollowing -> source.slotIndex(edit.leftSlotId)?.let { index ->
            HarmonyWorkspaceCommand.MoveBoundaryWithFollowing(index, edit.boundary)
        }
        is PracticeTimelineEdit.SetTonalLayoutBounds ->
            edit.tonalLayoutId.takeIf { id -> source.tonalLayouts.any { it.id == id } }?.let { id ->
                HarmonyWorkspaceCommand.SetTonalLayoutBounds(id, edit.start, edit.end)
            }
    }

    private fun HarmonyWorkspaceState.slotIndex(id: WorkspaceSlotId): Int? =
        slots.indexOfFirst { it.id == id }.takeIf { it >= 0 }

    private fun insertIdiom(
        intent: FreePracticeIntent.InsertIdiom,
        baseRevision: Long,
    ): FreePracticeDispatchResult {
        if (activeRequest != null) return invalidScope(baseRevision)
        val anchor = workspace.slots.firstOrNull { it.id == intent.anchorSlotId }
            ?: return staleTarget(baseRevision, intent.anchorSlotId)
        val definition = teachingCatalog.definitions.firstOrNull { it.id == intent.definitionId }
            ?: return staleCatalogTarget(baseRevision)
        val variant = definition.variants.firstOrNull { it.id == intent.variantId }
            ?: return staleCatalogTarget(baseRevision)
        val lead = variant.durations.take(variant.anchorStepIndex).fold(Fraction.ZERO) { total, duration ->
            total + duration
        }
        val onset = anchor.onset - lead
        if (onset.isNegative) return invalidScope(baseRevision)
        val sourceLayout = selectedIdiomCatalogLayout()
        val sourceKey = sourceLayout?.key
            ?: workspace.idiomSourceKeyAt(onset)
            ?: PracticeFindingComputer.fallbackKey(document())
        val targetKey = variant.suggestedKey?.toTheoryKey() ?: sourceKey
        val chordChoices = resolveIdiomChoices(variant, targetKey) ?: return staleCatalogTarget(baseRevision)
        if (idiomCatalogRoleFilterEnabled && !acceptsIdiomRoles(onset, variant.durations, chordChoices)) {
            return result(baseRevision, FreePracticeEffect(
                FreePracticeEffectKind.INVALID,
                "freePractice.harmonicRole.conflict",
            ))
        }
        val tonalities = workspace.resolveIdiomTonalities(onset, chordChoices, sourceKey, targetKey)
            ?: return invalidScope(baseRevision)
        val edit = HarmonyWorkspaceEditor.applyResult(
            workspace,
            HarmonyWorkspaceCommand.InsertIdiom(
                onset = onset,
                definitionId = definition.id,
                variantId = variant.id,
                sourceExerciseId = definition.sourceExerciseId,
                sourceChapterId = definition.sourceChapterId,
                tonalLayoutId = sourceLayout?.id
                    ?: workspace.activeTonalLayouts(onset).firstOrNull()?.id
                    ?: workspace.tonalLayouts.firstOrNull()?.id,
                chordIdentities = emptyList(),
                durations = variant.durations,
                parameters = variant.parameters,
                chordChoices = chordChoices,
                tonalities = tonalities,
                fixedInversionStepIndices = variant.fixedInversionStepIndices,
            ),
        )
        if (!edit.succeeded || edit.state == workspace) return invalidScope(baseRevision)
        val inserted = edit.state.idiomInstances.last()
        selectedSlotId = inserted.slotIds.first()
        selectedIdiomInstanceId = inserted.id
        return commitIdiomEdit(baseRevision, edit.state, inserted.slotIds)
    }

    private fun replaceIdiom(
        intent: FreePracticeIntent.ReplaceIdiom,
        baseRevision: Long,
    ): FreePracticeDispatchResult {
        if (activeRequest != null) return invalidScope(baseRevision)
        val instance = workspace.idiomInstances.firstOrNull { it.id == intent.idiomInstanceId }
            ?: return staleIdiomTarget(baseRevision, intent.idiomInstanceId)
        val definition = teachingCatalog.definitions.firstOrNull { it.id == intent.definitionId }
            ?: return staleCatalogTarget(baseRevision)
        val variant = definition.variants.firstOrNull { it.id == intent.variantId }
            ?: return staleCatalogTarget(baseRevision)
        val onset = instance.slotIds.mapNotNull { id -> workspace.slots.firstOrNull { it.id == id }?.onset }
            .minOrNull() ?: return staleIdiomTarget(baseRevision, instance.id)
        val sourceLayout = selectedIdiomCatalogLayout()
        val sourceKey = sourceLayout?.key
            ?: workspace.idiomSourceKeyAt(onset)
            ?: PracticeFindingComputer.fallbackKey(document())
        val targetKey = variant.suggestedKey?.toTheoryKey() ?: sourceKey
        val chordChoices = resolveIdiomChoices(variant, targetKey) ?: return staleCatalogTarget(baseRevision)
        if (idiomCatalogRoleFilterEnabled && !acceptsIdiomRoles(onset, variant.durations, chordChoices)) {
            return result(baseRevision, FreePracticeEffect(
                FreePracticeEffectKind.INVALID,
                "freePractice.harmonicRole.conflict",
            ))
        }
        val tonalities = workspace.resolveIdiomTonalities(onset, chordChoices, sourceKey, targetKey)
            ?: return invalidScope(baseRevision)
        val edit = HarmonyWorkspaceEditor.applyResult(
            workspace,
            HarmonyWorkspaceCommand.ReplaceIdiom(
                id = instance.id,
                definitionId = definition.id,
                sourceExerciseId = definition.sourceExerciseId,
                sourceChapterId = definition.sourceChapterId,
                tonalLayoutId = sourceLayout?.id
                    ?: workspace.activeTonalLayouts(onset).firstOrNull()?.id
                    ?: instance.tonalLayoutId,
                variantId = variant.id,
                chordIdentities = emptyList(),
                durations = variant.durations,
                parameters = variant.parameters,
                chordChoices = chordChoices,
                tonalities = tonalities,
                fixedInversionStepIndices = variant.fixedInversionStepIndices,
            ),
        )
        if (!edit.succeeded || edit.state == workspace) return invalidScope(baseRevision)
        val replaced = edit.state.idiomInstances.first { it.id == instance.id }
        selectedSlotId = replaced.slotIds.first()
        selectedIdiomInstanceId = replaced.id
        return commitIdiomEdit(baseRevision, edit.state, replaced.slotIds)
    }

    private fun setIdiomChordToneCount(
        intent: FreePracticeIntent.SetIdiomChordToneCount,
        baseRevision: Long,
    ): FreePracticeDispatchResult {
        val instance = workspace.idiomInstances.firstOrNull { it.id == intent.idiomInstanceId }
            ?: return staleIdiomTarget(baseRevision, intent.idiomInstanceId)
        val definition = teachingCatalog.definitions.firstOrNull { it.id == instance.definitionId }
            ?: return staleCatalogTarget(baseRevision)
        val current = definition.variants.firstOrNull { it.id == instance.variantId }
            ?: return staleCatalogTarget(baseRevision)
        val currentCounts = current.effectiveToneCounts()
        if (intent.stepIndex !in currentCounts.indices) return invalidScope(baseRevision)
        if (currentCounts[intent.stepIndex] == intent.toneCount) return noOp(baseRevision)
        val currentFamily = current.realizationFamilyKey()
        val replacement = definition.variants.firstOrNull { candidate ->
            if (candidate.realizationFamilyKey() != currentFamily) return@firstOrNull false
            val counts = candidate.effectiveToneCounts()
            counts.size == currentCounts.size && counts.indices.all { index ->
                counts[index] == if (index == intent.stepIndex) intent.toneCount else currentCounts[index]
            }
        } ?: return invalidScope(baseRevision)
        return replaceIdiom(
            FreePracticeIntent.ReplaceIdiom(
                expectedRevision = intent.expectedRevision,
                idiomInstanceId = instance.id,
                definitionId = definition.id,
                variantId = replacement.id,
            ),
            baseRevision,
        )
    }

    private fun PracticeIdiomVariantView.effectiveToneCounts(): List<Int> =
        chordToneCounts.takeIf { it.size == chordIdentities.size }
            ?: chordChoices.map { it.pitchClasses.distinct().size }

    private fun commitIdiomEdit(
        baseRevision: Long,
        nextWorkspace: HarmonyWorkspaceState,
        slotIds: List<WorkspaceSlotId>,
    ): FreePracticeDispatchResult = if (settings.writing.autoWritingEnabled) {
        requestWritingForWorkspace(
            nextWorkspace = nextWorkspace,
            triggerSlotId = slotIds.last(),
            configuredBacktrack = settings.writing.backtrackChordCount,
            requiredSlotIds = slotIds,
        )
    } else {
        commitPreparedWorkspace(baseRevision, nextWorkspace)
    }

    private fun acceptsIdiomRoles(
        onset: Fraction,
        durations: List<Fraction>,
        choices: List<com.mecon.theory.freepractice.WorkspaceChordChoice>,
    ): Boolean {
        if (durations.size != choices.size) return false
        val constraints = PracticeNoteConstraintProjector.catalogConstraints(
            noteConstraints,
            manager.currentState.runtimeScore,
        )
        return constraints.all { constraint ->
            var cursor = onset
            val index = durations.indexOfFirst { duration ->
                val contains = constraint.onset >= cursor && constraint.onset < cursor + duration
                cursor += duration
                contains
            }
            if (index < 0) true else PracticeNoteConstraintProjector.accepts(
                choices[index],
                mapOf(constraint.pitchClass to constraint.role),
            )
        }
    }

    private fun resolveIdiomChoices(
        variant: PracticeIdiomVariantView,
        key: com.mecon.theory.ModulationKey,
    ): List<com.mecon.theory.freepractice.WorkspaceChordChoice>? {
        if (variant.chordChoices.isNotEmpty()) return variant.chordChoices
        val choices = ChordSelectionCatalog.choices(key)
        return variant.chordIdentities.map { symbol ->
            val match = choices.flatMap { choice ->
                choice.interpretationRefs.zip(choice.interpretationSymbols)
                    .filter { (_, routeSymbol) -> routeSymbol == symbol }
                    .map { (ref, _) -> choice to ref }
            }.distinctBy { it.second }.singleOrNull() ?: return null
            com.mecon.theory.freepractice.WorkspaceChordChoice.of(
                match.first.pitchClasses,
                match.first.origin,
                match.second,
            )
        }
    }

    private fun PracticeKeyView.toTheoryKey() =
        com.mecon.theory.ModulationKey(fifths, mode.toTheory())

    private fun staleCatalogTarget(baseRevision: Long) = result(
        baseRevision,
        FreePracticeEffect(FreePracticeEffectKind.STALE_TARGET, "freePractice.catalog.staleTarget"),
    )

    private fun staleIdiomTarget(baseRevision: Long, id: WorkspaceIdiomInstanceId) = result(
        baseRevision,
        FreePracticeEffect(
            FreePracticeEffectKind.STALE_TARGET,
            "freePractice.idiom.staleTarget",
            arguments = mapOf("idiomInstanceId" to id.value),
        ),
    )

    private fun staleTonalLayoutTarget(baseRevision: Long, id: WorkspaceTonalLayoutId) = result(
        baseRevision,
        FreePracticeEffect(
            FreePracticeEffectKind.STALE_TARGET,
            "freePractice.tonalLayout.staleTarget",
            arguments = mapOf("tonalLayoutId" to id.value),
        ),
    )

    private inline fun workspaceCommandWithOptionalWriting(
        baseRevision: Long,
        slotId: WorkspaceSlotId,
        command: (Int) -> HarmonyWorkspaceCommand,
    ): FreePracticeDispatchResult {
        val index = editBase.slots.indexOfFirst { it.id == slotId }
        if (index < 0) return staleTarget(baseRevision, slotId)
        return workspaceCommandWithOptionalWriting(baseRevision, slotId, command(index))
    }

    private fun workspaceCommandWithOptionalWriting(
        baseRevision: Long,
        slotId: WorkspaceSlotId,
        command: HarmonyWorkspaceCommand,
        trimEmptyTail: Boolean = false,
    ): FreePracticeDispatchResult {
        if (!settings.writing.autoWritingEnabled) {
            return applyWorkspaceCommand(baseRevision, command, trimEmptyTail = trimEmptyTail)
        }
        val base = editBase
        val edit = HarmonyWorkspaceEditor.applyResult(base, command)
        if (!edit.succeeded) {
            return result(
                baseRevision,
                FreePracticeEffect(
                    FreePracticeEffectKind.INVALID,
                    "freePractice.workspace.invalid",
                    arguments = edit.errorMessage?.let { mapOf("reason" to it) }.orEmpty(),
                ),
            )
        }
        if (edit.state == base) return noOp(baseRevision)
        return requestWritingForWorkspace(
            nextWorkspace = edit.state,
            triggerSlotId = slotId,
            configuredBacktrack = settings.writing.backtrackChordCount,
            trimEmptyTail = trimEmptyTail,
        )
    }

    private fun applyWorkspaceCommand(
        baseRevision: Long,
        command: HarmonyWorkspaceCommand,
        trimEmptyTail: Boolean = false,
        afterCommit: (HarmonyWorkspaceState, HarmonyWorkspaceState) -> Unit = { _, _ -> },
    ): FreePracticeDispatchResult {
        val before = editBase
        val edit = HarmonyWorkspaceEditor.applyResult(before, command)
        if (!edit.succeeded) {
            return result(
                baseRevision,
                FreePracticeEffect(
                    FreePracticeEffectKind.INVALID,
                    "freePractice.workspace.invalid",
                    arguments = edit.errorMessage?.let { mapOf("reason" to it) }.orEmpty(),
                ),
            )
        }
        if (edit.state === before || edit.state == before) return noOp(baseRevision)
        return commitPreparedWorkspace(baseRevision, edit.state, trimEmptyTail) {
            afterCommit(before, edit.state)
        }
    }

    private fun commitPreparedWorkspace(
        baseRevision: Long,
        nextWorkspace: HarmonyWorkspaceState,
        trimEmptyTail: Boolean = false,
        afterCommit: () -> Unit = {},
    ): FreePracticeDispatchResult {
        val runtime = synchronizedTimelineScore(
            manager.currentState.runtimeScore,
            nextWorkspace,
            trimEmptyTail,
        )
        val computed = if (runtime === manager.currentState.runtimeScore) {
            manager.currentState.computedScore
        } else {
            computeScore(runtime)
        }
        transaction.commit(runtime, computed, nextWorkspace)
        scoreSession.notifyExternalCommit()
        afterCommit()
        cancelPending(PracticeWritingOutcome.Cancelled)
        revision++
        return result(baseRevision, FreePracticeEffect(FreePracticeEffectKind.APPLIED))
    }

    private fun requestWriting(
        baseRevision: Long,
        triggerSlotId: WorkspaceSlotId,
        requiredSlotIds: List<WorkspaceSlotId>?,
    ): FreePracticeDispatchResult {
        if (workspace.slots.none { it.id == triggerSlotId }) return staleTarget(baseRevision, triggerSlotId)
        val runtime = manager.currentState.runtimeScore
        val projection = FreePracticeMaterialProjector.project(workspace, runtime)
        val completionEligible = completionEligibleSlotIds(workspace, runtime, projection)
        val scope = requiredSlotIds?.let {
            PracticeWritingScopePlanner.idiom(workspace, it, projection, completionEligible)
        }
            ?: PracticeWritingScopePlanner.automatic(
                workspace,
                projection,
                triggerSlotId,
                settings.writing.backtrackChordCount,
                completionEligible,
            )
            ?: return invalidScope(baseRevision)
        return startWriting(baseRevision, scope)
    }

    private fun rewriteSelection(
        baseRevision: Long,
    ): FreePracticeDispatchResult {
        val slotIds = PracticeSelectionScopeResolver.slotIds(
            scoreSession.frame().selection,
            manager.currentState.runtimeScore,
            workspace,
        )
        val scope = PracticeWritingScopePlanner.selected(
            workspace,
            slotIds.toSet(),
            FreePracticeMaterialProjector.project(workspace, manager.currentState.runtimeScore),
        ) ?: return invalidScope(baseRevision)
        return startWriting(baseRevision, scope)
    }

    /**
     * A preceding slot containing only protected melody is incomplete harmony, not an already
     * realized chord. It may be absorbed when a later chord starts auto-writing, while arbitrary
     * unprotected partial material keeps the existing no-silent-overwrite behavior.
     */
    private fun completionEligibleSlotIds(
        workspace: HarmonyWorkspaceState,
        score: RuntimeScore,
        projection: com.mecon.theory.freepractice.WorkspaceMaterialProjection,
    ): Set<WorkspaceSlotId> {
        val timeMap = ScoreTimeMap.from(score)
        val staffLockedVoiceIds = score.staffTracks.values
            .filter { it.id in noteConstraints.lockedStaffTrackIds }
            .flatMapTo(linkedSetOf()) { staff -> staff.voiceTracks.map { it.id } }
        val lockedVoiceIds = noteConstraints.lockedVoiceTrackIds + staffLockedVoiceIds
        return workspace.slots.mapNotNullTo(linkedSetOf()) { slot ->
            if (projection.stateBySlotId[slot.id] !=
                com.mecon.theory.freepractice.WorkspaceSlotMaterialState.PARTIAL_OR_COMPLEX
            ) return@mapNotNullTo null
            val end = slot.onset + slot.duration
            val sounding = score.voiceTracks.values.flatMap { voice ->
                voice.events.toList().filterNot { it.isGrace || it.isRest }.flatMap { event ->
                    val onset = timeMap.absolute(event.onset)
                    val eventEnd = onset + event.duration.toFraction()
                    if (onset >= end || eventEnd <= slot.onset) emptyList() else {
                        event.pitches.indices.map { pitchIndex ->
                            voice.id to com.mecon.exploration.PracticeNoteheadRef(event.id, pitchIndex)
                        }
                    }
                }
            }
            slot.id.takeIf {
                sounding.isNotEmpty() && sounding.all { (voiceId, notehead) ->
                    voiceId in lockedVoiceIds || notehead in noteConstraints.lockedNoteheads
                }
            }
        }
    }

    private fun startWriting(
        baseRevision: Long,
        scope: PracticeWritingScope,
    ): FreePracticeDispatchResult {
        candidates = emptyList()
        nextCandidateIndex = 1
        revision++
        writing = PracticeWritingStatus(
            phase = PracticeWritingPhase.RUNNING,
            lastScope = scope.slotIds,
        )
        val request = backgroundRequest(scope, PracticeBackgroundRequestKind.FIRST_SOLVE)
        activeRequest = request
        return result(
            baseRevision,
            FreePracticeEffect(FreePracticeEffectKind.WRITING_REQUESTED, "freePractice.writing.running"),
            listOf(request),
        )
    }

    private fun alternate(baseRevision: Long): FreePracticeDispatchResult {
        val candidate = candidates.getOrNull(nextCandidateIndex)
            ?: return result(
                baseRevision,
                FreePracticeEffect(FreePracticeEffectKind.NO_OP, "freePractice.writing.noAlternative"),
            )
        val materialized = FreePracticeVoicingMaterializer.materialize(
            manager.currentState.runtimeScore,
            workspace,
            candidate,
            noteConstraints,
        )
        transaction.commit(materialized.score, computeScore(materialized.score), workspace)
        scoreSession.notifyExternalCommit()
        nextCandidateIndex++
        val scope = candidate.frames.map { it.slotId }
        writing = PracticeWritingStatus(
            phase = PracticeWritingPhase.READY,
            outcome = PracticeWritingOutcome.Solved(scope, replayRange(scope)),
            canAlternate = nextCandidateIndex < candidates.size,
            lastScope = scope,
        )
        revision++
        return result(
            baseRevision,
            FreePracticeEffect(
                FreePracticeEffectKind.WRITING_APPLIED,
                "freePractice.writing.alternateApplied",
                mapOf("index" to nextCandidateIndex.toString()),
            ),
            editPlayback = (writing.outcome as? PracticeWritingOutcome.Solved)
                ?.replayRange
                ?.let(PracticeEditPlayback::Excerpt),
        )
    }

    private fun cancelWriting(baseRevision: Long): FreePracticeDispatchResult {
        if (activeRequest == null && writing.phase != PracticeWritingPhase.RUNNING) return noOp(baseRevision)
        activeRequest?.let { request ->
            val synchronized = RuntimeScore.fromStorage(request.score)
            transaction.commit(synchronized, computeScore(synchronized), request.document.workspace)
            scoreSession.notifyExternalCommit()
        }
        cancelPending(PracticeWritingOutcome.Cancelled)
        revision++
        return result(
            baseRevision,
            FreePracticeEffect(FreePracticeEffectKind.WRITING_CANCELLED, "freePractice.writing.cancelled"),
        )
    }

    private fun undo(baseRevision: Long): FreePracticeDispatchResult {
        if (!manager.canUndo()) return noOp(baseRevision)
        manager.undo()
        scoreSession.notifyExternalCommit()
        selectedSlotId = selectedSlotId?.takeIf { id -> workspace.slots.any { it.id == id } }
            ?: workspace.slots.firstOrNull()?.id
        cancelPending(PracticeWritingOutcome.Cancelled)
        revision++
        return result(baseRevision, FreePracticeEffect(FreePracticeEffectKind.UNDONE))
    }

    private fun redo(baseRevision: Long): FreePracticeDispatchResult {
        if (!manager.canRedo()) return noOp(baseRevision)
        manager.redo()
        scoreSession.notifyExternalCommit()
        selectedSlotId = selectedSlotId?.takeIf { id -> workspace.slots.any { it.id == id } }
            ?: workspace.slots.firstOrNull()?.id
        cancelPending(PracticeWritingOutcome.Cancelled)
        revision++
        return result(baseRevision, FreePracticeEffect(FreePracticeEffectKind.REDONE))
    }

    private fun backgroundRequest(
        scope: PracticeWritingScope,
        kind: PracticeBackgroundRequestKind,
        excluded: Set<String> = emptySet(),
        requestWorkspace: HarmonyWorkspaceState = workspace,
        requestScore: RuntimeScore = manager.currentState.runtimeScore,
    ): PracticeBackgroundRequest {
        val id = ++requestCounter
        return PracticeBackgroundRequest(
            requestId = id,
            baseRevision = revision,
            scopeFingerprint = scopeFingerprint(requestWorkspace, scope.slotIds),
            kind = kind,
            document = FreePracticeDocument(
                settings = settings,
                workspace = requestWorkspace,
                noteConstraints = noteConstraints,
                migrationDiagnostics = migrationDiagnostics,
            ),
            score = requestScore.toStorage(),
            scopeSlotIds = scope.slotIds,
            triggerSlotId = scope.triggerSlotId,
            leftBoundarySlotId = scope.leftBoundarySlotId,
            replayWholeScope = scope.trigger ==
                com.mecon.theory.freepractice.PracticeWritingTrigger.IDIOM_CHANGE,
            excludedDiversityGroupKeys = excluded,
            search = if (kind == PracticeBackgroundRequestKind.FIRST_SOLVE) {
                PracticeSearchConfig(maxResults = 1, beamWidth = 12)
            } else {
                PracticeSearchConfig(maxResults = 4, beamWidth = 24, seed = id)
            },
        )
    }

    private fun PracticeBackgroundRequest.toScope() = PracticeWritingScope(
        slotIds = scopeSlotIds,
        triggerSlotId = triggerSlotId,
        leftBoundarySlotId = leftBoundarySlotId,
        trigger = com.mecon.theory.freepractice.PracticeWritingTrigger.ALTERNATE,
    )

    private fun replayRange(
        slotIds: List<WorkspaceSlotId>,
        replayWholeScope: Boolean = false,
    ): PracticeReplayRange? {
        val slotsById = workspace.slots.associateBy { it.id }
        val slots = slotIds.mapNotNull(slotsById::get)
        if (slots.isEmpty()) return null
        val requested = settings.writing.replayChordCount
        if (requested == 0) return null
        val lastIndex = workspace.slots.indexOfFirst { it.id == slots.last().id }
        val scopeFirstIndex = workspace.slots.indexOfFirst { it.id == slots.first().id }
        val configuredFirstIndex = maxOf(0, lastIndex - requested + 1)
        val firstIndex = if (replayWholeScope && slots.size > requested && scopeFirstIndex >= 0) {
            scopeFirstIndex
        } else {
            configuredFirstIndex
        }
        val replaySlots = workspace.slots.subList(firstIndex, lastIndex + 1)
        return PracticeReplayRange(
            firstSlotId = replaySlots.first().id,
            lastSlotId = replaySlots.last().id,
            start = replaySlots.first().onset,
            end = replaySlots.last().onset + replaySlots.last().duration,
            tempoBpm = settings.writing.playbackTempoBpm,
        )
    }

    private fun cancelPending(outcome: PracticeWritingOutcome) {
        activeRequest = null
        candidates = emptyList()
        nextCandidateIndex = 1
        if (writing.phase == PracticeWritingPhase.RUNNING) {
            writing = PracticeWritingStatus(
                phase = PracticeWritingPhase.READY,
                outcome = outcome,
                lastScope = writing.lastScope,
            )
        }
    }

    private fun scopeFingerprint(slotIds: List<WorkspaceSlotId>): String =
        scopeFingerprint(workspace, slotIds)

    private fun scopeFingerprint(
        sourceWorkspace: HarmonyWorkspaceState,
        slotIds: List<WorkspaceSlotId>,
    ): String = buildString {
        sourceWorkspace.slots.filter { it.id in slotIds }.forEach { slot ->
            append(slot.id.value).append(':')
            append(slot.onset).append(':').append(slot.duration).append(':')
            append(slot.chordChoice?.pitchClasses?.joinToString(",")).append(';')
        }
    }

    private fun document() = activeRequest?.document
        ?: FreePracticeDocument(
            settings = settings,
            workspace = workspace,
            noteConstraints = noteConstraints,
            migrationDiagnostics = migrationDiagnostics,
        )
    private val workspace: HarmonyWorkspaceState get() = workspaceController.state
    private val visibleWorkspace: HarmonyWorkspaceState get() = activeRequest?.document?.workspace ?: workspace

    /**
     * Base every workspace edit and preview resolves against.
     *
     * While auto-writing runs, the edit that started it is not committed yet — it lives in the
     * pending request, and that is the workspace every projection shows. Editing the last committed
     * state instead silently threw the pending edit away (a chord dragged during the solve snapped
     * back on the next commit) and previews had to be refused outright, which left the following
     * gesture with no feedback at all. Superseding the pending request from what the user actually
     * sees keeps preview and commit consistent; with no request in flight this is [workspace].
     */
    private val editBase: HarmonyWorkspaceState get() = visibleWorkspace

    private fun isPristinePractice(): Boolean = PracticeStructureProjector.isPristine(
        manager.currentState.runtimeScore,
        workspace,
        settings,
    )

    /**
     * Unlike the other projections this one reads [workspace], not [visibleWorkspace].
     *
     * `pristine` and `rewriteSelectionAvailable` are not descriptions of what is on screen — they
     * predict what a command will do, and both commands run against the committed workspace:
     * [setPracticeTimeSignature] rejects with `freePractice.timeSignature.scoreToolRequired` unless
     * [isPristinePractice] holds, and [rewriteSelection] resolves its scope from [workspace]. Basing
     * the prediction on the optimistic workspace of an in-flight write makes both platforms route to
     * an intent the session then refuses, i.e. a silently dead control. The remaining fields come
     * from the committed runtime score anyway.
     */
    private fun structureView(selection: List<ScoreSelectionTarget>): PracticeStructureView =
        PracticeStructureProjector.project(
            selection,
            manager.currentState.runtimeScore,
            workspace,
            settings,
        )

    private fun measureBoundary(score: RuntimeScore, afterMeasure: Int): Fraction =
        PracticeTimelineScoreSynchronizer.measureBoundary(score, afterMeasure)

    private fun synchronizedTimelineScore(
        score: RuntimeScore,
        targetWorkspace: HarmonyWorkspaceState,
        trimEmptyTail: Boolean,
    ): RuntimeScore = PracticeTimelineScoreSynchronizer.synchronize(
        score,
        targetWorkspace,
        trimEmptyTail,
    )

    private fun measureBoundaries(score: RuntimeScore): List<Fraction> =
        PracticeTimelineScoreSynchronizer.measureBoundaries(score)
    private fun staleTarget(baseRevision: Long, id: WorkspaceSlotId) = result(
        baseRevision,
        FreePracticeEffect(
            FreePracticeEffectKind.STALE_TARGET,
            "freePractice.staleTarget",
            mapOf("slotId" to id.value),
        ),
    )

    private fun invalidScope(baseRevision: Long) = result(
        baseRevision,
        FreePracticeEffect(FreePracticeEffectKind.INVALID, "freePractice.writing.invalidScope"),
    )

    private fun noOp(baseRevision: Long) = result(
        baseRevision,
        FreePracticeEffect(FreePracticeEffectKind.NO_OP),
    )

    private fun result(
        baseRevision: Long?,
        effect: FreePracticeEffect,
        requests: List<PracticeBackgroundRequest> = emptyList(),
        catalogRequests: List<PracticeTeachingCatalogRequest> = emptyList(),
        findingRequests: List<PracticeFindingRequest> = emptyList(),
        scoreUpdate: com.mecon.features.scoreediting.ScoreEditUpdate? = null,
        editPlayback: PracticeEditPlayback? = null,
    ): FreePracticeDispatchResult {
        val catalogWork = catalogRequests.ifEmpty { listOfNotNull(ensureTeachingCatalogRequest()) }
        val findingWork = findingRequests.ifEmpty { listOfNotNull(ensureFindingRequest()) }
        return FreePracticeDispatchResult(
            frame = frame(),
            baseRevision = baseRevision,
            effect = effect,
            requests = requests,
            catalogRequests = catalogWork,
            findingRequests = findingWork,
            scoreUpdate = scoreUpdate,
            editPlayback = editPlayback,
        )
    }

    private fun update(
        baseRevision: Long?,
        effect: FreePracticeEffect,
        requests: List<PracticeBackgroundRequest> = emptyList(),
        catalogRequests: List<PracticeTeachingCatalogRequest> = emptyList(),
        findingRequests: List<PracticeFindingRequest> = emptyList(),
        scoreUpdate: com.mecon.features.scoreediting.ScoreEditUpdate? = null,
        editPlayback: PracticeEditPlayback? = null,
        prepared: FreePracticeFrame? = null,
    ): FreePracticeUpdate {
        val catalogWork = catalogRequests.ifEmpty { listOfNotNull(ensureTeachingCatalogRequest()) }
        val findingWork = findingRequests.ifEmpty { listOfNotNull(ensureFindingRequest()) }
        // The frame has to be captured after the ensure* calls: they flip catalog `loading` and
        // findings `stale`, and the published frame must show those.
        val current = prepared ?: frame()
        // Derived from the committed state, never from the pending writing score the wire update
        // substitutes below: an in-flight solve has not persisted anything yet.
        val documentChanged = current.score.scoreChanged ||
            workspace != operationBaseWorkspace ||
            settings != operationBaseSettings ||
            noteConstraints != operationBaseNoteConstraints
        return FreePracticeUpdate(
        revision = revision,
        baseRevision = baseRevision,
        document = current.document,
        documentChanged = documentChanged,
        score = (scoreUpdate ?: scoreSession.wireUpdate(
            com.mecon.features.scoreediting.ScoreEditDispatchResult(
                frame = current.score,
                baseRevision = null,
                effect = com.mecon.features.scoreediting.ScoreEditEffect(ScoreEditEffectKind.APPLIED),
            ),
        )).let { currentScoreUpdate ->
            activeRequest?.score?.let { pendingScore ->
                currentScoreUpdate.copy(score = pendingScore, scoreChanged = true)
            } ?: currentScoreUpdate
        },
        selection = current.selection,
        selectedSlotId = selectedSlotId,
        findings = current.findings,
        catalog = current.catalog,
        noteConstraints = current.noteConstraints,
        timeline = current.timeline,
        structure = current.structure,
        plan = current.plan,
        writing = writing,
        effect = effect,
        editPlayback = editPlayback,
        requests = requests,
        catalogRequests = catalogWork,
        findingRequests = findingWork,
    )
    }

    private fun selectedIdiomCatalogLayout(): com.mecon.theory.freepractice.WorkspaceTonalLayout? {
        val selected = selectedSlotId?.let { id -> visibleWorkspace.slots.firstOrNull { it.id == id } }
            ?: return null
        val active = visibleWorkspace.activeTonalLayouts(selected.onset)
        return selectedIdiomTonalLayoutId
            ?.let { id -> active.firstOrNull { it.id == id } }
            ?: active.singleOrNull()
            ?: visibleWorkspace.selectedTonalLayout(selected)
            ?: active.lastOrNull()
    }

    /** Everything the teaching catalog depends on; see [applyTeachingCatalogResult]. */
    private fun teachingCatalogFingerprint(): String {
        val selected = selectedSlotId?.let { id -> visibleWorkspace.slots.firstOrNull { it.id == id } }
        val catalogKey = selectedIdiomCatalogLayout()?.key
            ?: PracticeFindingComputer.fallbackKey(document())
        val activeKeys = visibleWorkspace.tonalLayouts.map { it.key }.distinct()
            .ifEmpty { listOf(catalogKey) }
        val initialKey = PracticeFindingComputer.fallbackKey(document())
        val focus = selected?.chordChoice
        return buildString {
            append(initialKey.fifths).append(':').append(initialKey.mode.name).append('|')
            activeKeys.forEach { append(it.fifths).append(':').append(it.mode.name).append(',') }
            append('|').append(catalogKey.fifths).append(':').append(catalogKey.mode.name)
            append('|').append(focus?.pitchClasses?.joinToString(",").orEmpty())
            append('|').append(focus?.pinnedInterpretationRef?.toString().orEmpty())
            append('|').append(catalogIncludeOffKey)
            append('|').append(idiomCatalogRoleFilterEnabled)
            if (idiomCatalogRoleFilterEnabled) {
                PracticeNoteConstraintProjector.catalogConstraints(
                    noteConstraints,
                    manager.currentState.runtimeScore,
                ).forEach { constraint ->
                    append('|').append(constraint.onset).append(':')
                    append(constraint.pitchClass).append(':').append(constraint.role.name)
                }
            }
        }
    }

    private fun ensureTeachingCatalogRequest(): PracticeTeachingCatalogRequest? {
        val selected = selectedSlotId?.let { id -> visibleWorkspace.slots.firstOrNull { it.id == id } }
        val catalogKey = selectedIdiomCatalogLayout()?.key
            ?: PracticeFindingComputer.fallbackKey(document())
        val activeKeys = visibleWorkspace.tonalLayouts.map { it.key }.distinct()
            .ifEmpty { listOf(catalogKey) }
        val initialKey = PracticeFindingComputer.fallbackKey(document())
        val focus = selected?.chordChoice
        val fingerprint = teachingCatalogFingerprint()
        if (teachingCatalog.requestKey == fingerprint && !teachingCatalog.loading) return null
        // The fingerprint describes the request's inputs completely, so an in-flight request for the
        // same inputs is still the right one no matter how far the revision has moved meanwhile.
        activeTeachingCatalogRequest?.takeIf { it.fingerprint == fingerprint }?.let { return null }
        val request = PracticeTeachingCatalogRequest(
            requestId = ++requestCounter,
            baseRevision = revision,
            fingerprint = fingerprint,
            initialKey = initialKey.toView(),
            activeKeys = activeKeys.map { it.toView() },
            catalogKey = catalogKey.toView(),
            focus = focus,
            includeOffKey = catalogIncludeOffKey,
            focusOnset = selected?.onset ?: com.mecon.api.primitive.Fraction.ZERO,
            harmonicRoleFilterEnabled = idiomCatalogRoleFilterEnabled,
            harmonicRoleConstraints = PracticeNoteConstraintProjector.catalogConstraints(
                noteConstraints,
                manager.currentState.runtimeScore,
            ),
        )
        activeTeachingCatalogRequest = request
        teachingCatalog = teachingCatalog.copy(
            requestKey = fingerprint,
            loading = true,
            errorKey = null,
            includeOffKey = catalogIncludeOffKey,
        )
        return request
    }

    private fun com.mecon.theory.ModulationKey.toView() =
        PracticeKeyView(fifths, WorkspaceKeyMode.fromTheory(mode))

    /**
     * Identifies the actual finding inputs — practice document and score — rather than the revision.
     * Selections bump the revision without changing a single note, and keying findings on the
     * revision made every chord-slot click re-run the whole analysis.
     */
    private fun findingInputFingerprint(): String {
        val currentWorkspace = visibleWorkspace
        val currentRuntime = manager.currentState.runtimeScore
        val pendingScore = activeRequest?.score
        // Reference identity only: every commit installs new instances, while `toStorage()` would
        // allocate a fresh projection on each call and make the fingerprint change unconditionally.
        if (currentWorkspace !== findingInputWorkspace ||
            currentRuntime !== findingInputRuntime ||
            pendingScore !== findingInputPendingScore ||
            settings !== findingInputSettings
        ) {
            findingInputWorkspace = currentWorkspace
            findingInputRuntime = currentRuntime
            findingInputPendingScore = pendingScore
            findingInputSettings = settings
            findingInputGeneration++
        }
        return findingInputGeneration.toString()
    }

    private fun ensureFindingRequest(): PracticeFindingRequest? {
        val fingerprint = findingInputFingerprint()
        if (findingsFingerprint == fingerprint) return null
        activeFindingRequest?.takeIf { it.fingerprint == fingerprint }?.let { return null }
        val request = PracticeFindingRequest(
            requestId = ++requestCounter,
            baseRevision = revision,
            fingerprint = fingerprint,
            document = document(),
            score = activeRequest?.score ?: manager.currentState.runtimeScore.toStorage(),
        )
        activeFindingRequest = request
        findings = findings.copy(stale = true)
        return request
    }

    private fun catalog(): PracticeCatalogView {
        val selected = selectedSlotId?.let { id -> visibleWorkspace.slots.firstOrNull { it.id == id } }
        val key = selected?.tonality?.primary?.key
            ?: selected?.let(visibleWorkspace::selectedTonalLayout)?.key
            ?: PracticeFindingComputer.fallbackKey(document())
        val projected = projectPracticeCatalog(key)
        if (!chordCatalogRoleFilterEnabled) return projected
        val roleConstraints = roleConstraintsAt(selectedSlotId)
        val groups = projected.chordGroups.map { group ->
            group.copy(
                choices = group.choices.filter { choice ->
                    PracticeNoteConstraintProjector.accepts(choice.choice, roleConstraints)
                },
            )
        }.filter { it.choices.isNotEmpty() }
        return projected.copy(
            chordChoices = groups.flatMap { it.choices },
            chordGroups = groups,
            harmonicRoleFilterEnabled = true,
        )
    }

    private fun roleConstraintsAt(slotId: WorkspaceSlotId?) =
        PracticeNoteConstraintProjector.constraintsAtSelectedSlot(
            noteConstraints,
            visibleWorkspace,
            manager.currentState.runtimeScore,
            slotId,
        )

    private fun messageKeyFor(outcome: PracticeWritingOutcome): String = when (outcome) {
        is PracticeWritingOutcome.Solved -> "freePractice.writing.solved"
        PracticeWritingOutcome.NoSolution -> "freePractice.writing.noSolution"
        PracticeWritingOutcome.BudgetExhausted -> "freePractice.writing.budgetExhausted"
        PracticeWritingOutcome.Cancelled -> "freePractice.writing.cancelled"
        is PracticeWritingOutcome.Invalid -> "freePractice.writing.invalid"
        is PracticeWritingOutcome.Failed -> "freePractice.writing.failed"
    }

    companion object {
        fun open(document: FreePracticeDocument, score: RuntimeScore): FreePracticeSession =
            open(document, score, computeScore(score))

        fun open(
            document: FreePracticeDocument,
            score: RuntimeScore,
            computedScore: com.mecon.api.computed.ComputedScore,
        ): FreePracticeSession = FreePracticeSession(
            ScoreStateManager(score, computedScore),
            document,
        )

        fun open(document: FreePracticeDocument, score: com.mecon.api.storage.StorageScore): FreePracticeSession =
            open(document, RuntimeScore.fromStorage(score))

        fun open(document: FreePracticeDocument, manager: ScoreStateManager): FreePracticeSession =
            FreePracticeSession(manager, document)
    }
}

internal fun projectPracticeCatalog(key: com.mecon.theory.ModulationKey): PracticeCatalogView {
    fun ChordSelectionChoice.toView(): PracticeChordCatalogItem = PracticeChordCatalogItem(
        id = id.value,
        symbol = functionalSymbol,
        choice = com.mecon.theory.freepractice.WorkspaceChordChoice.of(
            pitchClasses,
            origin,
            confirmedInterpretationRef,
            rootPitchClass,
        ),
        absoluteTones = absoluteTones,
        relativeTones = relativeTones,
        rootPitchClass = rootPitchClass,
        interpretationCount = interpretationRefs.size,
        relativeLabel = "$functionalSymbol · ${relativeTones.joinToString("–")}",
        absoluteLabel = "$functionalSymbol · ${absoluteTones.joinToString("–")}",
    )
    val groups = ChordSelectionCatalog.groups(key)
    return PracticeCatalogView(
        requestKey = "${key.fifths}:${key.mode.name}",
        chordChoices = groups.flatMap { it.chords }.map { it.toView() },
        chordGroups = groups.map { group ->
            PracticeChordCatalogGroupView(
                id = group.category.id,
                titleLabel = practiceChordCatalogText(group.category.titleKey),
                descriptionLabel = practiceChordCatalogText(group.category.descriptionKey),
                choices = group.chords.map { it.toView() },
            )
        },
    )
}

private fun practiceChordCatalogText(key: String): String = when (key) {
    "exploration.chordCatalog.diatonicTriads.title" -> "自然音三和弦"
    "exploration.chordCatalog.diatonicTriads.description" -> "当前调式各音级上自然生成的三和弦。"
    "exploration.chordCatalog.diatonicSevenths.title" -> "自然音七和弦"
    "exploration.chordCatalog.diatonicSevenths.description" -> "在自然音三和弦上继续叠置三度形成的七和弦。"
    "exploration.chordCatalog.secondaryDominants.title" -> "副属和弦"
    "exploration.chordCatalog.secondaryDominants.description" -> "临时主音化调内目标音级的属三和弦与属七和弦。"
    "exploration.chordCatalog.secondaryLeading.title" -> "副导和弦"
    "exploration.chordCatalog.secondaryLeading.description" -> "以目标音级导音为根音的减三和弦与导七和弦。"
    "exploration.chordCatalog.augmentedTriads.title" -> "增三和弦"
    "exploration.chordCatalog.augmentedTriads.description" -> "由调式变化音自动发现的增三和弦色彩。"
    "exploration.chordCatalog.augmentedSixths.title" -> "其他增六和弦"
    "exploration.chordCatalog.augmentedSixths.description" ->
        "指向其他音级的意大利、德国与法国增六，以及半减七增六解释。"
    "exploration.chordCatalog.dominantAugmentedSixths.title" -> "指向属音的增六和弦"
    "exploration.chordCatalog.dominantAugmentedSixths.description" ->
        "清晰指向 V 级的意大利、德国与法国增六属前和弦。"
    "exploration.chordCatalog.rootlessDominantNinth.title" -> "减七和弦（省略根音属九）"
    "exploration.chordCatalog.rootlessDominantNinth.description" ->
        "同一减七音响可按所指向的目标音级获得不同功能解释。"
    "exploration.chordCatalog.modalColors.title" -> "调式色彩和弦"
    "exploration.chordCatalog.modalColors.description" -> "由上行或下行调式路径产生的其他变化和弦。"
    "exploration.chordCatalog.neapolitan.title" -> "拿坡里和弦"
    "exploration.chordCatalog.neapolitan.description" -> "从小下属关系和弦中单列的降二级大三和弦及其七和弦。"
    "exploration.chordCatalog.minorSubdominant.title" -> "小下属关系和弦"
    "exploration.chordCatalog.minorSubdominant.description" ->
        "由同主音小调与下属小调的自然音级和弦借入的降号和弦。"
    else -> key
}
