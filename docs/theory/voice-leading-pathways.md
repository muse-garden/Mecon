# Voice-leading 路径代数：挂留和弦、经过和弦与和弦外音

> 状态：**VL-A（路径代数 + 稳定性分层 + 张力度量 + 外音投影）与 VL-B（自由练习双端接入）已实现；
> VL-C 起的装饰层合流、复调候选空间为 🚧**。
> 前置：[neo-riemannian-voice-leading.md](neo-riemannian-voice-leading.md)（一步一音的基础邻接图）·
> [figuration.md](figuration.md)（外音特征模型、`NonChordToneClassifier`、骨架/装饰两阶段）。
> 本文只定义 **pitch-class 层的路径与度量**；四部实现、拍位与时值仍归 figuration.md 与写作引擎。

## 1. 一句话模型

把「和弦」与「和弦外音」放进同一个对象：**有序的单音变换路径**。

```
源和弦 S ──step₁──▶ N₁ ──step₂──▶ N₂ ──…──▶ N_k = 目标和弦 T
```

- **中间节点 Nᵢ 是什么，由它的集合类型决定**：稳定 → 读作一个（经过）和弦；不稳定 → 读作
  外音织体（挂留 / 先现 / 经过音）。这正是申克 *Stufe*（音级和弦）与 *Durchgang*（经过 / 声部进行和弦）
  的区分，本文把它变成一个可计算的判据而不是分析者的直觉。
- **同一组变换的不同顺序给出不同的 Nᵢ**，因而给出不同的张力曲线。挂留和弦就是「把最后一步先做」
  的产物（§3）。
- 张力、暧昧度、解决落差都在节点上量化（§5），用户按曲线形状挑选，而不是背规则（§6）。

## 2. 教材与文献依据

现有教材里没有把这四件事合成一套算子的写法，但每一块都有成熟出处：

| 主题 | 出处 | 本文取用 |
|---|---|---|
| 和声源自声部进行；挂留、经过和弦作为**声部进行和弦** | Aldwell / Schachter / Cadwallader, *Harmony and Voice Leading*；Salzer, *Structural Hearing*；Gauldin, *Harmonic Practice*「linear chords」 | §1 的稳定 / 过渡二分；§4 的经过和弦 |
| *Stufe* vs *Durchgang*，装饰性和弦不是独立音级 | Schenker, *Kontrapunkt* / *Der freie Satz* | 中间节点默认不产生功能标签 |
| 简约声部进行图、单音半音移动的和弦邻接 | Douthett & Steinbach, "Parsimonious Graphs" (*JMT* 42, 1998)；Cohn, *Audacious Euphony* (2012) | 已实现的基础邻接图 |
| **改变音数的声部进行**（一音分裂为二 / 二音融合为一） | Callender, "Voice-Leading Parsimony in Scriabin" (*JMT* 42, 1998)；Callender–Quinn–Tymoczko, "Generalized Voice-Leading Spaces" (*Science* 320, 2008) | §4 的 `Split` / `Fuse` 算子 |
| 声部进行几何、和声与对位统一 | Tymoczko, *A Geometry of Music* (2011)；*Tonality: An Owner's Manual* (2023) | 路径即对位事件流（§7） |
| 模糊 / 分裂变换与集合类空间 | Straus, "Voice Leading in Set-Class Space" (*JMT* 49, 2005) | 多解读数（暧昧度） |
| **张力的量化** | Lerdahl, *Tonal Pitch Space* (2001)；Hindemith, *Craft of Musical Composition* 和弦分组；Plomp & Levelt / Sethares 粗糙度 | §5 的可调 `TensionPolicy` |
| 不协和处理与拍位 | Fux, *Gradus*；Jeppesen, *Counterpoint* | §7 的种类对位映射 |
| 根音进行强弱三分 | Schoenberg（项目已接入） | §5 的推动力分解 |

**本项目的原创部分**：把「顺序」提升为一等公民——同一变换多重集的**全部排列**都被枚举、
度量并可选择；以及由此把和弦选择与外音处理统一到一个搜索空间。

## 3. 挂留和弦 = 被重排序的中间态

