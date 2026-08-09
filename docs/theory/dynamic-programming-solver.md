# 分层动态规划求解器

> 状态（2026-08-04）：DP 的状态正确性与预算语义已按审计意见修正。能力集的唯一通用门槛是
> **逐槽已选定的固定和弦目标**；和弦类型限制现在只属于 `FREE_*`（§1）。勋伯格「先枚举出固定
> 进行、再逐条求解」的主流路径已进入能力集，含七和弦 / 副属 / 减七 / 增六。仍必须显式选择
> `LAYERED_DP`。转移条数与每条转移的单价都已优化（§7），终层已改为 branch-and-bound（§8）。
> **在同等分值下 DP 已优于 DFS**（§9）：DP 能达到 DFS 在任何候选池深度都达不到的分值，且到达
> 同一分值更快。`AUTO` 仍保持 `GREEDY_DFS`——尚未在开放域与勋伯格形态上扫出 (分值, 耗时) 曲线，
> 中间层合并率也仍只有 21%。
>
> 前置：[free-harmony-solver.md](free-harmony-solver.md) ·
> [constraint-program.md](constraint-program.md) ·
> [free-practice-window-voicing.md](free-practice-window-voicing.md)

## 1. 当前边界

固定和弦进行可视为分层 DAG：每层是当前和弦的声部排列，边连接相邻排列。DP 只负责排列实现，
不选择符号和弦；调用方必须已经为每个槽选好唯一 `ChordTarget`。

能力审计（`LayeredDpStatePlanner.collect`）分为两段。**程序级**（对所有 preset 生效）：

- 每槽唯一目标——这是唯一的通用门槛，开放和弦域一律 fail closed；
- `ruleModules` 显式为空，不启用派生 textbook 规则；
- 没有尚未注册的 `RuleProfile.requirements` 或谓词；
- 每条启用规则都有已审计的 DP 状态声明。

**preset 级**：

| preset | 额外条件 | provider |
|---|---|---|
| `FREE_CLASSICAL` / `FREE_JAZZ` | 目标须为当前调内的**自然三和弦** | `FreeHarmonyRuleProvider` |
| `SCHOENBERG_GENERAL` | 无——**任意和弦类型** | `FourPartTextbookWritingRuleProvider` |
| `NONE` | 无 | 无 |
| `TEXTBOOK` | 未注册，fail closed | — |

自然三和弦这条不是通用限制，而是 `FreeHarmonyRuleProvider` 三条**目标敏感**规则
（`ROOTLESS_DIMINISHED_ROOT` / `ROOTLESS_DIMINISHED_ALTERED_STEP` / `DISSONANCE_RELEASE`）的代理
条件：它们没有状态声明，只能靠和弦类型排除在外。`SCHOENBERG_GENERAL` 装的 provider 全部规则只读
音高、音程与音域，与和弦类型无关，故不需要这条限制——七和弦、副属、减七、增六、无根属九全部
可进 DP。

显式请求不支持的 DP 返回 `ConstraintSolveOutcome.Invalid`；列表式 API 抛出参数错误，不再把“不支持”
伪装成空结果。`AUTO` 会记录原因并回退 DFS。

### 1.1 勋伯格的实用范围

判据只有一条：**该练习是否在求解前把符号进行定死**。

- **在能力集内**：descriptor 标了 `requiresEnumeratedProgression = true` 的练习。
  `SchoenbergExplorationRequestRunner` 先 `enumerate` 出进行，再为每条进行编译一个
  `progression != null` 的程序逐条求解——每槽只有一个目标。独立章节
  （`SchoenbergSecondInversionChapter` / `SchoenbergSeventhChordChapter`）内部也会
  `enumerate(key).first()`，同样落在集内。
- **在能力集外**：`SchoenbergRootPositionConnections`、`SchoenbergModulation` /
  `SchoenbergDistantModulationChapter`，以及禁忌表探测器——这些是真正的开放和弦域
  （求解器自己选和弦），继续走 `GreedyDepthFirstSolver`。

和弦外音义务与 textbook 模块仍未进入能力集。

## 2. 状态由规则自动收集

状态计划由 `LayeredDpStatePlanner.collect(program)` 在求解前编译。它读取实际和弦类型、preset、
`RuleProfile` 开关和所有约束，只收集当前任务真正启用的状态量。provider 规则在 RuleId 旁声明
`LayeredDpRuleStateDeclaration`；约束代数谓词由 planner 的穷尽 `when` 审计。未知项 fail closed。

