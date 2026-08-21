package com.mecon.core.engine

import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.tracks.RuntimeStaffTrack
import com.mecon.api.storage.events.OctaveShiftType
import com.mecon.api.storage.events.StorageOctaveShiftEnd
import com.mecon.api.storage.events.StorageOctaveShiftStart
import com.mecon.api.storage.tracks.Clef

object StaffPitchContext {

    data class Event(
        val onset: TimeCode,
        val clef: Clef,
        val middleCStaffPosition: Int,
    )

    /** Immutable, time-ordered clef context used by compute, renderer previews and hit conversion. */
    class Timeline internal constructor(val events: List<Event>) {
        init {
            require(events.isNotEmpty()) { "Staff pitch timeline must contain an initial event" }
        }

        fun at(onset: TimeCode): Event {
            var low = 0
            var high = events.lastIndex
            var found = events.first()
            while (low <= high) {
                val middle = (low + high).ushr(1)
                val candidate = events[middle]
                if (candidate.onset <= onset) {
                    found = candidate
                    low = middle + 1
                } else {
                    high = middle - 1
                }
            }
            return found
        }
    }

    fun timeline(staff: RuntimeStaffTrack): Timeline = timeline(
        initialClef = staff.clef,
        changes = staff.clefChanges.map { it.onset to it.clef },
    )

    fun timeline(initialClef: Clef, changes: Iterable<Pair<TimeCode, Clef>>): Timeline {
        val byOnset = linkedMapOf(TimeCode.ZERO to initialClef)
        changes.sortedBy { it.first }.forEach { (onset, clef) -> byOnset[onset] = clef }
        return Timeline(
            byOnset.entries
                .sortedBy { it.key }
                .map { (onset, clef) ->
                    Event(onset, clef, StaffPositionComputer.middleCStaffPosition(clef))
                },
        )
    }

    fun effectiveClef(onset: TimeCode, staff: RuntimeStaffTrack): Clef =
        timeline(staff).at(onset).clef

    fun diatonicStepsAt(staffPosition: Int, onset: TimeCode, timeline: Timeline): Int =
        staffPosition - timeline.at(onset).middleCStaffPosition

    fun staffPosition(pitch: Pitch, onset: TimeCode, timeline: Timeline, octaveShiftDiatonicOffset: Int): Int =
        pitch.diatonicSteps + timeline.at(onset).middleCStaffPosition + octaveShiftDiatonicOffset

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
