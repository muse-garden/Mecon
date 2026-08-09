package com.mecon.theory.constraint

import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.PitchClass
import com.mecon.api.primitive.TrackId
import com.mecon.theory.FixedVoiceWritingState
import com.mecon.theory.Key
import com.mecon.theory.PrefixDiversitySearchConfig
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.TonalContext
import com.mecon.theory.TonalPlan
import com.mecon.theory.TonalSpan
import com.mecon.theory.VoicePlan
import com.mecon.theory.toFixedVoices
import com.mecon.theory.schoenberg.SchoenbergMinorSubdominantChapter
import com.mecon.theory.schoenberg.SchoenbergPracticeTeachingRuleProjector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FreeHarmonyNeapolitanTest {
    private val key = Key.major(PitchClass.C)
    private val sourceProgram = SchoenbergMinorSubdominantChapter.neapolitanCadenceProgram(key)
    private val neapolitan = sourceProgram.slotDomains[0].targets.single()
    private val cadentialSixFour = sourceProgram.slotDomains[1].targets.single()
    private val voicePlan = VoicePlan.standardFourPart()
    private val search = SearchConfig(
        maxResults = 1,
        beamWidth = 32,
        prefixDiversity = PrefixDiversitySearchConfig(enabled = true),
    )

    @Test
    fun lowBassFourthIsInsideTheRangeAndEnumeratedForNeapolitanSixth() {
        val request = request(emptyList())
        val program = FreeHarmonySolver.compile(request)
        val voices = program.resolvedVoicePlan.toFixedVoices()
        val candidates = ChordTargetCandidateFactory(program, voices).candidates(
            state = FixedVoiceWritingState(),
            slotIndex = 0,
            target = neapolitan,
            task = program.toWritingTask(),
        )
        val bass = voices.last()

        assertTrue(Pitch.fromName("F2") in voicePlan.orderedHighToLow.last().range)
        assertTrue(
            candidates.any { it.pitchFor(bass) == Pitch.fromName("F2") },
            "N6 candidate pool should preserve the lower-octave scale degree 4",
        )
    }

    @Test
    fun neapolitanFlatSixBelowRootMovesInAllowedParallelFourthsWithLowBass() {
        val preferences = sourceProgram.constraints
            .filter { it.ruleId == SchoenbergMinorSubdominantChapter.NEAPOLITAN_TO_SIX_FOUR_RULE_ID }
            .map {
                it.copy(
                    modality = ConstraintModality.Prefer(
                        SchoenbergPracticeTeachingRuleProjector.VOICE_LEADING_RULE_WEIGHT,
                    )
                )
            }
        val pins = listOf(
            VoicePitchPin(0, TrackId("solver-soprano"), Pitch.fromName("Db5")),
            VoicePitchPin(0, TrackId("solver-alto"), Pitch.fromName("F4")),
            VoicePitchPin(0, TrackId("solver-tenor"), Pitch.fromName("Ab3")),
        )
        val program = FreeHarmonySolver.compile(request(preferences, pins))
        val solutions = ConstraintProgramSolver.solvePolyphonic(program)
        assertTrue(
            solutions.isNotEmpty(),
            "Pinned N6 should be solvable: ${ConstraintProgramSolver.trace(program, 512).trace}",
        )
        val solution = solutions.first()
        val first = solution.voicings[0].pitchesByVoiceId
        val second = solution.voicings[1].pitchesByVoiceId

        assertEquals(Pitch.fromName("F2"), first.getValue(TrackId("solver-bass")))
        assertEquals(
            listOf("C5", "E4", "G3", "G2").map(Pitch::fromName),
            listOf("soprano", "alto", "tenor", "bass").map { role ->
                second.getValue(TrackId("solver-$role"))
            },
            "flat 2 and flat 6 should descend in allowed parallel fourths",
        )
        assertFalse(
            solution.breakdown.findings.any {
                it.ruleId == FreeHarmonyRuleProvider.PARALLEL_PERFECT
            },
            "parallel fourths must not be reported as forbidden parallel perfects",
        )
    }

    private fun request(
        additionalConstraints: List<Constraint>,
        pitchPins: List<VoicePitchPin> = emptyList(),
    ): FreeHarmonyRequest {
        val context = TonalContext.fromKey(key)
        return FreeHarmonyRequest(
            key = key,
            tonalPlan = TonalPlan(listOf(TonalSpan(SlotWindow(0, 1), context))),
            slotCount = 2,
            vocabulary = listOf(neapolitan, cadentialSixFour),
            voicePlan = voicePlan,
            fixedTargetIdentityBySlot = mapOf(
                0 to neapolitan.identityKey(),
                1 to cadentialSixFour.identityKey(),
            ),
            pitchPins = pitchPins,
            additionalConstraints = additionalConstraints,
            searchConfig = search,
        )
    }
}