每条规则声明的是“完成当前层后，为将来裁决还需保存什么”，而不是它读取过多少帧：

| 状态需求 | key 中保存的量 | 例子 |
|---|---|---|
| 无未来状态 | 无 | 纵向完整性、交错、固定目标规则 |
| `RecentFrames(1)` | 最近一帧各声部 MIDI | 相邻运动、平五八、倾向音 |
| `RecentFrames(2)` | 最近两帧各声部 MIDI | 连续两次同向跳进 |
| `VoiceExtreme` | 指定声部当前极值及出现次数 | 最高/最低点唯一 |
| `TerminalRerank` | 不进等价 key；终局完整评分 | 短旋律模式反复 |

例如“三帧连续跳进”在第三帧加入时已经裁决了前三帧；为了下一次裁决，只需在该层后保留最近
两帧。最后一层没有未来，最近帧数自动降为 0。四槽默认计划是 `[1, 2, 2, 0]`；关闭
`free.melody.consecutive-leaps` 后自动缩为 `[1, 1, 1, 0]`。

固定和弦序列下，目标规则对所有声部路径的结果相同，因此 key 不保存 target history、音级对 bitset
或完整路径。`DpStateKey` 目前仅含逐层计划要求的最近 MIDI 帧和有限极值摘要；完整路径只留在 label
中用于结果恢复，不参与等价性比较。

## 3. 当前覆盖的规则

### 3.1 自由写作 provider

| 依赖 | 已覆盖规则 |
|---|---|
| 单槽 | 相邻声部同音、外/内声部交错、音域边缘余量、窗口相邻间距、baseline 改动成本 |
| 相邻槽 | 声部移动成本、别扭跳进、运动拥挤、多个声部同时大跳 |
| 古典相邻槽 | 平行纯音程、隐伏纯音程、倾向音 |
| 三槽 | 连续两次同向跳进的轮廓 |

DP 扩展时把垂直规则按“层候选帧”缓存，只计算一次，不再对每条入边重复计算。窗口相邻间距和
多声部同时大跳另有共享的纯判定：只有规则启用、最终严重度仍是 `HARD`、且不存在可能隐藏它的
suppression 时才提前剪枝；规则被降为 `SOFT` 或可被 suppression 覆盖时，仍进入完整规则管线。

DFS 与 DP 还共用一套不进入乐理评分的字典序搜索优先级：完整和弦、内声部小于五度且高音不大于
五度为严格层；之后依次放宽为合法省略一个音、内声部五度、高音六度，其他更大跳进保留为最终
兜底。有限候选池预选时，同层内先减少非级进内声部，再比较移动量；进入搜索后同层仍以完整
`ScoreBreakdown` 排序，不能越过相邻声部同音等规则 finding。三和弦只能省五音；七和弦只能省三音或五音，
且最多省一个，这是候选生成的硬边界，不随搜索层放宽。

自然三和弦子集不会触发的 `ROOTLESS_DIMINISHED_*` 和 `DISSONANCE_RELEASE` 没有冒充覆盖。
爵士 preset 不收集古典平五八、隐伏和倾向音状态，因为这些规则本来就未启用。

### 3.1.1 勋伯格 general provider

`SCHOENBERG_GENERAL` 只装 `FourPartTextbookWritingRuleProvider`，规则面就是
`FourPartTextbookRules` 的 8 条 + `AdjacentVoiceUnisonRule`。**这一面比 `FREE_CLASSICAL` 更简单**：
没有任何规则需要两帧，也没有终局重排，因此逐层计划恒为 `[1, 1, …, 1, 0]`。

| 依赖 | 规则 |
|---|---|
| 单槽（纵向） | 外声部交错、上方声部间距、声部音域、相邻声部同音 |
| 相邻槽（`RecentFrames(1)`） | 平行五度 / 八度、不等五度、隐伏五度 / 八度 |

全部规则只读 `transition.pairMotions` 或当前 verticality，与 `ChordTarget` 无关——这正是勋伯格
不需要自然三和弦审计的原因。声明挂在 `FourPartTextbookRules.dpStateDeclarations()`，与 RuleId
常量同处一地。

### 3.2 约束代数

