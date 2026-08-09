package com.mecon.theory

import kotlin.test.Test
import kotlin.test.assertEquals

class IncrementalRuleProfileTest {
    @Test
    fun chunkedProfileMatchesBatchWhenHiddenDominantSuppressesLaterFinding() {
        val anchor = "same-anchor"
        val outerDominant = finding("outer", anchor)
        val hiddenDominant = finding("hidden-dominant", anchor)
        val suppressed = finding("suppressed", anchor)
        val profile = RuleProfile(
            id = "incremental-suppression",
            suppressions = listOf(
                RuleSuppression(outerDominant.ruleId, hiddenDominant.ruleId),
                RuleSuppression(hiddenDominant.ruleId, suppressed.ruleId),
            ),
        )
        val batch = listOf(suppressed, outerDominant, hiddenDominant).applyProfile(profile)

        val incremental = IncrementalProfiledFindings<String>()
            .append(listOf(suppressed), profile).result
            .append(listOf(outerDominant, hiddenDominant), profile).result

        assertEquals(batch, incremental.visible)
        assertEquals(listOf(outerDominant), incremental.visible)
        assertEquals(3, incremental.configured.size)
    }

    private fun finding(id: String, anchor: String): RuleFinding<String> = RuleFinding(
        ruleId = RuleId(id),
        kind = RuleFindingKind.VIOLATION,
        severity = RuleSeverity.SOFT,
        message = id,
        anchors = listOf(anchor),
        scoreDelta = 1.0,
    )
}
