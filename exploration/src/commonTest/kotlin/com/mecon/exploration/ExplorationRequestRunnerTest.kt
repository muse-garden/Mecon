package com.mecon.exploration

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.PitchClass
import com.mecon.api.primitive.TimeCode
import com.mecon.api.storage.tracks.StorageKeySignatureChange
import com.mecon.theory.ChordQuality
import com.mecon.theory.textbook.RootPositionTriadRules
import com.mecon.theory.textbook.FirstInversionTriadRules
import com.mecon.theory.textbook.SecondInversionTriadRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExplorationRequestRunnerTest {
    @Test
    fun solvesRuleExampleAndStoresRenderableScore() {
        val output = ExplorationRequestRunner.run(
            RuleExampleRequest(
                from = DegreeSpec(5),
                to = DegreeSpec(1),
                selectedRules = listOf(RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE.value),
                search = SearchSpec(maxResults = 2, beamWidth = 96),
            )
        )

        assertTrue(output.diagnostics.isEmpty())
        assertTrue(output.candidates.isNotEmpty())
        output.candidates.forEach { candidate ->
            assertTrue(candidate.score.voiceTracks.values.sumOf { it.events.size } >= 8)
            assertTrue(candidate.findings.any { it.ruleId == RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE.value })
        }
    }

    @Test
    fun reportsInvalidRuleSelection() {
        val output = ExplorationRequestRunner.run(
            RuleExampleRequest(
                from = DegreeSpec(1),
                to = DegreeSpec(2),
                selectedRules = listOf(RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE.value),
            )
        )

        assertTrue(output.candidates.isEmpty())
        assertFalse(output.diagnostics.isEmpty())
    }

    @Test
    fun solvesMinorRaisedFifthToFourthDemonstration() {
        val output = ExplorationRequestRunner.run(
            RuleExampleRequest(
                key = KeySpec(mode = KeyModeSpec.MINOR),
                from = DegreeSpec(5),
                to = DegreeSpec(6),
                demonstrate = DemonstrationSpec(RootPositionTriadRules.MINOR_RAISED_FIFTH_TO_FOURTH.value),
                search = SearchSpec(maxResults = 2, beamWidth = 128),
            )
        )

        assertTrue(output.diagnostics.isEmpty(), output.diagnostics.joinToString())
        assertTrue(output.candidates.isNotEmpty())
        assertTrue(
            output.candidates.any { candidate ->
                candidate.findings.any {
                    it.ruleId == RootPositionTriadRules.MINOR_RAISED_FIFTH_TO_FOURTH.value &&
                        it.isDemonstrationTarget
                }
            }
        )
    }

    @Test
    fun solvesMajorDominantToMinorSixthDemonstration() {
        val output = ExplorationRequestRunner.run(
            RuleExampleRequest(
                key = KeySpec(mode = KeyModeSpec.MAJOR),
                from = DegreeSpec(5),
                to = DegreeSpec(6),
                demonstrate = DemonstrationSpec(FirstInversionTriadRules.MAJOR_ROOT_DOMINANT_TO_MINOR_SIXTH.value),
                search = SearchSpec(maxResults = 2, beamWidth = 128),
            )
        )

        assertTrue(output.diagnostics.isEmpty(), output.diagnostics.joinToString())
        assertTrue(output.candidates.isNotEmpty())
        assertTrue(
            output.candidates.any { candidate ->
                candidate.findings.any {
                    it.ruleId == FirstInversionTriadRules.MAJOR_ROOT_DOMINANT_TO_MINOR_SIXTH.value &&
                        it.isDemonstrationTarget
                }
            }
        )
        val afterChordPitches = output.candidates.first().score.pitchTracks.values
            .flatMap { it.events }
            .filter { it.onset == TimeCode.of(1, Fraction(1, 4)) }
            .flatMap { it.pitches }
        val afterPitchClasses = afterChordPitches.map { it.pitchClass }.toSet()
        assertTrue(PitchClass.A in afterPitchClasses)
        assertTrue(PitchClass.C in afterPitchClasses)
        assertTrue(PitchClass.E in afterPitchClasses)
        assertFalse(PitchClass.F in afterPitchClasses)
    }

    @Test
    fun solvesProgressionRequest() {
        val output = ExplorationRequestRunner.run(
            ProgressionRequest(
                slots = listOf(1, 5, 6, 4).map { ProgressionSlot(DegreeSpec(it)) },
                search = SearchSpec(maxResults = 1, beamWidth = 96),
            )
        )

        assertTrue(output.candidates.isNotEmpty())
        assertEquals(16, output.candidates.single().score.voiceTracks.values.sumOf { it.events.size })
    }

    @Test
    fun solvesModulationExerciseAndWritesDestinationKeyChange() {
        val output = ExplorationRequestRunner.run(
            ModulationExerciseCellRequest(
                sourceKey = KeySpec(fifths = 0, mode = KeyModeSpec.MAJOR),
                targetKey = KeySpec(fifths = 1, mode = KeyModeSpec.MAJOR),
                pivotRoot = PitchClass.E.value,
                pivotQuality = ChordQuality.MINOR,
                sourceChordCount = 1,
                targetChordCount = 2,
                solverPreset = ModulationSolverSpec.FREE,
                search = SearchSpec(maxResults = 1, beamWidth = 128),
            )
        )

        assertTrue(output.diagnostics.isEmpty(), output.diagnostics.joinToString())
        val score = output.candidates.single().score
        assertEquals(16, score.voiceTracks.values.sumOf { it.events.size })
        assertFalse(score.showTimeSignatures)
        assertEquals(2, score.measures.size)
        assertEquals(2, score.defaultTimeSignature.numerator)
        assertEquals(2, score.measures[1].timeSignature?.numerator ?: score.defaultTimeSignature.numerator)
        val keyChange = score.globalTrack.events.filterIsInstance<StorageKeySignatureChange>().single()
        assertEquals(1, keyChange.keySignature.fifths)
        assertEquals(TimeCode.of(2, Fraction.ZERO), keyChange.onset)
        score.voiceTracks.values.forEach { track ->
            assertEquals(
                keyChange.onset,
                track.events.single { it.id.value.startsWith("solver-voice-2-") }.onset,
                track.id.value,
            )
        }
    }

    @Test
    fun solvesFivePlusFiveCMajorToGMajorThroughGMajorPivot() {
        val output = ExplorationRequestRunner.run(
            ModulationExerciseCellRequest(
                sourceKey = KeySpec(fifths = 0, mode = KeyModeSpec.MAJOR),
                targetKey = KeySpec(fifths = 1, mode = KeyModeSpec.MAJOR),
                pivotRoot = PitchClass.G.value,
                pivotQuality = ChordQuality.MAJOR,
                sourceChordCount = 5,
                targetChordCount = 5,
                solverPreset = ModulationSolverSpec.SCHOENBERG,
                search = SearchSpec(maxResults = 4, beamWidth = 192),
            )
        )

        assertTrue(output.diagnostics.isEmpty(), output.diagnostics.joinToString())
        assertTrue(output.candidates.isNotEmpty())
        val score = output.candidates.first().score
        assertEquals(44, score.voiceTracks.values.sumOf { it.events.size })
        assertFalse(score.showTimeSignatures)
        assertEquals(2, score.measures.size)
        assertEquals(6, score.defaultTimeSignature.numerator)
        assertEquals(5, score.measures[1].timeSignature?.numerator)
        val keyChange = score.globalTrack.events.filterIsInstance<StorageKeySignatureChange>().single()
        assertEquals(TimeCode.of(2, Fraction.ZERO), keyChange.onset)
        score.voiceTracks.values.forEach { track ->
            assertEquals(
                keyChange.onset,
                track.events.single { it.id.value.startsWith("solver-voice-6-") }.onset,
                track.id.value,
            )
        }
    }

    @Test
    fun solvesSecondInversionRuleExampleAsThreeChordContext() {
        val output = ExplorationRequestRunner.run(
            RuleExampleRequest(
                from = DegreeSpec(1),
                to = DegreeSpec(5),
                selectedRules = listOf(SecondInversionTriadRules.CADENTIAL_SIX_FOUR.value),
                search = SearchSpec(maxResults = 2, beamWidth = 128),
            )
        )

        assertTrue(output.diagnostics.isEmpty(), output.diagnostics.joinToString())
        assertTrue(output.candidates.isNotEmpty())
        assertTrue(
            output.candidates.any { candidate ->
                candidate.findings.any { it.ruleId == SecondInversionTriadRules.CADENTIAL_SIX_FOUR.value }
            }
        )
        assertEquals(12, output.candidates.first().score.voiceTracks.values.sumOf { it.events.size })
    }

    @Test
    fun secondInversionProgressionPolicyKeepsAtLeastThreeChords() {
        val output = ExplorationRequestRunner.run(
            ProgressionRequest(
                slots = listOf(1, 5).map { ProgressionSlot(DegreeSpec(it)) },
                policyId = "second-inversion-triads",
                search = SearchSpec(maxResults = 1, beamWidth = 128),
            )
        )

        assertTrue(output.candidates.isNotEmpty())
        assertEquals(12, output.candidates.single().score.voiceTracks.values.sumOf { it.events.size })
    }
}
