# 分层动态规划求解器

> 状态（2026-08-12）：DP 状态由当前槽位的声部音高，以及启用规则声明的有限附加状态组成；
> 开放和弦域、七和弦和同音响多解释均已进入能力集。固定目标、低音锁、音高 pin 与解释选择是
> 候选域过滤或评分输入，不再是 DP 能力门槛。`EXACT` 按真实总分排序并对不安全的终点下界关闭
> branch-and-bound；`BOUNDED` 支持路径谱系、多样性硬门槛与排除项。普通单结果 `AUTO` 仍选 DFS，
> 启用自由练习的前缀/结果多样化时 `AUTO` 选择 `LAYERED_DP`。
>
> **自由练习已在用 DP**：首解与优化两档都开前缀多样化，因此两档都走 `LAYERED_DP`（§1.2 有实测
> 证据）；勋伯格章节与其它单结果调用仍走 DFS。剩余工作是性能/参数解耦与状态合并率，不是能力
> 缺口——路线见 §10。
>
> 前置：[free-harmony-solver.md](free-harmony-solver.md) ·
> [constraint-program.md](constraint-program.md) ·
> [free-practice-window-voicing.md](free-practice-window-voicing.md)

## 1. 当前边界

和弦写作可视为分层 DAG：每层节点是“当前 `ChordTarget` 选择 + 声部排列”，边连接相邻槽位。
槽位可含一个或多个目标；目标身份、解释身份和实际音响身份按规则需要进入有限状态，不能仅凭
各声部 MIDI 音高把不同解释合并。

能力审计（`LayeredDpStatePlanner.collect`）分为两段。**程序级**（对所有 preset 生效）：

- `ruleModules` 显式为空，不启用派生 textbook 规则；
- 没有尚未注册的 `RuleProfile.requirements`、suppression 或谓词；
- 每条启用规则都有已审计的 DP 状态声明。

**preset 级**：

| preset | 额外条件 | provider |
|---|---|---|
| `FREE_CLASSICAL` / `FREE_JAZZ` | 开放目标、三/七和弦均可；目标敏感规则必须声明状态 | `FreeHarmonyRuleProvider` |
| `SCHOENBERG_GENERAL` | 无——**任意和弦类型** | `FourPartTextbookWritingRuleProvider` |
| `NONE` | 无 | 无 |
| `TEXTBOOK` | 未注册，fail closed | — |

`FreeHarmonyRuleProvider` 的无根减和弦与不协和释放规则已有拼写/目标语义声明，不再用“自然三和弦”
作为代理能力门槛。七和弦、副属、减七、增六和同音响多解释均可进入 DP；未知目标敏感规则仍
fail closed。

显式请求不支持的 DP 返回 `ConstraintSolveOutcome.Invalid`；列表式 API 抛出参数错误，不再把“不支持”
伪装成空结果。`AUTO` 会记录原因并回退 DFS。

### 1.1 勋伯格的实用范围

固定进行和开放域都可进入能力集；实际判据是每条启用规则是否有有限状态声明。

- **在能力集内**：descriptor 标了 `requiresEnumeratedProgression = true` 的练习。
  `SchoenbergExplorationRequestRunner` 先 `enumerate` 出进行，再为每条进行编译一个
  `progression != null` 的程序逐条求解——每槽只有一个目标。独立章节
  （`SchoenbergSecondInversionChapter` / `SchoenbergSeventhChordChapter`）内部也会
  `enumerate(key).first()`，同样落在集内。
- 开放域若显式选择 DP 会按预算求解；普通非多样化 `AUTO` 仍选择 DFS，因此既有章节默认行为不变。

和弦外音义务与 textbook 模块仍未进入能力集。

### 1.2 自由练习的实际后端

自由练习的写作请求由 `FreePracticeWindowVoicer.prepare` 编译（`FreeHarmonySolver.compile`，
`FREE_CLASSICAL`、`ruleModules = emptyList()`、`includeDerivedTextbookConstraints = false`），
`FreePracticeSearchPolicy.initial` 与 `optimization(seed)`（桌面/Web 共用的
`FreePracticeWriting` 后台请求同样构造这两档）**都设 `prefixDiversity.enabled = true`**，
`prepare` 还会再强制打开一次。`SearchConfig.backend` 默认 `AUTO`，而 `LayeredDpCapability`
在任一多样化开启时置 `autoPreferred = true`，因此两档实际都跑分层 DP。

