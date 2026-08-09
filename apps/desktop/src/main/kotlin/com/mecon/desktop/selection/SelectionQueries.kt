package com.mecon.desktop

import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.interaction.*
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.core.engine.edit.NoteEditEngine

internal val EventSection.timeCode: TimeCode?
    get() = when (this) {
        is VoiceNoteSection -> event.onset
        is VoiceStemSection -> event.onset
        is VoiceFlagSection -> event.onset
        is VoiceBeamSection -> events.firstOrNull()?.onset
        is VoiceEventSection -> event.onset
        is VoiceArticulationSection -> event.onset
        is BarlineSection -> barline.time
        is ClefSection -> clef.time
        is KeySignatureSection -> keySignature.time
        is TimeSignatureSection -> timeSignature.time
        is VoiceTupletSection -> startEvent.onset
        is VoiceTieSection -> sourceEvent.onset
        is VoiceSlurSection -> startEvent.onset
        is StaffAttachmentSection -> attachment.time
        is MeasureStaffSection -> TimeCode.ofMeasure(measureNumber)
        is LayoutBreakSection -> TimeCode.ofMeasure(beforeMeasure)
        is HiddenStaffSection -> TimeCode.ofMeasure(range.from)
        is VoltaEndingSection -> TimeCode.ofMeasure(ending.startMeasure)
        is NavigationMarkSection -> navigation.time
    }

/** The voice track owning [eventId], by scanning each voice's events. */
internal fun RuntimeScore.voiceTrackIdOf(eventId: EventId): TrackId? =
    voiceTracks.entries.firstOrNull { (_, voice) ->
        voice.events.toList().any { it.id == eventId }
    }?.key

internal fun RuntimeScore.voiceNumberOf(eventId: EventId): Int? =
    voiceTracks.values.firstOrNull { voice ->
        voice.events.toList().any { it.id == eventId }
    }?.voiceNumber

internal fun RuntimeScore.voiceTrackIdsForStaff(staffTrackId: TrackId): Set<TrackId> =
    staffTracks[staffTrackId]?.voiceTracks?.mapTo(LinkedHashSet()) { it.id }.orEmpty()

internal fun RuntimeScore.hasPitchedEventsIn(measures: Set<Int>): Boolean =
    voiceTracks.values.asSequence()
        .flatMap { it.events.asSequence() }
        .any { it.onset.measure in measures && it.pitchEvent.pitches.isNotEmpty() }

internal fun expandMeasureSelection(
    selection: Set<EventSection>,
    runtime: RuntimeScore,
    computed: ComputedScore?,
): Set<EventSection> {
    val result = LinkedHashSet<EventSection>()
    selection.forEach { section ->
        if (section !is MeasureStaffSection) {
            result += section
            return@forEach
        }
        val voiceIds = runtime.voiceTrackIdsForStaff(section.staffTrackId)
        computed?.eventsInMeasure(section.measureNumber)
            ?.filter { event ->
                (event.originVoiceTrackId ?: runtime.voiceTrackIdOf(event.id)) in voiceIds
            }
            ?.forEach { result += VoiceEventSection(it) }
    }
    return result
}

internal fun buildDeletions(
    selection: Set<EventSection>,
    runtime: RuntimeScore,
    computed: ComputedScore?,
): List<NoteEditEngine.Deletion> {
    class Accumulator {
        var wholeEvent = false
        val pitches = mutableSetOf<Int>()
    }
    val byEvent = LinkedHashMap<EventId, Accumulator>()
    for (section in expandMeasureSelection(selection, runtime, computed)) {
        when (section) {
            is VoiceNoteSection ->
                byEvent.getOrPut(section.event.id, ::Accumulator).pitches.add(section.pitchIndex)
            is VoiceEventSection ->
                byEvent.getOrPut(section.event.id, ::Accumulator).wholeEvent = true
            else -> Unit
        }
    }
    return byEvent.mapNotNull { (eventId, accumulator) ->
        val voiceTrackId = runtime.voiceTrackIdOf(eventId) ?: return@mapNotNull null
        NoteEditEngine.Deletion(
            voiceTrackId = voiceTrackId,
            eventId = eventId,
            pitchIndices = if (accumulator.wholeEvent) null else accumulator.pitches,
        )
    }
}

