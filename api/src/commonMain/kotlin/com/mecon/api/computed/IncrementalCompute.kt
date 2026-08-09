package com.mecon.api.computed

import com.mecon.api.primitive.EventId

/**
 * Build a [ComputeChangeSet] for the transition from [previous] to [current] by **structurally diffing
 * the two computed scores** — no recompute, no precomputed change set required. The computed-event diff
 * runs over the persistent event store ([ComputedEventStore.diffFrom]), so two scores derived from one
 * another (incremental edits, adjacent history states) diff in O(changes · log N).
 *
 * Used wherever the change set from `computeScoreIncremental` is absent or describes a different
 * transition than the one being rendered:
 *  - **undo / redo** — a history jump whose two endpoints are arbitrary states;
 *  - the renderer's **lineage fallback** — a coalesced render skipped the intermediate frame, so the
 *    displayed frame is not the edit's direct predecessor (see `docs/data_model/incremental-update.md` §3).
 *
 * Any notation or measure-structure difference sets [ComputeChangeSet.notationChanged] /
 * [ComputeChangeSet.structureReflow], which forces the renderer to a full layout — the incremental
 * layout only handles bounded voice-event edits, never barline / clef / signature / measure-count moves.
 */
fun computeChangeSetBetween(previous: ComputedScore, current: ComputedScore): ComputeChangeSet {
    val diff = current.computedEvents.diffFrom(previous.computedEvents)
    val notationChanged = notationDiffers(previous, current)
    val structureReflow = notationChanged || measureCount(previous) != measureCount(current)
    return ComputeChangeSet(
        addedEvents = diff.added,
        removedEvents = diff.removed,
        modifiedEvents = diff.modified,
        affectedMeasures = diff.affectedMeasures,
        notationChanged = notationChanged,
        structureReflow = structureReflow,
    )
}

/** Whether any notation list (barlines / clefs / keys / time signatures) differs by content. */
private fun notationDiffers(a: ComputedScore, b: ComputedScore): Boolean =
    listDiffers(a.barlines, b.barlines) || listDiffers(a.clefs, b.clefs) ||
        listDiffers(a.keySignatures, b.keySignatures) || listDiffers(a.timeSignatures, b.timeSignatures)

/** `!=` with an `===` fast-path — incremental compute reuses unchanged notation lists by reference. */
private fun <T> listDiffers(a: List<T>, b: List<T>): Boolean = a !== b && a != b

private fun measureCount(s: ComputedScore): Int = s.barlines.maxOfOrNull { it.measureNumber } ?: 0

/**
 * Describes what an incremental compute pass changed, so the renderer can decide how much to
 * re-layout / re-draw (see `docs/data_model/incremental-update.md` §2.7 / §3.1).
 *
 * The event-id sets partition the recomputed window relative to the previous [ComputedScore]:
 *  - [addedEvents]: ids present now but not before (newly authored notes, new implicit rests);
 *  - [removedEvents]: ids present before but not now (deleted notes, vanished implicit rests);
 *  - [modifiedEvents]: ids present in both whose computed fields differ.
 *
 * [affectedMeasures] is the actual recompute window (after group-boundary expansion). It is the
 * measure span the renderer must consider for a local update.
 */
data class ComputeChangeSet(
    val addedEvents: Set<EventId>,
    val removedEvents: Set<EventId>,
    val modifiedEvents: Set<EventId>,
    /** Recompute window in measures (inclusive). Empty range when nothing changed. */
    val affectedMeasures: IntRange,
    /** True when any barline / clef / key / time-signature element changed. */
    val notationChanged: Boolean = false,
    /**
     * True when notation within the affected range changed in a way that needs the renderer to
     * reflow (e.g. the final-barline position moved because the score's last event changed).
     * Structural edits that change measure count or time signatures are out of scope for the
     * incremental path (decision Q4) and must be driven through a full recompute by the caller.
     */
    val structureReflow: Boolean = false,
) {
    /** All event ids touched by this change (added ∪ removed ∪ modified). */
    val touchedEvents: Set<EventId> get() = addedEvents + removedEvents + modifiedEvents

    /**
     * Gate for the renderer's measure-granular incremental layout (`docs/data_model/incremental-update.md`
     * §3.2): true when the edit changed neither notation nor measure structure and touched a bounded
     * measure window, so the renderer can re-solve only [affectedMeasures] and translate the rest.
     *
     * Width-changing edits (accidental added, duration changed) and insertions/deletions are still
     * incremental — they are confined to [affectedMeasures], which is re-solved; only structural
     * reflow / notation changes force a full layout.
     */
    val allowsIncrementalLayout: Boolean
        get() = !notationChanged && !structureReflow && !affectedMeasures.isEmpty()

    /** True when no computed event or notation element changed. */
    val isEmpty: Boolean
        get() = addedEvents.isEmpty() && removedEvents.isEmpty() &&
            modifiedEvents.isEmpty() && !notationChanged

    companion object {
        val EMPTY = ComputeChangeSet(
            addedEvents = emptySet(),
            removedEvents = emptySet(),
            modifiedEvents = emptySet(),
            affectedMeasures = IntRange.EMPTY,
        )

        /**
         * Construct a [ComputeChangeSet] that tells the renderer to re-render exactly [measures] without
         * any compute-level change. [allowsIncrementalLayout] will be true, so both splicers engage.
         *
         * Use this when a plugin or music theory analysis invalidates a known measure span independently
         * of an edit (e.g. chord analysis refresh, style override update) and you want to trigger a
         * bounded re-render via [com.mecon.renderer.render.RenderEngine.renderRange].
         */
        fun forRange(measures: IntRange): ComputeChangeSet = ComputeChangeSet(
            addedEvents = emptySet(),
            removedEvents = emptySet(),
            modifiedEvents = emptySet(),
            affectedMeasures = measures,
            notationChanged = false,
            structureReflow = false,
        )
    }
}

/**
 * Result of an incremental compute pass: the freshly merged [computed] score plus the
 * [changeSet] describing what differs from the previous one.
 */
data class IncrementalComputeResult(
    val computed: ComputedScore,
    val changeSet: ComputeChangeSet,
)
