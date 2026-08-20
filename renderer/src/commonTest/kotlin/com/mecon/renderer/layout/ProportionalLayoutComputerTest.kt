package com.mecon.renderer.layout

import com.mecon.api.computed.ComputedPitchData
import com.mecon.api.primitive.*
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.geometry.GlyphGeometry
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.elements.BarlineElement
import com.mecon.renderer.elements.NoteBodyElement
import com.mecon.renderer.elements.NoteheadRenderInfo
import com.mecon.renderer.elements.NoteElement
import com.mecon.renderer.smufl.SmuflGlyphs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProportionalLayoutComputerTest {

    private val config = RenderLayoutConfig.DEFAULT
    private val computer = ProportionalLayoutComputer.default(config)

    private fun createNoteEvent(
        time: TimeCode,
        duration: Duration,
        staffIndex: Int = 0,
        trackId: TrackId = TrackId("track1"),
        eventId: String = "event_${time}_$staffIndex"
    ): NoteElement = NoteElement(
        time = time,
        staffIndex = staffIndex,
        eventId = EventId(eventId),
        trackId = trackId,
        duration = duration,
        measureNumber = time.measure,
        pitchData = listOf(
            ComputedPitchData(
                pitch = Pitch(60),
                midiPitch = 60,
                staffPosition = 0,
                effectiveAccidental = null,
                needsLedgerLine = false
            )
        ),
        isRest = false,
        beamInfo = null
    )

    private fun createBarlineEvent(
        time: TimeCode,
        measureNumber: Int = time.measure
    ): BarlineElement = BarlineElement(
        time = time,
        type = BarlineType.SINGLE,
        measureNumber = measureNumber,
        geometryList = emptyList()  // Test uses empty geometry - just testing layout positions
    )

    @Test
    fun testSingleEventPositioning() {
        val event = createNoteEvent(
            time = TimeCode.of(1, Fraction.ZERO),
            duration = Duration.QUARTER
        )

        val result = computer.computeXPositions(listOf(event), StaffSpace(10f))

        assertEquals(1, result.size)
        // Result is the right edge position: startX + eventWidth
        val expectedRightEdge = StaffSpace(10f + event.minimumWidth.value)
        assertEquals(expectedRightEdge, result[event.time])
    }

    @Test
    fun testTwoEventsSequential() {
        val event1 = createNoteEvent(
            time = TimeCode.of(1, Fraction.ZERO),
            duration = Duration.QUARTER,
            eventId = "event1"
        )
        val event2 = createNoteEvent(
            time = TimeCode.of(1, Fraction.QUARTER),
            duration = Duration.QUARTER,
            eventId = "event2"
        )

        val result = computer.computeXPositions(listOf(event1, event2), StaffSpace(10f))

        assertEquals(2, result.size)
        val x1 = result[event1.time]!!
        val x2 = result[event2.time]!!
        assertTrue(x2 > x1, "Second event should be to the right of first")
    }

    @Test
    fun accidentalOverhangDoesNotLeaveTrailingBlankSpace() {
        val t0 = TimeCode.of(1, Fraction.ZERO)
        val t1 = TimeCode.of(1, Fraction.QUARTER)
        val t2 = TimeCode.of(1, Fraction.HALF)
        val plain0 = createNoteEvent(t0, Duration.QUARTER, eventId = "plain0")
            .copy(noteBody = noteBody(left = 0f, right = 1.5f))
        val altered = createNoteEvent(t1, Duration.QUARTER, eventId = "altered")
            .copy(noteBody = noteBody(left = -4f, right = 1.5f))
        val plain2 = createNoteEvent(t2, Duration.QUARTER, eventId = "plain2")
            .copy(noteBody = noteBody(left = 0f, right = 1.5f))

        val result = computer.computeXPositions(listOf(plain0, altered, plain2), StaffSpace.ZERO)
        val baseline = computer.computeXPositions(
            listOf(
                plain0,
                altered.copy(noteBody = noteBody(left = 0f, right = 1.5f)),
                plain2,
            ),
            StaffSpace.ZERO,
        )

        val trailingGap = result.getValue(t2).value - result.getValue(t1).value
        val baselineTrailingGap = baseline.getValue(t2).value - baseline.getValue(t1).value
        assertEquals(
            baselineTrailingGap,
            trailingGap,
            absoluteTolerance = 0.0001f,
            message = "a left accidental overhang must reserve room before its note, not after it",
        )
        assertEquals(StaffSpace(1.5f), altered.minimumWidth)
        assertEquals(StaffSpace(4f), altered.leftOverhang)
    }

    @Test
    fun testSimultaneousEventsAligned() {
        val time = TimeCode.of(1, Fraction.ZERO)
        val event1 = createNoteEvent(
            time = time,
            duration = Duration.QUARTER,
            staffIndex = 0,
            trackId = TrackId("track1"),
            eventId = "event1"
        )
        val event2 = createNoteEvent(
            time = time,
            duration = Duration.HALF,
            staffIndex = 1,
            trackId = TrackId("track2"),
            eventId = "event2"
        )

        val result = computer.computeXPositions(listOf(event1, event2), StaffSpace(10f))

        // Both events at the same time should have the same X position (right edge)
        assertEquals(1, result.size, "Should have one entry for shared time")
        // Result is the maximum right edge of all events at this time
        val maxWidth = maxOf(event1.minimumWidth, event2.minimumWidth)
        val expectedRightEdge = StaffSpace(10f + maxWidth.value)
        assertEquals(expectedRightEdge, result[time])
    }

    @Test
    fun testLongerDurationGivesMoreSpace() {
        // Create two sequences: one with quarter notes, one with half notes
        val quarterEvent1 = createNoteEvent(
            time = TimeCode.of(1, Fraction.ZERO),
            duration = Duration.QUARTER,
            eventId = "q1"
        )
        val quarterEvent2 = createNoteEvent(
            time = TimeCode.of(1, Fraction.QUARTER),
            duration = Duration.QUARTER,
            eventId = "q2"
        )

        val halfEvent1 = createNoteEvent(
            time = TimeCode.of(1, Fraction.ZERO),
            duration = Duration.HALF,
            eventId = "h1"
        )
        val halfEvent2 = createNoteEvent(
            time = TimeCode.of(1, Fraction.HALF),
            duration = Duration.HALF,
            eventId = "h2"
        )

        val quarterResult = computer.computeXPositions(listOf(quarterEvent1, quarterEvent2), StaffSpace.ZERO)
        val halfResult = computer.computeXPositions(listOf(halfEvent1, halfEvent2), StaffSpace.ZERO)

        val quarterGap = quarterResult[quarterEvent2.time]!!.value - quarterResult[quarterEvent1.time]!!.value
        val halfGap = halfResult[halfEvent2.time]!!.value - halfResult[halfEvent1.time]!!.value

        assertTrue(
            halfGap > quarterGap,
            "Half note gap ($halfGap) should be greater than quarter note gap ($quarterGap)"
        )
    }

    @Test
    fun testBarlineIsSystemWideVoice() {
        val noteEvent = createNoteEvent(
            time = TimeCode.of(1, Fraction.ZERO),
            duration = Duration.QUARTER
        )
        val barlineEvent = createBarlineEvent(
            time = TimeCode.of(1, Fraction.QUARTER)
        )

        val result = computer.computeXPositions(listOf(noteEvent, barlineEvent), StaffSpace(10f))

        assertEquals(2, result.size)
        assertTrue(result[barlineEvent.time]!! > result[noteEvent.time]!!)
    }

    @Test
    fun testMultipleVoicesWithDifferentRhythms() {
        // Voice 1: quarter notes
        // Voice 2: half notes
        // They should align at their shared time points
        val voice1Track = TrackId("voice1")
        val voice2Track = TrackId("voice2")

        val events = listOf(
            // Voice 1: beat 0, quarter
            createNoteEvent(
                time = TimeCode.of(1, Fraction.ZERO),
                duration = Duration.QUARTER,
                trackId = voice1Track,
                eventId = "v1_1"
            ),
            // Voice 2: beat 0, half
            createNoteEvent(
                time = TimeCode.of(1, Fraction.ZERO),
                duration = Duration.HALF,
                trackId = voice2Track,
                eventId = "v2_1"
            ),
            // Voice 1: beat 1/4, quarter
            createNoteEvent(
                time = TimeCode.of(1, Fraction.QUARTER),
                duration = Duration.QUARTER,
                trackId = voice1Track,
                eventId = "v1_2"
            ),
            // Voice 2: beat 1/2, half
            createNoteEvent(
                time = TimeCode.of(1, Fraction.HALF),
                duration = Duration.HALF,
                trackId = voice2Track,
                eventId = "v2_2"
            )
        )

        val result = computer.computeXPositions(events, StaffSpace.ZERO)

        // Verify all times are positioned
        assertEquals(3, result.size)  // beat 0, 1/4, 1/2

        // Verify ordering
        val x0 = result[TimeCode.of(1, Fraction.ZERO)]!!
        val x1 = result[TimeCode.of(1, Fraction.QUARTER)]!!
        val x2 = result[TimeCode.of(1, Fraction.HALF)]!!

        assertTrue(x0 < x1, "Beat 0 should be left of beat 1/4")
        assertTrue(x1 < x2, "Beat 1/4 should be left of beat 1/2")
    }

    @Test
    fun testConsecutiveSameVoiceEventsAtSameTimeDoNotSkipFollowingVoiceEvents() {
        val upperVoice = TrackId("upper")
        val lowerVoice = TrackId("lower")
        val t0 = TimeCode.of(1, Fraction.ZERO)
        val t1 = TimeCode.of(1, Fraction(1, 24))
        val t2 = TimeCode.of(1, Fraction(1, 12))
        val t3 = TimeCode.of(1, Fraction(1, 8))

        val events = listOf(
            createNoteEvent(t0, Duration.EIGHTH, trackId = upperVoice, eventId = "upper_0a"),
            createNoteEvent(t0, Duration.EIGHTH, trackId = upperVoice, eventId = "upper_0b"),
            createNoteEvent(t3, Duration.EIGHTH, trackId = upperVoice, eventId = "upper_1"),
            createNoteEvent(t0, Duration(DurationBase.SIXTEENTH, tuplet = Tuplet.SEXTUPLET), trackId = lowerVoice, eventId = "lower_0"),
            createNoteEvent(t1, Duration(DurationBase.SIXTEENTH, tuplet = Tuplet.SEXTUPLET), trackId = lowerVoice, eventId = "lower_1"),
            createNoteEvent(t2, Duration(DurationBase.SIXTEENTH, tuplet = Tuplet.SEXTUPLET), trackId = lowerVoice, eventId = "lower_2"),
            createNoteEvent(t3, Duration(DurationBase.SIXTEENTH, tuplet = Tuplet.SEXTUPLET), trackId = lowerVoice, eventId = "lower_3")
        )

        val result = computer.computeXPositions(events, StaffSpace.ZERO)
        val x0 = result[t0]!!.value
        val x1 = result[t1]!!.value
        val x2 = result[t2]!!.value
        val x3 = result[t3]!!.value

        val minimumGap = config.minimumNoteSpacing.value
        assertTrue(x1 - x0 >= minimumGap, "1/24 should not be interpolated into the 0 slot: x0=$x0 x1=$x1")
        assertTrue(x2 - x1 >= minimumGap, "1/12 should remain a distinct lower-voice slot: x1=$x1 x2=$x2")
        assertTrue(x3 - x2 >= minimumGap, "1/8 should remain after the preceding lower-voice slot: x2=$x2 x3=$x3")
    }

    @Test
    fun testEmptyEventsReturnsEmptyMap() {
        val result = computer.computeXPositions(emptyList(), StaffSpace(10f))
        assertTrue(result.isEmpty())
    }

    private fun noteBody(left: Float, right: Float): NoteBodyElement {
        val bounds = RelativeRect(
            origin = RelativePoint(StaffSpace.ZERO, StaffSpace.ZERO),
            width = StaffSpace(right),
            height = StaffSpace.ONE,
        )
        val head = GlyphGeometry(
            glyph = SmuflGlyphs.noteheadBlack,
            position = RelativePoint.ZERO,
            bounds = bounds,
        )
        return NoteBodyElement(
            noteheads = listOf(NoteheadRenderInfo(0, 0, head)),
            stemUpAttachment = RelativePoint.ZERO,
            stemDownAttachment = RelativePoint.ZERO,
            leftExtent = StaffSpace(left),
            rightExtent = StaffSpace(right),
        )
    }

    @Test
    fun testLoneAnnotationDoesNotReflowNextNote() {
        // A wide chord label with NO following label must not push the next note: the label simply
        // overhangs the following notes on its own band. Only label-vs-label proximity reflows notes.
        val t0 = TimeCode.of(1, Fraction.ZERO)
        val t1 = TimeCode.of(1, Fraction.QUARTER)
        val note0 = createNoteEvent(t0, Duration.QUARTER, eventId = "n0")
        val note1 = createNoteEvent(t1, Duration.QUARTER, eventId = "n1")

        val baseline = computer.computeXPositions(listOf(note0, note1), StaffSpace.ZERO)
        val withLabel = computer.computeXPositions(
            listOf(note0, note1),
            StaffSpace.ZERO,
            listOf(AnnotationSpacingParticipant(t0, TrackId("chords"), StaffSpace(20f)))
        )

        assertEquals(
            baseline[t1]!!.value,
            withLabel[t1]!!.value,
            "A lone chord label must not move the next note",
        )
    }

    @Test
    fun testAdjacentAnnotationLabelsReflowNotesToAvoidOverlap() {
        // Labels on consecutive onsets: the second onset's anchor must sit at least the first label's
        // width past the first onset's anchor, so the two labels never stack — and the note reflows.
        val t0 = TimeCode.of(1, Fraction.ZERO)
        val t1 = TimeCode.of(1, Fraction.QUARTER)
        val note0 = createNoteEvent(t0, Duration.QUARTER, eventId = "n0")
        val note1 = createNoteEvent(t1, Duration.QUARTER, eventId = "n1")

        val width0 = StaffSpace(15f)
        val track = TrackId("chords")
        val baseline = computer.computeXPositions(listOf(note0, note1), StaffSpace.ZERO)
        val result = computer.computeXPositions(
            listOf(note0, note1),
            StaffSpace.ZERO,
            listOf(
                AnnotationSpacingParticipant(t0, track, width0),
                AnnotationSpacingParticipant(t1, track, StaffSpace(8f)),
            )
        )

        // Right edges align with the (equal-width) note slots, so the right-edge gap tracks the anchor gap.
        val gap = result[t1]!!.value - result[t0]!!.value
        assertTrue(gap >= width0.value, "Adjacent labels must not overlap: gap=$gap width0=$width0")
        assertTrue(
            result[t1]!!.value > baseline[t1]!!.value,
            "Second note must reflow right to fit two close labels",
        )
    }

    @Test
    fun testProportionalSpacing() {
        // Create events at beat 0, 1/4, 1/2, 3/4 (all quarters)
        val events = listOf(
            createNoteEvent(TimeCode.of(1, Fraction.ZERO), Duration.QUARTER, eventId = "e1"),
            createNoteEvent(TimeCode.of(1, Fraction.QUARTER), Duration.QUARTER, eventId = "e2"),
            createNoteEvent(TimeCode.of(1, Fraction.HALF), Duration.QUARTER, eventId = "e3"),
            createNoteEvent(TimeCode.of(1, Fraction(3, 4)), Duration.QUARTER, eventId = "e4")
        )

        val result = computer.computeXPositions(events, StaffSpace.ZERO)

        // Note: result values are right edge positions, but gaps between them are still meaningful
        val x0 = result[TimeCode.of(1, Fraction.ZERO)]!!.value
        val x1 = result[TimeCode.of(1, Fraction.QUARTER)]!!.value
        val x2 = result[TimeCode.of(1, Fraction.HALF)]!!.value
        val x3 = result[TimeCode.of(1, Fraction(3, 4))]!!.value

        // For equal durations, spacing between right edges should be approximately equal
        // (since all events have the same width, right edge gaps = left edge gaps)
        val gap1 = x1 - x0
        val gap2 = x2 - x1
        val gap3 = x3 - x2

        // Allow some tolerance for floating-point
        val tolerance = 0.5f
        assertTrue(
            kotlin.math.abs(gap1 - gap2) < tolerance,
            "Gap1 ($gap1) should be close to Gap2 ($gap2)"
        )
        assertTrue(
            kotlin.math.abs(gap2 - gap3) < tolerance,
            "Gap2 ($gap2) should be close to Gap3 ($gap3)"
        )
    }
}