internal fun selectedEvents(
    selection: Set<EventSection>,
    runtime: RuntimeScore,
    computed: ComputedScore?,
): List<ComputedVoiceEvent> =
    expandMeasureSelection(selection, runtime, computed).mapNotNull { section ->
        when (section) {
            is VoiceNoteSection -> section.event
            is VoiceEventSection -> section.event
            is VoiceArticulationSection -> section.event
            else -> null
        }
    }.distinctBy { it.id }

internal fun buildSlurTargets(
    selection: Set<EventSection>,
    runtime: RuntimeScore,
    computed: ComputedScore?,
): List<NoteEditEngine.SlurTarget> = selectedEvents(selection, runtime, computed)
    .filterNot { it.isRest }
    .groupBy { it.originVoiceTrackId ?: runtime.voiceTrackIdOf(it.id) }
    .mapNotNull { (voiceTrackId, events) ->
        val voiceId = voiceTrackId ?: return@mapNotNull null
        val ordered = events.distinctBy { it.id }.sortedBy { it.onset }
        if (ordered.size < 2) return@mapNotNull null
        NoteEditEngine.SlurTarget(voiceId, ordered.first().id, ordered.last().id)
    }

internal fun resolveSlur(
    section: VoiceSlurSection,
    computed: ComputedScore?,
): com.mecon.api.computed.ComputedSlur? = computed?.slurs?.firstOrNull {
    (section.slurId == null || it.slurId == section.slurId) &&
        it.startEventId == section.startEvent.id &&
        it.endEventId == section.endEvent.id &&
        it.nestingLevel == section.nestingLevel
}

internal class PitchSelection(val event: ComputedVoiceEvent) {
    var whole = false
    val pitches = LinkedHashSet<Int>()

    fun targetIndices(): Set<Int>? =
        if (whole) null else pitches.filter { it in event.pitchData.indices }.toSet()

    fun selectedPitchData(): List<com.mecon.api.computed.ComputedPitchData> =
        if (whole) {
            event.pitchData
        } else {
            pitches.filter { it in event.pitchData.indices }.map { event.pitchData[it] }
        }
}

internal fun pitchSelections(
    selection: Set<EventSection>,
    runtime: RuntimeScore,
    computed: ComputedScore?,
): List<PitchSelection> {
    val byEvent = LinkedHashMap<EventId, PitchSelection>()
    for (section in expandMeasureSelection(selection, runtime, computed)) {
        when (section) {
            is VoiceNoteSection ->
                byEvent.getOrPut(section.event.id) { PitchSelection(section.event) }
                    .pitches.add(section.pitchIndex)
            is VoiceEventSection ->
                byEvent.getOrPut(section.event.id) { PitchSelection(section.event) }.whole = true
            else -> Unit
        }
    }
    return byEvent.values.toList()
}

internal fun buildVoiceMoveTargets(
    selection: Set<EventSection>,
    runtime: RuntimeScore,
    computed: ComputedScore?,
    targetVoiceNumber: Int,
): List<NoteEditEngine.VoiceMoveTarget> =
    pitchSelections(selection, runtime, computed)
        .filterNot { it.event.isRest }
        .mapNotNull { selected ->
            val voiceTrackId = runtime.voiceTrackIdOf(selected.event.id) ?: return@mapNotNull null
            NoteEditEngine.VoiceMoveTarget(
                voiceTrackId = voiceTrackId,
                eventId = selected.event.id,
                targetVoiceNumber = targetVoiceNumber,
                pitchIndices = selected.targetIndices(),
            )
        }
