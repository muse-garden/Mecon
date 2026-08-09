package com.mecon.exploration

import com.mecon.theory.ChordVocabulary
import com.mecon.theory.RuleCatalog
import com.mecon.theory.RuleDescriptor
import com.mecon.theory.RuleId
import com.mecon.theory.RuleScene
import com.mecon.theory.SceneMatcher
import com.mecon.theory.SymbolicMatch
import com.mecon.theory.constraint.ConstraintProgramSolver
import com.mecon.theory.constraint.ConstraintSolution
import com.mecon.theory.schoenberg.SchoenbergCommonToneExercises
import com.mecon.theory.schoenberg.SchoenbergExerciseOption
import com.mecon.theory.schoenberg.SchoenbergExerciseSelectionKeys
import com.mecon.theory.schoenberg.SchoenbergSymbolicProgression
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * [SolverApi] 的 in-process 实现（docs/theory/solver-api.md）。
 *
 * describe / enumerate / solve 已落地；refine（S2）与 check（待全谱入口）返回结构化占位诊断。
 */
object SolverEngine : SolverApi {

    // ---- describe ----------------------------------------------------------

    override fun describe(): CapabilityManifest =
        CapabilityManifest(
            protocolVersion = SOLVER_PROTOCOL_VERSION,
            rulesVersion = SOLVER_RULES_VERSION,
            chapters = buildChapters(),
            policies = Policies.all.map { policy ->
                PolicyInfo(
                    id = policy.id,
                    labelKey = policy.labelKey,
                    positions = policy.positions.map { it.name },
                    seventhPositions = policy.seventhPositions.map { it.name },
                    minSlots = policy.minSlots,
                )
            },
            constraintKinds = buildConstraintKinds(),
            forms = buildForms(),
        )

    private fun buildConstraintKinds(): List<ConstraintKindInfo> =
        listOf(
            ConstraintKindInfo(
                id = "chord-at",
                paramsSchema = buildJsonObject {
                    putJsonArray("params") {
                        add("slot"); add("degrees"); add("qualities"); add("positions")
                        add("arity"); add("triadSonority")
                    }
                },
            ),
            ConstraintKindInfo(
                id = "rule-at",
                paramsSchema = buildJsonObject {
                    putJsonArray("params") { add("window"); add("ruleId"); add("mode") }
                },
            ),
            ConstraintKindInfo(
                id = "doubling-at",
                paramsSchema = buildJsonObject {
                    putJsonArray("params") { add("slot"); add("tone"); add("required"); add("selector") }
                },
            ),
            ConstraintKindInfo(
                id = "avoid-doubling-at",
                paramsSchema = buildJsonObject {
                    putJsonArray("params") { add("slot"); add("tone"); add("required"); add("selector") }
                },
            ),
            ConstraintKindInfo(
                id = "avoid-scale-degree-doubling-at",
                paramsSchema = buildJsonObject {
                    putJsonArray("params") { add("slot"); add("degree"); add("alteration"); add("required"); add("selector") }
                },
            ),
            ConstraintKindInfo(
                id = "tone-completeness-at",
                paramsSchema = buildJsonObject {
                    putJsonArray("params") { add("window"); add("requiredTones"); add("omittedTones"); add("selector") }
                },
            ),
            ConstraintKindInfo(
                id = "spacing-at",
                paramsSchema = buildJsonObject {
                    putJsonArray("params") { add("window"); add("preference") }
                },
            ),
            ConstraintKindInfo(
                id = "fifth-at",
                paramsSchema = buildJsonObject {
                    putJsonArray("params") { add("slot"); add("fifth") }
                },
            ),
            ConstraintKindInfo(
                id = "all-different",
                paramsSchema = buildJsonObject {
                    putJsonArray("params") { add("window") }
                },
            ),
            ConstraintKindInfo(
                id = "adjacent-common-tone",
                paramsSchema = buildJsonObject {
                    putJsonArray("params") { add("window"); add("holdInSameVoice") }
                },
            ),
            ConstraintKindInfo(
                id = "chord-tone-neighbor",
                paramsSchema = buildJsonObject {
                    putJsonArray("params") {
                        add("window"); add("sourceSlot"); add("sourceTone"); add("direction"); add("candidateScaleDegrees")
                        add("allowedDiatonicStepDeltas"); add("voiceFilter"); add("required")
                        add("candidateAlterations"); add("sourceSelector"); add("neighborSelector")
                    }
                },
            ),
            ConstraintKindInfo(
                id = "target-feature-bonus",
                paramsSchema = buildJsonObject {
                    putJsonArray("params") { add("window"); add("selector"); add("ruleId"); add("message"); add("bonus") }
                },
            ),
            ConstraintKindInfo(
                id = "constraint-at",
                paramsSchema = buildJsonObject {
                    putJsonArray("params") {
                        add("window"); add("selector"); add("expr"); add("modality"); add("weight"); add("bonus"); add("ruleId"); add("message")
                    }
                    putJsonArray("exprKinds") { add("atom"); add("and"); add("or"); add("not") }
                    put("namedOrBranches", true)
                },
            ),
        )

