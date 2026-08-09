package com.mecon.theory.schoenberg

import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.Key
import com.mecon.theory.Mode
import com.mecon.theory.RuleId
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.TonalContext
import com.mecon.theory.constraint.AugmentedSixthFamily
import com.mecon.theory.constraint.AugmentedSixthMetadata
import com.mecon.theory.constraint.AugmentedSixthType
import com.mecon.theory.constraint.AugmentedSixthVocabulary
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.constraint.ChordToneNeighborDirection
import com.mecon.theory.constraint.ChordToneNeighborRequirement
import com.mecon.theory.constraint.ConstraintExplanation
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.TargetSelector
import com.mecon.theory.constraint.ToneCompletenessRequirement
import com.mecon.theory.harmony.ChordCatalogCategoryDescriptor
import com.mecon.theory.harmony.ChordCatalogCategoryId
import com.mecon.theory.harmony.ChordCatalogChapterProvider
import com.mecon.theory.harmony.ChordCatalogContribution
import com.mecon.theory.harmony.ChordCatalogNamedSubset
import com.mecon.theory.harmony.ChordChapterDescriptor
import com.mecon.theory.harmony.ChordChoiceProjection
import com.mecon.theory.harmony.ChordConstructionDetail
import com.mecon.theory.harmony.ChordConstructionTone
import com.mecon.theory.harmony.DiscoverableChordCatalogChapter
import com.mecon.theory.harmony.DiscoverableChordKnowledgeChapter
import com.mecon.theory.harmony.ChordKnowledgeChapterProvider
import com.mecon.theory.harmony.ChordKnowledgeContribution
import com.mecon.theory.harmony.ChordKnowledgeContributionId
import com.mecon.theory.harmony.ChordDetailDefinition
import com.mecon.theory.harmony.ChordExplanationId
import com.mecon.theory.harmony.ChordInterpretationRef
import com.mecon.theory.harmony.ChordSummary
import com.mecon.theory.harmony.ChordStructureDetail
import com.mecon.theory.harmony.ChordFunctionDetail
import com.mecon.theory.harmony.ChordVoiceLeadingDetail
import com.mecon.theory.harmony.AugmentedSixthConstructionKind
import com.mecon.theory.harmony.AugmentedSixthConstructionOrigin
import com.mecon.theory.harmony.ChordConstructionBases
import com.mecon.theory.harmony.ConstructionTonePresence
import com.mecon.theory.harmony.ConstructionToneRole
import com.mecon.theory.harmony.ConstructionRoute
import com.mecon.theory.harmony.ConstructionRouteId
import com.mecon.theory.harmony.FunctionalToneRole
import com.mecon.theory.harmony.TendencyDirection
import com.mecon.theory.harmony.TendencyToneDetail
import com.mecon.theory.harmony.TheoryClaimKind
import com.mecon.theory.harmony.TheorySourceRef
import com.mecon.theory.harmony.VagrantChordFamilyId
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookTriadPosition

/**
 * Schoenberg's Italian, German and French augmented-sixth constructions at the frontier of
 * tonality. The spelling-sensitive vocabulary owns chord identity; this chapter owns exercise
 * shapes and the two explicit chromatic-neighbour resolutions.
 */
