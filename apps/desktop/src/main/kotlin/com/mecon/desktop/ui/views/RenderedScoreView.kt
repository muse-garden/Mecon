package com.mecon.desktop.ui.views

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.primitive.EventId
import com.mecon.api.state.RenderHint
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.storage.BeamGeometry
import com.mecon.api.storage.tracks.Clef
import com.mecon.desktop.ui.views.drag.*
import com.mecon.desktop.ui.components.EditTool
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.audio.converter.ScoreToMidiConverter
import com.mecon.audio.engine.PlaybackState
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.api.render.RenderColor
import com.mecon.api.plugin.PluginRegistry
import com.mecon.api.interaction.*
import com.mecon.renderer.geometry.*
import com.mecon.renderer.layout.AlignedTimeAxisRequest
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.layout.ResolvedTimeAxis
import com.mecon.renderer.render.edit.GhostClef
import com.mecon.renderer.render.edit.GhostKeySignature
import com.mecon.renderer.render.edit.GhostTimeSignature
import com.mecon.renderer.render.edit.GhostNote
import com.mecon.renderer.render.edit.GhostExpressionSpan
import com.mecon.renderer.interaction.StyleOverrideManager
import com.mecon.renderer.interaction.StyleSnapshot
import com.mecon.renderer.interaction.OrderedOverride
import com.mecon.renderer.interaction.StyleTrackImpl
import com.mecon.renderer.render.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Cursor

