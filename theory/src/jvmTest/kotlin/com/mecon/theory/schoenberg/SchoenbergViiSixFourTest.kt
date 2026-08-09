package com.mecon.theory.schoenberg

import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.Key
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.SearchConfig
import com.mecon.theory.constraint.ConstraintProgramSolver
import com.mecon.theory.textbook.TextbookTriadPosition
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 减三导和弦的四六和弦（vii°6/4）回归：勋伯格用它做 IV-vii°6/4-iii、ii6-vii°6/4-iii，导和弦解决到 iii。
 * 枚举**不排除**它，而是按正确声部进行路由：二转位导和弦的五音在低音，须由前一和弦低音同声部预备
 * （IV / ii6 低音都是 F），并下行级进解决到 iii 根位（低音 = III）。解决到 iii6 会写不出，枚举也不产出。
 */
class SchoenbergViiSixFourTest {
    private val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
    private val R = TextbookTriadPosition.ROOT_POSITION
    private val S1 = TextbookTriadPosition.FIRST_INVERSION
    private val S2 = TextbookTriadPosition.SECOND_INVERSION

    private fun triad(degree: Int, quality: ChordQuality, pos: TextbookTriadPosition) =
        SchoenbergSymbolicChord(degree = degree, quality = quality, position = pos, arity = ChordArity.TRIAD)

    private fun solves(slots: List<SchoenbergSymbolicChord>): Boolean {
        val prog = SchoenbergSymbolicProgression(slots = slots, kind = SchoenbergConnectionKind.INTEGRATED)
        val program = SchoenbergIntegratedTechTree.program(
            key = key,
            continuationChordCount = slots.size - 1,
            treatmentIds = setOf(
                SchoenbergHarmonicTreatments.LEADING_TRIAD,
                SchoenbergHarmonicTreatments.FIRST_INVERSION,
                SchoenbergHarmonicTreatments.SECOND_INVERSION,
            ),
            progression = prog,
            searchConfig = SearchConfig(maxResults = 1, beamWidth = 384),
        )
        return ConstraintProgramSolver.solve(program).isNotEmpty()
    }

    @Test
    fun schoenbergTextbookViiSixFourResolutionsSolve() {
        // IV - vii°6/4 - iii（根位）
        assertTrue(solves(listOf(
            triad(1, ChordQuality.MAJOR, R), triad(4, ChordQuality.MAJOR, R),
            triad(7, ChordQuality.DIMINISHED, S2), triad(3, ChordQuality.MINOR, R), triad(1, ChordQuality.MAJOR, R),
        )), "IV-vii°6/4-iii 应可求解。")
        // ii6 - vii°6/4 - iii（根位），前置 IV 引入 ii6
        assertTrue(solves(listOf(
            triad(1, ChordQuality.MAJOR, R), triad(4, ChordQuality.MAJOR, R), triad(2, ChordQuality.MINOR, S1),
            triad(7, ChordQuality.DIMINISHED, S2), triad(3, ChordQuality.MINOR, R), triad(1, ChordQuality.MAJOR, R),
        )), "ii6-vii°6/4-iii 应可求解。")
    }

    @Test
    fun viiSixFourResolvingToFirstInversionMediantIsUnwritable() {
        // vii°6/4 -> iii6（低音上行到 G，五音无法下行解决），四部写不出。
        assertTrue(!solves(listOf(
            triad(1, ChordQuality.MAJOR, R), triad(4, ChordQuality.MAJOR, R),
            triad(7, ChordQuality.DIMINISHED, S2), triad(3, ChordQuality.MINOR, S1), triad(1, ChordQuality.MAJOR, R),
        )), "vii°6/4 解决到 iii6 应写不出。")
    }

    @Test
    fun viiSixFourIsReachableAndSolvableInSecondInversionExercise() {
        val id = SchoenbergCommonToneExercises.INTEGRATED_MAJOR_SECOND_INVERSION_EXERCISE_ID
        val count = SchoenbergCommonToneExercises.minContinuationChordCount(id)
        val withVii = SchoenbergCommonToneExercises.enumerateForExercise(id, key, count).filter { p ->
            p.slots.any {
                it.arity == ChordArity.TRIAD && it.position == S2 && it.quality == ChordQuality.DIMINISHED
            }
        }
        assertTrue(withVii.isNotEmpty(), "四六和弦综合练习应能枚举出含 vii°6/4 的进行。")
        // 枚举出的每个含 vii°6/4 的进行里，vii°6/4 都解决到 iii 根位。
        assertTrue(
            withVii.all { p ->
                p.slots.zipWithNext().none { (a, b) ->
                    a.arity == ChordArity.TRIAD && a.position == S2 && a.quality == ChordQuality.DIMINISHED &&
                        !(b.degree == 3 && b.position == R)
                }
            },
            "枚举出的 vii°6/4 必须解决到 iii 根位。",
        )
        val program = SchoenbergCommonToneExercises.programForExercise(
            id, key, count, progression = withVii.first(),
            searchConfig = SearchConfig(maxResults = 1, beamWidth = 160))
        assertTrue(ConstraintProgramSolver.solve(program).isNotEmpty(), "含 vii°6/4 的枚举进行应可求解。")
    }
}
