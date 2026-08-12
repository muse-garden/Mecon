# 自由和声求解器

> 状态：第一阶段已实现（任意固定声部、开放和弦、调性计划、习惯进行模板与自由软规则）。
> 独立节奏及完整和弦外音 figuration 仍为 🚧。
>
> 目标：以最少、抽象、默认非硬性的规则生成平衡而多样的和声写作；勋伯格和声学、
> textbook 四部和声与爵士和声都是同一求解器上的可组合 preset。
>
> 数据模型见 [../data_model/harmony.md](../data_model/harmony.md)，底层约束代数见
> [constraint-program.md](constraint-program.md)。分层动态规划搜索后端的可行性、首期自由写作
> 接入与精确/有界语义见 [dynamic-programming-solver.md](dynamic-programming-solver.md)。

## 1. 不是第二套搜索器

自由求解器把请求编译为现有 `ConstraintProgram`，复用：

- `Constraint` 的 `Require / Prefer / Reward / Annotate / Remind`；
- `RuleFinding`、`RuleProfile` 与 suppression；
- 固定声部候选空间；
- 确定性首解与多样化重启搜索；
- 章节规则模块与声明式命名约束。

新增的是通用数据、编译入口和自由 profile，不复制 DFS、finding 或规则判定。

## 2. 请求形态

```kotlin
data class FreeHarmonyRequest(
    val key: Key,                       // 旧规则兼容默认调
    val tonalPlan: TonalPlan,
    val slotCount: Int,
    val vocabulary: List<ChordTarget>, // 运行时实际使用 InterpretedChordTarget
    val voicePlan: VoicePlan,
    val style: FreeHarmonyStyle = FreeHarmonyStyle.CLASSICAL,
    val progressionPlacements: List<ProgressionPlacement> = emptyList(),
    val fixedTargetIdentityBySlot: Map<Int, String> = emptyMap(),
    val allowedDefinitionsBySlot: Map<Int, Set<ChordDefinitionId>> = emptyMap(),
    val pitchPins: List<VoicePitchPin> = emptyList(),
    val additionalConstraints: List<Constraint> = emptyList(),
    val ruleOverrides: Map<RuleId, RuleConfig> = emptyMap(),
    val searchConfig: SearchConfig = SearchConfig(),
)
```

编译顺序固定为：

1. 物理域与 fixed material；
2. 用户要求与习惯进行；
3. 风格软规则；
4. 教材 preset 增量；
5. 搜索排序与多样性。

## 3. 习惯进行模板

习惯进行不是固定和弦数组，而是可命名、可放置的约束表达式：

```kotlin
data class ProgressionTemplate(
    val id: String,
    val steps: List<ProgressionStep>,
    val directionalStrength: Double = 1.0,
)

data class ProgressionPlacement(
    val template: ProgressionTemplate,
    val startSlot: Int,
)
```

每个 step 可以是相对当前调性上下文的 `TargetSelector`、`And/Or/Not` 分支或关系谓词。
模板实例可选择 `Require / Prefer / Reward`。

首批内置模板为 `AUTHENTIC_CADENCE`、`CADENTIAL_SIX_FOUR` 与 `JAZZ_II_V_I`。
模板 step 编译为 `ConstraintPredicate.TargetMatches`，约束音级/转位等功能性质，不锁死
`ChordDefinition`；因此 V 的三和弦、七和弦或 altered dominant 可共用同一位置。

🚧 后续增加 `Or` 终止前导、阻碍终止与模板 scoped suppression；仍须委托同一
`ConstraintExpr` 本体。

## 4. 默认软规则

以下写作规则在自由 profile 中为 `Prefer`。普通章节形式仍低于通用声部可读性；章节明确指定的
逐声部邻接/级进则高于相邻声部同音与普通旋律成本。用户 pin 始终优先，软规则不得把固定交错
或特殊织体变成无解。

### 4.0 和弦选择与写作分离

