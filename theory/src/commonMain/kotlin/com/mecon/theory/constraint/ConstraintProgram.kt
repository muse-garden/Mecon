package com.mecon.theory.constraint

import com.mecon.api.primitive.PitchClass
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TrackId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeRange
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.theory.ConstraintSlot
import com.mecon.theory.MeterPlan
import com.mecon.theory.TonalPlan
import com.mecon.theory.defaultConstraintSlots
import com.mecon.theory.defaultTonalPlan
import com.mecon.theory.Key
import com.mecon.theory.RuleId
import com.mecon.theory.RuleProfile
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.VoiceRangeProfile
import com.mecon.theory.VoicePlan
import com.mecon.theory.FixedVoice
import com.mecon.theory.WritingTask
import com.mecon.theory.WritingTexture
import com.mecon.theory.WritingTimeline

data class ConstraintRequirementConfiguration(
    val ruleProfile: RuleProfile = RuleProfile("constraint-program"),
    val toneCompleteness: List<ToneCompletenessRequirement> = emptyList(),
    val doublings: List<DoublingRequirement> = emptyList(),
    val avoidDoublings: List<AvoidDoublingRequirement> = emptyList(),
    val avoidScaleDegreeDoublings: List<AvoidScaleDegreeDoublingRequirement> = emptyList(),
    val spacings: List<SpacingRequirement> = emptyList(),
    val allDifferent: List<AllDifferentRequirement> = emptyList(),
    val adjacentCommonTones: List<AdjacentCommonToneRequirement> = emptyList(),
    val chordToneNeighbors: List<ChordToneNeighborRequirement> = emptyList(),
    val targetFeatureBonuses: List<TargetFeatureBonusRequirement> = emptyList(),
    val constraints: List<Constraint> = emptyList(),
    val rangeProfile: VoiceRangeProfile = VoiceRangeProfile.humanFourPart(),
    val voicePlan: VoicePlan? = null,
    val pitchPins: List<VoicePitchPin> = emptyList(),
    val writingRulePreset: WritingRulePreset = WritingRulePreset.TEXTBOOK,
    val enforceNoCrossingDuringEnumeration: Boolean = true,
    val directionalWindows: List<DirectionalWindow> = emptyList(),
    val searchConfig: SearchConfig = SearchConfig(),
    val finalTonicMayOmitFifth: Boolean = true,
    val ruleModules: List<ChordRuleModule>? = null,
    val includeDerivedTextbookConstraints: Boolean = true,
)

/**
 * 约束程序的运行时形态（docs/theory/constraint-program.md）：`ConstraintProgramSpec`（:exploration 序列化层）
 * 编译到此，再由 [ConstraintProgramSolver] 驱动通用四部求解。核心约束（ChordAt / RuleAt / DoublingAt /
 * SpacingAt / AllDifferent / AdjacentCommonTone）覆盖三 / 七和弦 arity，同一程序可混排，
 * 规则由模块 selector 自发现；LinePattern 留后续。
 *
 * @param slotDomains 逐槽允许的和弦目标（[ChordTarget]，由 domain / ChordAt 收窄）。
 * @param constraints M6 统一约束代数；旧 spec 条目只在编译边界 desugar 到这里。
 * @param ruleModules null 使用默认注册表；章节兼容门面可注入同一接口的定制模块集合。
 */
