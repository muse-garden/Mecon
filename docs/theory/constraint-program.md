# 约束程序（Constraint Program）🚧 部分实现

> 状态：**核心约束子集 + 三/七和弦混合 arity + 架构优化 M1-M4、M6/M7 已落地**——`ConstraintProgramSpec`（`ChordAt` / `RuleAt` /
> `DoublingAt` / `SpacingAt` / `FifthAt` / `ToneCompletenessAt` / `AllDifferent` / `AdjacentCommonTone` /
> `AvoidDoublingAt` / `AvoidScaleDegreeDoublingAt` / `ChordToneNeighbor` / `TargetFeatureBonus` / `ConstraintAt`，
> `ChordArity` = TRIAD / SEVENTH，固定 `length`）+
> `ConstraintProgramCompiler`（`:exploration`，spec→运行时、便捷请求→spec，七和弦场景经
> `SceneMatcher.instantiateSeventh`）+ 通用 `ConstraintProgram` / `ConstraintProgramSolver`（`:theory` 包
> `com.mecon.theory.constraint`）。统一目标已打薄为 `ChordTarget` 能力接口 + `TargetSelector`；
> `ChordRuleDispatcher` 按注册的 `ChordRuleModule` 自发现三和弦 / 七和弦规则，混合 arity 的上下文检查不再按 arity
> 过滤后折叠序列。`RuleAt` 经兼容工厂降糖为带窗口的 `RuleFound` / `Not(RuleFound)`。
> 勋伯格练习原先 runtime-only 的 `AvoidDoublingRequirement` / `ChordToneNeighborRequirement` / `TargetFeatureBonusRequirement` 已进入公开
> `ConstraintProgramSpec`。**统一约束代数**（§2.1–2.4：`Constraint` = 适用域 + 谓词 + 强度 + 解释，
> And / Or / Not 组合 + 三值求值 + 通用 finding 桥）已由 M6/M7 落地。
> 🚧 未实现：`LinePattern` 自动机、`MotiveAt`、脚本谓词、目标项、变长 `length`、`refine`。
> 运行时类型归属 `:theory`，序列化 spec 归属 `:exploration`。
> **架构演进**：M1-M4 已完成；M5（degree+alteration 目标身份、拿坡里/增六目标实现类）
> 见 [constraint-architecture.md](constraint-architecture.md) 与 [roadmap.md](roadmap.md)。
> 消费方：[solver-api.md](solver-api.md) 的 `solve` / `refine` 入口；
> 搜索策略：[diverse-search.md](diverse-search.md)（首解贪心 DFS + 多样化重启 DFS）；
> 脚本引擎产出的即是本协议（[../exploration/scripting.md](../exploration/scripting.md)）。

## 1. 定位

`ConstraintProgram` 是"用户想要什么"的统一表达：表单 UI、用户/LLM 脚本、便捷请求
（旧 `RuleExampleRequest` / `ProgressionRequest`）最终都编译到它，再由编译器落成
`WritingTask`（timeline + targets + profile + material + 合成 rule provider）。

原则沿用 writing-engine §3：**每条约束在生成阶段收窄枚举的同时，必须在检查阶段
产生对应 `RuleFinding`**（`solver.constraint.*` 命名空间），使剪枝、扣分与解释同源。
声明式为主；脚本谓词是逃生舱，不是主路径。

## 2. 约束分类

### 2.1 统一约束模型 ✅（M6）

旧运行时 `ConstraintProgram` 曾积累九个平行 requirement 列表（doubling / avoidDoubling /
avoidScaleDegreeDoubling / toneCompleteness / spacing / allDifferent / adjacentCommonTone /
chordToneNeighbor / targetFeatureBonus），每加一个原语要同时改构造参数、专用 provider 与 spec；
且逻辑组合（"七音预备五型任一形态"）无处表达。现已收敛为单一 `Constraint` 代数；
顶层 scope 与旧原子自身的 window/selector 取交集：

```kotlin
data class Constraint(
    val expr: ConstraintExpr,             // 谓词：原子可逻辑组合（§2.2）
    val modality: ConstraintModality,     // REQUIRE / PREFER(weight) / REWARD(bonus) / ANNOTATE
    val ruleId: RuleId? = null,           // 解释：命名后即"规则"；null 用原子默认 id
    val explanation: ConstraintExplanation? = null,
    val scope: ConstraintScope = ConstraintScope(window = null, selector = ANY),
)
```

