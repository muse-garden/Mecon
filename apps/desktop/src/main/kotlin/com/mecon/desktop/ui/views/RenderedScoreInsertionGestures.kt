package com.mecon.desktop.ui.views

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.mecon.api.computed.ComputedScore
import com.mecon.api.interaction.BarlineSection
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.core.engine.edit.ClefEditEngine
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.desktop.ui.components.EditTool
import com.mecon.desktop.ui.components.NoteToolState
import com.mecon.desktop.ui.components.NoteEntryKind
import com.mecon.desktop.ui.components.PauseMarkKind
import com.mecon.api.primitive.EventId
import com.mecon.api.interaction.EventSection
import com.mecon.features.scoreediting.ScoreInteractionCatalog
import com.mecon.features.scoreediting.ScoreTargetingKind
import com.mecon.api.storage.events.OrnamentAnchor
import com.mecon.api.storage.events.OrnamentKind
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.render.RenderEngine
import com.mecon.renderer.render.RenderPage
import com.mecon.renderer.render.RenderResult
import com.mecon.renderer.render.edit.ExpressionSpanKind
import com.mecon.renderer.render.edit.GhostClef
import com.mecon.renderer.render.edit.GhostExpressionSpan
import com.mecon.renderer.render.edit.GhostKeySignature
import com.mecon.renderer.render.edit.GhostNote
import com.mecon.renderer.render.edit.GhostPointSymbol
import com.mecon.renderer.render.edit.GhostTimeSignature
import com.mecon.renderer.render.edit.PointSymbolKind

internal data class InsertionGestureEnvironment(
    val resultIdentityKey: Long,
    val result: RenderResult?,
    val computed: ComputedScore?,
    val runtime: RuntimeScore?,
    val engine: RenderEngine?,
    val paginated: Boolean,
    val pages: List<RenderPage>,
    val pageSlots: List<Offset>,
    val offset: () -> Offset,
    val scale: () -> Float,
    val density: Float,
    val panEnabled: Boolean,
    val updateOffset: (Offset) -> Unit,
    val updateFollowPlayback: (Boolean) -> Unit,
)

internal data class InsertionGestureActions(
    val insertNote: (NoteEditEngine.Insertion, (EventSection) -> Unit) -> Unit,
    val insertClef: (ClefEditEngine.Target) -> Unit,
    val insertTimeSignature: (Int) -> Unit,
    val insertKeySignature: (TimeCode) -> Unit,
    val insertBarline: (BarlineSection) -> Unit,
    val insertRepeatStructure: (BarlineSection) -> Unit,
    val insertDynamic: (TrackId, TimeCode) -> Unit,
    val insertPauseMark: (TrackId, TimeCode) -> Unit,
    val insertExpressionSpan: (TrackId, TimeCode, TimeCode) -> Unit,
    val insertTempo: (TimeCode) -> Unit,
    val insertTempoSpan: (TimeCode, TimeCode) -> Unit,
    val insertOrnament: (TrackId, EventId, OrnamentAnchor, TimeCode?) -> Unit,
    val insertArpeggio: (EventId) -> Unit,
)

internal data class InsertionGesturePreviews(
    val note: (GhostNote?) -> Unit,
    val clef: (GhostClef?) -> Unit,
    val timeSignature: (GhostTimeSignature?) -> Unit,
    val keySignature: (GhostKeySignature?) -> Unit,
    val pointSymbol: (GhostPointSymbol?) -> Unit,
    val expressionSpan: (GhostExpressionSpan?) -> Unit,
    val noteCommitted: (PendingNoteViewportAlignment) -> Unit,
)

internal data class InsertionGestureRequest(
    val environment: InsertionGestureEnvironment,
    val tool: NoteToolState?,
    val actions: InsertionGestureActions,
    val previews: InsertionGesturePreviews,
)

