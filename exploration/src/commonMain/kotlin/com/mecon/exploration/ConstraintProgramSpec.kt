package com.mecon.exploration

import com.mecon.theory.constraint.ChordIdentityMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 约束程序的序列化 spec（docs/theory/constraint-program.md §2）——"用户想要什么"的统一表达。
 * 编译到 `:theory` 的 `ConstraintProgram`（[ConstraintProgramCompiler]），再由 `ConstraintProgramSolver`
 * 驱动通用四部求解。
 *
 * 覆盖核心约束子集：ChordAt / RuleAt / DoublingAt / SpacingAt / FifthAt / AllDifferent /
 * AdjacentCommonTone，三 / 七和弦 arity（同一程序可混排）。
 * LinePattern / MotiveAt / 谓词 / 目标 / 变长 length 🚧 后续增量再加字段（kotlinx 默认值向后兼容）。
 *
 * @param length 固定槽数。
 * @param domain 词汇表收窄（转位集合，覆盖 policy 默认）；null = 用 policy。
 */
@Serializable
data class ConstraintProgramSpec(
    val length: Int,
    val domain: DomainSpec? = null,
    val slotConstraints: List<SlotConstraintSpec> = emptyList(),
) {
    init {
        require(length >= 1) { "ConstraintProgramSpec length must be >= 1" }
    }
}

/** 词汇表收窄：允许的转位集合（[com.mecon.theory.textbook.TextbookTriadPosition] 名）。 */
@Serializable
data class DomainSpec(
    val positions: List<String> = emptyList(),
)

/** 槽位窗口（含端点）；[end] = null 表示开放端。theory.SlotWindow 的 spec 镜像。 */
@Serializable
data class SlotWindowSpec(
    val start: Int,
    val end: Int? = null,
)

/** requirement 三态（theory.RequirementMode 的 spec 镜像）。 */
@Serializable
enum class RequirementModeSpec {
    REQUIRE_INDICATION,
    REQUIRE_VIOLATION,
    FORBID,
}

/** 和弦音角色（theory.constraint.ChordTone 的 spec 镜像）。 */
@Serializable
enum class ChordToneSpec {
    ROOT,
    THIRD,
    FIFTH,
    SEVENTH,
    BASS,
}

/**
 * 槽位和弦规模 / 规则路由（theory.ChordArity 的 spec 镜像）。决定该槽落成哪类 `ChordTarget`
 * 与走三章 / 七和弦章规则：TRIAD → [TextbookTriadPosition]；SEVENTH → 七和弦位置
 * （[com.mecon.theory.textbook.TextbookSeventhPosition]，含第三转位）。
 */
@Serializable
enum class ChordAritySpec {
    TRIAD,
    SEVENTH,
}

/** 七和弦五音完整性（theory.textbook.SeventhFifthConstraint 的 spec 镜像）。 */
@Serializable
enum class FifthConstraintSpec {
    REQUIRE_FIFTH,
    OMIT_FIFTH,
}

/** 单槽 / 窗口约束（constraint-program.md §2 约束分类）。polymorphic，按 SerialName 反序列化。 */
@Serializable
sealed interface SlotConstraintSpec

/** M6 通用约束强度；weight 仅 PREFER、bonus 仅 REWARD 使用。 */
@Serializable
enum class ConstraintModalitySpec {
    REQUIRE,
    PREFER,
    REWARD,
    ANNOTATE,
}

/** 可组合约束表达式；Atom 复用既有 spec 原语，避免两套序列化词汇。 */
@Serializable
sealed interface ConstraintExprSpec

@Serializable
@SerialName("atom")
data class ConstraintAtomExprSpec(val predicate: SlotConstraintSpec) : ConstraintExprSpec

@Serializable
@SerialName("and")
data class ConstraintAndExprSpec(val terms: List<ConstraintExprSpec>) : ConstraintExprSpec {
    init { require(terms.isNotEmpty()) { "ConstraintAndExprSpec terms must not be empty" } }
}

