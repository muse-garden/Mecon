# 分层 DP 槽位扩展性评审（2026-08-11）

> 结论（先说要点）：
>
> 1. **槽位变多本身不会让每层状态量增长**。逐层生成标签数、状态数、保留标签数在 9 / 13 / 19 / 25 槽
>    上逐项相同，转移总数只随槽位线性增长。
> 2. **13 槽变慢与 DP 状态设计、乐理规则求值都无关**。同一批转移，关闭多样化 0.61 s、开启 24.4 s
>    （25 槽）。瓶颈是 `selectDiverseLabels` 被前沿裁剪反复调用，且相似度在比较器里重算：一次 13 槽
>    求解触发 265 次裁剪、6,065,828 次 `prefixSimilarity`。
> 3. **但状态设计确实有独立问题**：`UniqueVoiceExtreme` 的极值摘要让 DP 状态几乎无法合并
>    （全开放域下 2,688 / 2,688 个标签互不相同），分层 DP 实际退化成 beam search；多个
>    constraint history 签名是随前缀增长的累加器，不是有限自动机。
>
> 前置：[dynamic-programming-solver.md](dynamic-programming-solver.md)

## 1. 测量方法

基准与 `ConstraintDpVersusDfsQualityBenchmarkTest.longerOpenProgressionUsesDpForDiverseFreeWriting`
同形态：C 大调卡农扩展进行，`index % 4 == 1` 的槽位在同音级三/七和弦之间二选一，其余槽位固定；
标准 SATB；`FreeHarmonySolver.compile`（`FREE_CLASSICAL`，`ruleModules = emptyList()`）。

```
SearchConfig(maxResults = 3, beamWidth = 12, backend = LAYERED_DP,
             prefixDiversity = (enabled, frontierWidth = 12),
             diversity = (enabled, minChangedSlotRatio = 0.25, minChangedVoiceCellRatio = 0.10),
             dynamicProgramming = (maxCandidatesPerTarget = 48, maxLabelsPerState = 2,
                                   maxFrontierStates = 32, maxTransitionEvaluations = 1_000_000))
```

逐层数据来自 `WritingSearchTrace` 的 `LAYER_COMPLETED`；状态分量归因与阶段耗时来自一组**临时探针**
（在 `ConstraintLayeredDynamicProgrammingSolver` 内按 key 分量重算 hash、按阶段计时），测完已回滚，
未进入仓库。耗时为本机单次采样，只看量级与相对比例。

## 2. 每层状态量不随槽位增长

13 槽与 19 槽逐层完全一致（前 13 层逐项相同），说明前沿上限已经把每层规模钉死：

| layer | 候选帧 | generatedLabels | distinctStates | retainedLabels |
|---|---|---|---|---|
| 0 | 41 | 12 | 12 | 12 |
| 1 | 67 | 288 | 288 | 32 |
| 3 | 34 | 432 | 227 | 56 |
| 5 | 77 | 1680 | 558 | 76 |
| 9 | 67 | 2064 | 461 | 77 |
| 11 | 38 | 924 | 242 | 86 |
| 终层 | 35 | 1032 | 1 | 128 |

- 每层规模由 `boundedFrontierLimit`（此配置 32）× 每前驱出边宽度决定，与槽位数无关；
- 转移总数线性增长：9 槽 6,392 → 13 槽 11,336 → 19 槽 19,856 → 25 槽 27,524；
- 终层 `distinctStates = 1` 是设计使然（终层状态计划为空），`retained = 128` 来自
  `terminalLabelLimit` 的 `restartBudget × mutationPoolSize = 32 × 4`——把 DP 终层池绑到
  **重启求解器**的旋钮上，属于耦合错位。

**所以“槽位变多 → 每槽状态量爆炸”这个假设不成立。** 真正随槽位增长的只有两项：转移条数（线性）
与每条转移里 O(槽数) 的路径工作。

## 3. 状态 key 分量归因：极值摘要吃掉了全部合并率

