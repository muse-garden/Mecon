package com.mecon.desktop.ui.views

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextMeasurer
import com.mecon.api.computed.ComputedScore
import com.mecon.api.interaction.EventSection
import com.mecon.api.runtime.RuntimeScore
import com.mecon.desktop.ui.views.drag.*
import com.mecon.renderer.interaction.StyleSnapshot
import kotlinx.coroutines.CoroutineScope

/**
 * How [RenderedScoreView]'s state maps onto each subsystem's request contract.
 *
 * The view keeps its own state in a handful of `remember`ed holders; the canvas, the four pointer
 * layers and the drawing pass each declare an immutable request describing exactly what they need.
 * Translating between the two is mechanical but bulky, so it lives here — that keeps the view's
 * modifier chain readable as *which layers are stacked in what order*, which is the thing that
 * actually matters when reasoning about gesture arbitration.
 *
 * Two rules hold throughout:
 *  - state that a long-lived pointer coroutine must observe is passed as a **holder or getter**,
 *    never as a copied snapshot;
 *  - a large immutable frame is passed by reference and keyed on [ScoreRenderFrame.identityKey],
 *    never compared structurally.
 */
internal fun ambientGestureRequest(
    frame: ScoreRenderFrame,
    score: RuntimeScore?,
    display: RenderedScoreDisplayConfig,
    selectionConfig: RenderedScoreSelectionConfig,
    selectorRegions: List<RenderedScoreStaffSelectorRegion>,
    viewport: RenderedScoreViewportState,
    onStaffSelector: (String) -> Unit,
    onAnnotationResizeHover: (Boolean) -> Unit,
    onHiddenStaffMenu: (List<HiddenStaffMenuOption>?, Offset) -> Unit,
) = AmbientGestureRequest(
    resultIdentityKey = frame.identityKey,
    result = frame.result,
    score = score,
    pages = frame.pages,
    pageSlots = frame.pageSlots,
    paginated = frame.paginated,
    zoomEnabled = display.zoomEnabled,
    showEditorMarkers = display.showEditorMarkers,
    resizableAnnotationEventIds = selectionConfig.resizableAnnotationEventIds,
    selectorRegions = selectorRegions,
    viewport = viewport,
    onStaffSelector = onStaffSelector,
    onAnnotationResizeHover = onAnnotationResizeHover,
    onHiddenStaffMenu = onHiddenStaffMenu,
)

internal fun dragGestureRequest(
    frame: ScoreRenderFrame,
    score: RuntimeScore?,
    computed: ComputedScore?,
    config: RenderedScoreViewConfig,
    overlay: ScoreSelectionOverlayState,
    insertionToolActive: Boolean,
    viewport: RenderedScoreViewportState,
    previews: ScoreDragPreviewState,
    selection: () -> Set<EventSection>,
    onSelectionChange: (Set<EventSection>) -> Unit,
) = DragGestureRequest(
    frame = DragGestureFrame(
        resultIdentityKey = frame.identityKey,
        result = frame.result,
        pages = frame.pages,
        pageSlots = frame.pageSlots,
        paginated = frame.paginated,
    ),
    document = DragGestureDocument(
        score = score,
        computed = computed,
        engine = frame.engine,
        beamGeometry = config.lifecycle.beamGeometry,
        selectedBeamControls = overlay.beamControls,
    ),
    mode = DragGestureMode(
        insertionToolActive = insertionToolActive,
        panEnabled = config.display.panEnabled,
        readOnly = config.display.readOnly,
        noteTool = config.edit.notation.noteTool,
        marqueeSelectableTypes = config.selectionConfig.marqueeSelectableTypes,
        resizableAnnotationEventIds = config.selectionConfig.resizableAnnotationEventIds,
    ),
    actions = DragGestureActions(
        selection = DragSelectionActions(
            selectionChange = onSelectionChange,
            auditionNote = config.edit.notation.onAuditionNote,
            selectAnnotationEvent = config.selectionConfig.onSelectAnnotationEvent,
            resizeAnnotationRange = config.selectionConfig.onResizeAnnotationRange,
        ),
        notes = with(config.edit.eventMovement) {
            DragNoteMovementActions(
                transpose = onTranspose,
                moveRest = onMoveRest,
                moveBeam = onMoveBeam,
            )
        },
        expressions = with(config.edit.eventMovement) {
            DragExpressionMovementActions(
                moveAttachment = onMoveAttachment,
                adjustTieCurve = onAdjustTieCurve,
                adjustSlurCurve = onAdjustSlurCurve,
            )
        },
        structure = with(config.edit.structuralMovement) {
            DragStructuralMovementActions(
                resizeSecondVolta = onResizeSecondVolta,
                resizeFirstVoltaStart = onResizeFirstVoltaStart,
                moveNavigationMark = onMoveNavigationMark,
            )
        },
    ),
    state = DragGestureState(
        viewport = viewport,
        previews = previews,
        selection = selection,
    ),
)

