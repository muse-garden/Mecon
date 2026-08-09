package com.mecon.theory.constraint

import com.mecon.theory.ChordArity
import com.mecon.theory.RuleId
import com.mecon.theory.SlotWindow

data class ChordToneCompletenessRuleIds(
    val triad: RuleId? = null,
    val seventh: RuleId? = null,
)

/**
 * Shared vertical material rules for programs that intentionally disable the textbook chord modules.
 * Triad root and third are hard requirements while the fifth remains a preference. Seventh-chord
 * root and seventh are hard requirements. This is the common material policy used by integrated
 * exercises and free practice; chapter-specific completeness rules are layered on top by callers.
 */
fun generalChordToneCompletenessRequirements(
    window: SlotWindow,
    ruleIds: ChordToneCompletenessRuleIds = ChordToneCompletenessRuleIds(),
): List<ToneCompletenessRequirement> =
    listOf(
        ToneCompletenessRequirement(
            window = window,
            requiredTones = setOf(ChordTone.ROOT, ChordTone.THIRD),
            selector = TargetSelector(arities = setOf(ChordArity.TRIAD)),
            required = true,
            ruleId = ruleIds.triad,
        ),
        ToneCompletenessRequirement(
            window = window,
            requiredTones = setOf(ChordTone.FIFTH),
            selector = TargetSelector(arities = setOf(ChordArity.TRIAD)),
            required = false,
            ruleId = ruleIds.triad,
        ),
        ToneCompletenessRequirement(
            window = window,
            requiredTones = setOf(ChordTone.ROOT, ChordTone.SEVENTH),
            selector = TargetSelector(arities = setOf(ChordArity.SEVENTH)),
            required = true,
            ruleId = ruleIds.seventh,
        ),
    )