只依赖符号和弦序列的规则（`ConstraintPredicate.isChordSelectionOnly`：根音进行方向、根音进行
偏好评分、类似和弦距离、类似进行重复）属于**和弦选择**阶段。自由练习的和弦一律由用户选定，
写作阶段改变声部排列既不能满足也不能违反它们，因此它们一律编译为 `ConstraintModality.Remind`：
违反时照常发 HINT finding 提醒用户，但 `scoreDelta = 0`、按 EXPLANATORY 计 0 分、不参与剪枝，
也不占用分层 DP 的合并状态。勋伯格章节规则经 `SchoenbergPracticeTeachingRuleProjector` 投影进
自由练习时同样降级。求解器将来若接手和弦选择，再单独决定这些规则如何参与那一阶段——
届时不要把它们塞回写作搜索。

### 4.1 和声反复（和弦选择，只作提醒）

- 相似和弦不宜过近；
- 相似有向进行不宜短距离重复；
- 相似性由 policy 定义，不能散落在各规则中。

默认相似性考虑根音/功能、发声音集合、转位和结构标签。勋伯格 preset 可继续使用
“同根音即类似”的教材定义；爵士 preset 可区分同根音的 substitute 与 altered dominant。

### 4.2 旋律

- 检测近距离重复的音高模式与短周期上下摆动；
- 外声部权重大于内声部；
- 外声部的主要极值宜唯一；
- 增音程、七度、过大跳进成本较高；
- 两个同向跳进宜勾勒当前和弦或可解释的协和结构。

用户可用 `pitchPins` 固定动机，并用 `ruleOverrides` 在对应任务关闭或降权反复/跳进规则。
按槽自动推导 motive suppression 仍为 🚧。

### 4.3 声部独立与运动分布

对每个 transition 取非保持声部的带符号半音位移。若大量声部的位移集中在
`±1` 半音的窄簇内，则增加成本；三个以上参与者触发，完全相同的位移簇成本更高。

- 使用带符号位移，反向运动不算相似；
- 保持音不进入簇统计；
- 平行五/八度仍是独立规则，不能由本规则替代。

### 4.4 声部交错、间距与停滞

- 外声部越界：最高档通用软成本；
- 内声部交错：高档通用软成本，高于连续跳进、倾向音和普通章节偏好；明确的章节级进可更高；
- voice overlap：较低成本；
- 显式固定材料优先，不因这些偏好产生无解。

相邻间距、voice overlap 与多槽停滞成本为 🚧。

### 4.5 倾向音

倾向音是相对局部 `TonalContext` 的数据：

```kotlin
data class Tendency(
    val source: RelativePitch,
    val preferredTargets: Set<RelativePitch>,
    val baseWeight: Double,
)
```

大调内置 `4→3`、`7→1`；小调变化音按教材确认后的相对音级注册。
已实现大调 `4→3`、`7→1` 与按拼写判定的小调 `#4→#5`、`#5→6`。习惯进行模板的
`directionalStrength` 在终止式窗口放大倾向权重。

### 4.6 不协和音

骨架阶段区分：

- 和弦结构成员；
- 可用扩展张力；
- avoid tone；
- 真正和弦外音。

当前骨架阶段仅对 `AVAILABLE_TENSION / SUSPENSION / AVOID_TONE` 要求保持或级进释放。
经过音、辅助音、延留音、倚音等强弱拍分类进入后续 figuration 阶段 🚧。
爵士 profile 关闭此规则，也关闭古典平五八、隐伏五八与倾向音规则。

### 4.7 副属和弦

`DiatonicChordVocabulary.forContext` 可用 `includeSecondaryHarmony` 接入勋伯格章节共享的
`SecondaryHarmonyVocabulary`。副属目标保留临时主音、变化根音、功能族与中古调式来源；
古典自由规则优先按局部功能解决导音。完整派生与章节语义见
[schoenberg/secondary-harmony.md](schoenberg/secondary-harmony.md)。

