package com.mecon.theory.schoenberg

import com.mecon.theory.ChordArity
import com.mecon.theory.Key
import com.mecon.theory.RuleId
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.TonalContext
import com.mecon.theory.constraint.ChordTarget
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.constraint.ChordToneNeighborDirection
import com.mecon.theory.constraint.ChordToneNeighborRequirement
import com.mecon.theory.constraint.ConstraintExplanation
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.RootlessDominantNinthType
import com.mecon.theory.constraint.RootlessDominantNinthVocabulary
import com.mecon.theory.constraint.TargetSelector
import com.mecon.theory.constraint.ToneCompletenessRequirement
import com.mecon.theory.constraint.toInterpretedTargets
import com.mecon.theory.harmony.ChordCatalogCategoryDescriptor
import com.mecon.theory.harmony.ChordCatalogChapterProvider
import com.mecon.theory.harmony.ChordCatalogCategoryId
import com.mecon.theory.harmony.ChordChapterDescriptor
import com.mecon.theory.harmony.ChordCatalogContribution
import com.mecon.theory.harmony.ChordChoiceProjection
import com.mecon.theory.harmony.ChordDetailDefinition
import com.mecon.theory.harmony.ChordFunctionDetail
import com.mecon.theory.harmony.ChordFunctionRelation
import com.mecon.theory.harmony.ChordConstructionDetail
import com.mecon.theory.harmony.ChordConstructionBasisRef
import com.mecon.theory.harmony.ChordConstructionBases
import com.mecon.theory.harmony.ChordConstructionTone
import com.mecon.theory.harmony.ConstructionTonePresence
import com.mecon.theory.harmony.ConstructionToneRole
import com.mecon.theory.harmony.ChordExplanationId
import com.mecon.theory.harmony.AudibleSonorityKey
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
import com.mecon.theory.harmony.FunctionalToneRole
import com.mecon.theory.harmony.ImpliedToneDetail
import com.mecon.theory.harmony.SonorityToneId
import com.mecon.theory.harmony.TendencyDirection
import com.mecon.theory.harmony.TendencyToneDetail
import com.mecon.theory.harmony.TheoryClaimKind
import com.mecon.theory.harmony.TheorySourceRef
import com.mecon.theory.harmony.VagrantChordFamilyId
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookTriadPosition

data class SchoenbergDiminishedSeventhChordChoice(
    val id: String,
    val chord: SchoenbergSymbolicChord,
)

data class SchoenbergDiminishedSeventhUsageChoice(
    val id: String,
    val chordId: String,
    val tonicizedDegree: Int,
    val omittedRootDegree: Int,
    val omittedRootAlteration: Int,
    val chord: SchoenbergSymbolicChord,
)

/**
 * Fully diminished seventh chords interpreted as rootless dominant ninths.
 *
 * The shared vocabulary owns the symmetric pitch-set grouping and functional identities.
 * This chapter only supplies exercise shape and typed voice-leading requirements.
 */
