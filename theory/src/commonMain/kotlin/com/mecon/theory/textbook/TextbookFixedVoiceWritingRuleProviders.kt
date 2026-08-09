package com.mecon.theory.textbook

import com.mecon.api.primitive.EventId
import com.mecon.theory.AdjacentVoiceUnisonRule
import com.mecon.theory.FixedVoiceVerticalRuleContext
import com.mecon.theory.FixedVoiceScoreRuleContext
import com.mecon.theory.FixedVoiceTransitionRuleContext
import com.mecon.theory.FixedVoiceWritingRuleProvider
import com.mecon.theory.Key
import com.mecon.theory.RuleFinding
import com.mecon.theory.VoiceRangeProfile
import com.mecon.theory.toFinding

class FourPartTextbookWritingRuleProvider<T>(
    private val rangeProfile: VoiceRangeProfile = VoiceRangeProfile.humanFourPart(),
) : FixedVoiceWritingRuleProvider<T> {
    override fun checkVertical(context: FixedVoiceVerticalRuleContext<T>): List<RuleFinding<EventId>> =
        AdjacentVoiceUnisonRule.check(context.verticality)

    override fun checkTransition(context: FixedVoiceTransitionRuleContext<T>): List<RuleFinding<EventId>> =
        FourPartTextbookRules.checkFixedVoiceTransition(context.transition, rangeProfile)
            .filterNot { it.ruleId == AdjacentVoiceUnisonRule.RULE_ID }
}

class MelodyTextbookWritingRuleProvider<T>(
    private val key: Key,
    private val requireUniqueClimax: Boolean = false,
) : FixedVoiceWritingRuleProvider<T> {
    override fun checkScore(context: FixedVoiceScoreRuleContext<T>): List<RuleFinding<EventId>> =
        context.fixedVoiceScore.voices.flatMap { voice ->
            MelodyTextbookRules.checkVoice(
                items = context.fixedVoiceScore.noteEventsForVoice(voice),
                key = key,
                pitchOf = { it.pitch ?: error("Expected generated note to have a pitch") },
                anchorOf = { it.id },
                requireUniqueClimax = requireUniqueClimax,
            ).map { it.toFinding() }
        }
}
