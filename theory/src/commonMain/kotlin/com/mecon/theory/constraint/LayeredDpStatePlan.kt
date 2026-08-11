package com.mecon.theory.constraint

import com.mecon.theory.AdjacentVoiceUnisonRule
import com.mecon.theory.RuleId
import com.mecon.theory.textbook.FourPartTextbookRules

/** Minimal future state requested by one active rule after a completed layer. */
internal sealed interface LayeredDpStateNeed {
    data class RecentFrames(
        val count: Int,
        val spelledPitches: Boolean = false,
        val targetSemantics: Boolean = false,
    ) : LayeredDpStateNeed

    data class VoiceExtreme(
        val constraintIndex: Int,
        val voiceFilter: ChordToneVoiceFilter,
        val extreme: com.mecon.theory.constraint.VoiceExtreme,
    ) : LayeredDpStateNeed

    /** A target-history atom owns a compact, predicate-specific automaton in the DP key. */
    data class ConstraintHistory(
        val constraintIndex: Int,
        val predicateIndex: Int,
    ) : LayeredDpStateNeed

    /** Finite Kleene-state vector for the atoms inside one And/Or/Not expression. */
    data class CompositeTruth(val constraintIndex: Int) : LayeredDpStateNeed

    /** The rule is scored only on complete paths; bounded DP keeps extra labels for final reranking. */
    data object TerminalRerank : LayeredDpStateNeed
}

internal data class LayeredDpStateBinding(
    val ruleId: RuleId,
    val need: LayeredDpStateNeed,
)

/** Provider-owned declaration; future rule additions should be registered beside their RuleId. */
internal data class LayeredDpRuleStateDeclaration(
    val ruleId: RuleId,
    /** Frames that must remain after the current layer for this rule's next evaluation. */
    val recentFrames: Int = 0,
    val spelledPitches: Boolean = false,
    val targetSemantics: Boolean = false,
)

internal data class LayeredDpLayerStatePlan(
    val afterSlot: Int,
    val bindings: Set<LayeredDpStateBinding>,
) {
    val recentFrameCount: Int = bindings.mapNotNull {
        (it.need as? LayeredDpStateNeed.RecentFrames)?.count
    }.maxOrNull() ?: 0

    val recentFramesNeedSpelling: Boolean = bindings.any {
        (it.need as? LayeredDpStateNeed.RecentFrames)?.spelledPitches == true
    }

    val recentFramesNeedTarget: Boolean = bindings.any {
        (it.need as? LayeredDpStateNeed.RecentFrames)?.targetSemantics == true
    }

    val voiceExtremes: List<LayeredDpStateNeed.VoiceExtreme> = bindings.mapNotNull {
        it.need as? LayeredDpStateNeed.VoiceExtreme
    }.distinct()

    val constraintHistories: List<LayeredDpStateNeed.ConstraintHistory> = bindings.mapNotNull {
        it.need as? LayeredDpStateNeed.ConstraintHistory
    }.distinct()

    val compositeTruths: List<LayeredDpStateNeed.CompositeTruth> = bindings.mapNotNull {
        it.need as? LayeredDpStateNeed.CompositeTruth
    }.distinct()

    fun describe(): String = buildList {
        add(
            "recentFrames=$recentFrameCount" +
                (if (recentFramesNeedSpelling) ":spelled" else ":midi") +
                (if (recentFramesNeedTarget) ":target" else "")
        )
        if (voiceExtremes.isNotEmpty()) {
            add("extrema=${voiceExtremes.joinToString(",") { "c${it.constraintIndex}:${it.voiceFilter}:${it.extreme}" }}")
        }
        if (constraintHistories.isNotEmpty()) {
            add("constraintHistory=${constraintHistories.joinToString(",") { "c${it.constraintIndex}.a${it.predicateIndex}" }}")
        }
        if (compositeTruths.isNotEmpty()) {
            add("compositeTruth=${compositeTruths.joinToString(",") { "c${it.constraintIndex}" }}")
        }
        val owners = bindings.map { it.ruleId.value }.distinct().sorted()
        add("rules=${owners.joinToString(",")}")
    }.joinToString("; ")
}

internal data class LayeredDpStatePlan(
    val layers: List<LayeredDpLayerStatePlan>,
    val coveredRuleIds: Set<RuleId>,
    val terminalRerankRuleIds: Set<RuleId>,
    val unsupportedReasons: List<String>,
) {
    val supported: Boolean get() = unsupportedReasons.isEmpty()
    val requiresBoundedGlobalRerank: Boolean get() = terminalRerankRuleIds.isNotEmpty()

    fun describeLayers(): List<String> = layers.map { layer ->
        "layer ${layer.afterSlot}: ${layer.describe()}"
    }
}