顶层 `constraints: List<Constraint>` 语义即合取；`ConstraintProgram` 构造收敛为
`(key, slotDomains, constraints, ruleProfile, rangeProfile, searchConfig, ruleModules)`；运行时的
`includeDerivedTextbookConstraints` 可隔离 textbook 派生约束，但不关闭通用四部检查，也不进入公开 spec。
九个列表参数已退役为 `fromRequirements` 构造工厂（desugar）；各 requirement 的 `required: Boolean` 由 `modality` 统一取代。

### 2.2 谓词代数：原子 + And / Or / Not

`ConstraintProgram.fromRequirements` 的兼容编译边界接收不可变 `ConstraintRequirementConfiguration`，集中传递规则 profile、typed requirement 家族、搜索配置和 textbook 派生开关；逐族 desugar 与禁忌相邻进行探测器的开放域投影语义不变。

原子从现有 requirement 蒸馏（判定逻辑不重写，换壳）：

| 原子 | 判定 | 蒸馏自 |
|------|------|--------|
| `ToneDoubled(tone)` | 锚槽重复了某和弦音 | `DoublingAt`；`AvoidDoublingAt` = `Not(...)` |
| `ScaleDegreeDoubled(degree, alteration)` | 锚槽重复了某调内音级 | `AvoidScaleDegreeDoublingAt` = `Not(...)` |
| `TonesPresent(tones)` / `TonesOmitted(tones)` | 和弦音在场 / 省略 | `ToneCompletenessAt` / `FifthAt` |
| `SpacingIs(OPEN/CLOSE)` | 排列形态 | `SpacingAt` |
| `TargetIs(selector)` | 锚槽目标特征 | `TargetFeatureBonus` 的判定部分 |
| `NeighborTone(sourceTone, direction, candidates, deltas, voiceFilter, neighborSelector)` | 某和弦音所在声部与前/后槽的音级关系 | `ChordToneNeighbor` |
| `CommonToneWithPrevious(holdInSameVoice)` | 与前槽有共同音（可要求同声部保持） | `AdjacentCommonTone` |
| `DistinctIdentities` | 窗口内和弦身份不重复 | `AllDifferent` |
| `RuleFound(ruleId, kind)` | 二阶：其他规则在锚槽产出了 finding | `RuleAt`（FORBID = `Not(...)`）|
| `Predicate(scriptId)` | 脚本逃生舱（§5） | `PredicateRef` |

组合节点 `And / Or / Not`。**Or 是新增表达力**："七音预备五型任一成立"、
"四六和弦四种用法之一"这类原先只能写 Kotlin 的形态识别成为约束的析取；Or 分支可
各自命名（分支级 ruleId / message），命中哪支发哪支的 INDICATION——"识别出是哪种
形态"由此进入声明式词汇。`RuleFound` 是二阶原子（观察其他规则的 finding），分层求值：
裁决由统一桥观察基础模块 finding；REQUIRE_INDICATION 的窗口投影语义不变。

M7 另在运行时代数中加入 `SameSonority`、`VoiceDiatonicSteps`、`VoicePitchClassCardinality`、`ToneMultiplicity` 与 `ToneInVoiceFilter` 原子，供四六和弦四用法和 V7-I 三种排列形态的命名约束使用；这些是 theory 内部迁移词汇，尚未暴露为公开 `SlotConstraintSpec`。

### 2.3 三值求值与剪枝 ✅

搜索前缀上每个原子求值为 SATISFIED / VIOLATED / **UNDETERMINED**（如 NEXT 方向的
`NeighborTone` 在下一槽落定前未决）。Kleene 传播：And 取最劣、Or 取最优、Not 交换
S/V、UNDETERMINED 保持。剪枝规则：`REQUIRE` 且求值 = VIOLATED → 剪掉该前缀；
Or 只有全部分支 VIOLATED 才剪——这是析取仍可参与逐槽 DFS 增量剪枝的前提。
完整候选上残留的 UNDETERMINED 按原子边界语义收敛（窗口越界 → 空真或违反，沿现
chordToneNeighbor 的窗口判定）。

