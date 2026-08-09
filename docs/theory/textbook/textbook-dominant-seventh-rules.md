# 属七和弦教材规则

> 代码入口：`theory/src/commonMain/kotlin/com/mecon/theory/textbook/DominantSeventhRules.kt`、
> `DominantSeventhWritingSolver.kt`、`DominantSeventhRuleCatalog.kt`
>
> 状态：已接入探索模式求解与正误对照输出。规则场景已注册到 `RuleCatalog`，出谱走
> `TextbookSeventhWritingSolver`，不复用三和弦 slot。

## 1. 覆盖范围

`DominantSeventhRules` 当前覆盖：

- 七和弦七音通常下行级进解决；上行解决作为错误对照入口，例外留待后续章节。
- 属七和弦性质必须是大小七和弦；小调属七必须包含升高导音。
- 导音在外声部时，上行级进解决到主音。
- 原位 `V7-I`：
  - 完整 `V7` 常解决到省略五音、三根一三音的 `I`。
  - 不完全 `V7`（省略五音、重复根音）可解决到完整 `I`。
  - 完整 `V7` 若导音在内声部，可解决到完整 `I`。
  - 为保留完整 `I` 造成平行五度时，即使导音上行解决，也作为错误对照。
- `V7-VI` 阻碍进行。
- 转位 `V7` 的三音、七音倾向解决；第三转位 `V42-I6`；第二转位的经过式提示。
- 七音预备需与解决放在三和弦上下文中检查：延留、经过、邻音、倚音，以及上方非相邻预备的错误对照。
- 通用七和弦省略：根音和七音不可省略；若要省略，优先省略五音，其次三音。
- II7：默认解决到 V，形成 `II-V-I`；也可到终止四六或到导和弦替代 V。
- 大调导七：半减七，具有属功能，可直接到 I，也可七音下行到 V7；到 I 时允许重复三音以避开平行五度。
- 小调导七：完全减七，含升导音；可到 I 或 V7；到 I 时标记减五度到纯五度的避让问题。
- 五度圈模进：识别 `4-7-3-6-2-5-1` 中前六个和弦为七和弦的写法，并区分原位完全/省五交替、一/三转位交替、二转位/原位交替。
  - 一/三转位交替以 `I6` 收束（末槽 V7 为第三转位，低音七音必须下行级进）。
  - 原位完全/省五交替按槽收窄五音完整性（`SeventhFifthConstraint`）：交替形态只在完整解的
    `checkScore` 可见，若只靠 `REQUIRE_INDICATION` 末端过滤，beam 会在前两槽丢弃完全和弦前缀导致无解。

## 2. 求解入口

探索模式仍通过 `SolverEngine.solve(SolveRequest(...))` 调用。`ExplorationRequestRunner` 发现所选规则属于
`DOMINANT_SEVENTH_CHAPTER` 时，分流到 `TextbookSeventhWritingSolver`：

- `TextbookSeventhWritingSlot` 表示七和弦/三和弦目标与允许的七和弦转位，可附 `fifthConstraint`
  （REQUIRE_FIFTH / OMIT_FIFTH）在生成阶段收窄五音完整性。
- `TextbookSeventhTarget` 固定低音 pitch class，其余声部从目标和弦 pitch class 枚举。
- 四部禁则与旋律规则继续复用 `FourPartTextbookWritingRuleProvider` 与
  `MelodyTextbookWritingRuleProvider`。

`RuleCatalog.scenes()` 为属七规则提供 solver-api/manifest 可见的场景摘要；当前 `SceneMatcher` 的词汇表仍以三和弦为主，因此实际出谱不要从这些场景实例化七和弦。

## 3. 正误对照

`CellOutput` 新增 `comparisonGroups`。当请求包含 `DemonstrationSpec` 时，属七 runner 会尝试生成：

- 正确例：同一上下文下不要求目标违规。
- 错误例：同一上下文下要求目标规则产生 `VIOLATION`，并将 finding 标记为 `isDemonstrationTarget`。

桌面探索页仍保留普通候选列表；候选 1/2 会提示“正确例 / 错误例”。后续若需要并排谱面，可直接消费
`comparisonGroups.correctCandidateIndex` 与 `incorrectCandidateIndex`。

## 4. 测试

- `DominantSeventhRulesTest` 覆盖七音下行/上行、外声部导音、原位 `V7-I` 省略五音、完整 `I` 平行五度错误、七音预备。
- `DominantSeventhRulesTest` 也覆盖 II7、大小调导七性质与五度圈转位交替。
- `SolverApiTest.solveDominantSeventhViolationReturnsComparisonPair` 覆盖 solver-api 便捷入口与正误对照输出。
- `SolverApiTest.solveSupertonicSeventhExampleUsesSeventhSolver` 覆盖 II7 通过探索模式出谱。
