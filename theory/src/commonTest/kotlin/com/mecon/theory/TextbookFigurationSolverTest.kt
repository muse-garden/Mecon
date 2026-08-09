package com.mecon.theory

import com.mecon.api.primitive.PitchClass
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.textbook.SuspensionInterval
import com.mecon.theory.textbook.TextbookFigurationProblem
import com.mecon.theory.textbook.TextbookFigurationSolver
import com.mecon.theory.textbook.TextbookTriadWritingSlot
import com.mecon.theory.textbook.textbookTriadInKey
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TextbookFigurationSolverTest {
    @Test
    fun solvesFourThreeThroughCanonicalFourPartWriting() {
        val key = Key.major(PitchClass.C)
        val slots = listOf(
            TextbookTriadWritingSlot.rootPosition(textbookTriadInKey(key, 4)),
            TextbookTriadWritingSlot.rootPosition(textbookTriadInKey(key, 1)),
        )
        assertNotNull(TextbookFigurationSolver.solve(TextbookFigurationProblem(key, slots)), "base harmony")
        assertNotNull(TextbookFigurationSolver.solve(TextbookFigurationProblem(
            key, slots, FixedVoiceRole.SOPRANO, requiredDiatonicDeltas = listOf(-1)
        )), "soprano descending step")
        val projected = assertNotNull(TextbookFigurationSolver.solve(TextbookFigurationProblem(
            key, slots, FixedVoiceRole.SOPRANO, requiredDiatonicDeltas = listOf(-1),
            requiredVoiceTones = listOf(ChordTone.ROOT, ChordTone.THIRD),
        )), "projected chord tones")
        val solution = assertNotNull(TextbookFigurationSolver.solve(TextbookFigurationProblem(
            key, slots, FixedVoiceRole.SOPRANO, requiredDiatonicDeltas = listOf(-1),
            requiredVoiceTones = listOf(ChordTone.ROOT, ChordTone.THIRD),
            suspensionIntervals = listOf(SuspensionInterval(4, 3)),
        )), "4-3 interval from ${projected.harmony}")
        assertFalse(solution.breakdown.hasHardViolation)
        assertEquals(-1, solution.harmony[1].soprano.diatonicSteps - solution.harmony[0].soprano.diatonicSteps)
    }

    @Test
    fun twoThreeSuspensionIsProjectedToDescendingBass() {
        val key = Key.major(PitchClass.C)
        val solution = assertNotNull(TextbookFigurationSolver.solve(TextbookFigurationProblem(
            key = key,
            slots = listOf(
                TextbookTriadWritingSlot.rootPosition(textbookTriadInKey(key, 1)),
                TextbookTriadWritingSlot.firstInversion(textbookTriadInKey(key, 5)),
            ),
            figuredVoice = FixedVoiceRole.BASS,
            requiredDiatonicDeltas = listOf(-1),
            suspensionIntervals = listOf(SuspensionInterval(2, 3)),
        )))

        assertEquals(FixedVoiceRole.BASS, solution.figuredVoice)
        assertEquals(-1, solution.harmony[1].bass.diatonicSteps - solution.harmony[0].bass.diatonicSteps)
        assertFalse(solution.breakdown.hasHardViolation)
    }
}
