# 运行时层 (Runtime Layer)

> 路径：`api/src/commonMain/kotlin/com/mecon/api/runtime/`

Runtime 层是 Storage 反序列化后驻留内存的形态，重点是**对象引用**与**时间索引查询**。

## 1. RuntimeScore

`RuntimeScore.fromStorage(storage)` 将 Storage 层的 ID 引用解析为对象引用：

```kotlin
data class RuntimeScore(
    val metadata: ScoreMetadata,
    val defaultTimeSignature: TimeSignature,
    val showTimeSignatures: Boolean,
    val pitchTracks: Map<TrackId, RuntimePitchTrack>,
    val voiceTracks: Map<TrackId, RuntimeVoiceTrack>,
    val staffTracks: Map<TrackId, RuntimeStaffTrack>,
    val partTracks: Map<TrackId, RuntimePartTrack>,
    val pluginTracks: Map<TrackId, RuntimePluginTrack<*>>,
    val globalTrack: RuntimeGlobalTrack,
    val measures: List<RuntimeMeasure>,        // 解析后的小节列表
    val partOrder: List<TrackId>,
)
```

`measures` 由 `globalTrack` 的拍号、小节线推导而来，提供给 ComputeEngine 与渲染层使用。每个 `RuntimeMeasure.keySignature` 综合以下三个来源（优先级递减）解析得到：`StorageMeasure.keySignature` → `StorageGlobalTrack.keySignatureChanges` → 前序小节继承。

`runtime.toStorage()` 只在保存文件的序列化边界执行。编辑、计算和撤销历史都以 Runtime 为权威状态，不为每次编辑反向同步 Storage。

`showTimeSignatures` 从 Storage 原样透传并在 `toStorage()` 写回；ComputeEngine 用它决定是否
生成拍号记谱事件，但不改变 `RuntimeMeasure.timeSignature` 的计时语义。

> **序列化完整性（`toStorage` 必须覆盖每个持久化字段）**：Runtime 是打开文件后的唯一内存权威状态，因此必须携带页面布局、视图偏好、全局轨、几何以及 analysis/orchestration 直通值；保存时 `toStorage()` 一次性写回全部字段。编辑逻辑不得读取或修改 `StorageScore`，否则会重新引入整谱转换和双份状态漂移。

## 2. TimeIndexedList

```kotlin
class TimeIndexedList<T : HasOnset> private constructor(
    private val tree: BPlusTree<TimeCode, T>,
)
```

封装一棵以 `TimeCode` 为键的 B+ 树，提供 O(log n) 的范围查询：

| API | 语义 |
|-----|------|
| `range(start, end)` | 时间区间内的事件 |
| `at(time)` | 该时间点的全部事件 |
| `lastBefore(time)` / `before(time)` | 严格早于的最后一个 |
| `firstAtOrAfter(time)` / `atOrAfter(time)` | `≥` 的第一个 |
| `insert(event)` / `insertAll(events)` | 返回新实例（结构共享） |
| `removeWhere { ... }` / `remove(id)` | 同上 |
| `filter / find / any / all` | 流式遍历 |

不可变更新依靠 B+ 树的结构共享实现：单事件插入只复制 O(log n) 个节点。

> 详见 `TimeIndexedList.kt`。

## 3. 运行时事件

```kotlin
data class RuntimePitchEvent(
    override val id: EventId,
    override val onset: TimeCode,
    val pitches: List<Pitch>,
    val articulations: List<Articulation>,   // 演奏记号（音乐信息）
) : RuntimeEvent

data class RuntimeVoiceEvent(
    override val id: EventId,
    override val onset: TimeCode,
    val pitchEvent: RuntimePitchEvent,    // 对象引用
    val duration: Duration,
    val rendering: VoiceEventRendering?,
    val ties: List<RuntimeTieInfo> = emptyList(),
    val tupletSpan: TupletSpan? = null,    // 透传自 Storage；仅起始事件非空
    val slurStarts: Int = 0,
    val slurEnds: Int = 0,
    val graceInfo: GraceNoteInfo? = null,  // 仅装饰音组首音非空
) : RuntimeEvent {
    val isGrace: Boolean get() = onset.grace != null
    val isGraceGroupStart: Boolean get() = graceInfo != null
}
```

`RuntimeTieInfo(pitchIndex, targetEvent: RuntimeVoiceEvent?, isLetRing: Boolean)` 把目标 ID 解析为目标对象引用。`isLetRing = (targetEventId == null)`。

**两轮转换**：`RuntimeScore.fromStorage()` 先遍历所有声部事件建立 `EventId → RuntimeVoiceEvent` 映射，第二轮再用 `copy(ties = ...)` 补填解析后的 `RuntimeTieInfo`，解决目标事件可能排在源事件之后的鸡蛋问题。

