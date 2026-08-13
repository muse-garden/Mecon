# 自由练习窗口写作与 refine 基础

> 状态：✅ 2026-08-12 runtime 窗口写作、锁定旋律与勋伯格章节软规则投影已实施；公开
> `SolverApi.refine` 与窗口外右端边界仍按 §8–9 留待后续。配套交互见
> [自由练习自动写作改造](../exploration/free-practice-auto-writing.md)。
> 本文描述本次应新增的求解原语，并界定公开 `SolverApi.refine` 的后续工作。

## 1. 审计结论

当前底层已能支撑“固定用户和弦、生成 3–6 个声部”的窗口写作，但不能直接宣称完成
[`solver-api.md`](solver-api.md) 的 `refine`：

| 能力 | 当前状态 | 本次处理 |
|---|---|---|
| `FreeHarmonySolver` 任意声部、真实槽时值 | ✅ | 复用 |
| `VoicePitchPin` | ✅ runtime | 预留细粒度入口，不做锁定 UI |
| 自由规则软评分 | ✅ | 复用并补可行性剪枝 |
| seeded Top-K 多样化搜索 | ✅ | 首解后缓存多候选，供“换一个” |
| 节点预算、trace 与协作取消 | ✅ runtime | 区分耗尽、无解与取消 |
| baseline 精确音高相似度 | ✅ runtime | 合成 SOFT rule provider |
| 左/右固定边界帧 | ◐ | 左边界已接入；窗口内首尾 `VoicePitchPin` 可由 DP 连接，窗口外右边界后续实现 |
| 公开 `RefineRequest` | 🚧 占位 | 后续升级协议 |
| `addedConstraints` 合并、完整 pin spec | ❌ | 后续公开 refine |
| 输出候选的 solver snapshot | ❌ | 本次 runtime 候选先带，后续协议化 |

因此本次实现 `FreePracticeWindowVoicer` 与可复用的 runtime solve context；公开 `SolverApi.refine`
继续返回 `refine-not-available`，直到 §8 的协议条件同时满足。这样不会为了赶 UI 再造一套搜索，
也不会把一个只能处理自由练习的半成品标成通用 refine。

## 2. 窗口请求

工作区解析与求解器之间使用 runtime 类型，不序列化、不持有 `RuntimeScore`：

```kotlin
data class PracticeWindowVoicingRequest(
    val key: Key,
    val tonalPlan: TonalPlan,
    val slots: List<PracticeVoicingSlot>,
    val voicePlan: VoicePlan,
    val leftBoundary: FixedVoiceBoundaryFrame? = null,
    val rightBoundary: FixedVoiceBoundaryFrame? = null,
    val baseline: VoicePitchBaseline = VoicePitchBaseline.EMPTY,
    val pins: List<VoicePitchPin> = emptyList(),
    val excludedDiversityKeys: Set<String> = emptySet(),
    val search: SearchConfig,
)

data class PracticeVoicingSlot(
    val workspaceSlotId: WorkspaceSlotId,
    val solverSlotId: HarmonySlotId,
    val time: HarmonicTimeSpan,
    val sourceAnchor: EventId? = null,
    val allowedTargetIdentityKeys: Set<String>,
)

data class FixedVoiceBoundaryFrame(
    val slotId: HarmonySlotId,
    val target: ChordTarget?,
    val pitchesByVoiceId: Map<TrackId, Pitch>,
)

data class VoicePitchBaseline(
    val pitchesBySlotAndVoice: Map<HarmonySlotId, Map<TrackId, Pitch>>,
    val similarityWeight: Double = 1.0,
)
```

`slots` 只含将被重写的槽；边界不计入输出长度。`workspaceSlotId` 用于 stale 校验/物化，
`solverSlotId` 用于求解结果和 baseline；二者都不能用列表下标代替。左边界是可信的
用户历史材料：即使超出预设音域、交叉或不完整表达所标和弦，也直接作为第一条 transition 的
前帧，不通过当前槽候选枚举器重新生成。`target = null` 时只运行不依赖前和弦身份的运动规则。

