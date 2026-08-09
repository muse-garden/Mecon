package com.mecon.exploration

import com.mecon.theory.ChordArity
import com.mecon.api.primitive.PitchClass
import com.mecon.theory.ChordQuality
import com.mecon.theory.NaturalTriads
import com.mecon.theory.RequirementMode
import com.mecon.theory.RuleCatalog
import com.mecon.theory.RuleId
import com.mecon.theory.RuleProfile
import com.mecon.theory.RuleRequirement
import com.mecon.theory.SceneMatcher
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.constraint.Constraint
import com.mecon.theory.constraint.ConstraintBranch
import com.mecon.theory.constraint.ConstraintExplanation
import com.mecon.theory.constraint.ConstraintExpr
import com.mecon.theory.constraint.ConstraintModality
import com.mecon.theory.constraint.ConstraintPredicate
import com.mecon.theory.constraint.ConstraintScope
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.AdjacentCommonToneRequirement
import com.mecon.theory.constraint.AllDifferentRequirement
import com.mecon.theory.constraint.AvoidDoublingRequirement
import com.mecon.theory.constraint.AvoidScaleDegreeDoublingRequirement
import com.mecon.theory.constraint.ChordTarget
import com.mecon.theory.constraint.ChordToneNeighborDirection
import com.mecon.theory.constraint.ChordToneNeighborRequirement
import com.mecon.theory.constraint.ChordToneVoiceFilter
import com.mecon.theory.constraint.DoublingRequirement
import com.mecon.theory.constraint.SlotDomain
import com.mecon.theory.constraint.SpacingRequirement
import com.mecon.theory.constraint.TargetFeatureBonusRequirement
import com.mecon.theory.constraint.TargetSelector
import com.mecon.theory.constraint.ToneCompletenessRequirement
import com.mecon.theory.constraint.SpacingPreference as TheorySpacingPreference
import com.mecon.theory.textbook.DominantSeventhRules
import com.mecon.theory.textbook.SeventhFifthConstraint
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookSeventhTarget
import com.mecon.theory.textbook.TextbookTriadPosition
import com.mecon.theory.textbook.TextbookTriadTarget
import com.mecon.theory.textbook.TextbookTriadConstraintPreset
import com.mecon.theory.textbook.TextbookTriadConstraintRequirements
import com.mecon.theory.textbook.TextbookTriadWritingSlot
import com.mecon.theory.textbook.requirementsFor
import com.mecon.theory.textbook.textbookTriadInKey

/**
 * `ConstraintProgramSpec`（:exploration 序列化层）→ `ConstraintProgram`（:theory 运行时）的编译器
 * （docs/theory/constraint-program.md §4）。另提供 [fromConvenience]：把便捷请求映射为等价 spec，
 * 供编译等价金标准与后续统一。本增量 TRIAD arity。
 */
object ConstraintProgramCompiler {
    data class CompileResult(
        val program: ConstraintProgram?,
        val diagnostics: List<SolverDiagnostic> = emptyList(),
    )