@DiscoverableChordCatalogChapter
@DiscoverableChordKnowledgeChapter
object SchoenbergAugmentedSixthChapter :
    ChordCatalogChapterProvider,
    ChordKnowledgeChapterProvider {
    val FAMILY_ID = VagrantChordFamilyId("schoenberg.augmented-sixth")
    private val CHAPTER = ChordChapterDescriptor("schoenberg.augmented-sixth", 650)

    override val chordCatalogContributions: List<ChordCatalogContribution> = listOf(
        ChordCatalogContribution(
            category = ChordCatalogCategoryDescriptor(
                id = "augmented-sixths",
                order = 820,
                titleKey = "exploration.chordCatalog.augmentedSixths.title",
                descriptionKey = "exploration.chordCatalog.augmentedSixths.description",
            ),
            chapter = CHAPTER,
            construct = { context, _ -> AugmentedSixthVocabulary.constructedChords(context) },
            symbolLabelProjection = { interpretation ->
                val family = interpretation.attributes.getValue(AugmentedSixthMetadata.FAMILY_NAME)
                    .let(AugmentedSixthFamily::valueOf)
                val targetDegree = interpretation.attributes.getValue(AugmentedSixthMetadata.TARGET_DEGREE_NAME)
                    .toInt()
                family.selectionSymbol(targetDegree)
            },
            choiceOrderProjection = { interpretation ->
                val family = interpretation.attributes.getValue(AugmentedSixthMetadata.FAMILY_NAME)
                    .let(AugmentedSixthFamily::valueOf)
                val targetDegree = interpretation.attributes.getValue(AugmentedSixthMetadata.TARGET_DEGREE_NAME)
                    .toInt()
                targetDegree * 10 + family.ordinal
            },
            choiceProjection = ChordChoiceProjection.BySoundingClass(FAMILY_ID),
            namedSubsets = listOf(
                ChordCatalogNamedSubset(
                    category = ChordCatalogCategoryDescriptor(
                        id = "dominant-augmented-sixths",
                        order = 805,
                        titleKey = "exploration.chordCatalog.dominantAugmentedSixths.title",
                        descriptionKey = "exploration.chordCatalog.dominantAugmentedSixths.description",
                    ),
                    matches = { interpretation ->
                        interpretation.attributes[AugmentedSixthMetadata.TARGET_DEGREE_NAME] == "5"
                    },
                )
            ),
        )
    )

    override val chordKnowledgeContributions: List<ChordKnowledgeContribution> = listOf(
        ChordKnowledgeContribution(
            id = ChordKnowledgeContributionId("schoenberg.augmented-sixth"),
            chapterId = CHAPTER.id,
            chapter = CHAPTER,
            familyId = FAMILY_ID,
            construct = AugmentedSixthVocabulary::constructedChords,
            details = { context, catalog ->
                catalog.entries.flatMap { entry ->
                    entry.interpretations.mapNotNull { interpretation ->
                        val family = interpretation.attributes[AugmentedSixthMetadata.FAMILY_NAME]
                            ?.let(AugmentedSixthFamily::valueOf)
                            ?: return@mapNotNull null
                        val targetDegree = interpretation.attributes[AugmentedSixthMetadata.TARGET_DEGREE_NAME]
                            ?.toIntOrNull()
                            ?: return@mapNotNull null
                        val targetAlteration = interpretation.attributes[AugmentedSixthMetadata.TARGET_ALTERATION_NAME]
                            ?.toIntOrNull()
                            ?: return@mapNotNull null
                        val type = AugmentedSixthVocabulary.types(context.tonalContext)
                            .single {
                                it.family == family &&
                                    it.targetDegree == targetDegree &&
                                    it.resolutionAlteration == targetAlteration
                            }
                        val ref = ChordInterpretationRef(entry.sonority.id, interpretation.id)
                        val flatNeighbor = interpretation.toneRoles.getValue(FunctionalToneRole.STRUCTURAL_ROOT)
                        val sharpNeighbor = interpretation.toneRoles.getValue(FunctionalToneRole.ALTERED_TONE)
                        val tendencies = listOf(
                            TendencyToneDetail(
                                toneId = flatNeighbor,
                                role = FunctionalToneRole.STRUCTURAL_ROOT,
                                direction = TendencyDirection.DESCENDING,
                                targetDegree = targetDegree,
                                descriptionKey = "exploration.chordDetail.augmentedSixth.flatNeighbor",
                            ),
                            TendencyToneDetail(
                                toneId = sharpNeighbor,
                                role = FunctionalToneRole.ALTERED_TONE,
                                direction = TendencyDirection.ASCENDING,
                                targetDegree = targetDegree,
                                descriptionKey = "exploration.chordDetail.augmentedSixth.sharpNeighbor",
                            ),
                        )
                        val route = ConstructionRoute(
                            id = ConstructionRouteId("route.${interpretation.id.value}"),
                            interpretationRef = ref,
                            formulaKey = family.formulaKey(),
                            steps = emptyList(),
                            tendencyTones = tendencies,
                            connectionRefs = interpretation.treatmentIds.toList(),
                            construction = ChordConstructionDetail.AugmentedSixthDerivation(
                                kind = family.constructionKind(),
                                origin = type.constructionOrigin(context.tonalContext),
                                augmentedSixthTones = type.resultTones.map {
                                    context.tonalContext.constructionTone(it)
                                },
                                descendingEndpoint = type.lowerTone,
                                ascendingEndpoint = type.upperTone,
                                resolutionTone = context.tonalContext.constructionTone(
                                    context.tonalContext.spellDegree(targetDegree, targetAlteration)
                                ),
                                resultSymbol = family.selectionSymbol(targetDegree),
                                alterationDescriptionKey = family.alterationDescriptionKey(),
                            ),
                            sourceRefs = listOf(AUGMENTED_SIXTH_SOURCE),
                        )
                        ChordDetailDefinition(
                            interpretationRef = ref,
                            explanationId = ChordExplanationId("schoenberg.${interpretation.id.value}"),
                            sourceCategoryIds = setOf(
                                ChordCatalogCategoryId(
                                    if (targetDegree == 5) {
                                        "dominant-augmented-sixths"
                                    } else {
                                        "augmented-sixths"
                                    }
                                )
                            ),
                            summary = ChordSummary(
                                nameKey = family.nameKey(),
                                descriptionKey = "exploration.chordDetail.augmentedSixth.summary",
                                tags = listOf("exploration.chordDetail.augmentedSixth.vagrantTag"),
                            ),
                            structure = ChordStructureDetail(
                                toneIds = interpretation.structuralToneOrder,
                                propertyKeys = listOf("exploration.chordDetail.augmentedSixth.spelling"),
                            ),
                            function = ChordFunctionDetail(
                                interpretation.function,
                                "exploration.chordDetail.augmentedSixth.function",
                            ),
                            voiceLeading = ChordVoiceLeadingDetail(
                                tendencyTones = tendencies,
                                connectionRefs = interpretation.treatmentIds.toList(),
                            ),
                            routes = listOf(route),
                            sourceRefs = listOf(AUGMENTED_SIXTH_SOURCE),
                        )
                    }
                }
            },
        )
    )

    val LOWER_RESOLUTION_RULE_ID = RuleId("schoenberg.augmented-sixth.lower-resolution")
    val UPPER_RESOLUTION_RULE_ID = RuleId("schoenberg.augmented-sixth.upper-resolution")
    val ENHARMONIC_LOWER_RULE_ID = RuleId("schoenberg.augmented-sixth.enharmonic-lower")
    val ENHARMONIC_UPPER_RULE_ID = RuleId("schoenberg.augmented-sixth.enharmonic-upper")
    val COMPLETE_RULE_ID = RuleId("schoenberg.augmented-sixth.complete")

    fun types(key: Key): List<AugmentedSixthType> =
        AugmentedSixthVocabulary.types(TonalContext.fromKey(key))

    fun exerciseChords(key: Key): List<SchoenbergSymbolicChord> =
        types(key).flatMap { type ->
            when (type.arity) {
                ChordArity.TRIAD -> TextbookTriadPosition.entries.map { position ->
                    type.toSymbolic(position = position)
                }
                ChordArity.SEVENTH -> TextbookSeventhPosition.entries.map { position ->
                    type.toSymbolic(seventhPosition = position)
                }
            }
        }

    fun enumerate(key: Key): List<SchoenbergSymbolicProgression> {
        val triads = exerciseTriads(key, includeLeadingTriad = true)
        val functional = exerciseChords(key)
            .filter { it.augmentedSixthFamily != AugmentedSixthFamily.HALF_DIMINISHED }
            .flatMap { chord ->
            val targetDegree = chord.appliedToDegree
                ?: error("Augmented-sixth chord lacks a target degree")
            val resolutionDegrees = buildSet {
                add(targetDegree)
                if (chord.augmentedSixthFamily == AugmentedSixthFamily.GERMAN) {
                    // Schoenberg compares these with V-vi and V-iv deceptive motions.
                    add(wrapDegree(targetDegree + 5))
                    add(wrapDegree(targetDegree + 3))
                }
            }
            resolutionDegrees.mapNotNull { degree ->
                triads.firstOrNull { it.degree == degree && !it.isLeadingTriad() }
                    ?.toSymbolic(TextbookTriadPosition.ROOT_POSITION)
                    ?.let { resolution ->
                        SchoenbergSymbolicProgression(
                            slots = listOf(chord, resolution),
                            kind = SchoenbergConnectionKind.AUGMENTED_SIXTH_RESOLUTION,
                            knowledgeTags = setOf(
                                SchoenbergKnowledgeTag.VAGRANT_CHORD,
                                SchoenbergKnowledgeTag.AUGMENTED_SIXTH,
                            ),
                        )
                    }
            }
        }
        return functional + enharmonicSecondResolution(key)
    }

    fun program(
        key: Key,
        progression: SchoenbergSymbolicProgression? = null,
        searchConfig: SearchConfig = SearchConfig(maxResults = 4, beamWidth = 512),
    ): ConstraintProgram {
        val selected = progression ?: enumerate(key).firstOrNull()
            ?: error("No augmented-sixth connection is available in the current key")
        require(selected.slots.size == EXERCISE_LENGTH) {
            "Augmented-sixth exercise requires a two-chord resolution"
        }
        val triads = exerciseTriads(key, includeLeadingTriad = true)
        val domains = exactProgressionDomains(selected, triads, EXERCISE_LENGTH)
        val source = domains.first().targets.single()
        val sourceIdentity = source.identityKey()
        val family = AugmentedSixthMetadata.familyOf(source)
            ?: error("Augmented-sixth family metadata is missing")
        val requirements = endpointRequirements(
            sourceIdentity = sourceIdentity,
            family = family,
            targetDegree = AugmentedSixthMetadata.targetDegreeOf(source)
                ?: error("Augmented-sixth target metadata is missing"),
            targetAlteration = AugmentedSixthMetadata.targetAlterationOf(source)
                ?: error("Augmented-sixth target alteration metadata is missing"),
            upperTone = if (source.arity == ChordArity.TRIAD) ChordTone.FIFTH else ChordTone.SEVENTH,
            sourceSlot = 0,
        )
        return ConstraintProgram.fromRequirements(
            key = key,
            slotDomains = domains,
            configuration = com.mecon.theory.constraint.ConstraintRequirementConfiguration(
                ruleProfile = SchoenbergCommonToneExercises.SCHOENBERG_PROFILE,
                chordToneNeighbors = requirements,
                toneCompleteness = listOf(
                    ToneCompletenessRequirement(
                        window = SlotWindow(0, 0),
                        requiredTones = if (source.arity == ChordArity.TRIAD) {
                            setOf(ChordTone.ROOT, ChordTone.THIRD, ChordTone.FIFTH)
                        } else {
                            setOf(ChordTone.ROOT, ChordTone.THIRD, ChordTone.FIFTH, ChordTone.SEVENTH)
                        },
                        selector = TargetSelector(identityKeys = setOf(sourceIdentity)),
                        required = true,
                        ruleId = COMPLETE_RULE_ID,
                        explanation = ConstraintExplanation(
                            "增六和弦的构成音均已保留。",
                            "增六和弦在四部写作中必须保留全部构成音。",
                        ),
                    ),
                    ToneCompletenessRequirement(
                        window = SlotWindow(1, 1),
                        requiredTones = setOf(ChordTone.ROOT, ChordTone.THIRD),
                        required = true,
                        ruleId = SchoenbergIntegratedTechTree.TRIAD_COMPLETE_RULE_ID,
                    ),
                ),
                searchConfig = searchConfig,
                ruleModules = emptyList(),
                includeDerivedTextbookConstraints = false,
            ),
        )
    }

    internal fun endpointRequirements(
        sourceIdentity: String,
        family: AugmentedSixthFamily,
        targetDegree: Int,
        targetAlteration: Int,
        upperTone: ChordTone,
        sourceSlot: Int? = null,
    ): List<ChordToneNeighborRequirement> {
        val selector = TargetSelector(identityKeys = setOf(sourceIdentity))
        val window = SlotWindow(0, null)
        return listOf(
            ChordToneNeighborRequirement(
                window = window,
                sourceSlot = sourceSlot,
                sourceTone = ChordTone.ROOT,
                direction = ChordToneNeighborDirection.NEXT,
                candidateScaleDegrees = setOf(targetDegree),
                candidateAlterations = setOf(targetAlteration),
                allowedDiatonicStepDeltas = setOf(
                    if (family == AugmentedSixthFamily.HALF_DIMINISHED) 0 else -1
                ),
                sourceSelector = selector,
                ruleId = if (family == AugmentedSixthFamily.HALF_DIMINISHED) {
                    ENHARMONIC_LOWER_RULE_ID
                } else {
                    LOWER_RESOLUTION_RULE_ID
                },
                explanation = ConstraintExplanation(
                    "降方邻音已下行到共同目标音。",
                    "增六和弦的降方邻音必须下行半音到共同目标音。",
                ),
            ),
            ChordToneNeighborRequirement(
                window = window,
                sourceSlot = sourceSlot,
                sourceTone = upperTone,
                direction = ChordToneNeighborDirection.NEXT,
                candidateScaleDegrees = setOf(targetDegree),
                candidateAlterations = setOf(targetAlteration),
                allowedDiatonicStepDeltas = setOf(1),
                sourceSelector = selector,
                ruleId = if (family == AugmentedSixthFamily.HALF_DIMINISHED) {
                    ENHARMONIC_UPPER_RULE_ID
                } else {
                    UPPER_RESOLUTION_RULE_ID
                },
                explanation = ConstraintExplanation(
                    "升方邻音已上行到共同目标音。",
                    "增六和弦的升方邻音必须上行半音到共同目标音。",
                ),
            ),
        )
    }

    private fun enharmonicSecondResolution(key: Key): List<SchoenbergSymbolicProgression> {
        if (key.mode != Mode.IONIAN) return emptyList()
        val borrowed = SchoenbergMinorSubdominantChapter.borrowedChords(key)
        val halfDiminished = exerciseChords(key).firstOrNull {
            it.augmentedSixthFamily == AugmentedSixthFamily.HALF_DIMINISHED &&
                it.seventhPosition == TextbookSeventhPosition.ROOT_POSITION
        } ?: return emptyList()
        val neapolitan = borrowed.firstOrNull {
            it.degree == 2 && it.rootAlteration == -1 &&
                it.quality == ChordQuality.MAJOR && it.arity == ChordArity.TRIAD
        } ?: return emptyList()
        return listOf(
            SchoenbergSymbolicProgression(
                slots = listOf(halfDiminished, neapolitan),
                kind = SchoenbergConnectionKind.ENHARMONIC_AUGMENTED_SIXTH_RESOLUTION,
                knowledgeTags = setOf(
                    SchoenbergKnowledgeTag.MINOR_SUBDOMINANT,
                    SchoenbergKnowledgeTag.NEAPOLITAN,
                    SchoenbergKnowledgeTag.VAGRANT_CHORD,
                    SchoenbergKnowledgeTag.AUGMENTED_SIXTH,
                ),
            )
        )
    }

    private fun AugmentedSixthType.toSymbolic(
        position: TextbookTriadPosition = TextbookTriadPosition.ROOT_POSITION,
        seventhPosition: TextbookSeventhPosition? = null,
    ): SchoenbergSymbolicChord = SchoenbergSymbolicChord(
        degree = lowerDegree,
        quality = ChordQuality.CUSTOM,
        position = position,
        arity = arity,
        seventhPosition = seventhPosition,
        rootAlteration = lowerAlteration,
        appliedToDegree = targetDegree,
        augmentedSixthFamily = family,
    )

    private fun wrapDegree(degree: Int): Int = (degree - 1).mod(7) + 1

    private fun AugmentedSixthFamily.nameKey(): String =
        "exploration.chordDetail.augmentedSixth.${name.lowercase()}Name"

    private fun AugmentedSixthFamily.formulaKey(): String =
        "exploration.chordDetail.augmentedSixth.${name.lowercase()}Formula"

    private fun AugmentedSixthFamily.selectionSymbol(targetDegree: Int): String = when (this) {
        AugmentedSixthFamily.ITALIAN -> "It+6"
        AugmentedSixthFamily.GERMAN -> "Ger+6"
        AugmentedSixthFamily.FRENCH -> "Fr+6"
        AugmentedSixthFamily.HALF_DIMINISHED -> "ø+6"
    } + if (this != AugmentedSixthFamily.HALF_DIMINISHED && targetDegree != 5) {
        "/${com.mecon.theory.FunctionalChordSymbolFormatter.romanDegree(targetDegree)}"
    } else {
        ""
    }

    private fun AugmentedSixthFamily.constructionKind(): AugmentedSixthConstructionKind = when (this) {
        AugmentedSixthFamily.ITALIAN -> AugmentedSixthConstructionKind.ITALIAN
        AugmentedSixthFamily.GERMAN -> AugmentedSixthConstructionKind.GERMAN
        AugmentedSixthFamily.FRENCH -> AugmentedSixthConstructionKind.FRENCH
        AugmentedSixthFamily.HALF_DIMINISHED -> AugmentedSixthConstructionKind.HALF_DIMINISHED
    }

    private fun AugmentedSixthFamily.alterationDescriptionKey(): String =
        "exploration.chordDetail.construction.augmentedSixth.alteration.${name.lowercase()}"

    private fun AugmentedSixthType.constructionOrigin(
        context: TonalContext,
    ): AugmentedSixthConstructionOrigin = when (family) {
        AugmentedSixthFamily.ITALIAN,
        AugmentedSixthFamily.GERMAN,
        -> {
            val roles = if (family == AugmentedSixthFamily.ITALIAN) {
                listOf(
                    ConstructionToneRole.THIRD,
                    ConstructionToneRole.FIFTH,
                    ConstructionToneRole.SEVENTH,
                )
            } else {
                listOf(
                    ConstructionToneRole.THIRD,
                    ConstructionToneRole.FIFTH,
                    ConstructionToneRole.SEVENTH,
                    ConstructionToneRole.NINTH,
                )
            }
            AugmentedSixthConstructionOrigin.RootlessAppliedChord(
                basis = com.mecon.theory.harmony.ChordConstructionBasisRef(
                    definition = if (family == AugmentedSixthFamily.ITALIAN) {
                        ChordConstructionBases.DOMINANT_SEVENTH
                    } else {
                        ChordConstructionBases.DOMINANT_NINTH
                    },
                    tonicizedDegree = targetDegree,
                ),
                tones = listOf(
                    context.constructionTone(
                        spelling = requireNotNull(virtualRootTone),
                        role = ConstructionToneRole.ROOT,
                        presence = ConstructionTonePresence.OMITTED,
                    )
                ) + basisTones.zip(roles) { spelling, role ->
                    context.constructionTone(spelling, role)
                },
                rootlessResultNameKey = if (family == AugmentedSixthFamily.ITALIAN) {
                    "exploration.chordDetail.construction.augmentedSixth.diminishedTriad"
                } else {
                    "exploration.chordDetail.construction.augmentedSixth.diminishedSeventh"
                },
            )
        }

        AugmentedSixthFamily.FRENCH -> AugmentedSixthConstructionOrigin.NamedChord(
            symbol = namedSeventhSymbol(),
            tones = basisTones.map { context.constructionTone(it) },
        )

        AugmentedSixthFamily.HALF_DIMINISHED -> AugmentedSixthConstructionOrigin.NamedChord(
            symbol = "iiø7",
            tones = basisTones.map { context.constructionTone(it) },
        )
    }

    private fun AugmentedSixthType.namedSeventhSymbol(): String {
        val root = basisTones.first().pitchClass
        val intervals = basisTones.map { (it.pitchClass.value - root.value).mod(12) }.toSet()
        val suffix = if (intervals == setOf(0, 3, 6, 10)) "ø7" else "7"
        val degree = com.mecon.theory.FunctionalChordSymbolFormatter
            .romanDegree(wrapDegree(targetDegree + 4))
            .lowercase()
        return degree + suffix
    }

    private fun TonalContext.constructionTone(
        spelling: com.mecon.theory.SpelledPitchClass,
        role: ConstructionToneRole = ConstructionToneRole.OTHER,
        presence: ConstructionTonePresence = ConstructionTonePresence.SOUNDING,
    ): ChordConstructionTone {
        val degree = (1..7).first { spellDegree(it).noteName == spelling.noteName }
        return ChordConstructionTone(
            degree = degree,
            alteration = spelling.chromaticOffset - spellDegree(degree).chromaticOffset,
            spelling = spelling,
            role = role,
            presence = presence,
        )
    }

    private val AUGMENTED_SIXTH_SOURCE = TheorySourceRef(
        sourceId = "textbook-schonberg.md",
        edition = "project transcription",
        chapterOrTopic = "在调性的边缘",
        locator = "游移和弦、德国增六、法国增六与大二度关系看作增六",
        claimKind = TheoryClaimKind.PROJECT_INFERENCE,
    )

    private const val EXERCISE_LENGTH = 2
}
