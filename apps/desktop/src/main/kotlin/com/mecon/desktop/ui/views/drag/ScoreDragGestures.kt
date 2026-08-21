package com.mecon.desktop.ui.views.drag

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import com.mecon.desktop.ui.components.EditTool

/**
 * The one pointer-drag entry point for the rendered score.
 *
 * A drag can mean very different things depending on what is under the pointer and which tool is
 * active, so this file only *arbitrates*: it resolves the hit once, picks the handler that claims
 * the gesture, and forwards move/release/cancel to it. Each behaviour lives in its own file, named
 * after the interaction family it belongs to (see [docs/ui/score-interaction-taxonomy.md]):
 *
 * | File | Family | Behaviour |
 * |------|--------|-----------|
 * | [ViewportPanDragHandler] | — | viewport pan (not an edit) |
 * | [MarqueeSelectDragHandler] | N | rubber-band selection |
 * | [NoteHandleDragHandler] | H | transpose a note / move a rest |
 * | [BeamHandleDragHandler] | H | beam slope and position |
 * | [AttachmentHandleDragHandler] | H | dynamics, hairpins, 8va, tempo, breath, ornaments |
 * | [CurveHandleDragHandler] | H | tie / slur apex |
 * | [VoltaHandleDragHandler] | H over B | ending-house measure range |
 * | [NavigationHandleDragHandler] | H over B | navigation mark boundary + offset |
 * | [AnnotationRangeDragHandler] | H over B | analysis annotation range |
 *
 * Every handler follows the same lifecycle: pointer moves only update transient preview state, and
 * release dispatches at most one edit — one history item, never a loop of them.
 *
 * Insertion tools (families E, P and S) own their own drags in
 * `Modifier.scoreInsertionGestures`, so this handler stands down for them entirely.
 */
internal fun Modifier.scoreDragGestures(request: DragGestureRequest): Modifier = this.pointerInput(
    request.frame.resultIdentityKey,
    request.mode.insertionToolActive,
    request.frame.paginated,
    request.mode.panEnabled,
) {
    if (request.mode.insertionToolActive) return@pointerInput
    val result = request.frame.result ?: return@pointerInput
    // Pan/zoom are read live through the context rather than keyed on: keying would rebuild this
    // handler (and re-engrave every element) on each frame of a drag.
    val context = ScoreDragContext(request, result, density)
    val handlers = ScoreDragHandlers()
    var active: ScoreDragHandler? = null
    detectDragGestures(
        onDragStart = { raw ->
            active = handlers.resolve(context, ScoreDragPick.resolve(context, raw), raw)
        },
        onDragCancel = {
            active?.cancel(context)
            context.previews.clearAll()
            context.selection.marquee = null
            active = null
        },
        onDragEnd = {
            active?.end(context)
            active = null
        },
    ) { change, dragAmount ->
        active?.drag(context, change, dragAmount)
    }
}

/** One instance per `pointerInput` scope, so a handler's own start state survives between gestures. */
internal class ScoreDragHandlers {
    val annotationRange = AnnotationRangeDragHandler()
    val curve = CurveHandleDragHandler()
    val navigation = NavigationHandleDragHandler()
    val volta = VoltaHandleDragHandler()
    val attachment = AttachmentHandleDragHandler()
    val beam = BeamHandleDragHandler()
    val note = NoteHandleDragHandler()
    val marquee = MarqueeSelectDragHandler()
    val pan = ViewportPanDragHandler()
}

/**
 * Decide which handler owns this gesture.
 *
 * Two arrangements exist, and Ctrl swaps between them for the duration of one drag (the note pen
 * never reaches here). Handles always win over the fallback; the fallback is the rubber band in
 * marquee mode and the viewport pan in select mode.
 *
 * The asymmetry is deliberate. In select mode the geometry handles ride along with `panEnabled`,
 * because an embedded editor that gives up local panning is also giving up fine engraving. Direct
 * note/rest manipulation and annotation ranges stay available regardless, since a sibling timeline
 * owning the shared horizontal offset must not make the score contents read-only.
 */
private fun ScoreDragHandlers.resolve(
    context: ScoreDragContext,
    pick: ScoreDragPick,
    raw: Offset,
): ScoreDragHandler? {
    val marqueeMode = when (context.mode.noteTool?.tool) {
        EditTool.MARQUEE -> !context.viewport.ctrlHeld
        EditTool.SELECT -> context.viewport.ctrlHeld
        else -> false
    }
    val engaged = if (marqueeMode) {
        annotationRange.start(context, pick)
            ?: curve.start(context, pick)
            ?: navigation.start(context, pick)
            ?: volta.start(context, pick)
            ?: attachment.start(context, pick)
            ?: beam.start(context, pick)
            // Grabbing an already-selected note/rest moves the whole selection;
            // anything else rubber-bands.
            ?: note.startWithinSelection(context, pick)
    } else {
        annotationRange.start(context, pick)
            ?: context.whenPanEnabled {
                curve.start(context, pick)
                    ?: navigation.start(context, pick)
                    ?: volta.start(context, pick)
                    ?: attachment.start(context, pick)
                    ?: beam.start(context, pick)
            }
            ?: note.startFromPick(context, pick)
    }
    return engaged ?: if (marqueeMode) marquee.start(context, raw) else pan.start(context)
}

private inline fun ScoreDragContext.whenPanEnabled(
    block: () -> ScoreDragHandler?,
): ScoreDragHandler? = if (mode.panEnabled) block() else null
