package com.mecon.core.engine

import com.mecon.api.computed.ComputedPluginEvent
import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.ComputedVoltaAttachment
import com.mecon.api.computed.tracks.ComputedPluginTrack
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.events.RuntimePluginEvent
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.TimeIndexedList
import com.mecon.api.runtime.orderedStaffs
import com.mecon.api.storage.events.StoragePluginEvent

/**
 * Computes a ComputedScore from a RuntimeScore.
 * Extracted from ComputedScore factory to prevent :api depending on :core.
 */
fun computeScore(runtime: RuntimeScore): ComputedScore {
    val engine = ComputeEngine(runtime)
    val computedEvents = engine.compute()
    val staffHeader = StaffHeaderComputer.compute(runtime)
    val notationEvents = engine.computeNotationEvents(staffHeader.barlineConnectivity)
    val repeatStructure = RepeatStructureComputer.compute(runtime, notationEvents.barlines)
    val slurs = engine.computeSlurs()
    val tempoKeyframes = TempoComputer.compute(runtime)
    val topStaff = runtime.orderedStaffs().firstOrNull()
    val barlineByBoundary = notationEvents.barlines.associateBy { it.measureNumber }
    val voltaAttachments = if (topStaff == null) emptyList() else repeatStructure.endings.mapNotNull { ending ->
        val start = barlineByBoundary[ending.startMeasure - 1]?.time ?: return@mapNotNull null
        val end = barlineByBoundary[ending.endMeasure]?.time ?: return@mapNotNull null
        ComputedVoltaAttachment(
            id = EventId(
                "volta:${ending.numbers.sorted().joinToString(",")}:" +
                    "${ending.startMeasure}-${ending.endMeasure}"
            ),
            time = start,
            endTime = end,
            staffTrackId = topStaff.id,
            staffIndex = 0,
            ending = ending,
        )
    }
    val staffAttachments = DynamicsComputer.compute(runtime) +
        tempoKeyframes.filterNot { it.isEditorOnly } +
        voltaAttachments

    @Suppress("UNCHECKED_CAST")
    val computedPluginTracks = runtime.pluginTracks.mapValues { (_, rt) ->
        ComputedPluginTrack(
            id = rt.id,
            name = rt.name,
            type = rt.type,
            events = TimeIndexedList.of(rt.events.toList().map { ev ->
                val rtEv = ev as RuntimePluginEvent<StoragePluginEvent>
                object : ComputedPluginEvent<StoragePluginEvent> {
                    override val id: EventId = rtEv.id
                    override val onset: TimeCode = rtEv.onset
                    override val runtimeEvent: RuntimePluginEvent<StoragePluginEvent> = rtEv
                }
            })
        )
    }

    return ComputedScore(
        runtime = runtime,
        computedEvents = computedEvents,
        barlines = notationEvents.barlines,
        voltaEndings = repeatStructure.endings,
        navigationMarks = repeatStructure.navigationMarks,
        clefs = notationEvents.clefs,
        keySignatures = notationEvents.keySignatures,
        timeSignatures = notationEvents.timeSignatures,
        slurs = slurs,
        staffAttachments = staffAttachments,
        tempoKeyframes = tempoKeyframes,
        pluginTracks = computedPluginTracks,
        staffHeader = staffHeader
    )
}
