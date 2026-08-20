package com.mecon.mobile

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.Accidental
import com.mecon.api.primitive.BarlineType
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.ArpeggioType
import com.mecon.api.storage.Articulation
import com.mecon.api.storage.BeamingInfo
import com.mecon.api.storage.NavigationMark
import com.mecon.api.interaction.LayoutBreakKind
import com.mecon.api.storage.ScoreGeometry
import com.mecon.api.computed.ComputedHairpin
import com.mecon.api.computed.ComputedOctaveShift
import com.mecon.api.computed.ComputedOrnamentMark
import com.mecon.api.computed.ComputedTempoKeyframe
import com.mecon.api.storage.events.DynamicLevel
import com.mecon.api.storage.events.HairpinStyle
import com.mecon.api.storage.events.HairpinType
import com.mecon.api.storage.events.GraceTimeSource
import com.mecon.api.storage.events.OctaveShiftType
import com.mecon.api.storage.events.OrnamentKind
import com.mecon.api.storage.events.OrnamentAnchor
import com.mecon.api.storage.events.TempoMarkType
import com.mecon.api.storage.tracks.BreathMarkScope
import com.mecon.api.storage.tracks.BreathMarkShape
import com.mecon.api.storage.tracks.Clef
import com.mecon.api.storage.tracks.FermataShape
import com.mecon.features.scoreediting.ScoreEditDispatchResult
import com.mecon.features.scoreediting.ScoreEditEffect
import com.mecon.features.scoreediting.ScoreEditingFrame
import com.mecon.features.scoreediting.ScoreEditIntent
import com.mecon.features.scoreediting.ScoreEditingSession
import com.mecon.features.scoreediting.ScoreEntryCursor
import com.mecon.features.scoreediting.ScoreEntryCursorAction
import com.mecon.features.scoreediting.ScoreInputCapabilities
import com.mecon.features.scoreediting.ScoreInteractionCatalog
import com.mecon.features.scoreediting.ScoreInteractionAnchor
import com.mecon.features.scoreediting.ScoreInteractionController
import com.mecon.features.scoreediting.ScoreInteractionState
import com.mecon.features.scoreediting.ScoreSelectionTarget
import com.mecon.features.scoreediting.ScoreToolGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MobileScoreActivity { RECORD, EDIT, ANALYZE, LISTEN }

data class MobileNoteInputState(
    val base: DurationBase = DurationBase.QUARTER,
    val dots: Int = 0,
    val restMode: Boolean = false,
) {
    val duration: Duration get() = Duration(base, dots)
}

/**
 * Android-first presentation state. It deliberately keeps referential equality: comparing two
 * frames must never recurse through a large immutable score on the UI thread.
 */
class MobileScoreEditorState(
    val activity: MobileScoreActivity = MobileScoreActivity.RECORD,
    val activeToolGroup: ScoreToolGroup = ScoreToolGroup.NOTES,
    val capabilities: ScoreInputCapabilities = ScoreInputCapabilities(),
    val interaction: ScoreInteractionState = ScoreInteractionState(),
    val noteInput: MobileNoteInputState = MobileNoteInputState(),
    val frame: ScoreEditingFrame,
    val lastEffect: ScoreEditEffect? = null,
) {
    fun copy(
        activity: MobileScoreActivity = this.activity,
        activeToolGroup: ScoreToolGroup = this.activeToolGroup,
        capabilities: ScoreInputCapabilities = this.capabilities,
        interaction: ScoreInteractionState = this.interaction,
        noteInput: MobileNoteInputState = this.noteInput,
        frame: ScoreEditingFrame = this.frame,
        lastEffect: ScoreEditEffect? = this.lastEffect,
    ): MobileScoreEditorState = MobileScoreEditorState(
        activity = activity,
        activeToolGroup = activeToolGroup,
        capabilities = capabilities,
        interaction = interaction,
        noteInput = noteInput,
        frame = frame,
        lastEffect = lastEffect,
    )
}

/**
 * Portable adapter used by the Android shell and later iOS/Harmony shells. It owns only workflow:
 * every persisted operation still crosses [ScoreEditingSession] as a normal [ScoreEditIntent].
 */
