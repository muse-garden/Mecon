# 和弦分析插件：当前架构与 TODO

> **状态**：✅ 插件输入、序列化、计算、两种谱面展示、符头着色、桌面面板和局部渲染已打通。
> 本文记录当前边界与验收入口；不再按提交逐条记录实现过程。复调分析见
> [polyphony-analysis.md](polyphony-analysis.md)。

## 1. 模块边界

| 模块 | 职责 |
|------|------|
| `:plugins:chord-analysis:core` | `StorageChordEvent`、Runtime/Computed 投影、`ChordCompute`、注释 provider、音符着色 provider |
| `:plugins:chord-analysis:desktop` | 和弦输入/检查面板、解析器、i18n、桌面插件注册 |
| `:theory` | `ChordSelectionCatalog`、共享和弦时间轴读法与调性区间语义 |
| `:api` plugin SPI | `MeconPlugin`、注册上下文、序列化模块、`AnnotationStaffProvider`、`NoteStyleProvider` |
| `:renderer` | annotation staff 布局/测量/绘制、`TEXT_ANNOTATION` 命中、样式覆盖 |
| `apps/desktop` | 插件 bootstrap、面板宿主、选择状态、提交和渲染刷新 |

插件 core 不依赖桌面 UI；`:api` 不引入 Compose。插件只能通过 SPI 输出数据和请求刷新，不能直接
拼接 `AbsolutePoint`、`RenderElement` 或绘制命令。

## 2. 数据流

```text
Chord panel input
    → StoragePluginEvent / ScoreSerializer
    → Runtime/Computed plugin track
    → ChordAnnotationProvider.layout(AnnotationLayoutContext)
    → AnnotationStaffLayoutComputer
    → AnnotationStaffRenderer / TEXT_ANNOTATION
```

`AnnotationLayoutContext` 只暴露 `ComputedScore`、notation staff layout 和 `xFor(TimeCode)`。
文字的尺寸由 renderer 测量，宽度参与比例排版；不要在插件里按字符数估算宽度。

右栏“在谱面上使用和声时间轴”在原有的下方点状 `ChordAnnotationProvider` 与上方的
`ChordTimelineAnnotationProvider` 之间切换。时间轴从相邻 `StorageChordEvent` 推导半开区间，
末项延伸到谱尾；调号区间和 `StorageTonalRegionEvent` 一并交给 `HarmonyTonalTimeline`，和弦的
多调性读法由 `HarmonyTimelineReadingProjector` 通过自由练习同源的 `ChordSelectionCatalog`
投影，不在插件内维护第二套离调/级数格式规则。

`sourceEventId` 从 annotation provider 贯通到 `RenderElement.eventId`。音符点击是只读上下文，
注释点击才进入精确的可编辑和弦事件；两类选择互斥。

## 3. 注释谱表与锚定

- `AboveAllStaves` / `BelowAllStaves` 已参与连续和分页的系统纵向占位；
- `AboveStaff` / `BelowStaff` 当前安全回退到 `BelowAllStaves`，以后补充指定 staff 的局部锚定；
- `interactive=false` 的注释生成零尺寸 hit box，让点击穿透到音符；
- annotation 文本、面板标题和候选结果共享 `FormattedText` / `ChordSymbolFormatter`；
- `AnnotationElement.Range` 携带起止拍点、框高度和多行文字；renderer 在断行后拆成逐系统片段，
  每个系统都为相交范围预留上方注释带，声明/实测宽度也进入比例排版；
- 注释宽度变化使用 `RenderEngine.renderRange` / `ComputeChangeSet.forRange`，窗口内重排，分行
  变化时安全回退全量。详见 [renderer/incremental-rendering.md](../renderer/incremental-rendering.md)。

## 4. 音符着色

`ChordToneStyleProvider` 只实现 `NoteStyleProvider`，不直接操作 renderer 的样式轨：

```text
ChordToneStyleProvider.computeStyles(computed)
    → StyleOverrideManager
    → StyleSnapshot
    → ComposeScoreRenderer
```

开关变化只需调用 `RenderEngine.reapplyNoteStyles()`，无需重新布局。和弦事件解析统一使用
`ComputedScore.pluginEventsOf<T>(trackType)`，不要对匿名 `ComputedPluginEvent` 直接做错误的
具体类型匹配。

## 5. 序列化与启动顺序

插件在 bootstrap 阶段注册 `SerializersModule`，再创建/使用 `ScoreSerializer`；必须早于任何文件
load/save。YAML 与 JSON round-trip 都要覆盖含插件事件的乐谱。若未来支持多窗口不同插件集，应将
当前 serializer 单例改为显式 scope，而不是继续扩大全局可变状态。

## 6. 当前性能边界

- plugin track 的 Computed 仍走全量 `computeScore`，避免把插件事件错误地塞进 voice/pitch 增量计算；
- annotation 的 renderer 布局和绘制可按已知小节范围 splice；
- note-style 可轻量刷新，但 provider/switch/首帧变化仍安全走全量；
- 自由 `PluginRenderComponent` 未声明 measure/system scope 时禁用 splice。

性能契约统一见 [renderer/incremental-rendering.md](../renderer/incremental-rendering.md)，不要在
本文复制 renderer 的逐轮 benchmark。

## 7. 验收

至少运行：

- `:core:jvmTest`（含插件序列化 spike）；
- `:renderer:jvmTest`；
- `RenderAnnotationSpliceTest`；
- 插件模块的实际测试（没有测试源时应明确记录 `NO-SOURCE`，不能当作行为覆盖）。

检查 YAML/JSON round-trip、注释宽度变化、注释命中与音符选择互斥、note-style 轻量刷新，以及
annotation 增量结果与冷全量结果等价。

## 8. 当前 TODO 与注意事项

1. 将 `AboveStaff` / `BelowStaff` 的实际锚点写入布局结果，并覆盖跨系统/分页高度变化。
2. 为 provider 增加可选的窗口计算协议；不支持窗口的 provider 继续全量回退。
3. 让 style snapshot 也能按 dirty section 增量合并，避免 provider 局部化后 snapshot 仍整轨重建。
4. 处理多窗口插件刷新前，先保持单一连续范围入口，避免 desktop 与 renderer 各维护一套窗口规则。
5. 分析页面需要时间轴时复用 `HarmonyTimelineReadingProjector`、`HarmonyTonalTimeline` 与
   `AnnotationElement.Range`，不要复制主界面 provider 的离调判定或框布局。
6. 修复 Gradle capability 冲突时必须先确认模块依赖方向，不能通过让 `:api` 依赖 Compose 或 core 来绕过。

相关文档：[plugin-framework.md](plugin-framework.md)、[custom-track.md](custom-track.md)、
[renderer/README.md](../renderer/README.md)、[ARCHITECTURE.md](../ARCHITECTURE.md)。
