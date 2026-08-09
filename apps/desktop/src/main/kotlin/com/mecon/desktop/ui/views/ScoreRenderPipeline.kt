package com.mecon.desktop.ui.views

import androidx.compose.runtime.*
import com.mecon.api.computed.ComputedScore
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.state.RenderHint
import com.mecon.desktop.ui.rememberBravuraFont
import com.mecon.renderer.layout.PageGeometry
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.render.RenderEngine
import com.mecon.renderer.render.RenderPage
import com.mecon.renderer.render.RenderResult
import com.mecon.renderer.smufl.BravuraFont
import com.mecon.renderer.smufl.BravuraFontLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Shared score-rendering pipeline for the desktop UI.
 *
 * Bundles the three pieces every score view needs — the [RenderEngine] (built with the
 * Bravura metadata context), the [ComposeScoreRenderer] that paints [RenderResult]s onto a
 * Compose canvas, and a serial background [dispatcher] that keeps layout/render off the UI
 * thread. Both the interactive [RenderedScoreView] and the lightweight, non-interactive
 * [SimpleScoreView] obtain their engine through [rememberScoreRenderer] so the setup lives in
 * one place.
 */
class ScoreRenderer internal constructor(
    val engine: RenderEngine,
    val composeRenderer: ComposeScoreRenderer,
    /**
     * Serial dispatcher (limitedParallelism(1)) shared by every engine mutation. Layout+render
     * and reapplyNoteStyles() both write the engine's shared note-style track, so they must run
     * serially off the UI thread to avoid a data race.
     */
    val dispatcher: kotlinx.coroutines.CoroutineDispatcher
)

/**
 * O(1) identity token for large immutable frame objects used as Compose effect keys. Passing a
 * RuntimeScore / ComputedScore / RenderResult directly makes Compose call their data-class equals,
 * which can scan tens of thousands of events/elements before finding a late-score pitch change.
 */
@Composable
internal fun rememberIdentityKey(value: Any?): Long {
    val holder = remember { IdentityKeyHolder() }
    return holder.keyFor(value)
}

/**
 * [rememberUpdatedState] uses structural equality. That is a poor fit for immutable score frames:
 * changing one late event can make equality walk tens of thousands of events on the UI thread before
 * the pointer coroutine receives the new value. Keep the same API shape while comparing by identity.
 */
@Composable
internal fun <T> rememberReferentialUpdatedState(value: T): State<T> {
    val state = remember { mutableStateOf(value, referentialEqualityPolicy()) }
    state.value = value
    return state
}

private class IdentityKeyHolder {
    private var value: Any? = UNSET
    private var generation = 0L

    fun keyFor(next: Any?): Long {
        if (value !== next) {
            value = next
            generation += 1
        }
        return generation
    }

    private companion object { val UNSET = Any() }
}

/** Referential wrapper: produceState otherwise structurally compares two 60k-element results. */
private class RenderResultPublication(val result: RenderResult?)

// Bravura metadata is identical for every view and parsing it is not free, so load it once and
// share the parsed [BravuraFont] across all engines in the process.
private val bravuraFontMutex = Mutex()
@Volatile private var cachedBravuraFont: BravuraFont? = null

private suspend fun loadBravuraFontCached(): BravuraFont? {
    cachedBravuraFont?.let { return it }
    return bravuraFontMutex.withLock {
        cachedBravuraFont ?: BravuraFontLoader().load()?.also { cachedBravuraFont = it }
    }
}

/**
 * Remember a [ScoreRenderer] for the given layout [config]. Returns null until the Bravura font
 * has finished loading asynchronously; callers should render an empty/placeholder state meanwhile.
 */
@Composable
fun rememberScoreRenderer(
    config: RenderLayoutConfig = RenderLayoutConfig.DEFAULT
): ScoreRenderer? {
    val bravuraFontFamily = rememberBravuraFont()

    var loadedFont by remember { mutableStateOf<BravuraFont?>(null) }
    LaunchedEffect(Unit) { loadedFont = loadBravuraFontCached() }

    val composeRenderer = remember(bravuraFontFamily) { ComposeScoreRenderer(bravuraFontFamily) }
    val dispatcher = remember { Dispatchers.Default.limitedParallelism(1) }

    val configIdentityKey = rememberIdentityKey(config)
    val engine = remember(loadedFont, configIdentityKey) {
        loadedFont?.let { font -> with(font) { RenderEngine(config) } }
    }

    return remember(engine, composeRenderer, dispatcher) {
        engine?.let { ScoreRenderer(it, composeRenderer, dispatcher) }
    }
}

