package com.mecon.theory.schoenberg

import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.Key
import com.mecon.theory.Mode
import com.mecon.theory.NaturalTriad
import com.mecon.theory.RuleId
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.TonalContext
import com.mecon.theory.theoryMemoMap
import com.mecon.theory.constraint.AvoidDoublingRequirement
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.constraint.ChordToneNeighborDirection
import com.mecon.theory.constraint.ChordToneNeighborRequirement
import com.mecon.theory.constraint.Constraint
import com.mecon.theory.constraint.ConstraintExpr
import com.mecon.theory.constraint.ConstraintExplanation
import com.mecon.theory.constraint.ConstraintModality
import com.mecon.theory.constraint.ConstraintPredicate
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.SecondaryHarmonyFamily
import com.mecon.theory.constraint.SecondaryHarmonyType
import com.mecon.theory.constraint.SecondaryHarmonyVocabulary
import com.mecon.theory.constraint.SecondaryHarmonyMetadata
import com.mecon.theory.harmony.ChordCatalogCategoryDescriptor
import com.mecon.theory.harmony.ChordCatalogCategoryId
import com.mecon.theory.harmony.ChordCatalogChapterProvider
import com.mecon.theory.harmony.ChordCatalogContribution
import com.mecon.theory.harmony.ChordChapterDescriptor
import com.mecon.theory.harmony.ChordChoiceProjection
import com.mecon.theory.harmony.ChordConstructionDetail
import com.mecon.theory.harmony.ChordDetailDefinition
import com.mecon.theory.harmony.ChordExplanationId
import com.mecon.theory.harmony.ChordFunctionDetail
import com.mecon.theory.harmony.ChordInterpretation
import com.mecon.theory.harmony.ChordInterpretationRef
import com.mecon.theory.harmony.ChordKnowledgeChapterProvider
import com.mecon.theory.harmony.ChordKnowledgeContribution
import com.mecon.theory.harmony.ChordKnowledgeContributionId
import com.mecon.theory.harmony.ChordStructureDetail
import com.mecon.theory.harmony.ChordSummary
import com.mecon.theory.harmony.ChordVoiceLeadingDetail
import com.mecon.theory.harmony.ConstructionOperation
import com.mecon.theory.harmony.ConstructionRoute
import com.mecon.theory.harmony.ConstructionRouteId
import com.mecon.theory.harmony.DiscoverableChordCatalogChapter
import com.mecon.theory.harmony.DiscoverableChordKnowledgeChapter
import com.mecon.theory.harmony.ModalScaleConstructionTone
import com.mecon.theory.harmony.TheoryClaimKind
import com.mecon.theory.harmony.TheorySourceRef
import com.mecon.theory.harmony.VagrantChordFamilyId
import com.mecon.theory.constraint.TargetSelector
import com.mecon.theory.constraint.TargetFeatureBonusRequirement
import com.mecon.theory.constraint.ToneCompletenessRequirement
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookTriadPosition

data class SchoenbergSecondaryHarmonyChoice(
    val id: String,
    val chord: SchoenbergSymbolicChord,
)

/**
 * Schoenberg secondary-dominant chapter.
 *
 * Chord identities come from [SecondaryHarmonyVocabulary], so symbolic enumeration and both
 * realization entry points consume one modal derivation. The chapter itself only supplies the
 * pedagogical progression shape and the scoped tendency-tone rules.
 */
