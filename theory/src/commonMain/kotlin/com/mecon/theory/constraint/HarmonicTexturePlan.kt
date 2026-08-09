package com.mecon.theory.constraint

import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.theory.SlotWindow

sealed interface HarmonicVoiceParticipation {
    data object ChordMember : HarmonicVoiceParticipation
    data class Sustained(val pitch: Pitch) : HarmonicVoiceParticipation
}

data class VoiceParticipationSpan(
    val window: SlotWindow,
    val voiceId: TrackId,
    val participation: HarmonicVoiceParticipation,
)

data class SustainedToneRelease(
    val slot: Int,
    val voiceId: TrackId,
    /** End of the sustained note and onset of its successor surface note. */
    val releaseOnset: TimeCode,
)

data class HarmonicTexturePlan(
    val participations: List<VoiceParticipationSpan> = emptyList(),
    val sustainedToneReleases: List<SustainedToneRelease> = emptyList(),
) {
    fun participationAt(slot: Int, voiceId: TrackId): HarmonicVoiceParticipation =
        participations.lastOrNull { it.voiceId == voiceId && it.window.contains(slot) }
            ?.participation
            ?: HarmonicVoiceParticipation.ChordMember

    fun chordMemberVoiceIdsAt(slot: Int, allVoiceIds: Collection<TrackId>): Set<TrackId> =
        allVoiceIds.filterTo(linkedSetOf()) {
            participationAt(slot, it) is HarmonicVoiceParticipation.ChordMember
        }

    companion object {
        fun allChordVoices(): HarmonicTexturePlan = HarmonicTexturePlan()
    }
}

enum class ChordOmissionPreference {
    OMIT_FIFTH_FIRST,
    EQUAL_THIRD_OR_FIFTH,
}

data class ChordOmissionPolicy(
    val triadRequiredTones: Set<ChordTone> = setOf(ChordTone.ROOT, ChordTone.THIRD),
    val triadOmittableTones: Set<ChordTone> = setOf(ChordTone.FIFTH),
    val seventhRequiredTones: Set<ChordTone> = setOf(ChordTone.ROOT, ChordTone.SEVENTH),
    val seventhOmittableTones: Set<ChordTone> = setOf(ChordTone.THIRD, ChordTone.FIFTH),
    val maximumOmittedTones: Int = 1,
    val preference: ChordOmissionPreference = ChordOmissionPreference.OMIT_FIFTH_FIRST,
) {
    init {
        require(ChordTone.ROOT in triadRequiredTones)
        require(ChordTone.THIRD in triadRequiredTones)
        require(triadRequiredTones.intersect(triadOmittableTones).isEmpty())
        require(ChordTone.ROOT in seventhRequiredTones)
        require(ChordTone.SEVENTH in seventhRequiredTones)
        require(seventhRequiredTones.intersect(seventhOmittableTones).isEmpty())
        require(maximumOmittedTones in 0..1) {
            "Triad/seventh search currently supports at most one omitted chord tone"
        }
    }
}
