package com.mecon.theory.freepractice

import com.mecon.theory.ModulationKey
import com.mecon.theory.SearchConfig
import com.mecon.theory.constraint.ChordTarget
import com.mecon.theory.constraint.Constraint

data class PracticeTeachingRuleRequest(
    val workspace: HarmonyWorkspaceState,
    val scope: PracticeWritingScope,
    val targetsBySlotId: Map<WorkspaceSlotId, List<ChordTarget>>,
    val fallbackKey: ModulationKey,
    val searchConfig: SearchConfig,
)

/** Replaceable bridge from a teaching system's registered programs into free-practice writing. */
fun interface PracticeTeachingRuleProjector {
    fun project(request: PracticeTeachingRuleRequest): List<Constraint>
}

object NoPracticeTeachingRules : PracticeTeachingRuleProjector {
    override fun project(request: PracticeTeachingRuleRequest): List<Constraint> = emptyList()
}
