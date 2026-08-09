package com.mecon.core.engine.edit

import com.mecon.api.primitive.*
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeTieInfo
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.runtime.tracks.RuntimeVoiceTrack
import com.mecon.api.storage.StorageScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests for [NoteEditEngine.transpose] — drag-to-transpose's pure edit layer. */
class NoteTransposeEngineTest {

    private fun scoreInKey(key: KeySignature): RuntimeScore =
        RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("T", TimeSignature.COMMON, key)))

    private fun RuntimeScore.ptId(): TrackId = pitchTracks.keys.first()
    private fun RuntimeScore.vtId(): TrackId = voiceTracks.keys.first()
    private fun tc(measure: Int, num: Int = 0, den: Int = 1) = TimeCode.of(measure, Fraction(num, den))
    private fun RuntimeScore.voice(): RuntimeVoiceTrack = getVoiceTrack(vtId())!!
    private fun RuntimeScore.events(): List<RuntimeVoiceEvent> = voice().events.toList()
    private fun RuntimeScore.event(id: String): RuntimeVoiceEvent = events().first { it.id == EventId(id) }

    private fun RuntimeScore.addChord(
        idTag: String, onset: TimeCode, pitches: List<Pitch>, duration: Duration,
        ties: List<RuntimeTieInfo> = emptyList(),
    ): RuntimeScore {
        val pe = RuntimePitchEvent(id = EventId("p-$idTag"), onset = onset, pitches = pitches)
        val ve = RuntimeVoiceEvent(
            id = EventId(idTag), onset = onset, pitchEvent = pe, duration = duration, ties = ties,
        )
        return addPitchEvent(ptId(), pe).addVoiceEvent(vtId(), ve)
    }

    private fun target(score: RuntimeScore, id: String, pitchIndices: Set<Int>? = null) =
        NoteEditEngine.TransposeTarget(score.vtId(), EventId(id), pitchIndices)

    // ---- basic diatonic moves -------------------------------------------------------------------

    @Test
    fun movesUpAndDownDiatonically() {
        val base = scoreInKey(KeySignature.C_MAJOR).addChord("a", tc(1), listOf(Pitch.C4), Duration.QUARTER)

        val up = NoteEditEngine.transpose(base, listOf(target(base, "a")), +1)!!
        assertEquals(Pitch.D4, up.score.event("a").pitches.single())

        val down = NoteEditEngine.transpose(base, listOf(target(base, "a")), -2)!!
        // C4 down two steps = A3 = Pitch(diatonicSteps = -2)
        assertEquals(Pitch(-2, 0), down.score.event("a").pitches.single())
    }

    // ---- temporary accidental dropped, key spelling kept ----------------------------------------

    @Test
    fun dropsTemporaryAccidentalOnMove() {
        // C#4 (a temporary sharp in C major) moved up one diatonic step → D natural.
        val base = scoreInKey(KeySignature.C_MAJOR).addChord("a", tc(1), listOf(Pitch(0, 1)), Duration.QUARTER)
        val up = NoteEditEngine.transpose(base, listOf(target(base, "a")), +1)!!
        assertEquals(Pitch(1, 0), up.score.event("a").pitches.single())
    }

    @Test
    fun spellsWithKeySignatureDefault() {
        // G major (F#). E4 moved up one step lands on F → must be F# from the key signature.
        val base = scoreInKey(KeySignature.G_MAJOR).addChord("a", tc(1), listOf(Pitch.E4), Duration.QUARTER)
        val up = NoteEditEngine.transpose(base, listOf(target(base, "a")), +1)!!
        assertEquals(Pitch(3, 1), up.score.event("a").pitches.single()) // F#4
    }

    // ---- chord: whole vs subset -----------------------------------------------------------------

    @Test
    fun movesWholeChordTogether() {
        val base = scoreInKey(KeySignature.C_MAJOR)
            .addChord("a", tc(1), listOf(Pitch.C4, Pitch.E4, Pitch.G4), Duration.QUARTER)
        val up = NoteEditEngine.transpose(base, listOf(target(base, "a")), +1)!!
        assertEquals(listOf(Pitch.D4, Pitch.F4, Pitch.A4), up.score.event("a").pitches)
        // Whole-event move → re-select the event (null pitch indices), not individual noteheads.
        assertEquals(listOf(NoteEditEngine.MovedEvent(EventId("a"), null)), up.movedEvents)
    }

    @Test
    fun movesOnlySelectedChordPitch() {
        val base = scoreInKey(KeySignature.C_MAJOR)
            .addChord("a", tc(1), listOf(Pitch.C4, Pitch.E4, Pitch.G4), Duration.QUARTER)
        // Move only the middle pitch (E4) up one step → F4; chord re-sorts to C4, F4, G4.
        val up = NoteEditEngine.transpose(base, listOf(target(base, "a", setOf(1))), +1)!!
        assertEquals(listOf(Pitch.C4, Pitch.F4, Pitch.G4), up.score.event("a").pitches)
        // Partial move → re-select only the moved notehead at its NEW index (F4 is index 1).
        assertEquals(listOf(NoteEditEngine.MovedEvent(EventId("a"), setOf(1))), up.movedEvents)
    }

    // ---- ties: dropped on moved pitch, re-indexed on survivors ----------------------------------

    @Test
    fun dropsTieOnMovedPitchAndReindexesSurvivors() {
        // Chord [C4, E4] with a tie on the higher pitch (index 1). Move only C4 (index 0) up to D4.
        // New order [D4, E4]; the surviving E4 tie must remap to its new index (still 1 here).
        val base = scoreInKey(KeySignature.C_MAJOR).addChord(
            "a", tc(1), listOf(Pitch.C4, Pitch.E4), Duration.QUARTER,
            ties = listOf(RuntimeTieInfo(1, isLetRing = false)),
        )
        val moved = NoteEditEngine.transpose(base, listOf(target(base, "a", setOf(0))), +1)!!.score.event("a")
        assertEquals(listOf(Pitch.D4, Pitch.E4), moved.pitches)
        assertEquals(listOf(RuntimeTieInfo(1, false)), moved.ties)

        // Now move the tied pitch itself → its tie is dropped.
        val movedTied = NoteEditEngine.transpose(base, listOf(target(base, "a", setOf(1))), +1)!!.score.event("a")
        assertTrue(movedTied.ties.isEmpty())
    }

    // ---- multi-target / interval reporting ------------------------------------------------------

    @Test
    fun reportsOneIntervalPerTouchedMeasure() {
        val base = scoreInKey(KeySignature.C_MAJOR)
            .addChord("a", tc(1), listOf(Pitch.C4), Duration.QUARTER)
            .addChord("b", tc(2), listOf(Pitch.D4), Duration.QUARTER)
        val single = NoteEditEngine.transpose(base, listOf(target(base, "a")), +1)!!
        assertEquals(1, single.intervals.size)

        val both = NoteEditEngine.transpose(base, listOf(target(base, "a"), target(base, "b")), +1)!!
        assertEquals(2, both.intervals.size)
        assertEquals(setOf(EventId("a"), EventId("b")), both.eventIds.toSet())
    }

    // ---- no-ops ---------------------------------------------------------------------------------

    @Test
    fun clampsDeltaToStayInMidiRange() {
        // A high (but valid) note transposed far up must be clamped so the result never exceeds MIDI 127.
        val high = Pitch.fromMidi(120)
        val base = scoreInKey(KeySignature.C_MAJOR).addChord("a", tc(1), listOf(high), Duration.QUARTER)
        val up = NoteEditEngine.transpose(base, listOf(target(base, "a")), +20)
        // Either clamped to a smaller move (still in range) or a no-op; never an out-of-range pitch.
        if (up != null) {
            assertTrue(up.score.event("a").pitches.all { it.midiNumber in 0..127 })
        }

        // A note already at the very top can't move up at all → no-op (null), not a crash.
        val atTop = scoreInKey(KeySignature.C_MAJOR).addChord("b", tc(1), listOf(Pitch.fromMidi(127)), Duration.QUARTER)
        assertNull(NoteEditEngine.transpose(atTop, listOf(target(atTop, "b")), +3))
    }

    @Test
    fun zeroDeltaAndUnknownAndRestAreNoOps() {
        val base = scoreInKey(KeySignature.C_MAJOR).addChord("a", tc(1), listOf(Pitch.C4), Duration.QUARTER)
        assertNull(NoteEditEngine.transpose(base, listOf(target(base, "a")), 0))
        assertNull(NoteEditEngine.transpose(base, listOf(target(base, "missing")), +1))
        assertNull(NoteEditEngine.transpose(base, emptyList(), +1))

        val withRest = scoreInKey(KeySignature.C_MAJOR).addChord("r", tc(1), emptyList(), Duration.QUARTER)
        assertNull(NoteEditEngine.transpose(withRest, listOf(target(withRest, "r")), +1))
    }
}
