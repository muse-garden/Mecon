# 增量布局与渲染

> **状态**：✅ 增量 compute、布局复用、元素级 splice、分页页缓存与分页流式输出已接入。
> 本文只记录 renderer 的当前契约；Core 的变更窗口见
> [../data_model/incremental-update.md](../data_model/incremental-update.md)，大谱桌面热路径见
> [../performance/large-score-editing.md](../performance/large-score-editing.md)。

## 1. 当前管线

```text
RuntimeScore
   │  ComputeEngine / computeScoreIncremental
   ▼
ComputedScore + ComputeChangeSet
   │
   ├─ notation / measure structure changed → full layout + full render
   └─ bounded voice-event change
         ▼
   UnifiedLayoutComputer(reuseXFrom, affectedMeasures)
         ▼
   ContinuousRenderSplicer 或 PaginatedRenderSplicer
         ▼
   完整且自洽的 RenderResult
```

`RenderResult` 的绘制元素、`SectionIndex`、空间索引和页面列表必须来自同一帧。renderer
可以复用旧帧的不可变对象，但不能对外发布“新绘制 + 旧索引”的半帧。

## 2. `RenderEngine` 的职责

`RenderEngine` 负责公共入口、上一帧缓存和路径调度：

- `render()`：建立冷的完整布局与完整结果；
- `renderIncremental()`：根据 `ComputeChangeSet` 复用布局、调用连续或分页 splicer；
- `renderRange()`：插件或分析只使一个已知小节范围失效时使用 `ComputeChangeSet.forRange`；
- `renderStreaming()`：分页的完整/reflow 路径按页回调，普通 splice 仍返回完整结果；
- `null changeSet`：同一文档可通过 `computeChangeSetBetween` 做 diff fallback，只有文档替换才
  应重置 renderer 缓存。

全量元素编排、结果装配、页切片和两种 splicer 分别由
`FullScoreRenderer`、`RenderResultAssembler`、`RenderPageBuilder`、
`ContinuousRenderSplicer` 与 `PaginatedRenderSplicer` 负责。新增 Renderer 协作者时保持
`RenderEngine` 只做调度和状态提交。

## 3. 当前已落地的复用边界

### 3.1 水平布局与系统谱系

- 普通音符编辑按 `affectedMeasures` 重解窗口内 X；窗口外小节按尾部平移复用缓存 X。
- 分行/分页改变时由 `systemLineage` 从受影响行的前一行开始重排，行首在缓存边界收敛后复用尾部。
- 分页稳定分区时，post-break slot、measure extent、attachment placement、voice-layout
  chunk 和相关索引按 system/measure 做结构共享；reflow 或契约不满足时回退完整布局。
- `systemIndex` 是分页元素归属的首选来源；历史元素没有 metadata 时才回退到 Y-band 路由。

### 3.2 元素级 splice

缓存元素按来源和小节分为前缀、受影响窗口、尾部。窗口内由新的 pass 重生成，尾部使用
`Δx/Δy` 平移；有跨窗口的 tie、slur、区间附件或无法证明局部等价的元素时扩大窗口或安全回退。

以下情况必须保持 fail-safe：

- 未知 `RenderElementType`、自由形态 `PluginRenderComponent`、自定义相交逻辑；
- 结构、谱号、调号、拍号或小节数量变化；
- 区间符号跨越受影响边界且没有可复用的完整端点/系统数据。

能否加入 splice 白名单不是性能猜测，而是一个需要“局部重生成 + 全量等价”测试证明的契约。

