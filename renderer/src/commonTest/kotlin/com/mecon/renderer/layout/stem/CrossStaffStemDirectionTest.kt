package com.mecon.renderer.layout.stem

import com.mecon.api.computed.BeamGroupId
import com.mecon.api.computed.BeamInfo
import com.mecon.api.computed.ComputedPitchData
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Pitch
import com.mecon.renderer.enums.StemDirection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * In a beam group that spans two staves (cross-staff), the beam sits between the
 * staves and stems interleave: notes on the upper staff point DOWN, notes on the
 * lower staff point UP.
 */
class CrossStaffStemDirectionTest {

    private val resolver = StemDirectionResolver()

    private fun pitch(staffPosition: Int) = ComputedPitchData(
        pitch = Pitch.C4,
        midiPitch = 60,
        staffPosition = staffPosition,
        effectiveAccidental = null,
        needsLedgerLine = false,
    )

    private fun input(id: EventId, staffIndex: Int, beamsLeft: Int, beamsRight: Int) =
        StemResolutionInput(
            eventId = id,
            pitchData = listOf(pitch(0)),
            beamInfo = BeamInfo(
                groupId = BeamGroupId("g"),
                totalBeamCount = 1,
                beamsLeft = beamsLeft,
                beamsRight = beamsRight,
            ),
            userStemDirection = null,
            voiceContext = VoiceContext(
                voiceNumber = 1,
                measureNumber = 1,
                hasMultipleVoices = false,
                staffIndex = staffIndex,
                crossStaffOffset = if (staffIndex == 0) -1 else 0,
            ),
        )

    @Test
    fun crossStaffBeamInterleavesStems() {
        val upper = EventId.generate()  // renders on staff 0 (upper)
        val lower = EventId.generate()  // renders on staff 1 (lower)

        val result = resolver.resolve(
            listOf(
                input(upper, staffIndex = 0, beamsLeft = 0, beamsRight = 1),
                input(lower, staffIndex = 1, beamsLeft = 1, beamsRight = 0),
            )
        )

        assertEquals(StemDirection.DOWN, result[upper], "upper-staff note points down toward the beam")
        assertEquals(StemDirection.UP, result[lower], "lower-staff note points up toward the beam")
    }

    @Test
    fun beamSpanningThreeStavesSplitsStemsAroundTheLowerMiddleGap() {
        val upper = EventId.generate()
        val middle = EventId.generate()
        val lower = EventId.generate()

        val result = resolver.resolve(
            listOf(
                input(upper, staffIndex = 0, beamsLeft = 0, beamsRight = 1),
                input(middle, staffIndex = 1, beamsLeft = 1, beamsRight = 1),
                input(lower, staffIndex = 2, beamsLeft = 1, beamsRight = 0),
            )
        )

        assertEquals(StemDirection.DOWN, result[upper])
        assertEquals(StemDirection.DOWN, result[middle])
        assertEquals(StemDirection.UP, result[lower])
    }

    @Test
    fun standaloneCrossStaffNoteUsesInsertionRule() {
        val borrowedUp = EventId.generate()
        val result = resolver.resolveIndividualDirection(
            StemResolutionInput(
                eventId = borrowedUp,
                pitchData = listOf(pitch(0)),
                beamInfo = null,
                userStemDirection = null,
                voiceContext = VoiceContext(
                    voiceNumber = 1,
                    measureNumber = 1,
                    hasMultipleVoices = false,
                    staffIndex = 0,
                    crossStaffOffset = -1,
                ),
            )
        )
        assertEquals(StemDirection.DOWN, result, "note borrowed up acts as lower voice -> stem down")
    }
}
