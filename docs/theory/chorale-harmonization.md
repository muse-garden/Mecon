# 圣咏配和声写作引擎

> 状态：**CH-1（任务模型 + 两阶段求解 + 律动实现 + 张力评分 + 多候选）已实现于
> `theory/.../chorale/`；乐谱装配为 CH-2，UI 接入为 CH-3，🚧**。
> 前置：[writing-engine.md](writing-engine.md)（`CandidateSpace` / `BeamSearchSolver` / `WritingTaskPlan`）·
> [constraint-program.md](constraint-program.md)（第一阶段骨架求解）·
> [figuration.md](figuration.md)（外音特征模型与两阶段边界）·
> [voice-leading-pathways.md](voice-leading-pathways.md)（张力度量与有准备的挂留）。

## 1. 定位：第一个真正的两阶段写作任务

这是 figuration.md §1 的两阶段设计的第一个完整落地，也是**检验 voice-leading 张力理论的实验台**：
同一段骨架的不同律动实现会得到不同的张力曲线，引擎按曲线形状排序，用户直接听到差别。

输入分两类，这个区分是整个模块的设计轴：

| 用户给定 | 完整性 | 进入哪一阶段 |
|---|---|---|
| 功能和弦与其时间位置 | **完整指定** | Stage 1 的 `slotDomains` + `slots`（可只给音级、留转位给搜索） |
| 已有旋律（圣咏本身） | 可选、逐音 | Stage 1 的 `pitchPins` |
| 每个声部的节奏型 | 部分（给候选集，逐跨度选一个） | Stage 2 |
| 冲突 / 解决的位置 | 部分（只标要求出现的槽与声部） | **两阶段都要**：反向投影进 Stage 1，实现在 Stage 2 |
| 旋律进行的大概方向 | 部分（窗口 + 方向，软偏好） | Stage 1 排序 |

**和弦完整指定不等于骨架已定**：转位、重复音、排列、四个声部的具体音高仍是搜索空间，
一般写作规则（平五平八、声部交错、音域、间距）在 Stage 1 全程生效。

## 2. Stage 1：骨架（复用既有求解器，不新建）

`ChoraleTask.skeleton` **就是一个 `ConstraintProgram`**——它已经携带调性计划、逐槽真实
onset/duration、`MeterPlan`、声部计划与音域、pitch pin、约束代数与搜索配置。模块不复制这套模型，
只在它之上加 Stage 2 需要的东西。产物是 `List<ChordVoicing>`：每个和声槽、每个声部一个音高。

**不变量（Stage 2 必须维持）**：Stage 1 求出的纵向，就是每个和声槽**强拍上真正响起的纵向**。
装饰不得改变它——唯一被许可的例外是延留音，它按定义推迟解决音的发声（figuration.md §3）。
因此 Stage 1 的平行、间距、音域判定在装饰后依然成立，Stage 2 只需补**表面级**复查（§6）。

### 2.1 冲突 / 解决位的反向投影

被要求的延留音不能等骨架定型再找位置（figuration.md §7.1）。`ChoraleFigurationRequest(slot, role,
SUSPENSION)` 编译成 Stage 1 约束：目标声部在 `slot-1` 的音高必须**不属于** `slot` 的和弦，且级进
（下行 = 延留 / 上行 = 上行延留）到它在 `slot` 的音高。这两条正是既有的
`ConstraintPredicate.VoiceDiatonicSteps` 与和弦音归属谓词，模块只负责编译，不新写判定。

这条投影是**充要**的：满足它的骨架，Stage 2 一定能实现该延留音；因此不需要跨阶段回退。
插入型外音（经过 / 邻音 / 先现）不投影，留给 Stage 2 搜索。

### 2.2 有准备的挂留

