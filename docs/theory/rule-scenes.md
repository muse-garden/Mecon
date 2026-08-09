# 规则适用场景模型（Rule Scenes）

> 状态：S0 已落地 MAY（符号级）—— `RuleScene` / `SceneFacet`（`RuleScene.kt`）+ `SceneMatcher`
> 逐槽 DFS 增量剪枝（`SceneMatcher.kt`）+ 三和弦三章场景声明；CONFIRMED（跑小规模 voicing 验证）🚧 未实现。
> 七和弦章（P1）已加词汇表维度：`RuleScene.chordArity` + `SlotChordSpec.seventhPositions/arity` +
> `DoublingExpectation` facet + `SceneMatcher.instantiateSeventh`，runner 的属七硬编码槽位已全删（见 §8）。
> 七和弦的**符号级 `enumerate`**已接入 arity-aware `ChordVocabulary`，可返回 `SEVENTH` 槽与七和弦章节里的
> `TRIAD` 解决槽；七和弦谱例的出谱仍走 `solve`/`instantiateSeventh`。
> 归属 `:theory`。消费方：[solver-api.md](solver-api.md) 的 `enumerate` 入口与 FormSpec 取值域推导。
> 前置：[rule-catalog.md](rule-catalog.md)（目录与关系）· [writing-engine.md](writing-engine.md) §5（RuleApplicability）。

## 1. 动机

"规则适用于什么场景"目前散落三处，且都不可被引擎查询：

1. `RuleCatalog.inferDegreePairs` 用一张人工代表和弦对表反查 applicability；
2. `ExplorationRequestRunner.secondInversionRuleExampleDegrees / *Slots /
   firstInversionRuleExampleSlots` 把每条规则的示例上下文硬编码成 Kotlin 分支；
3. `RuleExampleInputOverride` 手工维护调式/音级质量约束。

目标：每条规则**声明**自己的适用场景（可查询数据），场景匹配引擎在和弦词汇表上
枚举出适用的符号级进行——"引擎应当能找出哪些进行可能包含 #5→4"由此实现。

## 2. 场景模型

一条规则可声明多个场景（析取）；一个场景由若干 facet 合取而成：

```kotlin
data class RuleScene(
    val ruleId: RuleId,
    val window: IntRange,                // 进行槽数（终止四六 = 3..3；连接规则 = 2..2）
    val facets: List<SceneFacet>,        // 全部满足才算命中
    val role: SceneRole = SceneRole.EXAMPLE,  // EXAMPLE / DEMONSTRATION（错误示例可用不同场景）
    val unavailableReason: String? = null,    // 不适用当前上下文时给 UI 的说明（替代旧 applicability reason）
)

sealed interface SceneFacet
```

### 2.1 Facet 分类

| Facet | 表达内容 | 例 |
|-------|---------|----|
| `ChordPattern` | 具体和弦/音级序列（含通配、槽间引用） | 终止四六 `I⁶₄ → V → I`；持续音四六"前后同和弦" |
| `ChordQuality` | 和弦性质集合（槽位级或全局） | 减三和弦（→ 第一转位规则）；小调 V 须大三 |
| `BassPosition` | 转位/低音位置 | 原位规则要求低音=根音；四六规则要求第二转位 |
| `VoiceMotion` | 和弦内音走向（pitch-class 级） | #5→4 禁忌；经过四六"三音、五音上行" |
| `BassMotion` | 低音线条形态 | 经过四六低音级进穿过；持续音四六低音保持 |
| `IntervalRelation` | 纵向/横向音程关系 | 平五平八；增二度旋律进行 |
| `DoublingExpectation` | 重复音要求 | 四六优先重复低音（五音）；不重复导音 |
| `SpacingChange` | 排列及其变化 | 开放↔密集转换连接 |
| `KeyContext` | 调性上下文 | 仅大调 / 仅小调；要求升 4、升 5 来源 |
| `MetricPosition` 🚧 | 节拍位置 | 终止四六在强拍，经过四六在弱拍。设计已定：[figuration.md](figuration.md) §2 `MeterPlan` / §7 |
| `PhrasePosition` 🚧 | 乐句位置 | 终止式在句尾；开头主功能 |
| `NonChordTone` 🚧 | 和弦外音类型 | 经过音 / 邻音 / 延留音 / 先现音等八类。设计已定：[figuration.md](figuration.md) §7（合取骨架 `ChordPattern`，instantiate 产出 `WritingTaskPlan`） |
| `Texture` 🚧 | 织体/任务类型 | 四部固定声部 / 复调 species / 声部数 |
| `Register` 🚧 | 音域/音区 | 声部音域、交错区域相关规则 |
| `KeyPlan` 🚧 | 离调/转调 | 副属和弦、转调章节的目标调关系 |

