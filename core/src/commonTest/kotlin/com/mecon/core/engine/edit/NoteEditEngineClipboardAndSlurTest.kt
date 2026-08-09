package com.mecon.core.engine.edit

import com.mecon.api.primitive.*
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeTieInfo
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.runtime.tracks.RuntimeVoiceTrack
import com.mecon.api.storage.RenderingProps
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.TupletSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoteEditEngineClipboardAndSlurTest {

    // ---- fixtures --------------------------------------------------------------------------------

    private fun emptyScore(): RuntimeScore =
        RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("T", TimeSignature.COMMON, KeySignature.C_MAJOR)))

    private fun RuntimeScore.ptId(): TrackId = pitchTracks.keys.first()
    private fun RuntimeScore.vtId(): TrackId = voiceTracks.keys.first()
    private fun RuntimeScore.staffId(): TrackId = staffTracks.keys.first()

    private fun tc(measure: Int, num: Int = 0, den: Int = 1) = TimeCode.of(measure, Fraction(num, den))

    private fun RuntimeScore.addNote(
        idTag: String, onset: TimeCode, pitch: Pitch?, duration: Duration,
    ): RuntimeScore {
        val pe = RuntimePitchEvent(
            id = EventId("p-$idTag"), onset = onset,
            pitches = if (pitch == null) emptyList() else listOf(pitch),
        )
        val ve = RuntimeVoiceEvent(
            id = EventId(idTag), onset = onset, pitchEvent = pe, duration = duration,
        )
        return addPitchEvent(ptId(), pe).addVoiceEvent(vtId(), ve)
    }

    private fun RuntimeScore.addChord(
        idTag: String,
        onset: TimeCode,
        pitches: List<Pitch>,
        duration: Duration,
        rendering: RenderingProps? = null,
    ): RuntimeScore {
        val pe = RuntimePitchEvent(
            id = EventId("p-$idTag"),
            onset = onset,
            pitches = pitches,
        )
        val ve = RuntimeVoiceEvent(
            id = EventId(idTag),
            onset = onset,
            pitchEvent = pe,
            duration = duration,
            rendering = rendering,
        )
        return addPitchEvent(ptId(), pe).addVoiceEvent(vtId(), ve)
    }

    private fun RuntimeScore.voice(): RuntimeVoiceTrack = getVoiceTrack(vtId())!!
    private fun RuntimeScore.events(): List<RuntimeVoiceEvent> = voice().events.toList()

    @Test
    fun copyPasteLongNoteSplitsAcrossBarline() {
        val base = emptyScore()
            .addChord("h", tc(1, 0), listOf(Pitch.C4), Duration.HALF)

        val clipboard = NoteEditEngine.copyNotes(
            base,
            listOf(NoteEditEngine.CopyTarget(base.vtId(), EventId("h"))),
        )!!
        val pasted = NoteEditEngine.pasteNotes(
            base,
            clipboard,
            NoteEditEngine.PasteTarget(base.vtId(), tc(1, 3, 4)),
        )!!

        val pastedNotes = pasted.pastedEventIds.map { id ->
            pasted.score.events().single { it.id == id }
        }.sortedBy { it.onset }
        assertEquals(2, pastedNotes.size)
        assertEquals(tc(1, 3, 4), pastedNotes[0].onset)
        assertEquals(Duration.QUARTER, pastedNotes[0].duration)
        assertEquals(listOf(RuntimeTieInfo(0, isLetRing = false)), pastedNotes[0].ties)
        assertEquals(tc(2, 0), pastedNotes[1].onset)
        assertEquals(Duration.QUARTER, pastedNotes[1].duration)
        assertEquals(emptyList(), pastedNotes[1].ties)
    }

    @Test
    fun addAndDeleteSlurUsesStableFirstClassObject() {
        val base = emptyScore()
            .addNote("a", tc(1, 0), Pitch.C4, Duration.QUARTER)
            .addNote("b", tc(1, 1, 4), Pitch.E4, Duration.QUARTER)
        val added = NoteEditEngine.addSlurs(
            base,
            listOf(NoteEditEngine.SlurTarget(base.vtId(), EventId("a"), EventId("b"))),
        )!!

        val slur = added.score.voice().slurs.single()
        assertEquals(EventId("a"), slur.startEventId)
        assertEquals(EventId("b"), slur.endEventId)
        assertEquals(setOf(slur.id), added.slurIds)

        val deleted = NoteEditEngine.deleteSlurs(added.score, setOf(slur.id))!!
        assertTrue(deleted.score.voice().slurs.isEmpty())
    }

    @Test
    fun editingSlurPromotesLegacyCountsBeforeAddingNewSlur() {
        var legacy = emptyScore()
            .addNote("a", tc(1, 0), Pitch.C4, Duration.QUARTER)
            .addNote("b", tc(1, 1, 4), Pitch.E4, Duration.QUARTER)
            .addNote("c", tc(1, 1, 2), Pitch.G4, Duration.QUARTER)
        val replacements = legacy.events().associate { event ->
            event.id to when (event.id) {
                EventId("a") -> event.copy(slurStarts = 1)
                EventId("b") -> event.copy(slurEnds = 1)
                else -> event
            }
        }
        replacements.forEach { (id, event) ->
            legacy = legacy.removeVoiceEvent(legacy.vtId(), id).addVoiceEvent(legacy.vtId(), event)
        }

        val edited = NoteEditEngine.addSlurs(
            legacy,
            listOf(NoteEditEngine.SlurTarget(legacy.vtId(), EventId("b"), EventId("c"))),
        )!!.score

        assertEquals(2, edited.voice().slurs.size)
        assertTrue(edited.events().all { it.slurStarts == 0 && it.slurEnds == 0 })
        assertTrue(edited.voice().slurs.any { it.startEventId == EventId("a") && it.endEventId == EventId("b") })
        assertTrue(edited.voice().slurs.any { it.startEventId == EventId("b") && it.endEventId == EventId("c") })
    }

    @Test
    fun copyPasteCarriesSlurOnlyWhenBothEndpointsAreCopied() {
        val notes = emptyScore()
            .addNote("a", tc(1, 0), Pitch.C4, Duration.QUARTER)
            .addNote("b", tc(1, 1, 4), Pitch.E4, Duration.QUARTER)
        val source = NoteEditEngine.addSlurs(
            notes,
            listOf(NoteEditEngine.SlurTarget(notes.vtId(), EventId("a"), EventId("b"))),
        )!!.score

        val partial = NoteEditEngine.copyNotes(
            source,
            listOf(NoteEditEngine.CopyTarget(source.vtId(), EventId("a"))),
        )!!
        assertTrue(partial.slurs.isEmpty())

        val complete = NoteEditEngine.copyNotes(
            source,
            listOf(
                NoteEditEngine.CopyTarget(source.vtId(), EventId("a")),
                NoteEditEngine.CopyTarget(source.vtId(), EventId("b")),
            ),
        )!!
        assertEquals(1, complete.slurs.size)

        val pasted = NoteEditEngine.pasteNotes(
            source,
            complete,
            NoteEditEngine.PasteTarget(source.vtId(), tc(2, 0)),
        )!!
        val pastedIds = pasted.pastedEventIds.toSet()
        val pastedSlur = pasted.score.voice().slurs.single { slur ->
            slur.startEventId in pastedIds && slur.endEventId in pastedIds
        }
        assertTrue(pastedSlur.startEventId in pastedIds)
        assertTrue(pastedSlur.endEventId in pastedIds)
    }

    @Test
    fun pasteTupletRejectsGroupCrossingBarline() {
        val tupletDuration = Duration(DurationBase.EIGHTH, tuplet = Tuplet(3, 2))
        val withoutSpan = emptyScore()
            .addNote("a", tc(1, 0), Pitch.C4, tupletDuration)
            .addNote("b", tc(1, 1, 12), Pitch.D4, tupletDuration)
            .addNote("c", tc(1, 1, 6), Pitch.E4, tupletDuration)
        val start = withoutSpan.events().single { it.id == EventId("a") }.copy(
            tupletSpan = TupletSpan(tc(1, 1, 4), count = 3, beatUnit = DurationBase.EIGHTH),
        )
        val source = withoutSpan
            .removeVoiceEvent(withoutSpan.vtId(), start.id)
            .addVoiceEvent(withoutSpan.vtId(), start)
        val clipboard = NoteEditEngine.copyNotes(
            source,
            listOf(
                NoteEditEngine.CopyTarget(source.vtId(), EventId("a")),
                NoteEditEngine.CopyTarget(source.vtId(), EventId("b")),
                NoteEditEngine.CopyTarget(source.vtId(), EventId("c")),
            ),
        )!!

        assertTrue(
            NoteEditEngine.pasteNotesWithStatus(source, clipboard, NoteEditEngine.PasteTarget(source.vtId(), tc(1, 7, 8)))
                is NoteEditEngine.PasteOutcome.TupletCrossesBarline,
        )
        assertNull(NoteEditEngine.pasteNotes(source, clipboard, NoteEditEngine.PasteTarget(source.vtId(), tc(1, 7, 8))))
    }
}
