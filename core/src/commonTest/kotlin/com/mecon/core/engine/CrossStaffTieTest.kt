package com.mecon.core.engine

import com.mecon.api.primitive.*
import com.mecon.api.runtime.TimeIndexedList
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeTieInfo
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.storage.RenderingProps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A tie may only connect two notes rendered on the same staff. When the source and
 * target carry different cross-staff offsets, [TieTargetComputer] degrades to let-ring.
 */
class CrossStaffTieTest {

    private fun voice(
        onset: TimeCode,
        pitch: Pitch,
        crossStaffOffset: Int? = null,
        ties: List<RuntimeTieInfo> = emptyList(),
    ): RuntimeVoiceEvent = RuntimeVoiceEvent(
        id = EventId.generate(),
        onset = onset,
        pitchEvent = RuntimePitchEvent.single(onset, pitch),
        duration = Duration.QUARTER,
        rendering = crossStaffOffset?.let { RenderingProps(crossStaffOffset = it) },
        ties = ties,
    )

    @Test
    fun differentStaffOffsetsDegradeToLetRing() {
        val source = voice(
            TimeCode.of(1, Fraction.ZERO), Pitch.B4,
            crossStaffOffset = -1,
            ties = listOf(RuntimeTieInfo(0, isLetRing = false))
        )
        val target = voice(TimeCode.of(1, Fraction(1, 4)), Pitch.B4, crossStaffOffset = null)

        val track = TimeIndexedList.of(listOf(source, target))
        val results = TieTargetComputer.computePerPitchTies(source, track)

        assertEquals(1, results.size)
        val tie = results[0].tieTarget
        assertTrue(tie.isLetRing, "cross-staff tie should be let-ring")
        assertEquals(null, tie.targetEventId)
    }

    @Test
    fun sameStaffOffsetsConnectNormally() {
        val source = voice(
            TimeCode.of(1, Fraction.ZERO), Pitch.B4,
            crossStaffOffset = -1,
            ties = listOf(RuntimeTieInfo(0, isLetRing = false))
        )
        val target = voice(TimeCode.of(1, Fraction(1, 4)), Pitch.B4, crossStaffOffset = -1)

        val track = TimeIndexedList.of(listOf(source, target))
        val results = TieTargetComputer.computePerPitchTies(source, track)

        assertEquals(1, results.size)
        val tie = results[0].tieTarget
        assertTrue(!tie.isLetRing)
        assertEquals(target.id, tie.targetEventId)
    }
}
