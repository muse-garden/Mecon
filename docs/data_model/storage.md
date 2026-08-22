# 存储层 (Storage Layer)

> 路径：`api/src/commonMain/kotlin/com/mecon/api/storage/`

存储层是乐谱在磁盘上的形态，专为序列化与版本管理而设计。

## 1. StorageScore

新谱工厂以不可变 `StorageScore.CreationOptions` 收拢 metadata、默认拍号/调号/速度、
谱表预设、初始小节数、页面配置和乐器/分组模板；`StorageScore.create(options)` 只负责把该
规格展开为 Storage 层轨道。配置只含源字段，不保存 Runtime/Computed 对象引用。

`StorageScore` 是顶层容器，按轨道类型分组：

```kotlin
@Serializable
data class StorageScore(
    val metadata: ScoreMetadata,
    val defaultTimeSignature: TimeSignature,
    val showTimeSignatures: Boolean = true,
    val pitchTracks: Map<TrackId, StoragePitchTrack>,
    val voiceTracks: Map<TrackId, StorageVoiceTrack>,
    val staffTracks: Map<TrackId, StorageStaffTrack>,
    val instruments: List<StorageInstrument>,
    val pluginTracks: Map<TrackId, StoragePluginTrack>,
    val globalTrack: StorageGlobalTrack,
    val staffGroups: List<StorageStaffGroup>,
    val pageLayout: PageLayoutConfig,   // 纸张 / 页边距 / 比例 / 是否分页
    val viewPreferences: ScoreViewPreferences,  // 视图偏好（renderer 不读取），随文件持久化
    val reductions: List<StorageReduction>,      // 分层缩谱工作区、素材台与逐音 NoteLink
    val orchestration: StorageOrchestration?,    // O0 配器/谱表时变分配
)
```

- `globalTrack` 装载与具体声部无关的全局事件（拍号、调号、速度、文本、**强制分行 / 分页**）
- `showTimeSignatures=false` 只抑制拍号记谱元素；默认拍号与逐小节拍号仍参与小节时长、
  事件定位和自动符杠。该字段由 Computed 层消费，Renderer 不临时过滤拍号。
- 其他 `*Track` 引用关系都使用 `TrackId / EventId`
- `pageLayout` 见下方 §1.1，随文件持久化
- `viewPreferences` 见下方 §1.2：纯展示层状态，renderer 不消费
- 工厂：`StorageScore.create()` / `StorageScore.createDemo()`

### 1.3 Analysis / orchestration extensions

`StorageReduction` 是共享主谱 TimeCode 的分层工作区。层按固定语义顺序使用
`FORM / HARMONY / SKELETON / NOTATION / ORCHESTRATION`；`SKELETON` 与 `NOTATION`
可嵌套完整 `StorageScore`，时间轨层使用 `timelineItems`。`materialTray` 保存尚未确定
作品位置的 `StorageScoreFragment`，片段只保留局部乐谱与可选来源元数据，不带作品锚点。

```kotlin
data class StorageReduction(
    val id: ReductionId,
    val title: String,
    val anchor: ReductionAnchor?,
    val layers: List<StorageReductionLayer>,
    val materialTray: List<StorageScoreFragment>,
    val links: List<StorageNoteLink>,
)
```

旧文件的 `StorageReduction.score` 在反序列化后迁入默认 `NOTATION` 层；新写文件不再输出
这个兼容字段。`StorageNoteLink(source, target, octaveShift)` 仍表达内容线音符与记谱层
音符之间的多对多关系；`StorageOrchestration.links` 再表达内容线与总谱书面音符的关系。
演奏者标签由 `StorageOrchestration.players / performances` 派生，不复制进缩谱层。
详细语义见 [analysis/reduction.md](../analysis/reduction.md) 与
[analysis/orchestration.md](../analysis/orchestration.md)。

### 1.1 PageLayoutConfig（分行 / 分页）

`api/.../storage/PageLayoutConfig.kt`，**物理单位 mm** + 谱表大小：

```kotlin
@Serializable
data class PageLayoutConfig(
    val paperWidthMm: Float, val paperHeightMm: Float,
    val marginTopMm: Float, val marginBottomMm: Float,
    val marginLeftMm: Float, val marginRightMm: Float,
    val staffSpaceMm: Float,       // rastral：一个 staff space = X mm（即乐谱比例）
    val paginated: Boolean = false,// 默认连续单行
    val presetName: String? = "A4",
)
```

- `contentWidthStaffSpaces` / `contentHeightStaffSpaces` 由 mm / `staffSpaceMm` 派生，供布局层换行用。
- 纸张预设 `PaperPreset.ALL`：A4 / A3 / A5 / Letter / Legal。
- 渲染端经 `PageGeometry.from(config)` 投影到 staff space，详见 [../renderer/layout.md §7](../renderer/layout.md)。
- MusicXML 互转：`<defaults><scaling>` / `<page-layout>` 映射纸张、边距与 `staffSpaceMm`；
  `<print new-system/new-page>` 映射下方的强制断点。详见 [musicxml.md §4](musicxml.md)。