class MobileScoreEditorController private constructor(
    private val session: ScoreEditingSession,
    capabilities: ScoreInputCapabilities,
) {
    private val interaction = ScoreInteractionController()
    private val mutableState = MutableStateFlow(
        MobileScoreEditorState(capabilities = capabilities, frame = session.frame()),
    )
    val state: StateFlow<MobileScoreEditorState> = mutableState.asStateFlow()

    fun switchActivity(activity: MobileScoreActivity) {
        interaction.cancelRun()
        publish(activity = activity)
    }

    fun updateCapabilities(capabilities: ScoreInputCapabilities) {
        mutableState.value = mutableState.value.copy(capabilities = capabilities)
    }

    fun activate(commandId: String, cursor: ScoreEntryCursor? = interaction.state.value.entryCursor) {
        val spec = ScoreInteractionCatalog.spec(commandId)
        interaction.begin(commandId, cursor)
        publish(toolGroup = spec.toolGroup)
    }

    fun cancelRun() {
        interaction.cancelRun()
        publish()
    }

    fun selectDuration(base: DurationBase) {
        mutableState.value = mutableState.value.copy(
            noteInput = mutableState.value.noteInput.copy(base = base),
        )
    }

    fun toggleDot() {
        val current = mutableState.value.noteInput
        mutableState.value = mutableState.value.copy(
            noteInput = current.copy(dots = if (current.dots == 0) 1 else 0),
        )
    }

    fun setRestMode(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(
            noteInput = mutableState.value.noteInput.copy(restMode = enabled),
        )
    }

    /** Receives only semantic targets resolved by the platform hit-test adapter. */
    fun target(anchors: List<ScoreInteractionAnchor>, preview: Boolean = false) {
        interaction.target(anchors, preview)
        publish()
    }

    fun dispatch(intent: ScoreEditIntent): ScoreEditDispatchResult {
        val commandId = ScoreInteractionCatalog.commandId(intent)
        if (commandId != ScoreInteractionCatalog.NAVIGATION &&
            interaction.state.value.activeCommandId != commandId
        ) {
            activate(commandId)
        }
        if (interaction.state.value.activeCommandId != null) interaction.markCommitPending()
        val result = session.dispatch(intent)
        interaction.accept(intent, result)
        publish(frame = result.frame, effect = result.effect)
        return result
    }

    fun createTupletRegion(
        voiceTrackId: TrackId,
        start: TimeCode,
        totalDuration: Duration,
        count: Int,
        staffTrackId: TrackId? = null,
        voiceNumber: Int = 1,
    ): ScoreEditDispatchResult {
        activate(
            ScoreInteractionCatalog.ENTRY_TUPLET_REGION,
            ScoreEntryCursor(voiceTrackId, start),
        )
        return dispatch(
            ScoreEditIntent.CreateTupletRegion(
                expectedRevision = state.value.frame.revision,
                voiceTrackId = voiceTrackId,
                start = start,
                totalDuration = totalDuration,
                count = count,
                staffTrackId = staffTrackId,
                voiceNumber = voiceNumber,
            ),
        )
    }

    fun insertMidiNote(
        midiNote: Int,
        duration: Duration = state.value.noteInput.duration,
        preferSharps: Boolean = true,
    ): ScoreEditDispatchResult? {
        val cursor = interaction.state.value.entryCursor ?: return null
        return dispatch(
            ScoreEditIntent.InsertNote(
                expectedRevision = state.value.frame.revision,
                voiceTrackId = cursor.voiceTrackId,
                start = cursor.position,
                duration = duration,
                midiNote = midiNote,
                preferSharps = preferSharps,
                smallNoteAppendStartEventId = interaction.state.value.smallNoteAppendStartEventId,
            ),
        )
    }

    fun insertPitch(
        pitch: Pitch,
        duration: Duration = state.value.noteInput.duration,
    ): ScoreEditDispatchResult? {
        val cursor = interaction.state.value.entryCursor ?: return null
        return dispatch(
            ScoreEditIntent.InsertNote(
                expectedRevision = state.value.frame.revision,
                voiceTrackId = cursor.voiceTrackId,
                start = cursor.position,
                duration = duration,
                pitch = pitch,
                smallNoteAppendStartEventId = interaction.state.value.smallNoteAppendStartEventId,
            ),
        )
    }

    fun insertChord(
        pitches: List<Pitch>,
        duration: Duration = state.value.noteInput.duration,
    ): ScoreEditDispatchResult? {
        val cursor = interaction.state.value.entryCursor ?: return null
        if (pitches.isEmpty()) return null
        return dispatch(
            ScoreEditIntent.InsertChord(
                state.value.frame.revision,
                cursor.voiceTrackId,
                cursor.position,
                duration,
                pitches.distinct(),
            ),
        )
    }

    fun insertRest(
        duration: Duration = state.value.noteInput.duration,
    ): ScoreEditDispatchResult? {
        val cursor = interaction.state.value.entryCursor ?: return null
        return dispatch(
            ScoreEditIntent.InsertNote(
                expectedRevision = state.value.frame.revision,
                voiceTrackId = cursor.voiceTrackId,
                start = cursor.position,
                duration = duration,
                isRest = true,
                smallNoteAppendStartEventId = interaction.state.value.smallNoteAppendStartEventId,
            ),
        )
    }

    fun moveEntryCursor(action: ScoreEntryCursorAction) {
        interaction.moveEntryCursor(state.value.frame.runtimeScore, action)
        publish()
    }

    fun setSelection(targets: List<ScoreSelectionTarget>): ScoreEditDispatchResult = dispatch(
        ScoreEditIntent.SetSelection(state.value.frame.revision, targets),
    )

    fun deleteSelection(): ScoreEditDispatchResult? {
        val targets = selectedEventTargets()
        return if (targets.isEmpty()) null else dispatch(
            ScoreEditIntent.DeleteNotes(state.value.frame.revision, targets),
        )
    }

    fun transposeSelection(stepDelta: Int): ScoreEditDispatchResult? {
        if (stepDelta == 0) return null
        val targets = selectedEventTargets()
        return if (targets.isEmpty()) null else dispatch(
            ScoreEditIntent.TransposeNotes(state.value.frame.revision, targets, stepDelta),
        )
    }

    fun setSelectionDuration(duration: Duration): ScoreEditDispatchResult? {
        val targets = state.value.frame.selection
            .filterIsInstance<ScoreSelectionTarget.Event>()
            .mapNotNull { selected ->
                selected.voiceTrackId?.let {
                    ScoreEditIntent.DurationTarget(it, selected.eventId, duration)
                }
            }
        return if (targets.isEmpty()) null else dispatch(
            ScoreEditIntent.SetDurations(state.value.frame.revision, targets),
        )
    }

    fun copySelection(cut: Boolean = false): ScoreEditDispatchResult? {
        val targets = state.value.frame.selection
            .filterIsInstance<ScoreSelectionTarget.Event>()
            .mapNotNull { selected ->
                selected.voiceTrackId?.let {
                    ScoreEditIntent.CopyTarget(it, selected.eventId, selected.pitchIndices)
                }
            }
        if (targets.isEmpty()) return null
        return dispatch(
            if (cut) ScoreEditIntent.CutNotes(state.value.frame.revision, targets)
            else ScoreEditIntent.CopyNotes(state.value.frame.revision, targets),
        )
    }

    fun pasteAtEntryCursor(clearMeasure: Boolean = false): ScoreEditDispatchResult? {
        val cursor = interaction.state.value.entryCursor ?: return null
        return dispatch(
            ScoreEditIntent.PasteNotes(
                state.value.frame.revision,
                cursor.voiceTrackId,
                cursor.position,
                clearMeasure,
            ),
        )
    }

    fun moveSelectionToVoice(targetVoiceNumber: Int): ScoreEditDispatchResult? {
        if (targetVoiceNumber <= 0) return null
        val targets = state.value.frame.selection
            .filterIsInstance<ScoreSelectionTarget.Event>()
            .mapNotNull { selected -> selected.voiceTrackId?.let { voiceId ->
                ScoreEditIntent.VoiceMoveTarget(
                    voiceId,
                    selected.eventId,
                    targetVoiceNumber,
                    selected.pitchIndices,
                )
            } }
        return if (targets.isEmpty()) null else dispatch(
            ScoreEditIntent.MoveVoices(state.value.frame.revision, targets),
        )
    }

    fun setSelectionAccidental(accidental: Accidental?): ScoreEditDispatchResult? {
        val targets = state.value.frame.selection
            .filterIsInstance<ScoreSelectionTarget.Event>()
            .mapNotNull { selected -> selected.voiceTrackId?.let { voiceId ->
                ScoreEditIntent.AccidentalTarget(
                    voiceId,
                    selected.eventId,
                    accidental,
                    selected.pitchIndices,
                )
            } }
        return if (targets.isEmpty()) null else dispatch(
            ScoreEditIntent.SetAccidentals(state.value.frame.revision, targets),
        )
    }

    fun setSelectionTies(tieOut: Boolean): ScoreEditDispatchResult? {
        val targets = state.value.frame.selection
            .filterIsInstance<ScoreSelectionTarget.Event>()
            .mapNotNull { selected -> selected.voiceTrackId?.let { voiceId ->
                ScoreEditIntent.TieTarget(voiceId, selected.eventId, tieOut, selected.pitchIndices)
            } }
        return if (targets.isEmpty()) null else dispatch(
            ScoreEditIntent.SetTies(state.value.frame.revision, targets),
        )
    }

    fun setSelectionBeaming(beaming: BeamingInfo?): ScoreEditDispatchResult? {
        val targets = state.value.frame.selection
            .filterIsInstance<ScoreSelectionTarget.Event>()
            .mapNotNull { selected -> selected.voiceTrackId?.let { voiceId ->
                ScoreEditIntent.BeamingTarget(voiceId, selected.eventId, beaming)
            } }
        return if (targets.isEmpty()) null else dispatch(
            ScoreEditIntent.SetBeaming(state.value.frame.revision, targets),
        )
    }

    fun toggleSelectionArticulation(articulation: Articulation): ScoreEditDispatchResult? {
        val targets = selectedEventTargets()
        return if (targets.isEmpty()) null else dispatch(
            ScoreEditIntent.ToggleArticulation(state.value.frame.revision, targets, articulation),
        )
    }

    fun setSelectionArpeggio(type: ArpeggioType?): ScoreEditDispatchResult? {
        val targets = selectedEventTargets()
        return if (targets.isEmpty()) null else dispatch(
            ScoreEditIntent.SetArpeggio(state.value.frame.revision, targets, type),
        )
    }

    fun moveSelectedRests(staffPosition: Int?): ScoreEditDispatchResult? {
        val targets = state.value.frame.selection
            .filterIsInstance<ScoreSelectionTarget.Event>()
            .mapNotNull { selected -> selected.voiceTrackId?.let { voiceId ->
                ScoreEditIntent.RestPositionTarget(voiceId, selected.eventId, staffPosition)
            } }
        return if (targets.isEmpty()) null else dispatch(
            ScoreEditIntent.MoveRests(state.value.frame.revision, targets),
        )
    }

    fun deleteSelectedSlurs(): ScoreEditDispatchResult? {
        val ids = state.value.frame.selection.filterIsInstance<ScoreSelectionTarget.Slur>()
            .mapTo(linkedSetOf()) { it.slurId }
        return if (ids.isEmpty()) null else dispatch(
            ScoreEditIntent.DeleteSlurs(state.value.frame.revision, ids),
        )
    }

    fun deleteSelectedExpressions(): ScoreEditDispatchResult? {
        val ids = state.value.frame.selection.filterIsInstance<ScoreSelectionTarget.Attachment>()
            .mapTo(linkedSetOf()) { it.attachmentId }
        return if (ids.isEmpty()) null else dispatch(
            ScoreEditIntent.DeleteExpressions(state.value.frame.revision, ids),
        )
    }

    private fun selectedEventTargets(): List<ScoreEditIntent.EventTarget> = state.value.frame.selection
        .filterIsInstance<ScoreSelectionTarget.Event>()
        .mapNotNull { selected ->
            selected.voiceTrackId?.let {
                ScoreEditIntent.EventTarget(it, selected.eventId, selected.pitchIndices)
            }
        }

    fun applyTupletToSelection(count: Int): ScoreEditDispatchResult? {
        val targets = selectedEventGroups(count)
        return if (targets.isEmpty()) null else dispatch(
            ScoreEditIntent.ApplyTuplets(state.value.frame.revision, targets),
        )
    }

    fun createSmallNoteRegionFromSelection(): ScoreEditDispatchResult? {
        val targets = selectedEventGroups()
        return if (targets.isEmpty()) null else dispatch(
            ScoreEditIntent.CreateSmallNoteRegions(state.value.frame.revision, targets),
        )
    }

    fun setGraceGroupForSelection(
        totalDuration: Duration = Duration.EIGHTH,
        stealFrom: GraceTimeSource = GraceTimeSource.PRINCIPAL,
    ): ScoreEditDispatchResult? {
        val targets = state.value.frame.selection
            .filterIsInstance<ScoreSelectionTarget.Event>()
            .mapNotNull { selected ->
                selected.voiceTrackId?.let {
                    ScoreEditIntent.GraceGroupTarget(it, selected.eventId, totalDuration, stealFrom)
                }
            }
        return if (targets.isEmpty()) null else dispatch(
            ScoreEditIntent.SetGraceGroups(state.value.frame.revision, targets),
        )
    }

    private fun selectedEventGroups(count: Int? = null): List<ScoreEditIntent.EventGroupTarget> =
        state.value.frame.selection
            .filterIsInstance<ScoreSelectionTarget.Event>()
            .mapNotNull { selected -> selected.voiceTrackId?.let { it to selected.eventId } }
            .groupBy({ it.first }, { it.second })
            .map { (voiceTrackId, eventIds) ->
                ScoreEditIntent.EventGroupTarget(voiceTrackId, eventIds.toSet(), count)
            }

    fun insertMeasures(afterMeasure: Int, count: Int = 1): ScoreEditDispatchResult = dispatch(
        ScoreEditIntent.InsertMeasures(state.value.frame.revision, afterMeasure, count),
    )

    fun deleteMeasures(measureNumbers: Set<Int>): ScoreEditDispatchResult? =
        if (measureNumbers.isEmpty()) null else dispatch(
            ScoreEditIntent.DeleteMeasures(state.value.frame.revision, measureNumbers),
        )

    fun setBarline(boundaryMeasure: Int, type: BarlineType): ScoreEditDispatchResult = dispatch(
        ScoreEditIntent.SetBarline(state.value.frame.revision, boundaryMeasure, type),
    )

    fun setBarlineRepeatCount(boundaryMeasure: Int, repeatCount: Int): ScoreEditDispatchResult = dispatch(
        ScoreEditIntent.SetBarlineRepeatCount(state.value.frame.revision, boundaryMeasure, repeatCount),
    )

    fun toggleVoltaPair(boundaryMeasure: Int): ScoreEditDispatchResult = dispatch(
        ScoreEditIntent.ToggleVoltaPair(state.value.frame.revision, boundaryMeasure),
    )

    fun deleteSelectedVolta(): ScoreEditDispatchResult? =
        (state.value.frame.selection.singleOrNull() as? ScoreSelectionTarget.VoltaEnding)?.let { selected ->
            dispatch(
                ScoreEditIntent.DeleteVolta(
                    state.value.frame.revision,
                    selected.startMeasure,
                    selected.endMeasure,
                    selected.numbers,
                ),
            )
        }

    fun toggleNavigationMark(boundaryMeasure: Int, mark: NavigationMark): ScoreEditDispatchResult = dispatch(
        ScoreEditIntent.ToggleNavigationMark(state.value.frame.revision, boundaryMeasure, mark),
    )

    fun deleteSelectedNavigationMark(): ScoreEditDispatchResult? =
        (state.value.frame.selection.singleOrNull() as? ScoreSelectionTarget.NavigationMark)?.let { selected ->
            dispatch(
                ScoreEditIntent.DeleteNavigationMark(
                    state.value.frame.revision,
                    selected.boundaryMeasure,
                    selected.mark,
                ),
            )
        }

    fun setLayoutBreak(beforeMeasure: Int, kind: LayoutBreakKind?): ScoreEditDispatchResult = dispatch(
        ScoreEditIntent.SetLayoutBreak(state.value.frame.revision, beforeMeasure, kind),
    )

    fun setStaffVisibility(
        staffTrackIds: Set<TrackId>,
        startMeasure: Int,
        endMeasure: Int,
        hidden: Boolean,
    ): ScoreEditDispatchResult? = if (staffTrackIds.isEmpty()) null else dispatch(
        ScoreEditIntent.SetStaffVisibility(
            state.value.frame.revision,
            staffTrackIds,
            startMeasure,
            endMeasure,
            hidden,
        ),
    )

    /** Semantic staff-space nudge; device pixels never enter a geometry intent. */
    fun nudgeSelectedGeometry(
        capturedGeometry: ScoreGeometry?,
        dx: Float,
        dy: Float,
    ): ScoreEditDispatchResult? {
        if (dx == 0f && dy == 0f) return null
        val selected = state.value.frame.selection.singleOrNull() ?: return null
        val geometry = capturedGeometry ?: state.value.frame.runtimeScore.geometry ?: return null
        return when (selected) {
            is ScoreSelectionTarget.Slur -> geometry.slurs[selected.slurId]?.let { current ->
                dispatch(
                    ScoreEditIntent.SetSlurGeometry(
                        state.value.frame.revision,
                        selected.slurId,
                        current.copy(
                            startDx = current.startDx + dx,
                            startDy = current.startDy + dy,
                            endDx = current.endDx + dx,
                            endDy = current.endDy + dy,
                            directionOnly = false,
                            directionLocked = true,
                            manuallyAdjusted = true,
                        ),
                    ),
                )
            }
            is ScoreSelectionTarget.Tie -> geometry.ties[selected.sourceEventId]
                ?.firstOrNull { it.sourcePitchIndex == selected.sourcePitchIndex }
                ?.let { current ->
                    dispatch(
                        ScoreEditIntent.SetTieGeometry(
                            state.value.frame.revision,
                            selected.sourceEventId,
                            current.copy(
                                startDx = current.startDx + dx,
                                startDy = current.startDy + dy,
                                endDx = current.endDx + dx,
                                endDy = current.endDy + dy,
                                directionOnly = false,
                                directionLocked = true,
                                manuallyAdjusted = true,
                            ),
                        ),
                    )
                }
            is ScoreSelectionTarget.Beam -> geometry.beams[selected.groupId]?.let { current ->
                dispatch(
                    ScoreEditIntent.SetBeamGeometry(
                        state.value.frame.revision,
                        selected.groupId,
                        current.copy(
                            startDy = current.startDy + dy,
                            endDy = current.endDy + dy,
                            manuallyAdjusted = true,
                        ),
                    ),
                )
            }
            is ScoreSelectionTarget.Articulation -> geometry.articulations[selected.eventId]?.let { current ->
                val marks = current.marks.map { mark ->
                    if (selected.articulationIndex == null || mark.index == selected.articulationIndex) {
                        mark.copy(dx = mark.dx + dx, dy = mark.dy + dy)
                    } else mark
                }
                dispatch(
                    ScoreEditIntent.SetArticulationGeometry(
                        state.value.frame.revision,
                        selected.eventId,
                        current.copy(marks = marks),
                        selected.articulationIndex,
                    ),
                )
            }
            is ScoreSelectionTarget.Attachment -> geometry.attachments[selected.attachmentId]?.let { current ->
                val attachment = state.value.frame.computedScore.staffAttachments
                    .firstOrNull { it.id == selected.attachmentId } ?: return@let null
                val end = when (attachment) {
                    is ComputedHairpin -> attachment.endTime
                    is ComputedOctaveShift -> attachment.endTime
                    is ComputedOrnamentMark -> attachment.endTime
                    is ComputedTempoKeyframe -> attachment.nextTime
                    else -> null
                }
                dispatch(
                    ScoreEditIntent.MoveAttachment(
                        state.value.frame.revision,
                        selected.attachmentId,
                        attachment.time,
                        end,
                        current.copy(
                            startDx = current.startDx + dx,
                            startDy = current.startDy + dy,
                            endDx = current.endDx?.plus(dx),
                            endDy = current.endDy?.plus(dy),
                            manuallyAdjustedY = true,
                        ),
                    ),
                )
            }
            else -> null
        }
    }

    fun updateSelectedOrnament(oscillations: Int): ScoreEditDispatchResult? =
        selectedAttachmentId()?.let { id ->
            dispatch(
                ScoreEditIntent.UpdateOrnament(
                    expectedRevision = state.value.frame.revision,
                    ornamentId = id,
                    oscillations = oscillations,
                ),
            )
        }

    fun updateSelectedTempo(bpm: Float): ScoreEditDispatchResult? = selectedAttachmentId()?.let { id ->
        dispatch(ScoreEditIntent.UpdateTempo(state.value.frame.revision, id, effectiveBpm = bpm))
    }

    fun updateSelectedPerformance(amount: Fraction): ScoreEditDispatchResult? =
        selectedAttachmentId()?.let { id ->
            dispatch(ScoreEditIntent.UpdatePerformanceMark(state.value.frame.revision, id, amount))
        }

    /** Deterministic H-family alternative to dragging a volta endpoint. */
    fun resizeSelectedVolta(measureDelta: Int): ScoreEditDispatchResult? {
        if (measureDelta == 0) return null
        val selected = state.value.frame.selection.singleOrNull() as? ScoreSelectionTarget.VoltaEnding
            ?: return null
        return if (2 in selected.numbers) {
            dispatch(
                ScoreEditIntent.ResizeSecondVolta(
                    state.value.frame.revision,
                    selected.startMeasure,
                    selected.endMeasure + measureDelta,
                ),
            )
        } else if (1 in selected.numbers) {
            dispatch(
                ScoreEditIntent.ResizeFirstVoltaStart(
                    state.value.frame.revision,
                    selected.startMeasure,
                    selected.startMeasure + measureDelta,
                ),
            )
        } else null
    }

    /** Move a navigation mark by logical boundaries and/or staff-space displacement. */
    fun nudgeSelectedNavigation(
        boundaryDelta: Int = 0,
        dx: Float = 0f,
        dy: Float = 0f,
    ): ScoreEditDispatchResult? {
        val selected = state.value.frame.selection.singleOrNull() as? ScoreSelectionTarget.NavigationMark
            ?: return null
        val runtime = state.value.frame.runtimeScore
        val maxMeasure = runtime.measures.maxOfOrNull { it.value.number } ?: return null
        val targetBoundary = (selected.boundaryMeasure + boundaryDelta).coerceIn(1, maxMeasure)
        val current = runtime.getMeasure(selected.boundaryMeasure)
            ?.navigationMarkOffsets
            ?.get(selected.mark)
            ?: com.mecon.api.storage.NavigationMarkOffset()
        return dispatch(
            ScoreEditIntent.MoveNavigationMark(
                state.value.frame.revision,
                selected.boundaryMeasure,
                targetBoundary,
                selected.mark,
                current.copy(dx = current.dx + dx, dy = current.dy + dy),
            ),
        )
    }

    private fun selectedAttachmentId(): com.mecon.api.primitive.EventId? =
        (state.value.frame.selection.singleOrNull() as? ScoreSelectionTarget.Attachment)?.attachmentId

    fun setClef(staffTrackId: TrackId, onset: TimeCode, clef: Clef): ScoreEditDispatchResult =
        dispatch(ScoreEditIntent.SetClef(state.value.frame.revision, staffTrackId, onset, clef))

    fun setKeySignature(onset: TimeCode, keySignature: KeySignature): ScoreEditDispatchResult =
        dispatch(ScoreEditIntent.SetKeySignature(state.value.frame.revision, onset, keySignature))

    fun setTimeSignature(measureNumber: Int, timeSignature: TimeSignature): ScoreEditDispatchResult =
        dispatch(ScoreEditIntent.SetTimeSignature(state.value.frame.revision, measureNumber, timeSignature))

    fun addDynamic(staffTrackId: TrackId, onset: TimeCode, level: DynamicLevel): ScoreEditDispatchResult =
        dispatch(ScoreEditIntent.AddDynamic(state.value.frame.revision, staffTrackId, onset, level))

    fun addTempoMark(onset: TimeCode, bpm: Float): ScoreEditDispatchResult =
        dispatch(
            ScoreEditIntent.AddTempoMark(
                state.value.frame.revision,
                onset,
                TempoMarkType.METRONOME,
                bpm,
            ),
        )

    fun addFermata(afterTime: TimeCode, shape: FermataShape = FermataShape.NORMAL): ScoreEditDispatchResult =
        dispatch(ScoreEditIntent.AddFermata(state.value.frame.revision, afterTime, shape))

    fun addBreathMark(
        staffTrackId: TrackId,
        afterTime: TimeCode,
        shape: BreathMarkShape = BreathMarkShape.COMMA,
    ): ScoreEditDispatchResult = dispatch(
        ScoreEditIntent.AddBreathMark(
            state.value.frame.revision,
            staffTrackId,
            afterTime,
            BreathMarkScope.STAFF,
            shape,
        ),
    )

    fun addHairpin(
        staffTrackId: TrackId,
        start: TimeCode,
        end: TimeCode,
        type: HairpinType,
        style: HairpinStyle = HairpinStyle.WEDGE,
    ): ScoreEditDispatchResult = dispatch(
        ScoreEditIntent.AddHairpin(state.value.frame.revision, staffTrackId, start, end, type, style),
    )

    fun addOctaveShift(
        staffTrackId: TrackId,
        start: TimeCode,
        end: TimeCode,
        type: OctaveShiftType,
    ): ScoreEditDispatchResult = dispatch(
        ScoreEditIntent.AddOctaveShift(state.value.frame.revision, staffTrackId, start, end, type),
    )

    fun addGradualTempo(start: TimeCode, end: TimeCode, type: TempoMarkType): ScoreEditDispatchResult =
        dispatch(ScoreEditIntent.AddGradualTempo(state.value.frame.revision, start, end, type))

    fun addOrnament(
        staffTrackId: TrackId,
        sourceEventId: com.mecon.api.primitive.EventId,
        kind: OrnamentKind,
        endOnset: TimeCode? = null,
    ): ScoreEditDispatchResult = dispatch(
        ScoreEditIntent.AddOrnament(
            state.value.frame.revision,
            staffTrackId,
            sourceEventId,
            kind,
            OrnamentAnchor.ON_NOTE,
            endOnset,
        ),
    )

    fun addSlurFromSelection(): ScoreEditDispatchResult? {
        val targets = state.value.frame.selection
            .filterIsInstance<ScoreSelectionTarget.Event>()
            .mapNotNull { selected -> selected.voiceTrackId?.let { it to selected.eventId } }
            .groupBy({ it.first }, { it.second })
            .mapNotNull { (voiceId, ids) ->
                val ordered = ids.distinct().sortedBy { id ->
                    state.value.frame.computedScore.getComputedEvent(id)?.onset
                }
                if (ordered.size == 2) ScoreEditIntent.SlurTarget(voiceId, ordered[0], ordered[1]) else null
            }
        return if (targets.isEmpty()) null else dispatch(
            ScoreEditIntent.AddSlurs(state.value.frame.revision, targets),
        )
    }

    private fun publish(
        activity: MobileScoreActivity = mutableState.value.activity,
        toolGroup: ScoreToolGroup = mutableState.value.activeToolGroup,
        frame: ScoreEditingFrame = mutableState.value.frame,
        effect: ScoreEditEffect? = mutableState.value.lastEffect,
    ) {
        mutableState.value = mutableState.value.copy(
            activity = activity,
            activeToolGroup = toolGroup,
            interaction = interaction.state.value,
            frame = frame,
            lastEffect = effect,
        )
    }

    companion object {
        fun open(
            score: StorageScore,
            capabilities: ScoreInputCapabilities = ScoreInputCapabilities(),
        ): MobileScoreEditorController = MobileScoreEditorController(
            ScoreEditingSession.open(score),
            capabilities,
        )
    }
}
