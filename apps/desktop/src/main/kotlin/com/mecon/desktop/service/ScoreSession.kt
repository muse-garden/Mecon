package com.mecon.desktop.service

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mecon.api.computed.ComputeChangeSet
import com.mecon.api.computed.ComputedScore
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.ReductionId
import com.mecon.api.primitive.BarlineType
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.toStorage
import com.mecon.api.state.RenderHint
import com.mecon.api.state.ScoreState
import com.mecon.api.state.ScoreStateManager
import com.mecon.api.storage.PageArrangement
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.Articulation
import com.mecon.api.storage.NavigationMark
import com.mecon.api.storage.NavigationMarkOffset
import com.mecon.core.engine.computeScore
import com.mecon.core.engine.computeScoreIncremental
import com.mecon.core.engine.edit.ClefEditEngine
import com.mecon.core.engine.edit.BarlineEditEngine
import com.mecon.core.engine.edit.RepeatStructureEditEngine
import com.mecon.core.engine.edit.KeySignatureEditEngine
import com.mecon.core.engine.edit.MeasureEditEngine
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.core.engine.edit.ExpressionEditEngine
import com.mecon.core.engine.edit.TempoEditEngine
import com.mecon.core.engine.edit.TimeSignatureEditEngine
import com.mecon.features.scoreediting.ScoreEditDispatchResult
import com.mecon.features.scoreediting.ScoreEditEffectKind
import com.mecon.features.scoreediting.ScoreNoteInputTransition
import com.mecon.features.scoreediting.ScoreSelectionTarget
import com.mecon.features.scoreediting.eventIdOrNull
import com.mecon.features.scoreediting.ScoreEditIntent
import com.mecon.features.scoreediting.ScoreEditingSession
import com.mecon.core.analysis.OrchestrationEngine
import com.mecon.core.analysis.OrchestrationFlowEngine
import com.mecon.core.analysis.ReductionEngine
import com.mecon.core.analysis.ReductionSyncEngine
import com.mecon.core.analysis.ReductionWorkspaceEngine
import com.mecon.api.storage.PlayerKind
import com.mecon.api.storage.NoteRef
import com.mecon.api.storage.ReductionLayerKind
import com.mecon.api.primitive.ScoreFragmentId
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.TimeSignature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Holds the in-memory score document: the [ScoreStateManager], its current
 * [ScoreState], the on-disk identity, and every edit operation the UI invokes.
 *
 * The exposed [state]/[manager]/[currentFile]/[currentFileName] are Compose snapshot
 * state, so reading the derived getters from a composable recomposes on change.
 *
 * All heavy recompute runs on [Dispatchers.Default];
 * Compose state is mutated only back on the caller's (main) dispatcher.
 *
 * This holder knows nothing about file dialogs or audio — see [ScoreFileController]
 * and [PlaybackController].
 */
class ScoreSession(internal val scope: CoroutineScope) : EditableNoteHost {
    /** Serialises high-rate keyboard/MIDI edits so each request reads the preceding committed score. */
    private val noteInputMutex = Mutex()

    var structuralEditInFlight by mutableStateOf(false)
        internal set

    var manager by mutableStateOf<ScoreStateManager?>(null)
        internal set
    internal var sharedEditingSession: ScoreEditingSession? = null
    var state by mutableStateOf<ScoreState?>(null)
        internal set
    var currentFile by mutableStateOf<File?>(null)
        internal set
    var currentFileName by mutableStateOf("Untitled.mecon")
        internal set
    /** Whether the current history frame differs from the last successful manual save/load. */
    var isModified by mutableStateOf(false)
        internal set
    internal var savedRuntimeScore: RuntimeScore? = null
    override var documentVersion by mutableStateOf(0L)
        internal set
    var analysisMessage by mutableStateOf<String?>(null)
        internal set

    /**
     * Slur / articulation geometry captured from the most recent render of the main editing view,
     * in stable anchor-relative storage form. Folded into the [StorageScore] at save time so the
     * file persists its geometry. Plain (non-history, non-Compose) state — only read on save.
     * See [com.mecon.api.storage.ScoreGeometry].
     */
    @Volatile
    var lastRenderedGeometry: com.mecon.api.storage.ScoreGeometry? = null

    /**
     * The `.mecon` container this document was loaded from, if any. Retained so a save re-packs the
     * parts the desktop isn't actively editing — other scores, pluggable modules and workspace
     * preferences — instead of collapsing the file to a single score. Null for YAML/MusicXML imports
     * and brand-new documents (they save as a fresh single-score container). Plain (non-Compose)
     * state — only read on save. See [MeconDocumentService.buildDocument].
     */
    @Volatile
    var loadedContainer: com.mecon.core.container.MeconDocument? = null

    internal var collectJob: Job? = null

    // Editor-state controllers (e.g. selection) that ride the undo history. Kept here so they survive
    // document swaps (each new ScoreStateManager re-registers them in installManager).
    internal val editorStateControllers =
        LinkedHashMap<String, com.mecon.api.state.EditorStateController>()

    /**
     * Register an editor-state controller (e.g. the selection) so undo/redo restores it alongside the
     * score. Survives document reloads — re-applied to each new [ScoreStateManager]. See
     * [com.mecon.api.state.EditorStateController].
     */
    fun registerEditorState(key: String, controller: com.mecon.api.state.EditorStateController) {
        editorStateControllers[key] = controller
        manager?.registerEditorState(key, controller)
    }

    private fun <K, V> Map<K, V>.putPersistent(key: K, value: V): Map<K, V> =
        ((this as? PersistentMap<K, V>) ?: toPersistentMap()).put(key, value)

    private fun slurMeasureRange(computed: ComputedScore, slurId: EventId): IntRange? {
        val slur = computed.slurs.firstOrNull { it.slurId == slurId } ?: return null
        val start = computed.getComputedEvent(slur.startEventId)?.onset?.measure ?: return null
        val end = computed.getComputedEvent(slur.endEventId)?.onset?.measure ?: return null
        return minOf(start, end)..maxOf(start, end)
    }

    private fun tieMeasureRange(
        computed: ComputedScore,
        sourceEventId: EventId,
        sourcePitchIndex: Int,
    ): IntRange? {
        val source = computed.getComputedEvent(sourceEventId) ?: return null
        val targetId = source.pitchData.getOrNull(sourcePitchIndex)?.tieTarget?.targetEventId
        val targetMeasure = targetId?.let { computed.getComputedEvent(it)?.onset?.measure }
            ?: source.onset.measure
        return minOf(source.onset.measure, targetMeasure)..maxOf(source.onset.measure, targetMeasure)
    }

    override val runtimeScore: RuntimeScore? get() = state?.runtimeScore
    override val computedScore: ComputedScore? get() = state?.computedScore
    /** Hint for the render pipeline describing the last (incremental) transition; null after full commits. */
    override val renderHint: RenderHint? get() = state?.renderHint
    override val canUndo: Boolean get() = state?.let { manager?.canUndo() } ?: false
    override val canRedo: Boolean get() = state?.let { manager?.canRedo() } ?: false
    val paginated: Boolean get() = runtimeScore?.pageLayout?.paginated == true
    val pageArrangement: PageArrangement
        get() = runtimeScore?.viewPreferences?.pageArrangement ?: PageArrangement.VERTICAL
    val showMeasureNumbers: Boolean
        get() = runtimeScore?.viewPreferences?.showMeasureNumbers ?: true