已实现的分支：`ChordPattern` / `KeyContext` / `RootMotion` / `VoiceMotion` / `BassMotion`
（`BassMotion` 含 `PASSING_STEP` / `PEDAL_HELD`）+ `DoublingExpectation`（P1 新增，本期只表达七和弦
某槽五音完整性 REQUIRE/OMIT，供 `instantiateSeventh` 落成 `fifthConstraint`；voicing 级属性，对 MAY
枚举恒真）。`IntervalRelation` / `SpacingChange` 与 🚧 项仍未建分支——新增章节（外音、变化音和弦、
转调、复调）按"先加 facet 类型、再加词汇表维度"的顺序扩展，场景模型本身不动。

### 2.2 关键 facet 细化

```kotlin
data class ChordPattern(val slots: List<SlotChordSpec>) : SceneFacet

data class SlotChordSpec(
    val degrees: Set<Int>? = null,                 // null = 任意
    val qualities: Set<ChordQuality>? = null,
    val positions: Set<TextbookTriadPosition>? = null,
    val sameChordAsSlot: Int? = null,              // 槽间引用：与第 i 槽同和弦（持续音四六）
)

data class VoiceMotion(
    val from: ScaleDegreeSpec,                     // 音级 + 变化（RAISED / LOWERED / NATURAL）
    val to: ScaleDegreeSpec,
    val voiceScope: VoiceScope = VoiceScope.ANY,   // ANY / BASS / UPPER / 指定声部
    val direction: MotionDirection? = null,        // ASCENDING / DESCENDING / null
) : SceneFacet

data class ScaleDegreeSpec(val degree: Int, val alteration: DegreeAlteration = NATURAL)

// 关系型 facet（S0 新增）：不落到单槽，而是约束相邻槽的关系。fromSlot 指第一个参与槽。
data class RootMotion(val steps: Set<Int>, val fromSlot: Int = 0) : SceneFacet   // 根音最小音级距离 0..3
data class BassMotion(val kind: BassMotionKind, val fromSlot: Int = 0) : SceneFacet
enum class BassMotionKind { PASSING_STEP, PEDAL_HELD }
```

- `VoiceMotion` 的符号级判定是**必要条件**：`from` 音级属于第 i 槽和弦音、`to` 属于
  第 i+1 槽和弦音，即标记"可能包含"。是否真的会出现在声部进行中，由验证级确认（§4）。
- `RootMotion` 用 `degreeDistance(a,b)=min(up,7-up), up=(b-a).mod(7)`（与 `SelectionContext.degreeDistance`
  同式）替代根位连接规则原先按 `degreeDistance` 的分支；根位连接规则不再枚举代表和弦对。
- `BassMotion.PASSING_STEP` 判定三槽低音**同向的自然音阶级进**（相邻各差一个音阶度，不要求等半音差），
  故大三度跨度（C-D-E）与小三度跨度（D-E-F，半音差 2/1）都成立。配合首尾同和弦，C 大调每个框架和弦恰得
  一条经过四六，共 7 条（`I-V⁶₄-I⁶ / IV-I⁶₄-IV⁶ / V-ii⁶₄-V⁶ / ii-vi⁶₄-ii⁶ / iii-vii°⁶₄-iii⁶ /
  vi-iii⁶₄-vi⁶ / vii°-IV⁶₄-vii°⁶`），远比 `RootMotion∈{1,2,3}` 松散全集收窄。`PEDAL_HELD` 判定三槽低音音级保持不变。

## 3. 场景匹配引擎（SceneMatcher）

```kotlin
object SceneMatcher {
    // 枚举适用符号进行（逐槽 DFS + 增量剪枝）。
    fun enumerate(rule: RuleScene, key: Key, vocabulary: ChordVocabulary, windowLimit: Int = 3): List<SymbolicMatch>
    // CONFIRMED 占位（S0 未实现）：暂等价 MAY，直接返回 enumerate。
    fun verify(rule, key, vocabulary, windowLimit, level: VerifyLevel): List<SymbolicMatch>
    // window-2 投影，供 RuleCatalog.applicability 委托：把音级相关约束投到 (from,to)；无音级约束的场景恒真。
    fun appliesInContext(scenes: List<RuleScene>, fromDegree: Int, toDegree: Int): Boolean
    // 把场景 ChordPattern 落成写作槽供 runner 出谱例：degrees 单值 / sameChordAsSlot 引用 / 由 (from,to) 填充。
    fun instantiate(scene: RuleScene, key: Key, fromDegree: Int, toDegree: Int): List<TextbookTriadWritingSlot>
}
```

