# 力度记号与谱表附着符号（Dynamics & Staff Attachments）

> 状态：✅ 基本可用（力度字符、渐强渐弱箭头、cresc./dim. 文字+虚线、纵向占位避让、8va/8vb 括号）

力度记号、渐强渐弱属于"画在谱表上、不在音符流里的额外符号/文本"。渲染层把这类元素抽象为
**谱表附着符号**，使后续其它符号（踏板、八度记号、表情文字…）可复用同一管线。

## 1. 抽象结构

| 层 | 类型 | 职责 |
|----|------|------|
| 数据 | `ComputedStaffAttachment`（`ComputedDynamicMark` / `ComputedHairpin` / `ComputedOctaveShift`） | 决定"画什么 + 在哪条谱表"，带 `staffIndex` / `placement` |
| 几何 | `DrawableGeometry`（`GlyphGeometry` / `HairpinGeometry` / `IntervalAttachmentGeometry`） | 自包含的可绘制几何，提供 `bounds` 与 `draw(offset, transformer)` |
| 布局 | `StaffAttachmentLayoutComputer` → `PlacedStaffAttachment` | 解析锚点 X、纵向分层堆叠、计算每条谱表的额外占位 |
| 渲染 | `StaffAttachmentElement`（`RenderableElement`） | 把几何转成 `RenderElement` + `StaffAttachmentSection`（可拾取） |

**区间符号统一抽象（`IntervalAttachmentGeometry`）**：除渐强渐弱**箭头**（wedge）外，凡可表达为
"一条水平线 + 两端内容"的跨度符号（八度括号、cresc./dim. 文字虚线、踏板、accel./rit. …）都共用
同一套渲染几何，由两个参数描述，无需为新符号新增渲染代码：

- **水平线样式** `SpanLineStyle`：`SOLID`（实线）/ `DASHED`（虚线）
- **两端内容** `SpanEnd`（各端独立）：`None`（无内容）/ `Text`（文字标签）/ `Hook`（一小段收口竖线）

新增一种附着符号：
- 区间型（线+两端内容）→ 新增 `ComputedStaffAttachment` 子类，在 `StaffAttachmentLayoutComputer`
  中用 `intervalRaw(...)` 配好 `SpanLineStyle` + 两端 `SpanEnd` 即可，复用 `IntervalAttachmentGeometry`。
- 非区间型（如新的字形符号）→ 新增子类并在 `buildRaw` 里产出对应 `DrawableGeometry`。

## 2. 力度字符（DynamicMark）

`DynamicGlyphs.glyphsFor(level)` 把 `DynamicLevel` 直接映射为**单个 SMuFL 字形**；复合力度使用预组合字形。
复合字形从 U+E527 起（如 `dynamicMF` U+E52D、`dynamicFF` U+E52F）。`niente` 使用 `dynamicNiente`。
Bravura 字体已内置复合字形的正确字母间距，不需要手动拼接基础字母。
当前面板覆盖 `pppppp`–`ffffff`、`niente`、`mp/mf/pf/fp`、`sf/sfp/sfpp/sfz/sfzp/sffz`、
`fz/rf/rfz` 全部对应的 SMuFL 力度字形。

## 3. 渐强渐弱（Hairpin）

两种 `HairpinStyle` 走**不同**几何：

- `WEDGE`：`HairpinGeometry` 手工绘制两条向开口端收敛的直线（即"箭头"）。CRESCENDO 起点收拢、
  终点张开；DIMINUENDO 相反。跨行时各段携带插值后的 `startSpread` / `endSpread`，使开口在断行处连续。
- `TEXT_DASHED`：`cresc.` / `dim.` 斜体文字 + 虚线延续，属于通用区间符号，复用
  `IntervalAttachmentGeometry`（`startContent = Text`、`endContent = None`、`lineStyle = DASHED`）。

`StorageHairpin.endOnset` 决定终点时间码；锚点 X 取自 `UnifiedTimeSlotMap`，找不到终点槽时回退到固定长度。

## 4. 纵向占位与避让

布局在**水平 X 已定、谱表 Y 未定**之间运行。这里有**两个相互独立的纵向量**，刻意分开计算：

