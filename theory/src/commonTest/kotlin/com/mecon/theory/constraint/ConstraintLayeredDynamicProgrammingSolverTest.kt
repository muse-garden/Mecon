package com.mecon.theory.constraint

import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.PitchClass
import com.mecon.api.primitive.TrackId
import com.mecon.theory.DynamicProgrammingSearchConfig
import com.mecon.theory.DynamicProgrammingSearchMode
import com.mecon.theory.DiversitySearchConfig
import com.mecon.theory.PrefixDiversitySearchConfig
import com.mecon.theory.ChordArity
import com.mecon.theory.Key
import com.mecon.theory.SearchBackend
import com.mecon.theory.SearchConfig
import com.mecon.theory.RuleConfig
import com.mecon.theory.RuleId
import com.mecon.theory.RuleSeverity
import com.mecon.theory.RuleSuppression
import com.mecon.theory.SpelledPitchClass
import com.mecon.theory.TonalContext
import com.mecon.theory.TonalPlan
import com.mecon.theory.TonalSpan
import com.mecon.theory.VoiceBoundary
import com.mecon.theory.VoicePlan
import com.mecon.theory.VoiceRange
import com.mecon.theory.VoiceSpec
import com.mecon.theory.FixedVoiceWritingCandidate
import com.mecon.theory.SlotWindow
import com.mecon.theory.WritingSearchTraceEventKind
import com.mecon.theory.toFixedVoices
import com.mecon.theory.defaultConstraintSlots
import com.mecon.api.primitive.NoteName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConstraintLayeredDynamicProgrammingSolverTest {
    private val key = Key.major(PitchClass.C)
    private val tonalContext = TonalContext.fromKey(key, SpelledPitchClass(NoteName.C))
    private val tonalPlan = TonalPlan(listOf(TonalSpan(SlotWindow(0, null), tonalContext)))
    private val tonic = DiatonicChordVocabulary.forContext(
        context = tonalContext,
        compatibilityKey = key,
        includeSevenths = false,
        includeInversions = false,
    ).single { it.degree == 1 }
    private val seventhTonic = DiatonicChordVocabulary.forContext(
        context = tonalContext,
        compatibilityKey = key,
        includeSevenths = true,
        includeInversions = false,
    ).single { it.degree == 1 && it.arity == ChordArity.SEVENTH }
    private val pitches = listOf("C5", "G4", "E4", "C3").map(Pitch::fromName)
    private val voicePlan = exactVoicePlan(pitches)

    @Test
    fun exactLayeredDpMatchesDfsIncludingGlobalPreferenceFindings() {
        val base = freeProgram(slotCount = 4)
            .copy(writingRulePreset = WritingRulePreset.NONE)
            .withoutTerminalGlobalRules()
        val dfs = assertIs<ConstraintSolveOutcome.Solved>(
            ConstraintProgramSolver.solvePolyphonicOutcome(
                base.copy(searchConfig = base.searchConfig.copy(backend = SearchBackend.GREEDY_DFS)),
            )
        ).solutions.single()
        val dp = assertIs<ConstraintSolveOutcome.Solved>(
            ConstraintProgramSolver.solvePolyphonicOutcome(
                base.copy(
                    searchConfig = base.searchConfig.copy(
                        backend = SearchBackend.LAYERED_DP,
                        dynamicProgramming = DynamicProgrammingSearchConfig(
                            mode = DynamicProgrammingSearchMode.EXACT,
                        ),
                    ),
                ),
            )
        ).solutions.single()

        assertEquals(dfs.voicings, dp.voicings)
        assertEquals(dfs.breakdown, dp.breakdown)
    }

    @Test
    fun autoKeepsDfsUntilPerformanceThresholdsAreRevalidated() {
        val outcome = assertIs<ConstraintSolveOutcome.Solved>(
            ConstraintProgramSolver.solvePolyphonicOutcome(freeProgram(slotCount = 1)),
        )

        assertEquals(SearchBackend.GREEDY_DFS, outcome.trace.backend)
        assertNotNull(outcome.trace.fallbackReason)
    }

    @Test
    fun spacingPrePruneHonorsSeverityOverride() {
        val wideSpacing = listOf("C5", "G3", "E3", "C3").map(Pitch::fromName)
        val hard = freeProgram(slotCount = 1, requestedVoicePlan = exactVoicePlan(wideSpacing))
            .withoutTerminalGlobalRules()
            .copy(searchConfig = SearchConfig(backend = SearchBackend.LAYERED_DP, maxResults = 1))
        val rejected = assertIs<ConstraintSolveOutcome.NoSolution>(
            ConstraintProgramSolver.solvePolyphonicOutcome(hard),
        )
        assertEquals(0, rejected.trace.evaluatedTransitions)

        val soft = hard.copy(
            ruleProfile = hard.ruleProfile.copy(
                overrides = hard.ruleProfile.overrides +
                    (WindowFeasibilityRuleProvider.ADJACENT_SPACING to
                        RuleConfig(severityOverride = RuleSeverity.SOFT)),
            ),
        )
        fun solve(backend: SearchBackend) = assertIs<ConstraintSolveOutcome.Solved>(
            ConstraintProgramSolver.solvePolyphonicOutcome(
                soft.copy(searchConfig = soft.searchConfig.copy(backend = backend)),
            ),
        )
        val dfs = solve(SearchBackend.GREEDY_DFS)
        val dp = solve(SearchBackend.LAYERED_DP)
        assertEquals(dfs.solutions.single().breakdown, dp.solutions.single().breakdown)
        assertTrue(dp.solutions.single().breakdown.findings.any { finding ->
            finding.ruleId == WindowFeasibilityRuleProvider.ADJACENT_SPACING &&
                finding.severity == RuleSeverity.SOFT
        })
        assertEquals(1, dp.trace.evaluatedTransitions)
    }

    @Test
    fun exactRejectsTerminalOnlyGlobalRulesInsteadOfPretendingToBeExact() {
        val base = freeProgram(slotCount = 4).copy(writingRulePreset = WritingRulePreset.NONE)
        val outcome = ConstraintProgramSolver.solvePolyphonicOutcome(
            base.copy(
                searchConfig = base.searchConfig.copy(
                    backend = SearchBackend.LAYERED_DP,
                    dynamicProgramming = DynamicProgrammingSearchConfig(
                        mode = DynamicProgrammingSearchMode.EXACT,
                    ),
                ),
            )
        )

        val invalid = assertIs<ConstraintSolveOutcome.Invalid>(outcome)
        assertEquals(
            ConstraintSolveDiagnosticCode.UNSUPPORTED_SEARCH_BACKEND,
            invalid.diagnostics.single().code,
        )
    }

    @Test
    fun autoFallsBackForUnregisteredPresetAndExplicitDpRejectsIt() {
        val textbook = freeProgram(slotCount = 1).copy(writingRulePreset = WritingRulePreset.TEXTBOOK)
        val automatic = assertIs<ConstraintSolveOutcome.Solved>(
            ConstraintProgramSolver.solvePolyphonicOutcome(textbook),
        )
        assertEquals(SearchBackend.GREEDY_DFS, automatic.trace.backend)
        assertNotNull(automatic.trace.fallbackReason)

        val explicit = ConstraintProgramSolver.solvePolyphonicOutcome(
            textbook.copy(searchConfig = textbook.searchConfig.copy(backend = SearchBackend.LAYERED_DP)),
        )
        assertIs<ConstraintSolveOutcome.Invalid>(explicit)
    }

    @Test
    fun explicitDpAcceptsFixedNaturalTriadsAndReportsCompiledStatePlan() {
        val base = freeProgram(slotCount = 2)
        val outcome = ConstraintProgramSolver.solvePolyphonicOutcome(
            base.copy(searchConfig = base.searchConfig.copy(backend = SearchBackend.LAYERED_DP)),
        )

        val solved = assertIs<ConstraintSolveOutcome.Solved>(outcome)
        assertEquals(SearchBackend.LAYERED_DP, solved.trace.backend)
        assertEquals(2, solved.trace.dpStatePlan.size)
        assertTrue(solved.trace.dpStatePlan.first().contains("recentFrames=1"))
        assertTrue(solved.trace.dpStatePlan.last().contains("recentFrames=0"))
        assertTrue("free.melody.consecutive-leaps" in solved.trace.dpCoveredRules)
        assertTrue("free.melody.no-repeated-pattern" in solved.trace.dpTerminalRerankRules)
    }

    @Test
    fun disablingConsecutiveLeapRuleAutomaticallyShrinksEveryMiddleLayer() {
        val base = freeProgram(slotCount = 4)
        val defaultPlan = requireNotNull(LayeredDpCapability.analyze(base).statePlan)
        assertEquals(listOf(1, 2, 2, 0), defaultPlan.layers.map { it.recentFrameCount })

        val disabled = base.copy(
            ruleProfile = base.ruleProfile.copy(
                overrides = base.ruleProfile.overrides +
                    (FreeHarmonyRuleProvider.CONSECUTIVE_LEAPS to RuleConfig(enabled = false)),
            ),
        )
        val reducedPlan = requireNotNull(LayeredDpCapability.analyze(disabled).statePlan)
        assertEquals(listOf(1, 1, 1, 0), reducedPlan.layers.map { it.recentFrameCount })
        assertTrue(FreeHarmonyRuleProvider.CONSECUTIVE_LEAPS !in reducedPlan.coveredRuleIds)
    }

    @Test
    fun implicitConstraintRuleIdUsesTheSameProfileSwitchAsFindingEmission() {
        val implicitRuleId = RuleId("solver.constraint.unique-voice-extreme")
        val implicit = Constraint(
            expr = ConstraintExpr.Atom(
                ConstraintPredicate.UniqueVoiceExtreme(
                    voiceFilter = ChordToneVoiceFilter.INNER,
                    extreme = VoiceExtreme.HIGHEST,
                )
            ),
        )
        val compiled = freeProgram(slotCount = 3)
        val base = compiled.copy(
            constraints = compiled.constraints + implicit,
        )
        val enabled = requireNotNull(LayeredDpCapability.analyze(base).statePlan)
        assertTrue(enabled.layers.first().bindings.any { it.ruleId == implicitRuleId })

        val disabled = base.copy(
            ruleProfile = base.ruleProfile.copy(
                overrides = base.ruleProfile.overrides +
                    (implicitRuleId to RuleConfig(enabled = false)),
            ),
        )
        val reduced = requireNotNull(LayeredDpCapability.analyze(disabled).statePlan)
        assertTrue(reduced.layers.none { layer -> layer.bindings.any { it.ruleId == implicitRuleId } })
    }

    @Test
    fun profileSuppressionsFailClosedInsteadOfMergingRetractableCosts() {
        val base = freeProgram(slotCount = 2)
        val program = base.copy(
            ruleProfile = base.ruleProfile.copy(
                suppressions = listOf(
                    RuleSuppression(
                        dominantRuleId = RuleId("free.voice-leading.parallel-fifth"),
                        suppressedRuleId = FreeHarmonyRuleProvider.CONSECUTIVE_LEAPS,
                    )
                ),
            ),
        )

        val capability = LayeredDpCapability.analyze(program)
        assertTrue(!capability.supported)
        assertTrue(capability.reason.orEmpty().contains("suppressions"))
    }

    @Test
    fun targetHistoryRulesCompileAutomataAndUnknownVoiceSummaryRejectsDp() {
        val base = freeProgram(slotCount = 3)
        val scoringRuleId = RuleId("test.similar-chord-distance.scoring")
        val scoring = base.copy(
            constraints = base.constraints + Constraint(
                expr = ConstraintExpr.Atom(ConstraintPredicate.MinimumSimilarChordDistance(3)),
                modality = ConstraintModality.Prefer(28.0),
                ruleId = scoringRuleId,
            ),
        )
        val plan = requireNotNull(LayeredDpCapability.analyze(scoring).statePlan)
        assertTrue(plan.coveredRuleIds.contains(scoringRuleId))
        assertTrue(plan.layers.dropLast(1).all { layer ->
            layer.bindings.any { it.ruleId == scoringRuleId }
        })

        val unsupported = base.copy(
            constraints = base.constraints + Constraint(
                expr = ConstraintExpr.Atom(
                    ConstraintPredicate.VoicePitchClassCardinality(
                        voiceFilter = ChordToneVoiceFilter.OUTER,
                        slots = listOf(0, 1, 2),
                        allowedCounts = setOf(2),
                    )
                ),
            ),
        )
        val capability = LayeredDpCapability.analyze(unsupported)
        assertTrue(!capability.supported)
        assertTrue(capability.reason.orEmpty().contains("VoicePitchClassCardinality"))
    }

    /**
     * 和弦选择规则在自由写作里是 [ConstraintModality.Remind]：仍被 capability 审计覆盖，但既不计分
     * 也不能否决，因此不得占用任何合并状态——否则整条前缀历史会被塞进 DP key，状态永不合并。
     */
    @Test
    fun chordSelectionRemindersAreCoveredWithoutClaimingMergeState() {
        val base = freeProgram(slotCount = 3)
        val reminders = base.constraints
            .filter { it.modality == ConstraintModality.Remind }
            .mapNotNull { it.ruleId }
        assertTrue(reminders.isNotEmpty(), "自由写作应至少有一条和弦选择提醒")

        val plan = requireNotNull(LayeredDpCapability.analyze(base).statePlan)
        reminders.forEach { ruleId ->
            assertTrue(plan.coveredRuleIds.contains(ruleId), "${ruleId.value} 应通过能力审计")
            assertTrue(
                plan.layers.none { layer -> layer.bindings.any { it.ruleId == ruleId } },
                "${ruleId.value} 是提醒，不应占用 DP 合并状态",
            )
        }
    }

    /** 提醒发 finding、但不进入总分，也不会把路径判成硬违规。 */
    @Test
    fun chordSelectionRemindersDoNotChangeScores() {
        val base = freeProgram(slotCount = 3)
        val withoutReminders = base.copy(
            constraints = base.constraints.filterNot { it.modality == ConstraintModality.Remind },
        )
        val withReminders = ConstraintProgramSolver.solvePolyphonicOutcome(base, maxTraceEntries = 0)
        val without = ConstraintProgramSolver.solvePolyphonicOutcome(withoutReminders, maxTraceEntries = 0)
        val left = assertIs<ConstraintSolveOutcome.Solved>(withReminders)
        val right = assertIs<ConstraintSolveOutcome.Solved>(without)
        assertEquals(
            right.solutions.first().breakdown.total,
            left.solutions.first().breakdown.total,
            "提醒不得改变总分",
        )
    }

    @Test
    fun statePlannerAcceptsOpenDomainsAndTargetSensitiveSevenths() {
        val base = freeProgram(slotCount = 2)
        assertEquals(WritingRulePreset.FREE_CLASSICAL, base.writingRulePreset)
        val openDomains = listOf(
            SlotDomain(listOf(tonic, seventhTonic)),
            SlotDomain(listOf(tonic)),
        )
        val open = base.copy(
            slotDomains = openDomains,
            slots = defaultConstraintSlots(openDomains),
        )
        assertTrue(LayeredDpCapability.analyze(open).supported, LayeredDpCapability.analyze(open).reason)

        val seventhDomains = List(2) { SlotDomain(listOf(seventhTonic)) }
        val seventh = base.copy(
            slotDomains = seventhDomains,
            slots = defaultConstraintSlots(seventhDomains),
        )
        assertTrue(LayeredDpCapability.analyze(seventh).supported, LayeredDpCapability.analyze(seventh).reason)

        // 同一个七和弦程序换到勋伯格一般写作规则下也继续被接受。
        val schoenberg = seventh.copy(writingRulePreset = WritingRulePreset.SCHOENBERG_GENERAL)
        assertTrue(
            LayeredDpCapability.analyze(schoenberg).supported,
            "诊断：${LayeredDpCapability.analyze(schoenberg).reason}",
        )
    }

    @Test
    fun exactOpenTargetKeepsEqualPitchesWithDifferentInterpretationsDistinct() {
        val aliasA = AliasChordTarget(tonic, "alias-a")
        val aliasZ = AliasChordTarget(tonic, "alias-z")
        val domains = listOf(
            SlotDomain(listOf(aliasA, aliasZ)),
            SlotDomain(listOf(tonic)),
        )
        val rewardRule = RuleId("test.dp.target-identity-reward")
        val program = freeProgram(slotCount = 2)
            .copy(
                slotDomains = domains,
                slots = defaultConstraintSlots(domains),
                constraints = listOf(
                    Constraint(
                        expr = ConstraintExpr.Atom(
                            ConstraintPredicate.TargetMatches(
                                TargetFeatureBonusRequirement(
                                    window = SlotWindow(0, 0),
                                    selector = TargetSelector(identityKeys = setOf("alias-z")),
                                    ruleId = rewardRule,
                                    message = "保留语义上更优的同音解释。",
                                    bonus = 100.0,
                                )
                            )
                        ),
                        modality = ConstraintModality.Reward(100.0),
                        ruleId = rewardRule,
                    )
                ),
                writingRulePreset = WritingRulePreset.NONE,
                searchConfig = SearchConfig(
                    maxResults = 1,
                    beamWidth = 32,
                    backend = SearchBackend.LAYERED_DP,
                    dynamicProgramming = DynamicProgrammingSearchConfig(
                        mode = DynamicProgrammingSearchMode.EXACT,
                        maxLabelsPerState = 1,
                    ),
                ),
            )

        val solved = assertIs<ConstraintSolveOutcome.Solved>(
            ConstraintProgramSolver.solvePolyphonicOutcome(program),
        )
        assertEquals("alias-z", solved.solutions.single().voicings.first().target.identityKey())
        assertTrue(solved.solutions.single().breakdown.findings.any { it.ruleId == rewardRule })
    }

    @Test
    fun boundedDpHonorsFreePracticePairwiseDiversityGates() {
        val program = freeProgram(
            slotCount = 4,
            requestedVoicePlan = VoicePlan.standardFourPart(),
        ).withoutTerminalGlobalRules().copy(
            searchConfig = SearchConfig(
                maxResults = 4,
                beamWidth = 32,
                backend = SearchBackend.LAYERED_DP,
                prefixDiversity = PrefixDiversitySearchConfig(enabled = true, frontierWidth = 32),
                diversity = DiversitySearchConfig(
                    enabled = true,
                    seed = 17L,
                    minChangedSlotRatio = 0.35,
                    minChangedVoiceCellRatio = 0.20,
                ),
                dynamicProgramming = DynamicProgrammingSearchConfig(
                    maxCandidatesPerTarget = 128,
                    maxLabelsPerState = 16,
                    maxFrontierStates = 512,
                ),
            ),
        )

        val solved = assertIs<ConstraintSolveOutcome.Solved>(
            ConstraintProgramSolver.solvePolyphonicOutcome(program),
        )
        assertEquals(4, solved.solutions.size)
        val voiceIds = program.resolvedVoicePlan.voices.map { it.id }
        solved.solutions.indices.forEach { leftIndex ->
            (leftIndex + 1 until solved.solutions.size).forEach { rightIndex ->
                val left = solved.solutions[leftIndex].voicings
                val right = solved.solutions[rightIndex].voicings
                val changedSlots = left.indices.count { slot ->
                    voiceIds.any { voiceId ->
                        left[slot].pitchesByVoiceId.getValue(voiceId).pitchClass !=
                            right[slot].pitchesByVoiceId.getValue(voiceId).pitchClass
                    }
                }
                val changedCells = left.indices.sumOf { slot ->
                    voiceIds.count { voiceId ->
                        left[slot].pitchesByVoiceId.getValue(voiceId).pitchClass !=
                            right[slot].pitchesByVoiceId.getValue(voiceId).pitchClass
                    }
                }
                assertTrue(changedSlots.toDouble() / left.size >= 0.35)
                assertTrue(changedCells.toDouble() / (left.size * voiceIds.size) >= 0.20)
            }
        }
    }

    /**
     * 前沿裁剪必须先把 `Map.Entry` 拷成快照，再 `clear()` 重填分组表。Kotlin/JS 的 entry 是回指
     * 哈希表的活引用，clear 之后读它的 key/value 会抛
     * "The backing map has been modified after this entry was obtained."；JVM 的 LinkedHashMap
     * 节点在 clear 后仍带着 key/value，所以该缺陷只在 Web 端暴露，需由 jsNodeTest 守护。
     */
    @Test
    fun boundedDpPrunesTheFrontierWithoutReadingStaleMapEntries() {
        fun prunedProgram(preserveDiverseLabels: Boolean): ConstraintProgram = freeProgram(
            slotCount = 3,
            requestedVoicePlan = VoicePlan.standardFourPart(),
        ).withoutTerminalGlobalRules().copy(
            searchConfig = SearchConfig(
                // 前沿上限 = min(maxFrontierStates, max(beamWidth, 有效搜索宽度) × maxResults)。
                // 出边宽度受 beamWidth 约束，因此改用 maxFrontierStates 压低上限，保证每层都触发裁剪。
                maxResults = 1,
                beamWidth = 32,
                backend = SearchBackend.LAYERED_DP,
                prefixDiversity = PrefixDiversitySearchConfig(
                    enabled = preserveDiverseLabels,
                    frontierWidth = 32,
                ),
                dynamicProgramming = DynamicProgrammingSearchConfig(
                    maxLabelsPerState = 4,
                    maxFrontierStates = 2,
                ),
            ),
        )

        listOf(false, true).forEach { preserveDiverseLabels ->
            val solved = assertIs<ConstraintSolveOutcome.Solved>(
                ConstraintProgramSolver.solvePolyphonicOutcome(prunedProgram(preserveDiverseLabels)),
            )
            assertTrue(
                solved.trace.frontierTruncated,
                "preserveDiverseLabels=$preserveDiverseLabels 未触发前沿裁剪，回归失去意义",
            )
            assertEquals(3, solved.solutions.single().voicings.size)
        }
    }

    @Test
    fun excludedBestGroupDoesNotConsumeTheOnlyTerminalResultSlot() {
        val program = freeProgram(
            slotCount = 2,
            requestedVoicePlan = VoicePlan.standardFourPart(),
        ).withoutTerminalGlobalRules().copy(
            searchConfig = SearchConfig(
                maxResults = 1,
                beamWidth = 24,
                backend = SearchBackend.LAYERED_DP,
                dynamicProgramming = DynamicProgrammingSearchConfig(maxLabelsPerState = 4),
            ),
        )
        val first = assertIs<ConstraintSolveOutcome.Solved>(
            ConstraintProgramSolver.solvePolyphonicOutcome(program),
        ).solutions.single()
        val second = assertIs<ConstraintSolveOutcome.Solved>(
            ConstraintProgramSolver.solvePolyphonicOutcome(
                program,
                context = ConstraintSolveContext(
                    excludedDiversityGroupKeys = setOf(first.diversityGroupKey),
                ),
            ),
        ).solutions.single()

        assertTrue(first.diversityGroupKey != second.diversityGroupKey)
    }

    @Test
    fun exactDpMatchesExhaustiveNaturalTriadSatbDomain() {
        val vocabulary = DiatonicChordVocabulary.forContext(
            context = tonalContext,
            compatibilityKey = key,
            includeSevenths = false,
            includeInversions = false,
        )
        val byDegree = vocabulary.associateBy { it.degree }
        val compiled = FreeHarmonySolver.compile(
            FreeHarmonyRequest(
                key = key,
                tonalPlan = tonalPlan,
                slotCount = 2,
                vocabulary = vocabulary,
                voicePlan = VoicePlan.standardFourPart(),
                fixedTargetIdentityBySlot = mapOf(
                    0 to requireNotNull(byDegree[1]).identityKey(),
                    1 to requireNotNull(byDegree[5]).identityKey(),
                ),
                searchConfig = SearchConfig(maxResults = 1),
            )
        ).withoutTerminalGlobalRules()
        val program = compiled.copy(
            searchConfig = compiled.searchConfig.copy(
                backend = SearchBackend.LAYERED_DP,
                dynamicProgramming = DynamicProgrammingSearchConfig(
                    mode = DynamicProgrammingSearchMode.EXACT,
                    maxCandidatesPerTarget = 256,
                    maxFrontierStates = 20_000,
                    maxTransitionEvaluations = 100_000,
                ),
            ),
        )
        val search = ConstraintProgramSolver.buildSearch(program)
        val initial = search.space.initial(search.task)
        val firstFrames = search.candidateFactory.layerCandidates(
            0,
            program.slotDomains[0].targets.single(),
            maxCandidates = null,
        ).frames
        val secondFrames = search.candidateFactory.layerCandidates(
            1,
            program.slotDomains[1].targets.single(),
            maxCandidates = null,
        ).frames
        val exhaustive = buildList {
            firstFrames.forEach { first ->
                val firstState = search.space.apply(initial, FixedVoiceWritingCandidate(first))
                if (search.space.score(firstState, search.task).hasHardViolation) return@forEach
                secondFrames.forEach { second ->
                    if (!relationConstraintsAllowFrame(program, search.voices, firstState, second)) return@forEach
                    val complete = search.space.apply(firstState, FixedVoiceWritingCandidate(second))
                    val breakdown = search.space.score(complete, search.task)
                    if (!breakdown.hasHardViolation) add(complete to breakdown)
                }
            }
        }.minWith(
            compareBy<Pair<com.mecon.theory.FixedVoiceWritingState<ChordTarget>, com.mecon.theory.ScoreBreakdown>> {
                it.second.total
            }.thenBy { search.space.diversityKey(it.first) }
        )

        val solved = assertIs<ConstraintSolveOutcome.Solved>(
            ConstraintProgramSolver.solvePolyphonicOutcome(program),
        ).solutions.single()
        assertEquals(exhaustive.second.total, solved.breakdown.total)
        assertEquals(
            exhaustive.second.findings.map { it.ruleId to it.scoreDelta },
            solved.breakdown.findings.map { it.ruleId to it.scoreDelta },
        )
    }

    @Test
    fun exactDpMatchesExhaustiveThreeSlotDomainWithTwoFrameRuleState() {
        val narrowSatb = VoicePlan(
            listOf(
                voice("S", 0, VoiceBoundary.UPPER_OUTER, "C5", "G5"),
                voice("A", 1, VoiceBoundary.INNER, "G4", "E5"),
                voice("T", 2, VoiceBoundary.INNER, "C4", "G4"),
                voice("B", 3, VoiceBoundary.LOWER_OUTER, "C3", "G3"),
            )
        )
        val compiled = FreeHarmonySolver.compile(
            FreeHarmonyRequest(
                key = key,
                tonalPlan = tonalPlan,
                slotCount = 3,
                vocabulary = listOf(tonic),
                voicePlan = narrowSatb,
                fixedTargetIdentityBySlot = (0..2).associateWith { tonic.identityKey() },
                searchConfig = SearchConfig(maxResults = 1),
            )
        ).withoutTerminalGlobalRules()
        val program = compiled.copy(
            searchConfig = compiled.searchConfig.copy(
                backend = SearchBackend.LAYERED_DP,
                dynamicProgramming = DynamicProgrammingSearchConfig(
                    mode = DynamicProgrammingSearchMode.EXACT,
                    maxCandidatesPerTarget = 256,
                    maxFrontierStates = 100_000,
                    maxTransitionEvaluations = 500_000,
                ),
            ),
        )
        val plan = requireNotNull(LayeredDpCapability.analyze(program).statePlan)
        assertEquals(listOf(1, 2, 0), plan.layers.map { it.recentFrameCount })

        val search = ConstraintProgramSolver.buildSearch(program)
        val layers = program.slotDomains.indices.map { slotIndex ->
            search.candidateFactory.layerCandidates(
                slotIndex,
                program.slotDomains[slotIndex].targets.single(),
                maxCandidates = null,
            ).frames
        }
        val complete = mutableListOf<
            Pair<com.mecon.theory.FixedVoiceWritingState<ChordTarget>, com.mecon.theory.ScoreBreakdown>
            >()
        fun enumerate(state: com.mecon.theory.FixedVoiceWritingState<ChordTarget>) {
            if (state.frames.size == program.length) {
                val breakdown = search.space.score(state, search.task)
                if (!breakdown.hasHardViolation) complete += state to breakdown
                return
            }
            layers[state.frames.size].forEach { frame ->
                if (!relationConstraintsAllowFrame(program, search.voices, state, frame)) return@forEach
                val next = search.space.apply(state, FixedVoiceWritingCandidate(frame))
                if (!search.space.score(next, search.task).hasHardViolation) enumerate(next)
            }
        }
        enumerate(search.space.initial(search.task))
        val exhaustive = complete.minWith(
            compareBy<Pair<com.mecon.theory.FixedVoiceWritingState<ChordTarget>, com.mecon.theory.ScoreBreakdown>> {
                it.second.total
            }.thenBy { search.space.diversityKey(it.first) }
        )

        val solved = assertIs<ConstraintSolveOutcome.Solved>(
            ConstraintProgramSolver.solvePolyphonicOutcome(program),
        ).solutions.single()
        assertEquals(exhaustive.second.total, solved.breakdown.total)
        assertEquals(
            exhaustive.second.findings.map { it.ruleId to it.scoreDelta },
            solved.breakdown.findings.map { it.ruleId to it.scoreDelta },
        )
    }

    /**
     * 终层 branch-and-bound 只为可能进入 top-k 的完整路径展开全局规则。这条回归证明它不会丢掉
     * 最优解：在终层全局规则（旋律反复、极值唯一）全部启用的窄域上，DP 的最优分与穷举一致，
     * 且实际展开全局规则的路径数严格少于终层接受的转移数。
     */
    @Test
    fun terminalBranchAndBoundKeepsTheExhaustiveOptimumWithGlobalRules() {
        val narrowSatb = VoicePlan(
            listOf(
                voice("S", 0, VoiceBoundary.UPPER_OUTER, "C5", "G5"),
                voice("A", 1, VoiceBoundary.INNER, "G4", "E5"),
                voice("T", 2, VoiceBoundary.INNER, "C4", "G4"),
                voice("B", 3, VoiceBoundary.LOWER_OUTER, "C3", "G3"),
            )
        )
        val compiled = FreeHarmonySolver.compile(
            FreeHarmonyRequest(
                key = key,
                tonalPlan = tonalPlan,
                slotCount = 3,
                vocabulary = listOf(tonic),
                voicePlan = narrowSatb,
                fixedTargetIdentityBySlot = (0..2).associateWith { tonic.identityKey() },
                searchConfig = SearchConfig(maxResults = 1),
            )
        )
        val program = compiled.copy(
            searchConfig = compiled.searchConfig.copy(
                // 出边宽度 = min(max(8, 4 × maxResults), candidateLimit)：两者都放到域大小之上，
                // 使 bounded DP 在这个窄域里实际不截断，可与穷举逐分对比。
                maxResults = 64,
                beamWidth = 512,
                backend = SearchBackend.LAYERED_DP,
                dynamicProgramming = DynamicProgrammingSearchConfig(
                    maxCandidatesPerTarget = 512,
                    maxLabelsPerState = 64,
                    maxFrontierStates = 100_000,
                    maxTransitionEvaluations = 5_000_000,
                ),
            ),
        )
        assertTrue(
            "free.melody.no-repeated-pattern" in
                requireNotNull(LayeredDpCapability.analyze(program).statePlan)
                    .terminalRerankRuleIds.map { it.value },
        )

        val search = ConstraintProgramSolver.buildSearch(program)
        val layers = program.slotDomains.indices.map { slotIndex ->
            search.candidateFactory.layerCandidates(
                slotIndex,
                program.slotDomains[slotIndex].targets.single(),
                maxCandidates = null,
            ).frames
        }
        var exhaustiveBest = Double.MAX_VALUE
        fun enumerate(state: com.mecon.theory.FixedVoiceWritingState<ChordTarget>) {
            if (state.frames.size == program.length) {
                val breakdown = search.space.score(state, search.task)
                if (!breakdown.hasHardViolation) exhaustiveBest = minOf(exhaustiveBest, breakdown.total)
                return
            }
            layers[state.frames.size].forEach { frame ->
                if (!relationConstraintsAllowFrame(program, search.voices, state, frame)) return@forEach
                val next = search.space.apply(state, FixedVoiceWritingCandidate(frame))
                if (!search.space.score(next, search.task).hasHardViolation) enumerate(next)
            }
        }
        enumerate(search.space.initial(search.task))

        val solved = assertIs<ConstraintSolveOutcome.Solved>(
            ConstraintProgramSolver.solvePolyphonicOutcome(program, maxTraceEntries = 4_096),
        )
        assertEquals(exhaustiveBest, solved.solutions.minOf { it.breakdown.total })

        val terminalTransitions = solved.trace.entries
            .filter { it.kind == WritingSearchTraceEventKind.LAYER_COMPLETED }
            .maxBy { it.depth }
            .generatedLabels
        assertTrue(
            solved.trace.terminalGlobalEvaluations in 1 until terminalTransitions,
            "terminalGlobalEvaluations=${solved.trace.terminalGlobalEvaluations} " +
                "terminalTransitions=$terminalTransitions",
        )
    }

    @Test
    fun leftBoundaryHasTheSameTransitionSemanticsInDpAndDfs() {
        val base = freeProgram(slotCount = 1).copy(writingRulePreset = WritingRulePreset.NONE)
        val voices = base.resolvedVoicePlan.toFixedVoices()
        val boundary = FixedVoiceBoundaryFrame(
            target = tonic,
            pitchesByVoiceId = voices.zip(
                listOf("G4", "E4", "C4", "C3").map(Pitch::fromName),
            ).associate { (voice, pitch) -> voice.id to pitch },
        )
        fun solve(backend: SearchBackend) = assertIs<ConstraintSolveOutcome.Solved>(
            ConstraintProgramSolver.solvePolyphonicOutcome(
                base.copy(searchConfig = base.searchConfig.copy(backend = backend)),
                context = ConstraintSolveContext(leftBoundary = boundary),
            )
        ).solutions.single()

        val dfs = solve(SearchBackend.GREEDY_DFS)
        val dp = solve(SearchBackend.LAYERED_DP)
        assertEquals(dfs.breakdown.total, dp.breakdown.total)
        assertEquals(
            dfs.breakdown.findings.map { it.ruleId to it.scoreDelta },
            dp.breakdown.findings.map { it.ruleId to it.scoreDelta },
        )
    }

    private fun freeProgram(
        slotCount: Int,
        requestedVoicePlan: VoicePlan = voicePlan,
    ): ConstraintProgram =
        FreeHarmonySolver.compile(
            FreeHarmonyRequest(
                key = key,
                tonalPlan = tonalPlan,
                slotCount = slotCount,
                vocabulary = listOf(tonic),
                voicePlan = requestedVoicePlan,
                fixedTargetIdentityBySlot = (0 until slotCount).associateWith { tonic.identityKey() },
                searchConfig = SearchConfig(maxResults = 1),
            )
        )

    private fun ConstraintProgram.withoutTerminalGlobalRules(): ConstraintProgram = copy(
        constraints = constraints.filterNot { constraint ->
            val predicate = (constraint.expr as? ConstraintExpr.Atom)?.predicate
            predicate is ConstraintPredicate.NoRepeatedVoicePattern ||
                predicate is ConstraintPredicate.RootProgressionPreference
        },
    )

    private fun exactVoicePlan(exactPitches: List<Pitch>): VoicePlan = VoicePlan(
        exactPitches.mapIndexed { index, pitch ->
            VoiceSpec(
                id = TrackId("dp-voice-$index"),
                order = index,
                boundary = when (index) {
                    0 -> VoiceBoundary.UPPER_OUTER
                    exactPitches.lastIndex -> VoiceBoundary.LOWER_OUTER
                    else -> VoiceBoundary.INNER
                },
                range = VoiceRange(pitch, pitch),
            )
        }
    )

    private data class AliasChordTarget(
        val delegate: ChordTarget,
        val alias: String,
    ) : ChordTarget by delegate {
        override fun identityKey(): String = alias
        override fun interpretationIdentityKey(): String = alias
    }

    private fun voice(
        id: String,
        order: Int,
        boundary: VoiceBoundary,
        low: String,
        high: String,
    ): VoiceSpec = VoiceSpec(
        id = TrackId("dp-$id"),
        order = order,
        boundary = boundary,
        range = VoiceRange(Pitch.fromName(low), Pitch.fromName(high)),
    )
}
