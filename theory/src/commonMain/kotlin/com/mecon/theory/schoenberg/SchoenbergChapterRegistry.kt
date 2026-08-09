package com.mecon.theory.schoenberg

import com.mecon.theory.RuleId
import com.mecon.theory.Key
import com.mecon.theory.SearchConfig
import com.mecon.theory.ModulationKey
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.ConstraintProgramSolver
import com.mecon.theory.constraint.ConstraintSearchTrace
import com.mecon.theory.constraint.Constraint
import com.mecon.theory.constraint.ConstraintExpr
import com.mecon.theory.constraint.ConstraintPredicate
import com.mecon.theory.constraint.TargetSelector

data class SchoenbergProgramRequest(
    val exerciseId: String,
    val key: Key,
    val continuationChordCount: Int,
    val progression: SchoenbergSymbolicProgression?,
    val searchConfig: SearchConfig,
    val cadenceOptions: SchoenbergCadenceOptions,
    val sourceModulationKey: ModulationKey? = null,
    val selections: Map<String, List<String>> = emptyMap(),
) {
    fun selectedValue(key: String): String? = selections[key]?.singleOrNull()
}

data class SchoenbergEnumerationRequest(
    val exerciseId: String,
    val key: Key,
    val continuationChordCount: Int,
    val chordSelectors: List<TargetSelector>,
    val cadenceOptions: SchoenbergCadenceOptions,
    val selections: Map<String, List<String>>,
    val budget: SchoenbergIntegratedTechTree.EnumerationBudget =
        SchoenbergIntegratedTechTree.EnumerationBudget(),
    val shouldContinue: () -> Boolean = { true },
) {
    fun selectedValue(key: String): String? = selections[key]?.singleOrNull()
}

data class SchoenbergExerciseHandler(
    val id: String,
    val program: (SchoenbergProgramRequest) -> ConstraintProgram,
    val freePracticeProgram: (SchoenbergProgramRequest) -> ConstraintProgram = program,
    val enumerate: (SchoenbergEnumerationRequest) -> List<SchoenbergSymbolicProgression>,
    val freePracticeContribution: ((
        SchoenbergFreePracticeDiscoveryRequest,
        SchoenbergExerciseDescriptor,
    ) -> SchoenbergFreePracticeContribution)? = null,
)

data class SchoenbergExerciseRegistration(
    val definition: SchoenbergExerciseDescriptor,
    val handler: SchoenbergExerciseHandler,
)

data class SchoenbergChapterRegistration(
    val id: String,
    val exerciseIds: Set<String>,
    val ownedRulePrefixes: Set<String>,
    val knowledgeTags: Set<SchoenbergKnowledgeTag>,
)

/**
 * The descriptor list is the single curriculum registration source.
 *
 * Forbidden-transition diagnostics and exploration routing consume this registry instead of
 * maintaining their own chord-family or chapter switch statements.
 */
object SchoenbergChapterRegistry {
    val chapters: List<SchoenbergChapterRegistration>
        get() = SchoenbergCommonToneExercises.exerciseDescriptors
            .groupBy(SchoenbergExerciseDescriptor::chapterId)
            .map { (chapterId, descriptors) ->
                SchoenbergChapterRegistration(
                    id = chapterId,
                    exerciseIds = descriptors.mapTo(linkedSetOf(), SchoenbergExerciseDescriptor::exerciseId),
                    ownedRulePrefixes = descriptors
                        .flatMapTo(linkedSetOf(), SchoenbergExerciseDescriptor::ownedRulePrefixes),
                    knowledgeTags = descriptors
                        .flatMapTo(linkedSetOf(), SchoenbergExerciseDescriptor::requiredKnowledgeTags),
                )
            }

    fun chapterFor(ruleId: RuleId): SchoenbergChapterRegistration? =
        chapters
            .flatMap { chapter ->
                chapter.ownedRulePrefixes.map { prefix -> Triple(prefix.length, prefix, chapter) }
            }
            .filter { (_, prefix) ->
                ruleId.value == prefix || ruleId.value.startsWith("$prefix.")
            }
            .maxByOrNull { it.first }
            ?.third