**强制断点**（`globalTrack.events` 内，`type` 判别）：

```kotlin
data class StorageSystemBreak(val onset: TimeCode) : StorageGlobalEvent  // 在 onset.measure 前换行
data class StoragePageBreak(val onset: TimeCode)   : StorageGlobalEvent  // 在 onset.measure 前翻页（并隐含换行）
```

> 详见 `StorageScore.kt`。

### 1.2 ScoreViewPreferences（视图偏好）

`api/.../storage/PageLayoutConfig.kt`，描述乐谱**如何展示**，与 `pageLayout` 分离——分页排列仅供
UI 使用；行首小节号由 renderer 的最终 post-layout pass 读取。两者都不参与布局 / 渲染前置流程，
随文件持久化以便重新打开时恢复：

```kotlin
@Serializable
enum class PageArrangement { VERTICAL, HORIZONTAL }  // 分页时纸张上下 / 左右排列

@Serializable
data class ScoreViewPreferences(
    val pageArrangement: PageArrangement = PageArrangement.VERTICAL,
    val showMeasureNumbers: Boolean = true,
)
```

`showMeasureNumbers` controls the optional measure number drawn at each system's first measure.
It is emitted by the renderer's final post-layout pass, so it never participates in spacing,
line breaking, or pagination. `ScoreStateManager.updateViewPreferences {}` updates this preference
in place (and mirrors it into the computed runtime without recomputing the score), so the toggle
does not enter the undo stack.

- 分页模式下 `RenderResult.pages` 返回页内局部坐标，UI 按 `pageArrangement` 排列各页（详见 [../renderer/layout.md §7](../renderer/layout.md)）。
- 切换由 `ScoreStateManager.updateViewPreferences{}` **就地更新**（复用现有 runtime/computed，不重算、不入撤销栈）。

### 1.3 ScoreGeometry（持久化渲染几何 overlay）

`api/.../storage/ScoreGeometry.kt`，可选字段 `StorageScore.geometry: ScoreGeometry? = null`，持久化
**slur / articulation / 谱表附着符号 / beam 的渲染几何**，使其可保存、可局部重算并手动编辑：

```kotlin
@Serializable
data class ScoreGeometry(
    val articulations: Map<EventId, ArticulationGeometry> = emptyMap(),  // 键 = voice EventId
    val slurs: Map<EventId, SlurGeometry> = emptyMap(),                  // 键 = StorageSlurEvent.id / ComputedSlur.slurId
    val attachments: Map<EventId, AttachmentGeometry> = emptyMap(),      // 键 = ComputedStaffAttachment.id
    val beams: Map<String, BeamGeometry> = emptyMap(),                    // stable BeamGroupId.value
)
```

- **缺失（`null`）= 按现有逻辑自动排版**——旧文件 / 未存几何的文件行为完全不变（`encodeDefaults=false` 不写该键）。
- 坐标单位 staff space（`Float`，存储层不依赖 renderer 的 `StaffSpace`），存于**稳定的锚点相对系**，使多数编辑不致失效：
  - **articulation**：每个 glyph 原点序列化为相对其**时间槽 X** 与**谱表中线**的偏移（`MarkOffset(index, above, dx, dy)`）。消费时 X 会按当前符头/符干列重新居中，避免声部避让等水平位移让记号脱离音符；Y 仍复用持久化偏移。该缓存必须覆盖事件当前全部可绘制演奏法，索引集合不匹配时整组回退自动排版，不能用旧的单记号缓存隐藏后来追加的记号。
  - **slur**：端点相对**所锚音头**的偏移（`startDx/Dy`、`endDx/Dy`，跟随音符移动），曲线形状存 apex / damping 等参数。跨行 slur 第一版不持久化（回退自动）。
  - **attachment**（hairpin / 8va / 8vb，Phase 3a）：`AttachmentGeometry` 存**两个端点**——`startDx/endDx`
    各自相对**其 onset 槽位 X**、`startDy/endDy` 相对谱表中线——+ 楔形开口 `spread`；点状（dynamic）省略 end 字段。
    自动排版保持 hairpin 水平（`startDy == endDy`）；用户拖动端点后写入 `manuallyAdjustedY=true`，此时 X
    继续由 onset 与排版避让决定，两个端点 Y 独立持久化。重排仅在新音符或谱表边界发生碰撞时，以最短共同位移推出安全区。
  - **beam（Phase 4）**：按稳定 `BeamGroupId` 保存端点 Y。普通 beam 相对所属谱表中线；cross-staff beam 由
    `CrossStaffBeamBase + crossStaffOffset` 记录共同基准线，`BETWEEN_STAFFS` 额外用
    `betweenStaffUpperIndex/betweenStaffLowerIndex` 指明具体相邻谱表对，因此可无歧义表达跨三个以上谱表的 beam。
    `manuallyAdjusted=true` 仅用于用户拖动后的 beam；自动捕获保持 `false`，可在排版条件变化时重新计算。
    连接关系仍来自 computed `BeamInfo`，不存入几何 overlay；连/断编辑只失效旧、新 group 并集。
