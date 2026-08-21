package com.mecon.desktop.ui.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.interaction.EventSection
import com.mecon.api.render.RenderColor
import com.mecon.desktop.ui.components.EditTool
import com.mecon.desktop.ui.views.drag.ScoreDragPreviewState
import com.mecon.desktop.ui.views.drag.rememberScoreDragCommitHold
import com.mecon.desktop.ui.views.drag.rememberScoreDragHideSnapshot
import com.mecon.desktop.ui.views.drag.scoreDragGestures
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.renderer.interaction.StyleSnapshot
import java.awt.Cursor

/**
 * The interactive score surface: renders a [com.mecon.api.runtime.RuntimeScore], hit-tests it, and
 * hosts every editing gesture.
 *
 * This composable owns *composition* only — what state exists, in what order it is derived, and how
 * the surface is laid out. The work behind it lives in focused neighbours so each concern can be
 * reasoned about (and kept off the UI thread) on its own:
 *
 * | Concern | Lives in |
 * |---------|----------|
 * | engine, streaming render, pagination | [rememberScoreRenderFrame] |
 * | selection colour via engine style tracks | [ScoreSelectionStyleTracks] |
 * | selection handles and canvas decorations | [rememberScoreSelectionOverlay] |
 * | release → committed-frame hand-off, and the hide that goes with it | [rememberScoreDragCommitHold], [rememberScoreDragHideSnapshot] |
 * | playhead position and following | [rememberScorePlaybackMapping], [ScorePlayheadFollowEffect] |
 * | state → subsystem request wiring | [RenderedScoreViewWiring.kt][dragGestureRequest] |
 * | drawing | [drawRenderedScore] |
 *
 * The four pointer layers stack in a fixed order, outermost first: [scoreAmbientGestures] (zoom,
 * modifiers, selectors, context menu), [scoreDragGestures] (pan, marquee and every semantic handle),
 * [scoreSelectionGestures] (tap to select) and [scoreInsertionGestures] (the pens). An active pen
 * makes the two middle layers stand down entirely — see [INSERTION_TOOLS].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RenderedScoreView(
    config: RenderedScoreViewConfig,
    modifier: Modifier = Modifier,
) {
    val source = config.source
    val selectionConfig = config.selectionConfig
    val display = config.display
    val lifecycle = config.lifecycle
    val staffSelectors = config.staffSelectors
    val score = source.score
    val computed = source.computed
    val selection = selectionConfig.selection

    val perf = rememberCompositionPhaseProbe(display.isReference, display.readOnly)
    val textMeasurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    val screenDensity = LocalDensity.current.density
    val currentOnDocumentInteractive by rememberUpdatedState(lifecycle.onDocumentInteractive)

    val frame = rememberScoreRenderFrame(
        score = score,
        computed = computed,
        renderHint = source.renderHint,
        documentVersion = source.documentVersion,
        display = display,
        lifecycle = lifecycle,
    )
    perf.checkpoint("pipeline")

    val overlay = rememberScoreSelectionOverlay(frame, computed, selection, selectionConfig, display)
    val selectorRegions = remember(
        frame.identityKey,
        frame.scoreIdentityKey,
        staffSelectors.choicesByStaffId,
    ) {
        frame.result?.let { staffSelectorRegions(it, score, staffSelectors) }.orEmpty()
    }
    ScoreSelectionStyleTracks(
        selection = selection,
        localEventStyles = selectionConfig.localEventStyles,
        score = score,
        engine = frame.engine,
        result = frame.result,
        resultIdentityKey = frame.identityKey,
        scoreIdentityKey = frame.scoreIdentityKey,
    )

    // Drag previews, the hold that keeps them on screen until the committed frame lands, and the
    // view-local snapshot that hides whatever each preview is standing in for.
    val styleOverrideManager = frame.engine?.getStyleOverrideManager()
    val effectiveSnapshot by (
        styleOverrideManager?.snapshotFlow
            ?: kotlinx.coroutines.flow.MutableStateFlow(StyleSnapshot.EMPTY)
        ).collectAsState()
    val dragPreviews = remember { ScoreDragPreviewState() }
    val commitHold = rememberScoreDragCommitHold(dragPreviews, frame.result)
    val displayStyleSnapshot = rememberScoreDragHideSnapshot(
        base = effectiveSnapshot,
        previews = dragPreviews,
        hold = commitHold,
        selection = selection,
    )
    perf.checkpoint("selection+commit")

    val playback = rememberScorePlaybackMapping(
        score = score,
        frame = frame,
        playbackState = display.playbackState,
        currentPositionTicks = display.currentPositionTicks,
    )
    perf.checkpoint("playback")

    val viewport = remember { RenderedScoreViewportState() }
    ScorePlayheadFollowEffect(viewport, frame, playback, display.playbackState, screenDensity)
    LaunchedEffect(display.externalHorizontalOffsetPx) {
        display.externalHorizontalOffsetPx?.let { external ->
            viewport.scale.value = 1f
            viewport.offset.value = Offset(-external, viewport.offset.value.y)
        }
    }
    var annotationResizeHovered by remember { mutableStateOf(false) }
    // Right-click over a hidden dashed line / grey cell: the resolved per-staff reveal options and
    // the screen position to anchor the menu at.
    var hiddenStaffMenu by remember { mutableStateOf<List<HiddenStaffMenuOption>?>(null) }
    var hiddenStaffMenuPos by remember { mutableStateOf(Offset.Zero) }

    // Always-fresh views of the selection inputs so the long-lived pointer coroutines don't act on
    // a stale snapshot captured when the gesture started.
    val currentSelection by rememberUpdatedState(selection)
    val latestOnSelectionChange by rememberUpdatedState(selectionConfig.onSelectionChange)
    val latestSelectableSection by rememberUpdatedState(selectionConfig.selectableSection)
    val onSelectionChange: (Set<EventSection>) -> Unit = remember {
        { candidate -> latestOnSelectionChange(candidate.filterTo(linkedSetOf(), latestSelectableSection)) }
    }
    // The selector gesture only re-keys on the regions, so it must forward through a stable lambda
    // rather than capture the callback of the composition that installed it.
    val latestOnStaffSelector by rememberUpdatedState(staffSelectors.onSelect)
    val onStaffSelector: (String) -> Unit = remember { { key -> latestOnStaffSelector(key) } }

    val activeTool = config.edit.notation.noteTool?.tool
    val insertionToolActive = activeTool in INSERTION_TOOLS
    val insertionPreviews = remember { RenderedScoreInsertionPreviewState() }
    insertionPreviews.clearGhostsForInactiveTools(activeTool)

    LaunchedEffect(source.documentVersion) {
        dragPreviews.transpose.value = null
        viewport.scale.value = 1f
        viewport.offset.value = Offset(-(display.externalHorizontalOffsetPx ?: 0f), 0f)
        viewport.shiftHeld.value = false
        viewport.ctrlHeld.value = false
        viewport.marqueeRect.value = null
        insertionPreviews.clearGhostsForInactiveTools(activeTool = null)
    }
    perf.checkpoint("interaction+page")

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MeconColors.SurfaceDark.copy(alpha = 0.3f)),
        contentAlignment = Alignment.TopCenter,
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
                        display.isReference -> MeconColors.ScoreBackground
                        frame.paginated -> MeconColors.Background
                        else -> MeconColors.White
                    }
                )
                .then(
                    if (display.isReference) Modifier.border(
                        width = 4.dp,
                        color = MeconColors.Surface,
                        shape = RoundedCornerShape(4.dp),
                    ) else Modifier
                )
        ) {
            if (display.showViewLabel) {
                Text(
                    text = if (display.isReference) i18n("score.reference") else i18n("score.main"),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(MeconColors.Surface, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    fontSize = 9.sp,
                    color = MeconColors.TextSecondary,
                )
            }

            HiddenStaffMenuHost(
                options = hiddenStaffMenu,
                position = hiddenStaffMenuPos,
                maxMeasure = score?.measures?.maxOfOrNull { it.value.number } ?: 0,
                onReveal = { ids, range -> lifecycle.onRevealStaff(ids, range); hiddenStaffMenu = null },
                onDismiss = { hiddenStaffMenu = null },
            )

            if (frame.hasContent) {
                // Rendered score canvas with GPU-accelerated zoom/pan via graphicsLayer.
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(display.contentPadding)
                        .clipToBounds()
                        .onSizeChanged {
                            viewport.viewportSize.value = Size(it.width.toFloat(), it.height.toFloat())
                        }
                        .pointerHoverIcon(
                            PointerIcon(
                                Cursor(
                                    if (annotationResizeHovered ||
                                        dragPreviews.annotationRange.value != null
                                    ) Cursor.E_RESIZE_CURSOR else Cursor.DEFAULT_CURSOR
                                )
                            )
                        )
                        .scoreAmbientGestures(
                            ambientGestureRequest(
                                frame = frame,
                                score = score,
                                display = display,
                                selectionConfig = selectionConfig,
                                selectorRegions = selectorRegions,
                                viewport = viewport,
                                onStaffSelector = onStaffSelector,
                                onAnnotationResizeHover = { annotationResizeHovered = it },
                                onHiddenStaffMenu = { options, position ->
                                    hiddenStaffMenu = options
                                    if (options != null) hiddenStaffMenuPos = position
                                },
                            )
                        )
                        .scoreDragGestures(
                            dragGestureRequest(
                                frame = frame,
                                score = score,
                                computed = computed,
                                config = config,
                                overlay = overlay,
                                insertionToolActive = insertionToolActive,
                                viewport = viewport,
                                previews = dragPreviews,
                                selection = { currentSelection },
                                onSelectionChange = onSelectionChange,
                            )
                        )
                        .scoreSelectionGestures(
                            selectionGestureRequest(
                                frame = frame,
                                score = score,
                                computed = computed,
                                config = config,
                                insertionToolActive = insertionToolActive,
                                viewport = viewport,
                                selection = { currentSelection },
                                onSelectionChange = onSelectionChange,
                            )
                        )
                        .scoreInsertionGestures(
                            insertionGestureRequest(
                                frame = frame,
                                score = score,
                                computed = computed,
                                config = config,
                                viewport = viewport,
                                density = screenDensity,
                                previews = insertionPreviews,
                            )
                        )
                        .graphicsLayer {
                            scaleX = viewport.scale.value
                            scaleY = viewport.scale.value
                            translationX = viewport.offset.value.x
                            translationY = viewport.offset.value.y
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                ) {
                    drawRenderedScore(
                        scoreCanvasDrawRequest(
                            frame = frame,
                            score = score,
                            config = config,
                            selection = selection,
                            overlay = overlay,
                            styleSnapshot = displayStyleSnapshot,
                            textMeasurer = textMeasurer,
                            density = density,
                            insertionPreviews = insertionPreviews,
                            dragPreviews = dragPreviews,
                            commitHold = commitHold,
                            playback = playback,
                            viewport = viewport,
                            scope = scope,
                            onDocumentInteractive = currentOnDocumentInteractive,
                        )
                    )
                    drawStaffSelectors(
                        regions = selectorRegions,
                        textMeasurer = textMeasurer,
                        density = density,
                        paginated = frame.paginated,
                        pages = frame.pages,
                        pageSlots = frame.pageSlots,
                    )
                }

                // Marquee rubber-band overlay — drawn in raw pointer space (outside the zoom/pan
                // transform, matching the padded Canvas origin) so its border keeps a constant
                // width at any zoom.
                viewport.marqueeRect.value?.let { rect ->
                    Canvas(
                        modifier = Modifier.fillMaxSize().padding(display.contentPadding).clipToBounds()
                    ) {
                        drawRect(
                            color = MeconColors.voiceSelectionColor(1).copy(alpha = 0.13f),
                            topLeft = rect.topLeft,
                            size = rect.size,
                        )
                        drawRect(
                            color = MeconColors.voiceSelectionColor(1),
                            topLeft = rect.topLeft,
                            size = rect.size,
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                    }
                }
                // Selection details now live in the right panel's "selection properties" section.
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = i18n("score.empty"),
                        fontSize = 14.sp,
                        color = MeconColors.TextMuted,
                    )
                }
            }

            RenderedScoreStatusOverlays(
                interactionBlocked = commitHold.interactionBlocked || lifecycle.interactionBlocked,
                documentLoading = lifecycle.documentLoading,
                showRenderUpdatingLabel = frame.showUpdatingLabel,
                scale = viewport.scale.value,
                showZoomIndicator = display.showZoomIndicator,
            )
        }
    }
    perf.checkpoint("content")
}

/** Drop any ghost whose pen is no longer the active tool. */
private fun RenderedScoreInsertionPreviewState.clearGhostsForInactiveTools(activeTool: EditTool?) {
    if (activeTool != EditTool.NOTE && note.value != null) note.value = null
    if (activeTool != EditTool.CLEF && clef.value != null) clef.value = null
    if (activeTool != EditTool.TIME && timeSignature.value != null) timeSignature.value = null
    if (activeTool != EditTool.KEY && keySignature.value != null) keySignature.value = null
    if (activeTool !in EXPRESSION_SPAN_TOOLS && expressionSpan.value != null) {
        expressionSpan.value = null
    }
}

