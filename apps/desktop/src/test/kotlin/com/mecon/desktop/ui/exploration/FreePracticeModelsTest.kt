package com.mecon.desktop.ui.exploration

import com.mecon.api.primitive.Pitch
import com.mecon.features.freepractice.PracticeFindingView
import com.mecon.features.freepractice.PracticeFindingSeverity as SharedPracticeFindingSeverity
import kotlin.test.Test
import kotlin.test.assertEquals

class FreePracticeModelsTest {
    @Test
    fun fourVoiceWorkspaceUsesHumanSatbRanges() {
        val voices = initialWorkspace(4).voices.sortedBy { it.order }

        assertEquals(
            listOf(
                Pitch.fromName("C4") to Pitch.fromName("G5"),
                Pitch.fromName("G3") to Pitch.fromName("D5"),
                Pitch.fromName("C3") to Pitch.fromName("E4"),
                Pitch.fromName("E2") to Pitch.fromName("C4"),
            ),
            voices.map { it.lowest to it.highest },
        )
    }

    @Test
    fun sharedRuleMessageIsShownInsteadOfRuleId() {
        val finding = localizedPracticeFinding(
            PracticeFindingView(
                messageKey = "freePractice.rule.free.counterpoint.parallel-perfect",
                severity = SharedPracticeFindingSeverity.WARNING,
                ruleId = "free.counterpoint.parallel-perfect",
                message = "出现平行纯五度或纯八度；自由写作中保留为可调软偏好。",
            )
        )

        assertEquals("出现平行纯五度或纯八度；自由写作中保留为可调软偏好。", finding.title)
        assertEquals("规则：free.counterpoint.parallel-perfect", finding.detail)
    }
}