本机以卡农进行（C 大调 9 槽、标准 SATB、含七和弦与转位词汇）复现两档配置：

| 档位 | supported | autoPreferred | trace.backend | fallbackReason | terminalLowerBoundApplied |
|---|---|---|---|---|---|
| `initial` | true | true | `LAYERED_DP` | null | false |
| `optimization` | true | true | `LAYERED_DP` | null | false |

两档的 `requiresBoundedGlobalRerank` 均为 `true`（`free.melody.no-repeated-pattern` 是终局重排
规则），所以**自由练习形态只能用 `BOUNDED`**，显式请求 `EXACT` 会被拒；这也是终层
branch-and-bound 在自由练习里恒不生效的第二个原因（第一个是多样化，见 §8）。

⚠️ 两处缺口：

1. **没有守卫**。仓库里没有任何测试断言自由练习程序仍选 DP。教学约束由
   `SchoenbergPracticeTeachingRuleProjector` 投影进来，一旦某章节规则投影出未注册谓词
   （非相邻 `VoiceDiatonicSteps`、`RuleFound`、与其它原子共处的 `UniqueVoiceExtreme`），
   整个窗口会**静默退回 DFS**。当前章节程序不设 `RuleProfile.requirements`，投影又要求
   源/目标槽都连续，因此实际不会触发，但这是巧合而非约束。
2. **`fallbackReason` 无人读取**。它只写进 `WritingSearchTrace`，产品侧（session、桌面、Web）
   一处都没消费，因此退回既不报警也不可观测。

`SearchBackend` 在自由练习里不可配置：UI 与 intent 都不暴露该开关，只能改
`FreePracticeSearchPolicy`。

## 2. 状态由规则自动收集

状态计划由 `LayeredDpStatePlanner.collect(program)` 在求解前编译。它读取实际和弦类型、preset、
`RuleProfile` 开关和所有约束，只收集当前任务真正启用的状态量。provider 规则在 RuleId 旁声明
`LayeredDpRuleStateDeclaration`；约束代数谓词由 planner 的穷尽 `when` 审计。未知项 fail closed。

每条规则声明的是“完成当前层后，为将来裁决还需保存什么”，而不是它读取过多少帧：

| 状态需求 | key 中保存的量 | 例子 |
|---|---|---|
| 无未来状态 | 无 | 纵向完整性、交错 |
| `RecentFrames(1)` | 最近一帧各声部 MIDI；规则需要时附拼写或目标语义 | 相邻运动、平五八、倾向音 |
| `RecentFrames(2)` | 最近两帧各声部 MIDI | 连续两次同向跳进 |
| `VoiceExtreme` | 指定声部当前极值及出现次数 | 最高/最低点唯一 |
| `ConstraintHistory` | 谓词专用有限自动机 | 已见身份、根音对、最近同根音槽位 |
| `CompositeTruth` | `And/Or/Not` 各原子的三值真值与 active 位 | 分支已满足/违反/未确定 |
| `TerminalRerank` | 不进等价 key；终局完整评分 | 短旋律模式反复 |

例如“三帧连续跳进”在第三帧加入时已经裁决了前三帧；为了下一次裁决，只需在该层后保留最近
两帧。最后一层没有未来，最近帧数自动降为 0。四槽默认计划是 `[1, 2, 2, 0]`；关闭
`free.melody.consecutive-leaps` 后自动缩为 `[1, 1, 1, 0]`。

固定目标窗口中，目标语义与拼写可由“层号 + MIDI + 唯一目标”推出，key 会省略这些层常量；开放域
则保留完整解释身份。`DpStateKey` 不保存完整路径，只保存最近音高投影、极值、谓词自动机和复合
真值向量；完整路径仅留在 label 中用于规则执行与结果恢复，不参与等价性比较。

