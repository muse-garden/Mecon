package com.mecon.core.engine

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.storage.tracks.Clef
import kotlin.test.Test
import kotlin.test.assertEquals

class StaffPitchContextTest {
    @Test
    fun timelineExposesMiddleCPositionAtEveryClefEvent() {
        val bassAtM2 = TimeCode.of(2, Fraction.ZERO)
        val trebleAtM3 = TimeCode.of(3, Fraction.ZERO)
        val timeline = StaffPitchContext.timeline(
            initialClef = Clef.TREBLE,
            changes = listOf(bassAtM2 to Clef.BASS, trebleAtM3 to Clef.TREBLE),
        )

        assertEquals(listOf(-6, 6, -6), timeline.events.map { it.middleCStaffPosition })
        assertEquals(Clef.TREBLE, timeline.at(TimeCode.of(1, Fraction.ZERO)).clef)
        assertEquals(Clef.BASS, timeline.at(TimeCode.of(2, Fraction(1, 4))).clef)
        assertEquals(-6, StaffPitchContext.diatonicStepsAt(0, bassAtM2, timeline))
        assertEquals(0, StaffPitchContext.staffPosition(Pitch(-6), bassAtM2, timeline, 0))
    }

    @Test
    fun keySignaturePositionsAreCalculatedFromClefAndTraditionalOctave() {
        assertEquals(
            listOf(4, 1, 5, 2, -1, 3, 0),
            KeySignaturePositionComputer.staffPositions(KeySignature.majorByFifths(7), Clef.TREBLE),
        )
        assertEquals(
            listOf(2, -1, 3, 0, -3, 1, -2),
            KeySignaturePositionComputer.staffPositions(KeySignature.majorByFifths(7), Clef.BASS),
        )
        assertEquals(
            listOf(0, 3, -1, 2, -2, 1, -3),
            KeySignaturePositionComputer.staffPositions(KeySignature.majorByFifths(-7), Clef.TREBLE),
        )
        assertEquals(
            listOf(-2, 1, -3, 0, -4, -1, -5),
            KeySignaturePositionComputer.staffPositions(KeySignature.majorByFifths(-7), Clef.BASS),
        )
        assertEquals(listOf(3, 0, 4), KeySignaturePositionComputer.staffPositions(KeySignature.majorByFifths(3), Clef.ALTO))
        assertEquals(listOf(1, 4, 0), KeySignaturePositionComputer.staffPositions(KeySignature.majorByFifths(-3), Clef.TENOR))
    }
}
