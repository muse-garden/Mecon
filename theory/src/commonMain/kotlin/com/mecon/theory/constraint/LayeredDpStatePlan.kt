package com.mecon.theory.constraint

import com.mecon.theory.AdjacentVoiceUnisonRule
import com.mecon.theory.ChordArity
import com.mecon.theory.NaturalTriads
import com.mecon.theory.RuleId
import com.mecon.theory.textbook.FourPartTextbookRules

/** Minimal future state requested by one active rule after a completed layer. */
internal sealed interface LayeredDpStateNeed {
    data class RecentFrames(val count: Int) : LayeredDpStateNeed

    data class VoiceExtreme(
        val constraintIndex: Int,
        val voiceFilter: ChordToneVoiceFilter,
        val extreme: com.mecon.theory.constraint.VoiceExtreme,
    ) : LayeredDpStateNeed

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
)

internal data class LayeredDpLayerStatePlan(
    val afterSlot: Int,
    val bindings: Set<LayeredDpStateBinding>,
) {
    val recentFrameCount: Int = bindings.mapNotNull {
        (it.need as? LayeredDpStateNeed.RecentFrames)?.count
    }.maxOrNull() ?: 0

    val voiceExtremes: List<LayeredDpStateNeed.VoiceExtreme> = bindings.mapNotNull {
        it.need as? LayeredDpStateNeed.VoiceExtreme
    }.distinct()

    fun describe(): String = buildList {
        add("recentFrames=$recentFrameCount")
        if (voiceExtremes.isNotEmpty()) {
            add("extrema=${voiceExtremes.joinToString(",") { "c${it.constraintIndex}:${it.voiceFilter}:${it.extreme}" }}")
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
 * First incremental state compiler. Its intentionally narrow contract is a fixed progression of
 * natural triads. Every active rule must be registered here; unknown rules fail capability audit.
 */
internal object LayeredDpStatePlanner {
    fun collect(program: ConstraintProgram): LayeredDpStatePlan {
        val unsupported = mutableListOf<String>()
        val fixedTargets = program.slotDomains.mapIndexed { slot, domain ->
            domain.targets.singleOrNull() ?: run {
                unsupported += "slot $slot is not a fixed chord target"
                null
            }
        }
        /**
         * 自然三和弦审计是 [FreeHarmonyRuleProvider] 目标敏感规则的代理条件：
         * `ROOTLESS_DIMINISHED_*` 与 `DISSONANCE_RELEASE` 只对减七 / 张力音发射，没有状态声明，
         * 因此 FREE_* 下必须靠和弦类型把它们排除在外。SCHOENBERG_GENERAL 装的是
         * `FourPartTextbookWritingRuleProvider`，其规则只读音高、音程与音域，与和弦类型无关，
         * 不需要这条限制。
         */
        fun requireNaturalTriads() {
            fixedTargets.forEachIndexed { slot, target ->
                if (target == null) return@forEachIndexed
                val natural = target.arity == ChordArity.TRIAD &&
                    NaturalTriads.matchesPitchClasses(target.key, target.sonority.pitchClasses).any { triad ->
                        triad.degree == target.degree && triad.quality == target.quality
                    }
                if (!natural) unsupported += "slot $slot is not a natural triad"
            }
        }
        if (program.ruleModules?.isNotEmpty() != false) unsupported += "chord modules must be explicitly empty"
        if (program.includeDerivedTextbookConstraints) unsupported += "derived textbook constraints are not registered"
        if (program.ruleProfile.requirements.isNotEmpty()) unsupported += "RuleProfile requirements are not registered"

        val layerBindings = List(program.length) { linkedSetOf<LayeredDpStateBinding>() }
        val covered = linkedSetOf<RuleId>()
        val terminal = linkedSetOf<RuleId>()

        fun enabled(ruleId: RuleId): Boolean = program.ruleProfile.configFor(ruleId).enabled
        fun cover(ruleId: RuleId) {
            if (enabled(ruleId)) covered += ruleId
        }
        fun retainForFuture(ruleId: RuleId, frames: Int) {
            if (!enabled(ruleId)) return
            covered += ruleId
            (0 until (program.length - 1).coerceAtLeast(0)).forEach { layer ->
                layerBindings[layer] += LayeredDpStateBinding(
                    ruleId,
                    LayeredDpStateNeed.RecentFrames(minOf(frames, layer + 1)),
                )
            }
        }
        fun terminalRerank(ruleId: RuleId) {
            if (!enabled(ruleId)) return
            covered += ruleId
            terminal += ruleId
        }
        fun declare(declarations: List<LayeredDpRuleStateDeclaration>) {
            declarations.forEach { declaration ->
                if (declaration.recentFrames == 0) cover(declaration.ruleId)
                else retainForFuture(declaration.ruleId, declaration.recentFrames)
            }
        }

        when (program.writingRulePreset) {
            WritingRulePreset.FREE_CLASSICAL,
            WritingRulePreset.FREE_JAZZ,
            -> {
                requireNaturalTriads()
                // Vertical-only rules contribute cost now and no future state.
                cover(AdjacentVoiceUnisonRule.RULE_ID)
                retainForFuture(MOTION_COST_RULE_ID, 1)
                declare(
                    FreeHarmonyRuleProvider.naturalTriadDpStateDeclarations(
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
            // 合成式（And/Or/Not）按原子逐个表态，整条约束取最强状态需求：求值 Not(p) 需要的帧与 p 相同，
            // And/Or 需要的是各支需求的并集。任一原子被拒 → 整条约束被拒。单 Atom 是一个原子的特例。
            val atoms = constraint.expr.atomicPredicates().toList()
            atoms.forEach { predicate ->
                when (predicate) {
                    is ConstraintPredicate.ToneCompleteness,
                    is ConstraintPredicate.ToneDoubled,
                    is ConstraintPredicate.ToneNotDoubled,
                    is ConstraintPredicate.ScaleDegreeNotDoubled,
                    is ConstraintPredicate.Spacing,
                    is ConstraintPredicate.ToneMultiplicity,
                    is ConstraintPredicate.ToneInVoiceFilter,
                    is ConstraintPredicate.DistinctIdentities,
                    is ConstraintPredicate.TargetMatches,
                    is ConstraintPredicate.SameSonority,
                    is ConstraintPredicate.RootDiatonicMotion,
                    is ConstraintPredicate.MinimumSimilarChordDistance,
                    ConstraintPredicate.DistinctSimilarChordProgressions,
                    is ConstraintPredicate.RootProgressionPreference,
                    -> cover(ruleId)

                    is ConstraintPredicate.CommonToneWithPrevious,
                    is ConstraintPredicate.NeighborTone,
                    -> retainForFuture(ruleId, 1)

                    is ConstraintPredicate.VoiceDiatonicSteps -> {
                        if (predicate.slots.zipWithNext().any { (before, after) -> after != before + 1 }) {
                            unsupported += "constraint[$index] uses non-adjacent VoiceDiatonicSteps"
                        } else {
                            retainForFuture(ruleId, 1)
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
