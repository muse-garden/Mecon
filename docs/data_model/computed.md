# 计算层 (Computed Layer)

> 路径：
> - 数据：`api/src/commonMain/kotlin/com/mecon/api/computed/`
> - 引擎：`core/src/commonMain/kotlin/com/mecon/core/engine/ComputeEngine.kt`
>
> **状态**：✅ 已实现（全量重算）；🚧 增量更新与依赖追溯见 [incremental-compute.md](incremental-compute.md)

计算层把 Runtime 的"事实数据"扩展为"渲染就绪"的派生数据：临时记号、符杠分组、五线谱位置、MIDI 音高、延音线目标等。

## 1. ComputedScore

```kotlin
data class ComputedScore(
    val runtime: RuntimeScore,
    val computedEvents: ComputedEventStore,   // 持久化 B+ 树，见 computed-event-store.md
    val barlines: TimeIndexedList<ComputedBarline>,
    val clefs: TimeIndexedList<ComputedClef>,
    val keySignatures: TimeIndexedList<ComputedKeySignature>,
    val timeSignatures: TimeIndexedList<ComputedTimeSignature>,
    val pluginTracks: Map<TrackId, ComputedPluginTrack<*>>,
)
```

- 通过 `runtime` 持有上层引用，避免数据冗余
- `computedEvents` 物化全部声部事件的派生数据，以持久化 [`ComputedEventStore`](computed-event-store.md)（measure / EventId 双 B+ 树索引）存储，使增量更新 O(log N) 无整表拷贝
- `barlines / clefs / keySignatures / timeSignatures` 是 Computed 层生成的标注事件，由渲染层直接消费

> 详见 `ComputedScore.kt`。

## 2. ComputedVoiceEvent

```kotlin
data class ComputedVoiceEvent(
    val runtimeEvent: RuntimeVoiceEvent,
    val pitchData: List<ComputedPitchData>,        // 每个音高一项
    val fermata: ComputedFermata?,                 // global fermata 投影到本声部目标事件
    val measurePosition: MeasurePosition,
    val resolvedStemDirection: ResolvedStemDirection,
    val beamInfo: BeamInfo?,
    val tupletInfo: ComputedTupletInfo?,        // 非 null 表示该事件是连音组起始
    val graceInfo: GraceNoteInfo?,              // 仅装饰音组首音非空（透传自 Runtime）
)

data class ComputedPitchData(
    val pitch: Pitch,
    val midiPitch: Int,
    val staffPosition: StaffPosition,         // 五线谱上垂直位置
    val effectiveAccidental: Accidental?,     // 是否需要绘制临时记号
    val needsLedgerLine: Boolean,
    val tieTarget: ComputedTieTarget?,
)
```

> 详见 `ComputedEvents.kt`、`ComputedTypes.kt`。

### BeamInfo 符号化表示

```kotlin
data class BeamInfo(
    val groupId: BeamGroupId,
    val totalBeamCount: Int,        // 该音符总共有几条符尾/符杠
    val beamsLeft: Int,             // 与前一个音符共享的符杠数
    val beamsRight: Int,            // 与后一个音符共享的符杠数
)
```

派生属性：`throughBeamCount` / `leftHookCount` / `rightHookCount` / `isGroupStart` / `isGroupEnd`，渲染层据此画 beam 与 hook，无需重新分析时值组合。

`BeamGroupComputer` 的分流规则：

- `rendering.beaming == null`：进入**自动符杠**，按拍号与时值分组
- `rendering.beaming == BeamingInfo.NONE`：视为**显式不连杠**，该音符既不生成 `BeamInfo`，也不会再落回自动分组
- `rendering.beaming != null` 且 `isBeamed == true`：按 `beamLeft / beamRight` 构建**用户定义符杠组**

因此 Computed 层以 `beaming` 是否为 `null` 来区分“系统自动”和“显式配置”，而不是只看 `isBeamed`。

### ComputedTupletInfo 连音组解析

```kotlin
data class ComputedTupletInfo(
    val startEventId: EventId,
    val endEventId: EventId,        // 组内最后一个事件（含）
    val count: Int,                 // N 连音
    val displayStyle: TupletDisplayStyle,
    val smallNotes: Boolean,        // 是否为占拍小音符输入区域
)
```

- 仅出现在连音组**起始**事件的 `tupletInfo` 字段上；组内其他事件 `tupletInfo == null`。
- `smallNotes` 从 `TupletSpan` 透传，使 Renderer 能把区域起点的隐藏休止符绘制为编辑标记，
  而不必在渲染层反推音乐语义。
- `TupletComputer` 把 Storage 层 `TupletSpan.endTimeCode`（不含）解析为组内最后一个实际事件 `endEventId`。
- `displayStyle` 原样透传，渲染层不做自动降级。
- 不跨小节做任何特殊处理——渲染层直接按解析出的首尾画出。

