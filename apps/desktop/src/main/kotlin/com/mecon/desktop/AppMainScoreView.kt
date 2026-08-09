package com.mecon.desktop

import com.mecon.desktop.service.applyBeamGeometry
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*
import com.mecon.api.computed.ComputedScore
import com.mecon.api.interaction.*
import com.mecon.api.model.Score
import com.mecon.api.primitive.Accidental
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TrackId
import com.mecon.api.interaction.StyleOverride
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.events.TempoMarkType
import com.mecon.api.storage.ScoreGeometry
import com.mecon.api.storage.tracks.MeasureRange
import com.mecon.audio.engine.PlaybackState
import com.mecon.core.engine.edit.ExpressionEditEngine
import com.mecon.core.engine.edit.KeySignatureEditEngine
import com.mecon.core.engine.edit.TempoEditEngine
import com.mecon.desktop.service.PlaybackController
import com.mecon.desktop.service.ScoreFileController
import com.mecon.desktop.service.ScoreSession
import com.mecon.desktop.ui.components.*
import com.mecon.desktop.input.NoteInputState
import com.mecon.desktop.ui.components.topbar.ScoreViewMode
import com.mecon.desktop.ui.views.RenderedScoreDisplayConfig
import com.mecon.desktop.ui.views.RenderedScoreEditConfig
import com.mecon.desktop.ui.views.RenderedScoreExpressionInsertion
import com.mecon.desktop.ui.views.RenderedScoreLifecycleConfig
import com.mecon.desktop.ui.views.RenderedScoreNoteheadBackgroundGroup
import com.mecon.desktop.ui.views.RenderedScoreNotationInsertion
import com.mecon.desktop.ui.views.RenderedScoreSelectionConfig
import com.mecon.desktop.ui.views.RenderedScoreSource
import com.mecon.desktop.ui.views.RenderedScoreStaffSelectorConfig
import com.mecon.desktop.ui.views.RenderedScoreStructuralMoveActions
import com.mecon.desktop.ui.views.RenderedScoreView
import com.mecon.desktop.ui.views.RenderedScoreViewConfig
import com.mecon.desktop.ui.views.noteMovementActions
import com.mecon.renderer.interaction.*

internal data class AppMainScoreDocument(
    val session: ScoreSession,
    val fileController: ScoreFileController,
)

internal data class AppMainScorePlayback(
    val controller: PlaybackController,
    val currentPositionTicks: Long,
    val state: PlaybackState,
)

internal data class AppMainScoreUi(
    val noteTool: NoteToolState,
    val noteInput: NoteInputState,
    val midiDeviceName: String?,
    val onCycleMidiDevice: () -> Unit,
    val noteStyleNonce: Int,
    val pluginRenderNonce: Int,
    val scoreViewMode: ScoreViewMode,
)

internal data class AppMainScoreState(
    val geometry: () -> ScoreGeometry?,
    val setGeometry: (ScoreGeometry?) -> Unit,
    val selection: () -> Set<EventSection>,
    val setSelection: (Set<EventSection>) -> Unit,
    val selectedAnnotationId: () -> EventId?,
    val setSelectedAnnotationId: (EventId?) -> Unit,
)

internal data class AppMainScoreActions(
    val applyExpressionResult: (ExpressionEditEngine.Result?, () -> Unit) -> Unit,
    val auditionEditedEvent: (Set<EventSection>, RuntimeScore) -> Unit,
    val revealStaff: (List<TrackId>, MeasureRange) -> Unit,
)

internal data class AppMainScoreSyncMode(
    val selectableEventIds: Set<EventId>,
    val noteheadBackgroundGroups: List<RenderedScoreNoteheadBackgroundGroup>,
    val staffSelectors: RenderedScoreStaffSelectorConfig,
)