`VoiceLeadingSuspensionInsertion`（voice-leading-pathways.md §3.1）给出「前和弦能预备哪些延留音」。
模块用它做两件事：校验用户标的冲突位确实有预备；在用户只说「这里要冲突」而没指定声部时，
按 `tensionDrop` 排序候选。**预备来自前和弦，与简约变换无关**——`IV → Gsus4 → V` 正是路径搜索
够不到、而配和声天天用的那个图形。

## 3. Stage 2：律动实现

### 3.1 节奏型

`ChoraleRhythmPattern(id, divisions)`：`divisions` 是该和声跨度内的比例切分，和为 1。
每个声部给一组允许的节奏型（`ChoraleVoicePlan.patterns`），搜索**逐跨度**为每个声部选一个。
这就是「每个声部的节奏型」——是候选集而非完整指定。

### 3.2 逐格填充与装饰操作

选定 n 格后，为该声部枚举长度 n 的音高序列，规则如下：

- **第 0 格必须是骨架音**，除非该跨度被要求延留——那时第 0 格是**前一跨度的骨架音**，
  骨架音在后面某格才到达（唯一被许可的推迟）。
- 每格音高取自：当前和弦音（在该声部音域与 `cur` 的五度邻域内）∪ 前一格音高的自然音级邻音。
- 相邻相同音高合并成一个更长的音符，所以 `[C,C,D,E]` 出来是「二分 C + 四分 D + 四分 E」。
- **任何非和弦音必须能被 `NonChordToneClassifier` 判成一类**，否则整条序列丢弃。
  生成与检查共用同一份定义，不存在「生成得出来但检查说不出名字」的音。
- 最后一格必须能级进接到下一跨度的骨架音，或本身是和弦音——避免外音后面接无法解释的跳进。

由此涌现的操作恰好是 figuration.md §5 的那一组：延留 / 上行延留、经过、邻音、先现、和弦音转换。
模块**不为每类外音写生成器**，只写这一条填充规则加分类器校验。

### 3.3 密度预算

`ChoraleFigurationDensity(maxPerSpan, maxPerVoice)` 限制装饰爆炸，也是练习分级旋钮
（「只加经过音」= 类型白名单 + 密度 1）。

## 4. 旋律方向

`ChoraleContourRequest(role, window, direction, weight)`：窗口内该声部骨架音的走向偏好，
`ASCENDING / DESCENDING / ARCH / VALLEY / STATIC`。它是**软偏好**，按拟合度扣分而不剪枝——
用户说的是「大概方向」，不是硬约束。评分在 Stage 1 候选之上做，因此不进入约束程序。

## 5. 搜索与多候选

Stage 2 是一个 `ScoredCandidateSpace`，状态按**和声跨度**推进，一步同时决定全部声部在该跨度内的
填充。候选数 = 各声部（节奏型 × 填充）之积，逐跨度剪枝，由既有 `BeamSearchSolver` 驱动。

多候选来自 `SearchConfig.maxResults` + `diversityGroupKey`（按各声部的装饰类型序列分组），
所以返回的不是同一个实现的音高微调，而是**装饰方案确实不同**的几种写法。
Stage 1 本身也可返回多个骨架；CH-1 先取最优骨架，top-K 骨架轮询归 CH-2。

## 6. 评分：三套规则汇合

| 来源 | 作用面 | 进入方式 |
|---|---|---|
| 一般四部写作规则（平五平八、交错、间距、音域） | 骨架 | Stage 1 既有规则，不重复实现 |
| 和声学规则（章节原则、七音预备解决、导音倾向……） | 骨架 | Stage 1 的 `constraints` / `ruleModules` |
| 外音规则（拍位、到达 / 离开方式、预备） | 表面 | Stage 2 的分类器 + `ChoraleFigurationRequest` |
| **表面级平行五 / 八** | 表面 | Stage 2 新增复查（figuration.md §8 的 🚧 在此落地） |
| **voice-leading 张力曲线** | 表面 | 见下 |

