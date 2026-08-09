package com.mecon.theory.schoenberg

import com.mecon.api.primitive.PitchClass
import com.mecon.theory.BuiltInChordDefinitions
import com.mecon.theory.ChordArity
import com.mecon.theory.Key
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationChordId
import com.mecon.theory.ModulationCommonChordCatalog
import com.mecon.theory.ModulationKey
import com.mecon.theory.NaturalTriad
import com.mecon.theory.NaturalTriads
import com.mecon.theory.RuleId
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.TonalContext
import com.mecon.theory.TonalPlan
import com.mecon.theory.TonalSpan
import com.mecon.theory.VoicePlan
import com.mecon.theory.constraint.Constraint
import com.mecon.theory.constraint.ConstraintBranch
import com.mecon.theory.constraint.ConstraintExplanation
import com.mecon.theory.constraint.ConstraintExpr
import com.mecon.theory.constraint.ConstraintModality
import com.mecon.theory.constraint.ConstraintPredicate
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.ConstraintScope
import com.mecon.theory.constraint.ChordTarget
import com.mecon.theory.constraint.FreeHarmonyRequest
import com.mecon.theory.constraint.FreeHarmonySolver
import com.mecon.theory.constraint.SlotDomain
import com.mecon.theory.constraint.TargetFeatureBonusRequirement
import com.mecon.theory.constraint.TargetSelector
import com.mecon.theory.constraint.toInterpretedTargets
import com.mecon.theory.harmony.ChordBuilder
import com.mecon.theory.harmony.ChordCatalogCollector
import com.mecon.theory.harmony.ChordInterpretation
import com.mecon.theory.harmony.ChordRecipeId
import com.mecon.theory.harmony.ConstructedChord
import com.mecon.theory.harmony.ConstructionTrace
import com.mecon.theory.harmony.FunctionalChordSymbol
import com.mecon.theory.harmony.HarmonicFunction
import com.mecon.theory.harmony.InterpretationId
import com.mecon.theory.harmony.InterpretationTag
import com.mecon.theory.harmony.InterpretationTrace
import com.mecon.theory.harmony.TonalLens
import com.mecon.theory.textbook.TextbookTriadPosition

enum class ModulationSolverPreset {
    FREE,
    SCHOENBERG,
}

data class ModulationExerciseRequest(
    val sourceKey: ModulationKey,
    val targetKey: ModulationKey,
    val pivotChord: ModulationChordId,
    /** Number of chords in the source key before the common chord. */
    val sourceChordCount: Int = 2,
    /** Number of chords in the target key after the common chord, including the final V-I cadence. */
    val targetChordCount: Int = 4,
    val solverPreset: ModulationSolverPreset = ModulationSolverPreset.SCHOENBERG,
    val voicePlan: VoicePlan = VoicePlan.standardFourPart(),
    val searchConfig: SearchConfig = SearchConfig(maxResults = 4, beamWidth = 192),
) {
    init {
        require(sourceKey != targetKey) { "Source and target keys must differ" }
        require(sourceChordCount >= 1) { "At least one source-key chord is required" }
        require(targetChordCount >= 2) { "Target-key chord count must leave room for a V-I cadence" }
    }
}

data class ModulationExerciseProgram(
    val program: ConstraintProgram,
    val pivotSlot: Int,
    val targetStartSlot: Int,
    val targetKey: ModulationKey,
)

/**
 * Schoenberg's first modulation exercise is compiled into the shared free-harmony constraint
 * runtime: approach a common chord in the source key, reinterpret it, introduce a characteristic
 * destination-key tone, and close with an authentic cadence in the destination key.
 */
object SchoenbergModulation {
    val COMMON_CHORD_RULE_ID = RuleId("schoenberg.modulation.common-chord")
    val CHARACTERISTIC_TONE_RULE_ID = RuleId("schoenberg.modulation.characteristic-tone")
    val TARGET_DOMINANT_RULE_ID = RuleId("schoenberg.modulation.target-dominant")
    val TARGET_TONIC_RULE_ID = RuleId("schoenberg.modulation.target-tonic")

