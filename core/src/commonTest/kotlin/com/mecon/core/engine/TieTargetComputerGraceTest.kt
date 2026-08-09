package com.mecon.core.engine

import com.mecon.api.computed.ComputedTieTarget
import com.mecon.api.primitive.*
import com.mecon.api.runtime.TimeIndexedList
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeTieInfo
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the grace-note tie rule in [TieTargetComputer]: a tied grace must
 * resolve to the next same-`(measure, beat)` event with a matching pitch.
 * Search stops at the principal (the event with `grace == null`).
 */
class TieTargetComputerGraceTest {

    private fun voice(
        onset: TimeCode,
        pitch: Pitch,
        ties: List<RuntimeTieInfo> = emptyList(),
    ): RuntimeVoiceEvent {
        val pe = RuntimePitchEvent.single(onset, pitch)
        return RuntimeVoiceEvent(
            id = EventId.generate(),
            onset = onset,
            pitchEvent = pe,
            duration = Duration.EIGHTH,
            ties = ties,
        )
    }

    /** Grace tied to next grace in same group — should connect to immediate neighbor. */
    @Test
    fun graceTiesToNextGraceInGroup() {
        val g1 = voice(
            TimeCode.of(1, Fraction(1, 2), Fraction(-1, 1)),
            Pitch.D4,
            ties = listOf(RuntimeTieInfo(0, isLetRing = false))
        )
        val g2 = voice(TimeCode.of(1, Fraction(1, 2), Fraction(-1, 2)), Pitch.D4)
        val principal = voice(TimeCode.of(1, Fraction(1, 2)), Pitch.E4)

        val track = TimeIndexedList.of(listOf(g1, g2, principal))

        val results = TieTargetComputer.computePerPitchTies(g1, track)
        assertEquals(1, results.size)
        val target = results[0].tieTarget
        assertTrue(!target.isLetRing && target.targetEventId != null)
        assertEquals(g2.id, target.targetEventId)
    }

    /** Grace tied to principal when no intermediate grace matches. */
    @Test
    fun graceTiesToPrincipal() {
        val g1 = voice(
            TimeCode.of(1, Fraction.ZERO, Fraction(-1, 1)),
            Pitch.C4,
            ties = listOf(RuntimeTieInfo(0, isLetRing = false))
        )
        val principal = voice(TimeCode.of(1, Fraction.ZERO), Pitch.C4)

        val track = TimeIndexedList.of(listOf(g1, principal))

        val results = TieTargetComputer.computePerPitchTies(g1, track)
        assertEquals(1, results.size)
        val target = results[0].tieTarget
        assertTrue(!target.isLetRing && target.targetEventId != null)
        assertEquals(principal.id, target.targetEventId)
    }

    /**
     * No same-pitch candidate at the same beat — search must NOT spill over to
     * the next event in track order (which lives at a later beat).
     */
    @Test
    fun graceWithoutMatchReturnsNoTarget() {
        val g1 = voice(
            TimeCode.of(1, Fraction.ZERO, Fraction(-1, 1)),
            Pitch.C4,
            ties = listOf(RuntimeTieInfo(0, isLetRing = false))
        )
        val principal = voice(TimeCode.of(1, Fraction.ZERO), Pitch.E4)
        val nextBeat = voice(TimeCode.of(1, Fraction(1, 4)), Pitch.C4)

        val track = TimeIndexedList.of(listOf(g1, principal, nextBeat))

        val results = TieTargetComputer.computePerPitchTies(g1, track)
        // Either no tie or no event target — the C4 at the next beat must not
        // be selected.
        if (results.isNotEmpty()) {
            val target = results[0].tieTarget
            assertTrue(target.isLetRing || target.targetEventId != nextBeat.id)
        }
    }
}
