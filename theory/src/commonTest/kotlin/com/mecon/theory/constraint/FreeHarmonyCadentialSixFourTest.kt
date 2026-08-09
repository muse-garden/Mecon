package com.mecon.theory.constraint

import com.mecon.api.primitive.NoteName
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.PitchClass
import com.mecon.api.primitive.TrackId
import com.mecon.theory.Key
import com.mecon.theory.PrefixDiversitySearchConfig
import com.mecon.theory.RuleSeverity
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.SpelledPitchClass
import com.mecon.theory.TonalContext
import com.mecon.theory.TonalPlan
import com.mecon.theory.TonalSpan
import com.mecon.theory.VoiceBoundary
import com.mecon.theory.VoicePlan
import com.mecon.theory.VoiceRange
import com.mecon.theory.VoiceSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FreeHarmonyCadentialSixFourTest {
    private val key = Key.major(PitchClass.C)
    private val context = TonalContext.fromKey(key, SpelledPitchClass(NoteName.C))
    private val tonalPlan = TonalPlan(listOf(TonalSpan(SlotWindow(0, null), context)))
    private val vocabulary = DiatonicChordVocabulary.forContext(
        context = context,
        compatibilityKey = key,
        includeSevenths = false,
        includeInversions = true,
    )

    @Test
    fun leadingTonicCadentialSixFourUsesOrderedWritingWithWorkspaceSatbVoices() {
        val targets = listOf(
            target(degree = 1, inversion = 0),
            target(degree = 2, inversion = 0),
            target(degree = 1, inversion = 2),
            target(degree = 5, inversion = 0),
            target(degree = 1, inversion = 0),
        )
        val solution = solve(workspaceSatbVoicePlan(), targets).single()
        val sixFour = solution.voicings[2]
        val dominant = solution.voicings[3]
        val openingSoprano = solution.voicings.first().pitchesByVoiceId
            .getValue(TrackId("free-practice-voice-1"))
        val pinnedGOpening = solve(
            workspaceSatbVoicePlan(),
            targets,
            pitchPins = listOf(
                VoicePitchPin(
                    slot = 0,
                    voiceId = TrackId("free-practice-voice-1"),
                    pitch = Pitch.fromName("G4"),
                )
            ),
        ).single()

        assertTrue(solution.breakdown.findings.none { it.ruleId in crossingRuleIds })
        assertTrue(
            openingSoprano.midiNumber > Pitch.fromName("C4").midiNumber,
            "前缀多样化搜索应比较较高的主和弦排列，而不是停在女高音音域下界",
        )
        assertTrue(
            solution.breakdown.total <= pinnedGOpening.breakdown.total + 1e-9,
            "未固定的搜索不应错过固定 G4 后可见的更优完整路径",
        )
        assertEquals(
            sixFour.pitchesByVoiceId.getValue(TrackId("free-practice-voice-4")),
            dominant.pitchesByVoiceId.getValue(TrackId("free-practice-voice-4")),
            "终止四六到属和弦应能在标准低音音域内保持属音",
        )
    }

    @Test
    fun userForcedInnerCrossingRemainsSolvableAndSoft() {
        val fixedPitches = listOf("E5", "G4", "C5", "C4").map(Pitch::fromName)
        val voicePlan = VoicePlan(
            fixedPitches.mapIndexed { index, pitch ->
                VoiceSpec(
                    id = TrackId("forced-$index"),
                    order = index,
                    boundary = boundaryAt(index),
                    range = VoiceRange(pitch, pitch),
                )
            }
        )
        val solution = solve(
            voicePlan = voicePlan,
            targets = listOf(target(degree = 1, inversion = 0)),
        ).single()
        val crossing = solution.breakdown.findings.single {
            it.ruleId == FreeHarmonyRuleProvider.INNER_CROSSING
        }

        assertEquals(RuleSeverity.SOFT, crossing.severity)
        assertFalse(solution.breakdown.hasHardViolation)
    }

    @Test
    fun userPinnedRangeEdgeRemainsSolvableAndSoft() {
        val solution = solve(
            voicePlan = workspaceSatbVoicePlan(),
            targets = listOf(
                target(degree = 1, inversion = 0),
                target(degree = 2, inversion = 0),
            ),
            pitchPins = listOf(
                VoicePitchPin(
                    slot = 0,
                    voiceId = TrackId("free-practice-voice-1"),
                    pitch = Pitch.fromName("C4"),
                )
            ),
        ).single()
        val reserve = solution.breakdown.findings.first {
            it.ruleId == FreeHarmonyRuleProvider.CONTINUATION_RESERVE
        }

        assertEquals(RuleSeverity.SOFT, reserve.severity)
        assertEquals(
            Pitch.fromName("C4"),
            solution.voicings.first().pitchesByVoiceId.getValue(TrackId("free-practice-voice-1")),
        )
        assertFalse(solution.breakdown.hasHardViolation)
    }

    private fun solve(
        voicePlan: VoicePlan,
        targets: List<ChordTarget>,
        pitchPins: List<VoicePitchPin> = emptyList(),
    ): List<PolyphonicConstraintSolution> =
        ConstraintProgramSolver.solvePolyphonic(
            FreeHarmonySolver.compile(request(voicePlan, targets, pitchPins))
        )

    private fun request(
        voicePlan: VoicePlan,
        targets: List<ChordTarget>,
        pitchPins: List<VoicePitchPin>,
    ) = FreeHarmonyRequest(
        key = key,
        tonalPlan = tonalPlan,
        slotCount = targets.size,
        vocabulary = vocabulary,
        voicePlan = voicePlan,
        fixedTargetIdentityBySlot = targets
            .mapIndexed { index, target -> index to target.identityKey() }
            .toMap(),
        pitchPins = pitchPins,
        searchConfig = SearchConfig(
            maxResults = 1,
            prefixDiversity = PrefixDiversitySearchConfig(enabled = true),
        ),
    )

    private fun target(degree: Int, inversion: Int): ChordTarget =
        vocabulary.single { it.degree == degree && it.inversion == inversion }

    private fun workspaceSatbVoicePlan(): VoicePlan = VoicePlan(
        VoicePlan.standardFourPart().orderedHighToLow.mapIndexed { index, standard ->
            VoiceSpec(
                id = TrackId("free-practice-voice-${index + 1}"),
                order = index,
                boundary = boundaryAt(index),
                range = standard.range,
                label = listOf("S", "A", "T", "B")[index],
            )
        }
    )

    private fun boundaryAt(index: Int): VoiceBoundary = when (index) {
        0 -> VoiceBoundary.UPPER_OUTER
        3 -> VoiceBoundary.LOWER_OUTER
        else -> VoiceBoundary.INNER
    }

    private companion object {
        val crossingRuleIds = setOf(
            FreeHarmonyRuleProvider.OUTER_CROSSING,
            FreeHarmonyRuleProvider.INNER_CROSSING,
        )
    }
}
