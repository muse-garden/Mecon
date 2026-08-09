package com.mecon.theory.constraint

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.theory.ConstraintSlot
import com.mecon.theory.HarmonicTimeSpan
import com.mecon.theory.HarmonySlotId
import com.mecon.theory.Key
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.NaturalTriad
import com.mecon.theory.NaturalTriads
import com.mecon.theory.textbook.TextbookTriadPosition
import com.mecon.theory.textbook.TextbookTriadTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Free-practice slots carry the timeline geometry the user dragged, so grid-aligned values that are
 * not plain powers of two reach the solver.
 */
class ConstraintProgramSlotDurationTest {

    private val cMajor: Key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)

    private fun triad(degree: Int): NaturalTriad =
        NaturalTriads.inKey(cMajor).first { it.degree == degree }

    private fun rootSlot(degree: Int): SlotDomain =
        SlotDomain(listOf(TextbookTriadTarget(triad(degree), TextbookTriadPosition.ROOT_POSITION)))

    /** Onsets only have to be ordered; the durations are what this test exercises. */
    private fun programWithDurations(durations: List<Fraction>): ConstraintProgram {
        val domains = listOf(rootSlot(1), rootSlot(5), rootSlot(1))
        val base = ConstraintProgram.fromRequirements(
            key = cMajor,
            slotDomains = domains,
            configuration = ConstraintRequirementConfiguration(),
        )
        return base.copy(
            slots = domains.mapIndexed { index, domain ->
                ConstraintSlot(
                    id = HarmonySlotId("slot-$index"),
                    time = HarmonicTimeSpan(
                        onset = TimeCode.of(index + 1, Fraction.ZERO),
                        duration = durations[index],
                    ),
                    domain = domain,
                )
            },
        )
    }

    @Test
    fun dottedSlotsKeepTheirWrittenDuration() {
        val program = programWithDurations(
            listOf(Fraction(3, 8), Fraction(3, 4), Fraction.QUARTER),
        )

        assertEquals(Duration.DOTTED_QUARTER, program.durationAt(0))
        assertEquals(Duration.DOTTED_HALF, program.durationAt(1))
        assertEquals(Duration.QUARTER, program.durationAt(2))
    }

    @Test
    fun tiedSlotsFallBackToTheLongestFittingSymbol() {
        val program = programWithDurations(
            listOf(Fraction(5, 8), Fraction(9, 16), Fraction.QUARTER),
        )

        assertEquals(Duration.HALF, program.durationAt(0))
        assertEquals(Duration.HALF, program.durationAt(1))
    }

    @Test
    fun solverWritesProgressionsWhoseSlotsAreDotted() {
        val program = programWithDurations(
            listOf(Fraction(3, 8), Fraction(3, 8), Fraction.QUARTER),
        )

        val solutions = ConstraintProgramSolver.solve(program)

        assertTrue(solutions.isNotEmpty(), "带附点时值的 I-V-I 应有候选")
        assertEquals(listOf(1, 5, 1), solutions.first().voicings.map { it.target.degree })
    }
}