    fun compile(request: ModulationExerciseRequest): ModulationExerciseProgram {
        val sourceContext = request.sourceKey.tonalContext("modulation.source")
        val targetContext = request.targetKey.tonalContext("modulation.target")
        val sourceTriad = matchingTriad(request.sourceKey, request.pivotChord)
        val targetTriad = matchingTriad(request.targetKey, request.pivotChord)
        val pivotSlot = request.sourceChordCount
        val targetStart = pivotSlot + 1
        val lastSlot = pivotSlot + request.targetChordCount
        val slotCount = lastSlot + 1

        val sourceConstructions = chordConstructions(
            key = request.sourceKey,
            context = sourceContext,
            includeIntegratedVocabulary = request.solverPreset == ModulationSolverPreset.SCHOENBERG,
        )
        val targetConstructions = chordConstructions(
            key = request.targetKey,
            context = targetContext,
            includeIntegratedVocabulary = request.solverPreset == ModulationSolverPreset.SCHOENBERG,
        )
        val pivotConstruction = pivotConstruction(
            request = request,
            sourceContext = sourceContext,
            targetContext = targetContext,
            sourceTriad = sourceTriad,
            targetTriad = targetTriad,
        )
        val catalog = ChordCatalogCollector.collect(
            sourceConstructions + targetConstructions + pivotConstruction
        )
        val allTargets = catalog.toInterpretedTargets(request.sourceKey.key)
        val pivotTarget = allTargets.first {
            it.interpretation.id == pivotConstruction.interpretation.id && it.inversion == 0
        }
        val sourceTargets = allTargets.filter {
            it.interpretation.lens.contextId == sourceContext.id &&
                it.interpretation.id != pivotConstruction.interpretation.id
        }
        val targetTargets = allTargets.filter {
            it.interpretation.lens.contextId == targetContext.id &&
                it.interpretation.id != pivotConstruction.interpretation.id
        }
        val sourceTonic = sourceTargets.first {
            it.degree == 1 && it.arity == ChordArity.TRIAD && it.inversion == 0
        }
        val dominantSelector = when (request.solverPreset) {
            ModulationSolverPreset.FREE -> TargetSelector(degrees = setOf(5))
            ModulationSolverPreset.SCHOENBERG ->
                SchoenbergCadenceChapter.authenticDominantSelector(
                    minor = request.targetKey.mode == KeySignatureMode.MINOR,
                )
        }
        val alteredPitchClasses = targetTargets
            .flatMap { it.sonority.pitchClasses }
            .filterNot { it in request.sourceKey.key.scale.pitchClasses }
            .toSet()
        require(alteredPitchClasses.isNotEmpty()) {
            "Target key must contribute at least one pitch class absent from the source key"
        }

        val base = FreeHarmonySolver.compile(
            FreeHarmonyRequest(
                key = request.sourceKey.key,
                tonalPlan = TonalPlan(
                    listOf(
                        TonalSpan(SlotWindow(0, pivotSlot), sourceContext),
                        TonalSpan(SlotWindow(pivotSlot, lastSlot), targetContext),
                    )
                ),
                slotCount = slotCount,
                vocabulary = allTargets.filter {
                    it.interpretation.id != pivotConstruction.interpretation.id
                } + pivotTarget,
                voicePlan = request.voicePlan,
                fixedTargetIdentityBySlot = mapOf(
                    0 to sourceTonic.identityKey(),
                    pivotSlot to pivotTarget.identityKey(),
                ),
                additionalConstraints = modulationConstraints(
                    pivotSlot = pivotSlot,
                    lastSlot = lastSlot,
                    pivotPitchClasses = pivotTarget.sonority.pitchClasses.toSet(),
                    alteredPitchClasses = alteredPitchClasses,
                    dominantSelector = dominantSelector,
                ),
                searchConfig = request.searchConfig,
            )
        )
        val pivotIdentity = pivotTarget.identityKey()
        val cadenceDomains = base.slotDomains.mapIndexed { slot, domain ->
                if (slot == pivotSlot) {
                    domain
                } else {
                    val regionTargets = domain.targets.filter {
                        it.identityKey() != pivotIdentity
                    }
                    when (slot) {
                        lastSlot - 1 -> SlotDomain(regionTargets.filter(dominantSelector::matches))
                        lastSlot -> SlotDomain(
                            regionTargets.filter {
                                it.degree == 1 &&
                                    it.arity == ChordArity.TRIAD &&
                                    it.inversion == 0
                            }
                        )
                        else -> SlotDomain(regionTargets)
                    }
                }
            }
        val cadenceConstrainedBase = base.copy(
            slotDomains = cadenceDomains,
            slots = base.slots.zip(cadenceDomains) { slot, domain -> slot.copy(domain = domain) },
        )
        val program = when (request.solverPreset) {
            ModulationSolverPreset.FREE -> cadenceConstrainedBase
            ModulationSolverPreset.SCHOENBERG -> {
                val sourceWindow = SlotWindow(0, pivotSlot)
                val targetWindow = SlotWindow(targetStart, lastSlot)
                val sourceChapter = integratedChapterTemplate(
                    key = request.sourceKey.key,
                    length = slotCount,
                    searchConfig = request.searchConfig,
                )
                val targetChapter = integratedChapterTemplate(
                    key = request.targetKey.key,
                    length = slotCount,
                    searchConfig = request.searchConfig,
                )
                cadenceConstrainedBase.copy(
                    ruleProfile = SchoenbergCommonToneExercises.SCHOENBERG_PROFILE,
                    constraints = cadenceConstrainedBase.constraints +
                        sourceChapter.constraints.mapNotNull {
                            projectChapterConstraint(it, sourceWindow, request.sourceKey.key)
                        } +
                        targetChapter.constraints.mapNotNull {
                            projectChapterConstraint(it, targetWindow, request.targetKey.key)
                        } +
                        stableRegionRules(sourceWindow) +
                        stableRegionRules(targetWindow),
                    ruleModules = emptyList(),
                    includeDerivedTextbookConstraints = false,
                )
            }
        }
        return ModulationExerciseProgram(
            program = program,
            pivotSlot = pivotSlot,
            targetStartSlot = targetStart,
            targetKey = request.targetKey,
        )
    }

