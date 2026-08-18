# 统一布局系统 (Unified Layout)

> 入口：`renderer/.../layout/ScoreLayoutEntry.kt`
> 主编排：`UnifiedLayoutComputer.kt`（`context(BravuraFont)`）

## 1. 入口 API

```kotlin
context(bravuraFont) {
    val layout = ScoreLayoutEntry.computeLayoutWithComputed(
        runtimeScore = score,
        pageWidth = StaffSpace(100f),
    )
}
```

返回 `UnifiedLayoutResult`，作为渲染、命中索引、命令生成的共同输入。

## 2. UnifiedLayoutResult
 
| 字段 | 含义 |
|------|------|
| `timeSlotMap` | `TimeCode → UnifiedTimeSlot(x, ...)`，每个时间码的右端 X |
| `voiceEventLayouts` | `EventId → VoiceEventLayout`：该事件相对所在时间槽的几何 |
| `barlineLayouts` | 小节线槽位与几何 |
| `staffLayouts` | 各谱表的 Y 范围与中线 |
| `annotationElementLayouts` | `PluginStaffId → List<PlacedAnnotationElement>`，插件点注释或时值范围注释的最终位置 |
| `headerOriginX` | 谱表头（括号 + 标签）的左边缘 X |
| `systemStartX` | 谱线开始的 X（= `headerOriginX + headerWidth`） |

谱表头（括号 + 标签）的 `PlacedLabel` / `PlacedBracket` 不在顶层，而是**逐系统**存于 `SystemLayout.headerLabels / headerBrackets`，按各系统已偏移的谱表 Y 计算 → 每行行首重复出现且与本行谱表对齐。

`StaffLayoutInfo.kind: StaffKind` 区分 `NOTATION` / `ANNOTATION`，默认 `NOTATION` 不影响现有调用点。

## 2.5 谱表头布局（StaffHeaderLayoutComputer）

`StaffHeaderLayoutComputer` 分两阶段运行，作为 `UnifiedLayoutComputer` 的子组件：

**阶段 A — 宽度预估**（在谱表 Y 坐标确定之前）

遍历 `ComputedStaffHeader.brackets + labels`，按“乐器/组名 → 逐谱表演奏者编号 → 括号”
叠加列宽，得到 `headerWidth`。正式谱面按内层→外层从左到右排列，因此最外层大分组
最靠近谱表；演奏者编号位于所有括号左侧并紧邻乐器名。`systemStartX = headerOriginX + headerWidth`，
用作所有谱线和小节线的起点。

**阶段 B — 定位**（谱表 Y 坐标确定之后，**逐系统**进行）

分行 / 分页完成后，对每个 `SystemLayout` 用其**已偏移**的记谱谱表（`staffLayouts` 中的 NOTATION）调用一次 `compute`：
- 标签：`centerY = (firstStaff.centerY + lastStaff.centerY) / 2`
- 括号：`topY = firstStaff.topY − overhang`，`bottomY = lastStaff.bottomY + overhang`

结果写入该系统的 `SystemLayout.headerLabels / headerBrackets`。连续模式下只有一个 `yOffset = 0` 的系统，等价旧行为。

**渲染**（`StructuralElementRenderer` 委托 `SystemRenderer`）：
- `renderHeaderLabel` → `DrawText`，右对齐到列右边缘
- `renderHeaderBracket` — 按 `BracketStyle` 分发：
  - `SQUARE`：粗垂直线 + 上下水平衬线
  - `BRACE`：按实际跨度选择 SMuFL `braceSmall` / `brace` / `braceLarge` / `braceLarger` / `braceFlat`，再以相同的 X/Y 比例缩放到谱表组高度
  - `SUB_BRACKET`：细垂直线 + 固定长度短端点；不得把单个 SMuFL cap 字形按完整组高缩放，否则端点会随大型编制异常放大

**分段小节线**：渲染端的 time-slot pass 为每个 `BarlineElement` 查找 `ComputedBarline.connectedStaffRanges`，为每段调用 `SystemRenderer.renderSystemBarline`（垂直线从 `topY` 到 `bottomY`）。

## 3. 计算流程