| 量 | 来源 | 粒度 | 作用 |
|----|------|------|------|
| 记号绘制基线 `bandTopY` | `NoteExtentIndex.localExtent(...)` | **局部**（记号横向跨度 ± 邻域） | 决定记号画在哪 |
| 谱表间距预留 `AttachmentExtent` | `noteExtents`（全局/逐行） | **整行该谱表** | 决定上下谱表间距，保证不重叠 |

1. **局部锚定（绘制位置）**：每个记号的 `bandTopY` 取**自身横向跨度 `[xStart, xEnd]` 加邻域
   `localExtentMargin`（±1.5 staff space）内**的音符纵向范围，由 `NoteExtentIndex` 提供。
   这样行首一个极高/极低的音符**不会**再把整行所有记号顶出去——记号只受它"坐在上面"及紧邻的音符影响。
   这同时把单次编辑的影响半径收敛到其时间邻域，是增量重排可行的前提。
   - **跨行 span 例外**：渐强渐弱/八度等跨分行的 span，其归一化 `[xStart, xEnd]` 会撑满整条
     justified 带（终点绕到下一行，切分前 end < start）。直接按此采样会取到箭头**左侧空白区**里的
     最低音、把记号压得过低。故跨行 span 改按其在**起始行的真实绘制区间**（真起点 X 向右至行尾）
     采样局部音符范围。垂直位置在 `SystemBreaker.clipAttachment` 按行切分前即算定，切分只改 X/spread 不改 Y。
2. **全局预留（间距）**：谱表间距预留仍按**整行该谱表**的全局音符范围
   （`StaffLayoutComputer.calculateExtents`，分页模式下逐行）算 `reach = bandTopY + height − globalBottom`。
   `stackStaves` 在全局 extent 之上叠加该预留，故即便单个记号贴近局部音符、间距也始终为最坏情况留足空间；
   `reach ≤ 0` 表示记号已落在全局 extent 内，无需额外空间。两值口径一致，谱表永不重叠。
3. 按 `placement` 分上/下两侧，对水平重叠的符号做贪心分行堆叠（`packRows`，按 `systemIndex` 分组），避免互相重叠。
4. 产出 `AttachmentExtent(extraTop, extraBottom)`，键为 **(systemIndex → staffIndex)**
   （`StaffAttachmentLayoutResult.extents`）。分页时由 `SystemBreaker.applyAttachmentExtents` 折入**逐行**纵向堆叠，
   使每行谱表间距为本行力度记号留出空间；连续模式只有系统 0，`calculateStaffYPositions` 读 `extentsForSystem(0)`。

**`NoteExtentIndex`（局部 extent 索引）**：底层 `NoteExtentTree` 是两级持久化 B+ 树——外层按 measure 保存可
结构共享的 chunk，chunk 内按 staff/local-X 保存每音符纵向范围，并以 `max(top,bottom)` 聚合子树。
`localExtent(...)` 对查询范围调用 B+ `aggregateRange`，无音符时退化到裸五线谱 `(2, 2)`。连续与分页
re-stretch 共用同一棵持久树；当前 `UnifiedTimeSlotMap` 只生成 measure 的平移/缩放 transform，因此前序小节变宽或
system justification 不会重写未变 measure 的 keys。
每个不可变 `UnifiedTimeSlotMap` 同时惰性缓存该 transform 的 measure bounds；tree patch 与 pre/post-break 查询共享
这个中间结果，避免为同一 slot 快照重复执行 O(slots) 归约。

分页增量 placement 的 attachment 输入也按 measure 缓存：稳定分区下只展开 affected system 的 measure ranges，
从 `staffAttachmentsByMeasure` 取得候选；无需先对全谱力度/渐强/八度记号逐个做 `timeSlotMap.atTime` 过滤。跨 system
span 若碰到 affected/unaffected 边界仍回退全量 placement，避免漏算另一端。

`PlacedStaffAttachment.geometries` 的 Y 相对谱表中线存储；渲染时 `RenderEngine` 以
`offset = (0, staffCenterY)` 绘制，因此后续纵向重排不影响相对布局。

## 4.6 持久化几何（overlay，Phase 3a）