### 装饰音字段

`graceInfo` 由 `ComputedVoiceEvent.from()` 直接从 `RuntimeVoiceEvent.graceInfo` 透传，仅装饰音组**首音**非空。`ComputeEngine` 不对装饰音组做特殊聚合——`isGrace = (onset.grace != null)` 派生属性由消费者按需判断。tie 解析与播放语义见 [grace-notes.md](../renderer/grace-notes.md)。

### MeasurePosition

`MeasurePosition(measureIndex, beat: Fraction, isFirstInMeasure)`：把全局 `TimeCode` 解析到具体小节内部，方便渲染按小节布局。

## 2.5 ComputedStaffHeader

`ComputedScore.staffHeader: ComputedStaffHeader` 存储谱表左侧页边的所有渲染指令，由 `StaffHeaderComputer.compute(runtime)` 在 `computeScore()` 时一次性生成。

```kotlin
data class ComputedStaffHeader(
    val brackets: List<ComputedStaffBracket>,
    val labels: List<ComputedStaffLabel>,
    val barlineConnectivity: List<StaffIndexRange>
)

data class ComputedStaffBracket(
    val style: BracketStyle,
    val staffRange: StaffIndexRange,   // 包含的谱表索引区间（含两端）
    val depth: Int,                    // 0 = 最内侧（紧贴谱表），越大越靠外
    val sourceId: String               // 调试用：组 ID 或声部 ID
)

data class ComputedStaffLabel(
    val text: String,
    val abbreviation: String?,
    val staffRange: StaffIndexRange,
    val depth: Int,                    // 0 = 单行谱表标签；1 = 声部名；2+ = 组标签
    val sourceId: String,
    val placement: StaffLabelPlacement // 普通标签在左；演奏者编号紧随乐器名、位于所有括号左
)

data class StaffIndexRange(val first: Int, val last: Int)
```

### Depth 分配规则

| 内容 | depth |
|------|-------|
| 单行谱表标签（如 `"S."` / `"A."`） | 0 |
| 声部名（`instrumentName`） | 1 |
| 声部自带括号（如钢琴花括号） | 0 |
| 最内层组的括号 / 标签 | 2 |
| 次外层组 | 3 |
| …（越往外数字越大） | … |

`StaffHeaderComputer` 从默认（`lineId == null`）谱表分配生成逐谱表演奏者编号标签，
如圆号两行显示 `1,3` 与 `2,4`；它们使用 `BEFORE_BRACKETS`，位于所有括号左侧。
仅有一名演奏者的乐器不生成编号标签。

`StaffHeaderComputer` 先按嵌套深度由内到外赋予临时 depth，再整体翻转（`flip(d) = 2 + (maxDepth - d)`），确保 **最外层 = 最大 depth**，渲染层从大到小依次叠加到谱表左侧。

### barlineConnectivity

`barlineConnectivity: List<StaffIndexRange>` 描述小节线的连接分段——每段产生一条独立的垂直线。生成规则：

1. 同一声部内的相邻谱表：由 `RuntimePartTrack.innerBarlineConnect` 决定
2. 不同声部之间：检查是否有公共上级组且该组 `barlineConnect == true`
3. 按相邻谱表对的 boolean 结果合并为最大连续区间

`ComputeEngine` 把 `barlineConnectivity` 复制到每个 `ComputedBarline.connectedStaffRanges`，渲染层按此列表绘制分段小节线。

## 2.6 ComputedStaffAttachment（力度记号 / 渐强渐弱）

`ComputedScore.staffAttachments: List<ComputedStaffAttachment>` 由 `DynamicsComputer`
（`core/engine/DynamicsComputer.kt`）在 `computeScore()` 时生成：遍历 `runtime.orderedStaffs()`，
把每条谱表 `attachments` 中的 `StorageStaffAttachment` 解析为带 **显示顺序 `staffIndex`** 的
`ComputedDynamicMark` / `ComputedHairpin` / `ComputedBreathMark`（渲染层据此定位谱表）。局部
breath 按其 staff/voice scope 生成；每个 `StorageGlobalBreathMark` 展开为所有显示谱表各一项，
并保留共同的 `globalEventId` 供选择、编辑和删除联动。`placement` 按存储原样透传
（默认 BELOW），精确/手工偏移为后续工作。详见 [../renderer/dynamics.md](../renderer/dynamics.md)。

`ComputeEngine` 独立扫描 global fermata。对每个声部找到锚点前最后一个非装饰事件，将
`ComputedFermata(id, afterTime, extension, shape)` 写入该 `ComputedVoiceEvent`。Renderer 只根据
该字段选择 SMuFL 形态并排版，不自行判断哪个音符应有 fermata。

## 3. 标注事件

`ComputedNotationEvent` 是 Computed 层独立生成的视觉元素：