**跨小节元素必须按区间而不是起点分区。** `TEXT_ANNOTATION` 在连续 splicer 里按
`measureNumber` 划分 prefix / tail / 窗口，但 `AnnotationElement.Range` 的右边缘锚在 tail，
只看起点会把“起于窗口之前、伸进窗口”的框当成 prefix 原样复用，冻结旧宽度而后续记谱已按
δx 平移。因此 `RenderElement` 携带 `endMeasureNumber`，复用条件是 `endMeasureNumber < window.first`，
重生成条件是 `AnnotationElement.intersectsMeasures(window)`——两者必须互补，否则元素会重复或丢失。
分页 splicer 不受影响：区间已按系统拆片，逐片带自己的 `systemIndex`，复用键就是系统。
新增任何带时值的渲染元素时照此处理，并补一条连续模式下的全量等价测试
（`RenderAnnotationSpliceTest.continuousEditUnderASpanningRangeAnnotationSplicesAndMatchesFull`）。

### 3.3 分页与流式输出

- 未受影响页按 `RenderPage.elements` 引用复用；受影响页重新切片并应用页内位移。
- `renderStreaming` 先发送含 `affectedMeasures` 的锚点页，再发送其余页；首个页面到达不等于
  render generation 完成。
- 桌面 UI 只有在完整 `RenderResult` 发布后解除更新遮罩和交互保护；流式旧页在此之前保持 stale
  标记并跳过命中。

## 4. 性能工作的位置

renderer 侧优化统一记录在本目录，桌面 Compose、钢琴卷帘、播放时间线与提示状态记录在
[大乐谱编辑性能复盘](../performance/large-score-editing.md)。目前应保留的优化包括：

- `ComputedScore.eventsInMeasureRange` 的 B+ 树窗口查询；
- `VoiceEventLayoutMap` 的 measure chunk 与 beam-group 派生索引；
- attachment extent/placement 的跨帧复用和跨边界安全回退；
- ordered rich runs、分页 page reuse 与局部 `SectionIndex` patch；
- `PerfLog` 的阶段探针。先用 `render.stage` / `splice.bail` 归因，再选择数据结构或 pass 改动。

不要把这些实现细节重新写回数据模型文档；Core 只提供变更窗口，renderer 决定布局和绘制失效范围。

## 5. 验收与测试

修改布局或 RenderElement 时至少运行：

- `:renderer:jvmTest`；
- `RenderIncrementalParityTest`、`RenderSpliceEquivalenceTest`、
  `RenderIndexSpliceEquivalenceTest`；
- 分页相关的 `PaginatedIncrementalLayoutTest`、`RenderStreamingTest`；
- 涉及 geometry、插件注释或结构元素时，运行对应的
  `RenderGeometryIncrementalTest`、`RenderAttachmentGeometryTest`、`RenderAnnotationSpliceTest`。

每个增量用例都应与冷全量结果比较元素/命令、索引和页面；性能优化不能以放宽等价断言作为完成条件。

## 6. 当前 TODO

1. 将剩余的 `computeLayout` / assemble 全局工作继续按实测探针收窄；没有数据时不新增缓存层。
2. 评估更细的 per-system/page forward bucket，只有在能保持绘制顺序、引用共享和索引同源时才落地。
3. 为 `ComputedScore` 补充 tie 入边、slur 区间、tuplet 成员等反向索引，减少局部查询的重复定位。
4. 为 `AnnotationStaffProvider` 与可作用域化的 plugin overlay 定义稳定的窗口协议；自由 overlay 继续
   使用全量回退。
5. 新增 `RenderElementType` 时同步更新 continuous/paginated 能力表、system 归属、局部重生成和 parity 测试。

## 7. 已确认的弯路与注意事项

- `DependencyScope` 依赖图没有作为首版实现；边界明确的 measure window 更容易验证。
- 单独引入 B+ 树但没有实际 renderer 消费方会增加索引维护成本；缓存必须先有真实消费者。
- 不能用元素 hit-box 中心 X 代替音符 slot/measure 归属；符杆和变音记号可能横向越界。
- 分页元素不能用全局谱面 Y 反推系统；先用 system 的 staff core 区域锁定，再做局部候选查询。
- 取消发生在 assembly 提交前必须回滚 renderer 缓存；否则下一帧 lineage 会把半成品当作上一帧。
