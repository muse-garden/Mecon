package com.mecon.core.engine.edit

import com.mecon.api.primitive.BarlineType
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.StorageScore
import com.mecon.core.engine.computeScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BarlineEditEngineTest {
    private fun score(): RuntimeScore =
        RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions(measureCount = 3)))

    @Test
    fun explicitEndStyleFlowsThroughComputedLayer() {
        val result = BarlineEditEngine.set(score(), 1, BarlineType.DOUBLE)!!

        assertEquals(BarlineType.DOUBLE, result.score.getMeasure(1)?.endBarlineType)
        assertEquals(
            BarlineType.DOUBLE,
            computeScore(result.score).barlines.single { it.measureNumber == 1 }.type,
        )
    }

    @Test
    fun repeatBothWritesAdjacentSidesAndCount() {
        val result = BarlineEditEngine.set(score(), 1, BarlineType.REPEAT_BOTH, repeatCount = 3)!!

        assertTrue(result.score.getMeasure(1)!!.repeatEnd)
        assertEquals(3, result.score.getMeasure(1)!!.repeatCount)
        assertTrue(result.score.getMeasure(2)!!.repeatStart)
        assertEquals(
            BarlineType.REPEAT_BOTH,
            computeScore(result.score).barlines.single { it.measureNumber == 1 }.type,
        )
    }

    @Test
    fun replacingRepeatWithOrdinaryLineClearsBothSides() {
        val repeated = BarlineEditEngine.set(score(), 1, BarlineType.REPEAT_BOTH, 4)!!.score
        val ordinary = BarlineEditEngine.set(repeated, 1, BarlineType.DASHED)!!.score

        assertFalse(ordinary.getMeasure(1)!!.repeatEnd)
        assertFalse(ordinary.getMeasure(2)!!.repeatStart)
        assertEquals(BarlineType.DASHED, ordinary.getMeasure(1)!!.endBarlineType)
    }

    @Test
    fun openingBoundarySupportsExplicitStylesAndRepeatStart() {
        val double = BarlineEditEngine.set(score(), 0, BarlineType.DOUBLE)!!.score
        assertEquals(BarlineType.DOUBLE, computeScore(double).barlines.first().type)

        val repeat = BarlineEditEngine.set(double, 0, BarlineType.REPEAT_LEFT)!!.score
        assertTrue(repeat.getMeasure(1)!!.repeatStart)
        assertEquals(BarlineType.REPEAT_LEFT, computeScore(repeat).barlines.first().type)
    }

    @Test
    fun repeatCountCanBeEditedFromTheMatchingStartSign() {
        val withStart = BarlineEditEngine.set(score(), 0, BarlineType.REPEAT_LEFT)!!.score
        val repeated = BarlineEditEngine.set(withStart, 2, BarlineType.REPEAT_RIGHT, 2)!!.score
        val edited = BarlineEditEngine.setRepeatCount(repeated, 0, 4)!!.score

        assertEquals(4, edited.getMeasure(2)!!.repeatCount)
        assertEquals(4, BarlineEditEngine.repeatCountAt(edited, 0))
    }
}
