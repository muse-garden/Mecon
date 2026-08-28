# 和弦外音与装饰化层（Figuration）

> 状态：**F1 分析路径已实现；延留音的 Stage 1 反向投影与探索谱例已落地；通用 F2–F4 生成路径仍为 🚧**。对应教材"和弦外音"章（textbook.md：经过音 p / 邻音 n / 延留音 s·r /
> 倚音 app / 规避音 e / 邻音组 n.gr / 先现音 ant / 持续音 ped）。
> 相关：[chorale-harmonization.md](chorale-harmonization.md) 是本文两阶段设计的第一个完整落地
> （四部圣咏织体）：Stage 2 用「逐格填充 + `NonChordToneClassifier` 校验」一条规则涌现出 §5 的整组
> 装饰操作，不为每类外音写生成器；§7.1 的延留反向投影与 §8 的表面级平行复查也在那里首次实现。
> 通用 `FigurationCandidateSpace` / `WritingTaskPlan` staged solve（F2/F3）仍未接。
> 相关：[voice-leading-pathways.md](voice-leading-pathways.md) 从 pitch-class 变换路径侧给出
> 挂留 / 先现 / 经过的**骨架级**判定与张力度量，是 §7.1 反向投影的一个天然生成器；本文继续
> 拥有外音类型定义、表面分类与装饰层的时值实现。
> 前置：[writing-engine.md](writing-engine.md)（`WritingTaskPlan` 多阶段任务）·
> [rule-scenes.md](rule-scenes.md)（`NonChordTone` / `MetricPosition` facet 占位）·
> [constraint-program.md](constraint-program.md)（`FigurationAt` / `PedalAt` / `HarmonicRhythm` spec）。
> 落地增量 F0–F4 见 §10，排期见 [roadmap.md](roadmap.md)。

## 1. 定位：装饰层，不是新和弦族

和弦外音打破现有求解器的三个假设：每声部每槽单音、音必属于槽和弦、槽无拍位语义。
它**不是**新的和弦族——rule-scenes §8 的"加 `ChordArity` + 词汇表 + 求解器"配方不适用。
正确建模是**骨架 + 装饰两阶段**（`WritingTaskPlan` 的第一个真实用例）：

```
生成方向：Stage 1 和声骨架求解（现有引擎，不动）
          → 骨架作为 fixedMaterial 进入 Stage 2
          → Stage 2 装饰化求解（FigurationCandidateSpace，在声部线条上插入外音）

分析方向：带外音的乐谱表面 → 还原（reduction）→ 骨架 + 已分类外音标注
          （check 入口与曲式分析的共同地基，见 §6、§9）
```

延续 writing-engine §8 的决策：独立节奏、子槽事件**不进** `FixedVoiceWritingFrame`；
装饰层用自己的状态模型（§3）。

**阶段边界按信息依赖划分，不是"和弦 vs 音符"**：外音需求中约束和弦选择或声部配置的
部分，必须**反向投影**为 Stage 1 的骨架约束（§7.1）——延留音链就是典型：链上每个 transition
的"预备-不协和-解决"关系由骨架音完全决定，必须在选和弦的同时裁决，不能等骨架定型后再找。
骨架搜索不感知**子槽时值**，但感知被投影进来的外音**关系约束**——这与七音预备 /
`SeventhFifthConstraint` 已经在用的"生成期收窄"是同一模式。复调是该原则的极限情形：
骨架退化为 cantus firmus（fixedMaterial），两阶段不适用，见 §9。

## 2. 前置：拍位语义（MeterPlan，F0）

延留音"几乎总在强拍"、倚音在强位、规避音在弱位——强弱位是定义性特征，
也是 rule-scenes §7 中 `MetricPosition` facet 的已知阻塞点。设计：

```kotlin
data class MeterPlan(
    val beatsPerBar: Int,                  // v1 只要强弱层级，不引入完整拍号对象
    val slotDurations: List<Int> = ...,    // 每槽拍数，默认全 1（一槽一拍）
)
enum class BeatWeight { STRONG, WEAK }     // v1 两级：小节第一拍强；分级细化留给曲式（§9）

data class WritingTimeline(..., val meter: MeterPlan? = null)   // null = 现状（抽象槽序号）
```

