package com.mecon.theory.schoenberg

import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.Key
import com.mecon.theory.Mode
import com.mecon.theory.RuleId
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.constraint.ChordToneNeighborDirection
import com.mecon.theory.constraint.ChordToneNeighborRequirement
import com.mecon.theory.constraint.ChordToneVoiceFilter
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.constraint.Constraint
import com.mecon.theory.constraint.ConstraintBranch
import com.mecon.theory.constraint.ConstraintExplanation
import com.mecon.theory.constraint.ConstraintExpr
import com.mecon.theory.constraint.ConstraintModality
import com.mecon.theory.constraint.ConstraintPredicate
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.ConstraintScope
import com.mecon.theory.constraint.TargetFeatureBonusRequirement
import com.mecon.theory.constraint.TargetSelector
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookTriadPosition

data class SchoenbergCadenceOptions(
    val includeDeceptiveCadence: Boolean = false,
    val includeCadentialSixFour: Boolean = false,
)

interface SchoenbergSymbolicSequencePolicy {
    fun allowsPrefix(prefix: List<SchoenbergSymbolicChord>, totalLength: Int): Boolean
    fun constraints(length: Int): List<Constraint>
    fun exemptsHarmonicRepetitionAt(slot: Int, totalLength: Int): Boolean = false
}

/**
 * One immutable cadence definition shared by symbolic enumeration and runtime constraints.
 * It owns both the fixed terminal suffix and the optional earlier-event requirements.
 */
