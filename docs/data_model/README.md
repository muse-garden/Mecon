# 数据模型 (Data Model)

> Mecon 数据模型采用四层架构，从持久化到渲染逐级展开。

## 1. 四层架构总览

```
Storage   →   Runtime   →   Computed   →   Render Geometry
(持久化)      (内存查询)     (派生计算)        (绘制几何)
  YAML       BPlusTree      ID-引用         StaffSpace/Pixels
```

| 层级 | 职责 | 主要数据结构 | 字段类型 |
|------|------|-------------|---------|
| **Storage** | 序列化、版本管理 | 不可变树 + ID 引用 | 仅源字段 |
| **Runtime** | 时间索引查询 | `TimeIndexedList` (BPlusTree) + 对象引用 | 仅源字段 |
| **Computed** | 音乐逻辑计算 | `ComputedEventStore` (双 B+ 树) + 标注事件 | 源字段 + 派生字段 |
| **Render Geometry** | 视觉布局 | `UnifiedLayoutResult` + 几何图元 | 坐标、尺寸 |

每一层只增不减地携带数据：上层基于下层的内容衍生，最终送往 `RenderEngine`。

## 2. 文档索引

| 文档 | 内容概要 |
|------|---------|
| [primitives.md](primitives.md) | 基础类型（`Fraction` / `TimeCode` / `Pitch` / `Duration` / `Interval`） |
| [harmony.md](harmony.md) | 和声求解：调号/调性/和弦正交模型、拼写音高、任意固定声部与用户优先级 |
| [free-practice.md](free-practice.md) | ✅ 自由练习 schema v8：双谱表、写作设置、惯用进行、逐和弦调性与记谱来源 |
| [chord-construction-and-interpretation.md](chord-construction-and-interpretation.md) | 🚧 多阶段迁移；R4B 功能替代、动态构造/谱例契约已实施 |
| [storage.md](storage.md) | 存储层：`StorageScore` / 各类 `Storage*Event` / 序列化策略 |
| [score-geometry.md](score-geometry.md) | Tie / slur 等锚点相对排版几何、用户覆盖与增量失效 |
| [runtime.md](runtime.md) | 运行时层：`RuntimeScore` / `TimeIndexedList` / 对象引用模型 |
| [computed.md](computed.md) | 计算层：`ComputedScore` / `ComputeEngine` / 派生字段 |
| [incremental-compute.md](incremental-compute.md) | 🚧 增量计算与依赖追溯（设计阶段） |
| [incremental-update.md](incremental-update.md) | ✅ 乐谱局部更新（Core 增量计算契约；renderer 细节另文） |
| [computed-event-store.md](computed-event-store.md) | ✅ `ComputedEventStore`：计算事件改用持久化 B+ 树（已实现） |
| [score-format.md](score-format.md) | 单乐谱 YAML/JSON 文件格式约定 |
| [mecon-container.md](mecon-container.md) | `.mecon` 容器（zip：多乐谱 + 模块 + 冻结几何） |
| [musicxml.md](musicxml.md) | MusicXML 互操作 |

## 3. 关键设计决策

### 3.1 PitchEvent 与 VoiceEvent 分离

`PitchEvent` 只承载音高数据（和弦 = 多个 `Pitch`，休止 = 空列表）；
`VoiceEvent` 1:1 引用一个 `PitchEvent`，并携带 `duration`、渲染信息、`ties` 等声部专属属性。

**优势**：同一组音高在不同声部的渲染可独立调整（符干方向、连音线），而音高数据不会冗余。

### 3.2 Pitch 保留等音异写

`Pitch(diatonicSteps, chromaticOffset)` 同时存储自然音级与半音偏移，所以 `F#` 与 `Gb`、`B` 与 `Cb` 可以正确区分，不会在 MIDI 转换中丢失谱面信息。

### 3.3 显式延音线

`VoiceEvent.ties` 是 `List<TieInfo>`，每个 `TieInfo(pitchIndex, targetEventId)`：

- `pitchIndex` 指向当前事件第几个音高，支持**和弦部分延音**
- `targetEventId == null` 表示 **let-ring**（无目标延音）

### 3.4 计算层全量物化

`ComputeEngine` 一次重算所有派生字段（临时记号、符杠分组、Staff 位置、MIDI 音高），结果物化进持久化 [`ComputedEventStore`](computed-event-store.md)（measure / EventId 双 B+ 树）。下游（渲染、播放）无需再次推导；增量编辑只接触 O(log N) 个节点，其余子树按引用复用。

> 🚧 增量计算与 `DependencyScope` 见 [incremental-compute.md](incremental-compute.md)。

## 4. 模块映射

四层模型大部分由 `:api` 模块导出，`:core` 提供计算引擎实现：

```
api/
├── primitive/        # Fraction, TimeCode, Pitch, Duration, ...
├── storage/          # StorageScore, StorageEvent, StorageTrack
├── runtime/          # RuntimeScore, TimeIndexedList, RuntimeEvent
├── computed/         # ComputedScore, ComputedTypes, CalcBuilder
└── state/            # ScoreState, ScoreStateManager, GlobalScoreState

core/
└── engine/
    └── ComputeEngine.kt   # Storage → Runtime → Computed 全量计算
```

外部插件应仅依赖 `:api`，避免与 `:core` 内部耦合。