ConstraintProgramSolver 不使用通用 beam 层级全量展开。当前实现是贪心 DFS：每个前缀先按
“完整和弦/内声部小跳/高音小跳”的共享放宽层排序，再按完整前缀评分（含声部移动平顺度、规则
finding 与约束奖励）递归深入；找到 maxResults 个不同解立即停止。候选工厂按同一优先级只保留
每目标 8–32 个帧，并设节点
预算，避免长进行物化大量排列。🚧 下一步按 [多样化搜索契约](diverse-search.md) 改为
“确定性首解 + 强制变异重启”，在搜索期执行结构距离与重合剪枝；HARD finding 始终剪除。
求值同时返回 **witness**（命中的声部 / 槽），供
finding anchors 与 message 模板插值（原 neighbor `sourceVoices` 锚点逻辑的泛化）。

### 2.4 constraint → RuleFinding 桥 ✅

一个通用 finding 发射器取代逐 requirement 的专用 provider：

| modality | expr 结果 | finding |
|----------|-----------|---------|
| REQUIRE | VIOLATED | HARD VIOLATION |
| PREFER(w) | VIOLATED | SOFT VIOLATION（权重 w）|
| REWARD(b) | SATISFIED | INDICATION + `scoreDelta`（EXPLANATORY）|
| ANNOTATE | SATISFIED | INDICATION（教学演示，不计分）|

`ruleId = constraint.ruleId ?: solver.constraint.<原子名>`。§1 的同源原则由结构保证：
剪枝、扣分与解释共享同一 expr 求值，不可能出现第二份判定。**命名约束即声明式规则
本体**——勋伯格导和弦"预备五音 + 下行解决到 III"已经以 `ChordToneNeighbor` 形态存在
且满足时发 INDICATION，命名化只是把 ruleId / 文案从 provider 硬编码挪进数据。哪些规则
迁入声明式、哪些保留 Kotlin 模块，判据见 [constraint-architecture.md](constraint-architecture.md) §3.3。

### 2.5 spec 派生形式目录

下表为 spec 面向用户 / 脚本的词汇。统一模型落地后既有条目**保留为派生形式**（编译期
desugar 到 `Constraint`，kotlinx 默认值向后兼容），另新增通用 `constraint-at`
（window / selector / expr / modality / weight / bonus / ruleId / message，`ExprSpec` 多态）供脚本直接组合命名规则。

```kotlin
@Serializable
data class ConstraintProgramSpec(
    val length: SlotCountSpec,                    // 固定槽数或范围（低音线长度不定时用范围）
    val domain: DomainSpec? = null,               // 词汇表收窄（覆盖 policy 默认）
    val slotConstraints: List<SlotConstraintSpec> = emptyList(),
    val linePatterns: List<LinePatternSpec> = emptyList(),
    val motives: List<MotiveSpec> = emptyList(),
    val predicates: List<PredicateRefSpec> = emptyList(),
    val objectives: List<ObjectiveSpec> = emptyList(),
)
```