⚠️ 例外：目前有几个 `DpConstraintHistorySignature` 实为随前缀增长的累加器而非有限自动机
（`RootProgressionPreference` 的 `applicableDegrees` 就是完整音级前缀，
`MinimumSimilarChordDistance` 存绝对槽号，`DistinctSimilarChordProgressions` 与
`DistinctIdentities` 用不断增长的集合）。**这四项都不出现在自由练习的 key 里**：和弦选择规则
已降为 `Remind`，planner 不为其收集状态；勋伯格开放域 program 仍以 `Prefer` 使用它们。
极值摘要的 `occurrences` 已按 `maxOccurrences + 1` 饱和（`DpExtremeSummary.extend`），但主要
分裂源是极值**数值**而不是计数，合并率只降 0–20%。逐项与影响见
[dp-slot-scaling-review.md](dp-slot-scaling-review.md) §6 / §7。

## 3. 当前覆盖的规则

覆盖 = planner 为该规则收集到有限状态（或证明它不需要状态），DP 因此不会漏判它。规则总数按
**RuleId** 计，不含由约束代数按槽实例化的份数。

| 面 | provider / 来源 | 规则数 | 备注 |
|---|---|---|---|
| `FREE_CLASSICAL` provider | `FreeHarmonyRuleProvider` 12 + 交错同音 1 + 移动成本 1 + `WindowFeasibility` 2 + `BaselineSimilarity` 1 | **17** | jazz 少 6 条古典规则 → 11 |
| `SCHOENBERG_GENERAL` provider | `FourPartTextbookRules` 8 + 交错同音 1 + 移动成本 1 | **10** | 全部只需 `RecentFrames(1)` |
| 约束代数谓词 | §3.2 的 5 类 | 17 个谓词已注册 | 3 个明确拒绝 |

典型自由练习窗口（卡农进行、无教学 idiom）实测 `coveredRuleIds` 为 **24** 条 = 17 条 provider 规则
+ 7 条程序约束（`free.harmony.triad-complete` / `seventh-complete`、
`free.harmony.similar-chord-distance` / `distinct-progressions`（`Remind`，不收状态）、
`free.melody.unique-high` / `unique-low`（极值摘要）、`free.melody.no-repeated-pattern`（终局重排））。
挂上勋伯格教学 idiom 时，投影出的章节约束按 §3.2 逐条计入，不改变 provider 那 17 条。
该形态的逐层计划为 `[1, 2, 2, 2, 2, 2, 2, 2, 0]` 帧 + 两个外声部极值槽。

### 3.1 自由写作 provider

| 依赖 | 已覆盖规则 |
|---|---|
| 单槽 | 相邻声部同音、外/内声部交错、音域边缘余量、窗口相邻间距、baseline 改动成本 |
| 相邻槽 | 声部移动成本、别扭跳进、运动拥挤、多个声部同时大跳 |
| 古典相邻槽 | 平行纯音程、隐伏纯音程、倾向音 |
| 三槽 | 连续两次同向跳进的轮廓 |

DP 扩展会为每条入边计算垂直规则，因为 `DistinctIdentities` 等规则可能读取该前缀；候选帧本身的
MIDI、拼写、目标签名和 verticality 仍按层共享。窗口相邻间距和
多声部同时大跳另有共享的纯判定：只有规则启用、最终严重度仍是 `HARD`、且不存在可能隐藏它的
suppression 时才提前剪枝；规则被降为 `SOFT` 或可被 suppression 覆盖时，仍进入完整规则管线。

DFS 与 DP 还共用一套不进入乐理评分的字典序搜索优先级：完整和弦、内声部小于五度且高音不大于
五度为严格层；之后依次放宽为合法省略一个音、内声部五度、高音六度，其他更大跳进保留为最终
兜底。有限候选池预选时，同层内先减少非级进内声部，再比较移动量；进入搜索后同层仍以完整
`ScoreBreakdown` 排序，不能越过相邻声部同音等规则 finding。三和弦只能省五音；七和弦只能省三音或五音，
且最多省一个，这是候选生成的硬边界，不随搜索层放宽。

