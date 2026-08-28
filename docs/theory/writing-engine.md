# 写作任务与规则引擎基础设施

> 代码入口：
> `theory/src/commonMain/kotlin/com/mecon/theory/TheoryRule.kt`、
> `theory/src/commonMain/kotlin/com/mecon/theory/WritingTask.kt`、
> `theory/src/commonMain/kotlin/com/mecon/theory/WritingSolver.kt`、
> `theory/src/commonMain/kotlin/com/mecon/theory/FixedVoiceWritingSolver.kt`

## 1. 目标

写作引擎不以“四部和声”为根模型。通用任务应描述：

- 哪些材料固定；
- 哪些时间槽需要生成；
- 候选空间如何枚举；
- 规则从哪些视角检查与评分。

四部和声连接、给旋律配和声、复调写作都应复用同一批规则结果、锚点与评分结构，只替换 `CandidateSpace`。

## 2. 任务模型

`WritingTask` 由以下部分组成：

- `WritingTexture`：写作织体，如 `FOUR_PART_FIXED_VOICE`、`MELODY_HARMONIZATION`、`COUNTERPOINT`。
- `WritingTimeline`：任务范围与可生成的时间槽。
- `MaterialConstraint`：固定材料，如给定旋律、固定低音或已写出的声部事件。
- `WritingTarget`：写作目标，可是和声、旋律、复调、终止式等。
- `RuleProfile`：教材章节或风格预设，用于开关规则、覆盖严重度或权重。
- `SearchConfig`：top-K、beam width、多样化权重等搜索参数。
- `WritingTaskPlan`：多阶段任务计划。配和声、装饰音、声部细化等不应强行合成一次巨大搜索，而应让前一阶段输出成为后一阶段的 `fixedMaterial`。

原则：`HarmonicTarget` 只是目标类型之一。复调写作可以只给 `ContrapuntalTarget`；给旋律配和声则把旋律放入 `fixedMaterial`，再配合较宽的和声目标搜索。

## 3. 候选空间

搜索器通过 `CandidateSpace<State, Candidate>` 与具体写作任务解耦：

```kotlin
interface CandidateSpace<State, Candidate> {
    fun initial(task: WritingTask): State
    fun candidates(state: State, task: WritingTask): List<Candidate>
    fun apply(state: State, candidate: Candidate): State
}
```

需要评分和 top-K 搜索时，候选空间实现 `ScoredCandidateSpace`：

- `isComplete()` 判断状态是否已经写完。
- `score()` 返回 `ScoreBreakdown`，包含调解后的 `RuleFinding` 与分数贡献。
- `diversityKey()` 用于精确排序/去重；`diversityGroupKey()` 用于最终结果层的抽象合并（如只差八度的固定声部解），`similarity()` 配合 `SearchConfig.diversityWeight` 做相似度惩罚。

`BeamSearchSolver.solve()` 提供通用 beam search。它只依赖 `WritingTask` 与 `ScoredCandidateSpace`，遇到 `HARD` finding 会剪枝，其余 finding 按候选空间给出的 `ScoreBreakdown` 排序。完整结果先按 `diversityGroupKey()` 合并，再按可选相似度惩罚贪心取 top-K。

ConstraintProgram 的后续搜索契约见 [diverse-search.md](diverse-search.md)：保留确定性首解，
再以强制变异重启、逐步结构 key 与最小距离约束后续结果，不恢复层级 beam。候选空间需增加
`stepDiversityKey()` 描述最后一步的结构身份；固定声部实现包含目标身份与各声部 pitch class。
合法空间不足时允许少返回并给出诊断。

固定声部写作不应为每章实现独立 solver。`FixedVoiceWritingCandidateSpace` 把公共流程集中起来：

- `FixedVoiceWritingFrame<T>` 表示某个时间槽下，各固定声部生成出的音高；`T` 是任务目标，可是三和弦、七和弦、旋律配和声目标或复调目标。
- `FixedVoiceTargetProvider<T>` 根据当前状态给出下一步目标候选。固定和弦序列只是它的一个特例；给旋律配和声时，目标本身也可以进入搜索空间。
- `FixedVoiceCandidateFactory<T>` 只负责根据当前状态和目标枚举下一批 frame。
- `FixedVoiceWritingRuleProvider<T>` 以可插拔方式提供纵向、相邻 transition、全局 score 三类局部检查。
- `FixedVoiceVoicingEnumerator` 提供按声部音域与 pitch-class 集合枚举音高的通用工具，章节只补自己的约束。