internal data class SchoenbergCadencePolicy(
    val options: SchoenbergCadenceOptions,
    val requireFreerLeadingSubstitution: Boolean = false,
    val minor: Boolean = false,
) : SchoenbergSymbolicSequencePolicy {
    override fun exemptsHarmonicRepetitionAt(slot: Int, totalLength: Int): Boolean =
        slot >= totalLength - suffixPredicates().size

    override fun allowsPrefix(prefix: List<SchoenbergSymbolicChord>, totalLength: Int): Boolean {
        val suffix = suffixPredicates()
        val suffixStart = totalLength - suffix.size
        if (suffixStart < 0) return false
        prefix.indices.forEach { slot ->
            val suffixOffset = slot - suffixStart
            if (suffixOffset in suffix.indices && !suffix[suffixOffset](prefix[slot])) return false
        }
        val body = prefix.take(minOf(prefix.size, suffixStart))
        if (minimumAdditionalBodySlots(body) > suffixStart - body.size) return false
        if (prefix.size < totalLength) return true
        if (options.includeDeceptiveCadence && !hasDeceptiveCadence(prefix, suffixStart)) return false
        if (requireFreerLeadingSubstitution && !hasFreerLeadingSubstitution(prefix, suffixStart)) return false
        return true
    }

    /** Shortest program that still fits the terminal suffix and every required pre-cadence event. */
    fun minimumProgramLength(): Int =
        suffixPredicates().size + minimumAdditionalBodySlots(emptyList())

    override fun constraints(length: Int): List<Constraint> {
        val suffix = suffixExpressionBuilders()
        val suffixStart = length - suffix.size
        require(suffixStart >= 0) { "Cadence exercise is too short for its terminal suffix" }
        return buildList {
            suffix.forEachIndexed { offset, expressionAt ->
                add(
                    required(
                        expr = expressionAt(suffixStart + offset),
                        ruleId = when {
                            options.includeCadentialSixFour && offset == 1 ->
                                SchoenbergCadenceChapter.CADENTIAL_SIX_FOUR_RULE_ID
                            else -> SchoenbergCadenceChapter.AUTHENTIC_CADENCE_RULE_ID
                        },
                        violated = "末尾必须使用规定的正格终止式结构。",
                    )
                )
            }
            if (options.includeDeceptiveCadence) {
                add(
                    required(
                        expr = occurrenceBefore(suffixStart, ::deceptivePairExpr),
                        ruleId = SchoenbergCadenceChapter.DECEPTIVE_CADENCE_RULE_ID,
                        violated = "最终正格终止之前必须出现一次 V/V7-VI 或 V/V7-IV。",
                    )
                )
            }
            if (requireFreerLeadingSubstitution) {
                add(
                    required(
                        expr = occurrenceBefore(suffixStart, ::freerLeadingPairExpr),
                        ruleId = SchoenbergFreerDissonanceChapter.LEADING_SUBSTITUTION_RULE_ID,
                        violated = "自由处理练习必须使用一次 VII6-I/VI/IV/ii 的属功能替代连接。",
                    )
                )
            }
        }
    }

    private fun suffixPredicates(): List<(SchoenbergSymbolicChord) -> Boolean> =
        buildList {
            add(::isPredominant)
            if (options.includeCadentialSixFour) add(::isCadentialSixFour)
            add(::isRootDominant)
            add(::isRootTonic)
        }

    private fun suffixExpressionBuilders(): List<(Int) -> ConstraintExpr> =
        buildList {
            add { slot ->
                ConstraintExpr.Or(
                    listOf(
                        ConstraintBranch(
                            targetAt(
                                slot,
                                TargetSelector(
                                    degrees = PREDOMINANT_DEGREES,
                                    arities = setOf(ChordArity.TRIAD),
                                ),
                                "终止式的前属三和弦。",
                            )
                        ),
                        ConstraintBranch(
                            targetAt(
                                slot,
                                TargetSelector(
                                    degrees = PREDOMINANT_DEGREES,
                                    arities = setOf(ChordArity.SEVENTH),
                                ),
                                "终止式的前属七和弦。",
                            )
                        ),
                    )
                )
            }
            if (options.includeCadentialSixFour) {
                add { slot ->
                    targetAt(
                        slot,
                        TargetSelector(
                            degrees = setOf(TONIC_DEGREE),
                            arities = setOf(ChordArity.TRIAD),
                            inversions = setOf(TextbookTriadPosition.SECOND_INVERSION.ordinal),
                        ),
                        "终止四六和弦。",
                    )
                }
            }
            add { slot ->
                targetAt(
                    slot,
                    SchoenbergCadenceChapter.authenticDominantSelector(minor),
                    "终止式的原位属和弦。",
                )
            }
            add { slot ->
                targetAt(
                    slot,
                    TargetSelector(
                        degrees = setOf(TONIC_DEGREE),
                        arities = setOf(ChordArity.TRIAD),
                        inversions = setOf(0),
                    ),
                    "终止式的原位主和弦。",
                )
            }
        }

    private fun occurrenceBefore(
        suffixStart: Int,
        pairExpression: (Int) -> ConstraintExpr,
    ): ConstraintExpr {
        val starts = 0 until (suffixStart - 1)
        require(!starts.isEmpty()) { "Cadence option requires room before the terminal suffix" }
        return ConstraintExpr.Or(starts.map { ConstraintBranch(pairExpression(it)) })
    }

    private fun deceptivePairExpr(slot: Int): ConstraintExpr =
        ConstraintExpr.And(
            listOf(
                targetAt(
                    slot,
                    SchoenbergCadenceChapter.dominantSelector(minor),
                    "阻碍终止的属和弦。",
                ),
                targetAt(
                    slot + 1,
                    TargetSelector(degrees = DECEPTIVE_DESTINATIONS),
                    "阻碍终止的替代目标。",
                ),
            )
        )

    private fun freerLeadingPairExpr(slot: Int): ConstraintExpr =
        ConstraintExpr.And(
            listOf(
                targetAt(
                    slot,
                    TargetSelector(
                        degrees = setOf(LEADING_TONE_DEGREE),
                        arities = setOf(ChordArity.TRIAD),
                        inversions = setOf(TextbookTriadPosition.FIRST_INVERSION.ordinal),
                    ),
                    "作为属功能替代的 VII6。",
                ),
                targetAt(
                    slot + 1,
                    TargetSelector(degrees = FREER_LEADING_DESTINATIONS),
                    "VII6 的自由功能目标。",
                ),
            )
        )

    private fun targetAt(slot: Int, selector: TargetSelector, message: String): ConstraintExpr =
        ConstraintExpr.Atom(
            ConstraintPredicate.TargetMatches(
                TargetFeatureBonusRequirement(
                    window = SlotWindow(slot, slot),
                    selector = selector,
                    ruleId = SchoenbergCadenceChapter.AUTHENTIC_CADENCE_RULE_ID,
                    message = message,
                    bonus = 0.0,
                )
            )
        )

    private fun required(expr: ConstraintExpr, ruleId: RuleId, violated: String): Constraint =
        Constraint(
            expr = expr,
            modality = ConstraintModality.Require,
            ruleId = ruleId,
            explanation = ConstraintExplanation(
                satisfied = "符合勋伯格的终止式结构。",
                violated = violated,
            ),
        )

    private fun hasDeceptiveCadence(slots: List<SchoenbergSymbolicChord>, suffixStart: Int): Boolean =
        hasDeceptiveCadenceIn(slots.take(suffixStart))

    private fun hasFreerLeadingSubstitution(slots: List<SchoenbergSymbolicChord>, suffixStart: Int): Boolean =
        hasFreerLeadingSubstitutionIn(slots.take(suffixStart))

    private fun hasDeceptiveCadenceIn(body: List<SchoenbergSymbolicChord>): Boolean =
        body.zipWithNext().any { (before, after) ->
            isDominant(before) && after.degree in DECEPTIVE_DESTINATIONS
        }

    private fun hasFreerLeadingSubstitutionIn(body: List<SchoenbergSymbolicChord>): Boolean =
        body.zipWithNext().any { (before, after) ->
            isFreerLeadingSource(before) && after.degree in FREER_LEADING_DESTINATIONS
        }

    /**
     * Propagates required cadence events while the prefix is still being built. A missing
     * V-IV/VI or VII6-target event needs two body slots, or one when its source is already
     * the last chord. The two events cannot overlap because neither destination is the
     * other event's source degree.
     */
    private fun minimumAdditionalBodySlots(body: List<SchoenbergSymbolicChord>): Int {
        val deceptiveMissing = options.includeDeceptiveCadence && !hasDeceptiveCadenceIn(body)
        val leadingMissing = requireFreerLeadingSubstitution && !hasFreerLeadingSubstitutionIn(body)
        val missingCount = listOf(deceptiveMissing, leadingMissing).count { it }
        if (missingCount == 0) return 0
        val pendingEventCanComplete =
            deceptiveMissing && body.lastOrNull()?.let(::isDominant) == true ||
                leadingMissing && body.lastOrNull()?.let(::isFreerLeadingSource) == true
        return missingCount * REQUIRED_EVENT_PAIR_SIZE - if (pendingEventCanComplete) 1 else 0
    }

    private fun isFreerLeadingSource(chord: SchoenbergSymbolicChord): Boolean =
        chord.degree == LEADING_TONE_DEGREE &&
            chord.arity == ChordArity.TRIAD &&
            chord.position == TextbookTriadPosition.FIRST_INVERSION

    private fun isPredominant(chord: SchoenbergSymbolicChord): Boolean =
        chord.degree in PREDOMINANT_DEGREES

    private fun isCadentialSixFour(chord: SchoenbergSymbolicChord): Boolean =
        chord.degree == TONIC_DEGREE &&
            chord.arity == ChordArity.TRIAD &&
            chord.position == TextbookTriadPosition.SECOND_INVERSION

    private fun isRootDominant(chord: SchoenbergSymbolicChord): Boolean =
        SchoenbergCadenceChapter.isAuthenticDominant(chord, minor)

    private fun isDominant(chord: SchoenbergSymbolicChord): Boolean =
        chord.degree == DOMINANT_DEGREE &&
            (!minor || chord.quality in setOf(ChordQuality.MAJOR, ChordQuality.DOMINANT7))

    private fun isRootTonic(chord: SchoenbergSymbolicChord): Boolean =
        chord.degree == TONIC_DEGREE &&
            chord.arity == ChordArity.TRIAD &&
            chord.position == TextbookTriadPosition.ROOT_POSITION

    private companion object {
        const val TONIC_DEGREE = 1
        const val DOMINANT_DEGREE = 5
        const val LEADING_TONE_DEGREE = 7
        val PREDOMINANT_DEGREES = setOf(2, 4, 6)
        val DECEPTIVE_DESTINATIONS = setOf(4, 6)
        val FREER_LEADING_DESTINATIONS = setOf(1, 2, 4, 6)
        const val REQUIRED_EVENT_PAIR_SIZE = 2
    }
}