- 由 `MeterPlan` 可推导每槽及槽内细分点的 `BeatWeight`；`meter = null` 时既有章节行为不变。
- 同时解锁 `MetricPosition` facet（rule-scenes）与 `HarmonicRhythm` spec（constraint-program）
  的最小实现：v1 和声节奏固定为"一槽一拍"，教材谱例默认 2/4 或 4/4。

## 3. 装饰层数据模型（FiguredLine，F1）

```kotlin
data class SubSlotPosition(val slot: Int, val offset: Fraction, val duration: Fraction)
// offset/duration 为槽内比例（0..1）；v1 细分集 {1, 2, 3, 4}

data class FiguredNote(
    val position: SubSlotPosition,
    val pitch: Pitch,
    val role: FiguredRole,
)
sealed interface FiguredRole {
    object ChordTone : FiguredRole                       // 骨架音或和弦音转换（§5 ChordalSkip）
    data class NonChordTone(val type: NonChordToneType) : FiguredRole
}
enum class NonChordToneType {
    PASSING, NEIGHBOR, SUSPENSION, RETARDATION,          // s = 下行延留，r = 上行延留
    APPOGGIATURA, ESCAPE, ANTICIPATION, PEDAL,
}
// 邻音组是复合标签：FiguredGroup(NEIGHBOR_GROUP, notes)——成员音各自是 ESCAPE / APPOGGIATURA

data class FiguredLine(val voice: VoiceRole, val notes: List<FiguredNote>)
data class FiguredScore(val skeleton: /* Stage 1 输出 */, val lines: List<FiguredLine>)
```

关键点：**延留音改变骨架音的起始时刻**——解决音（骨架和弦音）在槽 k 的 `offset > 0`，
延留音（前槽音高的保持）占据 `[0, offset)`。装饰层不只是"在音之间插音"，
也允许推迟/替换骨架音的发声位置；骨架本身（和弦序列 + 声部音高）不变。

## 4. 外音特征模型与统一判定器（F1）

每类外音由四个特征定义：到达方式、离开方式、拍位、和弦隶属。
这是生成与检查的**同一份真相**（writing-engine §3：生成期收窄必须与检查期 finding 一致）：

| 类型 | 到达 | 离开 | 拍位 | 隶属 / 备注 |
|------|------|------|------|------|
| 经过 p | 级进 | **同向**级进 | 弱位 | 可为变化音；不同和弦音间或同和弦不同音间；一段可填多个 |
| 邻音 n | 级进 | **反向**级进回原音 | 弱位 | 可为变化音 |
| 延留 s / r | **同音保持**（预备） | 级进（s 下行 / r 上行） | **强位** | ∈ 前和弦；强拍不协和的来源 |
| 倚音 app | **跳进** | 级进解决（常与到达反向，不严格） | **强位** | — |
| 规避 e | 级进 | **跳进** | 弱位 | 仅自然音 |
| 邻音组 n.gr | （复合）e 后接 app 修饰同一和弦音 | | | 如 1 → 1-2-1-7-1 |
| 先现 ant | 级进（跳进亦常见） | 同音（即为下一和弦音的提前发声） | 弱位 | ∈ **后**和弦；倾向与他声部构成明显不协和（SOFT） |
| 自由先现 | 跳进 | 跳进 | 弱位 | ant 的特例标签，DEMONSTRATION 素材 |
| 持续 ped | 同音保持 | 同音保持（跨多槽） | — | ∈ 首尾和弦，中间槽豁免隶属；见 §7 `PedalAt` |

```kotlin
object NonChordToneClassifier {
    // 特征：approach/departure ∈ {HOLD, STEP_UP, STEP_DOWN, LEAP_UP, LEAP_DOWN}，
    //      onset 的 BeatWeight，membership(prevChord, currentChord, nextChord)
    fun classify(context: NctContext): List<NctClassification>   // 可多解，按教材常见度排序
}
```

- 判定器被三方共享：生成操作只在"结果能被判成目标类型"时发射（§5）；
  检查/还原对表面重新分类产出 finding（§6）；场景 MAY 判定复用其必要条件（§7）。
