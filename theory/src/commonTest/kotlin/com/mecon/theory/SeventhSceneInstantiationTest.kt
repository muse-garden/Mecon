package com.mecon.theory

import com.mecon.api.primitive.PitchClass
import com.mecon.theory.textbook.DominantSeventhRules
import com.mecon.theory.textbook.SeventhFifthConstraint
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookSeventhWritingSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P1 金标准：七和弦章的教材例题进行必须由场景数据（[SceneMatcher.instantiateSeventh]）产出，
 * 与 runner 迁移前 `dominantSeventhSlots` / `circleOfFifthsSlots` 等硬编码槽位一致（solver-api §1、§7）。
 */
class SeventhSceneInstantiationTest {
    private val key = Key.major(PitchClass.C)

    private fun instantiate(ruleId: RuleId): List<TextbookSeventhWritingSlot> =
        SceneMatcher.instantiateSeventh(RuleCatalog.scenes(ruleId).single(), key)

    private val TextbookSeventhWritingSlot.position: TextbookSeventhPosition
        get() = allowedPositions.single()

    private val TextbookSeventhWritingSlot.tones: Int
        get() = chord.chord.pitchClasses.size

    @Test
    fun v7ToIProducesRootSeventhToTonicTriad() {
        val slots = instantiate(DominantSeventhRules.SEVENTH_RESOLVES_DOWN)
        assertEquals(listOf(5, 1), slots.map { it.chord.degree })
        assertEquals(listOf(4, 3), slots.map { it.tones }, "V7(4 音) → I(3 音)")
        assertTrue(slots.all { it.position == TextbookSeventhPosition.ROOT_POSITION })
    }

    @Test
    fun thirdInversionResolvesToFirstInversionTonic() {
        val slots = instantiate(DominantSeventhRules.THIRD_INVERSION_TO_I6)
        assertEquals(listOf(5, 1), slots.map { it.chord.degree })
        assertEquals(TextbookSeventhPosition.THIRD_INVERSION, slots[0].position)
        assertEquals(TextbookSeventhPosition.FIRST_INVERSION, slots[1].position)
        assertEquals(listOf(4, 3), slots.map { it.tones })
    }

    @Test
    fun supertonicCadentialSixFourProducesFourChordProgression() {
        val slots = instantiate(DominantSeventhRules.SUPERTONIC_TO_CADENTIAL_SIX_FOUR)
        assertEquals(listOf(2, 1, 5, 1), slots.map { it.chord.degree })
        assertEquals(
            listOf(
                TextbookSeventhPosition.ROOT_POSITION,
                TextbookSeventhPosition.SECOND_INVERSION,
                TextbookSeventhPosition.ROOT_POSITION,
                TextbookSeventhPosition.ROOT_POSITION,
            ),
            slots.map { it.position },
        )
        assertEquals(listOf(4, 3, 4, 3), slots.map { it.tones }, "II7 - I⁶₄ - V7 - I")
    }

    @Test
    fun circleFirstThirdInversionAlternatesAndCadencesOnFirstInversionTonic() {
        val slots = instantiate(DominantSeventhRules.CIRCLE_FIRST_THIRD_INVERSION)
        assertEquals(listOf(4, 7, 3, 6, 2, 5, 1), slots.map { it.chord.degree })
        assertEquals(
            listOf(
                TextbookSeventhPosition.FIRST_INVERSION,
                TextbookSeventhPosition.THIRD_INVERSION,
                TextbookSeventhPosition.FIRST_INVERSION,
                TextbookSeventhPosition.THIRD_INVERSION,
                TextbookSeventhPosition.FIRST_INVERSION,
                TextbookSeventhPosition.THIRD_INVERSION,
                TextbookSeventhPosition.FIRST_INVERSION,
            ),
            slots.map { it.position },
        )
        // 前六个是七和弦，终止主和弦是三和弦（I6）。
        assertEquals(listOf(4, 4, 4, 4, 4, 4, 3), slots.map { it.tones })
        assertEquals(
            listOf(
                SeventhFifthConstraint.REQUIRE_FIFTH,
                SeventhFifthConstraint.REQUIRE_FIFTH,
                SeventhFifthConstraint.REQUIRE_FIFTH,
                SeventhFifthConstraint.REQUIRE_FIFTH,
                SeventhFifthConstraint.REQUIRE_FIFTH,
                SeventhFifthConstraint.REQUIRE_FIFTH,
                null,
            ),
            slots.map { it.fifthConstraint },
            "一/三转位交替教材例应保持所有七和弦完整",
        )
    }

    @Test
    fun circleRootPositionAlternationEncodesFifthCompleteness() {
        val slots = instantiate(DominantSeventhRules.CIRCLE_ROOT_POSITION_ALTERNATION)
        assertTrue(slots.dropLast(1).all { it.position == TextbookSeventhPosition.ROOT_POSITION })
        assertEquals(
            listOf(
                SeventhFifthConstraint.REQUIRE_FIFTH,
                SeventhFifthConstraint.OMIT_FIFTH,
                SeventhFifthConstraint.REQUIRE_FIFTH,
                SeventhFifthConstraint.OMIT_FIFTH,
                SeventhFifthConstraint.REQUIRE_FIFTH,
                SeventhFifthConstraint.OMIT_FIFTH,
                null,
            ),
            slots.map { it.fifthConstraint },
            "完全 / 省五交替编码进槽位（本轮 bug 修复）",
        )
    }

    @Test
    fun circleSecondRootInversionRequiresCompleteSevenths() {
        val slots = instantiate(DominantSeventhRules.CIRCLE_SECOND_ROOT_INVERSION)
        assertEquals(listOf(4, 7, 3, 6, 2, 5, 1), slots.map { it.chord.degree })
        assertEquals(
            listOf(
                TextbookSeventhPosition.SECOND_INVERSION,
                TextbookSeventhPosition.ROOT_POSITION,
                TextbookSeventhPosition.SECOND_INVERSION,
                TextbookSeventhPosition.ROOT_POSITION,
                TextbookSeventhPosition.SECOND_INVERSION,
                TextbookSeventhPosition.ROOT_POSITION,
                TextbookSeventhPosition.ROOT_POSITION,
            ),
            slots.map { it.position },
        )
        assertEquals(
            listOf(
                SeventhFifthConstraint.REQUIRE_FIFTH,
                SeventhFifthConstraint.REQUIRE_FIFTH,
                SeventhFifthConstraint.REQUIRE_FIFTH,
                SeventhFifthConstraint.REQUIRE_FIFTH,
                SeventhFifthConstraint.REQUIRE_FIFTH,
                SeventhFifthConstraint.REQUIRE_FIFTH,
                null,
            ),
            slots.map { it.fifthConstraint },
            "二转位/原位交替教材例不应靠省五降低分数",
        )
    }
}