| 处理 | 已覆盖谓词 |
|---|---|
| 单槽/固定目标，无未来状态 | `ToneCompleteness`、`ToneDoubled`、`ToneNotDoubled`、`ScaleDegreeNotDoubled`、`Spacing`、`ToneMultiplicity`、`ToneInVoiceFilter`、`DistinctIdentities`、`TargetMatches`、`SameSonority`、`RootDiatonicMotion` |
| 固定目标整段规则，无声部状态 | `MinimumSimilarChordDistance`、`DistinctSimilarChordProgressions`、`RootProgressionPreference` |
| 最近一帧 | `CommonToneWithPrevious`、`NeighborTone`、相邻槽 `VoiceDiatonicSteps` |
| 有限摘要 | `UniqueVoiceExtreme` |
| 有界终局重排 | `NoRepeatedVoicePattern` |

**合成式（`And` / `Or` / `Not`）不再一刀切拒绝**：planner 遍历 `expr.atomicPredicates()`，对每个原子
跑同一套 `when`，整条约束取最强状态需求（求值 `Not(p)` 需要的帧与 `p` 相同，`And`/`Or` 需要各支的
并集）。任一原子被拒则整条约束被拒；单 `Atom` 是一个原子的特例。这解锁了勋伯格的两处合成式：
根音进行下行补偿的 `Or`/`Not`（全是目标域 `RootDiatonicMotion`）与四六和弦准备的
`Or`（两支都是 `NeighborTone`）。

唯一的例外是 `UniqueVoiceExtreme`：它的 key 槽以**约束下标**为身份（求解器还用同一下标回查
`constraint.scope`），同一条约束里放多个会静默合并成一个槽，因此与其他原子共处一条约束时
fail closed。

`VoicePitchClassCardinality`、非相邻 `VoiceDiatonicSteps`、`RuleFound` 仍明确拒绝。
这份列表表示求解器不会漏掉这些规则，并不表示所有组合都已证明全局最优。

## 4. EXACT 与 BOUNDED

`EXACT` 仅在候选未截断、前沿未超上限且所有规则都有精确状态时成立：

- 候选超过 `maxCandidatesPerTarget` 或状态超过 `maxFrontierStates` 时返回 `BudgetExhausted`；
- 边计算另受 `maxTransitionEvaluations` 限制，避免把昂贵边工作误算成少量前沿节点；
- 存在 `TerminalRerank` 规则时拒绝 EXACT，不能把近似结果称为精确结果。

`BOUNDED` 可以截断候选、每前驱出边、状态和同状态 labels，并在终局按完整规则重排。每目标
出边宽度为 `min(candidateLimit, max(8, 4 × maxResults))`；先按上述放宽层排序，因此首解任务通常
只评估每状态 8 条最平顺边。trace 分别报告 `candidateLayersTruncated`、
`transitionCandidatesTruncated`、`frontierTruncated`、`equivalentLabelsTruncated` 和
`boundedGlobalRerank`。这是一种受控近似，不保证全局最优；`EXACT` 不做出边截断。

全局原子规则只在完整进行上评分，避免把尚未完成的唯一极值或反复模式误当作前缀代价。极值摘要
仍需进入中间层 key，因为不同前缀对未来终局结果可能不同。

## 5. Trace 与结果语义

每层 `LAYER_COMPLETED` 记录：

- `generatedLabels`：hard 过滤后的路径标签数；
- `distinctStates`：截断前的状态身份数；
- `retainedLabels`：进入下一层的标签数；
- `evaluatedTransitions`：通过廉价窗口硬剪枝后、实际进入关系检查和增量评分的累计边数；
- `transitionTierCounts` / `acceptedTransitionTierCounts`：本层五档放宽边的评估数与存活数，顺序为
  严格、合法省略、内声部五度、高音六度、更宽跳进。

trace 级还有 `terminalGlobalEvaluations`：终层实际展开全局规则的完整路径数（§8）。终层的
`generatedLabels` / `acceptedTransitionTierCounts` 现在统计的是**补全局分之前**接受的转移，
因此可能包含最终被全局硬规则否掉的路径；被 branch-and-bound 跳过的路径本来也进不了 top-k。

trace 还携带逐层状态计划、已覆盖规则、终局重排规则和独立 transition budget。容量很小时优先保留
`SOLUTION`、`BUDGET_EXHAUSTED`、`CANCELLED` 等结论事件；`maxEntries=0` 不分配条目。取消、预算
耗尽、无解和能力不支持维持不同的 outcome。

## 6. 正确性验证

