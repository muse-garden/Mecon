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
| `:api` plugin SPI | `MeconPlugin`、注册上下文、序列化模块、`AnnotationStaffProvider`、`NoteStyleProvider`、`NoteSelectionLabelProvider` |
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
投影，不在插件内维护第二套离调/级数格式规则。同一声音类只取目录中的规范读法，避免主和弦同时
显示 `I` 与次属解释 `V/IV`。活动显式调性不会移除谱面调号的次级解释；重叠调性按调性线起点排序，
和弦框像自由练习一样每个调性占一行，展示对应功能符号与相对/绝对构成音，所选音符的音级标签
使用同一顺序。该谱面时间轴固定采用浅色主题的 surface、
文字、边框与强调色，不随主界面的明暗主题切换。

`sourceEventId` 从 annotation provider 贯通到 `RenderElement.eventId`。音符点击是只读上下文，
注释点击才进入精确的可编辑和弦事件；两类选择互斥。

### 2.1 主界面调性区域编辑

桌面“和弦分析”面板直接提供“插入离调 / 转调”，不依赖“复调分析助手”总开关。目标起点来自
所选音符的最早 onset，或所选和声时间轴和弦框；新区域默认延伸到谱尾。弹层复用
五度圈，先勾选最多两个调性并选择中心后再确认插入；默认在所选音符或和弦的结束处终止与其重叠
的旧调性区域，而新区域仍延伸到谱尾。旧区域缩短、清除其后续调性中心和新增区域通过
`PluginPanelContext.onReplacePluginEvents` 一次提交，保持单历史项和失败原子性。首次插入时还会把
当前位置的谱面调号物化为 `SCORE_KEY_BASELINE` 调性区间：旧调性终点采用所选音符或和弦的终点，
新调性起点采用其起点，两者的区间交集即双重调性范围。

和声时间轴中的显式调性线携带 `sourceEventId`，选中后显示首尾手柄；桌面适配器只把指针映射到
当前系统的稳定 `TimeCode` 边界，再由 `TonalRegionEditPolicy.resize` 校验半开区间并更新插件事件。
多调性区域拆成独立显示线但仍共享一个存储事件与一组端点。谱面调号基线始终位于最上方 lane；
物化后的基线起点固定在对应调号段起点，终点可拖动，插入调性的首尾也可独立拖动。两条线只在
实际交集内共同参与解释，交集外分别沿用仍然有效的单一调性；交集片段降低基线透明度。拖动期间
原区域保留，并用吸附端点虚线实时预览。
跨系统拆分后只暴露第一行左端和最后一行右端，系统内小节边界先从 staff-space 转为全局像素再参与
吸附，避免续行片段被误当作整个调性区间的端点。分页横向拖动在端点原行附近锁定其源系统，只有
明显纵向移入另一行才重新选系统；悬停或拖动端点时使用水平双箭头光标。

模糊调性选择由 `TonalRegionKeyInference` 在插件 commonMain 统一投影：

- 单音模式按该音的拼写列出“音级 + 大/小调”组合，包含变化音级；
- 多音模式先最小化所选不同拼写音高中的变化音数量，再优先 `♯4`、`♯5` 等常见变化音，最后按
  当前调性的五度圈距离稳定排序；
- 下拉框在没有选中音符时也允许先切换五度圈、单音音级或多音候选；弹层采用非焦点模式，点击谱面
  修改选择时不会关闭，选择方式、是否终止旧调性的状态和实时候选都会保留；
- 所选音符的音级由 `NoteSelectionLabelProvider` 投影，桌面通过 `SectionIndex` 定位符头 X；同一位置的
  音级在白底框内纵向排列。框的底边位于每个系统所选范围最上方谱表的实际占用 `StaffRegion.topY`
  之上，避开加线、符干等扩张内容。覆盖框不进入 annotation staff、排版边界、分页或命中索引，
  因此选择变化只重绘画布；和弦分析面板可独立关闭该常驻显示；
- 完整复调音级轨道和经过和弦检测仍受原“复调分析助手”总开关控制。

调性候选与音级标签只消费 `Pitch` / `ModulationKey`、当前选择和存储事件，不把 Compose 状态或像素
坐标带入共享层。`NoteSelectionLabelProvider` 只遍历选择与调性区域，不在 Compose 主线程扫描整谱；
坐标定位是桌面 adapter 的非持久化职责。没有新增存储字段，旧 `.mecon` 的读写语义不变。

## 3. 注释谱表与锚定

- `AboveAllStaves` / `BelowAllStaves` 已参与连续和分页的系统纵向占位；
- `AboveStaff` / `BelowStaff` 当前安全回退到 `BelowAllStaves`，以后补充指定 staff 的局部锚定；
- `interactive=false` 的注释生成零尺寸 hit box，让点击穿透到音符；
- annotation 文本、面板标题和候选结果共享 `FormattedText` / `ChordSymbolFormatter`；
- `AnnotationElement.Range` 携带起止拍点、框高度和多行文字；renderer 在断行后拆成逐系统片段，
  每个系统都为相交范围预留上方注释带，声明/实测宽度也进入比例排版；
- **provider 里不得因陈旧数据抛异常**：`layout` 运行在渲染管线内，一次 `require` 失败会带走整帧，
  而不是丢掉一个标记。`MeasureEditEngine` 不重映射 plugin track，删小节后 `StorageTonalRegionEvent`
  会残留在谱尾之外，裁剪到谱尾就退化成空区间——用 `HarmonyTonalRange.clippedOrNull` 丢弃，
  与点状注释“解析不到 x 就不生成”保持同一种 fail-safe；
- 注释宽度变化使用 `RenderEngine.renderRange` / `ComputeChangeSet.forRange`，窗口内重排，分行
  变化时安全回退全量。跨小节的 `Range` 还要满足 `endMeasureNumber` 分区契约，详见
  [renderer/incremental-rendering.md](../renderer/incremental-rendering.md)。

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

调性区域入口还需运行 `:plugins:chord-analysis:core:jvmTest` 与
`:plugins:chord-analysis:core:jsTest`，覆盖候选顺序、单音音级、终止旧区域，以及独立于助手开关的
非占位选择音级投影。

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