`rightBoundary` 本次必须为 `null`；保留字段是为了固定调用方向和 fingerprint，不能在尚未评分
右端时悄悄忽略非空值。未来启用前，非空值返回 `INVALID(unsupported-right-boundary)`。

## 3. 从工作区编译

每个 `WorkspaceHarmonySlot` 的候选域按实际选择编译：

1. 取该槽选择的 `WorkspaceTonalLayout`，构造局部 tonal context。
2. 以 `WorkspaceChordChoice.pitchClasses` 查询共享 `ChordSelectionCatalog` / vocabulary。
3. `pinnedInterpretationRef != null` 时只保留该解释；否则保留所有同音响解释为互斥 target。
4. `bassPitchClass != null` 时只保留该和弦音在低音的 target；空值保留全部可用转位。
5. 若没有 target，返回 `INVALID(unresolved-chord-choice)`，不默认选第一条解释。
6. 把稳定 solver slot id、真实 onset/duration 与 source anchor 写入槽位元数据。

选择目录、解释发现索引、solver target 目录和窗口 `TonalPlan` 必须统一使用
`chordSelectionTonalContext()` 构造上下文。`TonalContextId` 会进入 `InterpretationId`；消费者各自使用
不同字符串会使已持久化的锁定解释无法在求解域中重建，尤其会令初始锁定主和弦在扩展写作范围时
报“slot-0 没有可用的求解解释”。目录回归测试须同时验证音响可匹配和每个
`ChordInterpretationRef` 可由 solver target 精确解析。

`FreeHarmonyRequest` 增加：

```kotlin
data class FreeHarmonySlotSpec(
    val id: HarmonySlotId,
    val time: HarmonicTimeSpan,
    val sourceAnchor: EventId? = null,
)

val slotSpecs: List<FreeHarmonySlotSpec>? = null
val allowedTargetIdentityKeysBySlot: Map<Int, Set<String>> = emptyMap()
```

`slotSpecs` 非空时数量必须等于 `slotCount`；编译器先用 vocabulary 和所有 identity/definition filter
得到唯一 `SlotDomain`，再与 spec 合成现有 `ConstraintSlot`，因此不传两份 domain 真相。空值仍生成
兼容的等时槽。allowed identity 集合与 `fixedTargetIdentityBySlot`、调性 vocabulary 和 definition
filter 取交集；singleton fixed target 保留兼容。自由练习不在 desktop 复制 domain 过滤逻辑。

## 4. 求解上下文与结果

不把取消令牌、baseline 或已排除候选塞进可序列化/可缓存的约束程序。扩展运行时入口：

```kotlin
data class ConstraintSolveContext(
    val leftBoundary: FixedVoiceBoundaryFrame? = null,
    val baseline: VoicePitchBaseline = VoicePitchBaseline.EMPTY,
    val excludedDiversityKeys: Set<String> = emptySet(),
    val cancellation: SearchCancellation = SearchCancellation.NONE,
)

sealed interface ConstraintSolveOutcome {
    data class Solved(
        val solutions: List<PolyphonicConstraintSolution>,
        val trace: WritingSearchSummary,
    ) : ConstraintSolveOutcome
    data class NoSolution(val trace: WritingSearchSummary) : ConstraintSolveOutcome
    data class BudgetExhausted(val trace: WritingSearchSummary) : ConstraintSolveOutcome
    data object Cancelled : ConstraintSolveOutcome
    data class Invalid(val diagnostics: List<ConstraintSolveDiagnostic>) : ConstraintSolveOutcome
}
```

`ConstraintSolveDiagnostic` 在 theory 层保存稳定 code、message、可选 rule id 与 slot index；desktop
不再把裸字符串当诊断模型。首解、后台候选合并、当前候选索引与 diversity 去重由
`FreePracticeCandidateSession` 统一维护，UI 只触发 advance / merge。

既有返回 `List` 的 API 继续作为兼容薄壳，只取 `Solved.solutions`；新自动写作必须消费完整 outcome。
搜索节点循环定期检查 cancellation。节点预算覆盖确定性首解与所有 restart；正常入口必须保留
`exhaustedBudget`，不能等同 `NoSolution`。