```kotlin
sealed interface ComputedNotationEvent {
    val onset: TimeCode
}

data class ComputedBarline(val type: BarlineType, ...) : ComputedNotationEvent
data class ComputedClef(val clef: Clef, ...) : ComputedNotationEvent
data class ComputedKeySignature(
    val keySignature: KeySignature,
    val isInitial: Boolean,
    val cancellationNaturals: List<CancellationNatural> = emptyList(),
    ...
) : ComputedNotationEvent
data class ComputedTimeSignature(val timeSignature: TimeSignature, ...) : ComputedNotationEvent
```

当 `RuntimeScore.showTimeSignatures=false` 时，`ComputeEngine` 不生成
`ComputedTimeSignature`；拍号数据仍保留用于小节时长、定位和自动符杠。

### 转调（Key Signature Change）

`ComputedKeySignature` 的 `cancellationNaturals` 字段由 `ComputeEngine` 在检测到调号变化时填充，遵循标准记谱规则：

| 转调类型 | 还原记号 | 示例 |
|---------|---------|------|
| 升号→降号 / 降号→升号 | 取消旧调所有变音记号 | E major (4♯) → A♭ major (4♭)：先画 4 个还原号 |
| 同类型，增加变音 | 无需还原 | G major (1♯) → D major (2♯)：直接画新调号 |
| 同类型，减少变音 | 取消被移除的变音 | A major (3♯) → G major (1♯)：C♯、G♯ 需还原 |
| 任意→C major (0) | 取消旧调所有变音 | G major → C major：F♯ 需还原 |
| C major→任意 | 无需还原 | C major → G major：直接画新调号 |

`CancellationNatural(noteName, fromSharpKey)` 携带音名和原变音类型（升号/降号），Renderer 据此在正确的五线谱位置放置还原号。数据来源：`StorageGlobalTrack.keySignatureChanges` 经 Runtime 层传播到每小节的 `RuntimeMeasure.keySignature`。

> ⚠️ **职责边界**：这些元素的"是否生成"由 Computed 层决定（如检测到调号变化才生成 `ComputedKeySignature`），**不要在渲染器内推导**。详见根目录 `AGENTS.md` 中"Renderer 与 Computed 层职责划分"。

> 详见 `ComputedNotationEvents.kt`。

## 4. ComputeEngine

```kotlin
object ComputeEngine {
    fun compute(runtime: RuntimeScore): ComputedScore
}
```

全量重算流程（位于 `core/engine/ComputeEngine.kt`）：

1. **MeasurePositionComputer** — 把 `TimeCode` 转为 `MeasurePosition`
2. **TieTargetComputer** — 解析 `ties` 中的目标事件，生成 `ComputedTieTarget`；源/目标 `crossStaffOffset` 不同（渲染在不同谱表）时降级为 let-ring
3. **EffectiveAccidentalComputer** — 在小节内追踪已出现的临时记号，决定每个音高是否需要绘制
4. **MidiPitchComputer** — 应用调号、移调乐器，得到实际 MIDI 音高
5. **StaffPositionComputer** — 按谱号计算垂直 staff position。涉及三项修正，均在 `ComputeEngine.computeVoiceEvent()` 内完成：
   - **跨谱表**：`crossStaffOffset` 非空时改用目标谱表的谱号（`resolveRenderStaff`）
   - **谱号变换**：`effectiveClef(onset, renderStaff)` 扫描 `renderStaff.clefChanges`，取最近的 `onset ≤ noteOnset` 变换，无则回退到初始谱号
   - **八度记号**：`octaveShiftDiatonicOffset(onset, homeStaff)` 扫描 `homeStaff.attachments` 中的 `StorageOctaveShiftStart/End` 对，若当前音符在左闭右开区间 `[startOnset, endOnset)` 内，返回 −7（8va）或 +7（8vb）并叠加到 staffPosition；渲染层括号虚线终止于 `endOnset` 前一个音符（见 `UnifiedTimeSlotMap.lastBefore`）
6. **BeamGroupComputer** — `rendering.beaming == null` 时按时值与拍号自动分组；非 `null` 时尊重显式配置（含 `BeamingInfo.NONE`），输出 `BeamInfo`
7. **computeNotationEvents** — 检测拍号 / 调号 / 谱号变化，生成 `ComputedNotationEvent`（调号变化时计算 `cancellationNaturals`）
8. **TupletComputer** — 把 `TupletSpan` 解析为 `ComputedTupletInfo`，检测整组共用 beam 时降级显示样式
9. **TiedChain 解析** — `ComputedScore.getTiedChains()` 按 `pitchIndex` 把 tie 链汇总为 `PitchTieChain`，支持部分和弦延音

每次调用 `compute()` 都从零生成完整快照。当前没有依赖追溯——任何修改都会触发全量重算。

