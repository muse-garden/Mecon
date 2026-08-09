# ComputedEventStore — 计算事件的持久化存储

> ✅ 已实现（2026-06-09）。把 `ComputedScore.computedEvents` 从 `Map<EventId, ComputedVoiceEvent>`
> 换成持久化 B+ 树支撑的 `ComputedEventStore`，消除增量更新时的整表拷贝。
> 实现：`api/.../computed/ComputedEventStore.kt`，测试 `ComputedEventStoreTest`。
> 关联：[incremental-update.md](incremental-update.md) · [computed.md](computed.md) · [runtime.md](runtime.md)

## 1. 动机

当前 `computedEvents` 是 `HashMap<EventId, ComputedVoiceEvent>`。增量重算时
`IncrementalComputeEngine` 做 `previous.computedEvents.toMutableMap()` —— **每次编辑都 O(N) 拷贝整表**，
与"局部更新"的初衷矛盾。

Runtime 层早已用持久化 `TimeIndexedList`（`BPlusTree` 支撑，结构共享，`put`/`remove` 为 O(log N)）。
Computed 层应当对齐：一次编辑只接触 O(log N + k) 个节点，其余子树按引用复用。

## 2. 访问模式（决定为何用双索引）

`computedEvents` 同时承担两种访问：

| 访问 | 调用点 | 频度 |
|------|--------|------|
| **按 EventId 点查** | `getComputedEvent`、`getTiedChains` 的 tie 链遍历、`applyNoteStyleProviders`、增量合并分类 | 热（tie 链逐链点查） |
| **按 measure / onset 范围 + 有序迭代** | `eventsInMeasure`、`allEventsSorted`、`getBeamGroups`、增量窗口的 range/删除检测、Tie/Tuplet 计算器迭代 | 热 |

单棵树无法同时把两者都做到 O(log)：
- 仅 measure-keyed → EventId 点查退化 O(N)（伤 tie 链遍历）。
- 仅 EventId-keyed → measure/range 退化 O(N)（伤 `eventsInMeasure`、增量窗口）。
- 渲染的 `eventsInMeasureRange` 为保留首拍前的负 grace 分量，会从目标窗口前一 measure 边界开始 B+ range，再按
  `onset.measure` 精确过滤；避免以 `TimeCode.ofMeasure(first)` 为下界漏掉属于 first measure 的 leading grace。

故采用**双持久化索引**，二者同步更新，均 O(log N)，无整表拷贝。

## 3. 类型设计

新增 `api/src/commonMain/kotlin/com/mecon/api/computed/ComputedEventStore.kt`：

```kotlin
class ComputedEventStore private constructor(
    private val byMeasure: TimeIndexedList<ComputedVoiceEvent>,        // measure-keyed，range/有序迭代
    private val byId: BPlusTree<EventId, ComputedVoiceEvent, Int>,     // EventId-keyed，O(log) 点查
    private val tieSourceIds: PersistentSet<EventId>,                  // 增量维护的 tie 源索引
) {
    val size: Int
    fun isEmpty(): Boolean

    operator fun get(id: EventId): ComputedVoiceEvent?                 // byId.get
    fun getValue(id: EventId): ComputedVoiceEvent                      // 缺失抛 NoSuchElementException
    operator fun contains(id: EventId): Boolean

    val values: List<ComputedVoiceEvent>                              // byMeasure.toList()，onset 有序
    val ids: Set<EventId>
    val tieSourceEventIds: Set<EventId>                               // 无需全谱扫描即可枚举 tie 源

    fun put(event: ComputedVoiceEvent): ComputedEventStore            // 持久化；先按 byId 取旧值定位旧 onset
    fun remove(id: EventId): ComputedEventStore                       // 持久化
    fun range(start: TimeCode, end: TimeCode): List<ComputedVoiceEvent>
    fun inMeasure(measure: Int): List<ComputedVoiceEvent>

    companion object {
        val EMPTY: ComputedEventStore
        fun of(events: Iterable<ComputedVoiceEvent>): ComputedEventStore
    }
}
```

### 3.1 `put` / `remove` 的双索引同步

`put(e)`：用 `byId.get(e.id)` 取旧实例 → 若存在则 `byMeasure.remove(old)`（`===` 身份删除，旧实例即来自
`byId`，安全）→ `byMeasure.insert(e)`；同时 `byId.put(e.id, e)`。
onset 变化（移动音符）时旧值在旧小节、新值在新小节，自然处理。

