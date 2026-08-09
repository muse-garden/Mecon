package com.mecon.theory

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixedVoiceWritingSolverTest {
    @Test
    fun explanatoryIndicationsDoNotReceiveSolverBonus() {
        val policy = FixedVoiceWritingScorePolicy()

        val defaultIndication = RuleFinding<EventId>(
            ruleId = TEST_VERTICAL_RULE,
            kind = RuleFindingKind.INDICATION,
            severity = RuleSeverity.HINT,
            message = "target pattern",
        )
        val explanatoryIndication = defaultIndication.copy(scoreIntent = RuleScoreIntent.EXPLANATORY)

        assertEquals(-12.0, policy.findingScore(defaultIndication))
        assertEquals(0.0, policy.findingScore(explanatoryIndication))
    }

    @Test
    fun finalResultsCollapseAbstractDiversityDuplicates() {
        val task = WritingTask(
            texture = WritingTexture.FOUR_PART_FIXED_VOICE,
            timeline = WritingTimeline(
                range = TimeRange(TimeCode.of(1, 0, 4), TimeCode.of(1, 1, 4)),
                slots = listOf(TimeCode.of(1, 0, 4)),
            ),
            searchConfig = SearchConfig(maxResults = 3, beamWidth = 8),
        )

        val results = BeamSearchSolver.solve(task, StringCandidateSpace())

        assertEquals(listOf("C3", "D4"), results.map { it.state })
    }

    @Test
    fun solvesFixedVoiceTaskWithPluggableRuleProvider() {
        val voices = standardFourPartWritingVoices()
        val task = WritingTask(
            texture = WritingTexture.FOUR_PART_FIXED_VOICE,
            timeline = WritingTimeline(
                range = TimeRange(TimeCode.of(1, 0, 4), TimeCode.of(1, 1, 4)),
                slots = listOf(TimeCode.of(1, 0, 4), TimeCode.of(1, 1, 4)),
            ),
            ruleProfile = RuleProfile("test.fixed-voice-writing"),
            searchConfig = SearchConfig(maxResults = 1, beamWidth = 4),
        )
        val space = FixedVoiceWritingCandidateSpace(
            targets = listOf("first", "second"),
            voices = voices,
            candidateFactory = FixedVoiceCandidateFactory { _, slotIndex, target, _ ->
                listOf(generatedFrame(slotIndex, target, voices))
            },
            ruleProviders = listOf(TestTransitionRuleProvider()),
        )

        val results = BeamSearchSolver.solve(task, space)

        val result = results.single()
        assertEquals(2, result.state.frames.size)
        assertTrue(result.breakdown.findings.any { it.ruleId == TEST_TRANSITION_RULE })
        assertTrue(result.breakdown.contributions.isNotEmpty())
    }

    @Test
    fun supportsDynamicTargetProviderAndCachesLocalFindings() {
        val voices = standardFourPartWritingVoices()
        val ruleProvider = CountingRuleProvider()
        val task = WritingTask(
            texture = WritingTexture.FOUR_PART_FIXED_VOICE,
            timeline = WritingTimeline(
                range = TimeRange(TimeCode.of(1, 0, 4), TimeCode.of(1, 1, 4)),
                slots = listOf(TimeCode.of(1, 0, 4), TimeCode.of(1, 1, 4)),
            ),
            ruleProfile = RuleProfile("test.fixed-voice-writing.dynamic-targets"),
            searchConfig = SearchConfig(maxResults = 1, beamWidth = 4),
        )
        val space = FixedVoiceWritingCandidateSpace(
            targetProvider = FixedVoiceTargetProvider { state, _ ->
                when (state.frames.size) {
                    0 -> listOf("first-a", "first-b")
                    1 -> listOf("second")
                    else -> emptyList()
                }
            },
            voices = voices,
            candidateFactory = FixedVoiceCandidateFactory { _, slotIndex, target, _ ->
                listOf(generatedFrame(slotIndex, target, voices))
            },
            ruleProviders = listOf(ruleProvider),
        )

        val result = BeamSearchSolver.solve(task, space).single()

        assertEquals(2, result.state.frames.size)
        assertEquals(3, ruleProvider.verticalChecks)
        assertEquals(1, ruleProvider.transitionChecks)
        assertTrue(result.breakdown.findings.any { it.ruleId == TEST_VERTICAL_RULE })
    }

    @Test
    fun greedySearchReportsCooperativeCancellation() {
        val voices = standardFourPartWritingVoices()
        val task = WritingTask(
            texture = WritingTexture.FOUR_PART_FIXED_VOICE,
            timeline = WritingTimeline(
                range = TimeRange(TimeCode.of(1, 0, 4), TimeCode.of(1, 2, 4)),
                slots = listOf(TimeCode.of(1, 0, 4), TimeCode.of(1, 1, 4)),
            ),
            searchConfig = SearchConfig(maxResults = 1, beamWidth = 4),
        )
        val space = FixedVoiceWritingCandidateSpace(
            targets = listOf("first", "second"),
            voices = voices,
            candidateFactory = FixedVoiceCandidateFactory { _, slotIndex, target, _ ->
                listOf(generatedFrame(slotIndex, target, voices))
            },
            ruleProviders = emptyList(),
        )
        var checks = 0

        val result = GreedyDepthFirstSolver.solveWithTrace(
            task,
            space,
            cancellation = SearchCancellation { ++checks > 1 },
        )

        assertTrue(result.results.isEmpty())
        assertTrue(result.trace.cancelled)
        assertTrue(result.trace.entries.any { it.kind == WritingSearchTraceEventKind.CANCELLED })
    }

    @Test
    fun prefixDiversityKeepsStrongDifferentLocalBranchesUntilTheirContinuationCanBeScored() {
        fun task(prefixDiversity: Boolean) = WritingTask(
            texture = WritingTexture.FOUR_PART_FIXED_VOICE,
            timeline = WritingTimeline(
                range = TimeRange(TimeCode.of(1, 0, 4), TimeCode.of(1, 2, 4)),
                slots = listOf(TimeCode.of(1, 0, 4), TimeCode.of(1, 1, 4)),
            ),
            searchConfig = SearchConfig(
                maxResults = 1,
                beamWidth = 4,
                prefixDiversity = PrefixDiversitySearchConfig(
                    enabled = prefixDiversity,
                    frontierWidth = 2,
                ),
            ),
        )
        val space = DelayedBranchScoreCandidateSpace()

        assertEquals("Ax", GreedyDepthFirstSolver.solve(task(false), space).single().state)
        assertEquals("Bx", GreedyDepthFirstSolver.solve(task(true), space).single().state)
        val combined = task(true).let { configured ->
            configured.copy(
                searchConfig = configured.searchConfig.copy(
                    diversity = DiversitySearchConfig(enabled = true, seed = 7L),
                )
            )
        }
        assertEquals("Bx", GreedyDepthFirstSolver.solve(combined, space).single().state)
    }

    private fun generatedFrame(
        slotIndex: Int,
        target: String,
        voices: List<FixedVoice>,
    ): FixedVoiceWritingFrame<String> {
        val pitches = if (slotIndex == 0) {
            mapOf(
                FixedVoiceRole.SOPRANO to Pitch.fromName("C5"),
                FixedVoiceRole.ALTO to Pitch.fromName("G4"),
                FixedVoiceRole.TENOR to Pitch.fromName("E3"),
                FixedVoiceRole.BASS to Pitch.fromName("C3"),
            )
        } else {
            mapOf(
                FixedVoiceRole.SOPRANO to Pitch.fromName("D5"),
                FixedVoiceRole.ALTO to Pitch.fromName("A4"),
                FixedVoiceRole.TENOR to Pitch.fromName("F3"),
                FixedVoiceRole.BASS to Pitch.fromName("D3"),
            )
        }
        return FixedVoiceWritingFrame(
            slotIndex = slotIndex,
            target = target,
            pitchesByVoiceId = voices.associate { voice ->
                voice.id to pitches.getValue(voice.role ?: error("Test voice must have a concrete role"))
            },
        )
    }
}

