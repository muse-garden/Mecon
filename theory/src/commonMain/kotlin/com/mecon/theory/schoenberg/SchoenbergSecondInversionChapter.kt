package com.mecon.theory.schoenberg

import com.mecon.theory.ChordArity
import com.mecon.theory.Key
import com.mecon.theory.NaturalTriad
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.constraint.ChordToneNeighborDirection
import com.mecon.theory.constraint.ChordToneNeighborRequirement
import com.mecon.theory.constraint.ChordToneVoiceFilter
import com.mecon.theory.constraint.Constraint
import com.mecon.theory.constraint.ConstraintBranch
import com.mecon.theory.constraint.ConstraintExplanation
import com.mecon.theory.constraint.ConstraintExpr
import com.mecon.theory.constraint.ConstraintModality
import com.mecon.theory.constraint.ConstraintPredicate
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.SlotDomain
import com.mecon.theory.constraint.TargetFeatureBonusRequirement
import com.mecon.theory.constraint.TargetSelector
import com.mecon.theory.constraint.ToneCompletenessRequirement
import com.mecon.theory.textbook.TextbookTriadPosition

/** 勋伯格四六和弦：只保留预备、解决与不可连续三条可推导原则。 */
object SchoenbergSecondInversionChapter {
    val NO_CONSECUTIVE_RULE_ID = com.mecon.theory.RuleId("schoenberg.second-inversion.no-consecutive")
    val PREPARATION_RULE_ID = com.mecon.theory.RuleId("schoenberg.second-inversion.preparation")
    val BASS_RESOLUTION_RULE_ID = com.mecon.theory.RuleId("schoenberg.second-inversion.bass-resolution")

