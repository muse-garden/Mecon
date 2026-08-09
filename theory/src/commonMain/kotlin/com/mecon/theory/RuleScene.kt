package com.mecon.theory

import com.mecon.theory.textbook.SeventhFifthConstraint
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookTriadPosition

/**
 * 规则适用场景模型（docs/theory/rule-scenes.md）。
 *
 * 每条规则声明可查询的 [RuleScene]（析取）；一个场景由若干 [SceneFacet] 合取而成，
 * [SceneMatcher] 在和弦词汇表上按场景枚举适用的符号级进行，替代 runner 的章节硬编码。
 *
 * S0：仅实现 MAY（符号级必要条件）；CONFIRMED（跑小规模 voicing 求解确认）留接口（见 SceneMatcher.verify）。
 */
enum class SceneRole {
    /** 正例：该进行应命中规则的 INDICATION。 */
    EXAMPLE,

    /** 错误示例：该进行用于演示规则违反（demonstrableAsViolation）。 */
    DEMONSTRATION,
}

/** 音级变化：自然、升、降（相对当前调音阶）。 */
enum class DegreeAlteration {
    NATURAL,
    RAISED,
    LOWERED,
}

/** 声部作用域（VoiceMotion 的符号级判定范围）。 */
enum class VoiceScope {
    ANY,
    BASS,
    UPPER,
}

/** 旋律走向。 */
enum class MotionDirection {
    ASCENDING,
    DESCENDING,
}

/**
 * 和弦规模（词汇表维度，rule-scenes §2.1 "先加词汇表维度"）：三和弦（3 音、3 转位）或
 * 七和弦（4 音、4 转位）。场景默认三和弦；七和弦章节的场景声明 [RuleScene.chordArity] = SEVENTH，
 * 由 [SceneMatcher.instantiateSeventh] 落成七和弦写作槽。单槽可用 [SlotChordSpec.arity] 覆盖
 * （如七和弦进行中的三和弦解决和弦）。
 */
enum class ChordArity {
    TRIAD,
    SEVENTH,
}

/** 低音线条形态（BassMotion）。 */
enum class BassMotionKind {
    /** 经过：三槽低音同向级进（经过四六）。 */
    PASSING_STEP,

    /** 持续：低音音级保持不变（持续音四六）。 */
    PEDAL_HELD,
}

/** 音级 + 变化，如 #5（RAISED 的 5 级）。 */
data class ScaleDegreeSpec(
    val degree: Int,
    val alteration: DegreeAlteration = DegreeAlteration.NATURAL,
) {
    init {
        require(degree in 1..7) { "degree must be in 1..7" }
    }
}

/**
 * 单槽和弦约束。null 字段表示不限。
 *
 * @param sameChordAsSlot 槽间引用：与第 i 槽同和弦（pitch-class 集合相同）。只允许**后向引用**
 *   （指向更早的槽），保证 [SceneMatcher] 逐槽增量剪枝时该约束一放好即可判定。
 */
data class SlotChordSpec(
    val degrees: Set<Int>? = null,
    val qualities: Set<ChordQuality>? = null,
    val positions: Set<TextbookTriadPosition>? = null,
    /** 七和弦场景专用：该槽允许的七和弦转位（含第三转位），三和弦场景留 null。 */
    val seventhPositions: Set<TextbookSeventhPosition>? = null,
    /** 单槽的和弦规模覆盖；null 表示沿用场景 [RuleScene.chordArity]。七和弦进行中的解决和弦声明 TRIAD。 */
    val arity: ChordArity? = null,
    val sameChordAsSlot: Int? = null,
) {
    init {
        require(degrees == null || degrees.all { it in 1..7 }) { "degrees must be in 1..7" }
    }
}

/**
 * 场景 facet。全部满足才算命中。本期实现分支见下；rule-scenes §2.1 的
 * MetricPosition / PhrasePosition / NonChordTone / Texture / Register / KeyPlan 🚧 暂不建分支，
 * 待引入对应词汇表维度后再加，届时场景模型本身不动。
 */