data class ConstraintProgram(
    val key: Key,
    val slotDomains: List<SlotDomain>,
    /** Multi-region tonal context. [key] remains the compatibility/default key. */
    val tonalPlan: TonalPlan = defaultTonalPlan(key, slotDomains.size),
    /** Stable slot identities and real harmonic onsets/durations. */
    val slots: List<ConstraintSlot> = defaultConstraintSlots(slotDomains),
    val meterPlan: MeterPlan = MeterPlan.FOUR_FOUR,
    /** Notational key-signature changes; tonalPlan may change without adding an entry here. */
    val keySignatureChangesByMeasure: Map<Int, KeySignature> = emptyMap(),
    val texturePlan: HarmonicTexturePlan = HarmonicTexturePlan.allChordVoices(),
    val omissionPolicy: ChordOmissionPolicy = ChordOmissionPolicy(),
    val ruleProfile: RuleProfile = RuleProfile("constraint-program"),
    val constraints: List<Constraint> = emptyList(),
    val rangeProfile: VoiceRangeProfile = VoiceRangeProfile.humanFourPart(),
    /** null keeps the legacy SATB plan, with ranges projected from [rangeProfile]. */
    val voicePlan: VoicePlan? = null,
    val pitchPins: List<VoicePitchPin> = emptyList(),
    val writingRulePreset: WritingRulePreset = WritingRulePreset.TEXTBOOK,
    val enforceNoCrossingDuringEnumeration: Boolean = true,
    val directionalWindows: List<DirectionalWindow> = emptyList(),
    val searchConfig: SearchConfig = SearchConfig(),
    val finalTonicMayOmitFifth: Boolean = true,
    val ruleModules: List<ChordRuleModule>? = null,
    /**
     * Whether the solver should add the textbook chapter's derived named constraints.
     * Chapter adapters that deliberately teach a smaller rule set can turn this off and
     * express only their own principles through [constraints]. General four-part writing
     * checks remain active either way.
     */
    val includeDerivedTextbookConstraints: Boolean = true,
) {
    init {
        require(slotDomains.isNotEmpty()) { "A constraint program must have at least one slot" }
        require(slots.size == slotDomains.size) { "Constraint slots must align with slot domains" }
        require(slots.map { it.domain } == slotDomains) {
            "Constraint slot domains must equal the compatibility slotDomains projection"
        }
        require(slots.map { it.id }.toSet().size == slots.size) { "Constraint slot ids must be unique" }
        require(keySignatureChangesByMeasure.keys.all { it >= 1 }) {
            "Key-signature change measures must be positive"
        }
        require(slots.zipWithNext().all { (a, b) -> a.time.onset < b.time.onset }) {
            "Constraint slot onsets must be strictly ordered"
        }
        require(slotDomains.indices.all { tonalPlan.contextsAt(it).isNotEmpty() }) {
            "Every constraint slot must belong to at least one tonal context"
        }
        require(slotDomains.withIndex().all { (slot, domain) ->
            val activeContexts = tonalPlan.contextsAt(slot)
            val activeIds = activeContexts.mapTo(hashSetOf()) { it.id }
            domain.targets.all { target ->
                target.key == key ||
                    target.tonalContextIds().any { it in activeIds } ||
                    activeContexts.any { context ->
                        context.tonic.pitchClass == target.key.root &&
                            context.scale == com.mecon.theory.ScaleDefinition.fromMode(target.key.mode)
                    }
            }
        }) {
            "Every chord target must belong to the default key or an active tonal context"
        }
        require(texturePlan.participations.all { participation ->
            participation.window.overlaps(slotDomains.indices)
        }) {
            "Texture participation window out of range"
        }
        require(texturePlan.sustainedToneReleases.all { release ->
            release.slot in slotDomains.indices &&
                configuredVoiceIds().contains(release.voiceId) &&
                release.releaseOnset > slots[release.slot].time.onset
        }) {
            "Sustained-tone release must refer to a configured slot/voice and follow its onset"
        }
        require(pitchPins.all { it.slot in slotDomains.indices }) { "VoicePitchPin slot out of range" }
        require(directionalWindows.all { it.window.overlaps(slotDomains.indices) }) {
            "DirectionalWindow out of range"
        }
        val configuredVoices = (voicePlan ?: VoicePlan.standardFourPart(rangeProfile)).voices
        require(pitchPins.all { pin -> configuredVoices.any { it.id == pin.voiceId } }) {
            "VoicePitchPin refers to a voice outside the voice plan"
        }
        require(predicates<ConstraintPredicate.ToneCompleteness>().map { it.requirement }.all { it.window.overlaps(slotDomains.indices) }) {
            "ToneCompletenessRequirement window out of range"
        }
        require(predicates<ConstraintPredicate.ToneDoubled>().map { it.requirement }.all { it.slot in slotDomains.indices }) { "DoublingRequirement slot out of range" }
        require(predicates<ConstraintPredicate.ToneNotDoubled>().map { it.requirement }.all { it.slot in slotDomains.indices }) { "AvoidDoublingRequirement slot out of range" }
        require(predicates<ConstraintPredicate.ScaleDegreeNotDoubled>().map { it.requirement }.all { it.slot in slotDomains.indices }) {
            "AvoidScaleDegreeDoublingRequirement slot out of range"
        }
        require(predicates<ConstraintPredicate.DistinctIdentities>().map { it.requirement }.all { it.window.overlaps(slotDomains.indices) }) { "AllDifferent window out of range" }
        require(predicates<ConstraintPredicate.CommonToneWithPrevious>().map { it.requirement }.all { it.window.overlaps(slotDomains.indices) }) {
            "AdjacentCommonTone window out of range"
        }
        require(predicates<ConstraintPredicate.NeighborTone>().map { it.requirement }.distinct().all { it.window.overlaps(slotDomains.indices) }) {
            "ChordToneNeighborRequirement window out of range"
        }
        require(predicates<ConstraintPredicate.NeighborTone>().map { it.requirement }.distinct().all { it.sourceSlot == null || it.sourceSlot in slotDomains.indices }) {
            "ChordToneNeighborRequirement sourceSlot out of range"
        }
        require(predicates<ConstraintPredicate.TargetMatches>().map { it.requirement }.all { it.window.overlaps(slotDomains.indices) }) {
            "TargetFeatureBonusRequirement window out of range"
        }
    }

    val length: Int get() = slotDomains.size

    val resolvedVoicePlan: VoicePlan by lazy {
        voicePlan ?: VoicePlan.standardFourPart(rangeProfile)
    }

    private fun configuredVoiceIds(): Set<TrackId> =
        (voicePlan ?: VoicePlan.standardFourPart(rangeProfile)).voices.mapTo(hashSetOf()) { it.id }

    fun rangeFor(voice: FixedVoice) =
        resolvedVoicePlan.voices.firstOrNull { it.id == voice.id }?.range
            ?: rangeProfile.rangeFor(voice.role)

    /**
     * Slot durations come from real workspace geometry, so any grid-aligned value can appear
     * (a dotted quarter after one resize step, a tied 5/8 after two). Values that need ties fall
     * back to the longest single symbol that fits: solver frames only synthesize events for rule
     * evaluation, while notation writes the slot's own [Fraction].
     */
    private val durationsBySlot: List<Duration> by lazy {
        slots.map { slot ->
            Duration.fromFraction(slot.time.duration) ?: Duration.atMost(slot.time.duration)
        }
    }

    fun durationAt(slot: Int): Duration = durationsBySlot[slot]

    /** Rules explicitly requested as demonstrations must not be hard-pruned during generation. */
    val demonstratedViolationRuleIds: Set<RuleId> by lazy {
        constraints
            .filter { it.modality == ConstraintModality.Require }
            .flatMap { it.expr.atomicPredicates().toList() }
            .filterIsInstance<ConstraintPredicate.RuleFound>()
            .filter { it.kind == com.mecon.theory.RuleFindingKind.VIOLATION }
            .map { it.ruleId }
            .toSet()
    }

    // These projections sit on the candidate-generation hot path. ConstraintProgram is immutable, so
    // compute each once instead of re-walking the complete algebra for every target/frame pair.
    val toneCompleteness: List<ToneCompletenessRequirement> by lazy {
        predicates<ConstraintPredicate.ToneCompleteness>().map { it.requirement }
    }
    val doublings: List<DoublingRequirement> by lazy {
        predicates<ConstraintPredicate.ToneDoubled>().map { it.requirement }
    }
    val avoidDoublings: List<AvoidDoublingRequirement> by lazy {
        predicates<ConstraintPredicate.ToneNotDoubled>().map { it.requirement }
    }
    val avoidScaleDegreeDoublings: List<AvoidScaleDegreeDoublingRequirement> by lazy {
        predicates<ConstraintPredicate.ScaleDegreeNotDoubled>().map { it.requirement }
    }
    val spacings: List<SpacingRequirement> by lazy {
        predicates<ConstraintPredicate.Spacing>().map { it.requirement }
    }
    val allDifferent: List<AllDifferentRequirement> by lazy {
        predicates<ConstraintPredicate.DistinctIdentities>().map { it.requirement }
    }
    val adjacentCommonTones: List<AdjacentCommonToneRequirement> by lazy {
        predicates<ConstraintPredicate.CommonToneWithPrevious>().map { it.requirement }
    }
    val chordToneNeighbors: List<ChordToneNeighborRequirement> by lazy {
        requiredPredicates<ConstraintPredicate.NeighborTone>()
            .map { it.requirement }
            .distinct()
            .filter { it.required }
    }
    val targetFeatureBonuses: List<TargetFeatureBonusRequirement> by lazy {
        predicates<ConstraintPredicate.TargetMatches>().map { it.requirement }
    }

    private inline fun <reified T : ConstraintPredicate> predicates(): List<T> =
        constraints.asSequence()
            .filter { it.scope == ConstraintScope() }
            .mapNotNull { (it.expr as? ConstraintExpr.Atom)?.predicate }
            .filterIsInstance<T>()
            .toList()

    /**
     * Typed requirements used for early candidate pruning must come from hard constraints only.
     * A requirement embedded in Prefer/Annotate remains available to the algebra evaluator for
     * scoring and findings, but must never eliminate a target or voicing before evaluation.
     */
    private inline fun <reified T : ConstraintPredicate> requiredPredicates(): List<T> =
        constraints.asSequence()
            .filter { it.scope == ConstraintScope() && it.modality == ConstraintModality.Require }
            .mapNotNull { (it.expr as? ConstraintExpr.Atom)?.predicate }
            .filterIsInstance<T>()
            .toList()

    /**
     * 通用 [WritingTask]：timeline + 携带窗口 requirement 的 ruleProfile + searchConfig。targets 留空——
     * 候选生成走 [SlotDomain]（targetProvider），task.targets 不参与本纹理的候选枚举。
     */
    fun toWritingTask(): WritingTask {
        val timeSlots = slots.map { it.time.onset }
        return WritingTask(
            texture = if (
                writingRulePreset == WritingRulePreset.TEXTBOOK ||
                writingRulePreset == WritingRulePreset.SCHOENBERG_GENERAL
            ) {
                WritingTexture.FOUR_PART_FIXED_VOICE
            } else {
                WritingTexture.FREE_POLYPHONY
            },
            timeline = WritingTimeline(
                range = TimeRange(timeSlots.first(), timeSlots.last()),
                slots = timeSlots,
            ),
            ruleProfile = ruleProfile,
            searchConfig = searchConfig,
        )
    }

    companion object {
        /** 旧九类 requirement 与 RuleRequirement 在构造边界一次性降糖，运行时只保存 Constraint。 */
        fun fromRequirements(
            key: Key,
            slotDomains: List<SlotDomain>,
            configuration: ConstraintRequirementConfiguration = ConstraintRequirementConfiguration(),
        ): ConstraintProgram {
            val ruleRequirements = configuration.ruleProfile.requirements
            return ConstraintProgram(
                key = key,
                slotDomains = slotDomains,
                tonalPlan = defaultTonalPlan(key, slotDomains.size),
                slots = defaultConstraintSlots(slotDomains),
                meterPlan = MeterPlan.FOUR_FOUR,
                keySignatureChangesByMeasure = emptyMap(),
                texturePlan = HarmonicTexturePlan.allChordVoices(),
                omissionPolicy = ChordOmissionPolicy(),
                ruleProfile = configuration.ruleProfile.copy(requirements = emptyList()),
                constraints = desugarRequirements(
                    ruleRequirements = ruleRequirements,
                    toneCompleteness = configuration.toneCompleteness,
                    doublings = configuration.doublings,
                    avoidDoublings = configuration.avoidDoublings,
                    avoidScaleDegreeDoublings = configuration.avoidScaleDegreeDoublings,
                    spacings = configuration.spacings,
                    allDifferent = configuration.allDifferent,
                    adjacentCommonTones = configuration.adjacentCommonTones,
                    chordToneNeighbors = configuration.chordToneNeighbors,
                    targetFeatureBonuses = configuration.targetFeatureBonuses,
                ) + configuration.constraints,
                rangeProfile = configuration.rangeProfile,
                voicePlan = configuration.voicePlan,
                pitchPins = configuration.pitchPins,
                writingRulePreset = configuration.writingRulePreset,
                enforceNoCrossingDuringEnumeration = configuration.enforceNoCrossingDuringEnumeration,
                directionalWindows = configuration.directionalWindows,
                searchConfig = configuration.searchConfig,
                finalTonicMayOmitFifth = configuration.finalTonicMayOmitFifth,
                ruleModules = configuration.ruleModules,
                includeDerivedTextbookConstraints = configuration.includeDerivedTextbookConstraints,
            )
        }
    }
}