- 同一音可有多重解释（弱位级进到达的下一和弦音既像 p 又像 ant）——返回多候选，
  作业检查取最优解释，规则示例按 `REQUIRE_INDICATION` 指定的类型判定。

按**定义信息所在层**，八类分成两组（决定 §7.1 反向投影的强度）：

- **骨架可判定型（s / r / ant / ped）**：定义完全由骨架音间关系给出——延留音 =
  `pitch(v,k) ∉ chord(k+1)` 且级进解决到 `pitch(v,k+1)`；先现音 = `pitch(v,k+1) ∈ chord(k+1)`
  的提前发声；持续音 = 隶属豁免窗口。Stage 1 可精确判定与计分（充要），
  表面实现（推迟/提前 onset）是确定性变换。
- **插入型（p / n / e / app / n.gr）**：在骨架音之间插入新音，几乎不约束和弦选择；
  对骨架只有弱必要条件（经过音要求声部级进可填的三度空间），留在 Stage 2 搜索。

## 5. 生成：FigurationCandidateSpace 与装饰操作（F2/F3）

Stage 2 是新的 `CandidateSpace`（复用 `BeamSearchSolver`）：状态 = 各声部 `FiguredLine`
前缀 + 增量 finding 缓存；候选 = 当前推进槽上可用的装饰操作：

```kotlin
sealed interface FigurationOp {
    // enabler（非外音）：声部在槽内移到同和弦另一音，为槽内经过音制造三度空间
    data class ChordalSkip(val voice: VoiceRole, val slot: Int, val target: ChordToneRole) : FigurationOp
    data class InsertPassing(val voice: VoiceRole, val span: FigSpan, val count: Int) : FigurationOp
    data class InsertNeighbor(val voice: VoiceRole, val slot: Int, val side: Side) : FigurationOp
    data class InsertNeighborGroup(val voice: VoiceRole, val slot: Int) : FigurationOp
    data class Suspend(val voice: VoiceRole, val intoSlot: Int, val direction: ResolutionDirection) : FigurationOp
    data class InsertAppoggiatura(val voice: VoiceRole, val slot: Int) : FigurationOp
    data class InsertEscape(val voice: VoiceRole, val intoSlot: Int) : FigurationOp
    data class InsertAnticipation(val voice: VoiceRole, val intoSlot: Int, val free: Boolean = false) : FigurationOp
}
```

- **可行性 = 判定器必要条件**：`InsertPassing` 要求该声部两音间距 ≥ 三度且级进可填；
  `Suspend` 要求前槽同声部音高对当前和弦不协和且级进解决到骨架音；发射前跑 classifier 确认。
- **骨架可判定型操作（Suspend / InsertAnticipation）不引入新音高**，只对骨架已满足关系的
  transition 做 onset 变换：机会式装饰（无 requirement）时在恰好可行处发射；需求驱动
  （延留音链等）时由 §7.1 的反向投影保证 Stage 1 产出可装饰骨架，Stage 2 退化为确定性实现。
- **密度预算**：`FigurationDensity`（每槽/每声部操作上限、总外音数范围）防止装饰爆炸，
  也是练习分级的旋钮（"只加经过音" = 操作白名单 + 密度 1）。
- **多经过音**：`count > 1` 时按三度/四度跨距分配细分（{2,3,4} 细分集），
  声部对间的表面协和性检查见 §8。
- 评分：外音 INDICATION 正向计分（`nct.passing` 等）；HARD 剪枝沿用（表面级禁则，§8）。

## 6. 检查与还原（分析路径，F1）

```kotlin
object FigurationAnalysis {
    // chords 由练习给定（作业检查场景）；从表面自动识别和弦 🚧（ChordRecognizer + 拍点聚合）
    fun reduce(score: FixedVoiceScore, meter: MeterPlan, chords: List<ChordTarget>): ReductionResult
}
// ReductionResult = 骨架（每声部每槽的和弦音）+ 各外音的 NctClassification + findings
```