internal fun selectionGestureRequest(
    frame: ScoreRenderFrame,
    score: RuntimeScore?,
    computed: ComputedScore?,
    config: RenderedScoreViewConfig,
    insertionToolActive: Boolean,
    viewport: RenderedScoreViewportState,
    selection: () -> Set<EventSection>,
    onSelectionChange: (Set<EventSection>) -> Unit,
) = SelectionGestureRequest(
    frame = SelectionGestureFrame(
        resultIdentityKey = frame.identityKey,
        arrangement = config.display.arrangement,
        result = frame.result,
        paginated = frame.paginated,
        pages = frame.pages,
        pageSlots = frame.pageSlots,
        editorMarkers = frame.editorMarkers,
    ),
    document = SelectionGestureDocument(score = score, computed = computed),
    mode = SelectionGestureMode(
        insertionToolActive = insertionToolActive,
        showEditorMarkers = config.display.showEditorMarkers,
    ),
    state = SelectionGestureState(
        offset = { viewport.offset.value },
        scale = { viewport.scale.value },
        shiftHeld = { viewport.shiftHeld.value },
        selection = selection,
    ),
    actions = SelectionGestureActions(
        selectionChange = onSelectionChange,
        auditionNote = config.edit.notation.onAuditionNote,
        selectAnnotationEvent = config.selectionConfig.onSelectAnnotationEvent,
    ),
)

internal fun insertionGestureRequest(
    frame: ScoreRenderFrame,
    score: RuntimeScore?,
    computed: ComputedScore?,
    config: RenderedScoreViewConfig,
    viewport: RenderedScoreViewportState,
    density: Float,
    previews: RenderedScoreInsertionPreviewState,
) = InsertionGestureRequest(
    environment = InsertionGestureEnvironment(
        resultIdentityKey = frame.identityKey,
        result = frame.result,
        computed = computed,
        runtime = score,
        engine = frame.engine,
        paginated = frame.paginated,
        pages = frame.pages,
        pageSlots = frame.pageSlots,
        offset = { viewport.offset.value },
        scale = { viewport.scale.value },
        density = density,
    ),
    tool = config.edit.notation.noteTool,
    actions = InsertionGestureActions(
        insertNote = config.edit.notation.onInsertNote,
        insertClef = config.edit.notation.onInsertClef,
        insertTimeSignature = config.edit.notation.onInsertTimeSignature,
        insertKeySignature = config.edit.notation.onInsertKeySignature,
        insertBarline = config.edit.notation.onInsertBarline,
        insertRepeatStructure = config.edit.notation.onInsertRepeatStructure,
        insertDynamic = config.edit.expression.onInsertDynamic,
        insertPauseMark = config.edit.expression.onInsertPauseMark,
        insertExpressionSpan = config.edit.expression.onInsertExpressionSpan,
        insertTempo = config.edit.expression.onInsertTempo,
        insertTempoSpan = config.edit.expression.onInsertTempoSpan,
        insertOrnament = config.edit.expression.onInsertOrnament,
        insertArpeggio = config.edit.expression.onInsertArpeggio,
    ),
    previews = InsertionGesturePreviews(
        note = { previews.note.value = it },
        clef = { previews.clef.value = it },
        timeSignature = { previews.timeSignature.value = it },
        keySignature = { previews.keySignature.value = it },
        expressionSpan = { previews.expressionSpan.value = it },
    ),
)

