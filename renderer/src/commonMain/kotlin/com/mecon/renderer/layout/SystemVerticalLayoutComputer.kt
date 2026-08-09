package com.mecon.renderer.layout

import com.mecon.renderer.geometry.StaffSpace

internal class SystemVerticalLayoutComputer(
    private val config: RenderLayoutConfig,
    private val measureVerticalExtentTree: MeasureVerticalExtentTree,
) {
    private val staffLayoutComputer = StaffLayoutComputer(config)
    /** Per-line vertical placement: this line's staves (Y already offset) + page index + the Y shift applied. */
    internal data class LineVertical(
        val staffLayouts: List<StaffLayoutInfo>,
        val yOffset: StaffSpace,
        val pageIndex: Int,
    )

    /**
     * Stack staves and paginate **per line**, from each line's own vertical extent.
     *
     * For each line: its note extent for a staff is the per-staff max of [perMeasureExtents] over the
     * measures the line contains (a staff with no events on the line falls back to the bare five lines);
     * attachment room ([perSystemAttachmentExtents], keyed by systemIndex → staffIndex) adds to it. The
     * staves are stacked from those extents (`startY = 0`, so positions are relative to the line top),
     * then the whole line is shifted down by [LineVertical.yOffset] so its top sits at the running page
     * cursor. Pagination overflow is judged against **each line's own height** — a tall line (high notes,
     * dynamics) takes more room, a plain line less — replacing the old single global height. Returns the
     * per-line placement and the page list.
     */
    /**
     * Staves visible on a line: those not fully hidden over [range]. Fully-hidden staves collapse out
     * of the line (a merged dashed [HiddenStaffMarker] is drawn in the gap). A line whose every staff is
     * hidden keeps them (never returns empty) so the line still stacks/paginates — the desktop greys such
     * a line instead of collapsing it to nothing.
     */
    fun visibleStaves(sortedStaffs: List<StaffInfo>, range: IntRange): List<StaffInfo> =
        sortedStaffs.filter { !it.isFullyHiddenOver(range) }.ifEmpty { sortedStaffs }

    fun full(
        lineMeasureRanges: List<IntRange>,
        staffTracks: List<StaffInfo>,
        perMeasureExtents: Map<Int, Map<Int, Pair<StaffSpace, StaffSpace>>>,
        perSystemAttachmentExtents: Map<Int, Map<Int, AttachmentExtent>>,
        pageGeometry: PageGeometry,
        forcedPageBreaks: Set<Int>,
        titleBlockHeight: StaffSpace,
        annotationLineExtents: (IntRange) -> AnnotationLineExtents = { AnnotationLineExtents.ZERO },
    ): Pair<List<LineVertical>, List<PageLayout>> {
        val sortedStaffs = staffTracks.sortedWith(compareBy({ it.partIndex }, { it.staffIndex }))
        val verticals = mutableListOf<LineVertical>()
        val pageOf = mutableMapOf<Int, Int>()

        var pageIndex = 0
        // Page 0 starts below the title block; later pages reset to the plain top margin,
        // so the title only reserves room on page 0.
        var withinPageTop = pageGeometry.topMargin + titleBlockHeight

        for ((lineIdx, range) in lineMeasureRanges.withIndex()) {
            val noteExtents = extents(range)
            val attach = perSystemAttachmentExtents[lineIdx] ?: emptyMap()
            val staves = staffLayoutComputer.stackStaves(
                visibleStaves(sortedStaffs, range), noteExtents, StaffSpace.ZERO, attach
            )
            val lineTop = staves.minOf { it.contentTopY }
            val annotation = annotationLineExtents(range)
            // Annotation bands are reserved on both sides of the notation. The top band shifts the
            // notation down; both bands grow the system footprint used for pagination and the next
            // line's offset. This must match AnnotationStaffLayoutComputer.computePaginated.
            val lineHeight =
                (staves.maxOf { it.contentBottomY } - lineTop) + annotation.total

            // Pagination placement (judged against THIS line's height).
            val forcedPage = range.first in forcedPageBreaks
            if (lineIdx > 0) {
                val overflow = withinPageTop + lineHeight >
                    pageGeometry.topMargin + pageGeometry.pageContentHeight
                if (forcedPage || overflow) {
                    pageIndex++
                    withinPageTop = pageGeometry.topMargin
                }
            }
            pageOf[lineIdx] = pageIndex
            val pageOriginY = StaffSpace(
                pageIndex * (pageGeometry.paperHeight.value + pageGeometry.pageGap.value)
            )
            val yOffset = (pageOriginY + withinPageTop + annotation.above) - lineTop
            verticals.add(LineVertical(staves.map { it.shiftedBy(yOffset) }, yOffset, pageIndex))
            withinPageTop += lineHeight + pageGeometry.systemGap
        }

        val pages = pageOf.values.distinct().sorted().map { pi ->
            PageLayout(
                pageIndex = pi,
                originY = StaffSpace(pi * (pageGeometry.paperHeight.value + pageGeometry.pageGap.value)),
                width = pageGeometry.paperWidth,
                height = pageGeometry.paperHeight,
            )
        }
        return verticals to pages
    }

    private fun cachedVerticals(systems: List<SystemLayout>): List<LineVertical> =
        systems.map { LineVertical(it.staffLayouts, it.yOffset, it.pageIndex) }

    /**
     * Recompute vertical placement from the beginning of the affected cached page, then propagate page by
     * page. Once an unaffected cached page start is reached at the exact same page/top coordinate, the
     * remaining tail is identical and is reused without visiting it.
     */
    fun incremental(
        lineMeasureRanges: List<IntRange>,
        staffTracks: List<StaffInfo>,
        perSystemAttachmentExtents: Map<Int, Map<Int, AttachmentExtent>>,
        pageGeometry: PageGeometry,
        forcedPageBreaks: Set<Int>,
        titleBlockHeight: StaffSpace,
        annotationLineExtents: (IntRange) -> AnnotationLineExtents,
        cachedSystems: List<SystemLayout>,
        affectedSystems: Set<Int>,
    ): IncrementalVerticalResult? {
        if (cachedSystems.size != lineMeasureRanges.size || affectedSystems.isEmpty()) return null
        if (affectedSystems.any { it !in lineMeasureRanges.indices }) return null
        for (i in lineMeasureRanges.indices) {
            if (cachedSystems[i].measureRange != lineMeasureRanges[i]) return null
        }
        val sortedStaffs = staffTracks.sortedWith(compareBy({ it.partIndex }, { it.staffIndex }))
        val out = cachedVerticals(cachedSystems).toMutableList()
        val firstAffected = affectedSystems.min()
        val lastAffected = affectedSystems.max()
        val startPage = cachedSystems[firstAffected].pageIndex
        val startLine = cachedSystems.indexOfFirst { it.pageIndex == startPage }
        if (startLine < 0) return null

        var pageIndex = startPage
        var withinPageTop = pageGeometry.topMargin +
            if (pageIndex == 0) titleBlockHeight else StaffSpace.ZERO
        var visited = 0
        for (lineIdx in startLine..lineMeasureRanges.lastIndex) {
            val cached = cachedSystems[lineIdx]
            val range = lineMeasureRanges[lineIdx]
            val isAffected = lineIdx in affectedSystems
            val sourceStaves = if (isAffected) {
                staffLayoutComputer.stackStaves(
                    visibleStaves(sortedStaffs, range), extents(range), StaffSpace.ZERO,
                    perSystemAttachmentExtents[lineIdx] ?: emptyMap(),
                )
            } else cached.staffLayouts
            if (sourceStaves.isEmpty()) return null
            val sourceTop = sourceStaves.minOf { it.contentTopY }
            val annotation = annotationLineExtents(range)
            val lineHeight = (sourceStaves.maxOf { it.contentBottomY } - sourceTop) +
                annotation.total

            if (lineIdx > startLine) {
                val forcedPage = range.first in forcedPageBreaks
                val overflow = withinPageTop + lineHeight >
                    pageGeometry.topMargin + pageGeometry.pageContentHeight
                if (forcedPage || overflow) {
                    pageIndex++
                    withinPageTop = pageGeometry.topMargin
                }
            }
            val pageOriginY = StaffSpace(pageIndex *
                (pageGeometry.paperHeight.value + pageGeometry.pageGap.value))
            val targetTop = pageOriginY + withinPageTop + annotation.above
            val shift = targetTop - sourceTop
            val yOffset = if (isAffected) shift else cached.yOffset + shift
            val placed = sourceStaves.map { it.shiftedBy(shift) }
            out[lineIdx] = LineVertical(placed, yOffset, pageIndex)
            withinPageTop += lineHeight + pageGeometry.systemGap
            visited++

            // The common no-Y-change case converges at the affected line itself. Data-class equality is
            // deliberately exact (including every Float); accepting an epsilon here could reuse a tail
            // whose prefix cursor differs from a cold vertical pass.
            if (lineIdx >= lastAffected && out[lineIdx] ==
                LineVertical(cached.staffLayouts, cached.yOffset, cached.pageIndex)
            ) break

            // Page reset is the exact floating-point convergence point: no prefix sum crosses it.
            // If this unaffected cached page-start landed at the same coordinates, the untouched tail is
            // bit-for-bit reusable. We intentionally do not accept epsilon convergence here.
            val cachedTop = cached.staffLayouts.minOf { it.contentTopY }
            val atCachedPageStart = lineIdx > lastAffected &&
                (lineIdx == 0 || cachedSystems[lineIdx - 1].pageIndex != cached.pageIndex)
            if (atCachedPageStart && pageIndex == cached.pageIndex &&
                targetTop.value.toBits() == cachedTop.value.toBits()
            ) break
        }
        val pages = out.asSequence().map { it.pageIndex }.distinct().sorted().map { page ->
            PageLayout(
                pageIndex = page,
                originY = StaffSpace(page * (pageGeometry.paperHeight.value + pageGeometry.pageGap.value)),
                width = pageGeometry.paperWidth,
                height = pageGeometry.paperHeight,
            )
        }.toList()
        return IncrementalVerticalResult(out, pages, visited)
    }

    internal data class IncrementalVerticalResult(
        val verticals: List<LineVertical>,
        val pages: List<PageLayout>,
        val visitedSystems: Int,
    )

    /** Per-staff note extent for one line: the per-staff max of [perMeasureExtents] over [range]'s measures. */
    fun extents(
        range: IntRange,
    ): Map<Int, Pair<StaffSpace, StaffSpace>> = measureVerticalExtentTree.extent(range)

    /** Per-(systemIndex → staffIndex) note extent for every system — the baseline the attachment placer stacks from. */
    fun perSystemNoteExtents(
        systems: List<SystemLayout>,
    ): Map<Int, Map<Int, Pair<StaffSpace, StaffSpace>>> =
        systems.associate { it.systemIndex to extents(it.measureRange) }

}
