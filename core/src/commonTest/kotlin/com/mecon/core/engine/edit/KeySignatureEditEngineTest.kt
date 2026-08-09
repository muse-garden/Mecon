package com.mecon.core.engine.edit

import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.PitchClass
import com.mecon.api.primitive.TimeCode
import com.mecon.api.storage.StorageScore
import com.mecon.api.runtime.RuntimeScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class KeySignatureEditEngineTest {
    private fun runtime(storage: StorageScore) = RuntimeScore.fromStorage(storage)
    @Test
    fun editInitialKeyUpdatesDefaultAndStaffKeys() {
        val score = runtime(StorageScore.create(StorageScore.CreationOptions(keySignature = KeySignature.C_MAJOR)))

        val result = assertNotNull(
            KeySignatureEditEngine.setKeySignature(
                score,
                KeySignatureEditEngine.Target(TimeCode.ZERO),
                KeySignature.G_MAJOR,
            )
        )

        assertEquals(KeySignature.G_MAJOR, result.score.defaultKeySignature)
        assertEquals(
            setOf(KeySignature.G_MAJOR),
            result.score.staffTracks.values.map { it.keySignature }.toSet(),
        )
        assertEquals(TimeCode.ZERO, result.editedOnset)
    }

    @Test
    fun insertAtExistingMeasureReplacesExplicitKey() {
        val score = runtime(StorageScore.create(StorageScore.CreationOptions(keySignature = KeySignature.C_MAJOR, measureCount = 4)))
        val m2 = TimeCode.ofMeasure(2)
        val withChange = assertNotNull(
            KeySignatureEditEngine.setKeySignature(
                score,
                KeySignatureEditEngine.Target(m2),
                KeySignature.G_MAJOR,
            )
        ).score

        val result = assertNotNull(
            KeySignatureEditEngine.setKeySignature(
                withChange,
                KeySignatureEditEngine.Target(m2),
                KeySignature.D_MAJOR,
            )
        )

        assertEquals(KeySignature.D_MAJOR, result.score.getKeySignatureAt(2))
        assertEquals(KeySignature.D_MAJOR, result.score.getMeasure(2)?.keySignature)
        assertEquals(1, result.score.measures.count { it.value.hasExplicitKeySignature })
    }

    @Test
    fun lineStartRestatementWritesVisibleMeasure() {
        val score = runtime(StorageScore.create(StorageScore.CreationOptions(keySignature = KeySignature.C_MAJOR, measureCount = 8)))
        val lineStart = TimeCode.ofMeasure(5)

        val result = assertNotNull(
            KeySignatureEditEngine.setKeySignature(
                score,
                KeySignatureEditEngine.Target(lineStart),
                KeySignature.F_MAJOR,
            )
        )

        assertEquals(5, result.editedMeasure)
        assertEquals(lineStart, result.editedOnset)
        assertEquals(KeySignature.C_MAJOR, result.score.getKeySignatureAt(4))
        assertEquals(KeySignature.F_MAJOR, result.score.getKeySignatureAt(5))
        assertEquals(KeySignature.F_MAJOR, result.score.getMeasure(5)?.keySignature)
    }

    @Test
    fun sameEffectiveKeyIsNoOp() {
        val score = runtime(StorageScore.create(StorageScore.CreationOptions(keySignature = KeySignature.C_MAJOR)))

        assertNull(
            KeySignatureEditEngine.setKeySignature(
                score,
                KeySignatureEditEngine.Target(TimeCode.ZERO),
                KeySignature.C_MAJOR,
            )
        )
    }

    @Test
    fun cMajorCanCancelAnEarlierNonDefaultKey() {
        val score = runtime(StorageScore.create(StorageScore.CreationOptions(keySignature = KeySignature.G_MAJOR, measureCount = 4)))

        val result = assertNotNull(
            KeySignatureEditEngine.setKeySignature(
                score,
                KeySignatureEditEngine.Target(TimeCode.ofMeasure(3)),
                KeySignature.C_MAJOR,
            )
        )

        assertEquals(KeySignature.C_MAJOR, result.score.getKeySignatureAt(3))
        assertEquals(PitchClass.C, result.score.getMeasure(3)?.keySignature?.root)
    }
}
