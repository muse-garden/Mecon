package com.mecon.theory

import com.mecon.api.primitive.EventId

/** Prefer independent adjacent voices not to share the same absolute pitch. */
object AdjacentVoiceUnisonRule {
    val RULE_ID = RuleId("writing.vertical.adjacent-voice-unison")

    fun check(verticality: FixedVoiceVerticality): List<RuleFinding<EventId>> =
        verticality.notes.zipWithNext().mapNotNull { (upper, lower) ->
            val upperPitch = upper.pitch ?: return@mapNotNull null
            val lowerPitch = lower.pitch ?: return@mapNotNull null
            if (upperPitch.midiNumber != lowerPitch.midiNumber) {
                return@mapNotNull null
            }
            RuleFinding(
                ruleId = RULE_ID,
                kind = RuleFindingKind.VIOLATION,
                severity = RuleSeverity.SOFT,
                message = "相邻独立声部处于同一绝对音高，宜将重复音错开八度。",
                anchors = listOf(upper.id, lower.id),
            )
        }
}