现有回归覆盖：

1. 真实 SATB 两槽多候选域：EXACT 与手工穷举的最低总分和 findings 一致；
2. 窄 SATB 三槽多候选域：包含 `RecentFrames(2)` 与极值摘要，EXACT 与穷举一致；
3. 关闭三槽规则后逐层状态计划自动缩小；
4. 固定目标规则不污染声部状态；未知状态谓词拒绝 DP；
5. open domain 与 textbook 显式 DP 仍 fail closed；`FREE_CLASSICAL` 下的七和弦被拒，
   **同一个七和弦程序换到 `SCHOENBERG_GENERAL` 就被接受**；
6. left boundary 在 DFS 与 DP 下语义一致；
7. spacing 被 profile 降为 `SOFT` 时不会误做提前剪枝，DP 与 DFS 的完整 breakdown 一致；
8. 分块追加 finding 与一次性 `applyProfile` 在 suppression 链上等价；
9. EXACT 的候选/状态上限、独立边预算以及优先 trace 事件有明确语义；
10. 增量路径优先级（`extendPathPriority`）与整段 `pathPriority` 在含全部放宽层的路径上逐分量相等；
11. 终层 branch-and-bound（§8）在终层全局规则全开的窄域上与穷举同分，且展开次数严格少于终层
    接受的转移数；
12. DFS 与 DP 的 (分值, 耗时) 曲线（§9）：DFS 质量随候选池到顶后不再改善，DP 放宽出边宽度后
    严格优于 DFS 的质量上限；
13. 勋伯格（`SchoenbergLayeredDynamicProgrammingTest`）：开放域整合练习仍 fail closed 且 `AUTO`
    带原因回退；固定进行的整合练习被接受且逐层计划为 `[1,…,1,0]`；**含七和弦**的固定进行上
    EXACT DP 不劣于 DFS（实测更优）且最优解无硬违规；根音进行章节的合成式约束进入能力集；
14. 状态声明完整性守卫（`LayeredDpStateDeclarationCompletenessTest`）：每个 provider 的
    `ALL_RULE_IDS` 减去已声明规则后必须为空，仅允许 `FREE_*` 自然三和弦子集内不可达的三条豁免。
    新增 RuleId 而忘记声明即测试红。

## 7. 每条转移的成本

转移**条数**已由放宽层排序和每前驱出边上限压到 1,864 条；本节记录的是**每条转移的单价**。
基准同上：C 大调 `I-V-vi-iii-IV-I-IV-V-I`、标准 SATB、每目标最多 128 个候选、有界前沿 32、
`maxResults=1`；计时改为预热 6 次后取 5 次采样的最小值（单次计时被 JIT 与 GC 支配，不可比）。

层内每个候选帧的常量（各声部 MIDI、省略音数、纵向跨度、tie-break key、纵向 finding、合成事件
verticality）只算一次；标签则携带路径优先级、前一帧摘要与前一帧 verticality。由此消除的重复
工作：

| 位置 | 此前 | 现在 |
|---|---|---|
| 出边排序 | 每个前驱对整层做 `O(L log L)` 排序，比较器内重算省略音集合并拼 tie-break 字符串 | 每帧一次常量 + 每 (标签,帧) 一次整型优先级；有界模式只选前 K 条，不排整层 |
| 路径优先级 | 比较器每次比较都对整条路径重算 `pathPriority` | 标签创建时按放宽层增量扩展（`extendPathPriority`，与整段重算等价并有回归测试） |
| 等价标签插入 | 每次接受都新建比较器并整表重排 | 层内共享比较器 + 有序插入；diversity key 惰性构造并缓存 |
| 转移 finding | 每条边为前后两帧各构造一次 verticality（8 个合成事件 + EventId 字符串） | 前一帧取自标签、当前帧取自层缓存 |
| 增量评分 | 每条边对全部可见 finding 重新求和、重新扫描硬违规 | 按新增/移除增量维护总分与硬违规计数；无抑制链时走快路径 |
| 约束代数 | 每次 `checkScore` 用协程 `Sequence` 重新给约束分区，并把整段目标序列拼成字符串缓存键 | 分区在编译期一次成型，缓存键直接用目标序列 |
| 自由写作规则 | 每条边重建倾向音表、张力音集合、方向权重，并为每个声部构造 24 个三和弦候选集合 | 按目标/槽位缓存，三和弦判定改为 12 位掩码查表 |

