# 乐理求解器后续工作（按优先级）

> 更新：2026-07-12，ConstraintProgram 多样化重启 DFS 设计已确认，实现待落地；
> 「约束求解架构优化」M6/M7（统一约束代数 + 命名规则迁移）已落地。
> 相关：[solver-api.md](solver-api.md)（协议与里程碑）· [rule-scenes.md](rule-scenes.md)（场景模型）·
> [constraint-program.md](constraint-program.md)（S2 约束程序）·
> [diverse-search.md](diverse-search.md)（多样化重启 DFS）·
> [constraint-architecture.md](constraint-architecture.md)（架构优化设计）·
> [figuration.md](figuration.md)（和弦外音）·
> [textbook-dominant-seventh-rules.md](textbook/textbook-dominant-seventh-rules.md)。
>
> 背景：五度圈模进两个"交替"变体无解的 bug 已修复（一/三转位交替改以 I6 收束；
> 原位完全/省五交替经 `SeventhFifthConstraint` 在生成期收窄）。以下是审查中确认的欠账与扩展方向。

## P1 ✅ — 偿还判据回归：七和弦章迁回场景数据驱动（本轮完成）

solver-api.md §1 的判据"runner 中按章节硬编码的分发与槽位扩展全部消失"已达成：属七章约 350 行
硬编码（`runDominantSeventhRuleExample` 分流、`dominantSeventhSlots` / `supertonicSlots` /
`leadingSeventhSlots` / `circleOfFifthsSlots`、`CIRCLE_OF_FIFTHS_RULES` 等规则集合常量）全部删除，
改由 `DominantSeventhRuleCatalog.scenes()` 声明的场景数据驱动。落地方式：

1. **场景模型加词汇表维度**：`RuleScene.chordArity`（TRIAD / SEVENTH）+ `SlotChordSpec.seventhPositions`
   （含第三转位）/ `arity`（单槽覆盖，解决用三和弦声明 TRIAD）；`SceneMatcher.instantiateSeventh`
   把七和弦场景落成 `TextbookSeventhWritingSlot`。
2. **`DoublingExpectation` facet**：把 `SeventhFifthConstraint`（完全/省五交替）表达为场景数据，
   五度圈原位交替的五音完整性由指向槽位的 facet 提供；MAY 枚举对该 facet 恒真（voicing 级，rule-scenes §4）。
3. runner 现为 arity 通用分发：TRIAD → `instantiate` → 三和弦求解器；SEVENTH → `instantiateSeventh`
   → 七和弦求解器。新章节接入不再改 runner（见 rule-scenes §8 接入指导）。
4. 金标准测试：`SeventhSceneInstantiationTest`（教材进行必须由场景产出）+ 既有
   `SolverApiTest` 便捷请求回归（五度圈四变体 × 大小调、上主七、正误对照对）全绿。

**符号枚举补齐（2026-07-08）**：七和弦的符号级 `enumerate` 已接入 arity-aware
`ChordVocabulary`：`SymbolicChordSpec` 可表达七和弦槽与七和弦章节中的三和弦解决槽；
exploration `Policies` / manifest 同时暴露三和弦与七和弦转位集合，`SymbolicChordSpecView`
返回 `arity`。探索页已改为按 `FormSpec` 字段分发渲染现有控件，window≥3 的进行 picker
可消费三/七和弦枚举标签。
> **七和弦通用 `solve` 已收进 S2（2026-07-07）**：便捷请求经 `instantiateSeventh` 落成 SEVENTH-arity
> `ConstraintProgramSpec`，由 `ConstraintProgramSolver`（统一 `ChordTarget` + 模块调度）求解，
> 支持三/七和弦混合程序（见下方 P2 S2 与 constraint-program.md）。

## P2 — S2 约束程序（勋伯格接入的前置）

按 [constraint-program.md](constraint-program.md) 落地 `ConstraintProgramSpec` + `refine`，
本次审查补充两个设计要求：

