package com.mecon.theory.schoenberg

import com.mecon.theory.Key
import com.mecon.theory.Mode
import com.mecon.theory.NaturalTriad
import com.mecon.theory.RuleId
import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.constraint.AdjacentCommonToneRequirement
import com.mecon.theory.constraint.AllDifferentRequirement
import com.mecon.theory.constraint.ChordToneNeighborRequirement
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.SlotDomain
import com.mecon.theory.constraint.TargetFeatureBonusRequirement
import com.mecon.theory.constraint.TargetSelector
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.constraint.ChordToneCompletenessRuleIds
import com.mecon.theory.constraint.ToneCompletenessRequirement
import com.mecon.theory.constraint.WritingRulePreset
import com.mecon.theory.constraint.generalChordToneCompletenessRequirements
import com.mecon.theory.constraint.RootlessDominantNinthMetadata
import com.mecon.theory.constraint.SecondaryHarmonyMetadata
import com.mecon.theory.constraint.InterpretedChordTarget
import com.mecon.theory.constraint.AugmentedSixthMetadata
import com.mecon.theory.harmony.HarmonicTreatmentId
import com.mecon.theory.textbook.DominantSeventhRules
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookTriadPosition

internal object SchoenbergIntegratedProgramBuilder {
    fun build(
        key: Key,
        continuationChordCount: Int,
        treatmentIds: Set<HarmonicTreatmentId>,
        progression: SchoenbergSymbolicProgression? = null,
        searchConfig: SearchConfig = SearchConfig(maxResults = 4, beamWidth = 160),
        requireAdjacentCommonTone: Boolean = true,
        dissonanceTreatment: SchoenbergDissonanceTreatment = SchoenbergDissonanceTreatment.STRICT,
    ): ConstraintProgram {
        require(continuationChordCount >= 1) { "continuationChordCount must be >= 1" }
        val length = continuationChordCount + 1
        require(progression == null || progression.slots.size == length) {
            "progression size must equal continuationChordCount + 1"
        }
        val stage = SchoenbergIntegratedStageSpec(treatmentIds)
        val includeLeadingTriad = stage.includes(SchoenbergHarmonicTreatments.LEADING_TRIAD)
        val includeSecondInversion = stage.includes(SchoenbergHarmonicTreatments.SECOND_INVERSION)
        val includeSeventhChords = stage.includes(SchoenbergHarmonicTreatments.DIATONIC_DOMINANT)
        val minor = key.mode == Mode.AEOLIAN
        val triads = exerciseTriads(key, includeLeadingTriad = includeLeadingTriad)
        val tonic = triads.firstOrNull { it.degree == TONIC_DEGREE }
            ?: error("Current key has no tonic triad")
        val tonicTarget = tonic.toTarget(TextbookTriadPosition.ROOT_POSITION)
        val openVocabulary = SchoenbergIntegratedTechTree.vocabulary(
            key = key,
            treatmentIds = treatmentIds,
        )
        val openTargets = SchoenbergChordCatalog.targets(key, openVocabulary)
        val activeTreatmentIds = openTargets
            .filterIsInstance<InterpretedChordTarget>()
            .flatMapTo(linkedSetOf()) { it.interpretation.treatmentIds }
        val activeRuleFamilies = SchoenbergHarmonicTreatments.registry
            .resolve(activeTreatmentIds)
            .ruleFamilies
        val usesSecondaryTreatment =
            SchoenbergHarmonicTreatments.LOCAL_TONICIZATION_RULES in activeRuleFamilies
        val usesRootlessTreatment =
            SchoenbergHarmonicTreatments.LOWER_TO_OMITTED_ROOT_RULES in activeRuleFamilies
        val usesAugmentedSixthTreatment =
            stage.includes(SchoenbergHarmonicTreatments.AUGMENTED_SIXTH)
        val slotDomains = progression?.let { exactProgressionDomains(it, triads, length) }
            ?: List(length) { slot ->
                SlotDomain(
                    targets = when (slot) {
                        0, length - 1 -> listOf(tonicTarget)
                        else -> openTargets
                    },
                )
            }
        val window = SlotWindow(0, null)
        val ordinarySeventhIdentityKeys = openTargets
            .filter { target ->
                target.arity == ChordArity.SEVENTH &&
                    !RootlessDominantNinthMetadata.isRootlessDominantNinth(target) &&
                    AugmentedSixthMetadata.familyOf(target) == null
            }
            .mapTo(linkedSetOf()) { it.identityKey() }
        val ordinaryDiminishedIdentityKeys = openTargets
            .filter { target ->
                target.quality in setOf(
                    ChordQuality.DIMINISHED,
                    ChordQuality.DIMINISHED7,
                    ChordQuality.HALF_DIMINISHED7,
                ) && !RootlessDominantNinthMetadata.isRootlessDominantNinth(target)
            }
            .mapTo(linkedSetOf()) { it.identityKey() }
        val secondaryHarmonyIdentityKeys = openTargets
            .filter { target ->
                SecondaryHarmonyMetadata.familyOf(target) != null
            }
            .mapTo(linkedSetOf()) { it.identityKey() }
        val ordinaryMinorMelodyIdentityKeys = openTargets
            .filterNot { it.identityKey() in secondaryHarmonyIdentityKeys }
            .mapTo(linkedSetOf()) { it.identityKey() }
        val rootlessIdentityKeys = openTargets
            .filter(RootlessDominantNinthMetadata::isRootlessDominantNinth)
            .mapTo(linkedSetOf()) { it.identityKey() }
        val augmentedSixthTargets = openTargets
            .filter { AugmentedSixthMetadata.familyOf(it) != null }
        val augmentedSixthIdentityKeys = augmentedSixthTargets
            .mapTo(linkedSetOf()) { it.identityKey() }
        val chapterConstraints = progression?.let {
            buildList {
                if (includeSecondInversion) addAll(SchoenbergSecondInversionChapter.sixFourPrinciples(key, slotDomains))
                if (includeSeventhChords) {
                    addAll(
                        SchoenbergSeventhChordChapter.inferredDissonanceConstraints(
                            key,
                            slotDomains,
                            includeResolution = dissonanceTreatment != SchoenbergDissonanceTreatment.STRICT,
                        )
                    )
                }
            }
        }.orEmpty()
        return ConstraintProgram.fromRequirements(
            key = key,
            slotDomains = slotDomains,
            configuration = com.mecon.theory.constraint.ConstraintRequirementConfiguration(
                ruleProfile = SchoenbergCommonToneExercises.SCHOENBERG_PROFILE,
                avoidDoublings = firstInversionAvoidDoublings(
                    length = length,
                    progression = progression,
                    triads = triads,
                    required = false,
                ) + when {
                    dissonanceTreatment == SchoenbergDissonanceTreatment.FREER -> emptyList()
                    minor -> SchoenbergMinorChapter.minorDissonanceAvoidDoublings(length)
                    else -> leadingFifthAvoidDoublings(length, required = true)
                },
                // The terminal I deliberately repeats the opening I; all preceding degree+inversion identities stay unique.
                allDifferent = listOf(AllDifferentRequirement(SlotWindow(0, length - 2))),
                adjacentCommonTones = if (requireAdjacentCommonTone) {
                    listOf(
                        AdjacentCommonToneRequirement(
                            window,
                            holdInSameVoice = false,
                            exemptIdentityKeys = secondaryHarmonyIdentityKeys + augmentedSixthIdentityKeys,
                        )
                    )
                } else {
                    emptyList()
                },
                chordToneNeighbors = minorOrLeadingNeighbors(
                    minor,
                    includeLeadingTriad,
                    key,
                    window,
                    dissonanceTreatment,
                    ordinaryDiminishedIdentityKeys,
                    ordinaryMinorMelodyIdentityKeys,
                ) + if (
                    includeSeventhChords &&
                    dissonanceTreatment == SchoenbergDissonanceTreatment.STRICT
                ) {
                    seventhResolutionNeighborRequirements(window, ordinarySeventhIdentityKeys)
                } else {
                    emptyList()
                } + if (usesSecondaryTreatment) {
                    SchoenbergSecondaryDominantChapter.appliedTendencyRequirements(key, window)
                } else {
                    emptyList()
                } + if (usesRootlessTreatment) {
                    SchoenbergDiminishedSeventhChapter.neighborRequirements(key, window)
                } else {
                    emptyList()
                } + if (usesAugmentedSixthTreatment) {
                    augmentedSixthTargets.flatMap { target ->
                        SchoenbergAugmentedSixthChapter.endpointRequirements(
                            sourceIdentity = target.identityKey(),
                            family = AugmentedSixthMetadata.familyOf(target)
                                ?: error("Augmented-sixth family metadata is missing"),
                            targetDegree = AugmentedSixthMetadata.targetDegreeOf(target)
                                ?: error("Augmented-sixth target metadata is missing"),
                            targetAlteration = AugmentedSixthMetadata.targetAlterationOf(target)
                                ?: error("Augmented-sixth target alteration metadata is missing"),
                            upperTone = if (target.arity == ChordArity.TRIAD) {
                                ChordTone.FIFTH
                            } else {
                                ChordTone.SEVENTH
                            },
                        )
                    }
                } else {
                    emptyList()
                },
                targetFeatureBonuses = targetFeatureBonuses(window),
                // 关闭 textbook 和弦模块后仍复用统一的纵向材料规则；综合练习保留“五音软偏好”的教学语义。
                toneCompleteness = generalChordToneCompletenessRequirements(
                    window = window,
                    ruleIds = ChordToneCompletenessRuleIds(
                        triad = SchoenbergIntegratedTechTree.TRIAD_COMPLETE_RULE_ID,
                        seventh = SchoenbergSeventhChordChapter.ROOT_AND_SEVENTH_PRESENT_RULE_ID,
                    ),
                ) + if (minor) {
                    listOf(SchoenbergMinorChapter.minorEssentialFifthCompleteness(window))
                } else {
                    emptyList()
                } + if (usesRootlessTreatment) {
                    listOf(
                        ToneCompletenessRequirement(
                            window = window,
                            requiredTones = setOf(
                                ChordTone.ROOT,
                                ChordTone.THIRD,
                                ChordTone.FIFTH,
                                ChordTone.SEVENTH,
                            ),
                            selector = TargetSelector(identityKeys = rootlessIdentityKeys),
                            required = true,
                            ruleId = SchoenbergDiminishedSeventhChapter.COMPLETE_RULE_ID,
                        )
                    )
                } else {
                    emptyList()
                } + if (usesAugmentedSixthTreatment) {
                    listOf(
                        ToneCompletenessRequirement(
                            window = window,
                            requiredTones = setOf(ChordTone.ROOT, ChordTone.THIRD, ChordTone.FIFTH),
                            selector = TargetSelector(identityKeys = augmentedSixthIdentityKeys),
                            required = true,
                            ruleId = SchoenbergAugmentedSixthChapter.COMPLETE_RULE_ID,
                        ),
                        ToneCompletenessRequirement(
                            window = window,
                            requiredTones = setOf(ChordTone.SEVENTH),
                            selector = TargetSelector(
                                identityKeys = augmentedSixthTargets
                                    .filter { it.arity == ChordArity.SEVENTH }
                                    .mapTo(linkedSetOf()) { it.identityKey() },
                            ),
                            required = true,
                            ruleId = SchoenbergAugmentedSixthChapter.COMPLETE_RULE_ID,
                        ),
                    )
                } else {
                    emptyList()
                },
                constraints = chapterConstraints.map { it.asSoftPreference() } +
                    chapterConstraints.map { it.asSuccessAnnotation() },
                searchConfig = searchConfig,
                writingRulePreset = WritingRulePreset.SCHOENBERG_GENERAL,
                // 勋伯格模式：关掉 textbook 关于具体和弦的规则（默认和弦模块 + 派生命名约束），只保留一般四部写作规则，
                // 具体和弦的行为由勋伯格章节原则接管。见 §4 / AGENTS.md。
                ruleModules = emptyList(),
                includeDerivedTextbookConstraints = false,

            ),
        )
    }

