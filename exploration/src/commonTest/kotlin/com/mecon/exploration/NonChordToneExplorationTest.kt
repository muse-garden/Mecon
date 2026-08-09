package com.mecon.exploration

import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.primitive.Duration
import com.mecon.plugins.chord.StorageChordEvent
import com.mecon.theory.RuleCatalog
import com.mecon.theory.textbook.NonChordToneRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class NonChordToneExplorationTest {
    @Test
    fun passingToneRuleProducesAnnotatedScoreAndFinding() {
        val output = ExplorationRequestRunner.run(
            RuleExampleRequest(
                from = DegreeSpec(1),
                to = DegreeSpec(1),
                selectedRules = listOf(NonChordToneRules.PASSING.value),
            )
        )

        val candidate = output.candidates.single()
        assertEquals(NonChordToneRules.PASSING.value, candidate.findings.single().ruleId)
        assertTrue(candidate.findings.single().anchors.isNotEmpty())
        assertTrue(candidate.score.pluginTracks.values.any { it.type == StorageChordEvent.TRACK_TYPE })
        assertEquals(2, candidate.score.staffTracks.size)
        assertEquals(4, candidate.score.voiceTracks.size)
        assertEquals(2, candidate.score.staffGroups.single().members.size)
        assertEquals(4, RuntimeScore.fromStorage(candidate.score).voiceTracks.size)
    }

    @Test
    fun everyTextbookTypeHasAnExplorationExample() {
        NonChordToneRules.selectable.forEach { ruleId ->
            val output = ExplorationRequestRunner.run(
                RuleExampleRequest(
                    from = DegreeSpec(1),
                    to = DegreeSpec(1),
                    selectedRules = listOf(ruleId.value),
                )
            )
            assertTrue(output.candidates.isNotEmpty(), "missing example for $ruleId")
        }
    }

    @Test
    fun suspensionCategoriesAndChainProduceDistinctRenderableExamples() {
        val rules = listOf(
            NonChordToneRules.SUSPENSION_4_3,
            NonChordToneRules.SUSPENSION_7_6,
            NonChordToneRules.SUSPENSION_9_8,
            NonChordToneRules.RETARDATION,
            NonChordToneRules.SUSPENSION_CHAIN,
        )
        rules.forEach { ruleId ->
            val output = SolverEngine.solve(
                SolveRequest(
                    RuleExampleRequest(
                        from = DegreeSpec(1),
                        to = DegreeSpec(1),
                        selectedRules = listOf(ruleId.value),
                    )
                )
            ).output
            val candidate = output.candidates.single()
            assertEquals(ruleId.value, candidate.findings.single().ruleId)
            assertEquals(4, RuntimeScore.fromStorage(candidate.score).voiceTracks.size)
        }
        val chain = ExplorationRequestRunner.run(
            RuleExampleRequest(
                from = DegreeSpec(1),
                to = DegreeSpec(1),
                selectedRules = listOf(NonChordToneRules.SUSPENSION_CHAIN.value),
            )
        ).candidates.single()
        assertEquals(2, chain.findings.single().anchors.size)
        val soprano = chain.score.voiceTracks.values.single { it.name == "Soprano figuration" }
        assertEquals(
            listOf(
                Duration.WHOLE,
                Duration.QUARTER,
                Duration.DOTTED_HALF,
                Duration.QUARTER,
                Duration.DOTTED_HALF,
            ),
            soprano.events.map { it.duration },
        )
        assertEquals(listOf(1, 0, 1, 0, 0), soprano.events.map { it.ties.size })
        assertTrue(!RuleCatalog.exampleInputSpec(NonChordToneRules.SUSPENSION_CHAIN).usesDegreeContext)
    }

    @Test
    fun twoThreeSuspensionIsRenderedInBassAndResolvesDown() {
        val candidate = ExplorationRequestRunner.run(
            RuleExampleRequest(
                from = DegreeSpec(1),
                to = DegreeSpec(1),
                selectedRules = listOf(NonChordToneRules.RETARDATION.value),
            )
        ).candidates.single()
        val bass = candidate.score.voiceTracks.values.single { it.name == "Bass figuration" }
        val pitchTrack = candidate.score.pitchTracks.getValue(bass.pitchTrackId)
        val pitches = bass.events.map { event ->
            pitchTrack.events.single { it.id == event.pitchEventId }.pitches.single()
        }

        assertEquals(listOf(Duration.WHOLE, Duration.QUARTER, Duration.DOTTED_HALF), bass.events.map { it.duration })
        assertEquals(-1, pitches[2].diatonicSteps - pitches[1].diatonicSteps)
        assertTrue(candidate.findings.single().anchors.all { it.value.startsWith("nct-bass-voice-") })
        assertFalse(candidate.score.voiceTracks.values.any { it.name == "Soprano figuration" })
    }
}
