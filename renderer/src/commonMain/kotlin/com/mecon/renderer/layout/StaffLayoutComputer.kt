package com.mecon.renderer.layout

import com.mecon.renderer.geometry.StaffSpace

/**
 * Computes staff Y positions based on each staff's actual occupied vertical
 * range (notes, stems and ledger lines above/below the five lines), separated
 * by a configurable gap.
 */
internal class StaffLayoutComputer(
    private val config: RenderLayoutConfig
) {
    fun calculateStaffYPositions(
        staffTracks: List<StaffInfo>,
        voiceEventLayouts: List<VoiceEventLayout>,
        startY: StaffSpace,
        /** Extra vertical room per staff index for attachments (dynamics, hairpins). */
        extraExtents: Map<Int, AttachmentExtent> = emptyMap(),
        /**
         * Pre-aggregated per-(measure → staffIndex) extents ([extentsByMeasureStaff]). When supplied, the
         * global per-staff extent is the per-staff max over measures instead of a fresh scan of every
         * [VoiceEventLayout] (see docs/renderer/incremental-rendering.md, `staffY`). This is byte-identical to scanning:
         * [calculateExtents] is a max-reduction floored at 2, so max-over-measures == max-over-all-events;
         * and a note's [VoiceEventLayout.trackId] is its **staff** track (both trackId and staffIndex come
         * from one [StaffInfo]), so the old `trackId && staffIndex` filter is exactly a staffIndex filter.
         * Reuses the cache already built for line-local layout (and, on the incremental path, itself
         * partly reused) rather than a 5th full-score scan. Null ⇒ scan [voiceEventLayouts] (fallback).
         */
        measureExtents: Map<Int, Map<Int, Pair<StaffSpace, StaffSpace>>>? = null
    ): List<StaffLayoutInfo> {
        if (staffTracks.isEmpty()) return emptyList()
        val sortedStaffs = staffTracks.sortedWith(
            compareBy({ it.partIndex }, { it.staffIndex })
        )
        // Per-staff note extents over ALL supplied events (global / continuous semantics).
        val noteExtents = if (measureExtents != null) {
            perStaffExtents(measureExtents)
        } else sortedStaffs.associate { staffInfo ->
            val staffEvents = voiceEventLayouts.filter {
                it.trackId == staffInfo.trackId && it.staffIndex == staffInfo.staffIndex
            }
            staffInfo.staffIndex to calculateExtents(staffEvents)
        }
        return stackStaves(sortedStaffs, noteExtents, startY, extraExtents)
    }

    /**
     * Collapse per-(measure → staffIndex) extents to a global per-staffIndex extent by taking, for each
     * staff, the max top/bottom over all measures. Equivalent to [calculateExtents] over every event on
     * that staff (max-reduction), but O(measures × staves) over the existing cache. Staves absent from
     * [measureExtents] are simply omitted — [stackStaves] falls back to the bare (2, 2) staff for them.
     */
    fun perStaffExtents(
        measureExtents: Map<Int, Map<Int, Pair<StaffSpace, StaffSpace>>>
    ): Map<Int, Pair<StaffSpace, StaffSpace>> {
        val acc = mutableMapOf<Int, Pair<StaffSpace, StaffSpace>>()
        for (perStaff in measureExtents.values) {
            for ((staffIndex, ext) in perStaff) {
                val cur = acc[staffIndex]
                acc[staffIndex] = if (cur == null) ext else {
                    val top = if (cur.first > ext.first) cur.first else ext.first
                    val bottom = if (cur.second > ext.second) cur.second else ext.second
                    top to bottom
                }
            }
        }
        return acc
    }

    /**
     * Vertically stack a sorted list of staves from pre-computed per-staff note extents.
     *
     * This is the pure geometry primitive shared by the global/continuous path
     * ([calculateStaffYPositions]) and the per-line paginated path
     * ([SystemBreaker]'s vertical pass): each staff occupies `[centre - topExtent,
     * centre + bottomExtent]`, staves are separated by [RenderLayoutConfig.interStaffGap]
     * (same part) or [RenderLayoutConfig.interPartGap] (across parts), and [extraExtents]
     * reserves additional room above/below for attachments.
     *
     * @param sortedStaffs staves already sorted by (partIndex, staffIndex).
     * @param noteExtents staffIndex → (topExtent, bottomExtent), both ≥ 2 staff-spaces.
     *   A missing entry falls back to the bare five-line staff (2, 2).
     */
    fun stackStaves(
        sortedStaffs: List<StaffInfo>,
        noteExtents: Map<Int, Pair<StaffSpace, StaffSpace>>,
        startY: StaffSpace,
        extraExtents: Map<Int, AttachmentExtent> = emptyMap()
    ): List<StaffLayoutInfo> {
        if (sortedStaffs.isEmpty()) return emptyList()

        val layouts = mutableListOf<StaffLayoutInfo>()
        var prevContentBottomY: StaffSpace? = null
        var prevPartIndex: Int? = null

        for (staffInfo in sortedStaffs) {
            val (noteTopExtent, noteBottomExtent) =
                noteExtents[staffInfo.staffIndex] ?: (StaffSpace(2f) to StaffSpace(2f))
            val extra = extraExtents[staffInfo.staffIndex]
            val topExtent = noteTopExtent + (extra?.extraTop ?: StaffSpace.ZERO)
            val bottomExtent = noteBottomExtent + (extra?.extraBottom ?: StaffSpace.ZERO)

            val staffCenterY = if (prevContentBottomY == null) {
                startY + topExtent
            } else {
                val gap = if (prevPartIndex == staffInfo.partIndex) {
                    config.interStaffGap
                } else {
                    config.interPartGap
                }
                prevContentBottomY + gap + topExtent
            }

            val contentTopY = staffCenterY - topExtent
            val contentBottomY = staffCenterY + bottomExtent

            layouts.add(StaffLayoutInfo(
                trackId = staffInfo.trackId,
                staffIndex = staffInfo.staffIndex,
                partIndex = staffInfo.partIndex,
                centerY = staffCenterY,
                topY = staffCenterY - StaffSpace(2f),
                bottomY = staffCenterY + StaffSpace(2f),
                contentTopY = contentTopY,
                contentBottomY = contentBottomY,
                clef = staffInfo.clef
            ))

            prevContentBottomY = contentBottomY
            prevPartIndex = staffInfo.partIndex
        }

        return layouts
    }

    /**
     * Per-(measure, staffIndex) note vertical extent, for line-local vertical layout.
     *
     * Groups [layouts] by their measure number and staff index, then applies
     * [calculateExtents] to each bucket. A line's note extent for a staff is the
     * per-staff max over the measures it contains (computed by the caller). Mirrors
     * the per-measure width cache the [SystemBreaker] keeps. Paginated consumers query the persistent
     * [MeasureVerticalExtentTree], so an edit patches only its measure window and a system max is a
     * subtree range aggregate rather than a measure scan.
     *
     * @return measure → (staffIndex → (topExtent, bottomExtent)).
     */
    fun extentsByMeasureStaff(
        layouts: List<VoiceEventLayout>
    ): Map<Int, Map<Int, Pair<StaffSpace, StaffSpace>>> =
        layouts.groupBy { it.measureNumber }
            .mapValues { (_, measureLayouts) -> extentsForMeasure(measureLayouts) }

    /**
     * Incremental [extentsByMeasureStaff] (see docs/renderer/incremental-rendering.md, `step5`/`extent` bucket): recompute
     * a measure's per-staff extent only when it is in the re-solve [window]; every other measure reuses
     * [cached] verbatim, skipping the [calculateExtents] scan over its [VoiceEventLayout]s.
     *
     * Correctness: on the incremental layout path an out-of-window measure's [VoiceEventLayout]s are
     * byte-identical to the previous frame (step 5 reuses them by event id), so their extent is unchanged
     * — the cached value equals a fresh compute, making the result identical to the full pass. A measure
     * absent from [cached] (a measure that only just appeared outside the window — not produced by a
     * contiguous-window edit, but handled for safety) falls back to a fresh compute.
     */
    fun extentsByMeasureStaffIncremental(
        layouts: List<VoiceEventLayout>,
        cached: Map<Int, Map<Int, Pair<StaffSpace, StaffSpace>>>,
        window: IntRange
    ): Map<Int, Map<Int, Pair<StaffSpace, StaffSpace>>> {
        // The incremental contract guarantees that measures outside [window] are byte-identical to
        // [cached]. Do not group every layout in the score merely to select that cached value again.
        // Removing all window keys first also handles a deletion that leaves a measure with no notes.
        val result = cached.toMutableMap()
        for (measure in window) result.remove(measure)
        layouts.asSequence()
            .filter { it.measureNumber in window }
            .groupBy { it.measureNumber }
            .forEach { (measure, measureLayouts) ->
                result[measure] = extentsForMeasure(measureLayouts)
            }
        return result
    }

    private fun extentsForMeasure(
        measureLayouts: List<VoiceEventLayout>
    ): Map<Int, Pair<StaffSpace, StaffSpace>> =
        measureLayouts.groupBy { it.staffIndex }
            .mapValues { (_, staffLayouts) -> calculateExtents(staffLayouts) }

    /**
     * Calculate how far above and below the staff center notes extend.
     * Minimum is the staff itself (2 staff spaces each side of center for a
     * 5-line staff).
     *
     * @return Pair of (topExtent, bottomExtent) in staff spaces, both positive.
     */
    fun calculateExtents(events: List<VoiceEventLayout>): Pair<StaffSpace, StaffSpace> {
        var topExtent = StaffSpace(2f)
        var bottomExtent = StaffSpace(2f)

        for (event in events) {
            val primaryY = event.primary.relativeY
            if (-primaryY > topExtent) topExtent = StaffSpace(-primaryY.value)
            if (primaryY > bottomExtent) bottomExtent = primaryY

            // A cross-staff note's stem reaches the beam in the inter-staff gap, not beyond the
            // staff — counting it here would double-count the gap and over-spread the staves.
            if (event.crossStaffOffset == 0) {
                event.stem?.let { stem ->
                    if (-stem.topY > topExtent) topExtent = StaffSpace(-stem.topY.value)
                    if (stem.bottomY > bottomExtent) bottomExtent = stem.bottomY
                }
            }

            for (ledger in event.ledgerLines) {
                if (-ledger.relativeY > topExtent) topExtent = StaffSpace(-ledger.relativeY.value)
                if (ledger.relativeY > bottomExtent) bottomExtent = ledger.relativeY
            }
        }

        return topExtent to bottomExtent
    }

    /** Per-event form of [calculateExtents], avoiding singleton-list allocation in extent indexes. */
    fun calculateExtent(event: VoiceEventLayout): Pair<StaffSpace, StaffSpace> {
        var topExtent = StaffSpace(2f)
        var bottomExtent = StaffSpace(2f)
        val primaryY = event.primary.relativeY
        if (-primaryY > topExtent) topExtent = StaffSpace(-primaryY.value)
        if (primaryY > bottomExtent) bottomExtent = primaryY

        if (event.crossStaffOffset == 0) {
            event.stem?.let { stem ->
                if (-stem.topY > topExtent) topExtent = StaffSpace(-stem.topY.value)
                if (stem.bottomY > bottomExtent) bottomExtent = stem.bottomY
            }
        }
        for (ledger in event.ledgerLines) {
            if (-ledger.relativeY > topExtent) topExtent = StaffSpace(-ledger.relativeY.value)
            if (ledger.relativeY > bottomExtent) bottomExtent = ledger.relativeY
        }
        return topExtent to bottomExtent
    }
}
