# 乐谱排版几何（ScoreGeometry）

> 实现：`api/.../storage/ScoreGeometry.kt`  
> 消费：`renderer/.../render/GeometryProjector.kt`

`StorageScore.geometry` 是 Storage 层的可选排版覆盖。音乐语义仍由 `TieInfo`、
`StorageSlurEvent` 等字段表达；几何只负责重现或编辑显示形状。

## Tie / slur

- `ties: Map<EventId, List<TieGeometry>>`：键为源 `StorageVoiceEvent.id`，列表按
  `sourcePitchIndex` 区分和弦内的多条 tie。
- `slurs: Map<EventId, SlurGeometry>`：键为稳定的 `StorageSlurEvent.id`。
- 端点以锚定音头中心为原点，单位为 staff space；音符移动或水平排版改变后重新解析锚点，
  无需重算未受影响曲线。
- `minApex` / `maxApex`、`slopeDamping`、`middleStraightening` 描述弧形。
- `directionLocked` 表示方向由用户或导入文件指定；`manuallyAdjusted` 表示曲率由用户或
  MusicXML Bézier 信息指定。增量失效不能静默丢弃这两类所有权。
- `autoEndpoints` 保留自动音头/符干端点，同时叠加导入的方向、相对偏移和弧高。

同一系统内自动排版的曲线会被捕获为稳定几何。跨系统曲线由各系统 stub 自动排版，不保存
一组易受换行影响的绝对端点。

## Tuplet

- `tuplets: Map<EventId, TupletGeometry>`：键为携带 `TupletSpan` 的首事件 id。
- `above` 保存当前符号侧别；`directionLocked=false` 是自动捕获缓存，renderer 仍按组内第一个
  实际符杆重新判定，`directionLocked=true` 才是用户在属性面板设置的持久排版指令。
- Tuplet 端点继续由成员符杆/休止符范围自动求解，不保存易受音符间距变化影响的绝对坐标。

## 增量约束

Tie 源由 `ComputedEventStore.tieSourceEventIds` 持久索引；局部失效通过小节 B+ tree
范围查询候选。几何方向/曲率编辑复用当前 `ComputedScore`，只重绘源—目标覆盖的小节。
几何 Map 使用持久映射做单键 patch，不复制或遍历整份 overlay。
