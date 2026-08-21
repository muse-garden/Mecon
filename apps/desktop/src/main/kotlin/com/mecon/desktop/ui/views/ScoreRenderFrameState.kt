package com.mecon.desktop.ui.views

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.mecon.api.computed.ComputedScore
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.state.RenderHint
import com.mecon.renderer.layout.AlignedTimeAxisRequest
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.render.RenderElement
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderEngine
import com.mecon.renderer.render.RenderPage
import com.mecon.renderer.render.RenderResult
import kotlinx.coroutines.launch

/**
 * Only publish a resolved axis, or an explicit null when nothing requested alignment.
 *
 * A keyed background render temporarily exposes a null axis even though the previous canvas frame is
 * still visible. An aligned host must keep reporting that frame's axis for the same interval, or
 * sibling timelines fall back to their own coordinate systems and visibly jump.
 */
internal fun shouldPublishResolvedTimeAxis(
    request: AlignedTimeAxisRequest?,
    nextAxis: com.mecon.renderer.layout.ResolvedTimeAxis?,
): Boolean = nextAxis != null || request == null

/**
 * One rendered generation of the score, ready to hit-test, draw and page.
 *
 * Layout and rendering run off the UI thread, so what the view shows at any moment is a mix of the
 * last settled [result] and pages streamed in by the render currently in flight. This holder is the
 * single place that mix is resolved: [pages] already merges them, and [stalePageIndices] marks the
 * ones still showing the previous frame.
 */
internal class ScoreRenderFrame(
    val engine: RenderEngine?,
    val composeRenderer: ComposeScoreRenderer?,
    val result: RenderResult?,
    /** O(1) effect key. Never key on [result] itself: equality would walk every element. */
    val identityKey: Long,
    val scoreIdentityKey: Long,
    val computedIdentityKey: Long,
    val pages: List<RenderPage>,
    val pageSlots: List<Offset>,
    val paginated: Boolean,
    val editorMarkersByPage: Map<Int, List<RenderElement>>,
    val editorMarkers: List<RenderElement>,
    val stalePageIndices: Set<Int>,
    /** A render has been in flight long enough to be worth telling the user about. */
    val showUpdatingLabel: Boolean,
    /** This view is still assembling the document the owner reports as loading. */
    val preparingLoadedDocument: Boolean,
    /**
     * Plain box, not Compose state: the draw pass writes it to fire the interaction-ready callback
     * exactly once per document, and must not schedule a recomposition doing so.
     */
    val readyFrameScheduledVersion: LongArray,
) {
    /** Something is on screen — a settled frame or at least one streamed page. */
    val hasContent: Boolean get() = result != null || pages.isNotEmpty()
}

/**
 * Build and maintain the score's render frame: engine, streaming render, pagination slots.
 *
 * Also publishes the resolved time axis to aligned hosts, and re-applies note styles when the
 * display toggles change without a full re-render.
 */
