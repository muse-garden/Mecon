package com.mecon.api.computed

import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.HasOnset
import com.mecon.api.runtime.TimeIndexedList
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the incremental aligners ([ReferenceAligner] / [BilateralAligner]):
 *
 *  1. **Correctness (golden rule)** — after any sequence of random edits, `update(a, b).aligned` is
 *     value-equal to a full `CalcBuilder.align*(a, b)`.
 *  2. **Effectiveness** — a localized edit re-pairs only a bounded number of rows
 *     ([ReferenceAligner.lastRecomputedRowCount]), far fewer than the whole track; `offset != 0` widens the
 *     B-side window to the track end while the A side stays tight.
 */
class CalcBuilderIncrementalTest {

    private data class Ev(val id: Int, val tick: Int, val payload: Int) : HasOnset {
        override val onset: TimeCode get() = tc(tick)
    }

    private fun til(items: List<Ev>) = TimeIndexedList.of(items)

    // ----------------------------------------------------------------------------------------------------
    // Correctness: incremental == full, over random edit sequences
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun referenceAlignerMatchesFullUnderRandomEdits() {
        for (strict in listOf(false, true)) {
            for (offset in listOf(0, 1, -1)) {
                val rnd = Random(7000 + offset + if (strict) 500 else 0)
                var aList = randomTrack(rnd)
                var bList = randomTrack(rnd)
                var nextId = 10_000
                var aligner = ReferenceAligner.build(til(aList), til(bList), strict, offset)
                assertEquals(fullRef(aList, bList, strict, offset), aligner.aligned.toList(), "build strict=$strict offset=$offset")

                repeat(80) { step ->
                    if (rnd.nextBoolean()) {
                        val (l, id) = randomEdit(aList, rnd, nextId); aList = l; nextId = id
                    } else {
                        val (l, id) = randomEdit(bList, rnd, nextId); bList = l; nextId = id
                    }
                    aligner = aligner.update(til(aList), til(bList))
                    assertEquals(
                        fullRef(aList, bList, strict, offset),
                        aligner.aligned.toList(),
                        "strict=$strict offset=$offset step=$step\nA=$aList\nB=$bList",
                    )
                }
            }
        }
    }

    @Test
    fun bilateralAlignerMatchesFullUnderRandomEdits() {
        val rnd = Random(9090)
        var aList = randomTrack(rnd)
        var bList = randomTrack(rnd)
        var nextId = 10_000
        var aligner = BilateralAligner.build(til(aList), til(bList))
        assertEquals(CalcBuilder.alignBilateral(til(aList), til(bList)).toList(), aligner.aligned.toList())

        repeat(120) { step ->
            if (rnd.nextBoolean()) {
                val (l, id) = randomEdit(aList, rnd, nextId); aList = l; nextId = id
            } else {
                val (l, id) = randomEdit(bList, rnd, nextId); bList = l; nextId = id
            }
            aligner = aligner.update(til(aList), til(bList))
            assertEquals(
                CalcBuilder.alignBilateral(til(aList), til(bList)).toList(),
                aligner.aligned.toList(),
                "step=$step\nA=$aList\nB=$bList",
            )
        }
    }

    // ----------------------------------------------------------------------------------------------------
    // Targeted correctness: carry-forward boundary cases
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun referenceAlignLeBoundaryIncludesEqualOnset() {
        // A note at the exact onset of a chord must match it (alignLe uses <=); alignL (<) must not.
        val a = listOf(Ev(1, 4, 0)) // tick 4
        val b = listOf(Ev(100, 4, 0))
        assertEquals(b[0], CalcBuilder.alignLe(til(a), til(b)).toList()[0].events.second)
        assertEquals(null, CalcBuilder.alignL(til(a), til(b)).toList()[0].events.second)
    }

