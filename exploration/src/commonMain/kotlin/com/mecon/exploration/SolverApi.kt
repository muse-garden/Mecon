package com.mecon.exploration

import com.mecon.api.storage.StorageScore
import com.mecon.theory.ChordArity
import com.mecon.theory.constraint.TargetSelector
import com.mecon.theory.Key
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookTriadPosition
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 求解器统一协议（docs/theory/solver-api.md）。把求解能力收敛为一套可序列化协议，
 * 同时服务表单 UI / 用户脚本 / LLM 三类消费者。实现见 [SolverEngine]。
 *
 * S1 落地：[describe] / [enumerate] / [solve]；[refine]（S2）与 [check]（待 writing-engine §7
 * 全谱入口）为已声明入口，本期返回结构化占位诊断。
 *
 * 接口本体保持同步签名，调用方负责调度（与 ExplorationRequestRunner 现状一致）。
 */
interface SolverApi {
    /** 能力发现：规则目录树 + 练习策略 + 约束类型 + 表单描述。 */
    fun describe(): CapabilityManifest

    /** 规则 → 适用进行（符号级）。 */
    fun enumerate(request: EnumerationRequest): EnumerationResult

    /** 约束程序 → top-K 候选。S1 承载便捷请求；ConstraintProgramSpec 🚧 S2。 */
    fun solve(request: SolveRequest): SolveResult

    /** 既有候选 + 新约束 → 优化。🚧 S2。 */
    fun refine(request: RefineRequest): SolveResult

    /** 乐谱 → 规则检查报告。🚧 待全谱入口。 */
    fun check(request: CheckRequest): CheckResult
}

// ---- 协议版本 --------------------------------------------------------------

/** 协议版本，随 spec 类型结构变化递增。 */
const val SOLVER_PROTOCOL_VERSION: Int = 5

/** theory 规则版本号，参与输出 fingerprint。规则目录结构变化时递增。 */
const val SOLVER_RULES_VERSION: Int = 3

// ---- enumerate -------------------------------------------------------------

/** 验证级 spec 镜像（theory.VerifyLevel）。CONFIRMED 🚧，本期等价 MAY。 */
@Serializable
enum class VerifyLevelSpec { MAY, CONFIRMED }

@Serializable
data class EnumerationRequest(
    val key: KeySpec = KeySpec(),
    val ruleIds: List<String>,
    val policyId: String = "second-inversion-triads",
    val windowLimit: Int = 3,
    val verify: VerifyLevelSpec = VerifyLevelSpec.MAY,
    val maxResults: Int? = null,
    val maxVisitedNodes: Int? = null,
    val selections: Map<String, List<String>> = emptyMap(),
    val chordFilters: List<SchoenbergChordFilterSpec> = emptyList(),
    val includeDeceptiveCadence: Boolean = false,
    val includeCadentialSixFour: Boolean = false,
) {
    init {
        require(maxResults == null || maxResults > 0) { "maxResults must be positive" }
        require(maxVisitedNodes == null || maxVisitedNodes > 0) { "maxVisitedNodes must be positive" }
    }
}

/**
 * 勋伯格综合练习的和弦性质筛选。非空字段在同一和弦上取交集；多个 filter 必须由
 * 不同槽位分别满足，因此既能表达“任意一个 II7”，也能表达“一个四六 + 一个 V7”。
 */
@Serializable
data class SchoenbergChordFilterSpec(
    val degree: Int? = null,
    val arity: String? = null,
    val inversion: Int? = null,
    val requiredPitchClasses: Set<Int> = emptySet(),
    val sonorityIdentityKeys: Set<String> = emptySet(),
    val interpretationIdentityKeys: Set<String> = emptySet(),
) {
    init {
        require(degree == null || degree in 1..7) { "degree must be in 1..7" }
        require(arity == null || arity == "TRIAD" || arity == "SEVENTH") {
            "arity must be TRIAD or SEVENTH"
        }
        require(inversion == null || inversion in 0..3) { "inversion must be in 0..3" }
        require(requiredPitchClasses.all { it in 0..11 })
        require(
            degree != null ||
                arity != null ||
                inversion != null ||
                requiredPitchClasses.isNotEmpty() ||
                sonorityIdentityKeys.isNotEmpty() ||
                interpretationIdentityKeys.isNotEmpty()
        ) {
            "at least one chord property must be specified"
        }
    }
}

