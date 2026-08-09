# 规则目录与规则关系

> 归属模块：`:theory`（`com.mecon.theory.RuleCatalog` 等）。
> 消费方：探索模式规则选择器（[../exploration/ui-interaction.md](../exploration/ui-interaction.md) §1）、
> 请求编译器（[../exploration/document-model.md](../exploration/document-model.md) §5）、
> 未来的规则调度器与 LLM `explain_rule` 工具（[../ai/roadmap.md](../ai/roadmap.md) §7.2）。
>
> 当前实现：`RuleCatalog` 聚合 `RuleCatalogProvider`；已接入
> `textbook.RootPositionTriadRuleCatalog`、`textbook.FirstInversionTriadRuleCatalog` 与
> `textbook.SecondInversionTriadRuleCatalog`、`textbook.DominantSeventhRuleCatalog`。
> `RuleProfile.requirements` 与
> `FixedVoiceWritingCandidateSpace` 已支持 `REQUIRE_INDICATION`、`REQUIRE_VIOLATION`、`FORBID`。

## 1. 动机

现有 `RuleId` 只是字符串标识，规则的教学元信息（名称、解释、所属章节）与规则间关系
（从属、互斥、可共选）散落在实现与文档里。探索模式层级 1 需要用户**按目录勾选规则**并
由求解器实现所选写法，这要求：

1. 每条可教学的规则/连接模式有稳定 id、i18n 文案与元信息；
2. 规则间关系显式建模，UI 据此启用/禁用勾选项；
3. "要求某写法出现"与"要求某禁则被违反（错误示例）"成为求解器的一等输入。

## 2. RuleCatalog 模型

```kotlin
data class RuleDescriptor(
    val id: RuleId,
    val titleKey: String,                 // i18n key
    val descriptionKey: String,
    val kind: RuleKind,                   // 见下
    val parent: RuleId? = null,           // 从属：子规则只在父上下文中有意义
    val chapter: ChapterId,               // "textbook.root-position-triad" 等
    val selectable: Boolean,              // 可否作为层级 1 的目标写法勾选
    val demonstrableAsViolation: Boolean = false,  // 可否作错误示例目标
)

enum class RuleKind {
    GROUP,        // 纯组织节点，无检查函数（如"根音四五度关系"）
    PATTERN,      // 连接模式，正确写法 INDICATION（如共同音保持连接）
    CONSTRAINT,   // 禁则/配置要求，违反产生 VIOLATION（如升5→4、平行五度）
    TENDENCY,     // 倾向性/许可（如内声部导音可跳进）
}

data class RuleRelation(
    val kind: RelationKind,               // EXCLUSIVE_GROUP / REQUIRES / SUPPRESSES
    val ruleIds: List<RuleId>,
)

data class RuleExampleInputSpec(
    val ruleId: RuleId,
    val degreePairs: List<RuleDegreePair>,      // 由 applicability 推导，可被 override 收窄
    val defaultPair: RuleDegreePair,
    val keyMode: RuleKeyModeConstraint? = null, // 仅特殊规则需要，如大调 V→vi / 小调 V→VI
    val degreeQualities: Map<Int, ChordQuality> = emptyMap(), // 如小调 V 必须取大三和弦
    val companionRuleOptions: List<RuleId> = emptyList(), // 由 REQUIRES 推导
    val defaultDemonstrationRuleId: RuleId? = null,
)

object RuleCatalog {
    fun descriptor(id: RuleId): RuleDescriptor?
    fun children(id: RuleId): List<RuleDescriptor>
    fun chapter(id: ChapterId): List<RuleDescriptor>       // 目录树根
    fun exampleInputSpec(id: RuleId): RuleExampleInputSpec // 规则示例输入约束
    fun validateSelection(
        selected: List<RuleId>,
        context: SelectionContext,        // 层级 1：调式 + 前后和弦根音关系
    ): SelectionValidation                // OK / 违反互斥 / 缺父上下文 / 不适用于该和弦对
}
```

