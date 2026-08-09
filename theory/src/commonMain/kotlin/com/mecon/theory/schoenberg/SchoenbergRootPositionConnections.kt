package com.mecon.theory.schoenberg

import com.mecon.theory.Key
import com.mecon.theory.Mode
import com.mecon.theory.NaturalTriad
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.constraint.AdjacentCommonToneRequirement
import com.mecon.theory.constraint.AllDifferentRequirement
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.DoublingRequirement
import com.mecon.theory.constraint.SlotDomain
import com.mecon.theory.constraint.DiatonicChordVocabulary
import com.mecon.theory.harmony.ChordCatalogCategoryDescriptor
import com.mecon.theory.harmony.ChordCatalogChapterProvider
import com.mecon.theory.harmony.ChordCatalogContribution
import com.mecon.theory.harmony.ChordDetailDefinition
import com.mecon.theory.harmony.ChordFunctionDetail
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
import com.mecon.theory.harmony.ChordChapterDescriptor
import com.mecon.theory.harmony.ChordExplanationId
import com.mecon.theory.harmony.ChordCatalogCategoryId
import com.mecon.theory.harmony.TheorySourceRef
import com.mecon.theory.textbook.TextbookTriadPosition

@DiscoverableChordCatalogChapter
@DiscoverableChordKnowledgeChapter
object SchoenbergRootPositionConnections :
    ChordCatalogChapterProvider,
    ChordKnowledgeChapterProvider {
    private val CHAPTER = ChordChapterDescriptor("schoenberg.root-position-connections", 100)
    override val chordCatalogContributions = listOf(
        ChordCatalogContribution(
            category = ChordCatalogCategoryDescriptor(
                id = "diatonic-triads",
                order = 100,
                titleKey = "exploration.chordCatalog.diatonicTriads.title",
                descriptionKey = "exploration.chordCatalog.diatonicTriads.description",
            ),
            chapter = CHAPTER,
            construct = { context, key ->
                DiatonicChordVocabulary.naturalTriadUnionConstructions(context, key)
            },
        )
    )

    override val chordKnowledgeContributions = listOf(
        ChordKnowledgeContribution(
            id = ChordKnowledgeContributionId("schoenberg.diatonic-triads"),
            chapterId = "schoenberg.root-position-connections",
            chapter = CHAPTER,
            construct = { context ->
                val mode = when (context.scale.id.value) {
                    "mode.ionian" -> Mode.IONIAN
                    "mode.aeolian" -> Mode.AEOLIAN
                    else -> error("Natural-triad knowledge requires a major or minor context")
                }
                DiatonicChordVocabulary.naturalTriadUnionConstructions(
                    context,
                    Key(context.tonic.pitchClass, mode),
                )
            },
            details = { _, catalog ->
                catalog.entries.flatMap { entry ->
                    entry.interpretations.map { interpretation ->
                        val ref = ChordInterpretationRef(entry.sonority.id, interpretation.id)
                        ChordDetailDefinition(
                            interpretationRef = ref,
                            explanationId = ChordExplanationId(
                                "schoenberg.diatonic.${interpretation.id.value}"
                            ),
                            sourceCategoryIds = setOf(ChordCatalogCategoryId("diatonic-triads")),
                            summary = ChordSummary(
                                nameKey = "chordDetail.diatonic.name",
                                descriptionKey = "chordDetail.diatonic.summary",
                                tags = listOf("diatonic"),
                            ),
                            structure = ChordStructureDetail(
                                toneIds = interpretation.structuralToneOrder,
                                propertyKeys = listOf("chordDetail.diatonic.tertian"),
                            ),
                            function = ChordFunctionDetail(interpretation.function),
                            voiceLeading = ChordVoiceLeadingDetail(
                                connectionRefs = interpretation.treatmentIds.toList()
                            ),
                            routes = listOf(
                                ConstructionRoute(
                                    id = ConstructionRouteId("route.${interpretation.id.value}"),
                                    interpretationRef = ref,
                                    formulaKey = "chordDetail.diatonic.scaleDegree",
                                    steps = entry.constructionTraces
                                        .flatMap { it.derivationSteps }
                                        .distinct()
                                        .map(ConstructionOperation::LegacyTrace),
                                    connectionRefs = interpretation.treatmentIds.toList(),
                                    sourceRefs = listOf(ROOT_POSITION_SOURCE),
                                )
                            ),
                            sourceRefs = listOf(ROOT_POSITION_SOURCE),
                        )
                    }
                }
            },
        )
    )

    fun program(
        key: Key,
        continuationChordCount: Int,
        progression: SchoenbergSymbolicProgression? = null,
        searchConfig: SearchConfig = SearchConfig(maxResults = 4, beamWidth = 128),
    ): ConstraintProgram {
        require(continuationChordCount >= 1) { "continuationChordCount must be >= 1" }
        val length = continuationChordCount + 1
        require(progression == null || progression.slots.size == length) {
            "progression size must equal continuationChordCount + 1"
        }

        val triads = exerciseTriads(key, includeLeadingTriad = false)
        val allRootTargets = triads.map { it.toTarget(TextbookTriadPosition.ROOT_POSITION) }
        val tonic = triads.firstOrNull { it.degree == TONIC_DEGREE }
            ?.toTarget(TextbookTriadPosition.ROOT_POSITION)
            ?: error("Current key has no tonic triad")

        val slotDomains = progression?.slots?.map { chord ->
            SlotDomain(targets = listOf(chord.toTarget(triads)))
        } ?: List(length) { slot ->
            SlotDomain(targets = if (slot == 0) listOf(tonic) else allRootTargets)
        }

        val window = SlotWindow(0, null)
        return ConstraintProgram.fromRequirements(
            key = key,
            slotDomains = slotDomains,
            configuration = com.mecon.theory.constraint.ConstraintRequirementConfiguration(
                ruleProfile = SchoenbergCommonToneExercises.SCHOENBERG_PROFILE,
                doublings = (0 until length).map { slot ->
                    DoublingRequirement(slot, ChordTone.ROOT, required = true)
                },
                allDifferent = listOf(AllDifferentRequirement(window)),
                adjacentCommonTones = listOf(AdjacentCommonToneRequirement(window, holdInSameVoice = true)),
                searchConfig = searchConfig,
                // 勋伯格模式只保留一般四部写作规则，关掉 textbook 关于具体和弦的模块与派生约束。见 §4 / AGENTS.md。
                ruleModules = emptyList(),
                includeDerivedTextbookConstraints = false,

            ),
        )
    }

    fun enumerate(
        key: Key,
        continuationChordCount: Int,
    ): List<SchoenbergSymbolicProgression> {
        require(continuationChordCount >= 1) { "continuationChordCount must be >= 1" }
        val length = continuationChordCount + 1
        val triads = exerciseTriads(key, includeLeadingTriad = false)
        require(length <= triads.size) {
            "Current key does not have enough distinct non-leading triads for $length slots"
        }
        val tonic = triads.firstOrNull { it.degree == TONIC_DEGREE }
            ?: error("Current key has no tonic triad")

        return buildList {
            fun visit(prefix: List<NaturalTriad>) {
                if (prefix.size == length) {
                    add(
                        prefix.toProgression(
                            kind = SchoenbergConnectionKind.ROOT_COMMON_TONE,
                            tags = setOf(SchoenbergKnowledgeTag.COMMON_TONE),
                        )
                    )
                    return
                }
                val previous = prefix.last()
                triads
                    .filter { triad -> prefix.none { it.degree == triad.degree && it.quality == triad.quality } }
                    .filter { triad -> previous.sharesChordToneWith(triad) }
                    .forEach { visit(prefix + it) }
            }
            visit(listOf(tonic))
        }
    }

    private val ROOT_POSITION_SOURCE = TheorySourceRef(
        sourceId = "schoenberg-theory-of-harmony-root-position",
        edition = "1983 English translation",
        chapterOrTopic = "Directions for Better Progressions: root-position triads",
    )
}