**张力曲线**是本模块检验 voice-leading 理论的入口：把每个发音点上真正响着的纵向取 pitch class
集合，用同一份 `VoiceLeadingTensionPolicy` 求张力，得到整条曲线。每个和声跨度记一条

```
arc = 该跨度内张力峰值 − 骨架自身四个音的张力
```

⚠️ **基准是骨架的纵向，不是相邻两个强拍**。延留音的不协和恰恰落在**强拍上**，若按「两端之间的
峰值」定义，本模块最核心的图形会被测成「毫无张力」。用骨架当基准还保证未装饰的跨度精确为 0
（省略五音等排列差异也被算进基准）。

- 被标了冲突 / 解决位的跨度，`arc > 0` 得奖励，`arc <= 0` 说明用户要的呼吸没写出来 → 扣分；
- 未被标记处出现张力起伏则轻微扣分（无理由的刺耳），但不剪枝。

**未被要求的装饰不得白拿**：除了张力扣分，每个用户没要求的额外发音点（包括纯协和的和弦音转换）
都记一份 `activityCost`。否则最朴素的写法只是与所有更花哨的写法**打平**，由任意 tie-break 决定谁
留在 beam 里——实测会把朴素解整个挤掉，让本来可解的任务报「无解」。默认因此是「没要求就写朴素」。

这条评分是**唯一**把 §2 的和声骨架与 §3 的表面装饰放在同一把尺子上比较的项，也是「同一骨架的
不同装饰方案哪个更好」的仲裁者。权重集中在 `ChoraleScoringPolicy`，与张力权重同样是单一本体。

## 7. 输出

```
ChoraleRealization(skeleton, lines, tensionCurve, breakdown)
ChoraleLine(role, notes)   ChoraleNote(onset, duration, pitch, slot, nonChordTone?)
```

`ChoraleNote.nonChordTone` 为空即和弦音。`onset` 是真实 `TimeCode`，`duration` 是全音符分数，
可以直接装配成 `StorageScore`（CH-2）。`tensionCurve` 与发音点一一对应，供 UI 画曲线。

## 8. 落地增量

| 增量 | 内容 | 门禁 |
|---|---|---|
| **CH-1 ✅** | 任务模型、Stage 1 编译（含延留反向投影）、Stage 2 候选空间与填充枚举、分类器校验、表面平行复查、张力曲线评分、多候选 | `ChoraleHarmonizerTest`：骨架不变性、被要求的延留真的出现且分类正确、未分类外音不出现、平行五八被剪、张力拱形区分两种装饰方案、多候选装饰类型确实不同 |
| **CH-2** 🚧 | `StorageScore` 装配（SATB 大谱表 + 和弦标注轨 + 外音 finding 锚点）与探索页入口 | 渲染回读后逐音归类与引擎输出一致 |
| **CH-3** 🚧 | 桌面 / Web 接入：进行编辑器、节奏型选择、冲突位标注、候选切换与张力曲线可视化 | 共享 session + typed view；双端 + trace |
| **CH-4** 🚧 | 复调化：放开「全声部同和声跨度推进」，各声部 frontier 独立推进（writing-engine §3 的统一状态模型），承接种类对位 | 一至四种对位金标准 |

## 9. 已知边界与开放问题

- **CH-1 只用最优骨架**：Stage 1 的 top-K 轮询未接，若某骨架的装饰全部无解，当前返回空而不回退。
- **表面平行复查是新写的**，只覆盖发音点上的纯五 / 纯八同向进行，不处理经过音「掩盖」平行的
  教材细则（figuration.md §11 的开放问题原样保留）。
- **节奏型是逐跨度独立选择的**，没有跨跨度的节奏动机连续性；动机（`MotiveAt`）挂 CH-4。
- **张力权重未经听感校准**，与 voice-leading-pathways.md §10 是同一笔欠账；本模块正是收集
  反例的地方。
- 半音化外音（变化经过音、那不勒斯语境）v1 只走自然音级邻音，半音邻音待与调性计划一起做。