- 目录是**声明式注册表**：各章节 provider 在自身文件里注册 descriptor
  （映射逻辑放在最相关位置，与现有规范一致），`RuleCatalog` 只做聚合查询。
- `SUPPRESSES` 关系与现有 `RuleSuppression` 同源：目录声明静态关系，`RuleProfile`
  仍是运行时调解的执行者，避免两处语义分叉——目录生成默认 profile，章节 profile 可覆盖。
- `validateSelection` 的不适用判定复用 `RuleApplicability` 思路：如四五度模式对
  根音二度关系的和弦对返回"不适用"，UI 灰化并提示切换。
- `RuleExampleInputSpec` 属于 `RuleCatalog`：它不是 UI 控件定义，而是可复用的规则输入约束。
  普通规则的常用和弦对由 `applicability(ruleId, SelectionContext)` 自动推导；
  伴随规则由 `REQUIRES` 关系推导；只有调式专属、固定音级对、默认违规演示这类例外通过
  provider 的 `inputOverride(ruleId)` 维护。桌面 UI 只把这些约束渲染成 chips / toggle。
  例：小调"升 5→4"错误示例的 spec 固定 V→VI、锁定小调，并约束第 5 级为大三和弦，
  避免请求编译器误选自然小调 v。

## 3. 原位三和弦章节目录（首个实例）

现有 `RootPositionTriadRules` 的 RuleId 组织为如下目录树（GROUP 节点为新增虚拟 id）：

```
textbook.root-position-triad                       (chapter)
├── same-chord-repetition                          PATTERN
├── fourth-fifth                                   GROUP「根音四（五）度关系」
│   ├── fourth-fifth-common-tone                   PATTERN ─┐
│   ├── fourth-fifth-no-common-tone                PATTERN  ├ EXCLUSIVE_GROUP
│   ├── fourth-fifth-open-close-shift              PATTERN ─┘
│   └── inner-leading-tone-leap                    TENDENCY（可与上面任一共选）
├── third-sixth                                    GROUP
│   └── third-sixth-common-tones                   PATTERN
├── second-seventh                                 GROUP「根音二（七）度关系」
│   ├── second-seventh-contrary-smooth             PATTERN
│   ├── major-dominant-to-sixth-inner-leading-tone TENDENCY（大调 V→vi 7→6）
│   └── minor-raised-fifth-to-fourth               CONSTRAINT，demonstrableAsViolation=true
└── （纵向配置：non-chord-tone / missing-chord-tone / expect-root-doubling /
     leading-tone-doubled / final-tonic-spacing —— CONSTRAINT，selectable=false，
     由 exercise policy 控制，不进入层级 1 勾选）
```

关系要点（即用户描述的规则拆分）：

- 四（五）度关系细分三种互斥连接模式；**内声部导音跳进**从属于该组（REQUIRES parent），
  是许可型 TENDENCY，可叠加勾选。
- **升 5→4 禁则**从属于二（七）度关系组；正常求解中是 HARD 剪枝，
  错误示例中可作为演示目标（§5）。
- 跨章节规则（平行五度、旋律原则）不进本章目录，但仍参与求解约束；
  目录的 `chapter()` 查询决定选择器展示范围，不决定规则启用范围。
- 探索模式左侧展示可在教材章节外再加一层 UI 组织节点"调性和声"，形成
  `调性和声 / 原位三和弦连接 / 根音四(五)度关系 / 共同音保持` 的查找路径；
  该节点不注册为 `RuleId`，不参与规则关系和求解。

## 3.1 三和弦第一转位章节目录

`FirstInversionTriadRules` 的 RuleId 组织为如下目录树：

```
textbook.first-inversion-triad                     (chapter)
├── bass-line-enrichment                           PATTERN
├── verticality                                    GROUP
│   ├── diminished-triad-first-inversion           CONSTRAINT
│   ├── leading-tone-doubled                       CONSTRAINT，selectable=false
│   ├── non-chord-tone                             CONSTRAINT，selectable=false
│   └── missing-chord-tone                         CONSTRAINT，selectable=false
    └── major-dominant                                 GROUP
        └── major-root-dominant-to-minor-sixth         CONSTRAINT，demonstrableAsViolation=true
```