- 归类失败的非和弦音 → `VIOLATION`（"无法解释的外音"），这就是作业检查的核心判定。
- 这是 solver-api `check` 入口（§2.5）在外音章的底座，也是曲式分析管线的第一段（§9）。
- UI：`INDICATION` finding 携带类型标签（p / n / s / app / e / n.gr / ant / ped），
  锚点为外音音符，`relatedAnchors` 指到达音与解决音——沿用现有 finding 呈现协议。

## 7. 场景与约束程序接入（F2/F4）

**场景 facet**（rule-scenes §2.1 的 🚧 占位落地）：

```kotlin
data class NonChordTone(val types: Set<NonChordToneType>, val voiceScope: VoiceScope = ANY) : SceneFacet
data class MetricPosition(val slot: Int, val weight: BeatWeight) : SceneFacet
```

- 外音规则的场景 = 骨架 `ChordPattern`（复用现有 facet）合取 `NonChordTone`；
  MAY 级判定 = 骨架上存在可行插入位（如经过音要求某声部相邻槽间可级进填充）。
- `SceneMatcher.instantiate*` 对含 `NonChordTone` 的场景产出 **`WritingTaskPlan`**
  （Stage 1 骨架 slots + Stage 2 装饰 requirement），runner 按"有无装饰阶段"通用分发——
  **红线不变**（rule-scenes §8）：不得出现按外音类型的 runner 分支。

**约束程序新 spec**（constraint-program §2 表新增行）：

| Spec | 表达内容 | 编译产物 |
|------|---------|---------|
| `FigurationAt(window, voice?, types, density, chain = false)` | 窗口内要求出现指定类型外音；`chain` 要求逐 transition 连续（延留音链） | **两部分**：Stage 1 骨架投影（§7.1）+ Stage 2 `RuleRequirement(REQUIRE_INDICATION, nct.*)` 与操作白名单 |
| `PedalAt(window, voice = BASS)` | 持续音 | **Stage 1** 约束：低音 `FixedPitch` + 中间槽纵向检查豁免低音隶属 + 首尾槽 `sameChordAsSlot` |
| `MeterSpec(beatsPerBar)` | 谱例拍号/和声节奏 | `WritingTimeline.meter` |

持续音整个落在骨架层：上方三声部照常连接（纵向完整性检查改投影到上三声部），
低音退出和弦隶属判定——它不是装饰操作，而是骨架求解的织体变体。

### 7.1 反向投影：装饰需求编译为骨架约束

被**要求**出现的外音（`FigurationAt` / 场景 `REQUIRE_INDICATION`）不能等骨架定型后再找位置：
随机骨架大概率不可装饰，尤其是链式需求。编译器按 §4 的分组给每类外音注入骨架级约束：

- **骨架可判定型 → 充要投影**。延留音链 `FigurationAt(window, {SUSPENSION}, chain = true)`
  编译为窗口内逐 transition 的骨架约束：存在同一声部 v，`pitch(v,k) ∉ chord(k+1)` 且下行
  二度级进到 `pitch(v,k+1)`——Stage 1 在**选和弦的同时**裁决声部与音的选取（经典 7-6、4-3
  链的和弦序列由此自然涌现）；对应 finding `solver.constraint.suspendable` 使剪枝可解释。
  Stage 2 只做确定性 onset 变换，不搜索。ant / ped 同理（ped 见上表）。
- **插入型 → 必要条件投影（软）**。经过音需求投影为"窗口内目标声部存在三度级进空间"的
  SOFT 偏好（不满足不剪枝，靠 Stage 2 无解回退），因为插入位在多数骨架上天然存在。
- **跨阶段回退**：投影只有必要性时 Stage 2 可能仍无解——Stage 1 输出 top-K 骨架作为
  Stage 2 的候选池，逐一尝试；全部失败则以 `figuration-unrealizable` 诊断报告哪条
  requirement 未满足（沿用 solver-api §5 诊断协议），不做跨阶段联合 beam（writing-engine §8
  的"多目标联合搜索先不做"决策不变）。

这与既有机制同构：七音预备四形式（延留式/经过式/邻音式/倚音式）本就是"外音形"约束
在**单阶段**骨架求解里生效的先例——那里可行是因为七音是和弦音；本节把同一手法推广到
真外音，靠的是骨架可判定型外音的定义信息本来就全部在骨架层。

