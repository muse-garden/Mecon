package com.mecon.core.engine.edit

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.tracks.Clef
import com.mecon.api.storage.tracks.StorageClefChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ClefEditEngineTest {
    @Test
    fun insertClefChangeAddsSortedChange() {
        val runtime = RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("ClefEdit")))
        val staffId = runtime.staffTracks.keys.first()
        val onset = TimeCode.of(2, Fraction.ZERO)

        val result = assertNotNull(
            ClefEditEngine.setClef(runtime, ClefEditEngine.Target(staffId, onset), Clef.BASS)
        )

        assertEquals(
            listOf(StorageClefChange(onset, Clef.BASS)),
            result.score.staffTracks.getValue(staffId).clefChanges,
        )
        assertEquals(onset, result.editedOnset)
    }

    @Test
    fun insertAtExistingClefChangeReplacesIt() {
        val base = StorageScore.create(StorageScore.CreationOptions("ClefEdit"))
        val staffId = base.staffTracks.keys.first()
        val onset = TimeCode.of(2, Fraction.ZERO)
        val storage = base.copy(
            staffTracks = base.staffTracks + (
                staffId to base.staffTracks.getValue(staffId)
                    .copy(clefChanges = listOf(StorageClefChange(onset, Clef.BASS)))
                )
        )
        val runtime = RuntimeScore.fromStorage(storage)

        val result = assertNotNull(
            ClefEditEngine.setClef(runtime, ClefEditEngine.Target(staffId, onset), Clef.ALTO)
        )

        assertEquals(
            listOf(StorageClefChange(onset, Clef.ALTO)),
            result.score.staffTracks.getValue(staffId).clefChanges,
        )
    }

    @Test
    fun resolvingLineStartRestatementEditsPreviousRealChange() {
        val base = StorageScore.create(StorageScore.CreationOptions("ClefEdit"))
        val staffId = base.staffTracks.keys.first()
        val m2 = TimeCode.of(2, Fraction.ZERO)
        val storage = base.copy(
            staffTracks = base.staffTracks + (
                staffId to base.staffTracks.getValue(staffId)
                    .copy(clefChanges = listOf(StorageClefChange(m2, Clef.BASS)))
                )
        )
        val runtime = RuntimeScore.fromStorage(storage)

        val result = assertNotNull(
            ClefEditEngine.setClef(
                runtime,
                ClefEditEngine.Target(staffId, TimeCode.of(4, Fraction.ZERO), resolveExisting = true),
                Clef.TENOR,
            )
        )

        assertEquals(m2, result.editedOnset)
        assertEquals(
            listOf(StorageClefChange(m2, Clef.TENOR)),
            result.score.staffTracks.getValue(staffId).clefChanges,
        )
    }

    @Test
    fun resolvingBeforeFirstChangeEditsInitialClef() {
        val runtime = RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("ClefEdit")))
        val staffId = runtime.staffTracks.keys.first()

        val result = assertNotNull(
            ClefEditEngine.setClef(
                runtime,
                ClefEditEngine.Target(staffId, TimeCode.of(1, Fraction.ZERO), resolveExisting = true),
                Clef.BASS,
            )
        )

        assertEquals(TimeCode.ZERO, result.editedOnset)
        assertEquals(Clef.BASS, result.score.staffTracks.getValue(staffId).clef)
        assertEquals(emptyList(), result.score.staffTracks.getValue(staffId).clefChanges)
    }

    @Test
    fun insertingAtFirstMusicalSlotEditsInitialClef() {
        val runtime = RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("ClefEdit")))
        val staffId = runtime.staffTracks.keys.first()

        val result = assertNotNull(
            ClefEditEngine.setClef(
                runtime,
                ClefEditEngine.Target(staffId, TimeCode.of(1, Fraction.ZERO)),
                Clef.BASS,
            ),
        )

        assertEquals(TimeCode.ZERO, result.editedOnset)
        assertEquals(Clef.BASS, result.score.staffTracks.getValue(staffId).clef)
        assertEquals(emptyList(), result.score.staffTracks.getValue(staffId).clefChanges)
    }

    @Test
    fun sameClefIsNoOp() {
        val runtime = RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions("ClefEdit")))
        val staffId = runtime.staffTracks.keys.first()

        assertNull(
            ClefEditEngine.setClef(
                runtime,
                ClefEditEngine.Target(staffId, TimeCode.ZERO),
                runtime.staffTracks.getValue(staffId).clef,
            )
        )
    }
}
