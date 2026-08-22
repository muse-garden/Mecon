package com.mecon.renderer.render

import kotlinx.collections.immutable.toPersistentMap
import com.mecon.api.computed.ComputedScore
import com.mecon.api.runtime.RuntimeScore
import com.mecon.renderer.elements.*
import com.mecon.api.interaction.VoiceNoteSection
import com.mecon.api.interaction.StyleOverride
import com.mecon.api.plugin.NoteStyleProvider
import com.mecon.api.plugin.PluginRegistry
import com.mecon.renderer.geometry.*
import com.mecon.renderer.interaction.*
import com.mecon.renderer.layout.*
import com.mecon.renderer.render.spatial.ScoreSpatialAdapter
import com.mecon.renderer.smufl.BravuraFont

/**
 * Which layout strategy [RenderEngine.renderIncremental] used on its last call.
 *
 * - [FULL]: ran the full proportional spacing solve (first incremental render, paginated mode, or a
 *   notation / structural change).
 * - [INCREMENTAL]: re-solved only the changed measures and translated the rest (measure-granular
 *   incremental layout). Used for any edit confined to `affectedMeasures`.
 */
enum class IncrementalRenderPath { FULL, INCREMENTAL }

/**
 * Main render engine that produces renderable output from score layout.
 *
 * The render engine delegates rendering to each element's [RenderableElement.render]
 * method, which produces [ElementRenderOutput] containing both [RenderElement]s and
 * [SectionRegistration]s. This replaces manual element-type dispatch and section registration.
 *
 * ## Rendering Pipeline
 *
 * 1. **Input**: UnifiedLayoutResult (from ScoreLayoutEntry)
 * 2. **System rendering**: Staff lines (via [SystemRenderer])
 * 3. **Element rendering**: Each LayoutElement calls its own render() method
 * 4. **Stem/flag rendering**: [StemElement] / [FlagElement] from VoiceEventLayouts
 * 5. **Beam rendering**: [BeamGroupElement] from [BeamGroupProcessor] output
 * 6. **Output**: RenderResult with all RenderElements and SectionIndex
 *
 * ## Coordinate System
 *
 * The engine uses a [CoordinateTransformer] to convert between:
 * - Relative coordinates (staff spaces) used in layout
 * - Absolute coordinates (pixels) used for rendering
 *
 * ## Hit Testing
 *
 * After rendering, a hierarchical spatial index is built for efficient hit testing.
 * Use [getHitTestService] to access the thread-safe [HitTestService].
 *
 * ## Style Overrides
 *
 * The [StyleOverrideManager] allows callers to apply per-element or per-event
 * style overrides (color, background) without re-rendering. Use [getStyleOverrideManager]
 * to access it.
 *
 * @param config Layout configuration
 * @param font Bravura font for glyph metrics (required)
 */
