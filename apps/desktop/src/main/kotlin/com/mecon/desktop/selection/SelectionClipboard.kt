package com.mecon.desktop

import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.interaction.*
import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.BeamingInfo
import com.mecon.core.engine.edit.NoteEditEngine

internal fun buildCutDeletions(
    selection: Set<EventSection>,
    runtime: RuntimeScore,
    computed: ComputedScore?,
): List<NoteEditEngine.Deletion> = buildDeletions(selection, runtime, computed)

internal fun buildCopyTargets(
    selection: Set<EventSection>,
    runtime: RuntimeScore?,
    computed: ComputedScore?,
): List<NoteEditEngine.CopyTarget> {
    if (runtime == null) return emptyList()
    val selections = pitchSelections(selection, runtime, computed).filter { !it.event.isRest }
    if (selections.isEmpty()) return emptyList()
    val selectedEventIds = selections.map { it.event.id }.toSet()
    val beamGroups = computed?.getBeamGroups().orEmpty()

    fun ComputedVoiceEvent.capturedBeaming(): BeamingInfo? {
        val beamable = duration.base.ticks <= DurationBase.EIGHTH.ticks
        val info = beamInfo ?: return if (beamable) BeamingInfo.NONE else null
        val group = beamGroups[info.groupId].orEmpty().sortedBy { it.onset }
        val index = group.indexOfFirst { it.id == id }
        val leftSelected =
            info.beamsLeft > 0 && index > 0 && group[index - 1].id in selectedEventIds
        val rightSelected =
            info.beamsRight > 0 &&
                index >= 0 &&
                index < group.lastIndex &&
                group[index + 1].id in selectedEventIds
        return when {
            leftSelected && rightSelected -> BeamingInfo.middle()
            rightSelected -> BeamingInfo.start()
            leftSelected -> BeamingInfo.end()
            beamable -> BeamingInfo.NONE
            else -> null
        }
    }

    return selections.mapNotNull { selectionInfo ->
        val voiceTrackId =
            runtime.voiceTrackIdOf(selectionInfo.event.id) ?: return@mapNotNull null
        val pitchIndices = selectionInfo.targetIndices()
        if (pitchIndices != null && pitchIndices.isEmpty()) return@mapNotNull null
        NoteEditEngine.CopyTarget(
            voiceTrackId = voiceTrackId,
            eventId = selectionInfo.event.id,
            pitchIndices = pitchIndices,
            beaming = selectionInfo.event.capturedBeaming(),
        )
    }
}

internal fun buildPasteTarget(
    selection: Set<EventSection>,
    runtime: RuntimeScore?,
): NoteEditEngine.PasteTarget? {
    val score = runtime ?: return null
    (selection.lastOrNull() as? StaffAttachmentSection)?.let { section ->
        val staff = score.staffTracks[section.attachment.staffTrackId] ?: return@let
        val voiceTrackId = staff.voiceTracks.firstOrNull()?.id ?: return@let
        return NoteEditEngine.PasteTarget(voiceTrackId, section.attachment.time)
    }
    (selection.lastOrNull() as? MeasureStaffSection)?.let { section ->
        val voiceTrackId =
            score.voiceTrackIdsForStaff(section.staffTrackId).firstOrNull() ?: return null
        return NoteEditEngine.PasteTarget(
            voiceTrackId = voiceTrackId,
            start = TimeCode.of(
                section.measureNumber,
                com.mecon.api.primitive.Fraction.ZERO,
            ),
            clearMeasure = true,
        )
    }
    val event = selection.lastOrNull()?.let { section ->
        when (section) {
            is VoiceNoteSection -> section.event
            is VoiceStemSection -> section.event
            is VoiceFlagSection -> section.event
            is VoiceEventSection -> section.event
            is VoiceArticulationSection -> section.event
            else -> null
        }
    } ?: return null
    val voiceTrackId = score.voiceTrackIdOf(event.id) ?: event.originVoiceTrackId ?: return null
    return NoteEditEngine.PasteTarget(voiceTrackId = voiceTrackId, start = event.onset)
}
