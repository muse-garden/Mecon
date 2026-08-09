package com.mecon.theory.writing

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals

class VoicingEventPlannerTest {
    @Test
    fun expandsFramesInStableSlotAndVoiceOrderAndHonorsOmissions() {
        val upper = TrackId("upper")
        val lower = TrackId("lower")
        val c3 = Pitch.fromName("C3")
        val d3 = Pitch.fromName("D3")
        val frames = listOf(
            VoicingPlanFrame(
                slotKey = "a",
                onset = TimeCode.of(1, Fraction.ZERO),
                duration = Fraction.QUARTER,
                pitchesByVoiceId = mapOf(upper to Pitch.C4, lower to c3),
            ),
            VoicingPlanFrame(
                slotKey = "b",
                onset = TimeCode.of(1, Fraction.QUARTER),
                duration = Fraction.QUARTER,
                pitchesByVoiceId = mapOf(upper to Pitch.D4, lower to d3),
            ),
        )

        val cells = VoicingEventPlanner.plan(
            frames = frames,
            voiceIds = listOf(upper, lower),
            omittedVoiceIdsByFrameIndex = mapOf(1 to setOf(lower)),
        )

        assertEquals(
            listOf(
                Triple("a", upper, Pitch.C4),
                Triple("a", lower, c3),
                Triple("b", upper, Pitch.D4),
            ),
            cells.map { Triple(it.slotKey, it.voiceId, it.pitch) },
        )
    }
}
