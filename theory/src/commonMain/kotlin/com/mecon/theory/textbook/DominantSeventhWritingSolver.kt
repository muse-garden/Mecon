package com.mecon.theory.textbook

import com.mecon.api.primitive.Pitch
import com.mecon.theory.Key
import com.mecon.theory.RuleProfile
import com.mecon.theory.ScoreBreakdown
import com.mecon.theory.SearchConfig
import com.mecon.theory.VoiceRangeProfile
import com.mecon.theory.constraint.ChordVoicing
import com.mecon.theory.constraint.ConstraintProgramSolver

/** 槽位对五音完整性的约束；检查期解释仍由七和弦规则模块提供。 */
enum class SeventhFifthConstraint {
    REQUIRE_FIFTH,
    OMIT_FIFTH,
}

data class TextbookSeventhWritingSlot(
    val chord: TextbookSeventhChord,
    val allowedPositions: Set<TextbookSeventhPosition>,
    val fifthConstraint: SeventhFifthConstraint? = null,
) {
    init {
        require(allowedPositions.isNotEmpty()) { "A seventh writing slot must allow at least one position" }
    }

    companion object {
        fun rootPosition(chord: TextbookSeventhChord) =
            TextbookSeventhWritingSlot(chord, setOf(TextbookSeventhPosition.ROOT_POSITION))

        fun firstInversion(chord: TextbookSeventhChord) =
            TextbookSeventhWritingSlot(chord, setOf(TextbookSeventhPosition.FIRST_INVERSION))

        fun secondInversion(chord: TextbookSeventhChord) =
            TextbookSeventhWritingSlot(chord, setOf(TextbookSeventhPosition.SECOND_INVERSION))

        fun thirdInversion(chord: TextbookSeventhChord) =
            TextbookSeventhWritingSlot(chord, setOf(TextbookSeventhPosition.THIRD_INVERSION))

        fun rootOrFirst(chord: TextbookSeventhChord) =
            TextbookSeventhWritingSlot(
                chord,
                setOf(TextbookSeventhPosition.ROOT_POSITION, TextbookSeventhPosition.FIRST_INVERSION),
            )
    }
}

data class TextbookSeventhWritingProblem(
    val key: Key,
    val slots: List<TextbookSeventhWritingSlot>,
    val ruleProfile: RuleProfile = DominantSeventhRules.INTRODUCTORY_PROFILE,
    val rangeProfile: VoiceRangeProfile = VoiceRangeProfile.humanFourPart(),
    val searchConfig: SearchConfig = SearchConfig(maxResults = 8, beamWidth = 64),
) {
    init {
        require(slots.isNotEmpty()) { "A seventh writing problem must include at least one slot" }
        require(slots.all { it.chord.key == key }) { "All chords must belong to the problem key" }
    }
}

data class TextbookSeventhWritingSolution(
    val voicings: List<TextbookSeventhVoicing>,
    val breakdown: ScoreBreakdown,
)

data class TextbookSeventhVoicing(
    val slotIndex: Int,
    val chord: TextbookSeventhChord,
    val position: TextbookSeventhPosition,
    val soprano: Pitch,
    val alto: Pitch,
    val tenor: Pitch,
    val bass: Pitch,
)

/** 兼容 textbook API 的薄适配器；执行路径统一进入 [ConstraintProgramSolver]。 */
object TextbookSeventhWritingSolver {
    fun solve(problem: TextbookSeventhWritingProblem): List<TextbookSeventhWritingSolution> =
        ConstraintProgramSolver.solve(problem.toConstraintProgram()).map { solution ->
            TextbookSeventhWritingSolution(
                voicings = solution.voicings.map { it.toTextbookSeventhVoicing() },
                breakdown = solution.breakdown,
            )
        }
}

private fun ChordVoicing.toTextbookSeventhVoicing(): TextbookSeventhVoicing {
    val seventhTarget = target as? TextbookSeventhTarget
        ?: error("Expected textbook seventh target at slot $slotIndex, got ${target::class.simpleName}")
    return TextbookSeventhVoicing(
        slotIndex = slotIndex,
        chord = seventhTarget.chord,
        position = seventhTarget.position,
        soprano = soprano,
        alto = alto,
        tenor = tenor,
        bass = bass,
    )
}