| 类别 | Spec | 表达内容 |
|------|------|---------|
| 规则 | `RuleAt(window, ruleId, mode)` | 在某槽位窗口要求/禁止/演示某规则（`RequirementMode` 三态） |
| 规则集 | `RuleSetAt(window, chapterId \| tag)` | 指定一系列规则（章节/风格），展开为多条 `RuleAt` |
| 和弦 | `ChordAt(slot, degrees?, qualities?, positions?, arity, triadSonority)` | 槽位和弦收窄；全 null 字段 = 不限。`arity` = TRIAD / SEVENTH（决定目标变体与规则路由）；`triadSonority` = SEVENTH 槽以三和弦发声但仍走七和弦章（V7-I 的 I / 终止四六 I⁶₄，镜像场景 `slot.arity`=TRIAD） |
| 排列 | `SpacingAt(window, OPEN / CLOSE / ANY)` | 开放/密集偏好（软，权重可调） |
| 重复音 | `DoublingAt(slot, chordTone, required?)` | 要求重复根音/三音/五音/低音；默认软偏好，`required=true` 时作为硬约束剪枝 |
| 重复音 | `AvoidDoublingAt(slot, chordTone, required?, degree?, position?, selector?)` | 禁止重复某和弦音；勋伯格六和弦/导和弦练习已用，已公开到 spec |
| 重复音 | `AvoidScaleDegreeDoublingAt(slot, degree, alteration?, required?, selector?)` | 禁止重复某调内音级；`alteration` 预留升导音与后续半音化 |
| 五音 | `FifthAt(slot, REQUIRE_FIFTH / OMIT_FIFTH)` | 七和弦槽五音完整性（生成期收窄，仅七和弦目标生效） |
| 完整性 | `ToneCompletenessAt(window, requiredTones, omittedTones, selector?)` | 候选必须包含 / 省略指定和弦音；`FifthAt` 编译为该通用原语 |
| 关系 | `AllDifferent(window)` | 窗口内和弦身份不得重复（degree / quality / arity）；用于长进行先选和弦 |
| 关系 | `AdjacentCommonTone(window, holdInSameVoice?)` | 相邻槽必须有共同音；`holdInSameVoice=true` 时要求某声部保持同一音高 |
| 关系 | `ChordToneNeighbor(window, tone, direction, candidates, voiceFilter?, selector?)` | 某个和弦音所在声部与前/后相邻槽的音级关系；支持 candidate alteration |
| 目标 | `TargetFeatureBonus(window, selector, ruleId, message, bonus)` | 综合练习按知识点丰富度排序，公开为数据化特征奖励 |
| 具体音 | `PitchAt(slot, voice, pitch \| scaleDegree)` | 指定某声部某槽的音 |
| 音域 | `RangeFor(voice, low..high)` | 覆盖默认 `VoiceRangeProfile` |
| 线条 | `LinePattern`（§3） | 声部走向模式，含变量与持续 |
| 动机 | `MotiveAt(voice, window, pitches, transposable?)` | 旋律片段/动机嵌入（[../analysis/motive.md](../analysis/motive.md)）；模式自动机（§3.1 配方）可挂任何逐音候选空间，含 figuration Stage 2 与复调；移位/倒影匹配 🚧 |
| 谓词 | `PredicateRef(scriptId, scope)`（§5） | 脚本判定逃生舱 |
| 目标 | `SimilarityTo(baseline, weight)` / `Diversity(weight)` | 软评分项，不剪枝 |
| 节奏 🚧 | `HarmonicRhythm` / `MeterSpec` | 和声节奏/槽位时值；拍位语义设计已定（[figuration.md](figuration.md) §2 `MeterPlan`，v1 一槽一拍） |
| 外音 🚧 | `FigurationAt(window, voice?, types, density)` | 窗口内要求出现指定类型和弦外音；编译到装饰阶段（Stage 2）requirement + 操作白名单（[figuration.md](figuration.md) §7） |
| 持续音 🚧 | `PedalAt(window, voice = BASS)` | 持续音：低音固定 + 中间槽豁免低音和弦隶属 + 首尾同和弦；编译到**骨架阶段**（[figuration.md](figuration.md) §7） |
| 调性计划 🚧 | `KeyPlanSpec` | 转调点与目标调（转调章节） |

窗口 `window` 统一为 `SlotWindow(start, end)`，支持开放端（"结尾处"、"任意位置"）。

`ChordToneNeighborRequirement` 的运行时形状可覆盖一批 textbook 中已硬编码的倾向音/预备规则：

- 勋伯格 VII：`sourceTone=FIFTH, sourceDegrees={7}`；`PREVIOUS + candidates={4} + delta=0` 表示预备保持，`NEXT + candidates={3} + delta=-1 + neighborDegrees={3}` 表示解决到 III。
- 根位三和弦章节的内声部导音例外：可用 `sourceTone` 指向含 7 的和弦音，`voiceFilter=INNER`，`NEXT + candidates={5}` 或 V-vi 的 `candidates={6}` 表达。
- 七和弦章节：V7 三音在外声部上行到 1 可用 `sourceTone=THIRD, voiceFilter=OUTER, NEXT + candidates={1} + delta=1`；七音下行解决可用 `sourceTone=SEVENTH, NEXT + candidates={3}, delta=-1`；七音预备可用 `PREVIOUS` 与相应 delta/candidates 表示。