object SchoenbergCadenceChapter {
    val AUTHENTIC_CADENCE_RULE_ID = RuleId("schoenberg.cadence.authentic")
    val DECEPTIVE_CADENCE_RULE_ID = RuleId("schoenberg.cadence.deceptive")
    val DECEPTIVE_OUTER_LEADING_TONE_RULE_ID =
        RuleId("schoenberg.cadence.deceptive.outer-leading-tone")
    val CADENTIAL_SIX_FOUR_RULE_ID = RuleId("schoenberg.cadence.six-four")

    internal fun authenticDominantSelector(minor: Boolean): TargetSelector =
        TargetSelector(
            degrees = setOf(DOMINANT_DEGREE),
            qualities = if (minor) MINOR_CADENTIAL_DOMINANT_QUALITIES else emptySet(),
            arities = setOf(ChordArity.TRIAD, ChordArity.SEVENTH),
            inversions = setOf(0),
        )

    internal fun dominantSelector(minor: Boolean): TargetSelector =
        authenticDominantSelector(minor).copy(inversions = emptySet())

    /**
     * Projects only structural inversion requirements into a concrete customary progression.
     * Inversions produced by enumeration as common examples are deliberately not included.
     */
    internal fun fixedInversionSlots(
        progression: List<SchoenbergSymbolicChord>,
        minor: Boolean,
        authenticEnding: Boolean,
    ): Set<Int> = buildSet {
        progression.forEachIndexed { index, chord ->
            if (
                chord.degree == TONIC_DEGREE &&
                chord.arity == ChordArity.TRIAD &&
                chord.position == TextbookTriadPosition.SECOND_INVERSION
            ) add(index)
        }
        if (authenticEnding && progression.size >= 2) {
            val dominantIndex = progression.lastIndex - 1
            val dominant = progression[dominantIndex]
            val tonic = progression.last()
            if (
                isAuthenticDominant(dominant, minor) &&
                tonic.degree == TONIC_DEGREE &&
                tonic.arity == ChordArity.TRIAD &&
                tonic.position == TextbookTriadPosition.ROOT_POSITION
            ) {
                add(progression.lastIndex)
            }
        }
    }