internal fun SchoenbergChordFilterSpec.toTargetSelector(): TargetSelector =
    TargetSelector(
        degrees = degree?.let(::setOf).orEmpty(),
        arities = arity?.let { setOf(ChordArity.valueOf(it)) }.orEmpty(),
        inversions = inversion?.let(::setOf).orEmpty(),
        requiredPitchClasses = requiredPitchClasses.mapTo(linkedSetOf()) {
            com.mecon.api.primitive.PitchClass(it)
        },
        sonorityIdentityKeys = sonorityIdentityKeys,
        interpretationIdentityKeys = interpretationIdentityKeys,
    )

@Serializable
data class EnumerationResult(
    val progressions: List<SymbolicProgression>,
    val diagnostics: List<SolverDiagnostic> = emptyList(),
)

/** 一条符号级适用进行 + facet 绑定说明。theory.SymbolicMatch 的 spec 镜像。 */
@Serializable
data class SymbolicProgression(
    val slots: List<SymbolicChordSpecView>,
    val explanation: List<SceneBindingNoteView> = emptyList(),
    val verified: Boolean = false,
)

/** 一个槽的符号级选择（音级 + 性质 + 和弦规模 + 转位）。 */
@Serializable
data class SymbolicChordSpecView(
    val degree: Int,
    val quality: String,
    val position: String,
    val arity: String = "TRIAD",
    val rootAlteration: Int = 0,
    val appliedToDegree: Int? = null,
    val secondaryFamily: String? = null,
    val modalOrigins: List<String> = emptyList(),
    val rootlessDominantNinthChordId: String? = null,
    val rootlessDominantNinthUsageId: String? = null,
    val omittedRootDegree: Int? = null,
    val omittedRootAlteration: Int = 0,
)

@Serializable
data class SceneBindingNoteView(
    val facetIndex: Int,
    val slot: Int,
    val detail: String,
)

// ---- solve / refine --------------------------------------------------------

/**
 * 综合练习请求。二选一承载：便捷请求 [convenience]（[RuleExampleRequest] / [ProgressionRequest]，走现有 runner）
 * 或约束程序 [program]（S2，走 `ConstraintProgramCompiler` + `ConstraintProgramSolver`）。
 * program 路径用 [key] / [policyId] / [search]（词汇表与搜索预算）。
 */
@Serializable
data class SolveRequest(
    val convenience: CellRequest? = null,
    val program: ConstraintProgramSpec? = null,
    val key: KeySpec = KeySpec(),
    val policyId: String = "second-inversion-triads",
    val search: SearchSpec = SearchSpec(),
) {
    init {
        require((convenience == null) != (program == null)) {
            "SolveRequest 必须且只能承载 convenience / program 之一"
        }
    }
}

@Serializable
data class SolveResult(
    val output: CellOutput,
    val diagnostics: List<SolverDiagnostic> = emptyList(),
) {
    /** 供 refine 引用的求解状态指纹。 */
    val solveStateFingerprint: String get() = output.fingerprint
}

/** 检查后优化请求（🚧 S2：重解 + 锚定 + 相似度软目标）。 */
@Serializable
data class RefineRequest(
    val base: SolveRequest,
    val baselineCandidateIndex: Int = 0,
    val pinnedSlots: List<Int> = emptyList(),
    val similarityWeight: Double = 1.0,
)

// ---- check -----------------------------------------------------------------

@Serializable
data class CheckRequest(
    val score: StorageScore,
    val profileId: String? = null,
)

@Serializable
data class CheckResult(
    val findings: List<StoredFinding> = emptyList(),
    val diagnostics: List<SolverDiagnostic> = emptyList(),
)

// ---- describe / manifest ---------------------------------------------------

@Serializable
data class CapabilityManifest(
    val protocolVersion: Int,
    val rulesVersion: Int,
    val chapters: List<ChapterInfo>,
    val policies: List<PolicyInfo>,
    val constraintKinds: List<ConstraintKindInfo> = emptyList(),
    val forms: List<FormSpec> = emptyList(),
)

@Serializable
data class ChapterInfo(
    val id: String,
    val titleKey: String,
    val rules: List<RuleNodeInfo>,
)

@Serializable
data class RuleNodeInfo(
    val id: String,
    val titleKey: String,
    val kind: String,
    val parentId: String? = null,
    val selectable: Boolean,
    val demonstrableAsViolation: Boolean = false,
    val scenes: List<SceneSummary> = emptyList(),
)

/** 规则场景摘要（rule-scenes.md）：适用窗口 + 涉及 facet 类型 + 不适用说明。 */
@Serializable
data class SceneSummary(
    val role: String,
    val windowMin: Int,
    val windowMax: Int,
    val facetTypes: List<String> = emptyList(),
    val unavailableReason: String? = null,
)