    @Test
    fun bilateralMidTrackRemovalRevertsCarryForward() {
        // B = b0@0, b1@8, b2@16 ; A spans across. Remove b1 → A rows in [8,16) revert to b0.
        val a = (0..20 step 2).map { Ev(it, it, 0) }
        val bBefore = listOf(Ev(100, 0, 0), Ev(101, 8, 0), Ev(102, 16, 0))
        val bAfter = listOf(Ev(100, 0, 0), Ev(102, 16, 0))
        var aligner = BilateralAligner.build(til(a), til(bBefore))
        aligner = aligner.update(til(a), til(bAfter))
        assertEquals(CalcBuilder.alignBilateral(til(a), til(bAfter)).toList(), aligner.aligned.toList())
    }

    @Test
    fun aOnsetMoveAcrossMeasures() {
        val a = listOf(Ev(1, 0, 0), Ev(2, 5, 0), Ev(3, 30, 0))
        val b = listOf(Ev(100, 2, 0), Ev(101, 20, 0))
        var aligner = ReferenceAligner.build(til(a), til(b))
        val moved = listOf(Ev(1, 0, 0), Ev(2, 25, 0), Ev(3, 30, 0)) // move id=2 from tick 5 → 25
        aligner = aligner.update(til(moved), til(b))
        assertEquals(CalcBuilder.alignLe(til(moved), til(b)).toList(), aligner.aligned.toList())
    }

    @Test
    fun emptyAndSingleEventEdges() {
        val empty = emptyList<Ev>()
        var ref = ReferenceAligner.build(til(empty), til(empty))
        assertEquals(emptyList(), ref.aligned.toList())
        ref = ref.update(til(listOf(Ev(1, 3, 0))), til(empty))
        assertEquals(CalcBuilder.alignLe(til(listOf(Ev(1, 3, 0))), til(empty)).toList(), ref.aligned.toList())

        var bi = BilateralAligner.build(til(empty), til(empty))
        bi = bi.update(til(listOf(Ev(1, 3, 0))), til(listOf(Ev(2, 1, 0))))
        assertEquals(
            CalcBuilder.alignBilateral(til(listOf(Ev(1, 3, 0))), til(listOf(Ev(2, 1, 0)))).toList(),
            bi.aligned.toList(),
        )
    }

    // ----------------------------------------------------------------------------------------------------
    // Effectiveness: localized edit → small recompute
    // ----------------------------------------------------------------------------------------------------

    private fun denseA() = (0 until 50).map { Ev(it, it, 0) }                 // 50 notes, ticks 0..49
    private fun sparseB() = (0..40 step 10).map { Ev(1000 + it, it, 0) }      // chords every 10 ticks

    @Test
    fun referenceAChangeRecomputesOneRow() {
        val a = denseA(); val b = sparseB()
        var aligner = ReferenceAligner.build(til(a), til(b))
        val edited = a.map { if (it.tick == 25) it.copy(payload = 9) else it }
        aligner = aligner.update(til(edited), til(b))
        assertEquals(1, aligner.lastRecomputedRowCount, "single A change → 1 row")
        assertEquals(CalcBuilder.alignLe(til(edited), til(b)).toList(), aligner.aligned.toList())
    }

    @Test
    fun referenceARemovalRecomputesNoneButDeletes() {
        val a = denseA(); val b = sparseB()
        var aligner = ReferenceAligner.build(til(a), til(b))
        val edited = a.filterNot { it.tick == 25 }
        aligner = aligner.update(til(edited), til(b))
        assertEquals(0, aligner.lastRecomputedRowCount, "removal re-pairs nothing")
        assertEquals(CalcBuilder.alignLe(til(edited), til(b)).toList(), aligner.aligned.toList())
        assertEquals(49, aligner.aligned.toList().size)
    }

    @Test
    fun referenceBChangeRecomputesOnlyTheGap() {
        val a = denseA(); val b = sparseB()
        var aligner = ReferenceAligner.build(til(a), til(b))
        // change chord at tick 20 → affects A rows in [20, 30) = 10 rows.
        val editedB = b.map { if (it.tick == 20) it.copy(payload = 7) else it }
        aligner = aligner.update(til(a), til(editedB))
        assertEquals(10, aligner.lastRecomputedRowCount, "B change → only the gap to the next chord")
        assertTrue(aligner.lastRecomputedRowCount < a.size)
        assertEquals(CalcBuilder.alignLe(til(a), til(editedB)).toList(), aligner.aligned.toList())
    }