`ROOTLESS_DIMINISHED_*` 和 `DISSONANCE_RELEASE` 已声明目标/拼写状态，七和弦与开放域不会再被代理条件挡住。
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
| 单槽、无未来状态 | `ToneCompleteness`、`ToneDoubled`、`ToneNotDoubled`、`ScaleDegreeNotDoubled`、`Spacing`、`ToneMultiplicity`、`ToneInVoiceFilter` |
| 目标历史自动机 | `DistinctIdentities`、`TargetMatches`、`SameSonority`、`RootDiatonicMotion`、`MinimumSimilarChordDistance`、`DistinctSimilarChordProgressions`、`RootProgressionPreference` |
| 最近一帧 | `CommonToneWithPrevious`、`NeighborTone`、相邻槽 `VoiceDiatonicSteps` |
| 有限摘要 | `UniqueVoiceExtreme` |
| 有界终局重排 | `NoRepeatedVoicePattern` |

`Annotate` 与 `Remind` 约束不计分也不能否决，因此不影响任何搜索决策，planner 只把它们记为
covered 而**不收集状态**。自由写作的和弦选择规则正是这一类（见
[free-harmony-solver.md](free-harmony-solver.md) §4.0），所以 `MinimumSimilarChordDistance` /
`DistinctSimilarChordProgressions` / `RootProgressionPreference` 的前缀历史不会进入自由练习的
DP key。

**合成式（`And` / `Or` / `Not`）使用有限自动机**：planner 遍历 `expr.atomicPredicates()`，收集每个
原子的音高/历史需求，同时把各原子的 Kleene 三值真值与 active 位纳入 key。它不会把完整前缀塞入
状态，也不会只保存整体真假而丢掉各分支进展。任一原子被拒则整条约束被拒。这解锁了勋伯格的两处合成式：
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
- 同状态 label 首先按累计真实分数排序，搜索优先级只作同分 tie-break；最终结果再次按完整总分排序。

`BOUNDED` 可以截断候选、每前驱出边、状态和同状态 labels，并在终局按完整规则重排。普通每目标
出边宽度为 `min(candidateLimit, max(8, 4 × maxResults))`；启用多样化时允许用到完整
`candidateLimit`。有效状态前沿为 `min(maxFrontierStates, max(beamWidth, activeSearchWidth) × maxResults)`。
先按上述放宽层排序，因此首解任务通常
只评估每状态 8 条最平顺边。trace 分别报告 `candidateLayersTruncated`、
`transitionCandidatesTruncated`、`frontierTruncated`、`equivalentLabelsTruncated` 和
`boundedGlobalRerank`。这是一种受控近似，不保证全局最优；`EXACT` 不做出边截断。

终点 branch-and-bound 只在下界可证明安全时启用；suppression、权重覆盖、复合约束、EXACT、多样化
和排除项都会关闭该优化，trace 的 `terminalLowerBoundApplied` 显式报告下界是否生效——为 `false` 时
`terminalGlobalEvaluations` 等于终层全部候选数，不要误读成规则变贵（自由练习开启多样化后恒为
`false`）。排除组先过滤再占用 top-k 槽位。全局原子规则只在完整进行上评分，避免把尚未完成的唯一极值或反复模式误当作前缀代价。极值摘要
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
4. 目标历史使用谓词专用自动机；未知状态谓词拒绝 DP；
5. open domain、七和弦与同音响不同解释进入 DP，后者即使 MIDI 相同也不会错误合并；
6. left boundary 在 DFS 与 DP 下语义一致；
7. spacing 被 profile 降为 `SOFT` 时不会误做提前剪枝，DP 与 DFS 的完整 breakdown 一致；
8. suppression 明确 fail closed，不让可撤回的累计代价参与错误合并；
9. EXACT 的候选/状态上限、独立边预算以及优先 trace 事件有明确语义；
10. 增量路径优先级（`extendPathPriority`）与整段 `pathPriority` 在含全部放宽层的路径上逐分量相等；
11. 终层 branch-and-bound（§8）在终层全局规则全开的窄域上与穷举同分，且展开次数严格少于终层
    接受的转移数；
12. DFS 与 DP 的 (分值, 耗时) 曲线（§9）：DFS 质量随候选池到顶后不再改善，DP 放宽出边宽度后
    严格优于 DFS 的质量上限；
13. 勋伯格（`SchoenbergLayeredDynamicProgrammingTest`）：开放域显式进入 DP、普通 `AUTO` 仍选 DFS；
    **含七和弦**的固定进行上 EXACT DP 不劣于 DFS，复合约束使用 `compositeTruth` 而非完整前缀；