@Serializable
@SerialName("or")
data class ConstraintOrExprSpec(val branches: List<ConstraintBranchSpec>) : ConstraintExprSpec {
    init { require(branches.isNotEmpty()) { "ConstraintOrExprSpec branches must not be empty" } }
}

@Serializable
@SerialName("not")
data class ConstraintNotExprSpec(val term: ConstraintExprSpec) : ConstraintExprSpec

@Serializable
data class ConstraintBranchSpec(
    val expr: ConstraintExprSpec,
    val ruleId: String? = null,
    val message: String? = null,
    val scoreDelta: Double? = null,
)

/** 脚本可直接构造的通用 constraint-at；旧条目继续作为向后兼容的派生形式。 */
@Serializable
@SerialName("constraint-at")
data class ConstraintAtSpec(
    val expr: ConstraintExprSpec,
    val window: SlotWindowSpec? = null,
    val selector: TargetSelectorSpec = TargetSelectorSpec(),
    val modality: ConstraintModalitySpec = ConstraintModalitySpec.REQUIRE,
    val weight: Double? = null,
    val bonus: Double? = null,
    val ruleId: String? = null,
    val message: String? = null,
) : SlotConstraintSpec {
    init {
        require(modality != ConstraintModalitySpec.REWARD || bonus != null) {
            "ConstraintAtSpec REWARD requires bonus"
        }
    }
}

@Serializable
data class TargetSelectorSpec(
    val degrees: Set<Int> = emptySet(),
    val qualities: Set<String> = emptySet(),
    val inversions: Set<Int> = emptySet(),
    val arities: Set<ChordAritySpec> = emptySet(),
    val requiredPitchClasses: Set<Int> = emptySet(),
    val identityKeys: Set<String> = emptySet(),
    val sonorityIdentityKeys: Set<String> = emptySet(),
    val interpretationIdentityKeys: Set<String> = emptySet(),
) {
    init {
        require(requiredPitchClasses.all { it in 0..11 }) {
            "requiredPitchClasses must use pitch-class values 0..11"
        }
    }
}

/**
 * 槽位和弦收窄；全 null 字段 = 不限。degrees/qualities/positions 取交集式收窄。
 *
 * @param arity 规则路由 / 和弦规模（默认 TRIAD，保后兼容）。SEVENTH 时 positions 按七和弦转位名解析。
 * @param triadSonority 仅 SEVENTH arity 生效：该槽以三和弦发声但仍走七和弦章规则（七和弦进行里的三和弦
 *   解决和弦，如 V7-I 的 I / 终止四六 I⁶₄）。镜像场景的 (chordArity=SEVENTH, slot.arity=TRIAD)。
 */
@Serializable
@SerialName("chord-at")
data class ChordAtSpec(
    val slot: Int,
    val degrees: Set<Int>? = null,
    val qualities: Set<String>? = null,
    val positions: Set<String>? = null,
    val arity: ChordAritySpec = ChordAritySpec.TRIAD,
    val triadSonority: Boolean = false,
) : SlotConstraintSpec

/** 在窗口内要求 / 禁止 / 演示某规则。编译为带窗口的 theory.RuleRequirement（生成期投影）。 */
@Serializable
@SerialName("rule-at")
data class RuleAtSpec(
    val window: SlotWindowSpec,
    val ruleId: String,
    val mode: RequirementModeSpec,
) : SlotConstraintSpec

/** 要求某槽重复某和弦音。 */
@Serializable
@SerialName("doubling-at")
data class DoublingAtSpec(
    val slot: Int,
    val tone: ChordToneSpec,
    val required: Boolean = false,
    val selector: TargetSelectorSpec = TargetSelectorSpec(),
) : SlotConstraintSpec

/** 禁止重复某和弦音；公开勋伯格六和弦/导和弦练习原先 runtime-only 的约束。 */
@Serializable
@SerialName("avoid-doubling-at")
data class AvoidDoublingAtSpec(
    val slot: Int,
    val tone: ChordToneSpec,
    val required: Boolean = false,
    val selector: TargetSelectorSpec = TargetSelectorSpec(),
) : SlotConstraintSpec

