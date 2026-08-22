package com.mecon.features.scoreediting

import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.ComputeChangeSet
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeRange
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.ScoreTimeMap
import com.mecon.api.runtime.toStorage
import com.mecon.api.state.EditorStateController
import com.mecon.api.state.RenderHint
import com.mecon.api.state.ScoreStateManager
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.ScoreGeometry
import com.mecon.api.storage.SlurGeometry
import com.mecon.api.storage.tracks.MeasureRange
import com.mecon.core.engine.computeScore
import com.mecon.core.engine.computeScoreIncremental
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.core.engine.edit.BarlineEditEngine
import com.mecon.core.engine.edit.ClefEditEngine
import com.mecon.core.engine.edit.KeySignatureEditEngine
import com.mecon.core.engine.edit.MeasureEditEngine
import com.mecon.core.engine.edit.RepeatStructureEditEngine
import com.mecon.core.engine.edit.TimeSignatureEditEngine
import com.mecon.core.engine.edit.ExpressionEditEngine
import com.mecon.core.engine.edit.TempoEditEngine
import com.mecon.core.engine.edit.LayoutBreakEditEngine
import com.mecon.core.engine.edit.StaffVisibilityEditEngine

/** In-process frame; large immutable score objects deliberately stay as references. */
data class ScoreEditingFrame(
    val revision: Long,
    val runtimeScore: RuntimeScore,
    val computedScore: ComputedScore,
    val renderHint: RenderHint?,
    val selection: List<ScoreSelectionTarget>,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val canPaste: Boolean,
    /** Where sequential input continues; see [ScoreEditUpdate.nextInputPosition]. */
    val nextInputPosition: TimeCode? = null,
    /** Whether the dispatch that produced this frame committed a new score. */
    val scoreChanged: Boolean = true,
)

data class ScoreEditDispatchResult(
    val frame: ScoreEditingFrame,
    val baseRevision: Long?,
    val effect: ScoreEditEffect,
    val noteInputTransition: ScoreNoteInputTransition? = null,
) {
    fun toWireUpdate(): ScoreEditUpdate = toWireUpdate(frame.runtimeScore.toStorage())

    internal fun toWireUpdate(storageScore: StorageScore): ScoreEditUpdate = ScoreEditUpdate(
        revision = frame.revision,
        baseRevision = baseRevision,
        score = storageScore,
        selection = frame.selection,
        canUndo = frame.canUndo,
        canRedo = frame.canRedo,
        canPaste = frame.canPaste,
        nextInputPosition = frame.nextInputPosition,
        noteInputTransition = noteInputTransition,
        scoreChanged = frame.scoreChanged,
        renderHint = frame.renderHint?.let {
            ScoreEditRenderHint(
                firstMeasure = it.changeSet.affectedMeasures.first,
                lastMeasure = it.changeSet.affectedMeasures.last,
                notationChanged = it.changeSet.notationChanged,
                structureReflow = it.changeSet.structureReflow,
            )
        },
        effect = effect,
    )
}

/** Optional composite-session policy applied before and within the same score history commit. */
class ScoreEditingCommitPolicy(
    val reject: (previous: RuntimeScore, candidate: RuntimeScore) -> String? = { _, _ -> null },
    val updateCompanionState: (previous: RuntimeScore, committed: RuntimeScore) -> Unit = { _, _ -> },
)

private class ScoreCommitRejected(val messageKey: String) : RuntimeException(messageKey)

/**
 * Serial, UI-free score editing state machine. Callers run [dispatch] on their document worker or
 * background dispatcher; the session itself never launches work or touches a platform UI runtime.
 */
