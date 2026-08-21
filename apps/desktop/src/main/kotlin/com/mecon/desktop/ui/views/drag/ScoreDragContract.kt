package com.mecon.desktop.ui.views.drag

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.desktop.ui.views.AnnotationRangeEndpoint
import com.mecon.api.interaction.EventSection
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.AttachmentGeometry
import com.mecon.api.storage.BeamGeometry
import com.mecon.api.storage.NavigationMark
import com.mecon.api.storage.NavigationMarkOffset
import com.mecon.api.storage.ScoreGeometry
import com.mecon.api.storage.SlurGeometry
import com.mecon.api.storage.TieGeometry
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.desktop.ui.components.NoteToolState
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderEngine
import com.mecon.renderer.render.RenderPage
import com.mecon.renderer.render.RenderResult

/**
 * Everything `Modifier.scoreDragGestures` needs, grouped so a handler can depend on one slice.
 *
 * The state groups are live views onto the composable's `mutableStateOf` cells rather than snapshot
 * values: pointer handlers outlive a frame, and keying them on pan/zoom would re-engrave the whole
 * score every frame of a drag.
 */
internal data class DragGestureFrame(
    val resultIdentityKey: Long,
    val result: RenderResult?,
    val pages: List<RenderPage>,
    val pageSlots: List<Offset>,
    val paginated: Boolean,
)

internal data class DragGestureDocument(
    val score: RuntimeScore?,
    val currentScore: RuntimeScore?,
    val computed: ComputedScore?,
    val engine: RenderEngine?,
    val beamGeometry: ScoreGeometry?,
    val selectedBeamControls: BeamControlPoints?,
)

internal data class DragGestureMode(
    val insertionToolActive: Boolean,
    val panEnabled: Boolean,
    val readOnly: Boolean,
    val noteTool: NoteToolState?,
    val marqueeSelectableTypes: Set<RenderElementType>,
    val resizableAnnotationEventIds: Set<EventId>,
)

internal data class DragSelectionActions(
    val selectionChange: (Set<EventSection>) -> Unit,
    val auditionNote: (ComputedVoiceEvent, Set<Int>?, Set<Int>?, Int) -> Unit,
    val selectAnnotationEvent: (EventId?) -> Unit,
    val resizeAnnotationRange: (EventId, AnnotationRangeEndpoint, TimeCode) -> Unit,
)

internal data class DragNoteMovementActions(
    val transpose: (List<NoteEditEngine.TransposeTarget>, Int) -> Unit,
    val moveRest: (List<NoteEditEngine.RestMoveTarget>) -> Unit,
    val moveBeam: (String, BeamGeometry) -> Unit,
)

internal data class DragExpressionMovementActions(
    val moveAttachment: (EventId, AttachmentGeometry, TimeCode, TimeCode?) -> Unit,
    val adjustTieCurve: (EventId, TieGeometry) -> Unit,
    val adjustSlurCurve: (EventId, SlurGeometry) -> Unit,
)

internal data class DragStructuralMovementActions(
    val resizeSecondVolta: (Int, Int) -> Unit,
    val resizeFirstVoltaStart: (Int, Int) -> Unit,
    val moveNavigationMark: (Int, Int, NavigationMark, NavigationMarkOffset) -> Unit,
)

internal data class DragGestureActions(
    val selection: DragSelectionActions,
    val notes: DragNoteMovementActions,
    val expressions: DragExpressionMovementActions,
    val structure: DragStructuralMovementActions,
)

internal class DragViewportState(
    private val getOffset: () -> Offset,
    private val setOffset: (Offset) -> Unit,
    private val getScale: () -> Float,
    private val getCtrlHeld: () -> Boolean,
    private val getShiftHeld: () -> Boolean,
    private val getFollowPlayback: () -> Boolean,
    private val setFollowPlayback: (Boolean) -> Unit,
) {
    var offset: Offset
        get() = getOffset()
        set(value) = setOffset(value)
    val scale: Float get() = getScale()
    val ctrlHeld: Boolean get() = getCtrlHeld()
    val shiftHeld: Boolean get() = getShiftHeld()
    var followPlayback: Boolean
        get() = getFollowPlayback()
        set(value) = setFollowPlayback(value)
}

internal class DragSelectionState(
    private val getSelection: () -> Set<EventSection>,
    private val getMarquee: () -> Rect?,
    private val setMarquee: (Rect?) -> Unit,
) {
    val current: Set<EventSection> get() = getSelection()
    var marquee: Rect?
        get() = getMarquee()
        set(value) = setMarquee(value)
}

internal class DragPreviewState(
    private val getTranspose: () -> TransposeDragState?,
    private val setTranspose: (TransposeDragState?) -> Unit,
    private val getBeam: () -> BeamDragState?,
    private val setBeam: (BeamDragState?) -> Unit,
    private val getAttachment: () -> AttachmentDragState?,
    private val setAttachment: (AttachmentDragState?) -> Unit,
    private val getVolta: () -> VoltaDragState?,
    private val setVolta: (VoltaDragState?) -> Unit,
    private val getNavigation: () -> NavigationDragState?,
    private val setNavigation: (NavigationDragState?) -> Unit,
    private val getCurve: () -> CurveDragState?,
    private val setCurve: (CurveDragState?) -> Unit,
    private val getAnnotationRange: () -> AnnotationRangeDragState?,
    private val setAnnotationRange: (AnnotationRangeDragState?) -> Unit,
) {
    var transpose: TransposeDragState?
        get() = getTranspose()
        set(value) = setTranspose(value)
    var beam: BeamDragState?
        get() = getBeam()
        set(value) = setBeam(value)
    var attachment: AttachmentDragState?
        get() = getAttachment()
        set(value) = setAttachment(value)
    var volta: VoltaDragState?
        get() = getVolta()
        set(value) = setVolta(value)
    var navigation: NavigationDragState?
        get() = getNavigation()
        set(value) = setNavigation(value)
    var curve: CurveDragState?
        get() = getCurve()
        set(value) = setCurve(value)
    var annotationRange: AnnotationRangeDragState?
        get() = getAnnotationRange()
        set(value) = setAnnotationRange(value)

    /** Drop every transient preview; used when a gesture is cancelled outright. */
    fun clearAll() {
        transpose = null
        beam = null
        attachment = null
        volta = null
        navigation = null
        curve = null
        annotationRange = null
    }
}

internal data class DragGestureState(
    val viewport: DragViewportState,
    val selection: DragSelectionState,
    val previews: DragPreviewState,
)

internal data class DragGestureRequest(
    val frame: DragGestureFrame,
    val document: DragGestureDocument,
    val mode: DragGestureMode,
    val actions: DragGestureActions,
    val state: DragGestureState,
)

/** Timeout safety net: if no committed re-render arrives this long after release, drop the hold anyway. */
internal const val COMMIT_HOLD_TIMEOUT_MS = 5000L