```
1. EventCollector
   - 从 ComputedScore 抽取所有需要排版的事件
   - 解析每个 VoiceEvent 的符干方向（StemDirectionResolver）

2. UnifiedTimeSlot 构建
   - 按 TimeCode 分组事件，建立时间槽（装饰音与主音各占独立槽）
   - `MultiVoiceSlotCollisionResolver` 在每个槽内按 staff 求解同时出现的多声部：
     先确定最小声部列偏移，再把临时记号统一排到该 staff 最左符头之前

3. ProportionalLayoutComputer（含装饰音预处理 + 注释间距参与者）
   - 装饰音（`onset.grace != null`）不进入比例算法；
     主音若带装饰音组，先计算该组所需宽度并注入 `NoteElement.extraLeftOverhang`，
     使比例算法自动为装饰音预留空间。
   - **注释轨道参与水平求解**：比例 pass 前先由 `UnifiedHorizontalSlotComputer.buildAnnotationSpacingParticipants`
     取各 annotation provider 的元素、测宽，生成 `AnnotationSpacingParticipant(time, trackId, labelWidth+gap)`
     注入本小节求解；它们是宽度为 0、只对**右侧**预留 `labelWidth+gap` 的注释语音（`VoiceId.Annotation`）。
     **仅当相邻两枚记号过近**时才把音符顶开、避免记号相叠；单独一枚记号不挤压其后的普通音符（见 §5）。
   - 按声部独立追踪间距，按时值线性插值
   - 跨声部最大值合并 → 时间槽 X 坐标
   - 主音 X 确定后，再用固定间距把装饰音槽倒序摆放至主音左侧

4. StaffLayoutComputer
   - 计算谱表 Y 中线、间距（`stackStaves` 纯堆叠原语）
   - 连续模式全局算一套；分页模式仅作为 flat 回退 / 注释基线，逐行 Y 在 `SystemBreaker` 内按 `extentsByMeasureStaff` 缓存逐行堆叠（见 §7）

5. VoiceEventLayoutBuilder
   - 为每个事件生成 NoteheadLayout、StemLayout、BeamLayoutInfo
   - 几何坐标存为相对值（见下节）

6. AnnotationStaffLayoutComputer
   - 拉取 PluginRegistry.annotationStaffProviders()
   - 对每个 provider 调 layout(ctx) 得到 `AnnotationElement.Text` / `AnnotationElement.Range` 列表
   - 用 `AnnotationElementMeasurer` 逐元素测量实际 bounds，并缓存本次布局中重复出现的文字尺寸
   - **横向空间已在 §3 / §5 的比例求解中上游预留**（音符已为记号让位）；本 pass 只负责 Y 分带 + 把标记锚定到音符左边缘。按 `(system, trackId)` 的轨道内最小间隙右移仅作安全网（音符已让位后通常不再触发）
   - 按 anchor 在已布好的 notation staves 之下/之上追加 StaffLayoutInfo(kind = ANNOTATION)
   - 写入 annotationElementLayouts，供 AnnotationStaffRenderer 消费
```

## 4. 双层坐标：负 `relativeX`

```
TimeSlot(x)  ─┐                   x = 时间槽的"右端"
              │  ┌──────────────┐
              │  │  ♪  ♪  ♬     │  ← VoiceEventLayout
              │  └──────────────┘
              │  ↑──── relativeX (负值) ────↑
              │
              └ slot.x = 所有事件最右端的对齐基准
```

每个 `voiceEvent.relativeX` 是从槽右端往左偏移的负数，元素实际起点 = `slot.x + relativeX`。

**为什么右对齐？**
- 多声部、多 staff 在同一 `TimeCode` 上对齐时，"音符头"的右边缘是稳定参考（左边缘会因和弦展开、临时记号宽度而变）
- 比例间距以右端为锚，避免和弦右侧的临时记号挤到下一槽

## 5. 比例布局算法

`ProportionalLayoutComputer` 基本规则：