    /** Keeps the cadential dominant editable while attaching its inversion advice to the idiom. */
    internal fun advisoryDominantSlots(
        progression: List<SchoenbergSymbolicChord>,
        minor: Boolean,
        authenticEnding: Boolean,
    ): Set<Int> {
        if (!authenticEnding || progression.size < 2) return emptySet()
        val dominantIndex = progression.lastIndex - 1
        return setOf(dominantIndex).takeIf {
            isAuthenticDominant(progression[dominantIndex], minor)
        }.orEmpty()
    }

    internal fun isAuthenticDominant(
        chord: SchoenbergSymbolicChord,
        minor: Boolean,
    ): Boolean =
        chord.degree == DOMINANT_DEGREE &&
            (!minor || chord.quality in MINOR_CADENTIAL_DOMINANT_QUALITIES) &&
            when (chord.arity) {
                ChordArity.TRIAD -> chord.position == TextbookTriadPosition.ROOT_POSITION
                ChordArity.SEVENTH -> chord.seventhPosition == TextbookSeventhPosition.ROOT_POSITION
            }

    internal fun inheritedConstraints(
        length: Int,
        cadenceOptions: SchoenbergCadenceOptions,
    ): List<Constraint> {
        val suffixSize = if (cadenceOptions.includeCadentialSixFour) 4 else 3
        val bodyWindow = SlotWindow(0, length - suffixSize - 1)
        return SchoenbergRootMotionAndRepetitionChapter.constraints(length).map {
            if (
                it.ruleId == SchoenbergRootMotionAndRepetitionChapter.SIMILAR_CHORD_DISTANCE_RULE_ID ||
                it.ruleId == SchoenbergRootMotionAndRepetitionChapter.SIMILAR_PROGRESSION_RULE_ID
            ) {
                it.copy(scope = ConstraintScope(window = bodyWindow))
            } else {
                it
            }
        }
    }

