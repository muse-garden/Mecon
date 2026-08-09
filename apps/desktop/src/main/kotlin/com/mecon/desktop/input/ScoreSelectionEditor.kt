package com.mecon.desktop.input

import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.interaction.EventSection
import com.mecon.api.primitive.Accidental
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.Articulation
import com.mecon.api.storage.BeamingInfo
import com.mecon.core.engine.edit.ExpressionEditEngine
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.desktop.buildSlurTargets
import com.mecon.desktop.buildVoiceMoveTargets
import com.mecon.desktop.pitchSelections
import com.mecon.desktop.selectedEvents
import com.mecon.desktop.service.EditableNoteHost
import com.mecon.desktop.ui.components.EditTool
import com.mecon.desktop.ui.components.NotePaletteActions
import com.mecon.desktop.ui.components.NoteToolState
import com.mecon.desktop.ui.components.PaletteSelectionInfo
import com.mecon.desktop.voiceTrackIdOf

/**
 * Shared selected-note editor used by both the main score and embedded notation workbenches.
 *
 * It is the single owner of section projection, set/clear toggle decisions and palette action
 * wiring. Each score host remains responsible only for committing engine results to its own history.
 */
class ScoreSelectionEditor(
    private val host: EditableNoteHost,
    private val noteTool: NoteToolState,
    private val selection: () -> Set<EventSection>,
    private val selectionInfo: () -> PaletteSelectionInfo,
    private val onAfterEdit: (Set<EventSection>) -> Unit,
    private val onDurationConflict: () -> Unit = {},
    private val onTupletConflict: () -> Unit = {},
    private val onSmallNoteConflict: () -> Unit = {},
) : SelectionEditor {
    override val active: Boolean
        get() = noteTool.tool != EditTool.NOTE && selectionInfo().editable

    override fun editDurationBase(base: DurationBase) {
        val edits = durationTargets()
            .map { (trackId, event) ->
                NoteEditEngine.DurationEdit(trackId, event.id, Duration(base))
            }
        host.applyDurationEdits(edits, onDurationConflict, onAfterEdit)
    }

    override fun editDots(dots: Int) {
        val turnOff = selectionInfo().dots == dots
        val edits = durationTargets()
            .map { (trackId, event) ->
                NoteEditEngine.DurationEdit(
                    trackId,
                    event.id,
                    Duration(event.duration.base, if (turnOff) 0 else dots),
                )
            }
        host.applyDurationEdits(edits, onDurationConflict, onAfterEdit)
    }

    override fun editAccidental(accidental: Accidental) {
        val clear = selectionInfo().accidental == accidental
        val edits = pitchTargets()
            .map { (trackId, eventId, pitchIndices) ->
                NoteEditEngine.AccidentalEdit(
                    trackId,
                    eventId,
                    if (clear) null else accidental,
                    pitchIndices,
                )
            }
        host.applyAccidentalEdit(edits, onAfterEdit)
    }

    override fun editTie() {
        val tieOut = selectionInfo().tieOut != true
        val edits = pitchTargets()
            .map { (trackId, eventId, pitchIndices) ->
                NoteEditEngine.TieEdit(trackId, eventId, tieOut, pitchIndices)
            }
        host.applyTieEdit(edits, onAfterEdit)
    }

    override fun editVoice(voiceNumber: Int) {
        noteTool.activeVoiceNumber = voiceNumber
        val runtime = host.runtimeScore ?: return
        val targets = buildVoiceMoveTargets(
            selection(),
            runtime,
            host.computedScore,
            voiceNumber,
        )
        host.applyVoiceMove(targets, onAfterEdit)
    }

    override fun editTuplet(count: Int) {
        noteTool.rememberTupletCount(count)
        host.applyTupletEdit(tupletTargets(count), onTupletConflict, onAfterEdit)
    }

    override fun editBeaming(beaming: BeamingInfo?) {
        val runtime = host.runtimeScore ?: return
        val info = selectionInfo()
        val alreadySet = beaming != null &&
            info.effectiveBeamLeft == beaming.beamLeft &&
            info.effectiveBeamRight == beaming.beamRight
        val replacement = if (alreadySet) null else beaming
        val edits = selectedEvents(selection(), runtime, host.computedScore)
            .mapNotNull { event ->
                runtime.voiceTrackIdOf(event.id)
                    ?.let { NoteEditEngine.BeamingEdit(it, event.id, replacement) }
            }
        host.applyBeamingEdit(edits, onAfterEdit)
    }

    override fun groupBeam() {
        val runtime = host.runtimeScore ?: return
        val notes = selectedEvents(selection(), runtime, host.computedScore)
            .filterNot { it.isRest }
            .sortedWith(compareBy({ it.onset.measure }, { it.onset.beat?.toDouble() ?: 0.0 }))
        if (notes.size < 2) return
        val edits = notes.mapIndexedNotNull { index, event ->
            val trackId = runtime.voiceTrackIdOf(event.id) ?: return@mapIndexedNotNull null
            val beaming = when (index) {
                0 -> BeamingInfo.start()
                notes.lastIndex -> BeamingInfo.end()
                else -> BeamingInfo.middle()
            }
            NoteEditEngine.BeamingEdit(trackId, event.id, beaming)
        }
        host.applyBeamingEdit(edits, onAfterEdit)
    }

    override fun addSlur() {
        val runtime = host.runtimeScore ?: return
        val targets = buildSlurTargets(selection(), runtime, host.computedScore)
        host.addSlurs(targets, onAfterEdit)
    }

    override fun editArticulation(articulation: Articulation) {
        val runtime = host.runtimeScore ?: return
        val targets = selectedNoteEvents(runtime).mapNotNull { event ->
            (event.originVoiceTrackId ?: runtime.voiceTrackIdOf(event.id))
                ?.let { ExpressionEditEngine.NoteTarget(it, event.id) }
        }
        host.toggleArticulation(targets, articulation, onAfterEdit)
    }

    override fun convertToSmallNotes() {
        val runtime = host.runtimeScore ?: return
        val selected = selectedEvents(selection(), runtime, host.computedScore)
        if (selected.isEmpty() || selected.any { !it.isRest }) return
        val edits = selected
            .mapNotNull { event -> runtime.voiceTrackIdOf(event.id)?.let { it to event.id } }
            .groupBy({ it.first }, { it.second })
            .map { (voiceId, eventIds) ->
                NoteEditEngine.SmallNoteEdit(voiceId, eventIds.toSet())
            }
        host.applySmallNoteEdits(
            edits = edits,
            onConflict = onSmallNoteConflict,
            onAfter = {
                noteTool.enterNormalEntry()
                onAfterEdit(it)
            },
        )
    }

    fun paletteActions(): NotePaletteActions = NotePaletteActions(
        editDurationBase = ::editDurationBase,
        editDots = ::editDots,
        editAccidental = ::editAccidental,
        editTie = ::editTie,
        addSlur = ::addSlur,
        editVoice = ::editVoice,
        applyTuplet = ::editTuplet,
        editBeaming = ::editBeaming,
        groupBeam = ::groupBeam,
        editArticulation = ::editArticulation,
        convertToSmallNotes = ::convertToSmallNotes,
    )

    private fun durationTargets(): List<Pair<TrackId, ComputedVoiceEvent>> {
        val runtime = host.runtimeScore ?: return emptyList()
        return selectedEvents(selection(), runtime, host.computedScore)
            .mapNotNull { event -> runtime.voiceTrackIdOf(event.id)?.let { it to event } }
    }

    private fun tupletTargets(count: Int): List<NoteEditEngine.TupletEdit> {
        val runtime = host.runtimeScore ?: return emptyList()
        return selectedEvents(selection(), runtime, host.computedScore)
            .mapNotNull { event -> runtime.voiceTrackIdOf(event.id)?.let { it to event.id } }
            .groupBy({ it.first }, { it.second })
            .map { (voiceTrackId, eventIds) ->
                NoteEditEngine.TupletEdit(
                    voiceTrackId = voiceTrackId,
                    eventIds = eventIds.toSet(),
                    count = count,
                )
            }
    }

    private fun pitchTargets(): List<Triple<TrackId, EventId, Set<Int>?>> {
        val runtime = host.runtimeScore ?: return emptyList()
        return pitchSelections(selection(), runtime, host.computedScore)
            .filterNot { it.event.isRest }
            .mapNotNull { pitchSelection ->
                runtime.voiceTrackIdOf(pitchSelection.event.id)?.let {
                    Triple(it, pitchSelection.event.id, pitchSelection.targetIndices())
                }
            }
    }

    private fun selectedNoteEvents(runtime: RuntimeScore) =
        selectedEvents(selection(), runtime, host.computedScore).filterNot { it.isRest }
}