教材章节的入口应尽量是薄适配器：声明目标、候选约束和规则 provider，再交给 `BeamSearchSolver` + `FixedVoiceWritingCandidateSpace`。

第一批实现顺序：

1. `FixedVoiceWritingCandidateSpace`：固定声部写作通用候选空间。✅
2. `MelodyHarmonizationCandidateSpace`：固定旋律，枚举和声、低音与内声部。
3. `CounterpointCandidateSpace`：固定 cantus firmus，按音程关系枚举对位声部。

`ChoraleRealizationSpace`（[chorale-harmonization.md](chorale-harmonization.md)）是第一个**装饰阶段**
候选空间：状态按和声跨度推进，一步同时决定全部声部在该跨度内的节奏与填充。它验证了本节的两条
设计——阶段间以 fixedMaterial 衔接（骨架来自 `ConstraintProgramSolver`），以及跨织体关注点以规则
身份进入任意空间（voice-leading 张力度量在这里对表面评分）。3 号的「各声部 frontier 独立推进」是
它放开「全声部同跨度」约束后的形态。

候选约束必须可解释。若某条规则在生成阶段缩小枚举范围，例如 V-I 中导音必须上行解决，它也必须在检查阶段返回对应 `RuleFinding`，以便用户写作检查和搜索解释一致。

**空间分离、评价贯通**：候选空间决定"谁在动"，规则 provider 决定"谁在看"。实际创作中
和声与复调并不分开（写外音时想引入动机、写对位时考虑和声走向与转调），但架构对此的回答
不是合并搜索空间——跨织体关注点以**规则 / 目标 / 阶段间投影**的身份进入任何空间：
`WritingTask.targets` 可同时携带 `Contrapuntal`（驱动枚举）与 `Harmonic` / `KeyPlan`
（逐拍评价上下文）；动机是逐音推进的模式自动机规则，任何逐音空间都能挂
（constraint-program §3.1 配方，figuration §9）。长期的统一状态模型是"各声部 frontier
在 MeterPlan 时间轴上独立推进的事件流"（即 `CounterpointCandidateSpace` 的形态），
`FixedVoiceWritingFrame` 是全声部同节奏推进的特例——统一发生在引擎层（本节接口）与
概念层，不追求用单一事件粒度求解器跑所有织体：和弦目标作为一等对象支撑着场景匹配、
`ChordAt` / arity 分发与 finding 解释，四部和声保持槽粒度。

## 4. 规则结果

旧的 `RuleDiagnostic` 只表达违规。新基础设施使用 `RuleFinding`：

- `VIOLATION`：错误或警告，参与剪枝/扣分。
- `WARNING`：较弱的不推荐写法。
- `HINT`：教学提示。
- `INDICATION`：正确写法的标记，例如终止式中的 7-1 解决。

`anchors` 标示主音符；`relatedAnchors` 标示同一规则涉及的其他音符，供 UI 对主音符和关联音符使用不同样式。`scoreIntent = EXPLANATORY` 表示该 finding 只解释许可性或语境性写法，不自动奖励候选；`scoreDelta` 可为需要梯度的写法显式覆盖评分。

## 5. 规则适用性

规则检查前应先判断适用性。`RuleApplicability` 只回答“这条规则是否应该接管当前上下文”，不把不适用当成错误：

- 原位三和弦连接规则遇到转位和弦，应返回 not applicable，并建议切到转位连接规则。
- 转位连接规则、和弦外音规则、复调规则也应各自声明适用上下文。
- 练习要求（例如本章只允许原位、当前练习禁止和弦外音）应放在 exercise policy / rule profile，而不是写死在连接规则里。

调度器的职责是按适用性选择规则集合；规则本体只在适用时返回 `RuleFinding`。

## 6. 规则调解

多个规则同时命中同一组音符时，应由 `RuleProfile` 做调解：

- `RuleConfig.severityOverride` 可把某条规则在当前章节中降级，例如把通用导音倾向从 `SOFT` 降到 `HINT`。
- `RuleSuppression` 可声明“若 A 规则已解释同一锚点，则不再展示 B 规则”，避免用户看到两个互相打架的提示。
- `RuleRequirement` 把探索模式中“要求出现某写法”或“要求演示某违规”变成 solver 的一等输入：
  `REQUIRE_INDICATION` 要求完整候选中出现对应 `INDICATION`，`REQUIRE_VIOLATION` 暂时豁免目标违规的硬剪枝并要求它出现，`FORBID` 反向排除某写法。
