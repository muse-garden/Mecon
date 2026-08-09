package com.mecon.core.computed

import com.mecon.api.primitive.*
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.StorageMeasure
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.tracks.StorageKeySignatureChange
import com.mecon.core.engine.computeScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeySignatureChangeTest {

    @Test
    fun initialKeySignatureOnly() {
        val runtime = createScoreWithKeyChanges(KeySignature.G_MAJOR, emptyList())
        val computed = computeScore(runtime)

        val keySigs = computed.allKeySignaturesSorted()
        assertEquals(1, keySigs.size)
        assertEquals(KeySignature.G_MAJOR, keySigs[0].keySignature)
        assertTrue(keySigs[0].isInitial)
        assertTrue(keySigs[0].cancellationNaturals.isEmpty())
    }

    @Test
    fun sharpToFlat_cancelsAllSharps() {
        // E major (4♯) → Ab major (4♭)
        val eMajor = KeySignature(PitchClass(4), Mode.MAJOR)   // 4 sharps
        val abMajor = KeySignature(PitchClass(8), Mode.MAJOR)  // 4 flats

        val changes = listOf(
            StorageKeySignatureChange(TimeCode.ofMeasure(3), abMajor)
        )
        val runtime = createScoreWithKeyChanges(eMajor, changes, measureCount = 4)
        val computed = computeScore(runtime)

        val keySigs = computed.allKeySignaturesSorted()
        assertEquals(2, keySigs.size)

        val change = keySigs[1]
        assertEquals(abMajor, change.keySignature)
        assertEquals(false, change.isInitial)
        // All 4 sharps (F, C, G, D) should be cancelled
        assertEquals(4, change.cancellationNaturals.size)
        assertTrue(change.cancellationNaturals.all { it.fromSharpKey })
        val cancelledNames = change.cancellationNaturals.map { it.noteName }.toSet()
        assertEquals(setOf(NoteName.F, NoteName.C, NoteName.G, NoteName.D), cancelledNames)
    }

    @Test
    fun flatToSharp_cancelsAllFlats() {
        // Ab major (4♭) → B major (5♯)
        val abMajor = KeySignature(PitchClass(8), Mode.MAJOR)   // 4 flats
        val bMajor = KeySignature(PitchClass(11), Mode.MAJOR)   // 5 sharps

        val changes = listOf(
            StorageKeySignatureChange(TimeCode.ofMeasure(3), bMajor)
        )
        val runtime = createScoreWithKeyChanges(abMajor, changes, measureCount = 4)
        val computed = computeScore(runtime)

        val change = computed.allKeySignaturesSorted()[1]
        assertEquals(4, change.cancellationNaturals.size)
        assertTrue(change.cancellationNaturals.all { !it.fromSharpKey })
    }

    @Test
    fun moreSharps_noCancellation() {
        // G major (1♯) → D major (2♯)
        val changes = listOf(
            StorageKeySignatureChange(TimeCode.ofMeasure(2), KeySignature.D_MAJOR)
        )
        val runtime = createScoreWithKeyChanges(KeySignature.G_MAJOR, changes, measureCount = 3)
        val computed = computeScore(runtime)

        val change = computed.allKeySignaturesSorted()[1]
        assertEquals(KeySignature.D_MAJOR, change.keySignature)
        assertTrue(change.cancellationNaturals.isEmpty())
    }

    @Test
    fun fewerSharps_cancelsRemovedSharps() {
        // A major (3♯: F#, C#, G#) → G major (1♯: F#)
        val aMajor = KeySignature(PitchClass(9), Mode.MAJOR)

        val changes = listOf(
            StorageKeySignatureChange(TimeCode.ofMeasure(2), KeySignature.G_MAJOR)
        )
        val runtime = createScoreWithKeyChanges(aMajor, changes, measureCount = 3)
        val computed = computeScore(runtime)

        val change = computed.allKeySignaturesSorted()[1]
        // C# and G# should be cancelled
        assertEquals(2, change.cancellationNaturals.size)
        assertTrue(change.cancellationNaturals.all { it.fromSharpKey })
        val cancelledNames = change.cancellationNaturals.map { it.noteName }.toSet()
        assertEquals(setOf(NoteName.C, NoteName.G), cancelledNames)
    }

    @Test
    fun toCMajor_cancelsAll() {
        // G major (1♯) → C major (0)
        val changes = listOf(
            StorageKeySignatureChange(TimeCode.ofMeasure(2), KeySignature.C_MAJOR)
        )
        val runtime = createScoreWithKeyChanges(KeySignature.G_MAJOR, changes, measureCount = 3)
        val computed = computeScore(runtime)

        val change = computed.allKeySignaturesSorted()[1]
        assertEquals(KeySignature.C_MAJOR, change.keySignature)
        assertEquals(1, change.cancellationNaturals.size)
        assertEquals(NoteName.F, change.cancellationNaturals[0].noteName)
        assertTrue(change.cancellationNaturals[0].fromSharpKey)
    }

    @Test
    fun fromCMajor_noCancellation() {
        // C major (0) → G major (1♯)
        val changes = listOf(
            StorageKeySignatureChange(TimeCode.ofMeasure(2), KeySignature.G_MAJOR)
        )
        val runtime = createScoreWithKeyChanges(KeySignature.C_MAJOR, changes, measureCount = 3)
        val computed = computeScore(runtime)

        val change = computed.allKeySignaturesSorted()[1]
        assertEquals(KeySignature.G_MAJOR, change.keySignature)
        assertTrue(change.cancellationNaturals.isEmpty())
    }

    @Test
    fun keyInheritedAcrossMeasures() {
        // Change at measure 2, measure 3 should inherit measure 2's key
        val changes = listOf(
            StorageKeySignatureChange(TimeCode.ofMeasure(2), KeySignature.D_MAJOR)
        )
        val runtime = createScoreWithKeyChanges(KeySignature.C_MAJOR, changes, measureCount = 4)

        // Verify runtime measures have correct key signatures
        assertEquals(KeySignature.C_MAJOR, runtime.getKeySignatureAt(1))
        assertEquals(KeySignature.D_MAJOR, runtime.getKeySignatureAt(2))
        assertEquals(KeySignature.D_MAJOR, runtime.getKeySignatureAt(3))
        assertEquals(KeySignature.D_MAJOR, runtime.getKeySignatureAt(4))
    }

    @Test
    fun multipleKeyChanges() {
        // C → G (m2) → D (m3) → A (m4)
        val aMajor = KeySignature(PitchClass(9), Mode.MAJOR)
        val changes = listOf(
            StorageKeySignatureChange(TimeCode.ofMeasure(2), KeySignature.G_MAJOR),
            StorageKeySignatureChange(TimeCode.ofMeasure(3), KeySignature.D_MAJOR),
            StorageKeySignatureChange(TimeCode.ofMeasure(4), aMajor),
        )
        val runtime = createScoreWithKeyChanges(KeySignature.C_MAJOR, changes, measureCount = 5)
        val computed = computeScore(runtime)

        val keySigs = computed.allKeySignaturesSorted()
        assertEquals(4, keySigs.size)  // initial + 3 changes

        // All adding sharps → no cancellations
        assertTrue(keySigs[1].cancellationNaturals.isEmpty())
        assertTrue(keySigs[2].cancellationNaturals.isEmpty())
        assertTrue(keySigs[3].cancellationNaturals.isEmpty())
    }

    private fun createScoreWithKeyChanges(
        initialKey: KeySignature,
        keyChanges: List<StorageKeySignatureChange>,
        measureCount: Int = 4
    ): RuntimeScore {
        val measures = (1..measureCount).map { StorageMeasure(number = it) }
        val base = StorageScore.create(StorageScore.CreationOptions("Test", TimeSignature.COMMON, initialKey))
        val storage = base.copy(
            measures = measures,
            globalTrack = base.globalTrack.copy(
                events = keyChanges
            )
        )
        return RuntimeScore.fromStorage(storage)
    }
}
