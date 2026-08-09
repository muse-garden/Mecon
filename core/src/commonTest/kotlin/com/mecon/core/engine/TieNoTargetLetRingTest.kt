package com.mecon.core.engine

import com.mecon.api.primitive.*
import com.mecon.api.runtime.TimeIndexedList
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeTieInfo
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An explicit tie with no following same-pitch note must degrade to a let-ring
 * (laissez vibrer) rather than disappearing — and reconnect as a normal tie once
 * a matching note is inserted after it. See [TieTargetComputer.computePerPitchTies].
 */
class TieNoTargetLetRingTest {

    private fun voice(
        onset: TimeCode,
        pitch: Pitch,
        ties: List<RuntimeTieInfo> = emptyList(),
    ): RuntimeVoiceEvent = RuntimeVoiceEvent(
        id = EventId.generate(),
        onset = onset,
        pitchEvent = RuntimePitchEvent.single(onset, pitch),
        duration = Duration.QUARTER,
        ties = ties,
    )

    /** Tie out with no following note at all → let-ring. */
    @Test
    fun tieWithNoSuccessorIsLetRing() {
        val source = voice(
            TimeCode.of(1, Fraction.ZERO), Pitch.C4,
            ties = listOf(RuntimeTieInfo(0, isLetRing = false))
        )
        val track = TimeIndexedList.of(listOf(source))
        val results = TieTargetComputer.computePerPitchTies(source, track)

        assertEquals(1, results.size)
        val tie = results[0].tieTarget
        assertTrue(tie.isLetRing, "tie with no successor should render as let-ring")
        assertEquals(null, tie.targetEventId)
    }

    /** Tie out followed only by a different-pitch note → still let-ring (no match). */
    @Test
    fun tieWithMismatchedSuccessorIsLetRing() {
        val source = voice(
            TimeCode.of(1, Fraction.ZERO), Pitch.C4,
            ties = listOf(RuntimeTieInfo(0, isLetRing = false))
        )
        val other = voice(TimeCode.of(1, Fraction(1, 4)), Pitch.E4)
        val track = TimeIndexedList.of(listOf(source, other))
        val results = TieTargetComputer.computePerPitchTies(source, track)

        assertEquals(1, results.size)
        val tie = results[0].tieTarget
        assertTrue(tie.isLetRing, "tie with no matching successor should render as let-ring")
        assertEquals(null, tie.targetEventId)
    }

    /** Inserting a same-pitch note after the let-ring source reconnects it as a normal tie. */
    @Test
    fun insertingMatchingSuccessorReconnects() {
        val source = voice(
            TimeCode.of(1, Fraction.ZERO), Pitch.C4,
            ties = listOf(RuntimeTieInfo(0, isLetRing = false))
        )
        val successor = voice(TimeCode.of(1, Fraction(1, 4)), Pitch.C4)
        val track = TimeIndexedList.of(listOf(source, successor))
        val results = TieTargetComputer.computePerPitchTies(source, track)

        assertEquals(1, results.size)
        val tie = results[0].tieTarget
        assertTrue(!tie.isLetRing, "tie should reconnect as a normal tie")
        assertEquals(successor.id, tie.targetEventId)
    }
}
