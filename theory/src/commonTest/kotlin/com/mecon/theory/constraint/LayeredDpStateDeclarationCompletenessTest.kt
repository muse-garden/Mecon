package com.mecon.theory.constraint

import com.mecon.theory.RuleId
import com.mecon.theory.textbook.FourPartTextbookRules
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 路线图第 2 项的守卫：受支持 preset 下每个 provider 能发射的 RuleId 都必须有 DP 状态声明。
 * 新增 RuleId 而忘记声明会让 DP 的等价性建立在没被审计过的规则上——那时这条测试变红。
 */
class LayeredDpStateDeclarationCompletenessTest {
    private fun declared(declarations: List<LayeredDpRuleStateDeclaration>): Set<RuleId> =
        declarations.mapTo(mutableSetOf()) { it.ruleId }

    @Test
    fun freeClassicalDeclaresEveryReachableProviderRule() {
        val undeclared = FreeHarmonyRuleProvider.ALL_RULE_IDS -
            declared(FreeHarmonyRuleProvider.dpStateDeclarations(classical = true))
        assertEquals(emptySet(), undeclared, "自由古典 provider 有规则缺少 DP 状态声明")
    }

    @Test
    fun freeJazzDeclaresEveryReachableProviderRule() {
        // 爵士不启用古典对位三条，它们不在该 preset 的 provider 面上。
        val classicalOnly = setOf(
            FreeHarmonyRuleProvider.PARALLEL_PERFECT,
            FreeHarmonyRuleProvider.HIDDEN_PERFECT,
            FreeHarmonyRuleProvider.TENDENCY_TONE,
            FreeHarmonyRuleProvider.ROOTLESS_DIMINISHED_ROOT,
            FreeHarmonyRuleProvider.ROOTLESS_DIMINISHED_ALTERED_STEP,
            FreeHarmonyRuleProvider.DISSONANCE_RELEASE,
        )
        val undeclared = FreeHarmonyRuleProvider.ALL_RULE_IDS -
            declared(FreeHarmonyRuleProvider.dpStateDeclarations(classical = false)) -
            classicalOnly
        assertEquals(emptySet(), undeclared, "自由爵士 provider 有规则缺少 DP 状态声明")
    }

    @Test
    fun schoenbergGeneralDeclaresEveryProviderRuleWithNoExemption() {
        // SCHOENBERG_GENERAL 的 provider 面与和弦类型无关，因此不允许任何豁免。
        val undeclared = FourPartTextbookRules.ALL_RULE_IDS -
            declared(FourPartTextbookRules.dpStateDeclarations())
        assertEquals(emptySet(), undeclared, "勋伯格一般写作规则有规则缺少 DP 状态声明")
    }

    @Test
    fun solverSideProvidersDeclareEveryRule() {
        assertEquals(
            emptySet(),
            WindowFeasibilityRuleProvider.ALL_RULE_IDS -
                declared(WindowFeasibilityRuleProvider.dpStateDeclarations()),
        )
        assertEquals(
            emptySet(),
            BaselineSimilarityRuleProvider.ALL_RULE_IDS -
                declared(BaselineSimilarityRuleProvider.dpStateDeclarations()),
        )
    }
}
