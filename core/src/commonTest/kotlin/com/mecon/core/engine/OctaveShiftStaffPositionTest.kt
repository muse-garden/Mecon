package com.mecon.core.engine

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.OctaveShiftType
import com.mecon.api.storage.events.StaffAttachmentPlacement
import com.mecon.api.storage.events.StorageOctaveShiftEnd
import com.mecon.api.storage.events.StorageOctaveShiftStart
import com.mecon.api.storage.events.StorageStaffAttachment
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies octave-shift bracket (8va / 8vb) effects on note staff position.
 *
 * Convention: pitches are stored at their sounding position. The bracket shifts
 * the written (displayed) position by one octave (7 diatonic steps):
 *   8va (OTTAVA):      written = sounding − octave  →  staffPosition offset = −7
 *   8vb (OTTAVA_BASSA): written = sounding + octave →  staffPosition offset = +7
 *
 * The bracket span is [startOnset, endOnset) — the end onset is exclusive.
 */
class OctaveShiftStaffPositionTest {

    private fun bracket(
        startOnset: TimeCode,
        endOnset: TimeCode,
        shiftType: OctaveShiftType
    ): List<StorageStaffAttachment> {
        val startId = EventId("osa-${shiftType.name}")
        val endId   = EventId("ose-${shiftType.name}")
        val placement = if (shiftType == OctaveShiftType.OTTAVA)
            StaffAttachmentPlacement.ABOVE else StaffAttachmentPlacement.BELOW
        return listOf(
            StorageOctaveShiftStart(id = startId, onset = startOnset, shiftType = shiftType,
                endEventId = endId, placement = placement),
            StorageOctaveShiftEnd(id = endId, onset = endOnset, placement = placement)
        )
    }

    private fun buildAndCompute(
        attachments: List<StorageStaffAttachment>,
        vararg notes: Pair<TimeCode, Pitch>
    ): List<Int> {
        val base = StorageScore.create(StorageScore.CreationOptions("OctaveShiftTest"))
        val staffId = base.staffTracks.keys.first()
        val updatedStaff = base.staffTracks.getValue(staffId).copy(attachments = attachments)
        val storage = base.copy(staffTracks = base.staffTracks + (staffId to updatedStaff))

        var runtime = RuntimeScore.fromStorage(storage)
        val pitchTrackId = runtime.pitchTracks.keys.first()
        val voiceTrackId = runtime.voiceTracks.keys.first()

        for ((onset, pitch) in notes) {
            val pe = RuntimePitchEvent.single(onset = onset, pitch = pitch)
            runtime = runtime.addPitchEvent(pitchTrackId, pe)
            runtime = runtime.addVoiceEvent(
                voiceTrackId,
                RuntimeVoiceEvent.create(onset = onset, pitchEvent = pe, duration = Duration.QUARTER)
            )
        }

        return computeScore(runtime).allEventsSorted()
            .filter { !it.isRest }  // skip implicit whole-measure rests in empty measures
            .map { it.pitchData.first().staffPosition }
    }

    // ── Baseline (no bracket) ─────────────────────────────────────────────────

    // E5: diatonicSteps=9, treble position = 9−6 = 3 (4th space)
    @Test
    fun e5WithNoShift_hasBaselinePosition() {
        val positions = buildAndCompute(
            attachments = emptyList(),
            notes = arrayOf(TimeCode.of(1, Fraction.ZERO) to Pitch(9, 0))
        )
        assertEquals(3, positions.single())
    }

    // ── 8va (OTTAVA) ──────────────────────────────────────────────────────────

    // E5 under 8va: displayed as E4 (−7 diatonic) → position = 3−7 = −4 (bottom line)
    @Test
    fun e5Under8va_displaysOneOctaveLower() {
        val m1 = TimeCode.of(1, Fraction.ZERO)
        val m2 = TimeCode.of(2, Fraction.ZERO)
        val positions = buildAndCompute(
            attachments = bracket(m1, m2, OctaveShiftType.OTTAVA),
            notes = arrayOf(m1 to Pitch(9, 0))
        )
        assertEquals(-4, positions.single())
    }

