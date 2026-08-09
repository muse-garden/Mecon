package com.mecon.api.state

import com.mecon.api.computed.ComputeChangeSet
import com.mecon.api.computed.ComputedScore
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore

/**
 * Hint describing how a [ScoreState] was produced incrementally from its predecessor, so the render
 * pipeline can drive [com.mecon.renderer]'s `renderIncremental` (reuse layout / splice elements) instead
 * of a full render. See `docs/data_model/incremental-update.md` §3.
 *
 * [previousComputed] is the exact [ComputedScore] the edit was computed against. The render pipeline
 * only trusts [changeSet] when the engine's last-rendered computed is **that same instance** — i.e. the
 * displayed frame really is this state's direct predecessor. When renders are coalesced (a fast burst of
 * edits skips an intermediate frame) the identity won't match and the pipeline falls back to a full
 * render, keeping the incremental layout's "reuse the previous frame's X" assumption sound.
 *
 * Absent (null on [ScoreState]) for non-incremental transitions — document load, undo/redo, plugin and
 * view-preference edits — which always render in full.
 */
data class RenderHint(
    val previousComputed: ComputedScore,
    val changeSet: ComputeChangeSet,
)

/**
 * Global state containing the current score's editable and computed representations.
 *
 * This provides a single unified view of the score that plugins and UI components
 * can observe and query.
 *
 * [renderHint], when present, lets the render pipeline update incrementally (see [RenderHint]); it is
 * transient render metadata about the transition that produced this state, not part of the score itself.
 */
data class ScoreState(
    val runtimeScore: RuntimeScore,
    val computedScore: ComputedScore,
    val renderHint: RenderHint? = null
) {
    /**
     * Helper to get a plugin's computed track by its ID.
     */
    fun getPluginTrack(id: TrackId) = computedScore.getPluginTrack(id)
}