基础邻接图里，「和弦根音下行二度」是一次单音变换。连做两次把主和弦送到属和弦：

```
1-3-5  --(1→7，下行小二度)-->  7-3-5 (iii)  --(3→2，下行大二度)-->  7-2-5 (V)
```

把两步**换序**，中间态就不再是三和弦：

```
1-3-5  --(3→2，下行大二度)-->  1-2-5 (sus)  --(1→7，下行小二度)-->  7-2-5 (V)
```

`{1,2,5}` 在 C 大调即 `{0,2,7}`，被识别为 **Csus2 = Gsus4**（同一 pitch-class 集合的两个读法，
项目 `ChordDefinition` 已把 sus 的二度 / 四度成员标为 `ChordMemberRole.SUSPENSION`）。取
Gsus4 读法时，第二步就是教科书的 **4–3 挂留解决**。

挂四和弦同理是**下属和弦的挂二**：

```
4-6-1 (IV)  --(6→5，下行大二度)-->  1-4-5 (Csus4 = Fsus2)  --(4→3，下行小二度)-->  1-3-5 (I)
```

因此引擎不需要「挂留和弦规则」：只要把 sus 注册为**过渡态集合类**（§4），换序枚举自然产出它，
且解决方向由路径剩余步骤唯一确定，而不是靠一条「sus4 解决到 3」的硬规则。

**功能性从哪来**：走挂留路径时，最后一步的根音读法是 `Gsus4 → G`（同根音），推动力来自外音解决
而非根音推进。引擎因此把连接强度拆成两项（§5.3）：根音进行分 ⊕ **张力落差**。

### 3.1 ⚠️ 路径推导解释了挂留*是什么*，预测不了它*用在哪*

上面这条推导会得出 `I → Isus2 → V`，但这个进行**实际上很少用**。常用的是把同一个挂留插进终止式：

```
IV  → Gsus4 → V → I          ii⁷ → Gsus4 → V → I
```

两处的挂留和弦是同一个音集 `{0,2,7}`；差别全在**它前面是什么**。三条结论：

1. **挂留由「预备」定义，不由简约变换定义。** 延留音是前一和弦已经唱过、被保持进新和声再级进
   解决的音。前和弦因此**不必是挂留和弦的简约邻居**——`IV → Gsus4` 有一个声部走了三个半音
   （A→D），路径搜索永远到不了它，而它恰恰是最标准的终止四六 / 4–3 挂留。
   这条由 `VoiceLeadingSuspensionInsertion.between(previous, resolution)` 表达：挂留和弦 =
   **解决和弦替换掉一个音**，替换音取自前和弦且与新和声构成不协和。
2. **挂留装饰进行，不升级进行。** `I → V` 无论怎么装饰仍是下降进行；`IV → V` 是超越进行、
   `ii⁷ → V` 是上升进行。`PreparedSuspension.underlyingRootMotion` 因此**跳过挂留和弦**测量
   `P → R`，不让漂亮的解决落差掩盖弱骨架。§5.3 的 `drive` 只用于同一 `P → R` 内部排序，
   不可跨进行比较。
3. **同一音集的读法由它装饰的和弦决定。** `{0,2,7}` 在主和弦语境读作 Csus2，在终止式里读作
   **Gsus4**——所以它听起来是 4–3 延留，而不是「主和弦的二度」。命名跟着解决和弦走。

**当前实现边界**：自由练习面板的挂留分组仍只列简约路径（`I → Gsus4 → V` 一类），
`VoiceLeadingSuspensionInsertion` 已落地并被写作引擎的冲突/解决位使用，但**尚未接进面板**；
接入计划见 §9 的 VL-B2。

## 4. 路径代数

### 4.1 宇宙（Universe）与稳定性分层

`VoiceLeadingUniverse` 注册若干 `ChordDefinition` 并给每个打稳定性标签：

| 稳定性 | 成员 | 角色 |
|---|---|---|
| `STABLE` | 大 / 小 / 增 / 减三和弦；九种七和弦 | 可作路径**终点**；读作独立和弦 |
| `TRANSITIONAL` | sus2 / sus4（同一集合类）；后续可注册四五度叠置等 | 只能作**中间节点**；默认读作外音织体 |

