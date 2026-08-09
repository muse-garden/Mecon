package com.mecon.theory.constraint

import com.mecon.theory.RuleId
import com.mecon.theory.SlotWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ConstraintProjectionTest {
    @Test
    fun projectsDeclarativeRuleAndSoftensItWithoutChangingPredicateMeaning() {
        val original = Constraint(
            expr = ConstraintExpr.Atom(
                ConstraintPredicate.NeighborTone(
                    ChordToneNeighborRequirement(
                        window = SlotWindow(1, 3),
                        sourceSlot = 2,
                        sourceTone = ChordTone.THIRD,
                        direction = ChordToneNeighborDirection.NEXT,
                        candidateScaleDegrees = setOf(1),
                    )
                )
            ),
            modality = ConstraintModality.Require,
            ruleId = RuleId("test.chapter.neighbor"),
        )

        val projected = requireNotNull(
            original.projectSlots(
                sourceSlotCount = 5,
                targetSlotBySource = mapOf(1 to 4, 2 to 5, 3 to 6),
                modality = ConstraintModality.Prefer(4.0),
            )
        )
        val predicate = assertIs<ConstraintPredicate.NeighborTone>(
            assertIs<ConstraintExpr.Atom>(projected.expr).predicate
        )

        assertEquals(SlotWindow(4, 6), predicate.requirement.window)
        assertEquals(5, predicate.requirement.sourceSlot)
        assertEquals(ConstraintModality.Prefer(4.0), projected.modality)
        assertEquals(SlotWindow(4, 6), projected.scope.window)
    }

    @Test
    fun dropsRelationWhenAnExplicitSourceSlotIsOutsideProjection() {
        val original = Constraint(
            ConstraintExpr.Atom(
                ConstraintPredicate.RootDiatonicMotion(0, 1, setOf(3))
            )
        )

        assertNull(original.projectSlots(3, mapOf(1 to 0, 2 to 1)))
    }
}