internal data class AppMainScoreRequest(
    val document: AppMainScoreDocument,
    val playback: AppMainScorePlayback,
    val ui: AppMainScoreUi,
    val state: AppMainScoreState,
    val actions: AppMainScoreActions,
    val syncMode: AppMainScoreSyncMode? = null,
)

@Composable
internal fun AppMainScoreView(request: AppMainScoreRequest) {
    val session = request.document.session
    val fileController = request.document.fileController
    val playback = request.playback.controller
    val currentPositionTicks = request.playback.currentPositionTicks
    val playbackState = request.playback.state
    val noteTool = request.ui.noteTool
    val noteStyleNonce = request.ui.noteStyleNonce
    val pluginRenderNonce = request.ui.pluginRenderNonce
    val scoreViewMode = request.ui.scoreViewMode
    val applyExpressionResult = request.actions.applyExpressionResult
    val auditionSingleEditedEvent = request.actions.auditionEditedEvent
    val revealStaff = request.actions.revealStaff
    var latestRenderedGeometry by com.mecon.desktop.ui.views.MutableLiveValue(
        request.state.geometry,
        request.state.setGeometry,
    )
    var eventSelection by com.mecon.desktop.ui.views.MutableLiveValue(
        request.state.selection,
        request.state.setSelection,
    )
    var selectedAnnotationEventId by com.mecon.desktop.ui.views.MutableLiveValue(
        request.state.selectedAnnotationId,
        request.state.setSelectedAnnotationId,
    )
    RenderedScoreView(
        config = RenderedScoreViewConfig(
            source = RenderedScoreSource(
                score = session.runtimeScore,
                computed = session.computedScore,
                renderHint = session.renderHint,
                documentVersion = session.documentVersion,
            ),
            lifecycle = RenderedScoreLifecycleConfig(
                interactionBlocked = session.structuralEditInFlight,
                documentLoading = fileController.documentLoading,
                loadingDocumentVersion = fileController.loadingDocumentVersion,
                onDocumentInteractive = fileController::onDocumentInteractive,
                onGeometryCaptured = {
                    latestRenderedGeometry = it
                    session.lastRenderedGeometry = it
                    session.onRenderSettled()
                },
                beamGeometry = latestRenderedGeometry ?: session.runtimeScore?.geometry,
                onRevealStaff = revealStaff,
            ),
            selectionConfig = RenderedScoreSelectionConfig(
                selection = eventSelection,
                onSelectionChange = {
                    eventSelection = request.syncMode?.let { mode ->
                        it.onlyEvents(mode.selectableEventIds)
                    } ?: it
                    selectedAnnotationEventId = null
                },
                onSelectAnnotationEvent = { id ->
                    selectedAnnotationEventId = id
                    if (id != null) eventSelection = emptySet()
                },
                selectedAnnotationEventId = selectedAnnotationEventId,
                noteheadBackgroundGroups = request.syncMode?.noteheadBackgroundGroups.orEmpty(),
                selectableSection = { section ->
                    request.syncMode?.let { mode ->
                        val ids = section.voiceEventIds()
                        ids.isNotEmpty() && ids.all { it in mode.selectableEventIds }
                    } ?: true
                },
            ),
            display = RenderedScoreDisplayConfig(
                noteStyleRefreshKey = noteStyleNonce,
                renderRefreshKey = pluginRenderNonce,
                currentPositionTicks = currentPositionTicks,
                playbackState = playbackState,
                arrangement = session.pageArrangement,
                showEditorMarkers = scoreViewMode == ScoreViewMode.EDIT,
            ),
            edit = RenderedScoreEditConfig(
                notation = RenderedScoreNotationInsertion(
                    noteTool = noteTool,
        onAuditionNote = { event, pitchIndices, transposedPitchIndices, stepDelta ->
            playback.audition(
                session.runtimeScore,
                event,
                pitchIndices,
                transposedPitchIndices,
                stepDelta,
            )
        },
        onInsertNote = { insertion ->
            val onInputTransition = noteTool.prepareInsertionCommit()
            session.applyNoteEdit(insertion, onInputTransition) { inserted, committedScore ->
                eventSelection = setOf(inserted)
                selectedAnnotationEventId = null
                auditionSingleEditedEvent(setOf(inserted), committedScore)
            }
        },
        onInsertClef = { target ->
            session.applyClefEdit(target, noteTool.selectedClef) { newSelection ->
                eventSelection = newSelection
                selectedAnnotationEventId = null
            }
        },
        onInsertTimeSignature = { measureNumber ->
            session.applyTimeSignatureEdit(measureNumber, noteTool.selectedTimeSignature) { newSelection ->
                eventSelection = newSelection
                selectedAnnotationEventId = null
            }
        },
        onInsertKeySignature = { onset ->
            session.applyKeySignatureEdit(
                KeySignatureEditEngine.Target(onset),
                noteTool.selectedKeySignature,
            ) { newSelection ->
                eventSelection = newSelection
                selectedAnnotationEventId = null
            }
        },
        onInsertBarline = { target ->
            session.applyBarlineEdit(
                target.barline.measureNumber,
                noteTool.selectedBarlineType,
                noteTool.selectedRepeatCount,
            ) { newSelection ->
                val updated = newSelection.singleOrNull() as? BarlineSection
                eventSelection = updated?.let {
                    setOf(it.copy(
                        systemIndex = target.systemIndex,
                        visualPlacement = target.visualPlacement,
                    ))
                }.orEmpty()
                selectedAnnotationEventId = null
            }
            noteTool.cancelInsertionTool()
        },
        onInsertRepeatStructure = { target ->
            val after: (Set<EventSection>) -> Unit = { newSelection ->
                val updated = newSelection.singleOrNull() as? BarlineSection
                eventSelection = updated?.let {
                    setOf(it.copy(
                        systemIndex = target.systemIndex,
                        visualPlacement = target.visualPlacement,
                    ))
                }.orEmpty()
                selectedAnnotationEventId = null
            }
            noteTool.selectedVoltaNumber?.let {
                session.applyVoltaEdit(target.barline.measureNumber, after)
            } ?: noteTool.selectedNavigationMark?.let { mark ->
                session.applyNavigationMarkEdit(target.barline.measureNumber, mark, after)
            }
            noteTool.cancelInsertionTool()
        },
                ),
                structuralMovement = RenderedScoreStructuralMoveActions(
        onResizeSecondVolta = { startMeasure, endMeasure ->
            session.resizeSecondVolta(startMeasure, endMeasure) { newSelection ->
                eventSelection = newSelection
                selectedAnnotationEventId = null
            }
        },
        onResizeFirstVoltaStart = { startMeasure, newStartMeasure ->
            session.resizeFirstVoltaStart(startMeasure, newStartMeasure) { newSelection ->
                eventSelection = newSelection
                selectedAnnotationEventId = null
            }
        },
        onMoveNavigationMark = { boundaryMeasure, targetBoundaryMeasure, mark, offset ->
            session.moveNavigationMark(
                boundaryMeasure, targetBoundaryMeasure, mark, offset
            ) { newSelection ->
                eventSelection = newSelection
                selectedAnnotationEventId = null
            }
        },
                ),
                expression = RenderedScoreExpressionInsertion(
        onInsertDynamic = { staffId, onset ->
            session.runtimeScore?.let { rt ->
                val result = ExpressionEditEngine.addDynamic(
                    rt, staffId, onset, noteTool.selectedDynamic,
                )
                applyExpressionResult(result) {
                    noteTool.cancelInsertionTool()
                }
            }
        },
        onInsertPauseMark = { staffId, onset ->
            session.runtimeScore?.let { rt ->
                val result = when (noteTool.selectedPauseKind) {
                    PauseMarkKind.FERMATA -> ExpressionEditEngine.addFermata(
                        rt,
                        onset,
                        noteTool.selectedFermataShape,
                    )
                    PauseMarkKind.BREATH -> ExpressionEditEngine.addBreathMark(
                        rt,
                        staffId,
                        onset,
                        noteTool.selectedBreathScope,
                        noteTool.selectedBreathShape,
                        voiceNumber = noteTool.activeVoiceNumber,
                    )
                }
                applyExpressionResult(result) {
                    noteTool.cancelInsertionTool()
                }
            }
        },
        onInsertTempo = onInsertTempo@{ onset ->
            val rt = session.runtimeScore ?: return@onInsertTempo
            applyExpressionResult(TempoEditEngine.addMark(
                rt,
                onset,
                noteTool.selectedTempoMark,
                bpm = if (noteTool.selectedTempoMark == TempoMarkType.METRONOME) {
                    noteTool.selectedTempoBpm
                } else null,
            )) { noteTool.cancelInsertionTool() }
        },
        onInsertTempoSpan = onInsertTempoSpan@{ start, end ->
            val rt = session.runtimeScore ?: return@onInsertTempoSpan
            applyExpressionResult(TempoEditEngine.addGradual(
                rt, start, end, noteTool.selectedTempoMark,
            )) { noteTool.cancelInsertionTool() }
        },
        onInsertExpressionSpan = onInsertExpressionSpan@{ staffId, start, end ->
            val rt = session.runtimeScore ?: return@onInsertExpressionSpan
            val result = when (noteTool.tool) {
                EditTool.HAIRPIN -> ExpressionEditEngine.addHairpin(
                    rt, staffId, start, end,
                    noteTool.selectedHairpinType,
                    noteTool.selectedHairpinStyle,
                )
                EditTool.OCTAVE -> ExpressionEditEngine.addOctaveShift(
                    rt, staffId, start, end, noteTool.selectedOctaveShift,
                )
                else -> null
            }
            applyExpressionResult(result) {
                noteTool.cancelInsertionTool()
            }
        },
        onInsertOrnament = onInsertOrnament@{ staffId, eventId, anchor, endOnset ->
            val rt = session.runtimeScore ?: return@onInsertOrnament
            applyExpressionResult(
                ExpressionEditEngine.addOrnament(
                    rt,
                    staffId,
                    eventId,
                    noteTool.selectedOrnamentKind,
                    anchor,
                    endOnset,
                )
            ) { noteTool.cancelInsertionTool() }
        },
        onInsertArpeggio = onInsertArpeggio@{ eventId ->
            val rt = session.runtimeScore ?: return@onInsertArpeggio
            val voiceId = rt.voiceTrackIdOf(eventId) ?: return@onInsertArpeggio
            applyExpressionResult(
                ExpressionEditEngine.setArpeggio(
                    rt,
                    listOf(ExpressionEditEngine.NoteTarget(voiceId, eventId)),
                    noteTool.selectedArpeggioType,
                )
            ) { noteTool.cancelInsertionTool() }
        },
                ),
                eventMovement = session.noteMovementActions { newSelection ->
                    eventSelection = newSelection
                    selectedAnnotationEventId = null
                }.copy(
        onMoveBeam = { groupId, geometry ->
            session.applyBeamGeometry(groupId, geometry)
        },
        onMoveAttachment = { id, geometry, start, end ->
            session.applyAttachmentMove(id, geometry, start, end) { newSelection ->
                eventSelection = newSelection
                selectedAnnotationEventId = null
            }
        },
        onAdjustTieCurve = { sourceEventId, geometry ->
            session.applyTieGeometry(sourceEventId, geometry)
        },
        onAdjustSlurCurve = { slurId, geometry ->
            session.applySlurGeometry(slurId, geometry)
        },
                ),
            ),
            staffSelectors = request.syncMode?.staffSelectors ?: RenderedScoreStaffSelectorConfig(),
        ),
    )
}