`FreeHarmonyRequest.vocabulary` 现在接受通用 `ChordTarget`，因此调用方可直接传入
`InterpretedChordTarget`。同一实际音响的多个解释会成为独立搜索分支，排布候选按
`realizationIdentityKey` 共享缓存；功能规则仍读取各分支选择的功能音角色。旧的
`DiatonicChordVocabulary.forContext` 返回类型保持不变，作为兼容入口继续可用。

## 5. 风格与教材 preset

| preset | 主要增量 |
|---|---|
| `FREE_CLASSICAL` | 默认软规则、调性倾向、软平五/八、简化张力释放 |
| `FREE_JAZZ` | 保留通用旋律/运动规则；关闭古典平五八、倾向音与张力解决 |
| `TEXTBOOK` | 现有四部 textbook provider 与派生命名约束 |
| Schoenberg adapter | `FreeHarmonySolver.fromSchoenberg` 保留章节 typed 规则与禁忌表，并合并用户覆盖 |

章节规则降为自由练习偏好时，违反成本使用**正值**；负值会奖励违反规则，禁止用于
`ConstraintModality.Prefer`。普通章节偏好使用低权重；明确的 `NeighborTone` /
`VoiceDiatonicSteps` 级进使用高于相邻声部同音的权重，使平顺解决优先于把两声部暂时置于同音。

勋伯格 program 的目标形态：

```text
free base
+ Schoenberg vocabulary
+ chapter constraints
+ Schoenberg scoring/sequence policy
+ strict/free forbidden-transition profile
```

## 6. 任意声部与搜索边界

第一版任意声部指固定 `N` 个同节奏单音声部。候选枚举使用惰性 DFS + 有界 best pool：

- 按 voice order 逐声部赋值；
- 槽内尽早检查音域、低音、显式固定音与可判定的和弦成员要求；
- 完整性是候选硬边界：三和弦最多省五音，七和弦最多省三音或五音，均不得省两个音；
- 完成 frame 后才做需要完整纵向的规则；
- 不物化全部笛卡尔积；
- pairwise 规则保持 `O(N²)`，运动簇统计保持 `O(N log N)`。

普通搜索和分层 DP 共用候选访问优先级：先尝试完整和弦、内声部小于五度跳进、高音不大于五度；
有限候选池预选时同层让内声部级进优先，进入搜索后同层仍由完整规则评分排序。无合适延续时
依次尝试合法省略、内声部五度、高音六度；更宽跳进继续
保留在最后兜底，因而固定材料不会仅因启发式排序变成无解。该优先级不写入 `ScoreBreakdown`，
避免把搜索性能启发式伪装成教材 finding。

独立节奏与声部进入/退出由 `WritingTaskPlan` 的复调/装饰阶段处理。

### 6.1 分层 DP 后端

分层 DP 接受开放 `ChordTarget` 域、三/七和弦和同音响多解释，并根据实际 preset、规则开关与
约束谓词自动编译逐层有限状态。用户低音锁、pitch pin、完整解释选择和目标白名单仍在候选域或
估值层处理，不参与“能否使用 DP”的判断。未注册规则、profile suppression、requirements 与
未支持谓词明确 fail closed，不会静默漏规则。

分层 DP 的层候选常量（各声部 MIDI/拼写/目标签名、省略音数、tie-break key 与合成事件视图）每层
只算一次；依赖前缀的纵向 finding 按入边评估。路径优先级、硬违规计数和目标历史自动机随标签
增量维护；规则侧的目标相关数据（倾向音表、张力音集合、
约束分区）按目标/程序缓存，DFS 也共享这些收益。

