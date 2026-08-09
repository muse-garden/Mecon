package com.mecon.theory.constraint

import com.mecon.api.primitive.EventId
import com.mecon.theory.ChapterId
import com.mecon.theory.FixedVoiceScoreRuleContext
import com.mecon.theory.FixedVoiceTransitionRuleContext
import com.mecon.theory.FixedVoiceVerticalRuleContext
import com.mecon.theory.FixedVoiceWritingFrame
import com.mecon.theory.FixedVoiceWritingRuleProvider
import com.mecon.theory.Key
import com.mecon.theory.RuleFinding

interface ChordRuleModule {
    val chapter: ChapterId
    fun verticalScope(): TargetSelector? = null
    fun checkVertical(context: FixedVoiceVerticalRuleContext<ChordTarget>): List<RuleFinding<EventId>> = emptyList()
    fun transitionScope(): Pair<TargetSelector, TargetSelector>? = null
    fun checkTransition(context: FixedVoiceTransitionRuleContext<ChordTarget>): List<RuleFinding<EventId>> = emptyList()
    fun scoreScope(): TargetSelector? = null
    fun checkScore(context: FixedVoiceScoreRuleContext<ChordTarget>): List<RuleFinding<EventId>> = emptyList()
}

/** 按模块声明的 selector 自发现规则；缓存只依赖目标身份与转位。 */
class ChordRuleDispatcher(
    private val modules: List<ChordRuleModule>,
) : FixedVoiceWritingRuleProvider<ChordTarget> {
    private val verticalCache = mutableMapOf<String, List<ChordRuleModule>>()
    private val transitionCache = mutableMapOf<Pair<String, String>, List<ChordRuleModule>>()
    private val scoreCache = mutableMapOf<String, List<ChordRuleModule>>()

    override fun checkVertical(context: FixedVoiceVerticalRuleContext<ChordTarget>): List<RuleFinding<EventId>> =
        verticalModules(context.frame.target).flatMap { it.checkVertical(context) }

    override fun checkTransition(context: FixedVoiceTransitionRuleContext<ChordTarget>): List<RuleFinding<EventId>> =
        transitionModules(context.previousFrame.target, context.currentFrame.target)
            .flatMap { it.checkTransition(context) }

    override fun checkScore(context: FixedVoiceScoreRuleContext<ChordTarget>): List<RuleFinding<EventId>> =
        scoreModules(context.state.frames).flatMap { it.checkScore(context) }

    private fun verticalModules(target: ChordTarget): List<ChordRuleModule> =
        verticalCache.getOrPut(target.cacheKey()) {
            modules.filter { it.verticalScope()?.matches(target) == true }
        }

    private fun transitionModules(before: ChordTarget, after: ChordTarget): List<ChordRuleModule> =
        transitionCache.getOrPut(before.cacheKey() to after.cacheKey()) {
            modules.filter { module ->
                module.transitionScope()?.let { (beforeScope, afterScope) ->
                    beforeScope.matches(before) && afterScope.matches(after)
                } == true
            }
        }

    private fun scoreModules(frames: List<FixedVoiceWritingFrame<ChordTarget>>): List<ChordRuleModule> {
        val key = frames.joinToString("|") { it.target.cacheKey() }
        return scoreCache.getOrPut(key) {
            modules.filter { module ->
                module.scoreScope()?.let { scope -> frames.any { scope.matches(it.target) } } == true
            }
        }
    }

    private fun ChordTarget.cacheKey(): String = "${arity.name}:${identityKey()}:$inversion"
}

fun defaultChordRuleModules(
    key: Key,
    slotCount: Int,
    finalTonicMayOmitFifth: Boolean = true,
): List<ChordRuleModule> =
    listOf(
        TriadChordRuleModule(key, slotCount, finalTonicMayOmitFifth),
        SeventhChordRuleModule(key),
    )
