package com.mecon.theory.schoenberg

import com.mecon.theory.BuiltInChordDefinitions
import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.Key
import com.mecon.theory.Mode
import com.mecon.theory.RuleId
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.TonalContext
import com.mecon.theory.constraint.ChordTarget
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.constraint.ChordToneNeighborDirection
import com.mecon.theory.constraint.ChordToneNeighborRequirement
import com.mecon.theory.constraint.ChordToneVoiceFilter
import com.mecon.theory.constraint.Constraint
import com.mecon.theory.constraint.ConstraintExplanation
import com.mecon.theory.constraint.ConstraintExpr
import com.mecon.theory.constraint.ConstraintModality
import com.mecon.theory.constraint.ConstraintPredicate
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.SecondaryHarmonyFamily
import com.mecon.theory.constraint.SlotDomain
import com.mecon.theory.constraint.TargetFeatureBonusRequirement
import com.mecon.theory.constraint.TargetSelector
import com.mecon.theory.constraint.ToneCompletenessRequirement
import com.mecon.theory.harmony.ChordBuilder
import com.mecon.theory.harmony.ChordCatalog
import com.mecon.theory.harmony.ChordCatalogCategoryDescriptor
import com.mecon.theory.harmony.ChordCatalogChapterProvider
import com.mecon.theory.harmony.ChordCatalogContribution
import com.mecon.theory.harmony.ChordCatalogCategoryId
import com.mecon.theory.harmony.ChordCatalogNamedSubset
import com.mecon.theory.harmony.ChordChapterDescriptor
import com.mecon.theory.harmony.ChordConstructionDetail
import com.mecon.theory.harmony.ChordConstructionTone
import com.mecon.theory.harmony.ChordDetailDefinition
import com.mecon.theory.harmony.ChordExplanationId
import com.mecon.theory.harmony.ChordFunctionDetail
import com.mecon.theory.harmony.ChordFunctionRelation
import com.mecon.theory.harmony.ChordInterpretationRef
import com.mecon.theory.harmony.ChordKnowledgeChapterProvider
import com.mecon.theory.harmony.ChordKnowledgeContribution
import com.mecon.theory.harmony.ChordKnowledgeContributionId
import com.mecon.theory.harmony.ChordStructureDetail
import com.mecon.theory.harmony.ChordSummary
import com.mecon.theory.harmony.ChordVoiceLeadingDetail
import com.mecon.theory.harmony.ChordConstructionContext
import com.mecon.theory.harmony.ChordInterpretation
import com.mecon.theory.harmony.ChordRecipeId
import com.mecon.theory.harmony.ConstructionTrace
import com.mecon.theory.harmony.ConstructionOperation
import com.mecon.theory.harmony.ConstructionRoute
import com.mecon.theory.harmony.ConstructionRouteId
import com.mecon.theory.harmony.ConstructionTonePresence
import com.mecon.theory.harmony.ConstructionToneRole
import com.mecon.theory.harmony.ConstructedChord
import com.mecon.theory.harmony.DiscoverableChordCatalogChapter
import com.mecon.theory.harmony.DiscoverableChordKnowledgeChapter
import com.mecon.theory.harmony.FunctionalChordSymbol
import com.mecon.theory.harmony.HarmonicFunction
import com.mecon.theory.harmony.InterpretationId
import com.mecon.theory.harmony.InterpretationTag
import com.mecon.theory.harmony.InterpretationTrace
import com.mecon.theory.harmony.TonalLens
import com.mecon.theory.harmony.TheoryClaimKind
import com.mecon.theory.harmony.TheorySourceRef
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookTriadPosition

/**
 * 小下属关系和弦：把同主音小调与下属小调的自然音级和弦投影回当前大调。
 *
 * 构造、符号枚举与四部排布共用同一组 [BorrowedSpec]。独立连接练习先使用自然音级和弦作前后文；
 * 变化音的半音下行是软偏好，而拿坡里与类拿坡里练习把各自的典型连接提升为硬约束。
 */
