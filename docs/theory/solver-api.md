# 求解器 API（Solver API）

> 状态：**S1 部分落地**——`SolverApi` 五入口 + `CapabilityManifest` / `FormSpec` + `SolverDiagnostic`
> 已在 `:exploration`（`SolverApi.kt` / `DefaultSolverApi.kt` 的 `object SolverEngine`）实现；
> `describe` / `enumerate` / `solve` ✅，`refine`（S2）与 `check`（待全谱入口）为已声明入口，返回结构化占位诊断。
> 前置：[writing-engine.md](writing-engine.md)（写作任务与求解器）·
> [rule-catalog.md](rule-catalog.md)（规则目录）。
> 配套设计：[rule-scenes.md](rule-scenes.md)（规则适用场景模型）·
> [constraint-program.md](constraint-program.md)（约束程序 DSL）·
> [diverse-search.md](diverse-search.md)（约束程序多样化搜索）·
> [free-harmony-solver.md](free-harmony-solver.md)（任意固定声部与开放和弦运行时入口）·
> [../exploration/scripting.md](../exploration/scripting.md)（脚本引擎）。

## 1. 目标

把求解器能力收敛为**一套可序列化协议**，同时服务三类消费者：

1. **表单 UI**：探索模式前端不再为每章手写表单，而是按能力清单（manifest）渲染；
2. **用户脚本**：JS/TS 脚本通过同一 API 构造复杂约束并执行求解（[scripting](../exploration/scripting.md)）；
3. **LLM**：五个入口 1:1 映射为 MCP tools（ai/roadmap §7），manifest 是工具 schema 与
   `d.ts` 类型声明的同一来源。

判据：`ExplorationRequestRunner` 中按章节硬编码的分发与槽位扩展
（`secondInversionRuleExampleSlots` / `dominantSeventhSlots` / `circleOfFifthsSlots` 等）全部消失，
改由规则声明的场景数据驱动；新章节接入只增加规则 + 场景 + 目录注册，不改 runner 与前端。

> **状态**：三和弦三章 + 属七/其余七和弦章均已达成——runner 只保留 arity 通用分发
> （TRIAD → `SceneMatcher.instantiate`；SEVENTH → `SceneMatcher.instantiateSeventh`），
> 无任何按规则集合的槽位扩展。接入新规则的正确姿势见 [rule-scenes.md](rule-scenes.md) §8。

## 2. 五个入口

```kotlin
interface SolverApi {
    fun describe(): CapabilityManifest                       // ✅ 能力发现
    fun enumerate(request: EnumerationRequest): EnumerationResult  // ✅ 规则 → 适用进行（符号级）
    fun solve(request: SolveRequest): SolveResult            // ✅ 便捷请求 + ConstraintProgramSpec（核心约束子集，三/七和弦混合 arity）→ top-K
    fun refine(request: RefineRequest): SolveResult          // 🚧 S2：既有候选 + 新约束 → 优化
    fun check(request: CheckRequest): CheckResult            // 🚧 待全谱入口：乐谱 → 规则检查报告
}
```

- 所有请求/响应均为 `@Serializable` spec 层类型（延续 `CellRequest` 的做法：theory
  运行时类型不直接序列化）。协议版本 `SOLVER_PROTOCOL_VERSION` / 规则版本 `SOLVER_RULES_VERSION` 见 `SolverApi.kt`；
  当前协议版本为 `4`、规则版本为 `3`。v4 把副属、减七和弦组及具体用法统一收敛到
  `selections: Map<String, List<String>>`；符号和弦镜像仍保留省略根音属九身份，确保选定进行
  往返后不丢失对称解释。新增和弦族不再扩展请求 DTO。
- 执行在 Compute dispatcher 协程中，可取消；接口本体保持同步签名，调用方负责调度
  （与 `ExplorationRequestRunner` 现状一致）。
- in-process 优先；MCP server / HTTP 暴露是同一协议的传输壳，本期不实现。
- S1 已落地：`describe`（manifest 从 `RuleCatalog` + `Policies` 构建）、`enumerate`（委托
  `SceneMatcher`，`CONFIRMED` 🚧 降级为 MAY 并附诊断）、`solve`（承载便捷请求，委托
  `ExplorationRequestRunner`）。`refine` / `check` 返回结构化占位诊断（见 §5）。

