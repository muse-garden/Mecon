package com.mecon.theory.writing

import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId

data class VoicingPlanFrame<SlotKey, DurationValue>(
    val slotKey: SlotKey,
    val onset: TimeCode,
    val duration: DurationValue,
    val pitchesByVoiceId: Map<TrackId, Pitch>,
)

data class VoicingEventCell<SlotKey, DurationValue>(
    val frameIndex: Int,
    val slotKey: SlotKey,
    val voiceId: TrackId,
    val onset: TimeCode,
    val duration: DurationValue,
    val pitch: Pitch,
)

/** Shared immutable expansion from solved frames to one event cell per participating voice. */
object VoicingEventPlanner {
    fun <SlotKey, DurationValue> plan(
        frames: List<VoicingPlanFrame<SlotKey, DurationValue>>,
        voiceIds: List<TrackId>,
        omittedVoiceIdsByFrameIndex: Map<Int, Set<TrackId>> = emptyMap(),
    ): List<VoicingEventCell<SlotKey, DurationValue>> {
        require(voiceIds.isNotEmpty() && voiceIds.distinct().size == voiceIds.size) {
            "A voicing event plan requires distinct voices"
        }
        require(omittedVoiceIdsByFrameIndex.keys.all(frames.indices::contains)) {
            "Omitted voicing cells must reference an existing frame"
        }
        return frames.flatMapIndexed { frameIndex, frame ->
            val omitted = omittedVoiceIdsByFrameIndex[frameIndex].orEmpty()
            require(omitted.all(voiceIds::contains)) {
                "Omitted voicing cells must reference a planned voice"
            }
            voiceIds.filterNot(omitted::contains).map { voiceId ->
                VoicingEventCell(
                    frameIndex = frameIndex,
                    slotKey = frame.slotKey,
                    voiceId = voiceId,
                    onset = frame.onset,
                    duration = frame.duration,
                    pitch = requireNotNull(frame.pitchesByVoiceId[voiceId]) {
                        "Voicing frame $frameIndex is missing voice ${voiceId.value}"
                    },
                )
            }
        }
    }
}