    /**
     * Enumerates a small, ranked set of target-only progressions before four-part realization.
     * This keeps long modulation exercises from multiplying every open chord target by every
     * possible SATB voicing in one DFS.
     */
    fun compileCandidates(
        request: ModulationExerciseRequest,
        maxPrograms: Int = 96,
    ): List<ModulationExerciseProgram> {
        require(maxPrograms > 0) { "maxPrograms must be positive" }
        val open = compile(request)
        if (request.solverPreset == ModulationSolverPreset.FREE) return listOf(open)

        val sourceSequences = enumerateRegionVariants(
            program = open.program,
            startSlot = 0,
            endSlot = open.pivotSlot,
            key = request.sourceKey.key,
        )
        val targetPivot = matchingTriad(request.targetKey, request.pivotChord)
            .toSymbolic(TextbookTriadPosition.ROOT_POSITION)
        val targetSequences = enumerateRegionVariants(
            program = open.program,
            startSlot = open.targetStartSlot,
            endSlot = open.program.length - 1,
            key = request.targetKey.key,
            forbiddenPredecessor = targetPivot,
        )
        if (sourceSequences.isEmpty() || targetSequences.isEmpty()) return emptyList()

        val alteredPitchClasses = open.program.slotDomains
            .drop(open.targetStartSlot)
            .flatMap { it.targets }
            .flatMap { it.sonority.pitchClasses }
            .filterNot { it in request.sourceKey.key.scale.pitchClasses }
            .toSet()
        val ranked = sourceSequences
            .flatMap { source ->
                targetSequences.mapNotNull { target ->
                    if (target.none { chord -> chord.sonority.pitchClasses.any { it in alteredPitchClasses } }) {
                        return@mapNotNull null
                    }
                    val targets = source + target
                    val fixedDomains = targets.map { SlotDomain(listOf(it)) }
                    val fixedForPreflight = open.program.copy(
                        slotDomains = fixedDomains,
                        slots = open.program.slots.zip(fixedDomains) { slot, domain ->
                            slot.copy(domain = domain)
                        },
                    )
                    if (com.mecon.theory.constraint.ConstraintProgramSolver
                            .targetOnlyHardViolations(fixedForPreflight)
                            .isNotEmpty()
                    ) {
                        null
                    } else {
                        val realizationProgram = fixedForPreflight.copy(
                            searchConfig = fixedForPreflight.searchConfig.copy(
                                beamWidth = minOf(
                                    fixedForPreflight.searchConfig.beamWidth,
                                    MODULATION_REALIZATION_BEAM_WIDTH,
                                ),
                            ),
                        )
                        ModulationExerciseProgram(
                            program = realizationProgram,
                            pivotSlot = open.pivotSlot,
                            targetStartSlot = open.targetStartSlot,
                            targetKey = open.targetKey,
                        )
                    }
                }
            }
            .sortedBy { candidate ->
                sequenceScore(candidate.program, 0, candidate.pivotSlot) +
                    sequenceScore(
                        candidate.program,
                        candidate.targetStartSlot,
                        candidate.program.length - 1,
                    ) +
                    chapterFeatureBalancePenalty(candidate.program)
            }
        return diversifyChapterFeatures(ranked).take(maxPrograms)
    }