@DiscoverableChordCatalogChapter
@DiscoverableChordKnowledgeChapter
object SchoenbergMinorSubdominantChapter :
    ChordCatalogChapterProvider,
    ChordKnowledgeChapterProvider {
    private val CHAPTER = ChordChapterDescriptor("schoenberg.minor-subdominant", 800)

    override val chordCatalogContributions = listOf(
        ChordCatalogContribution(
            category = ChordCatalogCategoryDescriptor(
                id = "minor-subdominant-related",
                order = 810,
                titleKey = "exploration.chordCatalog.minorSubdominant.title",
                descriptionKey = "exploration.chordCatalog.minorSubdominant.description",
            ),
            chapter = CHAPTER,
            construct = ::constructedChords,
            namedSubsets = listOf(
                ChordCatalogNamedSubset(
                    category = ChordCatalogCategoryDescriptor(
                        id = "neapolitan",
                        order = 800,
                        titleKey = "exploration.chordCatalog.neapolitan.title",
                        descriptionKey = "exploration.chordCatalog.neapolitan.description",
                    ),
                    matches = ::isNeapolitan,
                )
            ),
        )
    )

    override val chordKnowledgeContributions = listOf(
        ChordKnowledgeContribution(
            id = ChordKnowledgeContributionId("schoenberg.minor-subdominant"),
            chapterId = CHAPTER.id,
            chapter = CHAPTER,
            construct = { context ->
                constructedChords(
                    context,
                    Key(context.tonic.pitchClass, Mode.IONIAN),
                )
            },
            details = { knowledgeContext, catalog ->
                minorSubdominantDetails(knowledgeContext.tonalContext, catalog)
            },
        )
    )

    val DERIVATION_RULE_ID = RuleId("schoenberg.minor-subdominant.derivation")
    val ALTERED_TONE_APPROACH_RULE_ID =
        RuleId("schoenberg.minor-subdominant.altered-tone-approach")
    val ALTERED_TONE_DEPARTURE_RULE_ID =
        RuleId("schoenberg.minor-subdominant.altered-tone-departure")
    val NEAPOLITAN_TO_SIX_FOUR_RULE_ID =
        RuleId("schoenberg.minor-subdominant.neapolitan-to-six-four")
    val NEAPOLITAN_DIRECT_DOMINANT_RULE_ID =
        RuleId("schoenberg.minor-subdominant.neapolitan-direct-dominant")
    val ANALOGOUS_NEAPOLITAN_RULE_ID =
        RuleId("schoenberg.minor-subdominant.analogous-neapolitan-connection")
    val ANALOGOUS_CADENTIAL_BASS_RULE_ID =
        RuleId("schoenberg.minor-subdominant.analogous-cadential-bass")

    fun borrowedChords(
        key: Key,
        includeSevenths: Boolean = true,
    ): List<SchoenbergSymbolicChord> {
        requireMajor(key)
        return BORROWED_SPECS.flatMap { spec ->
            buildList {
                add(spec.toSymbolic(ChordArity.TRIAD))
                if (includeSevenths) add(spec.toSymbolic(ChordArity.SEVENTH))
            }
        }
    }

    fun enumerateConnections(key: Key): List<SchoenbergSymbolicProgression> {
        requireMajor(key)
        val triads = exerciseTriads(key, includeLeadingTriad = true)
        val natural = triads.map { it.toSymbolic(TextbookTriadPosition.ROOT_POSITION) }
        return borrowedChords(key).flatMap { borrowed ->
            val target = borrowed.toTarget(triads)
            natural.flatMap { before ->
                val beforeTarget = before.toTarget(triads)
                natural.mapNotNull { after ->
                    val afterTarget = after.toTarget(triads)
                    val slots = listOf(before, borrowed, after)
                    if (!supportsPreferredAlteredMotion(key, target, beforeTarget, afterTarget)) {
                        return@mapNotNull null
                    }
                    if (!supportsSeventhPreparationAndResolution(key, target, beforeTarget, afterTarget)) {
                        return@mapNotNull null
                    }
                    if (!SchoenbergRootMotionAndRepetitionChapter.followsDirectionPolicy(slots)) {
                        return@mapNotNull null
                    }
                    SchoenbergSymbolicProgression(
                        slots = slots,
                        kind = SchoenbergConnectionKind.MINOR_SUBDOMINANT_CONNECTION,
                        knowledgeTags = setOf(SchoenbergKnowledgeTag.MINOR_SUBDOMINANT),
                    )
                }
            }
        }
            .distinctBy { progression -> progression.slots.map(SchoenbergSymbolicChord::transitionToken) }
            .sortedWith(
                compareBy<SchoenbergSymbolicProgression> {
                    it.slots[1].arity != ChordArity.TRIAD
                }.thenBy { it.slots[1].degree }
                    .thenBy { it.slots[1].rootAlteration }
            )
            .take(MAX_CONNECTIONS)
    }

    fun connectionProgram(
        key: Key,
        progression: SchoenbergSymbolicProgression? = null,
        searchConfig: SearchConfig = SearchConfig(maxResults = 4, beamWidth = 256),
    ): ConstraintProgram {
        requireMajor(key)
        val selected = progression ?: enumerateConnections(key).firstOrNull()
            ?: error("Current key has no minor-subdominant connection")
        require(selected.slots.size == CONNECTION_LENGTH)
        require(selected.slots[1].isMinorSubdominantBorrowing()) {
            "The middle chord must come from the minor-subdominant vocabulary"
        }
        val triads = exerciseTriads(key, includeLeadingTriad = true)
        val domains = exactProgressionDomains(selected, triads, CONNECTION_LENGTH)
        val seventhRules = SchoenbergSeventhChordChapter.inferredDissonanceConstraints(
            key = key,
            domains = domains,
            includeResolution = true,
        )
        val alteredPreferences = alteredMotionConstraints(
            key = key,
            sourceSlot = 1,
            target = domains[1].targets.single(),
            requireApproach = true,
            required = false,
        )
        return baseProgram(
            key = key,
            domains = domains,
            searchConfig = searchConfig,
            constraints = seventhRules +
                seventhRules.map(Constraint::asSuccessAnnotation) +
                alteredPreferences +
                alteredPreferences.map(Constraint::asSuccessAnnotation) +
                rootMotionConstraints() +
                derivationAnnotation(1, domains[1].targets.single()),
        )
    }

    fun enumerateNeapolitanCadences(key: Key): List<SchoenbergSymbolicProgression> {
        val triads = exerciseTriads(key, includeLeadingTriad = true)
        val neapolitan = neapolitan(key).copy(position = TextbookTriadPosition.FIRST_INVERSION)
        val tonic = triads.first { it.degree == TONIC_DEGREE }
        val dominant = triads.first { it.degree == DOMINANT_DEGREE && it.quality == ChordQuality.MAJOR }
        val tonicRoot = tonic.toSymbolic(TextbookTriadPosition.ROOT_POSITION)
        val dominantRoot = dominant.toSymbolic(TextbookTriadPosition.ROOT_POSITION)
        return listOf(
            SchoenbergSymbolicProgression(
                slots = listOf(
                    neapolitan,
                    tonic.toSymbolic(TextbookTriadPosition.SECOND_INVERSION),
                    dominantRoot,
                    tonicRoot,
                ),
                kind = SchoenbergConnectionKind.NEAPOLITAN_CADENCE,
                knowledgeTags = setOf(
                    SchoenbergKnowledgeTag.MINOR_SUBDOMINANT,
                    SchoenbergKnowledgeTag.NEAPOLITAN,
                ),
            ),
            SchoenbergSymbolicProgression(
                slots = listOf(neapolitan, dominantRoot, tonicRoot),
                kind = SchoenbergConnectionKind.NEAPOLITAN_CADENCE,
                knowledgeTags = setOf(
                    SchoenbergKnowledgeTag.MINOR_SUBDOMINANT,
                    SchoenbergKnowledgeTag.NEAPOLITAN,
                ),
            ),
        )
    }

    fun neapolitanCadenceProgram(
        key: Key,
        progression: SchoenbergSymbolicProgression? = null,
        searchConfig: SearchConfig = SearchConfig(maxResults = 4, beamWidth = 256),
    ): ConstraintProgram {
        val selected = progression ?: enumerateNeapolitanCadences(key).first()
        require(selected.slots.size in setOf(DIRECT_CADENCE_LENGTH, SIX_FOUR_CADENCE_LENGTH))
        require(selected.slots.first().isNeapolitan()) {
            "Neapolitan cadence exercise must begin with flat-II"
        }
        val triads = exerciseTriads(key, includeLeadingTriad = true)
        val domains = exactProgressionDomains(selected, triads, selected.slots.size)
        val throughSixFour = selected.slots.size == SIX_FOUR_CADENCE_LENGTH
        val movementRules = neapolitanDepartureConstraints(
            key = key,
            target = domains.first().targets.single(),
            throughSixFour = throughSixFour,
        )
        val cadencePolicy = SchoenbergCadencePolicy(
            options = SchoenbergCadenceOptions(includeCadentialSixFour = throughSixFour),
            minor = key.mode == Mode.AEOLIAN,
        )
        return baseProgram(
            key = key,
            domains = domains,
            searchConfig = searchConfig,
            constraints = movementRules +
                movementRules.map(Constraint::asSuccessAnnotation) +
                cadencePolicy.constraints(domains.size) +
                derivationAnnotation(0, domains.first().targets.single()),
        )
    }

    fun enumerateAnalogousNeapolitanConnections(
        key: Key,
    ): List<SchoenbergSymbolicProgression> =
        enumerateAnalogousNeapolitanConnections(
            key = key,
            vocabularyFilter = SchoenbergProgressionVocabularyFilters.StudiedInActiveKey,
        )

    internal fun enumerateAnalogousNeapolitanConnections(
        key: Key,
        vocabularyFilter: SchoenbergProgressionVocabularyFilter,
    ): List<SchoenbergSymbolicProgression> {
        requireMajor(key)
        val triads = exerciseTriads(key, includeLeadingTriad = true)
        val majorSources = (
            borrowedChords(key, includeSevenths = false) +
                triads.filter { it.quality == ChordQuality.MAJOR }
                    .map { it.toSymbolic(TextbookTriadPosition.ROOT_POSITION) }
            )
            .filter { it.quality == ChordQuality.MAJOR && !it.isNeapolitan() }
            .distinctBy(SchoenbergSymbolicChord::transitionToken)
        val appliedDominants = SchoenbergSecondaryDominantChapter.harmonyChoices(key)
            .map { it.chord }
            .filter {
                it.secondaryFamily == SecondaryHarmonyFamily.SECONDARY_DOMINANT &&
                    it.arity == ChordArity.TRIAD &&
                    it.quality == ChordQuality.MAJOR
            }
        return majorSources.mapNotNull { source ->
            val localTonicDegree = previousDegree(source.degree)
            val localTonic = triads.firstOrNull {
                it.degree == localTonicDegree &&
                    it.quality in setOf(ChordQuality.MAJOR, ChordQuality.MINOR)
            } ?: return@mapNotNull null
            val appliedDominant = appliedDominants.firstOrNull {
                it.appliedToDegree == localTonicDegree
            } ?: return@mapNotNull null
            val progression = SchoenbergSymbolicProgression(
                slots = listOf(
                    source.copy(position = TextbookTriadPosition.FIRST_INVERSION),
                    localTonic.toSymbolic(TextbookTriadPosition.SECOND_INVERSION),
                    appliedDominant,
                ),
                kind = SchoenbergConnectionKind.ANALOGOUS_NEAPOLITAN,
                knowledgeTags = setOf(
                    SchoenbergKnowledgeTag.MINOR_SUBDOMINANT,
                    SchoenbergKnowledgeTag.NEAPOLITAN,
                    SchoenbergKnowledgeTag.SECONDARY_HARMONY,
                ),
            )
            progression.takeIf {
                AnalogousNeapolitanRelation.allows(key, progression.slots) &&
                    vocabularyFilter.allows(key, progression)
            }
        }
            .distinctBy { progression -> progression.slots.map(SchoenbergSymbolicChord::transitionToken) }
            .sortedByDescending {
                it.slots.first().degree == SUBMEDIANT_DEGREE &&
                    it.slots.first().rootAlteration == -1
            }
    }

    fun analogousNeapolitanProgram(
        key: Key,
        progression: SchoenbergSymbolicProgression? = null,
        searchConfig: SearchConfig = SearchConfig(maxResults = 4, beamWidth = 256),
    ): ConstraintProgram {
        requireMajor(key)
        val selected = progression ?: enumerateAnalogousNeapolitanConnections(key).firstOrNull()
            ?: error("Current key has no analogous Neapolitan connection")
        require(selected.slots.size == ANALOGOUS_LENGTH)
        require(
            selected.slots[0].arity == ChordArity.TRIAD &&
                selected.slots[0].quality == ChordQuality.MAJOR &&
                selected.slots[0].position == TextbookTriadPosition.FIRST_INVERSION &&
                selected.slots[1].position == TextbookTriadPosition.SECOND_INVERSION &&
                selected.slots[2].secondaryFamily == SecondaryHarmonyFamily.SECONDARY_DOMINANT
        ) {
            "Analogous Neapolitan exercise requires major-sixth - local I64 - local V"
        }
        val triads = exerciseTriads(key, includeLeadingTriad = true)
        val domains = exactProgressionDomains(selected, triads, ANALOGOUS_LENGTH)
        val source = domains[0].targets.single()
        val localTonicDegree = selected.slots[1].degree
        val rootRule = neighborConstraint(
            key = key,
            sourceSlot = 0,
            target = source,
            tone = ChordTone.ROOT,
            direction = ChordToneNeighborDirection.NEXT,
            candidateDegree = localTonicDegree,
            candidateAlteration = 0,
            delta = -1,
            ruleId = ANALOGOUS_NEAPOLITAN_RULE_ID,
            modality = ConstraintModality.Require,
            satisfied = "类拿坡里和弦的根音已下行半音到局部主音。",
            violated = "类拿坡里大六和弦的根音必须下行半音到局部主音。",
        )
        val alteredDepartures = alteredMotionConstraints(
            key = key,
            sourceSlot = 0,
            target = source,
            requireApproach = false,
            required = true,
        )
        val bassRule = Constraint(
            expr = ConstraintExpr.Atom(
                ConstraintPredicate.VoiceDiatonicSteps(
                    voiceFilter = ChordToneVoiceFilter.BASS,
                    slots = listOf(1, 2),
                    allowedDeltas = listOf(setOf(0)),
                )
            ),
            modality = ConstraintModality.Require,
            ruleId = ANALOGOUS_CADENTIAL_BASS_RULE_ID,
            explanation = ConstraintExplanation(
                "局部终止四六的低音保持到局部属和弦。",
                "类拿坡里连接中的局部 I64 低音必须保持到局部 V。",
            ),
        )
        return baseProgram(
            key = key,
            domains = domains,
            searchConfig = searchConfig,
            constraints = listOf(rootRule, bassRule) +
                alteredDepartures +
                (listOf(rootRule, bassRule) + alteredDepartures).map(Constraint::asSuccessAnnotation) +
                listOfNotNull(
                    derivationAnnotation(0, source),
                    analogousAnnotation(domains),
                ),
        )
    }

    private fun isNeapolitan(interpretation: ChordInterpretation): Boolean =
        interpretation.symbol.degree == SUPERTONIC_DEGREE &&
            interpretation.symbol.alteration == -1 &&
            when (interpretation.symbol.arity) {
                ChordArity.TRIAD -> interpretation.symbol.quality == ChordQuality.MAJOR
                ChordArity.SEVENTH -> interpretation.symbol.quality == ChordQuality.MAJOR7
            }

    private fun minorSubdominantDetails(
        context: TonalContext,
        catalog: ChordCatalog,
    ): List<ChordDetailDefinition> = catalog.entries.flatMap { entry ->
        entry.interpretations.map { interpretation ->
            val spec = BORROWED_SPECS.single { candidate ->
                candidate.matches(interpretation)
            }
            val isNeapolitan = isNeapolitan(interpretation)
            val ref = ChordInterpretationRef(entry.sonority.id, interpretation.id)
            val borrowedTones = interpretation.structuralToneOrder.mapIndexed { index, toneId ->
                val spelling = requireNotNull(entry.sonority.tone(toneId)).spelling
                spelling.toConstructionTone(context, index)
            }
            ChordDetailDefinition(
                interpretationRef = ref,
                explanationId = if (isNeapolitan) {
                    ChordExplanationId("schoenberg.neapolitan")
                } else {
                    ChordExplanationId("schoenberg.${interpretation.id.value}")
                },
                sourceCategoryIds = setOf(
                    ChordCatalogCategoryId(
                        if (isNeapolitan) "neapolitan" else "minor-subdominant-related"
                    )
                ),
                summary = if (isNeapolitan) {
                    ChordSummary(
                        nameKey = "chordDetail.neapolitan.name",
                        descriptionKey = "chordDetail.neapolitan.summary",
                        tags = listOf("chordDetail.minorSubdominant.borrowedTag", "chordDetail.minorSubdominant.predominantTag"),
                    )
                } else {
                    ChordSummary(
                        nameKey = "chordDetail.minorSubdominant.name",
                        descriptionKey = "chordDetail.minorSubdominant.summary",
                        tags = listOf("chordDetail.minorSubdominant.borrowedTag"),
                    )
                },
                structure = ChordStructureDetail(
                    toneIds = interpretation.structuralToneOrder,
                    propertyKeys = listOf(
                        if (isNeapolitan) {
                            "chordDetail.neapolitan.minorSubdominantOrigin"
                        } else {
                            "chordDetail.minorSubdominant.origin"
                        }
                    ),
                ),
                function = ChordFunctionDetail(
                    function = interpretation.function,
                    descriptionKey = if (isNeapolitan) {
                        "chordDetail.neapolitan.predominantFunction"
                    } else {
                        "chordDetail.minorSubdominant.function"
                    },
                ),
                voiceLeading = ChordVoiceLeadingDetail(
                    connectionRefs = interpretation.treatmentIds.toList(),
                ),
                routes = spec.sources.sortedBy(MinorSubdominantSource::routeOrder).map { source ->
                    ConstructionRoute(
                        id = ConstructionRouteId(
                            "route.${interpretation.id.value}" +
                                if (spec.sources.size == 1) "" else ".${source.id}"
                        ),
                        interpretationRef = ref,
                        routeOrder = source.routeOrder,
                        formulaKey = if (isNeapolitan) {
                            "chordDetail.neapolitan.minorModeSupertonic"
                        } else {
                            "chordDetail.minorSubdominant.borrowingRoute"
                        },
                        steps = listOf(
                            ConstructionOperation.LegacyTrace("minor-subdominant-borrowing.${source.id}")
                        ),
                        connectionRefs = interpretation.treatmentIds.toList(),
                        functionRelations = if (isNeapolitan) {
                            listOf(
                                ChordFunctionRelation.SubstitutesFor(
                                    targetTreatmentId = SchoenbergHarmonicTreatments.DIATONIC_PREDOMINANT,
                                    function = HarmonicFunction.PREDOMINANT,
                                )
                            )
                        } else {
                            emptyList()
                        },
                        construction = ChordConstructionDetail.MinorSubdominantRelation(
                            sourceMode = Mode.AEOLIAN,
                            sourceTonic = context.spellDegree(source.tonicDegree),
                            sourceKeySignatureFifths = context.keySignature?.fifths
                                ?.plus(source.keySignatureDelta)
                                ?.takeIf { it in -7..7 },
                            referenceFunction = source.referenceFunction,
                            referenceTones = source.referenceDegrees.mapIndexed { index, degree ->
                                context.spellDegree(degree).toConstructionTone(context, index)
                            },
                            borrowedTones = borrowedTones,
                        ),
                        sourceRefs = listOf(NEAPOLITAN_SOURCE),
                    )
                },
                sourceRefs = listOf(NEAPOLITAN_SOURCE),
            )
        }
    }

    private fun com.mecon.theory.SpelledPitchClass.toConstructionTone(
        context: TonalContext,
        memberIndex: Int,
    ): ChordConstructionTone {
        val degree = (noteName.ordinal - context.tonic.noteName.ordinal).mod(7) + 1
        val alteration = chromaticOffset - context.spellDegree(degree).chromaticOffset
        return ChordConstructionTone(
            degree = degree,
            alteration = alteration,
            spelling = this,
            role = when (memberIndex) {
                0 -> ConstructionToneRole.ROOT
                1 -> ConstructionToneRole.THIRD
                2 -> ConstructionToneRole.FIFTH
                3 -> ConstructionToneRole.SEVENTH
                else -> ConstructionToneRole.OTHER
            },
            presence = ConstructionTonePresence.SOUNDING,
        )
    }

    internal fun constructedChords(
        context: TonalContext,
        key: Key,
    ): List<ConstructedChord> {
        val chords = if (key.mode == Mode.AEOLIAN) {
            listOf(neapolitan(key))
        } else {
            requireMajor(key)
            borrowedChords(key)
        }
        return chords.map { chord -> construct(context, chord) }
    }

    internal fun interpretationId(chord: SchoenbergSymbolicChord): InterpretationId =
        InterpretationId(
            "minor-subdominant.${chord.degree}.${chord.rootAlteration}." +
                "${chord.quality.name.lowercase()}.${chord.arity.name.lowercase()}"
        )

    private fun baseProgram(
        key: Key,
        domains: List<SlotDomain>,
        searchConfig: SearchConfig,
        constraints: List<Constraint>,
    ): ConstraintProgram {
        val window = SlotWindow(0, domains.lastIndex)
        return ConstraintProgram.fromRequirements(
            key = key,
            slotDomains = domains,
            configuration = com.mecon.theory.constraint.ConstraintRequirementConfiguration(
                ruleProfile = SchoenbergCommonToneExercises.SCHOENBERG_PROFILE,
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
                ),
                constraints = constraints,
                searchConfig = searchConfig,
                ruleModules = emptyList(),
                includeDerivedTextbookConstraints = false,
            ),
        )
    }

    private fun alteredMotionConstraints(
        key: Key,
        sourceSlot: Int,
        target: ChordTarget,
        requireApproach: Boolean,
        required: Boolean,
    ): List<Constraint> =
        alteredTones(key, target).flatMap { altered ->
            buildList {
                if (requireApproach) {
                    add(
                        neighborConstraint(
                            key = key,
                            sourceSlot = sourceSlot,
                            target = target,
                            tone = altered.tone,
                            direction = ChordToneNeighborDirection.PREVIOUS,
                            candidateDegree = altered.degree,
                            candidateAlteration = 0,
                            delta = 0,
                            ruleId = ALTERED_TONE_APPROACH_RULE_ID,
                            modality = if (required) {
                                ConstraintModality.Require
                            } else {
                                ConstraintModality.Prefer(weight = ALTERED_TONE_WEIGHT)
                            },
                            satisfied = "小下属关系和弦的变化音由自然音下行半音引入。",
                            violated = "小下属关系和弦的降音宜由同音级自然音下行半音引入。",
                        )
                    )
                }
                add(
                    neighborConstraint(
                        key = key,
                        sourceSlot = sourceSlot,
                        target = target,
                        tone = altered.tone,
                        direction = ChordToneNeighborDirection.NEXT,
                        candidateDegree = previousDegree(altered.degree),
                        candidateAlteration = 0,
                        delta = -1,
                        ruleId = ALTERED_TONE_DEPARTURE_RULE_ID,
                        modality = if (required) {
                            ConstraintModality.Require
                        } else {
                            ConstraintModality.Prefer(weight = ALTERED_TONE_WEIGHT)
                        },
                        satisfied = "小下属关系和弦的变化音继续下行半音离开。",
                        violated = "小下属关系和弦的降音离开时宜继续下行半音。",
                    )
                )
            }
        }

    private fun neapolitanDepartureConstraints(
        key: Key,
        target: ChordTarget,
        throughSixFour: Boolean,
    ): List<Constraint> {
        val ruleId = if (throughSixFour) {
            NEAPOLITAN_TO_SIX_FOUR_RULE_ID
        } else {
            NEAPOLITAN_DIRECT_DOMINANT_RULE_ID
        }
        val rootDestination = if (throughSixFour) TONIC_DEGREE else LEADING_TONE_DEGREE
        val rootDelta = if (throughSixFour) -1 else -2
        return listOf(
            neighborConstraint(
                key = key,
                sourceSlot = 0,
                target = target,
                tone = ChordTone.ROOT,
                direction = ChordToneNeighborDirection.NEXT,
                candidateDegree = rootDestination,
                candidateAlteration = if (!throughSixFour && key.mode == Mode.AEOLIAN) 1 else 0,
                delta = rootDelta,
                ruleId = ruleId,
                modality = ConstraintModality.Require,
                satisfied = if (throughSixFour) {
                    "拿坡里和弦的降二级下行半音到终止四六的主音。"
                } else {
                    "拿坡里和弦的降二级直接下行减三度到导音。"
                },
                violated = if (throughSixFour) {
                    "拿坡里和弦进入终止四六时，降二级必须下行半音到主音。"
                } else {
                    "拿坡里和弦直达属和弦时，降二级必须下行减三度到导音。"
                },
            ),
            neighborConstraint(
                key = key,
                sourceSlot = 0,
                target = target,
                tone = ChordTone.FIFTH,
                direction = ChordToneNeighborDirection.NEXT,
                candidateDegree = DOMINANT_DEGREE,
                candidateAlteration = 0,
                delta = -1,
                ruleId = ruleId,
                modality = ConstraintModality.Require,
                satisfied = "拿坡里和弦的降六级已下行半音到属音。",
                violated = "拿坡里和弦的降六级必须下行半音到属音。",
            ),
        )
    }

    private fun neighborConstraint(
        key: Key,
        sourceSlot: Int,
        target: ChordTarget,
        tone: ChordTone,
        direction: ChordToneNeighborDirection,
        candidateDegree: Int,
        candidateAlteration: Int,
        delta: Int,
        ruleId: RuleId,
        modality: ConstraintModality,
        satisfied: String,
        violated: String,
    ): Constraint =
        Constraint(
            expr = ConstraintExpr.Atom(
                ConstraintPredicate.NeighborTone(
                    ChordToneNeighborRequirement(
                        window = when (direction) {
                            ChordToneNeighborDirection.PREVIOUS ->
                                SlotWindow(sourceSlot - 1, sourceSlot)
                            ChordToneNeighborDirection.NEXT ->
                                SlotWindow(sourceSlot, sourceSlot + 1)
                        },
                        sourceSlot = sourceSlot,
                        sourceTone = tone,
                        direction = direction,
                        candidateScaleDegrees = setOf(candidateDegree),
                        candidateAlterations = setOf(candidateAlteration),
                        allowedDiatonicStepDeltas = setOf(delta),
                        sourceSelector = TargetSelector(identityKeys = setOf(target.identityKey())),
                        sourcePitchClasses = setOfNotNull(target.pitchClassFor(tone)),
                        tonalKey = key,
                        ruleId = ruleId,
                        explanation = ConstraintExplanation(satisfied, violated),
                    )
                )
            ),
            modality = modality,
            ruleId = ruleId,
            explanation = ConstraintExplanation(satisfied, violated),
        )

    private fun supportsPreferredAlteredMotion(
        key: Key,
        borrowed: ChordTarget,
        before: ChordTarget,
        after: ChordTarget,
    ): Boolean =
        alteredTones(key, borrowed).all { altered ->
            key.scale.pitchClasses[altered.degree - 1] in before.sonority.pitchClasses &&
                key.scale.pitchClasses[previousDegree(altered.degree) - 1] in after.sonority.pitchClasses
        }

    private fun supportsSeventhPreparationAndResolution(
        key: Key,
        borrowed: ChordTarget,
        before: ChordTarget,
        after: ChordTarget,
    ): Boolean {
        if (borrowed.arity != ChordArity.SEVENTH) return true
        val seventh = borrowed.pitchClassFor(ChordTone.SEVENTH) ?: return false
        val coordinate = toneCoordinate(key, borrowed, ChordTone.SEVENTH) ?: return false
        val resolution = key.scale.pitchClasses[previousDegree(coordinate.degree) - 1]
        return seventh in before.sonority.pitchClasses &&
            resolution in after.sonority.pitchClasses
    }

    private fun alteredTones(
        key: Key,
        target: ChordTarget,
    ): List<ToneCoordinate> =
        structuralTones(target.arity).mapNotNull { tone ->
            toneCoordinate(key, target, tone)?.takeIf { it.alteration < 0 }
        }

    private fun toneCoordinate(
        key: Key,
        target: ChordTarget,
        tone: ChordTone,
    ): ToneCoordinate? {
        val pitchClass = target.pitchClassFor(tone) ?: return null
        val spelling = target.spellingFor(pitchClass) ?: return null
        val context = TonalContext.fromKey(key)
        val degree = (1..7).firstOrNull {
            context.spellDegree(it).noteName == spelling.noteName
        } ?: return null
        val natural = context.spellDegree(degree)
        return ToneCoordinate(
            tone = tone,
            degree = degree,
            alteration = spelling.chromaticOffset - natural.chromaticOffset,
        )
    }

    private fun derivationAnnotation(
        slot: Int,
        target: ChordTarget,
    ): Constraint =
        annotation(
            slot = slot,
            selector = TargetSelector(identityKeys = setOf(target.identityKey())),
            ruleId = DERIVATION_RULE_ID,
            message = "该和弦由同主音小调或下属小调的自然音级和弦借入。",
        )

    private fun analogousAnnotation(domains: List<SlotDomain>): Constraint =
        annotation(
            slot = 0,
            selector = TargetSelector(identityKeys = setOf(domains[0].targets.single().identityKey())),
            ruleId = ANALOGOUS_NEAPOLITAN_RULE_ID,
            message = "把大六和弦当作局部拿坡里和弦，连接到调内局部 I64 与其属和弦。",
        )

    private fun annotation(
        slot: Int,
        selector: TargetSelector,
        ruleId: RuleId,
        message: String,
    ): Constraint =
        Constraint(
            expr = ConstraintExpr.Atom(
                ConstraintPredicate.TargetMatches(
                    TargetFeatureBonusRequirement(
                        window = SlotWindow(slot, slot),
                        selector = selector,
                        ruleId = ruleId,
                        message = message,
                        bonus = 0.0,
                    )
                )
            ),
            modality = ConstraintModality.Annotate,
            ruleId = ruleId,
            explanation = ConstraintExplanation(message),
        )

    private fun rootMotionConstraints(): List<Constraint> {
        val ids = setOf(
            SchoenbergRootMotionAndRepetitionChapter.RISING_RULE_ID,
            SchoenbergRootMotionAndRepetitionChapter.DESCENDING_RULE_ID,
            SchoenbergRootMotionAndRepetitionChapter.SUPERSTRONG_RULE_ID,
            SchoenbergRootMotionAndRepetitionChapter.DESCENDING_COMPENSATION_RULE_ID,
            SchoenbergRootMotionAndRepetitionChapter.ROOT_PROGRESSION_SCORE_RULE_ID,
        )
        return SchoenbergRootMotionAndRepetitionChapter
            .constraints(SlotWindow(0, CONNECTION_LENGTH - 1))
            .filter { it.ruleId in ids }
    }

    private fun construct(
        context: TonalContext,
        chord: SchoenbergSymbolicChord,
    ): ConstructedChord {
        val recipeId = ChordRecipeId("schoenberg.minor-subdominant")
        val definition = BuiltInChordDefinitions.forQuality(chord.quality)
        val root = context.spellDegree(chord.degree, chord.rootAlteration)
        val interpretation = ChordInterpretation(
            id = interpretationId(chord),
            lens = TonalLens(context.id, context),
            symbol = FunctionalChordSymbol(
                degree = chord.degree,
                alteration = chord.rootAlteration,
                quality = chord.quality,
                arity = chord.arity,
            ),
            function = when (chord.degree) {
                1 -> HarmonicFunction.TONIC
                2, 4, 6 -> HarmonicFunction.PREDOMINANT
                5, 7 -> HarmonicFunction.DOMINANT
                else -> HarmonicFunction.OTHER
            },
            toneRoles = ChordBuilder.structuralToneRoles(definition, root),
            structuralToneOrder = ChordBuilder.structuralToneOrder(definition, root),
            treatmentIds = if (chord.isNeapolitan()) {
                setOf(SchoenbergHarmonicTreatments.NEAPOLITAN)
            } else {
                setOf(SchoenbergHarmonicTreatments.MINOR_SUBDOMINANT)
            },
            tags = setOf(
                InterpretationTag("function.minor-subdominant"),
                InterpretationTag("modal-origin.aeolian"),
            ),
            trace = InterpretationTrace(
                recipeId,
                listOf("same-tonic-or-subdominant-natural-minor"),
            ),
        )
        return ChordBuilder.fromDefinition(
            context = ChordConstructionContext(context),
            definition = definition,
            rootDegree = chord.degree,
            rootAlteration = chord.rootAlteration,
            interpretation = interpretation,
            trace = ConstructionTrace(
                recipeId,
                listOf("quality-${chord.quality.name.lowercase()}"),
            ),
        )
    }

    private fun neapolitan(key: Key): SchoenbergSymbolicChord =
        (if (key.mode == Mode.AEOLIAN) {
            BORROWED_SPECS.map { it.toSymbolic(ChordArity.TRIAD) }
        } else {
            borrowedChords(key, includeSevenths = false)
        }).first {
            it.degree == SUPERTONIC_DEGREE &&
                it.rootAlteration == -1 &&
                it.quality == ChordQuality.MAJOR
        }

    private fun SchoenbergSymbolicChord.isMinorSubdominantBorrowing(): Boolean =
        Mode.AEOLIAN in modalOrigins

    private fun SchoenbergSymbolicChord.isNeapolitan(): Boolean =
        degree == SUPERTONIC_DEGREE &&
            rootAlteration == -1 &&
            when (arity) {
                ChordArity.TRIAD -> quality == ChordQuality.MAJOR
                ChordArity.SEVENTH -> quality == ChordQuality.MAJOR7
            }

    private fun requireMajor(key: Key) {
        require(key.mode == Mode.IONIAN) {
            "Minor-subdominant related chords are derived against a major-key reference"
        }
    }

    private fun previousDegree(degree: Int): Int = (degree + 5).mod(7) + 1

    private fun structuralTones(arity: ChordArity): List<ChordTone> =
        if (arity == ChordArity.SEVENTH) TRIAD_TONES + ChordTone.SEVENTH else TRIAD_TONES

    private data class ToneCoordinate(
        val tone: ChordTone,
        val degree: Int,
        val alteration: Int,
    )

    private data class BorrowedSpec(
        val degree: Int,
        val rootAlteration: Int,
        val triadQuality: ChordQuality,
        val seventhQuality: ChordQuality,
        val sources: Set<MinorSubdominantSource>,
    ) {
        fun toSymbolic(arity: ChordArity): SchoenbergSymbolicChord =
            SchoenbergSymbolicChord(
                degree = degree,
                quality = if (arity == ChordArity.TRIAD) triadQuality else seventhQuality,
                arity = arity,
                seventhPosition = if (arity == ChordArity.SEVENTH) {
                    TextbookSeventhPosition.ROOT_POSITION
                } else {
                    null
                },
                rootAlteration = rootAlteration,
                modalOrigins = setOf(Mode.AEOLIAN),
            )

        fun matches(interpretation: ChordInterpretation): Boolean =
            interpretation.symbol.degree == degree &&
                interpretation.symbol.alteration == rootAlteration &&
                interpretation.symbol.quality == when (interpretation.symbol.arity) {
                    ChordArity.TRIAD -> triadQuality
                    ChordArity.SEVENTH -> seventhQuality
                }
    }

    private enum class MinorSubdominantSource(
        val id: String,
        val tonicDegree: Int,
        val keySignatureDelta: Int,
        val referenceFunction: HarmonicFunction,
        val routeOrder: Int,
    ) {
        TONIC_MINOR("tonic-minor", 1, -3, HarmonicFunction.DOMINANT, 0),
        SUBDOMINANT_MINOR("subdominant-minor", 4, -4, HarmonicFunction.TONIC, 1);

        val referenceDegrees: List<Int>
            get() = when (referenceFunction) {
                HarmonicFunction.TONIC -> listOf(1, 3, 5)
                HarmonicFunction.DOMINANT -> listOf(5, 7, 2)
                else -> error("Unsupported minor-subdominant reference function $referenceFunction")
            }
    }

    private val BORROWED_SPECS = listOf(
        BorrowedSpec(1, 0, ChordQuality.MINOR, ChordQuality.MINOR7, MinorSubdominantSource.entries.toSet()),
        BorrowedSpec(2, 0, ChordQuality.DIMINISHED, ChordQuality.HALF_DIMINISHED7, setOf(MinorSubdominantSource.TONIC_MINOR)),
        BorrowedSpec(3, -1, ChordQuality.MAJOR, ChordQuality.MAJOR7, MinorSubdominantSource.entries.toSet()),
        BorrowedSpec(4, 0, ChordQuality.MINOR, ChordQuality.MINOR7, MinorSubdominantSource.entries.toSet()),
        BorrowedSpec(5, 0, ChordQuality.MINOR, ChordQuality.MINOR7, setOf(MinorSubdominantSource.TONIC_MINOR)),
        BorrowedSpec(6, -1, ChordQuality.MAJOR, ChordQuality.MAJOR7, MinorSubdominantSource.entries.toSet()),
        BorrowedSpec(7, -1, ChordQuality.MAJOR, ChordQuality.DOMINANT7, setOf(MinorSubdominantSource.TONIC_MINOR)),
        BorrowedSpec(5, 0, ChordQuality.DIMINISHED, ChordQuality.HALF_DIMINISHED7, setOf(MinorSubdominantSource.SUBDOMINANT_MINOR)),
        BorrowedSpec(7, -1, ChordQuality.MINOR, ChordQuality.MINOR7, setOf(MinorSubdominantSource.SUBDOMINANT_MINOR)),
        BorrowedSpec(2, -1, ChordQuality.MAJOR, ChordQuality.MAJOR7, setOf(MinorSubdominantSource.SUBDOMINANT_MINOR)),
    )
    private val NEAPOLITAN_SOURCE = TheorySourceRef(
        sourceId = "schoenberg-harmonielehre",
        edition = "3rd-edition",
        chapterOrTopic = "minor subdominant and Neapolitan harmony",
        claimKind = TheoryClaimKind.PRIMARY_SOURCE,
    )
    private val TRIAD_TONES = listOf(ChordTone.ROOT, ChordTone.THIRD, ChordTone.FIFTH)
    private const val ALTERED_TONE_WEIGHT = 6.0
    private const val CONNECTION_LENGTH = 3
    private const val DIRECT_CADENCE_LENGTH = 3
    private const val SIX_FOUR_CADENCE_LENGTH = 4
    private const val ANALOGOUS_LENGTH = 3
    private const val MAX_CONNECTIONS = 96
    private const val SUPERTONIC_DEGREE = 2
    private const val DOMINANT_DEGREE = 5
}
