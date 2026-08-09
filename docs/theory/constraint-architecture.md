# 约束求解架构优化：通用和弦目标与规则自发现

> 状态：**M1-M4、M6/M7 与当前 textbook 命名约束迁移已实现，M5 待半音化章节推进**（§3.3 修订 +
> [constraint-program.md](constraint-program.md) §2.1-2.5）。
> 回答三个问题：①textbook 规则有多少能用 `ConstraintProgram`
> 拼出来；②旧 `TextbookChordTarget` 这层如何移除——它把所有章节规则耦合在统一分发点上；
> ③规则实现的总体架构如何与勋伯格（含半音化章节）一起演进。
> 前置：[constraint-program.md](constraint-program.md)（S2 约束程序）·
> [writing-engine.md](writing-engine.md) §5（RuleApplicability）·
> [rule-scenes.md](rule-scenes.md)（场景数据）·
> [schoenberg/schoenberg-harmony.md](schoenberg/schoenberg-harmony.md)。
> 里程碑见 [roadmap.md](roadmap.md)「约束求解架构优化」。

## 1. 问题诊断：TextbookChordTarget 的五处耦合

`constraint/TextbookChordTarget.kt`（约 540 行）是 S2 混合 arity 落地时的**过渡产物**：
用 sealed `Triad / Seventh` 联合体当统一目标，把"通用求解"落成了"逐 arity 分发"。耦合点：

1. **规则路由硬编码**：`ArityDispatchedChordRuleProvider` 写死 Triad→三章位置分发
   provider、Seventh→`DominantSeventhRules`；`checkScore` 写死三条上下文检查
   （二转位用法、七音预备、五度圈）。新章节 = 改这个类，与"runner 不再按章节分支"
   （roadmap P1 判据）的方向矛盾。
2. **候选工厂特判**：`ChordTargetCandidateFactory.allowFrame` 里 `when (target)` 分支——
   七和弦的 root/seventh 在场与 `SeventhFifthConstraint` 是硬编码代码，不是约束数据。
3. **`asSeventhTarget()` hack**：混合 transition 把三和弦端点包成 3 音"七和弦"喂
   `DominantSeventhConnection`。语义正确但位置错误：这是七和弦章的内部视角，
   却放在共享目标类型上，所有实现都被迫携带。
4. **checkScore 逐 arity 投影**（v1 已知限制）：混合进行里异 arity 邻槽会打断上下文窗口，
   二转位/七音预备检查可能漏判。根因是"按 arity 拆帧序列"而不是"给规则完整序列 + 通用视图"。
5. **章节概念泄漏进通用层**：`KnowledgeBonusRuleProvider` 硬编码 `isLeadingTriad` /
   `isFirstInversionTriad`；`AvoidDoublingRequirement.appliesTo` / `ChordToneNeighborRequirement.appliesToSource`
   写死 `target is Triad`；同一批 helper（`degree()` / `allows()`）在两个文件里重复了一份。

后果：每加一族和弦（九和弦、拿坡里、增六）都要改 sealed 类型 + 全部分发点；
每加一个章节都要改 `ArityDispatchedChordRuleProvider`。勋伯格半音化章节
（拿坡里 = "音阶第二位置上的另一个根音"）在这个结构下无处安放。

## 2. 目标图景：四个正交层

```
词汇层   ChordTarget 能力接口（和弦目标 = 数据，可扩展新和弦族）
约束层   Constraint 代数（适用域+谓词+强度+解释，And/Or/Not 组合，只消费能力接口）
规则层   命名约束（声明式本体）或章节 RuleModule（程序式本体），声明数据化适用性
调度层   RuleDispatcher（按适用性选规则模块——"solver 自行寻找适用规则"）
```

原则（writing-engine §3）修订表述：**生成期收窄必须有检查期 finding 对应**；
**判定本体唯一**——一条规则要么是命名约束（声明式，剪枝与解释共享同一 expr 求值，
经 constraint→finding 桥自动同源），要么是 Kotlin 模块（程序式），禁止两处并存。