按 key 分量分别重算 distinct 数（同一层、同一批标签）：

**形态 A：3 个开放槽（上文基准，13 槽）**

| layer | generated | full key | 去掉 history | 去掉 extremes | 只留 recentFrames |
|---|---|---|---|---|---|
| 3 | 432 | 227 | 227 | 70 | 70 |
| 5 | 1680 | 558 | 558 | 144 | 144 |
| 9 | 2064 | 461 | 461 | 96 | 96 |
| 11 | 924 | 242 | 242 | 48 | 48 |

**形态 B：全开放域（无固定目标，7 个三和弦 × 全部槽位，11 槽，关闭多样化）**

| layer | generated | full key | 去掉 history | 去掉 extremes | 只留 recentFrames |
|---|---|---|---|---|---|
| 2 | 2688 | 2688 | 2666 | 2184 | 1764 |
| 5 | 2940 | 2688 | 2650 | 1344 | 1176 |
| 9 | 2856 | 2688 | 2669 | 750 | 672 |

读法：

- **`full == 去掉 history`**：两条 target-history 谓词（`MinimumSimilarChordDistance`、
  `DistinctSimilarChordProgressions`）在这两种形态里几乎不产生额外分裂（形态 B 仅 ~1%），
  因为音级多由层号决定。它们不是当前瓶颈，但见 §6 的隐患。
- **`full` 与 `去掉 extremes` 相差 3.4–5.0 倍**：`UniqueVoiceExtreme`（`free.melody.unique-high` /
  `unique-low`）的极值摘要是唯一的实质分裂源，而且倍率随层号变深而上升。
- 形态 B 里 `full ≈ generated`：**分层 DP 在全开放域下几乎没有任何状态合并**，等价于一个带出边
  上限的 beam search。DP 相对 DFS 的优势（见主文档 §9）来自宽度受控的逐层展开，不来自合并。

极值摘要之所以不合并，是因为它保存 `(当前极值 MIDI, 出现次数)`，而 `occurrences` **没有饱和**：
`UniqueVoiceExtreme.maxOccurrences` 默认为 1，判定只关心 `occurrences > maxOccurrences`，
出现 3 次与 4 次在语义上完全等价，却是两个状态。

## 4. 耗时归因：90% 以上在前沿裁剪

13 槽 / 3 结果，逐层阶段耗时（μs，含探针开销）：

| layer | total | 候选构建 | 规则评分 | state key | **前沿裁剪** | 终层全局 |
|---|---|---|---|---|---|---|
| 4 | 449,008 | 7,288 | 28,945 | 2,549 | **381,970** | 0 |
| 8 | 912,798 | 5,658 | 14,670 | 1,396 | **878,385** | 0 |
| 10 | 1,176,698 | 4,675 | 8,926 | 1,251 | **1,118,844** | 0 |
| 终层 | 219,056 | 3,327 | 8,186 | 0 | 0 | 204,475 |

同一次求解累计：**265 次** `retainBestStateGroups`（每层 7–52 次），**6,065,828 次**
`prefixSimilarity`。规则评分合计约 0.25 s，state key 合计约 0.02 s，候选构建约 0.09 s。

开关对照（同一进行、同一批转移数）：

| 槽位 | 转移数 | 多样化关闭 | 多样化开启 |
|---|---|---|---|
| 9 | ~6.0k | 0.95 s（含预热） | 2.61 s |
| 13 | ~11.0k | **0.61 s** | 6.26 s |
| 19 | ~19.9k | **0.86 s** | 16.84 s |
| 25 | ~27.9k | **0.73 s** | 24.45 s |

关闭多样化后，25 槽 27.9k 条转移只要 0.73 s，且**几乎不随槽位增长**——DP 本身的算法与状态设计
在这个规模上完全够用。开启多样化后同样的搜索规模慢 10–35 倍，且随槽位超线性增长。

## 5. 根因

`ConstraintLayeredDynamicProgrammingSolver.selectDiverseLabels` 的选择循环：

