package com.mecon.desktop.ui.views

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.mecon.api.computed.ComputedScore
import com.mecon.api.interaction.EventSection
import com.mecon.api.interaction.VoiceEventSection
import com.mecon.api.interaction.VoiceNoteSection
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Pitch
import com.mecon.api.runtime.RuntimeScore
import com.mecon.audio.converter.ScoreToMidiConverter
import com.mecon.audio.engine.PlaybackState
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.desktop.ui.views.pianoroll.*
import com.mecon.components.keyboard.PianoRollKeyboard
import com.mecon.components.keyboard.PianoRollKeyboardOrientation
import com.mecon.plugins.chord.ChordSymbolDisplaySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

const val TICKS_PER_QUARTER = 1024

internal data class PianoRollFrame(
    val notes: List<PianoRollNote>,
    val barBeatPositions: BarBeatPositions,
    val chordSpans: List<PianoRollChordSpan>,
)

data class PianoRollGridPosition(
    val ticks: Long,
    val midi: Int,
)

enum class PianoRollNoteEdge { START, END }

data class PianoRollInteractionConfig(
    val onGridTap: ((PianoRollGridPosition) -> Unit)? = null,
    val onGridRangeDrag: ((PianoRollGridPosition, PianoRollGridPosition) -> Unit)? = null,
    val onNotePitchDrag: ((PianoRollNote, Int) -> Unit)? = null,
    val onNoteRangeDrag: ((PianoRollNote, PianoRollNoteEdge, Long) -> Unit)? = null,
    val snapPitch: (PianoRollNote, Int) -> Int = { _, midi -> midi },
    val snapTicks: (Long) -> Long = { it },
    val gridStepTicks: Long = 0L,
    val onSelectionChange: ((Set<EventSection>) -> Unit)? = null,
)

/** Optional non-linear horizontal mapping supplied by an aligned notation frame. */
data class PianoRollTimeProjection(
    val xAtTicks: (Float) -> Float,
    val ticksAtX: (Float) -> Long,
    val onHorizontalDrag: (Float) -> Unit = {},
)

data class PianoRollStyleConfig(
    val noteColor: (PianoRollNote) -> Color = { note ->
        if (note.isGrace) MeconColors.Emerald.copy(alpha = 0.5f)
        else MeconColors.Emerald.copy(alpha = 0.8f)
    },
    val showChordLabels: Boolean = true,
)

private sealed interface PianoRollGesture {
    data class Pan(val start: Offset) : PianoRollGesture
    data class Draw(
        val start: PianoRollGridPosition,
        val current: PianoRollGridPosition,
    ) : PianoRollGesture
    data class Pitch(val note: PianoRollNote, val midi: Int) : PianoRollGesture
    data class Range(
        val note: PianoRollNote,
        val edge: PianoRollNoteEdge,
        val ticks: Long,
    ) : PianoRollGesture
}

internal data class PianoRollSelection(
    val wholeEvents: Set<EventId>,
    val noteheads: Set<Pair<EventId, Int>>,
) {
    fun contains(note: PianoRollNote): Boolean =
        note.voiceEventIds.any { it in wholeEvents } ||
            note.voiceEventIds.any { it to note.midi in noteheads }
}