- **一旦存在即为全量与增量渲染共同的事实来源**：`render(带 overlay 的谱)` 是确定性的，全量/增量仍逐像素一致。
  渲染消费见 [../renderer/ties-and-slurs.md §7.8](../renderer/ties-and-slurs.md) / [../renderer/articulations.md](../renderer/articulations.md) /
  [../renderer/beams.md](../renderer/beams.md)；
  局部重算的影响范围分析见 [incremental-update.md](incremental-update.md)。

### 1.4 小节线与反复边界

`StorageScore.initialBarlineType` 保存乐谱开头的小节线类型；`StorageMeasure.endBarlineType`
保存该小节右边界的显式非反复样式。后者为 `null` 时，Computed 层按位置生成
`SINGLE` 或末尾的 `FINAL`。

反复仍按 MusicXML 的左右边界语义存储：

- `StorageMeasure.repeatStart`：本小节左边界为开始反复；
- `repeatEnd` / `repeatCount`：本小节右边界为结束反复及总演奏次数，默认 2 次；
- 同一逻辑边界若前一小节 `repeatEnd` 且后一小节 `repeatStart`，Computed 层生成
  `REPEAT_BOTH`。
- `voltaNumbers: Set<Int>` 标记该小节属于哪些房子；Computed 层把连续且号码相同的小节
  合并成 `ComputedVoltaEnding`，Renderer 只负责按系统切段画括号。
- `navigationMarks: Set<NavigationMark>` 保存 Segno、Coda、To Coda、Fine 和
  D.C./D.S.（含 al Fine / al Coda）指令。Computed 层生成 `ComputedNavigationMark`。
- `navigationMarkOffsets: Map<NavigationMark, NavigationMarkOffset>` 保存用户拖动后的
  `dx/dy`（谱表间距单位）；未出现的记号使用自动排版位置，删除记号时同步删除对应偏移。

`ComputeEngine.computeBarlines()` 是是否及生成何种 `ComputedBarline` 的唯一来源；
Renderer 只按其 `BarlineType` 排版。编辑由 `BarlineEditEngine` 同时更新边界两侧，
避免出现视觉类型与播放反复配置不一致。

## 2. 轨道类型

```kotlin
@Serializable
sealed interface StorageTrack { val id: TrackId }

data class StoragePitchTrack(...) : StorageTrack
data class StorageVoiceTrack(
    val voiceNumber: Int,
    val pitchTrackId: TrackId,    // 1:1 引用
    ...
) : StorageTrack
data class StorageStaffTrack(val clef: Clef, val transposition: TranspositionConfig?, ...)
data class StorageInstrument(
    val id: InstrumentId,
    val name: String,
    val catalogId: String?,       // 可选稳定目录 ID；用于保留圆号等乐器特有的默认分配
    val staffIds: List<TrackId>,
    val playback: InstrumentPlayback,
)
data class StorageGlobalTrack(...)
data class StoragePluginTrack(val type: String, ...) : StorageTrack
```

**轨道层级**：`Part → Staff → Voice → Pitch`，每一层只引用直接子级 ID。

和弦分析插件另存两类不可变区间事件：

- `StorageNonChordToneEvent(onset, endOnset, voiceEventId, pitchIndex)`：半开区间
  `[onset, endOnset)`，可只标记一个音符的一部分；
- `StorageTonalRegionEvent(onset, endOnset, keys, resolvedKey, role)`：候选调性可为多个；
  `resolvedKey` 必须属于 `keys`，表示区间结束后持续生效的调性中心。`role` 默认为
  `INSERTED`；`SCORE_KEY_BASELINE` 把谱面调号投影为可编辑的初始调性区间，使其与插入调性的
  交集成为可拖动的双重调性范围。旧文件缺少该字段时按 `INSERTED` 读取。

二者只保存源字段和 ID，不保存 Runtime / Computed 引用；轨道类型分别为
`mecon.chord_analysis.non_chord_tones` 与 `mecon.chord_analysis.tonal_regions`。
有限区间事件实现 `StoragePluginIntervalEvent`，宿主据此生成局部 RenderHint；调性区域还实现
`StoragePluginForwardAffectingEvent`，因为终点中心会继续影响后续音级解释。

> 详见 `tracks/StorageTracks.kt`。

## 3. 事件类型

```kotlin
@Serializable
sealed interface StorageEvent {
    val id: EventId
    val onset: TimeCode
}
```