1. ✅ **requirement 的生成期投影**（增量一落地）：`RuleRequirement` 加 `SlotWindow`；
   `FixedVoiceWritingSolver.applyRequirements` 按 anchor 槽窗口化——满足 / 缺失只在窗口内裁决，
   窗口起点仍在未来（前缀未覆盖）时**不判缺失**，消除"末端 finding 中途不可见、正确前缀被挤出
   beam"。`RuleAt(window, ruleId, mode)` 编译到带窗口 requirement。`indicationBonus` 跨槽累加
   计分本身（同规则去重 / 衰减）归 P3 质量项，未在本增量改 `findingScore`。
2. ✅ **槽间关系约束**（2026-07-08）：S2 已补 `AllDifferent(window)` 与
   `AdjacentCommonTone(window, holdInSameVoice)`，并进入 manifest 的 `constraintKinds`。
   `DoublingAt` 增加 `required` 开关，保留旧软偏好默认值；`required=true` 时作为硬约束剪枝。
   这覆盖了勋伯格第一个练习所需的"和弦不重复 / 相邻共同音保持 / 根音重复"组合。

> **增量一（2026-07-07）已落地**：`ConstraintProgramSpec`（ChordAt / RuleAt / DoublingAt / SpacingAt，
> TRIAD arity）+ `ConstraintProgramCompiler`（spec→运行时、便捷请求→spec）+ 通用
> `ConstraintProgramSolver`（`:theory`，编译到通用 `WritingTask` 驱动四部求解，复用位置分发型三章
> provider）；`SolveRequest.program` 接线、`describe().constraintKinds` + `constraint-program` FormSpec。
> 等价卡尺为行为等价（目标 finding 命中 / 无解诊断一致 / 候选满足约束）。
>
> **增量二（2026-07-07）已落地——七和弦收进 S2 + 混合 arity**：最初以统一目标 `TextbookChordTarget`
> （`Triad` / `Seventh`）让 `ConstraintProgramSolver` 用单一 `T` 驱动三/七和弦混排；
> 后续 M1-M4 已把这层替换为 `ChordTarget` 能力接口 + `ChordRuleDispatcher` 模块自发现。
> `ChordAt.arity` + `triadSonority`
> + `FifthAt` spec；`fromRuleExample` 遇 SEVENTH 场景经 `instantiateSeventh` 落成 SEVENTH-arity spec
> （取代旧的回退原位三和弦——这是七和弦走不进 S2 的根因）；`assembleTextbookChords` 混合装配。
> 金标准：五度圈四变体×大小调 / 上主七 / V7-I 省五经程序路径与便捷路径等价，I-V7-I 混合程序按 arity 分发。
> **v1 限制已由 M2 修复**：`checkScore` 不再逐 arity 投影后折叠序列；三和弦上下文按连续 triad run 检查，
> 七和弦模块拿完整序列视图。
> **关系约束增量（2026-07-08）已落地**：`AllDifferent` 和 `AdjacentCommonTone` 作为合成
> provider 接入 `ConstraintProgramSolver`，可在搜索中剪掉重复和弦、无共同音相邻槽、以及共同音未在同一声部保持的排布。
> **未含**：refine、LinePattern 自动机、变长 length。

## P2 ✅ — 约束求解架构优化 M1-M4（TextbookChordTarget 移除 + 规则自发现）

设计定稿见 [constraint-architecture.md](constraint-architecture.md)。动机：S2 混合 arity 的
过渡产物 `TextbookChordTarget`（sealed 联合体 + `ArityDispatchedChordRuleProvider` 硬编码分发 +
候选工厂 `when(target)` 特判 + `asSeventhTarget` hack + 章节概念泄漏进 KnowledgeBonus）把所有
章节耦合在统一分发点上，新和弦族/新章节都要改共享类型；勋伯格半音化章节（拿坡里/增六）在
该结构下无处安放。方向：词汇 / 约束 / 规则 / 调度四层正交——`ChordTarget` 能力接口 +
`TargetSelector` 数据化适用性 + 注册制 `ChordRuleModule` 由调度器按适用性自选（writing-engine
§5 调度器的落地形态），候选生成的家族特判改为编译期注入的约束数据。

里程碑（行为等价卡尺逐级替换，详见设计文档 §5）：

