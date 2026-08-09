package com.mecon.exploration

import com.mecon.theory.RuleCatalog
import com.mecon.theory.SearchConfig
import com.mecon.theory.constraint.ConstraintProgramSolver
import com.mecon.theory.textbook.DominantSeventhRuleCatalog
import com.mecon.theory.textbook.DominantSeventhRules
import com.mecon.theory.textbook.RootPositionTriadRules
import com.mecon.theory.textbook.SecondInversionTriadRules
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SolverApiTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ---- describe ----------------------------------------------------------

    @Test
    fun describeListsEverySelectableRuleAndSerializes() {
        val manifest = SolverEngine.describe()

        val nodeIds = manifest.chapters.flatMap { it.rules }.map { it.id }.toSet()
        val selectableRuleIds = RuleCatalog.allDescriptors()
            .filter { it.selectable }
            .map { it.id.value }
        selectableRuleIds.forEach { ruleId ->
            assertTrue(ruleId in nodeIds, "manifest 缺少 selectable 规则 $ruleId")
        }

        assertTrue(manifest.policies.isNotEmpty())
        manifest.policies.forEach { policy ->
            assertTrue(policy.positions.isNotEmpty(), "policy ${policy.id} 无转位")
            assertTrue(policy.seventhPositions.isNotEmpty(), "policy ${policy.id} 无七和弦转位")
        }
        assertTrue(manifest.forms.any { it.requestType == "rule-example" })
        assertTrue(manifest.forms.any { it.requestType == "progression" })
        assertTrue(manifest.chapters.any { it.id == DominantSeventhRuleCatalog.chapterId.value })

        // §7 一致性：manifest 可序列化并 round-trip 回原值。
        val encoded = json.encodeToString(CapabilityManifest.serializer(), manifest)
        val decoded = json.decodeFromString(CapabilityManifest.serializer(), encoded)
        assertEquals(manifest, decoded)
    }

    @Test
    fun describeAttachesSceneSummaryToPatternRules() {
        val manifest = SolverEngine.describe()
        val cadential = manifest.chapters.flatMap { it.rules }
            .first { it.id == SecondInversionTriadRules.CADENTIAL_SIX_FOUR.value }
        assertTrue(cadential.scenes.isNotEmpty())
        assertEquals(3, cadential.scenes.first().windowMax)
        assertTrue(cadential.scenes.first().facetTypes.any { it == "ChordPattern" })
    }

    // ---- enumerate ---------------------------------------------------------

    @Test
    fun enumerateCadentialSixFourIncludesTonicSixFourDominantTonic() {
        val result = SolverEngine.enumerate(
            EnumerationRequest(ruleIds = listOf(SecondInversionTriadRules.CADENTIAL_SIX_FOUR.value))
        )
        assertTrue(result.diagnostics.isEmpty())
        assertTrue(
            result.progressions.any { progression ->
                progression.slots.map { it.degree } == listOf(1, 5, 1) &&
                    progression.slots.first().position == "SECOND_INVERSION"
            },
            "终止四六应枚举出 I(46)-V-I：${result.progressions.map { p -> p.slots.map { it.degree to it.position } }}",
        )
    }

    @Test
    fun enumeratePassingSixFourGivesThreeChordProgressions() {
        val result = SolverEngine.enumerate(
            EnumerationRequest(ruleIds = listOf(SecondInversionTriadRules.PASSING_SIX_FOUR.value))
        )
        assertTrue(result.progressions.isNotEmpty())
        assertTrue(
            result.progressions.all { it.slots.size == 3 },
            "经过四六进行应为三槽",
        )
        assertTrue(
            result.progressions.all { it.slots[1].position == "SECOND_INVERSION" },
            "经过四六中间和弦应为第二转位",
        )
    }

    @Test
    fun enumerateReportsInvalidSelection() {
        val result = SolverEngine.enumerate(
            EnumerationRequest(ruleIds = listOf("does.not.exist"))
        )
        assertTrue(result.progressions.isEmpty())
        assertTrue(result.diagnostics.any { it.code == Diagnostics.CODE_INVALID_SELECTION })
    }

    @Test
    fun enumerateConfirmedDegradesToMayWithDiagnostic() {
        val result = SolverEngine.enumerate(
            EnumerationRequest(
                ruleIds = listOf(SecondInversionTriadRules.CADENTIAL_SIX_FOUR.value),
                verify = VerifyLevelSpec.CONFIRMED,
            )
        )
        assertTrue(result.diagnostics.any { it.code == Diagnostics.CODE_CONFIRMED_DEGRADED })
        assertTrue(result.progressions.all { !it.verified })
    }

    @Test
    fun enumerateDominantSeventhReturnsArityAwareSlots() {
        val result = SolverEngine.enumerate(
            EnumerationRequest(
                ruleIds = listOf(DominantSeventhRules.SEVENTH_RESOLVES_DOWN.value),
                policyId = "seventh-chords",
                windowLimit = 2,
            )
        )

        assertTrue(result.diagnostics.isEmpty())
        assertTrue(
            result.progressions.any { progression ->
                progression.slots.map { it.degree } == listOf(5, 1) &&
                    progression.slots.map { it.arity } == listOf("SEVENTH", "TRIAD") &&
                    progression.slots.all { it.position == "ROOT_POSITION" }
            },
            "V7-I 应由符号级 enumerate 返回七和弦槽：${result.progressions}",
        )
    }

    // ---- solve -------------------------------------------------------------

    @Test
    fun solveMatchesConvenienceRunnerOutput() {
        val request = RuleExampleRequest(
            from = DegreeSpec(5),
            to = DegreeSpec(1),
            selectedRules = listOf(RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE.value),
            search = SearchSpec(maxResults = 2, beamWidth = 96),
        )
        val viaApi = SolverEngine.solve(SolveRequest(request))
        val viaRunner = ExplorationRequestRunner.run(request)

        // solve 走同一便捷路径：指纹一致、候选数一致、目标 finding 命中。
        assertEquals(viaRunner.fingerprint, viaApi.output.fingerprint)
        assertEquals(viaRunner.candidates.size, viaApi.output.candidates.size)
        assertTrue(viaApi.output.candidates.isNotEmpty())
        assertTrue(
            viaApi.output.candidates.all { candidate ->
                candidate.findings.any { it.ruleId == RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE.value }
            }
        )
        assertEquals(viaApi.output.fingerprint, viaApi.solveStateFingerprint)
    }

    @Test
    fun solveSurfacesStructuredDiagnosticOnInvalidSelection() {
        val request = RuleExampleRequest(
            from = DegreeSpec(1),
            to = DegreeSpec(2),
            selectedRules = listOf(RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE.value),
        )
        val result = SolverEngine.solve(SolveRequest(request))
        assertTrue(result.output.candidates.isEmpty())
        assertTrue(result.diagnostics.isNotEmpty())
        assertTrue(result.diagnostics.all { it.code.isNotBlank() })
        // 中文渲染回退与结构化诊断同源。
        assertEquals(
            result.output.diagnostics,
            result.diagnostics.map(DiagnosticMessages::resolve),
        )
    }

    @Test
    fun solveDominantSeventhViolationReturnsComparisonPair() {
        val request = RuleExampleRequest(
            from = DegreeSpec(5),
            to = DegreeSpec(1),
            selectedRules = emptyList(),
            demonstrate = DemonstrationSpec(DominantSeventhRules.SEVENTH_ASCENDS.value),
            search = SearchSpec(maxResults = 2, beamWidth = 128),
        )
        val result = SolverEngine.solve(SolveRequest(request))

        assertTrue(result.output.diagnostics.isEmpty())
        assertEquals(2, result.output.candidates.size)
        assertTrue(result.output.comparisonGroups.isNotEmpty())
        assertTrue(
            result.output.candidates[1].findings.any {
                it.ruleId == DominantSeventhRules.SEVENTH_ASCENDS.value &&
                    it.isDemonstrationTarget
            }
        )
    }

    @Test
    fun solveSupertonicSeventhExampleUsesSeventhSolver() {
        val request = RuleExampleRequest(
            from = DegreeSpec(2),
            to = DegreeSpec(5),
            selectedRules = listOf(DominantSeventhRules.SUPERTONIC_TO_DOMINANT.value),
            search = SearchSpec(maxResults = 2, beamWidth = 128),
        )
        val result = SolverEngine.solve(SolveRequest(request))

        assertTrue(result.output.diagnostics.isEmpty())
        assertTrue(result.output.candidates.isNotEmpty())
        assertTrue(
            result.output.candidates.any { candidate ->
                candidate.findings.any { it.ruleId == DominantSeventhRules.SUPERTONIC_TO_DOMINANT.value }
            }
        )
    }

    @Test
    fun solveCircleOfFifthsVariantsProduceCandidates() {
        // 回归：原位完全/省五交替曾因 beam 中途丢弃完全和弦前缀而无解；
        // 一/三转位交替曾因终止槽硬编码原位 I（低音七音无法下行解决）而无解。
        val variants = listOf(
            DominantSeventhRules.CIRCLE_OF_FIFTHS_SEVENTHS,
            DominantSeventhRules.CIRCLE_ROOT_POSITION_ALTERNATION,
            DominantSeventhRules.CIRCLE_FIRST_THIRD_INVERSION,
            DominantSeventhRules.CIRCLE_SECOND_ROOT_INVERSION,
        )
        for (mode in listOf(KeyModeSpec.MAJOR, KeyModeSpec.MINOR)) {
            for (rule in variants) {
                val request = RuleExampleRequest(
                    key = KeySpec(fifths = 0, mode = mode),
                    from = DegreeSpec(4),
                    to = DegreeSpec(7),
                    selectedRules = listOf(rule.value),
                    search = SearchSpec(maxResults = 1, beamWidth = 128),
                )
                val result = SolverEngine.solve(SolveRequest(request))
                assertTrue(
                    result.output.candidates.isNotEmpty(),
                    "$mode ${rule.value} 应有候选，诊断：${result.output.diagnostics}",
                )
                assertTrue(
                    result.output.candidates.all { candidate ->
                        candidate.findings.any { it.ruleId == rule.value }
                    },
                    "$mode ${rule.value} 候选应命中目标规则 finding",
                )
            }
        }
    }

    // ---- solve：约束程序路径（S2 增量一）----------------------------------

    @Test
    fun solveViaConstraintProgramHitsTargetFinding() {
        // 编译等价（行为）：便捷请求 → fromConvenience → spec → program 路径求解，命中目标 finding。
        val convenience = RuleExampleRequest(
            from = DegreeSpec(5),
            to = DegreeSpec(1),
            selectedRules = listOf(RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE.value),
            search = SearchSpec(maxResults = 2, beamWidth = 96),
        )
        val spec = ConstraintProgramCompiler.fromConvenience(convenience)
        assertTrue(spec.slotConstraints.any { it is ToneCompletenessAtSpec })
        assertTrue(spec.slotConstraints.any { it is DoublingAtSpec })
        assertTrue(spec.slotConstraints.any { it is AvoidScaleDegreeDoublingAtSpec })
        val result = SolverEngine.solve(
            SolveRequest(
                program = spec,
                key = convenience.key,
                policyId = "free-triads",
                search = convenience.search,
            )
        )

        assertTrue(result.output.diagnostics.isEmpty(), "诊断：${result.output.diagnostics}")
        assertTrue(result.output.candidates.isNotEmpty())
        assertTrue(
            result.output.candidates.all { candidate ->
                candidate.findings.any { it.ruleId == RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE.value }
            },
            "约束程序路径的候选都应命中目标 finding",
        )
    }

    @Test
    fun describeExposesConstraintKinds() {
        val manifest = SolverEngine.describe()
        val ids = manifest.constraintKinds.map { it.id }.toSet()
        assertTrue(setOf("chord-at", "rule-at", "doubling-at", "spacing-at").all { it in ids })
        assertTrue(setOf("all-different", "adjacent-common-tone").all { it in ids })
        assertTrue(
            setOf(
                "tone-completeness-at",
                "avoid-doubling-at",
                "avoid-scale-degree-doubling-at",
                "chord-tone-neighbor",
                "target-feature-bonus",
                "constraint-at",
            ).all { it in ids },
        )
        manifest.constraintKinds.forEach { assertTrue(it.paramsSchema.isNotEmpty(), "${it.id} 无 schema") }
        assertTrue(
            manifest.constraintKinds.first { it.id == "doubling-at" }
                .paramsSchema.toString().contains("selector"),
            "DoublingAt schema 应公开统一 TargetSelector",
        )
        assertTrue(manifest.forms.any { it.requestType == "constraint-program" })
    }

    @Test
    fun schoenbergRuntimeConstraintsRoundTripThroughConstraintProgramSpec() {
        val spec = ConstraintProgramSpec(
            length = 3,
            slotConstraints = listOf(
                ChordAtSpec(slot = 0, degrees = setOf(2, 4), positions = setOf("ROOT_POSITION")),
                ChordAtSpec(slot = 1, degrees = setOf(7), positions = setOf("FIRST_INVERSION")),
                ChordAtSpec(slot = 2, degrees = setOf(3), positions = setOf("ROOT_POSITION")),
                AvoidDoublingAtSpec(
                    slot = 1,
                    tone = ChordToneSpec.FIFTH,
                    required = true,
                    selector = TargetSelectorSpec(degrees = setOf(7)),
                ),
                ChordToneNeighborSpec(
                    window = SlotWindowSpec(0, 2),
                    sourceTone = ChordToneSpec.FIFTH,
                    direction = ChordToneNeighborDirectionSpec.PREVIOUS,
                    candidateScaleDegrees = setOf(4),
                    allowedDiatonicStepDeltas = setOf(0),
                    sourceSelector = TargetSelectorSpec(
                        degrees = setOf(7),
                        inversions = setOf(1),
                    ),
                ),
                ChordToneNeighborSpec(
                    window = SlotWindowSpec(0, 2),
                    sourceTone = ChordToneSpec.FIFTH,
                    direction = ChordToneNeighborDirectionSpec.NEXT,
                    candidateScaleDegrees = setOf(3),
                    allowedDiatonicStepDeltas = setOf(-1),
                    sourceSelector = TargetSelectorSpec(
                        degrees = setOf(7),
                        inversions = setOf(1),
                    ),
                    neighborSelector = TargetSelectorSpec(degrees = setOf(3)),
                ),
                TargetFeatureBonusSpec(
                    window = SlotWindowSpec(0, 2),
                    selector = TargetSelectorSpec(
                        degrees = setOf(7),
                        arities = setOf(ChordAritySpec.TRIAD),
                        requiredPitchClasses = setOf(11),
                        identityKeys = setOf("target-identity"),
                        sonorityIdentityKeys = setOf("sonority-identity"),
                        interpretationIdentityKeys = setOf("interpretation-identity"),
                    ),
                    ruleId = "solver.constraint.knowledge.leading-triad",
                    message = "使用了导和弦。",
                    bonus = 8.0,
                ),
            ),
        )
        val decoded = json.decodeFromString(
            ConstraintProgramSpec.serializer(),
            json.encodeToString(ConstraintProgramSpec.serializer(), spec),
        )
        val compiled = ConstraintProgramCompiler.compile(
            spec = decoded,
            keySpec = KeySpec(),
            policyId = "free-triads",
            search = SearchConfig(maxResults = 1, beamWidth = 512),
        )
        val program = compiled.program ?: error("编译失败：${compiled.diagnostics}")
        assertTrue(program.targetFeatureBonuses.isNotEmpty(), "target-feature-bonus 应进入 runtime program")
        with(program.targetFeatureBonuses.single().selector) {
            assertEquals(setOf(11), requiredPitchClasses.map { it.value }.toSet())
            assertEquals(setOf("target-identity"), identityKeys)
            assertEquals(setOf("sonority-identity"), sonorityIdentityKeys)
            assertEquals(setOf("interpretation-identity"), interpretationIdentityKeys)
        }
        assertTrue(program.avoidDoublings.isNotEmpty(), "avoid-doubling-at 应进入 runtime program")
        assertTrue(program.chordToneNeighbors.isNotEmpty(), "chord-tone-neighbor 应进入 runtime program")
        val solutions = ConstraintProgramSolver.solve(program)

        assertTrue(solutions.isNotEmpty(), "公开 spec 往返后应仍能求解勋伯格导和弦约束")
    }

    @Test
    fun constraintAtOrRoundTripUsesMatchedBranchName() {
        val spec = ConstraintProgramSpec(
            length = 1,
            slotConstraints = listOf(
                ChordAtSpec(slot = 0, degrees = setOf(1), positions = setOf("ROOT_POSITION")),
                ConstraintAtSpec(
                    modality = ConstraintModalitySpec.ANNOTATE,
                    window = SlotWindowSpec(0, 0),
                    selector = TargetSelectorSpec(degrees = setOf(1)),
                    expr = ConstraintOrExprSpec(
                        branches = listOf(
                            ConstraintBranchSpec(
                                expr = ConstraintAtomExprSpec(
                                    TargetFeatureBonusSpec(
                                        window = SlotWindowSpec(0, 0),
                                        selector = TargetSelectorSpec(degrees = setOf(1)),
                                        ruleId = "unused.tonic",
                                        message = "unused",
                                        bonus = 1.0,
                                    )
                                ),
                                ruleId = "named-shape.tonic",
                                message = "命中主和弦分支。",
                            ),
                            ConstraintBranchSpec(
                                expr = ConstraintAtomExprSpec(
                                    TargetFeatureBonusSpec(
                                        window = SlotWindowSpec(0, 0),
                                        selector = TargetSelectorSpec(degrees = setOf(5)),
                                        ruleId = "unused.dominant",
                                        message = "unused",
                                        bonus = 1.0,
                                    )
                                ),
                                ruleId = "named-shape.dominant",
                                message = "命中属和弦分支。",
                            ),
                        )
                    ),
                ),
            ),
        )
        val decoded = json.decodeFromString(
            ConstraintProgramSpec.serializer(),
            json.encodeToString(ConstraintProgramSpec.serializer(), spec),
        )
        val compiled = ConstraintProgramCompiler.compile(
            decoded,
            KeySpec(),
            "free-triads",
            SearchConfig(maxResults = 1, beamWidth = 64),
        )
        val solution = ConstraintProgramSolver.solve(compiled.program ?: error("编译失败：${compiled.diagnostics}"))
            .single()

        assertTrue(solution.breakdown.findings.any {
            it.ruleId.value == "named-shape.tonic" && it.message == "命中主和弦分支。"
        })
        assertTrue(solution.breakdown.findings.none { it.ruleId.value == "named-shape.dominant" })
    }

    @Test
    fun solveProgramInvalidChordAtReturnsDiagnostic() {
        // 同槽 degrees 交集为空 → constraint-invalid。
        val spec = ConstraintProgramSpec(
            length = 2,
            slotConstraints = listOf(
                ChordAtSpec(slot = 0, degrees = setOf(1)),
                ChordAtSpec(slot = 0, degrees = setOf(5)),
            ),
        )
        val result = SolverEngine.solve(SolveRequest(program = spec, key = KeySpec()))
        assertTrue(result.output.candidates.isEmpty())
        assertTrue(result.diagnostics.any { it.code == Diagnostics.CODE_CONSTRAINT_INVALID })
    }

    @Test
    fun solveProgramUnsatisfiableRequirementReturnsNoSolution() {
        // I-I 无法产生四五度共同音 → 全局 requirement 判缺失 → 无解诊断。
        val spec = ConstraintProgramSpec(
            length = 2,
            slotConstraints = listOf(
                ChordAtSpec(slot = 0, degrees = setOf(1), positions = setOf("ROOT_POSITION")),
                ChordAtSpec(slot = 1, degrees = setOf(1), positions = setOf("ROOT_POSITION")),
                RuleAtSpec(
                    window = SlotWindowSpec(0, null),
                    ruleId = RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE.value,
                    mode = RequirementModeSpec.REQUIRE_INDICATION,
                ),
            ),
        )
        val result = SolverEngine.solve(SolveRequest(program = spec, key = KeySpec()))
        assertTrue(result.output.candidates.isEmpty())
        assertTrue(result.diagnostics.any { it.code == Diagnostics.CODE_NO_SOLUTION })
    }

    // ---- refine / check（占位）--------------------------------------------

    @Test
    fun refineReturnsNotAvailablePlaceholder() {
        val base = SolveRequest(
            RuleExampleRequest(
                from = DegreeSpec(5),
                to = DegreeSpec(1),
                selectedRules = listOf(RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE.value),
            )
        )
        val result = SolverEngine.refine(RefineRequest(base = base))
        assertTrue(result.diagnostics.any { it.code == Diagnostics.CODE_REFINE_NOT_AVAILABLE })
        assertTrue(result.output.candidates.isEmpty())
    }

    @Test
    fun checkReturnsNotAvailablePlaceholder() {
        val solved = SolverEngine.solve(
            SolveRequest(
                RuleExampleRequest(
                    from = DegreeSpec(5),
                    to = DegreeSpec(1),
                    selectedRules = listOf(RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE.value),
                )
            )
        )
        val score = solved.output.candidates.first().score
        val result = SolverEngine.check(CheckRequest(score = score))
        assertTrue(result.findings.isEmpty())
        assertTrue(result.diagnostics.any { it.code == Diagnostics.CODE_CHECK_NOT_AVAILABLE })
    }
}
