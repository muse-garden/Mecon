# 渲染遍历与局部化审计

> **状态**：✅ 主要全谱重复扫描已在增量路径收口；本文记录当前数据结构、失效粒度和剩余
> 热点。历史测量与逐轮尝试不再逐条保留，性能结论见
> [incremental-rendering.md](incremental-rendering.md) 与
> [大乐谱编辑性能复盘](../performance/large-score-editing.md)。

## 1. 审计结论

renderer 的局部化边界不是“一个音符一个元素”，而是由音乐关系和排版耦合决定：

| 渲染单元 | 默认失效范围 | 原因 |
|----------|-------------|------|
| Notehead / stem / flag | 受影响 slot 或 beam group | 同槽碰撞、音高和符干方向 |
| Beam | old/new `BeamGroupId` 的成员并集 | 时值、beaming、跨谱表基准 |
| Tie | source + incoming target | 延音目标存于源事件 |
| Slur | 起止事件与覆盖区间 | 嵌套、跨系统和碰撞 |
| Tuplet | 所属 span 的成员 | 连音组比例与括号/数字 |
| Articulation | 事件自身 | 符头/符干侧和局部堆叠 |
| Dynamics / octave / hairpin | staff attachment 行带或区间 | 纵向行优先级与跨系统拆段 |
| header / staff lines | system | 系统结构和谱表上下文 |

因此普通音高编辑可以保持 X、只重算 Y；时值/临时记号/插删会扩大到小节和系统；结构或
跨边界变化安全回退 full/reflow。

## 2. 当前已使用的索引与窗口入口

- `ComputedEventStore` 提供按小节的 B+ 树范围查询；`ComputedScore.eventsInMeasureRange` 从前一
  小节边界开始并按 onset measure 过滤，保留拍首前 grace note。
- `LayoutQuery` 统一事件环境、notehead anchor、staff/system 查询；局部 pass 不应重复扫描
  `voiceEventLayouts` 找同一个事件。
- `UnifiedLayoutResult.voiceLayoutsByMeasure` 与 `VoiceEventLayoutMap` 的 measure chunks 维护
  时间顺序和 beam-group 派生索引，增量 patch 只替换受影响小节。
- `systemIndex` 是分页元素的权威归属；没有 metadata 的旧/全局元素才走 Y-band fallback。
- attachment extent/placement、post-break slots、page buckets、ordered rich runs 和 section
  index 都允许未受影响部分按引用复用；任何缓存 lineage 不满足时返回 null 并全量重建。

这些索引属于 renderer/Computed 的真实消费方；不再为了“未来可能的查询”给 Runtime 增加未接线
索引。

## 3. 仍然允许的全谱工作

完整冷渲染必须枚举整个 `ComputedScore`；以下工作也可能在 reflow/full 路径保留全谱：

| 工作 | 当前边界 | 处理原则 |
|------|----------|----------|
| 首次 layout / page break | 全谱 | 产出可复用的 system、measure、page 中间结果 |
| 结构 header、staff line、全局 bounds | full 或不连续缓存 | 不为追求命中率破坏绘制顺序 |
| 自由 plugin overlay | full | 未声明锚点/范围就不能拼接 |
| 夸系统 tie/slur/attachment | 扩大窗口或局部 pass 回退 | 必须覆盖端点与碰撞上下文 |
| `allEventsSorted()` 等兼容 API | full consumer | 增量 pass 优先使用 range API，不在兼容入口偷改语义 |

`calculateBounds`、time-code positions、section/spatial index 的增量化必须由探针证明有收益，且
保持与完整结果同源；“遍历 O(N)”本身不是足以改结构的理由。

## 4. 诊断方法

使用 `renderer/.../debug/PerfLog.kt`：

```powershell
.\gradlew.bat :apps:desktop:run "-Dmecon.perf=true"
```

先看 `render.stage` 的 `computeLayout`、`splice/assemble`、geometry fold，再看
`splice.bail` 的具体原因。Compose `SideEffect` 的总耗时可能包含父级和兄弟 composable，不能
仅凭一个聚合数字把问题归因给 renderer。

## 5. 之前的弯路与保留的规则

- 早期把所有部件都按 `computedEvents.values` 全量过滤；当前局部 pass 必须从 range/chunk 起步，
  但 full 入口保持简单、可验证。
- 试过用元素 hit-box 中心 X 判断窗口归属；符杆、变音记号和宽和弦会越过 slot，因此现在按
  event/measure/system metadata 分类。
- 试过按 section 聚合再 patch；窗口键分散时临时表开销更大，现使用保持 painter 顺序的
  transient/persistent 结构共享。
- 试过按绝对 Y 建 B+ 树；编辑会改变绝对 Y，key 每帧漂移，不能作为稳定缓存键。
- 分页的 system core 区域必须先锁定指针所在行，再在该 system 的 measure bounds 中吸附；不得用
  带加线音或附件扩张后的 `SystemNode.topY/bottomY` 直接判行。
- 取消必须发生在 assembly 提交前并回滚缓存；元素、sections、spatial index 永远随同一帧提交。

## 6. 当前 TODO

1. 用真实 `PerfLog` 数据继续收窄 `computeLayout` 的剩余全谱 collect/index pass；无实测不新增缓存。
2. 评估 per-system/page forward bucket，要求顺序、bounds、page reuse 和 hit-test parity 同时成立。
3. 为 tie incoming、slur interval、tuplet membership 设计稳定反向索引，减少局部环境反查。
4. 将可局部化的插件样式、注释和 overlay 统一成 scope/window SPI；未声明作用域的插件继续回退。

## 7. 测试门禁

修改遍历、索引或局部 pass 时运行：

- `RenderIncrementalParityTest`；
- `RenderSpliceEquivalenceTest`、`RenderIndexSpliceEquivalenceTest`；
- `PaginatedIncrementalLayoutTest`、`RenderStreamingTest`；
- 相关的 geometry、annotation、clef-break 和跨系统 slur 测试。

任何优化都要证明：普通编辑仍走应有的 splice/reflow 路径，冷全量与增量元素/命令/索引/页面等价，
未知元素仍 fail-safe。
