# 自定义插件轨道

> 路径：`api/src/commonMain/kotlin/com/mecon/api/`
>
> 参考实现：`plugins/chord-analysis/core/.../`

## 1. 三层事件定义

### 1.1 Storage 层

```kotlin
@Serializable
@SerialName("mecon.chord_analysis.chord")          // 多态判别
data class StorageChordEvent(
    override val id: EventId,
    override val onset: TimeCode,
    val root: Int,
    val quality: ChordQuality,
    val bass: Int? = null,
) : StoragePluginEvent() {
    companion object {
        const val TRACK_TYPE: String = "mecon.chord_analysis"
    }
}
```

`@SerialName` 用作 kaml / Json 中的 `type:` 鉴别字段；必须经由 `PluginInstallContext.registerEventSerializer(...)` 注册到全局多态模块。

### 1.2 Runtime 层

```kotlin
class RuntimeChordEvent(
    override val storageEvent: StorageChordEvent,
) : RuntimePluginEvent<StorageChordEvent> {
    val chord: Chord by lazy { /* 用 :theory 解析 */ }
}
```

### 1.3 Computed 层（按需）

```kotlin
class ComputedChordEvent(
    override val runtimeEvent: RuntimeChordEvent,
) : ComputedPluginEvent<StorageChordEvent> {
    val symbol: String by lazy { /* "C" / "Dm" / "G7" / "F/A" */ }
}
```

简单轨道可跳过 Computed 层，直接在 Provider 中读 `RuntimePluginEvent`。

## 2. 注册多态序列化

```kotlin
class ChordAnalysisPlugin : MeconPlugin {
    override val id = "mecon.chord_analysis"
    override fun install(ctx: PluginInstallContext) {
        ctx.registerEventSerializer(StorageChordEvent::class, StorageChordEvent.serializer())
        ctx.registerAnnotationStaffProvider(ChordAnnotationProvider)
        ctx.registerNoteStyleProvider(ChordToneStyleProvider)
    }
}
```

桌面侧 `ChordAnalysisDesktopPlugin extends ChordAnalysisPlugin` 在 `super.install(ctx)` 之后再注册面板与 i18n 包。

## 3. 写入轨道状态

```kotlin
GlobalScoreState.activeManager.updatePluginTrackState(TrackId("chord-analysis")) { old ->
    old.withUpdatedEvents(newEvents)
}
```

`updatePluginTrackState` 不进入撤销栈（用户的"撤销"不该回滚自动分析结果）。详见 [../state-management.md](../state-management.md)。

## 4. 框架工具：pluginEventsOf / pluginTrackOf

> 路径：`api/src/commonMain/kotlin/com/mecon/api/computed/ComputedScore.kt`
> 路径（RuntimeScore）：`api/src/commonMain/kotlin/com/mecon/api/runtime/RuntimeScore.kt`

### pluginEventsOf（ComputedScore）

从 `ComputedScore` 中提取指定 track 类型的全量 storage 事件列表：

```kotlin
inline fun <reified T : StoragePluginEvent> ComputedScore.pluginEventsOf(
    trackType: String
): List<T>
```

封装了"过滤 track → flatMap events → cast storageEvent"的重复模式。所有需要遍历全部插件事件的 Provider（如注释布局、符头着色）均应使用此扩展：

```kotlin
// ✅ 正确
val chords = ctx.computedScore.pluginEventsOf<StorageChordEvent>(StorageChordEvent.TRACK_TYPE)

// ❌ 不推荐（重复样板代码）
val chords = computedScore.pluginTracks.values
    .filter { it.type == StorageChordEvent.TRACK_TYPE }
    .flatMap { it.events.toList() }
    .mapNotNull { it.runtimeEvent.storageEvent as? StorageChordEvent }
```

### pluginTrackOf（RuntimeScore / ComputedScore）

按 track 类型取出单条轨道，并携带完整类型参数（`RuntimePluginTrack<T>` / `ComputedPluginTrack<T>`）：

```kotlin
fun <T : StoragePluginEvent> RuntimeScore.pluginTrackOf(trackType: String): RuntimePluginTrack<T>?
fun <T : StoragePluginEvent> ComputedScore.pluginTrackOf(trackType: String): ComputedPluginTrack<T>?
```

用于需要直接在轨道上做查询的场景（相邻事件、按 ID 定位、按时间定位最近事件），避免手动 `.filter { it.type == ... }.firstOrNull()`：

```kotlin
// ✅ 正确：类型化轨道 + B+ 树查询，O(log n)
val track = runtimeScore.pluginTrackOf<StorageChordEvent>(StorageChordEvent.TRACK_TYPE)
val prev: StorageChordEvent? = track?.prevEvent(onset)?.storageEvent
val next: StorageChordEvent? = track?.nextEvent(onset)?.storageEvent

// ❌ 不推荐（全量展开后排序查索引）
val allChords = runtimeScore.pluginTracks.values
    .filter { it.type == StorageChordEvent.TRACK_TYPE }
    .flatMap { it.events.toList() }
    .sortedBy { it.onset }
```

> **选型原则**：需要**全量列表**（Provider 批量生成元素）→ 用 `pluginEventsOf`；需要**单条轨道**查询（点查、相邻）→ 用 `pluginTrackOf`。

## 5. 注释谱表（首选渲染路径）

`AnnotationStaffProvider` 把"我在第几个时间、什么相对 Y、显示什么文字"声明出来，由 `AnnotationStaffLayoutComputer` 统一排版、`AnnotationStaffRenderer` 统一绘制：