### 2.1 describe

```kotlin
@Serializable
data class CapabilityManifest(
    val protocolVersion: Int,
    val rulesVersion: Int,               // theory 规则版本号，参与输出 fingerprint
    val chapters: List<ChapterInfo>,     // 章节 + 规则目录树（含 scene 摘要与可选性）
    val policies: List<PolicyInfo>,      // 练习策略（词汇表：允许的和弦/转位范围）
    val constraintKinds: List<ConstraintKindInfo>, // solve 支持的约束类型及参数 schema
    val forms: List<FormSpec>,           // 各请求类型的表单描述（见 §4）
)
```

规则目录树来自 `RuleCatalog`；每个规则节点附带其场景摘要（适用窗口、涉及的
facet 类型），UI 与 LLM 据此判断"这条规则要什么输入"。
勋伯格和声学这类非 textbook 章节由 `:exploration` 在 manifest 中追加章节节点，
其音乐约束仍定义在 `:theory` 的独立目录。

### 2.2 enumerate（规则示例模式）

按 [rule-scenes.md](rule-scenes.md) 的场景匹配引擎，在给定调性与词汇表上枚举
适用于所选规则的**符号级进行**：

```kotlin
@Serializable
data class EnumerationRequest(
    val key: KeySpec,
    val ruleIds: List<String>,           // 需通过 validateSelection
    val policyId: String,                // 决定词汇表（章节练习策略）
    val windowLimit: Int = 3,            // 进行最大槽数
    val verify: VerifyLevel = VerifyLevel.CONFIRMED, // MAY / CONFIRMED，见 rule-scenes §4
    val maxResults: Int? = null,         // 可选调用方预算；null 使用章节默认值
    val maxVisitedNodes: Int? = null,    // 可选符号搜索节点上限
    val chordFilters: List<SchoenbergChordFilterSpec> = emptyList(),
    val includeDeceptiveCadence: Boolean = false,
    val includeCadentialSixFour: Boolean = false,
)

@Serializable
data class EnumerationResult(
    val progressions: List<SymbolicProgression>,
    val diagnostics: List<SolverDiagnostic> = emptyList(),
)

@Serializable
data class SymbolicProgression(
    val slots: List<SymbolicChordSpec>,  // degree + quality + position（如 I⁶₄-V-I）
    val explanation: List<SceneBindingNote>, // 为什么适用：facet 绑定说明（#5 在第几槽哪个和弦音）
    val verified: Boolean,               // 已通过抽样 voicing 验证规则可命中
)
```

用户从列表选中一条后，前端把它转成 `SolveRequest`（`ChordAt` 约束序列 +
`RuleAt` requirement）再出谱例——**enumerate 与 solve 分离**，进行多时不必全部求解。

> **S1 实现**：入参增加 `policyId`（决定三/七和弦词汇表转位集合，见 `Policies`）；返回
> `SymbolicProgression` 的槽用 spec 镜像 `SymbolicChordSpecView(degree/quality/position/arity)`，
> `explanation` 为 `SceneBindingNoteView`。
> `verified` 目前恒 `false`（CONFIRMED 🚧）；`verify = CONFIRMED` 时结果降级为 MAY 并附 `confirmed-degraded` 诊断。
> 桌面探索页四六进行选择器已改走 `SolverEngine.enumerate`（`ExplorationView.sceneProgressions`）。
> 勋伯格综合练习的桌面预览只展示少量候选，因此通过 `maxResults` / `maxVisitedNodes`
> 使用独立的轻量预算，并在后台枚举；未指定预算的 API 调用继续使用章节默认值。

### 2.3 solve（综合练习模式）

```kotlin
@Serializable
data class SolveRequest(
    val key: KeySpec,
    val program: ConstraintProgramSpec,  // 见 constraint-program.md
    val policyId: String,
    val search: SearchSpec = SearchSpec(),
)
```

