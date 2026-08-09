package com.mecon.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*
import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.interaction.*
import com.mecon.api.model.Score
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.events.HairpinStyle
import com.mecon.api.storage.events.HairpinType
import com.mecon.api.storage.events.OctaveShiftType
import com.mecon.api.storage.events.TempoMarkType
import com.mecon.api.storage.tracks.BreathMarkScope
import com.mecon.api.storage.tracks.BreathMarkShape
import com.mecon.api.storage.tracks.FermataShape
import com.mecon.api.storage.events.OrnamentKind
import com.mecon.api.storage.events.OrnamentAnchor
import com.mecon.api.storage.ArpeggioType
import com.mecon.core.engine.edit.ClefEditEngine
import com.mecon.core.engine.edit.ExpressionEditEngine
import com.mecon.core.engine.edit.KeySignatureEditEngine
import com.mecon.core.engine.edit.TempoEditEngine
import com.mecon.desktop.input.SelectionEditor
import com.mecon.desktop.service.ScoreSession
import com.mecon.desktop.ui.components.*
import com.mecon.renderer.interaction.*

internal data class AppLeftToolbarState(
    val selection: () -> Set<EventSection>,
    val setSelection: (Set<EventSection>) -> Unit,
    val selectedAnnotationId: () -> EventId?,
    val setSelectedAnnotationId: (EventId?) -> Unit,
)

internal data class AppLeftToolbarActions(
    val applyExpressionResult: (ExpressionEditEngine.Result?, () -> Unit) -> Unit,
    val selectedExpressionEvents: () -> List<ComputedVoiceEvent>,
    val staffIdForEvent: (ComputedVoiceEvent) -> TrackId?,
    val addHairpin: (HairpinType, HairpinStyle) -> Boolean,
    val addOctave: (OctaveShiftType) -> Boolean,
)

internal data class AppLeftToolbarRequest(
    val session: ScoreSession,
    val noteTool: NoteToolState,
    val toolbarSelection: LeftToolbarSelectionState,
    val selectionEditor: SelectionEditor,
    val state: AppLeftToolbarState,
    val actions: AppLeftToolbarActions,
)