internal fun scoreCanvasDrawRequest(
    frame: ScoreRenderFrame,
    score: RuntimeScore?,
    config: RenderedScoreViewConfig,
    selection: Set<EventSection>,
    overlay: ScoreSelectionOverlayState,
    styleSnapshot: StyleSnapshot,
    textMeasurer: TextMeasurer,
    density: Float,
    insertionPreviews: RenderedScoreInsertionPreviewState,
    dragPreviews: ScoreDragPreviewState,
    commitHold: ScoreDragCommitHold,
    playback: ScorePlaybackMapping,
    viewport: RenderedScoreViewportState,
    scope: CoroutineScope,
    onDocumentInteractive: (Long) -> Unit,
) = RenderedScoreCanvasDrawRequest(
    render = RenderedScoreRenderFrame(
        renderer = frame.composeRenderer,
        result = frame.result,
        textMeasurer = textMeasurer,
        styleSnapshot = styleSnapshot,
        density = density,
    ),
    page = RenderedScorePageFrame(
        pages = frame.pages,
        pageSlots = frame.pageSlots,
        markersByPage = frame.editorMarkersByPage,
        continuousMarkers = frame.editorMarkers,
        stalePageIndices = frame.stalePageIndices,
        paginated = frame.paginated,
        showEditorMarkers = config.display.showEditorMarkers,
        preparingLoadedDocument = frame.preparingLoadedDocument,
    ),
    selection = RenderedScoreSelectionOverlay(
        score = score,
        selection = selection,
        highlightedElements = config.selectionConfig.highlightedElements,
        selectedAnnotationEventId = config.selectionConfig.selectedAnnotationEventId,
        resizableAnnotationEventIds = config.selectionConfig.resizableAnnotationEventIds,
        selectedBeamSection = overlay.beamSection,
        selectedBeamControls = overlay.beamControls,
        selectedVoltaSection = overlay.voltaSection,
        selectedVoltaElements = overlay.voltaElements,
        selectedNavigationElements = overlay.navigationElements,
        selectedAttachmentSection = overlay.attachmentSection,
        selectedAttachmentElements = overlay.attachmentElements,
        noteheadBackgroundGroups = config.selectionConfig.noteheadBackgroundGroups,
        noteheadCenterMarkers = overlay.noteheadCenterMarkers,
        noteSelectionLabels = overlay.noteSelectionLabels,
    ),
    ghosts = RenderedScoreGhostOverlay(
        note = insertionPreviews.note.value,
        clef = insertionPreviews.clef.value,
        timeSignature = insertionPreviews.timeSignature.value,
        keySignature = insertionPreviews.keySignature.value,
        expressionSpan = insertionPreviews.expressionSpan.value,
        color = GHOST_COLOR,
    ),
    drags = RenderedScoreDragOverlay(
        transpose = dragPreviews.transpose.value,
        transposeCommitted = commitHold.transpose.committedFrameDisplayed,
        beam = dragPreviews.beam.value,
        beamCommitted = commitHold.beam.committedFrameDisplayed,
        attachment = dragPreviews.attachment.value,
        attachmentCommitted = commitHold.attachment.committedFrameDisplayed,
        volta = dragPreviews.volta.value,
        navigation = dragPreviews.navigation.value,
        navigationCommitted = commitHold.navigation.committedFrameDisplayed,
        curve = dragPreviews.curve.value,
        curveCommitted = commitHold.curve.committedFrameDisplayed,
        annotationRange = dragPreviews.annotationRange.value,
        selectionColor = SELECTION_FILL_COLOR,
    ),
    playback = RenderedScorePlaybackOverlay(
        state = config.display.playbackState,
        tickToX = playback.tickToX,
        positionTicks = playback.scorePositionTicks,
    ),
    lifecycle = RenderedScoreDrawLifecycle(
        documentVersion = config.source.documentVersion,
        readyFrameScheduledVersion = frame.readyFrameScheduledVersion,
        scope = scope,
        onDocumentInteractive = onDocumentInteractive,
    ),
    scale = viewport.scale.value,
    offset = viewport.offset.value,
)
