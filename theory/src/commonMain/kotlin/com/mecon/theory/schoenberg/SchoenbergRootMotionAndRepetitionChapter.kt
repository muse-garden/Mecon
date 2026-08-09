package com.mecon.theory.schoenberg

import com.mecon.theory.FixedVoiceRole
import com.mecon.theory.Key
import com.mecon.theory.RuleId
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.constraint.ChordToneVoiceFilter
import com.mecon.theory.constraint.Constraint
import com.mecon.theory.constraint.ConstraintBranch
import com.mecon.theory.constraint.ConstraintExplanation
import com.mecon.theory.constraint.ConstraintExpr
import com.mecon.theory.constraint.ConstraintModality
import com.mecon.theory.constraint.ConstraintPredicate
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.ConstraintScope
import com.mecon.theory.constraint.RootProgressionScore
import com.mecon.theory.constraint.RootProgressionScoringPolicy
import com.mecon.theory.constraint.VoiceExtreme

enum class SchoenbergRootMotionDirection {
    RISING,
    DESCENDING,
    SUPERSTRONG,
    REPEATED,
}

/**
 * General chapter following the major/minor connection branches.
 *
 * It deliberately reuses the complete integrated vocabulary: every natural triad and seventh chord
 * available in the key, together with all inversions introduced by the preceding chapters.
 */
object SchoenbergRootMotionAndRepetitionChapter {
    val RISING_RULE_ID = RuleId("schoenberg.root-motion.rising")
    val DESCENDING_RULE_ID = RuleId("schoenberg.root-motion.descending")
    val SUPERSTRONG_RULE_ID = RuleId("schoenberg.root-motion.superstrong")
    val DESCENDING_COMPENSATION_RULE_ID = RuleId("schoenberg.root-motion.descending-compensation")
    val UNIQUE_SOPRANO_CLIMAX_RULE_ID = RuleId("schoenberg.repetition.unique-soprano-climax")
    val UNIQUE_BASS_NADIR_RULE_ID = RuleId("schoenberg.repetition.unique-bass-nadir")
    val MELODIC_REPETITION_RULE_ID = RuleId("schoenberg.repetition.melodic-pattern")
    val SIMILAR_CHORD_DISTANCE_RULE_ID = RuleId("schoenberg.repetition.similar-chord-distance")
    val SIMILAR_PROGRESSION_RULE_ID = RuleId("schoenberg.repetition.similar-progression")
    val ROOT_PROGRESSION_SCORE_RULE_ID = RuleId("schoenberg.root-motion.preference-score")

    private val RISING_DELTAS = setOf(3, 5)
    private val DESCENDING_DELTAS = setOf(2, 4)
    private val SUPERSTRONG_DELTAS = setOf(1, 6)
    val ROOT_PROGRESSION_SCORING_POLICY = RootProgressionScoringPolicy(
        superstrongDeltas = SUPERSTRONG_DELTAS,
        superstrongWeight = 10.0,
        consecutiveSuperstrongWeight = 6.0,
        similarChordProximityWeight = 18.0,
    )

    fun program(
        key: Key,
        continuationChordCount: Int,
        progression: SchoenbergSymbolicProgression? = null,
        searchConfig: SearchConfig = SearchConfig(maxResults = 4, beamWidth = 192),
    ): ConstraintProgram {
        val base = SchoenbergIntegratedTechTree.program(
            key = key,
            continuationChordCount = continuationChordCount,
            treatmentIds = SchoenbergHarmonicTreatments.integratedDiatonicTreatments,
            progression = progression,
            searchConfig = searchConfig,
            requireAdjacentCommonTone = false,
        )
        return base.copy(constraints = base.constraints + constraints(base.length))
    }

    fun enumerate(
        key: Key,
        continuationChordCount: Int,
        chordSelectors: List<com.mecon.theory.constraint.TargetSelector> = emptyList(),
        budget: SchoenbergIntegratedTechTree.EnumerationBudget =
            SchoenbergIntegratedTechTree.EnumerationBudget(),
    ): List<SchoenbergSymbolicProgression> =
        SchoenbergIntegratedTechTree.enumerate(
            key = key,
            options = SchoenbergIntegratedTechTree.EnumerationOptions(
                continuationChordCount = continuationChordCount,
                treatmentIds = SchoenbergHarmonicTreatments.integratedDiatonicTreatments,
                budget = budget,
                chordSelectors = chordSelectors,
                requireAdjacentCommonTone = false,
                applyRootMotionDirection = true,
                applyHarmonicRepetitionPolicy = true,
            ),
        ).sortedBy { progression -> enumerationScore(progression.slots).total }

    fun classify(fromDegree: Int, toDegree: Int): SchoenbergRootMotionDirection {
        require(fromDegree in 1..7 && toDegree in 1..7) { "Root degrees must be in 1..7" }
        return when ((toDegree - fromDegree).mod(7)) {
            in RISING_DELTAS -> SchoenbergRootMotionDirection.RISING
            in DESCENDING_DELTAS -> SchoenbergRootMotionDirection.DESCENDING
            in SUPERSTRONG_DELTAS -> SchoenbergRootMotionDirection.SUPERSTRONG
            else -> SchoenbergRootMotionDirection.REPEATED
        }
    }

