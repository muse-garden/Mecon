# 引擎演进：分析规模化与记谱扩展

> **状态**：🚧 设计与路线图。本文只保留跨模块决策；增量 compute 见
> [data_model/incremental-update.md](data_model/incremental-update.md)，renderer 的实现和性能
> 见 [renderer/incremental-rendering.md](renderer/incremental-rendering.md)。

## 1. 当前结论

Mecon 已有可用的增量编辑链路，但“新增一个分析/记谱元素”不能只增加一个 renderer pass：它还
必须说明数据来源、Computed 生成、布局影响、分页归属、拼接策略和全量 parity。性能问题也不能
靠扩大窗口、增加搜索预算或放宽 splice guard 解决；先用 `PerfLog` 找到真实消费方，再决定是否
需要缓存或新索引。

## 2. 分析规模化

分析功能会同时产生大量音符样式、注释和覆盖层。当前边界如下：

| 通道 | 当前结论 | 后续工作 |
|------|----------|----------|
| `NoteStyleProvider` | 和弦音着色已有窗口化 provider/patch 入口；首帧、provider 集变化安全走全量 | 增量 snapshot 合并与 dirty-section 日志，补随机 patch parity |
| `AnnotationStaffProvider` | 注释宽度进入比例排版；连续/分页 splice 已按锚点重新生成并保留垂直占位 | 稳定的 `layoutWindow` 协议、跨帧测量缓存、跨窗口 span 规则 |
| `PluginRenderComponent` | 未声明范围的自由覆盖层继续禁用 splice，保证正确性 | 引入 `STATIC` / `MEASURE_ANCHORED` 等明确 scope；保留 `FULL_FRAME` 逃生路径 |
| `renderRange` / change set | 已支持已知连续小节的局部 renderer 刷新 | 需要离散多 ranges 时再设计协议，不提前复制两套窗口逻辑 |
| Compose/播放派生 | 大帧引用相等、钢琴卷帘和播放时间线后台化已有约束 | 继续用大谱回归验证主线程无 O(score) 新工作 |

插件接入指南：符头着色走样式轨；随时间点的文字走注释谱表；区域高亮声明 measure anchor；
只有真正自由绘制才使用 full-frame overlay。

## 3. 记谱扩展

### 3.1 StaffKind

TAB、打击乐谱和现代记谱需要从 Storage/Runtime 的谱表类型开始，而不是在 renderer 中猜测。
新增类型必须同时回答：

1. Storage 是否能序列化、兼容读取和迁移；
2. Computed 是否决定应生成哪些元素；
3. renderer 如何排版、分页和命中；
4. 普通编辑能否局部重生成，不能时如何安全回退；
5. MusicXML 与桌面/Web adapter 的能力边界。

### 3.2 传统五线谱的优先顺序

当前建议按“复用现有管线、闭环最小”的顺序推进：

1. 文本/排练记号与更多演奏法；
2. 装饰记号、琶音和滚奏；
3. 滑音、踏板等区间附件；
4. 歌词、多小节休止、cue/ossia 和完整跨谱表符杠。

MusicXML 已能解析但尚未进入 Storage 的元素，先补互操作和 round-trip，再接 UI；不要把解析器
半成品当作功能已实现。

## 4. 跨模块接入清单

新增元素或分析通道时，PR 必须附上：

- Storage/Runtime/Computed/Render Geometry 的数据流和兼容性说明；
- `ComputeChangeSet` 失效范围、system/page 归属和连续/分页 splice 决策；
- 冷全量与增量 parity 测试；
- 若有 UI 或 Web 入口，说明 adapter 如何调用共享业务本体；
- `docs/` 中当前结论与 TODO 的更新，避免新增逐条开发日志。

## 5. 当前 TODO

1. 完成 style snapshot 的增量合并，避免“provider 已窗口化、snapshot 仍全量”的半优化状态。
2. 为 annotation 和 measure-anchored overlay 固化窗口 SPI，并证明 reflow 时的垂直占位等价。
3. 在真实需求出现后设计 `StaffKind`，先从一个完整闭环的谱表类型开始，不并行铺开 TAB/打击乐/现代记谱。
4. 将新的 `RenderElementType` 与 continuous/paginated splice 能力表绑定，未知/插件元素保持 fail-safe。
5. 每项扩展都补文档与开源 PR 证据；性能改动必须附探针前后数据和回退条件。

## 6. 注意事项

- `DependencyScope`、任意绝对坐标 B+ 树和无消费方的缓存都不是当前默认方案。
- renderer 不决定“是否生成”乐谱元素；这属于 Computed 层。
- 规则、状态和 undo/redo 不得在 Web/桌面 adapter 各写一套；编辑能力遵守
  [多端接入规范](score-editing-multiplatform.md)。