/** 禁止重复某调内音级，alteration 为半音偏移；用于升导音和后续半音化章节。 */
@Serializable
@SerialName("avoid-scale-degree-doubling-at")
data class AvoidScaleDegreeDoublingAtSpec(
    val slot: Int,
    val degree: Int,
    val alteration: Int = 0,
    val required: Boolean = false,
    val selector: TargetSelectorSpec = TargetSelectorSpec(),
) : SlotConstraintSpec

/** 候选必须包含 / 省略指定和弦音。FifthAt 会编译成该通用原语。 */
@Serializable
@SerialName("tone-completeness-at")
data class ToneCompletenessAtSpec(
    val window: SlotWindowSpec,
    val requiredTones: Set<ChordToneSpec> = emptySet(),
    val omittedTones: Set<ChordToneSpec> = emptySet(),
    val selector: TargetSelectorSpec = TargetSelectorSpec(),
) : SlotConstraintSpec

/** 窗口内偏好开放 / 密集排列（软）。复用 [SpacingPreference]（ANY/CLOSE/OPEN）。 */
@Serializable
@SerialName("spacing-at")
data class SpacingAtSpec(
    val window: SlotWindowSpec,
    val preference: SpacingPreference,
) : SlotConstraintSpec

/** 七和弦槽五音完整性收窄（生成期与 checkScore 一致，见 writing-engine §3）。仅七和弦槽生效。 */
@Serializable
@SerialName("fifth-at")
data class FifthAtSpec(
    val slot: Int,
    val fifth: FifthConstraintSpec,
) : SlotConstraintSpec

/** 窗口内和弦不得重复。默认按和弦身份（degree/quality/arity）比较，适合根位练习与长进行枚举。 */
@Serializable
@SerialName("all-different")
data class AllDifferentSpec(
    val window: SlotWindowSpec = SlotWindowSpec(0, null),
    val identityMode: ChordIdentityMode = ChordIdentityMode.TARGET,
) : SlotConstraintSpec

/**
 * 相邻槽必须存在共同音；[holdInSameVoice] 为 true 时还要求某一声部原位保持该共同音。
 * 这类约束用于先决定符号级和弦序列，再约束排布连接。
 */
@Serializable
@SerialName("adjacent-common-tone")
data class AdjacentCommonToneSpec(
    val window: SlotWindowSpec = SlotWindowSpec(0, null),
    val holdInSameVoice: Boolean = true,
) : SlotConstraintSpec

@Serializable
enum class ChordToneNeighborDirectionSpec {
    PREVIOUS,
    NEXT,
}

@Serializable
enum class ChordToneVoiceFilterSpec {
    ANY,
    OUTER,
    INNER,
    SOPRANO,
    ALTO,
    TENOR,
    BASS,
}

/** 某和弦音所在声部需与前/后相邻槽形成指定音级关系。 */
@Serializable
@SerialName("chord-tone-neighbor")
data class ChordToneNeighborSpec(
    val window: SlotWindowSpec = SlotWindowSpec(0, null),
    val sourceSlot: Int? = null,
    val sourceTone: ChordToneSpec,
    val direction: ChordToneNeighborDirectionSpec,
    val candidateScaleDegrees: Set<Int>,
    val allowedDiatonicStepDeltas: Set<Int> = emptySet(),
    val voiceFilter: ChordToneVoiceFilterSpec = ChordToneVoiceFilterSpec.ANY,
    val required: Boolean = true,
    val candidateAlterations: Set<Int> = setOf(0),
    val sourceSelector: TargetSelectorSpec = TargetSelectorSpec(),
    val neighborSelector: TargetSelectorSpec = TargetSelectorSpec(),
) : SlotConstraintSpec

/** 数据化知识点/特征奖励，取代 solver 内硬编码 knowledge bonus。 */
@Serializable
@SerialName("target-feature-bonus")
data class TargetFeatureBonusSpec(
    val window: SlotWindowSpec = SlotWindowSpec(0, null),
    val selector: TargetSelectorSpec,
    val ruleId: String,
    val message: String,
    val bonus: Double,
) : SlotConstraintSpec