左边界接入 `FixedVoiceWritingCandidateSpace` 的初始 transition context：

- 第一槽候选的 motion ordering 相对左边界计算；
- transition rule provider 能观察边界到第一槽；
- state 完成长度仍等于请求槽数，边界不出现在 solution；
- 只对生成帧运行 vertical/target 硬约束。

## 5. baseline 相似度

多样化搜索现有 similarity 只用于结果间选择，不能代替 refine 的 baseline 成本。新增合成
`BaselineSimilarityRuleProvider`，在每个完成的生成帧比较精确音高：

```text
cellCost = 0                                  相同 Pitch
         = weight * (1 + min(semitones, 12)/12)  发生改变
slotCost = Σ cellCost
```

只对 baseline 中存在的 cell 计分；未写槽不受罚。每个改变发出统一 rule id
`solver.refine.baseline-distance` 的 SOFT finding，anchor 为该槽/声部，解释“为何此音被改变”。
baseline 永远不是 hard pin；真正必须保留的材料继续使用 `VoicePitchPin`。

自动写作的回溯范围以当前实际音高为 baseline，使求解器只在整体质量确有收益时大改旧声部。
“换一个结果”不反转该目标；它通过 Top-K 最小距离、排除 diversity key 与新 seed 寻找另一簇。

## 6. 可行性约束与规则放宽

自由练习不加载 textbook/Schoenberg 针对具体和弦的硬 requirement。`FREE_CLASSICAL` 中平五/八、
交叉、倾向音、七音/张力解决、普通旋律跳进保持 SOFT；选定和弦 target、显式 pin、
`VoicePlan` 音域、可表示音高和复音容量为硬材料；可信边界帧本身不复检这些生成约束。

四声部工作区默认音域必须直接取 `VoicePlan.standardFourPart()` 的人声 SATB 范围，不能使用
任意声部数的等距公式冒充 SATB。后者会把低音下限抬到 C3，使 `I64–V` 只能取 G3，并迫使
内声部在狭窄重叠音域中竞争。

相邻独立声部处于同一绝对音高时，统一产生 `writing.vertical.adjacent-voice-unison` 软违规；
自由写作与 textbook/Schoenberg 四部写作共用同一判定。它只引导首选结果将重复和弦音错开八度，
不得把用户 pin 或窄音域强制出的同度剪成无解。

关闭具体和弦模块不等于允许退化的纵向材料。自由练习与勋伯格综合练习统一调用
`generalChordToneCompletenessRequirements`：三和弦根音、三音必须出现，五音为软偏好；七和弦根音、
七音必须出现。完整性 requirement 由候选工厂在生成期执行，禁止在 `FreeHarmonySolver` 或 UI
另写 pitch-class 判断。由转位决定的重复音、平行与倾向音等规则继续使用现有 provider，并按自由
写作预设保持软约束，以免用户固定材料因教材章节规则而无解。

惯用进行保存的 `sourceExerciseId/sourceChapterId` 由 `PracticeTeachingRuleProjector` 解析。默认的
`SchoenbergPracticeTeachingRuleProjector` 只向章节注册表请求原始 program，再通过通用
`Constraint.projectSlots` 映射到当前写作槽窗并降为 `Prefer`（只依赖符号和弦序列的**和弦选择**
规则改为降到 `Remind`，见 [free-harmony-solver.md](free-harmony-solver.md) §4.0：用户已自行选定
和弦，这些规则只发提醒 finding，不计分也不占 DP 状态）；普通章节偏好权重为 4，明确指定
逐声部邻接/级进的 `NeighborTone` 与 `VoiceDiatonicSteps` 权重为 120，高于相邻声部同音的通用
软成本 100。这样 N6–I64 中 `b2–1`、`b6–5` 的平顺平行四度会促使低音选择合法的低八度 4，
而不会为了避免后继同音而改走更差的变化音程。投影同时按选中的 `ChordInterpretation` 重映射
target selector，因此同一音响解释为副属属七或德国增六时分别采用对应章节。惯用进行只展示章节
program 的一段时，目录变体持久化一条 `SchoenbergTeachingSource`（源调、完整 source progression、
可见区间 `start`、章节编译用的终止式选项），投影器据此调用章节注册表的 `freePracticeProgram`
单一入口；章节自行声明最小 program 长度等编译约定（见
`SchoenbergCadenceChapter.freePracticeProgram`）。`start` 只在 `program.length` 等于 source
progression 槽数时可信，否则回退到 `findTargetSpan`。禁止再遍历 key、终止选项、长度或枚举结果来
猜原 program，也禁止按截短后的槽数误判终止类型（如把 `N6–I64–V` 当作三槽的 `N6–V–I`）。
被 viewed-as 重新解读的实例，源调以 viewed-as 参数为准，优先于变体自带的 teaching source。

