package com.mecon.desktop.ui.views.drag

import androidx.compose.ui.geometry.Offset
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
import com.mecon.desktop.ui.views.RenderedScoreViewportState
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

/**
 * The mutable view state a drag reads and writes.
 *
 * These are the composable's own `remember`ed holders, passed by reference rather than copied:
 * a pointer handler outlives the composition that created it, so it must observe the *current*
 * pan, zoom and modifier state instead of a snapshot taken when the gesture handler was installed.
 * [selection] is a getter for the same reason.
 */
internal data class DragGestureState(
    val viewport: RenderedScoreViewportState,
    val previews: ScoreDragPreviewState,
    val selection: () -> Set<EventSection>,
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