关系要点：

- 第一转位用于丰富低音线条；自由练习可把同一 slot 的候选设为原位或第一转位。
- 第一转位重复音可按音响效果选择，但不重复导音。
- 减三和弦第一转位既可产生正向 indication，也可在非第一转位时产生 HARD violation。
- 大调原位 V 后接 vi 小和弦是错误示例入口，spec 固定 V→vi、大调，并约束 V 为大三和弦、vi 为小三和弦。

## 3.2 三和弦第二转位章节目录

`SecondInversionTriadRules` 的 RuleId 组织为如下目录树：

```
textbook.second-inversion-triad                    (chapter)
├── standard-uses                                  GROUP
│   ├── cadential-six-four                         PATTERN
│   ├── passing-six-four                           PATTERN
│   ├── pedal-six-four                             PATTERN
│   └── same-chord-inversion-insertion             PATTERN
└── verticality                                    GROUP
    ├── decorative-use                             PATTERN，selectable=false
    ├── unsupported-second-inversion               CONSTRAINT，selectable=false
    ├── upper-voice-leap                           CONSTRAINT，selectable=false
    ├── expect-bass-doubling                       CONSTRAINT，selectable=false
    ├── leading-tone-doubled                       CONSTRAINT，selectable=false
    ├── non-chord-tone                             CONSTRAINT，selectable=false
    └── missing-chord-tone                         CONSTRAINT，selectable=false
```

关系要点：

- 四种标准用法互斥；规则示例会扩成至少三个和弦的上下文。
- `cadential-six-four` 的输入约束为 I→V，执行器生成 `I(46)-V-I`。
- `passing-six-four`、`pedal-six-four` 和 `same-chord-inversion-insertion` 由完整候选上的三和弦窗口识别。
- `unsupported-second-inversion` 只在完整候选上作为 HARD violation 补齐，避免搜索前缀还没形成上下文时被误剪。

## 3.3 属七和弦章节目录

`DominantSeventhRules` 的 RuleId 组织为如下目录树：

```
textbook.dominant-seventh                         (chapter)
├── general                                       GROUP
│   ├── seventh-resolves-down                     TENDENCY
│   ├── seventh-ascends                           CONSTRAINT，demonstrableAsViolation=true
│   ├── quality                                   CONSTRAINT
│   ├── minor-requires-leading-tone               CONSTRAINT，demonstrableAsViolation=true
│   └── outer-leading-tone-resolution             CONSTRAINT，demonstrableAsViolation=true
├── root-position                                 GROUP
│   ├── root-v7-i-omitted-fifth                   PATTERN
│   ├── root-v7-complete-i-parallel-fifth         CONSTRAINT，demonstrableAsViolation=true
│   ├── incomplete-v7-complete-i                  PATTERN
│   └── inner-leading-tone-complete-i             PATTERN
├── other-resolutions                             GROUP
│   └── deceptive-resolution                      PATTERN
├── inversions                                    GROUP
│   ├── inversion-tendency-tones                  TENDENCY
│   ├── second-inversion-passing                  PATTERN
│   └── third-inversion-to-i6                     PATTERN
└── preparation                                   GROUP
    ├── preparation-suspension                    PATTERN
    ├── preparation-passing                       PATTERN
    ├── preparation-neighbor                      PATTERN
    ├── preparation-appoggiatura                  PATTERN
    └── preparation-above-leap                    CONSTRAINT，demonstrableAsViolation=true
├── other-sevenths                                GROUP（II7、导七、通用省略准则）
└── circle-of-fifths                              GROUP（4-7-3-6-2-5-1 七和弦模进）
```

关系要点：

- 原位 `V7-I` 中几种目标形态互斥选择；完整 `I` 引发平行五度作为错误对照入口。
- 属七规则的 `RuleScene` 用于 solver-api manifest、输入约束与说明；出谱由
  `ExplorationRequestRunner` 分流到 `TextbookSeventhWritingSolver`，不通过三和弦场景实例化。