当前已落地的最小竖切是 `TextbookFigurationProblem` / `TextbookFigurationSolver`：所有探索页
和弦外音谱例先调用通用 `ConstraintProgramSolver` 生成 SATB 骨架；延留音额外把目标声部、
和弦音身份与级进方向编译为 `ToneInVoiceFilter` + `VoiceDiatonicSteps`。这两个谓词已在垂直候选
和相邻转移阶段剪枝，不再等完整乐句后事后筛选。4-3、7-6、9-8 与链使用女高音向下解决；
2-3 使用低音声部，I → V6 上保持主音并向下解决到导音。探索层只负责确定性地展开预备、
延留与解决的时值/连音并转换为 `StorageScore`。通用 `WritingTaskPlan`、场景 facet 和任意规则
manifest 的 staged solve 仍归 F2/F3。

## 8. 规则族（textbook.NonChordToneRules）

- 每类外音一条 `INDICATION` 规则（`nct.passing` / `nct.neighbor` / …），由判定器命中产出；
- 定义性 `VIOLATION`：强弱位不符（延留/倚音不在强位、规避音不在弱位）、规避音用变化音、
  离开方式不符（延留音跳进离开等）、延留音无预备；
- `SOFT` 倾向：延留音下行解决较常见（r 需专门要求）、先现音倾向构成明显不协和、
  倚音到达与离开反向较常见；
- 自由先现音：`DEMONSTRATION` 场景素材（正误对照组呈现"一般先现 vs 自由先现"）；
- **表面级复查** 🚧：外音制造的平行五八等表面禁则（教材后续内容），v1 先只对
  骨架跑现有四部规则，表面级音程复查挂开放问题（§11）。

## 9. 对复调与曲式教材的支撑

**复调（species 对位）——本设计的直接受益方**：

- species 的不协和处理规则**就是**本文特征模型的参数化子集：二种对位 = 弱拍经过音；
  三种 = 经过 + 邻音；四种 = 延留音链（预备-不协和-解决跨小节）。
  `NonChordToneClassifier` + 规则族按 species 配置直接复用；
- `MeterPlan` 的拍权与 `FiguredLine` 的声部独立节奏正是 species 需要的时间模型；
- 差异在候选空间：复调**不走两阶段**——不协和处理与线条生成不可分（对位声部的每个音
  同时被音程规则与外音形规则裁决），拆成"骨架 + 装饰"会把耦合搜索错误地切开。
  `CounterpointCandidateSpace`（writing-engine §3 既定计划）直接在 figured-line 粒度对
  cantus firmus 逐拍生成：cf 是 fixedMaterial（骨架退化为固定材料），classifier 与拍权
  是**逐 transition 的局部检查**而非阶段间接口，四种对位的延留链在这里是原生搜索目标，
  不需要 §7.1 的投影。纵向检查用 `SpelledInterval` 音程关系（`IntervalRelation` facet 随之落地）；
- 需另建的部分：调式上下文（`Mode` 已有）、模仿/可动对位（挂 `MotiveAt` 🚧）、
  `Texture` facet 落地为 species 分级。
- **跨域贯通不靠合并空间**（原则见 writing-engine §3"空间分离、评价贯通"）：复调任务可
  同时携带 `Harmonic` / `KeyPlan` 目标作为逐拍评价上下文（和声走向、转调）；动机
  （`MotiveAt`，[../analysis/motive.md](../analysis/motive.md)）是逐音推进的模式自动机规则
  （constraint-program §3.1 配方），对复调空间与本文 Stage 2 figured line **都可挂**——
  机会式命中记 INDICATION 加分（"顺便引入动机"），要求式经 §7.1 把动机的和弦音锚点
  投影为骨架 `VoiceMotion` 约束。全局联合规划（同时选和弦与模仿点）归创作层迭代
  （analysis/composition.md + refine pins），不进 beam。

**曲式——分析优先，管线地基已就位**：

- 曲式分析的第一段管线 = §6 的还原（表面 → 骨架 → 功能序列 → 终止式识别）；
  `check` 入口 + 插件标注轨（`PluginTrack`）是天然输出形态；