- **逐小节处理**：以小节为单位独立分配宽度，避免长小节挤压短小节
- **声部独立追踪**：每个声部按时值累计槽间距
- **比例插值**：基础间距 ∝ 时值的对数（参考 Gourlay）
- **跨声部合并**：多个声部对同一 `TimeCode` 的间距取最大值
- **碰撞检测**：当左右两个槽的元素水平相撞，加入额外补偿
- **装饰音排除**：装饰音时间槽（`grace != null`）不参与比例计算；主音通过 `NoteElement.extraLeftOverhang` 向算法声明所需左侧空间（见 [grace-notes.md §4](grace-notes.md)）
- **注释右侧预留（trailing，仅记号间）**：注释间距参与者（和弦记号，左对齐、向右延展）作为 `VoiceId.Annotation` 语音加入求解，`VoiceState.lastTrailing` 记录其右侧预留 `labelWidth+gap`。`annotationTrailingFloor(time)` **只在该时刻正好有下一枚记号**时，才把该槽左端下界抬到 `上一记号右端 + labelWidth + gap`——即只有**相邻两枚记号**过近才把音符拉开；单独一枚记号对其后的普通音符不预留（记号在自己的带上自由 overhang）。跨小节由 `annotationCarry`（`AnnotationCarry(lastX, trailing)`）把上一小节末记号带入下一小节求解 seed，保证跨小节线的相邻记号也不相叠。判据只保证「记号不越过下一记号锚点」（宽度变化像临时记号一样可能在分页模式触发 reflow）

### 5.1 同槽多声部避让

`MultiVoiceSlotCollisionResolver` 是 `UnifiedHorizontalSlotComputer` 的子模块；它在
`assignRelativeXWithinSlot` 内运行，不在比例排版完成后扫描整小节。处理单位是
`TimeCode × staff`，结果直接写入各 `NoteElement.relativeX`，并用
`multiVoiceWidthExtension` 把展开后的簇宽反馈给比例间距。

- 符头 / 附点用 Bravura 实际 bounds 建立横向差分约束。声部按
  「下行符杆 → 较低音域 → 上行符杆 → 较高音域」形成稳定的左右次序；对该有向无环约束图
  求最长路，得到满足全部碰撞约束的最小总展开宽度，三声部以上也一次联合求解。
- 两组符头即使 bounds 没有直接相交，只要音域发生交错且不能合并，也视为结构性碰撞。例如
  B4–C5 二度和弦被 F4–F5 包围时，重合列会让两根符杆难以分辨。相反符杆按符头宽度的
  固定小比例做 mesh 仍可能让二度和弦的符杆几乎重合，因此直接比较两组符头计算出的符杆
  attachment X，并保证不同声部的符杆列至少相隔一个符头宽度，形成明确的双列关系。
- 两声部仅在以下条件全部满足时允许共同符头保持同列：符杆相反、符头类型相同、两声部音域
  交集内没有任一声部独有的音、共同音显示的临时记号逐音一致。音域交集外的独有音不阻止
  边界共同音合并。
- 最终仍在同列的共同音只保留一份相同临时记号。其余临时记号不随声部列机械平移，而是在
  staff 内重新贪心分列、统一放到最左符头之前；这样右移声部的升降号不会穿入左侧符头。
- 多声部簇的零偏移符头列始终保留在全谱共享的时间锚点上；`clusterLeft/clusterRight` 只计算
  占用宽度，不得用包含临时记号的左侧 ink bounds 重定义局部坐标原点。需要避让的其他声部
  才叠加正向列偏移，因此同一 `TimeCode` 上未避让的符头可继续跨 staff 对齐。
- 谱表间、不同 TimeCode 间不参与本模块；连续时间间距仍完全由比例排版负责。谱表内休止符
  也不做横移，后续如需多声部休止避让应采用独立的纵向规则。

## 6. 与 ComputedScore 的边界

布局只读取 `ComputedScore` 中**已经决定**的内容：

- `computedEvents` → 音符布局
- `barlines / clefs / keySignatures / timeSignatures` → 标注事件布局
- `BeamInfo` → 符杠斜率与位置

布局**不会**：
- 决定何时换谱号、何时绘制临时记号
- 计算符杠分组（这是 Computed 层职责）
- 推导小节边界

详见根 `AGENTS.md` 中"Renderer 与 Computed 层职责划分"。

## 7. 分行 / 分页（System Break & Pagination）

