package com.mecon.desktop.ui.views.drag

import androidx.compose.runtime.mutableStateOf

/**
 * The transient preview of each in-progress drag, kept out of the score and render-frame state.
 *
 * At most one is non-null at a time while dragging; after release the entry survives in its
 * `committing` form until the replacement frame is on screen (see [rememberScoreDragCommitHold]).
 * Handlers write here; the canvas overlay and the view's hide snapshot read here.
 */
internal class ScoreDragPreviewState {
    val transpose = mutableStateOf<TransposeDragState?>(null)
    val beam = mutableStateOf<BeamDragState?>(null)
    val attachment = mutableStateOf<AttachmentDragState?>(null)
    val volta = mutableStateOf<VoltaDragState?>(null)
    val navigation = mutableStateOf<NavigationDragState?>(null)
    val curve = mutableStateOf<CurveDragState?>(null)
    val annotationRange = mutableStateOf<AnnotationRangeDragState?>(null)

    /** Drop every transient preview; used when a gesture is cancelled outright. */
    fun clearAll() {
        transpose.value = null
        beam.value = null
        attachment.value = null
        volta.value = null
        navigation.value = null
        curve.value = null
        annotationRange.value = null
    }
}