    private val handlers: List<SchoenbergExerciseHandler> by lazy {
        listOf(
            handler(SchoenbergCommonToneExercises.FIRST_EXERCISE_ID)(
                program = { request ->
                    SchoenbergRootPositionConnections.program(
                        request.key,
                        request.continuationChordCount,
                        request.progression,
                        request.searchConfig,
                    )
                },
                enumerate = { request ->
                    SchoenbergRootPositionConnections.enumerate(
                        request.key,
                        request.continuationChordCount,
                    )
                },
            ),
            handler(SchoenbergCommonToneExercises.LEADING_TRIAD_EXERCISE_ID)(
                program = { request ->
                    SchoenbergLeadingTriadChapter.program(
                        request.key,
                        request.progression,
                        request.searchConfig,
                    )
                },
                enumerate = { SchoenbergLeadingTriadChapter.enumerate(it.key) },
            ),
            handler(SchoenbergCommonToneExercises.FIRST_INVERSION_EXERCISE_ID)(
                program = { request ->
                    SchoenbergFirstInversionChapter.program(
                        request.key,
                        request.progression,
                        request.searchConfig,
                    )
                },
                enumerate = { SchoenbergFirstInversionChapter.enumerate(it.key) },
            ),
            handler(SchoenbergCommonToneExercises.SECOND_INVERSION_EXERCISE_ID)(
                program = { request ->
                    SchoenbergSecondInversionChapter.program(
                        request.key,
                        request.progression,
                        request.searchConfig,
                    )
                },
                enumerate = { SchoenbergSecondInversionChapter.enumerate(it.key) },
            ),
            handler(SchoenbergCommonToneExercises.SEVENTH_CHORD_EXERCISE_ID)(
                program = { request ->
                    SchoenbergSeventhChordChapter.program(
                        request.key,
                        request.progression,
                        request.searchConfig,
                    )
                },
                enumerate = { SchoenbergSeventhChordChapter.enumerate(it.key) },
            ),
            handler(SchoenbergExerciseHandlerIds.NO_COMMON_TONE)(
                program = { request ->
                    SchoenbergNoCommonToneChapter.program(
                        request.key,
                        request.continuationChordCount,
                        request.progression,
                        request.searchConfig,
                    )
                },
                enumerate = { request ->
                    SchoenbergNoCommonToneChapter.enumerate(
                        request.key,
                        request.continuationChordCount,
                    )
                },
            ),
            handler(SchoenbergCommonToneExercises.ROOT_MOTION_AND_REPETITION_EXERCISE_ID)(
                program = { request ->
                    SchoenbergRootMotionAndRepetitionChapter.program(
                        request.key,
                        request.continuationChordCount,
                        request.progression,
                        request.searchConfig,
                    )
                },
                enumerate = { request ->
                    SchoenbergRootMotionAndRepetitionChapter.enumerate(
                        request.key,
                        request.continuationChordCount,
                        request.chordSelectors,
                    )
                },
            ),
            handler(SchoenbergCommonToneExercises.CADENCE_EXERCISE_ID)(
                program = { request ->
                    SchoenbergCadenceChapter.program(
                        request.key,
                        request.continuationChordCount,
                        request.cadenceOptions,
                        request.progression,
                        request.searchConfig,
                    )
                },
                freePracticeProgram = { request ->
                    SchoenbergCadenceChapter.freePracticeProgram(
                        request.key,
                        request.continuationChordCount,
                        request.cadenceOptions,
                        request.searchConfig,
                    )
                },
                enumerate = { request ->
                    SchoenbergCadenceChapter.enumerate(
                        request.key,
                        request.continuationChordCount,
                        request.cadenceOptions,
                        request.chordSelectors,
                    )
                },
                freePracticeContribution = ::discoverCadenceFreePracticeContribution,
            ),
            handler(SchoenbergCommonToneExercises.FREER_SEVENTH_LEADING_EXERCISE_ID)(
                program = { request ->
                    SchoenbergFreerDissonanceChapter.program(
                        request.key,
                        request.continuationChordCount,
                        request.cadenceOptions,
                        request.progression,
                        request.searchConfig,
                    )
                },
                enumerate = { request ->
                    SchoenbergFreerDissonanceChapter.enumerate(
                        request.key,
                        request.continuationChordCount,
                        request.cadenceOptions,
                        request.chordSelectors,
                    )
                },
            ),
            handler(SchoenbergCommonToneExercises.DIMINISHED_SEVENTH_EXERCISE_ID)(
                program = { request ->
                    SchoenbergDiminishedSeventhChapter.program(
                        request.key,
                        request.progression,
                        request.searchConfig,
                    )
                },
                enumerate = { request ->
                    SchoenbergDiminishedSeventhChapter.enumerate(
                        request.key,
                        request.selectedValue(SchoenbergExerciseSelectionKeys.DIMINISHED_SEVENTH_CHORD),
                        request.selectedValue(SchoenbergExerciseSelectionKeys.DIMINISHED_SEVENTH_USAGE),
                    )
                },
                freePracticeContribution = ::discoverDiminishedSeventhFreePracticeContribution,
            ),
            handler(SchoenbergCommonToneExercises.AUGMENTED_SIXTH_EXERCISE_ID)(
                program = { request ->
                    SchoenbergAugmentedSixthChapter.program(
                        request.key,
                        request.progression,
                        request.searchConfig,
                    )
                },
                enumerate = { request ->
                    SchoenbergAugmentedSixthChapter.enumerate(request.key)
                },
                freePracticeContribution = ::discoverAugmentedSixthFreePracticeContribution,
            ),
            handler(SchoenbergCommonToneExercises.MINOR_SUBDOMINANT_CONNECTION_EXERCISE_ID)(
                program = { request ->
                    SchoenbergMinorSubdominantChapter.connectionProgram(
                        request.key,
                        request.progression,
                        request.searchConfig,
                    )
                },
                enumerate = { SchoenbergMinorSubdominantChapter.enumerateConnections(it.key) },
            ),
            handler(SchoenbergCommonToneExercises.NEAPOLITAN_CADENCE_EXERCISE_ID)(
                program = { request ->
                    SchoenbergMinorSubdominantChapter.neapolitanCadenceProgram(
                        request.key,
                        request.progression,
                        request.searchConfig,
                    )
                },
                enumerate = { SchoenbergMinorSubdominantChapter.enumerateNeapolitanCadences(it.key) },
                freePracticeContribution = ::discoverNeapolitanFreePracticeContribution,
            ),
            handler(SchoenbergCommonToneExercises.ANALOGOUS_NEAPOLITAN_EXERCISE_ID)(
                program = { request ->
                    SchoenbergMinorSubdominantChapter.analogousNeapolitanProgram(
                        request.key,
                        request.progression,
                        request.searchConfig,
                    )
                },
                enumerate = { SchoenbergMinorSubdominantChapter.enumerateAnalogousNeapolitanConnections(it.key) },
                freePracticeContribution = ::discoverAnalogousNeapolitanFreePracticeContribution,
            ),
            handler(SchoenbergCommonToneExercises.DISTANT_MODULATION_EXERCISE_ID)(
                program = { request ->
                    val source = request.sourceModulationKey
                        ?: ModulationKey.circleOfFifths.firstOrNull { it.key == request.key }
                        ?: error("Cannot infer modulation spelling for ${request.key}")
                    val pathId = request.selectedValue(
                        SchoenbergExerciseSelectionKeys.DISTANT_MODULATION_PATH
                    )?.let(::TonalPathId) ?: SchoenbergDistantTonalPaths.THREE_SHARPS.id
                    val level = request.selectedValue(
                        SchoenbergExerciseSelectionKeys.TONAL_CONFIRMATION
                    )?.let(TonalConfirmationLevel::valueOf) ?: TonalConfirmationLevel.LIGHT
                    SchoenbergDistantModulationChapter.compile(
                        DistantModulationExerciseRequest(
                            sourceKey = source,
                            pathId = pathId,
                            confirmationLevel = level,
                            searchConfig = request.searchConfig,
                        )
                    ).program
                },
                enumerate = { emptyList() },
                freePracticeContribution = ::discoverDistantModulationFreePracticeContribution,
            ),
            handler(SchoenbergExerciseHandlerIds.INTEGRATED)(
                program = ::integratedProgram,
                enumerate = { request ->
                    SchoenbergIntegratedTechTree.enumerateForStage(
                        exerciseId = request.exerciseId,
                        key = request.key,
                        continuationChordCount = request.continuationChordCount,
                        chordSelectors = request.chordSelectors,
                        selections = request.selections,
                        budget = request.budget,
                        shouldContinue = request.shouldContinue,
                    )
                },
                freePracticeContribution = ::discoverIntegratedFreePracticeContribution,
            ),
        )
    }