`UnifiedLayoutComputer` 通过 `SystemBreakContent`（时间槽、边界、谱表与纵向范围）
和 `SystemBreakPage`（页面几何、强制断点、标题/注释占位）调用 `SystemBreaker`。
全量与增量断行复用这两组输入；增量请求只额外携带缓存、受影响窗口与附件垂直延迟标志。
系统组装和附件纵向折叠也复用同一页面策略，避免多条路径分别维护十余个平行参数。

默认仍为**连续单行**（`PageGeometry.continuous`，等价旧行为）。开启分页后由
`SystemBreaker`（`layout/SystemBreaker.kt`，`context(BravuraFont)`）在比例 X 计算之后运行：

1. **测量**：由 barline 网格得每小节自然宽度。
2. **贪心分行**：逐小节累加，超过**本行可用宽度**或命中 `forcedSystemBreaks` 即换行；非首行再减去行首谱号 / 调号宽度。可用宽度 = `(leftMargin + lineWidth) − systemStartX`，即谱表右端对齐到页面内容右缘（`leftMargin + lineWidth`），而非简单地从 `systemStartX` 起算满 `lineWidth`（后者会把谱线推出纸张右边）。
3. **逐行纵向堆叠 + 分页**（`SystemVerticalLayoutComputer`，分行决策**之后**）：每行的谱表纵向范围取**本行所含小节**的 per-measure 范围（`preBreakMeasureExtents`，按 staff 取 max）+ 本行附件占位，独立 `stackStaves` → 行高随本行内容变化；按**各行自身行高**累加判溢出，超过 `pageContentHeight` 或命中 `forcedPageBreaks` 即翻页。附件占位在 `applyAttachmentExtents` 中折入并可能重新分页。（旧实现用全局统一行高整体平移，已废弃。）
4. **行内拉伸**：按 slot.x（簇右端）线性映射填满行宽（末行不拉伸）；回写 `slot.x`，并给每个 `UnifiedTimeSlot` 打 `systemIndex`。**左锚**取每行最左簇的右端 `lo`：首行 `lo` 原位钉住（保持行内起始谱号 / 初始小节线与谱线起点对齐，不被拉伸），其余行从 `systemStartX + headerWidth` 起算。
5. **行首头**：非首行按活跃谱号 / 调号重排 `LineStartHeader`，并预留与首行等量的 lead-in（初始小节线宽 + `spaceAfterBarline`），使每行谱号相对谱线起点的偏移一致；下一行起始小节线被抑制（小节边界由上一行右端 `closingBarline` 表示）。
   - **行末警示谱号**：若下一行首小节恰好落有变谱号，本行右端重排一枚小号 `LineEndClef`（警示 / cautionary），并把该变谱号的行内 body clef 记入 `suppressedClefTimes` 由渲染端跳过——变谱号在分行处只画一次（下一行的行首头），不会重复。警示谱号的横向宽度在分行**之后**于对齐阶段预留（`lineParamsFor` 的 `contentEndInset`，内容拉伸止于预留条左侧，警示谱号 + `closingBarline` 占用预留条），不参与贪心分行本身。
6. **行首竖线**：每个系统左端画一条 system-start 竖线（`SystemRenderer.renderSystemStartLine`，跨该系统谱表上下缘）——非小节线的装饰性起始线；首行由行内初始小节线代替，故仅对 `systemIndex > 0` 绘制。
7. **最终叠加层（不占位）**：断行与分页全部完成后，`UnifiedLayoutResult.postLayoutMarkers`
   把已有强制边界映射到上一系统；`PostLayoutMarkerRenderer` 在其余元素之后绘制右上角回车 / 空心书本，
   并按 `ScoreViewPreferences.showMeasureNumbers` 在每行行首绘制可选小节号。若顶层谱表从方括号开始，
   小节号整体放到方括号左侧并保留间隙，避免向右侵入行首谱号区域。
   该入口走统一的 `PostLayoutMarker` + `PostLayoutMarkerPainter` 接口，不进入比例宽度、谱表 extent、
   贪心断行或分页计算。分页增量渲染按 system 复用未变记号，只重建受影响 system；Compose 的
   Picture 缓存不录制这层，因此编辑 / 预览切换可直接显示或隐藏而不使乐谱三层缓存失效。