/**
 * Voice 1 remains the transient/default selection blue; persistent selection styles use the selected
 * section's own voice swatch.
 */
internal val SELECTION_FILL_COLOR: RenderColor = voiceSelectionRenderColor(1)

/** Translucent grey for the note-pen ghost preview. */
internal val GHOST_COLOR: RenderColor = RenderColor.rgba(138, 138, 138, 140)

private val EXPRESSION_SPAN_TOOLS = setOf(
    EditTool.HAIRPIN,
    EditTool.OCTAVE,
    EditTool.TEMPO_SPAN,
)

/**
 * Tools that own their own pointer handling. While one is active the drag and tap-selection layers
 * stand down entirely, so this set is the single definition of "the pen is out".
 *
 * Ornament and arpeggio are deliberately absent: they place onto an existing note, so ordinary
 * selection must keep working underneath them.
 */
private val INSERTION_TOOLS = EXPRESSION_SPAN_TOOLS + setOf(
    EditTool.NOTE,
    EditTool.CLEF,
    EditTool.TIME,
    EditTool.KEY,
    EditTool.DYNAMIC,
    EditTool.PAUSE,
    EditTool.TEMPO,
    EditTool.BARLINE,
    EditTool.REPEAT_STRUCTURE,
)

/**
 * Opt-in composition timing probe: reports each phase that took long enough to matter.
 *
 * `SideEffect`'s total can include sibling or parent composition from the same batch, so a single
 * number cannot separate renderer cost from view cost — see
 * [docs/performance/large-score-editing.md]. The per-phase checkpoints are what make attribution
 * possible.
 */
@Composable
private fun rememberCompositionPhaseProbe(
    isReference: Boolean,
    readOnly: Boolean,
): CompositionPhaseProbe {
    val probe = CompositionPhaseProbe()
    SideEffect {
        com.mecon.renderer.debug.PerfLog.log("compose.score") {
            "composition=${probe.elapsedMsSinceStart()}ms reference=$isReference readOnly=$readOnly"
        }
    }
    return probe
}

private class CompositionPhaseProbe {
    private val startedAt = System.nanoTime()
    private var lastCheckpointAt = startedAt

    fun checkpoint(name: String) {
        val now = System.nanoTime()
        val elapsedMs = (now - lastCheckpointAt) / 1_000_000
        if (elapsedMs >= MIN_REPORTED_MS) {
            com.mecon.renderer.debug.PerfLog.log("compose.score.phase") { "$name=${elapsedMs}ms" }
        }
        lastCheckpointAt = now
    }

    fun elapsedMsSinceStart(): Long = (System.nanoTime() - startedAt) / 1_000_000

    private companion object { const val MIN_REPORTED_MS = 5L }
}
