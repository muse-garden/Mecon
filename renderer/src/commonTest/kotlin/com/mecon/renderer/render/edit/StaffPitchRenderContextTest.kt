package com.mecon.renderer.render.edit

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.tracks.RuntimeStaffTrack
import com.mecon.api.storage.events.OctaveShiftType
import com.mecon.api.storage.events.StaffAttachmentPlacement
import com.mecon.api.storage.events.StorageOctaveShiftEnd
import com.mecon.api.storage.events.StorageOctaveShiftStart
import com.mecon.api.storage.events.StorageStaffAttachment
import com.mecon.api.storage.tracks.Clef
import com.mecon.api.storage.tracks.StorageClefChange
import com.mecon.core.engine.StaffPitchContext
import kotlin.test.Test
import kotlin.test.assertEquals

class StaffPitchRenderContextTest {

    @Test
    fun dragPreviewUsesClefInEffectAtEventOnset() {
        val noteOnset = TimeCode.of(2, Fraction.ZERO)
        val staff = staff(
            initialClef = Clef.BASS,
            clefChanges = listOf(StorageClefChange(onset = noteOnset, clef = Clef.TREBLE)),
        )

        val clef = StaffPitchContext.effectiveClef(noteOnset, staff)
        val previewPosition = StaffPitchContext.staffPosition(Pitch.G4, clef, 0)

        assertEquals(Clef.TREBLE, clef)
        assertEquals(-2, previewPosition, "G4 after the inserted treble clef should preview on the treble staff")
        assertEquals(10, StaffPitchContext.staffPosition(Pitch.G4, Clef.BASS, 0),
            "This is the jumpy position produced when previewing with the initial bass clef")
    }

    @Test
    fun dragPreviewKeeps8vaOffset() {
        val m1 = TimeCode.of(1, Fraction.ZERO)
        val m2 = TimeCode.of(2, Fraction.ZERO)
        val staff = staff(attachments = octaveShift(m1, m2, OctaveShiftType.OTTAVA))
        val clef = StaffPitchContext.effectiveClef(m1, staff)
        val octaveOffset = StaffPitchContext.octaveShiftDiatonicOffset(m1, staff)

        val previewPosition = StaffPitchContext.staffPosition(Pitch(10, 0), clef, octaveOffset)

        assertEquals(-7, octaveOffset)
        assertEquals(-3, previewPosition, "F5 under 8va, dragged up one step from E5, previews one octave lower")
    }

    @Test
    fun dragPreviewKeeps8vbOffset() {
        val m1 = TimeCode.of(1, Fraction.ZERO)
        val m2 = TimeCode.of(2, Fraction.ZERO)
        val staff = staff(attachments = octaveShift(m1, m2, OctaveShiftType.OTTAVA_BASSA))
        val clef = StaffPitchContext.effectiveClef(m1, staff)
        val octaveOffset = StaffPitchContext.octaveShiftDiatonicOffset(m1, staff)

        val previewPosition = StaffPitchContext.staffPosition(Pitch(-6, 0), clef, octaveOffset)

        assertEquals(7, octaveOffset)
        assertEquals(-5, previewPosition, "D3 under 8vb, dragged up one step from C3, previews one octave higher")
    }

    @Test
    fun octaveShiftEndOnsetIsExclusive() {
        val m1 = TimeCode.of(1, Fraction.ZERO)
        val m2 = TimeCode.of(2, Fraction.ZERO)
        val staff = staff(attachments = octaveShift(m1, m2, OctaveShiftType.OTTAVA))

        assertEquals(0, StaffPitchContext.octaveShiftDiatonicOffset(m2, staff))
    }

    private fun staff(
        initialClef: Clef = Clef.TREBLE,
        clefChanges: List<StorageClefChange> = emptyList(),
        attachments: List<StorageStaffAttachment> = emptyList(),
    ) = RuntimeStaffTrack(
        id = TrackId("staff"),
        name = "Staff",
        clef = initialClef,
        keySignature = KeySignature.C_MAJOR,
        transposition = null,
        voiceTracks = emptyList(),
        attachments = attachments,
        clefChanges = clefChanges,
    )

    private fun octaveShift(
        startOnset: TimeCode,
        endOnset: TimeCode,
        shiftType: OctaveShiftType,
    ): List<StorageStaffAttachment> {
        val startId = EventId("octave-start-${shiftType.name}")
        val endId = EventId("octave-end-${shiftType.name}")
        val placement = if (shiftType == OctaveShiftType.OTTAVA) {
            StaffAttachmentPlacement.ABOVE
        } else {
            StaffAttachmentPlacement.BELOW
        }
        return listOf(
            StorageOctaveShiftStart(
                id = startId,
                onset = startOnset,
                shiftType = shiftType,
                endEventId = endId,
                placement = placement,
            ),
            StorageOctaveShiftEnd(
                id = endId,
                onset = endOnset,
                placement = placement,
            ),
        )
    }
}