enum class WritingRulePreset {
    TEXTBOOK,
    /** General fixed-voice rules only; chapter-specific chord rules come from typed requirements. */
    SCHOENBERG_GENERAL,
    FREE_CLASSICAL,
    FREE_JAZZ,
    NONE,
}

data class VoicePitchPin(
    val slot: Int,
    val voiceId: TrackId,
    val pitch: Pitch,
)

data class DirectionalWindow(
    val window: SlotWindow,
    val strength: Double = 1.0,
) {
    init { require(strength >= 1.0) { "Directional strength must be at least 1" } }
}

/**
 * 单槽允许的和弦目标集合（三 / 七和弦或后续半音化目标）。
 */
data class SlotDomain(
    val targets: List<ChordTarget>,
) {
    init {
        require(targets.isNotEmpty()) { "A slot domain must allow at least one chord target" }
    }
}

/** 声部候选必须包含 / 不包含指定和弦音。用于三和弦完整性、七和弦根/七音在场与省五。 */
data class ToneCompletenessRequirement(
    val window: SlotWindow,
    val requiredTones: Set<ChordTone> = emptySet(),
    val omittedTones: Set<ChordTone> = emptySet(),
    val selector: TargetSelector = TargetSelector(),
    /** false = retain the candidate and report the omission as a soft finding. */
    val required: Boolean = true,
    val ruleId: RuleId? = null,
    val explanation: ConstraintExplanation? = null,
)