> ⚠️ **值相等 → no-op（双索引一致性不变量）**：`put` 开头若 `old != null && old == e`（内容相等）必须直接
> `return this`，**不可**走 remove+insert。否则 `byId.put` 因值相等返回 `Unchanged`（**保留旧实例**），而
> `byMeasure` 已换成新实例——两索引对同一 id 持有**不同实例**而发散；下一次该 id 的 `put`/`remove` 做 `===`
> 身份删除时在 `byMeasure` 里找不到旧实例 → 删除失效 → **`byMeasure` 出现重复事件**（`values.size > size`，
> 渲染重复音符）。链式增量重算（相邻编辑的 BACK=1 窗口重叠，反复 re-put 未变事件）会触发；回归测试见
> `ComputedEventStoreTest.putValueEqualThenChangedDoesNotDuplicate`。

`put/remove` 同时按 `event.hasTies` 更新持久化 `tieSourceIds`。`TieLayoutComputer` 的完整与增量
排版都从该索引或小节 B+ tree 范围查询取得候选，不再通过 `computedEvents.values` 扫描全谱寻找 tie。

`remove(id)`：`byId.get(id)` 取旧实例 → `byMeasure.remove(old)` + `byId.remove(id)`。

> `byId` 树不需要聚合器（仅 get/put/remove + `BPlusTree.size`），构造时 `aggregator = null`，
> 类型参数 `A` 取 `Int` 占位。

### 3.2 值相等语义（关键）

`ComputedScore` 是 `data class`，黄金法则测试用
`assertEquals(computeScore(edited).computedEvents, inc.computedEvents)` 比较。
故 `ComputedEventStore` 必须实现**按内容**的 `equals`/`hashCode`（与树形无关）：以 `id → event` 映射比较。
两个含相同事件集合的 Store 相等，无论插入历史/树结构如何。

### 3.3 `EventId : Comparable<EventId>`

`byId` 的键需有序。在 `api/.../primitive/Ids.kt` 给 `EventId` 加
`: Comparable<EventId>` 并委托 `value.compareTo(other.value)`。`TrackId`/`ScoreId` 暂不动。

## 4. 迁移点

| 文件 | 改动 |
|------|------|
| `primitive/Ids.kt` | `EventId` 实现 `Comparable` |
| `computed/ComputedScore.kt` | 字段类型 `Map → ComputedEventStore`；`getComputedEvent`/`eventsInMeasure`/`allEventsSorted`/`getBeamGroups`/`getTiedChains` 改用 Store API（语义不变） |
| `core/engine/ScoreComputer.kt` | 构造时 `computedEvents = ComputedEventStore.of(...)` |
| `core/engine/ComputeEngine.kt` | `compute()` 产出改为 `ComputedEventStore`（内部仍可先建 map 再 `of`） |
| `core/engine/IncrementalComputeEngine.kt` | **核心收益**：去掉 `toMutableMap()`，改为在 Store 上持久化 `put`/`remove`；旧窗口事件删除走 `remove` |
| `renderer/.../RenderEngine.kt`、`TieLayoutComputer.kt`、`TupletLayoutComputer.kt` | `computedEvents.values` / `[id]` API 保持可用，无需逻辑改动 |
| 插件 `ChordCompute.kt` 及 `chord` 测试、`api` 状态测试、`core` 测试 | `associateBy { it.id }` → `ComputedEventStore.of(...)`；`emptyMap()` → `ComputedEventStore.EMPTY` |

## 5. 迭代顺序与快照（评审已确认：重生成并校验）

`HashMap.values` 顺序是按哈希的"任意"顺序；`ComputedEventStore.values` 改为 **onset 有序**（更确定）。
`TieLayoutComputer` / `TupletLayoutComputer` / 发音记号计算器按 `.values` 顺序产出元素，
而 `RenderSnapshotVerifyTest` **逐下标**比较元素列表 —— 含 tie/tuplet 的乐谱金标快照会因元素列表重排而失配。

处理：用 `GenerateSnapshotsTest` 重生成金标，再 `git diff` 确认改动**纯属元素列表重排**
（元素集合与每元素命令集合不变；`commandOrderNormalized` 已消除元素内命令顺序差异）。
一次性把元素顺序确定化（onset 有序），后续渲染稳定可复现。

> 若 diff 出现任何非重排的内容差异 → 说明重构有 bug，需排查，不得直接覆盖金标。

## 6. 单元测试要点