- `appliesInContext` 是 window-2 快捷判定（`SelectionContext` 投影），只覆盖两和弦连接规则；三槽以上
  场景（四六）投影会过松（`RootMotion∈{1,2,3}` 近乎全集），故消费方应改用 `enumerate` 取实际进行——
  探索页对 window≥3 规则即改用枚举进行选择器，不再用 from/to 投影（见 §5）。

- **词汇表**：`ChordVocabulary` = 调内自然三和弦 × 三和弦转位，或七和弦章节的
  `DominantSeventhRules.seventhChordInKey` / `triadInKey` × 七和弦转位；槽位由
  `RuleScene.chordArity` 与 `SlotChordSpec.arity` 决定。未来扩展为变化音和弦、
  副属和弦——词汇表是数据，匹配算法不变。
- **搜索**（S0 实现）：逐槽 DFS + 增量剪枝——每放一个 `(chord × position)` 就对前缀跑「已可判定」的
  facet 检查（`facetPrefixAlive`），死前缀立即回溯，不穷举完再过滤。约束只后向引用
  （`sameChordAsSlot` 指已放槽）以保证可增量判定。窗口通常 ≤ 3 槽；七和弦五度圈等长窗口由
  `windowLimit` 控制预算。
- **绑定说明**：每个命中附 `SceneBindingNote`（哪个 facet 绑定到哪个槽/哪个和弦音），
  即 `SymbolicProgression.explanation` 的来源——"该进行第 1 槽 V 含 #5、第 2 槽 VI 含 4"。

## 4. 两级验证与一致性闭环

场景是**索引，不是判定**。writing-engine §3 的原则（生成阶段收窄必须与检查阶段
finding 一致）在这里体现为：

- **MAY**（符号级）：facet 必要条件全部满足。用于 FormSpec 取值域、UI 灰化——快、
  可能有假阳性。
- **CONFIRMED**（验证级）：对该符号进行跑一次小规模 voicing 求解
  （`beamWidth` 缩小、`maxResults = 1`、注入 `REQUIRE_INDICATION` / `REQUIRE_VIOLATION`），
  规则本体真的产生目标 finding 才确认。enumerate 请求可选验证级；结果缓存
  （key + policy + rulesVersion 为键）。

假阳性举例：#5→4 的 MAY 匹配会给出所有含升 5 和弦 → 含 4 和弦的组合，但某组合可能
因其他 HARD 规则（如导音重复）根本无合法 voicing——CONFIRMED 级将其过滤，并可在
UI 中说明"符号上适用但当前策略下无解"。

## 5. 现有硬编码迁移清单

| 现状 | 迁移后 |
|------|--------|
| `RuleCatalog.inferDegreePairs` + `representativeDegreePairs` | **S0**：仍过滤 `representativeDegreePairs`，但判据 `applicability` 已改场景驱动。**S1**：`SolverApi.enumerate` 已作为取值域来源暴露（`SolverEngine.enumerate`，含 window≥3 场景）；通用 FormSpec 渲染器已消费字段，窗口=2 的 `DEGREE_PAIR` 取值域仍沿用 `RuleExampleInputSpec`，后续再改为 enumerate 推导 |
| `RuleExampleInputOverride.degreePairs / keyMode / degreeQualities` | `KeyContext` / `ChordQuality` facet；override 仅保留 UI 默认值 |
| `secondInversionRuleExampleDegrees`（终止/持续/同和弦分支） | 各规则的 `ChordPattern`（含 `sameChordAsSlot`） |
| `secondInversionRuleExampleSlots` / `firstInversionRuleExampleSlots` | `ChordPattern.positions` 直接给出槽位允许转位 |
| runner 章节分发（`usesFirstInversionChapter` 等） | 场景命中即知窗口与槽位约束，runner 只做"符号进行 → SolveRequest" |
| `RuleCatalogProvider.applicability(SelectionContext)` | provider 不再各自实现；`RuleCatalog.applicability` 委托 `SceneMatcher.appliesInContext`（`ChordPattern` + `RootMotion` 的 window-2 投影），无场景则恒 `APPLIES` |

`SelectionContext`（fromDegree/toDegree 二元组）不再是场景的根模型——它只是窗口=2
连接规则的投影；三槽以上场景（四六、未来终止式）直接以 `SymbolicProgression` 交互。

## 6. 测试约定

- 声明完备：每个 `selectable` 或 `demonstrableAsViolation` 的规则至少声明一个场景；
  场景引用的槽位索引在窗口内；`sameChordAsSlot` 无自引用/前向引用。
