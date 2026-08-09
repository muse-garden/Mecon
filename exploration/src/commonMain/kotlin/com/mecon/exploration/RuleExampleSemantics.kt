package com.mecon.exploration

import com.mecon.theory.ChordArity
import com.mecon.theory.Key
import com.mecon.theory.RequirementMode
import com.mecon.theory.RuleId
import com.mecon.theory.RuleRequirement
import com.mecon.theory.RuleScene
import com.mecon.theory.RuleCatalog
import com.mecon.theory.SceneMatcher
import com.mecon.theory.textbook.TextbookSeventhWritingSlot
import com.mecon.theory.textbook.TextbookTriadWritingSlot
import com.mecon.theory.textbook.textbookTriadInKey

internal data class RuleExampleSemantics(
    val key: Key,
    val ruleIds: List<RuleId>,
    val requirements: List<RuleRequirement>,
    val scene: RuleScene?,
    val triadSlots: List<TextbookTriadWritingSlot>?,
    val seventhSlots: List<TextbookSeventhWritingSlot>?,
)

internal fun RuleExampleRequest.compileSemantics(): RuleExampleSemantics {
    val key = key.toTheoryKey()
    val requirements = selectedRules.map {
        RuleRequirement(RuleId(it), RequirementMode.REQUIRE_INDICATION)
    } + listOfNotNull(
        demonstrate?.let {
            RuleRequirement(RuleId(it.ruleId), RequirementMode.REQUIRE_VIOLATION)
        }
    )
    val ruleIds = requirements.map(RuleRequirement::ruleId)
    val scene = ruleIds
        .flatMap(RuleCatalog::scenes)
        .maxByOrNull { it.window.last }
    val seventhSlots = scene
        ?.takeIf { it.chordArity == ChordArity.SEVENTH }
        ?.let { SceneMatcher.instantiateSeventh(it, key) }
    val triadSlots = if (seventhSlots == null) {
        scene
            ?.takeIf { it.chordArity == ChordArity.TRIAD }
            ?.let { SceneMatcher.instantiate(it, key, from.degree, to.degree) }
            ?: listOf(
                TextbookTriadWritingSlot.rootPosition(textbookTriadInKey(key, from.degree)),
                TextbookTriadWritingSlot.rootPosition(textbookTriadInKey(key, to.degree)),
            )
    } else {
        null
    }
    return RuleExampleSemantics(
        key = key,
        ruleIds = ruleIds,
        requirements = requirements,
        scene = scene,
        triadSlots = triadSlots,
        seventhSlots = seventhSlots,
    )
}