    // G5 under 8va: diatonicSteps=11, normal=11−6=5; shifted=5−7=−2 (2nd line)
    @Test
    fun g5Under8va_displaysOneOctaveLower() {
        val m1 = TimeCode.of(1, Fraction.ZERO)
        val m2 = TimeCode.of(2, Fraction.ZERO)
        val positions = buildAndCompute(
            attachments = bracket(m1, m2, OctaveShiftType.OTTAVA),
            notes = arrayOf(m1 to Pitch(11, 0))
        )
        assertEquals(-2, positions.single())
    }

    // Note at end onset of 8va bracket is NOT shifted (exclusive end).
    @Test
    fun noteAtBracketEndOnset_isNotShifted() {
        val m1 = TimeCode.of(1, Fraction.ZERO)
        val m2 = TimeCode.of(2, Fraction.ZERO)
        val positions = buildAndCompute(
            attachments = bracket(m1, m2, OctaveShiftType.OTTAVA),
            notes = arrayOf(m2 to Pitch(9, 0))
        )
        assertEquals(3, positions.single(), "End onset is exclusive — no shift applied")
    }

    // ── 8vb (OTTAVA_BASSA) ────────────────────────────────────────────────────

    // C3: diatonicSteps=−7, treble position = −7−6 = −13 (many ledger lines below)
    // Under 8vb: displayed as C4 (+7) → position = −13+7 = −6 (C4, one ledger below treble)
    @Test
    fun c3Under8vb_displaysOneOctaveHigher() {
        val m1 = TimeCode.of(1, Fraction.ZERO)
        val m2 = TimeCode.of(2, Fraction.ZERO)
        val positions = buildAndCompute(
            attachments = bracket(m1, m2, OctaveShiftType.OTTAVA_BASSA),
            notes = arrayOf(m1 to Pitch(-7, 0))
        )
        assertEquals(-6, positions.single())
    }

    // ── Boundary & multiple notes ─────────────────────────────────────────────

    // Notes inside the bracket are shifted; notes outside (after end) are not.
    @Test
    fun onlyNotesInsideBracketAreShifted() {
        val e5 = Pitch(9, 0)
        val m1 = TimeCode.of(1, Fraction.ZERO)
        val m2 = TimeCode.of(2, Fraction.ZERO)
        val m3 = TimeCode.of(3, Fraction.ZERO)
        val positions = buildAndCompute(
            attachments = bracket(m1, m2, OctaveShiftType.OTTAVA),
            notes = arrayOf(
                m1 to e5,  // inside — shifted
                m2 to e5,  // at end onset — not shifted
                m3 to e5   // after bracket — not shifted
            )
        )
        assertEquals(-4, positions[0], "M1: inside bracket → shifted")
        assertEquals(3,  positions[1], "M2: at end onset → not shifted")
        assertEquals(3,  positions[2], "M3: after bracket → not shifted")
    }

    // Consecutive brackets without gap: first note in each bracket uses the right shift.
    @Test
    fun consecutiveBrackets_eachNoteUsesCorrectShift() {
        val e5 = Pitch(9, 0)
        val c3 = Pitch(-7, 0)
        val m1 = TimeCode.of(1, Fraction.ZERO)
        val m2 = TimeCode.of(2, Fraction.ZERO)
        val m3 = TimeCode.of(3, Fraction.ZERO)

        val attachments = bracket(m1, m2, OctaveShiftType.OTTAVA) +
                          bracket(m2, m3, OctaveShiftType.OTTAVA_BASSA)

        val positions = buildAndCompute(
            attachments = attachments,
            notes = arrayOf(
                m1 to e5,  // 8va → E5 displayed as E4 → −4
                m2 to c3   // 8vb → C3 displayed as C4 → −6
            )
        )
        assertEquals(-4, positions[0], "8va note")
        assertEquals(-6, positions[1], "8vb note")
    }
}