普通单结果 `SearchBackend.AUTO` 保持 `GREEDY_DFS`；自由练习启用前缀或结果多样化时 AUTO
选择 `LAYERED_DP`，由一个前沿统一维护竞争路径。**自由练习的首解与优化两档都开前缀多样化，
因此实际后端恒为分层 DP**；该形态含终局重排规则（`free.melody.no-repeated-pattern`），只能用
`BOUNDED`，且退回 DFS 是静默的（`fallbackReason` 无人消费）——实测与缺口见
[dynamic-programming-solver.md](dynamic-programming-solver.md) §1.2。`EXACT` 超过候选、状态或边预算
返回 `BudgetExhausted`，含终局重排规则时拒绝精确模式。`BOUNDED` 另按结果数限制每个前驱的
排序出边；各类截断、五档转移计数与终局重排均写入 trace，不得称为全局最优。left boundary、
baseline、pin 与 `ConstraintSolveOutcome` 语义在两个后端一致。逐规则覆盖表与审计数据见
[dynamic-programming-solver.md](dynamic-programming-solver.md)。

### 6.2 自由练习的多样化前缀搜索

自由练习窗口启用 `PrefixDiversitySearchConfig`。它与最终 Top-K 的随机重启不同：每一槽都保留
一组低分但排列不同的前缀，并按外声部实际音区建立谱系；同一谱系内仍可保留多个有竞争力的
延续，直到后续运动成本可判定后再比较完整结果。`maxResults = 1` 因而也不会先耗尽某个首和弦
分支再停止。

候选工厂在该模式下扩大局部可见池，并正确按 exploit/explore 配额分别取样。explore 分层键同时
包含声部音级配置与外声部实际音区，避免紧凑但交错的排列占满候选上限。该机制只改变候选访问与
保留顺序，不改变 HARD/SOFT 规则裁决。

最终结果还执行 `DiversitySearchConfig` 的两项硬门槛：任意结果对都必须达到最低改动槽比例与最低
改动声部单元格比例；seed 只用于确定性同分排序。排除的 diversity group 在占用 top-k 前过滤，
不会让已排除最佳解挤掉唯一可返回槽位。

非末槽若某声部距音域边缘不超过两个半音，`free.range.continuation-reserve` 产生低权重 SOFT
成本，用于保留后续展开余量。用户 pin 到边缘音仍必须可解；该成本低于交错、平五平八等通用
可读性规则。

## 7. 自由练习窗口适配 🚧

自由练习自动写作的窗口适配不新增第二套搜索器。它固定用户已选的逐槽 target domain，向
`ConstraintProgramSolver` 传入可信左边界、baseline、排除的 diversity key 与取消令牌，再把
逻辑 frame 原子物化回局部 Runtime 范围。自由 profile 的普通音乐规则保持软偏好；另以统一
Constraint 谓词保留相邻声部过宽与多个声部同时大跳的可解释剪枝。详细契约、失败状态和
`SolverApi.refine` 边界见 [free-practice-window-voicing.md](free-practice-window-voicing.md)。

公开 `FreeHarmonyRequest` 将增加逐槽 `allowedTargetIdentityKeysBySlot`，用于自由解释和弦的多
target 互斥域；它与既有 `fixedTargetIdentityBySlot` 取交集，不改变 singleton 兼容语义。另增加
可选 `slotSpecs` 携带稳定 id 与真实 onset/duration；编译后仍只由 `ConstraintSlot.domain` 保存
最终 domain，避免窗口调用方与程序各维护一份。

## 8. 通用验收

实现完成必须证明：

1. ✅ 同一编译入口可求解 2、3、4、6 声部；
2. 用户固定奇怪跳进或只允许两个和弦时仍能求解；
3. 默认通用规则没有 `HARD` finding；
4. ✅ 正格终止与 ii–V–I 均以模板约束放在任意合法位置；
5. ✅ 共同和弦槽可同时携带源、目标调性解释；
6. ✅ C♯/D♭ 与 `b9/#9` 的候选拼写不丢失；
7. 爵士 preset 不因平五八或古典七音解决被剪枝；
8. 现有勋伯格练习行为与禁忌表时效守卫保持通过；
9. 多样化搜索在相同 seed 下可复现。
