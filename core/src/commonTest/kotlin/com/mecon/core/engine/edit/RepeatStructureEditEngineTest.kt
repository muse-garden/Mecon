package com.mecon.core.engine.edit

import com.mecon.api.computed.ComputedVoltaAttachment
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.NavigationMark
import com.mecon.api.storage.NavigationMarkOffset
import com.mecon.api.storage.StorageScore
import com.mecon.core.engine.computeScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepeatStructureEditEngineTest {
    private fun score(): RuntimeScore =
        RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions(measureCount = 6)))

    @Test
    fun voltaExtendsFromNextMeasureThroughFollowingRepeatEnd() {
        val repeated = BarlineEditEngine.set(score(), 4, com.mecon.api.primitive.BarlineType.REPEAT_RIGHT)!!.score
        val result = RepeatStructureEditEngine.toggleVolta(repeated, boundaryMeasure = 1, number = 1)!!

        assertEquals(emptySet(), result.score.getMeasure(1)!!.voltaNumbers)
        for (measure in 2..4) assertEquals(setOf(1), result.score.getMeasure(measure)!!.voltaNumbers)
        assertEquals(emptySet(), result.score.getMeasure(5)!!.voltaNumbers)
        assertEquals(2, computeScore(result.score).voltaEndings.single().startMeasure)
        assertEquals(4, computeScore(result.score).voltaEndings.single().endMeasure)
    }

    @Test
    fun navigationMarkFlowsThroughComputedLayer() {
        val result = RepeatStructureEditEngine.toggleNavigationMark(
            score(),
            boundaryMeasure = 3,
            mark = NavigationMark.DAL_SEGNO_AL_CODA,
        )!!

        assertTrue(NavigationMark.DAL_SEGNO_AL_CODA in result.score.getMeasure(3)!!.navigationMarks)
        assertEquals(
            NavigationMark.DAL_SEGNO_AL_CODA,
            computeScore(result.score).navigationMarks.single().mark,
        )
    }

    @Test
    fun pairInsertionCreatesSecondEndingAfterRepeatAndItCanBeExtended() {
        val repeated = BarlineEditEngine.set(
            score(), 3, com.mecon.api.primitive.BarlineType.REPEAT_RIGHT
        )!!.score
        val inserted = RepeatStructureEditEngine.toggleVoltaPair(repeated, boundaryMeasure = 0)!!.score

        assertEquals(setOf(1), inserted.getMeasure(1)!!.voltaNumbers)
        assertEquals(setOf(1), inserted.getMeasure(3)!!.voltaNumbers)
        assertEquals(setOf(2), inserted.getMeasure(4)!!.voltaNumbers)
        assertEquals(emptySet(), inserted.getMeasure(5)!!.voltaNumbers)
        assertEquals(
            listOf(setOf(1), setOf(2)),
            computeScore(inserted).staffAttachments
                .filterIsInstance<ComputedVoltaAttachment>()
                .map { it.ending.numbers },
        )

        val resized = RepeatStructureEditEngine.resizeSecondVolta(inserted, 4, 5)!!.score
        assertEquals(setOf(2), resized.getMeasure(4)!!.voltaNumbers)
        assertEquals(setOf(2), resized.getMeasure(5)!!.voltaNumbers)
        assertEquals(5, computeScore(resized).voltaEndings.single { 2 in it.numbers }.endMeasure)
    }

    @Test
    fun firstEndingStartCanMoveAndEitherEndingCanBeDeleted() {
        val repeated = BarlineEditEngine.set(
            score(), 3, com.mecon.api.primitive.BarlineType.REPEAT_RIGHT
        )!!.score
        val inserted = RepeatStructureEditEngine.toggleVoltaPair(repeated, boundaryMeasure = 0)!!.score
        val moved = RepeatStructureEditEngine.resizeFirstVoltaStart(inserted, 1, 2)!!.score

        assertEquals(emptySet(), moved.getMeasure(1)!!.voltaNumbers)
        assertEquals(setOf(1), moved.getMeasure(2)!!.voltaNumbers)
        assertEquals(setOf(1), moved.getMeasure(3)!!.voltaNumbers)

        val deletedFirst = RepeatStructureEditEngine.deleteVolta(moved, 2, 3, setOf(1))!!.score
        assertEquals(emptySet(), deletedFirst.getMeasure(2)!!.voltaNumbers)
        assertEquals(setOf(2), deletedFirst.getMeasure(4)!!.voltaNumbers)
        val deletedSecond = RepeatStructureEditEngine.deleteVolta(deletedFirst, 4, 4, setOf(2))!!.score
        assertEquals(emptySet(), deletedSecond.getMeasure(4)!!.voltaNumbers)
    }

    @Test
    fun navigationMarkOffsetMovesWithMarkAndIsRemovedOnDelete() {
        val inserted = RepeatStructureEditEngine.toggleNavigationMark(
            score(), 2, NavigationMark.SEGNO
        )!!.score
        val offset = NavigationMarkOffset(1.25f, -0.75f)
        val moved = RepeatStructureEditEngine.moveNavigationMark(
            inserted, 2, 2, NavigationMark.SEGNO, offset
        )!!.score
        assertEquals(offset, moved.getMeasure(2)!!.navigationMarkOffsets[NavigationMark.SEGNO])

        val deleted = RepeatStructureEditEngine.deleteNavigationMark(
            moved, 2, NavigationMark.SEGNO
        )!!.score
        assertTrue(NavigationMark.SEGNO !in deleted.getMeasure(2)!!.navigationMarks)
        assertTrue(NavigationMark.SEGNO !in deleted.getMeasure(2)!!.navigationMarkOffsets)
    }

    @Test
    fun navigationMarkCanSnapToAnotherBoundary() {
        val inserted = RepeatStructureEditEngine.toggleNavigationMark(
            score(), 2, NavigationMark.DAL_SEGNO
        )!!.score
        val moved = RepeatStructureEditEngine.moveNavigationMark(
            inserted,
            boundaryMeasure = 2,
            targetBoundaryMeasure = 4,
            mark = NavigationMark.DAL_SEGNO,
            offset = NavigationMarkOffset(0f, -1f),
        )!!.score

        assertTrue(NavigationMark.DAL_SEGNO !in moved.getMeasure(2)!!.navigationMarks)
        assertTrue(NavigationMark.DAL_SEGNO in moved.getMeasure(4)!!.navigationMarks)
        assertEquals(
            NavigationMarkOffset(0f, -1f),
            moved.getMeasure(4)!!.navigationMarkOffsets[NavigationMark.DAL_SEGNO],
        )
    }
}
