package com.mecon.theory.freepractice

import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TrackId
import com.mecon.theory.VoiceBoundary
import com.mecon.theory.VoicePlan
import com.mecon.theory.VoiceRange
import com.mecon.theory.VoiceSpec
import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceNotationPlanTest {
    @Test
    fun mapsFiveVoicesToStableLanesOnTwoStaves() {
        val voices = (0 until 5).map { index ->
            VoiceSpec(
                id = TrackId("voice-${index + 1}"),
                order = index,
                boundary = when (index) {
                    0 -> VoiceBoundary.UPPER_OUTER
                    4 -> VoiceBoundary.LOWER_OUTER
                    else -> VoiceBoundary.INNER
                },
                range = VoiceRange(
                    Pitch.fromMidi(48 + (4 - index) * 3),
                    Pitch.fromMidi(67 + (4 - index) * 3),
                ),
            )
        }

        val plan = VoiceNotationPlan.from(VoicePlan(voices))

        assertEquals(listOf(1, 2, 3, 1, 2), plan.bindings.map { it.voiceNumber })
        assertEquals(
            listOf(
                VoiceNotationPlan.UPPER_STAFF_ID,
                VoiceNotationPlan.UPPER_STAFF_ID,
                VoiceNotationPlan.UPPER_STAFF_ID,
                VoiceNotationPlan.LOWER_STAFF_ID,
                VoiceNotationPlan.LOWER_STAFF_ID,
            ),
            plan.bindings.map { it.staffId },
        )
        assertEquals(voices.map { it.id }, plan.bindings.map { it.voiceId })
    }
}
