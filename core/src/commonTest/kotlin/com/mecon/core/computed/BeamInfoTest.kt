package com.mecon.api.computed

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import kotlin.test.*

class BeamInfoTest {

    private val groupId = BeamGroupId("test-group")

    // ===== Basic Properties Tests =====

    @Test
    fun testThroughBeamCount() {
        // Through beams = min(beamsLeft, beamsRight)
        assertEquals(0, BeamInfo(groupId, 1, 0, 1).throughBeamCount)  // start
        assertEquals(1, BeamInfo(groupId, 1, 1, 1).throughBeamCount)  // middle
        assertEquals(0, BeamInfo(groupId, 1, 1, 0).throughBeamCount)  // end
        assertEquals(1, BeamInfo(groupId, 2, 2, 1).throughBeamCount)  // decrease
        assertEquals(1, BeamInfo(groupId, 2, 1, 2).throughBeamCount)  // increase
    }

    @Test
    fun testLeftHookCount() {
        // Left hooks = totalBeamCount - beamsRight, but only when not at group end
        assertEquals(0, BeamInfo(groupId, 1, 0, 1).leftHookCount)  // start, no hooks
        assertEquals(0, BeamInfo(groupId, 1, 1, 1).leftHookCount)  // middle, totalBeamCount == beamsRight
        assertEquals(0, BeamInfo(groupId, 2, 2, 0).leftHookCount)  // end, no hooks (ending beams instead)
        assertEquals(1, BeamInfo(groupId, 2, 2, 1).leftHookCount)  // backward hook: total=2, right=1, hook=1
        assertEquals(2, BeamInfo(groupId, 3, 3, 1).leftHookCount)  // two backward hooks: total=3, right=1, hook=2
    }

    @Test
    fun testRightHookCount() {
        // Right hooks = totalBeamCount - beamsLeft, but only when not at group start
        assertEquals(0, BeamInfo(groupId, 1, 1, 0).rightHookCount)  // end, no hooks
        assertEquals(0, BeamInfo(groupId, 1, 1, 1).rightHookCount)  // middle, totalBeamCount == beamsLeft
        assertEquals(0, BeamInfo(groupId, 2, 0, 2).rightHookCount)  // start, no hooks (starting beams instead)
        assertEquals(1, BeamInfo(groupId, 2, 1, 2).rightHookCount)  // forward hook: total=2, left=1, hook=1
        assertEquals(2, BeamInfo(groupId, 3, 1, 3).rightHookCount)  // two forward hooks: total=3, left=1, hook=2
    }

    @Test
    fun testStartingBeamCount() {
        // Starting beams: total beams when at group start (beamsLeft == 0)
        assertEquals(1, BeamInfo(groupId, 1, 0, 1).startingBeamCount)  // 8th start
        assertEquals(2, BeamInfo(groupId, 2, 0, 2).startingBeamCount)  // 16th start
        assertEquals(2, BeamInfo(groupId, 2, 0, 1).startingBeamCount)  // 16th start, only 1 connects right
        assertEquals(0, BeamInfo(groupId, 1, 1, 1).startingBeamCount)  // middle
        assertEquals(0, BeamInfo(groupId, 1, 1, 0).startingBeamCount)  // end
    }

    @Test
    fun testEndingBeamCount() {
        // Ending beams: total beams when at group end (beamsRight == 0)
        assertEquals(1, BeamInfo(groupId, 1, 1, 0).endingBeamCount)  // 8th end
        assertEquals(2, BeamInfo(groupId, 2, 2, 0).endingBeamCount)  // 16th end
        assertEquals(2, BeamInfo(groupId, 2, 1, 0).endingBeamCount)  // 16th end, only 1 came from left
        assertEquals(0, BeamInfo(groupId, 1, 1, 1).endingBeamCount)  // middle
        assertEquals(0, BeamInfo(groupId, 1, 0, 1).endingBeamCount)  // start
    }

    // ===== Position Tests =====

    @Test
    fun testIsGroupStart() {
        assertTrue(BeamInfo(groupId, 1, 0, 1).isGroupStart)
        assertTrue(BeamInfo(groupId, 2, 0, 2).isGroupStart)
        assertTrue(BeamInfo(groupId, 2, 0, 1).isGroupStart)  // 16th with only 1 beam connecting
        assertFalse(BeamInfo(groupId, 1, 1, 1).isGroupStart)
        assertFalse(BeamInfo(groupId, 1, 1, 0).isGroupStart)
    }

