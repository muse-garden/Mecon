# 探索文档数据模型

> 归属模块：`:exploration`（KMP 模块，依赖 `:api` `:theory`）。
> 总览见 [README.md](README.md)。UI 侧见 [ui-interaction.md](ui-interaction.md)。
>
> 当前实现：`ExplorationModel.kt` 定义 `ExplorationDocument`、cell、`RuleExampleRequest`、
> `ProgressionRequest` 与输出快照；`ExplorationRequestRunner.kt` 已能执行原位三和弦层级 1/2
> 请求并合成 SATB `StorageScore`。notebook 文件容器读写尚未接入 `ScoreFileService`；自由练习
> 工作台已使用独立的 `exploration.free-practice` module schema 接入 `.mecon`，见
> [free-practice-multiplatform.md](free-practice-multiplatform.md) §4。

## 1. 文件格式

与 `.mecon` 共用 YAML 容器，顶层 `kind` 判别文档类型；现有乐谱文件无 `kind` 字段，
加载时默认视为 `score`，向后兼容：

```yaml
kind: exploration
version: 1
title: 原位三和弦连接
cells:
  - id: cell-001
    type: text
    text: "## 根音四（五）度关系\n共同音保持时……"
  - id: cell-002
    type: request
    request:
      type: rule-example
      key: { tonic: C, mode: MAJOR }
      from: { degree: 5 }
      to: { degree: 1 }
      selectedRules: ["textbook.root-position-triad.fourth-fifth-common-tone"]
      search: { maxResults: 4 }
    output:            # 可选；保存后重开无需重跑
      fingerprint: "…"
      candidates: [...]
```

`ScoreFileService.loadAuto()` 返回类型改为 sealed：

```kotlin
sealed interface LoadedDocument {
    data class Score(val storage: StorageScore) : LoadedDocument
    data class Exploration(val document: ExplorationDocument) : LoadedDocument
}
```

序列化注册沿用 `ScoreSerializer.install()` 的模式，探索文档的多态 cell / request
在 `:exploration` 内注册自己的 `SerializersModule`。

## 2. Cell 模型

```kotlin
@Serializable
data class ExplorationDocument(
    val version: Int = 1,
    val title: String,
    val cells: List<ExplorationCell>,
)

@Serializable
sealed interface ExplorationCell { val id: CellId }

@Serializable @SerialName("text")
data class TextCell(override val id: CellId, val text: String) : ExplorationCell

@Serializable @SerialName("score")
data class ScoreCell(               // 独立可编辑乐谱片段
    override val id: CellId,
    val score: StorageScore,
    val caption: String = "",
) : ExplorationCell

@Serializable @SerialName("request")
data class RequestCell(
    override val id: CellId,
    val request: CellRequest,
    val material: StorageScore? = null,   // 可编辑材料谱（配和声的旋律等）；层级 1/2 通常为 null
    val output: CellOutput? = null,       // 附着输出，不是独立 cell
) : ExplorationCell
```

设计约束（遵循根目录 AGENTS.md 存储层原则）：cell 内只含源字段与 ID 引用，不含对象引用；
全部 `@Serializable` 不可变 data class。`CellId` 为 `@JvmInline value class`。

## 3. 请求协议 CellRequest

`CellRequest` 是探索模式的输入协议，也是未来 LLM 输出的目标格式（受限、可校验、可序列化）。
theory 类型（`Key` / `NaturalTriad` / `SearchConfig`）不直接序列化，`:exploration` 定义
spec 层值对象并负责映射：

```kotlin
@Serializable sealed interface CellRequest

@Serializable @SerialName("rule-example")     // 层级 1
data class RuleExampleRequest(
    val key: KeySpec,                          // tonic + mode
    val from: DegreeSpec,                      // 连接的前一和弦（调内音级；小调含 scaleForm 消歧）
    val to: DegreeSpec,
    val selectedRules: List<String>,           // RuleCatalog 节点 id，需通过 validateSelection
    val demonstrate: DemonstrationSpec? = null, // 错误示例：要求违反某规则
    val search: SearchSpec = SearchSpec(),
) : CellRequest

@Serializable @SerialName("progression")      // 层级 2
data class ProgressionRequest(
    val key: KeySpec,
    val slots: List<ProgressionSlot>,          // degree + spacing 偏好
    val policyId: String = "introductory-triads",
    val search: SearchSpec = SearchSpec(),
) : CellRequest

@Serializable
data class ProgressionSlot(
    val degree: DegreeSpec,
    val spacing: SpacingPreference = SpacingPreference.ANY,  // OPEN / CLOSE / ANY
)

@Serializable
data class DemonstrationSpec(val ruleId: String)   // mode 目前只有 REQUIRE_VIOLATION，预留扩展

@Serializable @SerialName("multi-stage")      // 层级 3 🚧 初步
data class MultiStageRequest(
    val stages: List<StageSpec>,               // 每阶段 = 章节 policy + 目标
    val materials: List<MaterialRef>,          // 引用其他 cell（或其输出候选）作固定材料
) : CellRequest
```

校验分两层：反序列化后先做结构校验（音级范围、规则 id 存在），再调
`RuleCatalog.validateSelection(selectedRules, from, to)` 做组合校验（互斥组、
根音关系适用性），错误以人类可读诊断返回给 UI / LLM。

## 4. 输出与过期