    private fun buildChapters(): List<ChapterInfo> =
        RuleCatalog.allDescriptors()
            .groupBy { it.chapter }
            .map { (chapter, descriptors) ->
                val titleKey = descriptors.firstOrNull { it.parent == null }?.titleKey ?: chapter.value
                ChapterInfo(
                    id = chapter.value,
                    titleKey = titleKey,
                    rules = descriptors.map { it.toNodeInfo() },
                )
            } + schoenbergChapter()

    private fun schoenbergChapter(): ChapterInfo =
        ChapterInfo(
            id = SchoenbergCommonToneExercises.CHAPTER_ID.value,
            titleKey = "chapter.schoenbergHarmony",
            rules = listOf(
                RuleNodeInfo(
                    id = SchoenbergCommonToneExercises.CHAPTER_RULE_ID.value,
                    titleKey = "chapter.schoenbergHarmony",
                    kind = "GROUP",
                    selectable = false,
                ),
                RuleNodeInfo(
                    id = SchoenbergCommonToneExercises.MAJOR_BRANCH_RULE_ID.value,
                    titleKey = "schoenberg.branch.majorConnections",
                    kind = "GROUP",
                    parentId = SchoenbergCommonToneExercises.CHAPTER_RULE_ID.value,
                    selectable = false,
                ),
                RuleNodeInfo(
                    id = SchoenbergCommonToneExercises.MINOR_BRANCH_RULE_ID.value,
                    titleKey = "schoenberg.branch.minorConnections",
                    kind = "GROUP",
                    parentId = SchoenbergCommonToneExercises.CHAPTER_RULE_ID.value,
                    selectable = false,
                ),
                RuleNodeInfo(
                    id = SchoenbergCommonToneExercises.GENERAL_BRANCH_RULE_ID.value,
                    titleKey = "schoenberg.branch.generalConnections",
                    kind = "GROUP",
                    parentId = SchoenbergCommonToneExercises.CHAPTER_RULE_ID.value,
                    selectable = false,
                ),
                RuleNodeInfo(
                    id = SchoenbergCommonToneExercises.MODULATION_BRANCH_RULE_ID.value,
                    titleKey = "schoenberg.branch.modulation",
                    kind = "GROUP",
                    parentId = SchoenbergCommonToneExercises.CHAPTER_RULE_ID.value,
                    selectable = false,
                ),
            ) + SchoenbergCommonToneExercises.exerciseDescriptors.map { descriptor ->
                RuleNodeInfo(
                    id = descriptor.ruleId.value,
                    titleKey = "schoenberg.${descriptor.exerciseId}",
                    kind = "EXERCISE",
                    parentId = descriptor.parentId.value,
                    selectable = true,
                )
            },
        )

    private fun RuleDescriptor.toNodeInfo(): RuleNodeInfo =
        RuleNodeInfo(
            id = id.value,
            titleKey = titleKey,
            kind = kind.name,
            parentId = parent?.value,
            selectable = selectable,
            demonstrableAsViolation = demonstrableAsViolation,
            scenes = RuleCatalog.scenes(id).map { it.toSummary() },
        )

    private fun RuleScene.toSummary(): SceneSummary =
        SceneSummary(
            role = role.name,
            windowMin = window.first,
            windowMax = window.last,
            facetTypes = facets.map { it::class.simpleName ?: "Facet" },
            unavailableReason = unavailableReason,
        )

