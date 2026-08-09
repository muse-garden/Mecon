package com.mecon.theory

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import kotlin.test.Test
import kotlin.test.assertEquals

class HarmonicPlanningTest {
    @Test
    fun equalQuarterSlotsWrapAtFourFourMeasureBoundaries() {
        val timeline = HarmonicTimeline.equalQuarterSlots(11)

        assertEquals(
            listOf(
                TimeCode.of(1, 0, 1),
                TimeCode.of(1, 1, 4),
                TimeCode.of(1, 2, 4),
                TimeCode.of(1, 3, 4),
                TimeCode.of(2, 0, 1),
                TimeCode.of(2, 1, 4),
                TimeCode.of(2, 2, 4),
                TimeCode.of(2, 3, 4),
                TimeCode.of(3, 0, 1),
                TimeCode.of(3, 1, 4),
                TimeCode.of(3, 2, 4),
            ),
            timeline.spans.map { it.onset },
        )
    }

    @Test
    fun twoFourTimelineCarriesRealMeasureOnsetsAndBeatWeights() {
        val timeline = HarmonicTimeline.twoFour(
            listOf(Fraction.QUARTER, Fraction.QUARTER, Fraction.HALF, Fraction.QUARTER)
        )

        assertEquals(
            listOf(
                TimeCode.of(1, 0, 1),
                TimeCode.of(1, 1, 4),
                TimeCode.of(2, 0, 1),
                TimeCode.of(3, 0, 1),
            ),
            timeline.spans.map { it.onset },
        )
        assertEquals(BeatWeight.STRONG, timeline.meterPlan.beatWeightAt(timeline.spans[2].onset))
        assertEquals(
            BeatWeight.WEAK,
            timeline.meterPlan.beatWeightAt(TimeCode.of(2, 1, 4)),
        )
    }
}
