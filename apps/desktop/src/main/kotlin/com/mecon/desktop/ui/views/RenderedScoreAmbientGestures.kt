package com.mecon.desktop.ui.views

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import com.mecon.api.primitive.EventId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.desktop.ui.views.drag.annotationRangeEndpointAt
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.Pixels

/**
 * The pointer handlers that sit *around* score editing: zoom, modifier tracking, staff selectors,
 * the hidden-staff context menu, and the resize cursor for annotation ranges.
 *
 * None of them selects or edits notation — that is
 * [com.mecon.desktop.ui.views.drag.scoreDragGestures], [scoreSelectionGestures] and
 * [scoreInsertionGestures]. They are kept together because they share one constraint: every one runs
 * for the lifetime of the canvas and must therefore read pan/zoom **live** rather than capture it.
 * Keying any of them on `scale`/`offset` would rebuild the handler each frame of a pan and re-engrave
 * the whole score with it.
 */
internal data class AmbientGestureRequest(
    val resultIdentityKey: Long,
    val result: com.mecon.renderer.render.RenderResult?,
    val score: RuntimeScore?,
    val pages: List<com.mecon.renderer.render.RenderPage>,
    val pageSlots: List<Offset>,
    val paginated: Boolean,
    val zoomEnabled: Boolean,
    val showEditorMarkers: Boolean,
    val resizableAnnotationEventIds: Set<EventId>,
    val selectorRegions: List<RenderedScoreStaffSelectorRegion>,
    val viewport: RenderedScoreViewportState,
    val onStaffSelector: (String) -> Unit,
    val onAnnotationResizeHover: (Boolean) -> Unit,
    val onHiddenStaffMenu: (List<HiddenStaffMenuOption>?, Offset) -> Unit,
)

internal fun Modifier.scoreAmbientGestures(request: AmbientGestureRequest): Modifier {
    val viewport = request.viewport

    fun absolutePoint(raw: Offset, density: Float): AbsolutePoint? {
        val offset = viewport.offset.value
        val scale = viewport.scale.value
        val design = Offset((raw.x - offset.x) / scale / density, (raw.y - offset.y) / scale / density)
        return if (request.paginated) {
            designToGlobal(design, request.pages, request.pageSlots)
        } else {
            AbsolutePoint(Pixels(design.x), Pixels(design.y))
        }
    }

    return this
        // Hovering a resizable annotation endpoint switches the cursor; the drag itself is family H.
        .pointerInput(
            request.resultIdentityKey,
            request.paginated,
            request.resizableAnnotationEventIds,
        ) {
            val result = request.result ?: run {
                request.onAnnotationResizeHover(false)
                return@pointerInput
            }
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    if (event.type == PointerEventType.Exit) {
                        request.onAnnotationResizeHover(false)
                        continue
                    }
                    if (event.type != PointerEventType.Move) continue
                    val raw = event.changes.firstOrNull()?.position ?: continue
                    val point = absolutePoint(raw, density)
                    request.onAnnotationResizeHover(
                        point != null && annotationRangeEndpointAt(
                            result = result,
                            point = point,
                            resizableEventIds = request.resizableAnnotationEventIds,
                            radius = ANNOTATION_HOVER_RADIUS / viewport.scale.value,
                        ) != null
                    )
                }
            }
        }
        // Scroll-wheel zoom, towards the pointer position.
        .pointerInput(request.zoomEnabled) {
            if (!request.zoomEnabled) return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Scroll) continue
                    val scrollDelta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    if (scrollDelta == 0f) continue
                    val scale = viewport.scale.value
                    val offset = viewport.offset.value
                    val zoomFactor = if (scrollDelta < 0) ZOOM_STEP else 1f / ZOOM_STEP
                    val newScale = (scale * zoomFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    val pointerPos = event.changes.first().position
                    val scaleChange = newScale / scale
                    viewport.offset.value = Offset(
                        pointerPos.x - scaleChange * (pointerPos.x - offset.x),
                        pointerPos.y - scaleChange * (pointerPos.y - offset.y),
                    )
                    viewport.scale.value = newScale
                    event.changes.forEach { it.consume() }
                }
            }
        }
        // Mirror the keyboard modifiers on the Initial pass: tap / marquee gesture callbacks carry no
        // modifier information, so the selection and drag handlers read the current value from here.
        // Ctrl temporarily swaps the Select and Marquee tools for the duration of a drag gesture.
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    viewport.shiftHeld.value = event.keyboardModifiers.isShiftPressed
                    viewport.ctrlHeld.value = event.keyboardModifiers.isCtrlPressed
                }
            }
        }
        .pointerInput(request.resultIdentityKey, request.selectorRegions) {
            if (request.selectorRegions.isEmpty()) return@pointerInput
            awaitEachGesture {
                // Claim selector presses during the Initial pass so the score's marquee/drag
                // handlers cannot win the same gesture.
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val point = absolutePoint(down.position, density)
                val hit = point?.let { p -> request.selectorRegions.firstOrNull { p in it.bounds } }
                if (hit != null) {
                    down.consume()
                    val up = waitForUpOrCancellation(PointerEventPass.Initial)
                    up?.consume()
                    if (up != null) request.onStaffSelector(hit.choice.key)
                }
            }
        }
        // Right-click over a hidden dashed line / grey cell opens the reveal-staff menu.
        .pointerInput(request.resultIdentityKey, request.paginated) {
            val result = request.result ?: return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    if (event.type != PointerEventType.Press ||
                        !event.buttons.isSecondaryPressed
                    ) continue
                    val change = event.changes.firstOrNull() ?: continue
                    val point = absolutePoint(change.position, density)
                    val options = if (request.showEditorMarkers) {
                        point?.let { buildHiddenStaffMenu(result, request.score, it) }
                    } else null
                    request.onHiddenStaffMenu(options, change.position)
                    if (options != null) change.consume()
                }
            }
        }
}

private const val ANNOTATION_HOVER_RADIUS = 10f
private const val ZOOM_STEP = 1.08f
private const val MIN_ZOOM = 0.25f
private const val MAX_ZOOM = 5f