/** 和弦音角色（DoublingAt 的重复对象）。 */
enum class ChordTone {
    ROOT,
    THIRD,
    FIFTH,
    SEVENTH,
    BASS,
}

/** 要求某槽重复某和弦音（DoublingAt）。不满足 → SOFT finding。 */
data class DoublingRequirement(
    val slot: Int,
    val tone: ChordTone,
    val required: Boolean = false,
    val selector: TargetSelector = TargetSelector(),
    val ruleId: RuleId? = null,
    val explanation: ConstraintExplanation? = null,
)

/**
 * 禁止重复某和弦音。用于勋伯格六和弦练习中“避免重复三音”等优先级约束。
 * [selector] 为空表示任意目标；章节约束包用它限制音级、性质、转位或 arity。
 */
data class AvoidDoublingRequirement(
    val slot: Int,
    val tone: ChordTone,
    val required: Boolean = false,
    val selector: TargetSelector = TargetSelector(),
    val ruleId: RuleId? = null,
    val explanation: ConstraintExplanation? = null,
)

/** 禁止重复某调内音级，支持 alteration，为小调升导音与后续半音化词汇预留。 */
data class AvoidScaleDegreeDoublingRequirement(
    val slot: Int,
    val degree: Int,
    val alteration: Int = 0,
    val required: Boolean = false,
    val selector: TargetSelector = TargetSelector(),
    val ruleId: RuleId? = null,
    val explanation: ConstraintExplanation? = null,
) {
    init {
        require(degree in 1..7) { "AvoidScaleDegreeDoublingRequirement degree must be in 1..7" }
    }

    fun pitchClasses(key: Key) =
        setOf(key.scale.pitchClasses[(degree - 1).mod(key.scale.pitchClasses.size)].transpose(alteration))
}