@Composable
fun PianoRollView(
    runtimeScore: RuntimeScore?,
    computedScore: ComputedScore? = null,
    selection: Set<EventSection> = emptySet(),
    showChordOverlay: Boolean = false,
    showScaleDegrees: Boolean = false,
    analysisRefreshKey: Any? = Unit,
    currentPositionTicks: Long,
    playbackState: PlaybackState = PlaybackState.IDLE,
    interaction: PianoRollInteractionConfig? = null,
    style: PianoRollStyleConfig = PianoRollStyleConfig(),
    chordSpansOverride: List<PianoRollChordSpan>? = null,
    timeProjection: PianoRollTimeProjection? = null,
    modifier: Modifier = Modifier
) {
    if (runtimeScore == null) {
        Box(modifier = modifier.fillMaxSize().background(MeconColors.SurfaceDark))
        return
    }

    val pianoRollSelection = remember(selection) {
        val wholeEvents = linkedSetOf<EventId>()
        val noteheads = linkedSetOf<Pair<EventId, Int>>()
        selection.forEach { section ->
            when (section) {
                is VoiceEventSection -> wholeEvents += section.event.id
                is VoiceNoteSection -> {
                    val midi = section.event.pitchData.getOrNull(section.pitchIndex)?.midiPitch
                    if (midi != null) noteheads += section.event.id to midi
                }
                else -> Unit
            }
        }
        PianoRollSelection(wholeEvents, noteheads)
    }
    val currentComputed by rememberReferentialUpdatedState(computedScore)
    val currentSelectionChange by rememberUpdatedState(interaction?.onSelectionChange)
    val onNoteTap: ((PianoRollNote) -> Unit)? =
        if (interaction?.onSelectionChange == null) null
        else { note ->
            val sections = note.voiceEventIds.mapNotNullTo(linkedSetOf()) { eventId ->
                val event = currentComputed?.getComputedEvent(eventId) ?: return@mapNotNullTo null
                val pitchIndex = event.pitchData.indexOfFirst { it.midiPitch == note.midi }
                if (pitchIndex >= 0) VoiceNoteSection(event, pitchIndex)
                else VoiceEventSection(event)
            }
            if (sections.isNotEmpty()) currentSelectionChange?.invoke(sections)
        }
    // MIDI conversion is whole-score work and can take about a second on orchestral scores. Keep it
    // entirely off the UI thread; produceState retains the previous frame while the replacement is
    // built. A short cancellable debounce coalesces rapid edit bursts before expensive work begins.
    val runtimeIdentityKey = rememberIdentityKey(runtimeScore)
    val computedIdentityKey = rememberIdentityKey(computedScore)
    val chordSymbolStyle = ChordSymbolDisplaySettings.style
    val pianoRollFrame by produceState<PianoRollFrame?>(
        initialValue = null,
        runtimeIdentityKey,
        computedIdentityKey,
        showChordOverlay,
        showScaleDegrees,
        chordSymbolStyle,
        analysisRefreshKey,
        chordSpansOverride,
    ) {
        val snapshot = runtimeScore
        val computedSnapshot = computedScore
        val chordOverlaySnapshot = showChordOverlay
        val degreeSnapshot = showScaleDegrees
        val symbolStyleSnapshot = chordSymbolStyle
        val chordSpansSnapshot = chordSpansOverride
        delay(50)
        value = withContext(Dispatchers.Default) {
            val startedAt = System.nanoTime()
            val midiScore = ScoreToMidiConverter.convert(
                snapshot,
                ScoreToMidiConverter.ConversionConfig(ticksPerQuarter = TICKS_PER_QUARTER),
            )
            val notes = addPianoRollDegreeLabels(
                notes = buildPianoRollNotes(snapshot, midiScore),
                score = computedSnapshot,
                enabled = degreeSnapshot,
            )
            PianoRollFrame(
                notes = notes,
                barBeatPositions = buildBarBeatPositions(snapshot, TICKS_PER_QUARTER),
                chordSpans = chordSpansSnapshot ?: buildPianoRollChordSpans(
                        runtime = snapshot,
                        computed = computedSnapshot,
                        timelineEndTicks = midiScore.getTotalTicks(),
                        ticksPerQuarter = TICKS_PER_QUARTER,
                        enabled = chordOverlaySnapshot,
                        symbolStyle = symbolStyleSnapshot,
                    ),
            ).also { frame ->
                com.mecon.renderer.debug.PerfLog.log("pianoroll") {
                    "buildFrame=${(System.nanoTime() - startedAt) / 1_000_000}ms " +
                        "notes=${frame.notes.size} chords=${frame.chordSpans.size}"
                }
            }
        }
    }
    PianoRollSurface(
        frame = pianoRollFrame,
        selection = pianoRollSelection,
        currentPositionTicks = currentPositionTicks,
        playbackState = playbackState,
        interaction = interaction,
        onNoteTap = onNoteTap,
        style = style,
        timeProjection = timeProjection,
        modifier = modifier,
    )
}