    internal fun deceptiveOuterLeadingToneConstraints(
        progression: SchoenbergSymbolicProgression?,
    ): List<Constraint> =
        progression?.slots?.zipWithNext()?.mapIndexedNotNull { slot, (source, destination) ->
            val isDominant =
                source.degree == DOMINANT_DEGREE &&
                    source.quality in MINOR_CADENTIAL_DOMINANT_QUALITIES
            if (!isDominant || destination.degree !in DECEPTIVE_DESTINATIONS) {
                return@mapIndexedNotNull null
            }
            Constraint(
                expr = ConstraintExpr.Atom(
                    ConstraintPredicate.NeighborTone(
                        ChordToneNeighborRequirement(
                            window = SlotWindow(slot, slot + 1),
                            sourceSlot = slot,
                            sourceTone = ChordTone.THIRD,
                            direction = ChordToneNeighborDirection.NEXT,
                            candidateScaleDegrees = setOf(TONIC_DEGREE),
                            allowedDiatonicStepDeltas = setOf(1),
                            voiceFilter = ChordToneVoiceFilter.OUTER,
                            sourceSelector = TargetSelector(
                                degrees = setOf(DOMINANT_DEGREE),
                            ),
                            neighborSelector = TargetSelector(degrees = DECEPTIVE_DESTINATIONS),
                            ruleId = DECEPTIVE_OUTER_LEADING_TONE_RULE_ID,
                            explanation = ConstraintExplanation(
                                satisfied = "阻碍终止中，外声部导音上行到主音。",
                                violated = "阻碍终止若由外声部陈述导音 7，必须上行级进到主音 1。",
                            ),
                        )
                    )
                ),
                modality = ConstraintModality.Require,
                ruleId = DECEPTIVE_OUTER_LEADING_TONE_RULE_ID,
            )
        }?.flatMap { listOf(it, it.asSuccessAnnotation()) }.orEmpty()

    fun program(
        key: Key,
        continuationChordCount: Int,
        cadenceOptions: SchoenbergCadenceOptions = SchoenbergCadenceOptions(),
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
            dissonanceTreatment = SchoenbergDissonanceTreatment.CADENTIAL,
        )
        val policy = SchoenbergCadencePolicy(
            options = cadenceOptions,
            minor = key.mode == Mode.AEOLIAN,
        )
        return base.copy(
            constraints = base.constraints +
                inheritedConstraints(base.length, cadenceOptions) +
                deceptiveOuterLeadingToneConstraints(progression) +
                policy.constraints(base.length),
        )
    }

    /**
     * The open teaching program a free-practice idiom cut from this chapter is projected against.
     *
     * A cadence idiom usually shows only a fragment, so the program is widened to the chapter's own
     * curriculum length and the fragment is located inside it. Compiling the fragment as its own
     * progression would push the terminal cadence suffix onto the wrong slots.
     */
    fun freePracticeProgram(
        key: Key,
        continuationChordCount: Int,
        cadenceOptions: SchoenbergCadenceOptions = SchoenbergCadenceOptions(),
        searchConfig: SearchConfig = SearchConfig(maxResults = 4, beamWidth = 192),
    ): ConstraintProgram = program(
        key = key,
        continuationChordCount = maxOf(
            continuationChordCount,
            SchoenbergCommonToneExercises.minContinuationChordCount(
                SchoenbergCommonToneExercises.CADENCE_EXERCISE_ID,
            ),
            SchoenbergCadencePolicy(
                options = cadenceOptions,
                minor = key.mode == Mode.AEOLIAN,
            ).minimumProgramLength() - 1,
        ),
        cadenceOptions = cadenceOptions,
        progression = null,
        searchConfig = searchConfig,
    )

    fun enumerate(
        key: Key,
        continuationChordCount: Int,
        cadenceOptions: SchoenbergCadenceOptions = SchoenbergCadenceOptions(),
        chordSelectors: List<TargetSelector> = emptyList(),
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
                dissonanceTreatment = SchoenbergDissonanceTreatment.CADENTIAL,
                sequencePolicy = SchoenbergCadencePolicy(
                    options = cadenceOptions,
                    minor = key.mode == Mode.AEOLIAN,
                ),
            ),
        )

    private const val TONIC_DEGREE = 1
    private const val DOMINANT_DEGREE = 5
    private val DECEPTIVE_DESTINATIONS = setOf(4, 6)
    private val MINOR_CADENTIAL_DOMINANT_QUALITIES = setOf(
        ChordQuality.MAJOR,
        ChordQuality.DOMINANT7,
    )
}
