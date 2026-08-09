package com.mecon.theory.schoenberg

import com.mecon.theory.ChordArity
import com.mecon.theory.Key
import com.mecon.theory.RuleId
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.constraint.ChordTarget
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.constraint.ChordToneNeighborDirection
import com.mecon.theory.constraint.ChordToneNeighborRequirement
import com.mecon.theory.constraint.Constraint
import com.mecon.theory.constraint.ConstraintBranch
import com.mecon.theory.constraint.ConstraintExplanation
import com.mecon.theory.constraint.ConstraintExpr
import com.mecon.theory.constraint.ConstraintModality
import com.mecon.theory.constraint.ConstraintPredicate
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.SlotDomain
import com.mecon.theory.constraint.TargetSelector
import com.mecon.theory.constraint.ToneCompletenessRequirement
import com.mecon.theory.constraint.RootlessDominantNinthMetadata
import com.mecon.theory.constraint.DiatonicChordVocabulary
import com.mecon.theory.harmony.ChordCatalogCategoryDescriptor
import com.mecon.theory.harmony.ChordCatalogChapterProvider
import com.mecon.theory.harmony.ChordCatalogContribution
import com.mecon.theory.harmony.DiscoverableChordCatalogChapter
import com.mecon.theory.textbook.DominantSeventhRules
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookTriadPosition

/** 勋伯格七和弦：从和弦结构推导不协和音，不为各和弦与转位重复手写规则。 */
@DiscoverableChordCatalogChapter
object SchoenbergSeventhChordChapter : ChordCatalogChapterProvider {
    override val chordCatalogContributions = listOf(
        ChordCatalogContribution(
            category = ChordCatalogCategoryDescriptor(
                id = "diatonic-sevenths",
                order = 200,
                titleKey = "exploration.chordCatalog.diatonicSevenths.title",
                descriptionKey = "exploration.chordCatalog.diatonicSevenths.description",
            ),
            construct = { context, key ->
                DiatonicChordVocabulary.naturalSeventhUnionConstructions(context, key)
            },
        )
    )

    val PREPARATION_RULE_ID = RuleId("schoenberg.seventh-chord.dissonance-preparation")
    val RESOLUTION_RULE_ID = RuleId("schoenberg.seventh-chord.dissonance-resolution")
    val ROOT_AND_SEVENTH_PRESENT_RULE_ID = RuleId("schoenberg.seventh-chord.root-seventh-present")

    private val circleDegrees = listOf(4, 7, 3, 6, 2, 5)

    fun enumerate(key: Key): List<SchoenbergSymbolicProgression> {
        val triads = exerciseTriads(key, includeLeadingTriad = true)
        val tonic = triads.first { it.degree == TONIC_DEGREE }
            .toSymbolic(TextbookTriadPosition.ROOT_POSITION)
        val patterns = listOf(
            List(circleDegrees.size) { TextbookSeventhPosition.ROOT_POSITION },
            List(circleDegrees.size) { index -> TextbookSeventhPosition.entries[index.mod(4)] },
            List(circleDegrees.size) { index -> TextbookSeventhPosition.entries[(index + 1).mod(4)] },
            List(circleDegrees.size) { index -> TextbookSeventhPosition.entries[(index + 2).mod(4)] },
        )
        return patterns.map { positions ->
            SchoenbergSymbolicProgression(
                slots = listOf(tonic) + circleDegrees.mapIndexed { index, degree ->
                    val chord = DominantSeventhRules.seventhChordInKey(key, degree)
                    SchoenbergSymbolicChord(
                        degree = degree,
                        quality = chord.quality,
                        arity = ChordArity.SEVENTH,
                        seventhPosition = positions[index],
                    )
                } + tonic,
                kind = SchoenbergConnectionKind.SEVENTH_CIRCLE,
                knowledgeTags = setOf(SchoenbergKnowledgeTag.SEVENTH_CHORD),
            )
        }
    }