    private fun buildForms(): List<FormSpec> =
        listOf(
            FormSpec(
                requestType = "rule-example",
                fields = listOf(
                    FormField("key", FormFieldKind.KEY_PICKER, "field.key"),
                    FormField("rule", FormFieldKind.RULE_TREE, "field.rule"),
                    // 两和弦连接规则用 DEGREE_PAIR；window≥3 场景（四六）用 PROGRESSION_PICKER。
                    FormField("degreePair", FormFieldKind.DEGREE_PAIR, "field.degreePair"),
                    FormField("progression", FormFieldKind.PROGRESSION_PICKER, "field.progression"),
                    FormField("demonstrate", FormFieldKind.TOGGLE, "field.demonstrate"),
                ),
            ),
            FormSpec(
                requestType = "progression",
                fields = listOf(
                    FormField("key", FormFieldKind.KEY_PICKER, "field.key"),
                    FormField(
                        id = "policy",
                        kind = FormFieldKind.SELECT,
                        labelKey = "field.policy",
                        constraints = buildJsonObject {
                            putJsonArray("options") { Policies.all.forEach { add(it.id) } }
                        },
                    ),
                    FormField("slots", FormFieldKind.SLOT_LIST, "field.slots"),
                ),
            ),
            FormSpec(
                requestType = "constraint-program",
                fields = listOf(
                    FormField("key", FormFieldKind.KEY_PICKER, "field.key"),
                    FormField(
                        id = "policy",
                        kind = FormFieldKind.SELECT,
                        labelKey = "field.policy",
                        constraints = buildJsonObject {
                            putJsonArray("options") { Policies.all.forEach { add(it.id) } }
                        },
                    ),
                    FormField("slots", FormFieldKind.SLOT_LIST, "field.slots"),
                    FormField("rules", FormFieldKind.RULE_TREE, "field.rule"),
                ),
            ),
            FormSpec(
                requestType = "schoenberg-exercise",
                fields = listOf(
                    FormField(
                        id = "exerciseId",
                        kind = FormFieldKind.SELECT,
                        labelKey = "field.exercise",
                        constraints = buildJsonObject {
                            putJsonArray("options") {
                                SchoenbergCommonToneExercises.exerciseDescriptors.forEach { add(it.exerciseId) }
                            }
                            putJsonArray("independent") {
                                SchoenbergCommonToneExercises.exerciseDescriptors
                                    .filter { it.group.name == "INDEPENDENT" }
                                    .forEach { add(it.exerciseId) }
                            }
                            putJsonArray("integrated") {
                                SchoenbergCommonToneExercises.exerciseDescriptors
                                    .filter { it.group.name == "INTEGRATED" }
                                    .forEach { add(it.exerciseId) }
                            }
                        },
                    ),
                    FormField("key", FormFieldKind.KEY_PICKER, "field.key"),
                    FormField(
                        id = SchoenbergExerciseSelectionKeys.SECONDARY_HARMONY,
                        kind = FormFieldKind.SELECT,
                        labelKey = "field.secondaryHarmony",
                        constraints = buildJsonObject {
                            putJsonArray("exerciseIds") {
                                exerciseIdsSelecting(SchoenbergExerciseSelectionKeys.SECONDARY_HARMONY)
                                    .forEach(::add)
                            }
                        },
                    ),
                    FormField(
                        id = SchoenbergExerciseSelectionKeys.DIMINISHED_SEVENTH_CHORD,
                        kind = FormFieldKind.SELECT,
                        labelKey = "field.diminishedSeventhChord",
                        constraints = buildJsonObject {
                            putJsonArray("exerciseIds") {
                                exerciseIdsSelecting(SchoenbergExerciseSelectionKeys.DIMINISHED_SEVENTH_CHORD)
                                    .forEach(::add)
                            }
                        },
                    ),
                    FormField(
                        id = SchoenbergExerciseSelectionKeys.DIMINISHED_SEVENTH_USAGE,
                        kind = FormFieldKind.SELECT,
                        labelKey = "field.diminishedSeventhUsage",
                        constraints = buildJsonObject {
                            putJsonArray("exerciseIds") {
                                exerciseIdsSelecting(SchoenbergExerciseSelectionKeys.DIMINISHED_SEVENTH_USAGE)
                                    .forEach(::add)
                            }
                        },
                    ),
                    FormField(
                        id = SchoenbergExerciseSelectionKeys.DISTANT_MODULATION_PATH,
                        kind = FormFieldKind.SELECT,
                        labelKey = "field.distantModulationPath",
                        constraints = buildJsonObject {
                            putJsonArray("exerciseIds") {
                                exerciseIdsSelecting(SchoenbergExerciseSelectionKeys.DISTANT_MODULATION_PATH)
                                    .forEach(::add)
                            }
                            putJsonArray("options") {
                                com.mecon.theory.schoenberg.SchoenbergDistantTonalPaths.all
                                    .forEach { add(it.id.value) }
                            }
                        },
                    ),
                    FormField(
                        id = SchoenbergExerciseSelectionKeys.TONAL_CONFIRMATION,
                        kind = FormFieldKind.SELECT,
                        labelKey = "field.tonalConfirmation",
                        constraints = buildJsonObject {
                            putJsonArray("exerciseIds") {
                                exerciseIdsSelecting(SchoenbergExerciseSelectionKeys.TONAL_CONFIRMATION)
                                    .forEach(::add)
                            }
                            putJsonArray("options") {
                                com.mecon.theory.schoenberg.TonalConfirmationLevel.entries
                                    .forEach { add(it.name) }
                            }
                        },
                    ),
                    FormField(
                        id = "progression",
                        kind = FormFieldKind.PROGRESSION_PICKER,
                        labelKey = "field.progression",
                        constraints = buildJsonObject {
                            putJsonArray("requiresEnumeratedProgression") {
                                SchoenbergCommonToneExercises.exerciseDescriptors
                                    .filter { it.requiresEnumeratedProgression }
                                    .forEach { add(it.exerciseId) }
                            }
                        },
                    ),
                    FormField(
                        id = "continuationChordCount",
                        kind = FormFieldKind.NUMBER,
                        labelKey = "field.continuationChordCount",
                        constraints = buildJsonObject {
                            putJsonObject("rangeByExercise") {
                                SchoenbergCommonToneExercises.exerciseDescriptors.forEach { descriptor ->
                                    putJsonObject(descriptor.exerciseId) {
                                        put("min", descriptor.continuationChordCountRange.first)
                                        put("max", descriptor.continuationChordCountRange.last)
                                    }
                                }
                            }
                        },
                    ),
                    FormField(
                        id = "chordFilters",
                        kind = FormFieldKind.CHORD_FILTERS,
                        labelKey = "field.chordFilters",
                    ),
                    FormField(
                        id = "includeDeceptiveCadence",
                        kind = FormFieldKind.TOGGLE,
                        labelKey = "field.includeDeceptiveCadence",
                        constraints = buildJsonObject {
                            putJsonArray("exerciseIds") {
                                exerciseIdsSupporting(SchoenbergExerciseOption.DECEPTIVE_CADENCE)
                                    .forEach(::add)
                            }
                        },
                    ),
                    FormField(
                        id = "includeCadentialSixFour",
                        kind = FormFieldKind.TOGGLE,
                        labelKey = "field.includeCadentialSixFour",
                        constraints = buildJsonObject {
                            putJsonArray("exerciseIds") {
                                exerciseIdsSupporting(SchoenbergExerciseOption.CADENTIAL_SIX_FOUR)
                                    .forEach(::add)
                            }
                        },
                    ),
                ),
            ),
            FormSpec(
                requestType = "modulation-exercise",
                fields = listOf(
                    FormField("sourceKey", FormFieldKind.KEY_PICKER, "field.sourceKey"),
                    FormField("targetKey", FormFieldKind.KEY_PICKER, "field.targetKey"),
                    FormField("pivotChord", FormFieldKind.SELECT, "field.pivotChord"),
                    FormField("sourceChordCount", FormFieldKind.NUMBER, "field.sourceChordCount"),
                    FormField("targetChordCount", FormFieldKind.NUMBER, "field.targetChordCount"),
                    FormField("solverPreset", FormFieldKind.SELECT, "field.solverPreset"),
                ),
            ),
        )