    val exerciseRegistrations: List<SchoenbergExerciseRegistration> by lazy {
        SchoenbergCommonToneExercises.exerciseDescriptors.map { definition ->
            SchoenbergExerciseRegistration(
                definition = definition,
                handler = handlerForDefinition(definition),
            )
        }
    }

    val registeredExerciseIds: Set<String>
        get() = exerciseRegistrations.mapTo(linkedSetOf()) { it.definition.exerciseId }

    fun program(request: SchoenbergProgramRequest): ConstraintProgram =
        handlerFor(request.exerciseId).program(request)

    /** Chapter-owned source-program compilation for persisted free-practice provenance. */
    fun freePracticeProgram(request: SchoenbergProgramRequest): ConstraintProgram =
        handlerFor(request.exerciseId).freePracticeProgram(request)

    fun enumerate(request: SchoenbergEnumerationRequest): List<SchoenbergSymbolicProgression> =
        handlerFor(request.exerciseId).enumerate(request)

    private fun handlerFor(exerciseId: String): SchoenbergExerciseHandler =
        exerciseRegistrations.firstOrNull { it.definition.exerciseId == exerciseId }?.handler
            ?: error("No Schoenberg exercise handler registered for $exerciseId")

    private fun handlerForDefinition(
        definition: SchoenbergExerciseDescriptor,
    ): SchoenbergExerciseHandler =
        handlersById[definition.handlerId]
            ?: error(
                "No Schoenberg exercise handler ${definition.handlerId} " +
                    "registered for ${definition.exerciseId}"
            )