/** 练习策略（词汇表）：允许的三/七和弦转位集合与最小槽数。 */
@Serializable
data class PolicyInfo(
    val id: String,
    val labelKey: String,
    val positions: List<String>,
    val seventhPositions: List<String> = emptyList(),
    val minSlots: Int,
)

/** solve 支持的约束类型及参数 schema。S1 为空占位，S2 填充。 */
@Serializable
data class ConstraintKindInfo(
    val id: String,
    val paramsSchema: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class FormSpec(
    val requestType: String,
    val fields: List<FormField>,
)

@Serializable
data class FormField(
    val id: String,
    val kind: FormFieldKind,
    val labelKey: String,
    val constraints: JsonObject = JsonObject(emptyMap()),
    val visibleWhen: FieldCondition? = null,
)

@Serializable
enum class FormFieldKind {
    KEY_PICKER,
    RULE_TREE,
    DEGREE_PAIR,
    SLOT_LIST,
    PROGRESSION_PICKER,
    SELECT,
    TOGGLE,
    NUMBER,
    CHORD_FILTERS,
    PATTERN_EDITOR,
    SCRIPT_EDITOR,
}

/** 单字段等值联动（避免表单协议演变成第二个脚本语言）。 */
@Serializable
data class FieldCondition(
    val fieldId: String,
    val equalsValue: String,
)

// ---- 诊断协议 --------------------------------------------------------------

/**
 * 结构化诊断（solver-api.md §5），替换 runner 中的裸中文字符串。
 * UI/LLM 用 [messageKey] + [messageArgs] 渲染；[DiagnosticMessages.resolve] 提供当前中文回退。
 */
@Serializable
data class SolverDiagnostic(
    val code: String,
    val messageKey: String,
    val messageArgs: List<String> = emptyList(),
    val ruleId: String? = null,
    val slotIndex: Int? = null,
)

/** 诊断工厂：集中构造已实现的诊断 code（solver-api.md §5）。 */
object Diagnostics {
    fun invalidSelection(ruleId: String, message: String): SolverDiagnostic =
        SolverDiagnostic(CODE_INVALID_SELECTION, "diagnostic.invalidSelection", listOf(message), ruleId)

    fun ruleNotApplicable(ruleId: String, message: String): SolverDiagnostic =
        SolverDiagnostic(CODE_RULE_NOT_APPLICABLE, "diagnostic.ruleNotApplicable", listOf(message), ruleId)

    val noSolution: SolverDiagnostic =
        SolverDiagnostic(CODE_NO_SOLUTION, "diagnostic.noSolution")

    val confirmedDegraded: SolverDiagnostic =
        SolverDiagnostic(CODE_CONFIRMED_DEGRADED, "diagnostic.confirmedDegraded")

    val refineNotAvailable: SolverDiagnostic =
        SolverDiagnostic(CODE_REFINE_NOT_AVAILABLE, "diagnostic.refineNotAvailable")

    val checkNotAvailable: SolverDiagnostic =
        SolverDiagnostic(CODE_CHECK_NOT_AVAILABLE, "diagnostic.checkNotAvailable")

    fun constraintInvalid(message: String): SolverDiagnostic =
        SolverDiagnostic(CODE_CONSTRAINT_INVALID, "diagnostic.constraintInvalid", listOf(message))

    /** 多样化搜索预算耗尽仍未凑满 top-K：返回较少结果而非近重复（diverse-search.md §9）。 */
    fun diversityExhausted(returned: Int, requested: Int): SolverDiagnostic =
        SolverDiagnostic(
            CODE_DIVERSITY_EXHAUSTED,
            "diagnostic.diversityExhausted",
            listOf(returned.toString(), requested.toString()),
        )

    const val CODE_INVALID_SELECTION = "invalid-selection"
    const val CODE_RULE_NOT_APPLICABLE = "rule-not-applicable"
    const val CODE_NO_SOLUTION = "no-solution"
    const val CODE_DIVERSITY_EXHAUSTED = "diversity-exhausted"
    const val CODE_CONFIRMED_DEGRADED = "confirmed-degraded"
    const val CODE_REFINE_NOT_AVAILABLE = "refine-not-available"
    const val CODE_CHECK_NOT_AVAILABLE = "check-not-available"
    const val CODE_CONSTRAINT_INVALID = "constraint-invalid"
}

/**
 * 诊断消息本地化（暂）。无独立 message catalog，先集中在此把 messageKey/args 映射回中文，
 * 保证现有 UI 文案不变；后续接入 i18n 时替换本对象。
 */
object DiagnosticMessages {
    fun resolve(diagnostic: SolverDiagnostic): String =
        when (diagnostic.code) {
            Diagnostics.CODE_INVALID_SELECTION,
            Diagnostics.CODE_RULE_NOT_APPLICABLE,
            -> diagnostic.messageArgs.firstOrNull() ?: diagnostic.messageKey
            Diagnostics.CODE_NO_SOLUTION -> "约束组合无解，请减少所选规则或调整和弦进行。"
            Diagnostics.CODE_DIVERSITY_EXHAUSTED -> {
                val returned = diagnostic.messageArgs.getOrNull(0) ?: "?"
                val requested = diagnostic.messageArgs.getOrNull(1) ?: "?"
                "多样化空间不足：仅找到 $returned/$requested 个足够不同的候选（已避免返回近重复）。"
            }
            Diagnostics.CODE_CONFIRMED_DEGRADED -> "CONFIRMED 验证级尚未实现，已按 MAY（符号级）返回。"
            Diagnostics.CODE_REFINE_NOT_AVAILABLE -> "refine 优化尚未实现（S2）。"
            Diagnostics.CODE_CHECK_NOT_AVAILABLE -> "全谱规则检查尚未接入。"
            Diagnostics.CODE_CONSTRAINT_INVALID -> diagnostic.messageArgs.firstOrNull() ?: "约束程序不合法。"
            else -> diagnostic.messageArgs.firstOrNull() ?: diagnostic.messageKey
        }
}

// ---- 练习策略注册表 --------------------------------------------------------

/** 练习策略：id → 允许三/七和弦转位集合 + 最小槽数 + 标签键。manifest 与 enumerate 共用。 */
data class SolverPolicy(
    val id: String,
    val labelKey: String,
    val positions: Set<TextbookTriadPosition>,
    val seventhPositions: Set<TextbookSeventhPosition>,
    val minSlots: Int,
)

object Policies {
    private val root = setOf(TextbookTriadPosition.ROOT_POSITION)
    private val rootOrFirst = setOf(TextbookTriadPosition.ROOT_POSITION, TextbookTriadPosition.FIRST_INVERSION)
    private val first = setOf(TextbookTriadPosition.FIRST_INVERSION)
    private val allThree = setOf(
        TextbookTriadPosition.ROOT_POSITION,
        TextbookTriadPosition.FIRST_INVERSION,
        TextbookTriadPosition.SECOND_INVERSION,
    )
    private val seventhRoot = setOf(TextbookSeventhPosition.ROOT_POSITION)
    private val allFourSeventh = setOf(
        TextbookSeventhPosition.ROOT_POSITION,
        TextbookSeventhPosition.FIRST_INVERSION,
        TextbookSeventhPosition.SECOND_INVERSION,
        TextbookSeventhPosition.THIRD_INVERSION,
    )

    val all: List<SolverPolicy> = listOf(
        SolverPolicy("introductory-triads", "policy.introductoryTriads", root, seventhRoot, minSlots = 2),
        SolverPolicy("schoenberg-common-tone-root-position", "policy.schoenbergCommonToneRootPosition", root, seventhRoot, minSlots = 2),
        SolverPolicy("free-triads", "policy.freeTriads", rootOrFirst, allFourSeventh, minSlots = 1),
        SolverPolicy("first-inversion-triads", "policy.firstInversionTriads", first, allFourSeventh, minSlots = 1),
        SolverPolicy("second-inversion-triads", "policy.secondInversionTriads", allThree, allFourSeventh, minSlots = 3),
        SolverPolicy("seventh-chords", "policy.seventhChords", root, allFourSeventh, minSlots = 2),
    )

    private val byId: Map<String, SolverPolicy> = all.associateBy { it.id }

    /** 未知 policyId 回退到含全部转位的策略，保证 enumerate 仍能给出结果。 */
    fun get(id: String): SolverPolicy = byId[id] ?: all.last()
}

// ---- 内部工具 --------------------------------------------------------------

internal fun KeySpec.toKey(): Key =
    when (mode) {
        KeyModeSpec.MAJOR -> Key.fromKeySignatureFifths(fifths, KeySignatureMode.MAJOR)
        KeyModeSpec.MINOR -> Key.fromKeySignatureFifths(fifths, KeySignatureMode.MINOR)
    }