## 3. LinePattern：带变量的线条模式

覆盖"低音半音下行、起始音高不定、换音位置不定、同一低音可持续两三个和弦"：

```kotlin
@Serializable
data class LinePatternSpec(
    val voice: VoiceRoleSpec? = null,      // null = 任意声部（由求解绑定）
    val anchor: SlotWindow,                // 模式允许出现的槽位窗口
    val elements: List<LineElementSpec>,
)

@Serializable
data class LineElementSpec(
    val pitch: PitchTermSpec,              // Concrete(pitch) / Degree(scaleDegree) / Var(name)
    val hold: IntRangeSpec = IntRangeSpec(1, 1),   // 同音延续的槽数范围
    val stepFromPrevious: StepTermSpec? = null,    // 与前一元素的关系
)

// StepTermSpec 取值：
//   Chromatic(-1)          半音下行
//   Diatonic(-1)           级进下行（调内）
//   Within(minSemi, maxSemi)  音程范围（含方向符号）
//   Direction(DOWN)        只约束方向
```

半音下行低音线（4 个换音点、每音持续 1~3 个和弦、起点不定）：

```yaml
linePatterns:
  - voice: BASS
    anchor: { start: 0, end: null }      # 任意起点
    elements:
      - { pitch: { var: X }, hold: [1, 3] }
      - { pitch: { var: X }, hold: [1, 3], stepFromPrevious: { chromatic: -1 } }
      - { pitch: { var: X }, hold: [1, 3], stepFromPrevious: { chromatic: -1 } }
      - { pitch: { var: X }, hold: [1, 3], stepFromPrevious: { chromatic: -1 } }
```

变量语义：`Var(name)` 首次出现时由求解器绑定（受声部音域与和弦约束共同收窄）；
同名变量再次出现表示同一音高（可用于"回到起点"类模式）。相对关系（半音下行）
只依赖 `stepFromPrevious`，与变量是否具名无关。

### 3.1 求解集成：模式自动机

`LinePattern` 编译为逐槽推进的匹配自动机（元素序列 + hold 计数 → NFA 状态集合）：

- 自动机状态挂在 `FixedVoiceWritingState` 上，随 `apply()` 增量推进——与现有
  纵向/transition finding 缓存同一模式，搜索内层不回溯扫描前缀。
- 剪枝：某状态下所有 NFA 分支均死亡且 anchor 窗口已无法重新开始 → 该前缀剪掉
  （HARD finding `solver.constraint.line-pattern-unmatchable`）。
- 完整候选上模式未完成 → HARD finding；匹配成功 → INDICATION finding，锚点为
  匹配到的各槽事件，UI 可高亮整条线。

## 4. 编译到 WritingTask

| 约束 | 编译产物 |
|------|---------|
| `RuleAt` | `RuleRequirement` 扩展槽位窗口：`RuleRequirement(ruleId, mode, window: SlotWindow? = null)`（theory 层改动：requirement 检查时按 finding 锚点所在槽过滤；null 保持现全局语义） |
| `RuleSetAt` | 按 `RuleCatalog.chapter()` / `RuleTag` 展开为 `RuleAt` 列表 + profile 合并 |
| `ChordAt` / `domain` | target provider 候选收窄（`TextbookTriadWritingSlot` 泛化为 `SlotDomain`：允许的 triad × position 集合） |
| `SpacingAt` / `DoublingAt` | 合成 rule provider（`SpacingPreferenceRuleProvider` 已有先例），SOFT finding |
| `AllDifferent` / `AdjacentCommonTone` | 合成 transition / vertical provider，HARD finding；目标和弦层先剪掉重复/无共同音分支，排列层再剪掉未保持共同音的 voicing |
| `PitchAt` | `MaterialConstraint.FixedPitch` |
| `RangeFor` | `VoiceRangeProfile` 覆盖 |
| `LinePattern` / `MotiveAt` | 模式自动机 rule provider（§3.1） |
| `SimilarityTo` | 相似度 rule provider：逐槽与 baseline 比较，偏离 → SOFT 扣分 + finding 说明改动位置 |
| `PredicateRef` | 谓词 rule provider（§5） |