    @Test
    fun offsetWidensBSideButKeepsASideTight() {
        val a = denseA(); val b = sparseB()
        var aligner = ReferenceAligner.build(til(a), til(b), strict = false, offset = 1)

        // B change with offset != 0 → index-based match can shift any row → full reference rebuild (50).
        val editedB = b.map { if (it.tick == 20) it.copy(payload = 7) else it }
        val afterB = aligner.update(til(a), til(editedB))
        assertEquals(a.size, afterB.lastRecomputedRowCount, "offset != 0 B change → full reference rebuild")
        assertEquals(CalcBuilder.alignLe(til(a), til(editedB), offset = 1).toList(), afterB.aligned.toList())

        // A change still tight even with offset != 0.
        val editedA = a.map { if (it.tick == 25) it.copy(payload = 9) else it }
        val afterA = aligner.update(til(editedA), til(b))
        assertEquals(1, afterA.lastRecomputedRowCount, "A change stays tight under offset")
        assertEquals(CalcBuilder.alignLe(til(editedA), til(b), offset = 1).toList(), afterA.aligned.toList())
    }

    @Test
    fun bilateralLocalizedChangeIsBounded() {
        val a = denseA(); val b = sparseB()
        var aligner = BilateralAligner.build(til(a), til(b))

        val editedA = a.map { if (it.tick == 25) it.copy(payload = 9) else it }
        val afterA = aligner.update(til(editedA), til(b))
        assertEquals(1, afterA.lastRecomputedRowCount, "single A change → 1 union row")
        assertEquals(CalcBuilder.alignBilateral(til(editedA), til(b)).toList(), afterA.aligned.toList())

        val editedB = b.map { if (it.tick == 20) it.copy(payload = 7) else it }
        val afterB = aligner.update(til(a), til(editedB))
        assertEquals(10, afterB.lastRecomputedRowCount, "B change → union rows in [20, 30)")
        assertEquals(CalcBuilder.alignBilateral(til(a), til(editedB)).toList(), afterB.aligned.toList())
    }

    @Test
    fun noChangeRecomputesNothing() {
        val a = denseA(); val b = sparseB()
        val aligner = ReferenceAligner.build(til(a), til(b))
        val same = aligner.update(til(a.toList()), til(b.toList()))
        assertEquals(0, same.lastRecomputedRowCount)
        assertEquals(aligner.aligned.toList(), same.aligned.toList())
    }

    // ----------------------------------------------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------------------------------------------

    private fun fullRef(a: List<Ev>, b: List<Ev>, strict: Boolean, offset: Int) =
        (if (strict) CalcBuilder.alignL(til(a), til(b), offset) else CalcBuilder.alignLe(til(a), til(b), offset)).toList()

    private fun randomTrack(rnd: Random): List<Ev> {
        val n = rnd.nextInt(0, 10)
        val ticks = mutableSetOf<Int>()
        while (ticks.size < n) ticks.add(rnd.nextInt(0, 40))
        return ticks.sorted().mapIndexed { i, t -> Ev(i, t, rnd.nextInt(0, 3)) }
    }

    /** Returns the edited list plus the next free id. */
    private fun randomEdit(list: List<Ev>, rnd: Random, nextId: Int): Pair<List<Ev>, Int> {
        val out = list.toMutableList()
        val used = out.map { it.tick }.toMutableSet()
        var id = nextId
        when (rnd.nextInt(4)) {
            0 -> { val t = rnd.nextInt(0, 40); if (t !in used) out.add(Ev(id++, t, rnd.nextInt(0, 3))) }
            1 -> if (out.isNotEmpty()) out.removeAt(rnd.nextInt(out.size))
            2 -> if (out.isNotEmpty()) { val i = rnd.nextInt(out.size); out[i] = out[i].copy(payload = out[i].payload + 1) }
            3 -> if (out.isNotEmpty()) { val i = rnd.nextInt(out.size); val t = rnd.nextInt(0, 40); if (t !in used) out[i] = out[i].copy(tick = t) }
        }
        return out to id
    }
}

private fun tc(tick: Int): TimeCode = TimeCode.of(tick / 4 + 1, tick % 4, 4)
