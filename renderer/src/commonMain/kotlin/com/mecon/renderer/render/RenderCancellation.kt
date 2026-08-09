package com.mecon.renderer.render

/**
 * Cooperative cancellation for the render pipeline (docs/renderer/incremental-rendering.md).
 *
 * The render runs as a plain (non-suspend) call on a background dispatcher, so coroutine cancellation
 * cannot interrupt it on its own — a superseded render would otherwise run to completion and hold up the
 * serial render dispatcher while the user keeps editing. The desktop pipeline therefore passes a probe
 * (`() -> Boolean`) backed by the render coroutine's Job; the renderer polls it at coarse checkpoints and
 * bails early. The renderer stays free of any coroutine dependency (the probe is a bare lambda; tests
 * pass their own).
 *
 * Checkpoints are placed **only before the engine commits its caches**. A bail therefore never leaves
 * half-updated state — and [RenderEngine.renderIncremental] additionally snapshots and rolls the caches
 * back, so even a checkpoint added after a partial mutation stays safe.
 */
internal fun (() -> Boolean).throwIfCancelled() {
    if (this()) throw kotlin.coroutines.cancellation.CancellationException("render superseded by a newer edit")
}