    private fun diversifyChapterFeatures(
        candidates: List<ModulationExerciseProgram>,
    ): List<ModulationExerciseProgram> {
        fun ModulationExerciseProgram.targets(): List<ChordTarget> =
            program.slotDomains.map { it.targets.first() }

        val buckets = listOf(
            candidates.filter { candidate ->
                candidate.program.slotDomains[candidate.program.length - 2]
                    .targets.first().arity == ChordArity.SEVENTH
            },
            candidates.filter { candidate ->
                val targets = candidate.targets()
                targets[candidate.program.length - 2].arity != ChordArity.SEVENTH &&
                    targets.any { it.arity == ChordArity.SEVENTH }
            },
            candidates.filter { candidate ->
                val targets = candidate.targets()
                targets.none { it.arity == ChordArity.SEVENTH } &&
                    targets.any { it.inversion != 0 }
            },
            candidates.filter { candidate ->
                candidate.targets().none {
                    it.arity == ChordArity.SEVENTH || it.inversion != 0
                }
            },
        )
        return buildList {
            repeat(buckets.maxOfOrNull { it.size } ?: 0) { rank ->
                buckets.forEach { bucket ->
                    bucket.getOrNull(rank)?.let(::add)
                }
            }
        }.distinct()
    }

    private fun enumerateRegionVariants(
        program: ConstraintProgram,
        startSlot: Int,
        endSlot: Int,
        key: Key,
        forbiddenPredecessor: SchoenbergSymbolicChord? = null,
    ): List<List<ChordTarget>> {
        val featureSlots = (startSlot..endSlot).filter { slot ->
            program.slotDomains[slot].targets.any {
                it.arity == ChordArity.SEVENTH || it.inversion != 0
            }
        }
        val simple = enumerateStableRegion(
            program = program,
            startSlot = startSlot,
            endSlot = endSlot,
            limit = SIMPLE_REGION_SEQUENCE_LIMIT,
            key = key,
            forbiddenPredecessor = forbiddenPredecessor,
            featureSlot = null,
        )
        val featuredBySlot = featureSlots.map { featureSlot ->
            enumerateStableRegion(
                program = program,
                startSlot = startSlot,
                endSlot = endSlot,
                limit = FEATURE_REGION_SEQUENCE_LIMIT,
                key = key,
                forbiddenPredecessor = forbiddenPredecessor,
                featureSlot = featureSlot,
            )
        }
        return buildList {
            addAll(simple)
            repeat(FEATURE_REGION_SEQUENCE_LIMIT) { rank ->
                featuredBySlot.forEach { variants ->
                    variants.getOrNull(rank)?.let(::add)
                }
            }
        }
            .distinctBy { sequence -> sequence.map { it.identityKey() } }
            .take(REGION_SEQUENCE_LIMIT)
    }

