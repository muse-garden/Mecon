package com.mecon.desktop

import com.mecon.api.computed.ComputedBreathMark
import com.mecon.api.interaction.EventSection
import com.mecon.api.interaction.LayoutBreakSection
import com.mecon.api.interaction.MeasureStaffSection
import com.mecon.api.interaction.NavigationMarkSection
import com.mecon.api.interaction.StaffAttachmentSection
import com.mecon.api.interaction.VoiceArticulationSection
import com.mecon.api.interaction.VoiceSlurSection
import com.mecon.api.interaction.VoiceTieSection
import com.mecon.api.interaction.VoltaEndingSection
import com.mecon.api.primitive.EventId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.core.engine.edit.ExpressionEditEngine
import com.mecon.core.engine.edit.LayoutBreakEditEngine
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.core.engine.edit.TempoEditEngine
import com.mecon.desktop.service.ScoreSession

internal fun deleteScoreSelection(
    session: ScoreSession,
    selection: Set<EventSection>,
    onSelectionChange: (Set<EventSection>) -> Unit,
    onAnnotationSelectionChange: (EventId?) -> Unit,
    onApplyExpressionResult: (ExpressionEditEngine.Result?) -> Unit,
) {
    val runtime = session.runtimeScore ?: return
    if (selection.isEmpty()) return

    val clearSelection = {
        onSelectionChange(emptySet())
        onAnnotationSelectionChange(null)
    }
    val tieEdits = selection.filterIsInstance<VoiceTieSection>().mapNotNull { section ->
        val voiceTrackId = runtime.voiceTrackIdOf(section.sourceEvent.id) ?: return@mapNotNull null
        NoteEditEngine.TieEdit(
            voiceTrackId = voiceTrackId,
            eventId = section.sourceEvent.id,
            tieOut = false,
            pitchIndices = setOf(section.sourcePitchIndex),
        )
    }
    if (tieEdits.isNotEmpty()) {
        session.applyTieEdit(tieEdits) { clearSelection() }
        return
    }
    val slurIds = selection.filterIsInstance<VoiceSlurSection>()
        .mapNotNull { resolveSlur(it, session.computedScore)?.slurId }
        .toSet()
    if (slurIds.isNotEmpty()) {
        NoteEditEngine.deleteSlurs(runtime, slurIds)?.let { result ->
            session.applySlurEdit(result) { clearSelection() }
        }
        return
    }
    val selectedAttachments = selection.filterIsInstance<StaffAttachmentSection>().map { it.attachment }
    val globalBreathIds = selectedAttachments
        .filterIsInstance<ComputedBreathMark>()
        .mapNotNullTo(LinkedHashSet()) { it.globalEventId }
    val attachmentIds = selectedAttachments
        .filterNot { it is ComputedBreathMark && it.isGlobal }
        .mapTo(LinkedHashSet()) { it.id }
    if (globalBreathIds.isNotEmpty()) {
        onApplyExpressionResult(
            ExpressionEditEngine.deleteGlobalPerformanceMarks(runtime, globalBreathIds)
        )
        return
    }
    if (attachmentIds.isNotEmpty()) {
        val tempoIds = attachmentIds.filterTo(LinkedHashSet()) { id ->
            runtime.globalTrack.tempoEvents.any { it.id == id }
        }
        var live: RuntimeScore = runtime
        var combined: ExpressionEditEngine.Result? = null
        TempoEditEngine.delete(live, tempoIds)?.let { next ->
            live = next.score
            combined = next
        }
        ExpressionEditEngine.deleteAttachments(live, attachmentIds - tempoIds)?.let { next ->
            combined = if (combined == null) {
                next
            } else {
                next.copy(
                    affectedMeasures = minOf(combined!!.affectedMeasures.first, next.affectedMeasures.first)..
                        maxOf(combined!!.affectedMeasures.last, next.affectedMeasures.last),
                )
            }
        }
        onApplyExpressionResult(combined)
        return
    }
    val articulationSections = selection.filterIsInstance<VoiceArticulationSection>()
    if (articulationSections.isNotEmpty()) {
        var live: RuntimeScore = runtime
        var combined: ExpressionEditEngine.Result? = null
        val fermataIds = articulationSections.mapNotNullTo(LinkedHashSet()) {
            it.event.fermata?.id
        }
        ExpressionEditEngine.deleteGlobalPerformanceMarks(live, fermataIds)?.let { next ->
            live = next.score
            combined = next
        }
        for ((articulation, sections) in articulationSections.groupBy { section ->
            section.event.articulations.getOrNull(section.index)
        }) {
            articulation ?: continue
            val targets = sections.mapNotNull { section ->
                live.voiceTrackIdOf(section.event.id)?.let {
                    ExpressionEditEngine.NoteTarget(it, section.event.id)
                }
            }
            val next = ExpressionEditEngine.toggleArticulation(live, targets, articulation) ?: continue
            live = next.score
            combined = next.copy(
                affectedMeasures = if (combined == null) {
                    next.affectedMeasures
                } else {
                    minOf(combined!!.affectedMeasures.first, next.affectedMeasures.first)..
                        maxOf(combined!!.affectedMeasures.last, next.affectedMeasures.last)
                },
                selectedEventIds = emptySet(),
            )
        }
        onApplyExpressionResult(combined)
        return
    }
    val layoutBreak = selection.singleOrNull() as? LayoutBreakSection
    if (layoutBreak != null) {
        val updated = LayoutBreakEditEngine.set(runtime, layoutBreak.beforeMeasure, null) ?: return
        session.applyMeasureEdit(
            updated,
            (layoutBreak.beforeMeasure - 1)..layoutBreak.beforeMeasure,
        ) {
            clearSelection()
        }
        return
    }
    val volta = selection.singleOrNull() as? VoltaEndingSection
    if (volta != null) {
        session.deleteVolta(volta) { clearSelection() }
        return
    }
    val navigation = selection.singleOrNull() as? NavigationMarkSection
    if (navigation != null) {
        session.deleteNavigationMark(navigation) { clearSelection() }
        return
    }
    val deletions = buildDeletions(selection, runtime, session.computedScore)
    if (deletions.isNotEmpty()) {
        session.applyNoteDeletes(deletions) { newSelection ->
            onSelectionChange(newSelection)
            onAnnotationSelectionChange(null)
        }
    }
}
