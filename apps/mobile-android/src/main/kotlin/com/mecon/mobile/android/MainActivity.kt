package com.mecon.mobile.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.Accidental
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.BarlineType
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.ArpeggioType
import com.mecon.api.storage.Articulation
import com.mecon.api.storage.BeamingInfo
import com.mecon.api.storage.NavigationMark
import com.mecon.api.interaction.LayoutBreakKind
import com.mecon.api.storage.events.HairpinStyle
import com.mecon.api.storage.events.HairpinType
import com.mecon.api.storage.events.DynamicLevel
import com.mecon.api.storage.events.OctaveShiftType
import com.mecon.api.storage.events.OrnamentKind
import com.mecon.api.storage.events.GraceTimeSource
import com.mecon.api.storage.events.TempoMarkType
import com.mecon.api.storage.tracks.BreathMarkShape
import com.mecon.api.storage.tracks.Clef
import com.mecon.api.storage.tracks.FermataShape
import com.mecon.api.computed.ComputedOrnamentMark
import com.mecon.api.computed.ComputedTempoKeyframe
import com.mecon.api.computed.ComputedBreathMark
import com.mecon.api.storage.tracks.StorageFermata
import com.mecon.features.scoreediting.ScoreEntryCursor
import com.mecon.features.scoreediting.ScoreEntryCursorAction
import com.mecon.features.scoreediting.ScoreEditingFrame
import com.mecon.features.scoreediting.ScoreInputCapabilities
import com.mecon.features.scoreediting.ScoreInteractionAnchor
import com.mecon.features.scoreediting.ScoreInteractionCatalog
import com.mecon.features.scoreediting.ScorePointerKind
import com.mecon.features.scoreediting.ScoreSelectionTarget
import com.mecon.features.scoreediting.ScoreToolGroup
import com.mecon.features.scoreediting.ScoreViewportClass
import com.mecon.mobile.MobileScoreActivity
import com.mecon.mobile.MobileScoreEditorController
import com.mecon.mobile.MobileNoteInputState
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.render.edit.GhostNote
import com.mecon.renderer.render.edit.GhostPointSymbol
import com.mecon.renderer.render.edit.PointSymbolKind
import com.mecon.renderer.render.RenderCommand
import com.mecon.renderer.render.RenderElement
import com.mecon.renderer.render.RenderElementId
import com.mecon.renderer.render.RenderedBarlineHit
import com.mecon.renderer.render.edit.ExpressionSpanKind
import com.mecon.renderer.render.edit.GhostClef
import com.mecon.renderer.render.edit.GhostExpressionSpan
import com.mecon.renderer.render.edit.GhostKeySignature
import com.mecon.renderer.render.edit.GhostTimeSignature

private enum class MobilePlacementTool {
    NOTE,
    CLEF_TREBLE,
    CLEF_BASS,
    KEY_C_MAJOR,
    KEY_G_MAJOR,
    TIME_FOUR_FOUR,
    TIME_THREE_FOUR,
    DYNAMIC_MF,
    TEMPO_120,
    FERMATA,
    BREATH,
    ORNAMENT_TRILL,
    HAIRPIN_CRESCENDO,
    HAIRPIN_DIMINUENDO,
    OCTAVE_UP,
    OCTAVE_DOWN,
    GRADUAL_RITARDANDO,
    GRADUAL_ACCELERANDO,
    ORNAMENT_TRILL_SPAN,
}

private sealed interface MobilePointPreview {
    val commands: List<RenderCommand>

    data class Clef(val ghost: GhostClef) : MobilePointPreview {
        override val commands: List<RenderCommand> get() = ghost.commands
    }

    data class Key(val ghost: GhostKeySignature) : MobilePointPreview {
        override val commands: List<RenderCommand> get() = ghost.commands
    }

    data class Meter(val ghost: GhostTimeSignature) : MobilePointPreview {
        override val commands: List<RenderCommand> get() = ghost.commands
    }

    data class Symbol(
        val tool: MobilePlacementTool,
        val ghost: GhostPointSymbol,
        val commitTime: TimeCode,
        val sourceEventId: EventId? = null,
    ) : MobilePointPreview {
        override val commands: List<RenderCommand> get() = ghost.commands
    }
}

private data class MobileSpanAnchor(
    val staffTrackId: com.mecon.api.primitive.TrackId,
    val time: TimeCode,
    val sourceEventId: EventId? = null,
)

private enum class MobileSpanEndpoint { START, END }

private data class MobileSpanPreview(
    val tool: MobilePlacementTool,
    val start: MobileSpanAnchor,
    val ghost: GhostExpressionSpan,
    val activeEndpoint: MobileSpanEndpoint,
)

private fun spanEndpointHit(
    ghost: GhostExpressionSpan,
    point: AbsolutePoint,
    radius: Float,
): MobileSpanEndpoint? = listOf(
    MobileSpanEndpoint.START to ghost.startHandle,
    MobileSpanEndpoint.END to ghost.endHandle,
).minByOrNull { (_, handle) ->
    val dx = handle.x.value - point.x.value
    val dy = handle.y.value - point.y.value
    dx * dx + dy * dy
}?.takeIf { (_, handle) ->
    val dx = handle.x.value - point.x.value
    val dy = handle.y.value - point.y.value
    dx * dx + dy * dy <= radius * radius
}?.first

private data class MobileGeometryDragPreview(
    val elements: List<RenderElement>,
    val dx: Float = 0f,
    val dy: Float = 0f,
)

private enum class MobileGroupDraftKind { TUPLET, SMALL_NOTES, GRACE_PRINCIPAL, GRACE_PREVIOUS }