8. **谱表隐藏折叠 + 虚线记号**：某谱表在某行全程隐藏（`StaffInfo.isFullyHiddenOver(range)`，读
   `RuntimeStaffTrack.hiddenRanges`）时，`SystemBreaker.visibleStaves` 在 `stackStaves` 前把它剔除——
   该行纵向**折叠**，上下谱表靠拢；其位置由 `hiddenStaffMarkers` 生成一个 `HiddenStaffMarker`
   （post-layout marker，与断行记号同层），`HiddenStaffMarkerPainter` 沿行宽在折叠间隙画一条水平虚线，
   连续隐藏谱表合并为一条。间隙 Y 于绘制时由该行**存在**的上下相邻谱表推导（不存 Y，随平移/翻页自适应）。
   整行所有谱表都隐藏时 `visibleStaves` 回退保留原谱表（不折叠），改由桌面视图整行灰显。
   行内部分隐藏不折叠、不生成记号，由桌面视图层灰显并禁止交互。

结果写入 `UnifiedLayoutResult.systems / pages / suppressedBarlineTimes / suppressedClefTimes / postLayoutMarkers`，渲染端
`RenderEngine` 按 `staffForSystem(systemIndex, staffIndex)` 解析每个事件所在系统的 Y。**已知系统里缺该谱表**即
表示它在该行被折叠（整行隐藏）——`staffForSystem` 返回 `null`，各渲染 pass 直接跳过其音符/休止/谱号，**不**回退到扁平
`staffLayouts` 的全局 Y（否则被隐藏谱表的音符会漂到全谱顶端、标题之上）。扁平回退仅用于未知系统（连续单系统兜底）。

**页内局部坐标输出（UI 端排列）**：分页模式下页面在布局里仍上下堆叠（`PageLayout.originY`），
但 `RenderPageBuilder` 会在装配 `RenderResult` 时按每页的全局 Y 带把扁平元素切片，并平移成**页内局部坐标**
（Y 减去该页 `originY`，X 已是页内坐标），写入 `RenderResult.pages: List<RenderPage>`
（`pageIndex / width / height / contentOffsetY / elements`，见 `render/RenderResult.kt`；
平移由 `render/RenderElementTransform.kt` 的 `RenderElement.translatedBy` 完成）。
这样 UI（`RenderedScoreView`）可自由把各页摆成「上下」或「左右」排列（`PageArrangement`，见下）并绘制成独立纸张。
命中测试不重建 per-page 索引——仍走全局 `RenderResult.hitTest()`，UI 用 `contentOffsetY` 把页内点击换算回全局坐标。
排列方向是纯视图状态，存于 `StorageScore.viewPreferences`（renderer 不读取），随文件持久化。

**页面配置**：物理单位（mm）+ 谱表大小 `staffSpaceMm`，存于 `StorageScore.pageLayout`
（`api/.../storage/PageLayoutConfig.kt`），经 `PageGeometry.from(...)` 投影到 staff space。
强制断点存于 `globalTrack`（`StorageSystemBreak` / `StoragePageBreak`）。

**跨行区间元素**：tie / slur 在 `TieLayoutComputer` / `SlurLayoutComputer` 中按端点
`systemIndex` 拆两段；hairpin / 渐强渐弱 / 8va 由 `SystemBreaker.tagAttachments` 委托
`AttachmentSystemTagger`
中按系统裁剪为每行一段。

slur 的碰撞避让通过 `LayoutQuery.voiceLayoutsOnStaffInXRange(systemIndex, staffIndex, ...)`
裁剪、插值并重算命中盒。查询同一行内的候选音符。分页 / 分行后各系统共享同一 X 带，查询必须按 `systemIndex`
隔离；否则下一行 / 下一页同 X 范围的音符会被误认为当前 slur 下方障碍，导致 apex 被推到极大值。

- **端点 X（关键）**：各行被拉进**同一 X 带** `[systemStartX, contentRightX]`，仅靠 Y 区分。
  因此一个跨行 span 的终点虽在更靠后的行，其 X 反而**小于**起点 X。`StaffAttachmentLayoutComputer`
  以「终点槽 X < 起点槽 X」判定跨行，此时**保留终点真实 X**、跳过同行用的「最小宽度」夹取
  （否则会把终点拽回起点旁，导致续行段横扫整行/越出纸张）。