@DiscoverableChordCatalogChapter
@DiscoverableChordKnowledgeChapter
object SchoenbergDiminishedSeventhChapter :
    ChordCatalogChapterProvider,
    ChordKnowledgeChapterProvider {
    val FAMILY_ID = VagrantChordFamilyId("schoenberg.diminished-seventh")
    private val CHAPTER = ChordChapterDescriptor("schoenberg.diminished-seventh", 600)

    override val chordCatalogContributions = listOf(
        ChordCatalogContribution(
            category = ChordCatalogCategoryDescriptor(
                id = "rootless-dominant-ninth",
                order = 600,
                titleKey = "exploration.chordCatalog.rootlessDominantNinth.title",
                descriptionKey = "exploration.chordCatalog.rootlessDominantNinth.description",
            ),
            chapter = CHAPTER,
            construct = { context, _ ->
                RootlessDominantNinthVocabulary.constructedChords(context)
            },
            symbolProjection = { interpretation ->
                interpretation.symbol.copy(degree = 7, alteration = 0)
            },
            choiceProjection = ChordChoiceProjection.BySoundingClass(FAMILY_ID),
        )
    )

    override val chordKnowledgeContributions = listOf(
        ChordKnowledgeContribution(
            id = ChordKnowledgeContributionId("schoenberg.diminished-seventh"),
            chapterId = "schoenberg.diminished-seventh",
            chapter = CHAPTER,
            familyId = FAMILY_ID,
            construct = RootlessDominantNinthVocabulary::constructedChords,
            details = { context, catalog ->
                val usagesById = RootlessDominantNinthVocabulary
                    .types(context.tonalContext)
                    .associateBy(RootlessDominantNinthType::id)
                catalog.entries.flatMap { entry ->
                    entry.interpretations.map { interpretation ->
                        val usageId = interpretation.attributes.getValue(
                            com.mecon.theory.constraint.RootlessDominantNinthMetadata.USAGE_ID_NAME
                        )
                        val usage = usagesById.getValue(usageId)
                        val ref = ChordInterpretationRef(entry.sonority.id, interpretation.id)
                        val loweredToneId = interpretation.toneRoles.getValue(
                            FunctionalToneRole.OMITTED_DOMINANT_ROOT_NEIGHBOR
                        )
                        val leadingToneId = interpretation.toneRoles.getValue(
                            FunctionalToneRole.LOCAL_LEADING_TONE
                        )
                        val tendencyTones = listOf(
                            TendencyToneDetail(
                                toneId = loweredToneId,
                                role = FunctionalToneRole.OMITTED_DOMINANT_ROOT_NEIGHBOR,
                                direction = TendencyDirection.DESCENDING,
                                targetDegree = usage.omittedRootDegree,
                                descriptionKey = "chordDetail.diminished.lowerToOmittedRoot",
                            ),
                            TendencyToneDetail(
                                toneId = leadingToneId,
                                role = FunctionalToneRole.LOCAL_LEADING_TONE,
                                direction = TendencyDirection.ASCENDING,
                                targetDegree = usage.tonicizedDegree,
                                descriptionKey = "chordDetail.diminished.localLeadingTone",
                            ),
                        )
                        val source = DIMINISHED_SEVENTH_SOURCE
                        val constructionTones = dominantFlatNinthConstruction(
                            context.tonalContext,
                            usage.omittedRootDegree,
                            usage.omittedRootAlteration,
                        )
                        val route = ConstructionRoute(
                            id = ConstructionRouteId("route.${interpretation.id.value}"),
                            interpretationRef = ref,
                            formulaKey = "chordDetail.diminished.rootlessFlatNinth",
                            steps = listOf(
                                ConstructionOperation.StackThirds(5),
                                ConstructionOperation.Omit(
                                    SonorityToneId(
                                        "implied.degree-${usage.omittedRootDegree}." +
                                            usage.omittedRootAlteration
                                    )
                                ),
                            ),
                            impliedOrOmittedTones = listOf(
                                ImpliedToneDetail(
                                    degree = usage.omittedRootDegree,
                                    alteration = usage.omittedRootAlteration,
                                    omitted = true,
                                    descriptionKey = "chordDetail.diminished.omittedDominantRoot",
                                )
                            ),
                            tendencyTones = tendencyTones,
                            connectionRefs = interpretation.treatmentIds.toList(),
                            functionRelations = listOf(
                                ChordFunctionRelation.SubstitutesFor(
                                    targetTreatmentId = SchoenbergHarmonicTreatments.DIATONIC_DOMINANT,
                                    function = com.mecon.theory.harmony.HarmonicFunction.DOMINANT,
                                    tonicizedDegree = usage.tonicizedDegree,
                                )
                            ),
                            construction = ChordConstructionDetail.OmittedFromFormula(
                                basis = ChordConstructionBasisRef(
                                    definition = ChordConstructionBases.DOMINANT_NINTH,
                                    tonicizedDegree = usage.tonicizedDegree,
                                ),
                                tones = constructionTones,
                            ),
                            sourceRefs = listOf(source),
                        )
                        ChordDetailDefinition(
                            interpretationRef = ref,
                            explanationId = ChordExplanationId(
                                "schoenberg.diminished-seventh.${AudibleSonorityKey.from(entry.sonority.tones.map { it.spelling.pitchClass.value }).value}"
                            ),
                            sourceCategoryIds = setOf(
                                ChordCatalogCategoryId("rootless-dominant-ninth")
                            ),
                            summary = ChordSummary(
                                nameKey = "chordDetail.diminished.name",
                                descriptionKey = "chordDetail.diminished.summary",
                                tags = listOf("symmetric", "vagrant"),
                            ),
                            structure = ChordStructureDetail(
                                toneIds = interpretation.structuralToneOrder,
                                propertyKeys = listOf(
                                    "chordDetail.diminished.stackedMinorThirds",
                                    "chordDetail.diminished.threeSoundingClasses",
                                ),
                            ),
                            function = ChordFunctionDetail(
                                function = interpretation.function,
                                descriptionKey = "chordDetail.diminished.dominantFunction",
                            ),
                            voiceLeading = ChordVoiceLeadingDetail(
                                tendencyTones = tendencyTones,
                                connectionRefs = interpretation.treatmentIds.toList(),
                            ),
                            routes = listOf(route),
                            sourceRefs = listOf(source),
                        )
                    }
                }
            },
        )
    )

    private fun dominantFlatNinthConstruction(
        context: TonalContext,
        rootDegree: Int,
        rootAlteration: Int,
    ): List<ChordConstructionTone> {
        val root = context.spellDegree(rootDegree, rootAlteration)
        val intervals = listOf(0, 4, 7, 10, 13)
        val degreeOffsets = listOf(0, 2, 4, 6, 1)
        val roles = listOf(
            ConstructionToneRole.ROOT,
            ConstructionToneRole.THIRD,
            ConstructionToneRole.FIFTH,
            ConstructionToneRole.SEVENTH,
            ConstructionToneRole.NINTH,
        )
        return intervals.indices.map { index ->
            val degree = (rootDegree - 1 + degreeOffsets[index]).mod(7) + 1
            val natural = context.pitchClassForDegree(degree)
            val desired = root.pitchClass.transpose(intervals[index])
            val alteration = (desired.value - natural.value + 6).mod(12) - 6
            ChordConstructionTone(
                degree = degree,
                alteration = alteration,
                spelling = context.spellDegree(degree, alteration),
                role = roles[index],
                presence = if (index == 0) {
                    ConstructionTonePresence.OMITTED
                } else {
                    ConstructionTonePresence.SOUNDING
                },
            )
        }
    }

    val LOWER_TO_ROOT_RULE_ID = RuleId("schoenberg.diminished-seventh.lower-to-omitted-root")
    val LOCAL_LEADING_TONE_RULE_ID = RuleId("schoenberg.diminished-seventh.local-leading-tone")
    val ALTERED_TONE_STEP_RULE_ID = RuleId("schoenberg.diminished-seventh.altered-tone-step")
    val COMPLETE_RULE_ID = RuleId("schoenberg.diminished-seventh.complete")

    fun chordChoices(key: Key): List<SchoenbergDiminishedSeventhChordChoice> =
        types(key)
            .groupBy(RootlessDominantNinthType::chordId)
            .map { (chordId, usages) ->
                SchoenbergDiminishedSeventhChordChoice(
                    id = chordId,
                    chord = usages.first().toSymbolic(),
                )
            }

    fun usageChoices(
        key: Key,
        chordId: String,
    ): List<SchoenbergDiminishedSeventhUsageChoice> =
        types(key)
            .filter { it.chordId == chordId }
            .map { type ->
                SchoenbergDiminishedSeventhUsageChoice(
                    id = type.id,
                    chordId = type.chordId,
                    tonicizedDegree = type.tonicizedDegree,
                    omittedRootDegree = type.omittedRootDegree,
                    omittedRootAlteration = type.omittedRootAlteration,
                    chord = type.toSymbolic(),
                )
            }

    fun enumerate(
        key: Key,
        chordId: String? = null,
        usageId: String? = null,
    ): List<SchoenbergSymbolicProgression> {
        val triads = exerciseTriads(key, includeLeadingTriad = true)
        val allTypes = types(key)
        require(chordId == null || allTypes.any { it.chordId == chordId }) {
            "Unknown diminished-seventh chord $chordId in the current key"
        }
        val chordTypes = allTypes.filter { chordId == null || it.chordId == chordId }
        require(usageId == null || chordTypes.any { it.id == usageId }) {
            "Unknown diminished-seventh use $usageId for chord $chordId"
        }
        val available = chordTypes.filter { usageId == null || it.id == usageId }
        return available.mapNotNull { type ->
            val resolution = triads.firstOrNull {
                it.degree == type.tonicizedDegree && !it.isLeadingTriad()
            } ?: return@mapNotNull null
            SchoenbergSymbolicProgression(
                slots = listOf(
                    type.toSymbolic(),
                    resolution.toSymbolic(TextbookTriadPosition.ROOT_POSITION),
                ),
                kind = SchoenbergConnectionKind.DIMINISHED_SEVENTH_FUNCTION,
                knowledgeTags = setOf(
                    SchoenbergKnowledgeTag.SECONDARY_HARMONY,
                    SchoenbergKnowledgeTag.DIMINISHED_SEVENTH,
                ),
            )
        }
    }

    fun progressionUsesSelection(
        progression: SchoenbergSymbolicProgression,
        chordId: String,
        usageId: String,
    ): Boolean =
        progression.slots.any {
            it.rootlessDominantNinthChordId == chordId &&
                it.rootlessDominantNinthUsageId == usageId
        }

    fun program(
        key: Key,
        progression: SchoenbergSymbolicProgression? = null,
        searchConfig: SearchConfig = SearchConfig(maxResults = 4, beamWidth = 256),
    ): ConstraintProgram {
        val selected = progression ?: enumerate(key).firstOrNull()
            ?: error("No diminished-seventh use is available in the current key")
        require(selected.slots.size == EXERCISE_LENGTH) {
            "Diminished-seventh exercise requires a two-chord resolution"
        }
        require(selected.slots.count(SchoenbergSymbolicChord::isRootlessDominantNinth) == 1) {
            "Diminished-seventh exercise requires exactly one rootless dominant ninth"
        }
        val triads = exerciseTriads(key, includeLeadingTriad = true)
        val domains = exactProgressionDomains(selected, triads, EXERCISE_LENGTH)
        val window = SlotWindow(0, domains.lastIndex)
        val rootlessIdentity = domains.first().targets.single().identityKey()
        return ConstraintProgram.fromRequirements(
            key = key,
            slotDomains = domains,
            configuration = com.mecon.theory.constraint.ConstraintRequirementConfiguration(
                ruleProfile = SchoenbergCommonToneExercises.SCHOENBERG_PROFILE,
                chordToneNeighbors = neighborRequirements(
                    key = key,
                    window = window,
                    usages = listOfNotNull(
                        types(key).firstOrNull {
                            it.id == selected.slots.first().rootlessDominantNinthUsageId
                        }
                    ),
                    sourceSlot = 0,
                ),
                toneCompleteness = listOf(
                    ToneCompletenessRequirement(
                        window = SlotWindow(0, 0),
                        requiredTones = CHORD_TONES.toSet(),
                        selector = TargetSelector(identityKeys = setOf(rootlessIdentity)),
                        required = true,
                        ruleId = COMPLETE_RULE_ID,
                        explanation = ConstraintExplanation(
                            "省略根音属九的四个减七和弦音均已保留。",
                            "减七和弦必须保留四个不同的和弦音。",
                        ),
                    ),
                    ToneCompletenessRequirement(
                        window = SlotWindow(1, 1),
                        requiredTones = setOf(ChordTone.ROOT, ChordTone.THIRD),
                        selector = TargetSelector(arities = setOf(ChordArity.TRIAD)),
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

    internal fun exerciseChords(key: Key): List<SchoenbergSymbolicChord> =
        types(key).map { it.toSymbolic() }

    internal fun neighborRequirements(
        key: Key,
        window: SlotWindow,
        usages: List<RootlessDominantNinthType> = types(key),
        sourceSlot: Int? = null,
    ): List<ChordToneNeighborRequirement> {
        val context = TonalContext.fromKey(key)
        return usages.flatMap { type ->
            val inversion = when (type.localLeadingTone) {
                ChordTone.ROOT -> 0
                ChordTone.THIRD -> 1
                ChordTone.FIFTH -> 2
                ChordTone.SEVENTH -> 3
                ChordTone.BASS -> error("Bass is not a structural diminished-seventh member")
            }
            val interpretationId = with(RootlessDominantNinthVocabulary) {
                type.interpretationId()
            }
            val target = RootlessDominantNinthVocabulary
                .catalog(context)
                .toInterpretedTargets(key, includeInversions = true)
                .first {
                    it.interpretation.id == interpretationId &&
                        it.inversion == inversion
                }
            val selector = TargetSelector(identityKeys = setOf(target.identityKey()))
            val (loweredSourceDegree, _) = degreeAndAlteration(context, target, type.loweredTone)
            val loweredDelta = if (loweredSourceDegree == type.omittedRootDegree) 0 else -1
            buildList {
                add(
                    ChordToneNeighborRequirement(
                        window = window,
                        sourceSlot = sourceSlot,
                        sourceTone = type.loweredTone,
                        direction = ChordToneNeighborDirection.NEXT,
                        candidateScaleDegrees = setOf(type.omittedRootDegree),
                        candidateAlterations = setOf(type.omittedRootAlteration),
                        allowedDiatonicStepDeltas = setOf(loweredDelta),
                        sourceSelector = selector,
                        sourcePitchClasses = setOfNotNull(target.pitchClassFor(type.loweredTone)),
                        ruleId = LOWER_TO_ROOT_RULE_ID,
                        explanation = ConstraintExplanation(
                            "所选减七和弦音已下降半音，显露省略的属根音。",
                            "所选减七和弦音必须在同一声部下降半音到省略的属根音。",
                        ),
                    )
                )
                add(
                    ChordToneNeighborRequirement(
                        window = window,
                        sourceSlot = sourceSlot,
                        sourceTone = type.localLeadingTone,
                        direction = ChordToneNeighborDirection.NEXT,
                        candidateScaleDegrees = setOf(type.tonicizedDegree),
                        candidateAlterations = setOf(0),
                        allowedDiatonicStepDeltas = setOf(1),
                        sourceSelector = selector,
                        sourcePitchClasses = setOfNotNull(target.pitchClassFor(type.localLeadingTone)),
                        ruleId = LOCAL_LEADING_TONE_RULE_ID,
                        explanation = ConstraintExplanation(
                            "省略根音属九的局部导音已上行到临时主音。",
                            "省略根音属九的局部导音必须上行到临时主音。",
                        ),
                    )
                )
                alteredTones(context, target).forEach { tone ->
                    add(
                        ChordToneNeighborRequirement(
                            window = window,
                            sourceSlot = sourceSlot,
                            sourceTone = tone,
                            direction = ChordToneNeighborDirection.NEXT,
                            candidateScaleDegrees = (1..7).toSet(),
                            candidateAlterations = setOf(0),
                            allowedDiatonicStepDeltas = setOf(-1, 0, 1),
                            sourceSelector = selector,
                            sourcePitchClasses = setOfNotNull(target.pitchClassFor(tone)),
                            ruleId = ALTERED_TONE_STEP_RULE_ID,
                            explanation = ConstraintExplanation(
                                "减七和弦的变化音已级进到调内非变化音。",
                                "非转调用法中，减七和弦的变化音必须级进到非变化音。",
                            ),
                        )
                    )
                }
            }
        }
    }

    private fun types(key: Key): List<RootlessDominantNinthType> =
        RootlessDominantNinthVocabulary.types(TonalContext.fromKey(key))

    private fun RootlessDominantNinthType.toSymbolic(): SchoenbergSymbolicChord =
        SchoenbergSymbolicChord(
            degree = soundingRootDegree,
            quality = com.mecon.theory.ChordQuality.DIMINISHED7,
            arity = ChordArity.SEVENTH,
            seventhPosition = when (localLeadingTone) {
                ChordTone.ROOT -> TextbookSeventhPosition.ROOT_POSITION
                ChordTone.THIRD -> TextbookSeventhPosition.FIRST_INVERSION
                ChordTone.FIFTH -> TextbookSeventhPosition.SECOND_INVERSION
                ChordTone.SEVENTH -> TextbookSeventhPosition.THIRD_INVERSION
                ChordTone.BASS -> error("Bass is not a structural diminished-seventh member")
            },
            rootAlteration = soundingRootAlteration,
            appliedToDegree = tonicizedDegree,
            secondaryFamily = com.mecon.theory.constraint.SecondaryHarmonyFamily.SECONDARY_DOMINANT,
            rootlessDominantNinthChordId = chordId,
            rootlessDominantNinthUsageId = id,
            omittedRootDegree = omittedRootDegree,
            omittedRootAlteration = omittedRootAlteration,
        )

    private fun alteredTones(
        context: TonalContext,
        target: ChordTarget,
    ): List<ChordTone> =
        CHORD_TONES.filter { tone ->
            degreeAndAlteration(context, target, tone).second != 0
        }

    private fun degreeAndAlteration(
        context: TonalContext,
        target: ChordTarget,
        tone: ChordTone,
    ): Pair<Int, Int> {
        val pitchClass = target.pitchClassFor(tone) ?: error("Missing $tone in diminished seventh")
        val spelling = target.spellingFor(pitchClass)
            ?: error("Missing spelling for $tone in diminished seventh")
        val degree = (1..7).firstOrNull { context.spellDegree(it).noteName == spelling.noteName }
            ?: error("Diminished-seventh member must map to a scale degree")
        val natural = context.spellDegree(degree)
        return degree to (spelling.chromaticOffset - natural.chromaticOffset)
    }

    private val CHORD_TONES = listOf(
        ChordTone.ROOT,
        ChordTone.THIRD,
        ChordTone.FIFTH,
        ChordTone.SEVENTH,
    )
    private const val EXERCISE_LENGTH = 2

    private val DIMINISHED_SEVENTH_SOURCE = TheorySourceRef(
        sourceId = "schoenberg-theory-of-harmony",
        edition = "1983 English translation",
        chapterOrTopic = "At the Frontiers of Tonality: diminished seventh chords",
        locator = "rootless dominant flat-ninth treatment",
        claimKind = TheoryClaimKind.PRIMARY_SOURCE,
    )
}
