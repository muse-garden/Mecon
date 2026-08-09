package com.mecon.theory.freepractice

import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.StemDirection
import com.mecon.api.storage.tracks.Clef
import com.mecon.theory.VoicePlan
import com.mecon.theory.writing.GrandStaffVoiceLayout

data class VoiceNotationBinding(
    val voiceId: TrackId,
    val pitchTrackId: TrackId,
    val staffId: TrackId,
    val voiceNumber: Int,
    val clef: Clef,
    val stemDirection: StemDirection,
)

/**
 * Explicit bridge from theory voices to editable notation lanes on a two-staff grand staff.
 *
 * The binding is only the default notation organization. A writing surface may move individual
 * noteheads between these lanes, and analysis must not treat the resulting lane as a guaranteed
 * monodic analytical voice.
 */
data class VoiceNotationPlan(
    val bindings: List<VoiceNotationBinding>,
) {
    init {
        require(bindings.isNotEmpty()) {
            "A free-practice notation plan must contain at least one voice"
        }
        require(bindings.map { it.voiceId }.toSet().size == bindings.size) {
            "Free-practice notation voice ids must be unique"
        }
        require(bindings.map { it.pitchTrackId }.toSet().size == bindings.size) {
            "Free-practice notation pitch-track ids must be unique"
        }
    }

    fun bindingFor(voiceId: TrackId): VoiceNotationBinding =
        bindings.first { it.voiceId == voiceId }

    companion object {
        val UPPER_STAFF_ID = TrackId("free-practice-upper-staff")
        val LOWER_STAFF_ID = TrackId("free-practice-lower-staff")

        fun from(
            voicePlan: VoicePlan,
            staffVoices: GrandStaffVoiceLayout = GrandStaffVoiceLayout.defaultFor(
                voicePlan.voices.size
            ),
        ): VoiceNotationPlan {
            val sorted = voicePlan.voices.sortedBy { it.order }
            require(staffVoices.capacity == sorted.size) {
                "Grand-staff voice capacity must match the voice plan"
            }
            val bindings = sorted.mapIndexed { index, voice ->
                val upper = index < staffVoices.upperVoiceCount
                VoiceNotationBinding(
                    voiceId = voice.id,
                    pitchTrackId = TrackId("${voice.id.value}-pitch"),
                    staffId = if (upper) UPPER_STAFF_ID else LOWER_STAFF_ID,
                    voiceNumber = if (upper) {
                        index + 1
                    } else {
                        index - staffVoices.upperVoiceCount + 1
                    },
                    clef = if (upper) Clef.TREBLE else Clef.BASS,
                    stemDirection = StemDirection.AUTO,
                )
            }
            return VoiceNotationPlan(bindings)
        }
    }
}