@Composable
internal fun PianoRollSurface(
    frame: PianoRollFrame?,
    selection: PianoRollSelection,
    currentPositionTicks: Long,
    playbackState: PlaybackState,
    interaction: PianoRollInteractionConfig? = null,
    onNoteTap: ((PianoRollNote) -> Unit)? = null,
    style: PianoRollStyleConfig = PianoRollStyleConfig(),
    timeProjection: PianoRollTimeProjection? = null,
    modifier: Modifier = Modifier,
) {
    val state = remember { PianoRollState() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val blackPitchClasses = remember(state.keyboardLayout, state.useRelativePitch) {
        (0..11).filterTo(linkedSetOf()) { midi ->
            val pitch = Pitch.fromMidi(midi, !state.useRelativePitch)
            state.keyboardLayout.isBlackKey(pitch)
        }
    }
    val notes = frame?.notes.orEmpty()
    val barBeatPositions = frame?.barBeatPositions ?: BarBeatPositions.EMPTY
    val chordSpans = frame?.chordSpans.orEmpty()
    val tickToPx = state.scaleX / TICKS_PER_QUARTER.toFloat()
    var boxSize by remember { mutableStateOf(Size.Zero) }
    var followPlayback by remember { mutableStateOf(true) }
    var gesture by remember { mutableStateOf<PianoRollGesture?>(null) }
    var ctrlHeld by remember { mutableStateOf(false) }
    val displayedPositionTicks by animateFloatAsState(
        targetValue = currentPositionTicks.toFloat(),
        animationSpec = if (playbackState == PlaybackState.PLAYING) {
            tween(durationMillis = PLAYBACK_POSITION_UPDATE_MS, easing = LinearEasing)
        } else {
            snap()
        },
        label = "piano-roll-playback-position",
    )

    LaunchedEffect(playbackState) {
        if (playbackState == PlaybackState.PLAYING) followPlayback = true
    }

    val autoFollowActive = playbackState == PlaybackState.PLAYING && followPlayback && boxSize != Size.Zero
    val timelineOffset = if (timeProjection != null) {
        0f
    } else if (autoFollowActive) {
        val viewportExtent = if (state.orientation == PianoRollOrientation.HORIZONTAL) boxSize.width else boxSize.height
        pianoRollFollowOffset(displayedPositionTicks * tickToPx, viewportExtent, state.keyboardBasis)
    } else if (state.orientation == PianoRollOrientation.HORIZONTAL) {
        state.offsetX
    } else {
        state.offsetY
    }
    val currentTimelineOffset by rememberUpdatedState(timelineOffset)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MeconColors.SurfaceDark)
            .clipToBounds()
            .onSizeChanged { boxSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        ctrlHeld = event.keyboardModifiers.isCtrlPressed
                    }
                }
            }
            .then(
                if (interaction == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(interaction, state.orientation, tickToPx, timeProjection) {
                        detectTapGestures { position ->
                            val note = state.noteAt(
                                position = position,
                                viewport = Size(size.width.toFloat(), size.height.toFloat()),
                                notes = notes,
                                timelineOffset = currentTimelineOffset,
                                tickToPx = tickToPx,
                                timeProjection = timeProjection,
                            )
                            if (note != null && onNoteTap != null) {
                                onNoteTap(note)
                                return@detectTapGestures
                            }
                            val hit = state.gridPosition(
                                position = position,
                                viewport = Size(size.width.toFloat(), size.height.toFloat()),
                                timelineOffset = currentTimelineOffset,
                                tickToPx = tickToPx,
                                timeProjection = timeProjection,
                            )
                            if (hit != null) interaction.onGridTap?.invoke(hit)
                        }
                    }
                }
            )
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val scrollDelta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (scrollDelta != 0f) {
                                val zoomFactor = if (scrollDelta < 0) 1.05f else 1f / 1.05f
                                val centroid = event.changes.first().position
                                // Vertical zoom for now, mapped to pitch row height
                                state.onZoom(zoomFactor, centroid, isXAxis = false, isYAxis = true)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            }
            .pointerInput(playbackState, timeProjection, interaction, notes) {
                detectDragGestures(
                    onDragStart = { position ->
                        val viewport = Size(size.width.toFloat(), size.height.toFloat())
                        val note = state.noteAt(
                            position = position,
                            viewport = viewport,
                            notes = notes,
                            timelineOffset = currentTimelineOffset,
                            tickToPx = tickToPx,
                            timeProjection = timeProjection,
                        )
                        val noteRect = note?.let {
                            state.noteRect(
                                note = it,
                                viewport = viewport,
                                timelineOffset = currentTimelineOffset,
                                tickToPx = tickToPx,
                                timeProjection = timeProjection,
                            )
                        }
                        val edge = noteRect?.let { rect ->
                            if (state.orientation != PianoRollOrientation.HORIZONTAL) null
                            else {
                                val hitWidth = minOf(NOTE_EDGE_HIT_PX, rect.width / 3f)
                                when {
                                    kotlin.math.abs(position.x - rect.left) <= hitWidth ->
                                        PianoRollNoteEdge.START
                                    kotlin.math.abs(position.x - rect.right) <= hitWidth ->
                                        PianoRollNoteEdge.END
                                    else -> null
                                }
                            }
                        }
                        gesture = when {
                            ctrlHeld -> PianoRollGesture.Pan(position)
                            note != null && edge != null && interaction?.onNoteRangeDrag != null ->
                                PianoRollGesture.Range(
                                    note = note,
                                    edge = edge,
                                    ticks = if (edge == PianoRollNoteEdge.START) {
                                        note.onsetTicks
                                    } else {
                                        note.onsetTicks + note.durationTicks
                                    },
                                )
                            note != null && interaction?.onNotePitchDrag != null ->
                                PianoRollGesture.Pitch(note, note.midi)
                            interaction?.onGridRangeDrag != null -> {
                                state.gridPosition(
                                    position = position,
                                    viewport = viewport,
                                    timelineOffset = currentTimelineOffset,
                                    tickToPx = tickToPx,
                                    timeProjection = timeProjection,
                                )?.let {
                                    val snapped = it.copy(ticks = interaction.snapTicks(it.ticks))
                                    PianoRollGesture.Draw(snapped, snapped)
                                }
                                    ?: PianoRollGesture.Pan(position)
                            }
                            else -> PianoRollGesture.Pan(position)
                        }
                        if (state.orientation == PianoRollOrientation.HORIZONTAL) {
                            state.offsetX = currentTimelineOffset
                        } else {
                            state.offsetY = currentTimelineOffset
                        }
                        followPlayback = false
                    },
                    onDragEnd = {
                        when (val completed = gesture) {
                            is PianoRollGesture.Draw ->
                                interaction?.onGridRangeDrag?.invoke(
                                    completed.start,
                                    completed.current,
                                )
                            is PianoRollGesture.Pitch ->
                                interaction?.onNotePitchDrag?.invoke(
                                    completed.note,
                                    completed.midi,
                                )
                            is PianoRollGesture.Range ->
                                interaction?.onNoteRangeDrag?.invoke(
                                    completed.note,
                                    completed.edge,
                                    completed.ticks,
                                )
                            else -> Unit
                        }
                        gesture = null
                        followPlayback = true
                    },
                    onDragCancel = {
                        gesture = null
                        followPlayback = true
                    },
                ) { change, dragAmount ->
                    val viewport = Size(size.width.toFloat(), size.height.toFloat())
                    when (val active = gesture) {
                        is PianoRollGesture.Draw -> {
                            state.gridPosition(
                                position = change.position,
                                viewport = viewport,
                                timelineOffset = currentTimelineOffset,
                                tickToPx = tickToPx,
                                timeProjection = timeProjection,
                            )?.let {
                                gesture = active.copy(
                                    current = it.copy(
                                        ticks = interaction?.snapTicks?.invoke(it.ticks) ?: it.ticks,
                                        midi = active.start.midi,
                                    )
                                )
                            }
                        }
                        is PianoRollGesture.Pitch -> {
                            state.gridPosition(
                                position = change.position,
                                viewport = viewport,
                                timelineOffset = currentTimelineOffset,
                                tickToPx = tickToPx,
                                timeProjection = timeProjection,
                            )?.let { hit ->
                                gesture = active.copy(
                                    midi = interaction?.snapPitch
                                        ?.invoke(active.note, hit.midi)
                                        ?.coerceIn(0, 127)
                                        ?: hit.midi,
                                )
                            }
                        }
                        is PianoRollGesture.Range -> {
                            state.gridPosition(
                                position = change.position,
                                viewport = viewport,
                                timelineOffset = currentTimelineOffset,
                                tickToPx = tickToPx,
                                timeProjection = timeProjection,
                            )?.let {
                                gesture = active.copy(
                                    ticks = interaction?.snapTicks?.invoke(it.ticks) ?: it.ticks,
                                )
                            }
                        }
                        is PianoRollGesture.Pan, null -> {
                            if (
                                timeProjection != null &&
                                state.orientation == PianoRollOrientation.HORIZONTAL
                            ) {
                                timeProjection.onHorizontalDrag(dragAmount.x)
                                state.onDrag(Offset(0f, dragAmount.y), boxSize)
                            } else {
                                state.onDrag(dragAmount, boxSize)
                            }
                        }
                    }
                    change.consume()
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (state.orientation == PianoRollOrientation.HORIZONTAL) {
                drawHorizontalRoll(
                    state, notes, chordSpans, selection, barBeatPositions,
                    displayedPositionTicks, timelineOffset, tickToPx, textMeasurer, style,
                    timeProjection, gesture, interaction?.gridStepTicks ?: 0L,
                )
            } else {
                drawVerticalRoll(
                    state, notes, chordSpans, selection, barBeatPositions,
                    displayedPositionTicks, timelineOffset, tickToPx, textMeasurer, style
                )
            }
        }
        if (state.orientation == PianoRollOrientation.HORIZONTAL) {
            PianoRollKeyboard(
                semitoneSizePx = state.scaleY,
                pitchOffsetPx = state.offsetY,
                blackPitchClasses = blackPitchClasses,
                orientation = PianoRollKeyboardOrientation.VERTICAL,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(with(density) { state.keyboardBasis.toDp() })
                    .fillMaxHeight(),
            )
        } else {
            PianoRollKeyboard(
                semitoneSizePx = state.scaleY,
                pitchOffsetPx = state.offsetX,
                blackPitchClasses = blackPitchClasses,
                orientation = PianoRollKeyboardOrientation.HORIZONTAL,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(with(density) { state.keyboardBasis.toDp() }),
            )
        }
    }
}