@DiscoverableChordCatalogChapter
@DiscoverableChordKnowledgeChapter
object SchoenbergSecondaryDominantChapter :
    ChordCatalogChapterProvider,
    ChordKnowledgeChapterProvider {
    private val CHAPTER = ChordChapterDescriptor("schoenberg.secondary-harmony", 300)
    val AUGMENTED_TRIAD_FAMILY_ID = VagrantChordFamilyId("schoenberg.augmented-triad")

    override val chordCatalogContributions = listOf(
        secondaryContribution(
            family = SecondaryHarmonyFamily.SECONDARY_DOMINANT,
            id = "secondary-dominants",
            order = 300,
            titleKey = "exploration.chordCatalog.secondaryDominants.title",
            descriptionKey = "exploration.chordCatalog.secondaryDominants.description",
            projectedDegree = 5,
        ),
        secondaryContribution(
            family = SecondaryHarmonyFamily.SECONDARY_LEADING,
            id = "secondary-leading",
            order = 400,
            titleKey = "exploration.chordCatalog.secondaryLeading.title",
            descriptionKey = "exploration.chordCatalog.secondaryLeading.description",
            projectedDegree = 7,
        ),
        secondaryContribution(
            family = SecondaryHarmonyFamily.MODAL_AUGMENTED,
            id = "augmented-triads",
            order = 500,
            titleKey = "exploration.chordCatalog.augmentedTriads.title",
            descriptionKey = "exploration.chordCatalog.augmentedTriads.description",
            choiceProjection = ChordChoiceProjection.BySoundingClass(AUGMENTED_TRIAD_FAMILY_ID),
        ),
        secondaryContribution(
            family = SecondaryHarmonyFamily.MODAL_DESCENDING_DOMINANT,
            id = "modal-colors",
            order = 700,
            titleKey = "exploration.chordCatalog.modalColors.title",
            descriptionKey = "exploration.chordCatalog.modalColors.description",
        ),
    )

    override val chordKnowledgeContributions = listOf(
        ChordKnowledgeContribution(
            id = ChordKnowledgeContributionId("schoenberg.secondary-harmony"),
            chapterId = CHAPTER.id,
            chapter = CHAPTER,
            construct = { context ->
                SecondaryHarmonyVocabulary.constructedChordsForContext(context)
            },
            details = { knowledgeContext, catalog ->
                val tonalContext = knowledgeContext.tonalContext
                val typesByInterpretation = SecondaryHarmonyVocabulary.harmonyTypes(tonalContext)
                    .filter { type ->
                        type.tonicizedDegree != 1 || type.family in setOf(
                            SecondaryHarmonyFamily.MODAL_AUGMENTED,
                            SecondaryHarmonyFamily.MODAL_DESCENDING_DOMINANT,
                        )
                    }
                    .associateBy { type ->
                        SecondaryHarmonyVocabulary.run { type.interpretationId() }
                    }
                catalog.entries.flatMap { entry ->
                    val soundingPitchClasses = entry.sonority.tones
                        .mapTo(linkedSetOf()) { it.spelling.pitchClass.value }
                    entry.interpretations.map { interpretation ->
                        val type = typesByInterpretation.getValue(interpretation.id)
                        val derivations = SecondaryHarmonyVocabulary.modalDerivations(tonalContext, type)
                        val chordSpellingByNoteName = entry.sonority.tones.associate {
                            it.spelling.noteName to it.spelling
                        }
                        val ref = ChordInterpretationRef(entry.sonority.id, interpretation.id)
                        ChordDetailDefinition(
                            interpretationRef = ref,
                            explanationId = ChordExplanationId("schoenberg.${interpretation.id.value}"),
                            sourceCategoryIds = setOf(type.family.categoryId()),
                            summary = ChordSummary(
                                nameKey = type.family.detailNameKey(),
                                descriptionKey = "chordDetail.secondaryHarmony.summary",
                                tags = listOf("chordDetail.secondaryHarmony.modalTag"),
                            ),
                            structure = ChordStructureDetail(
                                toneIds = interpretation.structuralToneOrder,
                                propertyKeys = listOf("chordDetail.secondaryHarmony.modalDerivation"),
                            ),
                            function = ChordFunctionDetail(
                                function = interpretation.function,
                                descriptionKey = type.family.functionDescriptionKey(),
                            ),
                            voiceLeading = ChordVoiceLeadingDetail(
                                connectionRefs = interpretation.treatmentIds.toList(),
                            ),
                            routes = derivations.map { derivation ->
                                val illustratedDegrees = derivation.degrees.map { modalDegree ->
                                    chordSpellingByNoteName[modalDegree.noteName] ?: modalDegree
                                }
                                ConstructionRoute(
                                    id = ConstructionRouteId(
                                        "route.${interpretation.id.value}.modal-scale.${derivation.mode.name.lowercase()}"
                                    ),
                                    interpretationRef = ref,
                                    formulaKey = "chordDetail.secondaryHarmony.modalRoute",
                                    steps = listOf(
                                        ConstructionOperation.LegacyTrace(
                                            "derive-${type.family.name.lowercase()}-from-${derivation.mode.name.lowercase()}"
                                        )
                                    ),
                                    connectionRefs = interpretation.treatmentIds.toList(),
                                    construction = ChordConstructionDetail.ModalScaleDegrees(
                                        mode = derivation.mode,
                                        path = derivation.path,
                                        tonicizedDegree = type.tonicizedDegree,
                                        keySignatureFifths = tonalContext.keySignature?.fifths,
                                        degrees = illustratedDegrees.mapIndexed { index, spelling ->
                                            ModalScaleConstructionTone(
                                                degree = index + 1,
                                                spelling = spelling,
                                                chordTone = spelling.pitchClass.value in soundingPitchClasses,
                                            )
                                        },
                                    ),
                                    sourceRefs = listOf(SECONDARY_HARMONY_SOURCE),
                                )
                            },
                            sourceRefs = listOf(SECONDARY_HARMONY_SOURCE),
                        )
                    }
                }
            },
        )
    )

    val FUNCTION_RULE_ID = RuleId("schoenberg.secondary-harmony.functional-resolution")
    val LEADING_TONE_RULE_ID = RuleId("schoenberg.secondary-harmony.leading-tone-resolution")
    val MODAL_DERIVATION_RULE_ID = RuleId("schoenberg.secondary-harmony.modal-derivation")

    private fun secondaryContribution(
        family: SecondaryHarmonyFamily,
        id: String,
        order: Int,
        titleKey: String,
        descriptionKey: String,
        projectedDegree: Int? = null,
        choiceProjection: ChordChoiceProjection = ChordChoiceProjection.ByInterpretation,
    ) = ChordCatalogContribution(
        category = ChordCatalogCategoryDescriptor(
            id = id,
            order = order,
            titleKey = titleKey,
            descriptionKey = descriptionKey,
        ),
        construct = { context, key ->
            SecondaryHarmonyVocabulary.constructedChords(
                context = context,
                compatibilityKey = key,
            ).filter {
                it.interpretation.attributes[SecondaryHarmonyMetadata.FAMILY_NAME] == family.name
            }
        },
        symbolProjection = { interpretation: ChordInterpretation ->
            projectedDegree?.let {
                interpretation.symbol.copy(degree = it, alteration = 0)
            } ?: interpretation.symbol
        },
        choiceProjection = choiceProjection,
    )

    /** Shared type list used by the exercise picker and the free-solver vocabulary adapter. */
    fun harmonyTypes(key: Key): List<SecondaryHarmonyType> =
        SecondaryHarmonyVocabulary.harmonyTypes(
            context = TonalContext.fromKey(key),
            sourceMode = key.mode,
        )

    /** Concrete applied chords shown before the progression picker. Primary V/I is not a secondary function. */
    fun harmonyChoices(key: Key): List<SchoenbergSecondaryHarmonyChoice> =
        harmonyChoiceMemo.getOrPut(key) {
            allHarmonyChoices(key).filter { choice ->
                enumerate(key, choice.id).isNotEmpty()
            }
        }

    private fun allHarmonyChoices(key: Key): List<SchoenbergSecondaryHarmonyChoice> =
        exerciseHarmonyTypes(key).map { type ->
            SchoenbergSecondaryHarmonyChoice(
                id = type.id,
                chord = type.toSymbolic(),
            )
        }

    /**
     * Enumeration is a pure function of (key, chord id) but callers query it repeatedly: the picker
     * filter runs it once per candidate chord, and free practice then re-enumerates each surviving
     * choice. Memoizing keeps the per-choice slicing — and therefore the per-choice dedup and
     * [MAX_ENUMERATED_PROGRESSIONS] cap — exactly as it was.
     */
    fun enumerate(
        key: Key,
        secondaryHarmonyId: String? = null,
    ): List<SchoenbergSymbolicProgression> =
        enumerationMemo.getOrPut(key to secondaryHarmonyId) { enumerateUncached(key, secondaryHarmonyId) }

    private val harmonyChoiceMemo =
        theoryMemoMap<Key, List<SchoenbergSecondaryHarmonyChoice>>()
    private val enumerationMemo =
        theoryMemoMap<Pair<Key, String?>, List<SchoenbergSymbolicProgression>>()

    private fun enumerateUncached(
        key: Key,
        secondaryHarmonyId: String?,
    ): List<SchoenbergSymbolicProgression> {
        val triads = exerciseTriads(key, includeLeadingTriad = true)
        val preparationChords = SchoenbergIntegratedTechTree.vocabulary(
            key = key,
            treatmentIds = setOf(
                SchoenbergHarmonicTreatments.LEADING_TRIAD,
                SchoenbergHarmonicTreatments.DIATONIC_DOMINANT,
            ),
        )
        val types = exerciseHarmonyTypes(key).let { available ->
            if (secondaryHarmonyId == null) {
                available
            } else {
                available.filter { it.id == secondaryHarmonyId }.also {
                    require(it.isNotEmpty()) {
                        "Unknown secondary-harmony chord $secondaryHarmonyId in the current key"
                    }
                }
            }
        }
        return buildList {
            types.forEach { type ->
                val applied = type.toSymbolic()
                val resolutions = resolutionDegrees(type.tonicizedDegree)
                    .mapNotNull { degree ->
                        triads.firstOrNull { it.degree == degree && !it.isLeadingTriad() }
                    }
                    .distinctBy { it.degree to it.quality }
                val preparations = preparationChords
                    .filter { candidate ->
                        allowsPreparation(
                            preparation = candidate,
                            applied = applied,
                            key = key,
                            triads = triads,
                        )
                    }
                    .take(MAX_PREPARATIONS_PER_TYPE)
                preparations.forEach { preparation ->
                    resolutions.forEach { resolution ->
                        val resolutionChord = resolution.toSymbolic(TextbookTriadPosition.ROOT_POSITION)
                        if (!allowsResolution(applied, resolutionChord, key, triads)) return@forEach
                        add(
                            SchoenbergSymbolicProgression(
                                slots = listOf(
                                    preparation,
                                    applied,
                                    resolutionChord,
                                ),
                                kind = SchoenbergConnectionKind.SECONDARY_FUNCTION,
                                knowledgeTags = setOf(SchoenbergKnowledgeTag.SECONDARY_HARMONY),
                            )
                        )
                    }
                }
            }
        }.distinctBy { progression -> progression.slots.map(SchoenbergSymbolicChord::transitionToken) }
            .take(MAX_ENUMERATED_PROGRESSIONS)
    }

    fun progressionUsesHarmony(
        progression: SchoenbergSymbolicProgression,
        secondaryHarmonyId: String,
        key: Key,
    ): Boolean {
        val selected = harmonyChoices(key).firstOrNull { it.id == secondaryHarmonyId }?.chord
            ?: return false
        return progression.slots.any { it.transitionToken() == selected.transitionToken() }
    }

    fun program(
        key: Key,
        progression: SchoenbergSymbolicProgression? = null,
        searchConfig: SearchConfig = SearchConfig(maxResults = 4, beamWidth = 256),
    ): ConstraintProgram {
        val triads = exerciseTriads(key, includeLeadingTriad = true)
        val selected = progression ?: enumerate(key).firstOrNull()
            ?: error("No secondary-harmony progression is available in the current key")
        require(selected.slots.size == EXERCISE_LENGTH)
        require(selected.slots.count { it.secondaryFamily != null } == 1) {
            "Secondary-harmony exercise requires exactly one applied chord"
        }
        val domains = exactProgressionDomains(selected, triads, EXERCISE_LENGTH)
        val window = SlotWindow(0, domains.lastIndex)
        val seventhRules = SchoenbergSeventhChordChapter.inferredDissonanceConstraints(
            key = key,
            domains = domains,
            includeResolution = true,
        )
        return ConstraintProgram.fromRequirements(
            key = key,
            slotDomains = domains,
            configuration = com.mecon.theory.constraint.ConstraintRequirementConfiguration(
                ruleProfile = SchoenbergCommonToneExercises.SCHOENBERG_PROFILE,
                avoidDoublings = listOf(
                    AvoidDoublingRequirement(
                        slot = 1,
                        tone = ChordTone.THIRD,
                        required = false,
                        selector = TargetSelector(
                            arities = setOf(ChordArity.TRIAD),
                            inversions = setOf(TextbookTriadPosition.FIRST_INVERSION.ordinal),
                        ),
                    )
                ) + SchoenbergMinorChapter.minorDissonanceAvoidDoublings(domains.size),
                chordToneNeighbors = appliedTendencyRequirements(selected, key, window) +
                    SchoenbergMinorChapter.minorDiminishedNeighborRequirements(window) +
                    SchoenbergMinorChapter.minorAugmentedNeighborRequirements(window),
                toneCompleteness = listOf(
                    ToneCompletenessRequirement(
                        window = window,
                        requiredTones = setOf(ChordTone.ROOT, ChordTone.THIRD),
                        selector = TargetSelector(arities = setOf(ChordArity.TRIAD)),
                        required = true,
                        ruleId = SchoenbergIntegratedTechTree.TRIAD_COMPLETE_RULE_ID,
                    ),
                    ToneCompletenessRequirement(
                        window = window,
                        requiredTones = setOf(ChordTone.FIFTH),
                        selector = TargetSelector(arities = setOf(ChordArity.TRIAD)),
                        required = false,
                        ruleId = SchoenbergIntegratedTechTree.TRIAD_COMPLETE_RULE_ID,
                    ),
                    ToneCompletenessRequirement(
                        window = window,
                        requiredTones = setOf(ChordTone.ROOT, ChordTone.SEVENTH),
                        selector = TargetSelector(arities = setOf(ChordArity.SEVENTH)),
                        required = true,
                        ruleId = SchoenbergSeventhChordChapter.ROOT_AND_SEVENTH_PRESENT_RULE_ID,
                    ),
                    SchoenbergMinorChapter.minorEssentialFifthCompleteness(window),
                ),
                constraints = seventhRules +
                    seventhRules.map { it.asSuccessAnnotation() } +
                    functionAnnotations(selected, domains[1].targets.single()),
                searchConfig = searchConfig,
                ruleModules = emptyList(),
                includeDerivedTextbookConstraints = false,
            ),
        )
        }

    private fun functionAnnotations(
        progression: SchoenbergSymbolicProgression,
        target: com.mecon.theory.constraint.ChordTarget,
    ): List<Constraint> {
        val chord = progression.slots[1]
        val selector = TargetSelector(
            degrees = setOf(chord.degree),
            qualities = setOf(chord.quality),
            inversions = setOf(target.inversion),
            arities = setOf(chord.arity),
            identityKeys = setOf(target.identityKey()),
        )
        fun annotation(ruleId: RuleId, message: String): Constraint =
            Constraint(
                expr = ConstraintExpr.And(
                    listOf(
                        ConstraintExpr.Atom(
                            ConstraintPredicate.TargetMatches(
                                TargetFeatureBonusRequirement(
                                    window = SlotWindow(1, 1),
                                    selector = selector,
                                    ruleId = ruleId,
                                    message = message,
                                    bonus = 0.0,
                                )
                            )
                        )
                    )
                ),
                modality = ConstraintModality.Annotate,
                ruleId = ruleId,
                explanation = ConstraintExplanation(message, message),
            )
        val targetLabel = chord.appliedToDegree ?: return emptyList()
        return listOf(
            annotation(
                FUNCTION_RULE_ID,
                "使用了指向第 $targetLabel 级的副属或副导功能。",
            ),
            annotation(
                MODAL_DERIVATION_RULE_ID,
                "该变化和弦由 ${chord.modalOrigins.joinToString("/") { it.name }} 调式派生。",
            ),
        )
    }

    /**
     * The local leading tone belongs to the applied function, not necessarily to the global
     * melodic-minor turn. Consequently B-D#-F# in a minor may resolve D# to global degree 5
     * instead of forcing D#-E#. This scoped requirement replaces, rather than duplicates, the
     * global #4-#5 rule for the secondary chord slot.
     */
    internal fun appliedTendencyRequirements(
        progression: SchoenbergSymbolicProgression,
        key: Key,
        window: SlotWindow,
    ): List<ChordToneNeighborRequirement> =
        progression.slots.mapIndexedNotNull { slot, chord ->
            tendencyRequirement(chord, key, window, sourceSlot = slot)
        }

    /**
     * Open-domain variant used by the integrated exercise and forbidden-transition generator.
     * Exact target identities keep distinct modal interpretations from imposing conflicting resolutions.
     */
    internal fun appliedTendencyRequirements(
        key: Key,
        window: SlotWindow,
    ): List<ChordToneNeighborRequirement> =
        harmonyChoices(key).mapNotNull { choice ->
            tendencyRequirement(choice.chord, key, window, sourceSlot = null)
        }

    private fun tendencyRequirement(
        chord: SchoenbergSymbolicChord,
        key: Key,
        window: SlotWindow,
        sourceSlot: Int?,
    ): ChordToneNeighborRequirement? {
        val family = chord.secondaryFamily ?: return null
        val targetDegree = chord.appliedToDegree ?: return null
        val sourceTone = when (family) {
            SecondaryHarmonyFamily.SECONDARY_DOMINANT -> ChordTone.THIRD
            SecondaryHarmonyFamily.SECONDARY_LEADING -> ChordTone.ROOT
            SecondaryHarmonyFamily.MODAL_AUGMENTED -> ChordTone.FIFTH
            SecondaryHarmonyFamily.MODAL_DESCENDING_DOMINANT -> return null
        }
        val target = chord.toTarget(exerciseTriads(key, includeLeadingTriad = true))
        val sourcePitchClass = target.pitchClassFor(sourceTone) ?: return null
        return ChordToneNeighborRequirement(
            window = window,
            sourceSlot = sourceSlot,
            sourceTone = sourceTone,
            direction = ChordToneNeighborDirection.NEXT,
            candidateScaleDegrees = setOf(targetDegree),
            candidateAlterations = setOf(0),
            allowedDiatonicStepDeltas = setOf(1),
            sourceSelector = TargetSelector(identityKeys = setOf(target.identityKey())),
            sourcePitchClasses = setOf(sourcePitchClass),
            ruleId = LEADING_TONE_RULE_ID,
            explanation = ConstraintExplanation(
                satisfied = "副属功能的局部导音已上行到临时主音。",
                violated = "副属或副导和弦的局部导音必须上行到临时主音。",
            ),
        )
    }

    internal fun allowsPreparation(
        preparation: SchoenbergSymbolicChord,
        applied: SchoenbergSymbolicChord,
        key: Key,
        triads: List<NaturalTriad>,
    ): Boolean {
        if (applied.isRootlessDominantNinth()) return true
        if (
            applied.secondaryFamily == null ||
            preparation.degree == applied.degree && preparation.rootAlteration == applied.rootAlteration
        ) return false
        val appliedTarget = applied.toTarget(triads)
        val preparationPitchClasses = buildSet {
            if (applied.arity == ChordArity.SEVENTH) {
                appliedTarget.pitchClassFor(ChordTone.SEVENTH)?.let(::add)
            }
            if (
                applied.secondaryFamily == SecondaryHarmonyFamily.SECONDARY_LEADING ||
                applied.secondaryFamily == SecondaryHarmonyFamily.MODAL_AUGMENTED &&
                    key.mode == Mode.AEOLIAN
            ) {
                appliedTarget.pitchClassFor(ChordTone.FIFTH)?.let(::add)
            }
        }
        val preparationPitches = preparation.toTarget(triads).sonority.pitchClasses
        return if (preparationPitchClasses.isNotEmpty()) {
            preparationPitchClasses.all { it in preparationPitches }
        } else {
            preparationPitches.any { it in appliedTarget.sonority.pitchClasses }
        }
    }

    internal fun allowsResolution(
        applied: SchoenbergSymbolicChord,
        resolution: SchoenbergSymbolicChord,
        key: Key,
        triads: List<NaturalTriad>,
    ): Boolean {
        val tonicizedDegree = applied.appliedToDegree ?: return false
        if (resolution.secondaryFamily != null || resolution.degree !in resolutionDegrees(tonicizedDegree)) return false
        if (resolution.arity != ChordArity.TRIAD || resolution.triadIn(triads).isLeadingTriad()) return false
        val tonicizedTonic = TonalContext.fromKey(key).pitchClassForDegree(tonicizedDegree)
        return tonicizedTonic in resolution.toTarget(triads).sonority.pitchClasses
    }

    internal fun exerciseChords(key: Key): List<SchoenbergSymbolicChord> =
        harmonyChoices(key).map { it.chord }

    private fun exerciseHarmonyTypes(key: Key): List<SecondaryHarmonyType> =
        harmonyTypes(key).filter { type ->
            type.tonicizedDegree != 1 || type.family in setOf(
                SecondaryHarmonyFamily.MODAL_AUGMENTED,
                SecondaryHarmonyFamily.MODAL_DESCENDING_DOMINANT,
            )
        }

    internal fun SecondaryHarmonyType.toSymbolic(): SchoenbergSymbolicChord =
        SchoenbergSymbolicChord(
            degree = rootDegree,
            quality = quality,
            position = if (
                arity == ChordArity.TRIAD &&
                family == SecondaryHarmonyFamily.SECONDARY_LEADING
            ) {
                TextbookTriadPosition.FIRST_INVERSION
            } else {
                TextbookTriadPosition.ROOT_POSITION
            },
            arity = arity,
            seventhPosition = if (arity == ChordArity.SEVENTH) {
                TextbookSeventhPosition.ROOT_POSITION
            } else {
                null
            },
            rootAlteration = rootAlteration,
            appliedToDegree = tonicizedDegree,
            secondaryFamily = family,
            modalOrigins = modalOrigins,
        )

    private fun resolutionDegrees(tonicizedDegree: Int): List<Int> =
        listOf(
            tonicizedDegree,
            wrapDegree(tonicizedDegree + 5), // local VI: deceptive resolution
            wrapDegree(tonicizedDegree + 3), // local IV: alternative deceptive resolution
        )

    private fun wrapDegree(degree: Int): Int = (degree - 1).mod(7) + 1

    private fun SecondaryHarmonyFamily.categoryId(): ChordCatalogCategoryId = ChordCatalogCategoryId(
        when (this) {
            SecondaryHarmonyFamily.SECONDARY_DOMINANT -> "secondary-dominants"
            SecondaryHarmonyFamily.SECONDARY_LEADING -> "secondary-leading"
            SecondaryHarmonyFamily.MODAL_AUGMENTED -> "augmented-triads"
            SecondaryHarmonyFamily.MODAL_DESCENDING_DOMINANT -> "modal-colors"
        }
    )

    private fun SecondaryHarmonyFamily.detailNameKey(): String = when (this) {
        SecondaryHarmonyFamily.SECONDARY_DOMINANT -> "chordDetail.secondaryHarmony.secondaryDominantName"
        SecondaryHarmonyFamily.SECONDARY_LEADING -> "chordDetail.secondaryHarmony.secondaryLeadingName"
        SecondaryHarmonyFamily.MODAL_AUGMENTED -> "chordDetail.secondaryHarmony.modalAugmentedName"
        SecondaryHarmonyFamily.MODAL_DESCENDING_DOMINANT -> "chordDetail.secondaryHarmony.modalColorName"
    }

    private fun SecondaryHarmonyFamily.functionDescriptionKey(): String = when (this) {
        SecondaryHarmonyFamily.SECONDARY_DOMINANT -> "chordDetail.secondaryHarmony.secondaryDominantFunction"
        SecondaryHarmonyFamily.SECONDARY_LEADING -> "chordDetail.secondaryHarmony.secondaryLeadingFunction"
        SecondaryHarmonyFamily.MODAL_AUGMENTED,
        SecondaryHarmonyFamily.MODAL_DESCENDING_DOMINANT,
        -> "chordDetail.secondaryHarmony.colorFunction"
    }

    private val SECONDARY_HARMONY_SOURCE = TheorySourceRef(
        sourceId = "schoenberg-harmonielehre",
        edition = "3rd-edition",
        chapterOrTopic = "secondary dominants and church-mode derivation",
        claimKind = TheoryClaimKind.PRIMARY_SOURCE,
    )

    private const val EXERCISE_LENGTH = 3
    private const val MAX_PREPARATIONS_PER_TYPE = 2
    private const val MAX_ENUMERATED_PROGRESSIONS = 96
}
