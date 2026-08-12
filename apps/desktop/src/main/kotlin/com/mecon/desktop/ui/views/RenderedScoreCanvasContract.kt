package com.mecon.desktop.ui.views

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextMeasurer
import com.mecon.api.interaction.EventSection
import com.mecon.api.interaction.StaffAttachmentSection
import com.mecon.api.interaction.VoiceBeamSection
import com.mecon.api.interaction.VoltaEndingSection
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.render.RenderColor
import com.mecon.api.runtime.RuntimeScore
import com.mecon.audio.engine.PlaybackState
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.interaction.StyleSnapshot
import com.mecon.renderer.render.RenderElement
import com.mecon.renderer.render.RenderElementId
import com.mecon.renderer.render.RenderPage
import com.mecon.renderer.render.RenderResult
import com.mecon.renderer.render.TimeCodePosition
import com.mecon.renderer.render.NoteheadCenterMarker
import com.mecon.renderer.render.edit.GhostClef
import com.mecon.renderer.render.edit.GhostExpressionSpan
import com.mecon.renderer.render.edit.GhostKeySignature
import com.mecon.renderer.render.edit.GhostNote
import com.mecon.renderer.render.edit.GhostTimeSignature
import kotlinx.coroutines.CoroutineScope

internal data class NoteheadBackgroundBlob(
    val systemIndex: Int?,
    val staffIndex: Int?,
    val bounds: AbsoluteRect,
)

internal data class RenderedScoreRenderFrame(
    val renderer: ComposeScoreRenderer?,
    val result: RenderResult?,
    val textMeasurer: TextMeasurer,
    val styleSnapshot: StyleSnapshot,
    val density: Float,
)

internal data class RenderedScorePageFrame(
    val pages: List<RenderPage>,
    val pageSlots: List<Offset>,
    val markersByPage: Map<Int, List<RenderElement>>,
    val continuousMarkers: List<RenderElement>,
    val stalePageIndices: Set<Int>,
    val paginated: Boolean,
    val showEditorMarkers: Boolean,
    val preparingLoadedDocument: Boolean,
)

internal data class RenderedScoreSelectionOverlay(
    val score: RuntimeScore?,
    val selection: Set<EventSection>,
    val highlightedElements: Set<RenderElementId>,
    val selectedAnnotationEventId: EventId?,
    val selectedBeamSection: VoiceBeamSection?,
    val selectedBeamControls: BeamControlPoints?,
    val selectedVoltaSection: VoltaEndingSection?,
    val selectedVoltaElements: List<RenderElement>,
    val selectedNavigationElements: List<RenderElement>,
    val selectedAttachmentSection: StaffAttachmentSection?,
    val selectedAttachmentElements: List<RenderElement>,
    val noteheadBackgroundGroups: List<RenderedScoreNoteheadBackgroundGroup>,
    val noteheadCenterMarkers: List<NoteheadCenterMarker>,
)

internal data class RenderedScoreGhostOverlay(
    val note: GhostNote?,
    val clef: GhostClef?,
    val timeSignature: GhostTimeSignature?,
    val keySignature: GhostKeySignature?,
    val expressionSpan: GhostExpressionSpan?,
    val color: RenderColor,
)

internal data class RenderedScoreDragOverlay(
    val transpose: TransposeDragState?,
    val transposeCommitted: Boolean,
    val beam: BeamDragState?,
    val beamCommitted: Boolean,
    val attachment: AttachmentDragState?,
    val attachmentCommitted: Boolean,
    val volta: VoltaDragState?,
    val navigation: NavigationDragState?,
    val navigationCommitted: Boolean,
    val curve: CurveDragState?,
    val curveCommitted: Boolean,
    val selectionColor: RenderColor,
)

internal data class RenderedScorePlaybackOverlay(
    val state: PlaybackState,
    val tickToX: List<Pair<Long, TimeCodePosition>>?,
    val positionTicks: Long,
)

internal data class RenderedScoreDrawLifecycle(
    val documentVersion: Long,
    val readyFrameScheduledVersion: LongArray,
    val scope: CoroutineScope,
    val onDocumentInteractive: (Long) -> Unit,
)

internal data class RenderedScoreCanvasDrawRequest(
    val render: RenderedScoreRenderFrame,
    val page: RenderedScorePageFrame,
    val selection: RenderedScoreSelectionOverlay,
    val ghosts: RenderedScoreGhostOverlay,
    val drags: RenderedScoreDragOverlay,
    val playback: RenderedScorePlaybackOverlay,
    val lifecycle: RenderedScoreDrawLifecycle,
    val scale: Float,
    val offset: Offset,
)