### 2.1 词汇层：ChordTarget 能力接口

sealed 联合体改为能力接口，通用层只见接口：

```kotlin
interface ChordTarget {
    val key: Key
    val sonority: Chord                    // 有序和弦音：root, 3rd, 5th, (7th, ...)
    val bassPitchClass: PitchClass
    val degree: Int                        // 调内音级（半音化章节将扩为 degree + alteration）
    val inversion: Int                     // 0=原位, 1=一转, ...
    fun pitchClassFor(tone: ChordTone): PitchClass?
    fun identityKey(): String              // AllDifferent / 缓存用身份
}
```

- `TextbookTriadTarget` / `TextbookSeventhTarget` 直接实现接口，留在 `textbook` 包；
  sealed 包装类与 `ChordTargetVoicing` 双胞胎已删除，统一输出 `ChordVoicing`
  （`slotIndex + target + SATB pitches`），逐章节展示形态只在兼容门面还原。
- 关系约束求值、候选枚举、合成 provider（Doubling/Spacing/AllDifferent/...）全部
  改为消费接口——`when (target)` 从通用层消失。
- 未来扩展只需新实现：`NeapolitanTarget`（degree=2 + LOWERED 根音）、
  `AugmentedSixthTarget`；`identityKey` 携带变化音信息，场景/约束/调度层不改。

### 2.2 统一 TargetSelector：数据化的"适用于什么"

现散落在各 requirement 的 `degrees / positions / sourceDegrees / sourcePositions`
字段收拢为一个可复用选择器，**同时供约束、规则模块、奖励项使用**：

```kotlin
data class TargetSelector(
    val degrees: Set<Int> = emptySet(),          // 空 = 不限
    val qualities: Set<ChordQuality> = emptySet(),
    val inversions: Set<Int> = emptySet(),
    val arities: Set<ChordArity> = emptySet(),
) { fun matches(target: ChordTarget): Boolean }
```

### 2.3 规则层 + 调度层：规则自发现

`ArityDispatchedChordRuleProvider` / `PositionDispatchedTriadRuleProvider` 替换为
**注册制规则模块 + 通用调度器**（writing-engine §5"调度器按适用性选规则集"与
rule-catalog 开放问题中"规则调度器"的落地形态）：

```kotlin
interface ChordRuleModule {
    val chapter: ChapterId
    // 纵向：selector 命中该槽目标才调用
    fun verticalScope(): TargetSelector?
    fun checkVertical(frame, verticality, key): List<RuleFinding<EventId>>
    // transition：before/after selector 都命中才调用（如七和弦连接规则声明
    // "任一端点为七和弦"；原位连接规则声明 "两端 inversion=0"）
    fun transitionScope(): Pair<TargetSelector, TargetSelector>?
    fun checkTransition(transition, before, after, key): List<RuleFinding<EventId>>
    // 上下文：trigger 命中任一帧才调用；收到**完整**帧序列 + 通用视图（修复 §1.4）
    fun scoreScope(): TargetSelector?
    fun checkScore(frames, voices, isComplete): List<RuleFinding<EventId>>
}
```

- 调度器按 `identityKey` 对模块选择做缓存（beam 内层高频路径，选择结果只依赖目标身份）。
- 章节专属上下文类型（`RootPositionTriadConnection` / `DominantSeventhConnection`）
  降级为模块**内部适配**：七和弦模块自己把三和弦端点视为 3 音和弦
  （`asSeventhTarget` 的逻辑移进七和弦模块，从共享目标上删除）。
- `RuleProfile` / `RuleSuppression` / `RuleRequirement` 调解机制不变——调度器只决定
  "谁被调用"，不动"finding 如何调解"。
