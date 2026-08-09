# 线程模型

本文档描述 Mecon 桌面端从「打开文件」到「绘制到屏幕」全过程的线程归属，作为乐谱编辑功能的并发设计基线。

## 1. 全景

数据流各阶段所在线程：

```
磁盘 (.mecon / MusicXML)
     │  ScoreFileService.loadAuto()        ── Dispatchers.IO
     ▼
StorageScore
     │  RuntimeScore.fromStorage()
     │  ComputeEngine.compute()            ── Dispatchers.Default
     ▼
ComputedScore
     │  ScoreLayoutEntry.computeLayout()
     │  RenderEngine.renderUnified()       ── renderDispatcher（Default 串行）
     ▼
RenderResult
     │  ComposeScoreRenderer.render()      ── UI 线程（Compose 重组 + Canvas 绘制）
     ▼
Compose Canvas
```

核心原则：**UI 线程只做重组、绘制与轻量命中查询；所有解析、建模、Compute、Layout、Render 都在后台线程完成。**

## 2. 各阶段线程归属

### 磁盘 I/O — `Dispatchers.IO`

`ScoreFileService` 所有读写方法均以 `withContext(Dispatchers.IO)` 包裹，见
[`ScoreFileService.kt`](../apps/desktop/src/main/kotlin/com/mecon/desktop/service/ScoreFileService.kt)。
在 IO 线程内完成 `readText()` 以及**格式解析**：

- `ScoreSerializer.fromYaml()` — YAML → `StorageScore`
- `MusicXmlConverter.import()` — MusicXML → `StorageScore`

### 建模 + Compute — `Dispatchers.Default`

`RuntimeScore.fromStorage()` 与 `computeScore()` 是 CPU 密集型纯函数（输入不可变、输出新实例），
放在 `Dispatchers.Default`。两处调用点见 [`App.kt`](../apps/desktop/src/main/kotlin/com/mecon/desktop/App.kt)：

- **打开文件**（`onOpenFile` → `loadAuto().onSuccess`）：
  `withContext(Dispatchers.Default) { fromStorage + computeScore }`，结果回主线程构造 `ScoreStateManager`。
- **插件编辑**（`applyPluginEdit`）：`coroutineScope.launch` + `withContext(Dispatchers.Default)`，
  在后台重算 Runtime/Computed，回主线程 `commitNewState`。
- **拖拽移调提交**（`ScoreSession.applyNoteTranspose`）：在 Compose 主线程以
  `CoroutineStart.UNDISPATCHED` 进入协程并立即切到 `Dispatchers.Default`，避免提交手势后先在
  主调度器冗余排队一次；增量 Compute 完成后仍回主线程提交状态与更新选择。

### Layout + Render — `renderDispatcher`（Default 上的串行调度器）

见 [`RenderedScoreView.kt`](../apps/desktop/src/main/kotlin/com/mecon/desktop/ui/views/RenderedScoreView.kt)。
`RenderEngine.render(score)`（内含 `ScoreLayoutEntry.computeLayoutWithComputed` + `renderUnified`）
通过 `produceState { withContext(renderDispatcher) { … } }` 在后台执行，得到 `RenderResult` 后由 UI 线程绘制。

```kotlin
val renderDispatcher = remember { Dispatchers.Default.limitedParallelism(1) }

val renderResult = produceState<RenderResult?>(null, score, loadedFont, renderEngine) {
    value = withContext(renderDispatcher) { engine.render(score) }
}.value
```

### UI 线程

只剩：Compose 重组、`ComposeScoreRenderer` 的 Canvas 绘制、以及 tap 命中查询。
命中查询走 `RenderResult.hitTest()`——对当前显示的 `RenderResult` 值（含其自带的不可变
`spatialIndex` + `transformerSnapshot`）做查询，与绘制同源，无需加锁，详见
[renderer/spatial-index.md](renderer/spatial-index.md) §1a。

## 3. 两个并发约束（务必遵守）

### a) 引擎写操作必须串行 → `renderDispatcher` 用 `limitedParallelism(1)`

`RenderEngine.render()` 末尾会调 `applyNoteStyleProviders()`，而样式开关变化时的
`reapplyNoteStyles()` 也会写**同一个共享的 `noteStyleTrack`**。若二者并发执行（初始加载时两条路径都以
`renderEngine` 为 key 触发），会产生数据竞态（甚至重复创建 track）。

解决办法：render 与 reapply **共用** `renderDispatcher`（`limitedParallelism(1)` 串行），
既离开 UI 线程，又保证两者顺序执行，互不交叠。`lastComputedScore` 的跨线程可见性亦由该调度器的
happens-before 保证。

> 引申规则：**任何写 `RenderEngine` 内部状态的调用，都必须走 `renderDispatcher`**，不要新开线程或直接在主线程调用。

### b) 不闪空屏 → `produceState` 保留旧值

`produceState` 在 key 变化时仅取消旧协程、**不会**把 `value` 重置为初始值。因此重算期间画布继续显示
旧的 `RenderResult`，直到新结果就绪才切换，避免「重算 → 空白 → 新谱」的闪烁。

## 4. 已具备的「最新优先」语义

`produceState(score, …)` 在 `score` 变化时会**取消上一个尚未完成的 render 协程**，
所以快速连续编辑时过期的中间渲染会被自动丢弃，天然具备 conflate / collectLatest 效果——无需额外处理。

## 5. 已知边界与后续方向 🚧

- **编辑为 fire-and-forget**：`applyPluginEdit` 用 `launch` 异步提交。当前插件编辑是离散的面板操作
  （增/改/删单个事件），频率低，无虞。但每个回调在**调用时**读取 `scoreManager.currentState.storageScore`，
  未来若出现高频连续编辑（拖拽、连续音符输入），可能基于稍旧状态计算而产生竞态——届时应引入
  「最新优先 + 取消旧任务」（如 conflated channel / `collectLatest`），并参考
  [`incremental-compute.md`](data_model/incremental-compute.md) 的增量重算设计减少每次工作量。
- **命中测试在 UI 线程**：tap 选择在主线程对 `RenderResult.hitTest()` 做轻量查询（索引随结果走，无锁、与绘制同源）；若未来命中逻辑变重再评估下放。
- **全量重算**：`computeScore` / `render` 目前均为整谱重算，本文只解决「在哪个线程」，未解决「算多少」。
  增量化是独立方向，见 [`incremental-compute.md`](data_model/incremental-compute.md)。

## 相关文档

- 整体数据流：[ARCHITECTURE.md](ARCHITECTURE.md)
- 状态管理 / 撤销重做：[state-management.md](state-management.md)
- 增量计算设计：[data_model/incremental-compute.md](data_model/incremental-compute.md)