    private class HandlerBuilder(
        private val id: String,
    ) {
        operator fun invoke(
            program: (SchoenbergProgramRequest) -> ConstraintProgram,
            freePracticeProgram: ((SchoenbergProgramRequest) -> ConstraintProgram)? = null,
            enumerate: (SchoenbergEnumerationRequest) -> List<SchoenbergSymbolicProgression>,
            freePracticeContribution: ((
                SchoenbergFreePracticeDiscoveryRequest,
                SchoenbergExerciseDescriptor,
            ) -> SchoenbergFreePracticeContribution)? = null,
        ): SchoenbergExerciseHandler = SchoenbergExerciseHandler(
            id = id,
            program = program,
            freePracticeProgram = freePracticeProgram ?: program,
            enumerate = enumerate,
            freePracticeContribution = freePracticeContribution,
        )
    }

    private fun handler(id: String): HandlerBuilder = HandlerBuilder(id)

    private val handlersById: Map<String, SchoenbergExerciseHandler> by lazy {
        handlers.associateBy(SchoenbergExerciseHandler::id).also {
            require(it.size == handlers.size) { "Schoenberg handler ids must be unique" }
        }
    }

    private fun integratedProgram(request: SchoenbergProgramRequest): ConstraintProgram =
        SchoenbergIntegratedTechTree.programForStage(
            request.exerciseId,
            request.key,
            request.continuationChordCount,
            request.progression,
            request.searchConfig,
        )
}

