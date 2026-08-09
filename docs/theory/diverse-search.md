# 约束程序的多样化搜索

> 状态：**已实现**（首版）。`GreedyDepthFirstSolver` 在 `SearchConfig.diversity.enabled` 时委托
> `DiversifiedRestartSolver`（`theory/.../DiversifiedRestartSolver.kt`）：阶段 A 确定性首解 + 阶段 B
> 强制变异重启 + 受限随机贪心 + 距离门槛 + 重合剪枝 + 候选双池（`ChordTargetCandidateFactory`）。
> 协议侧 `SearchSpec` 增 `diversify`/`seed`，未凑满 top-K 返回 `diversity-exhausted` 诊断；桌面
> ExplorationView 三种练习均可选候选个数并开关多样化。验收见
> `DiversifiedRestartSolverTest`。**实现偏差**：`DiversitySearchConfig.enabled` 默认 `false`（本文
> 原设 `true`）——关闭时保持既有确定性 DFS，开启由用户 toggle 驱动，保证首解稳定与既有测试不变。
> §11 双切点杂交仍未实现。相关：
> [writing-engine.md](writing-engine.md)（通用候选空间）、
> [constraint-program.md](constraint-program.md)（约束程序）、
> [solver-api.md](solver-api.md)（公开搜索协议）。

## 1. 问题与目标

当前 `GreedyDepthFirstSolver` 每层按完整前缀评分确定性排序，沿最优分支深入，得到
`maxResults` 个解后立即停止。它比逐层物化全部状态的 beam search 快且省内存，但多个结果
通常只在末端分叉；最终去重只能删除重复结果，不能让搜索访问更早的不同分支。

本次演进必须同时满足：

1. 保留 DFS 的 `O(depth)` 活跃路径内存模型和增量 HARD 剪枝；
2. 第一候选仍是确定性贪心首解，单结果请求不受随机性影响；
3. 后续候选主动从较早槽位分叉，而不是依赖自然回溯到末端兄弟节点；
4. 多样性在搜索期约束，不能只在完整候选上重排；
5. HARD 合法性边界不放宽；相同 seed 可复现完整结果与 trace；
6. 若合法空间不足以满足距离门槛，允许少返回，不能用近重复结果凑满 top-K。

## 2. 算法概览

采用 **多样化重启 DFS（diversified restart DFS）**。它是约束空间中的
ruin-and-recreate：保留参考解的一段前缀，从选定槽位强制变异，再用受限随机贪心 DFS
重建后缀。

### 2.1 阶段 A：确定性首解

先执行现有贪心 DFS，且阶段 A 固定只请求一个首解；即使最终 `maxResults > 1`，也不在确定性
搜索里先取满结果。搜索同时保存递归栈上的不可变前缀状态：

```text
S0 -> S1 -> S2 -> ... -> Sn
```

`Sm` 表示已完成 `[0, m)` 槽的状态，可直接作为变异重启点。首解不使用随机扰动；若首解
不存在，直接返回无解，不进入多样化阶段。

### 2.2 阶段 B：强制变异重启

生成后续结果时重复：

1. 从已接收结果中选择一个 reference；
2. 选择可变槽 `m`，恢复 reference 的前缀状态 `Sm`；
3. 枚举第 `m` 槽所有未触发 HARD finding 的子状态；
4. 排除与 reference 第 `m` 槽具有相同逐步结构 key 的子状态，保证实际分叉；
5. 从替代子状态继续受限随机贪心 DFS；
6. 完整合法候选通过距离门槛后加入候选池，否则继续回溯或更换重启点；
7. 当前 `(reference, m, alternativeKey)` 邻域耗尽后记录 tabu，避免重复探索。

reference 不应永远固定为首解。优先选择尚有较多未探索槽位的已接收结果，使搜索逐步覆盖
多个解簇，而不是围绕第一候选反复生成局部变体。

### 2.3 变异槽调度

可变槽排除固定材料槽和只有一个合法逐步结构 key 的槽。其余槽按以下原则加权：

```text
weight(m) = earlyBias(m) * penaltyBias(deltaScore(m + 1)) / (1 + attemptsAt(m))
```

- `earlyBias` 给予较早槽位更高但有限的权重，直接对抗末端分叉；
- `deltaScore(m + 1)` 是追加下一和弦时新增的正扣分；扣分越高，越优先从它的前一和弦 `m`
  分叉并重建整条关系，负增量/奖励按 0 处理；