    private fun enumerateStableRegion(
        program: ConstraintProgram,
        startSlot: Int,
        endSlot: Int,
        limit: Int,
        key: Key,
        featureSlot: Int?,
        forbiddenPredecessor: SchoenbergSymbolicChord? = null,
    ): List<List<ChordTarget>> {
        require(startSlot in 0 until program.length && endSlot in startSlot until program.length)
        val triads = exerciseTriads(key, includeLeadingTriad = true)
        val regionLength = endSlot - startSlot + 1
        var frontier = listOf(emptyList<ChordTarget>())
        for (nextSlot in startSlot..endSlot) {
            frontier = frontier
                .flatMap { prefix ->
                    program.slotDomains[nextSlot].targets
                        .asSequence()
                        .distinctBy { listOf(it.degree, it.quality, it.arity, it.inversion) }
                        .filter { candidate ->
                            if (nextSlot == featureSlot) {
                                candidate.arity == ChordArity.SEVENTH || candidate.inversion != 0
                            } else {
                                candidate.arity == ChordArity.TRIAD && candidate.inversion == 0
                            }
                        }
                        .filter { candidate ->
                            allowsStablePrefix(
                                prefix = prefix,
                                candidate = candidate,
                                key = key,
                                triads = triads,
                                regionLength = regionLength,
                                forbiddenPredecessor = forbiddenPredecessor,
                            )
                        }
                        .map { candidate -> prefix + candidate }
                        .toList()
                }
                .distinctBy { sequence -> sequence.map { it.identityKey() } }
                .sortedWith(
                    compareBy<List<ChordTarget>> { sequence ->
                        SchoenbergRootMotionAndRepetitionChapter.ROOT_PROGRESSION_SCORING_POLICY
                            .score(sequence.map { it.degree })
                            .total
                    }.thenBy { sequence ->
                        sequence.count { it.arity == ChordArity.SEVENTH }
                    }.thenBy { sequence ->
                        sequence.count { it.inversion != 0 }
                    }
                )
                .take(REGION_ENUMERATION_BEAM_WIDTH)
            if (frontier.isEmpty()) break
        }
        return frontier
            .filter { sequence ->
                sequence.size < 2 ||
                    SchoenbergRootMotionAndRepetitionChapter.followsDirectionPolicy(
                        sequence.map(ChordTarget::toSchoenbergSymbolicChord)
                    )
            }
            .take(limit)
    }

    private fun allowsStablePrefix(
        prefix: List<ChordTarget>,
        candidate: ChordTarget,
        key: Key,
        triads: List<NaturalTriad>,
        regionLength: Int,
        forbiddenPredecessor: SchoenbergSymbolicChord?,
    ): Boolean {
        val candidateSymbolic = candidate.toSchoenbergSymbolicChord()
        val symbolicPrefix = prefix.map(ChordTarget::toSchoenbergSymbolicChord)
        val effectivePrefix = if (symbolicPrefix.isEmpty() && forbiddenPredecessor != null) {
            listOf(forbiddenPredecessor)
        } else {
            symbolicPrefix
        }
        if (effectivePrefix.isEmpty()) return true
        val crossesPivot = symbolicPrefix.isEmpty() && forbiddenPredecessor != null
        return SchoenbergIntegratedTechTree.allowsIntegratedStep(
            prefix = effectivePrefix,
            after = candidateSymbolic,
            triads = triads,
            key = key,
            includeLeadingTriad = true,
            minor = key.mode == com.mecon.theory.Mode.AEOLIAN,
            requireAdjacentCommonTone = false,
            applyRootMotionDirection = !crossesPivot,
            applyHarmonicRepetitionPolicy = !crossesPivot,
            dissonanceTreatment = SchoenbergDissonanceTreatment.STRICT,
            sequencePolicy = null,
            totalLength = if (crossesPivot) 2 else regionLength,
        )
    }

    private fun sequenceScore(
        program: ConstraintProgram,
        startSlot: Int,
        endSlot: Int,
    ): Double =
        SchoenbergRootMotionAndRepetitionChapter.ROOT_PROGRESSION_SCORING_POLICY
            .score((startSlot..endSlot).map { program.slotDomains[it].targets.first().degree })
            .total

