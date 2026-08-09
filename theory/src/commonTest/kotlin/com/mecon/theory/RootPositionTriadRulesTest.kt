package com.mecon.theory

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.PitchClass
import com.mecon.api.runtime.RuntimeScore
import com.mecon.theory.textbook.MelodyTextbookRules
import com.mecon.theory.textbook.RootPositionTriadConnection
import com.mecon.theory.textbook.RootPositionTriadRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootPositionTriadRulesTest {
    @Test
    fun flagsMissingChordToneInNonFinalFourPartTriadButAllowsFinalTonicWithTripledRoot() {
        val key = Key.major(PitchClass.C)
        val tonic = RootPositionTriadRules.triadInKey(key, 1)
        val nonFinal = singleVerticality(
            soprano = "C5",
            alto = "E4",
            tenor = "C4",
            bass = "C3",
        )
        val final = singleVerticality(
            soprano = "C5",
            alto = "E4",
            tenor = "C4",
            bass = "C3",
        )

        val nonFinalFindings = RootPositionTriadRules.checkVerticality(
            nonFinal,
            tonic,
            key,
        )
        val finalFindings = RootPositionTriadRules.checkVerticality(
            final,
            tonic,
            key,
            isFinal = true,
        )

        assertTrue(nonFinalFindings.any { it.ruleId == RootPositionTriadRules.MISSING_CHORD_TONE })
        assertFalse(finalFindings.any { it.ruleId == RootPositionTriadRules.FINAL_TONIC_SPACING })
        assertFalse(finalFindings.any { it.ruleId == RootPositionTriadRules.MISSING_CHORD_TONE })
    }

    @Test
    fun treatsInvertedTriadAsNotApplicableRatherThanViolation() {
        val key = Key.major(PitchClass.C)
        val tonic = RootPositionTriadRules.triadInKey(key, 1)
        val transition = transition(
            soprano = "G4" to "C5",
            alto = "E4" to "G4",
            tenor = "C4" to "E4",
            bass = "C3" to "E3",
        )
        val connection = RootPositionTriadConnection(key, tonic, tonic)

        val applicability = RootPositionTriadRules.applicability(transition, connection)

        assertFalse(applicability.applies)
        assertEquals("inverted-triad", applicability.suggestedRuleSet)
        assertTrue(RootPositionTriadRules.checkTransition(transition, connection).isEmpty())
    }

    @Test
    fun ruleBodyAlwaysReportsNonChordToneAndLeadingToneDoubling() {
        val key = Key.major(PitchClass.C)
        val tonic = RootPositionTriadRules.triadInKey(key, 1)
        val dominant = RootPositionTriadRules.triadInKey(key, 5)
        val nonChordTone = singleVerticality(
            soprano = "D5",
            alto = "G4",
            tenor = "E4",
            bass = "C3",
        )
        val doubledLeadingTone = singleVerticality(
            soprano = "B4",
            alto = "B3",
            tenor = "D4",
            bass = "G2",
        )

        assertTrue(
            RootPositionTriadRules.checkVerticality(nonChordTone, tonic, key)
                .any { it.ruleId == RootPositionTriadRules.NON_CHORD_TONE }
        )
        assertTrue(
            RootPositionTriadRules.checkVerticality(doubledLeadingTone, dominant, key)
                .any { it.ruleId == RootPositionTriadRules.LEADING_TONE_DOUBLED }
        )
    }

    @Test
    fun marksSameChordRepetitionWithBassOctaveDisplacement() {
        val key = Key.major(PitchClass.C)
        val tonic = RootPositionTriadRules.triadInKey(key, 1)
        val transition = transition(
            soprano = "G4" to "C5",
            alto = "E4" to "G4",
            tenor = "C4" to "E4",
            bass = "C3" to "C2",
        )

        val findings = RootPositionTriadRules.checkTransition(
            transition,
            RootPositionTriadConnection(key, tonic, tonic),
        )

        assertTrue(findings.any { it.ruleId == RootPositionTriadRules.SAME_CHORD_REPETITION })
    }

    @Test
    fun marksFourthFifthCommonTonePattern() {
        val key = Key.major(PitchClass.C)
        val tonic = RootPositionTriadRules.triadInKey(key, 1)
        val dominant = RootPositionTriadRules.triadInKey(key, 5)
        val transition = transition(
            soprano = "G4" to "G4",
            alto = "E4" to "D4",
            tenor = "C4" to "B3",
            bass = "C3" to "G3",
        )

        val findings = RootPositionTriadRules.checkTransition(
            transition,
            RootPositionTriadConnection(key, tonic, dominant),
        )

        assertTrue(findings.any { it.ruleId == RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE })
    }

    @Test
    fun marksThirdSixthCommonTonePattern() {
        val key = Key.major(PitchClass.C)
        val tonic = RootPositionTriadRules.triadInKey(key, 1)
        val submediant = RootPositionTriadRules.triadInKey(key, 6)
        val transition = transition(
            soprano = "G4" to "A4",
            alto = "E4" to "E4",
            tenor = "C4" to "C4",
            bass = "C3" to "A2",
        )

        val findings = RootPositionTriadRules.checkTransition(
            transition,
            RootPositionTriadConnection(key, tonic, submediant),
        )

        assertTrue(findings.any { it.ruleId == RootPositionTriadRules.THIRD_SIXTH_COMMON_TONES })
    }

    @Test
    fun marksMajorDominantToSixthSmoothContraryMotionAndInnerLeadingToneDescent() {
        val key = Key.major(PitchClass.C)
        val dominant = RootPositionTriadRules.triadInKey(key, 5)
        val submediant = RootPositionTriadRules.triadInKey(key, 6)
        val transition = transition(
            soprano = "D5" to "C5",
            alto = "B4" to "A4",
            tenor = "G3" to "E3",
            bass = "G2" to "A2",
        )

        val findings = RootPositionTriadRules.checkTransition(
            transition,
            RootPositionTriadConnection(key, dominant, submediant),
        )

        assertTrue(findings.any { it.ruleId == RootPositionTriadRules.SECOND_SEVENTH_CONTRARY_SMOOTH })
        assertTrue(findings.any { it.ruleId == RootPositionTriadRules.MAJOR_DOMINANT_TO_SIXTH_INNER_LEADING_TONE })
    }

    @Test
    fun introductoryProfileSuppressesGenericLeadingToneWarningWhenInnerLeapIsAccepted() {
        val key = Key.major(PitchClass.C)
        val dominant = RootPositionTriadRules.triadInKey(key, 5)
        val tonic = RootPositionTriadRules.triadInKey(key, 1)
        val fixed = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("D5")), listOf(Pitch.fromName("C5"))),
            altoPitches = listOf(listOf(Pitch.fromName("B4")), listOf(Pitch.fromName("E4"))),
            tenorPitches = listOf(listOf(Pitch.fromName("G3")), listOf(Pitch.fromName("G3"))),
            bassPitches = listOf(listOf(Pitch.fromName("G2")), listOf(Pitch.fromName("C3"))),
        )
        val transition = VoiceLeadingAnalysis.transitions(fixed).single()
        val connectionFindings = RootPositionTriadRules.checkTransition(
            transition,
            RootPositionTriadConnection(key, dominant, tonic),
        )
        val melodyFindings = MelodyTextbookRules.checkFixedVoiceScore(fixed, key).map { it.toFinding() }

        val mediated = (connectionFindings + melodyFindings).applyProfile(RootPositionTriadRules.INTRODUCTORY_PROFILE)

        assertTrue(connectionFindings.any { it.ruleId == RootPositionTriadRules.INNER_LEADING_TONE_LEAP })
        assertTrue(melodyFindings.any { it.ruleId == MelodyTextbookRules.LEADING_TONE_RESOLUTION })
        assertTrue(mediated.any { it.ruleId == RootPositionTriadRules.INNER_LEADING_TONE_LEAP })
        assertFalse(mediated.any { it.ruleId == MelodyTextbookRules.LEADING_TONE_RESOLUTION })
    }

    @Test
    fun introductoryProfileSuppressesGenericLeadingToneWarningWhenMajorDominantToSixthExplainsIt() {
        val key = Key.major(PitchClass.C)
        val dominant = RootPositionTriadRules.triadInKey(key, 5)
        val submediant = RootPositionTriadRules.triadInKey(key, 6)
        val fixed = fixedVoiceScore(
            sopranoPitches = listOf(listOf(Pitch.fromName("D5")), listOf(Pitch.fromName("C5"))),
            altoPitches = listOf(listOf(Pitch.fromName("B4")), listOf(Pitch.fromName("A4"))),
            tenorPitches = listOf(listOf(Pitch.fromName("G3")), listOf(Pitch.fromName("E3"))),
            bassPitches = listOf(listOf(Pitch.fromName("G2")), listOf(Pitch.fromName("A2"))),
        )
        val transition = VoiceLeadingAnalysis.transitions(fixed).single()
        val connectionFindings = RootPositionTriadRules.checkTransition(
            transition,
            RootPositionTriadConnection(key, dominant, submediant),
        )
        val melodyFindings = MelodyTextbookRules.checkFixedVoiceScore(fixed, key).map { it.toFinding() }

        val mediated = (connectionFindings + melodyFindings).applyProfile(RootPositionTriadRules.INTRODUCTORY_PROFILE)

        assertTrue(
            connectionFindings.any {
                it.ruleId == RootPositionTriadRules.MAJOR_DOMINANT_TO_SIXTH_INNER_LEADING_TONE
            }
        )
        assertTrue(melodyFindings.any { it.ruleId == MelodyTextbookRules.LEADING_TONE_RESOLUTION })
        assertTrue(
            mediated.any {
                it.ruleId == RootPositionTriadRules.MAJOR_DOMINANT_TO_SIXTH_INNER_LEADING_TONE
            }
        )
        assertFalse(mediated.any { it.ruleId == MelodyTextbookRules.LEADING_TONE_RESOLUTION })
    }

    @Test
    fun introductoryProfileDowngradesGenericLeadingToneWarningWhenNoConnectionExceptionApplies() {
        val key = Key.major(PitchClass.C)
        val findings = MelodyTextbookRules.checkPitches(
            listOf(Pitch.fromName("B4"), Pitch.fromName("A4")),
            key,
        ).map { it.toFinding() }

        val mediated = findings.applyProfile(RootPositionTriadRules.INTRODUCTORY_PROFILE)
        val leadingTone = mediated.single { it.ruleId == MelodyTextbookRules.LEADING_TONE_RESOLUTION }

        assertEquals(RuleSeverity.HINT, leadingTone.severity)
    }

    @Test
    fun flagsMinorRaisedFifthMovingToFourthInDominantToSixth() {
        val key = Key.minor(PitchClass.A)
        val dominant = RootPositionTriadRules.triadInKey(key, 5, ChordQuality.MAJOR)
        val submediant = RootPositionTriadRules.triadInKey(key, 6, ChordQuality.MAJOR)
        val transition = transition(
            soprano = "B4" to "A4",
            alto = "G#4" to "F4",
            tenor = "E3" to "C3",
            bass = "E2" to "F2",
        )

        val findings = RootPositionTriadRules.checkTransition(
            transition,
            RootPositionTriadConnection(key, dominant, submediant),
        )

        assertTrue(findings.any { it.ruleId == RootPositionTriadRules.MINOR_RAISED_FIFTH_TO_FOURTH })
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
