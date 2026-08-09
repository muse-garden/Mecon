package com.mecon.theory

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.PitchClass
import com.mecon.api.runtime.RuntimeScore
import com.mecon.theory.textbook.FirstInversionTriadConnection
import com.mecon.theory.textbook.FirstInversionTriadRules
import com.mecon.theory.textbook.RootPositionTriadRules
import com.mecon.theory.textbook.textbookTriadInKey
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstInversionTriadRulesTest {
    @Test
    fun marksFirstInversionAsBassLineEnrichmentWithoutRequiringRootDoubling() {
        val key = Key.major(PitchClass.C)
        val tonic = RootPositionTriadRules.triadInKey(key, 1)
        val verticality = singleVerticality(
            soprano = "G4",
            alto = "C4",
            tenor = "C4",
            bass = "E3",
        )

        val findings = FirstInversionTriadRules.checkVerticality(
            verticality = verticality,
            triad = tonic,
            key = key,
        )

        assertTrue(findings.any { it.ruleId == FirstInversionTriadRules.FIRST_INVERSION_BASS_LINE })
        assertFalse(findings.any { it.ruleId == FirstInversionTriadRules.MISSING_CHORD_TONE })
    }

    @Test
    fun flagsLeadingToneDoublingInFirstInversion() {
        val key = Key.major(PitchClass.C)
        val dominant = RootPositionTriadRules.triadInKey(key, 5)
        val verticality = singleVerticality(
            soprano = "B4",
            alto = "G4",
            tenor = "D4",
            bass = "B2",
        )

        val findings = FirstInversionTriadRules.checkVerticality(
            verticality = verticality,
            triad = dominant,
            key = key,
        )

        assertTrue(findings.any { it.ruleId == FirstInversionTriadRules.LEADING_TONE_DOUBLED })
    }

    @Test
    fun diminishedTriadReportsViolationOutsideFirstInversionAndIndicationInFirstInversion() {
        val key = Key.major(PitchClass.C)
        val leadingToneTriad = RootPositionTriadRules.triadInKey(key, 7)
        val rootPosition = singleVerticality(
            soprano = "F4",
            alto = "D4",
            tenor = "B3",
            bass = "B2",
        )
        val firstInversion = singleVerticality(
            soprano = "F4",
            alto = "B3",
            tenor = "B3",
            bass = "D3",
        )

        val rootFindings = FirstInversionTriadRules.checkDiminishedTriadPosition(rootPosition, leadingToneTriad)
        val firstFindings = FirstInversionTriadRules.checkDiminishedTriadPosition(firstInversion, leadingToneTriad)

        assertTrue(rootFindings.any { it.kind == RuleFindingKind.VIOLATION })
        assertTrue(firstFindings.any { it.kind == RuleFindingKind.INDICATION })
    }

    @Test
    fun flagsMajorRootDominantFollowedByMinorSixthChord() {
        val key = Key.major(PitchClass.C)
        val dominant = textbookTriadInKey(key, 5, ChordQuality.MAJOR)
        val minorSixth = textbookTriadInKey(key, 6, ChordQuality.MINOR)
        val transition = transition(
            soprano = "D5" to "C5",
            alto = "B4" to "A4",
            tenor = "G3" to "E3",
            bass = "G2" to "C3",
        )

        val findings = FirstInversionTriadRules.checkTransition(
            transition = transition,
            connection = FirstInversionTriadConnection(key, dominant, minorSixth),
        )

        assertTrue(findings.any { it.ruleId == FirstInversionTriadRules.MAJOR_ROOT_DOMINANT_TO_MINOR_SIXTH })
    }

    private fun singleVerticality(
        soprano: String,
        alto: String,
        tenor: String,
        bass: String,
    ): FixedVoiceVerticality =
        VoiceLeadingAnalysis.verticalities(
            fixedVoiceScore(
                sopranoPitches = listOf(listOf(Pitch.fromName(soprano))),
                altoPitches = listOf(listOf(Pitch.fromName(alto))),
                tenorPitches = listOf(listOf(Pitch.fromName(tenor))),
                bassPitches = listOf(listOf(Pitch.fromName(bass))),
            )
        ).single()

    private fun transition(
        soprano: Pair<String, String>,
        alto: Pair<String, String>,
        tenor: Pair<String, String>,
        bass: Pair<String, String>,
    ): FixedVoiceTransition =
        VoiceLeadingAnalysis.transitions(
            fixedVoiceScore(
                sopranoPitches = listOf(listOf(Pitch.fromName(soprano.first)), listOf(Pitch.fromName(soprano.second))),
                altoPitches = listOf(listOf(Pitch.fromName(alto.first)), listOf(Pitch.fromName(alto.second))),
                tenorPitches = listOf(listOf(Pitch.fromName(tenor.first)), listOf(Pitch.fromName(tenor.second))),
                bassPitches = listOf(listOf(Pitch.fromName(bass.first)), listOf(Pitch.fromName(bass.second))),
            )
        ).single()

    private fun fixedVoiceScore(
        sopranoPitches: List<List<Pitch>>,
        altoPitches: List<List<Pitch>>,
        tenorPitches: List<List<Pitch>>,
        bassPitches: List<List<Pitch>>,
    ): FixedVoiceScore {
        val runtime = RuntimeScore.fromStorage(
            fixedVoiceStorageScore(
                sopranoPitches = sopranoPitches,
                altoPitches = altoPitches,
                tenorPitches = tenorPitches,
                bassPitches = bassPitches,
                sopranoDuration = Duration.QUARTER,
                altoDuration = Duration.QUARTER,
                tenorDuration = Duration.QUARTER,
                bassDuration = Duration.QUARTER,
            )
        )
        return FixedVoiceScore.load(runtime, FixedVoiceLayout.fourPartKeyboard(runtime))
    }
}