    private fun chapterFeatureBalancePenalty(program: ConstraintProgram): Double {
        val targets = program.slotDomains.map { it.targets.first() }
        val seventhCount = targets.count { it.arity == ChordArity.SEVENTH }
        val inversionCount = targets.count { it.inversion != 0 }
        val usesCadentialSeventh =
            targets[program.length - 2].arity == ChordArity.SEVENTH
        return kotlin.math.abs(seventhCount - 1) * SEVENTH_BALANCE_WEIGHT +
            kotlin.math.abs(inversionCount - 1) * INVERSION_BALANCE_WEIGHT -
            if (usesCadentialSeventh) CADENTIAL_SEVENTH_BONUS else 0.0
    }

    private fun stableRegionRules(window: SlotWindow): List<Constraint> =
        if ((window.end ?: window.start) > window.start) {
            SchoenbergRootMotionAndRepetitionChapter.constraints(window)
        } else {
            emptyList()
        }

    private fun integratedChapterTemplate(
        key: Key,
        length: Int,
        searchConfig: SearchConfig,
    ): ConstraintProgram =
        SchoenbergIntegratedTechTree.program(
            key = key,
            continuationChordCount = length - 1,
            treatmentIds = SchoenbergHarmonicTreatments.integratedDiatonicTreatments,
            searchConfig = searchConfig,
            requireAdjacentCommonTone = false,
            dissonanceTreatment = SchoenbergDissonanceTreatment.STRICT,
        )

    /**
     * Rebinds one integrated-chapter constraint to a stable modulation region. Templates are
     * created at the full exercise length, so slot-addressed requirements already use global
     * indices; window requirements are narrowed here and neighbor scale degrees retain their
     * own region key.
     */
    private fun projectChapterConstraint(
        constraint: Constraint,
        window: SlotWindow,
        tonalKey: Key,
    ): Constraint? {
        val atom = constraint.expr as? ConstraintExpr.Atom
            ?: return constraint.copy(scope = constraint.scope.copy(window = window))
        val predicate = when (val predicate = atom.predicate) {
            is ConstraintPredicate.ToneCompleteness ->
                predicate.copy(requirement = predicate.requirement.copy(window = window))
            is ConstraintPredicate.ToneDoubled -> {
                if (!window.contains(predicate.requirement.slot)) return null
                predicate
            }
            is ConstraintPredicate.ToneNotDoubled -> {
                if (!window.contains(predicate.requirement.slot)) return null
                predicate
            }
            is ConstraintPredicate.ScaleDegreeNotDoubled -> {
                if (!window.contains(predicate.requirement.slot)) return null
                predicate
            }
            is ConstraintPredicate.Spacing ->
                predicate.copy(requirement = predicate.requirement.copy(window = window))
            is ConstraintPredicate.DistinctIdentities ->
                predicate.copy(requirement = predicate.requirement.copy(window = window))
            is ConstraintPredicate.CommonToneWithPrevious ->
                predicate.copy(requirement = predicate.requirement.copy(window = window))
            is ConstraintPredicate.NeighborTone -> {
                if (
                    predicate.requirement.sourceSlot != null &&
                    !window.contains(predicate.requirement.sourceSlot)
                ) {
                    return null
                }
                predicate.copy(
                    requirement = predicate.requirement.copy(
                        window = window,
                        tonalKey = tonalKey,
                    )
                )
            }
            is ConstraintPredicate.TargetMatches ->
                predicate.copy(requirement = predicate.requirement.copy(window = window))
            else -> return constraint.copy(
                scope = ConstraintScope(
                    window = window,
                    selector = constraint.scope.selector,
                )
            )
        }
        return constraint.copy(expr = ConstraintExpr.Atom(predicate))
    }

