# 层次化空间索引

> 路径：`renderer/.../render/spatial/`、`HitTestService.kt`

## 1. 架构

```
HierarchicalSpatialIndex
└── SystemNode[i]                       (一个系统行，含 Y 范围)
    ├── BPlusTree<MeasureWidth>         小节宽度前缀和 → O(log M) 定位小节
    ├── List<StaffRegion>               谱表 Y 区域，遍历匹配（S ≤ 4）
    └── MeasureStaffCell[m][s]          每个小节×谱表存放命中元素
        └── List<ScoreHittableElement>   按 X 排序，线性扫描
```

三级分离让索引：
- 与音乐领域解耦（`HittableElement` / `BPlusTree` 是纯结构）
- 在适配层 (`ScoreSpatialAdapter`) 把 `UnifiedLayoutResult` 映射成索引
- 在服务层 (`HitTestService`) 用 `ReadWriteLock` 保证并发查询/原子更新

> 连续模式（未分页）生成**一个** `SystemNode`（`systemIndex=0`），整谱即一个 System。
> 分页模式下 `ScoreSpatialAdapter.buildPaginatedIndex` 为 `UnifiedLayoutResult.systems` 中**每个系统行各生成一个 `SystemNode`**：谱表 Y 区域取该行已加 `yOffset` 的 `staffLayouts`，小节边界用该行对齐（justify）后的小节线 X（`barlineLayouts` 按 `measureNumber` 取，首小节左界为 `lineStartX`）。命中富化里的全局 `measureIndices` 在分页下失效（各行共享同一 X 区间），故按命中框 Y 落到对应行的 Y 带、再用该行本地小节边界重新解析。

## 1a. 命中数据与渲染内容的一致性（单一真相源）

**索引随 `RenderResult` 走，不再是引擎里的可变字段。** `RenderResultAssembler` 构建的 `HierarchicalSpatialIndex` 会并入它产出的 `RenderResult`（`spatialIndex` + `transformerSnapshot` 两个不可变字段），并提供 `RenderResult.hitTest(absolutePoint)`。

UI（`RenderedScoreView`）对**当前显示的那个 `RenderResult` 值**做 hitTest：

```kotlin
val hitResult = renderResult.hitTest(point)   // 绘制与命中同源、原子替换
```

为什么这样：绘制用的 `RenderResult` 是值快照（`produceState` 持有），若索引另存于可变引擎字段，则存在「索引已换新、画面仍旧」的窗口，以及最新优先取消时「画面旧、索引半新」的不一致。把索引并入同一个值后，`produceState` 替换 `value` 是全有或全无，结构上消除了二者错位。

`RenderEngine.getHitTestService()` 仍在每次渲染后更新（供非 UI / 测试调用），但 UI 不再读它。

## 2. 核心类型

| 类型 | 职责 |
|------|------|
| `HittableElement` | 接口：`boundingBox` + `intersect(localPoint)` |
| `ScoreHittableElement` | 携带 `sections: List<EventSection>` 的领域包装 |
| `HittableRegistration` | 引擎侧富化：含 `staffIndex / measureIndices / sections` |
| `BPlusTree` | 带聚合器（Aggregator）的 B+ 树，支持前缀和定位 |
| `StaffRegion` | 谱表 Y 区段（允许重叠） |
| `MeasureStaffCell` | 小节×谱表单元格，按 X 排序线性扫描 |
| `SystemNode` | 一个系统行：组合上述结构 |
| `HierarchicalSpatialIndex` | 顶层：系统列表 + Y 二分查找 |

## 3. 构建（渲染时）

```
RenderEngine.renderUnified(layout)
  ├── 预计算 measureBoundaries（来自 barlineLayouts）
  ├── 各 element.render() → ElementRenderOutput
  │       (renderElements, sectionRegistrations, hitAreas)
  ├── RenderElementCollector.collect(output, staffIndex):
  │     - 从 sectionRegistrations 取 sections
  │     - 从 renderElements 取类型
  │     - findOverlappingMeasureIndices → measureIndices
  │     - 组装 HittableRegistration
  └── RenderResultAssembler
        └── ScoreSpatialAdapter.buildIndex(layout, boundaries, registrations)
        └── HierarchicalSpatialIndex
              └── HitTestService.updateIndex(...)   ← 写锁
```

## 4. 查询（用户点击）