    private fun exerciseIdsSelecting(selectionKey: String): List<String> =
        SchoenbergCommonToneExercises.exerciseDescriptors
            .filter { descriptor ->
                descriptor.selectionDefinitions.any { it.key == selectionKey }
            }
            .map { it.exerciseId }

    private fun exerciseIdsSupporting(option: SchoenbergExerciseOption): List<String> =
        SchoenbergCommonToneExercises.exerciseDescriptors
            .filter { option in it.supportedOptions }
            .map { it.exerciseId }

    // ---- enumerate ---------------------------------------------------------

    override fun enumerate(request: EnumerationRequest): EnumerationResult =
        enumerate(request) { true }

    fun enumerate(
        request: EnumerationRequest,
        shouldContinue: () -> Boolean,
    ): EnumerationResult {
        val schoenbergExerciseId = request.ruleIds
            .singleOrNull()
            ?.let { SchoenbergCommonToneExercises.exerciseIdForRule(RuleId(it)) }
        if (schoenbergExerciseId != null) {
            return enumerateSchoenbergExercise(request, schoenbergExerciseId, shouldContinue)
        }
        val ruleIds = request.ruleIds.map(::RuleId)
        val validation = RuleCatalog.validateRuleSet(ruleIds)
        if (!validation.isValid) {
            return EnumerationResult(
                progressions = emptyList(),
                diagnostics = validation.errors.map { Diagnostics.invalidSelection(it.ruleId.value, it.message) },
            )
        }

        val key = request.key.toKey()
        val policy = Policies.get(request.policyId)
        val vocabulary = ChordVocabulary.fromKey(
            key = key,
            positions = policy.positions,
            seventhPositions = policy.seventhPositions,
        )

        val diagnostics = mutableListOf<SolverDiagnostic>()
        // CONFIRMED 🚧：SceneMatcher.verify 仍等价 MAY，标注降级，verified 一律 false。
        if (request.verify == VerifyLevelSpec.CONFIRMED) diagnostics += Diagnostics.confirmedDegraded

        val progressions = ruleIds
            .flatMap { RuleCatalog.scenes(it) }
            .flatMap { scene -> SceneMatcher.enumerate(scene, key, vocabulary, request.windowLimit) }
            .map { it.toProgression() }
            .distinctBy { it.slots }

        return EnumerationResult(progressions, diagnostics)
    }