data class SchoenbergForbiddenTransitionDiagnosis(
    val writable: Boolean,
    val implicatedChapterIds: Set<String>,
    val activeRuleIds: Set<RuleId>,
    val trace: ConstraintSearchTrace,
)

object SchoenbergForbiddenTransitionAnalyzer {
    /**
     * Attributes an unwritable pair by chapter ablation: a chapter is implicated when removing
     * its registered constraints makes the same pair writable.
     */
    fun diagnose(program: ConstraintProgram): SchoenbergForbiddenTransitionDiagnosis {
        val trace = ConstraintProgramSolver.trace(program)
        val activeRuleIds = program.constraints
            .filter { it.appliesTo(program) }
            .mapNotNullTo(linkedSetOf()) { it.ruleId }
        if (trace.solutions.isNotEmpty()) {
            return SchoenbergForbiddenTransitionDiagnosis(
                writable = true,
                implicatedChapterIds = emptySet(),
                activeRuleIds = activeRuleIds,
                trace = trace,
            )
        }
        val activeChapters = activeRuleIds
            .mapNotNull(SchoenbergChapterRegistry::chapterFor)
            .distinctBy(SchoenbergChapterRegistration::id)
        val restoredByAblation = activeChapters.mapNotNullTo(linkedSetOf()) { chapter ->
            val reduced = program.withoutChapters(listOf(chapter))
            chapter.id.takeIf { ConstraintProgramSolver.solve(reduced).isNotEmpty() }
        }
        val implicated = when {
            restoredByAblation.isNotEmpty() -> restoredByAblation
            activeChapters.isNotEmpty() &&
                ConstraintProgramSolver.solve(program.withoutChapters(activeChapters)).isNotEmpty() ->
                activeChapters.mapTo(linkedSetOf(), SchoenbergChapterRegistration::id)
            else -> linkedSetOf(GENERAL_FOUR_PART_CHAPTER_ID)
        }
        return SchoenbergForbiddenTransitionDiagnosis(
            writable = false,
            implicatedChapterIds = implicated,
            activeRuleIds = activeRuleIds,
            trace = trace,
        )
    }

    const val GENERAL_FOUR_PART_CHAPTER_ID: String = "general-four-part-writing"
}

private fun ConstraintProgram.withoutChapters(
    chapters: Collection<SchoenbergChapterRegistration>,
): ConstraintProgram {
    val prefixes = chapters.flatMapTo(linkedSetOf(), SchoenbergChapterRegistration::ownedRulePrefixes)
    return copy(
        constraints = constraints.filterNot { constraint ->
            constraint.ruleId?.let { ruleId ->
                prefixes.any { prefix ->
                    ruleId.value == prefix || ruleId.value.startsWith("$prefix.")
                }
            } == true
        }
    )
}

private fun Constraint.appliesTo(program: ConstraintProgram): Boolean {
    val predicate = (expr as? ConstraintExpr.Atom)?.predicate ?: return true
    fun targetsIn(window: com.mecon.theory.SlotWindow) =
        program.slotDomains
            .filterIndexed { index, _ -> window.contains(index) }
            .flatMap { it.targets }
    return when (predicate) {
        is ConstraintPredicate.ToneCompleteness ->
            targetsIn(predicate.requirement.window).any(predicate.requirement.selector::matches)
        is ConstraintPredicate.ToneDoubled ->
            program.slotDomains[predicate.requirement.slot].targets.any(predicate.requirement.selector::matches)
        is ConstraintPredicate.ToneNotDoubled ->
            program.slotDomains[predicate.requirement.slot].targets.any(predicate.requirement.selector::matches)
        is ConstraintPredicate.NeighborTone -> {
            val requirement = predicate.requirement
            val sourceTargets = requirement.sourceSlot?.let { program.slotDomains[it].targets }
                ?: targetsIn(requirement.window)
            sourceTargets.any(requirement.sourceSelector::matches)
        }
        is ConstraintPredicate.TargetMatches ->
            targetsIn(predicate.requirement.window).any(predicate.requirement.selector::matches)
        else -> true
    }
}