投影的**准入闸门是 `SchoenbergChapterRegistry.chapterFor(ruleId)`**：只有归属某个章节的约束会被
投影，`withoutChapters` 的禁忌进行消融诊断也按同一映射删规则。归属由 descriptor 的
`ownedRulePrefixes` 决定，默认值只是练习自己的 `ruleId`——**章节规则用了别的命名空间时必须显式声明**，
否则整批规则会静默地既进不了自由练习、也无法被消融（2026-08-11 修复：`schoenberg.leading-triad.*`、
`schoenberg.second-inversion.*`、`schoenberg.seventh-chord.*`、`schoenberg.root-motion.*` /
`schoenberg.repetition.*`、`schoenberg.freer.*` 五组均曾漏配）。守卫见
`SchoenbergChapterRuleOwnershipTest`：它编译每个注册练习的 program，断言其中所有 `schoenberg.*`
规则都能映射到章节；`schoenberg.four-part.*` 是一般四部写作规则，按设计不归章节。

`FreePracticeWindowVoicer.solve` 接受可替换的 `PracticeTeachingRuleProjector`；未来爵士或插件体系
提供自己的注册表适配器即可，`FreeHarmonySolver`、约束代数与搜索器不依赖勋伯格类型。

新增两个通用三值谓词，使用 `ConstraintModality.Require` 参与前缀剪枝：

```kotlin
MaxAdjacentVoiceSpacing(
    upperPairsSemitones = 12,
    lowestPairSemitones = 19,
)

MaxSimultaneousLargeLeaps(
    thresholdSemitones = 12,
    maxVoices = 1,
)
```

两者必须通过统一 constraint → finding 桥产生相同 rule id、witness 与 prune 计数，不能在候选
factory 另写无解释的 if。大跳约束只观察 transition；若左边界版本是唯一死因，编译第二个
program，把“边界 → 第一槽”的这一条 modality 降为 `Prefer`，只重试一次。生成槽之间的规则
和纵向间距不自动放宽。

首版阈值须用 3–6 声部、极端开排列、远关系和弦和已交叉边界校准。失败诊断至少包含最早死槽、
各 hard predicate prune 数、访问节点和预算状态。

## 7. 物化契约

求解器返回逻辑 frame，不直接编辑乐谱。`FreePracticeVoicingMaterializer` 接收当前 Runtime、稳定
slot 范围和一个 candidate，返回不可变 edit result：

```kotlin
data class PracticeVoicingMaterialization(
    val score: RuntimeScore,
    val replacedEventIds: Set<EventId>,
    val insertedEventIdsByCell: Map<Pair<HarmonySlotId, TrackId>, EventId>,
    val renderHint: RenderHint?,
)
```

算法：

1. 用公共 `ScoreTimeMap` 把绝对 Fraction 边界转换成 `TimeCode`；不在 desktop 复制换算。
2. 对全部 voice/pitch event 与半开窗口求交；完整在外者引用复用。
3. 跨左/右边界者切分，保留窗口外声音；落在窗口内的片段移除。
4. 清理/截断引用被移除 event/notehead 的 tie、slur 和事件级附件；非音符结构元素不改。
5. 自动求解整谱与自由练习局部替换先统一经过 `VoicingEventPlanner`，把逻辑 frame 展开为每槽、
   每稳定 voice id 一个不可变 event cell；各物化器只负责把 cell 写入 Storage 或 Runtime 范围。