- 已尝试次数越多，权重越低；
- 选择由显式 seed 驱动；
- 所有可变槽最终都必须有机会被访问，不能永久饿死后半段。

### 2.4 确定性前缀多样化

`PrefixDiversitySearchConfig` 是与阶段 B 重启正交的可选策略。启用后，求解器逐层保留低分且
排列不同的前缀：先为外声部音区谱系保留代表，再按当前排列分层，余量使用
`score + prefixSimilarity * similarityWeight` 排序。同一谱系可以保留多个延续，避免“每个首和弦
只走一条局部贪心路径”。完整解仍按原始 `ScoreBreakdown.total` 排序，多样性不改写最终乐理分数。

候选工厂必须给该策略足够的可见性：exploit 与 explore 分别占用配额，不能先取满 exploit 后再
追加一个必然被截掉的 explore；探索键包含外声部实际音区。该模式当前由自由练习窗口启用，其他
教材与勋伯格程序默认关闭，以保持既有首解和禁忌探测语义。

## 3. 受限随机贪心

随机性只改变合法候选的访问顺序，不改变规则裁决。每个节点仍先执行：

1. `candidates` 枚举；
2. `apply` 生成子状态；
3. `score` 计算完整前缀 finding 与声部移动成本；
4. 删除带 HARD violation 的子状态；
5. 按基础分数排序。

随后从前若干名或与最佳分数处于容许差值内的候选构造 restricted candidate list，按分数
加权产生稳定随机顺序。较优候选仍有更高概率先被访问，但非第一名的合理写法能进入后缀。
随机顺序只在阶段 B 使用；阶段 A 保持原确定性顺序。

不得把 HARD 违规以概率保留。未来仍可能修复的谓词必须在前缀上返回 `UNDETERMINED`；
SOFT finding 可以带惩罚继续搜索。以概率放行 HARD 会破坏“生成剪枝与规则解释同源”的
Constraint 代数契约。

## 4. 多样性模型

完整状态的单一相似度不足以约束分叉形态。候选空间需提供：

- `diversityKey(state)`：精确状态排序与去重；
- `diversityGroupKey(state)`：完整结果的结构分组，固定声部默认忽略纯八度复制；
- `stepDiversityKey(state)`：最后一步的结构身份，用于强制变异和重合检测；
- `similarity(left, right)`：完整结果的声部单元相似度。

固定声部的 `stepDiversityKey` 至少包含目标身份以及 S/A/T/B 的 pitch class；八度不同但
和弦功能、声部音级配置相同的 frame 视为同一结构。

每对完整候选计算：

```text
changedSlotRatio      = 不同 stepDiversityKey 的槽数 / 总槽数
changedVoiceCellRatio = pitch class 不同的 (槽 x 声部) 数 / 总单元数
firstDivergence       = 首次 stepDiversityKey 不同的槽位
```

新候选必须与**全部**已接收结果满足 `minChangedSlots` 与 `minChangedVoiceCells`，不能只与
首解比较。默认不自动降低门槛；预算耗尽而结果不足时返回较少结果并产生
`diversity-exhausted` 诊断。

## 5. 重合与安全剪枝

“变异后再次遇到与 reference 相同的 frame”是近重复的强信号，但不能无条件剪枝：短暂
重合后仍可能再次分叉，且全局约束可能依赖完整历史。

默认 `BEFORE_MIN_DISTANCE` 策略：

- 强制分叉后，若很快重合且累计差异尚未达到门槛，剪掉该分支；
- 已形成足够差异后允许偶尔重合；
- 若 `changedSoFar + remainingSlots < minChangedSlots`，无论后缀如何变化都无法接收，安全剪枝；
- 完整候选仍必须重新执行完整评分，不能因某个 frame 重合而直接拼接 reference 后缀。

可选的 `FIRST_REJOIN` 可用于只接受单一连续变异区间的实验模式，但不作为默认语义。

## 6. 候选可见性

当前和弦候选工厂只保留每目标 8–32 个按完整性、跳进放宽层与局部移动排序的 frame。即使搜索器会重启，未进入
候选列表的结构仍不可达。实现时保持总量上限，但改为双池：

- exploit 池：完整和弦、内声部/高音小跳且局部声部移动最优的候选，保证首解质量；
- explore 池：按逐步结构 key 分层抽取的候选，提供稍远但合法的排列。