    // --- History ------------------------------------------------------------
    override fun undo() { manager?.undo() }
    override fun redo() { manager?.redo() }

    /**
     * Desktop adapter for the platform-neutral edit state machine. The same mutex also protects
     * keyboard/MIDI chord input, so every edit observes the state committed by the previous one.
     */
    private fun dispatchSharedEdit(
        intent: (expectedRevision: Long) -> ScoreEditIntent,
        onResult: (ScoreEditDispatchResult) -> Unit = {},
    ) {
        launchRecovering {
            noteInputMutex.withLock {
                val shared = sharedEditingSession ?: return@withLock
                val result = withContext(Dispatchers.Default) {
                    shared.dispatch(intent(shared.frame().revision))
                }
                // StateFlow collection is asynchronous; publish the exact committed frame before
                // selection callbacks make Compose inspect its sections.
                state = manager?.currentState
                onResult(result)
            }
        }
    }

    private fun sharedStructuralSelection(result: ScoreEditDispatchResult): Set<com.mecon.api.interaction.EventSection> {
        val computed = result.frame.computedScore
        return result.frame.selection.mapNotNullTo(LinkedHashSet()) { target ->
            when (target) {
                is ScoreSelectionTarget.Barline -> computed.barlines
                    .firstOrNull { it.measureNumber == target.boundaryMeasure }
                    ?.let { com.mecon.api.interaction.BarlineSection(it) }
                is ScoreSelectionTarget.VoltaEnding -> computed.voltaEndings
                    .firstOrNull {
                        it.startMeasure == target.startMeasure &&
                            it.endMeasure == target.endMeasure &&
                            it.numbers == target.numbers
                    }
                    ?.let { com.mecon.api.interaction.VoltaEndingSection(it) }
                is ScoreSelectionTarget.NavigationMark -> computed.navigationMarks
                    .firstOrNull {
                        it.boundaryMeasure == target.boundaryMeasure && it.mark == target.mark
                    }
                    ?.let { com.mecon.api.interaction.NavigationMarkSection(it) }
                else -> null
            }
        }
    }

    fun bindReductionSelection(request: OrchestrationFlowEngine.BindRequest) =
        applyStorageEdit { storage ->
            val result = OrchestrationFlowEngine.bindReductionSelection(storage, request)
                ?: return@applyStorageEdit storage
            analysisMessage = buildString {
                when (result.direction) {
                    OrchestrationFlowEngine.BindingDirection.REDUCTION_TO_WRITTEN -> {
                        append("已绑定 ${result.boundNotes} 个缩谱音符到内容线")
                        if (request.realizeNow) append("，写入总谱 ${result.realizedNotes} 个")
                    }
                    OrchestrationFlowEngine.BindingDirection.WRITTEN_TO_REDUCTION ->
                        append("已从总谱提取 ${result.boundNotes} 个音符，经内容线放入缩谱")
                    OrchestrationFlowEngine.BindingDirection.ROUTE_ONLY ->
                        append("已更新内容线的演奏者与谱表路由")
                }
                if (result.unresolvedNotes > 0) append("；${result.unresolvedNotes} 个等待分配")
                if (result.conflicts > 0) append("；${result.conflicts} 个位置冲突")
            }
            result.score
        }

    fun toggleReductionPlayer(request: OrchestrationFlowEngine.BindRequest) =
        applyStorageEdit { storage ->
            val result = OrchestrationFlowEngine.toggleReductionSelectionPlayer(storage, request)
                ?: return@applyStorageEdit storage
            analysisMessage = when {
                result.groupDeleted -> "已取消最后一位演奏者并删除同步组"
                result.playerAdded ->
                    "已加入演奏者；同步组现有 ${result.playerCount} 位演奏者" +
                        if (result.realizedNotes > 0) "，写入 ${result.realizedNotes} 个总谱音符" else ""
                else -> "已取消演奏者；同步组保留 ${result.playerCount} 位演奏者"
            }
            result.score
        }

    fun realizeAllOrchestrationLines() = applyStorageEdit { storage ->
        val result = OrchestrationFlowEngine.realizeAllLines(storage)
        analysisMessage = buildString {
            append("已写入总谱 ${result.realizedNotes} 个音符")
            if (result.unresolvedNotes > 0) append("；${result.unresolvedNotes} 个等待演奏者/谱表分配")
            if (result.conflicts > 0) append("；${result.conflicts} 个位置冲突")
        }
        result.score
    }

    fun applyReductionNoteEdit(
        id: ReductionId,
        insertion: NoteEditEngine.Insertion,
        onInserted: (com.mecon.api.interaction.EventSection, RuntimeScore) -> Unit = { _, _ -> },
    ) {
        val current = runtimeScore?.toStorage() ?: return
        val reduction = current.getReduction(id) ?: return
        val nested = RuntimeScore.fromStorage(reduction.notationScore)
        val result = NoteEditEngine.insert(nested, insertion) ?: return
        applyStorageEdit { storage ->
            storage.copy(reductions = storage.reductions.map { item ->
                if (item.id == id) {
                    item.updateLayerScore(ReductionLayerKind.NOTATION, result.score.toStorage())
                } else {
                    item
                }
            })
        }
        result.insertedEventId
            ?.let { computeScore(result.score).getComputedEvent(it) }
            ?.let { onInserted(com.mecon.api.interaction.VoiceEventSection(it), result.score) }
    }

    fun applyReductionTranspose(
        id: ReductionId,
        targets: List<NoteEditEngine.TransposeTarget>,
        stepDelta: Int,
    ) {
        val current = runtimeScore?.toStorage() ?: return
        val reduction = current.getReduction(id) ?: return
        val result = NoteEditEngine.transpose(
            RuntimeScore.fromStorage(reduction.notationScore),
            targets,
            stepDelta,
        ) ?: return
        applyStorageEdit { storage ->
            storage.copy(reductions = storage.reductions.map { item ->
                if (item.id == id) {
                    item.updateLayerScore(ReductionLayerKind.NOTATION, result.score.toStorage())
                } else {
                    item
                }
            })
        }
    }

    fun applyReductionRestMove(
        id: ReductionId,
        targets: List<NoteEditEngine.RestMoveTarget>,
    ) {
        val current = runtimeScore?.toStorage() ?: return
        val reduction = current.getReduction(id) ?: return
        val result = NoteEditEngine.moveRest(RuntimeScore.fromStorage(reduction.notationScore), targets)
        val updated = (result as? NoteEditEngine.EditOutcome.Changed)?.score ?: return
        applyStorageEdit { storage ->
            storage.copy(reductions = storage.reductions.map { item ->
                if (item.id == id) {
                    item.updateLayerScore(ReductionLayerKind.NOTATION, updated.toStorage())
                } else {
                    item
                }
            })
        }
    }