@Composable
internal fun AppLeftToolbar(request: AppLeftToolbarRequest) {
    val session = request.session
    val noteTool = request.noteTool
    val paletteInfo = request.toolbarSelection.notes
    val clefInfo = request.toolbarSelection.clef
    val keyInfo = request.toolbarSelection.key
    val timeInfo = request.toolbarSelection.time
    val barlineInfo = request.toolbarSelection.barline
    val selectionEditor = request.selectionEditor
    fun applyExpressionResult(
        result: ExpressionEditEngine.Result?,
        onAfter: () -> Unit = {},
    ) = request.actions.applyExpressionResult(result, onAfter)
    val selectedExpressionEvents = request.actions.selectedExpressionEvents
    val staffIdForEvent = request.actions.staffIdForEvent
    fun addSelectedSpan(
        hairpin: Pair<HairpinType, HairpinStyle>? = null,
        octave: OctaveShiftType? = null,
    ): Boolean = when {
        hairpin != null -> request.actions.addHairpin(hairpin.first, hairpin.second)
        octave != null -> request.actions.addOctave(octave)
        else -> false
    }
    var eventSelection by com.mecon.desktop.ui.views.MutableLiveValue(
        request.state.selection,
        request.state.setSelection,
    )
    var selectedAnnotationEventId by com.mecon.desktop.ui.views.MutableLiveValue(
        request.state.selectedAnnotationId,
        request.state.setSelectedAnnotationId,
    )
LeftToolbar(
        state = noteTool,
        selection = LeftToolbarSelectionState(
            notes = paletteInfo,
            clef = clefInfo,
            key = keyInfo,
            time = timeInfo,
            barline = barlineInfo,
        ),
        actions = LeftToolbarActions(
            notes = NotePaletteActions(
        editDurationBase = selectionEditor::editDurationBase,
        editDots = selectionEditor::editDots,
        editAccidental = selectionEditor::editAccidental,
        editTie = selectionEditor::editTie,
        addSlur = selectionEditor::addSlur,
        editVoice = selectionEditor::editVoice,
        applyTuplet = selectionEditor::editTuplet,
        editBeaming = selectionEditor::editBeaming,
        groupBeam = selectionEditor::groupBeam,
        editArticulation = selectionEditor::editArticulation,
        convertToSmallNotes = selectionEditor::convertToSmallNotes,
            ),
            scoreElements = ScoreElementPaletteActions(
        pickClef = { clef ->
            val target = (eventSelection.singleOrNull() as? ClefSection)?.clef
            if (target != null) {
                session.applyClefEdit(
                    ClefEditEngine.Target(
                        staffTrackId = target.staffTrackId,
                        onset = target.time,
                    ),
                    clef,
                ) { newSelection ->
                    eventSelection = newSelection
                    selectedAnnotationEventId = null
                }
            } else {
                noteTool.enterClefEntry(clef)
            }
        },
        pickTimeSignature = { ts ->
            val target = (eventSelection.singleOrNull() as? TimeSignatureSection)?.timeSignature
            if (target != null) {
                session.applyTimeSignatureEdit(target.time.measure, ts) { newSelection ->
                    eventSelection = newSelection
                    selectedAnnotationEventId = null
                }
            } else {
                noteTool.enterTimeEntry(ts)
            }
        },
        pickKeySignature = { key ->
            val target = (eventSelection.singleOrNull() as? KeySignatureSection)?.keySignature
            if (target != null) {
                session.applyKeySignatureEdit(
                    KeySignatureEditEngine.Target(target.time),
                    key,
                ) { newSelection ->
                    eventSelection = newSelection
                    selectedAnnotationEventId = null
                }
            } else {
                noteTool.enterKeyEntry(key)
            }
        },
        pickDynamic = { level ->
            var rt = session.runtimeScore
            val points = selectedExpressionEvents().mapNotNull { event ->
                staffIdForEvent(event)?.let { it to event.onset }
            }.distinct()
            var combined: ExpressionEditEngine.Result? = null
            if (rt != null && points.isNotEmpty()) {
                for ((staffId, onset) in points) {
                    val live = rt ?: break
                    val next = ExpressionEditEngine.addDynamic(live, staffId, onset, level) ?: continue
                    rt = next.score
                    combined = if (combined == null) next else next.copy(
                        affectedMeasures = minOf(combined.affectedMeasures.first, next.affectedMeasures.first)..
                            maxOf(combined.affectedMeasures.last, next.affectedMeasures.last),
                        selectedAttachmentIds = combined.selectedAttachmentIds + next.selectedAttachmentIds,
                    )
                }
                applyExpressionResult(combined) {
                    noteTool.cancelInsertionTool()
                }
            } else noteTool.enterDynamicEntry(level)
        },
        pickFermata = { shape: FermataShape ->
            var rt = session.runtimeScore
            val afterTimes = selectedExpressionEvents().map { it.endTime }.distinct()
            var combined: ExpressionEditEngine.Result? = null
            if (rt != null && afterTimes.isNotEmpty()) {
                for (afterTime in afterTimes) {
                    val next = ExpressionEditEngine.addFermata(rt ?: break, afterTime, shape) ?: continue
                    rt = next.score
                    combined = if (combined == null) next else next.copy(
                        affectedMeasures = minOf(combined.affectedMeasures.first, next.affectedMeasures.first)..
                            maxOf(combined.affectedMeasures.last, next.affectedMeasures.last),
                        selectedEventIds = combined.selectedEventIds + next.selectedEventIds,
                    )
                }
                applyExpressionResult(combined) { noteTool.cancelInsertionTool() }
            } else noteTool.enterFermataEntry(shape)
        },
        pickBreath = { shape: BreathMarkShape, scope: BreathMarkScope ->
            var rt = session.runtimeScore
            val selectedPoints = selectedExpressionEvents().mapNotNull { event ->
                staffIdForEvent(event)?.let { staffId ->
                    Triple(staffId, event.endTime, rt?.voiceNumberOf(event.id))
                }
            }
            val points = when (scope) {
                BreathMarkScope.VOICE -> selectedPoints.distinct()
                BreathMarkScope.STAFF -> selectedPoints.distinctBy { (staffId, afterTime, _) -> staffId to afterTime }
                BreathMarkScope.GLOBAL -> selectedPoints.distinctBy { (_, afterTime, _) -> afterTime }
            }
            var combined: ExpressionEditEngine.Result? = null
            if (rt != null && points.isNotEmpty()) {
                for ((staffId, afterTime, voiceNumber) in points) {
                    val next = ExpressionEditEngine.addBreathMark(
                        rt ?: break,
                        staffId,
                        afterTime,
                        scope,
                        shape,
                        voiceNumber = voiceNumber,
                    ) ?: continue
                    rt = next.score
                    combined = if (combined == null) next else next.copy(
                        affectedMeasures = minOf(combined.affectedMeasures.first, next.affectedMeasures.first)..
                            maxOf(combined.affectedMeasures.last, next.affectedMeasures.last),
                        selectedAttachmentIds = combined.selectedAttachmentIds + next.selectedAttachmentIds,
                    )
                }
                applyExpressionResult(combined) { noteTool.cancelInsertionTool() }
            } else noteTool.enterBreathEntry(shape, scope)
        },
        pickHairpin = { type, style ->
            if (!addSelectedSpan(hairpin = type to style)) noteTool.enterHairpinEntry(type, style)
        },
        pickOctaveShift = { type ->
            if (!addSelectedSpan(octave = type)) noteTool.enterOctaveEntry(type)
        },
        pickTempo = { type ->
            var rt = session.runtimeScore
            val onsets = selectedExpressionEvents().map { it.onset }.distinct()
            var combined: ExpressionEditEngine.Result? = null
            if (rt != null && onsets.isNotEmpty() &&
                type !in setOf(TempoMarkType.ACCELERANDO, TempoMarkType.RITARDANDO)) {
                for (onset in onsets) {
                    val live = rt ?: break
                    val next = TempoEditEngine.addMark(
                        live, onset, type,
                        bpm = if (type == TempoMarkType.METRONOME) noteTool.selectedTempoBpm else null,
                    ) ?: continue
                    rt = next.score
                    combined = if (combined == null) next else next.copy(
                        affectedMeasures = minOf(combined.affectedMeasures.first, next.affectedMeasures.first)..
                            maxOf(combined.affectedMeasures.last, next.affectedMeasures.last),
                        selectedAttachmentIds = combined.selectedAttachmentIds + next.selectedAttachmentIds,
                    )
                }
                applyExpressionResult(combined) { noteTool.cancelInsertionTool() }
            } else noteTool.enterTempoEntry(type)
        },
        pickBarline = { type, count ->
            val target = eventSelection.singleOrNull() as? BarlineSection
            if (target != null) {
                session.applyBarlineEdit(target.barline.measureNumber, type, count) { newSelection ->
                    val updated = newSelection.singleOrNull() as? BarlineSection
                    eventSelection = updated?.let {
                        setOf(it.copy(
                            systemIndex = target.systemIndex,
                            visualPlacement = target.visualPlacement,
                        ))
                    }.orEmpty()
                    selectedAnnotationEventId = null
                }
            } else {
                noteTool.enterBarlineEntry(type, count)
            }
        },
        pickVolta = { _ ->
            val target = eventSelection.singleOrNull() as? BarlineSection
            if (target != null) {
                session.applyVoltaEdit(target.barline.measureNumber) { newSelection ->
                    val updated = newSelection.singleOrNull() as? BarlineSection
                    eventSelection = updated?.let {
                        setOf(it.copy(
                            systemIndex = target.systemIndex,
                            visualPlacement = target.visualPlacement,
                        ))
                    }.orEmpty()
                }
            } else noteTool.enterVoltaEntry(1)
        },
        pickNavigation = { mark ->
            val target = eventSelection.singleOrNull() as? BarlineSection
            if (target != null) {
                session.applyNavigationMarkEdit(target.barline.measureNumber, mark) { newSelection ->
                    val updated = newSelection.singleOrNull() as? BarlineSection
                    eventSelection = updated?.let {
                        setOf(it.copy(
                            systemIndex = target.systemIndex,
                            visualPlacement = target.visualPlacement,
                        ))
                    }.orEmpty()
                }
            } else noteTool.enterNavigationEntry(mark)
        },
        pickOrnament = { kind: OrnamentKind, wavy: Boolean ->
            if (wavy) {
                noteTool.enterOrnamentEntry(kind, true)
            } else {
                var rt = session.runtimeScore
                var combined: ExpressionEditEngine.Result? = null
                val selected = selectedExpressionEvents()
                for (event in selected) {
                    val live = rt ?: break
                    val staffId = staffIdForEvent(event) ?: continue
                    val next = ExpressionEditEngine.addOrnament(
                        live,
                        staffId,
                        event.id,
                        kind,
                        OrnamentAnchor.ON_NOTE,
                    ) ?: continue
                    rt = next.score
                    combined = if (combined == null) next else next.copy(
                        affectedMeasures = minOf(combined.affectedMeasures.first, next.affectedMeasures.first)..
                            maxOf(combined.affectedMeasures.last, next.affectedMeasures.last),
                        selectedAttachmentIds = combined.selectedAttachmentIds + next.selectedAttachmentIds,
                    )
                }
                if (combined != null) {
                    applyExpressionResult(combined) { noteTool.cancelInsertionTool() }
                } else noteTool.enterOrnamentEntry(kind, false)
            }
        },
        pickArpeggio = { type: ArpeggioType ->
            val rt = session.runtimeScore
            val targets = if (rt == null) emptyList() else selectedExpressionEvents()
                .filter { it.pitchData.size >= 2 }
                .mapNotNull { event ->
                    (event.originVoiceTrackId ?: rt.voiceTrackIdOf(event.id))?.let {
                        ExpressionEditEngine.NoteTarget(it, event.id)
                    }
                }
            if (rt != null && targets.isNotEmpty()) {
                applyExpressionResult(ExpressionEditEngine.setArpeggio(rt, targets, type)) {
                    noteTool.cancelInsertionTool()
                }
            } else noteTool.enterArpeggioEntry(type)
        },
            ),
        ),
    )
}
