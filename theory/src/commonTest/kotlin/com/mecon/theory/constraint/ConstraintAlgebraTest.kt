package com.mecon.theory.constraint

import com.mecon.theory.SlotWindow
import kotlin.test.Test
import kotlin.test.assertEquals

class ConstraintAlgebraTest {
    private val a = ConstraintPredicate.DistinctIdentities(AllDifferentRequirement(SlotWindow(0, 1)))
    private val b = ConstraintPredicate.CommonToneWithPrevious(AdjacentCommonToneRequirement(SlotWindow(0, 1)))

    @Test
    fun andUsesWorstKleeneValue() {
        val expr = ConstraintExpr.And(listOf(ConstraintExpr.Atom(a), ConstraintExpr.Atom(b)))
        assertEquals(
            ConstraintTruth.VIOLATED,
            expr.evaluateTruth { if (it == a) ConstraintTruth.UNDETERMINED else ConstraintTruth.VIOLATED },
        )
        assertEquals(
            ConstraintTruth.UNDETERMINED,
            expr.evaluateTruth { if (it == a) ConstraintTruth.UNDETERMINED else ConstraintTruth.SATISFIED },
        )
    }

    @Test
    fun orOnlyViolatesAfterEveryBranchViolates() {
        val expr = ConstraintExpr.Or(
            listOf(
                ConstraintBranch(ConstraintExpr.Atom(a)),
                ConstraintBranch(ConstraintExpr.Atom(b)),
            )
        )
        assertEquals(
            ConstraintTruth.UNDETERMINED,
            expr.evaluateTruth { if (it == a) ConstraintTruth.UNDETERMINED else ConstraintTruth.VIOLATED },
        )
        assertEquals(ConstraintTruth.VIOLATED, expr.evaluateTruth { ConstraintTruth.VIOLATED })
    }

    @Test
    fun notPreservesUndeterminedAndSwapsDecidedValues() {
        val expr = ConstraintExpr.Not(ConstraintExpr.Atom(a))
        assertEquals(ConstraintTruth.VIOLATED, expr.evaluateTruth { ConstraintTruth.SATISFIED })
        assertEquals(ConstraintTruth.SATISFIED, expr.evaluateTruth { ConstraintTruth.VIOLATED })
        assertEquals(ConstraintTruth.UNDETERMINED, expr.evaluateTruth { ConstraintTruth.UNDETERMINED })
    }
}