**`ComputedEventStoreTest`（api，新增）**
- `of` 后 `get`/`getValue`/`contains`/`size`/`ids` 正确；缺失 `get` 返回 null、`getValue` 抛异常。
- `values` 为 onset 有序；跨小节、同 onset 多事件（不同 track）均稳定。
- `put` 新增 / `put` 覆盖同 id（同 onset 与改 onset 两种）/ `remove`：结果内容正确，且**原 Store 不被修改**（持久性）。
- `put`/`remove` 后 `byMeasure` 与 `byId` 一致（同一 id 两索引取到同实例）。
- **结构共享**：`put` 后 `===` 比较，未受影响的事件实例保持同引用（与原 Store 共享）。
- 值相等：内容相同、构造历史不同的两个 Store `==` 且 `hashCode` 一致；差一个事件则不等。
- `range` / `inMeasure` 边界（左闭右开、跨小节）。

**回归（已存在，验证不被破坏）**
- `IncrementalComputeEngineTest` 全部 12 例 + fuzz：黄金法则 `inc == full` 不变。
- `RenderIncrementalParityTest`：inc 与 full 渲染逐命令一致（双方同序，仍成立）。
- `RenderSnapshotVerifyTest`：重生成金标后通过。
- `ChordTone*` / `ScoreStateManagerTest`：构造 API 迁移后通过。

**增量性能（可选断言）**
- 在大谱（多小节）上做一次点编辑，断言 Store 与原 Store 共享绝大多数子树（结构共享计数）——
  作为"无整表拷贝"的回归守卫。

## 7. 落地顺序

1. `EventId : Comparable` + `ComputedEventStore` + `ComputedEventStoreTest`（api，独立可测）。
2. `ComputedScore` 字段切换 + 内部方法改写。
3. `ScoreComputer` / `ComputeEngine` 构造端迁移。
4. `IncrementalComputeEngine` 改持久化 `put`/`remove`（核心收益）。
5. 渲染端与插件/测试构造端迁移。
6. 跑 `:core:jvmTest`、`:renderer:jvmTest`；重生成快照并 `git diff` 校验。
7. 同步更新 `computed.md`、本目录 README 表格中"Computed 层主要数据结构"。

## 7a. 结构化 diff（两棵持久化 B+ 树比较）✅ 2026-06-11

持久化 B+ 树的杀手锏不止「写时 O(log N)」，还有**比较时也能 O(变化量·log N)**：两棵由少量 `put`/`remove`
互相派生的树共享除「编辑路径」外的全部子树，比较时遇到**引用相等（`===`）的子树整棵跳过**。

- **`BPlusTree.diff(newer, onRemoved, onAdded, onChanged)`**（`api/.../collection/BPlusTree.kt`）：
  接收者为旧树、`newer` 为新树。节点级递归：
  1. `old === new` → 整子树相同，跳过（核心收益）。
  2. 双方均叶 → 按键归并，值 `!== && !=` 才算 changed。
  3. 双方均内部且**分隔键相等**（非分裂/合并的 put/remove 后的形状）→ 子节点按下标对齐递归（共享子节点在第 1 步即返回）。
  4. 形状分叉（分裂/合并改了分隔键，或高度不同）→ 退化为把两子树各自**展平为有序条目**再归并；因兄弟子树已在上层被 `===` 跳过，此回退局限在分叉子树内。
  正确性由 `BPlusTreeDiffTest` 的随机模糊（对照暴力 map diff，order 3–8，含跨分裂/合并）守护。
- **`ComputedEventStore.diffFrom(previous)` → `EventStoreDiff`**：在 `byId` 树上跑 `diff`，产出 added/removed/modified 的
  `EventId` 集与受影响**小节区间**（取新旧 onset 并集，移动音符覆盖源与目的）。
- **`computeChangeSetBetween(previous, current)`**（`api/.../computed/IncrementalCompute.kt`）：用上面的事件 diff 组装
  `ComputeChangeSet`，并比对记号列表（barlines/clefs/keys/time，`===` 快路径）与小节数 → 任何记号/结构差异置位
  `notationChanged`/`structureReflow`，使渲染端退回全量布局。

**消费方**：① 渲染端 `RenderEngine.renderIncremental` 的谱系守卫——谱系未命中（合并跳帧 / 撤销重做）时用它现场算出
「显示帧 → 新 computed」的真实 changeSet，使这些场景**仍走增量**（见 [incremental-update.md](incremental-update.md) 与 [../ui/score-editing.md](../ui/score-editing.md)）。
② 撤销/重做天然受益（同一守卫路径）。

## 8. 范围说明

本方案只改 `computedEvents` 的存储与增量写入路径，**不**触及 `renderIncremental` 对
`changeSet` 的消费（局部渲染快路径"A"，仍依赖未建的 reuse-X 接缝，见
[incremental-update.md](incremental-update.md)）。二者解耦，先做本项。