**每一步之后的节点都必须被宇宙识别**——这既是音乐判据（不写无法命名的音响），也是唯一有效的剪枝。
扩宇宙 = 注册定义 + 标稳定性，遍历器不动（沿用基础引擎已有的扩展点契约）。

### 4.2 步骤算子

节点带**列（column）身份**：每列记录它源自哪些原始和弦音、已被移动几次。三个算子：

| 算子 | 效果 | 约束 |
|---|---|---|
| `Shift(column, ±1/±2)` | 单音半音 / 全音移动 | 目标 pitch class 不得与其他列重合 |
| `Split(column, ±1/±2)` | 一音**分叉**成双音（基数 +1） | 新音不得与其他列重合；两列共享同一来源 |
| `Fuse(from, into)` | 相距 1–2 半音的双音**合并**（基数 −1） | 合并列的来源取并集 |

基础引擎的两条不变量在这里被**参数化而非废除**：

- 「同一音不重复移动」放宽为 `maxMovesPerColumn`（默认 1 = 基础引擎行为；≥2 才产生经过音 / 邻音）；
- 「只取最短路径」放宽为 `maxSteps` + 按步数分组，因为经过和弦的价值恰恰在于**更长**的路径。

### 4.3 经过和弦

于是「起始和弦 → 目标和弦」之间的一系列经过和弦 = 一条 `stepCount > d_min` 的路径，其中间节点
按 §5 度量后：`STABLE` 且根音关系可分类 → 呈现为经过**和弦**；否则呈现为外音（§6）。
分叉 / 合并让路径可以跨基数（三和弦 ⇄ 七和弦），例如 `C-E-G` 的 `G` 分叉出 `A` 得到 `Am7`，
再合并回三和弦，这是纯 `Shift` 图做不到的连接。

## 5. 量化指标

全部权重集中在**唯一不可变** `VoiceLeadingTensionPolicy`（带版本 id），枚举与展示共用同一份，
禁止在 UI 或平台侧另写常量——与勋伯格 scoring policy 的单一本体约束同源。

### 5.1 节点指标

| 指标 | 定义 | 直觉 |
|---|---|---|
| `dissonance` | 音程类向量按权重平均（ic1/ic2/ic6 高，ic3/ic4/ic5 低） | 音响紧张度 |
| `instability` | `STABLE` = 0，`TRANSITIONAL` = 1 | 是否需要解决 |
| `tension` | `wDis·dissonance + wUns·instability` ∈ [0,1] | 综合冲突 |
| `readingCount` | 该 pitch-class 集合的和弦读法数 | 增三 / 减七 / sus 天然多解 |
| `resolutionBreadth` | 再走一步可达的**不同稳定集合**数 | 去向的发散程度 |
| `ambiguity` | `wRead·(1−1/readingCount) + wBreadth·(1−1/resolutionBreadth)` | 暧昧 ↔ 清晰 |

### 5.2 路径指标（呼吸感）

- `peakTension`：中间节点张力最大值；
- `arc = peakTension − max(tension(S), tension(T))`：**> 0 即为冲突–解决拱形**，用户要的「呼吸感」；
- `centroid ∈ [0,1]`：张力沿路径位置的加权重心。**低 = 前置张力**（挂留型：强位不协和后解决）；
  **高 = 后置张力**（先现 / 倚音型）；
- `resolutionDrop = tension(N_{k−1}) − tension(T)`：末步的解决落差；
- `monotonicRelease`：峰值之后张力是否单调下降（干净的一次呼吸）。

### 5.3 连接推动力分解

```
drive = wRoot · schoenbergRootMotionScore(S → T) + wTension · resolutionDrop
```

根音项复用既有勋伯格三分类；张力项来自本文。二者相加解释了「弱根音进行 + 强外音解决」的组合，
也给 UI 一个可排序的单一标量。**该分解不产生硬规则**，只用于排序与提示。

### 5.4 排列枚举