/** 排列偏好（SpacingAt）。 */
enum class SpacingPreference {
    OPEN,
    CLOSE,
    ANY,
}

/** 在某窗口内偏好开放 / 密集排列（SpacingAt）。ANY = 不约束。 */
data class SpacingRequirement(
    val window: SlotWindow,
    val preference: SpacingPreference,
)

/** 窗口内和弦身份不得重复（AllDifferent）。 */
data class AllDifferentRequirement(
    val window: SlotWindow = SlotWindow(0, null),
    val identityMode: ChordIdentityMode = ChordIdentityMode.TARGET,
)

/** 相邻和弦必须有共同音，可进一步要求共同音在同一声部保持。 */
data class AdjacentCommonToneRequirement(
    val window: SlotWindow = SlotWindow(0, null),
    val holdInSameVoice: Boolean = true,
    val exemptIdentityKeys: Set<String> = emptySet(),
)

/**
 * 勋伯格导和弦（三和弦 VII）三槽关系：前一和弦预备其减五度中的五音，后一和弦解决到 III，
 * 且该五音下行级进到 III 的三音。
 */
enum class ChordToneNeighborDirection {
    PREVIOUS,
    NEXT,
}

enum class ChordToneVoiceFilter {
    ANY,
    OUTER,
    INNER,
    SOPRANO,
    ALTO,
    TENOR,
    BASS,
}