```kotlin
@Serializable
data class CellOutput(
    val fingerprint: String,                   // 见下
    val candidates: List<OutputCandidate>,     // top-K，含至少 1 个
    val diagnostics: List<String> = emptyList(), // 无解 / 降级说明
)

@Serializable
data class OutputCandidate(
    val score: StorageScore,                   // 合成的大谱表 SATB 乐谱
    val totalScore: Double,
    val findings: List<StoredFinding>,         // 调解后的 finding 快照
    val breakdownEntries: List<StoredScoreEntry>, // ScoreBreakdown 规则贡献明细
)

@Serializable
data class StoredFinding(
    val ruleId: String,
    val severity: String,                      // HARD / SOFT / HINT / INDICATION
    val messageKey: String,                    // i18n key + 参数，不存成品文案
    val messageArgs: List<String> = emptyList(),
    val anchors: List<EventId>,                // 指向本候选 score 内的事件
    val relatedAnchors: List<EventId> = emptyList(),
    val isDemonstrationTarget: Boolean = false, // 错误示例中"被要求的违规"
)
```

要点：

- `RuleFinding`（theory 运行时类型）不直接序列化；`StoredFinding` 是其可持久化快照，
  锚点 `EventId` 与候选 `StorageScore` 内的事件一致，UI 高亮无需重算。
- **输出乐谱合成**：`ExplorationScoreAssembler` 把 `FixedVoiceWritingFrame` 序列
  转成 `StorageScore`（大谱表两行、SATB 固定声部、每 slot 一拍或一小节），
  **保留求解状态中已合成事件的 `EventId`**，使 finding 锚点直接可用。
- **fingerprint** = 稳定哈希（序列化后的 `request` + `material` 的 StorageScore +
  theory 规则版本号）。加载与每次编辑提交后重算并与 `output.fingerprint` 比对，
  不一致即过期（UI 表现见 [ui-interaction.md](ui-interaction.md) §3）。
  theory 规则版本号是 `:theory` 内一个手工递增常量：规则语义变化时递增，
  使旧文档的缓存输出正确显示为过期。

## 5. 请求编译与求解执行

第二转位规则示例会由 runner 扩成三槽上下文，例如终止四六从 I→V 输入生成
`I(46)-V-I`。`ProgressionRequest(policyId = "second-inversion-triads")` 会保底至少三个
slot；符号槽编译为 `ConstraintProgram`，由三和弦规则模块检查标准四六用法。

`ExplorationRequestRunner`（`:exploration`）把 `CellRequest` 编译为 `ConstraintProgramSpec`，
再由 `ConstraintProgramCompiler` 生成运行时程序：

```
RuleExampleRequest  → ConstraintProgramSpec(ChordAt + RuleAt)
ProgressionRequest  → ConstraintProgramSpec(ChordAt + SpacingAt)
                    → ConstraintProgram → ConstraintProgramSolver
```

- **规则选择 → RuleRequirement**：勾选的连接模式编译为
  `RuleRequirement(ruleId, REQUIRE_INDICATION)` 注入 `RuleProfile`；候选空间在对应
  完整候选中未产生该 indication 时按 HARD 剪枝。机制定义见
  [../theory/rule-catalog.md](../theory/rule-catalog.md) §4。
- **错误示例 → REQUIRE_VIOLATION**：目标规则自身的剪枝被豁免（违规 finding 保留为标记，
  `isDemonstrationTarget = true`），且要求该违规必须出现；其余规则照常约束。见同文 §5。
- **排列偏好**：`SpacingAt` 编译为通用 `SpacingRequirement`，
  纵向检查候选 frame 的排列（复用 `VoiceLeadingAnalysis` 密集/开放事实），不符合偏好产生
  高权重 SOFT finding——"尽量遵守"而非硬约束，偏离时 finding 即解释。
- **无解**：solver 返回空时输出 `diagnostics`（v1 只提示"约束组合无解，请减少所选规则"；
  "最接近候选 + 缺失需求"诊断 🚧 v2）。
- **执行**：编译与 beam search 在 Compute dispatcher 协程中运行，可取消；
  `BeamSearchSolver` 需在逐层扩展处加协作式取消检查（`ensureActive`）。

## 6. 层级 3 与 cell 引用 🚧 初步

- `MaterialRef(cellId, candidateIndex?)`：引用 `ScoreCell.score`、`RequestCell.material`
  或上游输出的某个候选。编译期解析为 `WritingTask.fixedMaterial`。
- `MultiStageRequest.stages` 编译为 `WritingTaskPlan`，阶段间以前一阶段结果为固定材料；
  是否把每个阶段展开为独立 cell（用户可干预中间结果）还是单 cell 内部流水线，待层级 1/2
  落地后按真实使用决定。
- 过期级联：引用图中上游 fingerprint 变化 → 下游 cell 递归标记过期；禁止循环引用
  （保存与编辑时校验）。

## 7. 开放问题

- `StorageScore` 内嵌于 cell 后文件体积与 diff 可读性（候选 K 个 × 每个一份乐谱）；
  必要时输出候选改为共享事件池或压缩存储。
- spec 层（KeySpec/DegreeSpec）与 theory 类型的映射放 `:exploration` 还是下沉 `:theory`
  的 serializable 伴生格式——先放 `:exploration`，出现第二个消费方再下沉。
- 输出候选的多样性控制参数是否暴露到 `SearchSpec`（对应 ai/roadmap §3.2 diversity）。
