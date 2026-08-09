# 细粒度拾取与交互样式

> 接口路径：`api/.../interaction/`
> 实现路径：`renderer/.../interaction/`

声明式系统：业务方申请独立的 `StyleTrack(priority)`，按 `EventSection` 设置覆盖样式；管理器合并所有 Track 得到 `StyleSnapshot`，通过 `StateFlow` 推送给渲染层。

## 1. 解决的问题

| 问题 | 方案 |
|------|------|
| 区分音符的 notehead / stem / flag / beam | `EventSection` + 数值 `EventSectionId` |
| 多个业务源同时改样式且需要堆叠 | `StyleTrack`（按 priority 排序） |
| 渲染层和插件解耦 | 接口在 `:api`；实现在 `:renderer` |

## 2. EventSection（拾取类型）

`sealed interface EventSection` 的运行时身份是 `val id: EventSectionId`（`Long` 包装类型）。Storage / Runtime
的 `EventId`、`TrackId` 仍保持字符串；构造 Section 时直接按来源 ID、Section 类型和局部索引计算一次数值 ID，
之后索引、样式和绘制缓存查询只传数值 ID。`val sectionId: String` 仅供属性面板、日志和 snapshot 展示：

| 类型 | sectionId 格式 |
|------|---------------|
| `VoiceNoteSection` | `"{eventId}:notehead:{pitchIndex}"` |
| `VoiceStemSection` | `"{eventId}:stem"` |
| `VoiceFlagSection` | `"{eventId}:flag"` |
| `VoiceBeamSection` | `"beam:{groupId}"` |
| `VoiceEventSection` | `"{eventId}:event"` |
| `BarlineSection` | `"barline:m{measure}:{time}"` |
| `ClefSection` | `"clef:{staffTrackId}:{time}"` |
| `KeySignatureSection` | `"keysig:{staffTrackId}:{time}"` |
| `TimeSignatureSection` | `"timesig:{staffTrackId}:{time}"` |

> 一个 `RenderElement` 可同时属于多个 Section（例如符头同时是 `VoiceEventSection` 与 `VoiceNoteSection`）。

`EventSectionFactory` 提供从 `ComputedVoiceEvent` / `RuntimePitchEvent` 构造 Section 的工具（详见源码注释）。

## 3. SectionIndex

```
EventSectionId  ↔  RenderElementId  ↔  EventSection
```

`RenderElementCollector` 先从各 `ElementRenderOutput.sectionRegistrations` 收集映射，`RenderResultAssembler` 再用 `SectionIndexBuilder` 建立索引：

```kotlin
sectionBuilder.register(VoiceEventSection(computedEvent), elemId)
for (i in computedEvent.pitchData.indices) {
    sectionBuilder.register(VoiceNoteSection(computedEvent, i), elemId)
}
```

`RenderElement.id` 使用类型安全的 `RenderElementId(Long)`，编码 `(systemIndex, generation, localOrdinal)`；字符串形式只由 `debugString()` 在日志或快照边界生成。`RenderElementCollector` 在元素进入索引前按 system 分配稠密 local ordinal，Storage / Runtime 的 `EventId`、`TrackId` 不受影响。

`build()` 后存入 `RenderResult.sectionIndex`。索引由 `systemBuckets[]` 与少量无 system 元素的 `globalBucket` 组成；每个 system 桶内按 generation 保存不可变稠密反向数组。正向查询使用 primitive long 开放寻址表把 `EventSectionId` 映射到桶内 ordinal，不保留 `Map<EventSection, Int>`。分页 splice 通过 `replaceSystems()` 整桶替换 affected systems，未受影响桶按引用复用；连续模式的局部窗口则只复制触及的桶，并允许同 system 的多 generation 共存。旧 `RenderResult` 始终持有旧桶，无原地清理、墓碑或后台回收线程。查询：

```kotlin
sectionIndex.sectionIdsFor(elementId)           // 元素 → List<EventSectionId>
sectionIndex.elementsForSectionId(sectionId)    // EventSectionId → 元素集合
```

绘制热路径 `sectionIdsFor(elementId)` 解码 system / generation / local ordinal，以整数 generation 路由后直接访问稠密数组；连续模式仍保留 partial-system splice，不因本次 ID 迁移扩大重建窗口。正向 section 查询通过 primitive long 表定位桶内 ordinal，再按需归并各 system 桶。