internal fun PianoRollState.noteAt(
    position: Offset,
    viewport: Size,
    notes: List<PianoRollNote>,
    timelineOffset: Float,
    tickToPx: Float,
    timeProjection: PianoRollTimeProjection? = null,
): PianoRollNote? =
    notes.asReversed().firstOrNull { note ->
        position in noteRect(note, viewport, timelineOffset, tickToPx, timeProjection)
    }

private fun PianoRollState.noteRect(
    note: PianoRollNote,
    viewport: Size,
    timelineOffset: Float,
    tickToPx: Float,
    timeProjection: PianoRollTimeProjection? = null,
): Rect = if (orientation == PianoRollOrientation.HORIZONTAL) {
    val x = timeProjection?.xAtTicks(note.onsetTicks.toFloat())
        ?: (keyboardBasis + note.onsetTicks * tickToPx - timelineOffset)
    val right = timeProjection?.xAtTicks((note.onsetTicks + note.durationTicks).toFloat())
        ?: (x + note.durationTicks * tickToPx)
    val y = viewport.height - (note.midi * scaleY - offsetY) - scaleY
    Rect(x, y, right, y + scaleY)
} else {
    val x = note.midi * scaleY - offsetX
    val y = keyboardBasis + note.onsetTicks * tickToPx - timelineOffset
    Rect(x, y, x + scaleY, y + note.durationTicks * tickToPx)
}