    @Test
    fun testIsGroupEnd() {
        assertTrue(BeamInfo(groupId, 1, 1, 0).isGroupEnd)
        assertTrue(BeamInfo(groupId, 2, 2, 0).isGroupEnd)
        assertTrue(BeamInfo(groupId, 2, 1, 0).isGroupEnd)  // 16th with only 1 beam connecting
        assertFalse(BeamInfo(groupId, 1, 1, 1).isGroupEnd)
        assertFalse(BeamInfo(groupId, 1, 0, 1).isGroupEnd)
    }

    @Test
    fun testIsGroupMiddle() {
        assertTrue(BeamInfo(groupId, 1, 1, 1).isGroupMiddle)
        assertTrue(BeamInfo(groupId, 2, 2, 2).isGroupMiddle)
        assertTrue(BeamInfo(groupId, 2, 1, 2).isGroupMiddle)  // with right hooks
        assertTrue(BeamInfo(groupId, 2, 2, 1).isGroupMiddle)  // with left hooks
        assertFalse(BeamInfo(groupId, 1, 0, 1).isGroupMiddle)
        assertFalse(BeamInfo(groupId, 1, 1, 0).isGroupMiddle)
    }

    // ===== Hook Tests =====

    @Test
    fun testHasLeftHook() {
        assertFalse(BeamInfo(groupId, 1, 1, 1).hasLeftHook)  // total == right
        assertFalse(BeamInfo(groupId, 2, 2, 0).hasLeftHook)  // group end, no hooks
        assertTrue(BeamInfo(groupId, 2, 2, 1).hasLeftHook)   // backward hook
        assertTrue(BeamInfo(groupId, 3, 2, 1).hasLeftHook)   // backward hooks
    }

    @Test
    fun testHasRightHook() {
        assertFalse(BeamInfo(groupId, 1, 1, 1).hasRightHook)  // total == left
        assertFalse(BeamInfo(groupId, 2, 0, 2).hasRightHook)  // group start, no hooks
        assertTrue(BeamInfo(groupId, 2, 1, 2).hasRightHook)   // forward hook
        assertTrue(BeamInfo(groupId, 3, 1, 2).hasRightHook)   // forward hooks
    }

    // ===== Beam Change Tests =====

    @Test
    fun testBeamsIncrease() {
        assertTrue(BeamInfo(groupId, 2, 1, 2).beamsIncrease)   // 8th -> 16th
        assertTrue(BeamInfo(groupId, 2, 0, 2).beamsIncrease)   // start
        assertFalse(BeamInfo(groupId, 1, 1, 1).beamsIncrease)  // equal
        assertFalse(BeamInfo(groupId, 2, 2, 1).beamsIncrease)  // decrease
    }

    @Test
    fun testBeamsDecrease() {
        assertTrue(BeamInfo(groupId, 2, 2, 1).beamsDecrease)   // 16th -> 8th
        assertTrue(BeamInfo(groupId, 2, 2, 0).beamsDecrease)   // end
        assertFalse(BeamInfo(groupId, 1, 1, 1).beamsDecrease)  // equal
        assertFalse(BeamInfo(groupId, 2, 1, 2).beamsDecrease)  // increase
    }

    // ===== Factory Methods Tests =====

    @Test
    fun testGroupStartFactory() {
        val beam = BeamInfo.groupStart(groupId, 2, 2)
        assertEquals(2, beam.totalBeamCount)
        assertEquals(0, beam.beamsLeft)
        assertEquals(2, beam.beamsRight)
        assertTrue(beam.isGroupStart)
    }

    @Test
    fun testGroupEndFactory() {
        val beam = BeamInfo.groupEnd(groupId, 2, 2)
        assertEquals(2, beam.totalBeamCount)
        assertEquals(2, beam.beamsLeft)
        assertEquals(0, beam.beamsRight)
        assertTrue(beam.isGroupEnd)
    }

    @Test
    fun testGroupMiddleFactory() {
        val beam = BeamInfo.groupMiddle(groupId, 2, 1, 2)
        assertEquals(2, beam.totalBeamCount)
        assertEquals(1, beam.beamsLeft)
        assertEquals(2, beam.beamsRight)
        assertTrue(beam.isGroupMiddle)
    }