    fun applyReductionNoteDeletes(
        id: ReductionId,
        deletions: List<NoteEditEngine.Deletion>,
    ) {
        if (deletions.isEmpty()) return
        val current = runtimeScore?.toStorage() ?: return
        val reduction = current.getReduction(id) ?: return
        var nested = RuntimeScore.fromStorage(reduction.notationScore)
        var changed = false
        deletions.forEach { deletion ->
            NoteEditEngine.delete(nested, deletion)?.let { result ->
                nested = result.score
                changed = true
            }
        }
        if (!changed) return
        applyStorageEdit { storage ->
            storage.copy(reductions = storage.reductions.map { item ->
                if (item.id == id) {
                    item.updateLayerScore(ReductionLayerKind.NOTATION, nested.toStorage())
                } else {
                    item
                }
            })
        }
    }

    fun initializeReductionLayer(id: ReductionId, kind: ReductionLayerKind) =
        applyStorageEdit { storage ->
            storage.copy(reductions = storage.reductions.map { reduction ->
                if (reduction.id == id) {
                    ReductionWorkspaceEngine.initializeScoreLayer(reduction, kind)
                } else {
                    reduction
                }
            })
        }

    fun saveReductionFragment(
        id: ReductionId,
        selectedNotes: Set<NoteRef>,
    ) = applyStorageEdit { storage ->
        val reduction = storage.getReduction(id) ?: return@applyStorageEdit storage
        val name = "素材 ${reduction.materialTray.size + 1}"
        val fragment = ReductionWorkspaceEngine.captureFragment(reduction, selectedNotes, name)
            ?: return@applyStorageEdit storage
        analysisMessage = "$name 已保存到素材台"
        storage.copy(reductions = storage.reductions.map { item ->
            if (item.id == id) {
                item.migrated().copy(materialTray = item.materialTray + fragment)
            } else {
                item
            }
        })
    }

    fun placeReductionFragment(
        id: ReductionId,
        fragmentId: ScoreFragmentId,
        targetMeasure: Int,
    ) = applyStorageEdit { storage ->
        val reduction = storage.getReduction(id) ?: return@applyStorageEdit storage
        val result = ReductionWorkspaceEngine.placeFragment(reduction, fragmentId, targetMeasure)
        if (result == null) {
            analysisMessage = "素材无法写入第 $targetMeasure 小节：目标声部已有内容或范围不足"
            storage
        } else {
            analysisMessage = "素材已作为独立内容写入第 $targetMeasure 小节（${result.copiedEvents} 个事件）"
            storage.copy(reductions = storage.reductions.map { item ->
                if (item.id == id) result.reduction else item
            })
        }
    }

    fun enableOrchestration() = applyStorageEdit { storage ->
        if (storage.orchestration != null) storage
        else storage.copy(orchestration = OrchestrationEngine.initializeFromInstruments(storage))
    }

    fun configureOrchestration(
        drafts: List<OrchestrationInstrumentDraft>
    ) = applyStorageEdit { storage ->
        drafts.fold(storage) { current, draft ->
            OrchestrationEngine.configureInstrument(
                score = current,
                instrumentId = draft.instrumentId,
                kind = draft.kind,
                playerCount = draft.playerCount,
                playerAssignments = draft.playerAssignments,
            )
        }
    }

    // --- Document lifecycle -------------------------------------------------
    override fun addSlurs(
        targets: List<NoteEditEngine.SlurTarget>,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit,
    ) {
        dispatchSharedEdit(
            intent = { revision ->
                ScoreEditIntent.AddSlurs(
                    revision,
                    targets.map {
                        ScoreEditIntent.SlurTarget(it.voiceTrackId, it.startEventId, it.endEventId)
                    },
                )
            },
        ) { result ->
            if (result.effect.kind != ScoreEditEffectKind.APPLIED) return@dispatchSharedEdit
            val computed = result.frame.computedScore
            onAfter(result.frame.selection.mapNotNullTo(linkedSetOf()) { target ->
                val slurId = (target as? ScoreSelectionTarget.Slur)?.slurId
                    ?: return@mapNotNullTo null
                val slur = computed.slurs.firstOrNull { it.slurId == slurId }
                    ?: return@mapNotNullTo null
                val start = computed.getComputedEvent(slur.startEventId)
                    ?: return@mapNotNullTo null
                val end = computed.getComputedEvent(slur.endEventId)
                    ?: return@mapNotNullTo null
                com.mecon.api.interaction.VoiceSlurSection(start, end, slur.nestingLevel)
            })
        }
    }

    override fun toggleArticulation(
        targets: List<ExpressionEditEngine.NoteTarget>,
        articulation: Articulation,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit,
    ) {
        dispatchSharedEdit(
            intent = { revision ->
                ScoreEditIntent.ToggleArticulation(
                    revision,
                    targets.map { ScoreEditIntent.EventTarget(it.voiceTrackId, it.eventId) },
                    articulation,
                )
            },
        ) { result ->
            if (result.effect.kind != ScoreEditEffectKind.APPLIED) return@dispatchSharedEdit
            onAfter(
                result.frame.computedScore.voiceEventSections(
                    result.frame.selection.mapNotNull { it.eventIdOrNull },
                ),
            )
        }
    }

    /** Commit slur creation/deletion and resolve the resulting slur selection. */
    fun applySlurEdit(
        result: NoteEditEngine.SlurEditResult,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit,
    ) {
        val mgr = manager ?: return
        val previousComputed = mgr.currentState.computedScore
        launchRecovering {
            val computed = withContext(Dispatchers.Default) { computeScore(result.score) }
            mgr.commitNewState(
                result.score,
                computed,
                RenderHint(previousComputed, ComputeChangeSet.forRange(result.affectedMeasures)),
            )
            onAfter(result.slurIds.mapNotNull { slurId ->
                val slur = computed.slurs.firstOrNull { it.slurId == slurId } ?: return@mapNotNull null
                val start = computed.getComputedEvent(slur.startEventId) ?: return@mapNotNull null
                val end = computed.getComputedEvent(slur.endEventId) ?: return@mapNotNull null
                com.mecon.api.interaction.VoiceSlurSection(start, end, slur.nestingLevel)
            }.toSet())
        }
    }

    fun applyExpressionEdit(
        result: ExpressionEditEngine.Result,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit,
    ) = commitExpressionEdit(result, onAfter)