private fun PianoRollState.gridPosition(
    position: Offset,
    viewport: Size,
    timelineOffset: Float,
    tickToPx: Float,
    timeProjection: PianoRollTimeProjection? = null,
): PianoRollGridPosition? {
    if (tickToPx <= 0f) return null
    val (ticks, midi) = if (orientation == PianoRollOrientation.HORIZONTAL) {
        if (position.x < keyboardBasis) return null
        val ticks = timeProjection?.ticksAtX(position.x)?.toFloat()
            ?: ((position.x - keyboardBasis + timelineOffset) / tickToPx)
        val midi = ((viewport.height - position.y + offsetY - 0.001f) / scaleY).toInt()
        ticks to midi
    } else {
        if (position.y < keyboardBasis) return null
        val ticks = (position.y - keyboardBasis + timelineOffset) / tickToPx
        val midi = ((position.x + offsetX) / scaleY).toInt()
        ticks to midi
    }
    if (midi !in 0..127) return null
    return PianoRollGridPosition(ticks = ticks.toLong().coerceAtLeast(0L), midi = midi)
}

private fun DrawScope.drawHorizontalRoll(
    state: PianoRollState,
    notes: List<PianoRollNote>,
    chordSpans: List<PianoRollChordSpan>,
    selection: PianoRollSelection,
    barBeatPositions: BarBeatPositions,
    currentPositionTicks: Float,
    timeOffsetX: Float,
    tickToPx: Float,
    textMeasurer: TextMeasurer,
    style: PianoRollStyleConfig,
    timeProjection: PianoRollTimeProjection?,
    gesture: PianoRollGesture?,
    gridStepTicks: Long,
) {
    val kbWidth = state.keyboardBasis

    // Draw bar lines (solid) and beat lines (dashed) behind everything.
    val beatDash = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
    for (ticks in barBeatPositions.barTicks) {
        val x = timeProjection?.xAtTicks(ticks.toFloat())
            ?: (kbWidth + (ticks * tickToPx) - timeOffsetX)
        if (x < kbWidth || x > size.width) continue
        drawLine(
            color = MeconColors.TextPrimary.copy(alpha = 0.35f),
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 1f
        )
    }
    for (ticks in barBeatPositions.beatTicks) {
        val x = timeProjection?.xAtTicks(ticks.toFloat())
            ?: (kbWidth + (ticks * tickToPx) - timeOffsetX)
        if (x < kbWidth || x > size.width) continue
        drawLine(
            color = MeconColors.TextPrimary.copy(alpha = 0.2f),
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 1f,
            pathEffect = beatDash
        )
    }

    drawHorizontalChordToneBackgrounds(
        state, chordSpans, timeOffsetX, tickToPx, timeProjection
    )

    // 1. Draw notes.
    // Grace notes use the same rectangle math — the MIDI converter has
    // already collapsed/shortened/delayed their windows for us.
    for (note in notes) {
        val preview = gesture?.takeIf {
            (it as? PianoRollGesture.Pitch)?.note === note ||
                (it as? PianoRollGesture.Range)?.note === note
        }
        val previewOnset = when (preview) {
            is PianoRollGesture.Range ->
                if (preview.edge == PianoRollNoteEdge.START) preview.ticks else note.onsetTicks
            else -> note.onsetTicks
        }
        val previewEnd = when (preview) {
            is PianoRollGesture.Range ->
                if (preview.edge == PianoRollNoteEdge.END) {
                    preview.ticks
                } else {
                    note.onsetTicks + note.durationTicks
                }
            else -> note.onsetTicks + note.durationTicks
        }
        val previewMidi = (preview as? PianoRollGesture.Pitch)?.midi ?: note.midi
        val x = timeProjection?.xAtTicks(previewOnset.toFloat())
            ?: (kbWidth + (previewOnset * tickToPx) - timeOffsetX)
        val right = timeProjection?.xAtTicks(previewEnd.toFloat())
            ?: (kbWidth + previewEnd * tickToPx - timeOffsetX)
        val w = right - x

        if (x + w < kbWidth || x > size.width) continue

        val y = size.height - ((previewMidi * state.scaleY) - state.offsetY) - state.scaleY
        if (y + state.scaleY < 0 || y > size.height) continue

        val selected = selection.contains(note)
        val color = when {
            selected -> MeconColors.PrimaryLight
            else -> style.noteColor(note)
        }
        val inset = if (note.isGrace) state.scaleY * 0.25f else 1f
        val noteTopLeft = Offset(x, y + inset)
        val noteSize = Size(
            (w - 1f).coerceAtLeast(0f),
            (state.scaleY - inset * 2f).coerceAtLeast(1f),
        )
        drawRect(
            color = color,
            topLeft = noteTopLeft,
            size = noteSize,
        )
        if (selected) {
            drawRect(Color.White.copy(alpha = 0.9f), noteTopLeft, noteSize, style = Stroke(1.5f))
        }
        note.degreeLabel?.let { label ->
            val textOrigin = visibleTextOrigin(
                content = Rect(
                    left = noteTopLeft.x,
                    top = noteTopLeft.y,
                    right = noteTopLeft.x + noteSize.width,
                    bottom = noteTopLeft.y + noteSize.height,
                ),
                viewport = Rect(kbWidth, 0f, size.width, size.height),
                padding = Offset(2f, 0f),
            )
            if (textOrigin != null) {
                drawText(
                    textMeasurer = textMeasurer,
                    text = label,
                    topLeft = textOrigin,
                    style = TextStyle(color = Color.White, fontSize = 8.sp),
                )
            }
        }
    }

    (gesture as? PianoRollGesture.Draw)?.let { draw ->
        val leftTicks = minOf(draw.start.ticks, draw.current.ticks)
        val rightTicks = maxOf(draw.start.ticks, draw.current.ticks) +
            gridStepTicks.coerceAtLeast(1L)
        val left = timeProjection?.xAtTicks(leftTicks.toFloat())
            ?: (kbWidth + leftTicks * tickToPx - timeOffsetX)
        val right = timeProjection?.xAtTicks(rightTicks.toFloat())
            ?: (kbWidth + rightTicks * tickToPx - timeOffsetX)
        val y = size.height -
            (draw.start.midi * state.scaleY - state.offsetY) -
            state.scaleY
        drawRect(
            color = MeconColors.PrimaryLight.copy(alpha = 0.55f),
            topLeft = Offset(left, y + 1f),
            size = Size((right - left).coerceAtLeast(2f), (state.scaleY - 2f).coerceAtLeast(1f)),
        )
    }
    
    // 5. Draw Playhead ON TOP of EVERYTHING
    val playheadX = timeProjection?.xAtTicks(currentPositionTicks)
        ?: (kbWidth + (currentPositionTicks * tickToPx) - timeOffsetX)
    if (playheadX in kbWidth..size.width) {
        drawLine(
            color = MeconColors.Playhead,
            start = Offset(playheadX, 0f),
            end = Offset(playheadX, size.height),
            strokeWidth = 2f
        )
    }

    // 6. Draw grid rows extending from keys
    val startMidi = ((state.offsetY) / state.scaleY).toInt()
    val endMidi = ((state.offsetY + size.height) / state.scaleY).toInt() + 1
    
    for (midi in startMidi..endMidi) {
        if (midi !in 0..127) continue
        val y = size.height - ((midi * state.scaleY) - state.offsetY) - state.scaleY
        
        val pitch = Pitch.fromMidi(midi, !state.useRelativePitch)
        val isBlack = state.keyboardLayout.isBlackKey(pitch)
        
        // Draw grid row background for black keys
        if (isBlack) {
            drawRect(
                color = MeconColors.TextPrimary.copy(alpha = 0.05f),
                topLeft = Offset(kbWidth, y),
                size = Size(size.width - kbWidth, state.scaleY)
            )
        }

        // Draw horizontal grid line
        drawLine(
            color = MeconColors.TextPrimary.copy(alpha = 0.1f),
            start = Offset(kbWidth, y + state.scaleY),
            end = Offset(size.width, y + state.scaleY),
            strokeWidth = 1f
        )
    }

    if (style.showChordLabels) {
        drawHorizontalChordLane(
            state, chordSpans, timeOffsetX, tickToPx, textMeasurer, timeProjection
        )
    }
}