1. ✅ **M1 接口化**：`ChordTarget` + `TargetSelector` 已落地；`TextbookTriadTarget` /
   `TextbookSeventhTarget` 直接实现接口，旧 `TextbookChordTarget` 兼容层已删除。
2. ✅ **M2 规则调度器**：`ChordRuleModule` + `ChordRuleDispatcher` 已替换通用 arity 分发与 standalone
   textbook solver 的位置分发入口；`forTargetType` / `PositionDispatchedTriadRuleProvider` 已删除，
   三和弦 `checkScore` 按连续 triad run 检查，七和弦模块拿完整序列视图。
3. ✅ **M3 候选约束化**：`ToneCompletenessRequirement` 承接 `FifthAt` 与七和弦 root/seventh 在场；
   `AvoidScaleDegreeDoublingRequirement` 与 neighbor alteration 已进入 runtime；三个旧 `*ExercisePolicy`
   和章节候选工厂已由 `TextbookTriadConstraintPreset` 约束包取代，三个 textbook solver 全部编译到 `ConstraintProgram`；
   `fromConvenience` 也把同一 preset 展开为公开 spec 约束；ToneCompleteness / 音级避免重复的
   候选剪枝与 finding provider 共享同一判定函数。
4. ✅ **M4 勋伯格泛化**：`TargetFeatureBonusRequirement` 取代硬编码知识点奖励；
   `AvoidDoublingAt` / `AvoidScaleDegreeDoublingAt` / `ChordToneNeighbor` / `TargetFeatureBonus`
   已进入公开 `ConstraintProgramSpec` 与 manifest；适用性统一使用 `TargetSelector`，旧字段已删除；
   输出统一为 `ChordVoicing`，不再按三/七和弦建立 sealed 包装。
5. **M5 半音化词汇**：degree+alteration 进目标身份，拿坡里/增六作为新目标实现类——与下方
   P3 半音化前置合流。
6. ✅ **M6 统一约束代数**：`Constraint`（适用域+谓词+强度+解释）与 And/Or/Not、Kleene 三值求值、
   `RuleFound` 二阶原子及通用 constraint→finding 桥已落地；`ConstraintProgram` 九个 requirement
   列表参数退役，由 `fromRequirements` 在边界 desugar。旧 spec 仍向后兼容，组合约束仅在原子
   顶层时参与旧的提前过滤，Or/Not 由代数求值，避免把析取误当合取剪枝。与 M5 正交。
7. ✅ **M7 命名规则迁移**：`constraint-at` 已公开可序列化的组合表达式、Or 分支 ruleId/文案/
   分支计分；勋伯格导和弦预备/解决已命名化。当前 textbook 迁移进一步覆盖三和弦垂直约束、
   V7 七音/导音解决、七和弦质量与省略、常见转移语境、五度圈转位交替，以及带源音级筛选
   的升五到四禁则。统一桥按 ruleId 过滤旧模块同名 finding；`REQUIRE_VIOLATION` 演示会
   放宽对应 hard 约束而保留 violation finding。
覆盖结论（设计文档 §3，2026-07-10 修订）：剪枝型规则约七成已落成约束数据；PATTERN 不再
一律保留 Kotlin 本体——expr 词汇覆盖（含 Or 分支命名）到哪，声明式本体（命名约束）就迁到哪；
程序式识别经 `RuleFound` 原子接入同一代数，判定本体始终只有一份。

## P2 — 和弦外音章（figuration.md，教材下一章）

教材"和弦外音"章（textbook.md：p / n / s·r / app / e / n.gr / ant / ped）不是新和弦族，
是叠在和声骨架上的**装饰层**——设计定稿见 [figuration.md](figuration.md)，按增量 F0–F4 落地：

1. **F0 拍位语义**：`WritingTimeline.meter: MeterPlan?`（强弱两级 + 槽时值），解锁
   `MetricPosition` facet 与 `HarmonicRhythm`/`MeterSpec`；`meter = null` 既有章节零影响，
   **无依赖，可立即开工**。