14. 状态声明完整性守卫（`LayeredDpStateDeclarationCompletenessTest`）：每个 provider 的
    `ALL_RULE_IDS` 减去已声明规则后必须为空；新增 RuleId 而忘记声明即测试红；
15. 前沿裁剪不读失效 entry（`boundedDpPrunesTheFrontierWithoutReadingStaleMapEntries`）：见 §6.1，
    该回归只在 Kotlin/JS 上才会红；
16. 槽位扩展性（`ConstraintLayeredDpSlotScalingBenchmarkTest`，JVM）：同一前缀的层其
    candidate / generated / distinct / retained 四元组不随总槽数变化，每层保留标签数不超过前沿上限。

**未覆盖**：没有任何测试断言**自由练习程序仍然选到 DP**（§1.2）。`LayeredDpCapability` 的输入
包含教学投影出的章节约束，退回 DFS 是静默的，因此这条链目前只靠人工复核。

### 6.1 ⚠️ 求解器是共享代码，`Map.Entry` 在 Kotlin/JS 上是活引用

Kotlin/JS 的 `Map.Entry` 回指底层哈希表并记录 modCount，表结构变动后再读它的 `key`/`value` 会抛
`ConcurrentModificationException("The backing map has been modified after this entry was obtained.")`。
JVM 的 `LinkedHashMap` 节点在 `clear()` 后仍持有 key/value，**同一段代码在桌面端完全正常**。

`retainBestStateGroups` 曾把 `grouped.entries` 排序后的 entry 列表留到 `grouped.clear()` 之后再重填，
于是 Web 端任何触发前沿裁剪的求解（离调 / Ger+6 一类的大搜索）都会在写作阶段整体崩溃。规则：
**跨越任何 map 结构性修改之前，先把 entry 拷成普通 `Pair`**。`entries.toList()` / `sortedWith` /
`asSequence()` 保留的都是活引用，只有 `map { it.key to it.value }` 这类拷贝才安全。

`theory` 的 commonTest 此前根本无法为 JS 编译（`ConstraintLayeredDpCanonBenchmarkTest` 用了
JVM-only 的 `toSortedMap`），所以这类缺陷完全没有守卫。现在三个 JVM 计时基准已移到
`theory/src/jvmTest`，Mocha 超时提到 120s（穷举对照测试在 JS 上比 JVM 慢一个量级），定向回归用
`./gradlew.bat :theory:jsNodeTest --tests "*ConstraintLayeredDynamicProgrammingSolverTest*"`。

整个 `:theory:jsNodeTest` 仍有 29 个既有失败，全部是同一个原因：勋伯格章节测试要读禁忌进行表，
而 JS build 按设计不打包该资源（`SchoenbergForbiddenTransitions` 的 JS actual 显式 `error(...)`，
以免静默退化成空表）。要让该 target 整体转绿，需要另行决定是把这些测试标为 JVM-only，还是把表
打进 JS 产物；不要误读为本次改动引入。

## 7. 每条转移的成本

转移**条数**已由放宽层排序和每前驱出边上限压到 1,864 条；本节记录的是**每条转移的单价**。
基准同上：C 大调 `I-V-vi-iii-IV-I-IV-V-I`、标准 SATB、每目标最多 128 个候选、有界前沿 32、
`maxResults=1`；计时改为预热 6 次后取 5 次采样的最小值（单次计时被 JIT 与 GC 支配，不可比）。

层内每个候选帧的常量（各声部 MIDI、省略音数、纵向跨度、tie-break key、合成事件 verticality）
只算一次；依赖前缀的纵向 finding 按入边计算。标签携带路径优先级、前一帧摘要与前一帧 verticality。由此消除的重复
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

`layerCandidates` / `verticalCandidates` 曾把 `(verticalPriority, verticalSpan, frameStructuralKey)`
写在比较器里，于是 `best()` 每次插入的 `O(log n)` 比较都重算省略音集合并重拼结构字符串。改为
decorate-sort-undecorate（`RankedFrame` 预先携带三个分量）后该阶段减半，DFS 与 DP 共用这条路径。
这与 §7 表格“出边排序”那行是同一类错误，只是漏在了层内枚举一侧。