```kotlin
while (selected.size < limit && remaining.isNotEmpty()) {
    val pool = remaining.filter { it.score.total <= qualityCeiling }.ifEmpty { remaining }
    val next = pool.minWith(
        compareBy<DpLabel> { candidate ->
            val similarity = selected.maxOf { chosen ->
                search.space.prefixSimilarity(candidate.state, chosen.state)   // ← 每次比较都重算
            }
            candidate.score.total + similarity * similarityWeight
        }...
    )
```

三层放大叠加：

1. `compareBy { selector }` 对**每次比较的两个操作数**都调用一次 selector，`minWith` 有 n−1 次比较
   → 每轮 2(n−1) 次 selector；
2. 每次 selector 又对 `selected` 里已选的 k 个标签各算一次 `prefixSimilarity`
   → 整个循环约 `n × limit²` 次相似度调用（本例 n≈65、limit=32，实测每次裁剪约 2.3 万次）；
3. `prefixSimilarity` 本身是 O(槽数 × 声部数) 次 `pitchFor(voice)`——按 `VoiceId` 做 map 查找，
   13 槽一次调用就是 104 次字符串 hash 查找。

而 `retainBestStateGroups` 是在**转移内层循环里**触发的：`grouped.size > boundedFrontierLimit × 2`
时裁到 `boundedFrontierLimit`，于是每再新增 `limit` 个状态就再触发一次——典型的裁剪抖动。
§3 的合并失效在这里二次放大：状态越不合并，`grouped.size` 涨得越快，裁剪次数越多。

次要项：`retainBestStateGroups` 里 `ordered.filterNot { it in diverse }` 对 `List<Map.Entry>` 做线性
`in` 查找，是 O(n²) 且比较的是 entry（含 label 列表）。

## 6. 状态设计评审（与性能独立的正确性/可扩展性隐患）

主文档 §2 称「`DpStateKey` 不保存完整路径，只保存最近音高投影、极值、谓词自动机和复合真值向量」。
逐项核对 `DpConstraintHistorySignature` 的实现，有几项并不是有限自动机：

| 签名 | 现状 | 问题 | 建议表示 |
|---|---|---|---|
| `DpRootProgressionHistorySignature` | `applicableDegrees: List<Int>` 逐槽追加 | **就是完整音级前缀**，一旦 `RootProgressionPreference` 生效，状态永不合并 | 增量维护 scoring policy 的可分解分量；不可分解则显式 fail closed |
| `DpMinimumDistanceHistorySignature` | `lastSlotByDegree` 存**绝对槽号** | 槽 5 用过 vs 槽 7 用过永远是两个状态，即使都早已超出 `minimumSlotDistance` | 存 `min(slot − last, minimumSlotDistance)` 的相对距离 |
| `DpDistinctProgressionHistorySignature` | `usedPairs: Set<Int>` | 集合随前缀增长；`violated` 已是吸收态却仍继续累积 pair | 49 位 `Long` 掩码；`violated` 后丢弃掩码 |
| `DpSameSonorityHistorySignature` | `selectedPitchClassSets: List<List<Int>>` | 逐槽追加整份音级集合 | 只需 `(首个集合, 是否全部相同)` |
| `DpIdentityHistorySignature` | `seen: Set<String>` | 语义上必需（窗口内互异），但用字符串集合做 key | 词表位掩码 |
| `DpExtremeSummary.occurrences` | 无上限计数 | 语义只需 `≤ maxOccurrences` / `> maxOccurrences` | 饱和到 `maxOccurrences + 1` |

这些在本次两种基准形态里几乎没有代价（音级多由层号决定），但 commit `a8fc6978` 的目标正是把 DP 推向
**真正开放的和声域**，届时前四项会直接把合并率打到 0。

其它设计观察：

- **§8 的终层 branch-and-bound 在多样化路径上从不生效**：`terminalGlobalLowerBound` 在
  `preserveDiverseLabels` 时为 `null`，而 `AUTO` 恰恰只在多样化开启时才选 DP。实测终层全局求值
  1,032 次 = 全部终层候选，一次都没剪掉。
