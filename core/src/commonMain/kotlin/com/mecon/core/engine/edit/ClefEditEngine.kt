package com.mecon.core.engine.edit

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeRange
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.tracks.Clef
import com.mecon.api.storage.tracks.StorageClefChange

/** Pure edits for staff clefs and mid-score clef changes. */
object ClefEditEngine {
    data class Target(
        val staffTrackId: TrackId,
        val onset: TimeCode,
        /**
         * When true, [onset] may be a line-start restatement. The edit resolves to the last real
         * clef change at or before [onset], or to the staff's initial clef when none exists.
         */
        val resolveExisting: Boolean = false,
    )

    data class Result(
        val score: RuntimeScore,
        val editedStaffTrackId: TrackId,
        val editedOnset: TimeCode,
        val editInterval: TimeRange,
    )

    fun setClef(runtime: RuntimeScore, target: Target, clef: Clef): Result? {
        val staff = runtime.staffTracks[target.staffTrackId] ?: return null
        val resolvedOnset = if (target.resolveExisting) {
            staff.clefChanges
                .filter { it.onset <= target.onset }
                .maxByOrNull { it.onset }
                ?.onset ?: TimeCode.ZERO
        } else if (target.onset.compareTo(TimeCode.START) == 0) {
            // Pointer insertion addresses the first musical slot as 1:0, while the initial staff
            // state is stored at the score origin 0:0. They are the same edit boundary.
            TimeCode.ZERO
        } else {
            target.onset
        }

        val updated = if (resolvedOnset == TimeCode.ZERO) {
            if (staff.clef == clef) return null
            staff.copy(clef = clef, clefChanges = staff.clefChanges.filter { it.onset != TimeCode.ZERO })
        } else {
            val existing = staff.clefChanges.firstOrNull { it.onset == resolvedOnset }
            if (existing?.clef == clef) return null
            val nextChanges = staff.clefChanges
                .filter { it.onset != resolvedOnset }
                .plus(StorageClefChange(resolvedOnset, clef))
                .sortedBy { it.onset }
            staff.copy(clefChanges = nextChanges)
        }

        val newStaffTracks = runtime.staffTracks + (staff.id to updated)
        val newRuntime = runtime.copy(
            staffTracks = newStaffTracks,
            staffGroups = runtime.staffGroups.map { StaffTrackOps.replaceStaffsInGroup(it, newStaffTracks) },
        )
        return Result(
            score = newRuntime,
            editedStaffTrackId = staff.id,
            editedOnset = resolvedOnset,
            editInterval = TimeRange(resolvedOnset, TimeCode.of(resolvedOnset.measure + 1, Fraction.ZERO)),
        )
    }
}
