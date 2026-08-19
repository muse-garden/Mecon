# 插件框架（注册与生命周期）

> **状态**：✅ 注册与一次性安装路径已实现；卸载 / 热重载暂未支持。

## 1. 核心接口

> 路径：`api/src/commonMain/kotlin/com/mecon/api/plugin/`

```kotlin
interface MeconPlugin {
    val id: String                       // 唯一标识，installAll 中按此去重
    fun install(ctx: PluginInstallContext)
}

interface PluginInstallContext {
    fun <T : StoragePluginEvent> registerEventSerializer(
        kClass: KClass<T>,
        serializer: KSerializer<T>,
    )
    fun registerAnnotationStaffProvider(provider: AnnotationStaffProvider)
    fun registerNoteStyleProvider(provider: NoteStyleProvider)
    fun registerNoteSelectionLabelProvider(provider: NoteSelectionLabelProvider)
    fun registerPanelDescriptor(descriptor: Any) // 实际类型 PluginPanelDescriptor
}
```

`PluginRegistry` 是定义期单例：

```kotlin
object PluginRegistry {
    fun installAll(plugins: List<MeconPlugin>)
    fun annotationStaffProviders(): List<AnnotationStaffProvider>
    fun noteStyleProviders(): List<NoteStyleProvider>
    fun noteSelectionLabelProviders(): List<NoteSelectionLabelProvider>
    fun panelDescriptors(): List<Any>
    fun buildSerializersModule(): SerializersModule
    fun resetForTesting()
}
```

## 2. AnnotationStaffProvider

```kotlin
interface AnnotationStaffProvider {
    val staffId: PluginStaffId
    val anchor: StaffAnchor             // BelowAllStaves / AboveAllStaves / (Above|Below)Staff
    val pluginTrackTypes: Set<String>   // 消费哪些 StoragePluginTrack.type
    fun layout(ctx: AnnotationLayoutContext): List<AnnotationElement>
}
```

- `AnnotationLayoutContext` 暴露 `computedScore` / `notationStaffLayouts` / `xFor(time)`，不暴露 `CoordinateTransformer` 或 `RenderElement`，插件无法触达渲染私有类型
- `AnnotationElement.Text` 是 v1 唯一实现的元素；`Glyph` 已预留（SMuFL 后续接入）
- `AnnotationElement.interactive: Boolean`（默认 `true`）：`false` 时渲染器生成零尺寸 hitBox，元素不参与点击命中
- `AboveAllStaves` / `BelowAllStaves` 已按系统实现并参与连续、分页和增量纵向排版；
  `AboveStaff` / `BelowStaff` 暂回退到 `BelowAllStaves` 并打一次性 debug 日志
- `sourceEventId` 已贯通到 `RenderElement.eventId`；`RenderedScoreView` 在命中 `RenderElementType.TEXT_ANNOTATION` 时通过 `onSelectAnnotationEvent` 回调上报选中的 `EventId`

## 3. NoteStyleProvider

> 路径：`api/src/commonMain/kotlin/com/mecon/api/plugin/NoteStyleProvider.kt`

```kotlin
interface NoteStyleProvider {
    val pluginTrackTypes: Set<String>
    fun computeStyles(computedScore: ComputedScore): Map<Pair<EventId, Int>, StyleOverride>
}
```

用于给单个符头（notehead）着色，键为 `(EventId, pitchIndex)`，与 `VoiceNoteSection` 一一对应。`RenderEngine` 在每次 `render()` 后自动调用所有已注册的 Provider，将结果写入 `StyleOverrideManager` 中 priority = 1 的专用 Track（低于选中高亮）。

**运行时开关**：Provider 可暴露 `var isEnabled: Boolean` 字段。面板切换后须调用 `ctx.onRequestNoteStyleRecompute?.invoke()`，宿主接收到信号后触发 `RenderEngine.reapplyNoteStyles()`，实现轻量刷新（无需全量重新布局）。

```kotlin
// 注册示例（plugin core install 方法内）
ctx.registerNoteStyleProvider(ChordToneStyleProvider)
```

## 4. NoteSelectionLabelProvider

> 路径：`api/src/commonMain/kotlin/com/mecon/api/plugin/NoteSelectionLabelProvider.kt`

该 SPI 将当前 `EventSection` 选择投影为 `(EventId, pitchIndex, text)`，供宿主绘制临时符头标签。
它与 `AnnotationStaffProvider` 的边界不同：标签不进入排版、分页、命中索引或存储；桌面宿主通过
`SectionIndex` 解析符头坐标。provider 在 UI 线程随选择调用，只能遍历当前选择和小型插件投影，
禁止扫描整谱。面板显示偏好变化通过 `onRequestSelectionOverlayRefresh` 只触发覆盖层重绘。

## 5. PluginPanel（桌面 UI）

> 路径：`apps/desktop-ui-kit/.../plugin/`

```kotlin
interface PluginPanel {
    val id: String
    val titleKey: String                // i18n 键
    val icon: ImageVector?
    val initialHeightDp: Int
    @Composable fun Content(ctx: PluginPanelContext)
}

data class PluginPanelContext(
    val score: Score,
    val selection: NoteId?,
    val eventSelection: Set<EventSection> = emptySet(),    // 当前乐谱元素选区
    val selectedAnnotationEventId: EventId? = null,        // 当前选中的注释元素 EventId
    val runtimeScore: RuntimeScore? = null,                // 完整运行时乐谱，用于插件轨道查询
    val targetTimeCode: TimeCode? = null,                  // 当前选中音符的 onset，用于新增事件
    val onRequestNoteStyleRecompute: (() -> Unit)? = null, // 触发符头着色轻量刷新
    val onAddPluginEvent: ((String, StoragePluginEvent) -> Unit)? = null,
    val onUpdatePluginEvent: ((String, EventId, StoragePluginEvent) -> Unit)? = null,
    val onDeletePluginEvent: ((String, EventId) -> Unit)? = null,
)
```

