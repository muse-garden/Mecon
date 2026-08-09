package com.mecon.renderer.layout

import com.mecon.api.computed.ComputedPitchData
import com.mecon.api.primitive.Accidental
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.renderer.elements.NoteElement
import com.mecon.renderer.enums.StemDirection
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.snapshot.loadFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MultiVoiceSlotCollisionResolverTest {
    private val time = TimeCode.of(1, Fraction.ZERO)

    @Test
    fun oppositeStemUnisonSharesColumnAndAccidental() = withFont {
        val slots = slots(
            note("up", voice = 1, direction = StemDirection.UP, pitches = listOf(pitch(0, Accidental.SHARP))),
            note("down", voice = 2, direction = StemDirection.DOWN, pitches = listOf(pitch(0, Accidental.SHARP))),
        )
        val notes = slots.noteEvents()

        assertEquals(notes[0].relativeX, notes[1].relativeX)
        assertEquals(1, notes.sumOf { it.noteBody.accidentals.size })
        val allocatedWidths = notes.map { it.minimumWidth.value }
        assertTrue(allocatedWidths.max() - allocatedWidths.min() < 0.0001f)
    }

    @Test
    fun sameStemUnisonUsesMinimumTwoColumnSpread() = withFont {
        val slots = slots(
            note("v1", voice = 1, direction = StemDirection.UP, pitches = listOf(pitch(0))),
            note("v3", voice = 3, direction = StemDirection.UP, pitches = listOf(pitch(0))),
        )
        val notes = slots.noteEvents()
        val spread = notes.maxOf { it.relativeX } - notes.minOf { it.relativeX }

        assertNotEquals(notes[0].relativeX, notes[1].relativeX)
        assertTrue(spread > StaffSpace.ONE)
        assertTrue(spread < StaffSpace(2f), "solver should use one compact notehead column, got $spread")
        assertTrue(
            notes.all {
                kotlin.math.abs(it.multiVoiceWidthExtension.value - spread.value) < 0.0001f
            }
        )
    }

    @Test
    fun uniquePitchInsideOverlapPreventsMerge() = withFont {
        val slots = slots(
            note(
                "upper",
                voice = 1,
                direction = StemDirection.UP,
                pitches = listOf(pitch(0), pitch(2)),
            ),
            note(
                "lower",
                voice = 2,
                direction = StemDirection.DOWN,
                pitches = listOf(pitch(0), pitch(1)),
            ),
        )
        val notes = slots.noteEvents()

        assertNotEquals(notes[0].relativeX, notes[1].relativeX)
    }

    @Test
    fun secondChordNestedInsideOtherVoiceRangeUsesSeparateColumn() = withFont {
        val notes = slots(
            note(
                "outer",
                voice = 1,
                direction = StemDirection.UP,
                pitches = listOf(pitch(0), pitch(7)),
            ),
            note(
                "nestedSecond",
                voice = 2,
                direction = StemDirection.DOWN,
                pitches = listOf(pitch(3), pitch(4)),
            ),
        ).noteEvents()

        val spread = notes.maxOf { it.relativeX } - notes.minOf { it.relativeX }
        val headWidth = notes.first().noteBody.noteheads.first().geometry.bounds.width
        assertTrue(spread > StaffSpace.ZERO)

        val stemColumns = notes.map { note ->
            note.relativeX.value + when (note.resolvedStemDirection) {
                StemDirection.UP -> note.stemUpAttachment.x.value
                StemDirection.DOWN -> note.stemDownAttachment.x.value
                null -> error("test note requires a resolved stem")
            }
        }
        val stemGap = kotlin.math.abs(stemColumns[0] - stemColumns[1])
        assertTrue(
            kotlin.math.abs(stemGap - headWidth.value) < 0.0001f,
            "solver should use exactly one readable stem column, got $stemGap",
        )
    }

    @Test
    fun uniquePitchesOutsideSingleCommonBoundaryStillMerge() = withFont {
        val slots = slots(
            note(
                "upper",
                voice = 1,
                direction = StemDirection.UP,
                pitches = listOf(pitch(0), pitch(2)),
            ),
            note(
                "lower",
                voice = 2,
                direction = StemDirection.DOWN,
                pitches = listOf(pitch(-2), pitch(0)),
            ),
        )
        val notes = slots.noteEvents()

        assertEquals(notes[0].relativeX, notes[1].relativeX)
    }

    @Test
    fun verticallyDistantVoicesDoNotAcquireAHorizontalShift() = withFont {
        val notes = slots(
            note("high", 1, StemDirection.UP, listOf(pitch(6))),
            note("low", 2, StemDirection.DOWN, listOf(pitch(-6))),
        ).noteEvents()

        assertEquals(notes[0].relativeX, notes[1].relativeX)
    }

    @Test
    fun accidentalInkDoesNotMoveMultiVoiceStaffAwayFromOtherStaff() = withFont {
        val notes = slots(
            note(
                "upperStaffUp",
                voice = 1,
                direction = StemDirection.UP,
                pitches = listOf(pitch(6, Accidental.SHARP)),
                staff = 0,
            ),
            note(
                "upperStaffDown",
                voice = 2,
                direction = StemDirection.DOWN,
                pitches = listOf(pitch(-6, Accidental.FLAT)),
                staff = 0,
            ),
            note(
                "lowerStaff",
                voice = 1,
                direction = StemDirection.UP,
                pitches = listOf(pitch(0)),
                staff = 1,
            ),
        ).noteEvents().associateBy { it.eventId.value }

        assertHeadColumnEquals(notes.getValue("upperStaffDown"), notes.getValue("lowerStaff"))
        assertHeadColumnEquals(notes.getValue("upperStaffUp"), notes.getValue("lowerStaff"))
    }

    @Test
    fun onlyCollidingVoiceMovesAwayFromCrossStaffTimeAnchor() = withFont {
        val notes = slots(
            note(
                "upperStaffUp",
                voice = 1,
                direction = StemDirection.UP,
                pitches = listOf(pitch(0, Accidental.SHARP)),
                staff = 0,
            ),
            note(
                "upperStaffDown",
                voice = 2,
                direction = StemDirection.DOWN,
                pitches = listOf(pitch(0, Accidental.NATURAL)),
                staff = 0,
            ),
            note(
                "lowerStaff",
                voice = 1,
                direction = StemDirection.UP,
                pitches = listOf(pitch(0)),
                staff = 1,
            ),
        ).noteEvents().associateBy { it.eventId.value }

        val anchored = notes.getValue("upperStaffDown")
        val displaced = notes.getValue("upperStaffUp")
        val otherStaff = notes.getValue("lowerStaff")

        assertHeadColumnEquals(anchored, otherStaff)
        assertTrue(headColumn(displaced) > headColumn(anchored))
    }

    @Test
    fun differentHeadShapesKeepOneCrossStaffReferenceColumn() = withFont {
        val notes = slots(
            note(
                "upperStaffQuarter",
                voice = 1,
                direction = StemDirection.UP,
                pitches = listOf(pitch(0)),
                duration = Duration.QUARTER,
                staff = 0,
            ),
            note(
                "upperStaffHalf",
                voice = 2,
                direction = StemDirection.DOWN,
                pitches = listOf(pitch(0)),
                duration = Duration.HALF,
                staff = 0,
            ),
            note(
                "lowerStaffHalf",
                voice = 1,
                direction = StemDirection.DOWN,
                pitches = listOf(pitch(0)),
                duration = Duration.HALF,
                staff = 1,
            ),
        ).noteEvents().associateBy { it.eventId.value }

        assertHeadColumnEquals(notes.getValue("upperStaffHalf"), notes.getValue("lowerStaffHalf"))
        assertTrue(
            headColumn(notes.getValue("upperStaffQuarter")) >
                headColumn(notes.getValue("upperStaffHalf"))
        )
    }

    @Test
    fun threeVoiceClusterRetainsOneCrossStaffReferenceColumn() = withFont {
        val notes = slots(
            note("up1", 1, StemDirection.UP, listOf(pitch(0)), staff = 0),
            note("down", 2, StemDirection.DOWN, listOf(pitch(0)), staff = 0),
            note("up3", 3, StemDirection.UP, listOf(pitch(0)), staff = 0),
            note("otherStaff", 1, StemDirection.DOWN, listOf(pitch(0)), staff = 1),
        ).noteEvents().associateBy { it.eventId.value }

        assertHeadColumnEquals(notes.getValue("down"), notes.getValue("otherStaff"))
        assertEquals(
            2,
            listOf("up1", "down", "up3").map { headColumn(notes.getValue(it)) }.distinct().size,
        )
    }

    @Test
    fun differentHeadShapesOrAccidentalsDoNotMerge() = withFont {
        val differentHeads = slots(
            note("quarter", 1, StemDirection.UP, listOf(pitch(0)), Duration.QUARTER),
            note("half", 2, StemDirection.DOWN, listOf(pitch(0)), Duration.HALF),
        ).noteEvents()
        assertNotEquals(differentHeads[0].relativeX, differentHeads[1].relativeX)

        val differentAccidentals = slots(
            note("sharp", 1, StemDirection.UP, listOf(pitch(0, Accidental.SHARP))),
            note("natural", 2, StemDirection.DOWN, listOf(pitch(0, Accidental.NATURAL))),
        ).noteEvents()
        assertNotEquals(differentAccidentals[0].relativeX, differentAccidentals[1].relativeX)
        assertEquals(2, differentAccidentals.sumOf { it.noteBody.accidentals.size })
        assertAccidentalsClearAllInk(differentAccidentals)
    }

    @Test
    fun threeVoicesUseGlobalTwoColumnSolution() = withFont {
        val notes = slots(
            note("up1", 1, StemDirection.UP, listOf(pitch(0, Accidental.SHARP))),
            note("down", 2, StemDirection.DOWN, listOf(pitch(0, Accidental.SHARP))),
            note("up3", 3, StemDirection.UP, listOf(pitch(0, Accidental.SHARP))),
        ).noteEvents()

        assertEquals(2, notes.map { it.relativeX }.distinct().size)
        assertEquals(2, notes.sumOf { it.noteBody.accidentals.size })
    }

    context(com.mecon.renderer.smufl.BravuraFont)
    private fun slots(vararg notes: NoteElement): UnifiedTimeSlot =
        UnifiedHorizontalSlotComputer(RenderLayoutConfig.DEFAULT)
            .buildBaseTimeSlotMap(notes.toList())
            .atTime(time)!!

    context(com.mecon.renderer.smufl.BravuraFont)
    private fun note(
        id: String,
        voice: Int,
        direction: StemDirection,
        pitches: List<ComputedPitchData>,
        duration: Duration = Duration.QUARTER,
        staff: Int = 0,
    ): NoteElement {
        val body = NoteBodyElementBuilder(RenderLayoutConfig.DEFAULT)
            .buildNoteGeometry(pitches, duration, direction)
        return NoteElement(
            time = time,
            staffIndex = staff,
            eventId = EventId(id),
            trackId = TrackId("staff"),
            duration = duration,
            measureNumber = 1,
            pitchData = pitches,
            isRest = false,
            voiceNumber = voice,
            beamInfo = null,
            resolvedStemDirection = direction,
            noteBody = body,
        )
    }

    private fun assertHeadColumnEquals(first: NoteElement, second: NoteElement) {
        assertTrue(
            kotlin.math.abs(headColumn(first) - headColumn(second)) < 0.0001f,
            "expected aligned head columns, got ${headColumn(first)} and ${headColumn(second)}",
        )
    }

    private fun headColumn(note: NoteElement): Float =
        note.relativeX.value + note.noteBody.noteheads.first().geometry.bounds.left.value

    private fun pitch(
        staffPosition: Int,
        accidental: Accidental? = null,
    ): ComputedPitchData = ComputedPitchData(
        pitch = Pitch(staffPosition, accidental?.offset ?: 0),
        midiPitch = 60 + staffPosition,
        staffPosition = staffPosition,
        effectiveAccidental = accidental,
        needsLedgerLine = false,
    )

    private fun assertAccidentalsClearAllInk(notes: List<NoteElement>) {
        val accidentals = notes.flatMap { note ->
            note.noteBody.accidentals.map { note to it.geometry.bounds.atX(note.relativeX) }
        }
        val heads = notes.flatMap { note ->
            note.noteBody.noteheads.map { note to it.geometry.bounds.atX(note.relativeX) }
        }
        for ((_, accidental) in accidentals) {
            assertTrue(heads.none { (_, head) -> accidental.overlaps(head) })
        }
        for (index in accidentals.indices) {
            for (other in index + 1 until accidentals.size) {
                assertTrue(!accidentals[index].second.overlaps(accidentals[other].second))
            }
        }
    }

    private fun RelativeRect.atX(offset: StaffSpace): RelativeRect = copy(
        origin = RelativePoint(origin.x + offset, origin.y)
    )

    private inline fun withFont(block: context(com.mecon.renderer.smufl.BravuraFont) () -> Unit) {
        val font = loadFont() ?: return
        block(font)
    }
}
