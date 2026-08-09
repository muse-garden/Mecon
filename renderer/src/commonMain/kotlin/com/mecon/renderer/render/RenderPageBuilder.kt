package com.mecon.renderer.render

import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.layout.PageLayout
import com.mecon.renderer.layout.UnifiedLayoutResult
import com.mecon.renderer.render.spatial.YBandRouting

/** Builds page-local render element groups for paginated results. */
internal class RenderPageBuilder(
    private val transformer: CoordinateTransformer,
) {
    fun build(
        elements: List<RenderElement>,
        layoutResult: UnifiedLayoutResult
    ): List<RenderPage> {
        data class Band(val page: PageLayout, val topPx: Float, val bottomPx: Float)
        val bands = layoutResult.pages.map { p ->
            val topPx = transformer.toPixels(p.originY).value
            val bottomPx = transformer.toPixels(p.originY + p.height).value
            Band(p, topPx, bottomPx)
        }
        val pageBySystem = layoutResult.systems.associate { it.systemIndex to it.pageIndex }
        val buckets = HashMap<Int, MutableList<RenderElement>>()
        for (el in elements) {
            val pageIndex = el.systemIndex?.let(pageBySystem::get) ?: run {
                val cy = el.hitBox.center.y.value
                YBandRouting.nearest(bands, cy, { it.topPx }, { it.bottomPx })?.page?.pageIndex
            } ?: continue
            buckets.getOrPut(pageIndex) { mutableListOf() }.add(el)
        }
        return bands.map { b ->
            val originYpx = b.topPx
            RenderPage(
                pageIndex = b.page.pageIndex,
                width = transformer.toPixels(b.page.width),
                height = transformer.toPixels(b.page.height),
                contentOffsetY = Pixels(originYpx),
                elements = (buckets[b.page.pageIndex] ?: emptyList()).map { it.translatedBy(0f, -originYpx) }
            )
        }
    }

    /**
     * Build a single [RenderPage] from [elements] that all belong to [pageLayout].
     *
     * Unlike [build] (which band-routes every element to its page), this method skips routing
     * entirely and translates all [elements] to page-local coordinates directly. Safe to call when
     * the caller already knows the elements belong to exactly one page — e.g. the streaming path
     * in [com.mecon.renderer.render.RenderEngine.renderStreaming], where [FullScoreRenderer.render]
     * was called with `systemFilter` restricted to the systems of one page.
     */
    fun buildForPage(elements: List<RenderElement>, pageLayout: PageLayout): RenderPage {
        val originYpx = transformer.toPixels(pageLayout.originY).value
        return RenderPage(
            pageIndex = pageLayout.pageIndex,
            width = transformer.toPixels(pageLayout.width),
            height = transformer.toPixels(pageLayout.height),
            contentOffsetY = Pixels(originYpx),
            elements = elements.map { it.translatedBy(0f, -originYpx) },
        )
    }

    /**
     * Incremental page build for the paginated splice: re-slice only [affectedPages] from the fresh
     * [elements]; reuse the cached [RenderPage] **by reference** (same `.elements` instance) for every
     * other page.
     *
     * Reuse is sound because page geometry is paper-fixed: [PageLayout.originY] / width / height are
     * `pageIndex * paper-stacking`, independent of content (see `SystemBreaker`). So in a non-reflow edit
     * an unaffected page's [RenderPage.contentOffsetY] and page-local [RenderPage.elements] are identical
     * to the cached frame. Keeping the same `.elements` reference lets the per-page Skia cache
     * ([com.mecon.desktop] `drawCachedPage`) replay the cached picture instead of re-recording every glyph.
     *
     * A page is "affected" when it contains a regenerated system (in the splice window) or any system that
     * shifted vertically (Δy ≠ 0); the caller derives [affectedPages] from those. Within a page the
     * downward propagation of a height change makes Δy ≠ 0 for following systems, so the whole page is
     * re-sliced — which is correct and still bounded to (usually) the single edited page.
     *
     * Returns null when the page partition shape changed (page count differs, or a cached page's origin
     * moved) — i.e. a reflow or zoom — so the caller falls back to a full [build].
     */
    fun buildIncremental(
        richElements: List<RichElement>,
        richRuns: List<PaginatedRichRun>,
        layoutResult: UnifiedLayoutResult,
        cached: List<RenderPage>,
        affectedPages: Set<Int>,
    ): List<RenderPage>? {
        if (richRuns.isEmpty()) return null
        if (cached.isEmpty() || cached.size != layoutResult.pages.size) return null
        val cachedByIndex = cached.associateBy { it.pageIndex }
        val pageBySystem = layoutResult.systems.associate { it.systemIndex to it.pageIndex }
        for (page in layoutResult.pages) {
            val cachedPage = cachedByIndex[page.pageIndex] ?: return null
            if (cachedPage.contentOffsetY != transformer.toPixels(page.originY)) return null
        }
        val buckets = HashMap<Int, MutableList<RenderElement>>()
        for (run in richRuns) {
            val directPage = run.systemIndex?.let(pageBySystem::get)
            if (directPage != null) {
                if (directPage !in affectedPages) continue
                val bucket = buckets.getOrPut(directPage) { ArrayList() }
                for (index in run.fromIndex until run.toIndexExclusive) {
                    bucket.add(richElements[index].element)
                }
            } else {
                // Page-global elements are rare (e.g. title block); route only these geometrically.
                for (index in run.fromIndex until run.toIndexExclusive) {
                    val element = richElements[index].element
                    val page = layoutResult.pages.minByOrNull { p ->
                        val top = transformer.toPixels(p.originY).value
                        val bottom = transformer.toPixels(p.originY + p.height).value
                        val y = element.hitBox.center.y.value
                        when {
                            y < top -> top - y
                            y > bottom -> y - bottom
                            else -> 0f
                        }
                    } ?: continue
                    if (page.pageIndex in affectedPages) {
                        buckets.getOrPut(page.pageIndex) { ArrayList() }.add(element)
                    }
                }
            }
        }
        return layoutResult.pages.map { page ->
            if (page.pageIndex !in affectedPages) cachedByIndex.getValue(page.pageIndex)
            else {
                val originY = transformer.toPixels(page.originY).value
                RenderPage(
                    page.pageIndex,
                    transformer.toPixels(page.width),
                    transformer.toPixels(page.height),
                    Pixels(originY),
                    buckets[page.pageIndex].orEmpty().map { it.translatedBy(0f, -originY) },
                )
            }
        }
    }

    fun buildIncremental(
        elements: List<RenderElement>,
        layoutResult: UnifiedLayoutResult,
        cached: List<RenderPage>,
        affectedPages: Set<Int>,
    ): List<RenderPage>? {
        if (cached.isEmpty() || cached.size != layoutResult.pages.size) return null
        val cachedByIndex = cached.associateBy { it.pageIndex }

        data class Band(val page: PageLayout, val topPx: Float, val bottomPx: Float)
        val bands = layoutResult.pages.map { p ->
            val topPx = transformer.toPixels(p.originY).value
            val bottomPx = transformer.toPixels(p.originY + p.height).value
            Band(p, topPx, bottomPx)
        }
        // Defensive: a cached page must exist for every page and sit at the same (paper-fixed) origin.
        // Any mismatch (reflow / zoom change) → bail to a full build.
        for (b in bands) {
            val c = cachedByIndex[b.page.pageIndex] ?: return null
            if (c.contentOffsetY.value != b.topPx) return null
        }

        // Bucket only the elements routing to an affected page (page-local translate applied there);
        // unaffected pages are reused wholesale, so their elements need no routing.
        // Almost every score element already carries its system index. Resolve those directly through the
        // tiny system→page table instead of running a geometric nearest-band search against all 69 pages
        // for every one of 66k elements. Only genuinely page-global elements (e.g. the title block) fall
        // back to Y-band routing. This keeps the current stable element ordering while removing the hot
        // O(elements × page-band lookup) work; replacing the remaining cheap O(elements) membership pass
        // with persistent per-page buckets is the later structural step.
        val pageBySystem = layoutResult.systems.associate { it.systemIndex to it.pageIndex }
        val buckets = HashMap<Int, MutableList<RenderElement>>()
        for (el in elements) {
            val pageIndex = el.systemIndex?.let(pageBySystem::get) ?: run {
                val cy = el.hitBox.center.y.value
                YBandRouting.nearest(bands, cy, { it.topPx }, { it.bottomPx })?.page?.pageIndex
            } ?: continue
            if (pageIndex !in affectedPages) continue
            buckets.getOrPut(pageIndex) { mutableListOf() }.add(el)
        }

        return bands.map { b ->
            val pageIndex = b.page.pageIndex
            if (pageIndex in affectedPages) {
                val originYpx = b.topPx
                RenderPage(
                    pageIndex = pageIndex,
                    width = transformer.toPixels(b.page.width),
                    height = transformer.toPixels(b.page.height),
                    contentOffsetY = Pixels(originYpx),
                    elements = (buckets[pageIndex] ?: emptyList()).map { it.translatedBy(0f, -originYpx) }
                )
            } else {
                cachedByIndex.getValue(pageIndex) // reuse by reference → per-page Skia cache hit
            }
        }
    }
}