internal fun Modifier.scoreInsertionGestures(request: InsertionGestureRequest): Modifier {
    val environment = request.environment
    val tool = request.tool
    val actions = request.actions
    val previews = request.previews
    val barlineActive = tool?.tool == EditTool.BARLINE
    val repeatStructureActive = tool?.tool == EditTool.REPEAT_STRUCTURE
    val noteActive = tool?.tool == EditTool.NOTE
    val dynamicActive = tool?.tool == EditTool.DYNAMIC
    val pauseActive = tool?.tool == EditTool.PAUSE
    val tempoActive = tool?.tool == EditTool.TEMPO
    val ornamentActive = tool?.tool == EditTool.ORNAMENT
    val arpeggioActive = tool?.tool == EditTool.ARPEGGIO
    val expressionSpanActive = tool?.tool == EditTool.HAIRPIN ||
        tool?.tool == EditTool.OCTAVE ||
        tool?.tool == EditTool.TEMPO_SPAN ||
        tool?.tool == EditTool.ORNAMENT_SPAN
    val clefActive = tool?.tool == EditTool.CLEF
    val timeActive = tool?.tool == EditTool.TIME
    val keyActive = tool?.tool == EditTool.KEY
    val insertionActive = barlineActive || repeatStructureActive || noteActive || dynamicActive ||
        pauseActive || tempoActive || expressionSpanActive || clefActive || timeActive || keyActive

    fun absolutePoint(raw: Offset): AbsolutePoint? {
        val offset = environment.offset()
        val scale = environment.scale()
        val designX = (raw.x - offset.x) / scale / environment.density
        val designY = (raw.y - offset.y) / scale / environment.density
        return if (environment.paginated) {
            designToGlobal(Offset(designX, designY), environment.pages, environment.pageSlots)
        } else {
            AbsolutePoint(Pixels(designX), Pixels(designY))
        }
    }

    return this
        .pointerInput(
            environment.resultIdentityKey,
            insertionActive,
            environment.paginated,
            environment.panEnabled,
        ) {
            if (!insertionActive || !environment.panEnabled) return@pointerInput
            val result = environment.result ?: return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val canPan = canStartInsertionPan(
                    raw = down.position,
                    offset = environment.offset(),
                    scale = environment.scale(),
                    density = environment.density,
                    paginated = environment.paginated,
                    pages = environment.pages,
                    pageSlots = environment.pageSlots,
                    systems = displayedScoreSystems(
                        result,
                        environment.paginated,
                        environment.pages,
                        environment.pageSlots,
                    ),
                )
                var previous = down.position
                var panning = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val delta = change.position - previous
                    previous = change.position
                    if (!canPan || delta == Offset.Zero) continue
                    if (!panning) {
                        panning = true
                        environment.updateFollowPlayback(false)
                        previews.note(null)
                    }
                    environment.updateOffset(environment.offset() + delta)
                    change.consume()
                }
                if (panning) environment.updateFollowPlayback(true)
            }
        }
        .pointerInput(
            environment.resultIdentityKey,
            barlineActive,
            repeatStructureActive,
            environment.paginated,
        ) {
            if (!barlineActive && !repeatStructureActive) return@pointerInput
            val result = environment.result ?: return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: continue
                    if (event.type != PointerEventType.Press) continue
                    val point = absolutePoint(change.position) ?: continue
                    val hit = result.barlineHitAt(point) ?: continue
                    val barline = environment.computed?.barlines?.firstOrNull {
                        it.measureNumber == hit.measureNumber
                    } ?: continue
                    val section = BarlineSection(
                        barline = barline,
                        systemIndex = hit.systemIndex,
                        visualPlacement = hit.placement,
                    )
                    if (barlineActive) actions.insertBarline(section)
                    else actions.insertRepeatStructure(section)
                    change.consume()
                }
            }
        }
        .pointerInput(
            environment.resultIdentityKey,
            noteActive,
            environment.paginated,
            tool?.activeVoiceNumber,
        ) {
            if (!noteActive) {
                previews.note(null)
                return@pointerInput
            }
            val result = environment.result ?: return@pointerInput
            val activeTool = tool ?: return@pointerInput
            val runtime = environment.runtime ?: return@pointerInput
            val engine = environment.engine ?: return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: continue
                    val target = absolutePoint(change.position)?.let {
                        engine.computeGhost(
                            result,
                            runtime,
                            it,
                            activeTool.duration,
                            activeTool.accidental,
                            activeTool.restMode,
                            activeTool.activeVoiceNumber,
                            activeTool.tupletCount,
                            activeTool.noteEntryKind == NoteEntryKind.GRACE,
                        )
                    }?.takeUnless {
                        runtime.staffTracks[it.staffTrackId]?.isHidden(it.onset.measure) == true
                    }
                    when (event.type) {
                        PointerEventType.Move, PointerEventType.Enter -> previews.note(target)
                        PointerEventType.Exit -> previews.note(null)
                        PointerEventType.Press -> if (target != null) {
                            val insertionCursorRaw = change.position
                            val originalSystemFirstMeasure = result
                                .insertionPositionAt(target.onset)
                                ?.systemIndex
                                ?.let(result.spatialIndex::getSystem)
                                ?.measureNumbers
                                ?.firstOrNull()
                                ?: target.onset.measure
                            actions.insertNote(
                                NoteEditEngine.Insertion(
                                    voiceTrackId = target.voiceTrackId,
                                    start = target.onset,
                                    duration = activeTool.duration,
                                    pitch = if (activeTool.restMode) null else target.pitch,
                                    isRest = activeTool.restMode,
                                    trailingTie = activeTool.tieMode,
                                    staffTrackId = target.staffTrackId,
                                    voiceNumber = target.voiceNumber,
                                    tupletCount = activeTool.tupletCount,
                                    beaming = activeTool.insertionBeaming,
                                    articulations = activeTool.articulations.toList(),
                                    grace = if (activeTool.noteEntryKind == NoteEntryKind.GRACE) {
                                        NoteEditEngine.GraceInsertion(
                                            totalDuration = activeTool.graceTotalDuration,
                                            stealFrom = activeTool.graceTimeSource,
                                            noteType = activeTool.graceNoteType,
                                        )
                                    } else null,
                                    smallNoteAppendStartEventId = target.smallNoteAppendStartEventId,
                                ),
                            ) { insertedSection ->
                                previews.noteCommitted(
                                    PendingNoteViewportAlignment(
                                        insertedSection = insertedSection,
                                        cursorRaw = insertionCursorRaw,
                                        originalSystemFirstMeasure = originalSystemFirstMeasure,
                                        originalResultIdentityKey = environment.resultIdentityKey,
                                    ),
                                )
                            }
                            change.consume()
                        }

                        else -> Unit
                    }
                }
            }
        }
        .pointerInput(
            environment.resultIdentityKey,
            pauseActive,
            environment.paginated,
            tool?.activeVoiceNumber,
            tool?.selectedPauseKind,
            tool?.selectedFermataShape,
            tool?.selectedBreathShape,
        ) {
            if (!pauseActive) {
                previews.pointSymbol(null)
                return@pointerInput
            }
            val result = environment.result ?: return@pointerInput
            val runtime = environment.runtime ?: return@pointerInput
            val engine = environment.engine ?: return@pointerInput
            val activeTool = tool ?: return@pointerInput
            val targeting = ScoreInteractionCatalog.targeting(
                when (activeTool.selectedPauseKind) {
                    PauseMarkKind.FERMATA -> ScoreTargetingKind.FERMATA
                    PauseMarkKind.BREATH -> ScoreTargetingKind.BREATH
                },
            )
            val symbolKind = when (activeTool.selectedPauseKind) {
                PauseMarkKind.FERMATA -> PointSymbolKind.Fermata(activeTool.selectedFermataShape)
                PauseMarkKind.BREATH -> PointSymbolKind.Breath(activeTool.selectedBreathShape)
            }
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: continue
                    val target = absolutePoint(change.position)?.let { point ->
                        resolvePauseInsertionTarget(
                            result = result,
                            runtime = runtime,
                            point = point,
                            targeting = targeting,
                            activeVoiceNumber = activeTool.activeVoiceNumber,
                        )
                    }?.takeUnless {
                        runtime.staffTracks[it.staffTrackId]?.isHidden(it.afterTime.measure) == true
                    }
                    val ghost = target?.let {
                        engine.computePointSymbolGhost(
                            result = result,
                            runtime = runtime,
                            staffTrackId = it.staffTrackId,
                            onset = it.ghostOnset,
                            kind = symbolKind,
                            absoluteX = it.absoluteX,
                            systemIndex = it.systemIndex,
                        )
                    }
                    when (event.type) {
                        PointerEventType.Move, PointerEventType.Enter -> previews.pointSymbol(ghost)
                        PointerEventType.Exit -> previews.pointSymbol(null)
                        PointerEventType.Press -> if (target != null && ghost != null) {
                            previews.pointSymbol(ghost)
                            actions.insertPauseMark(target.staffTrackId, target.afterTime)
                            change.consume()
                        }
                        else -> Unit
                    }
                }
            }
        }
        .pointerInput(
            environment.resultIdentityKey,
            dynamicActive,
            tempoActive,
            ornamentActive,
            arpeggioActive,
            expressionSpanActive,
            environment.paginated,
            tool?.selectedHairpinType,
            tool?.selectedHairpinStyle,
            tool?.selectedOctaveShift,
            tool?.selectedOrnamentKind,
        ) {
            if (!dynamicActive && !tempoActive && !ornamentActive &&
                !arpeggioActive && !expressionSpanActive) return@pointerInput
            val result = environment.result ?: return@pointerInput
            val runtime = environment.runtime ?: return@pointerInput
            val engine = environment.engine ?: return@pointerInput
            val activeTool = tool ?: return@pointerInput
            val spanKind = when (activeTool.tool) {
                EditTool.HAIRPIN -> ExpressionSpanKind.Hairpin(
                    activeTool.selectedHairpinType,
                    activeTool.selectedHairpinStyle,
                )
                EditTool.OCTAVE -> ExpressionSpanKind.Octave(activeTool.selectedOctaveShift)
                else -> null
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                val startAbs = absolutePoint(down.position) ?: return@awaitEachGesture
                val baseStart = resolveExpressionAnchor(result, runtime, startAbs) ?: return@awaitEachGesture
                val clickedNote = result.hitTest(startAbs).allSections()
                    .filterIsInstance<com.mecon.api.interaction.VoiceNoteSection>()
                    .firstOrNull()
                    ?.event
                val start = baseStart
                down.consume()
                if (arpeggioActive) {
                    clickedNote?.takeIf { it.pitchData.size >= 2 }?.let { actions.insertArpeggio(it.id) }
                    return@awaitEachGesture
                }
                if (ornamentActive) {
                    val canSitBetween = activeTool.selectedOrnamentKind in setOf(
                        OrnamentKind.TURN,
                        OrnamentKind.INVERTED_TURN,
                        OrnamentKind.TURN_SLASH,
                    )
                    val targetId = clickedNote?.id ?: if (canSitBetween) {
                        runtime.staffTracks[baseStart.first]
                            ?.voiceTracks
                            ?.asSequence()
                            ?.flatMap { it.events.toList().asSequence() }
                            ?.filter { !it.isRest && it.endTime <= baseStart.second }
                            ?.maxByOrNull { it.endTime }
                            ?.id
                    } else null
                    targetId?.let {
                        actions.insertOrnament(
                            baseStart.first,
                            it,
                            if (clickedNote == null) OrnamentAnchor.BETWEEN_NOTES else OrnamentAnchor.ON_NOTE,
                            null,
                        )
                    }
                    return@awaitEachGesture
                }
                if (dynamicActive) {
                    actions.insertDynamic(start.first, start.second)
                    return@awaitEachGesture
                }
                if (tempoActive) {
                    actions.insertTempo(start.second)
                    return@awaitEachGesture
                }
                previews.expressionSpan(null)
                var end = start
                val ornamentSpanSource =
                    if (activeTool.tool == EditTool.ORNAMENT_SPAN) clickedNote else null
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    absolutePoint(change.position)?.let {
                        resolveExpressionAnchor(result, runtime, it)?.let { anchor -> end = anchor }
                    }
                    if (spanKind != null && end.first == start.first) {
                        val first = minOf(start.second, end.second)
                        val last = maxOf(start.second, end.second)
                        previews.expressionSpan(
                            if (first < last) {
                                engine.computeExpressionSpanGhost(
                                    result = result,
                                    runtime = runtime,
                                    staffTrackId = start.first,
                                    start = first,
                                    end = last,
                                    kind = spanKind,
                                )
                            } else null
                        )
                    } else {
                        previews.expressionSpan(null)
                    }
                    change.consume()
                    if (!change.pressed) break
                }
                previews.expressionSpan(null)
                if (end.first == start.first) {
                    val first = minOf(start.second, end.second)
                    val last = maxOf(start.second, end.second)
                    if (first < last) {
                        if (activeTool.tool == EditTool.TEMPO_SPAN) {
                            actions.insertTempoSpan(first, last)
                        } else if (activeTool.tool == EditTool.ORNAMENT_SPAN &&
                            ornamentSpanSource != null &&
                            last > ornamentSpanSource.onset
                        ) {
                            actions.insertOrnament(
                                start.first,
                                ornamentSpanSource.id,
                                OrnamentAnchor.ON_NOTE,
                                last,
                            )
                        } else {
                            actions.insertExpressionSpan(start.first, first, last)
                        }
                    }
                }
            }
        }
        .pointerInput(
            environment.resultIdentityKey,
            clefActive,
            environment.paginated,
            tool?.selectedClef,
        ) {
            if (!clefActive) {
                previews.clef(null)
                return@pointerInput
            }
            val result = environment.result ?: return@pointerInput
            val activeTool = tool ?: return@pointerInput
            val runtime = environment.runtime ?: return@pointerInput
            val engine = environment.engine ?: return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: continue
                    val target = absolutePoint(change.position)?.let {
                        engine.computeClefGhost(result, runtime, it, activeTool.selectedClef)
                    }?.takeUnless {
                        runtime.staffTracks[it.staffTrackId]?.isHidden(it.onset.measure) == true
                    }
                    when (event.type) {
                        PointerEventType.Move, PointerEventType.Enter -> previews.clef(target)
                        PointerEventType.Exit -> previews.clef(null)
                        PointerEventType.Press -> if (target != null) {
                            actions.insertClef(
                                ClefEditEngine.Target(target.staffTrackId, target.onset)
                            )
                            change.consume()
                        }

                        else -> Unit
                    }
                }
            }
        }
        .pointerInput(
            environment.resultIdentityKey,
            timeActive,
            environment.paginated,
            tool?.selectedTimeSignature,
        ) {
            if (!timeActive) {
                previews.timeSignature(null)
                return@pointerInput
            }
            val result = environment.result ?: return@pointerInput
            val activeTool = tool ?: return@pointerInput
            val runtime = environment.runtime ?: return@pointerInput
            val engine = environment.engine ?: return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: continue
                    val target = absolutePoint(change.position)?.let {
                        engine.computeTimeSignatureGhost(
                            result,
                            runtime,
                            it,
                            activeTool.selectedTimeSignature,
                        )
                    }
                    when (event.type) {
                        PointerEventType.Move, PointerEventType.Enter -> previews.timeSignature(target)
                        PointerEventType.Exit -> previews.timeSignature(null)
                        PointerEventType.Press -> if (target != null) {
                            actions.insertTimeSignature(target.measure)
                            change.consume()
                        }

                        else -> Unit
                    }
                }
            }
        }
        .pointerInput(
            environment.resultIdentityKey,
            keyActive,
            environment.paginated,
            tool?.selectedKeySignature,
        ) {
            if (!keyActive) {
                previews.keySignature(null)
                return@pointerInput
            }
            val result = environment.result ?: return@pointerInput
            val activeTool = tool ?: return@pointerInput
            val runtime = environment.runtime ?: return@pointerInput
            val engine = environment.engine ?: return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: continue
                    val target = absolutePoint(change.position)?.let {
                        engine.computeKeySignatureGhost(
                            result,
                            runtime,
                            it,
                            activeTool.selectedKeySignature,
                        )
                    }
                    when (event.type) {
                        PointerEventType.Move, PointerEventType.Enter -> previews.keySignature(target)
                        PointerEventType.Exit -> previews.keySignature(null)
                        PointerEventType.Press -> if (target != null) {
                            actions.insertKeySignature(target.onset)
                            change.consume()
                        }

                        else -> Unit
                    }
                }
            }
        }
}