    /**
     * 导 / 减 / 增三和弦与旋律进行的相邻音硬约束。大调用 vii°（degree=7）专属规则；小调改用**按性质**的
     * 减五度 / 增五度预备解决 + 三条旋律硬要求（升六→导音、导音→主音、自然 6/7 不进变化音），覆盖 ii°/vii°、
     * III+ 及其对应七和弦。见 [SchoenbergMinorChapter] 与 AGENTS.md 复用原则。
     */
    private fun minorOrLeadingNeighbors(
        minor: Boolean,
        includeLeadingTriad: Boolean,
        key: Key,
        window: SlotWindow,
        dissonanceTreatment: SchoenbergDissonanceTreatment,
        ordinaryDiminishedIdentityKeys: Set<String>,
        ordinaryMinorMelodyIdentityKeys: Set<String>,
    ): List<ChordToneNeighborRequirement> =
        when {
            minor -> SchoenbergMinorChapter.minorMelodyNeighborRequirements(
                key,
                window,
                ordinaryMinorMelodyIdentityKeys,
            ) +
                if (dissonanceTreatment == SchoenbergDissonanceTreatment.FREER) {
                    SchoenbergMinorChapter.minorAugmentedNeighborRequirements(window)
                } else {
                    SchoenbergMinorChapter.minorDiminishedNeighborRequirements(
                        window,
                        ordinaryDiminishedIdentityKeys,
                    ) +
                        SchoenbergMinorChapter.minorAugmentedNeighborRequirements(window)
                }
            includeLeadingTriad && dissonanceTreatment != SchoenbergDissonanceTreatment.FREER ->
                leadingTriadNeighborRequirements(window)
            else -> emptyList()
        }