| 事件 | 关键字段 | 所在轨道 |
|------|---------|---------|
| `StoragePitchEvent` | `pitches: List<Pitch>`, `articulations: List<Articulation>` | `PitchTrack` |
| `StorageVoiceEvent` | `pitchEventId`, `duration`, `rendering`, `ties: List<TieInfo>?`, `tupletSpan: TupletSpan?`, `slurStarts/slurEnds: Int`（legacy）, `graceInfo: GraceNoteInfo?` | `VoiceTrack` |
| `StorageSlurEvent` | `id`, `startEventId`, `endEventId` | `VoiceTrack.slurs` |
| `StorageDynamicMark` / `StorageHairpin` | 力度记号 / 渐强渐弱（见 §3.6） | `StaffTrack.attachments` |
| `StorageBreathMark` | 后续 `TimeCode`、休止拍数、形态、可选 `voiceNumber` | `StaffTrack.attachments` |
| `StorageOrnamentMark` | trill/mordent/turn 类型、音符或音间锚点、辅助音变音、元素时值、波动次数与播放模式 | `StaffTrack.attachments` |
| `StorageFermata` / `StorageGlobalBreathMark` | 后续 `TimeCode`、延长/休止拍数、形态 | `GlobalTrack` |
| `StorageTempoEvent` | 速度、引用、显示和过渡关键帧（见 [tempo.md](tempo.md)） | `GlobalTrack` |
| `StorageTextEvent` | `text`, `textType` | `GlobalTrack` |
| `StoragePluginEvent` | （抽象，子类自行扩展） | `PluginTrack` |

> 详见 `events/StorageEvents.kt`。

### 3.1 装饰音与琶音

`StorageOrnamentMark` 是带稳定 `EventId` 的谱表附件，并以 `sourceEventId` 指向被装饰的
`StorageVoiceEvent`。`anchor` 区分记号位于音符上方或位于该音与后续音之间；区间 trill
另外保存 `endOnset`，Computed 层据此生成普通点状记号或带波浪延长线的区间附件。

辅助音默认使用当前调号内的相邻音级。`upperAccidental` / `lowerAccidental` 仅在用户覆盖时
保存显式变音；Renderer 只根据 Computed 结果显示对应 accidental glyph，不重新推导调内音高。
`elementDuration` 以四分音符拍为单位，插入时由当前有效 BPM 选择接近 100–150ms 的默认值，
之后可在属性面板修改。mordent 的 `oscillations` 表示往返次数。

trill 的 `playbackMode=AUTO` 表示播放后端支持原生装饰音控制流时优先发送控制事件，否则退化为
短音符展开；`EXPANDED` 强制展开，`CONTROL_FLOW` 请求控制流但在不支持的后端仍安全退化。

琶音是被装饰和弦自身的记谱/演奏属性，存于 `RenderingProps.arpeggio`。它不属于谱表上方附件：
Computed 音符携带其方向，Render Geometry 按和弦最低/最高符头生成竖向波线并预留左侧宽度。

## 3.4 符杠配置（`RenderingProps.beaming`）

`StorageVoiceEvent.rendering?.beaming` 有两层语义：

- `null`：**未显式指定**，交给 Computed 层按拍号 / 时值做自动符杠分组
- `BeamingInfo.NONE`：**显式不连杠**，保留符尾，不允许 Computed 层再自动把该音符并入 beam 组
- `BeamingInfo.start() / middle() / end()`：显式指定与相邻音符的连接关系

因此 `BeamingInfo.NONE` 与 `null` 不等价。前者是用户或导入器写下的版面决定，后者才表示“系统自动”。

## 3.5 跨谱表渲染（RenderingProps.crossStaffOffset）