/**
 * Run layout + render off the UI thread, retaining the previous result while a new render is in flight
 * so the canvas never flashes empty during recompute.
 *
 * Two render paths:
 *  - **Incremental** (when [computed] is provided): renders the already-computed [ComputedScore] via
 *    [RenderEngine.renderIncremental], reusing the previous frame's layout / elements when [renderHint]
 *    proves this is the displayed frame's direct successor. This is the editing path — an inserted note
 *    re-solves only the touched measures (docs/data_model/incremental-update.md). [renderHint] = null forces a full
 *    layout from [computed] (undo/redo, document load).
 *  - **Full from runtime** (when [computed] is null): the classic [RenderEngine.render] that computes
 *    and lays out [score] from scratch. Used by non-editing views (thumbnails, reference pane).
 *
 * @param pageGeometry Forces a specific page geometry; pass [PageGeometry.continuous] to render a
 *   single un-paginated system. When null, the engine derives geometry from the score's page layout.
 */
@Composable
fun rememberRenderResult(
    renderer: ScoreRenderer?,
    score: RuntimeScore?,
    pageGeometry: PageGeometry? = null,
    computed: ComputedScore? = null,
    renderHint: RenderHint? = null,
    /**
     * Bumped by the owner whenever the displayed document is loaded / replaced. Used to decide when to
     * drop the engine's cross-frame caches: only a real document change may reset. An undo/redo or
     * plugin-track update also arrives with a null [renderHint], but it stays within the SAME document,
     * so its cache must survive for [RenderEngine.renderIncremental]'s diff fallback to reuse it.
     */
    documentVersion: Long = 0,
    /**
     * Invoked after each successful render with the geometry captured from the engine
     * (anchor-relative storage form), so the owner can persist it. Null for views that
     * don't persist (thumbnails, reference pane). See [RenderEngine.captureGeometry].
     */
    onGeometryCaptured: ((com.mecon.api.storage.ScoreGeometry?) -> Unit)? = null,
    renderRefreshKey: Int = 0,
    /**
     * Called from the render thread each time a page is ready during a streaming render. Null for
     * non-streaming views (thumbnails, reference pane). The callback must be non-blocking; use a
     * coroutine scope to update Compose state from it.
     *
     * Only invoked on the full-render path (reflow / document load). The splice path (bounded
     * non-reflow edit) does not call this — it replaces only the affected pages atomically.
     */
    onPage: ((pageIndex: Int, page: RenderPage) -> Unit)? = null,
): RenderResult? {
    val currentOnPage by rememberUpdatedState(onPage)
    val rendererIdentityKey = rememberIdentityKey(renderer)
    val scoreIdentityKey = rememberIdentityKey(score)
    val computedIdentityKey = rememberIdentityKey(computed)
    // The documentVersion last committed to the engine. Non-observable (a plain box, not Compose state)
    // so writing it from the render coroutine never schedules a recomposition. Sentinel forces the very
    // first render — which has no cache to reuse anyway — down the reset/full path.
    val lastRenderedDocVersion = remember(renderer) { longArrayOf(Long.MIN_VALUE) }
    return produceState<RenderResultPublication?>(
        null,
        rendererIdentityKey,
        scoreIdentityKey,
        computedIdentityKey,
        pageGeometry,
        renderRefreshKey,
        documentVersion,
    ) {
        val effectStartedAt = System.nanoTime()
        var dispatcherEnteredAt = effectStartedAt
        var dispatcherFinishedAt = effectStartedAt
        val r = renderer
        val renderedResult = if (r != null) {
            try {
                withContext(r.dispatcher) {
                    dispatcherEnteredAt = System.nanoTime()
                    // Cooperative cancellation probe for the engine: true once this render coroutine has been
                    // cancelled (a newer edit superseded it). The engine polls it at coarse checkpoints and
                    // bails early so the serial render dispatcher is freed for the latest frame instead of
                    // grinding through stale ones (docs/performance/large-score-editing.md).
                    val job = coroutineContext[Job]
                    val isCancelled = { job?.isActive == false }
                    val renderStart = System.nanoTime()
                    var renderPathTag = "none"
                    val rendered = when {
                        // Incremental editing path: render via renderStreaming so paginated reflows emit
                        // the anchor page first. The splice path (non-reflow bounded edit) falls through
                        // to renderIncremental internally and does not call onPage.
                        //
                        // Only a genuine document change (load / replace, signalled by documentVersion)
                        // resets the engine's cross-frame caches. A null hint is NOT sufficient reason to
                        // reset: undo/redo and plugin-track updates carry a null hint yet stay in the SAME
                        // document, and the engine's diff fallback recovers the displayed→target change set
                        // by structural diff against the cached frame — cheap, and it drives an incremental
                        // layout (see RenderEngine.renderIncremental and docs/renderer/incremental-rendering.md). Resetting
                        // there would discard that cache and force a full re-layout of the whole score
                        // (the undo-of-drag lag: streaming-full over tens of thousands of elements).
                        computed != null -> {
                            val effectiveOnPage: (Int, RenderPage) -> Unit = { idx, page ->
                                currentOnPage?.invoke(idx, page)
                            }
                            val documentChanged = lastRenderedDocVersion[0] != documentVersion
                            if (documentChanged) {
                                r.engine.reset()
                                lastRenderedDocVersion[0] = documentVersion
                            }
                            // Pass the hint when present (direct-successor edit); otherwise pass nothing and
                            // let the engine diff the cached frame (null hint = undo/redo/plugin, or the
                            // post-reset first render which has no cache and falls through to full).
                            val changeSet = renderHint?.changeSet
                            val expectedPrevious = renderHint?.previousComputed
                            renderPathTag = when {
                                renderHint != null -> "streaming-incremental(hint)"
                                documentChanged -> "streaming-full(document)"
                                else -> "streaming-diff(no-hint)"
                            }
                            if (pageGeometry != null)
                                r.engine.renderStreaming(computed, changeSet, expectedPrevious, pageGeometry = pageGeometry, onPage = effectiveOnPage, isCancelled = isCancelled)
                            else
                                r.engine.renderStreaming(computed, changeSet, expectedPrevious, onPage = effectiveOnPage, isCancelled = isCancelled)
                        }
                        // Full render from runtime (no precomputed score supplied).
                        score != null -> {
                            renderPathTag = "render-from-runtime"
                            if (pageGeometry != null) r.engine.render(score, pageGeometry = pageGeometry)
                            else r.engine.render(score)
                        }
                        else -> null
                    }
                    if (rendered != null)
                        com.mecon.renderer.debug.PerfLog.log("render") { "path=$renderPathTag renderStreaming=${(System.nanoTime() - renderStart) / 1_000_000}ms pages=${rendered.pages.size} elements=${rendered.elements.size}" }
                    // Capture geometry on the render dispatcher (reads the engine's cached layout) so the
                    // owner can persist it on save.
                    if (rendered != null && onGeometryCaptured != null) {
                        val capStart = System.nanoTime()
                        val geom = r.engine.captureGeometry()
                        onGeometryCaptured.invoke(geom)
                        com.mecon.renderer.debug.PerfLog.log("render") { "captureGeometry+persist=${(System.nanoTime() - capStart) / 1_000_000}ms" }
                    }
                    dispatcherFinishedAt = System.nanoTime()
                    rendered
                }
            } catch (e: CancellationException) {
                // Superseded render: leave the previous frame on screen (don't null the result) and let
                // produceState handle the cancellation.
                throw e
            } catch (e: Exception) {
                println("Rendering failed: ${e.message}")
                e.printStackTrace()
                null
            }
        } else null
        // The wrapper's default referential equality makes publication O(1). Consumers still receive
        // the RenderResult itself; only this Compose state hand-off changes its equality semantics.
        val mainResumedAt = System.nanoTime()
        value = RenderResultPublication(renderedResult)
        val publishedAt = System.nanoTime()
        com.mecon.renderer.debug.PerfLog.log("render.handoff") {
            "effectToDispatcher=${(dispatcherEnteredAt - effectStartedAt) / 1_000_000}ms " +
                "dispatcherWork=${(dispatcherFinishedAt - dispatcherEnteredAt) / 1_000_000}ms " +
                "resumeMain=${(mainResumedAt - dispatcherFinishedAt) / 1_000_000}ms " +
                "publish=${(publishedAt - mainResumedAt) / 1_000_000}ms"
        }
    }.value?.result
}
