package com.mecon.theory

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.orderedStaffs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FixedVoiceScoreTest {
    @Test
    fun loadsFourPartKeyboardScoreAndQueriesVoiceContext() {
        val runtime = RuntimeScore.fromStorage(fixedVoiceStorageScore())
        val layout = FixedVoiceLayout.fourPartKeyboard(runtime)
        val fixed = FixedVoiceScore.load(runtime, layout)
        val soprano = fixed.voices.first { it.role == FixedVoiceRole.SOPRANO }
        val sopranoEvents = fixed.eventsByVoice.getValue(soprano.id)
        val firstSoprano = sopranoEvents.first()

        assertEquals(Pitch.fromName("C5"), firstSoprano.pitch)
        assertEquals(sopranoEvents, fixed.eventsForVoice(soprano))
        assertEquals(sopranoEvents, fixed.noteEventsForVoice(soprano))
        assertNull(fixed.previousInVoice(firstSoprano))
        assertEquals(Pitch.fromName("D5"), fixed.nextInVoice(firstSoprano)?.pitch)
        assertEquals(
            listOf(Pitch.fromName("E4"), Pitch.fromName("G3"), Pitch.fromName("C3")),
            fixed.simultaneousNotes(firstSoprano).map { it.pitch },
        )
        assertEquals(
            listOf(Pitch.fromName("G3"), Pitch.fromName("C3")),
            fixed.simultaneousNotes(firstSoprano, includeSameStaff = false).map { it.pitch },
        )
        assertEquals(
            listOf(Pitch.fromName("D5"), Pitch.fromName("G3"), Pitch.fromName("C3")),
            fixed.notesSoundingAt(TimeCode.of(1, Fraction.QUARTER)).map { it.pitch },
        )
    }

    @Test
    fun rejectsChordInsideFixedVoice() {
        val runtime = RuntimeScore.fromStorage(
            fixedVoiceStorageScore(
                sopranoPitches = listOf(listOf(Pitch.fromName("C5"), Pitch.fromName("E5"))),
            )
        )
        val error = assertFailsWith<FixedVoiceScoreException> {
            FixedVoiceScore.load(runtime, FixedVoiceLayout.fourPartKeyboard(runtime))
        }

        assertEquals(FixedVoiceDiagnosticCode.CHORD_IN_MONOPHONIC_VOICE, error.diagnostics.single().code)
    }

    @Test
    fun rejectsUnexpectedVoiceCountOnStaff() {
        val runtime = RuntimeScore.fromStorage(fixedVoiceStorageScore())
        val trebleStaff = runtime.orderedStaffs().first()
        val layout = FixedVoiceLayout(
            listOf(FixedVoiceStaffLayout(trebleStaff.id, listOf(FixedVoiceRole.SOPRANO)))
        )
        val error = assertFailsWith<FixedVoiceScoreException> {
            FixedVoiceScore.load(runtime, layout)
        }

        assertEquals(FixedVoiceDiagnosticCode.STAFF_VOICE_COUNT_MISMATCH, error.diagnostics.single().code)
    }
}
