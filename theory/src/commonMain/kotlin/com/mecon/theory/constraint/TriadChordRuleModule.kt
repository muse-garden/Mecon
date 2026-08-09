package com.mecon.theory.constraint

import com.mecon.api.primitive.EventId
import com.mecon.theory.ChapterId
import com.mecon.theory.ChordArity
import com.mecon.theory.FixedVoiceTransitionRuleContext
import com.mecon.theory.FixedVoiceVerticalRuleContext
import com.mecon.theory.FixedVoiceWritingFrame
import com.mecon.theory.Key
import com.mecon.theory.RuleFinding
import com.mecon.theory.textbook.FirstInversionTriadConnection
import com.mecon.theory.textbook.FirstInversionTriadRules
import com.mecon.theory.textbook.RootPositionTriadConnection
import com.mecon.theory.textbook.RootPositionTriadRules
import com.mecon.theory.textbook.SecondInversionTriadRules
import com.mecon.theory.textbook.TextbookTriadPosition
import com.mecon.theory.textbook.TextbookTriadTarget

class TriadChordRuleModule(
    private val key: Key,
    private val slotCount: Int,
    private val finalTonicMayOmitFifth: Boolean = true,
) : ChordRuleModule {
    override val chapter: ChapterId = ChapterId("textbook.triad-dispatch")
    private val triadScope = TargetSelector(arities = setOf(ChordArity.TRIAD))

    override fun verticalScope(): TargetSelector = triadScope

    override fun checkVertical(context: FixedVoiceVerticalRuleContext<ChordTarget>): List<RuleFinding<EventId>> {
        val target = context.frame.target as? TextbookTriadTarget ?: return emptyList()
        return buildList {
            when (target.position) {
                TextbookTriadPosition.ROOT_POSITION -> addAll(
                    RootPositionTriadRules.checkVerticality(
                        context.verticality,
                        target.triad,
                        key,
                        isFinal = isFinalTonic(context.frame),
                    )
                )
                TextbookTriadPosition.FIRST_INVERSION -> addAll(
                    FirstInversionTriadRules.checkVerticality(context.verticality, target.triad, key)
                )
                TextbookTriadPosition.SECOND_INVERSION -> addAll(
                    SecondInversionTriadRules.checkVerticality(context.verticality, target.triad, key)
                )
            }
            addAll(FirstInversionTriadRules.checkDiminishedTriadPosition(context.verticality, target.triad))
        }
    }

    override fun transitionScope(): Pair<TargetSelector, TargetSelector> = triadScope to triadScope

    override fun checkTransition(context: FixedVoiceTransitionRuleContext<ChordTarget>): List<RuleFinding<EventId>> {
        val before = context.previousFrame.target as? TextbookTriadTarget ?: return emptyList()
        val after = context.currentFrame.target as? TextbookTriadTarget ?: return emptyList()
        return buildList {
            if (
                before.position == TextbookTriadPosition.ROOT_POSITION &&
                after.position == TextbookTriadPosition.ROOT_POSITION
            ) {
                addAll(
                    RootPositionTriadRules.checkTransition(
                        context.transition,
                        RootPositionTriadConnection(
                            key = key,
                            before = before.triad,
                            after = after.triad,
                            afterIsFinal = isFinalTonic(context.currentFrame),
                        ),
                    )
                )
            }
            addAll(
                FirstInversionTriadRules.checkTransition(
                    context.transition,
                    FirstInversionTriadConnection(key, before.triad, after.triad),
                )
            )
        }
    }

    private fun isFinalTonic(frame: FixedVoiceWritingFrame<ChordTarget>): Boolean =
        finalTonicMayOmitFifth && frame.slotIndex == slotCount - 1 && frame.target.degree == TONIC_DEGREE
}

private const val TONIC_DEGREE = 1
