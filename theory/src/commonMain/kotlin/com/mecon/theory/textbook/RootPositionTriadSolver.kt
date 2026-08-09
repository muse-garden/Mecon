package com.mecon.theory.textbook

import com.mecon.api.primitive.Pitch
import com.mecon.theory.Key
import com.mecon.theory.NaturalTriad
import com.mecon.theory.RuleProfile
import com.mecon.theory.ScoreBreakdown
import com.mecon.theory.SearchConfig
import com.mecon.theory.VoiceRangeProfile

data class RootPositionTriadWritingProblem(
    val key: Key,
    val triads: List<NaturalTriad>,
    val constraintPreset: TextbookTriadConstraintPreset = TextbookTriadConstraintPreset.INTRODUCTORY,
    val ruleProfile: RuleProfile = RootPositionTriadRules.INTRODUCTORY_PROFILE,
    val rangeProfile: VoiceRangeProfile = VoiceRangeProfile.humanFourPart(),
    val searchConfig: SearchConfig = SearchConfig(maxResults = 8, beamWidth = 48),
    val finalTonicMayOmitFifth: Boolean = true,
) {
    init {
        require(triads.isNotEmpty()) { "A root-position triad writing problem must include at least one triad" }
        require(triads.all { it.key == key }) { "All triads must belong to the problem key" }
    }

    fun toConstraintProgram() = toTextbookProblem().toConstraintProgram()

    internal fun toTextbookProblem(): TextbookTriadWritingProblem =
        TextbookTriadWritingProblem(
            key = key,
            slots = triads.map(TextbookTriadWritingSlot::rootPosition),
            constraintPreset = constraintPreset,
            ruleProfile = ruleProfile,
            rangeProfile = rangeProfile,
            searchConfig = searchConfig,
            finalTonicMayOmitFifth = finalTonicMayOmitFifth,
        )
}

data class RootPositionTriadSolution(
    val voicings: List<RootPositionTriadVoicing>,
    val breakdown: ScoreBreakdown,
)

data class RootPositionTriadVoicing(
    val slotIndex: Int,
    val triad: NaturalTriad,
    val soprano: Pitch,
    val alto: Pitch,
    val tenor: Pitch,
    val bass: Pitch,
)

/** 原位章节的兼容门面；实际求解经 TextbookTriadWritingSolver 进入 ConstraintProgram。 */
object RootPositionTriadSolver {
    fun solve(problem: RootPositionTriadWritingProblem): List<RootPositionTriadSolution> =
        TextbookTriadWritingSolver.solve(problem.toTextbookProblem()).map { solution ->
            RootPositionTriadSolution(
                voicings = solution.voicings.map { voicing ->
                    RootPositionTriadVoicing(
                        slotIndex = voicing.slotIndex,
                        triad = voicing.triad,
                        soprano = voicing.soprano,
                        alto = voicing.alto,
                        tenor = voicing.tenor,
                        bass = voicing.bass,
                    )
                },
                breakdown = solution.breakdown,
            )
        }
}