    @Test
    fun testBeamCountFromDuration() {
        assertEquals(0, BeamInfo.beamCountFromDuration(Duration(DurationBase.WHOLE)))
        assertEquals(0, BeamInfo.beamCountFromDuration(Duration(DurationBase.HALF)))
        assertEquals(0, BeamInfo.beamCountFromDuration(Duration(DurationBase.QUARTER)))
        assertEquals(1, BeamInfo.beamCountFromDuration(Duration(DurationBase.EIGHTH)))
        assertEquals(2, BeamInfo.beamCountFromDuration(Duration(DurationBase.SIXTEENTH)))
        assertEquals(3, BeamInfo.beamCountFromDuration(Duration(DurationBase.THIRTY_SECOND)))
        assertEquals(4, BeamInfo.beamCountFromDuration(Duration(DurationBase.SIXTY_FOURTH)))
    }

    // ===== Real-World Scenario Tests =====

    @Test
    fun testFourEighthNotesBeamed() {
        // Four 8th notes in a beam group: ♪ ♪ ♪ ♪
        val note1 = BeamInfo.groupStart(groupId, 1, 1)
        val note2 = BeamInfo.groupMiddle(groupId, 1, 1, 1)
        val note3 = BeamInfo.groupMiddle(groupId, 1, 1, 1)
        val note4 = BeamInfo.groupEnd(groupId, 1, 1)

        // Note 1: start with 1 beam
        assertTrue(note1.isGroupStart)
        assertEquals(1, note1.startingBeamCount)
        assertEquals(0, note1.throughBeamCount)
        assertEquals(0, note1.leftHookCount)
        assertEquals(0, note1.rightHookCount)

        // Note 2, 3: middle with 1 through beam
        assertTrue(note2.isGroupMiddle)
        assertEquals(1, note2.throughBeamCount)
        assertEquals(0, note2.leftHookCount)
        assertEquals(0, note2.rightHookCount)

        // Note 4: end with 1 beam
        assertTrue(note4.isGroupEnd)
        assertEquals(1, note4.endingBeamCount)
        assertEquals(0, note4.throughBeamCount)
        assertEquals(0, note4.leftHookCount)
        assertEquals(0, note4.rightHookCount)
    }

    @Test
    fun testDottedEighthPlusSixteenth_BackwardHook() {
        // Dotted 8th (♪.) + 16th (♬) - the 16th has a backward hook
        // ♪.  ♬
        //  ===  (primary beam connects both)
        //   =← (secondary beam is a left hook on 16th)

        val note1 = BeamInfo(groupId, 1, 0, 1)  // dotted 8th at start, 1 beam goes right
        val note2 = BeamInfo(groupId, 2, 1, 0)  // 16th at end, needs 2 beams, 1 came from left

        // Note 1: start with 1 beam
        assertTrue(note1.isGroupStart)
        assertEquals(1, note1.startingBeamCount)
        assertEquals(0, note1.leftHookCount)
        assertEquals(0, note1.rightHookCount)

        // Note 2: end with backward hook
        assertTrue(note2.isGroupEnd)
        assertEquals(2, note2.endingBeamCount)
        assertEquals(0, note2.leftHookCount)  // At group end, extra beams are ending beams, not hooks
        assertEquals(0, note2.rightHookCount)
        // Renderer should draw: 1 beam from left + 1 additional beam (left hook style)
    }

    @Test
    fun testSixteenthPlusDottedEighth_ForwardHook() {
        // 16th (♬) + dotted 8th (♪.) - the 16th has a forward hook
        // ♬  ♪.
        //  ===  (primary beam connects both)
        // →=   (secondary beam is a right hook on 16th)

        val note1 = BeamInfo(groupId, 2, 0, 1)  // 16th at start, needs 2 beams, only 1 goes right
        val note2 = BeamInfo(groupId, 1, 1, 0)  // dotted 8th at end, 1 beam from left

        // Note 1: start with forward hook
        assertTrue(note1.isGroupStart)
        assertEquals(2, note1.startingBeamCount)
        assertEquals(0, note1.leftHookCount)
        assertEquals(0, note1.rightHookCount)  // At group start, extra beams are starting beams
        // Renderer should draw: 1 beam to right + 1 additional beam (right hook style)

        // Note 2: end with 1 beam
        assertTrue(note2.isGroupEnd)
        assertEquals(1, note2.endingBeamCount)
    }