    /** Change a selected slur's bow side and ask the renderer to place it afresh on that side. */
    fun applySlurDirection(
        slurId: EventId,
        above: Boolean,
        onAfter: () -> Unit = {},
    ) {
        val current = runtimeScore ?: return
        val mgr = manager ?: return
        val authoritative = current.geometry
        val captured = lastRenderedGeometry
        val base = captured ?: authoritative ?: return
        val slur = authoritative?.slurs?.get(slurId) ?: captured?.slurs?.get(slurId) ?: return
        // Older direction edits could flip only `above` while leaving captured endpoints on the
        // previous side. Treat matching legacy/captured geometry as an explicit request to repair it;
        // only a direction-only override already on the requested side is a true no-op.
        if (slur.above == above && slur.directionOnly) return
        val updatedSlur = slur.copy(
            above = above,
            directionOnly = true,
            directionLocked = true,
        )
        val updated = current.copy(
            geometry = base.copy(slurs = base.slurs.putPersistent(slurId, updatedSlur))
        )
        val previousComputed = mgr.currentState.computedScore
        val computed = previousComputed.copy(runtime = updated)
        val hint = slurMeasureRange(previousComputed, slurId)?.let {
            RenderHint(previousComputed, ComputeChangeSet.forRange(it))
        }
        launchRecovering {
            // Geometry edits do not change musical semantics. Reuse the current computed graph and
            // replace only its runtime reference; never traverse/recompute the score for a curve drag.
            mgr.commitNewState(updated, computed, hint)
            onAfter()
        }
    }

    /** Lock a selected tuplet to one side while retaining automatic endpoint placement. */
    fun applyTupletDirection(startEventId: EventId, above: Boolean) {
        val authoritative = runtimeScore?.geometry?.tuplets?.get(startEventId)
        val captured = lastRenderedGeometry?.tuplets?.get(startEventId)
        val current = authoritative ?: captured
        if (current?.above == above && current.directionLocked) return
        dispatchSharedEdit({ revision ->
            ScoreEditIntent.SetTupletGeometry(
                expectedRevision = revision,
                startEventId = startEventId,
                geometry = (current ?: com.mecon.api.storage.TupletGeometry(above)).copy(
                    above = above,
                    directionLocked = true,
                ),
            )
        })
    }

    /** Change one tie's bow side while retaining automatic anchors and collision avoidance. */
    fun applyTieDirection(
        sourceEventId: EventId,
        sourcePitchIndex: Int,
        above: Boolean,
        onAfter: () -> Unit = {},
    ) {
        val current = runtimeScore ?: return
        val mgr = manager ?: return
        val authoritative = current.geometry
        val captured = lastRenderedGeometry
        val base = captured ?: authoritative ?: return
        val sourceTies = (
            authoritative?.ties?.get(sourceEventId) ?: captured?.ties?.get(sourceEventId)
        ).orEmpty()
        val tie = sourceTies.firstOrNull { it.sourcePitchIndex == sourcePitchIndex } ?: return
        if (tie.above == above && tie.directionOnly && tie.directionLocked) return
        val updatedTie = tie.copy(
            above = above,
            directionOnly = true,
            directionLocked = true,
        )
        val updatedList = sourceTies.map {
            if (it.sourcePitchIndex == sourcePitchIndex) updatedTie else it
        }
        val updated = current.copy(
            geometry = base.copy(ties = base.ties.putPersistent(sourceEventId, updatedList)),
        )
        val previousComputed = mgr.currentState.computedScore
        val computed = previousComputed.copy(runtime = updated)
        val hint = tieMeasureRange(previousComputed, sourceEventId, sourcePitchIndex)?.let {
            RenderHint(previousComputed, ComputeChangeSet.forRange(it))
        }
        launchRecovering {
            mgr.commitNewState(updated, computed, hint)
            onAfter()
        }
    }

    /** Persist a user-adjusted tie curve. */
    fun applyTieGeometry(sourceEventId: EventId, geometry: com.mecon.api.storage.TieGeometry) {
        val current = runtimeScore ?: return
        val mgr = manager ?: return
        val authoritative = current.geometry
        val captured = lastRenderedGeometry
        val base = captured ?: authoritative ?: return
        val existing = (
            authoritative?.ties?.get(sourceEventId) ?: captured?.ties?.get(sourceEventId)
        ).orEmpty()
        val replacement = geometry.copy(
            directionOnly = false,
            directionLocked = true,
            manuallyAdjusted = true,
        )
        val updatedList = if (existing.any { it.sourcePitchIndex == geometry.sourcePitchIndex }) {
            existing.map { if (it.sourcePitchIndex == geometry.sourcePitchIndex) replacement else it }
        } else {
            existing + replacement
        }
        val updated = current.copy(
            geometry = base.copy(ties = base.ties.putPersistent(sourceEventId, updatedList))
        )
        val previousComputed = mgr.currentState.computedScore
        val computed = previousComputed.copy(runtime = updated)
        val hint = tieMeasureRange(
            previousComputed, sourceEventId, geometry.sourcePitchIndex,
        )?.let { RenderHint(previousComputed, ComputeChangeSet.forRange(it)) }
        launchRecovering {
            mgr.commitNewState(updated, computed, hint)
        }
    }

    /** Persist a user-adjusted slur curve. */
    fun applySlurGeometry(slurId: EventId, geometry: com.mecon.api.storage.SlurGeometry) {
        val current = runtimeScore ?: return
        val mgr = manager ?: return
        val authoritative = current.geometry
        val captured = lastRenderedGeometry
        val base = captured ?: authoritative ?: return
        val replacement = geometry.copy(
            directionOnly = false,
            directionLocked = true,
            manuallyAdjusted = true,
        )
        val updated = current.copy(
            geometry = base.copy(slurs = base.slurs.putPersistent(slurId, replacement))
        )
        val previousComputed = mgr.currentState.computedScore
        val computed = previousComputed.copy(runtime = updated)
        val hint = slurMeasureRange(previousComputed, slurId)?.let {
            RenderHint(previousComputed, ComputeChangeSet.forRange(it))
        }
        launchRecovering {
            mgr.commitNewState(updated, computed, hint)
        }
    }

    /** Persist an attachment drag as stable anchor-relative geometry. */
    fun applyAttachmentMove(
        attachmentId: EventId,
        geometry: com.mecon.api.storage.AttachmentGeometry,
        start: com.mecon.api.primitive.TimeCode,
        end: com.mecon.api.primitive.TimeCode?,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit = {},
    ) {
        val current = runtimeScore ?: return
        if (current.globalTrack.tempoEvents.any { it.id == attachmentId }) {
            TempoEditEngine.move(current, attachmentId, start, end)
                ?.let { applyExpressionEdit(it, onAfter) }
            return
        }
        val captured = lastRenderedGeometry ?: current.geometry
        // Runtime history is authoritative after undo/redo. Captured geometry may still describe the
        // frame before the history jump, so use it only to fill entries absent from the current state.
        val authoritative = current.geometry
        val merged = when {
            captured == null -> authoritative
            authoritative == null -> captured
            else -> captured.copy(
                articulations = captured.articulations + authoritative.articulations,
                slurs = captured.slurs + authoritative.slurs,
                attachments = captured.attachments + authoritative.attachments,
                beams = captured.beams + authoritative.beams,
            )
        }
        val seeded = if (merged != null) current.copy(geometry = merged) else current
        ExpressionEditEngine.moveAttachment(seeded, attachmentId, start, end, geometry)
            ?.let { applyExpressionEdit(it, onAfter) }
    }

