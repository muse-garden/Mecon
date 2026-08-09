# Beam 几何与编辑

## 几何来源

beam 的连杠关系和层数来自 Computed 层；Renderer 只根据 `BeamInfo` 排版。beam 端点 Y 属于可持久化的几何 overlay，存储在 `StorageScore.geometry.beams`，key 是稳定的 `BeamGroupId.value`。

手工或 MusicXML 导入的连杠边只在两个事件时间连续（前一事件的 `endTime` 等于后一事件的 `onset`）时成立；beamable 事件列表中的相邻项不能跨休止或其他时间空隙连接。

普通 beam 的 `startDy/endDy` 相对于所属谱表中线保存；跨谱表 beam 的端点偏移相对于所选基准线上的 beam 基准线保存。当前谱表中心或横向 X 变化时，Renderer 先恢复端点坐标，再根据当前端点 X 计算斜率并同步调整符干。

跨谱表 beam 还保存 `CrossStaffBeamBase`：

- `TOP_STAFF_MIDLINE`：以上谱表中线为基准；
- `BOTTOM_STAFF_MIDLINE`：以下谱表中线为基准；
- `BETWEEN_STAFFS`：以 `betweenStaffUpperIndex/betweenStaffLowerIndex` 指定的两条相邻谱表中线正中位置为基准。

跨三个或更多谱表的 group 受支持。自动排版会选择跨越范围中央、与 stem inward 分组一致的相邻谱表间隙；拖动时可在最上谱表中线、覆盖范围内每一对相邻谱表的中点、最下谱表中线之间重定位。旧文件若没有具体谱表对，则按当前覆盖范围推导中央相邻谱表对，并在下一次几何捕获时补齐。

`crossStaffOffset` 始终以谱线距离为单位。拖动后选择偏移量绝对值最小的基准，避免谱表间距变化时产生不必要的大偏移。

`BeamGeometry.manuallyAdjusted` 区分用户拖动值与自动捕获值。手工 beam 几何在普通音高或位置编辑后保持稳定；只有 beam 的成员/时间范围发生变化时才丢弃。若手工 beam 侵入最小净空，Renderer 保留其斜率并只做最小整体外移；若 beam 中线越过组内音符中心，则直接翻转整组符干方向及左右附着点，不回退到自动 beam 排版。自动捕获值仍可随普通排版重算，避免增量渲染与全量排版分叉。geometry-only 提交会显式覆盖上一帧捕获的 live geometry，并只刷新该 beam 覆盖的小节。

## 交互

beam 主体注册为 `VoiceBeamSection`，可以直接启动整体拖动；不依赖音符、符干和 beam 之间的普通选择命中优先级。

多层 beam 额外注册一条沿 beam 斜率变化的隐形命中带，覆盖各层符杠及层间空隙；该命中带没有绘制命令，不进入排版 bounds 或 snapshots。

空间索引的 system/staff Y 粗筛范围会合并 beam 的实际 hit bounds，而不只使用谱表 content extents；因此手工拖到谱表很远处的 beam 仍归属原 system/staff，并能进入精确命中检测。

端点控制点只属于桌面编辑 UI：根据当前选中 beam 的首尾符干尖端派生，仅选中时绘制蓝色空心方块。它们不是 `RenderElement`，不进入排版 bounds、分页、空间索引或 renderer snapshots。UI 使用独立命中半径识别端点拖动。

未选中 beam 也可在第一次按下后直接拖动主体或端点：UI 在指针附近做小范围 beam 查询并即时派生控制点，不需要先单击选中。端点命中半径小于控制点的视觉范围；短 beam 还会按长度收缩两端命中半径，保证中部始终保留整体平移区域。

- 拖动主体：普通 beam 的两个端点平行移动；cross-staff beam 移动共同基准线并重选最近的稳定基准，不重复叠加端点偏移；
- 拖动端点：只改变对应端点；
- 释放后通过 `ScoreSession.applyBeamGeometry` 写回文档几何，不改变音符或连杠关系。

增量渲染仍以新的 Computed beam 关系为准；几何 overlay 只影响端点位置，参见 [incremental-rendering.md](incremental-rendering.md)。
