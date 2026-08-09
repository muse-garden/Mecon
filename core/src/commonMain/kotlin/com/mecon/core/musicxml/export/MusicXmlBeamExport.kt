package com.mecon.core.musicxml.export

import com.mecon.api.computed.BeamInfo
import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.EventId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.StorageVoiceEvent
import com.mecon.core.engine.BeamGroupComputer
import com.mecon.core.musicxml.model.MusicXmlBeam

internal object MusicXmlBeamExport {
    fun exportBeams(
        voiceEvent: StorageVoiceEvent,
        automaticBeamInfo: BeamInfo?,
    ): List<MusicXmlBeam> {
        val beamCount = when (voiceEvent.duration.base) {
            DurationBase.EIGHTH -> 1
            DurationBase.SIXTEENTH -> 2
            DurationBase.THIRTY_SECOND -> 3
            DurationBase.SIXTY_FOURTH -> 4
            DurationBase.ONE_TWENTY_EIGHTH -> 5
            else -> return emptyList()
        }
        val primaryBeamValue = voiceEvent.rendering?.beaming?.let { beaming ->
            if (!beaming.isBeamed) return emptyList()
            when {
                beaming.isBeamStart -> "begin"
                beaming.isBeamEnd -> "end"
                beaming.isBeamMiddle -> "continue"
                else -> return emptyList()
            }
        } ?: automaticBeamInfo?.toMusicXmlBeamValue() ?: return emptyList()
        return (1..beamCount).map { MusicXmlBeam(it, primaryBeamValue) }
    }

    fun computeAutomaticBeamInfo(score: StorageScore): Map<EventId, BeamInfo> {
        val runtime = RuntimeScore.fromStorage(score)
        val result = mutableMapOf<EventId, BeamInfo>()
        for (voiceTrack in runtime.voiceTracks.values) {
            val events = voiceTrack.events.toList()
            val beamInfoByEventId = BeamGroupComputer.computeBeamingForTrack(
                events = events,
                measures = runtime.measures,
                defaultTimeSignature = runtime.defaultTimeSignature,
            )
            for (event in events) {
                if (event.rendering?.beaming == null) {
                    beamInfoByEventId[event.id]?.let { result[event.id] = it }
                }
            }
        }
        return result
    }

    private fun BeamInfo.toMusicXmlBeamValue(): String? = when {
        isGroupStart -> "begin"
        isGroupEnd -> "end"
        isGroupMiddle -> "continue"
        else -> null
    }
}