    @Test
    fun testMixedRhythm_InsideGroup_WithHooks() {
        // In the middle of a larger group: ...8th + 16th + 8th...
        // The 16th needs hooks on both sides

        val note1 = BeamInfo(groupId, 1, 1, 1)  // 8th in middle
        val note2 = BeamInfo(groupId, 2, 1, 1)  // 16th in middle, left=1, right=1
        val note3 = BeamInfo(groupId, 1, 1, 1)  // 8th in middle

        // Note 2 (16th): needs 2 beams but only 1 connects each side
        assertTrue(note2.isGroupMiddle)
        assertEquals(1, note2.throughBeamCount)
        assertEquals(1, note2.leftHookCount)   // totalBeamCount(2) - beamsRight(1) = 1
        assertEquals(1, note2.rightHookCount)  // totalBeamCount(2) - beamsLeft(1) = 1
        assertTrue(note2.hasLeftHook)
        assertTrue(note2.hasRightHook)
    }

    @Test
    fun testComplexRhythm_SixteenthEighthEighthSixteenth() {
        // 16th + 8th + 8th + 16th in one beam group
        // ♬ ♪ ♪ ♬
        //  =====   (primary beam through all)
        // →=   =← (secondary beams are hooks on first and last)

        val note1 = BeamInfo(groupId, 2, 0, 1)  // 16th start, needs 2, 1 goes right
        val note2 = BeamInfo(groupId, 1, 1, 1)  // 8th middle
        val note3 = BeamInfo(groupId, 1, 1, 1)  // 8th middle
        val note4 = BeamInfo(groupId, 2, 1, 0)  // 16th end, needs 2, 1 came from left

        // Note 1: 16th at start with forward hook
        assertTrue(note1.isGroupStart)
        assertEquals(2, note1.startingBeamCount)
        assertEquals(0, note1.rightHookCount)  // At start, these are starting beams

        // Middle 8th notes: just through beams
        assertEquals(1, note2.throughBeamCount)
        assertEquals(0, note2.leftHookCount)
        assertEquals(0, note2.rightHookCount)

        // Note 4: 16th at end with backward hook
        assertTrue(note4.isGroupEnd)
        assertEquals(2, note4.endingBeamCount)
        assertEquals(0, note4.leftHookCount)  // At end, these are ending beams
    }

    @Test
    fun testThirtySecondNotes() {
        // Four 32nd notes beamed (3 beam levels)
        val note1 = BeamInfo.groupStart(groupId, 3, 3)
        val note2 = BeamInfo.groupMiddle(groupId, 3, 3, 3)
        val note3 = BeamInfo.groupMiddle(groupId, 3, 3, 3)
        val note4 = BeamInfo.groupEnd(groupId, 3, 3)

        assertEquals(3, note1.totalBeamCount)
        assertEquals(3, note1.startingBeamCount)
        assertEquals(0, note1.leftHookCount)
        assertEquals(0, note1.rightHookCount)

        assertEquals(3, note2.throughBeamCount)
        assertEquals(0, note2.leftHookCount)
        assertEquals(0, note2.rightHookCount)

        assertEquals(3, note4.endingBeamCount)
    }

    @Test
    fun testIsolatedNote_NoBeams() {
        // An isolated flagged note (not beamed) would have beamInfo = null
        // in ComputedVoiceEvent. But we can test edge case:
        val isolated = BeamInfo(groupId, 1, 0, 0)

        assertFalse(isolated.isGroupStart)  // beamsRight == 0
        assertFalse(isolated.isGroupEnd)    // beamsLeft == 0
        assertFalse(isolated.isGroupMiddle)
        assertEquals(0, isolated.throughBeamCount)
        assertEquals(0, isolated.startingBeamCount)
        assertEquals(0, isolated.endingBeamCount)
        // This represents an invalid/edge case - normally beamInfo would be null
    }

    @Test
    fun testConnectingBeams() {
        val start = BeamInfo(groupId, 2, 0, 2)
        val middle = BeamInfo(groupId, 2, 2, 2)
        val end = BeamInfo(groupId, 2, 2, 0)

        assertEquals(0, start.connectingBeamsLeft)
        assertEquals(2, start.connectingBeamsRight)

        assertEquals(2, middle.connectingBeamsLeft)
        assertEquals(2, middle.connectingBeamsRight)

        assertEquals(2, end.connectingBeamsLeft)
        assertEquals(0, end.connectingBeamsRight)
    }
}