`RightPanel.kt` 遍历 `PluginRegistry.panelDescriptors()`，把每个面板作为 `ResizablePanelItem` 嵌入。`selectedAnnotationEventId` 由 `RenderedScoreView` 点击注释元素后通过 `App.kt` 状态向下传递，插件面板可据此查询 `runtimeScore.pluginTracks` 找到对应的 `StoragePluginEvent`。编辑回调（`onAddPluginEvent` / `onUpdatePluginEvent` / `onDeletePluginEvent`）由 `App.kt` 提供，每次调用触发 `ScoreStateManager.commitNewState()`，纳入撤销/重做历史。

**选中逻辑约定**：面板展示应区分"仅展示"与"可编辑"两种来源：

- `targetTimeCode`（音符选中）→ 定位相关事件仅用于显示，表单保持"新增"状态
- `selectedAnnotationEventId`（注释符号点击）→ 表单进入"编辑/删除"状态
- `eventSelection`（音符 / 音头多选）→ 插件可读取 `VoiceNoteSection` / `VoiceEventSection` 生成派生分析；例如和弦分析面板从多选音高识别和弦，并通过 `onAddPluginEvent` 写入最早选中音符处

## 6. 运行时轨道事件查询

`RuntimePluginTrack<T>` 在 `events: TimeIndexedList<RuntimePluginEvent<T>>` 之上提供高效查询方法，直接走底层 B+ 树，无需遍历全轨道：

```kotlin
// 按 EventId 精确查找
track.findEventById(id: EventId): RuntimePluginEvent<T>?

// 时序相邻查询（严格不等）
track.prevEvent(time: TimeCode): RuntimePluginEvent<T>?   // 严格 < time 的最后一个事件
track.nextEvent(time: TimeCode): RuntimePluginEvent<T>?   // 严格 > time 的第一个事件

// 当前或之前的最近事件（≤ time）
track.lastEventAtOrBefore(time: TimeCode): RuntimePluginEvent<T>?
```

`TimeIndexedList` 本身也暴露对应的低层方法（`lastBefore` / `firstAfter` / `lastAtOrBefore`），可在直接操作 `events` 时使用。

**轨道定位（`pluginTrackOf`）**：

```kotlin
// api/runtime/RuntimeScore.kt（顶层扩展）
fun <T : StoragePluginEvent> RuntimeScore.pluginTrackOf(trackType: String): RuntimePluginTrack<T>?

// api/computed/ComputedScore.kt（顶层扩展）
fun <T : StoragePluginEvent> ComputedScore.pluginTrackOf(trackType: String): ComputedPluginTrack<T>?
```

**用法示例（面板 / Canvas）**：

```kotlin
val track = runtimeScore.pluginTrackOf<StorageChordEvent>(StorageChordEvent.TRACK_TYPE)
    ?: return@remember null

// 按注释 EventId 定位（O(log n)）
val chord: StorageChordEvent? = track.findEventById(annotationId)?.storageEvent

// 按时间定位最近和弦（O(log n)）
val chord: StorageChordEvent? = track.lastEventAtOrBefore(onset)?.storageEvent

// 相邻和弦（Tonnetz 视觉过渡）
val prev: StorageChordEvent? = track.prevEvent(currentChord.onset)?.storageEvent
val next: StorageChordEvent? = track.nextEvent(currentChord.onset)?.storageEvent
```

> **原则**：插件 UI 组件不应调用 `.events.toList()` 或 `flatMap { it.events.toList() }`；始终通过 `pluginTrackOf` + 上述查询方法访问轨道，避免 O(n) 全量展开。

## 7. 多态事件序列化

```kotlin
// 插件 core 模块内
override fun install(ctx: PluginInstallContext) {
    ctx.registerEventSerializer(MyEvent::class, MyEvent.serializer())
}
```

`PluginRegistry.buildSerializersModule()` 将所有注册项组装为 `polymorphic(StoragePluginEvent::class) { ... }`，必须由 `ScoreSerializer.installSerializersModule(...)` 灌入 kaml / Json，否则乐谱保存时插件事件无法识别。Spike 验证：`core/.../serializer/ScorePluginRoundTripSpike.kt`。

## 8. 安装顺序

```
BuiltinStrings.install()              i18n 内置包
PluginRegistry.installAll([...])       每个 plugin.install(ctx)
ScoreSerializer.installSerializersModule(PluginRegistry.buildSerializersModule())
application { Window { App() } }
```

`bootstrapPlugins()` 是这条链的统一入口。

## 9. 已知限制 / TODO

- 卸载、热重载未实现
- 命令 / Shortcut 注册 SPI 缺失
- `ComputeStep` 注册尚未提取；当前由 `AnnotationStaffProvider.layout(...)` 内联触发计算
- `AboveStaff` / `BelowStaff` 两种单谱表锚定仍回退到 `BelowAllStaves`
- `TEXT_ANNOTATION` hitBox 与注释谱表间距使用 `AnnotationElementMeasurer` 的实际 bounds；水平排版按文字宽度加间隙预留，不再使用字符数估算
- 注释元素选中高亮为纯色叠加层（不走 `StyleOverrideManager`），未来可与音符选中统一