`RuntimePluginEvent<T : StoragePluginEvent>(val storageEvent: T)` 包装原始 Storage 事件，便于插件在运行时持有附加状态。

### 延长与停顿的演奏时间

Runtime 保持记谱时值不变。`RuntimeScore.performanceTimingFor(voiceTrackId, eventId)` 将
global fermata、全谱 breath 以及当前谱表/声部的 breath 解析为
`PerformanceTimingAdjustment(fermataExtension, globalBreathPause, localBreathPause)`。所有标记都以其后方 TimeCode
为锚点，目标是适用声部中严格早于该时刻的最后一个非装饰事件；同一事件上的数值相加。
`ScoreToMidiConverter` 在反复展开后消费该结果：fermata 推迟目标 note-off 及后续事件；全谱
breath 保留目标 note-off 并推迟后续事件；声部/谱表 breath 从目标音的尾部扣除休止量，后续
事件仍保持原书面拍点。`PlaybackTimeline` 只记录 fermata 与全谱 breath 的 hold 区间，播放线
在全谱停顿期间停留于对应书面位置，并在结束后扣除累计延时继续映射书面时间。
全局轨变化会使增量计算退化为完整重算，因为它可能同时影响所有声部。

> 详见 `events/RuntimeEvents.kt`。

## 4. 运行时轨道

```kotlin
data class RuntimePitchTrack(val events: TimeIndexedList<RuntimePitchEvent>, ...)
data class RuntimeVoiceTrack(
    val voiceNumber: Int,
    val pitchTrackId: TrackId,
    val pitchTrack: RuntimePitchTrack,    // 解析后的引用
    val events: TimeIndexedList<RuntimeVoiceEvent>,
    ...
)
data class RuntimeStaffTrack(
    val clef: Clef,
    val voiceTrackIds: List<TrackId>,
    fun getPitchTracks(): List<RuntimePitchTrack>   // 间接获取
)
data class RuntimePartTrack(...)
data class RuntimePluginTrack<T : StoragePluginEvent>(val type: String, val events: ...)
```

> 详见 `tracks/RuntimeTracks.kt`。

## 4.5 谱表头运行时树（RuntimeStaffGroup）

```kotlin
data class RuntimeStaffGroup(
    val id: StaffGroupId,
    val bracket: BracketStyle,
    val label: String?,
    val abbreviation: String?,
    val barlineConnect: Boolean,
    val members: List<RuntimeStaffGroupMember>
) {
    fun allParts(): List<RuntimePartTrack>   // 递归收集组内所有声部
}

sealed interface RuntimeStaffGroupMember {
    data class Part(val part: RuntimePartTrack) : RuntimeStaffGroupMember
    data class Group(val group: RuntimeStaffGroup) : RuntimeStaffGroupMember
}
```

`RuntimeScore.staffGroups: List<RuntimeStaffGroup>` 是顶层列表。`fromStorage()` 递归解析 `StorageStaffGroup` 树，将 `partId` 解析为对象引用；`toStorage()` 递归还原。

**`RuntimePartTrack` 新增字段**：

| 字段 | 来源 |
|------|------|
| `innerBarlineConnect: Boolean` | 透传自 Storage |
| `partBracket: BracketStyle` | 若 Storage 为 `null` 则自动推断：≥2 行谱表→`BRACE`，否则→`NONE` |

**`RuntimeStaffTrack` 新增字段**：

| 字段 | 含义 |
|------|------|
| `staffLabel: String?` | 传自 `StorageStaffTrack.staffLabel` |
| `staffLabelAbbreviation: String?` | 传自 `StorageStaffTrack.staffLabelAbbreviation` |

## 5. 修改与同步

修改流程在内存中始终是不可变更新：

```
old RuntimeScore  →  copy(...) 新 Track / TimeIndexedList  →  new RuntimeScore
                                                                    │
                                                                    └─ toStorage() → 落盘
```

**为什么不直接修改 BPlusTree？** 多个组件可能同时读到旧版本——可靠的做法是维持引用透明，所有写操作返回新树。结构共享让代价保持在 O(log n)。

## 6. 与上下游的关系

```
StorageScore  ──fromStorage──▶  RuntimeScore  ──ComputeEngine──▶  ComputedScore
     ▲                              │
     └─────────toStorage────────────┘
```

- **下游（Computed）**：`ComputeEngine.compute(runtime)` 只读取 Runtime，写出 `ComputedScore`
- **上游（Storage）**：`runtime.toStorage()` 在持久化点执行
- **状态管理**：`ScoreStateManager` 同时缓存这三个层的快照（[../state-management.md](../state-management.md)）

## 7. 设计取舍

**为什么 Runtime 层不直接持有 Computed 字段？**

Storage / Runtime 是数据事实；Computed 是规则推导。两者分离让规则可独立演进，且支持插件层注入派生逻辑。
