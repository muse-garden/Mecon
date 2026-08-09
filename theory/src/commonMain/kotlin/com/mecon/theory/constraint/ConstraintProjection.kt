package com.mecon.theory.constraint

import com.mecon.theory.SlotWindow

/**
 * Rebase one declarative constraint onto another timeline.
 *
 * This is intentionally theory-system agnostic: textbook, Schoenberg, jazz, or plugin rule
 * programs can all be projected without teaching the solver their musical vocabulary. A
 * relation that references an unmapped explicit slot is omitted instead of being weakened into
 * a different rule; range-based predicates are clipped to the mapped part of their source range.
 */
fun Constraint.projectSlots(
    sourceSlotCount: Int,
    targetSlotBySource: Map<Int, Int>,
    modality: ConstraintModality = this.modality,
    selectorProjection: (TargetSelector) -> TargetSelector = { it },
): Constraint? {
    require(sourceSlotCount > 0)
    require(targetSlotBySource.keys.all { it in 0 until sourceSlotCount })
    if (targetSlotBySource.isEmpty()) return null

    val projector = ConstraintSlotProjector(
        sourceSlotCount,
        targetSlotBySource,
        selectorProjection,
    )
    val projectedExpr = projector.project(expr) ?: return null
    val projectedScopeWindow = projector.projectWindow(
        scope.window ?: SlotWindow(0, sourceSlotCount - 1),
    ) ?: return null
    return copy(
        expr = projectedExpr,
        modality = modality,
        scope = scope.copy(
            window = projectedScopeWindow,
            selector = selectorProjection(scope.selector),
        ),
    )
}

private class ConstraintSlotProjector(
    private val sourceSlotCount: Int,
    private val targetSlotBySource: Map<Int, Int>,
    private val selectorProjection: (TargetSelector) -> TargetSelector,
) {
    fun project(expr: ConstraintExpr): ConstraintExpr? = when (expr) {
        is ConstraintExpr.Atom -> project(expr.predicate)?.let(ConstraintExpr::Atom)
        is ConstraintExpr.And -> expr.terms.map { project(it) ?: return null }
            .let(ConstraintExpr::And)
        is ConstraintExpr.Or -> expr.branches.map { branch ->
            branch.copy(expr = project(branch.expr) ?: return null)
        }.let(ConstraintExpr::Or)
        is ConstraintExpr.Not -> project(expr.term)?.let(ConstraintExpr::Not)
    }

    fun projectWindow(window: SlotWindow): SlotWindow? {
        val sourceSlots = (0 until sourceSlotCount)
            .filter { window.contains(it) && it in targetSlotBySource }
        if (sourceSlots.isEmpty()) return null
        val targetSlots = sourceSlots.map(targetSlotBySource::getValue)
        if (!sourceSlots.isConsecutive() || !targetSlots.isConsecutive()) return null
        return SlotWindow(targetSlots.first(), targetSlots.last())
    }

    private fun slot(source: Int): Int? = targetSlotBySource[source]

    private fun project(predicate: ConstraintPredicate): ConstraintPredicate? {
        return when (predicate) {
            is ConstraintPredicate.ToneCompleteness -> predicate.copy(
                requirement = predicate.requirement.copy(
                    window = projectWindow(predicate.requirement.window) ?: return null,
                    selector = selectorProjection(predicate.requirement.selector),
                ),
            )
            is ConstraintPredicate.ToneDoubled -> predicate.copy(
                requirement = predicate.requirement.copy(
                    slot = slot(predicate.requirement.slot) ?: return null,
                    selector = selectorProjection(predicate.requirement.selector),
                ),
            )
            is ConstraintPredicate.ToneNotDoubled -> predicate.copy(
                requirement = predicate.requirement.copy(
                    slot = slot(predicate.requirement.slot) ?: return null,
                    selector = selectorProjection(predicate.requirement.selector),
                ),
            )
            is ConstraintPredicate.ScaleDegreeNotDoubled -> predicate.copy(
                requirement = predicate.requirement.copy(
                    slot = slot(predicate.requirement.slot) ?: return null,
                    selector = selectorProjection(predicate.requirement.selector),
                ),
            )
            is ConstraintPredicate.Spacing -> predicate.copy(
                requirement = predicate.requirement.copy(
                    window = projectWindow(predicate.requirement.window) ?: return null,
                ),
            )
            is ConstraintPredicate.DistinctIdentities -> predicate.copy(
                requirement = predicate.requirement.copy(
                    window = projectWindow(predicate.requirement.window) ?: return null,
                ),
            )
            is ConstraintPredicate.CommonToneWithPrevious -> predicate.copy(
                requirement = predicate.requirement.copy(
                    window = projectWindow(predicate.requirement.window) ?: return null,
                ),
            )
            is ConstraintPredicate.NeighborTone -> predicate.copy(
                requirement = predicate.requirement.copy(
                    window = projectWindow(predicate.requirement.window) ?: return null,
                    sourceSlot = predicate.requirement.sourceSlot?.let { slot(it) ?: return null },
                    sourceSelector = selectorProjection(predicate.requirement.sourceSelector),
                    neighborSelector = selectorProjection(predicate.requirement.neighborSelector),
                ),
            )
            is ConstraintPredicate.TargetMatches -> predicate.copy(
                requirement = predicate.requirement.copy(
                    window = projectWindow(predicate.requirement.window) ?: return null,
                    selector = selectorProjection(predicate.requirement.selector),
                ),
            )
            is ConstraintPredicate.SameSonority -> predicate.copy(
                slots = predicate.slots.map { slot(it) ?: return null },
            )
            is ConstraintPredicate.VoiceDiatonicSteps -> predicate.copy(
                slots = predicate.slots.map { slot(it) ?: return null },
            )
            is ConstraintPredicate.VoicePitchClassCardinality -> predicate.copy(
                slots = predicate.slots.map { slot(it) ?: return null },
            )
            is ConstraintPredicate.ToneMultiplicity -> predicate.copy(
                slot = slot(predicate.slot) ?: return null,
            )
            is ConstraintPredicate.ToneInVoiceFilter -> predicate.copy(
                slot = slot(predicate.slot) ?: return null,
            )
            is ConstraintPredicate.RootDiatonicMotion -> predicate.copy(
                fromSlot = slot(predicate.fromSlot) ?: return null,
                toSlot = slot(predicate.toSlot) ?: return null,
            )
            is ConstraintPredicate.RuleFound -> predicate.copy(
                window = projectWindow(predicate.window ?: SlotWindow(0, sourceSlotCount - 1))
                    ?: return null,
            )
            is ConstraintPredicate.UniqueVoiceExtreme,
            is ConstraintPredicate.NoRepeatedVoicePattern,
            is ConstraintPredicate.MinimumSimilarChordDistance,
            ConstraintPredicate.DistinctSimilarChordProgressions,
            is ConstraintPredicate.RootProgressionPreference -> predicate
        }
    }
}

private fun List<Int>.isConsecutive(): Boolean =
    zipWithNext().all { (before, after) -> after == before + 1 }