    /** Commit a structural (measure insert/delete) edit with a full recompute and one undo entry. */
    fun applyMeasureEdit(
        newRuntime: RuntimeScore,
        affectedMeasures: IntRange,
        onAfter: () -> Unit = {},
    ) {
        val mgr = manager ?: return
        if (structuralEditInFlight) return
        val previousComputed = mgr.currentState.computedScore
        structuralEditInFlight = true
        launchRecovering {
            var committed = false
            try {
                val computed = withContext(Dispatchers.Default) { computeScore(newRuntime) }
                val changeSet = ComputeChangeSet(
                    addedEvents = emptySet(), removedEvents = emptySet(), modifiedEvents = emptySet(),
                    affectedMeasures = affectedMeasures, notationChanged = true, structureReflow = true,
                )
                mgr.commitNewState(newRuntime, computed, RenderHint(previousComputed, changeSet))
                committed = true
                onAfter()
            } finally {
                // A successful commit stays locked until the corresponding render settles; otherwise the
                // user could edit old hit-test/index state while the new structural page is still streaming.
                if (!committed) structuralEditInFlight = false
            }
        }
    }

    fun onRenderSettled() {
        structuralEditInFlight = false
    }

    /**
     * Apply a note / rest insertion to the current score and push it as a new history state.
     *
     * The edit runs on the current [runtimeScore] via [NoteEditEngine] (cheap, on the caller's
     * dispatcher); the recompute then runs off the UI thread via [computeScoreIncremental] — a bounded,
     * local recompute around the edited interval that reuses the previous [ComputedScore] by reference
     * (golden rule: identical to a full `computeScore`, see docs/data_model/incremental-update.md). The resulting
     * [RenderHint] (carrying the pre-edit computed + the change set) lets the render pipeline update
     * incrementally instead of re-rendering the whole score. A no-op edit (unknown voice, or a chord
     * pitch already present) is silently ignored.
     */
    override fun applyNoteEdit(
        insertion: NoteEditEngine.Insertion,
        onInputTransition: (ScoreNoteInputTransition) -> Unit,
        onInserted: (com.mecon.api.interaction.EventSection, RuntimeScore) -> Unit,
    ) {
        dispatchSharedEdit(
            intent = { revision ->
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
            },
        ) { result ->
            if (result.effect.kind != ScoreEditEffectKind.APPLIED) return@dispatchSharedEdit
            result.noteInputTransition?.let(onInputTransition)
            result.frame.selection.singleOrNull()
                ?.eventIdOrNull
                ?.let(result.frame.computedScore::getComputedEvent)
                ?.let { onInserted(com.mecon.api.interaction.VoiceEventSection(it), result.frame.runtimeScore) }
        }
    }

    fun insertMeasures(
        afterMeasure: Int,
        count: Int,
        boundaryInsertion: MeasureEditEngine.BoundaryInsertion,
        onAfter: () -> Unit = {},
    ) {
        if (structuralEditInFlight || sharedEditingSession == null) return
        structuralEditInFlight = true
        dispatchSharedEdit(
            intent = { revision ->
                ScoreEditIntent.InsertMeasures(
                    revision,
                    afterMeasure,
                    count,
                    ScoreEditIntent.BoundaryInsertion.valueOf(boundaryInsertion.name),
                )
            },
        ) { result ->
            if (result.effect.kind == ScoreEditEffectKind.APPLIED) onAfter()
            else structuralEditInFlight = false
        }
    }

    fun deleteMeasures(
        measureNumbers: Set<Int>,
        onAfter: () -> Unit = {},
    ) {
        if (structuralEditInFlight || sharedEditingSession == null) return
        structuralEditInFlight = true
        dispatchSharedEdit(
            intent = { revision -> ScoreEditIntent.DeleteMeasures(revision, measureNumbers) },
        ) { result ->
            if (result.effect.kind == ScoreEditEffectKind.APPLIED) onAfter()
            else structuralEditInFlight = false
        }
    }

    /**
     * Apply one computer/MIDI step-input batch atomically. Requests may arrive faster than compute
     * and rendering; [noteInputMutex] preserves their order and reads state only after the previous
     * batch has committed, so no note is based on a stale score snapshot.
     */
    fun applyChordInput(
        insertion: NoteEditEngine.ChordInsertion,
        onInserted: (com.mecon.api.interaction.EventSection, RuntimeScore) -> Unit = { _, _ -> },
    ) {
        launchRecovering {
            noteInputMutex.withLock {
                val mgr = manager ?: return@withLock
                val current = state ?: return@withLock
                val result = NoteEditEngine.insertChord(current.runtimeScore, insertion)
                    ?: return@withLock
                val previousComputed = current.computedScore
                val incremental = withContext(Dispatchers.Default) {
                    computeScoreIncremental(previousComputed, result.score, result.editInterval)
                }
                mgr.commitNewState(
                    result.score,
                    incremental.computed,
                    RenderHint(previousComputed, incremental.changeSet),
                )
                // Publish synchronously before unlocking; the next queued input must observe this
                // state even if the manager's collector has not resumed yet.
                state = mgr.currentState
                result.insertedEventId
                    ?.let { incremental.computed.getComputedEvent(it) }
                    ?.let { onInserted(com.mecon.api.interaction.VoiceEventSection(it), result.score) }
            }
        }
    }

    /** Quantize/materialize one realtime take and publish exactly one undo-history state. */
    fun applyCaptureInput(
        insertion: NoteEditEngine.CaptureInsertion,
        onCommitted: (RuntimeScore) -> Unit = {},
    ) {
        launchRecovering {
            noteInputMutex.withLock {
                val mgr = manager ?: return@withLock
                val current = state ?: return@withLock
                val result = NoteEditEngine.insertCapture(current.runtimeScore, insertion)
                    ?: return@withLock
                val previousComputed = current.computedScore
                val incremental = withContext(Dispatchers.Default) {
                    computeScoreIncremental(previousComputed, result.score, result.editInterval)
                }
                mgr.commitNewState(
                    result.score,
                    incremental.computed,
                    RenderHint(previousComputed, incremental.changeSet),
                )
                state = mgr.currentState
                onCommitted(result.score)
            }
        }
    }

    /**
     * Delete the selected note(s) / rest(s) and push the result as one history state.
     *
     * Each [NoteEditEngine.Deletion] is folded onto the runtime in turn (cheap, on the caller's
     * dispatcher); unaffected events keep their identity so a later deletion in the same batch still
     * resolves its target. A single deletion takes the incremental recompute + [RenderHint] path
     * (like [applyNoteEdit]); a multi-delete falls back to a full recompute. The resulting rests /
     * trimmed chord events are reported back via [onAfter] so the caller can re-point the selection.
     * A batch that resolves to no real deletions (all unknown events) is silently ignored.
     */
    override fun applyNoteDeletes(
        deletions: List<NoteEditEngine.Deletion>,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit,
    ) {
        if (deletions.isEmpty()) return
        dispatchSharedEdit(
            intent = { revision ->
                ScoreEditIntent.DeleteNotes(
                    expectedRevision = revision,
                    targets = deletions.map {
                        ScoreEditIntent.EventTarget(it.voiceTrackId, it.eventId, it.pitchIndices)
                    },
                )
            },
        ) { result ->
            if (result.effect.kind != ScoreEditEffectKind.APPLIED) return@dispatchSharedEdit
            onAfter(
                result.frame.computedScore.voiceEventSections(
                    result.frame.selection.mapNotNull { it.eventIdOrNull },
                ),
            )
        }
    }