```kotlin
object ChordAnnotationProvider : AnnotationStaffProvider {
    override val staffId = PluginStaffId(StorageChordEvent.TRACK_TYPE)
    override val anchor = StaffAnchor.BelowAllStaves
    override val pluginTrackTypes = setOf(StorageChordEvent.TRACK_TYPE)

    override fun layout(ctx: AnnotationLayoutContext): List<AnnotationElement> =
        ctx.computedScore
            .pluginEventsOf<StorageChordEvent>(StorageChordEvent.TRACK_TYPE)
            .map { storageChord ->
                AnnotationElement.Text(
                    time = storageChord.onset,
                    relativeY = 0f,
                    sourceEventId = storageChord.id,
                    text = ComputedChordEvent.fromRuntime(RuntimeChordEvent.fromStorage(storageChord)).symbol,
                    alignment = AnnotationAlignment.LEFT
                )
            }
}
```

**插件不接触** `CoordinateTransformer` / `AbsolutePoint` / `RenderElement`，由渲染层在 `UnifiedLayoutResult.annotationElementLayouts` 中落实绝对坐标。

## 6. 符头着色（NoteStyleProvider）

> 路径：`api/src/commonMain/kotlin/com/mecon/api/plugin/NoteStyleProvider.kt`

```kotlin
interface NoteStyleProvider {
    val pluginTrackTypes: Set<String>
    fun computeStyles(computedScore: ComputedScore): Map<Pair<EventId, Int>, StyleOverride>
}
```

返回值键为 `(EventId, pitchIndex)`，与 `VoiceNoteSection` 一一对应，`RenderEngine` 在渲染后自动转换为 `sectionId` 并写入低优先级 Track。

**和弦内外音着色示例**（`ChordToneStyleProvider`）：

```kotlin
object ChordToneStyleProvider : NoteStyleProvider {
    override val pluginTrackTypes = setOf(StorageChordEvent.TRACK_TYPE)
    var isEnabled: Boolean = true           // 面板开关

    override fun computeStyles(computedScore: ComputedScore): Map<Pair<EventId, Int>, StyleOverride> {
        if (!isEnabled) return emptyMap()
        return ChordToneAnalysis.compute(computedScore).mapValues { (_, isChordTone) ->
            StyleOverride(fillColor = if (isChordTone) green else orange)
        }
    }
}
```

`ChordToneAnalysis.compute()` 使用 `CalcBuilder.alignLe` 为每个音符找到 onset ≤ 该音符的最近和弦，判断每个音高是否属于该和弦。

**运行时开关刷新**：面板切换 `isEnabled` 后调用 `ctx.onRequestNoteStyleRecompute?.invoke()`，宿主触发 `RenderEngine.reapplyNoteStyles()`，对已有 `ComputedScore` 重跑所有 Provider，不需要重新布局。

## 7. CalcBuilder：跨轨对齐

多轨派生场景使用 `CalcBuilder`（`api/.../computed/CalcBuilder.kt`）：

```kotlin
// 双向对齐
val (pitchAligned, chordAligned) = CalcBuilder.alignBilateral(pitches, chords)

// 单向查找：每个音符配 onset ≤ 该音符的最新和弦
val pairs = CalcBuilder.alignLe(referenceTrack = pitches, alignTrack = chords)
```

`alignLe` 的典型用法：给定 voices 与 chords 两条轨道，为每个音符找到"当前生效的和弦"，再判断该音符是否是和弦内音。

### 增量对齐

需要随编辑反复对齐时，用有状态对齐器代替每次全量 `align*`：

```kotlin
// 初次：全量构建
var aligner = ReferenceAligner.build(voiceList, chordList)   // alignLe 语义；BilateralAligner.build 对应 alignBilateral

// 编辑后：只重算受影响行（基于 TimeIndexedList.changedSpan → BPlusTree.diff 定位变化区间）
aligner = aligner.update(newVoiceList, newChordList)
aligner.aligned                  // 新的对齐结果，窗口外行按引用复用
aligner.lastRecomputedRowCount   // 本次重算了多少行（效果度量）
```

`update(a,b).aligned` 与全量 `CalcBuilder.align*(a,b)` 逐值相等（黄金法则，模糊测试守护）。voice 侧应直接对齐 `computedScore.computedEvents.asTimeIndexedList()`（持久化 B+ 树），使相邻增量 compute 的输入共享结构、diff 为 O(变化量·log N)。`ChordToneAnalysis` 即按此缓存 `ReferenceAligner`，编辑后只重算受影响音符的和弦内外音判定。`offset != 0` 时 align 轨改动会平移所有后续索引 → 退化整轨重排。

## 8. 通用渲染叠加（逃生口）

`PluginRenderComponent` 接口仍保留，用于无法套进"注释谱表"或"符头着色"模型的场景（自定义形状、跨多个时间槽连接等）。当前无内置实现，从前的 `ChordTextRenderComponent` 已删除——它直接拼装 `AbsolutePoint`，正是 `AnnotationStaffProvider` 要替代的样板。

## 9. 样式覆盖（直接操作）

如需绕过 NoteStyleProvider 直接操作样式（例如交互高亮）：

```kotlin
val track = renderEngine.getStyleOverrideManager().createTrack(priority = 0)
val section = VoiceNoteSection(computedVoiceEvent, pitchIndex)
track.setStyle(section, StyleOverride(fillColor = RenderColor.rgb(255, 165, 0)))
track.submit()
```

详见 [../renderer/interaction.md](../renderer/interaction.md)。