🚧 `SearchSpec` 将按 [多样化搜索契约](diverse-search.md) 从 `maxResults + beamWidth` 扩展为
显式候选上限、节点预算与 `DiversitySearchSpec`：`seed`、重启预算、最小槽距离、最小声部单元
距离、早期变异偏置和重合策略。第一候选不使用随机性；相同请求与 seed 必须复现候选顺序和
trace。合法空间不足以满足距离门槛时允许少返回，并产生 `diversity-exhausted` 诊断，不能用
近重复结果补齐 `maxResults`。迁移期 `beamWidth` 保留反序列化兼容并映射到候选上限。

现有 `RuleExampleRequest` / `ProgressionRequest` 降级为**便捷请求**：编译为
`SolveRequest` 后走同一路径（迁移期保留反序列化兼容）。`SolveResult` 沿用
`CellOutput` 结构（候选 + `StoredFinding` + breakdown + 诊断），另加
`solveStateFingerprint` 供 refine 引用。

> **实现**：`SolveRequest` 二选一承载 `convenience: CellRequest`（走 `ExplorationRequestRunner.run`）
> 或 `program: ConstraintProgramSpec`（S2 增量一，走 `ConstraintProgramCompiler.compile` +
> `ConstraintProgramSolver.solve`，用 `key` / `policyId` / `search`）。`SolveResult` 包 `CellOutput` +
> 结构化 `SolverDiagnostic`，`solveStateFingerprint` 取 `output.fingerprint`。桌面探索页运行按钮走
> `SolverEngine.solve(SolveRequest(request))`（便捷路径不变）。

### 2.3.1 自由和声运行时入口

`:theory` 已提供 `FreeHarmonySolver.compile/solve`。它把 `FreeHarmonyRequest` 编译成同一
`ConstraintProgram`，支持 `TonalPlan`、多重调性解释、开放和弦、任意 `VoicePlan`、习惯进行
模板、固定音，以及 `FREE_CLASSICAL / FREE_JAZZ` preset。

`FreeHarmonySolver.fromSchoenberg` 可保留既有章节 typed 规则与禁忌表，再最后合并用户
override。当前这是 in-process API；可序列化 spec、manifest 表单与桌面自由求解页面尚未接入 🚧。

### 2.4 refine（检查后优化）

语义 = **重解 + 锚定 + 相似度软目标**：

```kotlin
@Serializable
data class RefineRequest(
    val base: SolveRequest,              // 原请求（或由候选反推的等价请求）
    val baseline: OutputCandidate,       // 被检查的候选
    val pins: List<PinSpec>,             // 用户认可、必须保留的槽位/声部音高
    val addedConstraints: ConstraintProgramSpec,  // 增量约束，与 base.program 合并
    val similarityWeight: Double = 1.0,  // 与 baseline 的距离作为软扣分项
)
```

pins 编译为 `MaterialConstraint.FixedPitch`；相似度实现为合成 rule provider
（逐槽比较，偏离产生 SOFT finding），使"为什么这里改了"与其他解释同源。
真正的局部修复算法不做（v1 决策）；beam search 全量重解，靠 pin + 相似度收敛改动。

> **S1 状态**：🚧 未实现（依赖 S2 的 ConstraintProgramSpec）。`refine` 为已声明入口，
> 返回带 `refine-not-available` 诊断的空 `SolveResult`。`RefineRequest` 目前为最小占位
> （base / baselineCandidateIndex / pinnedSlots / similarityWeight）。
>
> **2026-08-02 审计结论**：当前占位协议还不能无歧义表达本文上方的目标形态：
> `OutputCandidate` 没有逻辑槽/声部 snapshot，candidate index 不带 base fingerprint，且缺少
> 逐音 `PinSpec`、`addedConstraints`、取消与预算耗尽状态。本轮自由练习自动写作先落 runtime
> 窗口重解、左边界、baseline 相似度与协作取消原语；公开 refine 待协议一次性升级后实现，
> 不把自由练习专用半成品冒充通用入口。完整缺口与目标协议见
> [free-practice-window-voicing.md](free-practice-window-voicing.md) §8。

### 2.5 check

对给定乐谱（`StorageScore` 载入 `FixedVoiceScore`）按 profile 跑全谱规则检查，
返回 findings。复用 writing-engine §7 的全谱入口；这是"用户作业检查"与
LLM `check_score` 工具的共同底座。