- 七音预备规则声明三和弦窗口，要求预备、属七和弦、解决同时出现。
- II7、导七和五度圈模进继续复用七和弦 solver；通用省略准则在纵向检查中执行。

## 4. RuleRequirement：把所选写法变成求解目标

`RuleProfile` 新增需求列表：

```kotlin
data class RuleRequirement(
    val ruleId: RuleId,
    val mode: RequirementMode,   // REQUIRE_INDICATION / REQUIRE_VIOLATION / FORBID
)
```

- `REQUIRE_INDICATION`：候选在对应 transition 上未产生该 PATTERN 的 indication finding
  → 视同 HARD 剪枝。生成与检查一致性原则不变：模式识别逻辑仍在规则本体，
  需求只消费其 finding，不另写第二份判定。
- `FORBID`：反向排除某写法（预留，如"给我一个不保持共同音的连接"用
  FORBID(common-tone) 比穷举其余模式更直观）。
- 执行位置：`FixedVoiceWritingCandidateSpace` 评分/剪枝阶段，紧随 profile 调解之后；
  `REQUIRE_INDICATION` / `REQUIRE_VIOLATION` 只在完整候选上检查，避免搜索前缀因还未形成
  transition 被误剪。
  TENDENCY 型需求（如要求演示内声部导音跳进）同样走 REQUIRE_INDICATION。

## 5. 错误示例（REQUIRE_VIOLATION）

用户想**听到**违规写法的效果（如小调 V→VI 中升 5 进行到 4 的增二度）：

- 编译器把 `DemonstrationSpec(ruleId)` 译为 `RuleRequirement(ruleId, REQUIRE_VIOLATION)`。
- 候选空间处理：
  1. 目标规则的 HARD/SOFT **剪枝与扣分被豁免**（finding 仍生成，降为标记）；
  2. 候选在对应位置未出现该规则的 VIOLATION finding → 剪枝；
  3. 其余规则照常约束——输出是"除演示目标外尽量规范"的写法，违规效果不被其他错误污染。
- 输出侧：该 finding 标记 `isDemonstrationTarget`，UI 置顶展示并说明这是被要求的演示
  （见 [../exploration/ui-interaction.md](../exploration/ui-interaction.md) §3）。
- `demonstrableAsViolation` 控制入口：只有教学上"值得听错"的 CONSTRAINT 开放
  （如 minor-raised-fifth-to-fourth、属七七音上行、外声部导音未解决、完整 `I` 平行五度等）。
- 属七章节会额外生成 `CellOutput.comparisonGroups`，把正确例与目标错误例成对标记，桌面页可据此提示“正确例 / 错误例”。

## 6. 测试约定

- 目录一致性：每个注册的 `RuleId` 必有 descriptor；EXCLUSIVE_GROUP 成员同父；
  REQUIRES 无环；`selectable` 的规则必有 i18n 文案。
- `validateSelection`：互斥冲突、缺父、根音关系不适用各至少一例。
- `REQUIRE_INDICATION`：能解出勾选模式；输出候选全部含该 indication；
  与和弦对不适用组合返回可解释的无解诊断。
- `REQUIRE_VIOLATION`：升 5→4 演示能解出；目标违规 finding 存在且标记演示目标；
  其余 finding 不含未豁免的 HARD。

## 7. 开放问题

- GROUP 虚拟节点是否需要自己的 `RuleApplicability`（按根音关系直接灰化整组）——
  倾向于是，由组内成员 applicability 聚合而来。
- 目录与未来规则调度器（ai/roadmap §10）关系：调度器按 applicability 自动选规则集，
  目录按章节组织人工选择；两者共享 descriptor，不重复注册。
- `RuleTag` 层级 suppression（writing-engine §9.4）落地后，目录 `SUPPRESSES`
  关系应改由 tag 推导，减少成对维护。
