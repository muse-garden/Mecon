package com.mecon.desktop.ui.views.drag

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerInputChange
import com.mecon.api.interaction.EventSection
import com.mecon.api.interaction.NavigationMarkSection
import com.mecon.api.interaction.StaffAttachmentSection
import com.mecon.api.interaction.VoiceBeamSection
import com.mecon.api.interaction.VoltaEndingSection
import com.mecon.desktop.ui.views.nearestDisplayedSystemByStaffCore
import com.mecon.desktop.ui.views.nearestDisplayedSystemByFullRange
import com.mecon.desktop.ui.views.rawToAbsolutePoint
import com.mecon.desktop.ui.views.selectByPriority
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.render.RenderElement
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderResult

/**
 * The environment one pointer gesture runs in: the displayed frame plus live view state and the
 * edit callbacks a handler commits through.
 *
 * Created once per `pointerInput` block, so the coordinate helpers always read the *current* pan and
 * zoom without the handler having to re-key on them.
 */
internal class ScoreDragContext(
    private val request: DragGestureRequest,
    val result: RenderResult,
    val density: Float,
) {
    val frame: DragGestureFrame get() = request.frame
    val document: DragGestureDocument get() = request.document
    val mode: DragGestureMode get() = request.mode
    val actions: DragGestureActions get() = request.actions
    val previews: ScoreDragPreviewState get() = request.state.previews

    val readOnly: Boolean get() = mode.readOnly

    // Live viewport reads: a gesture handler outlives the composition that installed it.
    private val viewport get() = request.state.viewport
    val scale: Float get() = viewport.scale.value
    val shiftHeld: Boolean get() = viewport.shiftHeld.value
    val ctrlHeld: Boolean get() = viewport.ctrlHeld.value
    var offset: Offset
        get() = viewport.offset.value
        set(value) { viewport.offset.value = value }
    var followPlayback: Boolean
        get() = viewport.followPlayback.value
        set(value) { viewport.followPlayback.value = value }
    var marquee: Rect?
        get() = viewport.marqueeRect.value
        set(value) { viewport.marqueeRect.value = value }

    val selection: Set<EventSection> get() = request.state.selection()

    /** Raw pointer → canvas design space (undo pan, zoom, density). */
    fun toDesign(raw: Offset): Offset {
        val offset = offset
        val scale = scale
        return Offset((raw.x - offset.x) / scale / density, (raw.y - offset.y) / scale / density)
    }

    /** Raw pointer → the global render point the hit index uses; null outside every page. */
    fun toAbsolute(raw: Offset): AbsolutePoint? = rawToAbsolutePoint(
        raw, offset, scale, density,
        frame.paginated, frame.pages, frame.pageSlots,
    )

    fun toRelative(point: AbsolutePoint): RelativePoint =
        result.transformerSnapshot.toRelative(point)

    /** Staff-space Y of a global point in the displayed frame. */
    fun relativeY(point: AbsolutePoint): Float = toRelative(point).y.value

    /**
     * The visual system nearest the raw pointer, matched on five-line staff cores.
     * See [nearestDisplayedSystemByStaffCore] for why the expanded system band must not be used.
     */
    fun nearestSystem(raw: Offset): Int? = nearestDisplayedSystemByStaffCore(
        result, raw, offset, scale, density,
        frame.paginated, frame.pages, frame.pageSlots,
    )

    /** Analysis lanes belong to the full system band, not only the five-line staff core. */
    fun nearestAnnotationSystem(raw: Offset, preferredSystemIndex: Int? = null): Int? =
        nearestDisplayedSystemByFullRange(
            result, raw, offset, scale, density,
            frame.paginated, frame.pages, frame.pageSlots,
            preferredSystemIndex,
        )

    /** Select [section] alone unless it is already part of the current selection. */
    fun ensureSelected(section: EventSection) {
        if (section !in selection) actions.selection.selectionChange(setOf(section))
    }
}

/**
 * Everything the arbitration learned about what sits under the pointer, resolved exactly once when
 * a gesture starts. Handlers read this instead of re-running hit tests.
 */