    fun applyNotePaste(
        clipboard: NoteEditEngine.NoteClipboard,
        target: NoteEditEngine.PasteTarget,
        onTupletCrossesBarline: () -> Unit = {},
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit = {},
    ) {
        val mgr = manager ?: return
        val current = state ?: return
        val previousComputed = current.computedScore
        val result = when (val outcome = NoteEditEngine.pasteNotesWithStatus(current.runtimeScore, clipboard, target)) {
            is NoteEditEngine.PasteOutcome.Changed -> outcome.result
            NoteEditEngine.PasteOutcome.TupletCrossesBarline -> {
                onTupletCrossesBarline()
                return
            }
            NoteEditEngine.PasteOutcome.NoOp -> return
        }

        launchRecovering {
            if (result.intervals.size == 1) {
                val incremental = withContext(Dispatchers.Default) {
                    computeScoreIncremental(previousComputed, result.score, result.intervals.single())
                }
                mgr.commitNewState(
                    result.score,
                    incremental.computed,
                    RenderHint(previousComputed, incremental.changeSet),
                )
                onAfter(incremental.computed.voiceEventSections(result.pastedEventIds))
            } else {
                val computed = withContext(Dispatchers.Default) { computeScore(result.score) }
                mgr.commitNewState(result.score, computed)
                onAfter(computed.voiceEventSections(result.pastedEventIds))
            }
        }
    }

    fun applyEditorPaste(
        noteClipboard: NoteEditEngine.NoteClipboard?,
        expressionClipboard: ExpressionEditEngine.Clipboard?,
        target: NoteEditEngine.PasteTarget,
        onTupletCrossesBarline: () -> Unit = {},
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit = {},
    ) {
        val mgr = manager ?: return
        val current = state ?: return
        var score = current.runtimeScore
        val pastedEventIds = ArrayList<EventId>()
        if (noteClipboard != null && !noteClipboard.isEmpty) {
            when (val outcome = NoteEditEngine.pasteNotesWithStatus(score, noteClipboard, target)) {
                is NoteEditEngine.PasteOutcome.Changed -> {
                    score = outcome.result.score
                    pastedEventIds += outcome.result.pastedEventIds
                }
                NoteEditEngine.PasteOutcome.TupletCrossesBarline -> {
                    onTupletCrossesBarline()
                    return
                }
                NoteEditEngine.PasteOutcome.NoOp -> {}
            }
        }
        val staffId = score.staffTracks.values.firstOrNull { staff ->
            staff.voiceTracks.any { it.id == target.voiceTrackId }
        }?.id
        val expressionResult = if (expressionClipboard != null && staffId != null) {
            ExpressionEditEngine.pasteAttachments(score, expressionClipboard, staffId, target.start)
        } else null
        if (expressionResult != null) score = expressionResult.score
        if (score === current.runtimeScore) return
        val finalScore = score
        launchRecovering {
            val computed = withContext(Dispatchers.Default) { computeScore(finalScore) }
            mgr.commitNewState(finalScore, computed)
            onAfter(buildSet {
                pastedEventIds.mapNotNull(computed::getComputedEvent)
                    .forEach { add(com.mecon.api.interaction.VoiceEventSection(it)) }
                val ids = expressionResult?.selectedAttachmentIds.orEmpty()
                computed.staffAttachments.filter { it.id in ids }
                    .forEach { add(com.mecon.api.interaction.StaffAttachmentSection(it)) }
            })
        }
    }

    /**
     * Transpose the selected note(s) by [stepDelta] diatonic steps and push the result as one history
     * state. Mirrors [applyNoteDeletes]: a single touched measure takes the incremental recompute +
     * [RenderHint] path; multiple measures fall back to a full recompute. The moved events keep their
     * identity, so [onAfter] re-points the selection at them (they stay selected after the drag). A
     * no-op transpose (zero delta, unknown events, or rests only) is silently ignored.
     */
    override fun applyNoteTranspose(
        targets: List<NoteEditEngine.TransposeTarget>,
        stepDelta: Int,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit,
    ) {
        if (targets.isEmpty() || stepDelta == 0) return
        dispatchSharedEdit(
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
            if (result.effect.kind != ScoreEditEffectKind.APPLIED) return@dispatchSharedEdit
            onAfter(
                result.frame.computedScore.movedEventSections(
                    result.frame.selection.filterIsInstance<ScoreSelectionTarget.Event>()
                        .map { it.eventId to it.pitchIndices },
                ),
            )
        }
    }

    fun applySharedNoteCopy(
        targets: List<NoteEditEngine.CopyTarget>,
        onAfter: (Boolean) -> Unit = {},
    ) {
        dispatchSharedEdit(
            intent = { revision ->
                ScoreEditIntent.CopyNotes(
                    revision,
                    targets.map {
                        ScoreEditIntent.CopyTarget(it.voiceTrackId, it.eventId, it.pitchIndices, it.beaming)
                    },
                )
            },
        ) { result -> onAfter(result.effect.kind == ScoreEditEffectKind.COPIED) }
    }

    fun applySharedNoteCut(
        targets: List<NoteEditEngine.CopyTarget>,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit = {},
    ) {
        dispatchSharedEdit(
            intent = { revision ->
                ScoreEditIntent.CutNotes(
                    revision,
                    targets.map {
                        ScoreEditIntent.CopyTarget(it.voiceTrackId, it.eventId, it.pitchIndices, it.beaming)
                    },
                )
            },
        ) { result ->
            if (result.effect.kind == ScoreEditEffectKind.CUT) {
                onAfter(result.frame.computedScore.voiceEventSections(result.frame.selection.mapNotNull { it.eventIdOrNull }))
            }
        }
    }

    fun applySharedNotePaste(
        target: NoteEditEngine.PasteTarget,
        onTupletCrossesBarline: () -> Unit = {},
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit = {},
    ) {
        dispatchSharedEdit(
            intent = { revision ->
                ScoreEditIntent.PasteNotes(revision, target.voiceTrackId, target.start, target.clearMeasure)
            },
        ) { result ->
            when (result.effect.kind) {
                ScoreEditEffectKind.PASTED -> onAfter(
                    result.frame.computedScore.voiceEventSections(result.frame.selection.mapNotNull { it.eventIdOrNull }),
                )
                ScoreEditEffectKind.CONFLICT -> if (
                    result.effect.messageKey == "scoreEditing.pasteTupletCrossesBarline"
                ) onTupletCrossesBarline()
                else -> Unit
            }
        }
    }