建议初始比例为 3:1，最终数值由长进行基准确定。搜索器仍按完整前缀评分排序，因此探索池
不会自动挤掉首解，只为阶段 B 提供可变异分支。

## 7. 预算、配置与可复现性

`beamWidth` 在 DFS 中同时承担候选上限和节点预算乘数，命名与语义已经漂移。设计目标是拆分：

```kotlin
data class SearchConfig(
    val maxResults: Int,
    val candidateLimit: Int,
    val nodeBudget: Int,
    val diversity: DiversitySearchConfig,
)

data class DiversitySearchConfig(
    val enabled: Boolean = true,
    val seed: Long = 0L,
    val restartBudget: Int = 32,
    val mutationPoolSize: Int = 4,
    val minChangedSlotRatio: Double = 0.35,
    val minChangedVoiceCellRatio: Double = 0.20,
    val earlyMutationBias: Double = 1.0,
    val penaltyMutationBias: Double = 2.0,
    val rejoinPolicy: RejoinPolicy = BEFORE_MIN_DISTANCE,
)
```

公开 `SearchSpec` 镜像这些语义；迁移期可保留 `beamWidth` 反序列化兼容并映射到
`candidateLimit`。同一输入、配置与 seed 必须得到相同结果顺序、访问节点数和 trace。
调用方要“再来一批”时显式更换 seed。

总节点预算覆盖首解与全部重启；每次重启得到预算切片，未使用部分可回流。达到预算后停止，
不得因尚未凑满 `maxResults` 无界搜索。

## 8. 完整结果选择

阶段 B 先建立大于 `maxResults` 的合法候选池，再执行质量与多样性的贪心选择：

1. 第一名固定为基础分数最优的阶段 A 首解；
2. 后续每次选择“基础质量 + 与已选集合最大相似度惩罚”最优者；
3. `diversityWeight` 只控制已通过硬距离门槛候选之间的排序，不能替代距离门槛；
4. `diversityGroupKey` 相同的完整解只能保留一个。

这样搜索期保证“确实不同”，最终选择再平衡质量与覆盖面。

## 9. Trace 与诊断

在现有 `EXPANDED / HARD_PRUNED / DEAD_END / SOLUTION / BUDGET_EXHAUSTED` 基础上增加：

- `SEED_SOLUTION`：确定性首解；
- `MUTATION_RESTART`：reference、变异槽、seed 派生值；
- `DIVERSITY_REJECTED`：合法但距离不足；
- `REJOIN_PRUNED`：变异后过早重合；
- `NEIGHBORHOOD_EXHAUSTED`：某个变异邻域搜索完毕。

汇总 trace 记录 seed、各槽尝试次数、候选间距离、首次分叉位置、节点预算分配与是否耗尽。
公开结果在未凑满 top-K 时返回结构化 `diversity-exhausted`，区别于 `no-solution`。

## 10. 测试与验收

1. **首解稳定**：相同任务的第一候选与旧贪心 DFS 相同；`maxResults=1` 不进入重启阶段。
2. **合法性**：所有输出无 HARD violation；`UNDETERMINED` 只在完整候选边界按既有语义收敛。
3. **可复现**：相同 seed 的候选、顺序与 trace 相同；不同 seed 可覆盖不同合法邻域。
4. **多样性**：可满足时，任意两解达到槽距离和声部单元距离门槛。
5. **分叉位置**：长进行的后续结果不得全部只在末端分叉；记录 `firstDivergence` 分布。
6. **窄空间**：唯一解或可行解过少时返回较少结果和 `diversity-exhausted`，不返回近重复。
7. **终止性**：访问节点不超过 `nodeBudget`，重启次数不超过 `restartBudget`。
8. **候选池**：explore 池能暴露 exploit 前缀之外的结构，且第一候选质量不回退。
9. **回归**：混合三/七和弦、Or 的未来 `UNDETERMINED`、窗口 requirement 与无解诊断保持一致。

## 11. 后续：带修复窗口的杂交

首版不实现标准遗传算法。若多样化重启仍无法覆盖足够远的解簇，再增加双切点杂交：保留
父解 A 的前缀与父解 B 的后缀，清空中间窗口，把两侧作为 pin 后用同一 Constraint DFS 重建。
杂交产物仍须通过全部 HARD 检查；不维护非法种群，也不改变本文的距离与预算契约。