    fun program(
        key: Key,
        progression: SchoenbergSymbolicProgression? = null,
        searchConfig: SearchConfig = SearchConfig(maxResults = 4, beamWidth = 256),
    ): ConstraintProgram {
        val triads = exerciseTriads(key, includeLeadingTriad = true)
        val selected = progression ?: enumerate(key).first()
        val domains = exactProgressionDomains(selected, triads, circleDegrees.size + 2)
        val window = SlotWindow(0, domains.lastIndex)
        return ConstraintProgram.fromRequirements(
            key = key,
            slotDomains = domains,
            configuration = com.mecon.theory.constraint.ConstraintRequirementConfiguration(
                // 导七和弦的五音预备/解决与禁重复，复用一般导和弦规则（不限 arity），不在本章重写。
                chordToneNeighbors = leadingTriadNeighborRequirements(window),
                avoidDoublings = leadingFifthAvoidDoublings(domains.size, required = true),
                toneCompleteness = listOf(
                    ToneCompletenessRequirement(
                        window = SlotWindow(0, domains.lastIndex),
                        requiredTones = setOf(ChordTone.ROOT, ChordTone.SEVENTH),
                        selector = TargetSelector(arities = setOf(ChordArity.SEVENTH)),
                        required = true,
                        ruleId = ROOT_AND_SEVENTH_PRESENT_RULE_ID,
                        explanation = ConstraintExplanation(
                            "七和弦保留根音与七音；若省略，只会省略三音或五音。",
                            "七和弦不可省略根音或七音。",
                        ),
                    ),
                    ToneCompletenessRequirement(
                        window = SlotWindow(0, domains.lastIndex),
                        requiredTones = setOf(ChordTone.ROOT, ChordTone.THIRD, ChordTone.FIFTH),
                        selector = TargetSelector(arities = setOf(ChordArity.TRIAD)),
                    ),
                ),
                constraints = inferredDissonanceConstraints(key, domains).let { rules ->
                    rules + rules.map { it.asSuccessAnnotation() }
                },
                searchConfig = searchConfig,
                ruleModules = emptyList(),
                includeDerivedTextbookConstraints = false,

            ),
        )
    }

    /**
     * 半减/减七的五音与所有七和弦的七音都从实际结构进入同一预备、解决管线。
     *
     * [includeResolution]=false 时省略「七音下行级进解决」一条：综合练习改由 [seventhResolutionNeighborRequirements]
     * 以不限转位的 typed requirement 统一表达（既供求解，又供禁忌表探测器继承），避免同一解决规则在此再重复一份软约束。
     */
    internal fun inferredDissonanceConstraints(
        key: Key,
        domains: List<SlotDomain>,
        includeResolution: Boolean = true,
    ): List<Constraint> = buildList {
        domains.indices.forEach { slot ->
            val target = domains[slot].targets.single()
            if (target.arity != ChordArity.SEVENTH || slot == 0 || slot == domains.lastIndex) return@forEach
            if (RootlessDominantNinthMetadata.isRootlessDominantNinth(target)) return@forEach
            val tones = target.inferredDissonantTones()
            tones.forEach { tone ->
                val sourceDegree = scaleDegreeOf(key, target, tone)
                add(
                    neighborConstraint(
                        slot = slot,
                        target = target,
                        tone = tone,
                        direction = ChordToneNeighborDirection.PREVIOUS,
                        candidateDegree = sourceDegree,
                        delta = 0,
                        ruleId = PREPARATION_RULE_ID,
                        satisfied = "不协和的${tone.label()}已由前一和弦保持预备。",
                        violated = "不协和的${tone.label()}必须由前一和弦的协和音保持预备。",
                    )
                )
                if (includeResolution) {
                    add(
                        neighborConstraint(
                            slot = slot,
                            target = target,
                            tone = tone,
                            direction = ChordToneNeighborDirection.NEXT,
                            candidateDegree = (sourceDegree + 5).mod(7) + 1,
                            delta = -1,
                            ruleId = RESOLUTION_RULE_ID,
                            satisfied = "不协和的${tone.label()}已下行级进解决。",
                            violated = "不协和的${tone.label()}必须下行级进解决。",
                        )
                    )
                }
            }
            if (target.inversion == TextbookSeventhPosition.SECOND_INVERSION.ordinal) {
                add(
                    Constraint(
                        expr = ConstraintExpr.Or(
                            listOf(ChordTone.ROOT, ChordTone.FIFTH).map { tone ->
                                val (degree, alteration) = degreeAndAlteration(key, target, tone)
                                ConstraintBranch(
                                    neighborExpression(
                                        slot = slot,
                                        target = target,
                                        tone = tone,
                                        direction = ChordToneNeighborDirection.PREVIOUS,
                                        candidateDegree = degree,
                                        delta = 0,
                                        candidateAlteration = alteration,
                                    )
                                )
                            }
                        ),
                        modality = ConstraintModality.Require,
                        ruleId = PREPARATION_RULE_ID,
                        explanation = ConstraintExplanation(
                            "七和弦第二转位的根音或五音已由前一和弦保持预备。",
                            "七和弦第二转位还必须预备根音或五音之一。",
                        ),
                    )
                )
            }
        }
    }