private fun DrawScope.drawVerticalRoll(
    state: PianoRollState,
    notes: List<PianoRollNote>,
    chordSpans: List<PianoRollChordSpan>,
    selection: PianoRollSelection,
    barBeatPositions: BarBeatPositions,
    currentPositionTicks: Float,
    timeOffsetY: Float,
    tickToPx: Float,
    textMeasurer: TextMeasurer,
    style: PianoRollStyleConfig,
) {
    val kbHeight = state.keyboardBasis

    // Vertical: Pitch is on X axis, Time is on Y axis (moving downwards)

    // Draw bar lines (solid) and beat lines (dashed) behind everything.
    val beatDash = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
    for (ticks in barBeatPositions.barTicks) {
        val y = kbHeight + (ticks * tickToPx) - timeOffsetY
        if (y < kbHeight || y > size.height) continue
        drawLine(
            color = MeconColors.TextPrimary.copy(alpha = 0.35f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f
        )
    }
    for (ticks in barBeatPositions.beatTicks) {
        val y = kbHeight + (ticks * tickToPx) - timeOffsetY
        if (y < kbHeight || y > size.height) continue
        drawLine(
            color = MeconColors.TextPrimary.copy(alpha = 0.2f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f,
            pathEffect = beatDash
        )
    }

    drawVerticalChordToneBackgrounds(state, chordSpans, timeOffsetY, tickToPx)

    // 1. Draw Notes
    for (note in notes) {
        val y = kbHeight + (note.onsetTicks * tickToPx) - timeOffsetY
        val h = note.durationTicks * tickToPx

        if (y + h < kbHeight || y > size.height) continue

        val x = (note.midi * state.scaleY) - state.offsetX
        if (x + state.scaleY < 0 || x > size.width) continue

        val selected = selection.contains(note)
        val color = when {
            selected -> MeconColors.PrimaryLight
            else -> style.noteColor(note)
        }
        val inset = if (note.isGrace) state.scaleY * 0.25f else 1f
        val noteTopLeft = Offset(x + inset, y)
        val noteSize = Size(
            (state.scaleY - inset * 2f).coerceAtLeast(1f),
            (h - 1f).coerceAtLeast(0f),
        )
        drawRect(
            color = color,
            topLeft = noteTopLeft,
            size = noteSize,
        )
        if (selected) {
            drawRect(Color.White.copy(alpha = 0.9f), noteTopLeft, noteSize, style = Stroke(1.5f))
        }
        note.degreeLabel?.let { label ->
            val textOrigin = visibleTextOrigin(
                content = Rect(
                    left = noteTopLeft.x,
                    top = noteTopLeft.y,
                    right = noteTopLeft.x + noteSize.width,
                    bottom = noteTopLeft.y + noteSize.height,
                ),
                viewport = Rect(0f, kbHeight, size.width, size.height),
                padding = Offset(1f, 1f),
            )
            if (textOrigin != null) {
                drawText(
                    textMeasurer = textMeasurer,
                    text = label,
                    topLeft = textOrigin,
                    style = TextStyle(color = Color.White, fontSize = 8.sp),
                )
            }
        }
    }
    
    // 5. Draw Playhead ON TOP of EVERYTHING
    val playheadY = kbHeight + (currentPositionTicks * tickToPx) - timeOffsetY
    if (playheadY in kbHeight..size.height) {
        drawLine(
            color = MeconColors.Playhead,
            start = Offset(0f, playheadY),
            end = Offset(size.width, playheadY),
            strokeWidth = 2f
        )
    }

    // 6. Draw Grid Columns
    val startMidi = ((state.offsetX) / state.scaleY).toInt()
    val endMidi = ((state.offsetX + size.width) / state.scaleY).toInt() + 1
    
    for (midi in startMidi..endMidi) {
        if (midi !in 0..127) continue
        val x = (midi * state.scaleY) - state.offsetX
        
        val pitch = Pitch.fromMidi(midi, !state.useRelativePitch)
        val isBlack = state.keyboardLayout.isBlackKey(pitch)
        
        if (isBlack) {
            drawRect(
                color = MeconColors.TextPrimary.copy(alpha = 0.05f),
                topLeft = Offset(x, kbHeight),
                size = Size(state.scaleY, size.height - kbHeight)
            )
        }

        drawLine(
            color = MeconColors.TextPrimary.copy(alpha = 0.1f),
            start = Offset(x + state.scaleY, kbHeight),
            end = Offset(x + state.scaleY, size.height),
            strokeWidth = 1f
        )
    }

    if (style.showChordLabels) {
        drawVerticalChordLabels(state, chordSpans, timeOffsetY, tickToPx, textMeasurer)
    }
}

private fun DrawScope.drawHorizontalChordToneBackgrounds(
    state: PianoRollState,
    chordSpans: List<PianoRollChordSpan>,
    timeOffsetX: Float,
    tickToPx: Float,
    timeProjection: PianoRollTimeProjection?,
) {
    val startMidi = (state.offsetY / state.scaleY).toInt().coerceIn(0, 127)
    val endMidi = ((state.offsetY + size.height) / state.scaleY).toInt().coerceIn(0, 127)
    chordSpans.forEach { span ->
        val left = timeProjection?.xAtTicks(span.onsetTicks.toFloat())
            ?: (state.keyboardBasis + span.onsetTicks * tickToPx - timeOffsetX)
        val right = timeProjection?.xAtTicks(span.endTicks.toFloat())
            ?: (state.keyboardBasis + span.endTicks * tickToPx - timeOffsetX)
        if (right < state.keyboardBasis || left > size.width) return@forEach
        for (midi in startMidi..endMidi) {
            val memberIndex = span.pitchClasses.indexOf(midi.mod(12))
            if (memberIndex < 0) continue
            val y = size.height - (midi * state.scaleY - state.offsetY) - state.scaleY
            drawRect(
                color = chordToneColor(memberIndex),
                topLeft = Offset(left.coerceAtLeast(state.keyboardBasis), y),
                size = Size(
                    (right.coerceAtMost(size.width) - left.coerceAtLeast(state.keyboardBasis))
                        .coerceAtLeast(0f),
                    state.scaleY,
                ),
            )
        }
    }
}

private fun DrawScope.drawVerticalChordToneBackgrounds(
    state: PianoRollState,
    chordSpans: List<PianoRollChordSpan>,
    timeOffsetY: Float,
    tickToPx: Float,
) {
    val startMidi = (state.offsetX / state.scaleY).toInt().coerceIn(0, 127)
    val endMidi = ((state.offsetX + size.width) / state.scaleY).toInt().coerceIn(0, 127)
    chordSpans.forEach { span ->
        val top = state.keyboardBasis + span.onsetTicks * tickToPx - timeOffsetY
        val bottom = state.keyboardBasis + span.endTicks * tickToPx - timeOffsetY
        if (bottom < state.keyboardBasis || top > size.height) return@forEach
        for (midi in startMidi..endMidi) {
            val memberIndex = span.pitchClasses.indexOf(midi.mod(12))
            if (memberIndex < 0) continue
            val x = midi * state.scaleY - state.offsetX
            drawRect(
                color = chordToneColor(memberIndex),
                topLeft = Offset(x, top.coerceAtLeast(state.keyboardBasis)),
                size = Size(
                    state.scaleY,
                    (bottom.coerceAtMost(size.height) - top.coerceAtLeast(state.keyboardBasis))
                        .coerceAtLeast(0f),
                ),
            )
        }
    }
}

private fun DrawScope.drawHorizontalChordLane(
    state: PianoRollState,
    chordSpans: List<PianoRollChordSpan>,
    timeOffsetX: Float,
    tickToPx: Float,
    textMeasurer: TextMeasurer,
    timeProjection: PianoRollTimeProjection?,
) {
    if (chordSpans.isEmpty()) return
    drawRect(
        color = MeconColors.Surface.copy(alpha = 0.96f),
        topLeft = Offset(state.keyboardBasis, 0f),
        size = Size((size.width - state.keyboardBasis).coerceAtLeast(0f), CHORD_LANE_HEIGHT),
    )
    drawLine(
        color = MeconColors.BorderLight,
        start = Offset(state.keyboardBasis, CHORD_LANE_HEIGHT),
        end = Offset(size.width, CHORD_LANE_HEIGHT),
    )
    chordSpans.forEach { span ->
        val left = timeProjection?.xAtTicks(span.onsetTicks.toFloat())
            ?: (state.keyboardBasis + span.onsetTicks * tickToPx - timeOffsetX)
        val right = timeProjection?.xAtTicks(span.endTicks.toFloat())
            ?: (state.keyboardBasis + span.endTicks * tickToPx - timeOffsetX)
        if (right < state.keyboardBasis || left > size.width) return@forEach
        val visibleLeft = left.coerceIn(state.keyboardBasis, size.width)
        drawLine(
            color = MeconColors.BorderLight,
            start = Offset(visibleLeft, 0f),
            end = Offset(visibleLeft, CHORD_LANE_HEIGHT),
        )
        visibleTextOrigin(
            content = Rect(left, 0f, right, CHORD_LANE_HEIGHT),
            viewport = Rect(state.keyboardBasis, 0f, size.width, size.height),
            padding = Offset(4f, 3f),
            minVisibleHeight = 8f,
        )?.let { origin ->
            drawText(
                textMeasurer = textMeasurer,
                text = span.symbol,
                topLeft = origin,
                style = TextStyle(color = MeconColors.TextPrimary, fontSize = 11.sp),
            )
        }
    }
}

private fun DrawScope.drawVerticalChordLabels(
    state: PianoRollState,
    chordSpans: List<PianoRollChordSpan>,
    timeOffsetY: Float,
    tickToPx: Float,
    textMeasurer: TextMeasurer,
) {
    chordSpans.forEach { span ->
        val y = state.keyboardBasis + span.onsetTicks * tickToPx - timeOffsetY
        visibleTextOrigin(
            content = Rect(0f, y, size.width, y + CHORD_LANE_HEIGHT),
            viewport = Rect(0f, state.keyboardBasis, size.width, size.height),
            padding = Offset(4f, 2f),
            minVisibleHeight = 8f,
        )?.let { origin ->
            drawText(
                textMeasurer = textMeasurer,
                text = span.symbol,
                topLeft = origin,
                style = TextStyle(color = MeconColors.TextPrimary, fontSize = 10.sp),
            )
        }
    }
}

internal fun visibleTextOrigin(
    content: Rect,
    viewport: Rect,
    padding: Offset,
    minVisibleWidth: Float = 10f,
    minVisibleHeight: Float = 9f,
): Offset? {
    val visibleLeft = maxOf(content.left, viewport.left)
    val visibleTop = maxOf(content.top, viewport.top)
    val visibleRight = minOf(content.right, viewport.right)
    val visibleBottom = minOf(content.bottom, viewport.bottom)
    if (
        visibleRight - visibleLeft < minVisibleWidth ||
        visibleBottom - visibleTop < minVisibleHeight
    ) {
        return null
    }
    val origin = Offset(visibleLeft + padding.x, visibleTop + padding.y)
    return origin.takeIf {
        it.x >= viewport.left && it.x < viewport.right &&
            it.y >= viewport.top && it.y < viewport.bottom
    }
}

private fun chordToneColor(memberIndex: Int): Color = CHORD_TONE_COLORS[
    memberIndex.coerceIn(0, CHORD_TONE_COLORS.lastIndex)
]

internal data class BarBeatPositions(
    val barTicks: List<Long>,
    val beatTicks: List<Long>
) {
    companion object {
        val EMPTY = BarBeatPositions(emptyList(), emptyList())
    }
}

private const val PLAYBACK_POSITION_UPDATE_MS = 50
private const val CHORD_LANE_HEIGHT = 24f
private const val NOTE_EDGE_HIT_PX = 7f
private val CHORD_TONE_COLORS = listOf(
    Color(0x3DF59E0B),
    Color(0x333B82F6),
    Color(0x338B5CF6),
    Color(0x33EC4899),
    Color(0x3322C55E),
)

private fun buildBarBeatPositions(score: RuntimeScore, ticksPerQuarter: Int): BarBeatPositions {
    val wholeNoteTicks = 4L * ticksPerQuarter
    val barTicks = mutableListOf<Long>()
    val beatTicks = mutableListOf<Long>()
    var currentTick = 0L
    for (entry in score.measures) {
        val ts = entry.value.timeSignature
        val beatUnitTicks = wholeNoteTicks / ts.denominator
        barTicks.add(currentTick)
        for (beat in 1 until ts.numerator) {
            beatTicks.add(currentTick + beat * beatUnitTicks)
        }
        currentTick += beatUnitTicks * ts.numerator
    }
    return BarBeatPositions(barTicks, beatTicks)
}