6. 运行 polyphony validator，生成 bounded `RenderHint`；不能证明 splice 等价时显式全量 compute。

锁定旋律可把一个工作区和弦槽细分为多个 solver segment，但物化时未锁定声部按原工作区槽合并，
每槽只落一个持续和弦音。这样 DP 仍能观察首尾固定音及中间旋律约束，记谱层不会把 segment 数量
误当成伴奏重击次数。

本轮选段重写即替换范围内所有骨架，不保存自动生成来源。未来 lock 通过 pins/texture 明确保留材料，
而不是靠猜“手工音符”绕过替换。

## 8. 为什么公开 refine 后续实现

当前 `RefineRequest(base, baselineCandidateIndex, pinnedSlots, similarityWeight)` 无法满足文档语义：

- candidate index 依赖重跑顺序，没有 baseline fingerprint 或逻辑 frame；
- `OutputCandidate` 只有 `StorageScore`，不能可靠恢复槽/声部/target；
- `pinnedSlots` 只能表达整槽，不能表达音符/声部；
- 没有 `addedConstraints`、冲突诊断、取消与预算耗尽结果；
- convenience 请求并非全部都有等价、可恢复的 runtime program snapshot。

公开入口启用前须一次完成协议升级：

```kotlin
@Serializable
data class RefineRequest(
    val base: SolveRequest,
    val baseFingerprint: String,
    val baseline: SolverCandidateSnapshot,
    val pins: List<PinSpec>,
    val addedConstraints: ConstraintProgramPatchSpec,
    val similarityWeight: Double = 1.0,
    val search: SearchSpec? = null,
)
```

`SolverCandidateSnapshot` 至少含稳定槽、target identity、voice id 与精确 Pitch；`PinSpec` 为
`slotId + voiceId + pitch`，整槽/整声部只是 UI 展开的糖。patch 只含增量约束列表，不能再伪装成
自带 length/domain 的完整 program。协议版本、manifest schema、fingerprint 和 MCP 映射一并升级。

## 9. 后续：右端匹配与锁定

右端匹配复用 `rightBoundary`，但必须在完整候选末端执行生成尾帧 → 右边界的 transition rules
与 terminal distance；默认仍只传左边界。若长窗口出现大量临近尾部才失败，再增加反向可行性
启发式，不能先把右端音高硬钉到最后生成槽。

锁定单音直接编译一个 `VoicePitchPin`；锁定声部展开为范围内多个 pin。锁定旋律可能包含和弦外音，
此时还要把对应 cell 标为 `HarmonicVoiceParticipation.Sustained`，否则候选枚举会先按和弦成员
过滤掉它。UI 锁状态、notehead → 分析 voice 映射与是否持久化另行设计。

## 10. 测试

- 锁定/自由解释 target domain 与共享目录完全一致；无解释时结构化失败。
- 极端左边界不接受重新校验，仍参与第一连接软评分；边界放宽只在唯一死因时发生。
- baseline 精确相同零成本，改变逐 cell 产生 finding；pin 永不改变。
- `NoSolution`、`BudgetExhausted`、`Cancelled`、`Invalid` 可区分且节点计数稳定。
- 同 seed 候选与顺序复现；排除 key 后不返回旧候选；`maxResults=1` 不作为换 seed 方案。
- Layered DP 在同一声部首尾 `VoicePitchPin` 固定、中间槽待定时仍返回连接结果；增加端点 pin 应
  缩小而不是放大访问节点数，并保持在显式 transition/frontier 预算内。
- 范围外 Runtime 引用复用；跨边界声音保持；范围内引用无悬空；3–6 声部可物化。
- 将来公开 refine 的契约测试须覆盖 added constraint、过期 fingerprint、逐音/整声部 pin 和
  baseline finding，替换当前仅验证 `refine-not-available` 的占位测试。