internal fun shouldPublishResolvedTimeAxis(
    request: AlignedTimeAxisRequest?,
    nextAxis: ResolvedTimeAxis?,
): Boolean = nextAxis != null || request == null

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RenderedScoreView(
    config: RenderedScoreViewConfig,
    modifier: Modifier = Modifier,
) {
    val source = config.source
    val selectionConfig = config.selectionConfig
    val display = config.display
    val notation = config.edit.notation
    val expression = config.edit.expression
    val eventMovement = config.edit.eventMovement
    val structuralMovement = config.edit.structuralMovement
    val lifecycle = config.lifecycle
    val staffSelectors = config.staffSelectors

    val score = source.score
    val computed = source.computed
    val renderHint = source.renderHint
    val documentVersion = source.documentVersion
    val selection = selectionConfig.selection
    val onSelectionChange = selectionConfig.onSelectionChange
    val onSelectAnnotationEvent = selectionConfig.onSelectAnnotationEvent
    val resizableAnnotationEventIds = selectionConfig.resizableAnnotationEventIds
    val onResizeAnnotationRange = selectionConfig.onResizeAnnotationRange
    val marqueeSelectableTypes = selectionConfig.marqueeSelectableTypes
    val selectedAnnotationEventId = selectionConfig.selectedAnnotationEventId
    val highlightedElements = selectionConfig.highlightedElements
    val localEventStyles = selectionConfig.localEventStyles
    val noteheadBackgroundGroups = selectionConfig.noteheadBackgroundGroups
    val noteheadCenterMarkerNotes = selectionConfig.noteheadCenterMarkerNotes
    val selectableSection = selectionConfig.selectableSection
    val noteStyleRefreshKey = display.noteStyleRefreshKey
    val renderRefreshKey = display.renderRefreshKey
    val selectionOverlayRefreshKey = display.selectionOverlayRefreshKey
    val showPluginSelectionLabels = display.showPluginSelectionLabels
    val isReference = display.isReference
    val readOnly = display.readOnly
    val panEnabled = display.panEnabled
    val zoomEnabled = display.zoomEnabled
    val currentPositionTicks = display.currentPositionTicks
    val playbackState = display.playbackState
    val arrangement = display.arrangement
    val showEditorMarkers = display.showEditorMarkers
    val showViewLabel = display.showViewLabel
    val showZoomIndicator = display.showZoomIndicator
    val firstSystemIndent = display.firstSystemIndent
    val alignedTimeAxisRequest = display.alignedTimeAxisRequest
    val onResolvedTimeAxis = display.onResolvedTimeAxis
    val externalHorizontalOffsetPx = display.externalHorizontalOffsetPx
    val contentPadding = display.contentPadding
    val padEmptyMeasures = display.padEmptyMeasures
    val noteTool = notation.noteTool
    val onInsertNote = notation.onInsertNote
    val onAuditionNote = notation.onAuditionNote
    val onInsertClef = notation.onInsertClef
    val onInsertTimeSignature = notation.onInsertTimeSignature
    val onInsertKeySignature = notation.onInsertKeySignature
    val onInsertBarline = notation.onInsertBarline
    val onInsertRepeatStructure = notation.onInsertRepeatStructure
    val onInsertDynamic = expression.onInsertDynamic
    val onInsertPauseMark = expression.onInsertPauseMark
    val onInsertExpressionSpan = expression.onInsertExpressionSpan
    val onInsertTempo = expression.onInsertTempo
    val onInsertTempoSpan = expression.onInsertTempoSpan
    val onInsertOrnament = expression.onInsertOrnament
    val onInsertArpeggio = expression.onInsertArpeggio
    val onTranspose = eventMovement.onTranspose
    val onMoveRest = eventMovement.onMoveRest
    val onMoveBeam = eventMovement.onMoveBeam
    val onMoveAttachment = eventMovement.onMoveAttachment
    val onAdjustTieCurve = eventMovement.onAdjustTieCurve
    val onAdjustSlurCurve = eventMovement.onAdjustSlurCurve
    val onResizeSecondVolta = structuralMovement.onResizeSecondVolta
    val onResizeFirstVoltaStart = structuralMovement.onResizeFirstVoltaStart
    val onMoveNavigationMark = structuralMovement.onMoveNavigationMark
    val commitUpdatingDelayMs = lifecycle.commitUpdatingDelayMs
    val interactionBlocked = lifecycle.interactionBlocked
    val documentLoading = lifecycle.documentLoading
    val loadingDocumentVersion = lifecycle.loadingDocumentVersion
    val onDocumentInteractive = lifecycle.onDocumentInteractive
    val onGeometryCaptured = lifecycle.onGeometryCaptured
    val beamGeometry = lifecycle.beamGeometry
    val onRevealStaff = lifecycle.onRevealStaff
    val compositionStartedAt = System.nanoTime()
    var compositionCheckpointAt = compositionStartedAt
    fun compositionCheckpoint(name: String) {
        val now = System.nanoTime()
        val elapsedMs = (now - compositionCheckpointAt) / 1_000_000
        if (elapsedMs >= 5) {
            com.mecon.renderer.debug.PerfLog.log("compose.score.phase") {
                "$name=${elapsedMs}ms"
            }
        }
        compositionCheckpointAt = now
    }
    SideEffect {
        com.mecon.renderer.debug.PerfLog.log("compose.score") {
            "composition=${(System.nanoTime() - compositionStartedAt) / 1_000_000}ms " +
                "reference=$isReference readOnly=$readOnly"
        }
    }
    val textMeasurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    val currentOnDocumentInteractive by rememberUpdatedState(onDocumentInteractive)
    val readyFrameScheduledVersion = remember { longArrayOf(Long.MIN_VALUE) }
    val screenDensity = LocalDensity.current.density

    val preparingLoadedDocument = documentLoading && loadingDocumentVersion == documentVersion
    LaunchedEffect(documentLoading, loadingDocumentVersion, documentVersion) {
        if (documentLoading) readyFrameScheduledVersion[0] = Long.MIN_VALUE
    }

    // Shared rendering pipeline (font + engine + Compose renderer + serial dispatcher). Null
    // until the Bravura font finishes loading. The same helper backs the lightweight
    // SimpleScoreView; this view layers pagination, hit-testing and interaction on top.
    // Blank-bar padding is a view policy. Aligned workbenches disable it because the external ruler
    // already guarantees an editable width and must remain the sole horizontal spacing authority.
    val alignedTimeAxisIdentityKey = rememberIdentityKey(alignedTimeAxisRequest)
    val editConfig = remember(alignedTimeAxisIdentityKey, padEmptyMeasures, firstSystemIndent) {
        RenderLayoutConfig.DEFAULT.copy(
            padEmptyMeasures = padEmptyMeasures,
            firstSystemIndent = firstSystemIndent ?: RenderLayoutConfig.DEFAULT.firstSystemIndent,
            alignedTimeAxisRequest = alignedTimeAxisRequest,
        )
    }
    val renderer = rememberScoreRenderer(editConfig)
    val renderEngine = renderer?.engine
    val composeRenderer = renderer?.composeRenderer
    val scoreIdentityKey = rememberIdentityKey(score)
    val computedIdentityKey = rememberIdentityKey(computed)

    // Streaming state: pages arrive progressively from renderStreaming's onPage callback.
    // streamedPages maps pageIndex → the latest page emitted; stalePageIndices is the set of
    // pages that existed before the current in-flight render began (shown with a grey overlay
    // until replaced). Both are cleared once the settled RenderResult arrives.
    val streamedPages = remember { mutableStateMapOf<Int, RenderPage>() }
    var stalePageIndices by remember { mutableStateOf(emptySet<Int>()) }
    var renderInFlight by remember { mutableStateOf(false) }
    var showRenderUpdatingLabel by remember { mutableStateOf(false) }

    // Right-click context menu over a hidden dashed line / grey cell: the resolved per-staff reveal
    // options and the screen position to anchor the menu at.
    var hiddenStaffMenu by remember { mutableStateOf<List<HiddenStaffMenuOption>?>(null) }
    var hiddenStaffMenuPos by remember { mutableStateOf(Offset.Zero) }

    // When a new render starts (keys match those of rememberRenderResult), mark the currently
    // displayed streaming pages stale so the view shows them dimmed until new versions arrive.
    LaunchedEffect(renderer, scoreIdentityKey, computedIdentityKey, renderRefreshKey, interactionBlocked) {
        stalePageIndices = streamedPages.keys.toSet()
        if (renderer == null || (score == null && computed == null)) return@LaunchedEffect
        // Structural edits flip interactionBlocked before their off-thread compute starts. Preserve the
        // same generation when computed later changes, so the label covers compute + layout + anchor-page
        // rendering without disappearing/restarting its delay between phases.
        if (!renderInFlight) {
            renderInFlight = true
            showRenderUpdatingLabel = false
            kotlinx.coroutines.delay(commitUpdatingDelayMs)
            if (renderInFlight) showRenderUpdatingLabel = true
        }
    }

    // Once the full, settled RenderResult arrives, clear the transient streaming state — the
    // view switches to renderResult.pages as the authoritative source.
    val renderResult = rememberRenderResult(
        renderer, score, computed = computed, renderHint = renderHint,
        documentVersion = documentVersion,
        onGeometryCaptured = onGeometryCaptured,
        renderRefreshKey = renderRefreshKey,
        onPage = { pageIdx, page ->
            scope.launch {
                streamedPages[pageIdx] = page
                stalePageIndices = stalePageIndices - pageIdx
                // The anchor page arriving does not settle the render generation: the old RenderResult
                // still owns hit testing and commit hand-off until all pages have been assembled. Keep
                // the updating state alive so the delayed label gets at least one drawable frame and
                // accurately covers the interval in which commit interaction remains guarded.
            }
        },
    )
    val renderResultIdentityKey = rememberIdentityKey(renderResult)
    val noteSelectionLabels = remember(
        computedIdentityKey,
        selection,
        selectionOverlayRefreshKey,
        showPluginSelectionLabels,
    ) {
        computed?.takeIf { showPluginSelectionLabels }?.let { score ->
            PluginRegistry.noteSelectionLabelProviders().flatMap { provider ->
                provider.labels(score, selection)
            }
        }.orEmpty()
    }
    val currentOnResolvedTimeAxis by rememberUpdatedState(onResolvedTimeAxis)
    LaunchedEffect(renderResultIdentityKey, alignedTimeAxisIdentityKey) {
        val nextAxis = renderResult?.resolvedTimeAxis
        // A keyed background render temporarily exposes null even though the previous canvas frame
        // remains visible. An aligned host must retain that frame's axis for the same interval, or
        // sibling timelines fall back to their independent coordinate systems and visibly jump.
        if (shouldPublishResolvedTimeAxis(alignedTimeAxisRequest, nextAxis)) {
            currentOnResolvedTimeAxis(nextAxis)
        }
    }
    val selectorRegions = remember(
        renderResultIdentityKey,
        scoreIdentityKey,
        staffSelectors.choicesByStaffId,
    ) {
        renderResult?.let { staffSelectorRegions(it, score, staffSelectors) }.orEmpty()
    }
    LaunchedEffect(renderResultIdentityKey) {
        if (renderResult != null) {
            streamedPages.clear()
            stalePageIndices = emptySet()
            renderInFlight = false
            showRenderUpdatingLabel = false
        }
    }
    compositionCheckpoint("pipeline")

    // Re-apply note styles when the key increments (toggle changed) without a full re-render.
    // Shares the pipeline dispatcher so it is serialized against the render above.
    LaunchedEffect(noteStyleRefreshKey, renderer) {
        val r = renderer ?: return@LaunchedEffect
        withContext(r.dispatcher) { r.engine.reapplyNoteStyles() }
    }

    // Collect style snapshot from render engine
    val styleOverrideManager = renderEngine?.getStyleOverrideManager()
    val effectiveSnapshot by (styleOverrideManager?.snapshotFlow ?: kotlinx.coroutines.flow.MutableStateFlow(StyleSnapshot.EMPTY)).collectAsState()

    // Lock markers are session overlays. Resolve their stable note references once per immutable
    // render frame, never in the Canvas draw hot path.
    val noteheadCenterMarkers = remember(renderResultIdentityKey, noteheadCenterMarkerNotes) {
        renderResult?.let { result ->
            NoteheadCenterMarkerComputer.compute(result.elements, noteheadCenterMarkerNotes)
        }.orEmpty()
    }

    // Voice 1 remains the transient/default selection blue. Persistent selection styles below use
    // the selected section's voice-specific swatch.
    val selectionFillColor = remember { voiceSelectionRenderColor(1) }
    // Translucent grey for the note-pen ghost preview.
    val ghostColor = remember { RenderColor.rgba(138, 138, 138, 140) }

    // Build selection snapshot: one highest-priority track carrying a voice-specific fill for every
    // selected section. Multiple setStyle calls share one track (keyed by sectionId) and submit once.
    // Voice/note selection styling is keyed by stable section IDs. A transpose replaces the embedded
    // ComputedVoiceEvent but keeps the same IDs, so rebuilding an identical style track would only
    // invalidate and re-record the page's notes Picture. Whole-measure selection is the exception: it
    // expands through the current frame's section index and therefore really does depend on the frame.
    val selectionSectionIds = selection.mapTo(LinkedHashSet()) { it.id }
    val selectionVoiceColorKey = selection.map { it.id to it.voiceNumber(score) }
    val measureSelection = selection.any { it is MeasureStaffSection }
    val selectionFrameKey = if (measureSelection) renderResultIdentityKey else 0L
    val selectionScoreKey = if (measureSelection) scoreIdentityKey else 0L
    val selectionTrack = remember(
        selectionSectionIds,
        selectionVoiceColorKey,
        renderEngine,
        selectionFrameKey,
        selectionScoreKey,
    ) {
        if (selection.isNotEmpty() && renderEngine != null) {
            val styleOverrideManager = renderEngine.getStyleOverrideManager()
            val track = styleOverrideManager.createTrack(Int.MAX_VALUE) // highest priority
            val styledSections = LinkedHashSet<EventSection>()
            selection.forEach { section ->
                if (section !is MeasureStaffSection) {
                    styledSections += section
                    return@forEach
                }
                val voiceIds = score?.staffTracks?.get(section.staffTrackId)?.voiceTracks
                    ?.mapTo(HashSet()) { it.id }.orEmpty()
                renderResult?.sectionIndex?.allOfType<VoiceEventSection>()
                    ?.filter { eventSection ->
                        eventSection.event.onset.measure == section.measureNumber &&
                            (eventSection.event.originVoiceTrackId ?: score?.voiceTracks?.entries
                                ?.firstOrNull { (_, voice) -> voice.events.toList().any { it.id == eventSection.event.id } }
                                ?.key) in voiceIds
                    }
                    ?.forEach { styledSections += it }
            }
            styledSections.forEach { section ->
                track.setStyle(
                    section,
                    StyleOverride(fillColor = voiceSelectionRenderColor(section.voiceNumber(score) ?: 1)),
                )
            }
            track.submit()
            track
        } else {
            null
        }
    }

    DisposableEffect(selectionTrack, renderEngine) {
        onDispose {
            selectionTrack?.let { renderEngine?.getStyleOverrideManager()?.removeTrack(it) }
        }
    }

    val localStyleTrack = remember(localEventStyles, renderEngine) {
        if (localEventStyles.isNotEmpty() && renderEngine != null) {
            val track = renderEngine.getStyleOverrideManager().createTrack(Int.MAX_VALUE - 10) as StyleTrackImpl
            localEventStyles.forEach { (eventId, override) ->
                track.setStyleBySectionId(EventSectionId.voiceEvent(eventId), override)
                track.setStyleBySectionId(EventSectionId.voiceNote(eventId, 0), override)
                track.setStyleBySectionId(EventSectionId.voiceStem(eventId), override)
                track.setStyleBySectionId(EventSectionId.voiceFlag(eventId), override)
            }
            track.submit()
            track
        } else {
            null
        }
    }

    DisposableEffect(localStyleTrack, renderEngine) {
        onDispose {
            localStyleTrack?.let { renderEngine?.getStyleOverrideManager()?.removeTrack(it) }
        }
    }

    // In-progress drag-to-transpose, or null when not dragging a note's pitch. Drives both the
    // hidden-original style track and the re-engraved preview overlay.
    val dragPreviews = remember { RenderedScoreDragPreviewState() }
    var transposeDrag by dragPreviews.transpose
    var beamDrag by dragPreviews.beam
    var attachmentDrag by dragPreviews.attachment
    var voltaDrag by dragPreviews.volta
    var navigationDrag by dragPreviews.navigation
    var curveDrag by dragPreviews.curve
    var annotationRangeDrag by dragPreviews.annotationRange
    // Always-fresh views for the long-lived pointer coroutines (avoid stale captures).
    // Large immutable frames must use referential equality here. rememberUpdatedState's default
    // structural comparison turns a one-note late-score edit into an O(score) UI-thread pause.
    val currentScore by rememberReferentialUpdatedState(score)
    val currentComputed by rememberReferentialUpdatedState(computed)
    val currentOnTranspose by rememberUpdatedState(onTranspose)
    val currentOnMoveRest by rememberUpdatedState(onMoveRest)
    val currentOnMoveBeam by rememberUpdatedState(onMoveBeam)
    val currentOnResizeSecondVolta by rememberUpdatedState(onResizeSecondVolta)
    val currentOnResizeFirstVoltaStart by rememberUpdatedState(onResizeFirstVoltaStart)
    val currentOnMoveNavigationMark by rememberUpdatedState(onMoveNavigationMark)
    val currentOnStaffSelector by rememberUpdatedState(staffSelectors.onSelect)

    // The preview is held until the committed re-render lands, so the score never flashes the
    // pre-drag notation; see [rememberScoreDragCommitHold] for the full hand-off contract.
    val commitHold = rememberScoreDragCommitHold(dragPreviews, renderResult)
    val committedFrameDisplayed = commitHold.transpose.committedFrameDisplayed
    val beamCommittedFrameDisplayed = commitHold.beam.committedFrameDisplayed
    val attachmentCommittedFrameDisplayed = commitHold.attachment.committedFrameDisplayed
    val navigationCommittedFrameDisplayed = commitHold.navigation.committedFrameDisplayed
    val curveCommittedFrameDisplayed = commitHold.curve.committedFrameDisplayed
    val commitInteractionBlocked = commitHold.interactionBlocked
    // The drag's hide override is transient view state, not an engine-global style track. Merge it
    // into the snapshot consumed by this Canvas only. That lets a committed frame remove both the hide
    // and its preview in one recomposition, instead of waiting for StyleOverrideManager.snapshotFlow
    // to round-trip and forcing an extra notes-Picture recording.
    val movedEventIds = transposeDrag
        ?.takeIf { it.preview != null && !committedFrameDisplayed }
        ?.previewTargets?.keys.orEmpty()
    val draggedBeamSection = beamDrag?.takeUnless { beamCommittedFrameDisplayed }?.let { drag ->
        selection.filterIsInstance<VoiceBeamSection>().firstOrNull { it.groupId.value == drag.groupId }
    }
    val draggedBeamEventIds = draggedBeamSection?.events?.mapTo(LinkedHashSet()) { it.id }.orEmpty()
    val draggedBeamGroupId = draggedBeamSection?.groupId?.value
    val draggedAttachmentId = attachmentDrag?.takeUnless { attachmentCommittedFrameDisplayed }?.id
    val draggedNavigationSectionId = navigationDrag
        ?.takeUnless { navigationCommittedFrameDisplayed }
        ?.sectionId
    val draggedCurveSectionId = curveDrag
        ?.takeUnless { curveCommittedFrameDisplayed }
        ?.sectionId
    val displayStyleSnapshot = remember(
        effectiveSnapshot,
        movedEventIds,
        draggedBeamEventIds,
        draggedBeamGroupId,
        draggedAttachmentId,
        draggedNavigationSectionId,
        draggedCurveSectionId,
    ) {
        if (movedEventIds.isEmpty() && draggedBeamGroupId == null &&
            draggedAttachmentId == null && draggedNavigationSectionId == null &&
            draggedCurveSectionId == null
        ) {
            effectiveSnapshot
        } else {
            val merged = HashMap(effectiveSnapshot.overrides)
            var order = (merged.values.maxOfOrNull { it.order } ?: -1) + 1
            movedEventIds.forEach { eventId ->
                merged[EventSectionId.voiceEvent(eventId)] = OrderedOverride(
                    order = order++,
                    override = StyleOverride(hidden = true),
                )
            }
            draggedBeamGroupId?.let { groupId ->
                merged[EventSectionId.voiceBeam(groupId)] = OrderedOverride(
                    order = order++,
                    override = StyleOverride(hidden = true),
                )
                draggedBeamEventIds.forEach { eventId ->
                    merged[EventSectionId.voiceStem(eventId)] = OrderedOverride(
                        order = order++,
                        override = StyleOverride(hidden = true),
                    )
                }
            }
            draggedAttachmentId?.let { attachmentId ->
                merged[EventSectionId.staffAttachment(attachmentId)] = OrderedOverride(
                    order = order++,
                    override = StyleOverride(hidden = true),
                )
            }
            draggedNavigationSectionId?.let { sectionId ->
                merged[sectionId] = OrderedOverride(
                    order = order++,
                    override = StyleOverride(hidden = true),
                )
            }
            draggedCurveSectionId?.let { sectionId ->
                merged[sectionId] = OrderedOverride(
                    order = order++,
                    override = StyleOverride(hidden = true),
                )
            }
            StyleSnapshot(merged)
        }
    }

    compositionCheckpoint("selection+commit")

    // Build the O(N) playback-position mapping only while a playhead can actually be shown/followed.
    // Conversion runs off the UI thread and batches TimeCodes through one measure-offset pass.
    val playbackNeedsPositionMap =
        playbackState == PlaybackState.PLAYING || playbackState == PlaybackState.PAUSED
    val tickToXMapping by produceState<List<Pair<Long, TimeCodePosition>>?>(
        initialValue = null,
        renderResultIdentityKey,
        scoreIdentityKey,
        playbackNeedsPositionMap,
    ) {
        val playbackScore = score
        value = if (playbackNeedsPositionMap && renderResult != null && playbackScore != null) {
            withContext(Dispatchers.Default) {
                val startedAt = System.nanoTime()
                val positions = renderResult.timeCodePositions.values.toList()
                val ticks = ScoreToMidiConverter.timeCodesToTicks(
                    positions.map { it.timeCode },
                    playbackScore,
                )
                positions.zip(ticks) { position, tick -> tick to position }
                    .sortedBy { it.first }
                    .also { mapping ->
                        com.mecon.renderer.debug.PerfLog.log("render.compose") {
                            "playbackPositionMap=${(System.nanoTime() - startedAt) / 1_000_000}ms " +
                                "entries=${mapping.size}"
                        }
                    }
            }
        } else null
    }
    // Repeat/hold expansion can scan every voice for each fermata. Build it off the UI thread so
    // starting playback (and edits made while playing) never stall Compose. The primitive identity
    // key avoids structural score equality; produceState cancellation discards superseded timelines.
    val playbackTimeline by produceState<ScoreToMidiConverter.PlaybackTimeline?>(
        initialValue = null,
        scoreIdentityKey,
        playbackNeedsPositionMap,
    ) {
        value = if (playbackNeedsPositionMap) {
            val playbackScore = score
            if (playbackScore == null) null else withContext(Dispatchers.Default) {
                val startedAt = System.nanoTime()
                ScoreToMidiConverter.playbackTimeline(playbackScore).also { timeline ->
                    com.mecon.renderer.debug.PerfLog.log("render.compose") {
                        "playbackTimeline=${(System.nanoTime() - startedAt) / 1_000_000}ms " +
                            "occurrences=${timeline.occurrences.size}"
                    }
                }
            }
        } else null
    }
    val scorePositionTicks = playbackTimeline?.sourceTicksAt(currentPositionTicks)
        ?: currentPositionTicks
    compositionCheckpoint("playback")

    // Zoom and pan state
    val viewport = remember { RenderedScoreViewportState() }
    var scale by viewport.scale
    var offset by viewport.offset
    var viewportSize by viewport.viewportSize
    var followPlayback by viewport.followPlayback
    LaunchedEffect(externalHorizontalOffsetPx) {
        externalHorizontalOffsetPx?.let { external ->
            scale = 1f
            offset = Offset(-external, offset.y)
        }
    }

    // Live keyboard-modifier state — tap/drag gesture callbacks don't expose modifiers, so an
    // Initial-pass listener mirrors them here for the selection/drag handlers to read. Ctrl
    // temporarily swaps the Select and Marquee tools for the duration of a drag gesture:
    //   Select + Ctrl  → marquee (rubber-band select);  Marquee + Ctrl → pan (drag the score).
    var shiftHeld by viewport.shiftHeld
    var ctrlHeld by viewport.ctrlHeld
    // The in-progress marquee rectangle in raw pointer coordinates (for drawing the overlay).
    var marqueeRect by viewport.marqueeRect
    var annotationResizeHovered by remember { mutableStateOf(false) }
    // Always-fresh views of the selection inputs so the long-lived pointer coroutines don't act on
    // a stale snapshot captured when the gesture started.
    val currentSelection by rememberUpdatedState(selection)
    val latestOnSelectionChange by rememberUpdatedState(onSelectionChange)
    val latestSelectableSection by rememberUpdatedState(selectableSection)
    val currentOnSelectionChange: (Set<EventSection>) -> Unit = remember {
        { candidate ->
            latestOnSelectionChange(
                candidate.filterTo(linkedSetOf(), latestSelectableSection)
            )
        }
    }
    val currentOnAuditionNote by rememberUpdatedState(onAuditionNote)
    val currentOnMoveAttachment by rememberUpdatedState(onMoveAttachment)
    val currentOnAdjustTieCurve by rememberUpdatedState(onAdjustTieCurve)
    val currentOnAdjustSlurCurve by rememberUpdatedState(onAdjustSlurCurve)
    val selectedBeamSection = selection.filterIsInstance<VoiceBeamSection>().singleOrNull()
    val selectedAttachmentSection = selection.filterIsInstance<StaffAttachmentSection>().singleOrNull()
    val selectedVoltaSection = selection.filterIsInstance<VoltaEndingSection>().singleOrNull()
    val selectedNavigationSection = selection.filterIsInstance<NavigationMarkSection>().singleOrNull()
    val selectedVoltaElements = remember(renderResultIdentityKey, selectedVoltaSection?.id) {
        val rr = renderResult
        if (rr == null || selectedVoltaSection == null) emptyList() else
            rr.sectionIndex.elementsFor(selectedVoltaSection).elementIds
                .mapNotNull(rr::elementById)
    }
    val selectedNavigationElements = remember(renderResultIdentityKey, selectedNavigationSection?.id) {
        val rr = renderResult
        if (rr == null || selectedNavigationSection == null) emptyList() else
            rr.sectionIndex.elementsFor(selectedNavigationSection).elementIds
                .mapNotNull(rr::elementById)
    }
    val selectedAttachmentElements = remember(renderResultIdentityKey, selectedAttachmentSection?.id) {
        val rr = renderResult
        if (rr == null || selectedAttachmentSection == null) emptyList() else
            rr.sectionIndex.elementsFor(selectedAttachmentSection).elementIds
                .mapNotNull(rr::elementById)
    }
    val selectedBeamControls = remember(renderResultIdentityKey, selectedBeamSection?.id) {
        val result = renderResult
        if (result != null && selectedBeamSection != null) {
            findBeamControlPoints(result, selectedBeamSection)
        } else {
            null
        }
    }

    // Note-editing: whether the pen tool is active, and the current ghost-note preview (if any).
    val noteToolActive = noteTool?.tool == EditTool.NOTE
    val clefToolActive = noteTool?.tool == EditTool.CLEF
    val timeToolActive = noteTool?.tool == EditTool.TIME
    val keyToolActive = noteTool?.tool == EditTool.KEY
    val dynamicToolActive = noteTool?.tool == EditTool.DYNAMIC
    val pauseToolActive = noteTool?.tool == EditTool.PAUSE
    val tempoToolActive = noteTool?.tool == EditTool.TEMPO
    val barlineToolActive = noteTool?.tool == EditTool.BARLINE
    val repeatStructureToolActive = noteTool?.tool == EditTool.REPEAT_STRUCTURE
    val expressionSpanToolActive = noteTool?.tool == EditTool.HAIRPIN || noteTool?.tool == EditTool.OCTAVE ||
        noteTool?.tool == EditTool.TEMPO_SPAN
    val insertionToolActive = noteToolActive || clefToolActive || timeToolActive || keyToolActive ||
        dynamicToolActive || pauseToolActive || tempoToolActive || expressionSpanToolActive || barlineToolActive ||
            repeatStructureToolActive
    val insertionPreviews = remember { RenderedScoreInsertionPreviewState() }
    var ghost by insertionPreviews.note
    var clefGhost by insertionPreviews.clef
    var timeGhost by insertionPreviews.timeSignature
    var keyGhost by insertionPreviews.keySignature
    var expressionSpanGhost by insertionPreviews.expressionSpan
    // Clear any stale ghost when the tool is switched off.
    if (!noteToolActive && ghost != null) ghost = null
    if (!clefToolActive && clefGhost != null) clefGhost = null
    if (!timeToolActive && timeGhost != null) timeGhost = null
    if (!keyToolActive && keyGhost != null) keyGhost = null
    if (!expressionSpanToolActive && expressionSpanGhost != null) expressionSpanGhost = null
    LaunchedEffect(documentVersion) {
        transposeDrag = null
        scale = 1f
        offset = Offset(-(externalHorizontalOffsetPx ?: 0f), 0f)
        shiftHeld = false
        ctrlHeld = false
        marqueeRect = null
        ghost = null
        clefGhost = null
        keyGhost = null
        expressionSpanGhost = null
    }

    // Paginated mode: per-page draw slots (design px) computed from the page sizes and the
    // chosen arrangement. Empty in continuous mode → the single-canvas path is used.
    // During a streaming render (renderResult still null), use streamedPages for early display.
    val settledPages = renderResult?.pages.orEmpty()
    val pages = if (streamedPages.isNotEmpty()) {
        // produceState keeps the previous complete result during a new render. Overlay pages as they
        // stream in so the anchor page is visible immediately, while untouched pages remain as the old
        // frame until their replacements arrive.
        (settledPages.associateBy { it.pageIndex } + streamedPages).values.sortedBy { it.pageIndex }
    } else settledPages
    val paginatedView = (renderResult?.paginated == true || streamedPages.isNotEmpty()) && pages.isNotEmpty()
    val pageSlots = remember(pages, arrangement) { pageSlotOffsets(pages, arrangement) }
    val editorMarkersByPage = remember(pages) {
        pages.associate { page ->
            page.pageIndex to page.elements.filter { it.type == RenderElementType.EDITOR_MARKER }
        }
    }
    val editorMarkers = remember(renderResultIdentityKey) {
        renderResult?.elements?.filter { it.type == RenderElementType.EDITOR_MARKER }.orEmpty()
    }

    LaunchedEffect(playbackState) {
        if (playbackState == PlaybackState.PLAYING) followPlayback = true
    }

    LaunchedEffect(
        playbackState, scorePositionTicks, tickToXMapping, pages, pageSlots, paginatedView,
        arrangement, viewportSize, scale, followPlayback, screenDensity,
    ) {
        if (
            playbackState != PlaybackState.PLAYING || !followPlayback || !paginatedView ||
            viewportSize == Size.Zero || tickToXMapping.isNullOrEmpty()
        ) return@LaunchedEffect

        val mapping = tickToXMapping ?: return@LaunchedEffect
        val matchIndex = mapping.indexOfLast { it.first <= scorePositionTicks }
        val position = if (matchIndex < 0) mapping.first().second else mapping[matchIndex].second
        val playheadTop = globalToDesign(position.x, position.topY, pages, pageSlots)
            ?: return@LaunchedEffect
        val playheadBottom = globalToDesign(position.x, position.bottomY, pages, pageSlots)
            ?: return@LaunchedEffect
        offset = scorePlayheadFollowOffset(
            currentOffset = offset,
            playheadTop = playheadTop,
            playheadBottom = playheadBottom,
            viewportSize = viewportSize,
            scale = scale,
            density = screenDensity,
        )
    }
    compositionCheckpoint("interaction+page")

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MeconColors.SurfaceDark.copy(alpha = 0.3f)),
        contentAlignment = Alignment.TopCenter
    ) {
        // Score paper — fills entire middle area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .shadow(8.dp, RoundedCornerShape(4.dp))
                .clip(RoundedCornerShape(4.dp))
                // In paginated mode the area is a "canvas" behind the individual white page
                // sheets, so use the app's base background for separation. Continuous mode keeps
                // the single white paper filling the area.
                .background(
                    when {
                        isReference -> MeconColors.ScoreBackground
                        paginatedView -> MeconColors.Background
                        else -> MeconColors.White
                    }
                )
                .then(
                    if (isReference) Modifier.border(
                        width = 4.dp,
                        color = MeconColors.Surface,
                        shape = RoundedCornerShape(4.dp)
                    ) else Modifier
                )
        ) {
            if (showViewLabel) {
                val labelText = if (isReference) i18n("score.reference") else i18n("score.main")
                Text(
                    text = labelText,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(MeconColors.Surface, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    fontSize = 9.sp,
                    color = MeconColors.TextSecondary
                )
            }

            HiddenStaffMenuHost(
                options = hiddenStaffMenu,
                position = hiddenStaffMenuPos,
                maxMeasure = score?.measures?.maxOfOrNull { it.value.number } ?: 0,
                onReveal = { ids, range -> onRevealStaff(ids, range); hiddenStaffMenu = null },
                onDismiss = { hiddenStaffMenu = null },
            )

            if (renderResult != null || streamedPages.isNotEmpty()) {
                // Rendered score canvas with GPU-accelerated zoom/pan via graphicsLayer
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                        .clipToBounds()
                        .onSizeChanged { viewportSize = Size(it.width.toFloat(), it.height.toFloat()) }
                        .pointerHoverIcon(
                            PointerIcon(
                                Cursor(
                                    if (annotationResizeHovered || annotationRangeDrag != null) {
                                        Cursor.E_RESIZE_CURSOR
                                    } else {
                                        Cursor.DEFAULT_CURSOR
                                    }
                                )
                            )
                        )
                        .pointerInput(
                            renderResultIdentityKey,
                            paginatedView,
                            resizableAnnotationEventIds,
                        ) {
                            val result = renderResult ?: run {
                                annotationResizeHovered = false
                                return@pointerInput
                            }
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    if (event.type == PointerEventType.Exit) {
                                        annotationResizeHovered = false
                                        continue
                                    }
                                    if (event.type != PointerEventType.Move) continue
                                    val raw = event.changes.firstOrNull()?.position ?: continue
                                    val point = rawToAbsolutePoint(
                                        raw,
                                        offset,
                                        scale,
                                        density,
                                        paginatedView,
                                        pages,
                                        pageSlots,
                                    )
                                    val hovered = point != null && annotationRangeEndpointAt(
                                        result = result,
                                        point = point,
                                        resizableEventIds = resizableAnnotationEventIds,
                                        radius = 10f / scale,
                                    ) != null
                                    if (hovered != annotationResizeHovered) {
                                        annotationResizeHovered = hovered
                                    }
                                }
                            }
                        }
                        // Scroll-wheel zoom (zoom towards pointer position)
                        .pointerInput(zoomEnabled) {
                            if (!zoomEnabled) return@pointerInput
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Scroll) {
                                        val scrollDelta = event.changes.firstOrNull()
                                            ?.scrollDelta?.y ?: 0f
                                        if (scrollDelta != 0f) {
                                            // Zoom factor: scroll up = zoom in, scroll down = zoom out
                                            val zoomFactor = if (scrollDelta < 0) 1.08f else 1f / 1.08f
                                            val newScale = (scale * zoomFactor).coerceIn(0.25f, 5f)
                                            // Zoom towards the pointer position
                                            val pointerPos = event.changes.first().position
                                            val scaleChange = newScale / scale
                                            offset = Offset(
                                                pointerPos.x - scaleChange * (pointerPos.x - offset.x),
                                                pointerPos.y - scaleChange * (pointerPos.y - offset.y)
                                            )
                                            scale = newScale
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                }
                            }
                        }
                        // Track the shift modifier on the Initial pass so the tap / marquee
                        // selection handlers (whose gesture callbacks lack modifier info) can
                        // read a current value.
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    shiftHeld = event.keyboardModifiers.isShiftPressed
                                    ctrlHeld = event.keyboardModifiers.isCtrlPressed
                                }
                            }
                        }
                        .pointerInput(renderResultIdentityKey, selectorRegions) {
                            if (selectorRegions.isEmpty()) return@pointerInput
                            awaitEachGesture {
                                // Claim selector presses during the Initial pass so the score's
                                // marquee/drag handlers cannot win the same gesture.
                                val down = awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial,
                                )
                                val design = Offset(
                                    (down.position.x - offset.x) / scale / density,
                                    (down.position.y - offset.y) / scale / density,
                                )
                                val point = if (paginatedView) {
                                    designToGlobal(design, pages, pageSlots)
                                } else {
                                    AbsolutePoint(Pixels(design.x), Pixels(design.y))
                                }
                                val hit = point?.let { p ->
                                    selectorRegions.firstOrNull { p in it.bounds }
                                }
                                if (hit != null) {
                                    down.consume()
                                    val up = waitForUpOrCancellation(PointerEventPass.Initial)
                                    up?.consume()
                                    if (up != null) currentOnStaffSelector(hit.choice.key)
                                }
                            }
                        }
                        // Right-click over a hidden dashed line / grey cell opens the reveal-staff menu.
                        .pointerInput(renderResultIdentityKey, paginatedView) {
                            val result = renderResult ?: return@pointerInput
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    if (event.type != PointerEventType.Press ||
                                        !event.buttons.isSecondaryPressed) continue
                                    val change = event.changes.firstOrNull() ?: continue
                                    val designX = (change.position.x - offset.x) / scale / density
                                    val designY = (change.position.y - offset.y) / scale / density
                                    val point = if (paginatedView) {
                                        designToGlobal(Offset(designX, designY), pages, pageSlots)
                                    } else AbsolutePoint(Pixels(designX), Pixels(designY))
                                    val options = if (showEditorMarkers) {
                                        point?.let { buildHiddenStaffMenu(result, score, it) }
                                    } else null
                                    hiddenStaffMenu = options
                                    if (options != null) {
                                        hiddenStaffMenuPos = change.position
                                        change.consume()
                                    }
                                }
                            }
                        }
                        .scoreDragGestures(
                            DragGestureRequest(
                                frame = DragGestureFrame(
                                    resultIdentityKey = renderResultIdentityKey,
                                    result = renderResult,
                                    pages = pages,
                                    pageSlots = pageSlots,
                                    paginated = paginatedView,
                                ),
                                document = DragGestureDocument(
                                    score = score,
                                    currentScore = currentScore,
                                    computed = currentComputed,
                                    engine = renderEngine,
                                    beamGeometry = beamGeometry,
                                    selectedBeamControls = selectedBeamControls,
                                ),
                                mode = DragGestureMode(
                                    insertionToolActive = insertionToolActive,
                                    panEnabled = panEnabled,
                                    readOnly = readOnly,
                                    noteTool = noteTool,
                                    marqueeSelectableTypes = marqueeSelectableTypes,
                                    resizableAnnotationEventIds = resizableAnnotationEventIds,
                                ),
                                actions = DragGestureActions(
                                    selection = DragSelectionActions(
                                        selectionChange = currentOnSelectionChange,
                                        auditionNote = currentOnAuditionNote,
                                        selectAnnotationEvent = onSelectAnnotationEvent,
                                        resizeAnnotationRange = onResizeAnnotationRange,
                                    ),
                                    notes = DragNoteMovementActions(
                                        transpose = currentOnTranspose,
                                        moveRest = currentOnMoveRest,
                                        moveBeam = currentOnMoveBeam,
                                    ),
                                    expressions = DragExpressionMovementActions(
                                        moveAttachment = currentOnMoveAttachment,
                                        adjustTieCurve = currentOnAdjustTieCurve,
                                        adjustSlurCurve = currentOnAdjustSlurCurve,
                                    ),
                                    structure = DragStructuralMovementActions(
                                        resizeSecondVolta = currentOnResizeSecondVolta,
                                        resizeFirstVoltaStart = currentOnResizeFirstVoltaStart,
                                        moveNavigationMark = currentOnMoveNavigationMark,
                                    ),
                                ),
                                state = DragGestureState(
                                    viewport = DragViewportState(
                                        getOffset = { offset },
                                        setOffset = { offset = it },
                                        getScale = { scale },
                                        getCtrlHeld = { ctrlHeld },
                                        getShiftHeld = { shiftHeld },
                                        getFollowPlayback = { followPlayback },
                                        setFollowPlayback = { followPlayback = it },
                                    ),
                                    selection = DragSelectionState(
                                        getSelection = { currentSelection },
                                        getMarquee = { marqueeRect },
                                        setMarquee = { marqueeRect = it },
                                    ),
                                    previews = DragPreviewState(
                                        getTranspose = { transposeDrag },
                                        setTranspose = { transposeDrag = it },
                                        getBeam = { beamDrag },
                                        setBeam = { beamDrag = it },
                                        getAttachment = { attachmentDrag },
                                        setAttachment = { attachmentDrag = it },
                                        getVolta = { voltaDrag },
                                        setVolta = { voltaDrag = it },
                                        getNavigation = { navigationDrag },
                                        setNavigation = { navigationDrag = it },
                                        getCurve = { curveDrag },
                                        setCurve = { curveDrag = it },
                                        getAnnotationRange = { annotationRangeDrag },
                                        setAnnotationRange = { annotationRangeDrag = it },
                                    ),
                                ),
                            )
                        )
                        .scoreSelectionGestures(
                            SelectionGestureRequest(
                                frame = SelectionGestureFrame(
                                    resultIdentityKey = renderResultIdentityKey,
                                    arrangement = arrangement,
                                    result = renderResult,
                                    paginated = paginatedView,
                                    pages = pages,
                                    pageSlots = pageSlots,
                                    editorMarkers = editorMarkers,
                                ),
                                document = SelectionGestureDocument(
                                    score = currentScore,
                                    computed = currentComputed,
                                ),
                                mode = SelectionGestureMode(
                                    insertionToolActive = insertionToolActive,
                                    showEditorMarkers = showEditorMarkers,
                                ),
                                state = SelectionGestureState(
                                    offset = { offset },
                                    scale = { scale },
                                    shiftHeld = { shiftHeld },
                                    selection = { currentSelection },
                                ),
                                actions = SelectionGestureActions(
                                    selectionChange = currentOnSelectionChange,
                                    auditionNote = currentOnAuditionNote,
                                    selectAnnotationEvent = onSelectAnnotationEvent,
                                ),
                            )
                        )
                        .scoreInsertionGestures(
                            InsertionGestureRequest(
                                environment = InsertionGestureEnvironment(
                                    resultIdentityKey = renderResultIdentityKey,
                                    result = renderResult,
                                    computed = currentComputed,
                                    runtime = score,
                                    engine = renderEngine,
                                    paginated = paginatedView,
                                    pages = pages,
                                    pageSlots = pageSlots,
                                    offset = { offset },
                                    scale = { scale },
                                    density = screenDensity,
                                ),
                                tool = noteTool,
                                actions = InsertionGestureActions(
                                    insertNote = onInsertNote,
                                    insertClef = onInsertClef,
                                    insertTimeSignature = onInsertTimeSignature,
                                    insertKeySignature = onInsertKeySignature,
                                    insertBarline = onInsertBarline,
                                    insertRepeatStructure = onInsertRepeatStructure,
                                    insertDynamic = onInsertDynamic,
                                    insertPauseMark = onInsertPauseMark,
                                    insertExpressionSpan = onInsertExpressionSpan,
                                    insertTempo = onInsertTempo,
                                    insertTempoSpan = onInsertTempoSpan,
                                    insertOrnament = onInsertOrnament,
                                    insertArpeggio = onInsertArpeggio,
                                ),
                                previews = InsertionGesturePreviews(
                                    note = { ghost = it },
                                    clef = { clefGhost = it },
                                    timeSignature = { timeGhost = it },
                                    keySignature = { keyGhost = it },
                                    expressionSpan = { expressionSpanGhost = it },
                                ),
                            )
                        )
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                        }
                ) {
                    drawRenderedScore(
                        RenderedScoreCanvasDrawRequest(
                            render = RenderedScoreRenderFrame(
                                renderer = composeRenderer,
                                result = renderResult,
                                textMeasurer = textMeasurer,
                                styleSnapshot = displayStyleSnapshot,
                                density = density,
                            ),
                            page = RenderedScorePageFrame(
                                pages = pages,
                                pageSlots = pageSlots,
                                markersByPage = editorMarkersByPage,
                                continuousMarkers = editorMarkers,
                                stalePageIndices = stalePageIndices,
                                paginated = paginatedView,
                                showEditorMarkers = showEditorMarkers,
                                preparingLoadedDocument = preparingLoadedDocument,
                            ),
                            selection = RenderedScoreSelectionOverlay(
                                score = score,
                                selection = selection,
                                highlightedElements = highlightedElements,
                                selectedAnnotationEventId = selectedAnnotationEventId,
                                resizableAnnotationEventIds = resizableAnnotationEventIds,
                                selectedBeamSection = selectedBeamSection,
                                selectedBeamControls = selectedBeamControls,
                                selectedVoltaSection = selectedVoltaSection,
                                selectedVoltaElements = selectedVoltaElements,
                                selectedNavigationElements = selectedNavigationElements,
                                selectedAttachmentSection = selectedAttachmentSection,
                                selectedAttachmentElements = selectedAttachmentElements,
                                noteheadBackgroundGroups = noteheadBackgroundGroups,
                                noteheadCenterMarkers = noteheadCenterMarkers,
                                noteSelectionLabels = noteSelectionLabels,
                            ),
                            ghosts = RenderedScoreGhostOverlay(
                                note = ghost,
                                clef = clefGhost,
                                timeSignature = timeGhost,
                                keySignature = keyGhost,
                                expressionSpan = expressionSpanGhost,
                                color = ghostColor,
                            ),
                            drags = RenderedScoreDragOverlay(
                                transpose = transposeDrag,
                                transposeCommitted = committedFrameDisplayed,
                                beam = beamDrag,
                                beamCommitted = beamCommittedFrameDisplayed,
                                attachment = attachmentDrag,
                                attachmentCommitted = attachmentCommittedFrameDisplayed,
                                volta = voltaDrag,
                                navigation = navigationDrag,
                                navigationCommitted = navigationCommittedFrameDisplayed,
                                curve = curveDrag,
                                curveCommitted = curveCommittedFrameDisplayed,
                                annotationRange = annotationRangeDrag,
                                selectionColor = selectionFillColor,
                            ),
                            playback = RenderedScorePlaybackOverlay(
                                state = playbackState,
                                tickToX = tickToXMapping,
                                positionTicks = scorePositionTicks,
                            ),
                            lifecycle = RenderedScoreDrawLifecycle(
                                documentVersion = documentVersion,
                                readyFrameScheduledVersion = readyFrameScheduledVersion,
                                scope = scope,
                                onDocumentInteractive = currentOnDocumentInteractive,
                            ),
                            scale = scale,
                            offset = offset,
                        )
                    )
                    drawStaffSelectors(
                        regions = selectorRegions,
                        textMeasurer = textMeasurer,
                        density = density,
                        paginated = paginatedView,
                        pages = pages,
                        pageSlots = pageSlots,
                    )
                }

                // Marquee rubber-band overlay — drawn in raw pointer space (outside the zoom/pan
                // transform, matching the padded Canvas origin) so its border keeps a constant
                // width at any zoom.
                marqueeRect?.let { r ->
                    Canvas(modifier = Modifier.fillMaxSize().padding(contentPadding).clipToBounds()) {
                        drawRect(
                            color = MeconColors.voiceSelectionColor(1).copy(alpha = 0.13f),
                            topLeft = r.topLeft,
                            size = r.size,
                        )
                        drawRect(
                            color = MeconColors.voiceSelectionColor(1),
                            topLeft = r.topLeft,
                            size = r.size,
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }
                }

                // Selection details now live in the right panel's "selection properties" section.
            } else {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = i18n("score.empty"),
                        fontSize = 14.sp,
                        color = MeconColors.TextMuted
                    )
                }
            }

            RenderedScoreStatusOverlays(
                interactionBlocked = commitInteractionBlocked || interactionBlocked,
                documentLoading = documentLoading,
                showRenderUpdatingLabel = showRenderUpdatingLabel,
                scale = scale,
                showZoomIndicator = showZoomIndicator,
            )
        }
    }
    compositionCheckpoint("content")
}