- `RuleScene` 仍是符号级枚举（enumerate 方向）的数据；模块的 `TargetSelector` 是
  检查方向的适用性。两者共享词汇（degree/quality/inversion/arity），暂不强行合一，
  开放问题见 §6。

### 2.4 约束层：候选生成约束化

候选工厂做成完全通用的"枚举 + 约束过滤"，家族特判改为**编译期注入的约束数据**：

- 新原语 `ToneCompletenessAt(slot|window, requiredTones, omittable, selector?)`：
  统一表达三和弦"四声部不省略和弦音 / 终止主和弦可省五"、七和弦"root+seventh 必在场"、
  `SeventhFifthConstraint` 完全/省五。`ChordAt(arity=SEVENTH)` 编译时默认注入
  root+seventh 在场；`FifthAt` 编译为它的实例。
- `AvoidDoublingAt` 增加**按音级**变体（含小调升变）：`AvoidScaleDegreeDoublingAt(degree, alteration?)`
  ——"导音几乎永远不重复"目前在三章里各写了一份，收编为一条通用约束。
- 旧三个 `*ExercisePolicy` 已退役为 `TextbookTriadConstraintPreset` **章节约束包**；
  完整性、重复音、避免重复导音均编译为 requirement，规则本体只产生 finding。
  原位、通用三和弦、七和弦 textbook 求解器均为“编译到 ConstraintProgram”的薄适配器。

## 3. textbook 规则 → ConstraintProgram 覆盖分析

三类处置。原则：**能无损落成约束数据的迁移**（剪枝与解释同源自动成立）；
处置判据不是"剪枝 vs 模式识别"的类别之分，而是**词汇覆盖**（§3.3 修订）——
expr 词汇覆盖到哪，声明式本体就迁到哪；覆盖不到的保留 Kotlin 本体，
程序经 `RuleAt(REQUIRE_INDICATION)`（即 `RuleFound` 原子）要求其出现。

### 3.1 已迁移到 ConstraintExpr ✅

当前 solver 会自动注入以下命名约束，并以 ruleId 作为唯一 finding 本体：

| 规则组 | 当前约束实现 |
|------|------|
| 三和弦完整性、原位重复根音、四六重复低音、避免重复导音 | `ToneCompleteness` / `ToneDoubled` / `ScaleDegreeNotDoubled` |
| 第一转位低音线、减三和弦第一转位、原位 V–vi 禁止进行 | `TargetMatches` + `Not/And` |
| 终止主和弦省略五音、内声部导音解决、升五到四禁则 | `ToneMultiplicity` / 带源音级筛选的 `ChordToneNeighbor` |
| 七和弦七音下行/上行特例、外声部导音解决 | `ChordToneNeighbor` |
| 七和弦质量、根音/七音省略、五音/三音省略提示 | `TargetMatches` / `ToneCompleteness` |
| 转位 V7 解决、阻碍进行、II7/导七进行 | `TargetMatches` / `ToneMultiplicity` / `VoiceDiatonicSteps` |
| 七音预备五型、四六和弦四种语境、V7-I 三种排列形态 | `NamedSeventhPreparationConstraints` / `NamedSixFourConstraints` / `NamedV7ResolutionConstraints` |
| 五度圈七和弦、转位交替、完全/省五交替 | 五度圈槽位目标的 `And/Or` 组合约束 |

requirement 仍可通过 `fromRequirements` 兼容工厂进入统一代数；这是编译边界的兼容层，
不是第二套运行时判定。软完整性不会参与 hard 剪枝，错误示例通过 `REQUIRE_VIOLATION`
自动放宽对应 ruleId。

### 3.2 有意保留 Kotlin escape hatch ✅

以下规则需要逐声部运动、音程 witness 或完整旋律统计，当前不强行伪装成简单原子：

- 原位三和弦四/五度、三/六度、二/七度连接模式；
- 平行五度/八度、不相等五度、隐伏音程、声部交叉与音域；
- 小调导七到主和弦的减五到纯五、特殊平行五度检测；
- `FourPartTextbookRules` 与 `MelodyTextbookRules`。

