# RenderEngine 拆分现状

> 路径：`renderer/src/commonMain/kotlin/com/mecon/renderer/render/`
> 状态：✅ `RenderEngine` 已收敛为渲染调度、缓存与结果提交入口。增量路径的完整契约见
> [incremental-rendering.md](incremental-rendering.md)。

## 1. 目标边界

`RenderEngine` 的职责应收敛为：

- 对外 API：`render()` / `renderIncremental()` / `renderUnified()`。
- 渲染状态：上一帧 `ComputedScore`、`UnifiedLayoutResult`、`RichElement` 缓存、增量路径诊断标志。
- 调度顺序：全量渲染、连续谱 splice、分页 splice 的路径选择。
- 渲染状态落点：所有路径最终通过 `finishAssembly()` 更新 `RenderResult`、`RichElement` 缓存与 hit-test index。

它不应长期持有的职责：

- `RenderResult` 装配、SectionIndex / SpatialIndex 构建。
- 页内局部坐标切片。
- title block 文字渲染。
- 命中区域富化与 `RichElement` 组装。
- staff lines / system-start / closing barline / header bracket 等结构性元素渲染细节。
- 连续谱 / 分页 splice 的 guard、缓存元素分类、窗口重渲染细节。

## 2. 已拆出的协作者

| 文件 | 职责 |
|------|------|
| `RichElement.kt` | 渲染元素 + sections + hittable 的缓存单元；供全量与增量 splice 共用 |
| `RenderElementCollector.kt` | 收集 `ElementRenderOutput`，合并 `sectionRegistrations` 与 hit area |
| `RenderHitAreaEnricher.kt` | 将 `ElementHitArea` 富化为 `HittableRegistration` |
| `SpliceWindowCollector.kt` | 增量路径中收集重新生成的窗口元素 |
| `RenderResultAssembler.kt` | 统一构建 `RenderResult`、`SectionIndex`、`HierarchicalSpatialIndex`、time-code positions |
| `FullScoreRenderer.kt` | 全量渲染编排：slot、stem/flag、beam、articulation、tie、slur、tuplet、attachment、annotation、plugin |
| `NotationElementPassRenderer.kt` | time-slot 元素与 stem/flag 通用 pass；全量渲染与 splice 窗口重渲染共用 |
| `ContinuousRenderSplicer.kt` | 连续单系统增量 splice：guard、prefix/window/tail 分类、窗口重渲染 |
| `PaginatedRenderSplicer.kt` | 分页行级增量 splice：affected system 判断、系统复用、窗口重渲染与安全回退；分行移动时由 `systemLineage` 复用前缀并在收敛后复用尾部，细节见 [incremental-rendering.md](incremental-rendering.md) |
| `RenderPageBuilder.kt` | 分页模式下把全局元素切成 page-local `RenderPage` |
| `StructuralElementRenderer.kt` | staff lines、system-start line、closing barline、header bracket / label |
| `LineStartHeaderRenderer.kt` | 分页行首重述的 clef / key signature |
| `TitleBlockRenderer.kt` | 标题 / 副标题 / 作者文本元素 |

## 3. 当前状态

`RenderEngine` 现在已经不再直接持有全量渲染或 splice 的细节实现。它保留的核心代码是：

- public API 与 layout 入口调用。
- incremental change set lineage / diff fallback。
- 选择 full / continuous splice / paginated splice。
- 统一更新上一帧缓存与 `HitTestService`。

## 4. 当前 TODO

### A. 继续压缩 `renderIncremental()` 的调度代码

`renderIncremental()` 仍承担 change set 解析、layout reuse 条件、路径选择与状态更新。可进一步提取一个轻量的 `IncrementalRenderCoordinator` 或纯函数结果对象，但需要谨慎保持 `RenderEngine` 的状态所有权不分散。

### B. 提取 notation 局部单元

`TieLayoutComputer` / `TupletLayoutComputer` 的单单元逻辑仍内联在循环中。等编辑 API 需要按单元重渲染时，可公开 `renderTie(...)`、`renderTuplet(...)` 这类纯局部入口。

### C. 建立 Computed 层反向索引

分页 splice 现在仍会在受影响系统内跑若干全局 layout computer，再按 affected system 过滤。下一步性能收益更大的方向是 `ComputedScore` 的 tie 入边、slur 区间、tuplet 成员等反向索引，让编辑路径能从事件直接定位局部渲染单元。

## 5. 推荐顺序

1. `TieLayoutComputer` / `TupletLayoutComputer` 单单元函数。
2. `ComputedScore` 反向索引设计。
3. `renderIncremental()` 调度对象化。

每一步都应保持 `:renderer:jvmTest` 通过；涉及 splice 时重点看 `RenderSpliceEquivalenceTest`、`RenderIndexSpliceEquivalenceTest`、`PaginatedIncrementalLayoutTest`。

## 6. 已确认的注意事项

- `RenderEngine` 必须是所有路径更新 `RenderResult`、rich-element cache 与 hit-test index 的唯一提交点。
- unknown/plugin/free-form 元素继续安全回退，不以“暂时没测到”作为放行理由。
- 涉及 splice 的改动先更新 [incremental-rendering.md](incremental-rendering.md) 的契约和 parity 测试，
  再修改调度代码。