2026-08-04 本机样本（同一进程内先 DFS 后 DP）：

- DFS `0.069 s → 0.055 s`；
- DP `0.463 s → 0.140 s`，**约 3.3 倍**；边数、访问状态数与最低分 `266.70` 逐项不变，
  说明收益全部来自单条转移的成本而非搜索规模；
- DP/DFS 耗时比从约 6.7 倍降到约 **2.5 倍**。

### 7.1 层候选构建：排序 key 每帧只算一次

上一轮之后按阶段重新归因，最大的一段并不在终层，而是**层候选构建**（占 DP 的 26.6%）：
`layerCandidates` 与 `verticalCandidates` 都把 `(verticalPriority, verticalSpan, frameStructuralKey)`
写在比较器里，而 `best()` 每插入一个候选要做 `O(log n)` 次比较——每次比较都重算一遍省略音集合
（`chordMemberNotes` → 逐声部查 `texturePlan.participationAt`）并重新拼一次结构字符串。一层枚举
上百个候选，等于把这些 key 各算十几遍。

改为 decorate-sort-undecorate（`RankedFrame` 携带预先算好的三个分量）后该阶段减半（246→130 ms /
5 次），DFS 与 DP 共用这条路径，两个后端一起受益。这与 §7 表格里“出边排序”那一行是同一类
错误，只是漏在了层内枚举这一侧。

## 8. 终层：branch-and-bound

终层的逐层状态计划恒为空（`LayeredDpStatePlanner` 的绑定只写到 `length - 1`），因此**所有完整
路径都落进同一个状态组**，最终只保留 `labelLimit` 条。此前每条终边都要在整条路径上评估一次
全局规则：基准里 256 条终边评估 256 次，只有 1 条被留下。

现在终层先只算基础分并把标签攒起来，再排序后逐条补全局分：

- 排序键 `(路径优先级, 基础分)` 与全局规则无关，且全局分不低于一个静态下界
  `terminalGlobalScoreLowerBound(program, policy)`；
- 因此在 `(优先级, 基础分 + 下界)` **严格劣于**当前第 k 名时即可停止，后续候选两个分量都只会更差；
- 用严格大于（而非大于等于）保证并列候选仍被展开，多样化 tie-break 与逐边展开完全一致；
- 下界按约束的 modality 求和：`Require`/`Prefer` 只在 VIOLATED 时发射（代价为正），
  `Reward` 记 `-bonus`，`Annotate` 记 0；谓词自带 `branchScoreDelta` 的情形由
  `branchScoreDeltaLowerBound()` 的**穷尽 `when`** 逐个表态——新增谓词不表态就编译不过，
  避免下界失效把更优解剪掉。

另外 `FixedVoiceScoreRuleContext.fixedVoiceScore` 改为惰性：约束代数与自由写作的全局规则只读
`state.frames`，此前每条终边仍会合成 36 个事件与 72 个 EventId 字符串，现在一次都不合成。

基准里终层全局规则的求值次数从 256 降到 **121**，`WritingSearchTrace.terminalGlobalEvaluations`
直接报告这个数，卡农基准逐次打印并断言它严格小于终层标签数。
`ConstraintLayeredDynamicProgrammingSolverTest.
terminalBranchAndBoundKeepsTheExhaustiveOptimumWithGlobalRules` 另在终层全局规则全开的窄域上
证明 DP 最优分与穷举一致。

### 8.1 state key 也改为增量摘要

`stateKey` 此前每条边都重建各声部 MIDI 列表，并为每个 `UniqueVoiceExtreme` 需求**回扫整条前缀**
求极值与出现次数（2 个需求 × 2 个外声部 = 4 趟）。现在：

- 每帧的 MIDI 签名是层常量，写在 `DpLayerFrame` 上，同层所有入边共用一个实例；
- 极值摘要 `DpExtremeSummary` 随 label 增量更新（加一帧 O(槽数)），key 只读已经算好的紧凑
  `IntArray`。

该阶段从 51.1 ms 降到 1.9 ms（/5 次）。三项合计后卡农基准 DP 从 132.7 ms 降到 **71–93 ms**，
DFS 从 54.8 ms 降到 **35–46 ms**（本机逐次抖动就有这么大，只能看量级）；最低分 `266.70`、
转移数 1864、逐层 tier 分布全部逐项不变。

