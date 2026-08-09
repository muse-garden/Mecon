package com.mecon.core.engine

import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.tracks.RuntimeStaffTrack
import com.mecon.api.storage.events.OctaveShiftType
import com.mecon.api.storage.events.StorageOctaveShiftEnd
import com.mecon.api.storage.events.StorageOctaveShiftStart
import com.mecon.api.storage.tracks.Clef

object StaffPitchContext {

    fun effectiveClef(onset: TimeCode, staff: RuntimeStaffTrack): Clef =
        staff.clefChanges
            .filter { it.onset <= onset }
            .maxByOrNull { it.onset }
            ?.clef
            ?: staff.clef

    /**
     * Returns the diatonic staff-position offset imposed by an active octave-shift bracket.
     *
     * Pitches are stored at their sounding position. The written notation shows them an octave
     * closer to the staff centre:
     *  - 8va (OTTAVA):       written = sounding - octave -> offset = -7
     *  - 8vb (OTTAVA_BASSA): written = sounding + octave -> offset = +7
     */
    fun octaveShiftDiatonicOffset(onset: TimeCode, staff: RuntimeStaffTrack): Int {
        val endById = staff.attachments
            .filterIsInstance<StorageOctaveShiftEnd>()
            .associateBy { it.id }
        for (shift in staff.attachments.filterIsInstance<StorageOctaveShiftStart>()) {
            val end = endById[shift.endEventId] ?: continue
            if (onset >= shift.onset && onset < end.onset) {
                return when (shift.shiftType) {
                    OctaveShiftType.OTTAVA -> -7
                    OctaveShiftType.OTTAVA_BASSA -> 7
                }
            }
        }
        return 0
    }

    fun staffPosition(pitch: Pitch, clef: Clef, octaveShiftDiatonicOffset: Int): Int =
        StaffPositionComputer.compute(pitch, clef) + octaveShiftDiatonicOffset
}