    fun compile(
        spec: ConstraintProgramSpec,
        keySpec: KeySpec,
        policyId: String,
        search: SearchConfig,
    ): CompileResult {
        val key = keySpec.toKey()
        val diagnostics = mutableListOf<SolverDiagnostic>()

        val basePositions = spec.domain?.positions?.takeIf { it.isNotEmpty() }
            ?.let { parsePositions(it, diagnostics) }
            ?: Policies.get(policyId).positions
        if (diagnostics.isNotEmpty()) return CompileResult(null, diagnostics)

        // 槽位越界校验
        spec.slotConstraints.forEach { constraint ->
            val slot = when (constraint) {
                is ChordAtSpec -> constraint.slot
                is DoublingAtSpec -> constraint.slot
                is AvoidDoublingAtSpec -> constraint.slot
                is AvoidScaleDegreeDoublingAtSpec -> constraint.slot
                is FifthAtSpec -> constraint.slot
                is ChordToneNeighborSpec -> constraint.sourceSlot
                else -> null
            }
            if (slot != null && slot !in 0 until spec.length) {
                diagnostics += Diagnostics.constraintInvalid("约束槽位 $slot 超出 0..${spec.length - 1}。")
            }
            val window = when (constraint) {
                is RuleAtSpec -> constraint.window
                is SpacingAtSpec -> constraint.window
                is AllDifferentSpec -> constraint.window
                is AdjacentCommonToneSpec -> constraint.window
                is ToneCompletenessAtSpec -> constraint.window
                is ChordToneNeighborSpec -> constraint.window
                is TargetFeatureBonusSpec -> constraint.window
                is ConstraintAtSpec -> constraint.window
                else -> null
            }
            if (window != null && !window.overlaps(spec.length)) {
                diagnostics += Diagnostics.constraintInvalid(
                    "约束窗口 ${window.start}..${window.end ?: "end"} 超出 0..${spec.length - 1}。",
                )
            }
        }
        spec.slotConstraints.filterIsInstance<ConstraintAtSpec>()
            .flatMap { it.expr.atomicSpecs() }
            .forEach { atom ->
                val slot = when (atom) {
                    is DoublingAtSpec -> atom.slot
                    is AvoidDoublingAtSpec -> atom.slot
                    is AvoidScaleDegreeDoublingAtSpec -> atom.slot
                    is FifthAtSpec -> atom.slot
                    is ChordToneNeighborSpec -> atom.sourceSlot
                    else -> null
                }
                if (slot != null && slot !in 0 until spec.length) {
                    diagnostics += Diagnostics.constraintInvalid("组合约束槽位 $slot 超出 0..${spec.length - 1}。")
                }
                val window = when (atom) {
                    is RuleAtSpec -> atom.window
                    is SpacingAtSpec -> atom.window
                    is AllDifferentSpec -> atom.window
                    is AdjacentCommonToneSpec -> atom.window
                    is ToneCompletenessAtSpec -> atom.window
                    is ChordToneNeighborSpec -> atom.window
                    is TargetFeatureBonusSpec -> atom.window
                    else -> null
                }
                if (window != null && !window.overlaps(spec.length)) {
                    diagnostics += Diagnostics.constraintInvalid(
                        "组合约束窗口 ${window.start}..${window.end ?: "end"} 超出 0..${spec.length - 1}。",
                    )
                }
            }
        if (diagnostics.isNotEmpty()) return CompileResult(null, diagnostics)

        val naturalTriads = NaturalTriads.inKey(key)
        val chordAtBySlot = spec.slotConstraints.filterIsInstance<ChordAtSpec>().groupBy { it.slot }
        val fifthBySlot = spec.slotConstraints.filterIsInstance<FifthAtSpec>()
            .associate { it.slot to it.fifth }

        // 逐槽按 arity 建 targets；targets 可能空（收窄无解），此时记诊断留待统一早返，
        // 不在此构造 SlotDomain（其 init 拒空）。
        val slotTargets: List<List<ChordTarget>> = (0 until spec.length).map { slot ->
            val chordAts = chordAtBySlot[slot].orEmpty()
            val arity = chordAts.map { it.arity }.distinct().let { arities ->
                when {
                    arities.size <= 1 -> arities.firstOrNull() ?: ChordAritySpec.TRIAD
                    else -> {
                        diagnostics += Diagnostics.constraintInvalid("第 ${slot + 1} 槽 arity 冲突。")
                        ChordAritySpec.TRIAD
                    }
                }
            }
            val degrees = chordAts.mapNotNull { it.degrees }.reduceOrNull { a, b -> a intersect b }
            val qualities = chordAts.mapNotNull { it.qualities }.reduceOrNull { a, b -> a intersect b }
                ?.let { parseQualities(it, diagnostics) }
            val triadSonority = chordAts.any { it.triadSonority }

            val targets: List<ChordTarget> = when (arity) {
                ChordAritySpec.TRIAD -> {
                    val positions = chordAts.mapNotNull { it.positions }.reduceOrNull { a, b -> a intersect b }
                        ?.let { parsePositions(it, diagnostics) }
                        ?: basePositions
                    naturalTriads
                        .filter { degrees == null || it.degree in degrees }
                        .filter { qualities == null || it.quality in qualities }
                        .flatMap { triad -> positions.map { pos -> TextbookTriadTarget(triad, pos) } }
                }
                ChordAritySpec.SEVENTH -> {
                    val positions = chordAts.mapNotNull { it.positions }.reduceOrNull { a, b -> a intersect b }
                        ?.let { parseSeventhPositions(it, diagnostics) }
                        ?: setOf(TextbookSeventhPosition.ROOT_POSITION)
                    if (degrees == null) {
                        diagnostics += Diagnostics.constraintInvalid("第 ${slot + 1} 槽七和弦需指定音级。")
                        emptyList()
                    } else {
                        degrees.flatMap { degree ->
                            val chord = if (triadSonority) {
                                DominantSeventhRules.triadInKey(key, degree)
                            } else {
                                DominantSeventhRules.seventhChordInKey(key, degree)
                            }
                            positions.map { pos -> TextbookSeventhTarget(chord, pos) }
                        }
                    }
                }
            }
            if (targets.isEmpty()) {
                diagnostics += Diagnostics.constraintInvalid("第 ${slot + 1} 槽约束收窄后无可用和弦。")
            }
            targets
        }
        if (diagnostics.isNotEmpty()) return CompileResult(null, diagnostics)
        val slotDomains = slotTargets.map { targets -> SlotDomain(targets = targets) }

        // RuleAt → 带窗口 requirement；REQUIRE_INDICATION 目标复用规则集校验（与便捷路径一致）。
        val ruleAts = spec.slotConstraints.filterIsInstance<RuleAtSpec>()
        val indicationRuleIds = ruleAts
            .filter { it.mode == RequirementModeSpec.REQUIRE_INDICATION }
            .map { RuleId(it.ruleId) }
        val validation = RuleCatalog.validateRuleSet(indicationRuleIds)
        if (!validation.isValid) {
            return CompileResult(
                null,
                validation.errors.map { Diagnostics.invalidSelection(it.ruleId.value, it.message) },
            )
        }
        val requirements = ruleAts.map { ruleAt ->
            RuleRequirement(
                ruleId = RuleId(ruleAt.ruleId),
                mode = ruleAt.mode.toTheory(),
                window = ruleAt.window.toTheory(),
            )
        }

        val doublings = spec.slotConstraints.filterIsInstance<DoublingAtSpec>()
            .map {
                DoublingRequirement(
                    slot = it.slot,
                    tone = it.tone.toTheory(),
                    required = it.required,
                    selector = it.selector.toTheory(diagnostics),
                )
            }
        val avoidDoublings = spec.slotConstraints.filterIsInstance<AvoidDoublingAtSpec>()
            .map {
                AvoidDoublingRequirement(
                    slot = it.slot,
                    tone = it.tone.toTheory(),
                    required = it.required,
                    selector = it.selector.toTheory(diagnostics),
                )
            }
        val avoidScaleDegreeDoublings = spec.slotConstraints.filterIsInstance<AvoidScaleDegreeDoublingAtSpec>()
            .map {
                AvoidScaleDegreeDoublingRequirement(
                    slot = it.slot,
                    degree = it.degree,
                    alteration = it.alteration,
                    required = it.required,
                    selector = it.selector.toTheory(diagnostics),
                )
            }
        val toneCompleteness = spec.slotConstraints.filterIsInstance<ToneCompletenessAtSpec>()
            .map {
                ToneCompletenessRequirement(
                    window = it.window.toTheory(),
                    requiredTones = it.requiredTones.map { tone -> tone.toTheory() }.toSet(),
                    omittedTones = it.omittedTones.map { tone -> tone.toTheory() }.toSet(),
                    selector = it.selector.toTheory(diagnostics),
                )
            } + fifthBySlot.map { (slot, fifth) ->
                fifth.toToneCompleteness(slot)
            } + slotTargets.flatMapIndexed { slot, targets ->
                if (targets.any { it.arity == ChordArity.SEVENTH && it.sonority.pitchClasses.size >= SEVENTH_CHORD_SIZE }) {
                    listOf(
                        ToneCompletenessRequirement(
                            window = SlotWindow(slot, slot),
                            requiredTones = setOf(ChordTone.ROOT, ChordTone.SEVENTH),
                            selector = TargetSelector(arities = setOf(ChordArity.SEVENTH)),
                        )
                    )
                } else {
                    emptyList()
                }
            }
        val spacings = spec.slotConstraints.filterIsInstance<SpacingAtSpec>()
            .map { SpacingRequirement(it.window.toTheory(), it.preference.toTheorySpacing()) }
        val allDifferent = spec.slotConstraints.filterIsInstance<AllDifferentSpec>()
            .map { AllDifferentRequirement(it.window.toTheory(), it.identityMode) }
        val adjacentCommonTones = spec.slotConstraints.filterIsInstance<AdjacentCommonToneSpec>()
            .map { AdjacentCommonToneRequirement(it.window.toTheory(), holdInSameVoice = it.holdInSameVoice) }
        val chordToneNeighbors = spec.slotConstraints.filterIsInstance<ChordToneNeighborSpec>()
            .map {
                ChordToneNeighborRequirement(
                    window = it.window.toTheory(),
                    sourceSlot = it.sourceSlot,
                    sourceTone = it.sourceTone.toTheory(),
                    direction = it.direction.toTheory(),
                    candidateScaleDegrees = it.candidateScaleDegrees,
                    allowedDiatonicStepDeltas = it.allowedDiatonicStepDeltas,
                    voiceFilter = it.voiceFilter.toTheory(),
                    required = it.required,
                    candidateAlterations = it.candidateAlterations,
                    sourceSelector = it.sourceSelector.toTheory(diagnostics),
                    neighborSelector = it.neighborSelector.toTheory(diagnostics),
                )
            }
        val targetFeatureBonuses = spec.slotConstraints.filterIsInstance<TargetFeatureBonusSpec>()
            .map {
                TargetFeatureBonusRequirement(
                    window = it.window.toTheory(),
                    selector = it.selector.toTheory(diagnostics),
                    ruleId = RuleId(it.ruleId),
                    message = it.message,
                    bonus = it.bonus,
                )
            }
        val algebraConstraints = spec.slotConstraints.filterIsInstance<ConstraintAtSpec>()
            .mapNotNull { it.toTheoryConstraint(diagnostics) }
        if (diagnostics.isNotEmpty()) return CompileResult(null, diagnostics)

        val program = ConstraintProgram.fromRequirements(
            key = key,
            slotDomains = slotDomains,
            configuration = com.mecon.theory.constraint.ConstraintRequirementConfiguration(
                ruleProfile = RuleProfile(id = "constraint-program", requirements = requirements),
                toneCompleteness = toneCompleteness,
                doublings = doublings,
                avoidDoublings = avoidDoublings,
                avoidScaleDegreeDoublings = avoidScaleDegreeDoublings,
                spacings = spacings,
                allDifferent = allDifferent,
                adjacentCommonTones = adjacentCommonTones,
                chordToneNeighbors = chordToneNeighbors,
                targetFeatureBonuses = targetFeatureBonuses,
                constraints = algebraConstraints,
                searchConfig = search,
            ),
        )
        return CompileResult(program)
    }