    fun enumerate(key: Key): List<SchoenbergSymbolicProgression> {
        val triads = exerciseTriads(key, includeLeadingTriad = true)
        val surroundingPositions = listOf(
            TextbookTriadPosition.ROOT_POSITION,
            TextbookTriadPosition.FIRST_INVERSION,
        )
        return buildList {
            triads.forEach { before ->
                triads.forEach { sixFour ->
                    if (!preparesRootOrBass(before, sixFour)) return@forEach
                    triads.forEach { after ->
                        surroundingPositions.forEach { beforePosition ->
                            surroundingPositions.forEach { afterPosition ->
                                if (!bassCanResolveByStep(sixFour, after, afterPosition, key)) return@forEach
                                add(
                                    SchoenbergSymbolicProgression(
                                        slots = listOf(
                                            before.toSymbolic(beforePosition),
                                            sixFour.toSymbolic(TextbookTriadPosition.SECOND_INVERSION),
                                            after.toSymbolic(afterPosition),
                                        ),
                                        kind = SchoenbergConnectionKind.SECOND_INVERSION_PREPARATION_RESOLUTION,
                                        knowledgeTags = setOf(SchoenbergKnowledgeTag.SECOND_INVERSION),
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }.distinctBy { it.slots }.take(64)
    }

    fun program(
        key: Key,
        progression: SchoenbergSymbolicProgression? = null,
        searchConfig: SearchConfig = SearchConfig(maxResults = 4, beamWidth = 192),
    ): ConstraintProgram {
        val triads = exerciseTriads(key, includeLeadingTriad = true)
        val selected = progression ?: enumerate(key).firstOrNull()
            ?: error("Current key has no Schoenberg six-four progression")
        val domains = exactProgressionDomains(selected, triads, 3)
        return ConstraintProgram.fromRequirements(
            key = key,
            slotDomains = domains,
            configuration = com.mecon.theory.constraint.ConstraintRequirementConfiguration(
                toneCompleteness = listOf(
                    ToneCompletenessRequirement(
                        window = SlotWindow(0, 2),
                        requiredTones = setOf(ChordTone.ROOT, ChordTone.THIRD, ChordTone.FIFTH),
                        selector = TargetSelector(arities = setOf(ChordArity.TRIAD)),
                    )
                ),
                constraints = sixFourPrinciples(key, domains).let { rules ->
                    rules + rules.map { it.asSuccessAnnotation() }
                },
                searchConfig = searchConfig,
                ruleModules = emptyList(),
                includeDerivedTextbookConstraints = false,

            ),
        )
    }

    internal fun sixFourPrinciples(key: Key, domains: List<SlotDomain>): List<Constraint> = buildList {
        domains.indices.zipWithNext().forEach { (before, after) ->
            add(
                Constraint(
                    expr = ConstraintExpr.Not(
                        ConstraintExpr.And(listOf(secondInversionAt(before), secondInversionAt(after)))
                    ),
                    modality = ConstraintModality.Require,
                    ruleId = NO_CONSECUTIVE_RULE_ID,
                    explanation = ConstraintExplanation(
                        "没有连续使用两个四六和弦。",
                        "一个四六和弦不能直接连接到另一个四六和弦。",
                    ),
                )
            )
        }
        domains.indices.drop(1).forEach { slot ->
            val target = domains[slot].targets.single()
            if (target.arity != ChordArity.TRIAD || target.inversion != 2) return@forEach
            val selector = exactSelector(target)
            val rootPreparation = heldPreparation(key, slot, selector, ChordTone.ROOT, target)
            val bassPreparation = heldPreparation(key, slot, selector, ChordTone.BASS, target)
            add(
                Constraint(
                    expr = ConstraintExpr.Or(
                        listOf(ConstraintBranch(rootPreparation), ConstraintBranch(bassPreparation))
                    ),
                    modality = ConstraintModality.Require,
                    ruleId = PREPARATION_RULE_ID,
                    explanation = ConstraintExplanation(
                        "四六和弦的根音或低音由前一和弦保持预备。",
                        "四六和弦的根音或低音至少要有一个由前一和弦保持预备。",
                    ),
                )
            )
            if (slot < domains.lastIndex) {
                add(
                    Constraint(
                        expr = ConstraintExpr.Atom(
                            ConstraintPredicate.VoiceDiatonicSteps(
                                voiceFilter = ChordToneVoiceFilter.BASS,
                                slots = listOf(slot, slot + 1),
                                allowedDeltas = listOf(setOf(-1, 0, 1)),
                            )
                        ),
                        modality = ConstraintModality.Require,
                        ruleId = BASS_RESOLUTION_RULE_ID,
                        explanation = ConstraintExplanation(
                            "四六和弦低音以保持或级进解决。",
                            "四六和弦解决时，低音必须保持或级进。",
                        ),
                    )
                )
            }
        }
    }

    private fun heldPreparation(
        key: Key,
        slot: Int,
        selector: TargetSelector,
        tone: ChordTone,
        target: com.mecon.theory.constraint.ChordTarget,
    ): ConstraintExpr {
        val pitchClass = target.pitchClassFor(tone) ?: error("Missing $tone in six-four chord")
        // 小调的旋律变体四六和弦（如根 / 低音为升六、导音）其音级不在自然小调音阶内，indexOf 返回 -1。
        // 「保持」预备为 delta 0（同音持续），候选 PC 须等于源 PC，故按自然音级 + 变化半音定位。
        val (degree, alteration) = degreeAndAlteration(key, pitchClass)
        return ConstraintExpr.Atom(
            ConstraintPredicate.NeighborTone(
                ChordToneNeighborRequirement(
                    window = SlotWindow(slot - 1, slot),
                    sourceSlot = slot,
                    sourceTone = tone,
                    direction = ChordToneNeighborDirection.PREVIOUS,
                    candidateScaleDegrees = setOf(degree),
                    candidateAlterations = setOf(alteration),
                    allowedDiatonicStepDeltas = setOf(0),
                    sourceSelector = selector,
                )
            )
        )
    }

    /** 把音级 PC 定位到（自然小调音级, 变化半音）：优先自然音级，否则找相差半音的邻近自然音级。 */
    private fun degreeAndAlteration(key: Key, pitchClass: com.mecon.api.primitive.PitchClass): Pair<Int, Int> {
        val natural = key.scale.pitchClasses.indexOf(pitchClass)
        if (natural >= 0) return (natural + 1) to 0
        for (alteration in listOf(1, -1)) {
            val index = key.scale.pitchClasses.indexOfFirst { it.transpose(alteration) == pitchClass }
            if (index >= 0) return (index + 1) to alteration
        }
        error("Six-four preparation tone must be within a semitone of the key")
    }

    private fun secondInversionAt(slot: Int): ConstraintExpr = ConstraintExpr.Atom(
        ConstraintPredicate.TargetMatches(
            TargetFeatureBonusRequirement(
                window = SlotWindow(slot, slot),
                selector = TargetSelector(inversions = setOf(2), arities = setOf(ChordArity.TRIAD)),
                ruleId = NO_CONSECUTIVE_RULE_ID,
                message = "四六和弦目标。",
                bonus = 0.0,
            )
        )
    )

    private fun preparesRootOrBass(before: NaturalTriad, sixFour: NaturalTriad): Boolean =
        sixFour.chord.root in before.chord.pitchClasses ||
            sixFour.chord.pitchClasses[FIFTH_INDEX] in before.chord.pitchClasses

    private fun bassCanResolveByStep(
        sixFour: NaturalTriad,
        after: NaturalTriad,
        afterPosition: TextbookTriadPosition,
        key: Key,
    ): Boolean {
        val from = key.scale.pitchClasses.indexOf(sixFour.chord.pitchClasses[FIFTH_INDEX])
        val to = key.scale.pitchClasses.indexOf(after.toTarget(afterPosition).bassPitchClass)
        if (from < 0 || to < 0) return false
        val wrapped = (to - from).mod(7)
        return wrapped == 0 || wrapped == 1 || wrapped == 6
    }
}

internal fun Constraint.asSuccessAnnotation(): Constraint =
    copy(modality = ConstraintModality.Annotate)

/**
 * 综合练习里，章节原则（四六和弦预备/解决、七和弦不协和音预备/解决）作为软偏好参与打分，而不作硬约束。
 * 独立章节练习用精选进行、以硬约束严格教学；综合练习的进行是枚举拼接的，硬约束会与四部写作规则相互挤死、
 * 导致「搜不出来」。软化后求解器总能给出合规四部实现，同时通过 SOFT/HINT finding 提示原则的满足与否。
 */
internal fun Constraint.asSoftPreference(weight: Double = 6.0): Constraint =
    copy(modality = ConstraintModality.Prefer(weight = weight))

internal fun exactSelector(target: com.mecon.theory.constraint.ChordTarget): TargetSelector =
    TargetSelector(
        degrees = setOf(target.degree),
        qualities = setOf(target.quality),
        inversions = setOf(target.inversion),
        arities = setOf(target.arity),
    )