## 8. 终层：branch-and-bound

终层的逐层状态计划恒为空（`LayeredDpStatePlanner` 的绑定只写到 `length - 1`），因此**所有完整
路径都落进同一个状态组**，最终只保留 `labelLimit` 条。此前每条终边都要在整条路径上评估一次
全局规则：基准里 256 条终边评估 256 次，只有 1 条被留下。

现在终层先只算基础分并把标签攒起来，再按基础分排序后逐条补全局分：

- 排序键以真实累计分为首项，路径优先级只作同分 tie-break；全局分不低于一个静态下界
  `terminalGlobalScoreLowerBound(program, policy)`；
- 因此在 `基础分 + 下界` **严格劣于**当前第 k 名时即可停止；
- 用严格大于（而非大于等于）保证并列候选仍被展开，多样化 tie-break 与逐边展开完全一致；
- 下界按约束的 modality 求和：`Require`/`Prefer` 只在 VIOLATED 时发射（代价为正），
  `Reward` 记 `-bonus`，`Annotate` 记 0；谓词自带 `branchScoreDelta` 的情形由
  `branchScoreDeltaLowerBound()` 的**穷尽 `when`** 逐个表态——新增谓词不表态就编译不过，
  避免下界失效把更优解剪掉；存在 suppression、权重覆盖、复合约束、多样化、排除项或 EXACT 时
  直接关闭下界剪枝。

另外 `FixedVoiceScoreRuleContext.fixedVoiceScore` 改为惰性：约束代数与自由写作的全局规则只读
`state.frames`，此前每条终边仍会合成 36 个事件与 72 个 EventId 字符串，现在一次都不合成。

基准里终层全局规则的求值次数从 256 降到 **121**，`WritingSearchTrace.terminalGlobalEvaluations`
直接报告这个数，卡农基准逐次打印并断言它严格小于终层标签数。
`ConstraintLayeredDynamicProgrammingSolverTest.
terminalBranchAndBoundKeepsTheExhaustiveOptimumWithGlobalRules` 另在终层全局规则全开的窄域上
证明 DP 最优分与穷举一致。

### 8.1 state key 也改为增量摘要

`stateKey` 此前每条边都重建各声部 MIDI 列表，并为每个 `UniqueVoiceExtreme` 需求回扫整条前缀。
现在每帧的 MIDI 签名是写在 `DpLayerFrame` 上的层常量，极值摘要 `DpExtremeSummary` 随 label
增量更新（加一帧 O(槽数)、计数按 `maxOccurrences + 1` 饱和），key 只读紧凑 `IntArray`。

三项合计后卡农基准 DP 从 132.7 ms 降到 **71–93 ms**、DFS 从 54.8 ms 降到 **35–46 ms**；最低分
`266.70`、转移数 1864、逐层 tier 分布逐项不变。剩余占比：逐边转移规则求值约 30%、层候选构建
约 26%、终层全局规则约 11%，state key 已降到 1% 以下。注意该基准用 `maxFrontierStates = 32`
——按 §9 它在本形态下换不到任何分值，绝对耗时并不代表 DP 的最优配置。

## 9. 同等分值下的 DFS / DP 对比

只比“首解耗时”会同时误判两个后端：两者的默认宽度并不等价，各自还有一个真正决定质量的旋钮
——DFS 是每节点候选池 `beamWidth`，DP 是**每前驱出边宽度**
`min(max(8, 4 × maxResults), candidateLimit)`。`ConstraintDpVersusDfsQualityBenchmarkTest`
按 (分值, 耗时) 扫这两条曲线（分值确定可断言，耗时只打印）：

| 配置 | 最低分 | 耗时 |
|---|---|---|
| DFS 候选池 16 | 344.60 | 12.8 ms |
| DFS 候选池 32（默认） | 324.55 | 19.7 ms |
| DFS 候选池 48 / 64 / 128 | 245.95 | 27.1 / 30.8 / 51.7 ms |
| DP 层池 32，出边 8 | 403.65 | 21.2 ms |
| DP 层池 64 / 128，出边 8 | 266.70 | 17.7 / 18.6 ms |
| DP 层池 128，出边 16 | **233.80** | 29.8 ms |
| DP 层池 128，出边 32 | 247.35 | 60.4 ms |

