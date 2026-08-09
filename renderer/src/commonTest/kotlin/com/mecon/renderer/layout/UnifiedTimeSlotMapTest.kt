package com.mecon.renderer.layout

import com.mecon.api.computed.ComputedPitchData
import com.mecon.api.primitive.BarlineType
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.renderer.elements.BarlineElement
import com.mecon.renderer.elements.NoteElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UnifiedTimeSlotMapTest {

    private fun note(time: TimeCode): NoteElement = NoteElement(
        time = time,
        staffIndex = 0,
        eventId = EventId("n_$time"),
        trackId = TrackId("t1"),
        duration = Duration.WHOLE,
        measureNumber = time.measure,
        pitchData = listOf(
            ComputedPitchData(
                pitch = Pitch(60),
                midiPitch = 60,
                staffPosition = 0,
                effectiveAccidental = null,
                needsLedgerLine = false,
            )
        ),
        isRest = false,
        beamInfo = null,
    )

    private fun barline(time: TimeCode): BarlineElement = BarlineElement(
        time = time,
        type = BarlineType.SINGLE,
        measureNumber = time.measure,
        geometryList = emptyList(),
    )

    /** lastNoteBefore skips a barline-only slot that sits between the note and the end time. */
    @Test
    fun lastNoteBeforeSkipsTrailingBarlineSlot() {
        val noteTime = TimeCode.of(3, Fraction.ZERO)
        val barlineTime = TimeCode.of(3, Fraction(9, 8)) // barline-only slot after the note
        val endTime = TimeCode.of(4, Fraction.ZERO)

        val map = UnifiedTimeSlotMap.fromEvents(
            listOf(note(noteTime), barline(barlineTime))
        )

        val result = map.lastNoteBefore(endTime)
        assertEquals(noteTime, result?.time, "should return the note slot, not the barline slot")
    }

    @Test
    fun lastNoteBeforeReturnsNullWhenNoNotePrecedesTime() {
        val map = UnifiedTimeSlotMap.fromEvents(
            listOf(note(TimeCode.of(5, Fraction.ZERO)))
        )
        assertNull(map.lastNoteBefore(TimeCode.of(2, Fraction.ZERO)))
    }
}