这些模块仍由 `ChordRuleDispatcher` / composite provider 调用，并通过 `RuleFound`
接入同一约束代数。已迁移 ruleId 会由 composite provider 过滤旧模块同名 finding，避免双份判定。

### 3.3 规则本体的两种形态：词汇覆盖判据（2026-07-10 修订）🚧

v1 结论"PATTERN 一律保留 Kotlin 本体"过强。存在证明：勋伯格导和弦的"预备五音 +
下行解决到 III"本身就是用约束（`ChordToneNeighbor`）描述的，且满足时发 INDICATION——
约束缺的从来不是表达力，是**命名与解释的桥**（constraint-program §2.4：命名约束携带
ruleId / 文案，通用发射器按 modality 产出 VIOLATION / INDICATION）。原理由"重写为约束
组合会造出第二份判定"只在"约束、规则各写一份"时成立；当规则**被定义为**命名约束时，
判定本体只有一份，同源反而由结构保证。修订后的判据：

- **声明式本体**：expr 词汇（含 Or 分支命名）能完整表达判定特征与教学解释的规则，
  定义为命名约束。原 v1 清单中"多特征合取的形态识别"实为**合取的析取**——七音预备
  五型 = `Or(保持型, 经过型, 辅助型, 倚音型, 跳进上方型)`、四六和弦四种用法、`V7-I`
  各形态——Or + 分支命名落地后逐步迁入，命中哪支发哪支的 INDICATION 解释。
- **Kotlin 本体**：需要专用识别算法、witness 结构或文案超出原子词汇的规则
  （四五度三种连接模式的逐声部程序性判定、bass-line-enrichment、本就通用的
  `FourPartTextbookRules` / `MelodyTextbookRules`），保留 `ChordRuleModule` 注册 +
  调度器选择，经 `RuleFound` 原子接入同一代数（`RuleAt` 的推广）。
- **迁移纪律不变**：迁入声明式后，active ConstraintProgram 路径只能由声明式本体发出该 finding；兼容 API 可以保留旧 Kotlin 检查，但 composite provider 必须过滤同名旧 finding，禁止 active path 双份判定。

结论修订：不再是"约束数据 vs 规则本体"双轨，而是**单一约束代数 + Kotlin 逃生舱**。
"章节 = 词汇表 + 约束包 + 规则模块集"的读法不变，但约束包里可以直接携带命名规则；
规则模块集随词汇扩充单调收缩。

## 4. 勋伯格与半音化的落点

- 勋伯格章节已是 S2 的正确形态（直接构造 `ConstraintProgram`），本方案已让它更薄：
  `AvoidDoubling / AvoidScaleDegreeDoubling / ChordToneNeighbor / TargetFeatureBonus` 进公开
  `ConstraintProgramSpec` 后，`SchoenbergExerciseRequest` 的编译产物可完全序列化，脚本/LLM 可复用同一批约束。
- 硬编码的知识点奖励改 `TargetFeatureBonus` 数据后，科技树节点 = "词汇表增量 +
  约束包增量 + 奖励项"，新增节点不再写 Kotlin 分支。
- 半音化章节（schonberg-chromatic-chord.md：拿坡里/增六）依赖词汇层扩展：
  `ChordTarget.degree` 扩为 degree + alteration，`identityKey` 携带拼写——
  这正是 §2.1 接口化的直接收益，roadmap P3 与本方案 M5 合流。
- 小调分支、无共同音连接等后续节点在新结构下均为数据增量。

## 5. 迁移策略与卡尺

行为等价优先，逐里程碑替换（详细排期见 roadmap）：

1. ✅ **M1 接口化**：抽 `ChordTarget` + `TargetSelector`，`TextbookTriadTarget` /
   `TextbookSeventhTarget` 直接实现接口；旧 `TextbookChordTarget` 兼容层已删除。