给定源与目标，`orderings(S, T)` 枚举同一变换多重集的**全部合法排列**，每个排列附一份 §5.2 剖面。
这是用户「调顺序试效果」的直接入口：面板按 `arc` 或 `centroid` 排序即可在
「稳定经过和弦」与「挂留冲突」之间连续滑动。

## 6. 外音投影：路径 → `NonChordToneType`

路径本身不含时值。`VoiceLeadingFigurationProjector` 按**放置方式**把中间节点投影成外音标注，
产出的类型直接复用 figuration.md 的 `NonChordToneType`，不新建平行枚举：

| 放置 | 中间节点落在 | 列的角色判定 |
|---|---|---|
| `SUSPENSION_BEFORE_TARGET` | 目标槽的**强位**起点，目标和弦推迟发声 | 仍停在源音且不属于目标 → `SUSPENSION`（级进下行解决）/ `RETARDATION`（上行）；已到达目标音 → 和弦音 |
| `ANTICIPATION_AFTER_SOURCE` | 源槽的**弱位**末尾 | 已到达目标音而源和声仍在响 → `ANTICIPATION` |
| `PASSING_CHORD` | 自己的槽 | 稳定节点 → 各列都是和弦音，不产生外音标注；**过渡态节点无法充当经过和弦**，自动回退到上一行的挂留读法 |

任一放置下，落在**既不属于源也不属于目标**的列（只能由 `maxMovesPerColumn ≥ 2` 产生）按其两次
移动方向判为 `PASSING`（同向）或 `NEIGHBOR`（反向折回）。

**与 figuration.md 的分工**：本文只给出「哪一列在哪个节点是何种外音」的**骨架级判定**；
拍位、时值细分、连音与四部实现仍由装饰层负责。反过来，本投影正是 figuration.md §7.1
所要求的「骨架可判定型外音的充要投影」的一个天然生成器——挂留链不再需要专门的约束编译，
它就是一条 `centroid` 低、逐 transition 有 `resolutionDrop` 的路径序列。

## 7. 渗透到复调模块

figuration.md §9 的判断不变：复调**不走骨架 + 装饰两阶段**。本文提供的是另一件东西——
**候选生成器**：

- 一条路径的「列」就是声部，「步骤序列」就是各声部的先后动作。把路径展开成事件流，
  即得到一段可直接喂给 `CounterpointCandidateSpace` 的对位骨架；
- 种类对位是本模型的参数切片：**一种** = 0 个中间节点；**二 / 三种** = `maxMovesPerColumn ≥ 2`
  的弱位经过节点；**四种** = `SUSPENSION_BEFORE_TARGET` 放置 + `centroid` 低的排列；
- 分叉 / 合并对应声部分部与同度汇合，在三声部以上才有意义，可按 `Texture` 分级开关；
- 因此「和声学渗透进复调」的具体含义是：**对位声部的每一步都能被回读成一条和声路径的一个步骤**，
  从而复用 §5 的张力度量做逐拍评价，而不必把和声规则硬塞进对位空间。这与写作引擎既定的
  「空间分离、评价贯通」原则一致。

## 8. 边界：不重复造轮子

- 变换枚举、平行五 / 八风险、根音方向分类 → 基础引擎（neo-riemannian-voice-leading.md）不动，
  本模块在其之上扩展，不 fork；
- 外音**类型定义**与表面分类 → `NonChordToneClassifier` 唯一拥有；本模块只产出骨架级角色，
  由投影器转成同一套枚举；
- 四部实现、音域、平行禁则 → 写作引擎与 textbook 规则；本模块只报「风险」不判违规；
- 功能标签 / 罗马数字 → 过渡态节点**不生成**功能标签，只给绝对 / 相对音名，沿用现有非功能候选的呈现约定。

## 9. 落地增量