    private fun modulationConstraints(
        pivotSlot: Int,
        lastSlot: Int,
        pivotPitchClasses: Set<PitchClass>,
        alteredPitchClasses: Set<PitchClass>,
        dominantSelector: TargetSelector,
    ): List<Constraint> = listOf(
        exactTargetConstraint(
            slot = pivotSlot,
            selector = TargetSelector(requiredPitchClasses = pivotPitchClasses),
            ruleId = COMMON_CHORD_RULE_ID,
            satisfied = "共同和弦同时属于原调与目标调。",
            violated = "转调点必须使用所选共同和弦。",
        ),
        Constraint(
            expr = ConstraintExpr.Or(
                alteredPitchClasses.sortedBy(PitchClass::value).map { pitchClass ->
                    ConstraintBranch(
                        expr = ConstraintExpr.Atom(
                            ConstraintPredicate.TargetMatches(
                                TargetFeatureBonusRequirement(
                                    window = SlotWindow(pivotSlot + 1, lastSlot),
                                    selector = TargetSelector(requiredPitchClasses = setOf(pitchClass)),
                                    ruleId = CHARACTERISTIC_TONE_RULE_ID,
                                    message = "目标调进行出现原调音阶外的特征音。",
                                    bonus = 0.0,
                                )
                            )
                        ),
                        ruleId = CHARACTERISTIC_TONE_RULE_ID,
                    )
                }
            ),
            modality = ConstraintModality.Require,
            ruleId = CHARACTERISTIC_TONE_RULE_ID,
            explanation = ConstraintExplanation(
                satisfied = "目标调已通过原调中没有的变化音得到确认。",
                violated = "共同和弦之后必须出现至少一个原调音阶中没有的目标调特征音。",
            ),
        ),
        exactTargetConstraint(
            slot = lastSlot - 1,
            selector = dominantSelector,
            ruleId = TARGET_DOMINANT_RULE_ID,
            satisfied = "目标调属功能进入终止式。",
            violated = "倒数第二个和弦必须是目标调 V 级和弦。",
        ),
        exactTargetConstraint(
            slot = lastSlot,
            selector = TargetSelector(degrees = setOf(1), inversions = setOf(0)),
            ruleId = TARGET_TONIC_RULE_ID,
            satisfied = "进行在目标调主和弦上终止。",
            violated = "最后一个和弦必须是目标调原位 I 级和弦。",
        ),
    )

    private fun exactTargetConstraint(
        slot: Int,
        selector: TargetSelector,
        ruleId: RuleId,
        satisfied: String,
        violated: String,
    ): Constraint =
        Constraint(
            expr = ConstraintExpr.Atom(
                ConstraintPredicate.TargetMatches(
                    TargetFeatureBonusRequirement(
                        window = SlotWindow(slot, slot),
                        selector = selector,
                        ruleId = ruleId,
                        message = satisfied,
                        bonus = 0.0,
                    )
                )
            ),
            modality = ConstraintModality.Require,
            ruleId = ruleId,
            explanation = ConstraintExplanation(satisfied, violated),
        )

    private fun matchingTriad(key: ModulationKey, chordId: ModulationChordId): NaturalTriad =
        NaturalTriads.inKey(key.key).firstOrNull {
            it.chord.root == chordId.root && it.chord.quality == chordId.quality
        } ?: error("${chordId.quality} chord on ${chordId.root} is not available in ${key.displayName}")

    private fun pivotConstruction(
        request: ModulationExerciseRequest,
        sourceContext: TonalContext,
        targetContext: TonalContext,
        sourceTriad: NaturalTriad,
        targetTriad: NaturalTriad,
    ): ConstructedChord {
        val definition = BuiltInChordDefinitions.forQuality(request.pivotChord.quality)
        val spelledRoot = ModulationCommonChordCatalog.spellChordTone(
            request.sourceKey,
            sourceTriad.degree,
            request.pivotChord.root,
        )
        return tonalConstruction(
            context = sourceContext,
            definition = definition,
            spelledRoot = spelledRoot,
            degree = sourceTriad.degree,
            alteration = 0,
            arity = ChordArity.TRIAD,
            interpretationId = InterpretationId(
                "modulation.pivot.${sourceContext.id.value}.${targetContext.id.value}." +
                    "${sourceTriad.degree}.${targetTriad.degree}.${request.pivotChord.quality.name.lowercase()}"
            ),
            compatibleContextIds = setOf(targetContext.id),
            traceSteps = listOf(
                "source-degree-${sourceTriad.degree}",
                "target-degree-${targetTriad.degree}",
            ),
        )
    }

