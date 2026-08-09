package com.mecon.theory

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Pitch
import com.mecon.api.runtime.RuntimeScore
import com.mecon.theory.textbook.FourPartTextbookRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FourPartTextbookRulesTest {
    @Test
    fun treatsAdjacentVoiceUnisonAsSoft() {
        val fixed = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("C5"))),
            altoPitches = listOf(listOf(Pitch.fromName("C5"))),
            tenorPitches = listOf(listOf(Pitch.fromName("E4"))),
            bassPitches = listOf(listOf(Pitch.fromName("C3"))),
        )

        val finding = FourPartTextbookRules.checkFixedVoiceScoreFindings(fixed)
            .single { it.ruleId == AdjacentVoiceUnisonRule.RULE_ID }

        assertEquals(RuleSeverity.SOFT, finding.severity)
        assertEquals(2, finding.anchors.size)
    }

    @Test
    fun flagsOuterVoiceCrossingEvenWhenOnlyOneVoiceMoves() {
        val fixed = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("C5")), listOf(Pitch.fromName("C4"))),
            altoPitches = listOf(listOf(Pitch.fromName("E4"))),
            tenorPitches = listOf(listOf(Pitch.fromName("C4"))),
            bassPitches = listOf(listOf(Pitch.fromName("C3"))),
            sopranoDuration = Duration.QUARTER,
            altoDuration = Duration.HALF,
        )

        val diagnostics = FourPartTextbookRules.checkFixedVoiceScore(fixed)

        assertTrue(diagnostics.any { it.ruleId == FourPartTextbookRules.OUTER_VOICE_CROSSING })
    }

    @Test
    fun allowsTemporaryInnerVoiceCrossingWithinOuterBounds() {
        val fixed = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("G4"))),
            altoPitches = listOf(listOf(Pitch.fromName("C4")), listOf(Pitch.fromName("E4"))),
            tenorPitches = listOf(listOf(Pitch.fromName("E4")), listOf(Pitch.fromName("C4"))),
            bassPitches = listOf(listOf(Pitch.fromName("C3"))),
            altoDuration = Duration.QUARTER,
            tenorDuration = Duration.QUARTER,
        )

        val diagnostics = FourPartTextbookRules.checkFixedVoiceScore(fixed)

        assertFalse(diagnostics.any { it.ruleId == FourPartTextbookRules.OUTER_VOICE_CROSSING })
    }

    @Test
    fun flagsNonLowAdjacentVoicesWiderThanOctave() {
        val fixed = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("G5"))),
            altoPitches = listOf(listOf(Pitch.fromName("C4"))),
            tenorPitches = listOf(listOf(Pitch.fromName("E3"))),
            bassPitches = listOf(listOf(Pitch.fromName("C3"))),
        )

        val spacing = FourPartTextbookRules.checkFixedVoiceScore(fixed)
            .single { it.ruleId == FourPartTextbookRules.UPPER_VOICE_SPACING }

        assertEquals(2, spacing.anchors.size)
    }

    @Test
    fun checksRangesThroughConfigurableProfile() {
        val fixed = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("A5"))),
            altoPitches = listOf(listOf(Pitch.fromName("C4"))),
            tenorPitches = listOf(listOf(Pitch.fromName("E4"))),
            bassPitches = listOf(listOf(Pitch.fromName("C3"))),
        )

        val defaultDiagnostics = FourPartTextbookRules.checkFixedVoiceScore(fixed)
        val relaxedDiagnostics = FourPartTextbookRules.checkFixedVoiceScore(
            fixed,
            rangeProfile = VoiceRangeProfile.humanFourPart(
                soprano = VoiceRange(Pitch.fromName("C4"), Pitch.fromName("A5")),
            ),
        )

        assertTrue(defaultDiagnostics.any { it.ruleId == FourPartTextbookRules.VOICE_RANGE })
        assertFalse(relaxedDiagnostics.any { it.ruleId == FourPartTextbookRules.VOICE_RANGE })
    }

    @Test
    fun flagsParallelFifthsAndOctavesIncludingCompoundOctaves() {
        val parallelFifth = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("A4")), listOf(Pitch.fromName("G4"))),
            altoPitches = listOf(listOf(Pitch.fromName("C4"))),
            tenorPitches = listOf(listOf(Pitch.fromName("E3"))),
            bassPitches = listOf(listOf(Pitch.fromName("D3")), listOf(Pitch.fromName("C3"))),
            bassDuration = Duration.QUARTER,
        )
        val parallelOctave = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("C5")), listOf(Pitch.fromName("D5"))),
            altoPitches = listOf(listOf(Pitch.fromName("E4"))),
            tenorPitches = listOf(listOf(Pitch.fromName("G3"))),
            bassPitches = listOf(listOf(Pitch.fromName("C3")), listOf(Pitch.fromName("D3"))),
            bassDuration = Duration.QUARTER,
        )

        val fifthDiagnostics = FourPartTextbookRules.checkFixedVoiceScore(parallelFifth)
        val octaveDiagnostics = FourPartTextbookRules.checkFixedVoiceScore(parallelOctave)

        assertTrue(fifthDiagnostics.any { it.ruleId == FourPartTextbookRules.PARALLEL_FIFTH })
        assertTrue(octaveDiagnostics.any { it.ruleId == FourPartTextbookRules.PARALLEL_OCTAVE })
    }

    @Test
    fun canCheckOnlyOneGeneratedTransitionForParallelFifths() {
        val fixed = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("A4")), listOf(Pitch.fromName("G4"))),
            altoPitches = listOf(listOf(Pitch.fromName("C4"))),
            tenorPitches = listOf(listOf(Pitch.fromName("E3"))),
            bassPitches = listOf(listOf(Pitch.fromName("D3")), listOf(Pitch.fromName("C3"))),
            bassDuration = Duration.QUARTER,
        )
        val transition = VoiceLeadingAnalysis.transitions(fixed).single()

        val findings = FourPartTextbookRules.checkFixedVoiceTransition(transition)

        assertTrue(findings.any { it.ruleId == FourPartTextbookRules.PARALLEL_FIFTH })
        assertTrue(findings.all { finding ->
            finding.anchors.all { transition.containsAnchor(it) }
        })
    }

    @Test
    fun flagsOctaveDisplacedCorrectionOfParallelFifthsAndOctaves() {
        val correctedFifth = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("C5"))),
            altoPitches = listOf(listOf(Pitch.fromName("E4"))),
            tenorPitches = listOf(listOf(Pitch.fromName("A3")), listOf(Pitch.fromName("G3"))),
            bassPitches = listOf(listOf(Pitch.fromName("D3")), listOf(Pitch.fromName("C4"))),
            tenorDuration = Duration.QUARTER,
            bassDuration = Duration.QUARTER,
        )
        val correctedOctave = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("C5")), listOf(Pitch.fromName("D5"))),
            altoPitches = listOf(listOf(Pitch.fromName("E4"))),
            tenorPitches = listOf(listOf(Pitch.fromName("G3"))),
            bassPitches = listOf(listOf(Pitch.fromName("C3")), listOf(Pitch.fromName("D4"))),
            bassDuration = Duration.QUARTER,
        )

        assertTrue(
            FourPartTextbookRules.checkFixedVoiceScore(correctedFifth)
                .any { it.ruleId == FourPartTextbookRules.PARALLEL_FIFTH }
        )
        assertTrue(
            FourPartTextbookRules.checkFixedVoiceScore(correctedOctave)
                .any { it.ruleId == FourPartTextbookRules.PARALLEL_OCTAVE }
        )
    }

    @Test
    fun doesNotTreatPlainParallelFourthsAsCorrectedParallelFifths() {
        val fixed = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("C5")), listOf(Pitch.fromName("D5"))),
            altoPitches = listOf(listOf(Pitch.fromName("G4")), listOf(Pitch.fromName("A4"))),
            tenorPitches = listOf(listOf(Pitch.fromName("E3"))),
            bassPitches = listOf(listOf(Pitch.fromName("C3"))),
        )

        val diagnostics = FourPartTextbookRules.checkFixedVoiceScore(fixed)

        assertFalse(diagnostics.any { it.ruleId == FourPartTextbookRules.PARALLEL_FIFTH })
    }

    @Test
    fun flagsUnequalFifthsOnlyFromLowVoiceToPerfectFifthInSimilarMotion() {
        val unequal = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("C5"))),
            altoPitches = listOf(listOf(Pitch.fromName("E4"))),
            tenorPitches = listOf(listOf(Pitch.fromName("F3")), listOf(Pitch.fromName("G3"))),
            bassPitches = listOf(listOf(Pitch.fromName("B2")), listOf(Pitch.fromName("C3"))),
            tenorDuration = Duration.QUARTER,
            bassDuration = Duration.QUARTER,
        )
        val contrary = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("C5"))),
            altoPitches = listOf(listOf(Pitch.fromName("E4"))),
            tenorPitches = listOf(listOf(Pitch.fromName("F3")), listOf(Pitch.fromName("G3"))),
            bassPitches = listOf(listOf(Pitch.fromName("B2")), listOf(Pitch.fromName("A2"))),
            tenorDuration = Duration.QUARTER,
            bassDuration = Duration.QUARTER,
        )

        assertTrue(
            FourPartTextbookRules.checkFixedVoiceScore(unequal)
                .any { it.ruleId == FourPartTextbookRules.UNEQUAL_FIFTH }
        )
        assertFalse(
            FourPartTextbookRules.checkFixedVoiceScore(contrary)
                .any { it.ruleId == FourPartTextbookRules.UNEQUAL_FIFTH }
        )
    }

    @Test
    fun flagsHiddenPerfectConsonancesOnlyForOuterVoicesWhenSopranoLeaps() {
        val hiddenFifth = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("C5")), listOf(Pitch.fromName("G5"))),
            altoPitches = listOf(listOf(Pitch.fromName("C5"))),
            tenorPitches = listOf(listOf(Pitch.fromName("G3"))),
            bassPitches = listOf(listOf(Pitch.fromName("C3")), listOf(Pitch.fromName("C4"))),
            bassDuration = Duration.QUARTER,
        )
        val hiddenOctave = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("D5")), listOf(Pitch.fromName("G5"))),
            altoPitches = listOf(listOf(Pitch.fromName("C5"))),
            tenorPitches = listOf(listOf(Pitch.fromName("E3"))),
            bassPitches = listOf(listOf(Pitch.fromName("C3")), listOf(Pitch.fromName("G3"))),
            bassDuration = Duration.QUARTER,
        )
        val sopranoStep = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("F5")), listOf(Pitch.fromName("G5"))),
            altoPitches = listOf(listOf(Pitch.fromName("C5"))),
            tenorPitches = listOf(listOf(Pitch.fromName("E3"))),
            bassPitches = listOf(listOf(Pitch.fromName("C3")), listOf(Pitch.fromName("G3"))),
            bassDuration = Duration.QUARTER,
        )

        assertTrue(
            FourPartTextbookRules.checkFixedVoiceScore(hiddenFifth)
                .any { it.ruleId == FourPartTextbookRules.HIDDEN_FIFTH }
        )
        assertTrue(
            FourPartTextbookRules.checkFixedVoiceScore(hiddenOctave)
                .any { it.ruleId == FourPartTextbookRules.HIDDEN_OCTAVE }
        )
        assertFalse(
            FourPartTextbookRules.checkFixedVoiceScore(sopranoStep)
                .any { it.ruleId == FourPartTextbookRules.HIDDEN_OCTAVE }
        )
    }

    @Test
    fun contraryMotionIntoPerfectConsonanceIsNotParallelOrHidden() {
        val fixed = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("A4")), listOf(Pitch.fromName("G4"))),
            altoPitches = listOf(listOf(Pitch.fromName("E4"))),
            tenorPitches = listOf(listOf(Pitch.fromName("G3"))),
            bassPitches = listOf(listOf(Pitch.fromName("C3")), listOf(Pitch.fromName("C4"))),
            bassDuration = Duration.QUARTER,
        )

        val diagnostics = FourPartTextbookRules.checkFixedVoiceScore(fixed)

        assertFalse(diagnostics.any { it.ruleId == FourPartTextbookRules.PARALLEL_FIFTH })
        assertFalse(diagnostics.any { it.ruleId == FourPartTextbookRules.HIDDEN_FIFTH })
    }

    private fun fixedVoiceScore(
        sopranoPitches: List<List<Pitch>>,
        altoPitches: List<List<Pitch>>,
        tenorPitches: List<List<Pitch>>,
        bassPitches: List<List<Pitch>>,
        sopranoDuration: Duration = Duration.QUARTER,
        altoDuration: Duration = Duration.QUARTER,
        tenorDuration: Duration = Duration.HALF,
        bassDuration: Duration = Duration.HALF,
    ): FixedVoiceScore {
        val runtime = RuntimeScore.fromStorage(
            fixedVoiceStorageScore(
                sopranoPitches = sopranoPitches,
                altoPitches = altoPitches,
                tenorPitches = tenorPitches,
                bassPitches = bassPitches,
                sopranoDuration = sopranoDuration,
                altoDuration = altoDuration,
                tenorDuration = tenorDuration,
                bassDuration = bassDuration,
            )
        )
        return FixedVoiceScore.load(runtime, FixedVoiceLayout.fourPartKeyboard(runtime))
    }
}