    private fun targetFeatureBonuses(window: SlotWindow): List<TargetFeatureBonusRequirement> =
        listOf(
            TargetFeatureBonusRequirement(
                window = window,
                selector = TargetSelector(degrees = setOf(LEADING_TONE_DEGREE), arities = setOf(ChordArity.TRIAD)),
                ruleId = SchoenbergCommonToneExercises.KNOWLEDGE_LEADING_TRIAD_RULE_ID,
                message = "使用了导和弦。",
                bonus = 8.0,
            ),
            TargetFeatureBonusRequirement(
                window = window,
                selector = TargetSelector(
                    inversions = setOf(TextbookTriadPosition.FIRST_INVERSION.ordinal),
                    arities = setOf(ChordArity.TRIAD),
                ),
                ruleId = SchoenbergCommonToneExercises.KNOWLEDGE_FIRST_INVERSION_RULE_ID,
                message = "使用了六和弦丰富低音线条。",
                bonus = 4.0,
            ),
            TargetFeatureBonusRequirement(
                window = window,
                selector = TargetSelector(
                    degrees = setOf(LEADING_TONE_DEGREE),
                    inversions = setOf(TextbookTriadPosition.FIRST_INVERSION.ordinal),
                    arities = setOf(ChordArity.TRIAD),
                ),
                ruleId = SchoenbergCommonToneExercises.KNOWLEDGE_LEADING_FIRST_INVERSION_RULE_ID,
                message = "使用了导和弦的第一转位，综合了导和弦与六和弦知识。",
                bonus = 16.0,
            ),
        )

}