区间型附着符号（hairpin / 8va / 8vb）的几何可捕获进 `StorageScore.geometry`
（[../data_model/storage.md §1.3](../data_model/storage.md)，键 = `ComputedStaffAttachment.id`）：

- `GeometryProjector.toStored(PlacedStaffAttachment)` 把已排好的 `HairpinGeometry` /
  `IntervalAttachmentGeometry` 折成 `AttachmentGeometry`——**两端各存一份**
  （`startDx/endDx` 相对各自 onset 槽位 X、`startDy/endDy` 相对谱表中线）+ 楔形 `spread`。
  自动排版保持 hairpin **水平**（`startDy == endDy`）；手动端点编辑写入 `manuallyAdjustedY=true`，独立 Y
  在 undo/redo 与增量重排后仍保持。
- **增量失效**：自动 span 条目 stale ⟺ 其小节跨度与 `ComputeChangeSet.affectedMeasures` 相交（与现在的避让行为一致——
  区间符号要避让跨度内元素）；并按**纵向堆叠**向外**级联**（reshape 的 hairpin 会带动其外侧 8va/8vb 一起重算，
  自内向外）。`manuallyAdjustedY` 条目不因普通小节重算而丢弃；若与新音符或谱表范围碰撞，两个端点作最短共同 Y 位移。
  判定见 [incremental-rendering.md](incremental-rendering.md)。
- **overlay 驱动排版（Phase 3b-render）**：`StaffAttachmentLayoutComputer.compute(geometry=)` 对有条目的 span
  **以存储 Y 为权威**——`bandTopY = storedYCenter − height/2`，存储==自动时精确恒等、被改动时 honour 新位置；
  手动 hairpin 的 X 始终回到当前 TimeCode 的自动布局位置，Y 使用独立端点值；自动条目的 stale span 回退 reshape。
- 点状力度（dynamic，多字形非线段）暂不持久化，始终自动排版。

## 5. 拾取与样式

`StaffAttachmentElement` 注册 `StaffAttachmentSection(attachment)`，可独立选中力度记号 / 渐强渐弱 / 八度记号，
并接入既有的 `StyleOverrideManager`。`RenderElementType` 枚举值：`DYNAMIC` / `HAIRPIN` / `OCTAVE_SHIFT`。

## 6. 八度记号（8va / 8vb）

`ComputedOctaveShift` 由 `DynamicsComputer` 配对 `StorageOctaveShiftStart`（持有 `endEventId`）
与对应的 `StorageOctaveShiftEnd` 生成，携带 `shiftType`（`OTTAVA` / `OTTAVA_BASSA`）、跨度
`[time, endTime)` 及 `placement`（ABOVE = 8va，BELOW = 8vb）。

**区间约定**：音符效果范围为左闭右开 `[startOnset, endOnset)`，`endOnset` 处的音符不受移位影响。
视觉形态为**左开右闭**：左侧无竖线（仅 "8va"/"8vb" 文字），右侧有收口短线（钩）。
渲染时虚线终止于 `endOnset` 之前最后一个音符处（`UnifiedTimeSlotMap.lastBefore(endOnset)`），而非 `endOnset` 自身位置。

**几何**（通用 `IntervalAttachmentGeometry`，配置 `startContent = Text("8va"/"8vb")`、
`endContent = Hook`、`lineStyle = DASHED`）：
- "8va" / "8vb" 斜体文字标签，左边缘对齐起始音符符头左侧
- 水平虚线延伸至最后一个受影响音符右侧（`endOnset` 前一个槽的 X）
- 竖向收口短线（`Hook`）：ABOVE 时朝下，BELOW 时朝上

**布局**：复用 `StaffAttachmentLayoutComputer`（`buildOctaveShift()`），按行堆叠避让与力度记号互不重叠。

**音符位置修正**（非渲染层职责）：`ComputeEngine.octaveShiftDiatonicOffset()` 在 Computed 层
调整 `staffPosition`（±7 自然音阶步），渲染层直接读取已修正的坐标，**不重新判断括号区间**。

> 存储数据格式见 [../data_model/storage.md §3.6](../data_model/storage.md)。
> 测试乐谱：`test-scores/19_dynamics.mscore.yaml`（力度）、`test-scores/21_clef_time_8va.mscore.yaml`（8va/8vb）。