| 增量 | 内容 | 门禁 |
|---|---|---|
| **VL-A ✅** | `VoiceLeadingUniverse` + 稳定性分层 + `Shift/Split/Fuse` 路径搜索 + 排列枚举 + `TensionPolicy` + 外音投影 | `VoiceLeadingPathwayTest` / `VoiceLeadingTensionTest` / `VoiceLeadingFigurationTest`：I→V 两种排序分别产出 iii 与 Gsus4；sus4 由 IV 的挂二解释；分叉产生 Am7；张力拱形与重心可区分排序；投影得到 `SUSPENSION` / `ANTICIPATION` / `PASSING` |
| **VL-B ✅** | 自由练习接入：voice-leading 页签下的挂留 / 经过分组、按推动力排序、放置开关、`FreePracticeIntent.InsertVoiceLeadingPathway` 一次写入整条路径 | `FreePracticeViewProjectorTest` / `FreePracticeSessionTest`（替换后续框、单历史项、`NON_CHORD_TONE` 被拒）；`practice-trace.json` 追加 6 步并由 JVM + Kotlin/JS 重放；Playwright 真实插入与单次撤销 |
| **VL-B2** 🚧 | 面板接入 `VoiceLeadingSuspensionInsertion`：下一框已有和弦时增加「有准备的挂留」分组，用 `SPLIT_SPAN` 插在两框之间，并显示跳过挂留测得的 `P → R` 根音进行 | `IV → V` 能列出 Gsus4 且标记为超越进行；`I → V` 同图形但标记为下降进行；双端 + trace |
| **VL-C** 🚧 | 与装饰层合流：路径投影直接生成 `FiguredLine`，替代挂留链的专用约束编译（figuration.md §7.1） | 骨架不变性 + 生成-检查闭环测试 |
| **VL-D** ◐ | 写作引擎消费张力度量：[chorale-harmonization.md](chorale-harmonization.md) 已用它给四部圣咏的表面装饰评分；复调候选空间与种类对位分级仍 🚧 | 圣咏张力曲线测试已绿；一至四种对位金标准谱例待做 |
| **VL-E** 🚧 | 过渡态词汇扩充（四五度叠置、附加音）与用户自定义宇宙 | 注册即生效，遍历器零改动 |

### 9.1 VL-B 的自由练习契约

- **候选与排序的唯一本体**是 `PracticeVoiceLeadingPathwayCatalog`：搜索参数（`maxSteps = 3`、
  每列至多移动一次、暂不开分叉合并）、分组判据、`drive` 排序与每节点根音读法都在这里，
  投影器与 session 共用；平台只做展示。
- **v1 catalog 不开 `maxMovesPerColumn ≥ 2`**：经过音 / 邻音需要一个音移动两次，而它们属于
  外音放置，装饰层未接入前放进和弦槽会得到没有名字的中间态。
- **下一框已有和弦时投影收窄到该目标**（「把挂留填进这两个和弦之间」），这是 UI 过滤；
  session 仍按完整目录校验 `pathwayId`。
- **写入语义**：路径上除源以外的每个节点写成一个和弦框，先替换后续已有框、越过末框才追加，
  一次动作一个历史项，不创建 `WorkspaceIdiomInstance`。
- **过渡态和弦必须能显示自己的符号**：时间轴的非功能和弦回退改用本文的宇宙而非基础族，
  否则插入的 `0-2-7` 会退化成裸音名。
- `placement = NON_CHORD_TONE` 由 session **显式拒绝**并在面板置灰，等 VL-C；不得在平台侧
  悄悄按经过和弦形态写入。

## 10. 开放问题

- **张力权重的经验校准**：当前权重按 Hindemith / 粗糙度模型的定性排序手调，尚无听感实验或语料回归；
  是否引入 Lerdahl 的音级距离作为第三项待定。
- **暧昧度的可听性**：`resolutionBreadth` 是图论量，不一定对应听感的「不知去向」；需要与实际
  解决频次统计对照。
- **路径爆炸的上界**：`maxSteps ≥ 4` 且开启分叉时候选数量的实际分布尚未压测，当前用
  `maxPathsPerTarget` 与节点预算兜底，未做代价制搜索。
- **等音与拼写**：本层全部在 pitch class 上工作，挂留音的拼写（`F` vs `E♯`）留给实现层的
  `SpelledPitchClass`；跨调路径的拼写策略未定。
- **与惯用进行目录的关系**：经过和弦路径是否应沉淀为可命名的惯用进行条目，还是永远即时枚举。