private class TestTransitionRuleProvider : FixedVoiceWritingRuleProvider<String> {
    override fun checkTransition(context: FixedVoiceTransitionRuleContext<String>): List<RuleFinding<com.mecon.api.primitive.EventId>> =
        listOf(
            RuleFinding(
                ruleId = TEST_TRANSITION_RULE,
                kind = RuleFindingKind.INDICATION,
                severity = RuleSeverity.HINT,
                message = "test transition rule",
                anchors = context.transition.current.notes.map { it.id },
            )
        )
}

private class CountingRuleProvider : FixedVoiceWritingRuleProvider<String> {
    var verticalChecks: Int = 0
        private set
    var transitionChecks: Int = 0
        private set

    override fun checkVertical(context: FixedVoiceVerticalRuleContext<String>): List<RuleFinding<com.mecon.api.primitive.EventId>> {
        verticalChecks++
        return listOf(
            RuleFinding(
                ruleId = TEST_VERTICAL_RULE,
                kind = RuleFindingKind.INDICATION,
                severity = RuleSeverity.HINT,
                message = "test vertical rule",
                anchors = context.verticality.notes.map { it.id },
            )
        )
    }

    override fun checkTransition(context: FixedVoiceTransitionRuleContext<String>): List<RuleFinding<com.mecon.api.primitive.EventId>> {
        transitionChecks++
        return emptyList()
    }
}

private class StringCandidateSpace : ScoredCandidateSpace<String, String> {
    override fun initial(task: WritingTask): String = ""

    override fun candidates(
        state: String,
        task: WritingTask,
    ): List<String> = listOf("C3", "C4", "D4")

    override fun apply(
        state: String,
        candidate: String,
    ): String = candidate

    override fun isComplete(
        state: String,
        task: WritingTask,
    ): Boolean = state.isNotEmpty()

    override fun score(
        state: String,
        task: WritingTask,
    ): ScoreBreakdown = ScoreBreakdown(total = 0.0)

    override fun diversityKey(state: String): String = state

    override fun diversityGroupKey(state: String): String = state.take(1)
}

private class DelayedBranchScoreCandidateSpace : ScoredCandidateSpace<String, Char> {
    override fun initial(task: WritingTask): String = ""

    override fun candidates(state: String, task: WritingTask): List<Char> = when (state.length) {
        0 -> listOf('A', 'B')
        1 -> listOf('x')
        else -> emptyList()
    }

    override fun apply(state: String, candidate: Char): String = state + candidate

    override fun isComplete(state: String, task: WritingTask): Boolean = state.length == 2

    override fun score(state: String, task: WritingTask): ScoreBreakdown = ScoreBreakdown(
        total = if (state.length == 2 && state.startsWith('A')) 10.0 else 0.0,
    )

    override fun diversityKey(state: String): String = state
}

private val TEST_TRANSITION_RULE = RuleId("test.fixed-voice-writing.transition")
private val TEST_VERTICAL_RULE = RuleId("test.fixed-voice-writing.vertical")