sealed interface SceneFacet

/** 具体和弦/音级序列（含通配、槽间引用）。 */
data class ChordPattern(val slots: List<SlotChordSpec>) : SceneFacet {
    init {
        require(slots.isNotEmpty()) { "ChordPattern must have at least one slot" }
        slots.forEachIndexed { index, slot ->
            slot.sameChordAsSlot?.let {
                require(it in 0 until index) { "sameChordAsSlot must reference an earlier slot (no self/forward ref)" }
            }
        }
    }
}

/** 调性上下文：仅大调 / 仅小调。 */
data class KeyContext(val mode: RuleKeyModeConstraint) : SceneFacet

/**
 * 相邻槽根音的最小音级距离（0/1/2/3）。用于替代根位连接规则原先的 SelectionContext.degreeDistance 判定。
 */
data class RootMotion(
    val steps: Set<Int>,
    val fromSlot: Int = 0,
) : SceneFacet {
    init {
        require(steps.isNotEmpty()) { "RootMotion must list at least one step distance" }
        require(steps.all { it in 0..3 }) { "root motion step distance must be in 0..3" }
        require(fromSlot >= 0) { "fromSlot must be >= 0" }
    }
}

/**
 * 声部走向（符号级必要条件）：[from] 属于第 fromSlot 槽和弦音、[to] 属于第 fromSlot+1 槽和弦音，
 * 即标记"可能包含"该走向。是否真出现在声部进行中由 CONFIRMED 级确认（S0 不实现）。
 */
data class VoiceMotion(
    val from: ScaleDegreeSpec,
    val to: ScaleDegreeSpec,
    val voiceScope: VoiceScope = VoiceScope.ANY,
    val direction: MotionDirection? = null,
    val fromSlot: Int = 0,
) : SceneFacet {
    init {
        require(fromSlot >= 0) { "fromSlot must be >= 0" }
    }
}

/** 低音线条形态。 */
data class BassMotion(
    val kind: BassMotionKind,
    val fromSlot: Int = 0,
) : SceneFacet {
    init {
        require(fromSlot >= 0) { "fromSlot must be >= 0" }
    }
}

/**
 * 重复音/完整性要求（rule-scenes §2.1 DoublingExpectation）。本期表达七和弦某槽五音的完整性收窄
 * （完全 / 省五），供 [SceneMatcher.instantiateSeventh] 落成 [com.mecon.theory.textbook.TextbookSeventhWritingSlot]
 * 的 fifthConstraint——与 checkScore 的交替判定保持一致（writing-engine §3）。
 *
 * 五音完整性是 voicing 级属性，不是符号级必要条件，故对 [SceneMatcher.enumerate] 的 MAY 判定恒真
 * （只在 instantiate 阶段生效，rule-scenes §4）。
 */
data class DoublingExpectation(
    val slot: Int,
    val fifth: SeventhFifthConstraint,
) : SceneFacet {
    init {
        require(slot >= 0) { "slot must be >= 0" }
    }
}

/**
 * 一条规则的适用场景。
 *
 * @param window 进行槽数范围（终止四六 = 3..3；连接规则 = 2..2；单和弦竖直规则 = 1..1）。
 * @param unavailableReason 当该场景不适用于所选上下文时，提供给 UI 的说明（替代旧 applicability reason）。
 */
data class RuleScene(
    val ruleId: RuleId,
    val window: IntRange,
    val facets: List<SceneFacet>,
    val role: SceneRole = SceneRole.EXAMPLE,
    val unavailableReason: String? = null,
    val chordArity: ChordArity = ChordArity.TRIAD,
) {
    init {
        require(!window.isEmpty()) { "scene window must be non-empty" }
        require(window.first >= 1) { "scene window must start at >= 1" }
    }
}