- 调解发生在各规则生成 finding 之后、UI 展示/搜索评分之前。

例：原位三和弦章节中，`INNER_LEADING_TONE_LEAP` 表示内声部导音跳进在该连接中可接受；若它与 `LEADING_TONE_RESOLUTION` 锚点重叠，profile 应 suppress 后者，只保留连接规则解释。

## 7. 局部检查

搜索内层不能反复扫描全谱。规则实现应优先提供局部入口：

- 纵向规则检查当前 `FixedVoiceVerticality`。
- 声部进行规则检查当前 `FixedVoiceTransition`。
- 和声连接规则检查当前 `TransitionContext.harmonicConnection`。
- 复调规则检查当前 transition 的音程关系。

`VoiceLeadingAnalysis.transitions(score)` 产出相邻纵向快照；`transitionsTouching(score, eventIds)` 可按受影响事件筛出局部 transition。四部规则已提供：

```kotlin
FourPartTextbookRules.checkFixedVoiceTransition(transition)
```

固定声部写作层在 `apply()` 候选时缓存新 frame 的纵向 finding 与新 transition finding；`score()` 直接复用这些局部 finding，只额外执行确实需要全局视野的 `checkScore()`。全谱入口保留给用户作业检查、导入后批量扫描和回归测试。

## 8. 当前取舍

已落地：

- 固定声部求解层已支持 target provider，目标不再必须是预先确定的 `List<T>`。
- 纵向与 transition finding 已随状态增量缓存，避免搜索评分阶段反复扫描同一前缀。
- 解释性 `INDICATION` 与目标型 `INDICATION` 已在评分语义上区分，避免“可以这样写”的说明变成无条件优化目标。
- 最终结果层已支持抽象多样性合并与相似度惩罚，固定声部写作默认把纯八度复制品合并到同一候选桶。
- `WritingTaskPlan` 提供 staged solving 的数据模型，后续可把“选和弦 → 配置声部 → 加装饰”串成任务流水线。

暂不硬塞进当前实现：

- 复调、主题发展、模进等需要新的状态模型；不要把独立节奏、tie/rest、动机变形塞进 `FixedVoiceWritingFrame`。
  和弦外音的独立节奏模型（`FiguredLine`，含子槽细分）设计在 [figuration.md](figuration.md)：
  作为 `WritingTaskPlan` 的第二阶段（骨架 → 装饰），骨架搜索不感知外音，与本条一致。
- 多目标联合搜索先不做。更可控的路径是 staged solving，而不是把和弦、节奏、动机变形放进同一个 beam。
  真正需要联合优化的全局规划（如同时选和弦序列与模仿点位置）也不进 beam——那是创作层的
  迭代（[../analysis/composition.md](../analysis/composition.md) 创作路径 + `refine` pins +
  分析层级栈的跨层反馈），求解器只负责"给定规划，解出实现并解释哪里不满足"。
- `checkScore()` 仍可做全局检查；若某条全局规则进入高频搜索路径，再为该规则设计专门的增量摘要。
- 逐声部推进不改变搜索树大小（分支乘积相同），且 beam 在"半帧状态"上排序是拿不完整
  信息做淘汰（重复音/排列/完整性都是帧级性质），会加剧"正确前缀被挤出 beam"。
  槽内优化的正确拿法是**枚举器内早剪枝**：`FixedVoiceVoicingEnumerator` 的 DFS 中途做
  与前帧的逐声部对 HARD 检查（平行五八、交叉）+ 惰性产出（替代 `beamWidth × 数百帧`
  的全量物化，即 P3 beam 内存问题的主因）+ 候选按与前帧声部移动距离排序（value ordering）；
  beam 仍只在槽边界排序。

## 9. 后续落地

教材规则按教程顺序逐条加入：

1. 规则注册与调度器：消费 `RuleApplicability`，自动在原位、转位、七和弦、外音、复调等规则集之间选择。
2. 连接类型库：V-I、V7-I、vii°-I、ii-V、终止式等。
3. 连接规则：导音解决、七音解决、减和弦预备与解决。
4. tag/层级 suppression：用 `RuleTag` 和规则层级表达“章节连接规则压制通用倾向规则”，减少成对枚举。
5. 槽位调性上下文：把 `HarmonicState.key` 接入 provider，支持离调与转调。
6. 配和声任务组合：先选和声骨架，再把结果作为 fixed material 进入 voicing 任务。
7. 乐理分析插件：规则计数 AnnotationStaff、音符着色、右侧 finding 详情与 hover 联动。