2. **F1 装饰层模型 + 判定器 + 还原**（分析先行）：`FiguredLine`（子槽细分、延留音推迟
   骨架音 onset）+ `NonChordToneClassifier`（到达/离开/拍位/隶属四特征，八类外音金标准）+
   `FigurationAnalysis.reduce`——兼作 solver-api `check` 入口的外音底座。
3. **F2/F3 生成**：`FigurationCandidateSpace`（`WritingTaskPlan` 首个真实两阶段用例）——
   先弱位插入（p/n），再强位与时值变换（s/r/app）与 e/n.gr/ant；`NonChordTone` facet +
   场景/目录接入，runner 红线不变（按"有无装饰阶段"通用分发）。**阶段边界按信息依赖划分**
   （figuration.md §7.1 反向投影）：延留音链等骨架可判定型需求充要投影为 Stage 1 约束
   （选和弦同时裁决延留声部，同七音预备/`SeventhFifthConstraint` 的生成期收窄模式），
   插入型软投影 + top-K 跨阶段回退兜底。
4. **F4 持续音与 spec**：`PedalAt` 落在**骨架阶段**（低音固定 + 中间槽豁免隶属），
   `FigurationAt` / `MeterSpec` 进 `constraintKinds` 与 FormSpec。

**前瞻**：F1 的特征判定器 + F0 拍权即是复调 species 不协和处理的全部词汇
（二种=弱拍经过、四种=延留链），复调立项挂 F2 后、只需另建 `CounterpointCandidateSpace`；
F1 还原管线（表面→骨架→功能）是曲式分析的第一段，曲式再叠 `PhrasePosition` / 分段层级模型
（见 figuration.md §9）。

## P2 — 勋伯格和声学接入（schoenberg/schoenberg-harmony.md）

方向判断：**不再走"新章节 = runner 新分支"的路**，把勋伯格练习当作 S2 约束程序的
第一个真实客户立项。映射关系：

- **一般规范类规则**（不协和音预备/解决、重复音偏好、"最平顺连接"）→ 现有
  `CONSTRAINT` / `TENDENCY` 规则 + `RuleProfile` 权重即可表达；
- **规则优先顺位**（"导和弦预备 > 不重复三音 > 共同音保持"）→ `RuleSuppression` +
  权重覆盖；"理解优先顺位"的教学目标适合正误对照输出（`comparisonGroups` 已就位）；
- **自由探索式练习**（枚举所有连接情况、长进行、丰富变化）→ 约束程序 + enumerate，
  窗口需超出 RuleScene 现有 ≤3 槽假设，由 S2 的程序化窗口承担；
- **练习分级**（先原位、再六和弦……）→ `Policies` 词汇表分级，与调性和声教材同机制。

**第一个练习（2026-07-08）已接入**：独立文档见
[schoenberg-harmony.md](schoenberg/schoenberg-harmony.md)，代码入口为 `theory/.../schoenberg/`：
`SchoenbergCommonToneExercises.kt` 只保留门面与目录，共同音、导和弦、六和弦、综合科技树分别拆到独立文件；
`exploration` 仅保留 `SchoenbergExerciseRequest` / manifest / FormSpec 适配。共同音 / 综合练习使用接续和弦个数；
导和弦、六和弦等独立练习先展示全部枚举结果，再把用户选中的 progression 传回求解；
系统可先 `enumerateFirstExerciseProgressions` 枚举从 I 出发、互不重复且相邻有共同音的原位三和弦序列，
再把选中的 `SchoenbergSymbolicProgression` 交给 `firstExerciseProgram` 生成四部排布。若不先选定具体进行，
同一请求也可保持后继槽开放，由 S2 在根位三和弦词汇表内搜索。