class MainActivity : ComponentActivity() {
    private val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 8))
    private val controller by lazy {
        MobileScoreEditorController.open(
            score,
            ScoreInputCapabilities(
                pointerKinds = setOf(ScorePointerKind.TOUCH),
                viewportClass = ScoreViewportClass.COMPACT,
            ),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MobileScoreEditor(controller)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileScoreEditor(controller: MobileScoreEditorController) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val renderScope = rememberCoroutineScope()
    val renderCoordinator = remember(context) { AndroidScoreRenderCoordinator(context, renderScope) }
    val renderFrame by renderCoordinator.frame.collectAsState()
    val bravuraTypeface = remember(context) { loadAndroidBravuraTypeface(context) }
    val textMeasurer = rememberTextMeasurer()
    var pianoVisible by rememberSaveable { mutableStateOf(false) }
    var noteGhost by remember { mutableStateOf<GhostNote?>(null, referentialEqualityPolicy()) }
    var placementTool by rememberSaveable { mutableStateOf(MobilePlacementTool.NOTE) }
    var pointPreview by remember { mutableStateOf<MobilePointPreview?>(null) }
    var spanPreview by remember { mutableStateOf<MobileSpanPreview?>(null) }
    var structureBoundary by remember { mutableStateOf<RenderedBarlineHit?>(null) }
    var appendSelection by rememberSaveable { mutableStateOf(false) }
    var dynamicLevel by rememberSaveable { mutableStateOf(DynamicLevel.MF) }
    var tempoBpm by rememberSaveable { mutableFloatStateOf(120f) }
    var fermataShape by rememberSaveable { mutableStateOf(FermataShape.NORMAL) }
    var breathShape by rememberSaveable { mutableStateOf(BreathMarkShape.COMMA) }
    var ornamentKind by rememberSaveable { mutableStateOf(OrnamentKind.TRILL) }
    var hairpinStyle by rememberSaveable { mutableStateOf(HairpinStyle.WEDGE) }
    var chordLatch by rememberSaveable { mutableStateOf(false) }
    var pendingChordMidi by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val voiceId = state.frame.runtimeScore.voiceTracks.keys.first()
    LaunchedEffect(voiceId) {
        if (state.interaction.entryCursor == null) {
            controller.activate(
                ScoreInteractionCatalog.ENTRY_NOTE,
                ScoreEntryCursor(voiceId, TimeCode.of(1, Fraction.ZERO)),
            )
        }
    }
    LaunchedEffect(state.frame.revision) {
        noteGhost = null
        pointPreview = null
        spanPreview = null
        structureBoundary = null
        renderCoordinator.submit(state.frame)
    }
    LaunchedEffect(state.noteInput.duration, state.noteInput.restMode) {
        noteGhost = null
    }
    LaunchedEffect(state.activeToolGroup) {
        pointPreview = null
        spanPreview = null
        structureBoundary = null
        placementTool = when (state.activeToolGroup) {
            ScoreToolGroup.NOTES -> MobilePlacementTool.NOTE
            ScoreToolGroup.POINT_SYMBOLS -> MobilePlacementTool.CLEF_TREBLE
            ScoreToolGroup.SPANS -> MobilePlacementTool.HAIRPIN_CRESCENDO
            else -> placementTool
        }
    }
    DisposableEffect(renderCoordinator) {
        onDispose { renderCoordinator.close() }
    }
    val selectedElementIds = remember(renderFrame, state.frame.selection) {
        renderFrame.result?.let { result ->
            state.frame.selection.flatMapTo(linkedSetOf()) { target ->
                renderCoordinator.elementsForSelection(result, target).map { it.id }
            }
        }.orEmpty()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("快速草稿") },
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        controller.dispatch(com.mecon.features.scoreediting.ScoreEditIntent.Undo(state.frame.revision))
                    }) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "撤销") }
                    IconButton(onClick = {
                        controller.dispatch(com.mecon.features.scoreediting.ScoreEditIntent.Redo(state.frame.revision))
                    }) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "重做") }
                },
            )
        },
        bottomBar = {
            Column {
                when (state.activeToolGroup) {
                    ScoreToolGroup.NOTES -> {
                        EntryContextBar(
                            controller = controller,
                            pianoVisible = pianoVisible,
                            onTogglePiano = { pianoVisible = !pianoVisible },
                            chordLatch = chordLatch,
                            pendingChordMidi = pendingChordMidi,
                            onToggleChordLatch = {
                                chordLatch = !chordLatch
                                if (!chordLatch) pendingChordMidi = emptySet()
                            },
                            onClearChord = { pendingChordMidi = emptySet() },
                            onWriteChord = {
                                if (pendingChordMidi.isNotEmpty()) {
                                    controller.insertChord(pendingChordMidi.sorted().map(Pitch::fromMidi))
                                    pendingChordMidi = emptySet()
                                }
                            },
                        )
                        DurationInputBar(controller)
                        if (pianoVisible) PianoKeyboard(
                            controller = controller,
                            chordLatch = chordLatch,
                            pendingChordMidi = pendingChordMidi,
                            onChordKey = { midi ->
                                pendingChordMidi = if (midi in pendingChordMidi) pendingChordMidi - midi
                                else pendingChordMidi + midi
                            },
                        )
                    }
                    ScoreToolGroup.POINT_SYMBOLS -> PointPlacementBar(
                        selected = placementTool,
                        previewReady = pointPreview != null,
                        dynamicLevel = dynamicLevel,
                        tempoBpm = tempoBpm,
                        fermataShape = fermataShape,
                        breathShape = breathShape,
                        ornamentKind = ornamentKind,
                        onDynamicLevelChange = { dynamicLevel = it; pointPreview = null },
                        onTempoBpmChange = { tempoBpm = it.coerceIn(20f, 400f); pointPreview = null },
                        onFermataShapeChange = { fermataShape = it; pointPreview = null },
                        onBreathShapeChange = { breathShape = it; pointPreview = null },
                        onOrnamentKindChange = { ornamentKind = it; pointPreview = null },
                        onSelect = {
                            placementTool = it
                            pointPreview = null
                            controller.activate(ScoreInteractionCatalog.POINT_SYMBOL)
                        },
                        onCancel = {
                            pointPreview = null
                            controller.cancelRun()
                        },
                        onCommit = {
                            when (val preview = pointPreview) {
                                is MobilePointPreview.Clef -> controller.setClef(
                                    preview.ghost.staffTrackId,
                                    preview.ghost.onset,
                                    preview.ghost.clef,
                                )
                                is MobilePointPreview.Key -> controller.setKeySignature(
                                    preview.ghost.onset,
                                    preview.ghost.keySignature,
                                )
                                is MobilePointPreview.Meter -> controller.setTimeSignature(
                                    preview.ghost.measure,
                                    preview.ghost.timeSignature,
                                )
                                is MobilePointPreview.Symbol -> when (preview.tool) {
                                    MobilePlacementTool.DYNAMIC_MF -> controller.addDynamic(
                                        preview.ghost.staffTrackId,
                                        preview.commitTime,
                                        dynamicLevel,
                                    )
                                    MobilePlacementTool.TEMPO_120 -> controller.addTempoMark(
                                        preview.commitTime,
                                        tempoBpm,
                                    )
                                    MobilePlacementTool.FERMATA -> controller.addFermata(
                                        preview.commitTime,
                                        fermataShape,
                                    )
                                    MobilePlacementTool.BREATH -> controller.addBreathMark(
                                        preview.ghost.staffTrackId,
                                        preview.commitTime,
                                        breathShape,
                                    )
                                    MobilePlacementTool.ORNAMENT_TRILL -> preview.sourceEventId?.let { sourceId ->
                                        controller.addOrnament(
                                            preview.ghost.staffTrackId,
                                            sourceId,
                                            ornamentKind,
                                        )
                                    }
                                    else -> Unit
                                }
                                null -> Unit
                            }
                        },
                    )
                    ScoreToolGroup.SPANS -> SpanPlacementBar(
                        selected = placementTool,
                        previewReady = spanPreview != null,
                        hairpinStyle = hairpinStyle,
                        ornamentKind = ornamentKind,
                        onHairpinStyleChange = { hairpinStyle = it; spanPreview = null },
                        onOrnamentKindChange = { ornamentKind = it; spanPreview = null },
                        onSelect = {
                            placementTool = it
                            spanPreview = null
                            controller.activate(ScoreInteractionCatalog.SPAN_SYMBOL)
                        },
                        onCancel = {
                            spanPreview = null
                            controller.cancelRun()
                        },
                        onCommit = {
                            spanPreview?.let { preview ->
                                when (val tool = preview.tool) {
                                    MobilePlacementTool.HAIRPIN_CRESCENDO,
                                    MobilePlacementTool.HAIRPIN_DIMINUENDO -> controller.addHairpin(
                                        preview.ghost.staffTrackId,
                                        preview.ghost.start,
                                        preview.ghost.end,
                                        if (tool == MobilePlacementTool.HAIRPIN_CRESCENDO) {
                                            HairpinType.CRESCENDO
                                        } else {
                                            HairpinType.DIMINUENDO
                                        },
                                        hairpinStyle,
                                    )
                                    MobilePlacementTool.OCTAVE_UP,
                                    MobilePlacementTool.OCTAVE_DOWN -> controller.addOctaveShift(
                                        preview.ghost.staffTrackId,
                                        preview.ghost.start,
                                        preview.ghost.end,
                                        if (tool == MobilePlacementTool.OCTAVE_UP) {
                                            OctaveShiftType.OTTAVA
                                        } else {
                                            OctaveShiftType.OTTAVA_BASSA
                                        },
                                    )
                                    MobilePlacementTool.GRADUAL_RITARDANDO,
                                    MobilePlacementTool.GRADUAL_ACCELERANDO -> controller.addGradualTempo(
                                        preview.ghost.start,
                                        preview.ghost.end,
                                        if (tool == MobilePlacementTool.GRADUAL_ACCELERANDO) {
                                            TempoMarkType.ACCELERANDO
                                        } else {
                                            TempoMarkType.RITARDANDO
                                        },
                                    )
                                    MobilePlacementTool.ORNAMENT_TRILL_SPAN -> preview.start.sourceEventId?.let { sourceId ->
                                        controller.addOrnament(
                                            preview.ghost.staffTrackId,
                                            sourceId,
                                            ornamentKind,
                                            preview.ghost.end,
                                        )
                                    }
                                    else -> Unit
                                }
                            }
                        },
                    )
                    ScoreToolGroup.SELECTION -> if (
                        state.interaction.activeCommandId == ScoreInteractionCatalog.PROPERTY
                    ) {
                        PropertyDraftBar(state.frame, controller)
                    } else {
                        SelectionTransformBar(
                            appendSelection = appendSelection,
                            onToggleAppend = { appendSelection = !appendSelection },
                            controller = controller,
                        )
                    }
                    ScoreToolGroup.STRUCTURE -> StructureBoundaryBar(
                        boundary = structureBoundary?.measureNumber,
                        selected = state.frame.selection.singleOrNull(),
                        firstStaffId = state.frame.runtimeScore.staffTracks.keys.firstOrNull(),
                        maxMeasure = state.frame.runtimeScore.measures.maxOfOrNull { it.value.number } ?: 1,
                        controller = controller,
                        onCancel = {
                            structureBoundary = null
                            controller.cancelRun()
                        },
                    )
                    ScoreToolGroup.ENGRAVING -> GeometryHandleBar(
                        selected = state.frame.selection.singleOrNull(),
                        renderFrame = renderFrame,
                        controller = controller,
                    )
                    else -> Unit
                }
                ToolGroupBar(controller, state.activeToolGroup, state.interaction.activeCommandId)
                NavigationBar {
                    MobileScoreActivity.entries.forEach { activity ->
                        NavigationBarItem(
                            selected = state.activity == activity,
                            onClick = { controller.switchActivity(activity) },
                            icon = { Text(activity.shortLabel) },
                            label = { Text(activity.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            InteractionStatus(
                family = state.interaction.family.name,
                phase = state.interaction.phase.name,
                position = state.interaction.entryCursor?.position,
            )
            FocusedScoreView(
                renderFrame = renderFrame,
                controller = controller,
                renderCoordinator = renderCoordinator,
                editingFrame = state.frame,
                activeToolGroup = state.activeToolGroup,
                entryPosition = state.interaction.entryCursor?.position
                    ?.takeIf { state.activeToolGroup == ScoreToolGroup.NOTES },
                selectedElementIds = selectedElementIds,
                appendSelection = appendSelection,
                structureBoundary = structureBoundary,
                onStructureBoundaryChange = {
                    structureBoundary = it
                    it?.let { hit ->
                        controller.target(listOf(ScoreInteractionAnchor.Boundary(hit.measureNumber)), preview = true)
                    }
                },
                noteInput = state.noteInput,
                noteGhost = noteGhost,
                onGhostChange = { noteGhost = it },
                placementTool = placementTool,
                dynamicLevel = dynamicLevel,
                tempoBpm = tempoBpm,
                fermataShape = fermataShape,
                breathShape = breathShape,
                ornamentKind = ornamentKind,
                hairpinStyle = hairpinStyle,
                pointPreview = pointPreview,
                onPointPreviewChange = { pointPreview = it },
                spanPreview = spanPreview,
                onSpanPreviewChange = { spanPreview = it },
                bravuraTypeface = bravuraTypeface,
                textMeasurer = textMeasurer,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun InteractionStatus(family: String, phase: String, position: TimeCode?) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("$family · $phase", style = MaterialTheme.typography.labelLarge)
            Text(position?.let { "第 ${it.measure} 小节 · ${it.beat ?: Fraction.ZERO}" } ?: "选择插入位置")
        }
    }
}

@Composable
private fun FocusedScoreView(
    renderFrame: AndroidScoreRenderFrame,
    controller: MobileScoreEditorController,
    renderCoordinator: AndroidScoreRenderCoordinator,
    editingFrame: ScoreEditingFrame,
    activeToolGroup: ScoreToolGroup,
    entryPosition: TimeCode?,
    selectedElementIds: Set<RenderElementId>,
    appendSelection: Boolean,
    structureBoundary: RenderedBarlineHit?,
    onStructureBoundaryChange: (RenderedBarlineHit?) -> Unit,
    noteInput: MobileNoteInputState,
    noteGhost: GhostNote?,
    onGhostChange: (GhostNote?) -> Unit,
    placementTool: MobilePlacementTool,
    dynamicLevel: DynamicLevel,
    tempoBpm: Float,
    fermataShape: FermataShape,
    breathShape: BreathMarkShape,
    ornamentKind: OrnamentKind,
    hairpinStyle: HairpinStyle,
    pointPreview: MobilePointPreview?,
    onPointPreviewChange: (MobilePointPreview?) -> Unit,
    spanPreview: MobileSpanPreview?,
    onSpanPreviewChange: (MobileSpanPreview?) -> Unit,
    bravuraTypeface: android.graphics.Typeface,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    modifier: Modifier = Modifier,
) {
    val result = renderFrame.result
    val density = LocalDensity.current.density
    val latestSpanPreview = rememberUpdatedState(spanPreview)
    val latestOnSpanPreviewChange = rememberUpdatedState(onSpanPreviewChange)
    var marqueeStart by remember(result) { mutableStateOf<AbsolutePoint?>(null) }
    var marqueeRect by remember(result) { mutableStateOf<AbsoluteRect?>(null) }
    var selectionCandidates by remember(result, editingFrame.revision) {
        mutableStateOf<List<ScoreSelectionTarget>>(emptyList())
    }
    var geometryDrag by remember(result, editingFrame.revision) {
        mutableStateOf<MobileGeometryDragPreview?>(null)
    }
    var semanticDragBoundary by remember(result, editingFrame.revision) {
        mutableStateOf<RenderedBarlineHit?>(null)
    }
    var spanDragOriginal by remember(result, editingFrame.revision) {
        mutableStateOf<MobileSpanPreview?>(null)
    }
    var spanDraggingEndpoint by remember(result, editingFrame.revision) {
        mutableStateOf<MobileSpanEndpoint?>(null)
    }

    fun spanKind(): ExpressionSpanKind? = when (placementTool) {
        MobilePlacementTool.HAIRPIN_CRESCENDO -> ExpressionSpanKind.Hairpin(
            HairpinType.CRESCENDO,
            hairpinStyle,
        )
        MobilePlacementTool.HAIRPIN_DIMINUENDO -> ExpressionSpanKind.Hairpin(
            HairpinType.DIMINUENDO,
            hairpinStyle,
        )
        MobilePlacementTool.OCTAVE_UP -> ExpressionSpanKind.Octave(OctaveShiftType.OTTAVA)
        MobilePlacementTool.OCTAVE_DOWN -> ExpressionSpanKind.Octave(OctaveShiftType.OTTAVA_BASSA)
        MobilePlacementTool.GRADUAL_ACCELERANDO -> ExpressionSpanKind.GradualTempo(TempoMarkType.ACCELERANDO)
        MobilePlacementTool.GRADUAL_RITARDANDO -> ExpressionSpanKind.GradualTempo(TempoMarkType.RITARDANDO)
        MobilePlacementTool.ORNAMENT_TRILL_SPAN -> ExpressionSpanKind.Ornament(ornamentKind)
        else -> null
    }

    fun spanAnchorAt(point: AbsolutePoint): MobileSpanAnchor? {
        val displayed = result ?: return null
        val target = renderCoordinator.computeNoteGhost(
            editingFrame,
            displayed,
            point,
            noteInput.duration,
            false,
        ) ?: return null
        val sourceEventId = (renderCoordinator.selectionTargetAt(displayed, point)
            as? ScoreSelectionTarget.Event)?.eventId
        return MobileSpanAnchor(target.staffTrackId, target.onset, sourceEventId)
    }

    fun publishSpan(preview: MobileSpanPreview?) {
        latestOnSpanPreviewChange.value(preview)
        preview?.let {
            controller.target(
                listOf(
                    ScoreInteractionAnchor.StaffTime(it.ghost.staffTrackId, it.ghost.start),
                    ScoreInteractionAnchor.StaffTime(it.ghost.staffTrackId, it.ghost.end),
                ),
                preview = true,
            )
        }
    }

    fun createDefaultSpan(anchor: MobileSpanAnchor): MobileSpanPreview? {
        if (placementTool == MobilePlacementTool.ORNAMENT_TRILL_SPAN && anchor.sourceEventId == null) return null
        val kind = spanKind() ?: return null
        val ghost = renderCoordinator.computeDefaultExpressionSpanGhost(
            editingFrame,
            result ?: return null,
            anchor.staffTrackId,
            anchor.time,
            kind,
        ) ?: return null
        return MobileSpanPreview(placementTool, anchor, ghost, MobileSpanEndpoint.END)
    }

    fun moveSpanEndpoint(
        preview: MobileSpanPreview,
        endpoint: MobileSpanEndpoint,
        anchor: MobileSpanAnchor,
    ): MobileSpanPreview? {
        if (anchor.staffTrackId != preview.ghost.staffTrackId) return null
        if (endpoint == MobileSpanEndpoint.START &&
            placementTool == MobilePlacementTool.ORNAMENT_TRILL_SPAN && anchor.sourceEventId == null
        ) return null
        val start = if (endpoint == MobileSpanEndpoint.START) anchor else preview.start
        val end = if (endpoint == MobileSpanEndpoint.END) anchor.time else preview.ghost.end
        if (end <= start.time) return null
        val ghost = renderCoordinator.computeExpressionSpanGhost(
            editingFrame,
            result ?: return null,
            start.staffTrackId,
            start.time,
            end,
            spanKind() ?: return null,
        ) ?: return null
        return preview.copy(start = start, ghost = ghost, activeEndpoint = endpoint)
    }
    fun commitSelection(target: ScoreSelectionTarget?) {
        val current = editingFrame.selection
        val updated = when {
            target == null && appendSelection -> current
            target == null -> emptyList()
            !appendSelection -> listOf(target)
            target in current -> current.filterNot { it == target }
            else -> mergeSelectionTargets(current + target)
        }
        controller.setSelection(updated)
        selectionCandidates = emptyList()
    }
    Column(modifier = modifier.fillMaxWidth().padding(12.dp)) {
        Text("聚焦谱段", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        when {
            result != null -> Box(
                modifier = Modifier.fillMaxSize()
                    .horizontalScroll(rememberScrollState())
                    .verticalScroll(rememberScrollState()),
            ) {
                Canvas(
                    modifier = Modifier
                        .width(maxOf(360f, result.bounds.width.value).dp)
                        .height(maxOf(220f, result.bounds.height.value).dp)
                        .background(Color.White)
                        .pointerInput(result, activeToolGroup, appendSelection, editingFrame.revision) {
                            if (activeToolGroup != ScoreToolGroup.SELECTION) return@pointerInput
                            detectDragGestures(
                                onDragStart = { local ->
                                    selectionCandidates = emptyList()
                                    val start = AbsolutePoint(
                                        Pixels(local.x / density + result.bounds.origin.x.value),
                                        Pixels(local.y / density + result.bounds.origin.y.value),
                                    )
                                    marqueeStart = start
                                    marqueeRect = AbsoluteRect(start, Pixels.ZERO, Pixels.ZERO)
                                },
                                onDragCancel = {
                                    marqueeStart = null
                                    marqueeRect = null
                                },
                                onDragEnd = {
                                    val rect = marqueeRect
                                    if (rect != null && rect.width.value >= 4f && rect.height.value >= 4f) {
                                        val targets = renderCoordinator.selectionTargetsInRegion(result, rect)
                                        controller.setSelection(
                                            if (appendSelection) mergeSelectionTargets(editingFrame.selection + targets)
                                            else targets,
                                        )
                                    }
                                    marqueeStart = null
                                    marqueeRect = null
                                },
                            ) { change, _ ->
                                val start = marqueeStart ?: return@detectDragGestures
                                val current = AbsolutePoint(
                                    Pixels(change.position.x / density + result.bounds.origin.x.value),
                                    Pixels(change.position.y / density + result.bounds.origin.y.value),
                                )
                                val left = minOf(start.x.value, current.x.value)
                                val top = minOf(start.y.value, current.y.value)
                                marqueeRect = AbsoluteRect(
                                    AbsolutePoint(Pixels(left), Pixels(top)),
                                    Pixels(kotlin.math.abs(current.x.value - start.x.value)),
                                    Pixels(kotlin.math.abs(current.y.value - start.y.value)),
                                )
                                change.consume()
                            }
                        }
                        .pointerInput(result, activeToolGroup, editingFrame.selection, editingFrame.revision) {
                            if (activeToolGroup != ScoreToolGroup.ENGRAVING) return@pointerInput
                            val selected = editingFrame.selection.singleOrNull()
                            if (selected !is ScoreSelectionTarget.Slur &&
                                selected !is ScoreSelectionTarget.Tie &&
                                selected !is ScoreSelectionTarget.Beam &&
                                selected !is ScoreSelectionTarget.Articulation &&
                                selected !is ScoreSelectionTarget.Attachment &&
                                selected !is ScoreSelectionTarget.VoltaEnding &&
                                selected !is ScoreSelectionTarget.NavigationMark
                            ) return@pointerInput
                            var dragging = false
                            detectDragGestures(
                                onDragStart = { local ->
                                    val point = AbsolutePoint(
                                        Pixels(local.x / density + result.bounds.origin.x.value),
                                        Pixels(local.y / density + result.bounds.origin.y.value),
                                    )
                                    if (renderCoordinator.selectionCandidatesAt(result, point).any {
                                            selectionIdentityEquals(it, selected)
                                        }
                                    ) {
                                        if (selected is ScoreSelectionTarget.VoltaEnding ||
                                            selected is ScoreSelectionTarget.NavigationMark
                                        ) {
                                            dragging = true
                                            semanticDragBoundary = result.barlineHitAt(point, Pixels(10_000f))
                                        } else {
                                            val elements = renderCoordinator.elementsForSelection(result, selected)
                                            if (elements.isNotEmpty()) {
                                                dragging = true
                                                geometryDrag = MobileGeometryDragPreview(elements)
                                            }
                                        }
                                    }
                                },
                                onDragCancel = {
                                    dragging = false
                                    geometryDrag = null
                                    semanticDragBoundary = null
                                    controller.cancelRun()
                                },
                                onDragEnd = {
                                    val preview = geometryDrag
                                    val boundary = semanticDragBoundary
                                    if (dragging && selected is ScoreSelectionTarget.NavigationMark && boundary != null) {
                                        controller.nudgeSelectedNavigation(
                                            boundaryDelta = boundary.measureNumber - selected.boundaryMeasure,
                                        )
                                    } else if (dragging && selected is ScoreSelectionTarget.VoltaEnding && boundary != null) {
                                        val sourceBoundary = if (2 in selected.numbers) {
                                            selected.endMeasure
                                        } else {
                                            selected.startMeasure
                                        }
                                        controller.resizeSelectedVolta(boundary.measureNumber - sourceBoundary)
                                    } else if (dragging && preview != null && (preview.dx != 0f || preview.dy != 0f)) {
                                        val origin = result.transformerSnapshot.toRelative(AbsolutePoint.ZERO)
                                        val delta = result.transformerSnapshot.toRelative(
                                            AbsolutePoint(Pixels(preview.dx), Pixels(preview.dy)),
                                        )
                                        controller.nudgeSelectedGeometry(
                                            result.capturedGeometry,
                                            delta.x.value - origin.x.value,
                                            delta.y.value - origin.y.value,
                                        )
                                    }
                                    dragging = false
                                    geometryDrag = null
                                    semanticDragBoundary = null
                                },
                            ) { change, dragAmount ->
                                if (!dragging) return@detectDragGestures
                                if (selected is ScoreSelectionTarget.VoltaEnding ||
                                    selected is ScoreSelectionTarget.NavigationMark
                                ) {
                                    val point = AbsolutePoint(
                                        Pixels(change.position.x / density + result.bounds.origin.x.value),
                                        Pixels(change.position.y / density + result.bounds.origin.y.value),
                                    )
                                    semanticDragBoundary = result.barlineHitAt(point, Pixels(10_000f))
                                    change.consume()
                                    return@detectDragGestures
                                }
                                val preview = geometryDrag ?: return@detectDragGestures
                                geometryDrag = preview.copy(
                                    dx = preview.dx + dragAmount.x / density,
                                    dy = preview.dy + dragAmount.y / density,
                                )
                                change.consume()
                            }
                        }
                        .pointerInput(
                            result,
                            activeToolGroup,
                            placementTool,
                            editingFrame.revision,
                            noteInput.duration,
                            hairpinStyle,
                            ornamentKind,
                        ) {
                            if (activeToolGroup != ScoreToolGroup.SPANS) return@pointerInput
                            var workingPreview: MobileSpanPreview? = null
                            detectDragGestures(
                                onDragStart = { local ->
                                    val point = AbsolutePoint(
                                        Pixels(local.x / density + result.bounds.origin.x.value),
                                        Pixels(local.y / density + result.bounds.origin.y.value),
                                    )
                                    val existing = latestSpanPreview.value
                                    spanDragOriginal = existing
                                    if (existing == null) {
                                        val created = spanAnchorAt(point)?.let(::createDefaultSpan)
                                        if (created != null) {
                                            workingPreview = created
                                            spanDraggingEndpoint = MobileSpanEndpoint.END
                                            publishSpan(created)
                                        }
                                    } else {
                                        val endpoint = spanEndpointHit(existing.ghost, point, 22f)
                                        if (endpoint != null) {
                                            val selected = existing.copy(activeEndpoint = endpoint)
                                            workingPreview = selected
                                            spanDraggingEndpoint = endpoint
                                            publishSpan(selected)
                                        }
                                    }
                                },
                                onDragCancel = {
                                    publishSpan(spanDragOriginal)
                                    workingPreview = null
                                    spanDraggingEndpoint = null
                                    spanDragOriginal = null
                                },
                                onDragEnd = {
                                    workingPreview = null
                                    spanDraggingEndpoint = null
                                    spanDragOriginal = null
                                },
                            ) { change, _ ->
                                val preview = workingPreview ?: return@detectDragGestures
                                val endpoint = spanDraggingEndpoint ?: return@detectDragGestures
                                val point = AbsolutePoint(
                                    Pixels(change.position.x / density + result.bounds.origin.x.value),
                                    Pixels(change.position.y / density + result.bounds.origin.y.value),
                                )
                                val moved = spanAnchorAt(point)?.let { moveSpanEndpoint(preview, endpoint, it) }
                                if (moved != null) {
                                    workingPreview = moved
                                    publishSpan(moved)
                                }
                                change.consume()
                            }
                        }
                        .pointerInput(
                            renderFrame,
                            editingFrame.revision,
                            noteInput.duration,
                            noteInput.restMode,
                            noteGhost,
                            placementTool,
                            dynamicLevel,
                            tempoBpm,
                            fermataShape,
                            breathShape,
                            ornamentKind,
                            hairpinStyle,
                            pointPreview,
                            spanPreview,
                            activeToolGroup,
                            appendSelection,
                            structureBoundary,
                            entryPosition,
                        ) {
                            detectTapGestures { local ->
                                val point = AbsolutePoint(
                                    Pixels(local.x / density + result.bounds.origin.x.value),
                                    Pixels(local.y / density + result.bounds.origin.y.value),
                                )
                                if (activeToolGroup == ScoreToolGroup.SELECTION ||
                                    activeToolGroup == ScoreToolGroup.ENGRAVING
                                ) {
                                    val candidates = renderCoordinator.selectionCandidatesAt(result, point)
                                    when {
                                        candidates.size > 1 -> selectionCandidates = candidates
                                        else -> commitSelection(candidates.singleOrNull())
                                    }
                                    return@detectTapGestures
                                }
                                if (activeToolGroup == ScoreToolGroup.STRUCTURE) {
                                    onStructureBoundaryChange(result.barlineHitAt(point, Pixels(24f)))
                                    return@detectTapGestures
                                }
                                when (placementTool) {
                                    MobilePlacementTool.NOTE -> {
                                        val candidate = renderCoordinator.computeNoteGhost(
                                            frame = editingFrame,
                                            result = result,
                                            point = point,
                                            duration = noteInput.duration,
                                            restMode = noteInput.restMode,
                                        )
                                        if (candidate != null) {
                                            val confirmsPreview = noteGhost?.let {
                                                it.voiceTrackId == candidate.voiceTrackId &&
                                                    it.onset == candidate.onset &&
                                                    it.pitch == candidate.pitch
                                            } == true
                                            controller.activate(
                                                ScoreInteractionCatalog.ENTRY_NOTE,
                                                ScoreEntryCursor(candidate.voiceTrackId, candidate.onset),
                                            )
                                            controller.target(
                                                listOf(ScoreInteractionAnchor.StaffTime(candidate.staffTrackId, candidate.onset)),
                                                preview = true,
                                            )
                                            if (confirmsPreview) {
                                                if (noteInput.restMode) controller.insertRest()
                                                else controller.insertPitch(candidate.pitch)
                                                onGhostChange(null)
                                            } else {
                                                onGhostChange(candidate)
                                            }
                                        }
                                    }
                                    MobilePlacementTool.CLEF_TREBLE,
                                    MobilePlacementTool.CLEF_BASS -> {
                                        val ghost = renderCoordinator.computeClefGhost(
                                            editingFrame,
                                            result,
                                            point,
                                            if (placementTool == MobilePlacementTool.CLEF_TREBLE) Clef.TREBLE else Clef.BASS,
                                        ) ?: return@detectTapGestures
                                        controller.target(
                                            listOf(ScoreInteractionAnchor.StaffTime(ghost.staffTrackId, ghost.onset)),
                                            preview = true,
                                        )
                                        onPointPreviewChange(MobilePointPreview.Clef(ghost))
                                    }
                                    MobilePlacementTool.KEY_C_MAJOR,
                                    MobilePlacementTool.KEY_G_MAJOR -> {
                                        val ghost = renderCoordinator.computeKeySignatureGhost(
                                            editingFrame,
                                            result,
                                            point,
                                            if (placementTool == MobilePlacementTool.KEY_C_MAJOR) {
                                                KeySignature.C_MAJOR
                                            } else {
                                                KeySignature.G_MAJOR
                                            },
                                        ) ?: return@detectTapGestures
                                        controller.target(
                                            listOf(ScoreInteractionAnchor.MeasureRange(ghost.measure, ghost.measure)),
                                            preview = true,
                                        )
                                        onPointPreviewChange(MobilePointPreview.Key(ghost))
                                    }
                                    MobilePlacementTool.TIME_FOUR_FOUR,
                                    MobilePlacementTool.TIME_THREE_FOUR -> {
                                        val meter = if (placementTool == MobilePlacementTool.TIME_FOUR_FOUR) {
                                            TimeSignature(4, 4)
                                        } else {
                                            TimeSignature(3, 4)
                                        }
                                        val ghost = renderCoordinator.computeTimeSignatureGhost(
                                            editingFrame,
                                            result,
                                            point,
                                            meter,
                                        ) ?: return@detectTapGestures
                                        controller.target(
                                            listOf(ScoreInteractionAnchor.MeasureRange(ghost.measure, ghost.measure)),
                                            preview = true,
                                        )
                                        onPointPreviewChange(MobilePointPreview.Meter(ghost))
                                    }
                                    MobilePlacementTool.DYNAMIC_MF,
                                    MobilePlacementTool.TEMPO_120,
                                    MobilePlacementTool.FERMATA,
                                    MobilePlacementTool.BREATH,
                                    MobilePlacementTool.ORNAMENT_TRILL -> {
                                        val noteCandidate = renderCoordinator.computeNoteGhost(
                                            editingFrame,
                                            result,
                                            point,
                                            noteInput.duration,
                                            false,
                                        ) ?: return@detectTapGestures
                                        val eventTarget = renderCoordinator.selectionTargetAt(result, point)
                                            as? ScoreSelectionTarget.Event
                                        if (placementTool == MobilePlacementTool.ORNAMENT_TRILL && eventTarget == null) {
                                            return@detectTapGestures
                                        }
                                        val event = eventTarget?.let {
                                            editingFrame.computedScore.getComputedEvent(it.eventId)
                                        }
                                        val timeMap = result.scoreTimeMap
                                        val afterEvent = event?.let { computed ->
                                            timeMap?.timeCodeAt(
                                                timeMap.absolute(computed.onset) + computed.duration.toFraction(),
                                            )
                                        }
                                        val commitTime = when (placementTool) {
                                            MobilePlacementTool.FERMATA,
                                            MobilePlacementTool.BREATH -> afterEvent ?: noteCandidate.onset
                                            else -> noteCandidate.onset
                                        }
                                        val kind = when (placementTool) {
                                            MobilePlacementTool.DYNAMIC_MF -> PointSymbolKind.Dynamic(dynamicLevel)
                                            MobilePlacementTool.TEMPO_120 -> PointSymbolKind.Tempo(tempoBpm)
                                            MobilePlacementTool.FERMATA -> PointSymbolKind.Fermata(fermataShape)
                                            MobilePlacementTool.BREATH -> PointSymbolKind.Breath(breathShape)
                                            MobilePlacementTool.ORNAMENT_TRILL -> PointSymbolKind.Ornament(ornamentKind)
                                            else -> return@detectTapGestures
                                        }
                                        val displayTime = event?.onset ?: noteCandidate.onset
                                        val ghost = renderCoordinator.computePointSymbolGhost(
                                            editingFrame,
                                            result,
                                            noteCandidate.staffTrackId,
                                            displayTime,
                                            kind,
                                        ) ?: return@detectTapGestures
                                        controller.target(
                                            listOf(ScoreInteractionAnchor.StaffTime(ghost.staffTrackId, commitTime)),
                                            preview = true,
                                        )
                                        onPointPreviewChange(
                                            MobilePointPreview.Symbol(
                                                placementTool,
                                                ghost,
                                                commitTime,
                                                eventTarget?.eventId,
                                            ),
                                        )
                                    }
                                    MobilePlacementTool.HAIRPIN_CRESCENDO,
                                    MobilePlacementTool.HAIRPIN_DIMINUENDO,
                                    MobilePlacementTool.OCTAVE_UP,
                                    MobilePlacementTool.OCTAVE_DOWN,
                                    MobilePlacementTool.GRADUAL_RITARDANDO,
                                    MobilePlacementTool.GRADUAL_ACCELERANDO,
                                    MobilePlacementTool.ORNAMENT_TRILL_SPAN -> {
                                        val existing = spanPreview
                                        val endpoint = existing?.let { spanEndpointHit(it.ghost, point, 22f) }
                                        if (existing != null && endpoint != null) {
                                            publishSpan(existing.copy(activeEndpoint = endpoint))
                                            return@detectTapGestures
                                        }
                                        val anchor = spanAnchorAt(point) ?: return@detectTapGestures
                                        val updated = if (existing == null || existing.tool != placementTool) {
                                            createDefaultSpan(anchor)
                                        } else {
                                            moveSpanEndpoint(existing, existing.activeEndpoint, anchor)
                                        }
                                        if (updated != null) publishSpan(updated)
                                    }
                                }
                            }
                        }
                        .semantics {
                            contentDescription = "真实乐谱渲染；点按拍位可移动输入光标"
                        },
                ) {
                    drawScoreResult(
                        result = result,
                        textMeasurer = textMeasurer,
                        bravuraTypeface = bravuraTypeface,
                        entryPosition = entryPosition,
                        selectedElementIds = selectedElementIds,
                        selectedBarlineHit = semanticDragBoundary ?: structureBoundary,
                        marqueeRect = marqueeRect,
                        geometryDragElements = geometryDrag?.elements.orEmpty(),
                        geometryDragDx = geometryDrag?.dx ?: 0f,
                        geometryDragDy = geometryDrag?.dy ?: 0f,
                        previewCommands = noteGhost?.commands.orEmpty() +
                            pointPreview?.commands.orEmpty() +
                            spanPreview?.ghost?.commands.orEmpty(),
                    )
                    spanPreview?.let { preview ->
                        val handleSize = 11.dp.toPx()
                        val strokeWidth = 1.5.dp.toPx()
                        listOf(
                            MobileSpanEndpoint.START to preview.ghost.startHandle,
                            MobileSpanEndpoint.END to preview.ghost.endHandle,
                        ).forEach { (endpoint, handle) ->
                            val center = Offset(
                                (handle.x.value - result.bounds.origin.x.value) * density,
                                (handle.y.value - result.bounds.origin.y.value) * density,
                            )
                            val topLeft = center - Offset(handleSize / 2f, handleSize / 2f)
                            drawRect(
                                color = if (preview.activeEndpoint == endpoint) {
                                    Color(0xFF2563EB)
                                } else {
                                    Color.White
                                },
                                topLeft = topLeft,
                                size = Size(handleSize, handleSize),
                                style = Fill,
                            )
                            drawRect(
                                color = Color(0xFF2563EB),
                                topLeft = topLeft,
                                size = Size(handleSize, handleSize),
                                style = Stroke(width = strokeWidth),
                            )
                        }
                    }
                }
                if (noteGhost != null) {
                    Text(
                        if (noteInput.restMode) "再次点按同一位置写入休止符"
                        else "再次点按同一位置直接写入；或使用下方钢琴决定音高",
                        modifier = Modifier.align(Alignment.BottomStart)
                            .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.88f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (selectionCandidates.isNotEmpty()) {
                    Column(
                        modifier = Modifier.align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            .padding(6.dp),
                    ) {
                        Text("请选择目标", style = MaterialTheme.typography.labelLarge)
                        selectionCandidates.take(6).forEach { candidate ->
                            TextButton(onClick = { commitSelection(candidate) }) {
                                Text(selectionTargetLabel(candidate))
                            }
                        }
                        TextButton(onClick = { selectionCandidates = emptyList() }) { Text("取消") }
                    }
                }
            }

            renderFrame.error != null -> Text(
                "乐谱渲染失败：${renderFrame.error}",
                color = MaterialTheme.colorScheme.error,
            )

            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在生成乐谱…")
            }
        }
    }
}

private fun mergeSelectionTargets(targets: List<ScoreSelectionTarget>): List<ScoreSelectionTarget> {
    val events = linkedMapOf<Pair<EventId, com.mecon.api.primitive.TrackId?>, ScoreSelectionTarget.Event>()
    val others = linkedSetOf<ScoreSelectionTarget>()
    targets.forEach { target ->
        if (target is ScoreSelectionTarget.Event) {
            val key = target.eventId to target.voiceTrackId
            val existing = events[key]
            val existingPitches = existing?.pitchIndices
            val targetPitches = target.pitchIndices
            events[key] = if (existing == null) target else existing.copy(
                pitchIndices = if (existingPitches == null || targetPitches == null) {
                    null
                } else {
                    existingPitches + targetPitches
                },
            )
        } else {
            others += target
        }
    }
    return events.values + others
}

private fun selectionTargetLabel(target: ScoreSelectionTarget): String = when (target) {
    is ScoreSelectionTarget.Event -> if (target.pitchIndices == null) "音符/休止" else "和弦音 ${target.pitchIndices}"
    is ScoreSelectionTarget.Clef -> "谱号 · 第 ${target.onset.measure} 小节"
    is ScoreSelectionTarget.KeySignature -> "调号 · 第 ${target.onset.measure} 小节"
    is ScoreSelectionTarget.TimeSignature -> "拍号 · 第 ${target.onset.measure} 小节"
    is ScoreSelectionTarget.Barline -> "小节线 · 边界 ${target.boundaryMeasure}"
    is ScoreSelectionTarget.VoltaEnding -> "房子 ${target.numbers}"
    is ScoreSelectionTarget.NavigationMark -> "导航 · ${target.mark}"
    is ScoreSelectionTarget.Slur -> "连音线"
    is ScoreSelectionTarget.Tie -> "延音线"
    is ScoreSelectionTarget.Beam -> "符杠"
    is ScoreSelectionTarget.Articulation -> "演奏法"
    is ScoreSelectionTarget.Attachment -> "附加记号"
    is ScoreSelectionTarget.LayoutBreak -> "换行 · 第 ${target.beforeMeasure} 小节前"
    is ScoreSelectionTarget.StaffVisibility -> "隐藏谱表 · ${target.startMeasure}–${target.endMeasure}"
}

private fun selectionIdentityEquals(a: ScoreSelectionTarget, b: ScoreSelectionTarget): Boolean = when {
    a is ScoreSelectionTarget.Slur && b is ScoreSelectionTarget.Slur -> a.slurId == b.slurId
    a is ScoreSelectionTarget.Tie && b is ScoreSelectionTarget.Tie ->
        a.sourceEventId == b.sourceEventId && a.sourcePitchIndex == b.sourcePitchIndex
    a is ScoreSelectionTarget.Beam && b is ScoreSelectionTarget.Beam -> a.groupId == b.groupId
    a is ScoreSelectionTarget.Articulation && b is ScoreSelectionTarget.Articulation ->
        a.eventId == b.eventId &&
            (a.articulationIndex == null || b.articulationIndex == null || a.articulationIndex == b.articulationIndex)
    a is ScoreSelectionTarget.Attachment && b is ScoreSelectionTarget.Attachment -> a.attachmentId == b.attachmentId
    else -> a == b
}

@Composable
private fun EntryContextBar(
    controller: MobileScoreEditorController,
    pianoVisible: Boolean,
    onTogglePiano: () -> Unit,
    chordLatch: Boolean,
    pendingChordMidi: Set<Int>,
    onToggleChordLatch: () -> Unit,
    onClearChord: () -> Unit,
    onWriteChord: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val cursor = state.interaction.entryCursor
    var tupletCount by remember { mutableIntStateOf(3) }
    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Button(onClick = onTogglePiano) {
                Text(if (pianoVisible) "收起钢琴" else "打开钢琴")
            }
            if (chordLatch) Button(onClick = onToggleChordLatch) { Text("和弦输入：开") }
            else TextButton(onClick = onToggleChordLatch) { Text("和弦输入") }
            Button(
                enabled = cursor != null && pendingChordMidi.isNotEmpty(),
                onClick = onWriteChord,
            ) { Text("写入和弦 (${pendingChordMidi.size})") }
            TextButton(enabled = pendingChordMidi.isNotEmpty(), onClick = onClearChord) { Text("清空和弦") }
            CursorButton("上一音", ScoreEntryCursorAction.PREVIOUS_NOTE, controller)
            CursorButton("下一音", ScoreEntryCursorAction.NEXT_NOTE, controller)
            CursorButton("上一小节", ScoreEntryCursorAction.PREVIOUS_MEASURE, controller)
            CursorButton("下一小节", ScoreEntryCursorAction.NEXT_MEASURE, controller)
            TextButton(onClick = { tupletCount = if (tupletCount == 3) 5 else 3 }) { Text("$tupletCount 连音") }
            Button(
                enabled = cursor != null,
                onClick = {
                    cursor?.let {
                        controller.createTupletRegion(
                            it.voiceTrackId,
                            it.position,
                            state.noteInput.duration,
                            tupletCount,
                        )
                    }
                },
            ) { Text("建 ${state.noteInput.duration.shortLabel()} 范围") }
        }
    }
}

@Composable
private fun DurationInputBar(controller: MobileScoreEditorController) {
    val state by controller.state.collectAsState()
    val input = state.noteInput
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("时值", style = MaterialTheme.typography.labelLarge)
        listOf(
            DurationBase.WHOLE to "全",
            DurationBase.HALF to "二分",
            DurationBase.QUARTER to "四分",
            DurationBase.EIGHTH to "八分",
            DurationBase.SIXTEENTH to "十六分",
            DurationBase.THIRTY_SECOND to "三十二分",
        ).forEach { (base, label) ->
            if (input.base == base) {
                Button(onClick = { controller.selectDuration(base) }) { Text(label) }
            } else {
                TextButton(onClick = { controller.selectDuration(base) }) { Text(label) }
            }
        }
        if (input.dots > 0) {
            Button(onClick = controller::toggleDot) { Text("附点 ·") }
        } else {
            TextButton(onClick = controller::toggleDot) { Text("附点") }
        }
        if (input.restMode) {
            Button(onClick = { controller.setRestMode(false) }) { Text("休止模式") }
        } else {
            TextButton(onClick = { controller.setRestMode(true) }) { Text("休止") }
        }
        Button(
            enabled = state.interaction.entryCursor != null,
            onClick = { controller.insertRest() },
        ) { Text("写入休止") }
    }
}

@Composable
private fun PianoKeyboard(
    controller: MobileScoreEditorController,
    chordLatch: Boolean,
    pendingChordMidi: Set<Int>,
    onChordKey: (Int) -> Unit,
) {
    data class WhiteKey(val midi: Int, val label: String)
    data class BlackKey(val midi: Int, val label: String, val afterWhiteIndex: Int)

    val whiteKeys = remember {
        listOf(
            WhiteKey(60, "C4"), WhiteKey(62, "D4"), WhiteKey(64, "E4"), WhiteKey(65, "F4"),
            WhiteKey(67, "G4"), WhiteKey(69, "A4"), WhiteKey(71, "B4"), WhiteKey(72, "C5"),
            WhiteKey(74, "D5"), WhiteKey(76, "E5"), WhiteKey(77, "F5"), WhiteKey(79, "G5"),
            WhiteKey(81, "A5"), WhiteKey(83, "B5"), WhiteKey(84, "C6"),
        )
    }
    val blackKeys = remember {
        listOf(
            BlackKey(61, "C♯4", 0), BlackKey(63, "D♯4", 1),
            BlackKey(66, "F♯4", 3), BlackKey(68, "G♯4", 4), BlackKey(70, "A♯4", 5),
            BlackKey(73, "C♯5", 7), BlackKey(75, "D♯5", 8),
            BlackKey(78, "F♯5", 10), BlackKey(80, "G♯5", 11), BlackKey(82, "A♯5", 12),
        )
    }
    val whiteWidth = 50.dp
    val blackWidth = 32.dp
    val cursorAvailable = controller.state.collectAsState().value.interaction.entryCursor != null

    Box(
        modifier = Modifier.fillMaxWidth().height(152.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(rememberScrollState()),
    ) {
        Box(modifier = Modifier.width(whiteWidth * whiteKeys.size).height(148.dp)) {
            Row {
                whiteKeys.forEach { key ->
                    Box(
                        modifier = Modifier.width(whiteWidth).height(146.dp)
                            .background(if (key.midi in pendingChordMidi) Color(0xFFBFDBFE) else Color.White)
                            .border(1.dp, Color(0xFF777777))
                            .clickable(enabled = cursorAvailable) {
                                if (chordLatch) onChordKey(key.midi) else {
                                    controller.setRestMode(false)
                                    controller.insertMidiNote(key.midi)
                                }
                            }
                            .semantics { contentDescription = "输入 ${key.label}" },
                        contentAlignment = Alignment.BottomCenter,
                    ) { Text(key.label, color = Color.Black, modifier = Modifier.padding(bottom = 8.dp)) }
                }
            }
            blackKeys.forEach { key ->
                Box(
                    modifier = Modifier.offset(
                        x = whiteWidth * (key.afterWhiteIndex + 1) - blackWidth / 2,
                    ).width(blackWidth).height(92.dp)
                        .background(if (key.midi in pendingChordMidi) Color(0xFF2563EB) else Color(0xFF171717))
                        .border(1.dp, Color.Black)
                        .clickable(enabled = cursorAvailable) {
                            if (chordLatch) onChordKey(key.midi) else {
                                controller.setRestMode(false)
                                controller.insertMidiNote(key.midi)
                            }
                        }
                        .semantics { contentDescription = "输入 ${key.label}" },
                    contentAlignment = Alignment.BottomCenter,
                ) { Text(key.label, color = Color.White, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

private fun Duration.shortLabel(): String = buildString {
    append(
        when (base) {
            DurationBase.WHOLE -> "全音符"
            DurationBase.HALF -> "二分"
            DurationBase.QUARTER -> "四分"
            DurationBase.EIGHTH -> "八分"
            DurationBase.SIXTEENTH -> "十六分"
            DurationBase.THIRTY_SECOND -> "三十二分"
            else -> base.displayName
        },
    )
    repeat(dots) { append("·") }
}

@Composable
private fun CursorButton(
    label: String,
    action: ScoreEntryCursorAction,
    controller: MobileScoreEditorController,
) {
    TextButton(onClick = { controller.moveEntryCursor(action) }) { Text(label) }
}

@Composable
private fun PointPlacementBar(
    selected: MobilePlacementTool,
    previewReady: Boolean,
    dynamicLevel: DynamicLevel,
    tempoBpm: Float,
    fermataShape: FermataShape,
    breathShape: BreathMarkShape,
    ornamentKind: OrnamentKind,
    onDynamicLevelChange: (DynamicLevel) -> Unit,
    onTempoBpmChange: (Float) -> Unit,
    onFermataShapeChange: (FermataShape) -> Unit,
    onBreathShapeChange: (BreathMarkShape) -> Unit,
    onOrnamentKindChange: (OrnamentKind) -> Unit,
    onSelect: (MobilePlacementTool) -> Unit,
    onCancel: () -> Unit,
    onCommit: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(
                MobilePlacementTool.CLEF_TREBLE to "高音谱号",
                MobilePlacementTool.CLEF_BASS to "低音谱号",
                MobilePlacementTool.KEY_C_MAJOR to "C 大调",
                MobilePlacementTool.KEY_G_MAJOR to "G 大调",
                MobilePlacementTool.TIME_FOUR_FOUR to "4/4",
                MobilePlacementTool.TIME_THREE_FOUR to "3/4",
                MobilePlacementTool.DYNAMIC_MF to dynamicLevel.letters,
                MobilePlacementTool.TEMPO_120 to "♩=${tempoBpm.toInt()}",
                MobilePlacementTool.FERMATA to "延长记号",
                MobilePlacementTool.BREATH to "换气",
                MobilePlacementTool.ORNAMENT_TRILL to ornamentKind.name,
            ).forEach { (tool, label) ->
                if (selected == tool) Button(onClick = { onSelect(tool) }) { Text(label) }
                else TextButton(onClick = { onSelect(tool) }) { Text(label) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (selected) {
                MobilePlacementTool.DYNAMIC_MF -> {
                    Text("力度")
                    listOf(DynamicLevel.PP, DynamicLevel.P, DynamicLevel.MP, DynamicLevel.MF,
                        DynamicLevel.F, DynamicLevel.FF, DynamicLevel.SFZ).forEach { level ->
                        if (level == dynamicLevel) Button(onClick = { onDynamicLevelChange(level) }) {
                            Text(level.letters)
                        } else TextButton(onClick = { onDynamicLevelChange(level) }) { Text(level.letters) }
                    }
                }
                MobilePlacementTool.TEMPO_120 -> {
                    Text("BPM ${tempoBpm.toInt()}")
                    TextButton(onClick = { onTempoBpmChange(tempoBpm - 5f) }) { Text("−5") }
                    TextButton(onClick = { onTempoBpmChange(tempoBpm + 5f) }) { Text("+5") }
                }
                MobilePlacementTool.FERMATA -> FermataShape.entries.forEach { shape ->
                    val label = when (shape) {
                        FermataShape.VERY_SHORT -> "极短"
                        FermataShape.SHORT -> "短"
                        FermataShape.NORMAL -> "普通"
                        FermataShape.LONG -> "长"
                        FermataShape.VERY_LONG -> "极长"
                    }
                    if (shape == fermataShape) Button(onClick = { onFermataShapeChange(shape) }) { Text(label) }
                    else TextButton(onClick = { onFermataShapeChange(shape) }) { Text(label) }
                }
                MobilePlacementTool.BREATH -> BreathMarkShape.entries.forEach { shape ->
                    if (shape == breathShape) Button(onClick = { onBreathShapeChange(shape) }) { Text(shape.name) }
                    else TextButton(onClick = { onBreathShapeChange(shape) }) { Text(shape.name) }
                }
                MobilePlacementTool.ORNAMENT_TRILL -> listOf(
                    OrnamentKind.TRILL,
                    OrnamentKind.MORDENT,
                    OrnamentKind.INVERTED_MORDENT,
                    OrnamentKind.TURN,
                    OrnamentKind.INVERTED_TURN,
                ).forEach { kind ->
                    if (kind == ornamentKind) Button(onClick = { onOrnamentKindChange(kind) }) { Text(kind.name) }
                    else TextButton(onClick = { onOrnamentKindChange(kind) }) { Text(kind.name) }
                }
                else -> Unit
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("取消") }
            Button(enabled = previewReady, onClick = onCommit) { Text("完成") }
        }
    }
}

@Composable
private fun SpanPlacementBar(
    selected: MobilePlacementTool,
    previewReady: Boolean,
    hairpinStyle: HairpinStyle,
    ornamentKind: OrnamentKind,
    onHairpinStyleChange: (HairpinStyle) -> Unit,
    onOrnamentKindChange: (OrnamentKind) -> Unit,
    onSelect: (MobilePlacementTool) -> Unit,
    onCancel: () -> Unit,
    onCommit: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(
                MobilePlacementTool.HAIRPIN_CRESCENDO to "渐强",
                MobilePlacementTool.HAIRPIN_DIMINUENDO to "渐弱",
                MobilePlacementTool.OCTAVE_UP to "8va",
                MobilePlacementTool.OCTAVE_DOWN to "8vb",
                MobilePlacementTool.GRADUAL_RITARDANDO to "渐慢",
                MobilePlacementTool.GRADUAL_ACCELERANDO to "渐快",
                MobilePlacementTool.ORNAMENT_TRILL_SPAN to "区间 ${ornamentKind.name}",
            ).forEach { (tool, label) ->
                if (selected == tool) Button(onClick = { onSelect(tool) }) { Text(label) }
                else TextButton(onClick = { onSelect(tool) }) { Text(label) }
            }
        }
        if (selected == MobilePlacementTool.HAIRPIN_CRESCENDO ||
            selected == MobilePlacementTool.HAIRPIN_DIMINUENDO
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HairpinStyle.entries.forEach { style ->
                    val label = if (style == HairpinStyle.WEDGE) "楔形" else "文字虚线"
                    if (style == hairpinStyle) Button(onClick = { onHairpinStyleChange(style) }) { Text(label) }
                    else TextButton(onClick = { onHairpinStyleChange(style) }) { Text(label) }
                }
            }
        } else if (selected == MobilePlacementTool.ORNAMENT_TRILL_SPAN) {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                listOf(OrnamentKind.TRILL, OrnamentKind.MORDENT, OrnamentKind.TURN).forEach { kind ->
                    if (kind == ornamentKind) Button(onClick = { onOrnamentKindChange(kind) }) { Text(kind.name) }
                    else TextButton(onClick = { onOrnamentKindChange(kind) }) { Text(kind.name) }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (previewReady) "拖动方块，或点选方块后再点目标位置" else "点按起点，或从起点向右拖动",
                style = MaterialTheme.typography.labelMedium,
            )
            Row {
                TextButton(onClick = onCancel) { Text("取消") }
                Button(enabled = previewReady, onClick = onCommit) { Text("完成") }
            }
        }
    }
}

@Composable
private fun ToolGroupBar(
    controller: MobileScoreEditorController,
    active: ScoreToolGroup,
    activeCommandId: String?,
) {
    HorizontalDivider()
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ToolGroupButton("音符", ScoreToolGroup.NOTES, active, activeCommandId, ScoreInteractionCatalog.ENTRY_NOTE, controller)
        ToolGroupButton("力度", ScoreToolGroup.POINT_SYMBOLS, active, activeCommandId, ScoreInteractionCatalog.POINT_SYMBOL, controller)
        ToolGroupButton("区间", ScoreToolGroup.SPANS, active, activeCommandId, ScoreInteractionCatalog.SPAN_SYMBOL, controller)
        ToolGroupButton("选择", ScoreToolGroup.SELECTION, active, activeCommandId, ScoreInteractionCatalog.NAVIGATION, controller)
        ToolGroupButton("属性", ScoreToolGroup.SELECTION, active, activeCommandId, ScoreInteractionCatalog.PROPERTY, controller)
        ToolGroupButton("微调", ScoreToolGroup.ENGRAVING, active, activeCommandId, ScoreInteractionCatalog.HANDLE, controller)
        ToolGroupButton("结构", ScoreToolGroup.STRUCTURE, active, activeCommandId, ScoreInteractionCatalog.STRUCTURE, controller)
        Spacer(Modifier.width(2.dp))
    }
}

@Composable
private fun SelectionTransformBar(
    appendSelection: Boolean,
    onToggleAppend: () -> Unit,
    controller: MobileScoreEditorController,
) {
    val state by controller.state.collectAsState()
    val selectedCount = state.frame.selection.count { it is ScoreSelectionTarget.Event }
    val selectedSlurs = state.frame.selection.count { it is ScoreSelectionTarget.Slur }
    val selectedAttachments = state.frame.selection.count { it is ScoreSelectionTarget.Attachment }
    var tupletCount by rememberSaveable { mutableIntStateOf(3) }
    var targetVoice by rememberSaveable { mutableIntStateOf(2) }
    var restPosition by rememberSaveable { mutableIntStateOf(2) }
    var groupDraft by remember(state.frame.selection) { mutableStateOf<MobileGroupDraftKind?>(null) }
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(8.dp),
    ) {
        Text(
            if (selectedCount == 0) "点按谱面中的音符进行选择" else "已选择 $selectedCount 个音符",
            style = MaterialTheme.typography.labelLarge,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (appendSelection) {
                Button(onClick = onToggleAppend) { Text("追加选择：开") }
            } else {
                TextButton(onClick = onToggleAppend) { Text("追加选择") }
            }
            Button(enabled = selectedCount > 0, onClick = { controller.deleteSelection() }) {
                Text("删除")
            }
            TextButton(enabled = selectedCount > 0, onClick = { controller.copySelection() }) { Text("复制") }
            TextButton(enabled = selectedCount > 0, onClick = { controller.copySelection(cut = true) }) { Text("剪切") }
            TextButton(enabled = state.frame.canPaste, onClick = { controller.pasteAtEntryCursor() }) { Text("粘贴") }
            TextButton(enabled = selectedCount > 0, onClick = { controller.transposeSelection(-1) }) {
                Text("降半音")
            }
            TextButton(enabled = selectedCount > 0, onClick = { controller.transposeSelection(1) }) {
                Text("升半音")
            }
            Text("时值", style = MaterialTheme.typography.labelMedium)
            listOf(
                Duration.EIGHTH to "八分",
                Duration.QUARTER to "四分",
                Duration.HALF to "二分",
                Duration.WHOLE to "全音符",
            ).forEach { (duration, label) ->
                TextButton(
                    enabled = selectedCount > 0,
                    onClick = { controller.setSelectionDuration(duration) },
                ) { Text(label) }
            }
            TextButton(
                enabled = selectedCount > 0,
                onClick = { controller.setSelection(emptyList()) },
            ) { Text("取消选择") }
            TextButton(
                enabled = selectedCount >= 2,
                onClick = {
                    groupDraft = MobileGroupDraftKind.TUPLET
                    controller.activate(ScoreInteractionCatalog.GROUP_EVENTS)
                    controller.target(listOf(ScoreInteractionAnchor.Selection(state.frame.selection)), preview = true)
                },
            ) { Text("预览 $tupletCount 连音组") }
            TextButton(onClick = { tupletCount = (tupletCount - 1).coerceAtLeast(2) }) { Text("连音数−") }
            TextButton(onClick = { tupletCount = (tupletCount + 1).coerceAtMost(12) }) { Text("连音数+") }
            TextButton(
                enabled = selectedCount == 2,
                onClick = { controller.addSlurFromSelection() },
            ) { Text("添加连线") }
            TextButton(enabled = selectedSlurs > 0, onClick = { controller.deleteSelectedSlurs() }) {
                Text("删除连线")
            }
            TextButton(enabled = selectedAttachments > 0, onClick = { controller.deleteSelectedExpressions() }) {
                Text("删除表情")
            }
            TextButton(enabled = selectedCount > 0, onClick = { controller.setSelectionAccidental(Accidental.SHARP) }) {
                Text("升号")
            }
            TextButton(enabled = selectedCount > 0, onClick = { controller.setSelectionAccidental(Accidental.FLAT) }) {
                Text("降号")
            }
            TextButton(enabled = selectedCount > 0, onClick = { controller.setSelectionAccidental(Accidental.NATURAL) }) {
                Text("还原号")
            }
            TextButton(enabled = selectedCount > 0, onClick = { controller.setSelectionAccidental(null) }) {
                Text("自动变音")
            }
            TextButton(enabled = selectedCount > 0, onClick = { controller.setSelectionTies(true) }) {
                Text("添加延音线")
            }
            TextButton(enabled = selectedCount > 0, onClick = { controller.setSelectionTies(false) }) {
                Text("移除延音线")
            }
            TextButton(enabled = selectedCount > 0, onClick = { controller.setSelectionBeaming(BeamingInfo.NONE) }) {
                Text("断开符杠")
            }
            TextButton(enabled = selectedCount > 0, onClick = { controller.setSelectionBeaming(null) }) {
                Text("自动符杠")
            }
            listOf(
                BeamingInfo(false, true) to "符杠起点",
                BeamingInfo(true, true) to "符杠中间",
                BeamingInfo(true, false) to "符杠终点",
            ).forEach { (beaming, label) ->
                TextButton(enabled = selectedCount > 0, onClick = { controller.setSelectionBeaming(beaming) }) {
                    Text(label)
                }
            }
            listOf(Articulation.STACCATO, Articulation.TENUTO, Articulation.ACCENT, Articulation.MARCATO)
                .forEach { articulation ->
                    TextButton(
                        enabled = selectedCount > 0,
                        onClick = { controller.toggleSelectionArticulation(articulation) },
                    ) { Text("切换 ${articulation.name}") }
                }
            ArpeggioType.entries.forEach { type ->
                TextButton(
                    enabled = selectedCount > 0,
                    onClick = { controller.setSelectionArpeggio(type) },
                ) { Text("琶音 ${type.name}") }
            }
            TextButton(enabled = selectedCount > 0, onClick = { controller.setSelectionArpeggio(null) }) {
                Text("移除琶音")
            }
            TextButton(onClick = { targetVoice = (targetVoice - 1).coerceAtLeast(1) }) { Text("声部−") }
            TextButton(onClick = { targetVoice = (targetVoice + 1).coerceAtMost(8) }) { Text("声部+") }
            TextButton(enabled = selectedCount > 0, onClick = { controller.moveSelectionToVoice(targetVoice) }) {
                Text("移到声部 $targetVoice")
            }
            TextButton(onClick = { restPosition-- }) { Text("休止位置−") }
            TextButton(onClick = { restPosition++ }) { Text("休止位置+") }
            TextButton(enabled = selectedCount > 0, onClick = { controller.moveSelectedRests(restPosition) }) {
                Text("休止位置 $restPosition")
            }
            TextButton(enabled = selectedCount > 0, onClick = { controller.moveSelectedRests(null) }) {
                Text("休止符自动位置")
            }
            TextButton(
                enabled = selectedCount > 0,
                onClick = {
                    groupDraft = MobileGroupDraftKind.SMALL_NOTES
                    controller.activate(ScoreInteractionCatalog.GROUP_SMALL_NOTES)
                    controller.target(listOf(ScoreInteractionAnchor.Selection(state.frame.selection)), preview = true)
                },
            ) { Text("预览小音符组") }
            TextButton(
                enabled = selectedCount > 0,
                onClick = {
                    groupDraft = MobileGroupDraftKind.GRACE_PRINCIPAL
                    controller.activate(ScoreInteractionCatalog.GROUP_EVENTS)
                    controller.target(listOf(ScoreInteractionAnchor.Selection(state.frame.selection)), preview = true)
                },
            ) { Text("预览倚音·借主音") }
            TextButton(
                enabled = selectedCount > 0,
                onClick = {
                    groupDraft = MobileGroupDraftKind.GRACE_PREVIOUS
                    controller.activate(ScoreInteractionCatalog.GROUP_EVENTS)
                    controller.target(listOf(ScoreInteractionAnchor.Selection(state.frame.selection)), preview = true)
                },
            ) { Text("预览倚音·借前音") }
        }
        groupDraft?.let { draft ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (draft) {
                        MobileGroupDraftKind.TUPLET -> "$selectedCount 个事件 → $tupletCount 连音组"
                        MobileGroupDraftKind.SMALL_NOTES -> "$selectedCount 个事件 → 小音符区域"
                        MobileGroupDraftKind.GRACE_PRINCIPAL -> "$selectedCount 个事件 → 倚音（借主音）"
                        MobileGroupDraftKind.GRACE_PREVIOUS -> "$selectedCount 个事件 → 倚音（借前音）"
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
                Row {
                    TextButton(onClick = { groupDraft = null; controller.cancelRun() }) { Text("取消") }
                    Button(onClick = {
                        when (draft) {
                            MobileGroupDraftKind.TUPLET -> controller.applyTupletToSelection(tupletCount)
                            MobileGroupDraftKind.SMALL_NOTES -> controller.createSmallNoteRegionFromSelection()
                            MobileGroupDraftKind.GRACE_PRINCIPAL -> controller.setGraceGroupForSelection()
                            MobileGroupDraftKind.GRACE_PREVIOUS -> controller.setGraceGroupForSelection(
                                stealFrom = GraceTimeSource.PREVIOUS,
                            )
                        }
                        groupDraft = null
                    }) { Text("完成") }
                }
            }
        }
    }
}

@Composable
private fun StructureBoundaryBar(
    boundary: Int?,
    selected: ScoreSelectionTarget?,
    firstStaffId: com.mecon.api.primitive.TrackId?,
    maxMeasure: Int,
    controller: MobileScoreEditorController,
    onCancel: () -> Unit,
) {
    var insertCount by rememberSaveable { mutableIntStateOf(1) }
    var repeatCount by rememberSaveable { mutableIntStateOf(2) }
    var hiddenSpan by rememberSaveable { mutableIntStateOf(1) }
    var navigationMark by rememberSaveable { mutableStateOf(NavigationMark.SEGNO) }
    var draftLabel by remember(boundary, selected) { mutableStateOf<String?>(null) }
    var draftCommit by remember(boundary, selected) { mutableStateOf<(() -> Unit)?>(null) }
    fun stage(label: String, commit: () -> Unit) {
        draftLabel = label
        draftCommit = commit
        controller.target(
            boundary?.let { listOf(ScoreInteractionAnchor.Boundary(it)) }.orEmpty(),
            preview = true,
        )
    }
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(8.dp),
    ) {
        Text(
            boundary?.let { "已选择第 $it 小节后的边界" } ?: "点按紫色目标所在的小节线",
            style = MaterialTheme.typography.labelLarge,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            TextButton(onClick = { insertCount = (insertCount - 1).coerceAtLeast(1) }) { Text("插入数−") }
            TextButton(onClick = { insertCount = (insertCount + 1).coerceAtMost(32) }) { Text("插入数+") }
            Button(enabled = boundary != null, onClick = {
                boundary?.let { at ->
                    val count = insertCount
                    stage("在边界 $at 后插入 $count 小节") {
                        controller.insertMeasures(at, count)
                    }
                }
            }) {
                Text("在后方插入 $insertCount 小节")
            }
            TextButton(
                enabled = boundary != null && boundary > 0,
                onClick = { boundary?.takeIf { it > 0 }?.let { measure -> stage("删除第 $measure 小节") {
                    controller.deleteMeasures(setOf(measure))
                } } },
            ) { Text("删除前一小节") }
            TextButton(
                enabled = boundary != null && boundary > 0,
                onClick = { boundary?.let { at -> stage("边界 $at → 单小节线") {
                    controller.setBarline(at, BarlineType.SINGLE)
                } } },
            ) { Text("单小节线") }
            TextButton(
                enabled = boundary != null && boundary > 0,
                onClick = { boundary?.let { at -> stage("边界 $at → 双小节线") {
                    controller.setBarline(at, BarlineType.DOUBLE)
                } } },
            ) { Text("双小节线") }
            TextButton(
                enabled = boundary != null && boundary > 0,
                onClick = { boundary?.let { at -> stage("边界 $at → 右反复线") {
                    controller.setBarline(at, BarlineType.REPEAT_RIGHT)
                } } },
            ) { Text("右反复") }
            TextButton(onClick = { repeatCount = (repeatCount - 1).coerceAtLeast(2) }) { Text("反复数−") }
            TextButton(onClick = { repeatCount = (repeatCount + 1).coerceAtMost(32) }) { Text("反复数+") }
            TextButton(
                enabled = boundary != null && boundary > 0,
                onClick = { boundary?.let { at ->
                    val count = repeatCount
                    stage("边界 $at 反复 $count 次") { controller.setBarlineRepeatCount(at, count) }
                } },
            ) { Text("反复 $repeatCount 次") }
            TextButton(
                enabled = boundary != null,
                onClick = { boundary?.let { at -> stage("切换边界 $at 的 1/2 房子") {
                    controller.toggleVoltaPair(at)
                } } },
            ) { Text("切换 1/2 房子") }
            TextButton(
                enabled = selected is ScoreSelectionTarget.VoltaEnding,
                onClick = { stage("删除选中的房子") { controller.deleteSelectedVolta() } },
            ) { Text("删除房子") }
            NavigationMark.entries.forEach { mark ->
                if (mark == navigationMark) Button(onClick = { navigationMark = mark }) { Text(mark.name) }
                else TextButton(onClick = { navigationMark = mark }) { Text(mark.name) }
            }
            TextButton(
                enabled = boundary != null && boundary > 0,
                onClick = { boundary?.let { at ->
                    val mark = navigationMark
                    stage("边界 $at 切换 ${mark.name}") { controller.toggleNavigationMark(at, mark) }
                } },
            ) { Text("切换 ${navigationMark.name}") }
            TextButton(
                enabled = selected is ScoreSelectionTarget.NavigationMark,
                onClick = { stage("删除选中的导航记号") { controller.deleteSelectedNavigationMark() } },
            ) { Text("删除导航记号") }
            TextButton(
                enabled = boundary != null && boundary < maxMeasure,
                onClick = { boundary?.let { at -> stage("第 ${at + 1} 小节前换行") {
                    controller.setLayoutBreak(at + 1, LayoutBreakKind.SYSTEM)
                } } },
            ) { Text("换行") }
            TextButton(
                enabled = boundary != null && boundary < maxMeasure,
                onClick = { boundary?.let { at -> stage("第 ${at + 1} 小节前换页") {
                    controller.setLayoutBreak(at + 1, LayoutBreakKind.PAGE)
                } } },
            ) { Text("换页") }
            TextButton(
                enabled = boundary != null && boundary < maxMeasure,
                onClick = { boundary?.let { at -> stage("清除第 ${at + 1} 小节前换行/页") {
                    controller.setLayoutBreak(at + 1, null)
                } } },
            ) { Text("清除换行/页") }
            TextButton(
                enabled = boundary != null && firstStaffId != null,
                onClick = {
                    if (boundary != null && firstStaffId != null) {
                        val start = (boundary + 1).coerceIn(1, maxMeasure)
                        val end = (start + hiddenSpan - 1).coerceAtMost(maxMeasure)
                        stage("隐藏第 $start–$end 小节谱表") {
                            controller.setStaffVisibility(setOf(firstStaffId), start, end, hidden = true)
                        }
                    }
                },
            ) { Text("隐藏后续 $hiddenSpan 小节谱表") }
            TextButton(onClick = { hiddenSpan = (hiddenSpan - 1).coerceAtLeast(1) }) { Text("隐藏范围−") }
            TextButton(onClick = { hiddenSpan = (hiddenSpan + 1).coerceAtMost(maxMeasure) }) { Text("隐藏范围+") }
            TextButton(
                enabled = selected is ScoreSelectionTarget.StaffVisibility,
                onClick = {
                    (selected as? ScoreSelectionTarget.StaffVisibility)?.let {
                        val target = it
                        stage("恢复第 ${target.startMeasure}–${target.endMeasure} 小节谱表") {
                            controller.setStaffVisibility(
                                setOf(target.staffTrackId),
                                target.startMeasure,
                                target.endMeasure,
                                hidden = false,
                            )
                        }
                    }
                },
            ) { Text("恢复谱表") }
            TextButton(enabled = boundary != null || draftLabel != null, onClick = {
                draftLabel = null
                draftCommit = null
                onCancel()
            }) { Text("取消") }
        }
        draftLabel?.let { label ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("结构预览：$label", style = MaterialTheme.typography.labelMedium)
                Row {
                    TextButton(onClick = {
                        draftLabel = null
                        draftCommit = null
                        controller.cancelRun()
                    }) { Text("取消") }
                    Button(onClick = {
                        val commit = draftCommit
                        draftLabel = null
                        draftCommit = null
                        commit?.invoke()
                    }) { Text("完成") }
                }
            }
        }
    }
}

@Composable
private fun GeometryHandleBar(
    selected: ScoreSelectionTarget?,
    renderFrame: AndroidScoreRenderFrame,
    controller: MobileScoreEditorController,
) {
    val geometry = renderFrame.result?.capturedGeometry
    val geometrySupported = selected is ScoreSelectionTarget.Slur ||
        selected is ScoreSelectionTarget.Tie ||
        selected is ScoreSelectionTarget.Beam ||
        selected is ScoreSelectionTarget.Articulation ||
        (selected is ScoreSelectionTarget.Attachment && geometry?.attachments?.containsKey(selected.attachmentId) == true)
    val supported = geometrySupported || selected is ScoreSelectionTarget.VoltaEnding ||
        selected is ScoreSelectionTarget.NavigationMark
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(8.dp),
    ) {
        Text(
            when {
                selected == null -> "点按连线、符杠、演奏法或附加符号"
                selected is ScoreSelectionTarget.VoltaEnding -> "已选房子；按小节移动可编辑端点"
                selected is ScoreSelectionTarget.NavigationMark -> "已选导航记号；可跨边界或按 0.5 谱表间距微调"
                supported -> "已选 ${selected::class.simpleName}；每次微调 0.5 个谱表间距"
                else -> "该对象没有可用的语义手柄"
            },
            style = MaterialTheme.typography.labelLarge,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            when (selected) {
                is ScoreSelectionTarget.VoltaEnding -> {
                    Button(onClick = { controller.resizeSelectedVolta(-1) }) { Text("端点前移一小节") }
                    Button(onClick = { controller.resizeSelectedVolta(1) }) { Text("端点后移一小节") }
                }
                is ScoreSelectionTarget.NavigationMark -> {
                    Button(onClick = { controller.nudgeSelectedNavigation(boundaryDelta = -1) }) {
                        Text("上一边界")
                    }
                    Button(onClick = { controller.nudgeSelectedNavigation(boundaryDelta = 1) }) {
                        Text("下一边界")
                    }
                    listOf("左移" to (-0.5f to 0f), "右移" to (0.5f to 0f),
                        "上移" to (0f to -0.5f), "下移" to (0f to 0.5f)).forEach { (label, delta) ->
                        Button(onClick = { controller.nudgeSelectedNavigation(dx = delta.first, dy = delta.second) }) {
                            Text(label)
                        }
                    }
                }
                else -> listOf(
                    Triple("左移", -0.5f, 0f),
                    Triple("右移", 0.5f, 0f),
                    Triple("上移", 0f, -0.5f),
                    Triple("下移", 0f, 0.5f),
                ).forEach { (label, dx, dy) ->
                    Button(
                        enabled = geometrySupported && geometry != null,
                        onClick = { controller.nudgeSelectedGeometry(geometry, dx, dy) },
                    ) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun PropertyDraftBar(
    frame: ScoreEditingFrame,
    controller: MobileScoreEditorController,
) {
    val target = frame.selection.singleOrNull() as? ScoreSelectionTarget.Attachment
    val attachment = target?.let { selected ->
        frame.computedScore.staffAttachments.firstOrNull { it.id == selected.attachmentId }
    }
    val fermata = target?.let { selected ->
        frame.runtimeScore.globalTrack.events
            .filterIsInstance<StorageFermata>()
            .firstOrNull { it.id == selected.attachmentId }
    }
    val propertyKind = when {
        attachment is ComputedOrnamentMark -> MobilePropertyKind.ORNAMENT
        attachment is ComputedTempoKeyframe -> MobilePropertyKind.TEMPO
        attachment is ComputedBreathMark || fermata != null -> MobilePropertyKind.PERFORMANCE
        target == null -> MobilePropertyKind.NONE
        else -> MobilePropertyKind.UNSUPPORTED
    }
    val initial = when (propertyKind) {
        MobilePropertyKind.ORNAMENT -> (attachment as ComputedOrnamentMark).oscillations.toString()
        MobilePropertyKind.TEMPO -> (attachment as ComputedTempoKeyframe).effectiveBpm.toString()
        MobilePropertyKind.PERFORMANCE -> when (attachment) {
            is ComputedBreathMark -> attachment.pause.toString()
            else -> fermata?.extension?.toString() ?: "1"
        }
        else -> ""
    }
    var draft by remember(target?.attachmentId) { mutableStateOf(initial) }
    val valid = when (propertyKind) {
        MobilePropertyKind.ORNAMENT -> draft.toIntOrNull()?.let { it > 0 } == true
        MobilePropertyKind.TEMPO -> draft.toFloatOrNull()?.let { it > 0f } == true
        MobilePropertyKind.PERFORMANCE -> parsePositiveFraction(draft) != null
        else -> false
    }
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(8.dp),
    ) {
        Text(
            when (propertyKind) {
                MobilePropertyKind.ORNAMENT -> "装饰音振荡次数"
                MobilePropertyKind.TEMPO -> "速度 BPM"
                MobilePropertyKind.PERFORMANCE -> "延长/停顿比例"
                MobilePropertyKind.NONE -> "先点按一个可编辑属性的附加符号"
                MobilePropertyKind.UNSUPPORTED -> "该附加符号暂无属性表单"
            },
            style = MaterialTheme.typography.labelLarge,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                enabled = propertyKind != MobilePropertyKind.NONE && propertyKind != MobilePropertyKind.UNSUPPORTED,
                singleLine = true,
                modifier = Modifier.width(150.dp),
                label = { Text("草稿") },
            )
            Button(
                enabled = valid,
                onClick = {
                    when (propertyKind) {
                        MobilePropertyKind.ORNAMENT -> draft.toIntOrNull()?.let(controller::updateSelectedOrnament)
                        MobilePropertyKind.TEMPO -> draft.toFloatOrNull()?.let(controller::updateSelectedTempo)
                        MobilePropertyKind.PERFORMANCE -> parsePositiveFraction(draft)
                            ?.let(controller::updateSelectedPerformance)
                        else -> Unit
                    }
                },
            ) { Text("确认") }
            TextButton(onClick = { controller.cancelRun() }) { Text("取消") }
        }
    }
}

private enum class MobilePropertyKind { NONE, ORNAMENT, TEMPO, PERFORMANCE, UNSUPPORTED }

private fun parsePositiveFraction(value: String): Fraction? {
    val parts = value.trim().split('/')
    val fraction = when (parts.size) {
        1 -> parts[0].toIntOrNull()?.let { Fraction(it, 1) }
        2 -> {
            val numerator = parts[0].toIntOrNull()
            val denominator = parts[1].toIntOrNull()
            if (numerator != null && denominator != null && denominator > 0) {
                Fraction(numerator, denominator)
            } else null
        }
        else -> null
    }
    return fraction?.takeIf { it.isPositive }
}

@Composable
private fun ToolGroupButton(
    label: String,
    group: ScoreToolGroup,
    active: ScoreToolGroup,
    activeCommandId: String?,
    commandId: String,
    controller: MobileScoreEditorController,
) {
    if (active == group && activeCommandId == commandId) {
        Button(onClick = { controller.activate(commandId) }) { Text(label) }
    } else {
        TextButton(onClick = { controller.activate(commandId) }) { Text(label) }
    }
}

private val MobileScoreActivity.label: String
    get() = when (this) {
        MobileScoreActivity.RECORD -> "记录"
        MobileScoreActivity.EDIT -> "编辑"
        MobileScoreActivity.ANALYZE -> "分析"
        MobileScoreActivity.LISTEN -> "试听"
    }

private val MobileScoreActivity.shortLabel: String
    get() = when (this) {
        MobileScoreActivity.RECORD -> "●"
        MobileScoreActivity.EDIT -> "✎"
        MobileScoreActivity.ANALYZE -> "◇"
        MobileScoreActivity.LISTEN -> "▶"
    }
