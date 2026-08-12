package com.mecon.desktop.ui.views

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.interaction.*
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.render.RenderColor
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.state.RenderHint
import com.mecon.api.storage.BeamGeometry
import com.mecon.api.storage.NoteRef
import com.mecon.api.storage.NavigationMark
import com.mecon.api.storage.NavigationMarkOffset
import com.mecon.api.storage.PageArrangement
import com.mecon.api.storage.tracks.MeasureRange
import com.mecon.api.storage.events.OrnamentAnchor
import com.mecon.audio.engine.PlaybackState
import com.mecon.core.engine.edit.ClefEditEngine
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.desktop.ui.components.NoteToolState
import com.mecon.renderer.geometry.*
import com.mecon.renderer.layout.AlignedTimeAxisRequest
import com.mecon.renderer.layout.ResolvedTimeAxis
import com.mecon.renderer.render.*

/**
 * Score view that renders using the unified layout system.
 *
 * Accepts a [RuntimeScore] directly and uses [RenderEngine.render] to
 * perform both layout computation and rendering internally.
 *
 * @param panEnabled  Whether mouse-drag panning is enabled.
 * @param zoomEnabled Whether scroll-wheel zooming is enabled.
 */
data class RenderedScoreSource(
    val score: RuntimeScore?,
    val computed: ComputedScore? = null,
    val renderHint: RenderHint? = null,
    val documentVersion: Long = 0,
)

data class RenderedScoreSelectionConfig(
    val selection: Set<EventSection> = emptySet(),
    val onSelectionChange: (Set<EventSection>) -> Unit = {},
    val onSelectAnnotationEvent: (EventId?) -> Unit = {},
    val marqueeSelectableTypes: Set<RenderElementType> = DEFAULT_MARQUEE_TYPES,
    val selectedAnnotationEventId: EventId? = null,
    val highlightedElements: Set<RenderElementId> = emptySet(),
    val localEventStyles: Map<EventId, StyleOverride> = emptyMap(),
    val noteheadBackgroundGroups: List<RenderedScoreNoteheadBackgroundGroup> = emptyList(),
    /** Stable noteheads decorated with renderer-computed centered contrast dots. */
    val noteheadCenterMarkerNotes: Set<NoteRef> = emptySet(),
    val selectableSection: (EventSection) -> Boolean = { true },
) {
    companion object {
        val DEFAULT_MARQUEE_TYPES: Set<RenderElementType> = setOf(
            RenderElementType.NOTEHEAD,
            RenderElementType.REST,
            RenderElementType.ARTICULATION,
            RenderElementType.ORNAMENT,
            RenderElementType.FERMATA,
            RenderElementType.DYNAMIC,
            RenderElementType.HAIRPIN,
            RenderElementType.OCTAVE_SHIFT,
            RenderElementType.TEMPO_MARKING,
            RenderElementType.EDITOR_MARKER,
        )
    }
}

data class RenderedScoreNoteheadBackgroundGroup(
    val notes: Set<NoteRef>,
    val color: RenderColor,
)

data class RenderedScoreStaffSelectorChoice(
    val key: String,
    val label: String,
    val selected: Boolean,
)

data class RenderedScoreStaffSelectorConfig(
    val choicesByStaffId: Map<TrackId, List<RenderedScoreStaffSelectorChoice>> = emptyMap(),
    val onSelect: (String) -> Unit = {},
)

data class RenderedScoreDisplayConfig(
    val noteStyleRefreshKey: Int = 0,
    val renderRefreshKey: Int = 0,
    val isReference: Boolean = false,
    val readOnly: Boolean = false,
    val panEnabled: Boolean = true,
    val zoomEnabled: Boolean = true,
    val currentPositionTicks: Long = 0L,
    val playbackState: PlaybackState = PlaybackState.IDLE,
    val arrangement: PageArrangement = PageArrangement.VERTICAL,
    val showEditorMarkers: Boolean = true,
    val showViewLabel: Boolean = true,
    val showZoomIndicator: Boolean = true,
    /** Optional first-system inset override for compact embedded score editors. */
    val firstSystemIndent: com.mecon.renderer.geometry.StaffSpace? = null,
    val alignedTimeAxisRequest: AlignedTimeAxisRequest? = null,
    /** Keeps reporting the last complete axis while a replacement aligned render is in flight. */
    val onResolvedTimeAxis: (ResolvedTimeAxis?) -> Unit = {},
    /** Physical-pixel scroll shared with sibling time surfaces; null keeps the local pan state. */
    val externalHorizontalOffsetPx: Float? = null,
    /** Padding inside the score canvas. Aligned workbenches set this to zero and provide their own gutter. */
    val contentPadding: androidx.compose.ui.unit.Dp = 40.dp,
    /**
     * Keeps blank measures easy to click in the general score editor. Aligned workbenches disable it
     * because their requested time segments already provide the editable width.
     */
    val padEmptyMeasures: Boolean = true,
)