按阶段重测后的剩余占比：逐边转移规则求值约 30%，层候选构建约 26%，终层全局规则约 11%，
state key 已降到 1% 以下。注意该基准用 `maxFrontierStates = 32`——按 §9 它在本形态下换不到
任何分值，却让每层转移从 64 条涨到 256 条，因此这里的绝对耗时并不代表 DP 的最优配置。

## 9. 同等分值下的 DFS / DP 对比

只比“首解耗时”会同时误判两个后端：两者的默认宽度并不等价，各自还有一个真正决定质量的旋钮
——DFS 是每节点候选池 `beamWidth`，DP 是**每前驱出边宽度**
`min(max(8, 4 × maxResults), candidateLimit)`。`ConstraintDpVersusDfsQualityBenchmarkTest`
按 (分值, 耗时) 扫这两条曲线（分值确定可断言，耗时只打印）：

| 配置 | 最低分 | 耗时 |
|---|---|---|
| DFS 候选池 16 | 344.60 | 12 ms |
| DFS 候选池 32（默认） | 324.55 | 21 ms |
| DFS 候选池 48 / 64 / 128 | 245.95 | 26 / 31 / 53 ms |
| DP 层池 32，出边 8 | 403.65 | 17 ms |
| DP 层池 64 / 128，出边 8 | 266.70 | 16 / 20 ms |
| DP 层池 128，出边 16 | **233.80** | 27 ms |
| DP 层池 128，出边 32 | 247.35 | 47 ms |

结论：

1. **DP 在同等分值下已经更快，并且能到达 DFS 到不了的分值。** DFS 的质量在候选池 48 处到顶
   （245.95），再加深只是更慢；DP 在 27 ms 就拿到 233.80。
2. **DP 的前沿宽度几乎买不到质量。** 前沿 8 / 16 / 32 在本基准上分值完全相同，而耗时接近线性
   增长（§7 基准用的 32 比 8 多花约 4 倍转移）。真正的质量旋钮是层候选池与出边宽度。
3. **出边宽度不是单调的**：出边 32 反而比 16 差（247.35 vs 233.80）。有界 DP 在前沿上限处的
   淘汰顺序会随出边宽度改变，宽度不是越大越好——调参必须按 (分值, 耗时) 实测，不能想当然。
4. 出边宽度目前被 `maxResults` 绑死（`max(8, 4 × maxResults)`）：想要更宽的出边就必须同时要更多
   结果。这是个不必要的耦合，应拆成独立配置项。

## 10. 后续路线

1. 把出边宽度从 `maxResults` 解耦成独立配置项，并按 §9 的曲线给出默认值；前沿宽度默认
   （`maxFrontierStates = 4096`）在这一形态下明显过大，需要按程序形态而非固定常量给建议值；
2. ~~把规则状态声明下沉到每条 RuleId 定义旁，建立完整性守卫~~ ✅ 声明已挂在各 provider 的
   companion（`FourPartTextbookRules` / `FreeHarmonyRuleProvider` / `WindowFeasibilityRuleProvider` /
   `BaselineSimilarityRuleProvider`），守卫见 §6 第 14 条。谓词侧仍靠 planner 的穷尽 `when`；
   `requiresGlobalEvaluation()` / `isTargetOnly()` 用的是 `else ->`，**不**fail closed，仍需人工复核；
3. 逐边的转移规则求值现在是最大的一段（约 30%）：相邻两帧才决定的转移 finding 按 (前帧, 现帧)
   缓存，供共享前帧的多个状态复用；需要先把 `RecentFrames(2)` 规则（连续跳进轮廓）从逐对缓存
   里分离；
4. 层候选构建仍占约 26%：`verticalCandidateSequence` 的枚举与过滤本身尚未按目标缓存；
5. 为旋律反复实现精确有限自动机，再允许带该规则的 EXACT；
6. 剩余未 review 的是**和弦外音义务**与 textbook 模块；七和弦、组合约束与勋伯格 typed
   requirements 已完成（§1.1 / §3.1.1 / §3.2）；
7. 在开放域与**勋伯格形态**上扫 §9 的 (分值, 耗时) 曲线，并复核中间层合并率，再评估是否开启
   `AUTO`。勋伯格形态尤其需要：本轮实测其 BOUNDED 首解分值明显劣于 DFS（出边宽度被
   `maxResults=1` 压到 8），只有 EXACT 才稳定优于 DFS——这与第 1 项的解耦是同一件事。