2. ✅ **M2 规则调度器**：`ChordRuleModule` 注册 + `ChordRuleDispatcher` 已替换通用 arity 分发；
   standalone 的 `forTargetType()` / `PositionDispatchedTriadRuleProvider` 已删除，checkScore 使用完整序列视图。
3. ✅ **M3 候选约束化 + 新原语**：`ToneCompletenessAt` / 音级 AvoidDoubling / alteration neighbor
   已进入 runtime；`FifthAt` 编译为 `ToneCompletenessRequirement`；旧 policy 候选工厂已由章节约束包取代，
   便捷请求与兼容门面共用同一 preset 编译入口，候选剪枝与对应 finding provider 共享 requirement 判定。
4. ✅ **M4 勋伯格泛化 + spec 曝光**：`TargetFeatureBonus` 取代硬编码 knowledge bonus；
   `AvoidDoubling` / `AvoidScaleDegreeDoubling` / `ChordToneNeighbor` / bonus 已进入
   `ConstraintProgramSpec` 与 manifest；所有适用性字段统一为 `TargetSelector`，旧双轨字段已删除。
   卡尺：公开 spec 序列化 round-trip 后可编译并求解勋伯格导和弦约束。
5. **M5 半音化词汇**：degree+alteration、拿坡里/增六目标实现类（与 roadmap P3 合并推进）。
6. ✅ **M6 统一约束代数**：`ConstraintScope` + `Constraint` + And/Or/Not、Kleene 三值求值、`RuleFound` 与通用
   finding 桥已落地；九个 requirement 构造参数退役为 desugar 工厂，专用 provider 已由
   `ConstraintCompositeRuleProvider` 收编。旧 spec 与便捷请求仍走同一原子判定。
7. ✅ **M7 命名规则迁移**：勋伯格预备/解决、三和弦垂直规则、七和弦七音/导音解决、
   质量/省略/常见转移语境、五度圈转位模式，以及升五到四禁则均已进入命名约束。
   NamedTriadConstraints、NamedV7MotionConstraints、NamedSeventhContextConstraints
   与既有 NamedSeventhPreparationConstraints / NamedSixFourConstraints /
   NamedV7ResolutionConstraints 共同构成当前 textbook 声明式规则层；逐声部音程、
   旋律统计等专用算法仍通过 Kotlin escape hatch 接入。

风险与对策：

- **调度开销**：模块选择进 beam 内层——按 `identityKey` 缓存选择结果；调度器本身无状态。
- **双份判定**：active ConstraintProgram 路径中，已迁移 ruleId 只能由命名约束产出；兼容 API 可保留旧检查，但 composite provider 会过滤同名旧 finding。
- **REQUIRE_INDICATION 窗口投影**不受影响：`RuleAt` 已降糖为 `RuleFound`，未来窗口仍保持
  `UNDETERMINED`，基础模块 finding 由统一桥观察。

## 6. 开放问题

- `TargetSelector` 与 `RuleScene` facet 的合一程度：selector 是 scene 的目标级子集，
  长期可由 scene 推导 selector（单一声明源），v1 先分开以免场景模型被检查路径反向绑架。
- `ChordRuleModule` 粒度：按章节一个模块，还是按规则组（四五度组/预备组）？
  倾向章节级起步，模块内部保留现有规则对象结构。
- Or 分支命名默认复用已有 `RuleCatalog` ruleId；脚本自定义分支可只作 INDICATION 变体。
  分支 `scoreDelta` 已用于保持七音预备五型的旧排序。剩余求值优化（expr memo）见
  constraint-program §8。
- 三声部/五声部写作：`ToneCompletenessAt` 的省略规则依声部数变化，selector 是否需要
  voiceCount 维度——推迟到非四部织体立项时定。
- 变化音 `identityKey` 与 `AllDifferent` 的相互作用（拿坡里与 II 是否"同身份"）：
  需要教学语义裁决，挂 M5。