    private fun enumerateSchoenbergExercise(
        request: EnumerationRequest,
        exerciseId: String,
        shouldContinue: () -> Boolean,
    ): EnumerationResult {
        val descriptor = SchoenbergCommonToneExercises.descriptorForExercise(exerciseId)
        val selections = request.selections
        descriptor.selectionValidationError(
            selections = selections,
            hasChordFilters = request.chordFilters.isNotEmpty(),
            enabledOptions = request.enabledSchoenbergOptions(),
        )?.let { message ->
            return EnumerationResult(
                progressions = emptyList(),
                diagnostics = listOf(
                    Diagnostics.invalidSelection(exerciseId, message)
                ),
            )
        }
        val continuationChordCount = (request.windowLimit - 1).coerceAtLeast(1)
        val defaultBudget = com.mecon.theory.schoenberg.SchoenbergIntegratedTechTree.EnumerationBudget()
        val budget = defaultBudget.copy(
            maxResults = request.maxResults ?: defaultBudget.maxResults,
            maxVisitedNodes = request.maxVisitedNodes ?: defaultBudget.maxVisitedNodes,
        )
        return runCatching {
            EnumerationResult(
                progressions = SchoenbergCommonToneExercises.enumerateForExercise(
                    exerciseId = exerciseId,
                    key = request.key.toKey(),
                    continuationChordCount = continuationChordCount,
                    chordSelectors = request.chordFilters.map { it.toTargetSelector() },
                    cadenceOptions = com.mecon.theory.schoenberg.SchoenbergCadenceOptions(
                        includeDeceptiveCadence = request.includeDeceptiveCadence,
                        includeCadentialSixFour = request.includeCadentialSixFour,
                    ),
                    selections = selections,
                    budget = budget,
                    shouldContinue = shouldContinue,
                ).map { it.toProgression() },
                diagnostics = if (request.verify == VerifyLevelSpec.CONFIRMED) {
                    listOf(Diagnostics.confirmedDegraded)
                } else {
                    emptyList()
                },
            )
        }.getOrElse { error ->
            EnumerationResult(
                progressions = emptyList(),
                diagnostics = listOf(Diagnostics.constraintInvalid(error.message ?: "勋伯格练习枚举失败。")),
            )
        }
    }

    private fun SymbolicMatch.toProgression(): SymbolicProgression =
        SymbolicProgression(
            slots = slots.map {
                SymbolicChordSpecView(
                    degree = it.degree,
                    quality = it.quality.name,
                    position = it.positionName,
                    arity = it.arity.name,
                )
            },
            explanation = bindings.map { SceneBindingNoteView(it.facetIndex, it.slot, it.detail) },
            verified = false,
        )