> **S1 状态**：🚧 入口已声明（`CheckRequest(score, profileId?)` → `CheckResult`），
> 但底座待 writing-engine §7 全谱入口就绪；当前返回带 `check-not-available` 诊断的空结果。

## 3. 分层与模块

```
:theory        RuleScene / SceneMatcher / ConstraintProgram（运行时）/ 编译到 WritingTask
:exploration   spec 层序列化类型 + SolverApi 实现 + CapabilityManifest / FormSpec
apps/desktop   表单渲染器、脚本宿主（GraalJS）、未来 MCP server
```

- 依赖方向不变：`desktop → exploration → theory → api`。
- spec ↔ theory 映射仍在 `:exploration`（document-model §7 的既有决策）；MCP server
  成为第二个消费方时再抽独立协议模块。
- `:theory` 不依赖脚本引擎：谓词逃生舱以 `interface ScriptPredicateHost` 注入
  （见 [constraint-program.md](constraint-program.md) §5）。

## 4. 表单渲染（FormSpec）

`RuleExampleInputSpec` 泛化为通用表单描述，由 manifest 输出：

```kotlin
@Serializable
data class FormSpec(
    val requestType: String,             // "rule-example" / "progression" / "constraint-program"
    val fields: List<FormField>,
)

@Serializable
data class FormField(
    val id: String,
    val kind: FormFieldKind,   // KEY_PICKER / RULE_TREE / DEGREE_PAIR / SLOT_LIST /
                               // SELECT / TOGGLE / NUMBER / PATTERN_EDITOR / SCRIPT_EDITOR
    val labelKey: String,
    val constraints: JsonObject = JsonObject(emptyMap()), // 取值域（枚举项、范围、默认值）
    val visibleWhen: FieldCondition? = null,              // 联动（选了错误示例才显示演示规则）
)
```

- 前端实现一个**通用渲染器** + 按 `kind` 的控件注册表；`RULE_TREE`、`SLOT_LIST`、
  `PATTERN_EDITOR` 是富控件，其余是基础控件。现有探索页手写表单逐步替换。
- 字段取值域从规则场景推导（例如 `DEGREE_PAIR` 的可选项 = enumerate 的 MAY 级结果），
  替代 `RuleExampleInputSpec.degreePairs` 的人工/代表对推导。
- `visibleWhen` 只做单字段等值条件，避免表单协议演变成第二个脚本语言；
  更复杂的输入交互直接升级为脚本/代码模式。

> **S1 实现**：`describe().forms` 已输出 `rule-example` / `progression` 两个 `FormSpec`
> （字段镜像现有控件：`KEY_PICKER` / `RULE_TREE` / `DEGREE_PAIR` / `PROGRESSION_PICKER` /
> `SELECT`(policy) / `TOGGLE` / `SLOT_LIST`）。桌面探索页已有 `FormSpecRenderer` 按字段分发到
> 现有控件；取数走 `SolverEngine`（solve / enumerate）+ 消费结构化诊断。二槽 `DEGREE_PAIR`
> 的取值域仍沿用 `RuleExampleInputSpec`，后续再改成 enumerate 推导。
> 勋伯格练习额外暴露 `schoenberg-exercise` 表单：`SELECT`(`exerciseId`) + `KEY_PICKER` +
> `PROGRESSION_PICKER`(`progression`) + `NUMBER`(`continuationChordCount`) +
> `CHORD_FILTERS`(`chordFilters`)。终止式及后续练习另有两个 `TOGGLE`：
> `includeDeceptiveCadence` 与 `includeCadentialSixFour`。descriptor 中声明
> 独立 / 综合分组与 `requiresEnumeratedProgression`；导和弦、六和弦等独立练习先通过 `enumerate`
> 返回 `SymbolicProgression` 列表，再把选中的 progression 放入
> `SchoenbergExerciseRequest.progression` 固定符号进行并渲染乐谱，桌面端不显示接续个数。
> 综合练习可通过 `chordFilters` 指定一个或多个和弦性质筛选；单项内的音级、规模、转位取交集，
> 多项由不同和弦分别满足。`EnumerationRequest.chordFilters` 与 solve 便捷请求采用相同协议。
> 两个终止式选项也同时进入 enumerate 与 solve 请求，避免预览进行和最终求解采用不同结构。

