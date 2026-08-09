# 增量计算与依赖追溯

> **状态**：🚧 `DependencyScope` 仍是后续设计；当前生产实现使用可审计的 measure window，见
> [incremental-update.md](incremental-update.md)。本文不重复已落地的 renderer 方案。

## 1. 当前实现

普通 `ComputeEngine.computeScore` 仍提供稳定的全量路径；编辑会话按条件调用
`computeScoreIncremental(previous, newRuntime, editInterval)`。后者：

- 向前扩一小节，按符杠/连音组边界继续扩窗；
- 只重算发生引用变化的声部窗口、隐式休止和该声部 slur；
- 通过持久 `ComputedEventStore` 合并，未触及节点按引用共享；
- 结构、谱号、调号、拍号、全局轨道或末小节变化时回退全量，并生成结构性 change set。

窗口策略先解决正确性和真实编辑路径，不假设每个派生字段都能被独立切片。

## 2. 为什么暂不引入 `DependencyScope`

概念模型如下：

```kotlin
data class DependencyScope(
    val trackSelector: TrackSelector,
    val timeRange: TimeRange,
    val eventFilter: (RuntimeEvent) -> Boolean,
)
```

它可以描述“临时记号依赖本声部本小节”“延音目标依赖后继同音高事件”“符杠依赖完整 group”等
关系，并由变更集反推出失效事件。然而完整落地仍需解决：

1. 依赖声明与现有 `ComputeEngine` 计算器的单一事实来源；
2. 跨小节、跨声部和插件字段的边界表达；
3. 依赖图自身的构建、缓存、调试和失效回退；
4. 增量结果与全量结果的逐字段 parity。

在这些问题有真实数据和稳定消费方之前，声明式图会增加幽灵 bug 风险，不能取代当前窗口方案。

## 3. 与布局层的区别

Compute 层输出“哪些事件和派生字段改变”；它不决定 X/Y、系统、页面、hit box 或 RenderElement。
renderer 通过 `ComputeChangeSet.affectedMeasures` 选择重排级别，详见
[../renderer/incremental-rendering.md](../renderer/incremental-rendering.md)。

不要把 `DependencyScope` 设计成 renderer 的元素依赖图；两者失效边界不同，最终都必须保持
Storage → Runtime → Computed → Render Geometry 的层级方向。

## 4. 未来 TODO

1. 继续收集不同类型编辑的窗口扩展和 parity 数据，确认 measure window 是否成为长期边界。
2. 若窗口仍是主要瓶颈，先把高频字段抽成带测试的纯计算器，再为它们设计有限 scope，而不是一次性
   给所有派生字段加图。
3. 为 tie incoming、slur interval、tuplet membership 等跨事件关系补充可查询索引；索引若没有
   实际局部 consumer，不提前加入 Runtime。
4. 若分析插件需要离散小节批量刷新，再讨论 `List<IntRange>` change set，并保留单窗口兼容入口。

## 5. 验收要求

- 增量计算与冷全量 `ComputedScore` 逐字段等价，未改部分尽量保持引用；
- 结构编辑不得伪装为普通音符编辑；
- stale/no-op/失败必须原子返回；
- 任何新依赖都要补“窗口边界、跨界、删除、撤销/重做”的测试，并更新
  [incremental-update.md](incremental-update.md) 的当前结论。