    private fun SchoenbergSymbolicProgression.toProgression(): SymbolicProgression =
        SymbolicProgression(
            slots = slots.map {
                SymbolicChordSpecView(
                    degree = it.degree,
                    quality = it.quality.name,
                    position = it.seventhPosition?.name ?: it.position.name,
                    arity = it.arity.name,
                    rootAlteration = it.rootAlteration,
                    appliedToDegree = it.appliedToDegree,
                    secondaryFamily = it.secondaryFamily?.name,
                    modalOrigins = it.modalOrigins.map { mode -> mode.name },
                    rootlessDominantNinthChordId = it.rootlessDominantNinthChordId,
                    rootlessDominantNinthUsageId = it.rootlessDominantNinthUsageId,
                    omittedRootDegree = it.omittedRootDegree,
                    omittedRootAlteration = it.omittedRootAlteration,
                )
            },
            verified = false,
        )

    // ---- solve -------------------------------------------------------------

    override fun solve(request: SolveRequest): SolveResult {
        request.program?.let { return solveProgram(request, it) }
        val output = ExplorationRequestRunner.run(request.convenience!!)
        return SolveResult(output = output, diagnostics = output.structuredDiagnostics)
    }

    /** 约束程序路径：编译 spec → ConstraintProgram → 通用求解器 → CellOutput（复用三和弦装配器）。 */
    private fun solveProgram(request: SolveRequest, spec: ConstraintProgramSpec): SolveResult {
        val compiled = ConstraintProgramCompiler.compile(
            spec = spec,
            keySpec = request.key,
            policyId = request.policyId,
            search = request.search.toSearchConfig(),
        )
        val program = compiled.program
            ?: return programDiagnostics(request, spec, compiled.diagnostics)

        val solutions = ConstraintProgramSolver.solve(program)
        if (solutions.isEmpty()) {
            return programDiagnostics(request, spec, listOf(Diagnostics.noSolution))
        }
        val demonstrationRuleId = spec.slotConstraints
            .filterIsInstance<RuleAtSpec>()
            .firstOrNull { it.mode == RequirementModeSpec.REQUIRE_VIOLATION }
            ?.ruleId
        val output = CellOutput(
            fingerprint = programFingerprint(request, spec),
            candidates = solutions.map { it.toCandidate(request, demonstrationRuleId) },
        )
        return SolveResult(output = output, diagnostics = output.structuredDiagnostics)
    }

    private fun ConstraintSolution.toCandidate(
        request: SolveRequest,
        demonstrationRuleId: String?,
    ): OutputCandidate =
        OutputCandidate(
            score = ExplorationScoreAssembler.assembleTextbookChords(
                title = "约束程序写作",
                keySignature = request.key.toApiKeySignature(),
                voicings = voicings,
            ),
            totalScore = breakdown.total,
            findings = breakdown.findings.map { it.toStoredFinding(demonstrationRuleId) },
            breakdownEntries = breakdown.contributions.map {
                StoredScoreEntry(ruleId = it.ruleId.value, amount = it.amount, reason = it.reason)
            },
        )

    private fun programDiagnostics(
        request: SolveRequest,
        spec: ConstraintProgramSpec,
        diagnostics: List<SolverDiagnostic>,
    ): SolveResult {
        val output = CellOutput(
            fingerprint = programFingerprint(request, spec),
            candidates = emptyList(),
            diagnostics = diagnostics.map(DiagnosticMessages::resolve),
            structuredDiagnostics = diagnostics,
        )
        return SolveResult(output = output, diagnostics = diagnostics)
    }

    private fun programFingerprint(request: SolveRequest, spec: ConstraintProgramSpec): String =
        "exploration-constraint-v1:${spec.hashCode()}:${request.key.hashCode()}:${request.policyId.hashCode()}"

    // ---- refine（🚧 S2）----------------------------------------------------

    override fun refine(request: RefineRequest): SolveResult {
        val diagnostic = Diagnostics.refineNotAvailable
        val baseFingerprint = request.base.convenience
            ?.let { ExplorationRequestRunner.fingerprint(it) }
            ?: "exploration-refine-base"
        return SolveResult(
            output = CellOutput(
                fingerprint = baseFingerprint,
                candidates = emptyList(),
                diagnostics = listOf(DiagnosticMessages.resolve(diagnostic)),
                structuredDiagnostics = listOf(diagnostic),
            ),
            diagnostics = listOf(diagnostic),
        )
    }

    // ---- check（🚧 待全谱入口）--------------------------------------------

    override fun check(request: CheckRequest): CheckResult =
        CheckResult(
            findings = emptyList(),
            diagnostics = listOf(Diagnostics.checkNotAvailable),
        )
}