class ScoreEditingSession private constructor(
    private val manager: ScoreStateManager,
    private val commitPolicy: ScoreEditingCommitPolicy?,
) {
    private var revision: Long = 0L
    private var selection: List<ScoreSelectionTarget> = emptyList()
    private var noteClipboard: NoteEditEngine.NoteClipboard? = null
    private var observedState = manager.currentState
    private var nextInputPosition: TimeCode? = null

    /**
     * State the current operation started from — a [dispatch], or a composite session's operation
     * announced through [beginExternalOperation]. Every commit replaces
     * [ScoreStateManager.currentState] with a new instance, so an identity comparison reports whether
     * the score really changed without each handler having to remember to flag it.
     */
    private var dispatchBaseState = manager.currentState

    /** Memoized storage projection of the last serialized runtime score; see [wireUpdate]. */
    private var storageSource: RuntimeScore? = null
    private var storageProjection: StorageScore? = null

    init {
        manager.registerEditorState(
            SELECTION_STATE_KEY,
            object : EditorStateController {
                override fun capture(): Any = selection

                @Suppress("UNCHECKED_CAST")
                override fun restore(snapshot: Any?) {
                    selection = snapshot as? List<ScoreSelectionTarget> ?: emptyList()
                }
            },
        )
    }

    fun frame(): ScoreEditingFrame {
        synchronizeExternalState()
        val state = manager.currentState
        return ScoreEditingFrame(
            revision = revision,
            runtimeScore = state.runtimeScore,
            computedScore = state.computedScore,
            renderHint = state.renderHint,
            selection = selection,
            canUndo = manager.canUndo(),
            canRedo = manager.canRedo(),
            canPaste = noteClipboard?.isEmpty == false,
            nextInputPosition = nextInputPosition,
            // Renderers consume the score, so identity of the score itself is the honest predicate:
            // a companion-state-only commit leaves it untouched and needs no re-layout.
            scoreChanged = state.runtimeScore !== dispatchBaseState.runtimeScore,
        )
    }

    /**
     * Sequential input continues after the committed event, so tuplet ratios, dots and cross-measure
     * splits are resolved by the same engine that performed the edit rather than re-derived by each
     * platform. Grace notes never move the cursor: they borrow time from their principal.
     */
    private fun advanceInputPositionTo(eventId: EventId?) {
        val event = eventId?.let { manager.currentState.computedScore.getComputedEvent(it) } ?: return
        if (event.onset.grace?.isZero == false) return
        val runtime = manager.currentState.runtimeScore
        val timeMap = ScoreTimeMap.from(runtime)
        nextInputPosition = timeMap.timeCodeAt(
            timeMap.absolute(event.onset) + event.duration.toFraction(),
        )
    }

    fun initialUpdate(): ScoreEditUpdate = wireUpdate(
        ScoreEditDispatchResult(
            frame = frame(),
            baseRevision = null,
            effect = ScoreEditEffect(ScoreEditEffectKind.APPLIED),
        ),
    )

    /**
     * Serializes [result] for the wire, reusing the previous storage projection while the runtime
     * score is unchanged. Selection, clipboard and background-result frames all republish the same
     * score, and `toStorage` is O(score) — paying it per frame is exactly the whole-score work a
     * large-document editor must keep off the hot path.
     */
    fun wireUpdate(result: ScoreEditDispatchResult): ScoreEditUpdate {
        val runtime = result.frame.runtimeScore
        val storage = storageProjection?.takeIf { storageSource === runtime } ?: runtime.toStorage().also {
            storageSource = runtime
            storageProjection = it
        }
        return result.toWireUpdate(storage)
    }

    /**
     * Marks the start of an owning composite session's operation.
     *
     * [frame] reports `scoreChanged` by comparing against the state this session last started an
     * operation from. Composite sessions commit score material outside [dispatch], so without this
     * marker the first external commit would make every later frame — selections, catalog and
     * finding results — claim the score changed, and each of those would re-layout the whole score.
     */
    fun beginExternalOperation() {
        synchronizeExternalState()
        dispatchBaseState = manager.currentState
    }

    /**
     * Publishes a score transaction committed by an owning composite session.
     *
     * Composite features such as free practice share this session's [ScoreStateManager] so score
     * material and their companion state can be committed on one undo boundary. Calling this
     * method after that commit advances the score-editing revision explicitly; it avoids relying
     * on the temporary reference-identity migration fallback in [synchronizeExternalState].
     */
    fun notifyExternalCommit() {
        val current = manager.currentState
        if (current !== observedState) {
            observedState = current
            revision++
        }
    }

    fun dispatch(intent: ScoreEditIntent): ScoreEditDispatchResult {
        synchronizeExternalState()
        // Only an accepted edit may move a client's input cursor; rejected and no-op intents report
        // null so a stale command can never drag the cursor backwards.
        nextInputPosition = null
        dispatchBaseState = manager.currentState
        if (intent.expectedRevision != revision) {
            return result(
                baseRevision = intent.expectedRevision,
                effect = ScoreEditEffect(
                    kind = ScoreEditEffectKind.STALE_REVISION,
                    messageKey = "scoreEditing.staleRevision",
                    expectedRevision = intent.expectedRevision,
                    actualRevision = revision,
                ),
            )
        }
        val baseRevision = revision
        return try {
            when (intent) {
            is ScoreEditIntent.SetSelection -> {
                selection = intent.targets.distinct()
                revision++
                result(baseRevision, ScoreEditEffect(ScoreEditEffectKind.SELECTION_CHANGED))
            }
            is ScoreEditIntent.InsertNote -> insert(intent, baseRevision)
            is ScoreEditIntent.InsertChord -> insertChord(intent, baseRevision)
            is ScoreEditIntent.CreateTupletRegion -> createTupletRegion(intent, baseRevision)
            is ScoreEditIntent.CopyNotes -> copy(intent, baseRevision)
            is ScoreEditIntent.CutNotes -> cut(intent, baseRevision)
            is ScoreEditIntent.PasteNotes -> paste(intent, baseRevision)
            is ScoreEditIntent.MoveVoices -> moveVoices(intent, baseRevision)
            is ScoreEditIntent.SetClef -> setClef(intent, baseRevision)
            is ScoreEditIntent.SetKeySignature -> setKeySignature(intent, baseRevision)
            is ScoreEditIntent.SetTimeSignature -> setTimeSignature(intent, baseRevision)
            is ScoreEditIntent.InsertMeasures -> insertMeasures(intent, baseRevision)
            is ScoreEditIntent.DeleteMeasures -> deleteMeasures(intent, baseRevision)
            is ScoreEditIntent.SetBarline -> setBarline(intent, baseRevision)
            is ScoreEditIntent.SetBarlineRepeatCount -> setBarlineRepeatCount(intent, baseRevision)
            is ScoreEditIntent.ToggleVoltaPair -> repeatStructure(baseRevision) { score ->
                RepeatStructureEditEngine.toggleVoltaPair(score, intent.boundaryMeasure)
            }
            is ScoreEditIntent.ResizeSecondVolta -> repeatStructure(baseRevision) { score ->
                RepeatStructureEditEngine.resizeSecondVolta(score, intent.startMeasure, intent.endMeasure)
            }
            is ScoreEditIntent.ResizeFirstVoltaStart -> repeatStructure(baseRevision) { score ->
                RepeatStructureEditEngine.resizeFirstVoltaStart(score, intent.startMeasure, intent.newStartMeasure)
            }
            is ScoreEditIntent.DeleteVolta -> repeatStructure(baseRevision, select = false) { score ->
                RepeatStructureEditEngine.deleteVolta(score, intent.startMeasure, intent.endMeasure, intent.numbers)
            }
            is ScoreEditIntent.ToggleNavigationMark -> repeatStructure(baseRevision) { score ->
                RepeatStructureEditEngine.toggleNavigationMark(score, intent.boundaryMeasure, intent.mark)
            }
            is ScoreEditIntent.MoveNavigationMark -> repeatStructure(baseRevision) { score ->
                RepeatStructureEditEngine.moveNavigationMark(
                    score,
                    intent.boundaryMeasure,
                    intent.targetBoundaryMeasure,
                    intent.mark,
                    intent.offset,
                )
            }
            is ScoreEditIntent.DeleteNavigationMark -> repeatStructure(baseRevision, select = false) { score ->
                RepeatStructureEditEngine.deleteNavigationMark(score, intent.boundaryMeasure, intent.mark)
            }
            is ScoreEditIntent.AddSlurs -> addSlurs(intent, baseRevision)
            is ScoreEditIntent.DeleteSlurs -> deleteSlurs(intent, baseRevision)
            is ScoreEditIntent.SetSlurGeometry -> setSlurGeometry(intent, baseRevision)
            is ScoreEditIntent.SetTieGeometry -> setTieGeometry(intent, baseRevision)
            is ScoreEditIntent.SetTupletGeometry -> setTupletGeometry(intent, baseRevision)
            is ScoreEditIntent.SetBeamGeometry -> setBeamGeometry(intent, baseRevision)
            is ScoreEditIntent.SetArticulationGeometry -> setArticulationGeometry(intent, baseRevision)
            is ScoreEditIntent.AddDynamic -> expression(
                baseRevision,
                ExpressionEditEngine.addDynamic(
                    manager.currentState.runtimeScore, intent.staffTrackId, intent.onset, intent.level,
                ),
            )
            is ScoreEditIntent.AddHairpin -> expression(
                baseRevision,
                ExpressionEditEngine.addHairpin(
                    manager.currentState.runtimeScore,
                    intent.staffTrackId,
                    intent.start,
                    intent.end,
                    intent.hairpinType,
                    intent.style,
                ),
            )
            is ScoreEditIntent.AddOctaveShift -> expression(
                baseRevision,
                ExpressionEditEngine.addOctaveShift(
                    manager.currentState.runtimeScore,
                    intent.staffTrackId,
                    intent.start,
                    intent.end,
                    intent.shiftType,
                ),
            )
            is ScoreEditIntent.AddTempoMark -> expression(
                baseRevision,
                TempoEditEngine.addMark(
                    manager.currentState.runtimeScore,
                    intent.onset,
                    intent.markType,
                    intent.bpm,
                    intent.beatUnit,
                ),
            )
            is ScoreEditIntent.AddGradualTempo -> expression(
                baseRevision,
                TempoEditEngine.addGradual(
                    manager.currentState.runtimeScore, intent.start, intent.end, intent.markType,
                ),
            )
            is ScoreEditIntent.AddFermata -> expression(
                baseRevision,
                ExpressionEditEngine.addFermata(
                    manager.currentState.runtimeScore, intent.afterTime, intent.shape,
                ),
            )
            is ScoreEditIntent.AddBreathMark -> expression(
                baseRevision,
                ExpressionEditEngine.addBreathMark(
                    manager.currentState.runtimeScore,
                    intent.staffTrackId,
                    intent.afterTime,
                    intent.scope,
                    intent.shape,
                    voiceNumber = intent.voiceNumber,
                ),
            )
            is ScoreEditIntent.DeleteExpressions -> deleteExpressions(intent, baseRevision)
            is ScoreEditIntent.MoveAttachment -> expression(
                baseRevision,
                ExpressionEditEngine.moveAttachment(
                    manager.currentState.runtimeScore,
                    intent.attachmentId,
                    intent.start,
                    intent.end,
                    intent.geometry,
                ) ?: TempoEditEngine.move(
                    manager.currentState.runtimeScore,
                    intent.attachmentId,
                    intent.start,
                    intent.end,
                ),
            )
            is ScoreEditIntent.SetLayoutBreak -> setLayoutBreak(intent, baseRevision)
            is ScoreEditIntent.SetStaffVisibility -> setStaffVisibility(intent, baseRevision)
            is ScoreEditIntent.DeleteNotes -> delete(intent, baseRevision)
            is ScoreEditIntent.TransposeNotes -> transpose(intent, baseRevision)
            is ScoreEditIntent.SetDurations -> propertyEdit(
                baseRevision,
                NoteEditEngine.editDurations(
                    manager.currentState.runtimeScore,
                    intent.targets.map { NoteEditEngine.DurationEdit(it.voiceTrackId, it.eventId, it.duration) },
                ),
            )
            is ScoreEditIntent.SetAccidentals -> propertyEdit(
                baseRevision,
                NoteEditEngine.editAccidentals(
                    manager.currentState.runtimeScore,
                    intent.targets.map {
                        NoteEditEngine.AccidentalEdit(it.voiceTrackId, it.eventId, it.accidental, it.pitchIndices)
                    },
                ),
            )
            is ScoreEditIntent.SetTies -> propertyEdit(
                baseRevision,
                NoteEditEngine.editTies(
                    manager.currentState.runtimeScore,
                    intent.targets.map {
                        NoteEditEngine.TieEdit(it.voiceTrackId, it.eventId, it.tieOut, it.pitchIndices)
                    },
                ),
            )
            is ScoreEditIntent.SetBeaming -> propertyEdit(
                baseRevision,
                NoteEditEngine.editBeaming(
                    manager.currentState.runtimeScore,
                    intent.targets.map { NoteEditEngine.BeamingEdit(it.voiceTrackId, it.eventId, it.beaming) },
                ),
            )
            is ScoreEditIntent.SetGraceGroups -> propertyEdit(
                baseRevision,
                NoteEditEngine.editGraceGroups(
                    manager.currentState.runtimeScore,
                    intent.targets.map {
                        NoteEditEngine.GraceGroupEdit(
                            it.voiceTrackId,
                            it.eventId,
                            it.totalDuration,
                            it.stealFrom,
                        )
                    },
                ),
            )
            is ScoreEditIntent.ToggleArticulation -> expression(
                baseRevision,
                ExpressionEditEngine.toggleArticulation(
                    manager.currentState.runtimeScore,
                    intent.targets.map { ExpressionEditEngine.NoteTarget(it.voiceTrackId, it.eventId) },
                    intent.articulation,
                ),
            )
            is ScoreEditIntent.ApplyTuplets -> propertyEdit(
                baseRevision,
                NoteEditEngine.applyTuplets(
                    manager.currentState.runtimeScore,
                    intent.targets.mapNotNull {
                        it.count?.let { count -> NoteEditEngine.TupletEdit(it.voiceTrackId, it.eventIds, count) }
                    },
                ),
            )
            is ScoreEditIntent.CreateSmallNoteRegions -> createSmallNoteRegions(intent, baseRevision)
            is ScoreEditIntent.SetArpeggio -> expression(
                baseRevision,
                ExpressionEditEngine.setArpeggio(
                    manager.currentState.runtimeScore,
                    intent.targets.map { ExpressionEditEngine.NoteTarget(it.voiceTrackId, it.eventId) },
                    intent.arpeggioType,
                ),
            )
            is ScoreEditIntent.MoveRests -> propertyEdit(
                baseRevision,
                NoteEditEngine.moveRest(
                    manager.currentState.runtimeScore,
                    intent.targets.map {
                        NoteEditEngine.RestMoveTarget(it.voiceTrackId, it.eventId, it.staffPosition)
                    },
                ),
            )
            is ScoreEditIntent.AddOrnament -> expression(
                baseRevision,
                ExpressionEditEngine.addOrnament(
                    manager.currentState.runtimeScore,
                    intent.staffTrackId,
                    intent.sourceEventId,
                    intent.ornamentKind,
                    intent.anchor,
                    intent.endOnset,
                ),
            )
            is ScoreEditIntent.UpdateOrnament -> expression(
                baseRevision,
                ExpressionEditEngine.updateOrnament(
                    manager.currentState.runtimeScore,
                    intent.ornamentId,
                    intent.upperAccidental,
                    intent.lowerAccidental,
                    intent.elementDuration,
                    intent.oscillations,
                    intent.trillPlaybackMode,
                    intent.updateUpperAccidental,
                    intent.updateLowerAccidental,
                ),
            )
            is ScoreEditIntent.UpdateTempo -> expression(
                baseRevision,
                TempoEditEngine.update(
                    manager.currentState.runtimeScore,
                    intent.tempoId,
                    intent.effectiveBpm,
                    intent.displayStyle,
                    intent.transition,
                    intent.text,
                ),
            )
            is ScoreEditIntent.UpdatePerformanceMark -> expression(
                baseRevision,
                ExpressionEditEngine.updatePerformanceMark(
                    manager.currentState.runtimeScore,
                    intent.markId,
                    intent.amount,
                ),
            )
            is ScoreEditIntent.Undo -> history(baseRevision, undo = true)
                is ScoreEditIntent.Redo -> history(baseRevision, undo = false)
            }
        } catch (rejected: ScoreCommitRejected) {
            result(
                baseRevision,
                ScoreEditEffect(ScoreEditEffectKind.CONFLICT, rejected.messageKey),
            )
        }
    }

    private fun insert(intent: ScoreEditIntent.InsertNote, baseRevision: Long): ScoreEditDispatchResult {
        val pitch = intent.pitch
            ?: intent.midiNote?.let { Pitch.fromMidi(it.coerceIn(0, 127), intent.preferSharps) }
        val result = NoteEditEngine.insert(
            manager.currentState.runtimeScore,
            NoteEditEngine.Insertion(
                voiceTrackId = intent.voiceTrackId,
                start = intent.start,
                duration = intent.duration,
                pitch = pitch,
                isRest = intent.isRest,
                trailingTie = intent.trailingTie,
                staffTrackId = intent.staffTrackId,
                voiceNumber = intent.voiceNumber,
                tupletCount = intent.tupletCount,
                beaming = intent.beaming,
                articulations = intent.articulations,
                grace = intent.grace?.let {
                    NoteEditEngine.GraceInsertion(it.totalDuration, it.stealFrom, it.noteType)
                },
                smallNoteAppendStartEventId = intent.smallNoteAppendStartEventId,
            ),
        ) ?: return noOp(baseRevision)
        commit(result.score, listOf(result.editInterval))
        selection = result.insertedEventId?.let { listOf(selectionTarget(it, intent.voiceTrackId)) }.orEmpty()
        advanceInputPositionTo(result.insertedEventId)
        return applied(baseRevision, noteInputTransition(intent.duration, intent.tupletCount))
    }

    private fun insertChord(
        intent: ScoreEditIntent.InsertChord,
        baseRevision: Long,
    ): ScoreEditDispatchResult {
        if (intent.pitches.isEmpty()) return noOp(baseRevision)
        val result = NoteEditEngine.insertChord(
            manager.currentState.runtimeScore,
            NoteEditEngine.ChordInsertion(
                voiceTrackId = intent.voiceTrackId,
                start = intent.start,
                duration = intent.duration,
                pitches = intent.pitches,
                trailingTie = intent.trailingTie,
                staffTrackId = intent.staffTrackId,
                voiceNumber = intent.voiceNumber,
                tupletCount = intent.tupletCount,
                beaming = intent.beaming,
                articulations = intent.articulations,
                grace = intent.grace?.let {
                    NoteEditEngine.GraceInsertion(it.totalDuration, it.stealFrom, it.noteType)
                },
            ),
        ) ?: return noOp(baseRevision)
        commit(result.score, listOf(result.editInterval))
        selection = result.insertedEventId?.let { listOf(selectionTarget(it, intent.voiceTrackId)) }.orEmpty()
        advanceInputPositionTo(result.insertedEventId)
        return applied(baseRevision, noteInputTransition(intent.duration, intent.tupletCount))
    }

    private fun createTupletRegion(
        intent: ScoreEditIntent.CreateTupletRegion,
        baseRevision: Long,
    ): ScoreEditDispatchResult {
        val result = NoteEditEngine.insert(
            manager.currentState.runtimeScore,
            NoteEditEngine.Insertion(
                voiceTrackId = intent.voiceTrackId,
                start = intent.start,
                duration = intent.totalDuration,
                pitch = null,
                isRest = true,
                staffTrackId = intent.staffTrackId,
                voiceNumber = intent.voiceNumber,
                tupletCount = intent.count,
            ),
        ) ?: return noOp(baseRevision)
        commit(result.score, listOf(result.editInterval))
        selection = result.insertedEventId
            ?.let { listOf(selectionTarget(it, intent.voiceTrackId)) }
            .orEmpty()
        // The group is empty: entry starts at its first member, not after that placeholder rest.
        nextInputPosition = intent.start
        return applied(baseRevision, noteInputTransition(intent.totalDuration, intent.count))
    }

    private fun copy(intent: ScoreEditIntent.CopyNotes, baseRevision: Long): ScoreEditDispatchResult {
        val copied = NoteEditEngine.copyNotes(
            manager.currentState.runtimeScore,
            intent.targets.map {
                NoteEditEngine.CopyTarget(it.voiceTrackId, it.eventId, it.pitchIndices, it.beaming)
            },
        )?.takeUnless { it.isEmpty } ?: return noOp(baseRevision)
        noteClipboard = copied
        revision++
        return result(baseRevision, ScoreEditEffect(ScoreEditEffectKind.COPIED))
    }

    private fun cut(intent: ScoreEditIntent.CutNotes, baseRevision: Long): ScoreEditDispatchResult {
        val copied = NoteEditEngine.copyNotes(
            manager.currentState.runtimeScore,
            intent.targets.map {
                NoteEditEngine.CopyTarget(it.voiceTrackId, it.eventId, it.pitchIndices, it.beaming)
            },
        )?.takeUnless { it.isEmpty } ?: return noOp(baseRevision)
        val copiedIds = copied.events.mapNotNullTo(HashSet()) { it.sourceEventId }
        var score = manager.currentState.runtimeScore
        val intervals = mutableListOf<TimeRange>()
        val replacementIds = mutableListOf<EventId>()
        intent.targets.filter { it.eventId in copiedIds }.forEach { target ->
            val result = NoteEditEngine.delete(
                score,
                NoteEditEngine.Deletion(target.voiceTrackId, target.eventId, target.pitchIndices),
            ) ?: return@forEach
            score = result.score
            intervals += result.editInterval
            result.insertedEventId?.let(replacementIds::add)
        }
        noteClipboard = copied
        if (intervals.isEmpty()) {
            revision++
            return result(baseRevision, ScoreEditEffect(ScoreEditEffectKind.COPIED))
        }
        commit(score, intervals)
        selection = replacementIds.map { selectionTarget(it) }
        return result(baseRevision, ScoreEditEffect(ScoreEditEffectKind.CUT))
    }

    private fun paste(intent: ScoreEditIntent.PasteNotes, baseRevision: Long): ScoreEditDispatchResult {
        val clipboard = noteClipboard?.takeUnless { it.isEmpty } ?: return noOp(baseRevision)
        return when (val outcome = NoteEditEngine.pasteNotesWithStatus(
            manager.currentState.runtimeScore,
            clipboard,
            NoteEditEngine.PasteTarget(intent.voiceTrackId, intent.start, intent.clearMeasure),
        )) {
            is NoteEditEngine.PasteOutcome.Changed -> {
                commit(outcome.result.score, outcome.result.intervals)
                selection = outcome.result.pastedEventIds.map { selectionTarget(it, intent.voiceTrackId) }
                advanceInputPositionTo(outcome.result.pastedEventIds.lastOrNull())
                result(baseRevision, ScoreEditEffect(ScoreEditEffectKind.PASTED))
            }
            NoteEditEngine.PasteOutcome.TupletCrossesBarline -> result(
                baseRevision,
                ScoreEditEffect(ScoreEditEffectKind.CONFLICT, "scoreEditing.pasteTupletCrossesBarline"),
            )
            NoteEditEngine.PasteOutcome.NoOp -> noOp(baseRevision)
        }
    }

    private fun moveVoices(intent: ScoreEditIntent.MoveVoices, baseRevision: Long): ScoreEditDispatchResult {
        val moved = NoteEditEngine.moveVoices(
            manager.currentState.runtimeScore,
            intent.targets.map {
                NoteEditEngine.VoiceMoveTarget(
                    voiceTrackId = it.voiceTrackId,
                    eventId = it.eventId,
                    targetVoiceNumber = it.targetVoiceNumber,
                    pitchIndices = it.pitchIndices,
                    targetStaffId = it.targetStaffId,
                )
            },
        ) ?: return noOp(baseRevision)
        commit(moved.score, moved.intervals)
        selection = moved.movedEvents.map { event ->
            selectionTarget(event.eventId).copy(pitchIndices = event.pitchIndices)
        }
        return applied(baseRevision)
    }

    private fun setClef(intent: ScoreEditIntent.SetClef, baseRevision: Long): ScoreEditDispatchResult {
        val result = ClefEditEngine.setClef(
            manager.currentState.runtimeScore,
            ClefEditEngine.Target(intent.staffTrackId, intent.onset, intent.resolveExisting),
            intent.clef,
        ) ?: return noOp(baseRevision)
        commitStructural(result.score, result.editInterval.start.measure..result.editInterval.end.measure)
        selection = manager.currentState.computedScore.clefs.firstOrNull {
            it.staffTrackId == result.editedStaffTrackId && it.time == result.editedOnset
        }?.let { listOf(ScoreSelectionTarget.Clef(staffTrackId = it.staffTrackId, onset = it.time)) }.orEmpty()
        return applied(baseRevision)
    }

    private fun setKeySignature(
        intent: ScoreEditIntent.SetKeySignature,
        baseRevision: Long,
    ): ScoreEditDispatchResult {
        val result = KeySignatureEditEngine.setKeySignature(
            manager.currentState.runtimeScore,
            KeySignatureEditEngine.Target(intent.onset),
            intent.keySignature,
        ) ?: return noOp(baseRevision)
        commitStructural(result.score, result.editedMeasure..result.editedMeasure)
        selection = manager.currentState.computedScore.keySignatures.firstOrNull {
            it.keySignature == intent.keySignature &&
                (it.time == result.editedOnset || it.time.measure in (result.editedMeasure - 1)..result.editedMeasure)
        }?.let { listOf(ScoreSelectionTarget.KeySignature(staffTrackId = it.staffTrackId, onset = it.time)) }.orEmpty()
        return applied(baseRevision)
    }

    private fun setTimeSignature(
        intent: ScoreEditIntent.SetTimeSignature,
        baseRevision: Long,
    ): ScoreEditDispatchResult {
        val result = TimeSignatureEditEngine.setTimeSignature(
            manager.currentState.runtimeScore,
            intent.measureNumber,
            intent.timeSignature,
        ) ?: return noOp(baseRevision)
        val maxMeasure = result.score.measures.maxOfOrNull { it.value.number } ?: result.editedMeasure
        commitStructural(result.score, result.editedMeasure..maxMeasure)
        selection = manager.currentState.computedScore.timeSignatures.firstOrNull {
            it.time.measure == result.editedMeasure ||
                (result.editedMeasure == 1 && it.time == com.mecon.api.primitive.TimeCode.ZERO)
        }?.let { listOf(ScoreSelectionTarget.TimeSignature(staffTrackId = it.staffTrackId, onset = it.time)) }.orEmpty()
        return applied(baseRevision)
    }

    private fun insertMeasures(
        intent: ScoreEditIntent.InsertMeasures,
        baseRevision: Long,
    ): ScoreEditDispatchResult {
        val result = MeasureEditEngine.insertAfter(
            manager.currentState.runtimeScore,
            intent.afterMeasure,
            intent.count,
            MeasureEditEngine.BoundaryInsertion.valueOf(intent.boundaryInsertion.name),
        ) ?: return noOp(baseRevision)
        val maxMeasure = result.measures.maxOfOrNull { it.value.number } ?: intent.afterMeasure
        commitStructural(result, (intent.afterMeasure + 1)..maxMeasure)
        selection = emptyList()
        return applied(baseRevision)
    }

    private fun deleteMeasures(
        intent: ScoreEditIntent.DeleteMeasures,
        baseRevision: Long,
    ): ScoreEditDispatchResult {
        val result = MeasureEditEngine.delete(
            manager.currentState.runtimeScore,
            intent.measureNumbers,
        ) ?: return noOp(baseRevision)
        val first = intent.measureNumbers.minOrNull() ?: return noOp(baseRevision)
        val maxMeasure = result.measures.maxOfOrNull { it.value.number } ?: first
        commitStructural(result, first.coerceAtMost(maxMeasure)..maxMeasure)
        selection = emptyList()
        return applied(baseRevision)
    }

    private fun setBarline(
        intent: ScoreEditIntent.SetBarline,
        baseRevision: Long,
    ): ScoreEditDispatchResult {
        val result = BarlineEditEngine.set(
            manager.currentState.runtimeScore,
            intent.boundaryMeasure,
            intent.barlineType,
            intent.repeatCount,
        ) ?: return noOp(baseRevision)
        val affected = boundaryMeasures(result.score, intent.boundaryMeasure)
        commitStructural(result.score, affected)
        selection = barlineSelection(intent.boundaryMeasure)
        return applied(baseRevision)
    }

    private fun setBarlineRepeatCount(
        intent: ScoreEditIntent.SetBarlineRepeatCount,
        baseRevision: Long,
    ): ScoreEditDispatchResult {
        val result = BarlineEditEngine.setRepeatCount(
            manager.currentState.runtimeScore,
            intent.boundaryMeasure,
            intent.repeatCount,
        ) ?: return noOp(baseRevision)
        commitComputed(
            result.score,
            boundaryMeasures(result.score, intent.boundaryMeasure),
            notationChanged = false,
            structureReflow = false,
        )
        selection = barlineSelection(intent.boundaryMeasure)
        return applied(baseRevision)
    }

    private fun repeatStructure(
        baseRevision: Long,
        select: Boolean = true,
        edit: (RuntimeScore) -> RepeatStructureEditEngine.Result?,
    ): ScoreEditDispatchResult {
        val result = edit(manager.currentState.runtimeScore) ?: return noOp(baseRevision)
        commitStructural(result.score, result.affectedMeasures)
        selection = if (select) repeatStructureSelection(result.affectedMeasures) else emptyList()
        return applied(baseRevision)
    }

    private fun boundaryMeasures(score: RuntimeScore, boundaryMeasure: Int): IntRange {
        val maxMeasure = score.measures.maxOfOrNull { it.value.number } ?: 1
        val start = boundaryMeasure.coerceAtLeast(1).coerceAtMost(maxMeasure)
        val end = (boundaryMeasure + 1).coerceAtLeast(start).coerceAtMost(maxMeasure)
        return start..end
    }

    private fun barlineSelection(boundaryMeasure: Int): List<ScoreSelectionTarget> =
        manager.currentState.computedScore.barlines.firstOrNull { it.measureNumber == boundaryMeasure }
            ?.let {
                listOf(
                    ScoreSelectionTarget.Barline(
                        boundaryMeasure = it.measureNumber,
                        onset = it.time,
                    ),
                )
            }.orEmpty()

    private fun repeatStructureSelection(affectedMeasures: IntRange): List<ScoreSelectionTarget> {
        val computed = manager.currentState.computedScore
        computed.navigationMarks.lastOrNull { it.boundaryMeasure in affectedMeasures }?.let {
            return listOf(
                ScoreSelectionTarget.NavigationMark(
                    boundaryMeasure = it.boundaryMeasure,
                    mark = it.mark,
                    onset = it.time,
                ),
            )
        }
        computed.voltaEndings.lastOrNull {
            it.startMeasure in affectedMeasures || it.endMeasure in affectedMeasures
        }?.let {
            return listOf(
                ScoreSelectionTarget.VoltaEnding(
                    startMeasure = it.startMeasure,
                    endMeasure = it.endMeasure,
                    numbers = it.numbers,
                ),
            )
        }
        return barlineSelection(affectedMeasures.first - 1)
    }

    private fun addSlurs(
        intent: ScoreEditIntent.AddSlurs,
        baseRevision: Long,
    ): ScoreEditDispatchResult {
        val result = NoteEditEngine.addSlurs(
            manager.currentState.runtimeScore,
            intent.targets.map { NoteEditEngine.SlurTarget(it.voiceTrackId, it.startEventId, it.endEventId) },
        ) ?: return noOp(baseRevision)
        commitComputed(result.score, result.affectedMeasures, notationChanged = false, structureReflow = false)
        selection = slurSelection(result.slurIds)
        return applied(baseRevision)
    }

    private fun deleteSlurs(
        intent: ScoreEditIntent.DeleteSlurs,
        baseRevision: Long,
    ): ScoreEditDispatchResult {
        val result = NoteEditEngine.deleteSlurs(
            manager.currentState.runtimeScore,
            intent.slurIds,
        ) ?: return noOp(baseRevision)
        commitComputed(result.score, result.affectedMeasures, notationChanged = false, structureReflow = false)
        selection = emptyList()
        return applied(baseRevision)
    }

    private fun setSlurGeometry(
        intent: ScoreEditIntent.SetSlurGeometry,
        baseRevision: Long,
    ): ScoreEditDispatchResult {
        val current = manager.currentState
        val slur = current.computedScore.slurs.firstOrNull { it.slurId == intent.slurId }
            ?: return noOp(baseRevision)
        val replacement = intent.geometry.copy(
            directionLocked = true,
            manuallyAdjusted = !intent.geometry.directionOnly,
            minApex = intent.geometry.minApex.coerceIn(SlurGeometry.MIN_APEX, SlurGeometry.MAX_APEX),
            maxApex = intent.geometry.maxApex.coerceIn(SlurGeometry.MIN_APEX, SlurGeometry.MAX_APEX),
        )
        val base = current.runtimeScore.geometry ?: ScoreGeometry()
        if (base.slurs[intent.slurId] == replacement) return noOp(baseRevision)
        val score = current.runtimeScore.copy(
            geometry = base.copy(slurs = base.slurs + (intent.slurId to replacement)),
        )
        val startMeasure = current.computedScore.getComputedEvent(slur.startEventId)?.onset?.measure ?: 1
        val endMeasure = current.computedScore.getComputedEvent(slur.endEventId)?.onset?.measure ?: startMeasure
        val affected = minOf(startMeasure, endMeasure)..maxOf(startMeasure, endMeasure)
        commitNewState(
            score,
            current.computedScore.copy(runtime = score),
            RenderHint(current.computedScore, ComputeChangeSet.forRange(affected)),
        )
        observedState = manager.currentState
        revision++
        selection = slurSelection(setOf(intent.slurId))
        return applied(baseRevision)
    }

    private fun setTieGeometry(
        intent: ScoreEditIntent.SetTieGeometry,
        baseRevision: Long,
    ): ScoreEditDispatchResult {
        val current = manager.currentState
        val source = current.computedScore.getComputedEvent(intent.sourceEventId)
            ?: return noOp(baseRevision)
        val replacement = intent.geometry.copy(
            directionLocked = true,
            manuallyAdjusted = !intent.geometry.directionOnly,
        )
        val base = current.runtimeScore.geometry ?: ScoreGeometry()
        val existing = base.ties[intent.sourceEventId].orEmpty()
        val updated = if (existing.any { it.sourcePitchIndex == replacement.sourcePitchIndex }) {
            existing.map { if (it.sourcePitchIndex == replacement.sourcePitchIndex) replacement else it }
        } else {
            existing + replacement
        }
        if (existing == updated) return noOp(baseRevision)
        val score = current.runtimeScore.copy(
            geometry = base.copy(ties = base.ties + (intent.sourceEventId to updated)),
        )
        val affected = source.onset.measure..(source.onset.measure + 1)
        commitNewState(
            score,
            current.computedScore.copy(runtime = score),
            RenderHint(current.computedScore, ComputeChangeSet.forRange(affected)),
        )
        observedState = manager.currentState
        revision++
        selection = listOf(
            ScoreSelectionTarget.Tie(
                sourceEventId = intent.sourceEventId,
                sourcePitchIndex = replacement.sourcePitchIndex,
                voiceTrackId = voiceTrackIdOf(intent.sourceEventId),
            ),
        )
        return applied(baseRevision)
    }

    private fun setTupletGeometry(
        intent: ScoreEditIntent.SetTupletGeometry,
        baseRevision: Long,
    ): ScoreEditDispatchResult {
        val current = manager.currentState
        val start = current.computedScore.getComputedEvent(intent.startEventId)
            ?.takeIf { it.tupletInfo != null }
            ?: return noOp(baseRevision)
        val info = start.tupletInfo ?: return noOp(baseRevision)
        val replacement = intent.geometry.copy(directionLocked = true)
        val base = current.runtimeScore.geometry ?: ScoreGeometry()
        if (base.tuplets[intent.startEventId] == replacement) return noOp(baseRevision)
        val score = current.runtimeScore.copy(
            geometry = base.copy(tuplets = base.tuplets + (intent.startEventId to replacement)),
        )
        val endMeasure = current.computedScore.getComputedEvent(info.endEventId)?.onset?.measure
            ?: start.onset.measure
        val affected = minOf(start.onset.measure, endMeasure)..maxOf(start.onset.measure, endMeasure)
        commitNewState(
            score,
            current.computedScore.copy(runtime = score),
            RenderHint(current.computedScore, ComputeChangeSet.forRange(affected)),
        )
        observedState = manager.currentState
        revision++
        return applied(baseRevision)
    }

    private fun setBeamGeometry(
        intent: ScoreEditIntent.SetBeamGeometry,
        baseRevision: Long,
    ): ScoreEditDispatchResult {
        val current = manager.currentState
        val group = current.computedScore.getBeamGroups().entries
            .firstOrNull { it.key.value == intent.groupId }
            ?.value
            ?: return noOp(baseRevision)
        val replacement = intent.geometry.copy(manuallyAdjusted = true)
        val base = current.runtimeScore.geometry ?: ScoreGeometry()
        if (base.beams[intent.groupId] == replacement) return noOp(baseRevision)
        val score = current.runtimeScore.copy(
            geometry = base.copy(beams = base.beams + (intent.groupId to replacement)),
        )
        val measures = group.map { it.measurePosition.measure }
        commitNewState(
            score,
            current.computedScore.copy(runtime = score),
            RenderHint(current.computedScore, ComputeChangeSet.forRange(measures.min()..measures.max())),
        )
        observedState = manager.currentState
        revision++
        selection = listOf(
            ScoreSelectionTarget.Beam(groupId = intent.groupId),
        )
        return applied(baseRevision)
    }

    private fun setArticulationGeometry(
        intent: ScoreEditIntent.SetArticulationGeometry,
        baseRevision: Long,
    ): ScoreEditDispatchResult {
        val current = manager.currentState
        val source = current.computedScore.getComputedEvent(intent.eventId) ?: return noOp(baseRevision)
        val base = current.runtimeScore.geometry ?: ScoreGeometry()
        if (base.articulations[intent.eventId] == intent.geometry) return noOp(baseRevision)
        val score = current.runtimeScore.copy(
            geometry = base.copy(articulations = base.articulations + (intent.eventId to intent.geometry)),
        )
        commitNewState(
            score,
            current.computedScore.copy(runtime = score),
            RenderHint(current.computedScore, ComputeChangeSet.forRange(source.onset.measure..source.onset.measure)),
        )
        observedState = manager.currentState
        revision++
        selection = listOf(
            ScoreSelectionTarget.Articulation(
                eventId = intent.eventId,
                articulationIndex = intent.selectedIndex,
                voiceTrackId = voiceTrackIdOf(intent.eventId),
            ),
        )
        return applied(baseRevision)
    }

    private fun slurSelection(slurIds: Set<EventId>): List<ScoreSelectionTarget> =
        manager.currentState.computedScore.slurs.filter { it.slurId in slurIds }.map {
            ScoreSelectionTarget.Slur(
                slurId = it.slurId,
                voiceTrackId = it.voiceTrackId,
                startEventId = it.startEventId,
                endEventId = it.endEventId,
            )
        }

    private fun expression(
        baseRevision: Long,
        outcome: ExpressionEditEngine.Result?,
    ): ScoreEditDispatchResult {
        val result = outcome ?: return noOp(baseRevision)
        commitComputed(result.score, result.affectedMeasures, notationChanged = false, structureReflow = false)
        selection = result.selectedAttachmentIds.map {
            ScoreSelectionTarget.Attachment(attachmentId = it)
        } + result.selectedEventIds.map { selectionTarget(it) }
        return applied(baseRevision)
    }

    private fun deleteExpressions(
        intent: ScoreEditIntent.DeleteExpressions,
        baseRevision: Long,
    ): ScoreEditDispatchResult {
        if (intent.ids.isEmpty()) return noOp(baseRevision)
        var score = manager.currentState.runtimeScore
        var affected: IntRange? = null
        fun absorb(result: ExpressionEditEngine.Result?) {
            if (result == null) return
            score = result.score
            affected = affected?.let {
                minOf(it.first, result.affectedMeasures.first)..maxOf(it.last, result.affectedMeasures.last)
            } ?: result.affectedMeasures
        }
        absorb(TempoEditEngine.delete(score, intent.ids))
        absorb(ExpressionEditEngine.deleteAttachments(score, intent.ids))
        absorb(ExpressionEditEngine.deleteGlobalPerformanceMarks(score, intent.ids))
        val range = affected ?: return noOp(baseRevision)
        commitComputed(score, range, notationChanged = false, structureReflow = false)
        selection = emptyList()
        return applied(baseRevision)
    }

    private fun setLayoutBreak(
        intent: ScoreEditIntent.SetLayoutBreak,
        baseRevision: Long,
    ): ScoreEditDispatchResult {
        val score = LayoutBreakEditEngine.set(
            manager.currentState.runtimeScore,
            intent.beforeMeasure,
            intent.kind,
        ) ?: return noOp(baseRevision)
        val maxMeasure = score.measures.maxOfOrNull { it.value.number } ?: intent.beforeMeasure
        commitStructural(score, (intent.beforeMeasure - 1).coerceAtLeast(1)..maxMeasure)
        selection = listOf(
            ScoreSelectionTarget.LayoutBreak(beforeMeasure = intent.beforeMeasure),
        )
        return applied(baseRevision)
    }

    private fun setStaffVisibility(
        intent: ScoreEditIntent.SetStaffVisibility,
        baseRevision: Long,
    ): ScoreEditDispatchResult {
        if (intent.startMeasure > intent.endMeasure || intent.staffTrackIds.isEmpty()) return noOp(baseRevision)
        val range = MeasureRange(intent.startMeasure, intent.endMeasure)
        val current = manager.currentState.runtimeScore
        if (intent.hidden && StaffVisibilityEditEngine.hasNotesInRegion(current, intent.staffTrackIds, range)) {
            return result(
                baseRevision,
                ScoreEditEffect(ScoreEditEffectKind.CONFLICT, "scoreEditing.staffVisibilityContainsNotes"),
            )
        }
        val score = if (intent.hidden) {
            StaffVisibilityEditEngine.hide(current, intent.staffTrackIds, range)
        } else {
            StaffVisibilityEditEngine.show(current, intent.staffTrackIds, range)
        } ?: return noOp(baseRevision)
        commitStructural(score, intent.startMeasure..intent.endMeasure)
        selection = intent.staffTrackIds.map {
            ScoreSelectionTarget.StaffVisibility(
                staffTrackId = it,
                startMeasure = intent.startMeasure,
                endMeasure = intent.endMeasure,
            )
        }
        return applied(baseRevision)
    }

    private fun delete(intent: ScoreEditIntent.DeleteNotes, baseRevision: Long): ScoreEditDispatchResult {
        if (intent.targets.isEmpty()) return noOp(baseRevision)
        var score = manager.currentState.runtimeScore
        val intervals = mutableListOf<TimeRange>()
        val resultIds = mutableListOf<EventId>()
        intent.targets.forEach { target ->
            val result = NoteEditEngine.delete(
                score,
                NoteEditEngine.Deletion(target.voiceTrackId, target.eventId, target.pitchIndices),
            ) ?: return@forEach
            score = result.score
            intervals += result.editInterval
            result.insertedEventId?.let(resultIds::add)
        }
        if (intervals.isEmpty()) return noOp(baseRevision)
        commit(score, intervals)
        selection = resultIds.map { selectionTarget(it) }
        return applied(baseRevision)
    }

    private fun transpose(intent: ScoreEditIntent.TransposeNotes, baseRevision: Long): ScoreEditDispatchResult {
        val result = NoteEditEngine.transpose(
            manager.currentState.runtimeScore,
            intent.targets.map { NoteEditEngine.TransposeTarget(it.voiceTrackId, it.eventId, it.pitchIndices) },
            intent.stepDelta,
        ) ?: return noOp(baseRevision)
        commit(result.score, result.intervals)
        selection = result.movedEvents.map { moved ->
            selectionTarget(moved.eventId).copy(pitchIndices = moved.pitchIndices)
        }
        return applied(baseRevision)
    }

    private fun propertyEdit(
        baseRevision: Long,
        outcome: NoteEditEngine.EditOutcome,
    ): ScoreEditDispatchResult = when (outcome) {
        NoteEditEngine.EditOutcome.Conflict -> result(
            baseRevision,
            ScoreEditEffect(ScoreEditEffectKind.CONFLICT, "scoreEditing.editConflict"),
        )
        NoteEditEngine.EditOutcome.NoOp -> noOp(baseRevision)
        is NoteEditEngine.EditOutcome.Changed -> {
            commit(outcome.score, outcome.intervals)
            selection = outcome.resultEventIds.map { selectionTarget(it) }
            applied(baseRevision)
        }
    }

    private fun createSmallNoteRegions(
        intent: ScoreEditIntent.CreateSmallNoteRegions,
        baseRevision: Long,
    ): ScoreEditDispatchResult {
        val outcome = NoteEditEngine.createSmallNoteRegions(
            manager.currentState.runtimeScore,
            intent.targets.map { NoteEditEngine.SmallNoteEdit(it.voiceTrackId, it.eventIds) },
        )
        return when (outcome) {
            NoteEditEngine.EditOutcome.Conflict -> result(
                baseRevision,
                ScoreEditEffect(ScoreEditEffectKind.CONFLICT, "scoreEditing.editConflict"),
            )
            NoteEditEngine.EditOutcome.NoOp -> noOp(baseRevision)
            is NoteEditEngine.EditOutcome.Changed -> {
                commit(outcome.score, outcome.intervals)
                val resultIds = outcome.resultEventIds.toSet()
                val anchors = manager.currentState.runtimeScore.voiceTracks.values
                    .flatMap { voice -> voice.events.toList() }
                    .filter { event -> event.id in resultIds && event.tupletSpan?.smallNotes == true }
                    .sortedBy { it.onset }
                selection = anchors.map { selectionTarget(it.id) }
                val anchor = anchors.firstOrNull()
                if (anchor != null) nextInputPosition = anchor.onset
                applied(
                    baseRevision,
                    anchor?.let {
                        ScoreNoteInputTransition(
                            duration = Duration(it.tupletSpan!!.beatUnit),
                            smallNoteAppendStartEventId = it.id,
                        )
                    },
                )
            }
        }
    }

    private fun history(baseRevision: Long, undo: Boolean): ScoreEditDispatchResult {
        val possible = if (undo) manager.canUndo() else manager.canRedo()
        if (!possible) return noOp(baseRevision)
        if (undo) manager.undo() else manager.redo()
        observedState = manager.currentState
        revision++
        return result(
            baseRevision,
            ScoreEditEffect(if (undo) ScoreEditEffectKind.UNDONE else ScoreEditEffectKind.REDONE),
        )
    }

    private fun commitNewState(
        score: RuntimeScore,
        computed: ComputedScore,
        renderHint: RenderHint? = null,
    ) {
        val previous = manager.currentState.runtimeScore
        commitPolicy?.reject?.invoke(previous, score)?.let { throw ScoreCommitRejected(it) }
        manager.commitNewState(
            runtimeScore = score,
            computedScore = computed,
            renderHint = renderHint,
            updateCompanionState = commitPolicy?.let { policy ->
                { policy.updateCompanionState(previous, score) }
            },
        )
    }

    private fun commit(score: RuntimeScore, intervals: List<TimeRange>) {
        val previous = manager.currentState.computedScore
        if (intervals.size == 1) {
            val incremental = computeScoreIncremental(previous, score, intervals.single())
            commitNewState(
                score,
                incremental.computed,
                RenderHint(previous, incremental.changeSet),
            )
        } else {
            commitNewState(score, computeScore(score))
        }
        observedState = manager.currentState
        revision++
    }

    private fun commitStructural(score: RuntimeScore, affectedMeasures: IntRange) {
        commitComputed(score, affectedMeasures, notationChanged = true, structureReflow = true)
    }

    private fun commitComputed(
        score: RuntimeScore,
        affectedMeasures: IntRange,
        notationChanged: Boolean,
        structureReflow: Boolean,
    ) {
        val previous = manager.currentState.computedScore
        val computed = computeScore(score)
        commitNewState(
            score,
            computed,
            RenderHint(
                previous,
                ComputeChangeSet(
                    addedEvents = emptySet(),
                    removedEvents = emptySet(),
                    modifiedEvents = emptySet(),
                    affectedMeasures = affectedMeasures,
                    notationChanged = notationChanged,
                    structureReflow = structureReflow,
                ),
            ),
        )
        observedState = manager.currentState
        revision++
    }

    /**
     * Desktop migration is incremental: unsupported commands can still commit through the existing
     * host. Detect those manager transitions before accepting the next shared intent so revision
     * checks remain monotonic and stale async commands are rejected.
     */
    private fun synchronizeExternalState() {
        val current = manager.currentState
        if (current !== observedState) {
            observedState = current
            revision++
        }
    }

    private fun selectionTarget(
        eventId: EventId,
        fallbackVoiceId: TrackId? = null,
    ): ScoreSelectionTarget.Event = ScoreSelectionTarget.Event(
        eventId = eventId,
        voiceTrackId = voiceTrackIdOf(eventId) ?: fallbackVoiceId,
    )

    private fun voiceTrackIdOf(eventId: EventId): TrackId? =
        manager.currentState.runtimeScore.voiceTracks.entries
            .firstOrNull { (_, voice) -> voice.events.toList().any { it.id == eventId } }
            ?.key

    private fun applied(
        baseRevision: Long,
        noteInputTransition: ScoreNoteInputTransition? = null,
    ): ScoreEditDispatchResult =
        result(baseRevision, ScoreEditEffect(ScoreEditEffectKind.APPLIED), noteInputTransition)

    private fun noOp(baseRevision: Long): ScoreEditDispatchResult =
        result(baseRevision, ScoreEditEffect(ScoreEditEffectKind.NO_OP))

    private fun result(
        baseRevision: Long?,
        effect: ScoreEditEffect,
        noteInputTransition: ScoreNoteInputTransition? = null,
    ): ScoreEditDispatchResult =
        ScoreEditDispatchResult(frame(), baseRevision, effect, noteInputTransition)

    private fun noteInputTransition(duration: Duration, tupletCount: Int?):
        ScoreNoteInputTransition? = tupletCount
        ?.let { NoteEditEngine.tupletSpecFor(duration.toFraction(), it) }
        ?.let { ScoreNoteInputTransition(Duration(it.beatUnit)) }

    companion object {
        private const val SELECTION_STATE_KEY = "score-editing.selection"

        fun open(score: StorageScore): ScoreEditingSession {
            val runtime = RuntimeScore.fromStorage(score)
            return ScoreEditingSession(ScoreStateManager(runtime, computeScore(runtime)), null)
        }

        fun open(runtime: RuntimeScore, computed: ComputedScore = computeScore(runtime)): ScoreEditingSession =
            ScoreEditingSession(ScoreStateManager(runtime, computed), null)

        fun open(
            manager: ScoreStateManager,
            commitPolicy: ScoreEditingCommitPolicy? = null,
        ): ScoreEditingSession = ScoreEditingSession(manager, commitPolicy)
    }
}
