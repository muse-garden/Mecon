package com.mecon.desktop.ui.views.drag

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import com.mecon.renderer.render.RenderResult
import kotlinx.coroutines.delay

/**
 * State of one handle drag's hand-off between "pointer released" and "committed frame on screen".
 */
internal data class DragCommitHold(
    /**
     * The committed frame is the displayed one, so the preview may stop being drawn *during this
     * composition* — before the cleanup effect has run. Deriving it here rather than waiting for the
     * effect prevents one intermediate draw of the new page that still applies the old frame's hide,
     * followed by a second notes-layer recording when the state is finally cleared.
     */
    val committedFrameDisplayed: Boolean,
    /** Still waiting for the replacement frame: block interaction until it lands. */
    val blocking: Boolean,
)

/**
 * The hand-off for every handle drag at once.
 *
 * Each drag hides its original and shows a preview. Clearing that preview the moment the edit is
 * dispatched would flash the pre-drag notation, because the committed re-render is still being
 * produced off-thread. So the preview is *held* until the displayed [RenderResult] is no longer the
 * frame captured at mouse-up, and interaction stays blocked for exactly that window.
 *
 * Per [docs/performance/large-score-editing.md] the release point is a complete `RenderResult`;
 * a streamed first page does not end the hold.
 */
internal data class ScoreDragCommitHold(
    val transpose: DragCommitHold,
    val beam: DragCommitHold,
    val attachment: DragCommitHold,
    val navigation: DragCommitHold,
    val curve: DragCommitHold,
) {
    /**
     * The cleanup effects run after the committed frame has already been composed and drawn. The
     * pointer-consuming overlay must not stay alive for that bookkeeping tail, so this unlocks on
     * the first composition that owns the replacement result — the visual settlement point.
     */
    val interactionBlocked: Boolean = transpose.blocking || beam.blocking ||
        attachment.blocking || navigation.blocking || curve.blocking
}

/**
 * Derive the hold flags for every handle drag and run the effects that release them.
 *
 * Each kind gets the same two effects: clear the preview once the committed frame is displayed, plus
 * an independent timeout in case the commit failed or was a no-op (normally cancelled as soon as the
 * first effect clears the state).
 */
@Composable
internal fun rememberScoreDragCommitHold(
    previews: ScoreDragPreviewState,
    renderResult: RenderResult?,
): ScoreDragCommitHold = ScoreDragCommitHold(
    transpose = commitHold(
        state = previews.transpose,
        renderResult = renderResult,
        committing = { it.committing },
        baseline = { it.commitBaseline },
        onCommittedFrameDisplayed = { drag ->
            com.mecon.renderer.debug.PerfLog.log("transpose.handoff") {
                "renderWait=${(System.nanoTime() - drag.commitStartedAtNanos) / 1_000_000}ms " +
                    "revealWait=0ms"
            }
        },
    ),
    beam = commitHold(
        state = previews.beam,
        renderResult = renderResult,
        committing = { it.committing },
        baseline = { it.commitBaseline },
    ),
    attachment = commitHold(
        state = previews.attachment,
        renderResult = renderResult,
        committing = { it.committing },
        baseline = { it.commitBaseline },
    ),
    navigation = commitHold(
        state = previews.navigation,
        renderResult = renderResult,
        committing = { it.committing },
        baseline = { it.commitBaseline },
    ),
    curve = commitHold(
        state = previews.curve,
        renderResult = renderResult,
        committing = { it.committing },
        baseline = { it.commitBaseline },
    ),
)

@Composable
private fun <T : Any> commitHold(
    state: MutableState<T?>,
    renderResult: RenderResult?,
    committing: (T) -> Boolean,
    baseline: (T) -> RenderResult?,
    onCommittedFrameDisplayed: ((T) -> Unit)? = null,
): DragCommitHold {
    val current = state.value
    val isCommitting = current != null && committing(current)
    // Frames are compared by identity: structural equality on a whole render frame is an
    // O(score) stall on the UI thread.
    val displayed = isCommitting && renderResult !== baseline(current!!)
    LaunchedEffect(displayed) {
        if (!displayed) return@LaunchedEffect
        state.value?.let { onCommittedFrameDisplayed?.invoke(it) }
        state.value = null
    }
    LaunchedEffect(isCommitting) {
        if (!isCommitting) return@LaunchedEffect
        delay(COMMIT_HOLD_TIMEOUT_MS)
        state.value = null
    }
    return DragCommitHold(committedFrameDisplayed = displayed, blocking = isCommitting && !displayed)
}