/**
 * Requires the voice carrying [sourceTone] in a source chord to relate to the previous/next chord by scale degree.
 *
 * [candidateScaleDegrees] are degrees in [tonalKey], or in the program key when [tonalKey] is null,
 * for the neighboring note in the same voice. If
 * [allowedDiatonicStepDeltas] is non-empty, the neighbor pitch must also satisfy
 * `neighbor.diatonicSteps - source.diatonicSteps in allowedDiatonicStepDeltas`.
 */
data class ChordToneNeighborRequirement(
    val window: SlotWindow = SlotWindow(0, null),
    val sourceSlot: Int? = null,
    val sourceTone: ChordTone,
    val direction: ChordToneNeighborDirection,
    val candidateScaleDegrees: Set<Int>,
    val allowedDiatonicStepDeltas: Set<Int> = emptySet(),
    val voiceFilter: ChordToneVoiceFilter = ChordToneVoiceFilter.ANY,
    val required: Boolean = true,
    val candidateAlterations: Set<Int> = setOf(0),
    val sourceSelector: TargetSelector = TargetSelector(),
    val neighborSelector: TargetSelector = TargetSelector(),
    val sourcePitchClasses: Set<PitchClass> = emptySet(),
    /** Region-specific tonal key for programs that span more than one key. */
    val tonalKey: Key? = null,
    val ruleId: RuleId? = null,
    val explanation: ConstraintExplanation? = null,
) {
    init {
        require(sourceSlot == null || window.contains(sourceSlot)) {
            "ChordToneNeighborRequirement sourceSlot must be inside window"
        }
        require(candidateScaleDegrees.isNotEmpty()) {
            "ChordToneNeighborRequirement candidateScaleDegrees must not be empty"
        }
        require(candidateScaleDegrees.all { it in 1..7 }) {
            "ChordToneNeighborRequirement candidateScaleDegrees must be in 1..7"
        }
    }
}

/**
 * 搜索排序用的目标特征奖励。数值为正，求解器内部转换为负分奖励。
 */
data class TargetFeatureBonusRequirement(
    val window: SlotWindow = SlotWindow(0, null),
    val selector: TargetSelector,
    val ruleId: RuleId,
    val message: String,
    val bonus: Double,
)

private fun SlotWindow.overlaps(indices: IntRange): Boolean =
    indices.any { contains(it) }