    /**
     * Change the note value of the selected event(s) in place and push the result as one history
     * state. A batch whose grow would overlap a following note is rejected wholesale ([onConflict]
     * fires, nothing changes — see [NoteEditEngine.editDurations]). Otherwise mirrors
     * [applyNoteDeletes]: a single touched measure takes the incremental path, multiple fall back to a
     * full recompute, and the resulting events are reported back via [onAfter] to re-point the selection.
     */
    override fun applyDurationEdits(
        edits: List<NoteEditEngine.DurationEdit>,
        onConflict: () -> Unit,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit,
    ) {
        dispatchSharedEdit(
            intent = { revision ->
                ScoreEditIntent.SetDurations(
                    revision,
                    edits.map { ScoreEditIntent.DurationTarget(it.voiceTrackId, it.eventId, it.duration) },
                )
            },
        ) { result ->
            when (result.effect.kind) {
                ScoreEditEffectKind.CONFLICT -> onConflict()
                ScoreEditEffectKind.APPLIED -> onAfter(
                    result.frame.computedScore.voiceEventSections(result.frame.selection.mapNotNull { it.eventIdOrNull }),
                )
                else -> Unit
            }
        }
    }

    override fun applyTupletEdit(
        edits: List<NoteEditEngine.TupletEdit>,
        onConflict: () -> Unit,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit,
    ) {
        val current = state ?: return
        when (val outcome = NoteEditEngine.applyTuplets(current.runtimeScore, edits)) {
            is NoteEditEngine.EditOutcome.Conflict -> onConflict()
            is NoteEditEngine.EditOutcome.NoOp -> {}
            is NoteEditEngine.EditOutcome.Changed -> commitEdit(current.computedScore, outcome, onAfter)
        }
    }

    fun applyGraceGroupEdits(
        edits: List<NoteEditEngine.GraceGroupEdit>,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit = {},
    ) {
        val current = state ?: return
        (NoteEditEngine.editGraceGroups(current.runtimeScore, edits) as? NoteEditEngine.EditOutcome.Changed)
            ?.let { commitEdit(current.computedScore, it, onAfter) }
    }

    override fun applySmallNoteEdits(
        edits: List<NoteEditEngine.SmallNoteEdit>,
        onConflict: () -> Unit,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit,
    ) {
        val current = state ?: return
        when (val outcome = NoteEditEngine.createSmallNoteRegions(current.runtimeScore, edits)) {
            is NoteEditEngine.EditOutcome.Conflict -> onConflict()
            is NoteEditEngine.EditOutcome.NoOp -> Unit
            is NoteEditEngine.EditOutcome.Changed -> commitEdit(current.computedScore, outcome, onAfter)
        }
    }

    /** Set/clear the accidental on the selected event(s) (chord-wide); see [NoteEditEngine.editAccidentals]. */
    override fun applyAccidentalEdit(
        edits: List<NoteEditEngine.AccidentalEdit>,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit,
    ) {
        dispatchSharedEdit(
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
        ) { result ->
            if (result.effect.kind == ScoreEditEffectKind.APPLIED) {
                onAfter(result.frame.computedScore.voiceEventSections(result.frame.selection.mapNotNull { it.eventIdOrNull }))
            }
        }
    }

    /** Toggle the trailing tie on the selected event(s) (chord-wide); see [NoteEditEngine.editTies]. */
    override fun applyTieEdit(
        edits: List<NoteEditEngine.TieEdit>,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit,
    ) {
        dispatchSharedEdit(
            intent = { revision ->
                ScoreEditIntent.SetTies(
                    revision,
                    edits.map {
                        ScoreEditIntent.TieTarget(it.voiceTrackId, it.eventId, it.tieOut, it.pitchIndices)
                    },
                )
            },
        ) { result ->
            if (result.effect.kind == ScoreEditEffectKind.APPLIED) {
                onAfter(result.frame.computedScore.voiceEventSections(result.frame.selection.mapNotNull { it.eventIdOrNull }))
            }
        }
    }

    /** Set or clear the explicit beam override on the selected events; see [NoteEditEngine.editBeaming]. */
    override fun applyBeamingEdit(
        edits: List<NoteEditEngine.BeamingEdit>,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit,
    ) {
        dispatchSharedEdit(
            intent = { revision ->
                ScoreEditIntent.SetBeaming(
                    revision,
                    edits.map { ScoreEditIntent.BeamingTarget(it.voiceTrackId, it.eventId, it.beaming) },
                )
            },
        ) { result ->
            if (result.effect.kind == ScoreEditEffectKind.APPLIED) {
                onAfter(result.frame.computedScore.voiceEventSections(result.frame.selection.mapNotNull { it.eventIdOrNull }))
            }
        }
    }

    /** Insert or edit a clef change on a staff. */
    fun applyClefEdit(
        target: ClefEditEngine.Target,
        clef: com.mecon.api.storage.tracks.Clef,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit = {},
    ) {
        dispatchSharedEdit(
            intent = { revision ->
                ScoreEditIntent.SetClef(
                    revision,
                    target.staffTrackId,
                    target.onset,
                    clef,
                    target.resolveExisting,
                )
            },
        ) { result ->
            if (result.effect.kind == ScoreEditEffectKind.APPLIED) {
                val targets = result.frame.selection.filterIsInstance<ScoreSelectionTarget.Clef>()
                onAfter(result.frame.computedScore.clefs.filter { clef ->
                    targets.any { it.staffTrackId == clef.staffTrackId && it.onset == clef.time }
                }
                    .mapTo(LinkedHashSet()) { com.mecon.api.interaction.ClefSection(it) })
            }
        }
    }

    /** Replace one existing logical barline boundary and reselect its computed result. */
    fun applyBarlineEdit(
        boundaryMeasure: Int,
        type: BarlineType,
        repeatCount: Int = 2,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit = {},
    ) {
        dispatchSharedEdit(
            intent = { revision -> ScoreEditIntent.SetBarline(revision, boundaryMeasure, type, repeatCount) },
        ) { result ->
            if (result.effect.kind == ScoreEditEffectKind.APPLIED) onAfter(sharedStructuralSelection(result))
        }
    }

    /** Change the repeat pass count exposed by either end of a repeat section. */
    fun applyBarlineRepeatCountEdit(
        selectedBoundaryMeasure: Int,
        repeatCount: Int,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit = {},
    ) {
        dispatchSharedEdit(
            intent = { revision ->
                ScoreEditIntent.SetBarlineRepeatCount(revision, selectedBoundaryMeasure, repeatCount)
            },
        ) { result ->
            if (result.effect.kind == ScoreEditEffectKind.APPLIED) onAfter(sharedStructuralSelection(result))
        }
    }

    fun applyVoltaEdit(
        boundaryMeasure: Int,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit = {},
    ) = dispatchSharedEdit(
        intent = { revision -> ScoreEditIntent.ToggleVoltaPair(revision, boundaryMeasure) },
    ) { result ->
        if (result.effect.kind == ScoreEditEffectKind.APPLIED) onAfter(sharedStructuralSelection(result))
    }