@Composable
internal fun rememberScoreRenderFrame(
    score: RuntimeScore?,
    computed: ComputedScore?,
    renderHint: RenderHint?,
    documentVersion: Long,
    display: RenderedScoreDisplayConfig,
    lifecycle: RenderedScoreLifecycleConfig,
): ScoreRenderFrame {
    // Blank-bar padding is a view policy. Aligned workbenches disable it because the external ruler
    // already guarantees an editable width and must remain the sole horizontal spacing authority.
    val alignedTimeAxisIdentityKey = rememberIdentityKey(display.alignedTimeAxisRequest)
    val editConfig = remember(
        alignedTimeAxisIdentityKey,
        display.padEmptyMeasures,
        display.firstSystemIndent,
    ) {
        RenderLayoutConfig.DEFAULT.copy(
            padEmptyMeasures = display.padEmptyMeasures,
            firstSystemIndent = display.firstSystemIndent ?: RenderLayoutConfig.DEFAULT.firstSystemIndent,
            alignedTimeAxisRequest = display.alignedTimeAxisRequest,
        )
    }
    // Shared rendering pipeline (font + engine + Compose renderer + serial dispatcher). Null until
    // the Bravura font finishes loading. The same helper backs the lightweight SimpleScoreView;
    // this view layers pagination, hit-testing and interaction on top.
    val renderer = rememberScoreRenderer(editConfig)
    val scope = rememberCoroutineScope()
    val scoreIdentityKey = rememberIdentityKey(score)
    val computedIdentityKey = rememberIdentityKey(computed)

    // Streaming state: pages arrive progressively from renderStreaming's onPage callback.
    // streamedPages maps pageIndex → the latest page emitted; stalePageIndices is the set of
    // pages that existed before the current in-flight render began (shown with a grey overlay
    // until replaced). Both are cleared once the settled RenderResult arrives.
    val streamedPages = remember { mutableStateMapOf<Int, RenderPage>() }
    var stalePageIndices by remember { mutableStateOf(emptySet<Int>()) }
    var renderInFlight by remember { mutableStateOf(false) }
    var showUpdatingLabel by remember { mutableStateOf(false) }

    val readyFrameScheduledVersion = remember { longArrayOf(Long.MIN_VALUE) }
    val preparingLoadedDocument =
        lifecycle.documentLoading && lifecycle.loadingDocumentVersion == documentVersion
    LaunchedEffect(lifecycle.documentLoading, lifecycle.loadingDocumentVersion, documentVersion) {
        if (lifecycle.documentLoading) readyFrameScheduledVersion[0] = Long.MIN_VALUE
    }

    // When a new render starts (keys match those of rememberRenderResult), mark the currently
    // displayed streaming pages stale so the view shows them dimmed until new versions arrive.
    LaunchedEffect(
        renderer,
        scoreIdentityKey,
        computedIdentityKey,
        display.renderRefreshKey,
        lifecycle.interactionBlocked,
    ) {
        stalePageIndices = streamedPages.keys.toSet()
        if (renderer == null || (score == null && computed == null)) return@LaunchedEffect
        // Structural edits flip interactionBlocked before their off-thread compute starts. Preserve the
        // same generation when computed later changes, so the label covers compute + layout + anchor-page
        // rendering without disappearing/restarting its delay between phases.
        if (!renderInFlight) {
            renderInFlight = true
            showUpdatingLabel = false
            kotlinx.coroutines.delay(lifecycle.commitUpdatingDelayMs)
            if (renderInFlight) showUpdatingLabel = true
        }
    }

    val result = rememberRenderResult(
        renderer,
        score,
        computed = computed,
        renderHint = renderHint,
        documentVersion = documentVersion,
        onGeometryCaptured = lifecycle.onGeometryCaptured,
        renderRefreshKey = display.renderRefreshKey,
        onPage = { pageIndex, page ->
            scope.launch {
                streamedPages[pageIndex] = page
                stalePageIndices = stalePageIndices - pageIndex
                // The anchor page arriving does not settle the render generation: the old RenderResult
                // still owns hit testing and commit hand-off until all pages have been assembled. Keep
                // the updating state alive so the delayed label gets at least one drawable frame and
                // accurately covers the interval in which commit interaction remains guarded.
            }
        },
    )
    val identityKey = rememberIdentityKey(result)

    // Once the full, settled RenderResult arrives, clear the transient streaming state — the
    // view switches to result.pages as the authoritative source.
    LaunchedEffect(identityKey) {
        if (result != null) {
            streamedPages.clear()
            stalePageIndices = emptySet()
            renderInFlight = false
            showUpdatingLabel = false
        }
    }

    val currentOnResolvedTimeAxis by rememberUpdatedState(display.onResolvedTimeAxis)
    LaunchedEffect(identityKey, alignedTimeAxisIdentityKey) {
        val nextAxis = result?.resolvedTimeAxis
        // A keyed background render temporarily exposes null even though the previous canvas frame
        // remains visible. An aligned host must retain that frame's axis for the same interval, or
        // sibling timelines fall back to their independent coordinate systems and visibly jump.
        if (shouldPublishResolvedTimeAxis(display.alignedTimeAxisRequest, nextAxis)) {
            currentOnResolvedTimeAxis(nextAxis)
        }
    }

    // Re-apply note styles when the key increments (toggle changed) without a full re-render.
    // Shares the pipeline dispatcher so it is serialized against the render above.
    LaunchedEffect(display.noteStyleRefreshKey, renderer) {
        val active = renderer ?: return@LaunchedEffect
        kotlinx.coroutines.withContext(active.dispatcher) { active.engine.reapplyNoteStyles() }
    }

    // Paginated mode: per-page draw slots (design px) computed from the page sizes and the
    // chosen arrangement. Empty in continuous mode → the single-canvas path is used.
    val settledPages = result?.pages.orEmpty()
    val pages = if (streamedPages.isNotEmpty()) {
        // produceState keeps the previous complete result during a new render. Overlay pages as they
        // stream in so the anchor page is visible immediately, while untouched pages remain as the old
        // frame until their replacements arrive.
        (settledPages.associateBy { it.pageIndex } + streamedPages).values.sortedBy { it.pageIndex }
    } else settledPages
    val paginated = (result?.paginated == true || streamedPages.isNotEmpty()) && pages.isNotEmpty()

    return ScoreRenderFrame(
        engine = renderer?.engine,
        composeRenderer = renderer?.composeRenderer,
        result = result,
        identityKey = identityKey,
        scoreIdentityKey = scoreIdentityKey,
        computedIdentityKey = computedIdentityKey,
        pages = pages,
        pageSlots = remember(pages, display.arrangement) {
            pageSlotOffsets(pages, display.arrangement)
        },
        paginated = paginated,
        editorMarkersByPage = remember(pages) {
            pages.associate { page ->
                page.pageIndex to page.elements.filter { it.type == RenderElementType.EDITOR_MARKER }
            }
        },
        editorMarkers = remember(identityKey) {
            result?.elements?.filter { it.type == RenderElementType.EDITOR_MARKER }.orEmpty()
        },
        stalePageIndices = stalePageIndices,
        showUpdatingLabel = showUpdatingLabel,
        preparingLoadedDocument = preparingLoadedDocument,
        readyFrameScheduledVersion = readyFrameScheduledVersion,
    )
}