- **续行起点对齐首音符**：span 在「既非起始行、又非其自然起点」的行上，从该行**首个音符** X
  起画（`SystemBreaker.lineFirstNoteX`），而非裸谱线起点，避免压到重排的谱号 / 调号。
- **渐强渐弱（wedge）**：每段端点开口（`startSpread / endSpread`）按**累计可视长度**插值，
  长度逐行累加（每行已按自身比例拉伸），故断点处开口连续、两边各按本行比例延伸——不用统一比例。
- **标签 / 钩**：文字标签只在首段、闭合钩只在末段绘制（`showLabel / showHook`），
  整条跨页 span 读作一个符号而非每页各画一个完整符号。
- **行内堆叠（row packing）按系统分组**：各力度 / hairpin 的垂直行（离谱表多远）在
  `StaffAttachmentLayoutComputer` 内**按 `systemIndex` 分组**后再贪心排行——否则不同行、
  但因两端对齐落在相近 X 的记号（如两行各自末小节的力度）会在共享 X 带里假性重叠、被挤到外层行。
  跨行 span 因横跨两系统（Y 不相交）则整体排除在碰撞之外、钉在最内层行，使其与被偏移避让的
  相邻力度记号同处一行。

beam 不跨小节故几乎不跨行（跨谱表 beam 在同一系统内）。

### 7.1 乐谱标题块（Title Block）

**仅分页模式**下，第一页首系统上方绘制乐谱 meta 信息——标题 / 副标题 / 作者（`ScoreMetadata.title / subtitle / composer`）：

- `TitleBlockComputer`（`layout/TitleBlockComputer.kt`）从 `runtime.metadata` 生成 `TitleBlockLayout`（每行携带 page-0 staff-space 几何：`topY` 顶边、`anchorX` + `TitleAlignment`、字号、粗 / 斜体）。仅当对应字段非空才产出对应行；全空则返回 `null`（不占空间、不绘制）。
- 标题 / 副标题居中于页面内容宽 `[leftMargin, leftMargin + lineWidth]`，作者右对齐到内容右缘。
- `SystemBreaker` 收到 `titleBlockHeight` 后，**仅页 0**把首系统起点从 `topMargin` 下移至 `topMargin + 高度`（翻页时 `withinPageTop` 重置回 `topMargin`，故后续页不预留）。
- `TitleBlockRenderer` 把每行投影为 `DrawText`（`TEXT_ANNOTATION`，字号经 `transformer.toPixels` 随缩放变化，与谱表头标签同源）；元素落在页 0 的 Y 带，`RenderPageBuilder` 自动归入第一页。
- 连续模式不生成标题块（`titleBlock == null`）。生成 meta 文本属排版范畴，与谱表头乐器名（`SystemRenderer.renderHeaderLabel`）同类，不属 Computed 层「乐谱元素」。

### 已知不足 🚧

- **行内均匀性**：贪心累加 + 线性拉伸，未做 LilyPond 式的"先粗排再优化"与弹性间距。
- **增量分页渲染**：分页 splice 只覆盖受保护的布局形状；遇到 `PluginRenderComponent` 叠加或无法证明等价的跨系统变化时回退全量渲染。注释谱表已支持（连续 + 分页，整体重生成，见 [incremental-rendering.md](incremental-rendering.md)）。
- **注释谱表**：连续模式按全局基线排在记号谱上/下方并预留高度；分页模式由 `AnnotationStaffLayoutComputer.computePaginated` 在断行后按 system 重解析坐标（per-system justified X + `systemIndex`）。`Text` 使用实测文字 bounds；`Range` 使用起止 `TimeCode`，跨系统时拆成“本行起点—行尾 / 行首—本行终点”的片段，并在每个相交系统计入垂直 extent。两类元素的实测/声明最小宽度都可作为 annotation spacing participant 参与比例排版；点注释仍按 `trackId` 依次避让，范围框保持音乐边界不被碰撞器平移。分页垂直空间由 `perLineExtentsFn` 经 `SystemBreaker.verticalPass` 并入该行占位，因此范围框不会落入下一系统。

后续演进思路见 [../roadmap.md](../roadmap.md)。