    fun resizeSecondVolta(
        startMeasure: Int,
        endMeasure: Int,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit = {},
    ) = dispatchSharedEdit(
        intent = { revision -> ScoreEditIntent.ResizeSecondVolta(revision, startMeasure, endMeasure) },
    ) { result ->
        if (result.effect.kind == ScoreEditEffectKind.APPLIED) onAfter(sharedStructuralSelection(result))
    }

    fun resizeFirstVoltaStart(
        startMeasure: Int,
        newStartMeasure: Int,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit = {},
    ) = dispatchSharedEdit(
        intent = { revision -> ScoreEditIntent.ResizeFirstVoltaStart(revision, startMeasure, newStartMeasure) },
    ) { result ->
        if (result.effect.kind == ScoreEditEffectKind.APPLIED) onAfter(sharedStructuralSelection(result))
    }

    fun deleteVolta(
        section: com.mecon.api.interaction.VoltaEndingSection,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit = {},
    ) = dispatchSharedEdit(
        intent = { revision ->
            ScoreEditIntent.DeleteVolta(
                revision,
                section.ending.startMeasure,
                section.ending.endMeasure,
                section.ending.numbers,
            )
        },
    ) { result ->
        if (result.effect.kind == ScoreEditEffectKind.APPLIED) onAfter(emptySet())
    }

    fun applyNavigationMarkEdit(
        boundaryMeasure: Int,
        mark: NavigationMark,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit = {},
    ) = dispatchSharedEdit(
        intent = { revision -> ScoreEditIntent.ToggleNavigationMark(revision, boundaryMeasure, mark) },
    ) { result ->
        if (result.effect.kind == ScoreEditEffectKind.APPLIED) onAfter(sharedStructuralSelection(result))
    }

    fun moveNavigationMark(
        boundaryMeasure: Int,
        targetBoundaryMeasure: Int,
        mark: NavigationMark,
        offset: NavigationMarkOffset,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit = {},
    ) = dispatchSharedEdit(
        intent = { revision ->
            ScoreEditIntent.MoveNavigationMark(
                revision,
                boundaryMeasure,
                targetBoundaryMeasure,
                mark,
                offset,
            )
        },
    ) { result ->
        if (result.effect.kind == ScoreEditEffectKind.APPLIED) onAfter(sharedStructuralSelection(result))
    }

    fun deleteNavigationMark(
        section: com.mecon.api.interaction.NavigationMarkSection,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit = {},
    ) = dispatchSharedEdit(
        intent = { revision ->
            ScoreEditIntent.DeleteNavigationMark(
                revision,
                section.navigation.boundaryMeasure,
                section.navigation.mark,
            )
        },
    ) { result ->
        if (result.effect.kind == ScoreEditEffectKind.APPLIED) onAfter(emptySet())
    }

    /**
     * Set the time signature at [measureNumber] and re-bar the affected span (see
     * [TimeSignatureEditEngine]). A meter change re-flows onsets and may add measures, so this takes
     * the full-recompute path (like [applyClefEdit]) rather than an incremental one. The resulting
     * [com.mecon.api.interaction.TimeSignatureSection] at the edited measure is reported back via
     * [onAfter] so the caller can re-point the selection.
     */
    fun applyTimeSignatureEdit(
        measureNumber: Int,
        timeSignature: TimeSignature,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit = {},
    ) {
        dispatchSharedEdit(
            intent = { revision -> ScoreEditIntent.SetTimeSignature(revision, measureNumber, timeSignature) },
        ) { result ->
            if (result.effect.kind == ScoreEditEffectKind.APPLIED) {
                val targets = result.frame.selection.filterIsInstance<ScoreSelectionTarget.TimeSignature>()
                onAfter(result.frame.computedScore.timeSignatures.filter { signature ->
                    targets.any { it.staffTrackId == signature.staffTrackId && it.onset == signature.time }
                }
                    .mapTo(LinkedHashSet()) { com.mecon.api.interaction.TimeSignatureSection(it) })
            }
        }
    }

    /** Insert or edit a key signature and re-point the selection to the edited key section. */
    fun applyKeySignatureEdit(
        target: KeySignatureEditEngine.Target,
        keySignature: KeySignature,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit = {},
    ) {
        dispatchSharedEdit(
            intent = { revision -> ScoreEditIntent.SetKeySignature(revision, target.onset, keySignature) },
        ) { result ->
            if (result.effect.kind == ScoreEditEffectKind.APPLIED) {
                val targets = result.frame.selection.filterIsInstance<ScoreSelectionTarget.KeySignature>()
                onAfter(result.frame.computedScore.keySignatures.filter { signature ->
                    targets.any { it.staffTrackId == signature.staffTrackId && it.onset == signature.time }
                }
                    .mapTo(LinkedHashSet()) { com.mecon.api.interaction.KeySignatureSection(it) })
            }
        }
    }

    /** Move the selected rest(s) to a new display staff position; see [NoteEditEngine.moveRest]. */
    override fun applyRestMove(
        targets: List<NoteEditEngine.RestMoveTarget>,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit,
    ) {
        val current = state ?: return
        (NoteEditEngine.moveRest(current.runtimeScore, targets) as? NoteEditEngine.EditOutcome.Changed)
            ?.let { commitEdit(current.computedScore, it, onAfter) }
    }

    /** Move selected noteheads/events to another voice; see [NoteEditEngine.moveVoices]. */
    override fun applyVoiceMove(
        targets: List<NoteEditEngine.VoiceMoveTarget>,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit,
    ) {
        dispatchSharedEdit(
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
            if (result.effect.kind == ScoreEditEffectKind.APPLIED) {
                onAfter(
                    result.frame.computedScore.movedEventSections(
                        result.frame.selection.filterIsInstance<ScoreSelectionTarget.Event>()
                        .map { it.eventId to it.pitchIndices },
                    ),
                )
            }
        }
    }

    /**
     * Commit an in-place property edit ([NoteEditEngine.EditOutcome.Changed]) off the UI thread and
     * re-point the selection at the edited events. A single touched interval takes the incremental
     * recompute + [RenderHint] path; multiple intervals fall back to a full recompute. Shared by
     * [applyDurationEdits] / [applyAccidentalEdit] / [applyTieEdit].
     */
    private fun commitEdit(
        previousComputed: ComputedScore,
        outcome: NoteEditEngine.EditOutcome.Changed,
        onAfter: (Set<com.mecon.api.interaction.EventSection>) -> Unit,
    ) {
        val mgr = manager ?: return
        val finalRt = outcome.score
        launchRecovering {
            if (outcome.intervals.size == 1) {
                val incremental = withContext(Dispatchers.Default) {
                    computeScoreIncremental(previousComputed, finalRt, outcome.intervals.single())
                }
                mgr.commitNewState(
                    finalRt, incremental.computed,
                    RenderHint(previousComputed, incremental.changeSet),
                )
                onAfter(incremental.computed.voiceEventSections(outcome.resultEventIds))
            } else {
                val computed = withContext(Dispatchers.Default) { computeScore(finalRt) }
                mgr.commitNewState(finalRt, computed)
                onAfter(computed.voiceEventSections(outcome.resultEventIds))
            }
        }
    }


}