`RenderingProps.crossStaffOffset: Int?` 让单个 `StorageVoiceEvent` 临时渲染到相邻谱表上
（钢琴常见的跨谱表记谱）。取值为**谱表显示顺序**（[`orderedStaffs`](runtime.md#45-谱表头运行时树runtimestaffgroup)）上的带符号偏移：

| 值 | 含义 |
|----|------|
| `null` / `0` | 本谱表（默认） |
| `-1` | 上方一行谱表 |
| `+1` | 下方一行谱表 |

偏移在解析时会被裁剪到可用谱表范围内。该音符仍归属其原声部（参与符杠、连奏线、连音线），但：

- 垂直位置改用**目标谱表的谱号**计算（`ComputeEngine` 用 `renderStaff.clef`）；
- 若处于符杠组且组内音符跨谱表，符杠落在两谱表之间，符杆交错；
- 连音线只能连同一谱表的两音——源/目标 `crossStaffOffset` 不同则降级为 let-ring。

> MusicXML 互转暂不映射该字段。

## 3.5b 休止符竖直位置（RenderingProps.restStaffPosition）

`RenderingProps.restStaffPosition: Int?` 指定**休止符**的显示谱位（对音符/和弦无效）。多声部排版常把上声部休止符上移、下声部下移以避让；该字段让用户把休止符钉到任意谱线/谱间。

- 约定同 [`ComputedPitchData.staffPosition`](computed.md)：`0` = 中线，正=上、负=下，每步半个 staff space（`relativeY = -position * 0.5`）。
- `null` = 按类型默认（全休止符挂在中线上方 = 位置 2，其余居中 = 位置 0；见 `RestLayout.defaultRestStaffPosition`）。
- 这是**纯渲染**选择（不影响演奏），故记在 voice 事件的 `RenderingProps` 上，而非共享的 `StoragePitchEvent`。
- 插入休止符时仍用默认位置；用户可在画布上拖动调整（吸附到谱位步），编辑走 [`NoteEditEngine.moveRest`](../ui/score-editing.md)；拖回默认位置时归一为 `null`。
- MusicXML：导出为 `<rest><display-step>/<display-octave>`（按所在谱号换算），导入反算回谱位；默认休止符不写 display 元素。见 [musicxml.md](musicxml.md)。

## 3.6 力度记号与渐强渐弱（Staff Attachments）

力度记号、渐强渐弱等"画在谱表上的额外符号/文本"统一抽象为 **谱表附着符号**
（`StorageStaffAttachment`），存储在 `StorageStaffTrack.attachments` 上（**不在**声部轨道），
可选 `voiceNumber` 缩小到单个声部。

```kotlin
@Serializable
sealed interface StorageStaffAttachment {
    val id: EventId
    val onset: TimeCode
    val voiceNumber: Int?                    // null = 整条谱表
    val placement: StaffAttachmentPlacement  // ABOVE / BELOW
}

@SerialName("dynamicMark")
data class StorageDynamicMark(..., val level: DynamicLevel, val controllerEventId: EventId?)

@SerialName("hairpin")
data class StorageHairpin(
    ..., val endOnset: TimeCode,
    val direction: HairpinType,    // CRESCENDO / DIMINUENDO（字段名避开多态判别符 `type`）
    val style: HairpinStyle,       // WEDGE（绘制箭头）/ TEXT_DASHED（cresc./dim. + 虚线）
    val controllerStartId: EventId?, val controllerEndId: EventId?,
)
```

- `DynamicLevel` 用 `letters`（如 `"mf"`、`"pp"`、`"n"`）保存 MusicXML 兼容拼写，渲染层将
  每个等级映射为 Bravura 中对应的预组合 SMuFL 字形，不手动拼接基础字母。
- 渐强渐弱记录在开始 `onset`，`endOnset` 为结束位置；对应 Controller Track 上的开头/结束两个事件。

### 延长与停顿

- `StorageFermata` 存在 `globalTrack.events`，`onset` 是记号**后方**的 TimeCode；
  `extension: Fraction` 为正数，单位是四分音符拍（`1`/`1/1` = 一拍），表示每个声部在该时刻前
  最后一个非装饰事件的附加拍数。
- `StorageBreathMark` 存在谱表 attachments：`voiceNumber != null` 表示单声部，`null` 表示单谱表；
  `pause: Fraction` 同样以四分音符拍计。二者可随音符剪切/复制/粘贴并保存 `AttachmentGeometry`。
- `StorageGlobalBreathMark` 存在 global track，作用于全谱，不能进入剪贴板。
- fermata 与三种 breath 都保存形态枚举；三种 breath 均可拖到音符列中点或小节线重新吸附。
  全谱 breath 的时间移动联动所有谱表，其单谱表投影可保存编辑几何。旧
  `StoragePitchEvent.articulations` 中的 `FERMATA` 仅保留文件兼容，新建操作不再写入。

### 八度记号（8va / 8vb）

八度记号也存储在 `attachments`，用两个相互引用的事件表示：

```kotlin
@Serializable
enum class OctaveShiftType { OTTAVA, OTTAVA_BASSA }

@SerialName("octaveShiftStart")
data class StorageOctaveShiftStart(
    ..., val shiftType: OctaveShiftType,
    val endEventId: EventId,    // 对应 StorageOctaveShiftEnd 的 id
    val placement: StaffAttachmentPlacement  // ABOVE = 8va；BELOW = 8vb
)

@SerialName("octaveShiftEnd")
data class StorageOctaveShiftEnd(..., val placement: StaffAttachmentPlacement)
```

**语义**：音高数据存储的是**实际音高（发声音高）**。括号内的音符在视觉上移位一个八度（7 个自然音阶步），使记谱更靠近谱表中心，减少加线：
- `OTTAVA`（8va）：书写音比实际音低一个八度，五线谱位置 −7。
- `OTTAVA_BASSA`（8vb）：书写音比实际音高一个八度，五线谱位置 +7。

括号区间为**左闭右开** `[startOnset, endOnset)`：`startOnset` 处的音符受影响，`endOnset` 处的音符**不**受影响。

视觉形态为**左开右闭**——左侧无竖线（以 "8va"/"8vb" 斜体文字开头），右侧以收口短线（钩）闭合；渲染时虚线终止于 `endOnset` 之前最后一个音符的位置。

YAML 写法（见 `test-scores/21_clef_time_8va.mscore.yaml`）：
```yaml
attachments:
  - type: octaveShiftStart
    id: "osa-1"
    onset: { components: [...] }
    shiftType: OTTAVA
    endEventId: "ose-1"
    placement: ABOVE
  - type: octaveShiftEnd
    id: "ose-1"
    onset: { components: [...] }
    placement: ABOVE
```

渲染细节见 [../renderer/dynamics.md](../renderer/dynamics.md)。

### Controller Track

为让力度记号与播放联动，新增 `StorageControllerTrack`（顶层 `StorageScore.controllerTracks`）：

```kotlin
@SerialName("controller")
data class StorageControllerTrack(
    override val id: TrackId, override val name: String,
    val scope: ControllerScope,                 // staffIds 空 = 全谱；voiceNumbers 空 = 全声部
    val events: List<StorageControllerEvent>,   // SET_DYNAMIC / RAMP_START / RAMP_END
)
```

第一版仅记录符号意图（`level` / `hairpin` 可空的"空白事件"），不合成实际播放效果。
Staff 附着符号通过 `controllerEventId` / `controllerStartId`+`controllerEndId` 引用对应事件。

> 完整示例：`test-scores/19_dynamics.mscore.yaml`。渲染细节见 [../renderer/dynamics.md](../renderer/dynamics.md)。

## 3.7 谱号变换（Clef Changes）

谱号变换存储在 `StorageStaffTrack.clefChanges`，与最初的谱号（`clef` 字段）分开记录：

```kotlin
@Serializable
data class StorageClefChange(
    val onset: TimeCode,
    val clef: Clef      // TREBLE / BASS / ALTO / TENOR / PERCUSSION
)

data class StorageStaffTrack(
    val clef: Clef,                                      // 初始谱号
    val clefChanges: List<StorageClefChange> = emptyList(), // 中途变化，按 onset 排序
    ...
)
```

**生效规则**：`ComputeEngine.effectiveClef(onset, staff)` 返回该 onset 处有效的谱号——取所有 `onset ≤ noteOnset` 中最大的那个，无则回退到 `staff.clef`。

**渲染**：`ComputeEngine.computeClefs()` 为每个谱号变换生成 `ComputedClef`（`isInitial = false`），时间码保留 `StorageClefChange.onset`。Renderer 按该时间排版；若谱号变换不在小节线时间，使用较小的谱号变换尺寸。

YAML 写法（见 `test-scores/21_clef_time_8va.mscore.yaml`）：
```yaml
staffTracks:
  "st-main":
    clef: TREBLE
    clefChanges:
      - onset: { components: [...] }
        clef: BASS
      - onset: { components: [...] }
        clef: TREBLE
```

## 3.8 谱表隐藏（Hidden Ranges）

谱表可在若干小节范围内隐藏，存储在 `StorageStaffTrack.hiddenRanges`（与 `clefChanges` 同为"每谱表列表"模式），隐藏与分行分页无关，区间可跨行：

```kotlin
@Serializable
data class MeasureRange(val from: Int, val to: Int)   // 闭区间（含端点）

data class StorageStaffTrack(
    val hiddenRanges: List<MeasureRange> = emptyList(), // 规范化：排序、合并相邻/重叠
    ...
)
```

区间维护经 `MeasureRanges`（`normalize` / `add` / `subtract` / `coversAll`），编辑经 `StaffVisibilityEditEngine`（`hide` / `show`，有音符时拒绝隐藏）。

**渲染分两种形态**（Renderer/Layout 读 `RuntimeStaffTrack.hiddenRanges`，Computed 层无新字段）：
- **整行某谱表全程隐藏** → 该谱表从该行 `SystemLayout.staffLayouts` 折叠移除，上下谱表靠拢；在留出的间隙画一条水平虚线（`HiddenStaffMarker` post-layout marker，连续隐藏谱表合并为一条），见 `docs/renderer/layout.md`。
- **行内部分隐藏** → 谱表仍排版，隐藏小节由桌面视图层灰显并禁止音符录入。
- 极端情形（整行所有谱表都隐藏）不折叠，退化为整行灰显。

MusicXML 互操作见 `docs/data_model/musicxml.md`（`<staff-details print-object>`）。

## 3.5 谱表头（Staff Header）

谱表左侧的括号、乐器名和小节线连接规则由以下字段控制：

### StorageStaffTrack 新增字段

| 字段 | 含义 |
|------|------|
| `staffLabel: String?` | 该谱表的独立标签（如 `"S."` / `"A."`），显示在括号内侧 |
| `staffLabelAbbreviation: String?` | 后续系统使用的缩写标签 |

### StoragePartTrack 新增字段

| 字段 | 默认值 | 含义 |
|------|--------|------|
| `innerBarlineConnect: Boolean` | `true` | 同声部多行谱表之间是否共用小节线 |
| `partBracket: BracketStyle?` | `null` | 该声部自带的括号（`null` = Runtime 自动推断：多行谱表→BRACE，单行→NONE） |

### BracketStyle

```kotlin
enum class BracketStyle { NONE, SQUARE, BRACE, SUB_BRACKET }
```

| 值 | 外观 |
|----|------|
| `NONE` | 无括号 |
| `SQUARE` | 粗方括号 + 上下衬线（管弦乐族括号） |
| `BRACE` | 花括号（钢琴大谱表） |
| `SUB_BRACKET` | 细方括号，用于组内细分（如弦乐组内再分第一/第二提琴） |

### StorageStaffGroup

```kotlin
@Serializable
data class StorageStaffGroup(
    val id: StaffGroupId,
    val bracket: BracketStyle = BracketStyle.NONE,
    val label: String? = null,          // 组标签（"Strings"、"Choir"）
    val abbreviation: String? = null,
    val barlineConnect: Boolean = false, // true = 小节线贯通整组
    val members: List<StaffGroupMember> = emptyList()
)

sealed interface StaffGroupMember {
    @SerialName("part") data class Part(val partId: TrackId) : StaffGroupMember
    @SerialName("group") data class Group(val group: StorageStaffGroup) : StaffGroupMember
}
```

- `members` 支持无限嵌套（如弦乐组内再嵌小提琴子组）
- 不在任何组内的声部独立渲染，无括号、无组标签
- `StorageScore.staffGroups: List<StorageStaffGroup>` 是顶层组列表
- `StorageScore.create(..., groupTemplates)` 接受连续、可嵌套的范围并构造上述树；部分相交范围会形成交叉括号，因此直接拒绝
- 一个 `InstrumentTemplate` 含多个谱表时，创建器自动添加 `BRACE` 子组并连接内部小节线；显式同范围模板可覆盖该默认组

**YAML 写法**：

```yaml
staffGroups:
  - id: "sg-choir"
    bracket: SQUARE
    label: "Choir"
    barlineConnect: true
    members:
      - type: part          # StaffGroupMember.Part 的 kaml 多态判别符
        partId: "part-choir"
      - type: group         # StaffGroupMember.Group
        group:
          id: "sg-winds"
          bracket: SUB_BRACKET
          members:
            - type: part
              partId: "part-flute"
```

> 完整示例：`test-scores/17_staff_groups.mscore.yaml`

## 4. 延音线表示

`TieInfo` 显式描述每个音高的延音目标：

```kotlin
@Serializable
data class TieInfo(
    val pitchIndex: Int,                  // 当前事件第几个音高
    val targetEventId: EventId? = null,   // null = let-ring
)
```

支持的语义：
- 普通延音：`targetEventId` 指向下一个相同音高的事件
- 部分和弦延音：仅记录需要保持的 `pitchIndex`
- Let-ring：`targetEventId == null`

避免了 "tie-start / tie-stop" 双向标记带来的歧义。

Tie / slur 的持久排版字段、所有权和失效规则见
[score-geometry.md](score-geometry.md)。

## 4.1 连音组 (Tuplet)

连音组信息只挂在 `VoiceTrack` 上，仅由该组**第一个** `StorageVoiceEvent` 通过
`tupletSpan` 字段承载。`PitchTrack` 不参与连音表达。

```kotlin
@Serializable
enum class TupletDisplayStyle {
    NONE,                  // 不绘制任何提示，仅按音符 Duration 缩放时长
    NUMBER_ONLY,           // 只绘制数字
    BRACKET_AND_NUMBER,    // 折线方括号 + 数字
    SLUR_AND_NUMBER,       // 弧线 + 数字
}

@Serializable
data class TupletSpan(
    val endTimeCode: TimeCode,                 // 连音组结束（不含）
    val count: Int,                            // N 连音
    val beatUnit: DurationBase,                // 编辑提示，渲染不消费
    val displayStyle: TupletDisplayStyle = TupletDisplayStyle.BRACKET_AND_NUMBER,
    val smallNotes: Boolean = false,            // 肖邦式占拍小音符区域
)
```

Tuplet 的自动排版结果与用户侧别覆盖保存在 `StorageScore.geometry.tuplets`，以连音组首事件
`EventId` 为稳定键。`TupletGeometry(above, directionLocked)` 中，自动捕获的条目
`directionLocked=false`，后续仍会跟随组内符杆方向重新排版；属性面板显式选择“上方/下方”后写入
`directionLocked=true`，跨保存、撤销/重做和重新排版保持该侧别。

语义：
- `endTimeCode` **不含**该 TimeCode 自身（半开区间）。例：4/4 拍第 1 拍的三连音，`endTimeCode = "0:1/4"`（即第 2 拍开始）。
- `count > 1` 由构造器约束。
- `beatUnit` 是编辑提示：表示连音拍面向用户显示的基础时值，渲染层只消费 `ComputedTupletInfo`。
- 显示样式由 `displayStyle` 直接决定，渲染层不做自动降级；是否在 beam 上方再画 bracket 由作者按谱例自行选择。
- 默认侧别取连音组内第一个实际存在的符杆（符杆向上 → 上方，符杆向下 → 下方），因此组首为休止符时不会误回退到符头侧；全休止符组才回退到上方。
- 渲染层不会因连音跨越小节线做特殊处理；编辑器新建与粘贴连音时则拒绝跨小节线，以维持当前交互模型的单小节连音约束。
- `smallNotes=true` 表示占拍的小音符输入区域：仍占用 `[start,endTimeCode)`，但隐藏括号/数字，
  成员由 `RenderingProps.scale` 缩小；未输入部分保留为 `RenderingProps.hidden=true` 的休止
  事件，提供计量覆盖与编辑吸附。区域起点的隐藏休止符由 Renderer 以专用颜色绘制成输入
  标记，其余内部休止片段不绘制。它不同于不占小节计量时间的 `TimeCode.grace` 装饰音。

## 4.2 装饰音 (Grace Notes)

装饰音通过 `TimeCode` 的**第三个分量**编码位置——同一 `(measure, beat)` 下用**负分数**沿 `[-1, 0)` 区间均匀排列：

| 装饰音个数 | 第三分量取值（按出现顺序） |
|----------|--------------------------|
| 1 | `-1/1` |
| 2 | `-1/1`, `-1/2` |
| 3 | `-1/1`, `-2/3`, `-1/3` |
| N | `-1`, `-(N-1)/N`, …, `-1/N` |

`StoragePitchEvent` 与 `StorageVoiceEvent` 字段结构与普通音符相同。`PitchTrack` 用 `TimeCode` 的三个分量自然排序，`(measure, beat, -k/N)` 始终排在同一 `(measure, beat)` 的本音之前。

每个**装饰音组的第一个** `StorageVoiceEvent` 额外携带 `graceInfo`：

```kotlin
@Serializable
data class GraceNoteInfo(
    val totalDuration: Duration,        // 该装饰音组占用的总音乐时值
    val stealFrom: GraceTimeSource,     // PREVIOUS = 从前一音借时；PRINCIPAL = 从本音借时
)

@Serializable
enum class GraceTimeSource { PREVIOUS, PRINCIPAL }
```

- `totalDuration` 是音乐时值（`Duration`），由播放层在转换为 MIDI 时按拍乘以拍长得到 tick。
- 同一组其余装饰音的 `graceInfo == null`——元数据只在首个事件上记录。
- 允许"孤悬装饰音"——同一 `(measure, beat)` 内可以没有 `grace == null` 的本音；播放与渲染都不做特殊处理。

参考：[grace-notes.md](../renderer/grace-notes.md)。

## 4.3 连奏线 (Slurs)

slur 现为**一等存储事件** `StorageSlurEvent(id, startEventId, endEventId)`，挂在 `StorageVoiceTrack.slurs`。
稳定 `id` 是持久化几何（§1.3，键 = `id`）与未来手动编辑的句柄。

```kotlin
@Serializable
data class StorageSlurEvent(val id: EventId, val startEventId: EventId, val endEventId: EventId)

data class StorageVoiceTrack(..., val slurs: List<StorageSlurEvent> = emptyList())
```

**兼容 legacy 计数**：`StorageVoiceEvent.slurStarts/slurEnds` 仍受支持。`RuntimeScore.fromStorage` 规则——
`voiceTrack.slurs` 非空则用显式事件；否则按 LIFO 栈从计数合成等价 slur 并生成**确定性派生 id**
（`SlurResolver.computeFromCounts`）。因此所有 counts 写法的 test-scores 继续可用，几何也能稳定回连。
`ComputedSlur.slurId` 携带该 id；显式路径的 `nestingLevel` 按同轨**包含关系**计算，counts 路径维持 LIFO 深度。

> 配对算法与方向 / 嵌套 / 避让见 [../renderer/ties-and-slurs.md §7](../renderer/ties-and-slurs.md)。

## 5. 序列化 (kaml YAML)

- `StorageScore` 使用 `kaml` 序列化为 YAML
- `TimeCode` 自定义序列化器同时支持紧凑（`"0:1/4"`）与展开（`[0, 1/4]`）形式
- 所有 `StorageEvent` 经由 `@Serializable sealed` 多态分派

文件格式参见 [score-format.md](score-format.md)。

## 6. 设计取舍

**为什么用 ID 引用而不是对象引用？**

序列化层必须是无环的；YAML 无法表达对象图共享。Storage 用 ID 引用打平结构，Runtime 层负责把 ID 解析为对象引用以供查询。

**为什么 PitchEvent 与 VoiceEvent 拆开？**

同一组音高可能在不同声部以不同方式被使用（多声部分配同一音符）。拆分允许声部独立调整渲染信息（符干方向、连音线），同时复用底层音高数据。当前实现是 1:1 引用，将来可放宽为 N:1。

按此原则，**演奏记号（articulation）属于"如何演奏"的音乐信息，记在 `StoragePitchEvent.articulations`**；而"画在符头侧还是符尾侧"是纯排版选择，记在 `RenderingProps.articulationPlacement`（`AUTO`/`NOTEHEAD`/`STEM`）。渲染细节见 [../renderer/articulations.md](../renderer/articulations.md)。

**插件事件如何序列化？**

`StoragePluginEvent` 是抽象类，子类需 `@Serializable` 标注且通过模块注册多态序列化器。详见 [../plugin/custom-track.md](../plugin/custom-track.md)。