- `ConnectionType` / `WritingTarget.Cadence` 已预留终止式类型库；
  `KeyPlan` facet（rule-scenes 🚧）承接转调/离调段落；
- 需要的新维度（均为已识别的扩展点，非结构性返工）：`BeatWeight` 从两级扩展为
  分级 + 超小节层（hypermeter）；`PhrasePosition` facet；乐句/乐段的**层级分段模型**
  （新数据结构，建立在还原结果之上）；动机相似度（`MotiveAt` 语义随复调一并定）。
- 结论：曲式以分析任务为主，本设计的还原管线与拍位模型是其前置；
  "按曲式写作"的生成任务远期，仍由 `WritingTaskPlan` 分阶段承载（曲式计划 → 和声骨架 → 装饰）。

## 10. 落地增量

| 增量 | 内容 | 判据 |
|------|------|------|
| **F0** | `MeterPlan` + `WritingTimeline.meter` + `MetricPosition` facet | 既有章节行为不变（meter=null 回归全绿） |
| **F1 ✅** | `NonChordToneClassifier` + textbook rule catalog + 主页面增量适配器 + 由通用四部求解器生成的 SATB 探索谱例；延留音含 4-3 / 7-6 / 9-8 / 2-3 分类与链式谱例；2-3 固定在低音并下行解决；外音谱例表单不暴露未参与生成的起止和弦音级；`FiguredLine` / 通用 `reduce` 待 F2 前补齐 | 教材外音分类金标准；主页面按用户和弦标注分类着色；探索页各类型均以无 HARD 四部规则违例的骨架形成可渲染结果 |
| **F2** | `FigurationCandidateSpace` + 弱位插入操作（p / n）+ staged solve 接线 + `NonChordTone` facet + 目录注册 | 场景产出 `WritingTaskPlan`；生成结果 reduce 后逐音归类一致 |
| **F3 ◐** | 强位与时值变换操作（s / r / app）+ e / n.gr / ant + 自由先现 DEMONSTRATION + **反向投影**（§7.1：延留音的骨架声部/音身份/级进投影已落地；通用操作、插入型软投影、top-K 跨阶段回退仍 🚧） | 延留音解决音 onset 推迟正确；**延留音链金标准**（连续 4-3 → 7-6；逐 transition 由骨架投影保证）；正误对照组 |
| **F4** | `PedalAt`（骨架层）+ `FigurationAt` / `MeterSpec` spec + manifest `constraintKinds` + FormSpec | 持续音谱例上三声部和弦进行成立、首尾同和弦 |

复调 species 立项挂 F1–F2 之后（classifier 与 MeterPlan 就位即可开工，候选空间独立）。

## 11. 测试约定与开放问题

测试：

- 分类金标准：教材各类外音的最小谱例逐一被 classifier 正确归类（含邻音组复合标签）；
- 生成-检查闭环：每个 `FigurationOp` 产物 reduce 后归类为目标类型（MAY ⊇ 生成结果）；
- 骨架不变性：Stage 2 输出去掉外音后与 Stage 1 骨架逐音相等（延留音按解决音归位）；
- 反向投影一致性：投影约束（`solver.constraint.suspendable` 等）满足的骨架，其 Stage 2
  实现必成功（充要投影不允许出现跨阶段回退）；插入型软投影失败路径产出
  `figuration-unrealizable` 诊断而非静默空结果；
- meter=null 回归：既有全部章节测试不受 F0 影响。

开放问题：

- 表面级平行五八：外音是否"制造/掩盖"平行的判定范围与教材依据，v1 不做，先收集反例；
- 节奏丰富度：细分集 {2,3,4} 够教材谱例；自由节奏（附点、跨槽连音）留待复调；
- 自动和弦识别的还原（无给定和弦的作业检查）：强拍聚合 + `ChordRecognizer` 的歧义消解策略；
- classifier 多解的呈现：作业检查取最优解释还是全部列出（UI 决策）；
- beam 内存（roadmap P3）在 Stage 2 的密度预算下是否复现，需在 F2 压测。
