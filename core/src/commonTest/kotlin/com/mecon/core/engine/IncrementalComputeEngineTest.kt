package com.mecon.core.engine

import com.mecon.api.computed.ComputedScore
import com.mecon.api.primitive.*
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeTieInfo
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.TupletSpan
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Golden-rule tests for [computeScoreIncremental]: for every edit, the incrementally merged
 * [ComputedScore] must equal a full [computeScore] of the same runtime, field by field. See
 * `docs/data_model/incremental-update.md` §5.
 */
class IncrementalComputeEngineTest {

    // ---- fixtures -------------------------------------------------------------------------------

    private fun emptyScore(key: KeySignature = KeySignature.C_MAJOR): RuntimeScore =
        RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("T", TimeSignature.COMMON, key)))

    private fun RuntimeScore.ptId(): TrackId = pitchTracks.keys.first()
    private fun RuntimeScore.vtId(): TrackId = voiceTracks.keys.first()

    private fun tc(measure: Int, beatNum: Int = 0, beatDen: Int = 1) =
        TimeCode.of(measure, Fraction(beatNum, beatDen))

    /** Add a note (or rest when [pitch] is null) with a deterministic id derived from [idTag]. */
    private fun RuntimeScore.addNote(
        idTag: String,
        onset: TimeCode,
        pitch: Pitch?,
        duration: Duration,
        tupletSpan: TupletSpan? = null,
        slurStarts: Int = 0,
        slurEnds: Int = 0,
        ties: List<RuntimeTieInfo> = emptyList(),
    ): RuntimeScore {
        val eventId = EventId(idTag)
        val pe = RuntimePitchEvent(
            id = EventId("p-$idTag"),
            onset = onset,
            pitches = if (pitch == null) emptyList() else listOf(pitch),
        )
        val ve = RuntimeVoiceEvent(
            id = eventId,
            onset = onset,
            pitchEvent = pe,
            duration = duration,
            tupletSpan = tupletSpan,
            slurStarts = slurStarts,
            slurEnds = slurEnds,
            ties = ties,
        )
        return addPitchEvent(ptId(), pe).addVoiceEvent(vtId(), ve)
    }

    private fun RuntimeScore.findVoiceEvent(eventId: EventId): RuntimeVoiceEvent =
        voiceTracks.getValue(vtId()).events.toList().first { it.id == eventId }

    /** Replace a note's pitch, keeping its event id (the common "change pitch" edit). */
    private fun RuntimeScore.editPitch(eventId: EventId, newPitch: Pitch): RuntimeScore {
        val ve = findVoiceEvent(eventId)
        val newPe = ve.pitchEvent.copy(pitches = listOf(newPitch))
        return removeVoiceEvent(vtId(), eventId)
            .removePitchEvent(ptId(), ve.pitchEvent.id)
            .addPitchEvent(ptId(), newPe)
            .addVoiceEvent(vtId(), ve.copy(pitchEvent = newPe))
    }

    private fun RuntimeScore.deleteNote(eventId: EventId): RuntimeScore {
        val ve = findVoiceEvent(eventId)
        return removeVoiceEvent(vtId(), eventId).removePitchEvent(ptId(), ve.pitchEvent.id)
    }

    private fun interval(start: TimeCode, end: TimeCode) = TimeRange(start, end)

    // ---- golden assertion -----------------------------------------------------------------------

    private fun assertIncrementalMatchesFull(
        previous: ComputedScore,
        newRuntime: RuntimeScore,
        editInterval: TimeRange,
    ): ComputedScore {
        val inc = computeScoreIncremental(previous, newRuntime, editInterval).computed
        val full = computeScore(newRuntime)
        assertEquals(full.computedEvents, inc.computedEvents, "computedEvents differ from full recompute")
        assertEquals(full.slurs.toSet(), inc.slurs.toSet(), "slurs differ from full recompute")
        assertEquals(full.barlines, inc.barlines, "barlines differ from full recompute")
        assertEquals(full.clefs, inc.clefs, "clefs differ from full recompute")
        assertEquals(full.keySignatures, inc.keySignatures, "keySignatures differ from full recompute")
        assertEquals(full.timeSignatures, inc.timeSignatures, "timeSignatures differ from full recompute")
        return inc
    }

    // ---- accidentals ----------------------------------------------------------------------------

    @Test
    fun pitchChangePropagatesAccidentalWithinMeasure() {
        // m1: C4 then C4 (no accidentals). Anchor in m3 keeps the measure count fixed.
        val base = emptyScore()
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.QUARTER)
            .addNote("n2", tc(1, 1, 4), Pitch.C4, Duration.QUARTER)
            .addNote("anchor", tc(3, 0), Pitch.G4, Duration.WHOLE)
        val previous = computeScore(base)
        // Edit n1 → C#4. n2 (C natural after C#) must now show a natural — i.e. n2 is recomputed too.
        val edited = base.editPitch(EventId("n1"), Pitch(0, 1))
        val inc = assertIncrementalMatchesFull(previous, edited, interval(tc(1, 0), tc(1, 1, 4)))
        assertEquals(Accidental.SHARP, inc.computedEvents.getValue(EventId("n1")).pitchData.first().effectiveAccidental)
        // n2's accidental context changed (it now follows a C#); the golden computedEvents equality
        // above already proves it matches a full recompute — so n2 was recomputed despite not being edited.
    }

    @Test
    fun pitchChangeRemovesPriorAccidentalPropagation() {
        // m1: C#4 then C4(shows natural). Editing the first back to C4 clears n2's natural.
        val base = emptyScore()
            .addNote("n1", tc(1, 0), Pitch(0, 1), Duration.QUARTER)
            .addNote("n2", tc(1, 1, 4), Pitch.C4, Duration.QUARTER)
            .addNote("anchor", tc(3, 0), Pitch.G4, Duration.WHOLE)
        val previous = computeScore(base)
        val edited = base.editPitch(EventId("n1"), Pitch.C4)
        assertIncrementalMatchesFull(previous, edited, interval(tc(1, 0), tc(1, 1, 4)))
    }

    // ---- ties -----------------------------------------------------------------------------------

    @Test
    fun tieBreaksWhenTargetPitchChangedSameMeasure() {
        // Explicit tie: n1 (C4) is tied to the next C4. Change the 2nd to D4 → the connecting tie has
        // no valid target and degrades to a let-ring (laissez vibrer) rather than disappearing.
        // (Ties are explicit only; back-to-back same-pitch notes are NOT auto-tied.)
        val base = emptyScore()
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.QUARTER, ties = listOf(RuntimeTieInfo(0, false)))
            .addNote("n2", tc(1, 1, 4), Pitch.C4, Duration.QUARTER)
            .addNote("anchor", tc(3, 0), Pitch.G4, Duration.WHOLE)
        val previous = computeScore(base)
        val before = previous.computedEvents.getValue(EventId("n1")).pitchData.first().tieTarget
        assertEquals(EventId("n2"), before?.targetEventId, "precondition: n1 connects to n2")
        assertFalse(before!!.isLetRing, "precondition: n1 is a real connecting tie")
        val edited = base.editPitch(EventId("n2"), Pitch.D4)
        val inc = assertIncrementalMatchesFull(previous, edited, interval(tc(1, 1, 4), tc(1, 2, 4)))
        val after = inc.computedEvents.getValue(EventId("n1")).pitchData.first().tieTarget
        assertTrue(after?.isLetRing == true, "n1 connecting tie should degrade to let-ring")
        assertEquals(null, after?.targetEventId, "n1 tie no longer points to a target")
    }

    @Test
    fun tieAcrossBarlineRecomputesPredecessorViaBackwardMargin() {
        // m1 last beat C4 → m2 beat0 C4 (tie across barline). Editing the m2 note must re-resolve the
        // m1 predecessor (one measure back) — exercises BACK = 1.
        val base = emptyScore()
            .addNote("n1", tc(1, 3, 4), Pitch.C4, Duration.QUARTER, ties = listOf(RuntimeTieInfo(0, false)))
            .addNote("n2", tc(2, 0), Pitch.C4, Duration.QUARTER)
            .addNote("anchor", tc(3, 0), Pitch.G4, Duration.WHOLE)
        val previous = computeScore(base)
        val before = previous.computedEvents.getValue(EventId("n1")).pitchData.first().tieTarget
        assertEquals(EventId("n2"), before?.targetEventId, "precondition: n1 connects across barline to n2")
        assertFalse(before!!.isLetRing, "precondition: n1 is a real connecting tie")
        val edited = base.editPitch(EventId("n2"), Pitch.D4)
        val inc = assertIncrementalMatchesFull(previous, edited, interval(tc(2, 0), tc(2, 1, 4)))
        val after = inc.computedEvents.getValue(EventId("n1")).pitchData.first().tieTarget
        assertTrue(after?.isLetRing == true, "n1 cross-barline connecting tie should degrade to let-ring")
        assertEquals(null, after?.targetEventId, "n1 tie no longer points to a target")
    }

    // ---- beaming --------------------------------------------------------------------------------

    @Test
    fun beamGroupRecomputedOnEdit() {
        // Two eighths in beat 0 auto-beam together. Editing one keeps the group; result must match full.
        val base = emptyScore()
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.EIGHTH)
            .addNote("n2", tc(1, 1, 8), Pitch.D4, Duration.EIGHTH)
            .addNote("anchor", tc(3, 0), Pitch.G4, Duration.WHOLE)
        val previous = computeScore(base)
        val edited = base.editPitch(EventId("n1"), Pitch.E4)
        assertIncrementalMatchesFull(previous, edited, interval(tc(1, 0), tc(1, 1, 8)))
    }

    @Test
    fun sextupletAcrossTwoBeatsUsesOneAutomaticBeamGroup() {
        val duration = Duration(DurationBase.EIGHTH, tuplet = Tuplet(6, 4))
        val span = TupletSpan(tc(1, 1, 2), count = 6, beatUnit = DurationBase.EIGHTH)
        var score = emptyScore()
        repeat(6) { index ->
            score = score.addNote(
                "s$index",
                tc(1, index, 12),
                Pitch.C4,
                duration,
                tupletSpan = if (index == 0) span else null,
            )
        }

        val computed = computeScore(score)
        val beams = (0 until 6).map { computed.computedEvents.getValue(EventId("s$it")).beamInfo }
        assertTrue(beams.all { it != null })
        assertEquals(1, beams.map { it!!.groupId }.distinct().size)
        assertEquals(0, beams.first()!!.beamsLeft)
        assertEquals(0, beams.last()!!.beamsRight)
        assertTrue(beams.drop(1).all { it!!.beamsLeft == 1 })
        assertTrue(beams.dropLast(1).all { it!!.beamsRight == 1 })
    }

    // ---- tuplets --------------------------------------------------------------------------------

    @Test
    fun tupletRecomputedWhenEditingMember() {
        val span = TupletSpan(endTimeCode = tc(1, 3, 8), count = 3, beatUnit = DurationBase.EIGHTH)
        val base = emptyScore()
            .addNote("t1", tc(1, 0), Pitch.C4, Duration.EIGHTH, tupletSpan = span)
            .addNote("t2", tc(1, 1, 8), Pitch.D4, Duration.EIGHTH)
            .addNote("t3", tc(1, 2, 8), Pitch.E4, Duration.EIGHTH)
            .addNote("anchor", tc(3, 0), Pitch.G4, Duration.WHOLE)
        val previous = computeScore(base)
        assertEquals(EventId("t3"), previous.computedEvents.getValue(EventId("t1")).tupletInfo?.endEventId)
        val edited = base.editPitch(EventId("t2"), Pitch.F4)
        assertIncrementalMatchesFull(previous, edited, interval(tc(1, 1, 8), tc(1, 2, 8)))
    }

    // ---- implicit rests -------------------------------------------------------------------------

    @Test
    fun insertingNoteRemovesImplicitRest() {
        // m1 note, m2 empty (implicit rest), m3 anchor.
        val base = emptyScore()
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.WHOLE)
            .addNote("anchor", tc(3, 0), Pitch.G4, Duration.WHOLE)
        val previous = computeScore(base)
        assertTrue(previous.computedEvents.values.any { it.isRest && it.onset.measure == 2 }, "precondition: m2 rest")
        val edited = base.addNote("n2", tc(2, 0), Pitch.D4, Duration.WHOLE)
        val inc = assertIncrementalMatchesFull(previous, edited, interval(tc(2, 0), tc(3, 0)))
        assertFalse(inc.computedEvents.values.any { it.isRest && it.onset.measure == 2 }, "m2 rest should be gone")
    }

    @Test
    fun deletingOnlyNoteAddsImplicitRest() {
        val base = emptyScore()
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.WHOLE)
            .addNote("n2", tc(2, 0), Pitch.D4, Duration.WHOLE)
            .addNote("anchor", tc(3, 0), Pitch.G4, Duration.WHOLE)
        val previous = computeScore(base)
        val edited = base.deleteNote(EventId("n2"))
        val inc = assertIncrementalMatchesFull(previous, edited, interval(tc(2, 0), tc(3, 0)))
        assertTrue(inc.computedEvents.values.any { it.isRest && it.onset.measure == 2 }, "m2 rest should appear")
    }

    // ---- reference reuse ------------------------------------------------------------------------

    @Test
    fun unaffectedEventsAndNotationKeepSameReference() {
        val base = emptyScore()
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.WHOLE)
            .addNote("n2", tc(2, 0), Pitch.D4, Duration.WHOLE)
            .addNote("anchor", tc(4, 0), Pitch.G4, Duration.WHOLE)
        val previous = computeScore(base)
        val edited = base.editPitch(EventId("n1"), Pitch.E4)
        val inc = computeScoreIncremental(previous, edited, interval(tc(1, 0), tc(2, 0))).computed
        // The far-away anchor event (measure 4) is outside the window → reused by reference.
        assertSame(
            previous.computedEvents.getValue(EventId("anchor")),
            inc.computedEvents.getValue(EventId("anchor")),
            "untouched event should be the same instance",
        )
        // Notation never changes on the incremental path → reused wholesale.
        assertSame(previous.barlines, inc.barlines, "barlines list reused by reference")
        assertSame(previous.clefs, inc.clefs, "clefs list reused by reference")
    }

    // ---- slurs ----------------------------------------------------------------------------------

    @Test
    fun slurCountEditRecomputesSlurs() {
        // n1 opens a slur, n3 closes it. Editing pitch (not counts) keeps slurs; structure matches full.
        val base = emptyScore()
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.QUARTER, slurStarts = 1)
            .addNote("n2", tc(1, 1, 4), Pitch.D4, Duration.QUARTER)
            .addNote("n3", tc(1, 2, 4), Pitch.E4, Duration.QUARTER, slurEnds = 1)
            .addNote("anchor", tc(3, 0), Pitch.G4, Duration.WHOLE)
        val previous = computeScore(base)
        assertEquals(1, previous.slurs.size)
        val edited = base.editPitch(EventId("n2"), Pitch.F4)
        assertIncrementalMatchesFull(previous, edited, interval(tc(1, 1, 4), tc(1, 2, 4)))
    }

    // ---- structural fallback --------------------------------------------------------------------

    @Test
    fun tailExtendingInsertFallsBackToFull() {
        // Appending a note past the last measure changes the measure count → structureReflow + full.
        val base = emptyScore()
            .addNote("n1", tc(1, 0), Pitch.C4, Duration.WHOLE)
            .addNote("n2", tc(2, 0), Pitch.D4, Duration.WHOLE)
        val previous = computeScore(base)
        val edited = base.addNote("n3", tc(5, 0), Pitch.E4, Duration.WHOLE)
        val result = computeScoreIncremental(previous, edited, interval(tc(5, 0), tc(6, 0)))
        assertTrue(result.changeSet.structureReflow, "tail extension should report structureReflow")
        assertEquals(computeScore(edited).computedEvents, result.computed.computedEvents)
    }

    // ---- fuzz -----------------------------------------------------------------------------------

    @Test
    fun fuzzIncrementalMatchesFullAcrossEditChain() {
        val pitches = listOf(Pitch.C4, Pitch.D4, Pitch.E4, Pitch.F4, Pitch.G4, Pitch.A4, Pitch.B4,
            Pitch(0, 1), Pitch(3, 1)) // + C#, F#
        val beats = listOf(0 to 1, 1 to 4, 2 to 4, 3 to 4) // beat 0, 1/4, 2/4, 3/4
        val rng = Random(20260609)

        repeat(8) { trial ->
            // Random grid over measures 1..3; measure 4 anchor keeps the count fixed.
            var runtime = emptyScore()
            for (m in 1..3) for ((bn, bd) in beats) {
                if (rng.nextDouble() < 0.55) {
                    runtime = runtime.addNote("g$m-$bn-$bd", tc(m, bn, bd), pitches.random(rng), Duration.QUARTER)
                }
            }
            runtime = runtime.addNote("anchor", tc(4, 0), Pitch.G4, Duration.WHOLE)
            var previous = computeScore(runtime)

            repeat(15) {
                val m = rng.nextInt(1, 4)
                val (bn, bd) = beats.random(rng)
                val onset = tc(m, bn, bd)
                val tag = "g$m-$bn-$bd"
                val eventId = EventId(tag)
                val exists = runtime.voiceTracks.getValue(runtime.vtId()).events.toList().any { it.id == eventId }
                runtime = when {
                    !exists -> runtime.addNote(tag, onset, pitches.random(rng), Duration.QUARTER)
                    rng.nextDouble() < 0.4 -> runtime.deleteNote(eventId)
                    else -> runtime.editPitch(eventId, pitches.random(rng))
                }
                val editEnd = TimeCode.of(m, Fraction(bn, bd) + Fraction(1, 4)) // onset + a quarter
                previous = assertIncrementalMatchesFull(previous, runtime, interval(onset, editEnd))
            }
        }
    }
}