/**
 * Incremental state compiler. Every active rule must be registered here; unknown rules fail the
 * capability audit. Open target domains are supported when every target-sensitive rule has an
 * explicit recent-frame or constraint-history state declaration.
 */
internal object LayeredDpStatePlanner {
    fun collect(program: ConstraintProgram): LayeredDpStatePlan {
        val unsupported = mutableListOf<String>()
        if (program.ruleModules?.isNotEmpty() != false) unsupported += "chord modules must be explicitly empty"
        if (program.includeDerivedTextbookConstraints) unsupported += "derived textbook constraints are not registered"
        if (program.ruleProfile.requirements.isNotEmpty()) unsupported += "RuleProfile requirements are not registered"
        if (program.ruleProfile.suppressions.isNotEmpty()) unsupported += "RuleProfile suppressions are not registered"

        val layerBindings = List(program.length) { linkedSetOf<LayeredDpStateBinding>() }
        val covered = linkedSetOf<RuleId>()
        val terminal = linkedSetOf<RuleId>()

        fun enabled(ruleId: RuleId): Boolean = program.ruleProfile.configFor(ruleId).enabled
        fun cover(ruleId: RuleId) {
            if (enabled(ruleId)) covered += ruleId
        }
        fun retainForFuture(
            ruleId: RuleId,
            frames: Int,
            spelledPitches: Boolean = false,
            targetSemantics: Boolean = false,
        ) {
            if (!enabled(ruleId)) return
            covered += ruleId
            (0 until (program.length - 1).coerceAtLeast(0)).forEach { layer ->
                layerBindings[layer] += LayeredDpStateBinding(
                    ruleId,
                    LayeredDpStateNeed.RecentFrames(
                        count = minOf(frames, layer + 1),
                        spelledPitches = spelledPitches,
                        targetSemantics = targetSemantics,
                    ),
                )
            }
        }
        fun terminalRerank(ruleId: RuleId) {
            if (!enabled(ruleId)) return
            covered += ruleId
            terminal += ruleId
        }
        fun retainConstraintHistory(ruleId: RuleId, constraintIndex: Int, predicateIndex: Int) {
            if (!enabled(ruleId)) return
            covered += ruleId
            (0 until (program.length - 1).coerceAtLeast(0)).forEach { layer ->
                layerBindings[layer] += LayeredDpStateBinding(
                    ruleId,
                    LayeredDpStateNeed.ConstraintHistory(constraintIndex, predicateIndex),
                )
            }
        }
        fun retainCompositeTruth(ruleId: RuleId, constraintIndex: Int) {
            if (!enabled(ruleId)) return
            covered += ruleId
            (0 until (program.length - 1).coerceAtLeast(0)).forEach { layer ->
                layerBindings[layer] += LayeredDpStateBinding(
                    ruleId,
                    LayeredDpStateNeed.CompositeTruth(constraintIndex),
                )
            }
        }
        fun declare(declarations: List<LayeredDpRuleStateDeclaration>) {
            declarations.forEach { declaration ->
                if (declaration.recentFrames == 0) cover(declaration.ruleId)
                else retainForFuture(
                    declaration.ruleId,
                    declaration.recentFrames,
                    declaration.spelledPitches,
                    declaration.targetSemantics,
                )
            }
        }

        when (program.writingRulePreset) {
            WritingRulePreset.FREE_CLASSICAL,
            WritingRulePreset.FREE_JAZZ,
            -> {
                // Vertical-only rules contribute cost now and no future state.
                cover(AdjacentVoiceUnisonRule.RULE_ID)
                retainForFuture(MOTION_COST_RULE_ID, 1)
                declare(
                    FreeHarmonyRuleProvider.dpStateDeclarations(
                        classical = program.writingRulePreset == WritingRulePreset.FREE_CLASSICAL,
                    ) + WindowFeasibilityRuleProvider.dpStateDeclarations() +
                        BaselineSimilarityRuleProvider.dpStateDeclarations()
                )
            }
            WritingRulePreset.SCHOENBERG_GENERAL -> {
                // 只装 FourPartTextbookWritingRuleProvider；和弦类型由 typed requirement 管，与写作规则无关。
                cover(AdjacentVoiceUnisonRule.RULE_ID)
                retainForFuture(MOTION_COST_RULE_ID, 1)
                declare(FourPartTextbookRules.dpStateDeclarations())
            }
            WritingRulePreset.NONE -> retainForFuture(MOTION_COST_RULE_ID, 1)
            else -> unsupported += "${program.writingRulePreset} provider has no DP state declaration"
        }

        program.constraints.forEachIndexed { index, constraint ->
            val ruleId = constraint.ruleId ?: defaultConstraintRuleId(constraint.expr)
            if (!enabled(ruleId)) return@forEachIndexed
            if (
                constraint.modality == ConstraintModality.Annotate ||
                constraint.modality == ConstraintModality.Remind
            ) {
                // 解释与提醒都不计分、不能否决，因此不影响任何搜索决策：无需为它们保留合并状态。
                // finding 仍由规则管线在最终路径上求值，与保留哪条 label 无关。
                covered += ruleId
                return@forEachIndexed
            }
            if (constraint.expr !is ConstraintExpr.Atom) {
                // The expression tree itself is immutable. Its future behavior is determined by
                // the finite truth/active state of each atom plus the atom-specific summaries
                // declared below; retaining the entire frame prefix would defeat DP merging.
                retainCompositeTruth(ruleId, index)
            }
            val atoms = constraint.expr.atomicPredicates().toList()
            atoms.forEachIndexed { predicateIndex, predicate ->
                when (predicate) {
                    is ConstraintPredicate.ToneCompleteness,
                    is ConstraintPredicate.ToneDoubled,
                    is ConstraintPredicate.ToneNotDoubled,
                    is ConstraintPredicate.ScaleDegreeNotDoubled,
                    is ConstraintPredicate.Spacing,
                    is ConstraintPredicate.ToneMultiplicity,
                    is ConstraintPredicate.ToneInVoiceFilter,
                    -> cover(ruleId)

                    is ConstraintPredicate.DistinctIdentities,
                    is ConstraintPredicate.TargetMatches,
                    is ConstraintPredicate.SameSonority,
                    is ConstraintPredicate.RootDiatonicMotion,
                    is ConstraintPredicate.MinimumSimilarChordDistance,
                    ConstraintPredicate.DistinctSimilarChordProgressions,
                    is ConstraintPredicate.RootProgressionPreference,
                    -> retainConstraintHistory(ruleId, index, predicateIndex)

                    is ConstraintPredicate.CommonToneWithPrevious,
                    -> retainForFuture(ruleId, 1, targetSemantics = true)

                    is ConstraintPredicate.NeighborTone,
                    -> retainForFuture(ruleId, 1, spelledPitches = true, targetSemantics = true)

                    is ConstraintPredicate.VoiceDiatonicSteps -> {
                        if (predicate.slots.zipWithNext().any { (before, after) -> after != before + 1 }) {
                            unsupported += "constraint[$index] uses non-adjacent VoiceDiatonicSteps"
                        } else {
                            retainForFuture(ruleId, 1, spelledPitches = true, targetSemantics = true)
                        }
                    }
                    is ConstraintPredicate.UniqueVoiceExtreme -> {
                        // 极值摘要的 key 槽以约束下标为身份（求解器还用它回查 constraint.scope），
                        // 同一条约束里放多个 UniqueVoiceExtreme 会静默合并成一个槽——fail closed。
                        if (atoms.size > 1) {
                            unsupported += "constraint[$index] combines UniqueVoiceExtreme with other atoms"
                        } else {
                            covered += ruleId
                            (0 until (program.length - 1).coerceAtLeast(0)).forEach { layer ->
                                layerBindings[layer] += LayeredDpStateBinding(
                                    ruleId,
                                    LayeredDpStateNeed.VoiceExtreme(
                                        index,
                                        predicate.voiceFilter,
                                        predicate.extreme,
                                    ),
                                )
                            }
                        }
                    }
                    is ConstraintPredicate.NoRepeatedVoicePattern -> terminalRerank(ruleId)
                    is ConstraintPredicate.VoicePitchClassCardinality ->
                        unsupported += "constraint[$index] uses VoicePitchClassCardinality"
                    is ConstraintPredicate.RuleFound ->
                        unsupported += "constraint[$index] depends on RuleFound(${predicate.ruleId.value})"
                }
            }
        }

        return LayeredDpStatePlan(
            layers = layerBindings.mapIndexed { slot, bindings -> LayeredDpLayerStatePlan(slot, bindings) },
            coveredRuleIds = covered,
            terminalRerankRuleIds = terminal,
            unsupportedReasons = unsupported.distinct(),
        )
    }

    private val MOTION_COST_RULE_ID = RuleId("solver.motion-cost")
}