```
tap (Compose) → AbsolutePoint
   │  HitTestService.hitTest(absolutePoint)        ← 读锁
   ▼
CoordinateTransformer.toRelative
   ▼
SystemNode.query(relativePoint):
   ├── measureTree.findByPrefix(x - startX)        [O(log M)]
   ├── staffRegions.filter { containsY }           [O(S)]
   └── cell[m][s].query(localPoint)                [线性扫描]
   ▼
HitTestResult.allSections() → List<EventSection>
```

UI 侧（`RenderedScoreView`）通常按类型过滤，例如：

```kotlin
val voice = hit.allSections().filterIsInstance<VoiceEventSection>().firstOrNull()
onSelectEvent(voice?.event?.id)
```

空白处点击整小节时，`RenderResult.measureStaffAt` 使用谱表中心线上下 2 个 staff space
（五线谱最外两线之间）的范围；音符、符干、加线等元素命中仍使用 `StaffRegion` 的完整内容范围。
因此收窄小节选择响应不会改变外伸音符的精确点击，也不会改变整小节选中后的绘制范围。

## 4a. 区域查询（框选 / marquee）

点查询的对偶：`queryRegion(rect)` 收集**包围盒与矩形相交**的所有元素，支撑左侧「框选」工具的拖动选区。算法与点查询同构，逐层把矩形裁剪到候选单元格的本地坐标系：

```
marquee drag (Compose) → AbsoluteRect（全局像素）
   │  RenderResult.hitTestRegion(rect, types?)     ← 无锁，查 RenderResult 自带索引
   ▼
transformerSnapshot.toRelative(两角) → 归一化 RelativeRect
   ▼
HierarchicalSpatialIndex.queryRegion(rect):
   ├── 跳过 locked / Y 带不相交的 SystemNode
   └── SystemNode.queryRegion(rect):
         ├── 只遍历 Y 带相交的 StaffRegion          [O(S)]
         ├── 只遍历 X 跨度相交的小节（前缀和）       [线性，小节数有限]
         └── cell[m][s].queryRegion(localRect)       [包围盒 overlaps 线性扫描]
   ▼
去重（按 elementId）→ List<ScoreHittableElement>
```

要点：

- **重叠用包围盒**（`RelativeRect.overlaps`），不做 `intersect` 的逐形状精修——擦到字形框即选中，符合框选直觉。空矩形（点击未拖动）`overlaps` 恒 false，故纯点击不会误选，交给点查询处理。
- **可配置范围**：`hitTestRegion(rect, types)` 的 `types: Set<RenderElementType>?` 即框选范围。UI 默认传 `{NOTEHEAD, REST}`（仅音符/休止符），不同功能可传不同集合；`null` 表示不过滤。范围过滤在 `RenderResult` 层完成，索引层只管几何。
- **单一真相源**：与 `hitTest` 一样查 `RenderResult` 自带的不可变索引，框选结果与画面同源、无锁。
- **分页**：全局索引为单一坐标空间，分页下 UI（`RenderedScoreView`）逐页把设计空间选框裁剪到各页槽位、经 `designToGlobal` 映射成该页全局矩形后分别 `hitTestRegion`，再并集——故跨页框选正确。

## 5. 跨小节 / 跨谱表元素

- 小节线：在每个相关谱表的单元格各存一份
- 大括号 / 系统括号：存入所有谱表
- 缺失 `staffIndex` 的元素：默认存入所有谱表

这样无论用户点到哪个 staff，都能命中同一逻辑元素。

## 6. 线程安全

- `RenderResult.hitTest()`（UI 主路径）**无需加锁**：每个 `RenderResult` 持有一份此后不再变更的索引实例（`query` 仅读，`lockSystem` 等可变操作 UI 不调用），值替换由 Compose 单值原子完成。
- `HitTestService`（非 UI / 测试路径）保留 `expect class ReadWriteLock`（JVM 委托 `ReentrantReadWriteLock`）：`hitTest()` 读锁并发查询，`updateIndex()` 写锁独占交换，并接收 `transformer.copy()` 防止读锁内视口变换被外部并发修改。

## 7. 性能要点

| 操作 | 复杂度 |
|------|--------|
| `BPlusTree.findByPrefix(x)` | O(log M) |
| `staffRegions.filter` | O(S)，S 通常 1–4 |
| 单元格内扫描 | 元素数通常 < 10 |
| 系统行二分 | O(log S_n) |

整条查询基本 O(log N + 常数)，足以支撑实时拖动选区。