context(BravuraFont)
class RenderEngine(
    private val config: RenderLayoutConfig = RenderLayoutConfig.DEFAULT
) {
    private val transformer = CoordinateTransformer()
    private var lastResult: RenderResult? = null

    // Last unified layout, cached so the X-preserving incremental path (renderIncremental) can reuse
    // slot X and skip the proportional solve (the reuse-X seam, docs/renderer/incremental-rendering.md).
    private var lastLayout: UnifiedLayoutResult? = null

    // Which layout path the last renderIncremental took (FULL after a full render()).
    private var lastIncrementalPath: IncrementalRenderPath = IncrementalRenderPath.FULL

    // Rich elements (element + sections + hit) from the last assembled result, cached so the
    // element-level incremental splice can reuse prefix elements / translate the tail by Δ.
    private var lastRichElements: List<RichElement> = emptyList()
    private var lastPaginatedRichRuns: List<PaginatedRichRun> = emptyList()

    // Whether the most recent renderIncremental produced its result via the element-level splice
    // (true) or fell back to a full renderUnified (false). Diagnostic / test hook.
    private var lastRenderSpliced: Boolean = false

    // Whether the most recent assemble reused the cached section + spatial indices (incremental) rather
    // than rebuilding them from scratch. Diagnostic / test hook for the index-splice path.
    private var lastAssembleIncremental: Boolean = false

    // Hierarchical spatial index
    private val scoreSpatialAdapter = ScoreSpatialAdapter()
    private val hitTestService = HitTestService()

    // Style override manager
    private val styleOverrideManager = StyleOverrideManager()

    // Plugin components
    private val pluginComponents = mutableListOf<com.mecon.renderer.plugin.PluginRenderComponent>()

    // Low-priority track for plugin-driven note coloring (rebuilt each render).
    private val noteStyles = NoteStyleCoordinator(styleOverrideManager)

    // Cached so reapplyNoteStyles() works without a full re-render
    private var lastComputedScore: ComputedScore? = null

    // The authoritative captured slur / articulation geometry overlay for the displayed frame
    // (form "b" of docs/data_model/incremental-update.md). Seeded on a full render (full auto capture, or from a
    // loaded overlay) and folded incrementally on each renderIncremental: edited entries are
    // recomputed from auto-layout, unaffected entries are reused by reference (no drift, manual-edit
    // ready). null when the score has no slurs / articulations. Returned by captureGeometry().
    private var geometryInvalidation: com.mecon.api.computed.GeometryInvalidation? = null
    private var liveGeometry: com.mecon.api.storage.ScoreGeometry? = null

    // Transactional snapshot of the mutable render caches, used to roll back a cancelled incremental
    // render so a superseded (bailed) frame never leaves the engine in a half-updated state for the next
    // one (docs/renderer/incremental-rendering.md). hitTestService / noteStyleTrack need no snapshot: they are only
    // touched in finishAssembly / applyNoteStyleProviders, which run after the last cancellation
    // checkpoint, so a cancelled render never reaches them.
    private class CacheSnapshot(
        val result: RenderResult?,
        val layout: UnifiedLayoutResult?,
        val incrementalPath: IncrementalRenderPath,
        val richElements: List<RichElement>,
        val paginatedRichRuns: List<PaginatedRichRun>,
        val renderSpliced: Boolean,
        val assembleIncremental: Boolean,
        val computedScore: ComputedScore?,
        val geometry: com.mecon.api.storage.ScoreGeometry?,
        val invalidation: com.mecon.api.computed.GeometryInvalidation?,
    )

    private fun snapshotCache() = CacheSnapshot(
        lastResult, lastLayout, lastIncrementalPath, lastRichElements, lastPaginatedRichRuns,
        lastRenderSpliced, lastAssembleIncremental, lastComputedScore, liveGeometry, geometryInvalidation,
    )

    private fun restoreCache(s: CacheSnapshot) {
        lastResult = s.result
        lastLayout = s.layout
        lastIncrementalPath = s.incrementalPath
        lastRichElements = s.richElements
        lastPaginatedRichRuns = s.paginatedRichRuns
        lastRenderSpliced = s.renderSpliced
        lastAssembleIncremental = s.assembleIncremental
        lastComputedScore = s.computedScore
        liveGeometry = s.geometry
        geometryInvalidation = s.invalidation
    }

    // Specialized renderers
    private val systemRenderer = SystemRenderer(config, transformer)
    private val beamGroupProcessor = BeamGroupProcessor(config)
    private val tieLayoutComputer = TieLayoutComputer(config)
    private val slurLayoutComputer = SlurLayoutComputer(config)
    private val articulationLayoutComputer = ArticulationLayoutComputer(config)
    private val tupletLayoutComputer = TupletLayoutComputer(config)
    private val annotationStaffRenderer = AnnotationStaffRenderer(transformer)
    private val structuralElementRenderer = StructuralElementRenderer(systemRenderer)
    private val lineStartHeaderRenderer = LineStartHeaderRenderer(transformer)
    private val titleBlockRenderer = TitleBlockRenderer(transformer)
    private val postLayoutMarkerRenderer = PostLayoutMarkerRenderer(transformer)
    private val notationElementPassRenderer = NotationElementPassRenderer(config, transformer, systemRenderer)
    private val fullScoreRenderer = FullScoreRenderer(
        config = config,
        transformer = transformer,
        structuralElementRenderer = structuralElementRenderer,
        notationElementPassRenderer = notationElementPassRenderer,
        beamGroupProcessor = beamGroupProcessor,
        tieLayoutComputer = tieLayoutComputer,
        slurLayoutComputer = slurLayoutComputer,
        articulationLayoutComputer = articulationLayoutComputer,
        tupletLayoutComputer = tupletLayoutComputer,
        annotationStaffRenderer = annotationStaffRenderer,
        lineStartHeaderRenderer = lineStartHeaderRenderer,
        titleBlockRenderer = titleBlockRenderer,
        postLayoutMarkerRenderer = postLayoutMarkerRenderer,
    )
    private val pageBuilder = RenderPageBuilder(transformer)
    private val resultAssembler = RenderResultAssembler(transformer, scoreSpatialAdapter, pageBuilder)
    private val continuousRenderSplicer = ContinuousRenderSplicer(
        config = config,
        transformer = transformer,
        structuralElementRenderer = structuralElementRenderer,
        notationElementPassRenderer = notationElementPassRenderer,
        beamGroupProcessor = beamGroupProcessor,
        articulationLayoutComputer = articulationLayoutComputer,
        tupletLayoutComputer = tupletLayoutComputer,
        tieLayoutComputer = tieLayoutComputer,
        slurLayoutComputer = slurLayoutComputer,
        annotationStaffRenderer = annotationStaffRenderer,
        postLayoutMarkerRenderer = postLayoutMarkerRenderer,
        resultAssembler = resultAssembler,
    )
    private val paginatedRenderSplicer = PaginatedRenderSplicer(
        config = config,
        transformer = transformer,
        structuralElementRenderer = structuralElementRenderer,
        notationElementPassRenderer = notationElementPassRenderer,
        beamGroupProcessor = beamGroupProcessor,
        tieLayoutComputer = tieLayoutComputer,
        slurLayoutComputer = slurLayoutComputer,
        articulationLayoutComputer = articulationLayoutComputer,
        tupletLayoutComputer = tupletLayoutComputer,
        lineStartHeaderRenderer = lineStartHeaderRenderer,
        titleBlockRenderer = titleBlockRenderer,
        annotationStaffRenderer = annotationStaffRenderer,
        postLayoutMarkerRenderer = postLayoutMarkerRenderer,
        resultAssembler = resultAssembler,
    )

    internal val editPreviewFacade = RenderEditPreviewFacade(config)

    /**
     * Add a plugin render component.
     */
    fun addPluginComponent(component: com.mecon.renderer.plugin.PluginRenderComponent) {
        pluginComponents.add(component)
    }

    /**
     * Set the scale factor.
     */
    fun setScale(scale: ScaleFactor) {
        transformer.setScale(scale)
    }

    /**
     * Set the viewport bounds.
     */
    fun setViewport(bounds: AbsoluteRect) {
        transformer.setViewportBounds(bounds)
    }

    /**
     * Render directly from a RuntimeScore.
     *
     * This is the primary API for rendering. It encapsulates the layout computation
     * and delegates to [renderUnified] for actual rendering. The intermediate
     * layout result is not exposed to callers.
     *
     * @param score The runtime score to render
     * @param pageWidth Available page width in staff spaces
     * @param pageHeight Available page height in staff spaces
     * @return Complete render result
     */
    fun render(
        score: RuntimeScore,
        pageWidth: StaffSpace = StaffSpace(100f),
        pageHeight: StaffSpace = StaffSpace(80f),
        pageGeometry: com.mecon.renderer.layout.PageGeometry =
            com.mecon.renderer.layout.PageGeometry.from(score.pageLayout)
    ): RenderResult {
        val (layout, computedScore) = ScoreLayoutEntry.computeLayoutWithComputed(
            score, pageWidth, pageHeight, config, pageGeometry
        )
        lastLayout = layout
        lastComputedScore = computedScore
        lastIncrementalPath = IncrementalRenderPath.FULL
        lastRenderSpliced = false
        val result = renderUnified(layout, computedScore)
        // Seed the live overlay: the full render displayed the loaded overlay (sticky) when present,
        // else laid out from scratch — capturing with that overlay reproduces what is on screen and
        // gives later incremental edits a "previous" to fold against (docs/data_model/incremental-update.md).
        geometryInvalidation = null
        liveGeometry = captureGeometryWith(computedScore.runtime.geometry)
        applyNoteStyleProviders(computedScore)
        return result
    }

    /**
     * Render from an already-computed [ComputedScore] — the local-update seam (see
     * `docs/data_model/incremental-update.md` §3).
     *
     * The caller passes [computed] (computed once, off the render thread), so the expensive full compute
     * pass is skipped. This method lays out and renders it directly, returning a **complete,
     * self-consistent** [RenderResult] whose [RenderResult.spatialIndex] ships with its
     * [RenderResult.elements] (the single-source-of-truth invariant from spatial-index.md §1a).
     *
     * ## The change set: hint, lineage, and diff fallback
     *
     * The incremental layout reuses the *previously rendered* frame's slot X (re-solving only the changed
     * measures and translating the rest), so it is only valid against the frame actually on screen
     * ([lastComputedScore]). The change set driving it is resolved as:
     *
     *  - **[changeSet] as a hint** — when the displayed frame is the edit's direct predecessor
     *    ([expectedPrevious] `===` [lastComputedScore]), or the caller opts out of the check
     *    ([expectedPrevious] null, e.g. tests rendering in strict sequence), the precomputed [changeSet]
     *    is trusted as-is. This is the common editing path and needs no diff.
     *  - **diff fallback** — when [expectedPrevious] does not match the displayed frame (a coalesced
     *    render skipped the intermediate frame, or an **undo/redo** jumped history) but a frame is
     *    cached, the true displayed→[computed] change set is recovered by structurally diffing the two
     *    computed scores ([com.mecon.api.computed.computeChangeSetBetween]) — cheap thanks to the
     *    persistent event store's sharing. The stale [changeSet] is ignored.
     *  - **full** — no cached frame (first render): nothing to reuse, so a full layout.
     *
     * ## Incremental layout (measure-granular)
     *
     * When the effective change set changed neither notation nor measure structure
     * ([ComputeChangeSet.allowsIncrementalLayout]) and both previous and current layout are continuous,
     * the layout **re-solves only `affectedMeasures`** and translates every other measure rigidly by the
     * shift the window introduced (docs/renderer/incremental-rendering.md). Because the proportional solve's tail is
     * an affine function of the start X, this is identical to a full solve for any edit confined to the
     * window. Vertical geometry (stems / beams / ties / slurs) is always recomputed from [computed].
     *
     * Which path ran is recorded for tests via [lastIncrementalRenderPath].
     */
    /**
     * Re-render only [measureRange], reusing cached elements outside it.
     *
     * Useful for plugins or analysis features that invalidate a known measure span without a score edit
     * (e.g. chord-analysis refresh, style-override update, or any feature that wants sub-page granularity).
     * Falls back to a full render if the splice path cannot engage (no cache, notation changed, etc.).
     *
     * For edit-driven re-renders, prefer [renderIncremental] with the [ComputeChangeSet] from the engine.
     */
    fun renderRange(
        computed: ComputedScore,
        measureRange: IntRange,
        pageWidth: StaffSpace = StaffSpace(100f),
        pageHeight: StaffSpace = StaffSpace(80f),
        pageGeometry: com.mecon.renderer.layout.PageGeometry =
            com.mecon.renderer.layout.PageGeometry.from(computed.runtime.pageLayout),
    ): RenderResult = renderIncremental(
        computed = computed,
        changeSet = com.mecon.api.computed.ComputeChangeSet.forRange(measureRange),
        expectedPrevious = null,
        pageWidth = pageWidth,
        pageHeight = pageHeight,
        pageGeometry = pageGeometry,
    )

    /**
     * Progressive streaming render for paginated scores: computes layout once, then renders pages
     * one at a time and calls [onPage] with each [RenderPage] as it is ready — **anchor page first**.
     *
     * The anchor page is the page that contains [changeSet]'s `affectedMeasures` (the visually changed
     * region). Emitting it before all other pages lets the UI show the result of the edit immediately,
     * without waiting for the entire score to render. Remaining pages are emitted in document order.
     *
     * **When streaming does NOT engage:**
     * - The splice path (non-reflow, bounded edit) is already bounded; [onPage] is not called and the
     *   result is identical to [renderIncremental].
     * - Continuous (non-paginated) mode: [onPage] is not called; falls through to [renderIncremental].
     * - Single-page scores: [onPage] is not called; no benefit over [renderIncremental].
     *
     * The returned [RenderResult] is complete and fully assembled (global spatial index, section index,
     * all pages). It is committed to the engine cache so subsequent edits can splice against it.
     *
     * @param onPage Called for each [RenderPage] in page-local coordinates as it is ready.
     *   The anchor page is always first. Not called on the splice or continuous paths.
     *   The callback runs on the same thread/dispatcher as the render; callers must post to the UI
     *   thread if needed (e.g. `withContext(Dispatchers.Main) { ... }` around the call site).
     */
    fun renderStreaming(
        computed: ComputedScore,
        changeSet: com.mecon.api.computed.ComputeChangeSet? = null,
        expectedPrevious: ComputedScore? = null,
        pageWidth: StaffSpace = StaffSpace(100f),
        pageHeight: StaffSpace = StaffSpace(80f),
        pageGeometry: com.mecon.renderer.layout.PageGeometry =
            com.mecon.renderer.layout.PageGeometry.from(computed.runtime.pageLayout),
        onPage: (pageIndex: Int, page: RenderPage) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): RenderResult {
        val cacheSnapshot = snapshotCache()
        try {
            return renderIncrementalInternal(
                computed, changeSet, expectedPrevious,
                pageWidth, pageHeight, pageGeometry, isCancelled, onPage,
            )
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            restoreCache(cacheSnapshot)
            throw e
        }
    }

    /**
     * Full-path streaming: computes one page at a time from a completed [layout], calling [onPage]
     * for each as it is ready (anchor page first), then assembles and returns the complete result.
     */
    private fun renderFullStreaming(
        layout: com.mecon.renderer.layout.UnifiedLayoutResult,
        computedForRender: ComputedScore,
        effectiveChangeSet: com.mecon.api.computed.ComputeChangeSet,
        onPage: (Int, RenderPage) -> Unit,
        isCancelled: () -> Boolean,
    ): RenderResult {
        // Determine anchor page: the page containing the edit window.
        val anchorPageIndex = if (!effectiveChangeSet.affectedMeasures.isEmpty()) {
            val lo = effectiveChangeSet.affectedMeasures.first
            val hi = effectiveChangeSet.affectedMeasures.last
            layout.systems
                .firstOrNull { it.measureRange.first <= hi && it.measureRange.last >= lo }
                ?.pageIndex ?: 0
        } else 0

        // Map from pageIndex → set of systemIndex values on that page.
        val systemsByPage: Map<Int, Set<Int>> = layout.systems
            .groupBy { it.pageIndex }
            .mapValues { (_, systems) -> systems.mapTo(HashSet()) { it.systemIndex } }

        // Shared ID counter so elements across all per-page renders have globally unique IDs.
        var streamingIdCounter = 0
        fun nextStreamingId() = RenderElementId.global(streamingIdCounter++.toLong())

        val allRichElements = ArrayList<RichElement>()

        fun renderAndEmitPage(pageLayout: com.mecon.renderer.layout.PageLayout) {
            val systems = systemsByPage[pageLayout.pageIndex] ?: emptySet()
            val pageRich = fullScoreRenderer.render(
                layout, computedForRender, pluginComponents,
                systemFilter = systems,
                idGenerator = ::nextStreamingId,
            )
            allRichElements.addAll(pageRich)
            onPage(pageLayout.pageIndex, pageBuilder.buildForPage(pageRich.map { it.element }, pageLayout))
        }

        // Anchor page first, then remaining pages in document order.
        layout.pages.find { it.pageIndex == anchorPageIndex }?.let { renderAndEmitPage(it) }
        isCancelled.throwIfCancelled()
        for (pageLayout in layout.pages) {
            if (pageLayout.pageIndex == anchorPageIndex) continue
            renderAndEmitPage(pageLayout)
            isCancelled.throwIfCancelled()
        }

        return finishAssembly(resultAssembler.assemble(allRichElements, layout, isCancelled))
    }

    fun renderIncremental(
        computed: ComputedScore,
        changeSet: com.mecon.api.computed.ComputeChangeSet? = null,
        expectedPrevious: ComputedScore? = null,
        pageWidth: StaffSpace = StaffSpace(100f),
        pageHeight: StaffSpace = StaffSpace(80f),
        pageGeometry: com.mecon.renderer.layout.PageGeometry =
            com.mecon.renderer.layout.PageGeometry.from(computed.runtime.pageLayout),
        /**
         * Cooperative cancellation probe: returns true once this render has been superseded by a newer
         * edit (its coroutine cancelled). Polled at coarse checkpoints so a stale render bails early and
         * frees the serial render dispatcher; caches are snapshotted and rolled back on bail so the next
         * render stays consistent (docs/renderer/incremental-rendering.md). Default never cancels.
         */
        isCancelled: () -> Boolean = { false },
    ): RenderResult {
        val cacheSnapshot = snapshotCache()
        try {
            return renderIncrementalInternal(
                computed, changeSet, expectedPrevious, pageWidth, pageHeight, pageGeometry, isCancelled,
            )
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            restoreCache(cacheSnapshot) // bailed mid-render → undo any partial cache mutation
            throw e
        }
    }

    private fun renderIncrementalInternal(
        computed: ComputedScore,
        changeSet: com.mecon.api.computed.ComputeChangeSet?,
        expectedPrevious: ComputedScore?,
        pageWidth: StaffSpace,
        pageHeight: StaffSpace,
        pageGeometry: com.mecon.renderer.layout.PageGeometry,
        isCancelled: () -> Boolean,
        onPage: ((pageIndex: Int, page: RenderPage) -> Unit)? = null,
    ): RenderResult {
        // Capture the cached previous render before computeLayout / assembleResult overwrite it — the
        // element-level splice reuses these.
        val cachedLayout = lastLayout
        val cachedRich = lastRichElements
        val cachedRichRuns = lastPaginatedRichRuns
        val cached = lastComputedScore
        val cachedResult = lastResult

        // Resolve the effective change set (see KDoc): trust the hint when it describes the displayed
        // frame, else diff displayed→new, else (no cache) force a full layout.
        val lineageOk = expectedPrevious == null || expectedPrevious === cached
        val effectiveChangeSet = when {
            changeSet != null && lineageOk -> changeSet
            cached != null -> com.mecon.api.computed.computeChangeSetBetween(cached, computed)
            else -> com.mecon.api.computed.ComputeChangeSet.EMPTY
        }

        val computer = UnifiedLayoutComputer(config)
        // Reuse the cached layout (re-solve only affectedMeasures, translate the rest) when nothing
        // structural changed and the previous layout's mode matches this request. Paginated is reusable
        // too: the layout reuses the cached PRE-break proportional X (preBreakTimeSlotMap) and the breaker
        // reuses the cached line partition, re-justifying only the affected line (falling back to a full
        // solve on reflow). See docs/renderer/incremental-rendering.md.
        val reuseFrom = cachedLayout?.takeIf {
            effectiveChangeSet.allowsIncrementalLayout && it.paginated == pageGeometry.paginated
        }
        // Persisted geometry: classify which overlay entries the edit invalidated — done BEFORE layout so
        // the pruned overlay can drive attachment layout too. Stale entries are dropped, so attachment
        // spans fall back to auto (reshape) and stale slur / articulation passes re-lay-out; every other
        // entry stays present and resolves anchor-relative (follows its moved notes / honours stored Y). A
        // notation / structure change forces a wholesale recapture. The original [computed] still drives
        // note layout, lineage and caching — the overlay rides side-band (an explicit layout param, and a
        // copy of [computed] for the post-layout slur / articulation passes).
        // A geometry-only editor commit (beam/slur handle drag) is an explicit new overlay and must
        // beat the previous frame's captured liveGeometry. Ordinary musical edits keep the same
        // runtime overlay and continue from liveGeometry so auto-captured entries are preserved.
        val runtimeGeometryChanged = computed.runtime.geometry != cached?.runtime?.geometry
        val baseGeometry = if (runtimeGeometryChanged) {
            computed.runtime.geometry
        } else {
            liveGeometry ?: computed.runtime.geometry
        }
        val invalidation = baseGeometry?.let {
            com.mecon.api.computed.analyzeGeometryInvalidation(
                it, computed, effectiveChangeSet, previousComputed = lastComputedScore,
            )
        }
        geometryInvalidation = invalidation
        val renderGeometry = when {
            baseGeometry == null -> computed.runtime.geometry
            invalidation!!.full -> com.mecon.api.storage.ScoreGeometry(
                // Tuplet side overrides are engraving directives, not stale coordinates.
                tuplets = baseGeometry.tuplets.filterValues { it.directionLocked },
            )
            else -> baseGeometry.without(
                invalidation.staleArticulations, invalidation.staleSlurs, invalidation.staleAttachments,
                invalidation.staleBeams,
                staleTies = invalidation.staleTies,
            )
        }
        val computedForRender =
            if (renderGeometry === computed.runtime.geometry) computed
            else computed.copy(runtime = computed.runtime.copy(geometry = renderGeometry))

        val _tLayout = kotlin.time.TimeSource.Monotonic.markNow()
        val layout = computer.computeLayout(
            computed = computed,
            runtime = computed.runtime,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            pageGeometry = pageGeometry,
            reuseXFrom = reuseFrom,
            reuseWindow = if (reuseFrom != null) effectiveChangeSet.affectedMeasures else null,
            attachmentGeometry = renderGeometry,
        )
        com.mecon.renderer.debug.PerfLog.log("render.stage") { "computeLayout=${_tLayout.elapsedNow().inWholeMilliseconds}ms incrementalLayoutUsed=${computer.incrementalLayoutUsed} reuseFrom=${reuseFrom != null}" }
        // Layout done — bail before the (more expensive) splice / assemble if already superseded.
        isCancelled.throwIfCancelled()
        lastIncrementalPath = if (computer.incrementalLayoutUsed)
            IncrementalRenderPath.INCREMENTAL else IncrementalRenderPath.FULL
        lastComputedScore = computed
        // Element-level incremental render when the layout was incremental and the splice guard holds;
        // otherwise a full render. Both set lastLayout / lastRichElements via assembleResult. Paginated and
        // continuous use line- vs measure-granular splices respectively.
        val _tSplice = kotlin.time.TimeSource.Monotonic.markNow()
        var incrementalGeometryCapture: IncrementalGeometryCapture? = null
        val spliced = if (computer.incrementalLayoutUsed) {
            if (layout.paginated)
                paginatedRenderSplicer.render(
                    cachedLayout = cachedLayout,
                    cachedRich = cachedRich,
                    cachedRichRuns = cachedRichRuns,
                    cachedResult = cachedResult,
                    layout = layout,
                    computed = computedForRender,
                    changeSet = effectiveChangeSet,
                    hasPluginComponents = pluginComponents.isNotEmpty(),
                    nextId = ::nextSpliceId,
                    isCancelled = isCancelled,
                )?.let { assembly ->
                    incrementalGeometryCapture = assembly.geometryCapture
                    finishAssembly(assembly)
                }
            else continuousRenderSplicer.render(
                cachedLayout = cachedLayout,
                cachedRich = cachedRich,
                cachedResult = cachedResult,
                layout = layout,
                computed = computedForRender,
                changeSet = effectiveChangeSet,
                hasPluginComponents = pluginComponents.isNotEmpty(),
                nextId = ::nextSpliceId,
                isCancelled = isCancelled,
            )?.let(::finishAssembly)
        } else null
        // Full fallback also polls before its (whole-score) assemble.
        val result = spliced ?: run {
            isCancelled.throwIfCancelled()
            if (onPage != null && layout.paginated && layout.pages.size > 1)
                renderFullStreaming(layout, computedForRender, effectiveChangeSet, onPage, isCancelled)
            else
                renderUnified(layout, computedForRender, isCancelled)
        }
        com.mecon.renderer.debug.PerfLog.log("render.stage") { "splice/assemble=${_tSplice.elapsedNow().inWholeMilliseconds}ms spliced=${spliced != null}" }
        lastRenderSpliced = spliced != null
        lastLayout = layout
        // Re-fold the live overlay from the just-rendered frame: stale (pruned) entries captured fresh
        // from auto-layout, unaffected entries reused from the base overlay by reference.
        val _tFold = kotlin.time.TimeSource.Monotonic.markNow()
        // Bounded fold window: only the layout-incremental path re-solves a sub-range; a full render
        // recaptures the whole overlay (window null).
        val foldWindow = if (computer.incrementalLayoutUsed) effectiveChangeSet.affectedMeasures else null
        liveGeometry = foldLiveGeometry(
            baseGeometry, renderGeometry, invalidation, foldWindow, incrementalGeometryCapture,
        )
        com.mecon.renderer.debug.PerfLog.log("render.stage") { "foldLiveGeometry=${_tFold.elapsedNow().inWholeMilliseconds}ms" }
        val _tStyle = kotlin.time.TimeSource.Monotonic.markNow()
        applyNoteStyleProviders(computed)
        com.mecon.renderer.debug.PerfLog.log("render.stage") { "applyNoteStyleProviders=${_tStyle.elapsedNow().inWholeMilliseconds}ms" }
        return result
    }

    /**
     * Build the new live overlay after an incremental render. [renderGeometry] is the (pruned) overlay
     * the frame was drawn with, so capturing against it yields auto-layout for the stale entries and a
     * sticky re-fold for the rest. Unaffected entries are then overwritten with the [base] values by
     * reference — avoiding sub-pixel drift across edits and (later) preserving manual overrides.
     */
    private fun foldLiveGeometry(
        base: com.mecon.api.storage.ScoreGeometry?,
        renderGeometry: com.mecon.api.storage.ScoreGeometry?,
        invalidation: com.mecon.api.computed.GeometryInvalidation?,
        window: IntRange?,
        incrementalCapture: IncrementalGeometryCapture? = null,
    ): com.mecon.api.storage.ScoreGeometry? {
        // Full recapture: first incremental frame (no base), a notation / structure change (full), or no
        // bounded window — recompute the whole overlay from auto-layout.
        if (base == null || invalidation == null || invalidation.full || window == null || window.isEmpty()) {
            return captureGeometryWith(renderGeometry)
        }
        // Incremental fold: recompute geometry only inside the edit window. By the invalidation contract a
        // stale articulation's event (touched) and a stale slur's span both fall in [window], so the window
        // capture contains exactly the stale + newly-authored entries; every reusable entry is kept from
        // [base] by reference (no sub-pixel drift, manual-edit ready), and a stale entry the edit deleted is
        // simply absent from the capture and dropped.
        val captured = captureGeometryWith(
            renderGeometry,
            measureFilter = window,
            attachmentFilter = { it !in invalidation.reusableAttachments },
            incrementalCapture = incrementalCapture,
        )

        fun <V> mergeWindowed(
            baseMap: Map<com.mecon.api.primitive.EventId, V>,
            capturedMap: Map<com.mecon.api.primitive.EventId, V>?,
            reusable: Set<com.mecon.api.primitive.EventId>,
        ): Map<com.mecon.api.primitive.EventId, V> {
            val out = LinkedHashMap<com.mecon.api.primitive.EventId, V>()
            for ((id, v) in baseMap) if (id in reusable) out[id] = v
            capturedMap?.forEach { (id, v) -> if (id !in reusable) out[id] = v }
            return out
        }

        val articulations = mergeWindowed(base.articulations, captured?.articulations, invalidation.reusableArticulations)
        val tieBuilder = ((base.ties as? kotlinx.collections.immutable.PersistentMap<
            com.mecon.api.primitive.EventId,
            List<com.mecon.api.storage.TieGeometry>
        >) ?: base.ties.toPersistentMap()).builder()
        invalidation.staleTies.forEach { id ->
            val owned = base.ties[id].orEmpty().filter {
                it.directionLocked || it.manuallyAdjusted || it.autoEndpoints
            }
            if (owned.isEmpty()) tieBuilder.remove(id) else tieBuilder[id] = owned
        }
        captured?.ties?.forEach { (id, value) -> tieBuilder[id] = value }
        val ties = tieBuilder.build()
        val slurBuilder = ((base.slurs as? kotlinx.collections.immutable.PersistentMap<
            com.mecon.api.primitive.EventId,
            com.mecon.api.storage.SlurGeometry
        >) ?: base.slurs.toPersistentMap()).builder()
        invalidation.staleSlurs.forEach { id ->
            val owned = base.slurs[id]?.takeIf {
                it.directionLocked || it.manuallyAdjusted || it.autoEndpoints
            }
            if (owned == null) slurBuilder.remove(id) else slurBuilder[id] = owned
        }
        invalidation.removedSlurs.forEach(slurBuilder::remove)
        captured?.slurs?.forEach { (id, value) -> slurBuilder[id] = value }
        val slurs = slurBuilder.build()
        val attachments = mergeWindowed(
            base.attachments, captured?.attachments, invalidation.reusableAttachments,
        )
        val beams = LinkedHashMap<String, com.mecon.api.storage.BeamGeometry>()
        for ((id, geometry) in base.beams) if (id in invalidation.reusableBeams) beams[id] = geometry
        captured?.beams?.forEach { (id, geometry) ->
            if (id !in invalidation.reusableBeams) beams[id] = geometry
        }
        val tuplets = LinkedHashMap<com.mecon.api.primitive.EventId, com.mecon.api.storage.TupletGeometry>()
        for ((id, geometry) in base.tuplets) {
            val event = lastComputedScore?.getComputedEvent(id)
            if (event?.tupletInfo != null && event.onset.measure !in window) tuplets[id] = geometry
        }
        captured?.tuplets?.forEach { (id, geometry) -> tuplets[id] = geometry }
        val result = com.mecon.api.storage.ScoreGeometry(
            articulations = articulations,
            ties = ties,
            slurs = slurs,
            attachments = attachments,
            beams = beams,
            tuplets = tuplets,
        )
        return if (result.isEmpty) null else result
    }

    /**
     * Expand [window] to also cover every slur whose measure span reaches into it, so the beam pass that
     * feeds those slurs' endpoint stem adjustments is complete. Returns [window] unchanged when there are no
     * slurs, and null (whole score) when [window] is null.
     */
    private fun expandWindowForSlurs(
        computed: ComputedScore,
        query: LayoutQuery,
        window: IntRange?,
    ): IntRange? {
        if (window == null || computed.slurs.isEmpty()) return window
        var lo = window.first
        var hi = window.last
        for (slur in computed.slurs) {
            val s = query.event(slur.startEventId)?.onset?.measure ?: continue
            val e = query.event(slur.endEventId)?.onset?.measure ?: continue
            val slo = minOf(s, e)
            val shi = maxOf(s, e)
            if (shi < window.first || slo > window.last) continue // slur does not reach the window
            if (slo < lo) lo = slo
            if (shi > hi) hi = shi
        }
        return lo..hi
    }

    /**
     * Capture the currently-displayed slur / articulation geometry into stable
     * anchor-relative storage form, recomputed on demand from the last render's
     * cached layout + computed score (so it reflects the live state regardless of
     * whether the last frame was full or spliced). Attach to `StorageScore.geometry`
     * to persist it. Null before the first render. See [com.mecon.api.storage.ScoreGeometry].
     */
    fun captureGeometry(): com.mecon.api.storage.ScoreGeometry? = liveGeometry

    /**
     * The classification of overlay entries (stale vs reusable) from the most recent
     * [renderIncremental] with a live overlay, or null after a full render / for an overlay-less score.
     * Diagnostic / test hook for the Phase 2 invalidation analysis.
     */
    fun lastGeometryInvalidation(): com.mecon.api.computed.GeometryInvalidation? = geometryInvalidation

    /**
     * Recompute the slur / articulation geometry overlay from the last render's cached layout +
     * computed score, resolving against [overlay] (entries present there are sticky; absent ones fall
     * back to auto-layout). Returns null when there is nothing to capture (no slurs / articulations).
     */
    private fun captureGeometryWith(
        overlay: com.mecon.api.storage.ScoreGeometry?,
        /**
         * When non-null, only recompute articulation / slur geometry for entries touching this measure
         * window; every other entry is reused from the base overlay by [foldLiveGeometry]. Attachments are
         * read from the (whole-score) placed layout regardless — they are cheap and not recomputed here.
         * null ⇒ whole-score recapture (a full render, or the first incremental frame).
         */
        measureFilter: IntRange? = null,
        /** Incremental capture can skip attachment ids whose stored geometry is reusable by reference. */
        attachmentFilter: ((com.mecon.api.primitive.EventId) -> Boolean)? = null,
        /** Layouts already computed by the paginated splice for the same affected window. */
        incrementalCapture: IncrementalGeometryCapture? = null,
    ): com.mecon.api.storage.ScoreGeometry? {
        val layout = lastLayout ?: return null
        val computed = lastComputedScore ?: return null
        val staffLayoutByIndex = layout.staffLayouts.associateBy { it.staffIndex }
        val query = LayoutQuery(layout, staffLayoutByIndex, computed)
        val articulationStart = kotlin.time.TimeSource.Monotonic.markNow()
        val articulationLayouts = incrementalCapture?.articulations
            ?: articulationLayoutComputer.computeArticulationLayouts(
                computed, query, overlay, measureFilter = measureFilter
            )
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "geometry.articulation=${articulationStart.elapsedNow().inWholeMilliseconds}ms layouts=${articulationLayouts.size}"
        }
        val slurStart = kotlin.time.TimeSource.Monotonic.markNow()
        val tieLayouts = incrementalCapture?.ties ?: tieLayoutComputer.computeTieLayouts(
            computed,
            query,
            measureFilter = measureFilter,
            geometry = overlay,
        )
        val beamWindow = expandWindowForSlurs(computed, query, measureFilter)
        val beamProc = beamGroupProcessor.processBeamGroups(
            layout, staffLayoutByIndex, measureFilter = beamWindow,
            geometry = overlay?.beams ?: emptyMap(),
        )
        val slurLayouts = incrementalCapture?.slurs ?: if (computed.slurs.isEmpty()) emptyList() else {
            // Beams feed the stem adjustment at each slur endpoint, which may lie outside [measureFilter]
            // for a wide slur that the edit only reshaped mid-span. So process beams over the window
            // *expanded to cover every slur that reaches into it* — bounded for local edits, whole-score
            // only when a genuinely score-spanning slur is in play (which needs full beams anyway).
            slurLayoutComputer.computeSlurLayouts(
                computed, query, articulationLayouts,
                stemAdjustments = beamProc.stemAdjustments,
                beamGroups = beamProc.beamGroupRenderData,
                geometry = overlay,
                measureFilter = measureFilter,
            )
        }
        val tupletLayouts = tupletLayoutComputer.computeTupletLayouts(
            computed,
            query,
            stemAdjustments = beamProc.stemAdjustments,
            measureFilter = measureFilter,
            geometry = overlay,
        )
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "geometry.slur=${slurStart.elapsedNow().inWholeMilliseconds}ms layouts=${slurLayouts.size}"
        }
        // Attachments are read straight from the already-placed layout (no overlay-driven
        // re-anchor yet — Phase 3 captures their geometry for persistence; the auto layout
        // stays authoritative for display until manual editing lands).
        val projectStart = kotlin.time.TimeSource.Monotonic.markNow()
        val captured = GeometryProjector.capture(
            articulationLayouts,
            tieLayouts,
            slurLayouts,
            tupletLayouts,
            layout.placedAttachments,
            query,
            attachmentFilter,
            beamGroups = beamProc.beamGroupRenderData,
        )
        // Projection records the displayed coordinates, while ownership metadata comes from the
        // persisted overlay. Preserve manual-Y authority across a full capture; coordinates may have
        // received the shortest collision-avoidance shift during layout.
        val ownedCapture = captured.copy(
            ties = captured.ties.mapValues { (eventId, geometries) ->
                val prior = overlay?.ties?.get(eventId).orEmpty().associateBy { it.sourcePitchIndex }
                geometries.map { geometry ->
                    prior[geometry.sourcePitchIndex]?.let { old ->
                        geometry.copy(
                            directionLocked = old.directionLocked || old.directionOnly,
                            manuallyAdjusted = old.manuallyAdjusted,
                        )
                    } ?: geometry
                }
            },
            slurs = captured.slurs.mapValues { (id, geometry) ->
                overlay?.slurs?.get(id)?.let { old ->
                    geometry.copy(
                        directionLocked = old.directionLocked || old.directionOnly,
                        manuallyAdjusted = old.manuallyAdjusted,
                    )
                } ?: geometry
            },
            attachments = captured.attachments.mapValues { (id, geometry) ->
                if (overlay?.attachments?.get(id)?.manuallyAdjustedY == true) {
                    geometry.copy(manuallyAdjustedY = true)
                } else geometry
            },
            tuplets = captured.tuplets.mapValues { (id, geometry) ->
                overlay?.tuplets?.get(id)?.let { old ->
                    geometry.copy(directionLocked = old.directionLocked)
                } ?: geometry
            },
        )
        // Cross-system curves are split into multiple stubs and intentionally absent from a single
        // captured segment. On a full capture, retain only user/import-owned entries that could not be
        // represented as one segment; ordinary automatic cache entries remain disposable.
        val capturedWithOwnership = if (measureFilter != null || overlay == null) {
            ownedCapture
        } else {
            val tieBuilder = ownedCapture.ties.toPersistentMap().builder()
            for ((eventId, geometries) in overlay.ties) {
                val currentByPitch = tieBuilder[eventId].orEmpty().associateBy { it.sourcePitchIndex }
                val missingOwned = geometries.filter {
                    it.sourcePitchIndex !in currentByPitch &&
                        (it.directionLocked || it.manuallyAdjusted || it.autoEndpoints)
                }
                if (missingOwned.isNotEmpty()) {
                    tieBuilder[eventId] = (tieBuilder[eventId].orEmpty() + missingOwned)
                        .sortedBy { it.sourcePitchIndex }
                }
            }
            val slurBuilder = ownedCapture.slurs.toPersistentMap().builder()
            for ((id, geometry) in overlay.slurs) {
                if (id !in slurBuilder &&
                    (geometry.directionLocked || geometry.manuallyAdjusted || geometry.autoEndpoints)
                ) {
                    slurBuilder[id] = geometry
                }
            }
            ownedCapture.copy(ties = tieBuilder.build(), slurs = slurBuilder.build())
        }
        com.mecon.renderer.debug.PerfLog.log("render.stage") {
            "geometry.project=${projectStart.elapsedNow().inWholeMilliseconds}ms attachments=${captured.attachments.size}"
        }
        return if (capturedWithOwnership.isEmpty) null else capturedWithOwnership
    }

    /** Which layout path the most recent [renderIncremental] took. Diagnostic / test hook. */
    fun lastIncrementalRenderPath(): IncrementalRenderPath = lastIncrementalPath

    /**
     * The [ComputedScore] this engine most recently rendered (full [render] or [renderIncremental]), or
     * null before the first render. The pipeline compares it by identity against a state's
     * [com.mecon.api.state.RenderHint.previousComputed] to confirm the displayed frame is the edit's
     * direct predecessor before driving an incremental update.
     */
    fun lastRenderedComputed(): ComputedScore? = lastComputedScore

    /**
     * Whether the most recent [renderIncremental] produced its result via the element-level splice
     * (reuse prefix / translate tail / regenerate window) rather than a full render. Diagnostic / test hook.
     */
    fun lastRenderWasSpliced(): Boolean = lastRenderSpliced

    /**
     * Whether the most recent assemble reused the cached section + spatial indices (the incremental
     * index splice) rather than rebuilding them. Diagnostic / test hook.
     */
    fun lastAssembleWasIncremental(): Boolean = lastAssembleIncremental

    /**
     * Drop cached render/layout state so the next render starts from a clean slate.
     *
     * Used when the displayed score changes non-incrementally (for example opening a different
     * document). Without this, a subsequent [renderIncremental] may diff against the previous
     * document and incorrectly reuse layout/element caches across scores.
     */
    fun reset() {
        lastResult = null
        lastLayout = null
        lastIncrementalPath = IncrementalRenderPath.FULL
        lastRichElements = emptyList()
        lastPaginatedRichRuns = emptyList()
        lastRenderSpliced = false
        lastAssembleIncremental = false
        noteStyles.reset()
        lastComputedScore = null
        liveGeometry = null
        geometryInvalidation = null
        spliceIdCounter = 0
    }

    /**
     * Re-run note style providers against the most recently rendered score without
     * triggering a full layout+render pass. Call this when a provider's configuration
     * changes (e.g. a toggle in a plugin panel) to refresh note coloring.
     */
    fun reapplyNoteStyles() {
        applyNoteStyleProviders(lastComputedScore ?: return)
    }

    private fun applyNoteStyleProviders(computedScore: ComputedScore) = noteStyles.apply(computedScore)

    /**
     * Render from unified layout result with computed score.
     *
     * Each element's render() method produces its own RenderElements and SectionRegistrations,
     * eliminating manual type dispatch and section registration.
     *
     * @param layoutResult The unified layout result
     * @param computedScore The computed score for section registration
     * @return Complete render result
     */
    fun renderUnified(
        layoutResult: UnifiedLayoutResult,
        computedScore: ComputedScore,
        isCancelled: () -> Boolean = { false },
    ): RenderResult {
        val richElements = fullScoreRenderer.render(layoutResult, computedScore, pluginComponents)
        return finishAssembly(resultAssembler.assemble(richElements, layoutResult, isCancelled))
    }

    /**
     * Render only the systems in [systemFilter], producing a self-consistent [RenderResult] whose elements
     * cover exactly those systems.
     *
     * This is the building block for per-page / per-section streaming (Phase 3): the caller computes a
     * full [com.mecon.renderer.layout.UnifiedLayoutResult] once (so line-breaks and justification are
     * globally correct), then calls this method once per page/section to produce elements incrementally.
     *
     * The returned [RenderResult] has a complete spatial index and section index, but they are scoped
     * to the rendered systems only — do not use them for global hit-test or section queries across the
     * full score.
     *
     * For fully assembled (non-streaming) incremental re-renders, prefer [renderIncremental] or
     * [renderRange] which also reuse cached elements.
     */
    fun renderSystems(
        computed: ComputedScore,
        systemFilter: Set<Int>,
        pageWidth: StaffSpace = StaffSpace(100f),
        pageHeight: StaffSpace = StaffSpace(80f),
        pageGeometry: com.mecon.renderer.layout.PageGeometry =
            com.mecon.renderer.layout.PageGeometry.from(computed.runtime.pageLayout),
        isCancelled: () -> Boolean = { false },
    ): RenderResult {
        val computer = UnifiedLayoutComputer(config)
        val layout = computer.computeLayout(
            computed = computed,
            runtime = computed.runtime,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            pageGeometry = pageGeometry,
        )
        val richElements = fullScoreRenderer.render(layout, computed, pluginComponents, systemFilter = systemFilter)
        return finishAssembly(resultAssembler.assemble(richElements, layout, isCancelled))
    }

    private fun finishAssembly(assembly: RenderAssembly): RenderResult {
        lastResult = assembly.result
        lastRichElements = assembly.richElements
        lastPaginatedRichRuns = assembly.paginatedRichRuns
        lastAssembleIncremental = assembly.incremental
        hitTestService.updateIndex(assembly.result.spatialIndex, assembly.result.transformerSnapshot)
        return assembly.result
    }

    // Monotonic id source for splice-regenerated elements, kept distinct from reused cached ids
    // (which keep their identity) so a spliced result never has two elements sharing an id.
    private var spliceIdCounter = 0
    private fun nextSpliceId(): RenderElementId = RenderElementId.global(
        (0xFFFFFFL shl 24) or spliceIdCounter++.toLong()
    )

    fun computeGhost(
        result: RenderResult,
        runtime: RuntimeScore,
        point: AbsolutePoint,
        duration: com.mecon.api.primitive.Duration,
        accidental: com.mecon.api.primitive.Accidental?,
        restMode: Boolean,
        voiceNumber: Int = 1,
        tupletCount: Int? = null,
        graceMode: Boolean = false,
    ): com.mecon.renderer.render.edit.GhostNote? =
        editPreviewFacade.note.compute(
            result, runtime, point, duration, accidental, restMode, voiceNumber, tupletCount,
            graceMode,
        )

    fun computeClefGhost(
        result: RenderResult,
        runtime: RuntimeScore,
        point: AbsolutePoint,
        clef: com.mecon.api.storage.tracks.Clef,
    ): com.mecon.renderer.render.edit.GhostClef? =
        editPreviewFacade.clef.compute(result, runtime, point, clef)

    fun computeTimeSignatureGhost(
        result: RenderResult,
        runtime: RuntimeScore,
        point: AbsolutePoint,
        timeSignature: com.mecon.api.primitive.TimeSignature,
    ): com.mecon.renderer.render.edit.GhostTimeSignature? =
        editPreviewFacade.timeSignature.compute(result, runtime, point, timeSignature)

    fun computeKeySignatureGhost(
        result: RenderResult,
        runtime: RuntimeScore,
        point: AbsolutePoint,
        keySignature: com.mecon.api.primitive.KeySignature,
    ): com.mecon.renderer.render.edit.GhostKeySignature? =
        editPreviewFacade.keySignature.compute(result, runtime, point, keySignature)

    fun computeExpressionSpanGhost(
        result: RenderResult,
        runtime: RuntimeScore,
        staffTrackId: com.mecon.api.primitive.TrackId,
        start: com.mecon.api.primitive.TimeCode,
        end: com.mecon.api.primitive.TimeCode,
        kind: com.mecon.renderer.render.edit.ExpressionSpanKind,
    ): com.mecon.renderer.render.edit.GhostExpressionSpan? =
        editPreviewFacade.expressionSpan.compute(result, runtime, staffTrackId, start, end, kind)

    fun computePointSymbolGhost(
        result: RenderResult,
        runtime: RuntimeScore,
        staffTrackId: com.mecon.api.primitive.TrackId,
        onset: com.mecon.api.primitive.TimeCode,
        kind: com.mecon.renderer.render.edit.PointSymbolKind,
        absoluteX: Float? = null,
        systemIndex: Int? = null,
    ): com.mecon.renderer.render.edit.GhostPointSymbol? =
        editPreviewFacade.pointSymbol.compute(
            result, runtime, staffTrackId, onset, kind, absoluteX, systemIndex,
        )

    fun computeTransposePreview(
        result: RenderResult,
        runtime: RuntimeScore,
        computed: com.mecon.api.computed.ComputedScore,
        targets: Map<com.mecon.api.primitive.EventId, Set<Int>?>,
        stepDelta: Int,
    ): com.mecon.renderer.render.edit.TransposePreview? =
        editPreviewFacade.transpose.compute(result, runtime, computed, targets, stepDelta)

    /**
     * Build a transpose preview against the last complete render generation.
     *
     * Worker-backed clients intentionally do not keep a second ComputedScore beside the renderer;
     * using the renderer-owned frame keeps preview engraving on the same generation as the frozen
     * commands currently displayed.
     */
    fun computeTransposePreview(
        result: RenderResult,
        runtime: RuntimeScore,
        targets: Map<com.mecon.api.primitive.EventId, Set<Int>?>,
        stepDelta: Int,
    ): com.mecon.renderer.render.edit.TransposePreview? {
        val computed = lastComputedScore ?: return null
        if (lastResult !== result) return null
        return editPreviewFacade.transpose.compute(result, runtime, computed, targets, stepDelta)
    }

    fun computeRestMovePreview(
        result: RenderResult,
        computed: com.mecon.api.computed.ComputedScore,
        targets: Map<com.mecon.api.primitive.EventId, Int>,
    ): com.mecon.renderer.render.edit.TransposePreview? =
        editPreviewFacade.restMove.compute(result, computed, targets)

    /** Build a rest-move preview from the same renderer-owned frame as the displayed commands. */
    fun computeRestMovePreview(
        result: RenderResult,
        targets: Map<com.mecon.api.primitive.EventId, Int>,
    ): com.mecon.renderer.render.edit.TransposePreview? {
        val computed = lastComputedScore ?: return null
        if (lastResult !== result) return null
        return editPreviewFacade.restMove.compute(result, computed, targets)
    }

    /**
     * Get the thread-safe hit test service using the hierarchical spatial index.
     */
    fun getHitTestService(): HitTestService = hitTestService

    /**
     * Get the last render result.
     */
    fun getLastResult(): RenderResult? = lastResult

    /**
     * Get the style override manager for applying per-element style overrides.
     */
    fun getStyleOverrideManager(): StyleOverrideManager = styleOverrideManager

    companion object {
        /**
         * Create a render engine with default configuration.
         */
        context(BravuraFont)
        fun default(): RenderEngine = RenderEngine()
    }
}