    private fun neighborConstraint(
        slot: Int,
        target: ChordTarget,
        tone: ChordTone,
        direction: ChordToneNeighborDirection,
        candidateDegree: Int,
        delta: Int,
        ruleId: RuleId,
        satisfied: String,
        violated: String,
    ): Constraint = Constraint(
        expr = neighborExpression(slot, target, tone, direction, candidateDegree, delta),
        modality = ConstraintModality.Require,
        ruleId = ruleId,
        explanation = ConstraintExplanation(satisfied, violated),
    )

    private fun neighborExpression(
        slot: Int,
        target: ChordTarget,
        tone: ChordTone,
        direction: ChordToneNeighborDirection,
        candidateDegree: Int,
        delta: Int,
        candidateAlteration: Int = 0,
    ): ConstraintExpr = ConstraintExpr.Atom(
            ConstraintPredicate.NeighborTone(
                ChordToneNeighborRequirement(
                    window = SlotWindow(slot - 1, slot + 1),
                    sourceSlot = slot,
                    sourceTone = tone,
                    direction = direction,
                    candidateScaleDegrees = setOf(candidateDegree),
                    candidateAlterations = setOf(candidateAlteration),
                    allowedDiatonicStepDeltas = setOf(delta),
                    sourceSelector = exactSelector(target),
                )
            )
    )

    private fun scaleDegreeOf(key: Key, target: ChordTarget, tone: ChordTone): Int =
        degreeAndAlteration(key, target, tone).first

    /**
     * 把和弦音定位到（自然音级, 变化半音）。小调旋律变体七和弦的根 / 五音可能是升六 / 导音等阶外音
     * （如 vii°7 的根音 G#），indexOf 在自然音阶内找不到；此时按相差半音的邻近自然音级 + 变化量表示。
     */
    private fun degreeAndAlteration(key: Key, target: ChordTarget, tone: ChordTone): Pair<Int, Int> {
        val pitchClass = target.pitchClassFor(tone) ?: error("Missing $tone in seventh chord")
        val natural = key.scale.pitchClasses.indexOf(pitchClass)
        if (natural >= 0) return (natural + 1) to 0
        for (alteration in listOf(1, -1)) {
            val index = key.scale.pitchClasses.indexOfFirst { it.transpose(alteration) == pitchClass }
            if (index >= 0) return (index + 1) to alteration
        }
        error("$tone must be within a semitone of the exercise key")
    }

    /**
     * 七和弦章节只负责七音。减五度（如导七和弦的五音）是其**对应三和弦**（导三和弦）的音，
     * 由一般导和弦规则（[leadingTriadNeighborRequirements]）统一处理，此处不重写——书中三和弦规则在七和弦一体适用。
     */
    private fun ChordTarget.inferredDissonantTones(): List<ChordTone> = listOf(ChordTone.SEVENTH)

    private fun ChordTone.label(): String = when (this) {
        ChordTone.FIFTH -> "五音"
        ChordTone.SEVENTH -> "七音"
        else -> name.lowercase()
    }
}