## 4. StyleOverride / StyleTrack

```kotlin
data class StyleOverride(
    val fillColor:       RenderColor? = null,
    val backgroundColor: RenderColor? = null,
)

interface StyleTrack {
    val priority: Int
    fun setStyle(section: EventSection, override: StyleOverride)
    fun removeStyle(section: EventSection)
    fun clear()
    fun submit()       // 触发管理器重建 snapshot
}

interface StyleRegistry {
    fun createTrack(priority: Int): StyleTrack
    fun removeTrack(track: StyleTrack)
}
```

`fillColor` 替换 `DrawGlyph / DrawLine / DrawText / DrawBezier / DrawRect / DrawPath / DrawEllipse` 的颜色；`backgroundColor` 在元素 `hitBox` 区域绘制底色矩形。

## 5. StyleOverrideManager 与 Snapshot

`renderer.getStyleOverrideManager()` 实现 `StyleRegistry`：

- 保存所有 `StyleTrack`
- 任意 Track `submit()` 后，按 `priority` 升序合并所有覆盖，分配全局 `order`
- 输出不可变 `StyleSnapshot: Map<EventSectionId, OrderedOverride>`，通过 `MutableStateFlow` 发布

渲染时（`ComposeScoreRenderer.renderElement`）：

```kotlin
val ids = sectionIndex.sectionIdsFor(element.id)
val override = snapshot.getOverride(ids)        // 取 order 最大者
if (override?.backgroundColor != null) drawBackgroundRect(element.hitBox, ...)
if (override?.fillColor       != null) renderCommandWithOverride(element, fillColor)
```

## 6. 典型用法：选中高亮（Compose）

```kotlin
val selectionTrack = remember(selection, renderEngine) {
    if (selection != null && renderEngine != null) {
        val mgr = renderEngine.getStyleOverrideManager()
        val track = mgr.createTrack(priority = Int.MAX_VALUE)
        track.setStyle(selection, StyleOverride(fillColor = RenderColor.rgb(37, 99, 235)))
        track.submit()
        track
    } else null
}

DisposableEffect(selectionTrack, renderEngine) {
    onDispose { selectionTrack?.let { renderEngine?.getStyleOverrideManager()?.removeTrack(it) } }
}

val snapshot by renderEngine.getStyleOverrideManager().snapshotFlow.collectAsState()
composeRenderer.render(this, renderResult, snapshot, sectionIndex)
```

## 7. 多源叠加示例

```kotlin
val analysis = manager.createTrack(priority = 0)     // 分析插件，低优先级
val hover    = manager.createTrack(priority = 100)   // 鼠标悬停，高优先级

analysis.setStyle(section, StyleOverride(fillColor = orange));   analysis.submit()
hover   .setStyle(section, StyleOverride(fillColor = blue));     hover.submit()
// 当前显示蓝色

hover.clear(); hover.submit()
// 自动回落到橙色——分析插件不受影响
```

## 8. 插件着色：NoteStyleProvider

插件不直接操作 `StyleTrack`，而是实现 `NoteStyleProvider` SPI（`api/.../plugin/NoteStyleProvider.kt`）：

```kotlin
interface NoteStyleProvider {
    val pluginTrackTypes: Set<String>
    fun computeStyles(computedScore: ComputedScore): Map<Pair<EventId, Int>, StyleOverride>
}
```

`RenderEngine` 在每次 `render()` 后自动调用 `applyNoteStyleProviders()`，把结果写入 `priority = 1` 的内部 `StyleTrack`。

**轻量刷新**：`RenderEngine.reapplyNoteStyles()` 用缓存的 `lastComputedScore` 重跑所有 Provider，不触发重新布局。面板开关切换后通过此路径刷新颜色（详见 [../plugin/plugin-framework.md](../plugin/plugin-framework.md) §3）。

## 9. 设计要点

- **声明式**：业务方只声明"我要这些 section 是什么颜色"，无需关心其他 Track
- **不可变 snapshot**：渲染层接收的是只读快照，可以在多线程间安全共享
- **`:api` ↔ `:renderer` 解耦**：插件只需依赖 `:api` 中的 `StyleRegistry / StyleTrack / EventSection / RenderColor`