- 枚举金标准（对照教材）：
  - `cadential-six-four` → 含 `I⁶₄-V-I`；`passing-six-four` → 低音级进三槽组合；
  - `minor-raised-fifth-to-fourth`（DEMONSTRATION 场景）→ 小调 V→VI 组合被命中；
  - `fourth-fifth-common-tone` → MAY 结果与现 `applicability` 判定一致（迁移等价性）。
- MAY ⊇ CONFIRMED：验证级结果必为符号级子集；CONFIRMED 的每条进行 solve 必出目标 finding。
- 词汇表扩展回归：往词汇表加一个转位后，旧场景枚举结果只增不变（单调性）。
- 七和弦金标准（P1）：`SeventhSceneInstantiationTest` 断言教材进行由 `instantiateSeventh` 产出
  （V7-I、V42-I6、II7-I⁶₄-V7-I、五度圈一/三转位交替、原位完全/省五交替）；`SolverApiTest` 断言便捷
  请求求解出候选并命中目标 finding（迁移等价性），并断言 `enumerate` 返回 V7-I 的
  `SEVENTH → TRIAD` 符号槽。

## 7. 开放问题

- `BassMotion.PASSING_STEP` 现按自然音阶级进判定，但**只认自然音阶成员**：小调的升音低音（如 A 小调升 7 的
  G♯）落不到 `key.scale.pitchClasses`，相关经过会被漏判。放开需引入"音阶度低音"模型（区分自然/变化音级），
  与 `MetricPosition` 等 🚧 facet 同属"先加词汇表维度"的扩展。此外框架和弦目前不限性质，会枚举出
  `vii°` 原位/一转位作框架的经过（`vii°-IV⁶₄-vii°⁶`），是否按和弦性质收窄留作后续。
- CONFIRMED 验证的预算控制：进行数 × 小规模求解的耗时上限，超限时降级返回 MAY 并标注。
- ~~`MetricPosition` 依赖把槽位映射到拍点~~——拍位语义设计已定：`WritingTimeline.meter: MeterPlan?`
  （[figuration.md](figuration.md) §2，F0 增量），`meter = null` 保持现状。
- 场景与 `RuleApplicability`（写作检查路径）的长期关系：检查路径遇到不适用上下文时，
  能否直接引用场景数据给出"建议切换的规则集"，替代手写 `suggestedRuleSet` 字符串。

## 8. 新规则接入指导（避免回退 runner 硬编码）

> **红线**：新规则**只**增加「规则实现 + 场景声明 + 目录注册」三处，**不得**在
> `ExplorationRequestRunner` 里加按规则/章节的分支或 `*_RULES` 集合常量。P1 之所以要偿还，正是
> 属七章曾在 runner 塞进约 350 行 `dominantSeventhSlots` / `circleOfFifthsSlots` 式槽位扩展——
> 谱例形态属于**规则声明的数据**，不属于 runner 的控制流。

接入一条新规则的步骤：

1. **规则实现**：在对应 `*Rules` 里写 `checkVertical/Transition/Score`，产出 `INDICATION`（正例）
   或 `VIOLATION`（错误示例）finding。
2. **场景声明**：在该章 `RuleCatalogProvider.scenes(ruleId)` 返回 `RuleScene`——
   - `window` = 进行槽数；`ChordPattern` 逐槽用 `SlotChordSpec` 给 `degrees` /（三和弦）`positions` /
     （七和弦）`seventhPositions` / `qualities`；槽间同和弦用 `sameChordAsSlot`（只后向引用）。
   - 调式限制用 `KeyContext`；错误示例把 `role` 设 `DEMONSTRATION`。
   - **和弦规模**：七和弦进行把 `RuleScene.chordArity = SEVENTH`；其中的三和弦解决和弦
     （I / VI / 终止四六 I⁶₄）在该槽 `SlotChordSpec.arity = TRIAD`。
   - **五音完整性/重复音**：用 `DoublingExpectation(slot, fifth)` facet，别在 runner 里按索引 `copy`。
   - 生成期收窄必须与 checkScore 判定一致（writing-engine §3）——五度圈交替形态就是靠槽位
     `fifthConstraint` / 转位收窄，而非末端过滤（见本轮 bug）。
3. **目录注册**：`descriptors` 加节点（`selectable` / `demonstrableAsViolation`），必要时加 `relations`。
4. **金标准测试**：断言 `enumerate`（三和弦）或 `instantiateSeventh`（七和弦）产出教材进行，
   且 `solve` 命中目标 finding。

runner 只按 arity 通用分发（`instantiate` / `instantiateSeventh` → 对应求解器）。若引入**新的和弦族**
（九和弦、变化音和弦等），做法是「加 `ChordArity` 取值 + 词汇表 + 求解器 + `instantiate*`」，仍不在 runner 里
按规则分支。