    /**
     * A descending progression is legal only as a temporary motion: one more chord must follow,
     * and the two-step root result must not itself be descending.
     */
    fun followsDirectionPolicy(slots: List<SchoenbergSymbolicChord>): Boolean =
        (0 until slots.lastIndex).all { index ->
            if (classify(slots[index].degree, slots[index + 1].degree) != SchoenbergRootMotionDirection.DESCENDING) {
                true
            } else {
                index + 2 < slots.size &&
                    classify(slots[index].degree, slots[index + 2].degree) !=
                    SchoenbergRootMotionDirection.DESCENDING
            }
        }

    /**
     * Chords are similar when their roots share a scale degree. Inversion, triad/seventh arity,
     * and quality therefore do not disguise a recurrence.
     */
    fun followsHarmonicRepetitionPolicy(
        slots: List<SchoenbergSymbolicChord>,
        minimumSimilarChordDistance: Int = 3,
    ): Boolean {
        val similarChordsAreSeparated = slots.indices.all { earlier ->
            (earlier + 1 until slots.size).none { later ->
                later - earlier < minimumSimilarChordDistance &&
                    slots[earlier].degree == slots[later].degree
            }
        }
        if (!similarChordsAreSeparated) return false
        val rootProgressions = slots.zipWithNext { before, after -> before.degree to after.degree }
        return rootProgressions.size == rootProgressions.distinct().size
    }

    /**
     * Enumeration ranking is intentionally softer than the validity policy:
     * legal progressions remain available, but cautious root motion and well-separated recurrences rank first.
     */
    fun enumerationScore(slots: List<SchoenbergSymbolicChord>): RootProgressionScore =
        ROOT_PROGRESSION_SCORING_POLICY.score(slots.map { it.degree })

    internal fun constraints(length: Int): List<Constraint> =
        constraints(SlotWindow(0, length - 1))