槽数为范围（`SlotCountSpec` 非定长）时，长度本身进入搜索：target provider 在
"继续扩展"与"收束结尾"间分支，`isComplete()` 判定改为"到达允许长度且模式均完成"。
v1 建议限制：仅 `LinePattern` 驱动的任务允许变长，上限槽数硬编码（如 12）。

## 5. 脚本谓词逃生舱

声明式覆盖不了的判定（罕见的教学性偏好、实验性约束）走谓词：

```kotlin
// :theory 只见接口，宿主实现在 desktop 脚本模块（GraalJS）
interface ScriptPredicateHost {
    fun evaluate(ref: PredicateRef, view: PredicateView): PredicateVerdict
    // PredicateVerdict = Pass / Fail(messageKey) / Score(delta, messageKey)
}
```

- `scope`：`SLOT`（纵向 frame）/ `TRANSITION`（相邻两 frame）/ `COMPLETE`（完整候选）。
  view 是只读 JSON 快照（音高、音级、和弦、声部角色），不暴露可变状态。
- **纯函数要求**：宿主对 (predicateId, view digest) 做 memo 缓存；脚本副作用不可见。
- 性能预算：beam 48 × 槽 8 × TRANSITION ≈ 数千次调用；GraalJS 单次跨界 µs 级 + memo
  后可行，但 manifest 中该约束标注 `costly = true`，UI 提示。`COMPLETE` scope 最便宜，
  应优先。
- 失败呈现：`Fail` → HARD finding `solver.constraint.predicate.<id>`，messageKey 由
  脚本给出——解释链与内置规则一致。

## 6. refine 语义（重解 + 锚定 + 相似度）

`RefineRequest`（solver-api §2.4）编译：

1. `pins` → `PitchAt`（即 `FixedPitch` 材料），锚定的音在所有候选中不变；
2. `addedConstraints` 与 `base.program` 合并（同槽 `ChordAt` 取交集；冲突 → 编译期诊断）；
3. 自动注入 `SimilarityTo(baseline, similarityWeight)`。

输出仍是 top-K：第一名通常是"最小改动可行解"，靠后的候选展示更大幅度的替代方案。
不实现真正的局部修复算法（v1 决策，见 solver-api §2.4）。

## 7. 测试约定

- 编译等价：旧 `ProgressionRequest`（含 spacing 偏好、policy 保底）编译为
  ConstraintProgram 后求解结果与现 runner 一致。
- LinePattern：
  - 半音下行低音金标准：C 大调 8 槽内解出 `8̂-♭7̂(♯6̂)-6̂-…` 类低音线，INDICATION 锚点连续；
  - hold 边界：持续 3 槽 + 换音在小节中途的组合；anchor 开窗与关窗各一例；
  - 不可满足模式（音域外的半音链）→ 无解诊断而非死循环。
- 变长任务：`SlotCountSpec` 范围求解终止性（上限槽数强制收束）。
- RuleAt 窗口化：同一规则"仅在结尾要求"与"全局要求"给出不同解集。
- 关系约束：AllDifferent 可剪掉重复和弦；AdjacentCommonTone 可剪掉无共同音或共同音未保持的相邻排布。
- refine：pin 保持；冲突约束（pin 与新 `ChordAt` 矛盾）→ 编译期诊断，不进搜索。
- 谓词：memo 生效（同 view 只调一次脚本）；脚本抛异常 → 该候选 Fail + 诊断，不崩溃求解。

## 8. 开放问题

- 统一模型（§2.1-2.4）剩余两则：① Not 的 witness 继续补足更精确的反向锚点；
  ② 深嵌套 expr 的 beam 内层求值成本——按 expr 结构 +
  目标身份 memo（同 `ChordRuleDispatcher` 缓存思路）。
- `MotiveAt` 的匹配语义（严格音高 / 允许移位 / 允许节奏增减值）与复调章节需求
  强相关，推迟到复调 texture 设计时一并定。
- 变长搜索与 diversity 的相互作用：不同长度候选的评分可比性（按槽均值 vs 总分）。
- 同槽多约束的冲突检测做到什么深度：v1 只查 `ChordAt` 交集为空与 pin 冲突，
  跨约束类型的不可满足仍靠求解无解 + 诊断兜底。