- **出边宽度在多样化时被放到 `candidateLimit`**，正是它把每层 generatedLabels 推到 1,000+，喂给
  §5 的裁剪抖动。这与主文档 §10 第 1 条（把出边宽度从 `maxResults` 解耦）是同一件事，应一并处理。
- **终层标签池绑定 `restartBudget × mutationPoolSize`**（=128）与 DP 无关，应独立配置。

## 7. 建议（按性价比排序）

1. **相似度增量维护**（预计 10–30× 收益，改动最小）：`selectDiverseLabels` 为每个候选维护一个
   `maxSimilarityToSelected` 数组，每选中一个标签只对剩余候选 O(n) 更新一次，并把选择键**先算好再
   排序**（decorate-sort-undecorate），避免 `compareBy` 在比较器内重算。相似度调用从 `n × limit²`
   降到 `n × limit`。
2. **前沿裁剪不做多样化**：内层循环里的 `retainBestStateGroups` 是内存保护，按 comparator 顺序裁剪
   即可；多样化选择只在层末做一次。同时把触发阈值改为摊还式（裁到 `limit` 后阈值仍为 `2 × limit`
   会立刻再次触发，应裁到更低水位或改用定容堆）。
3. **`prefixSimilarity` 走 MIDI 签名**：label 上已有 `DpVoiceFrameSignature.midi`（`IntArray`），
   在 label 上顺带维护累积路径签名，避免 `pitchFor` 的 map 查找。
4. **极值摘要瘦身**：`occurrences` 饱和到 `maxOccurrences + 1`；进一步在 BOUNDED 模式下评估把
   `UniqueVoiceExtreme` 降为 terminal rerank（它已是 `Prefer` 软约束且带 `futureCanSupersedeExtreme`
   可恢复语义），可把合并率提高约 3.4–5×。EXACT 保持现状。
5. **history 签名常量化**（§6 表格），为开放和声域做准备；`RootProgressionPreference` 若无法有限
   表示，应在 planner 里 fail closed 而不是塞进完整前缀。
   > 2026-08-11 部分解决：和弦选择规则在自由练习里已降为 `ConstraintModality.Remind`，planner
   > 不再为 `Annotate` / `Remind` 收集状态，因此这四个累加式签名不会出现在自由练习的 DP key
   > 里（见 [free-harmony-solver.md](free-harmony-solver.md) §4.0）。勋伯格章节自己的开放域
   > program 仍以 `Prefer` 使用它们——求解器一旦真正接手和弦选择，本条仍需按表格落实。
6. **把终层 BnB 的下界扩展到多样化路径**，或至少在 trace 里显式报告「下界已关闭」。
7. **基准配置入库**：把本文的 (槽位, maxResults, 多样化开关) 曲线固化成一个可选运行的基准测试，
   耗时只打印、转移数与逐层状态数做断言，避免再出现无法复现的历史数字。

## 8. 与现有文档的出入

- 主文档 §9.1 记「13 槽 / 3 结果约 25 s，17 槽超过 3 分钟」。本次同形态实测 13 槽 / 3 结果 6.26 s、
  17 槽 12.79 s、25 槽 24.45 s（11 槽 / 2 结果 1.53 s 与原记录 1.32 s 吻合）。原记录未写明 13 / 17 槽
  的具体配置，无法复现，应按第 7 条第 7 项固化后重写该节。
- 主文档 §9.1 的结论「不应通过增大预算掩盖**指数**增长」在测量上不成立：本形态下转移数随槽位
  **线性**增长，耗时的超线性完全来自 §5 的多样化裁剪。滑动窗口/分段写作的产品建议仍然成立，
  但理由应改为「多样化选择的实现成本」，修好之后长窗口上限可以显著抬高。
- 主文档 §2 的「不保存完整路径」需按 §6 修正为「除 `RootProgressionPreference` 外」，或把该谓词
  改造/降级。