## 5. 插件轨道工具扩展

### pluginEventsOf（ComputedScore）

> 路径：`api/src/commonMain/kotlin/com/mecon/api/computed/ComputedScore.kt`

从 `ComputedScore` 中提取指定轨道类型的全量 storage 事件列表：

```kotlin
inline fun <reified T : StoragePluginEvent> ComputedScore.pluginEventsOf(
    trackType: String
): List<T>
```

用于 Provider 批量遍历所有事件（注释布局、符头着色等）：

```kotlin
val chords = computedScore.pluginEventsOf<StorageChordEvent>(StorageChordEvent.TRACK_TYPE)
```

> ⚠️ `ComputedPluginTrack` 中的事件以匿名泛型对象存储，`filterIsInstance<ConcreteEvent>()` 始终返回空；必须通过 `runtimeEvent.storageEvent as? T` 提取，`pluginEventsOf` 已正确处理此细节。

### pluginTrackOf（RuntimeScore / ComputedScore）

> 路径：`api/.../runtime/RuntimeScore.kt`、`api/.../computed/ComputedScore.kt`

取出单条类型化轨道，用于点查 / 相邻事件查询（直接走 B+ 树，O(log n)）：

```kotlin
fun <T : StoragePluginEvent> RuntimeScore.pluginTrackOf(trackType: String): RuntimePluginTrack<T>?
fun <T : StoragePluginEvent> ComputedScore.pluginTrackOf(trackType: String): ComputedPluginTrack<T>?
```

```kotlin
val track = runtimeScore.pluginTrackOf<StorageChordEvent>(StorageChordEvent.TRACK_TYPE)
val prev: StorageChordEvent? = track?.prevEvent(onset)?.storageEvent
val next: StorageChordEvent? = track?.nextEvent(onset)?.storageEvent
```

**选型原则**：需要**全量列表** → `pluginEventsOf`；需要**单轨点查或相邻** → `pluginTrackOf`。

## 7. CalcBuilder（插件对齐工具）

```kotlin
object CalcBuilder {
    fun <A, B> alignBilateral(a: TimeIndexedList<A>, b: TimeIndexedList<B>)
    fun <Ref, Aln> alignLe(referenceTrack, alignTrack, offset: Int = 0)
    fun <Ref, Aln> alignL(referenceTrack, alignTrack)
    fun <A, B, R> mapToNewTrack(...): TimeIndexedList<R>
}
```

为插件提供两类对齐：
- `alignBilateral`：双向并联（拿到任一侧事件变化的所有时间点）
- `alignLe / alignL`：以参考轨道为基准，对齐时回查最近的辅助事件（`alignLe` 用 `<=`、`alignL` 用 `<`，再按 `offset` 平移索引）

### 增量对齐（`CalcBuilderIncremental.kt`）

重复对齐场景（轨道一次只变一点）用有状态对齐器，避免每次全量重排：

```kotlin
val aligner0 = ReferenceAligner.build(refTrack, alignTrack)   // 或 BilateralAligner.build(a, b)
val aligner1 = aligner0.update(newRef, newAlign)              // 只重算受影响行，其余按引用复用
aligner1.aligned                                             // TimeIndexedList<AlignedEvent2<…>>
aligner1.lastRecomputedRowCount                              // 本次重算行数（效果度量）
```

`update` 用 [`TimeIndexedList.changedSpan`](../data_model/computed-event-store.md)（基于 `BPlusTree.diff`，`===` 子树整跳）定位变化区间，据对齐语义推出受影响窗口（reference 改动→自身行；align 改动→回查命中改变的行区间，含 `alignL` 严格边界的「下一行也受影响」修正），重算窗口并拼接进持久化输出。`offset != 0` 时 align 轨改动会平移所有后续索引 → 退化为整轨重排（reference 轨改动仍紧界）。

**黄金法则**：`update(a, b).aligned` 与全量 `CalcBuilder.align*(a, b)` 逐值相等（`CalcBuilderIncrementalTest` 随机模糊守护）。消费者示例：`ChordToneAnalysis` 缓存 `ReferenceAligner`，编辑后只重算受影响音符的和弦内外音判定。

详见 [../plugin/custom-track.md](../plugin/custom-track.md) 第 7 节。

## 8. 设计取舍

**为什么物化所有派生字段，而不是按需计算？**

- 渲染、播放、分析这些下游消费者各自重算成本高
- 一次 `compute()` 后所有结果可被任意次数读取
- 不可变 `ComputedScore` 适合 `MutableStateFlow` 直接发布给 UI

**为什么是全量重算？**

- 当前性能足够（即便上千事件也在毫秒级）
- 增量更新需要完整的依赖图（参见 🚧 [incremental-compute.md](incremental-compute.md)）
- 全量保证正确性，避免"局部更新遗漏依赖"的隐患