## 5. 诊断协议

```kotlin
@Serializable
data class SolverDiagnostic(
    val code: String,                    // "no-solution" / "rule-not-applicable" / ...
    val messageKey: String,
    val messageArgs: List<String> = emptyList(),
    val ruleId: String? = null,
    val slotIndex: Int? = null,
)
```

替换 runner 中的裸中文字符串。无解时 v1 按 requirement 类型报告未满足计数；
逐约束松弛探测（"去掉哪条就有解"）🚧 v2。

> **S1 已实现的 code**（`Diagnostics` 工厂 + `DiagnosticMessages.resolve` 中文回退）：
> `invalid-selection` / `rule-not-applicable`（规则集校验）、`no-solution`（约束无解）、
> `confirmed-degraded`（CONFIRMED 降级）、`refine-not-available`（S2）、`check-not-available`（待全谱入口）。
> `ExplorationRequestRunner` 三处诊断已改为构造 `SolverDiagnostic`，`CellOutput.structuredDiagnostics`
> 携带结构化诊断，`CellOutput.diagnostics`（`List<String>`）为其中文渲染回退，保持桌面向后兼容。
> 无独立 message catalog 前，`messageKey` → 中文映射集中在 `DiagnosticMessages`，接入 i18n 时替换。

## 6. 里程碑

| 里程碑 | 内容 | 依赖 |
|--------|------|------|
| **S0** ✅ | RuleScene 模型 + SceneMatcher（MAY，逐槽 DFS 增量剪枝）+ 三章硬编码迁移为场景数据；CONFIRMED 🚧 | rule-catalog（已完成） |
| **S1** ✅ | SolverApi 五入口 + manifest / FormSpec + SolverDiagnostic；describe/enumerate/solve 落地，探索页 FormSpec 字段分发器接入；refine 🚧(S2)、check 🚧(待全谱入口) | S0 |
| **S2** 🚧部分 | ✅：ConstraintProgramSpec 核心约束（RuleAt / ChordAt / Doubling / Spacing / **FifthAt→ToneCompleteness** / **AllDifferent** / **AdjacentCommonTone** / **AvoidDoubling** / **AvoidScaleDegreeDoubling** / **ChordToneNeighbor** / **TargetFeatureBonus** / **ConstraintAt(And/Or/Not)**）+ 编译器 + 通用 `ConstraintProgramSolver` + `RuleFound` 窗口投影 + `solve` program 路径 + `constraintKinds`；**三/七和弦混合 arity** 已改为 `ChordTarget` 能力接口 + `ChordRuleDispatcher` 模块自发现，七和弦便捷请求经 `instantiateSeventh` 收进 program 路径，混合上下文窗口精确化已完成。🚧：LinePattern、refine 协议与实现、变长 length；自由练习所需 runtime refine 基础见 [窗口写作设计](free-practice-window-voicing.md) | S1 |
| **S3** | GraalJS 脚本宿主 + scripted request + 谓词逃生舱 | S2 |
| **S4** | MCP tools 映射（对接 ai/roadmap M5/M6） | S1（solve/check）、S3（脚本工具） |

## 7. 测试约定

- manifest 一致性：每个 selectable 规则出现在目录树；每个 constraintKind 有 schema；
  FormSpec 字段引用的枚举项均可反序列化回请求。
- 关系约束：`constraintKinds` 暴露 `all-different` / `adjacent-common-tone`；勋伯格练习可通过
  `exerciseId` 选择知识点并出解。
- enumerate 金标准：教材例题的适用进行必须出现（终止四六 → I⁶₄-V-I；
  V7-I → `SEVENTH → TRIAD`；#5→4 → 小调含升 5 和弦接含 4 音级和弦的组合）；CONFIRMED 级结果逐条可 solve 出
  含目标 indication 的候选。
- 便捷请求回归：旧 `RuleExampleRequest` / `ProgressionRequest` 编译为 SolveRequest 后，
  输出与迁移前 runner 等价（对既有测试样例逐一对比）。
- refine：pin 的音不变；加约束后新解满足约束且相似度 finding 正确解释偏离位置。
