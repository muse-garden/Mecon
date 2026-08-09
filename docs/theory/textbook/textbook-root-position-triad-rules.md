# 教材原位三和弦连接规则

> 代码入口：
> `theory/src/commonMain/kotlin/com/mecon/theory/textbook/RootPositionTriadRules.kt`、
> `theory/src/commonMain/kotlin/com/mecon/theory/textbook/RootPositionTriadSolver.kt`

## 1. 适用范围

这批规则只检查**已给定的原位三和弦连接**，不负责调性构建、和弦识别或罗马数字分析。调用方需要提供：

- `Key`
- 前后两个 `NaturalTriad`
- 当前 `FixedVoiceTransition`
- 后一个和弦是否为终止 1 级和弦

因此它可以直接服务两类场景：

- 用户作业检查：从已有和弦标注/教程上下文传入连接。
- 写作搜索：候选生成后只检查当前 transition，不扫描全谱。

## 2. 适用性与章节约束包

“必须为原位和弦”是规则适用范围问题，不是原位连接规则自身的违规结果。`applicability(transition, connection)` 会检查前后低音是否为根音：

- 若适用，`checkTransition()` 返回原位连接 finding。
- 若不适用，`checkTransition()` 返回空列表，并通过 `RuleApplicability.suggestedRuleSet = "inverted-triad"` 提示调度器切换到转位和弦连接规则。

规则本体始终产生和弦外音、缺音、重复音与连接模式 finding；练习级别不再改变规则是否执行。
`TextbookTriadConstraintPreset.GENERAL / INTRODUCTORY` 只决定哪些建议编译为生成期硬约束，
并与 `RuleProfile` 一起组成章节约束包。

章节还提供 `RootPositionTriadRules.INTRODUCTORY_PROFILE` 用于调解跨规则结果：

- 将通用 `MelodyTextbookRules.LEADING_TONE_RESOLUTION` 从 `SOFT` 降为 `HINT`。
- 当 `INNER_LEADING_TONE_LEAP` 或 `MAJOR_DOMINANT_TO_SIXTH_INNER_LEADING_TONE` 已解释同一组锚点时，suppress 通用导音倾向 finding，避免两个规则同时出现在 UI。

## 3. 纵向配置规则

`checkVerticality(verticality, triad, key, isFinal)` 覆盖：

- 和弦内音要求，返回 `HARD`。
- 四声部及以上：除最后 1 级和弦外，一般不省略和弦音，返回 `SOFT`。
- 四声部最后 1 级：可省略五音，但应保留一个三音和三个根音。
- 三声部：可省略五音；最后 1 级只可为三次出现的根音。
- 原位三和弦通常重复根音，未重复根音返回 `SOFT`。
- 7 级音几乎永远不重复，重复时返回 `SOFT`。

这些规则会返回 `RuleFinding`，其中违规类 finding 使用 `VIOLATION`；后续 UI 可用 anchors 标出具体音符。

## 4. 连接模式 indication

用户说明的常用连接模式不是唯一合法写法。因此 `RootPositionTriadRules` 对匹配模式返回 `INDICATION`，没有匹配时不直接判错：

- 同和弦反复：低音可作八度变换，上方声部可在一般原则内自由变换。
- 根音四（五）度关系：
  - 共同音保持，其他两音与低音反向同向级进。
  - 不保持共同音，三个上方声部与低音反向进行，跳进不超过三度。
  - 共同音保持，一音级进，一音反向跳进，用于开放与密集排列转换。
- 根音三（六）度关系：两个共同音保持，另一音级进。
- 根音二（七）度关系：低音级进，上方三声部反向作较平稳进行。
- 大调 V-vi：内声部 7 级音可级进到 6 级。
- 四（五）度关系中，导音 7 在内声部作跳进进行时可接受。

这类 finding 的用途是给用户解释“这里用了教材中的常见写法”，也可在搜索评分中加分。

## 5. 禁则与例外

小调属和弦到六级时，如果前一和弦使用升 5，升 5 不可进行到 4；这会造成不平顺的增二度。该规则返回 `HARD`，可用于搜索剪枝。

注意：内声部导音跳进可接受、以及大调 V-vi 内声部 7-6，都会与更早的通用导音解决规则发生表面冲突。当前通过 `INTRODUCTORY_PROFILE` 处理：普通未解决导音降级为提示；若连接规则已说明该导音进行可接受，则不再展示通用导音提示。

## 6. 写作求解器

`RootPositionTriadSolver` 只是原位三和弦章节的兼容门面，不拥有候选枚举或搜索流程：

- 输入 `RootPositionTriadWritingProblem`，包含 `Key`、给定的原位三和弦序列、约束 preset、规则 profile、音域 profile 与搜索配置。
- 门面转成 `TextbookTriadWritingProblem`，再由 `toConstraintProgram()` 注入完整性、重复根音与避免重复导音 requirement。
- 规则由 `ChordRuleDispatcher` 按 `TargetSelector` 选择三和弦模块。
- 四部和声禁则与旋律规则通过 `FourPartTextbookWritingRuleProvider`、`MelodyTextbookWritingRuleProvider` 复用，不属于原位三和弦章节。
- 候选枚举、局部上下文、profile 调解、评分与 top-K 搜索统一由 `ConstraintProgramSolver` 处理。

输出 `RootPositionTriadSolution` 只是把通用 `ChordVoicing` 转回本章易用结构。新增和弦族只需目标实现、约束包与规则模块，不再增加章节候选工厂。

## 7. 规则目录

本章可教学规则已注册到 `RootPositionTriadRuleCatalog`，并由 `RuleCatalog` 聚合：

- 四（五）度关系下的三种连接模式为互斥选择。
- `INNER_LEADING_TONE_LEAP` 是附属倾向，需与四（五）度连接模式之一共选。
- `MINOR_RAISED_FIFTH_TO_FOURTH` 可作为错误示例目标，探索模式会编译为 `REQUIRE_VIOLATION`。
- 纵向配置类规则由章节约束包在生成期收窄，目录中保留 descriptor，但不作为层级 1 直接勾选项。

## 8. 测试约定

新增原位三和弦规则时至少覆盖：

- 一个纵向配置违规；
- 一个不适用上下文，例如转位和弦应交给转位规则而非报错；
- 一个连接模式 indication；
- 一个跨规则调解用例，避免与通用导音规则重复提示；
- 一个“其他写法不应误判为错误”的例外；
- 若规则用于搜索剪枝，必须覆盖 `checkTransition()` 的局部入口。

新增求解器能力时至少覆盖：

- 能解出指定和弦序列；
- 输出不含 `HARD` finding；
- profile suppression 后不会重复展示互相解释同一锚点的规则；
- `ScoreBreakdown` 保留规则贡献，供 UI 展示。