**后续章节增量（2026-07-19）已接入**：`SchoenbergCommonToneExercises` 扩为练习目录，新增
导和弦 `enumerateLeadingTriadProgressions` / `leadingTriadProgram`、六和弦
`enumerateFirstInversionConnectionProgressions` / `firstInversionConnectionProgram`，以及综合练习
`integratedConnectionProgram`。S2 runtime 补 `AvoidDoublingRequirement`、`ChordToneNeighborRequirement` 与
`TargetFeatureBonusRequirement`；四六、七和弦已同时接入独立练习与累计综合科技树，前一级允许词汇表
严格包含于后一级。综合练习的 `chordFilters` 可按音级、三/七和弦规模与转位取交集，并支持多个和弦
筛选。正确候选的勋伯格预备/解决 finding 前置于通用调性和声 finding。通用 `ConstraintProgramSpec`
已公开 `AvoidDoublingAt` / `AvoidScaleDegreeDoublingAt` / `ChordToneNeighbor` / `TargetFeatureBonus`，
小调分支、无共同音连接与转调科技树仍挂后续增量。

## P3 — 半音化和弦前置（schonberg-chromatic-chord.md：拿坡里 / 增六）

和弦详情、家族内音响去重、减七/增三/增六章节切片与自由练习迁移见独立
[和弦音响与多重解释 Roadmap](chord-detail-roadmap.md)；本节只保留求解器前置关系。

依赖 P1 词汇表泛化，可与 P2 并行设计：

1. **`SlotChordSpec` 支持 degree + alteration**：拿坡里 = "音阶第二位置上的另一个根音
   D♭"——即同一音级位置容纳多个根音，正是"词汇表加维度"；目前 alteration 只存在于
   `VoiceMotion`。"句法位置替代"（N6 占 II 的岗位）用场景析取表达（II 槽允许 {II, ♭II}），
   不需要新机制。
2. **`KeyPlan` facet**（rule-scenes 🚧 预留）：表达"从小下属调区借入"、副属倾向。
3. **等音双重身份**（德增六 ≡ A♭7 漂泊和弦）：依赖拼写敏感音高（`SpelledInterval` 已有），
   场景需要能按拼写+解决方向区分同一键位集合的两种身份。

## P3 — 求解器质量与健壮性

1. **indication bonus 叠加带偏**：同一规则多次命中线性叠加（六个省五 = −72），导致
   五度圈谱例永远全省五音、教材描述的自然交替形态从不出现。考虑同规则去重或衰减计分。
2. ✅ **ConstraintProgram 搜索内存**（2026-07-11）：原 BeamSearchSolver 是逐槽层级展开：
   每轮物化 beamWidth × 每状态全部四部排列，综合练习可在 512MB 测试堆触发 JVM GC 原生崩溃。
   ConstraintProgramSolver 已改用贪心 DFS：候选按完整前缀评分（规则 finding + 声部移动平顺度）
   排序后立即向下搜索，收集到 maxResults 个不同解即退出；每目标只保留 8–32 个低声部移动候选，
   并由 beamWidth × maxResults × 32 导出节点预算。BeamSearchSolver 仍供其他旧写作器使用。
3. 🚧 **ConstraintProgram 结果多样性**（设计确认，2026-07-12）：按
   [diverse-search.md](diverse-search.md) 实现“确定性首解 + 多样化重启 DFS”。后续结果从加权槽位
   恢复参考前缀，强制选择不同 frame，再以受限随机贪心完成后缀；搜索期同时要求最小槽距离
   与声部单元距离。HARD 违规始终剪枝，seed 可复现；空间不足时少返回并报告
   `diversity-exhausted`，不以近重复补齐 top-K。候选工厂在原总量上限内拆 exploit/explore 双池。
4. **CONFIRMED 验证级**（rule-scenes §4）：enumerate 目前恒降级 MAY 并附诊断；
   落地小规模 voicing 验证 + 缓存 + 预算控制。

## 已完成（本轮）

- ✅ 五度圈一/三转位交替：终止槽改 I6（V42 低音七音必须下行级进）。
- ✅ 五度圈原位完全/省五交替：`TextbookSeventhWritingSlot.fifthConstraint` 生成期收窄。
- ✅ 回归测试 `SolverApiTest.solveCircleOfFifthsVariantsProduceCandidates`（大小调 × 4 变体）。
- ✅ 七和弦符号级 `enumerate`：V7-I 返回 `SEVENTH → TRIAD` 槽，供 FormSpec 取值域使用。
- ✅ 探索页 FormSpec 渲染器：`rule-example` / `progression` 表单按 manifest 字段分发到现有控件。