    /**
     * Applies the chapter's shared rule body to one stable tonal region. Modulation exercises use
     * this overload on the source and destination regions independently so scale-degree motion is
     * never measured across two different tonal coordinate systems.
     */
    internal fun constraints(window: SlotWindow): List<Constraint> {
        val end = requireNotNull(window.end) {
            "Root-motion chapter requires a bounded slot window"
        }
        require(end > window.start) { "Root-motion chapter requires at least two slots" }
        return buildList {
            for (fromSlot in window.start until end) {
                val toSlot = fromSlot + 1
                add(
                    annotation(
                        rootMotion(fromSlot, toSlot, RISING_DELTAS),
                        RISING_RULE_ID,
                        "上升进行：根音上行四度会使前和弦根音成为后和弦五音；根音下行三度会保留两个共同音，并使前根音成为后和弦三音。",
                    )
                )
                add(
                    annotation(
                        rootMotion(fromSlot, toSlot, DESCENDING_DELTAS),
                        DESCENDING_RULE_ID,
                        "下降进行：后和弦把前和弦较不重要的音提升为根音；它须由下一进行补偿，因而只作为临时和弦。",
                    )
                )
                add(
                    annotation(
                        rootMotion(fromSlot, toSlot, SUPERSTRONG_DELTAS),
                        SUPERSTRONG_RULE_ID,
                        "超越进行：根音二度上行或下行，不保留旧和弦音；可以使用，但应当节省。",
                    )
                )
                val descending = rootMotion(fromSlot, toSlot, DESCENDING_DELTAS)
                val compensationExpr = if (toSlot < end) {
                    ConstraintExpr.Or(
                        listOf(
                            ConstraintBranch(ConstraintExpr.Not(descending)),
                            ConstraintBranch(
                                ConstraintExpr.Not(rootMotion(fromSlot, toSlot + 1, DESCENDING_DELTAS))
                            ),
                        )
                    )
                } else {
                    ConstraintExpr.Not(descending)
                }
                add(
                    Constraint(
                        expr = compensationExpr,
                        modality = ConstraintModality.Require,
                        ruleId = DESCENDING_COMPENSATION_RULE_ID,
                        explanation = ConstraintExplanation(
                            satisfied = "下降进行已由后续根音方向补偿。",
                            violated = "下降进行后须再接一个进行，且两步后的根音结果不可仍是下降进行。",
                        ),
                    )
                )
            }

            add(
                globalPreference(
                    predicate = ConstraintPredicate.RootProgressionPreference(
                        scoringPolicy = ROOT_PROGRESSION_SCORING_POLICY,
                    ),
                    modality = ConstraintModality.Prefer(),
                    ruleId = ROOT_PROGRESSION_SCORE_RULE_ID,
                    satisfied = "根音进行没有产生谨慎使用成本。",
                    violated = "根音进行包含超越、连续超越或较近的类似和弦回返。",
                    scope = ConstraintScope(window = window),
                )
            )
            add(
                globalPreference(
                    predicate = ConstraintPredicate.MinimumSimilarChordDistance(
                        minimumSlotDistance = 3,
                    ),
                    modality = ConstraintModality.Require,
                    ruleId = SIMILAR_CHORD_DISTANCE_RULE_ID,
                    satisfied = "同根音的类似和弦保持了足够间隔。",
                    violated = "同根音的不同转位、三和弦或七和弦都属于类似和弦；两次出现之间至少应隔开两个和弦。",
                    scope = ConstraintScope(window = window),
                )
            )
            add(
                globalPreference(
                    predicate = ConstraintPredicate.DistinctSimilarChordProgressions,
                    modality = ConstraintModality.Require,
                    ruleId = SIMILAR_PROGRESSION_RULE_ID,
                    satisfied = "没有反复已经出现过的类似和弦进行。",
                    violated = "出现了相同根音对构成的类似进行；改变转位或改用同根音七和弦不能消除这种反复。",
                    scope = ConstraintScope(window = window),
                )
            )
            add(
                globalPreference(
                    predicate = ConstraintPredicate.UniqueVoiceExtreme(
                        voiceFilter = ChordToneVoiceFilter.SOPRANO,
                        extreme = VoiceExtreme.HIGHEST,
                    ),
                    modality = ConstraintModality.Require,
                    ruleId = UNIQUE_SOPRANO_CLIMAX_RULE_ID,
                    satisfied = "高音声部的最高点只出现一次。",
                    violated = "高音声部的最高点只能出现一次，以免无意强调该音。",
                    scope = ConstraintScope(window = window),
                )
            )
            add(
                globalPreference(
                    predicate = ConstraintPredicate.UniqueVoiceExtreme(
                        voiceFilter = ChordToneVoiceFilter.BASS,
                        extreme = VoiceExtreme.LOWEST,
                    ),
                    modality = ConstraintModality.Prefer(weight = 6.0),
                    ruleId = UNIQUE_BASS_NADIR_RULE_ID,
                    satisfied = "低音声部的最低点只出现一次。",
                    violated = "低音声部的最低音宜只出现一次。",
                    scope = ConstraintScope(window = window),
                )
            )
            listOf(
                FixedVoiceRole.SOPRANO to 2.0,
                FixedVoiceRole.BASS to 2.0,
                FixedVoiceRole.ALTO to 1.0,
                FixedVoiceRole.TENOR to 1.0,
            ).forEach { (role, scale) ->
                add(
                    globalPreference(
                        predicate = ConstraintPredicate.NoRepeatedVoicePattern(
                            voiceFilter = role.toVoiceFilter(),
                            minPatternNotes = 2,
                            maxPatternNotes = 5,
                            penaltyScale = scale,
                        ),
                        modality = ConstraintModality.Prefer(),
                        ruleId = MELODIC_REPETITION_RULE_ID,
                        satisfied = "${role.chineseName()}旋律没有近距离反复。",
                        violated = "${role.chineseName()}旋律出现了相同或移位的近距离反复；外声部反复的权重更高。",
                        scope = ConstraintScope(window = window),
                    )
                )
            }
        }
    }

    private fun rootMotion(fromSlot: Int, toSlot: Int, deltas: Set<Int>): ConstraintExpr =
        ConstraintExpr.Atom(
            ConstraintPredicate.RootDiatonicMotion(
                fromSlot = fromSlot,
                toSlot = toSlot,
                allowedDeltas = deltas,
            )
        )

    private fun annotation(expr: ConstraintExpr, ruleId: RuleId, message: String): Constraint =
        Constraint(
            expr = ConstraintExpr.And(listOf(expr)),
            modality = ConstraintModality.Annotate,
            ruleId = ruleId,
            explanation = ConstraintExplanation(message),
        )

    private fun globalPreference(
        predicate: ConstraintPredicate,
        modality: ConstraintModality,
        ruleId: RuleId,
        satisfied: String,
        violated: String,
        scope: ConstraintScope = ConstraintScope(),
    ): Constraint =
        Constraint(
            expr = ConstraintExpr.And(listOf(ConstraintExpr.Atom(predicate))),
            modality = modality,
            ruleId = ruleId,
            explanation = ConstraintExplanation(satisfied, violated),
            scope = scope,
        )

    private fun FixedVoiceRole.toVoiceFilter(): ChordToneVoiceFilter = when (this) {
        FixedVoiceRole.SOPRANO -> ChordToneVoiceFilter.SOPRANO
        FixedVoiceRole.ALTO -> ChordToneVoiceFilter.ALTO
        FixedVoiceRole.TENOR -> ChordToneVoiceFilter.TENOR
        FixedVoiceRole.BASS -> ChordToneVoiceFilter.BASS
        else -> error("Unsupported four-part role $this")
    }

    private fun FixedVoiceRole.chineseName(): String = when (this) {
        FixedVoiceRole.SOPRANO -> "高音声部"
        FixedVoiceRole.ALTO -> "女低音声部"
        FixedVoiceRole.TENOR -> "男高音声部"
        FixedVoiceRole.BASS -> "低音声部"
        else -> name
    }
}