结论：

1. **DP 在同等分值下已经更快，并且能到达 DFS 到不了的分值。** DFS 的质量在候选池 48 处到顶
   （245.95），再加深只是更慢；DP 在 27 ms 就拿到 233.80。
2. **DP 的前沿宽度几乎买不到质量。** 前沿 8 / 16 / 32 在本基准上分值完全相同，而耗时接近线性
   增长（§7 基准用的 32 比 8 多花约 4 倍转移）。真正的质量旋钮是层候选池与出边宽度。
3. **出边宽度不是单调的**：出边 32 反而比 16 差（247.35 vs 233.80）。有界 DP 在前沿上限处的
   淘汰顺序会随出边宽度改变，宽度不是越大越好——调参必须按 (分值, 耗时) 实测，不能想当然。
4. 出边宽度目前被 `maxResults` 绑死（`max(8, 4 × maxResults)`）：想要更宽的出边就必须同时要更多
   结果。这是个不必要的耦合，应拆成独立配置项。

### 9.1 较长开放域自由写作

`longerOpenProgressionUsesDpForDiverseFreeWriting` 使用 11 槽进行，其中 3 槽允许同音级三和弦/七和弦
二选一，并要求 2 个满足 pairwise 槽位/声部差异门槛的结果。DFS 在 8,192 节点预算内拿不到完整结果
（0.76 s 后 `BudgetExhausted`），BOUNDED DP 给出 2 个结果、最佳 313.65。

多样化路径的耗时经 [dp-slot-scaling-review.md](dp-slot-scaling-review.md) §7 第 1、3 项整改后：

| 形态（3 结果，除首行外） | 整改前 | 现在 |
|---|---|---|
| 11 槽 / 2 结果 | 1.32 s | **0.44 s** |
| 9 槽 | 2.61 s | 0.81 s |
| 13 槽 | 6.26 s | 0.85 s |
| 19 槽 | 16.84 s | 1.94 s → 1.03 s（MIDI 签名后） |

**槽位增长本身从来不是问题**：逐层生成标签数、状态数与保留标签数不随槽位变化，转移数只线性
增长（关闭多样化时 25 槽 27.9k 条转移只需 0.73 s）。原先的超线性完全来自多样化标签选择在前沿
裁剪里被反复调用；现在相似度增量维护 + `pathMidi` 整型比较已把它压掉一个量级，曲线由
`ConstraintLayeredDpSlotScalingBenchmarkTest` 固化（断言逐层规模的前缀不变性，耗时只打印）。

产品接入仍保持滑动窗口/分段写作与后台取消，但这已是产品交互选择而非性能下限；进一步抬高长
窗口上限之前应先做 §10 第 1 项的宽度解耦，不要靠增大节点或前沿预算掩盖。

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
   `maxResults=1` 压到 8），只有 EXACT 才稳定优于 DFS——这与第 1 项的解耦是同一件事；
8. 多样化路径的性能与状态合并率整改，见
   [dp-slot-scaling-review.md](dp-slot-scaling-review.md) §7。已完成：相似度增量维护、
   `prefixSimilarity` 走 MIDI 签名、极值摘要 `occurrences` 饱和、基准配置入库、
   `terminalLowerBoundApplied` 可观测。**剩余**：
   - 前沿裁剪不做多样化（会改变哪些状态存活，需连同前缀多样性质量一起评估）；
   - `UniqueVoiceExtreme` 在 BOUNDED 下整体降为终局重排——这是拿回 §3 那 3.4–5× 合并率的唯一
     途径，EXACT 必须保持现状；
   - history 签名常量化（仅影响勋伯格开放域，自由练习已因 `Remind` 规避）；
   - 终层 BnB 需要多样化感知的下界才能开启，当前终层约占多样化基准总耗时的 20%；
   - 终层标签池仍绑在 `restartBudget × mutationPoolSize`（=128），与 DP 无关，应独立配置；
9. 为自由练习补一条后端守卫测试，并把 `fallbackReason` 接到可观测通道（§1.2 的两处缺口）。