    private fun chordConstructions(
        key: ModulationKey,
        context: TonalContext,
        includeIntegratedVocabulary: Boolean,
    ): List<ConstructedChord> {
        if (!includeIntegratedVocabulary) return naturalTriadConstructions(key, context)
        val triads = exerciseTriads(key.key, includeLeadingTriad = true)
        return SchoenbergIntegratedTechTree.vocabulary(
            key = key.key,
            treatmentIds = SchoenbergHarmonicTreatments.integratedDiatonicTreatments,
        )
            .distinctBy {
                listOf(it.degree, it.rootAlteration, it.quality, it.arity)
            }
            .map { symbolic ->
            val target = symbolic.toTarget(triads)
            val definition = BuiltInChordDefinitions.forQuality(target.quality)
            tonalConstruction(
                context = context,
                definition = definition,
                spelledRoot = ModulationCommonChordCatalog.spellChordTone(
                    key = key,
                    degree = target.degree,
                    pitchClass = target.sonority.root,
                ),
                degree = target.degree,
                alteration = symbolic.rootAlteration,
                arity = symbolic.arity,
                interpretationId = InterpretationId(
                    "modulation.${context.id.value}.${target.degree}.${symbolic.rootAlteration}." +
                        "${target.quality.name.lowercase()}.${symbolic.arity.name.lowercase()}"
                ),
            )
        }
    }

    private fun naturalTriadConstructions(
        key: ModulationKey,
        context: TonalContext,
    ): List<ConstructedChord> =
        NaturalTriads.inKey(key.key).map { triad ->
            val definition = BuiltInChordDefinitions.forQuality(triad.quality)
            tonalConstruction(
                context = context,
                definition = definition,
                spelledRoot = ModulationCommonChordCatalog.spellChordTone(
                    key,
                    triad.degree,
                    triad.root,
                ),
                degree = triad.degree,
                alteration = 0,
                arity = ChordArity.TRIAD,
                interpretationId = InterpretationId(
                    "modulation.${context.id.value}.${triad.degree}.0.${triad.quality.name.lowercase()}.triad"
                ),
            )
        }

    private fun tonalConstruction(
        context: TonalContext,
        definition: com.mecon.theory.ChordDefinition,
        spelledRoot: com.mecon.theory.SpelledPitchClass,
        degree: Int,
        alteration: Int,
        arity: ChordArity,
        interpretationId: InterpretationId,
        compatibleContextIds: Set<com.mecon.theory.TonalContextId> = emptySet(),
        traceSteps: List<String> = listOf("scale-degree-$degree"),
    ): ConstructedChord {
        val recipeId = ChordRecipeId("schoenberg.modulation")
        val interpretation = ChordInterpretation(
            id = interpretationId,
            lens = TonalLens(context.id, context),
            compatibleContextIds = compatibleContextIds,
            symbol = FunctionalChordSymbol(
                degree = degree,
                alteration = alteration,
                quality = definition.compatibilityQuality,
                arity = arity,
            ),
            function = when (degree) {
                1 -> HarmonicFunction.TONIC
                2, 4 -> HarmonicFunction.PREDOMINANT
                5 -> HarmonicFunction.DOMINANT
                7 -> HarmonicFunction.LEADING
                else -> HarmonicFunction.OTHER
            },
            toneRoles = ChordBuilder.structuralToneRoles(definition, spelledRoot),
            structuralToneOrder = ChordBuilder.structuralToneOrder(definition, spelledRoot),
            tags = setOf(InterpretationTag("function.modulation")),
            trace = InterpretationTrace(recipeId, traceSteps),
        )
        return ChordBuilder.fromSpelledRoot(
            definition = definition,
            spelledRoot = spelledRoot,
            interpretation = interpretation,
            trace = ConstructionTrace(recipeId, traceSteps),
        )
    }

    private const val REGION_SEQUENCE_LIMIT = 24
    private const val REGION_ENUMERATION_BEAM_WIDTH = 256
    private const val SIMPLE_REGION_SEQUENCE_LIMIT = 8
    private const val FEATURE_REGION_SEQUENCE_LIMIT = 4
    private const val MODULATION_REALIZATION_BEAM_WIDTH = 192
    private const val SEVENTH_BALANCE_WEIGHT = 8.0
    private const val INVERSION_BALANCE_WEIGHT = 4.0
    private const val CADENTIAL_SEVENTH_BONUS = 16.0
}
