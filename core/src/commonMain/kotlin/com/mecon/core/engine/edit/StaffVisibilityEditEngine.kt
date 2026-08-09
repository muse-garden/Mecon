package com.mecon.core.engine.edit

import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.tracks.MeasureRange
import com.mecon.api.storage.tracks.MeasureRanges

/**
 * Immutable edits toggling per-staff measure visibility ([hiddenRanges]).
 *
 * Hiding is independent of system/page layout: a hidden range may span line
 * breaks. The renderer decides per line whether a hidden span is fully hidden
 * (collapsed dashed line) or partially hidden (grey region) — this engine only
 * mutates the stored ranges, keeping it pure and unit-testable.
 *
 * Row/line-scoped operations ("show on this line", "show on all following lines")
 * are resolved to concrete measure ranges by the desktop caller against the
 * current layout's system measure ranges; this engine receives ranges only.
 */
object StaffVisibilityEditEngine {

    private fun maxMeasure(score: RuntimeScore): Int? =
        score.measures.maxOfOrNull { it.value.number }

    /** Whether any targeted (staff, measure) cell carries a non-rest voice event. */
    fun hasNotesInCells(score: RuntimeScore, cells: Map<TrackId, Set<Int>>): Boolean =
        cells.any { (staffId, measures) ->
            val staff = score.staffTracks[staffId] ?: return@any false
            staff.getAllVoiceEvents().any { !it.isRest && it.onset.measure in measures }
        }

    /** Whether any of [staffIds] carries a non-rest voice event within [range]. */
    fun hasNotesInRegion(score: RuntimeScore, staffIds: Set<TrackId>, range: MeasureRange): Boolean =
        hasNotesInCells(score, staffIds.associateWith { range.measures.toSet() })

    /** Hide [range] on every staff in [staffIds]. Null if blocked (notes present) or a no-op. */
    fun hide(score: RuntimeScore, staffIds: Set<TrackId>, range: MeasureRange): RuntimeScore? =
        hideCells(score, staffIds.associateWith { range.measures.toSet() })

    /** Show (un-hide) [range] on every staff in [staffIds]. Null if a no-op. */
    fun show(score: RuntimeScore, staffIds: Set<TrackId>, range: MeasureRange): RuntimeScore? =
        showCells(score, staffIds.associateWith { range.measures.toSet() })

    /** Hide the specified per-staff measure cells. Blocked (null) when any cell has notes. */
    fun hideCells(score: RuntimeScore, cells: Map<TrackId, Set<Int>>): RuntimeScore? {
        val max = maxMeasure(score) ?: return null
        if (cells.isEmpty() || hasNotesInCells(score, cells)) return null
        return apply(score, cells, max, hide = true)
    }

    /** Show (un-hide) the specified per-staff measure cells. */
    fun showCells(score: RuntimeScore, cells: Map<TrackId, Set<Int>>): RuntimeScore? {
        val max = maxMeasure(score) ?: return null
        if (cells.isEmpty()) return null
        return apply(score, cells, max, hide = false)
    }

    private fun apply(
        score: RuntimeScore,
        cells: Map<TrackId, Set<Int>>,
        max: Int,
        hide: Boolean,
    ): RuntimeScore? {
        var changed = false
        val updated = score.staffTracks.mapValues { (id, staff) ->
            val measures = cells[id]?.filter { it in 1..max }?.toSet()
            if (measures.isNullOrEmpty()) return@mapValues staff
            val newRanges = toggle(staff.hiddenRanges, measures, max, hide)
            if (newRanges != staff.hiddenRanges) { changed = true; staff.copy(hiddenRanges = newRanges) }
            else staff
        }
        if (!changed) return null
        return score.replaceTracks(staffTracks = updated)
    }

    /** Add or remove [measures] (as merged contiguous runs) from [ranges], normalized into `[1, max]`. */
    private fun toggle(
        ranges: List<MeasureRange>,
        measures: Set<Int>,
        max: Int,
        hide: Boolean,
    ): List<MeasureRange> {
        var result = ranges
        for (run in contiguousRuns(measures)) {
            result = if (hide) MeasureRanges.add(result, MeasureRange(run.first, run.last), 1, max)
            else MeasureRanges.subtract(result, run.first, run.last)
        }
        return MeasureRanges.normalize(result, 1, max)
    }

    /** Collapse a measure set into ascending contiguous runs. */
    private fun contiguousRuns(measures: Set<Int>): List<IntRange> {
        val sorted = measures.sorted()
        if (sorted.isEmpty()) return emptyList()
        val runs = ArrayList<IntRange>()
        var start = sorted.first()
        var prev = start
        for (m in sorted.drop(1)) {
            if (m == prev + 1) prev = m
            else { runs.add(start..prev); start = m; prev = m }
        }
        runs.add(start..prev)
        return runs
    }
}
