package com.mecon.theory.constraint

import com.mecon.api.primitive.EventId
import com.mecon.theory.ChapterId
import com.mecon.theory.Chord
import com.mecon.theory.ChordArity
import com.mecon.theory.FixedVoiceScoreRuleContext
import com.mecon.theory.FixedVoiceTransitionRuleContext
import com.mecon.theory.FixedVoiceVerticalRuleContext
import com.mecon.theory.FixedVoiceWritingFrame
import com.mecon.theory.Key
import com.mecon.theory.RuleFinding
import com.mecon.theory.standardFourPartWritingVoices
import com.mecon.theory.textbook.DominantSeventhConnection
import com.mecon.theory.textbook.DominantSeventhRuleCatalog
import com.mecon.theory.textbook.DominantSeventhRules
import com.mecon.theory.textbook.TextbookSeventhChord
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookSeventhTarget

class SeventhChordRuleModule(
    private val key: Key,
) : ChordRuleModule {
    override val chapter: ChapterId = DominantSeventhRuleCatalog.chapterId
    private val seventhScope = TargetSelector(arities = setOf(ChordArity.SEVENTH))
    private val anyScope = TargetSelector()

    override fun verticalScope(): TargetSelector = seventhScope

    override fun checkVertical(context: FixedVoiceVerticalRuleContext<ChordTarget>): List<RuleFinding<EventId>> =
        DominantSeventhRules.checkVerticality(context.verticality, context.frame.target.toRuleTarget(), key)

    override fun transitionScope(): Pair<TargetSelector, TargetSelector> = anyScope to anyScope

    override fun checkTransition(context: FixedVoiceTransitionRuleContext<ChordTarget>): List<RuleFinding<EventId>> {
        val before = context.previousFrame.target
        val after = context.currentFrame.target
        if (before.arity != ChordArity.SEVENTH && after.arity != ChordArity.SEVENTH) return emptyList()
        val beforeSeventh = before.toRuleTarget()
        val afterSeventh = after.toRuleTarget()
        return DominantSeventhRules.checkTransition(
            context.transition,
            DominantSeventhConnection(
                key = key,
                before = beforeSeventh.chord,
                beforePosition = beforeSeventh.position,
                after = afterSeventh.chord,
                afterPosition = afterSeventh.position,
            ),
            includeConstraintMigratedRules = false,
        )
    }

    override fun scoreScope(): TargetSelector = seventhScope

    override fun checkScore(context: FixedVoiceScoreRuleContext<ChordTarget>): List<RuleFinding<EventId>> {
        val voices = standardFourPartWritingVoices()
        val frames = context.state.frames.map { it.retarget(it.target.toRuleTarget()) }
        return DominantSeventhRules.checkCircleOfFifthsSequence(frames, voices)
    }
}

/** 七和弦章节内部视图；三和弦解决端点只在本模块内适配。 */
private fun ChordTarget.toRuleTarget(): TextbookSeventhTarget =
    this as? TextbookSeventhTarget ?: TextbookSeventhTarget(
        chord = TextbookSeventhChord(
            key,
            degree,
            sonority as? Chord ?: Chord(sonority.root, quality),
        ),
        position = when (inversion) {
            0 -> TextbookSeventhPosition.ROOT_POSITION
            1 -> TextbookSeventhPosition.FIRST_INVERSION
            2 -> TextbookSeventhPosition.SECOND_INVERSION
            else -> TextbookSeventhPosition.THIRD_INVERSION
        },
    )

private fun <A, B> FixedVoiceWritingFrame<A>.retarget(newTarget: B): FixedVoiceWritingFrame<B> =
    FixedVoiceWritingFrame(slotIndex, newTarget, pitchesByVoiceId, duration)