internal class ScoreDragPick private constructor(
    val point: AbsolutePoint?,
    val section: EventSection?,
    val attachment: StaffAttachmentSection?,
    val attachmentElement: RenderElement?,
    val volta: VoltaEndingSection?,
    val navigation: NavigationMarkSection?,
    val beam: VoiceBeamSection?,
    val beamControls: BeamControlPoints?,
    val beamEndpoint: String?,
    val grabsNote: Boolean,
    val grabsRest: Boolean,
) {
    /** A note or rest under the pointer that this gesture is allowed to move. */
    val grabsMovable: Boolean get() = grabsNote || grabsRest

    companion object {
        fun resolve(context: ScoreDragContext, raw: Offset): ScoreDragPick {
            val result = context.result
            val point = context.toAbsolute(raw)
            val hitResult = point?.let(result::hitTest)
            val hitSections = hitResult?.allSections().orEmpty()
            // Volta and navigation handles sit on top of whatever staff element they overlap.
            val picked = hitSections
                .filter { it is VoltaEndingSection || it is NavigationMarkSection }
                .selectByPriority()
                ?: hitSections.selectByPriority()
            val attachment = picked as? StaffAttachmentSection
            val attachmentElement = attachment?.let { section ->
                result.sectionIndex.elementsFor(section).elementIds
                    .mapNotNull(result::elementById).firstOrNull()
            }
            val beamHit = hitResult?.elements?.asReversed()
                ?.firstOrNull { it.metadata["groupId"] != null }
            val controlHit = point?.let { resolveBeamControlHit(context, it) }
            val beamSection = controlHit?.first?.section
                ?: beamHit?.sections?.filterIsInstance<VoiceBeamSection>()?.firstOrNull()
            val editable = !context.readOnly && point != null && picked != null
            return ScoreDragPick(
                point = point,
                section = picked,
                attachment = attachment,
                attachmentElement = attachmentElement,
                volta = picked as? VoltaEndingSection,
                navigation = picked as? NavigationMarkSection,
                beam = beamSection,
                beamControls = controlHit?.first,
                beamEndpoint = controlHit?.second,
                grabsNote = editable && picked!!.movableEvent() != null,
                // A rest is dragged vertically (move its display position) instead of transposed;
                // it follows the same gesture rules as a movable note.
                grabsRest = editable && picked!!.restEvent() != null,
            )
        }

        /**
         * Endpoint handles are editor overlays and do not exist in the spatial index. Probe a small
         * region around the pointer to discover an unselected beam, then derive and test its
         * endpoints in the same initial gesture.
         */
        private fun resolveBeamControlHit(
            context: ScoreDragContext,
            point: AbsolutePoint,
        ): Pair<BeamControlPoints, String>? {
            val result = context.result
            val radius = BEAM_CONTROL_HIT_RADIUS / context.scale
            val nearby = result.hitTestRegion(
                AbsoluteRect(
                    AbsolutePoint(
                        Pixels(point.x.value - radius),
                        Pixels(point.y.value - radius),
                    ),
                    Pixels(radius * 2f),
                    Pixels(radius * 2f),
                ),
                setOf(RenderElementType.BEAM),
            ).asReversed().flatMap { hit ->
                hit.sections.filterIsInstance<VoiceBeamSection>()
            }.distinctBy { it.groupId }
            return buildList {
                context.document.selectedBeamControls?.let(::add)
                nearby.forEach { section -> findBeamControlPoints(result, section)?.let(::add) }
            }.distinctBy { it.section.groupId }.firstNotNullOfOrNull { controls ->
                hitBeamControlPoint(point, controls, radius)?.let { endpoint -> controls to endpoint }
            }
        }
    }
}

/**
 * One drag behaviour — a viewport pan, a marquee, or one semantic handle of family H
 * (see [docs/ui/score-interaction-taxonomy.md]).
 *
 * A handler owns its own `start*` entry point rather than a shared one, because engagement
 * conditions genuinely differ per family; [resolveScoreDragHandler] encodes the order they compete
 * in. `start*` returns the handler itself when it took the gesture, so arbitration reads as an
 * elvis chain.
 *
 * Handlers only ever write transient preview state. Persisting a change happens once, in [end].
 */
internal interface ScoreDragHandler {
    fun drag(context: ScoreDragContext, change: PointerInputChange, dragAmount: Offset)

    /** Pointer released: commit at most one edit, or drop the preview when nothing changed. */
    fun end(context: ScoreDragContext) {}

    /** Gesture abandoned. Previews are cleared for every handler by the dispatcher. */
    fun cancel(context: ScoreDragContext) {}
}
