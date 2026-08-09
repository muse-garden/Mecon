# 乐谱局部更新

> **状态**：✅ Core 增量计算、`ComputeChangeSet`、renderer 增量布局与元素级 splice 已落地。
> 本文只说明 Storage/Runtime/Computed 之间的契约；布局、拼接、分页缓存和性能探针见
> [renderer/增量布局与渲染](../renderer/incremental-rendering.md)，桌面热路径见
> [大乐谱编辑性能复盘](../performance/large-score-editing.md)。

## 1. 当前链路

```text
编辑先产生新的 RuntimeScore
        │
        ▼
computeScoreIncremental(previous, newRuntime, editInterval)
        │
        ├─ ComputedScore（窗口内重算，未变部分结构共享）
        └─ ComputeChangeSet（事件差集 + 实际小节窗口）
        │
        ▼
RenderEngine.renderIncremental / renderStreaming
        │
        ▼
完整、自洽、与空间索引同源的 RenderResult
```

“增量”指内部复用和局部重算；对调用方仍返回完整 `ComputedScore` 与完整 `RenderResult`，不暴露
半帧或局部索引。

## 2. Core 增量计算

### 2.1 入口与窗口

`core/.../engine/IncrementalComputeEngine.kt` 的入口是：

```kotlin
fun computeScoreIncremental(
    previous: ComputedScore,
    newRuntime: RuntimeScore,
    editInterval: TimeRange,
): IncrementalComputeResult
```

调用方负责先提交不可变的 `RuntimeScore`，引擎不修改 Storage/Runtime。默认窗口为编辑起始小节
前一小节到编辑结束小节；`ComputeEngine.computeVoiceTrackRange` 会继续扩展到完整的符杠组、连音组
和需要解析的前驱范围。窗口边界扩展后，以实际重算事件计算 `affectedMeasures`。

窗口策略覆盖音符、时值、休止、和弦与有限的声部编辑。它不引入字段级依赖图：准确性由窗口规则、
结构回退和全量 parity 测试共同保证。

### 2.2 结构共享

- `ComputedEventStore` 以持久化 B+ 树保存计算事件；窗口内 `put/remove`，窗口外节点按引用共享。
- 受影响声部的事件、隐式小节休止和该声部的 slur 重新计算；未触及声部及其派生列表保持共享。
- 临时记号、符杠、连音组、延音目标等继续由现有 `ComputeEngine` 计算器决定，增量入口只负责
  选择范围和合并结果。
- `ComputedScore.eventsInMeasureRange` 提供 grace-safe 的小节范围查询，供 renderer 局部 pass 使用。

## 3. `ComputeChangeSet` 契约

定义位置：`api/.../computed/IncrementalCompute.kt`。

| 字段 | 含义 |
|------|------|
| `addedEvents` | 新增音符、和弦或隐式休止的 `EventId` |
| `removedEvents` | 被删除或不再生成的 `EventId` |
| `modifiedEvents` | ID 保留但计算字段改变的事件 |
| `affectedMeasures` | 窗口扩展后的闭区间，renderer 的最小失效提示 |
| `notationChanged` | 小节线、谱号、调号或拍号列表变化 |
| `structureReflow` | 需要重排结构/分页；结构回退通常同时置为 `true` |

`allowsIncrementalLayout` 只有在没有 notation/structure 变化且窗口非空时为真。插件或分析只知道
某个小节范围时可用 `ComputeChangeSet.forRange(range)`，不必伪造事件差集。

`computeChangeSetBetween(previous, current)` 用持久事件存储的 diff 处理 undo/redo、跳过中间帧等
没有直接 change set 的场景；notation 或小节数量不同会安全标记为结构重排。

## 4. 必须全量回退的情况

以下修改不属于首版 bounded voice-event path：

- 小节数量、拍号、调号、默认谱号或谱表集合变化；
- staff clef/key/transposition/clef changes、全局轨道或会影响全谱的 attachment 变化；
- 末小节位置改变；
- 任何不能由当前窗口规则表达的跨全谱依赖。

回退不是错误，也不能通过扩大搜索预算或伪造窄窗口绕过。全量结果仍应生成对应的 diff，供 renderer
按结构重排并保持历史/撤销语义。

## 5. 与 renderer 和桌面的边界

Core 只提供“哪些 Computed 内容变了”和“影响到哪些小节”；它不决定 X/Y、系统分页、命中框或
绘制命令。renderer 根据 change set 选择连续 splice、分页 splice 或 full/reflow，详见
[renderer/incremental-rendering.md](../renderer/incremental-rendering.md)。

桌面 `ScoreRenderPipeline` 必须把同一文档的 null hint 交给 renderer 做 diff fallback；只有
`documentVersion` 变化时才清除上一帧缓存。渲染完成前保留旧结果，取消中的 renderer 缓存要在提交前回滚。
Compose 大帧 identity、钢琴卷帘和播放时间线的后台化属于桌面性能文档，不在数据模型层重复说明。

## 6. 正确性门禁

任何增量改动都以冷全量结果为黄金标准：

- `ComputedScore`：事件、隐式休止、slur、notation 列表与 runtime 语义等价；
- `RenderResult`：元素/命令、页面、section index、空间索引和 hit box 等价；
- 失败或 stale 输入：不部分提交、不污染历史、不留下半更新缓存。

核心回归包括 `IncrementalComputeEngineTest`、`LargeScoreDiffTest`；renderer 回归包括
`RenderIncrementalParityTest`、`RenderSpliceEquivalenceTest`、`RenderIndexSpliceEquivalenceTest`、
`PaginatedIncrementalLayoutTest` 和 `RenderStreamingTest`。

推荐命令：

```powershell
.\gradlew.bat --no-daemon :core:jvmTest
.\gradlew.bat --no-daemon :renderer:jvmTest
```

## 7. 当前 TODO

1. 增量 compute 仍是 measure window，不引入 `DependencyScope`；只有出现可重复的真实瓶颈时再设计更细
   的失效协议。
2. `ComputeChangeSet` 目前是单一连续窗口；分析插件的多窗口变更仍需先定义稳定的 ranges 协议。
3. 对跨全谱的 slur、attachment、插件派生数据继续补充明确的失效边界，而不是把它们偷偷塞进 voice window。
4. 新的 Storage/Runtime 字段必须先更新 `docs/data_model/`，并为增量与全量各补一条 parity 用例。

## 8. 已确认的弯路与注意事项

- `DependencyScope` 依赖图是后续探索，不是当前实现；首版选择可审计的区间扩展。
- 曾考虑用 B+ 树承载任意渲染 diff；在没有真实消费方前会增加维护和回退复杂度，因此当前只在事件范围
  查询、持久事件 store 和已有 renderer chunk 中使用持久结构。
- 窗口不能只按事件中心判断：grace note 位于拍首之前，beam/tuplet/slur 可能跨窗口；查询后仍需按
  `onset.measure` 精确过滤。
- 结构编辑不能伪装成普通音符编辑；宁可全量重排，也不能复用过期小节线、谱号或页面谱系。