data class RenderedScoreNotationInsertion(
    val noteTool: NoteToolState? = null,
    val onInsertNote: (NoteEditEngine.Insertion) -> Unit = {},
    val onAuditionNote: (ComputedVoiceEvent, Set<Int>?, Set<Int>?, Int) -> Unit = { _, _, _, _ -> },
    val onInsertClef: (ClefEditEngine.Target) -> Unit = {},
    val onInsertTimeSignature: (Int) -> Unit = {},
    val onInsertKeySignature: (TimeCode) -> Unit = {},
    val onInsertBarline: (BarlineSection) -> Unit = {},
    val onInsertRepeatStructure: (BarlineSection) -> Unit = {},
)

data class RenderedScoreExpressionInsertion(
    val onInsertDynamic: (TrackId, TimeCode) -> Unit = { _, _ -> },
    val onInsertPauseMark: (TrackId, TimeCode) -> Unit = { _, _ -> },
    val onInsertExpressionSpan: (TrackId, TimeCode, TimeCode) -> Unit = { _, _, _ -> },
    val onInsertTempo: (TimeCode) -> Unit = {},
    val onInsertTempoSpan: (TimeCode, TimeCode) -> Unit = { _, _ -> },
    val onInsertOrnament: (TrackId, EventId, OrnamentAnchor, TimeCode?) -> Unit = { _, _, _, _ -> },
    val onInsertArpeggio: (EventId) -> Unit = {},
)

data class RenderedScoreEventMoveActions(
    val onTranspose: (List<NoteEditEngine.TransposeTarget>, Int) -> Unit = { _, _ -> },
    val onMoveRest: (List<NoteEditEngine.RestMoveTarget>) -> Unit = {},
    val onMoveBeam: (String, BeamGeometry) -> Unit = { _, _ -> },
    val onMoveAttachment: (EventId, com.mecon.api.storage.AttachmentGeometry, TimeCode, TimeCode?) -> Unit = { _, _, _, _ -> },
    val onAdjustTieCurve: (EventId, com.mecon.api.storage.TieGeometry) -> Unit = { _, _ -> },
    val onAdjustSlurCurve: (EventId, com.mecon.api.storage.SlurGeometry) -> Unit = { _, _ -> },
)

data class RenderedScoreStructuralMoveActions(
    val onResizeSecondVolta: (Int, Int) -> Unit = { _, _ -> },
    val onResizeFirstVoltaStart: (Int, Int) -> Unit = { _, _ -> },
    val onMoveNavigationMark: (Int, Int, NavigationMark, NavigationMarkOffset) -> Unit = { _, _, _, _ -> },
)

data class RenderedScoreEditConfig(
    val notation: RenderedScoreNotationInsertion = RenderedScoreNotationInsertion(),
    val structuralMovement: RenderedScoreStructuralMoveActions = RenderedScoreStructuralMoveActions(),
    val expression: RenderedScoreExpressionInsertion = RenderedScoreExpressionInsertion(),
    val eventMovement: RenderedScoreEventMoveActions = RenderedScoreEventMoveActions(),
)

data class RenderedScoreLifecycleConfig(
    val commitUpdatingDelayMs: Long = 100L,
    val interactionBlocked: Boolean = false,
    val documentLoading: Boolean = false,
    val loadingDocumentVersion: Long? = null,
    val onDocumentInteractive: (Long) -> Unit = {},
    val onGeometryCaptured: ((com.mecon.api.storage.ScoreGeometry?) -> Unit)? = null,
    val beamGeometry: com.mecon.api.storage.ScoreGeometry? = null,
    val onRevealStaff: (List<TrackId>, MeasureRange) -> Unit = { _, _ -> },
)

data class RenderedScoreViewConfig(
    val source: RenderedScoreSource,
    val selectionConfig: RenderedScoreSelectionConfig = RenderedScoreSelectionConfig(),
    val display: RenderedScoreDisplayConfig = RenderedScoreDisplayConfig(),
    val edit: RenderedScoreEditConfig = RenderedScoreEditConfig(),
    val lifecycle: RenderedScoreLifecycleConfig = RenderedScoreLifecycleConfig(),
    val staffSelectors: RenderedScoreStaffSelectorConfig = RenderedScoreStaffSelectorConfig(),
)