    /** 便捷请求 → 等价 spec。TRIAD 场景复用 [SceneMatcher.instantiate] 的逐槽转位，保证编译行为等价。 */
    fun fromConvenience(request: CellRequest): ConstraintProgramSpec =
        ConvenienceRequestSpecMapper.fromConvenience(request)

    private fun parsePositions(
        names: Collection<String>,
        diagnostics: MutableList<SolverDiagnostic>,
    ): Set<TextbookTriadPosition> =
        names.mapNotNull { name ->
            runCatching { TextbookTriadPosition.valueOf(name) }.getOrElse {
                diagnostics += Diagnostics.constraintInvalid("未知转位 $name。")
                null
            }
        }.toSet()

    private fun parseSeventhPositions(
        names: Collection<String>,
        diagnostics: MutableList<SolverDiagnostic>,
    ): Set<TextbookSeventhPosition> =
        names.mapNotNull { name ->
            runCatching { TextbookSeventhPosition.valueOf(name) }.getOrElse {
                diagnostics += Diagnostics.constraintInvalid("未知七和弦转位 $name。")
                null
            }
        }.toSet()

    private fun parseQualities(
        names: Collection<String>,
        diagnostics: MutableList<SolverDiagnostic>,
    ): Set<ChordQuality> =
        names.mapNotNull { name ->
            runCatching { ChordQuality.valueOf(name) }.getOrElse {
                diagnostics += Diagnostics.constraintInvalid("未知和弦性质 $name。")
                null
            }
        }.toSet()

    private const val SEVENTH_CHORD_SIZE = 4
}
